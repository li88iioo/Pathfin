#!/usr/bin/env python3
"""隔离包真实系统返回验证：原子请求、窗口焦点核对、每请求只注入一次并记录回执。"""
import argparse
import json
import shlex
import subprocess
import time
import uuid
from pathlib import Path

PACKAGE = "dev.pathfin.android.verification"
MARKER = "files/native-back-request.json"
ACK = "files/native-back-ack.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--tls", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    run_id = uuid.uuid4().hex
    remote_log = f"/data/local/tmp/pathfin-native-return-{run_id}.log"
    events: list[dict] = []
    log = ""

    def command(*parts: str, stdin: str | None = None) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(adb + list(parts), capture_output=True, text=True, input=stdin, timeout=12)
        except subprocess.TimeoutExpired:
            return subprocess.CompletedProcess(adb + list(parts), 124, "", "ADB command timed out")

    def require_success(result: subprocess.CompletedProcess, operation: str) -> str:
        if result.returncode != 0:
            raise RuntimeError(f"{operation}失败（exit={result.returncode}）：{result.stderr.strip()[:512]}")
        return result.stdout

    def read_request() -> dict | None:
        result = command("shell", "run-as", PACKAGE, "cat", MARKER)
        if result.returncode != 0:
            # 发布前或 finally 移除后不存在是正常状态，其它 run-as 错误不能当作未就绪。
            if "No such file" in result.stderr or "No such file" in result.stdout:
                return None
            require_success(result, "读取验证包握手")
        try:
            parsed = json.loads(result.stdout)
            return parsed if isinstance(parsed, dict) else None
        except (ValueError, TypeError):
            return None

    def acknowledge(request: dict, event: dict) -> None:
        payload = json.dumps({"run_id": run_id, "id": request["id"],
            "status": "input_sent" if event["returncode"] == 0 else "input_failed",
            "returncode": event["returncode"], "error": event.get("error", "")}, ensure_ascii=False)
        inner = f"cat > {ACK}.tmp && mv {ACK}.tmp {ACK}"
        result = command("shell", f"run-as {PACKAGE} sh -c {shlex.quote(inner)}", stdin=payload)
        event["ack_returncode"] = result.returncode
        if result.returncode != 0:
            event["ack_error"] = result.stderr.strip()[:512]

    def inject_once(request: dict) -> None:
        event = {"id": request["id"], "type": request.get("type"), "attempted": False, "returncode": -1,
            "display_id": request.get("display_id"), "navigation_mode": request.get("navigation_mode"),
            "device_locked": request.get("device_locked"), "window": [request.get(key) for key in ("window_left", "window_top", "width", "height")]}
        # claim 后无论结果如何都不再注入同一请求，防止第二次返回掩盖第一次错误。
        events.append(event)
        try:
            if request.get("version") != 2 or request.get("package") != PACKAGE or request.get("activity") != "dev.pathfin.android.MainActivity":
                raise RuntimeError("请求不是当前验证包的 v2 MainActivity 握手，请重建 verification 测试包")
            if request.get("window_focused") is not True or request.get("focused_for_ms", 0) < 500:
                raise RuntimeError("测试窗口尚未稳定取得系统焦点")
            display = int(request["display_id"])
            left, top, width, height = [int(request[key]) for key in ("window_left", "window_top", "width", "height")]
            if display not in range(0, 1024) or not (0 <= left < 16384 and 0 <= top < 16384 and 0 < width <= 16384 and 0 < height <= 16384):
                raise RuntimeError("请求中的显示器或窗口坐标无效")
            # 只检查真实系统 focus，不把 View.requestFocus 成功误认为 Activity 已是输入目标。
            focused = 0
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                # API 35 的 windows 子命令不一定包含全局 mCurrentFocus；完整 dump 保留该字段。
                snapshot = command("shell", "dumpsys", "window")
                require_success(snapshot, "读取系统窗口焦点")
                lines = [line.strip() for line in snapshot.stdout.splitlines() if "mCurrentFocus=" in line]
                event["focused_windows"] = lines
                is_target = any(f"{PACKAGE}/" in line and "MainActivity" in line for line in lines)
                focused = focused + 1 if is_target else 0
                if focused >= 2:
                    break
                time.sleep(0.1)
            if focused < 2:
                raise RuntimeError("系统焦点不在 verification MainActivity，未发送返回")
            fresh = read_request()
            if fresh is None or fresh.get("run_id") != run_id or fresh.get("id") != request["id"]:
                raise RuntimeError("握手已撤销或属于另一测试，未发送返回")
            if request["type"] == "key":
                parts = ("shell", "input", "-d", str(display), "keyevent", "KEYCODE_BACK")
            elif request["type"] == "gesture":
                if request.get("device_locked") is not False:
                    raise RuntimeError("设备锁屏，真实系统边缘手势尚未验证")
                if request.get("navigation_mode") in (0, 1):
                    raise RuntimeError("当前不是手势导航，未将普通屏幕 swipe 当作系统返回")
                if left != 0:
                    raise RuntimeError("当前窗口未从屏幕左缘开始，不能确认系统边缘手势坐标")
                parts = ("shell", "input", "touchscreen", "-d", str(display), "swipe", "1", str(top + height // 2),
                    str(left + width // 2), str(top + height // 2), "300")
            else:
                raise RuntimeError("未知系统输入请求")
            event["attempted"] = True
            result = command(*parts)
            event["returncode"] = result.returncode
            event["stdout"] = result.stdout.strip()[:512]
            event["stderr"] = result.stderr.strip()[:512]
            if result.returncode != 0:
                event["error"] = f"input exit={result.returncode}: {result.stderr.strip()[:384]}"
        except (RuntimeError, KeyError, TypeError, ValueError) as error:
            event["error"] = str(error)[:512]
        acknowledge(request, event)
        print(json.dumps(event, ensure_ascii=False), flush=True)

    try:
        installed = require_success(command("shell", "pm", "list", "packages", PACKAGE), "查询验证包")
        if f"package:{PACKAGE}\n" not in installed:
            raise RuntimeError("必须先安装独立 verification 包；不运行日常包测试")
        for port in (8097, 8098):
            require_success(command("reverse", f"tcp:{port}", f"tcp:{port}"), "建立回环 fixture reverse")
        require_success(command("shell", "run-as", PACKAGE, "rm", "-f", MARKER, MARKER + ".new", ACK, ACK + ".tmp"), "清理上一轮握手")
        classes = "dev.pathfin.android.PlayerBackNavigationTest"
        if args.tls:
            classes += ",dev.pathfin.android.TlsCloseRegressionTest"
        options = f" -e pathfin.externalInput true -e pathfin.externalInputRunId {run_id}"
        if args.tls:
            options += " -e pathfin.tlsMedia true"
        require_success(command("shell", f"nohup am instrument -w -e class {shlex.quote(classes)}{options} "
            f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner > {remote_log} 2>&1 < /dev/null &"), "启动验证")
        deadline = time.monotonic() + 240
        handled = set()
        while time.monotonic() < deadline:
            request = read_request()
            if request and request.get("run_id") == run_id and isinstance(request.get("id"), str) and request["id"] not in handled:
                handled.add(request["id"])
                inject_once(request)
            result = command("shell", "cat", remote_log)
            if result.returncode == 0:
                log = result.stdout
            elif "No such file" not in result.stderr:
                require_success(result, "读取本轮仪器日志")
            if any(end in log for end in ("OK (", "FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")):
                break
            time.sleep(0.5)
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(log)
        args.output.with_suffix(".inputs.json").write_text(json.dumps({"run_id": run_id, "remote_log": remote_log, "events": events}, ensure_ascii=False, indent=2))
    print(log)
    succeeded = [event for event in events if event["attempted"] and event["returncode"] == 0 and event.get("ack_returncode") == 0]
    print(f"完成 {len(succeeded)} 次已确认系统输入；握手 {len(events)} 次，实际 input 调用 {sum(event['attempted'] for event in events)} 次")
    return 0 if "OK (" in log and len(events) == len(succeeded) == 2 else 1


if __name__ == "__main__":
    raise SystemExit(main())

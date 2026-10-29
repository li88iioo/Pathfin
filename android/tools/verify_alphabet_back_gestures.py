#!/usr/bin/env python3
"""以ADB真实触摸执行隔离字母栏返回测试；不连接UiAutomation或修改系统设置。"""
import argparse
import json
import math
from pathlib import Path
import queue
import re
import subprocess
import threading
import time

TARGET = "dev.pathfin.android.verification"
TEST = "dev.pathfin.android.AlphabetIndexBackNavigationTest"
STAGES = {"browser-cancel", "browser-complete", "detail-complete", "returned-browser-complete"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    log_path = args.output / "instrumentation.log"
    if log_path.exists():
        raise RuntimeError("拒绝覆盖已有验证记录，请指定新目录")
    adb = ["adb", "-s", args.serial]

    def command(*parts):
        return subprocess.run(adb + list(parts), capture_output=True, text=True, check=True, timeout=20).stdout

    for package in (TARGET, TARGET + ".test"):
        if not command("shell", "pm", "path", package).startswith("package:"):
            raise RuntimeError("请先安装当前verification目标和测试APK")
    sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", command("shell", "wm", "size"))
    if not sizes:
        raise RuntimeError("无法读取当前屏幕尺寸")
    width, height = map(int, sizes[-1])
    process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
        "-e", "pathfin.externalEdgeInput", "true", TARGET + ".test/androidx.test.runner.AndroidJUnitRunner"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()

    def read_output():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    active = None
    seen = set()
    completed = []
    failure = None
    deadline = time.monotonic() + 150

    def motion(action, x, y):
        focus = command("shell", "dumpsys", "window")
        if not any("mCurrentFocus=" in line and TARGET + "/" in line for line in focus.splitlines()):
            raise RuntimeError("验证窗口失去焦点，停止注入触摸")
        command("shell", "input", "touchscreen", "motionevent", action, str(round(x)), str(round(y)))

    def move(start, end, y):
        for step in range(1, 7):
            motion("MOVE", start + (end - start) * step / 6, y)

    try:
        with log_path.open("x") as log:
            while True:
                if time.monotonic() > deadline:
                    raise TimeoutError("真实系统手势回归超时")
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    continue
                if line is None:
                    break
                log.write(line)
                log.flush()
                request = re.match(r"INSTRUMENTATION_STATUS: pathfin\.edgeRequest=(.+)", line)
                observed = re.match(r"INSTRUMENTATION_STATUS: pathfin\.edgeObserved=(\S+)", line)
                if request:
                    value = json.loads(request.group(1))
                    stage = value["stage"]
                    if active or stage not in STAGES or stage in seen:
                        raise RuntimeError("收到重复或无效手势阶段")
                    x, y, end = (float(value[k]) for k in ("startX", "y", "finishX"))
                    if not all(map(math.isfinite, (x, y, end))) or not 0 <= end < x < width or not 0 <= y < height:
                        raise RuntimeError("手势坐标超出当前屏幕")
                    if value["cancel"] is not (stage == "browser-cancel"):
                        raise RuntimeError("手势阶段与取消标记不匹配")
                    active = {"stage": stage, "startX": x, "y": y, "finishX": end, "cancel": value["cancel"]}
                    seen.add(stage)
                    motion("DOWN", x, y)
                    move(x, end, y)
                    # 等待设备观察到真实系统进度并验收按住阶段，不能自行提前UP。
                elif observed:
                    if not active or observed.group(1) != active["stage"]:
                        raise RuntimeError("真实系统进度确认与当前手势不匹配")
                    x, y, end = active["startX"], active["y"], active["finishX"]
                    if active["cancel"]:
                        move(end, x, y)
                        time.sleep(.15)  # 边缘停留后抬手，交由SystemUI判定取消。
                    motion("UP", x if active["cancel"] else end, y)
                    completed.append(active)
                    active = None
        process.wait(timeout=10)
    except Exception as error:
        failure = str(error)
    finally:
        if active:
            try:
                motion("CANCEL", active["finishX"], active["y"])
            except Exception:
                pass
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        if failure is not None:
            command("shell", "am", "force-stop", TARGET)
    text = log_path.read_text()
    passed = failure is None and process.returncode == 0 and seen == STAGES and len(completed) == 4 and "OK (2 tests)" in text
    result = {"passed": passed, "target": TARGET, "input": "adb-shell-input-motionevent",
        "systemProgressRequired": True, "completed": completed, "error": failure,
        "note": "命令成功不等于验收通过；设备断言系统进度、零字母查询和实际返回/取消。"}
    (args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())

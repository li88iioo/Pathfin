#!/usr/bin/env python3
"""通过受控 instrumentation 握手导出 verification 合成 UI/播放器的真实全屏 PNG。"""

import argparse
import json
from pathlib import Path
import queue
import re
import shlex
import struct
import subprocess
import threading
import time
import uuid
import zlib

TARGET = "dev.pathfin.android.verification"
RUNNER = TARGET + ".test/androidx.test.runner.AndroidJUnitRunner"
UI_TEST = "dev.pathfin.android.UiPreviewTest#exportRepresentativeScreens"
PLAYER_TEST = "dev.pathfin.android.PlayerControlsFlowTest#exportReferencePlaybackFrames"
ASPECT_TEST = "dev.pathfin.android.PlayerControlsFlowTest#pausedAspectModesHaveRealScreenPixelsAndKeepAssAligned"
UI_FRAMES = (
    "01-servers-light", "02-add-server-light", "03-home-light", "04-libraries-light",
    "05-grid-light", "06-detail-light", "07-settings-light", "08-home-dark",
    "09-detail-dark", "10-series-light", "11-season-light", "12-favorites-light",
    "13-sort-sheet", "14-filter-sheet", "15-player-settings", "16-interaction-settings",
    "17-backdrop-grid", "18-servers-dark", "19-cache-settings", "20-category-sheet",
    "22-detail-more", "23-detail-synopsis", "24-detail-media",
    "25-detail-lower", "26-detail-large-type", "27-episode-sheet", "28-appearance-settings",
    "29-library-settings", "30-library-index", "31-home-scrolled", "32-detail-scrolled",
    "33-image-editor", "34-image-search", "35-image-provider", "36-detail-white-logo",
    "37-detail-white-logo-dark", "38-hidden-filter", "39-hidden-home-scrolled", "40-home-scrolled-dark",
    "41-image-search-end", "42-metadata-scrolled", "43-welcome-light", "44-welcome-dark", "45-welcome-account-light", "46-settings-dark", "47-search-empty", "48-search-results", "49-server-switcher", "50-home-menu", "51-search-dark", "52-favorites", "53-search-hidden-status",
    "54-search-multiselect", "55-search-more", "56-favorites-more",
    "57-grid-scrolled", "58-grid-scrolled-dark", "59-grid-hidden-large-type", "60-search-scrolled",
    "61-servers-scrolled", "62-servers-scrolled-dark",
    "63-person-light", "64-person-dark", "65-person-large-type",
)
PLAYER_FRAMES = (
    "player-landscape-controls", "player-landscape-more", "player-landscape-sleep", "player-landscape-chapters", "player-landscape-information",
    "player-landscape-audio", "player-landscape-subtitles", "player-portrait-controls",
    "player-portrait-more",
)
ASPECT_FRAMES = tuple(f"player-aspect-{orientation}-{mode}" for orientation in ("portrait", "landscape", "ass")
                      for mode in ("fit", "fill", "zoom"))
READY_PREFIX = "INSTRUMENTATION_STATUS: pathfin.screenshotReady="
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def frame_plan(suite, requested):
    if suite in {"player", "aspect"}:
        if requested:
            raise ValueError("播放器导出使用固定帧，不接受 --frames")
        return ASPECT_FRAMES if suite == "aspect" else PLAYER_FRAMES
    selected = tuple(part.strip() for part in (requested or "").split(",") if part.strip())
    if len(selected) != len(set(selected)) or any(name not in UI_FRAMES for name in selected):
        raise ValueError("--frames 只允许不重复的预设合成 UI 帧名")
    return tuple(name for name in UI_FRAMES if not selected or name in selected)


def fixture_origin(value):
    from urllib.parse import urlsplit

    uri = urlsplit(value)
    try:
        port = uri.port
    except ValueError as error:
        raise ValueError("fixture 端口无效") from error
    if (uri.scheme != "http" or uri.hostname not in {"127.0.0.1", "localhost", "::1"}
            or uri.username is not None or uri.password is not None or uri.query or uri.fragment
            or (port is not None and not 1 <= port <= 65535) or any(char.isspace() for char in value)):
        raise ValueError("fixture 仅允许无凭据、无查询参数的 HTTP 回环地址")
    return value.rstrip("/")


def focused_verification_window(dump, expected_uid):
    """按 focused window token 查实际 owner，允许本应用自有 Dialog/Popup 的独立窗口。"""
    focused = set(re.findall(r"mCurrentFocus=Window\{([0-9a-fA-F]+)\b[^\r\n]*\}", dump))
    if len(focused) != 1:
        return None
    token = next(iter(focused))
    blocks = list(re.finditer(r"(?m)^\s*Window #\d+ Window\{([0-9a-fA-F]+)\b[^\r\n]*:\s*$", dump))
    for index, match in enumerate(blocks):
        if match.group(1) != token:
            continue
        end = blocks[index + 1].start() if index + 1 < len(blocks) else len(dump)
        block = dump[match.end():end]
        owner = re.search(r"mOwnerUid=(\d+)\b[^\r\n]*\bpackage=([^\s]+)", block)
        if owner and int(owner.group(1)) == expected_uid and owner.group(2) == TARGET:
            return token
    return None


def screen_sizes(output):
    sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", output)
    if not sizes:
        raise ValueError("无法读取屏幕物理/覆盖尺寸")
    width, height = map(int, sizes[-1])
    if not (0 < width <= 16384 and 0 < height <= 16384):
        raise ValueError("屏幕尺寸无效")
    return {(width, height), (height, width)}


def validate_png(data, expected_sizes):
    """校验完整 PNG 块和 CRC，且尺寸必须等于当前设备屏幕（允许旋转）。"""
    if not 32 <= len(data) <= 64 * 1024 * 1024 or not data.startswith(PNG_SIGNATURE):
        raise ValueError("screencap 未返回有效 PNG")
    offset, dimensions, has_pixels, ended = 8, None, False, False
    while offset + 12 <= len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError("PNG 被截断")
        payload = data[offset + 8:offset + 8 + length]
        checksum = struct.unpack_from(">I", data, offset + 8 + length)[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != checksum:
            raise ValueError("PNG 块校验失败")
        if dimensions is None:
            if kind != b"IHDR" or length != 13:
                raise ValueError("PNG 缺少首个 IHDR")
            dimensions = struct.unpack_from(">II", payload)
            if dimensions not in expected_sizes:
                raise ValueError("PNG 尺寸不等于当前设备屏幕")
        elif kind == b"IHDR":
            raise ValueError("PNG 重复 IHDR")
        if kind == b"IDAT":
            has_pixels = True
        if kind == b"IEND":
            if length != 0 or end != len(data):
                raise ValueError("PNG 结束块无效")
            ended = True
            break
        offset = end
    if not ended or not has_pixels:
        raise ValueError("PNG 未完整结束或没有像素数据")
    return dimensions


def ready_frame(line, run, expected):
    if not line.startswith(READY_PREFIX):
        return None
    value = json.loads(line[len(READY_PREFIX):])
    if (not isinstance(value, dict) or set(value) != {"run", "frame"}
            or value["run"] != run or not isinstance(value["frame"], str)
            or value["frame"] != expected):
        raise ValueError("收到过期、重复或非预设顺序的截图请求")
    return value["frame"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="明确指定已安装 verification APK 的设备")
    parser.add_argument("--suite", choices=("ui", "player", "aspect"), default="ui")
    parser.add_argument("--output", type=Path, required=True, help="新的或空的导出目录")
    parser.add_argument("--frames", help="仅 UI 套件：逗号分隔的预设帧名；省略为全部")
    parser.add_argument("--fixture-url", help="仅 UI 套件：回环 fixture 地址，默认 http://127.0.0.1:8097")
    parser.add_argument("--blur-top-bar", action="store_true", help="UI 截图启用状态栏、标题栏与底部导航模糊")
    parser.add_argument("--timeout", type=int, default=600, help="整个 instrumentation 的秒数上限，默认600")
    args = parser.parse_args()
    try:
        frames = frame_plan(args.suite, args.frames)
        if not 30 <= args.timeout <= 3600:
            raise ValueError("--timeout 必须介于30至3600秒")
        if args.suite != "ui" and args.fixture_url:
            raise ValueError("播放器套件只使用自身固定合成 fixture，不接受 --fixture-url")
        fixture = fixture_origin(args.fixture_url or "http://127.0.0.1:8097")
        if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
            raise ValueError("拒绝覆盖已有文件，请指定新的或空的输出目录")
    except ValueError as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ["adb", "-s", args.serial]

    def command(*parts, timeout=15):
        result = subprocess.run(adb + list(parts), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"ADB 命令失败（退出码 {result.returncode}），未采集或确认下一帧")
        return result.stdout

    for package in (TARGET, TARGET + ".test"):
        if not command("shell", "pm", "path", package).startswith("package:"):
            raise RuntimeError("请先安装 verification 应用和对应测试 APK")
    packages = command("shell", "cmd", "package", "list", "packages", "-U", TARGET)
    uid_match = re.search(rf"(?m)^package:{re.escape(TARGET)}\s+uid:(\d+)\s*$", packages)
    if not uid_match:
        raise RuntimeError("无法确认 verification 包的独立 UID")
    uid = int(uid_match.group(1))
    sizes = screen_sizes(command("shell", "wm", "size"))
    run = uuid.uuid4().hex
    test = {"ui": UI_TEST, "player": PLAYER_TEST, "aspect": ASPECT_TEST}[args.suite]
    invocation = ["shell", "am", "instrument", "-w", "-r", "-e", "class", test,
                  "-e", "pathfin.screenshotRun", run]
    if args.suite == "ui":
        invocation += ["-e", "pathfin.screenshots", "true", "-e", "pathfin.fixtureUrl", fixture,
                       "-e", "pathfin.previewFrames", ",".join(frames), "-e", "pathfin.previewBlurTopBar", str(args.blur_top_bar).lower()]
    elif args.suite == "player":
        invocation += ["-e", "pathfin.playerPreviews", "true"]
    else:
        invocation += ["-e", "pathfin.aspectPreviews", "true"]
    invocation.append(RUNNER)
    process = subprocess.Popen(adb + ["shell", shlex.join(invocation[1:])], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1)
    lines = queue.Queue()

    def read_output():
        try:
            for line in process.stdout:
                lines.put(line)
        finally:
            lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    captured, received, failure = [], [], None
    deadline = time.monotonic() + args.timeout
    log_path = args.output / "instrumentation.log"

    def focused_window():
        return focused_verification_window(command("shell", "dumpsys", "window"), uid)

    def await_own_window():
        expires = min(deadline, time.monotonic() + 10)
        previous, stable_since = None, None
        while time.monotonic() < expires:
            token = focused_window()
            now = time.monotonic()
            if token is None:
                previous, stable_since = None, None
            elif token != previous:
                previous, stable_since = token, now
            elif stable_since is not None and now - stable_since >= .25:
                return token
            time.sleep(.05)
        raise RuntimeError("verification 自有窗口未稳定获焦，拒绝截取其它应用或系统弹窗")

    try:
        with log_path.open("x", encoding="utf-8") as log:
            while True:
                if time.monotonic() > deadline:
                    raise TimeoutError("真实屏幕预览导出超时")
                try:
                    line = lines.get(timeout=.5)
                except queue.Empty:
                    continue
                if line is None:
                    break
                received.append(line)
                log.write(line)
                log.flush()
                expected = frames[len(captured)] if len(captured) < len(frames) else None
                name = ready_frame(line, run, expected)
                if name is None:
                    continue
                token = await_own_window()
                result = subprocess.run(adb + ["exec-out", "screencap", "-p"], capture_output=True, timeout=15)
                if result.returncode:
                    raise RuntimeError(f"真实屏幕截图失败（退出码 {result.returncode}）")
                dimensions = validate_png(result.stdout, sizes)
                if focused_window() != token:
                    raise RuntimeError("截图过程中 verification 窗口失去焦点，丢弃本帧且不确认")
                path = args.output / f"{name}.png"
                with path.open("xb") as output:
                    output.write(result.stdout)
                if args.suite == "aspect":
                    # 只有已验证归属、尺寸和CRC的真实屏幕像素回传给验证包；先完整写入再ACK。
                    directory = f"files/ui-preview/returned/{run}"
                    script = f"mkdir -p {shlex.quote(directory)} && cat > {shlex.quote(directory + '/' + name + '.png')}"
                    returned = subprocess.run(adb + ["shell", "-T", shlex.join(["run-as", TARGET, "sh", "-c", script])],
                                              input=result.stdout, capture_output=True, timeout=15)
                    if returned.returncode:
                        raise RuntimeError("比例截图回传失败，未确认本帧")
                # 设备端已创建专用目录；run-as 只写当前白名单帧的随机 run ACK。
                script = f"printf %s {shlex.quote(run)} > {shlex.quote('files/ui-preview/acks/' + name)}"
                command("shell", shlex.join(["run-as", TARGET, "sh", "-c", script]))
                captured.append({"frame": name, "width": dimensions[0], "height": dimensions[1]})
                print(f"{name}.png {dimensions[0]}x{dimensions[1]}", flush=True)
        process.wait(timeout=10)
    except Exception as error:
        failure = str(error)
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if failure is not None:
            try:
                command("shell", "am", "force-stop", TARGET)
            except Exception:
                pass
    log = "".join(received)
    passed = (failure is None and process.returncode == 0 and tuple(item["frame"] for item in captured) == frames
              and re.search(r"\bOK \(1 test\)", log) is not None
              and "FAILURES!!!" not in log and "INSTRUMENTATION_FAILED" not in log)
    if not passed and failure is None:
        failure = ("预设合成帧未全部完成截图握手" if len(captured) != len(frames)
                   else "instrumentation 未报告单项测试通过，请检查导出日志")
    summary = {"passed": passed, "target": TARGET, "test": test, "capture": "adb-exec-out-screencap",
               "frames": captured, "error": failure,
               "note": "仅 verification 合成帧；每帧验证焦点窗口 owner、PNG 完整性及屏幕尺寸后才确认。"}
    (args.output / "result.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())

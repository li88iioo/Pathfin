#!/usr/bin/env python3
"""同机 release 媒体库对照；仅安装并清理本次 verification 包，不接触日常包。

先启动 fixture_server.py --benchmark-items 240 --api-delay-ms 120 --image-delay-ms 60。
构建 -Ppathfin.verification=true :app:assembleRelease，再将 APK、设备和请求日志传入本工具。
由 library_benchmark/build.py 同签原 release 与最小 bridge，使用公开 ViewStructure 采样。
进入耗时包含 shell 握手和节点采样，是可见内容出现的上界；不冒充首帧精确计时。
逐手势采样：手势前 reset，抬手后 dump，保留原始帧数据和分页请求，避免只统计末尾的帧环形缓冲。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import ipaddress
import json
from pathlib import Path
import queue
import re
import secrets
import shlex
import statistics
import subprocess
import threading
import time
from urllib.parse import urlsplit
from urllib.request import urlopen


PACKAGE = "dev.pathfin.android.verification"
BRIDGE = PACKAGE + ".test"
COMPONENT = BRIDGE + "/dev.pathfin.smoke.LibraryBenchmarkInstrumentation"
ACTION = PACKAGE + ".BENCHMARK_CONTROL"
LIBRARY = "测试电影"


def checked(command, timeout=30):
    result = subprocess.run([str(value) for value in command], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{Path(str(command[0])).name} failed ({result.returncode}): " +
                           result.stdout.decode("utf-8", "replace")[-1500:] + result.stderr.decode("utf-8", "replace")[-1500:])
    return result.stdout.decode("utf-8", "replace")


def uptime_upper_bound(text):
    value = text.split()[0]
    if not re.fullmatch(r"\d+\.\d{1,9}", value):
        raise RuntimeError("无法解析设备 /proc/uptime")
    seconds, fraction = value.split(".")
    # /proc/uptime 按小数位截断；向上取一个精度单位，避免误删刚完成的帧。
    return int(seconds) * 1_000_000_000 + (int(fraction) + 1) * 1_000_000_000 // 10 ** len(fraction)


def frames(text, completed_before_ns):
    samples = []
    seen = set()
    missed = 0
    deadlines = 0
    incomplete = 0
    invalid_future = 0
    for section in text.split("---PROFILEDATA---")[1::2]:
        for row in csv.DictReader(io.StringIO(section.strip())):
            try:
                if int(row["Flags"]) != 0:
                    continue
                start, end = int(row["IntendedVsync"]), int(row["FrameCompleted"])
                if (start, end) in seen:
                    continue
                seen.add((start, end))
                if end <= start:
                    incomplete += 1
                    continue
                if end > completed_before_ns:
                    invalid_future += 1
                    continue
                samples.append((end - start) / 1_000_000)
                deadline = int(row.get("FrameDeadline", "0"))
                if deadline > start:
                    deadlines += 1
                    missed += end > deadline
            except (KeyError, ValueError, TypeError):
                continue
    ordered = sorted(samples)
    def percentile(fraction):
        return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction))] if ordered else None
    total = re.findall(r"^Total frames rendered:\s*(\d+)", text, re.M)
    janky = re.findall(r"^Janky frames:\s*(\d+)", text, re.M)
    return {"frames": len(samples), "sampleScope": "retained gfxinfo frame ring",
            "completedBeforeDeviceUptimeNs": completed_before_ns,
            "ignoredIncompleteFrameCount": incomplete, "invalidFutureFrameCount": invalid_future,
            "windowFrames": sum(map(int, total)) if total else None, "windowJankyFrames": sum(map(int, janky)) if janky else None,
            "medianMs": statistics.median(samples) if samples else None,
            "p95Ms": percentile(.95), "p99Ms": percentile(.99), "maxMs": max(samples) if samples else None,
            "framesWithDeadline": deadlines, "missedDeadlineFrames": missed if deadlines else None,
            "missedDeadlinePercent": missed * 100 / deadlines if deadlines else None}


class Benchmark:
    def __init__(self, args):
        self.args = args
        self.adb = [str(args.adb), "-s", args.serial]
        self.digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.bridge_digest = hashlib.sha256(args.bridge.read_bytes()).hexdigest()
        self.owned = False
        self.bridge_owned = False
        self.bridge_process = None
        self.bridge_number = 0
        self.reverse_owned = False
        self.results = []
        self.report = {"apkSha256": self.digest, "bridgeSha256": self.bridge_digest, "package": PACKAGE, "rounds": self.results,
                       "blur": args.blur,
                       "entryTiming": "public ViewStructure visible-node upper bound, includes shell handshake and sampling",
                       "scrollTiming": "per gesture: reset, 400ms swipe, 180ms settle, framestats dump; no sampling while finger moving"}

    def adb_command(self, *values):
        return checked(self.adb + list(values))

    def shell(self, *values):
        return self.adb_command("shell", shlex.join(str(value) for value in values))

    def installed_hash(self, package=PACKAGE):
        if "package:" + package not in self.shell("pm", "list", "packages", package).splitlines():
            return None
        paths = self.shell("pm", "path", package).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            return None
        values = self.shell("sha256sum", paths[0][8:]).split()
        return values[0] if values else None

    def require_owned(self):
        if not self.owned or self.installed_hash() != self.digest:
            raise RuntimeError("verification APK 已被其它任务替换，拒绝清数据或继续输入")

    def preflight(self):
        manifest = checked([self.args.aapt2, "dump", "xmltree", self.args.apk, "--file", "AndroidManifest.xml"])
        package = re.search(r'^\s+A: package="([^"]+)"', manifest, re.M)
        if not package or package.group(1) != PACKAGE or re.search(r':(?:debuggable|testOnly)\([^)]*\)=(?:true|0xffffffff)', manifest):
            raise RuntimeError("只接受 dev.pathfin.android.verification 且非 debuggable/testOnly 的 release APK")
        bridge = checked([self.args.aapt2, "dump", "xmltree", self.args.bridge, "--file", "AndroidManifest.xml"])
        if not re.search(r'^\s+A: package="' + re.escape(BRIDGE) + '"', bridge, re.M) or not re.search(
                r':targetPackage\([^)]*\)="' + re.escape(PACKAGE) + '"', bridge):
            raise RuntimeError("bridge 必须为 verification.test 且只指向 verification")
        certificates = []
        for apk in (self.args.apk, self.args.bridge):
            result = checked([self.args.aapt2.parent / "apksigner", "verify", "--print-certs", apk])
            certificates.append(set(re.findall(r"(?:Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest: ([a-f0-9]{64})", result)))
        if len(certificates[0]) != 1 or certificates[0] != certificates[1]:
            raise RuntimeError("bridge 与 release APK 必须同签")
        installed = set(self.shell("pm", "list", "packages", PACKAGE).splitlines())
        running = set(self.shell("ps", "-A", "-o", "NAME").splitlines())
        for name in (PACKAGE, PACKAGE + ".test"):
            if "package:" + name in installed:
                raise RuntimeError("verification 包已存在；请结束其它验证并清理其自有包后再运行")
            if name in running:
                raise RuntimeError("已有验证进程，拒绝覆盖安装")
        with urlopen(self.args.fixture_url + "/health", timeout=10) as response:
            fixture = json.load(response)
        if fixture.get("Id") != "pathfin-fixture-server" or fixture.get("BenchmarkItems", 0) < 240:
            raise RuntimeError("服务必须为开启至少 240 项的本机合成 benchmark fixture")
        records = self.request_records()
        if not any(record.get("run_id") == fixture.get("RunId") for record in records):
            raise RuntimeError("请求日志与当前合成服务身份不一致")
        self.fixture = fixture
        self.report["fixture"] = fixture
        self.report["device"] = {"model": self.shell("getprop", "ro.product.model").strip(),
                                 "sdk": self.shell("getprop", "ro.build.version.sdk").strip(),
                                 "screen": self.shell("wm", "size").strip()}
        if urlsplit(self.args.fixture_url).hostname == "127.0.0.1":
            port = urlsplit(self.args.fixture_url).port or 80
            self.reverse_port = "tcp:" + str(port)
            existing = self.adb_command("reverse", "--list")
            if any(self.reverse_port in line.split()[1:2] for line in existing.splitlines()):
                raise RuntimeError("基准 API 端口已有 adb reverse，请用空闲端口避免影响其它任务")
            self.adb_command("reverse", self.reverse_port, self.reverse_port)
            self.reverse_owned = True
        self.adb_command("install", str(self.args.apk))
        self.owned = True
        self.require_owned()
        self.adb_command("install", "-t", str(self.args.bridge))
        self.bridge_owned = True
        if self.installed_hash(BRIDGE) != self.bridge_digest:
            raise RuntimeError("bridge 安装后的 APK hash 不符")

    def start_bridge(self):
        self.stop_bridge()
        self.bridge_number += 1
        self.token = secrets.token_hex(32)
        self.nonce = ""
        self.messages = queue.Queue()
        self.bridge_process = subprocess.Popen(self.adb + ["shell", "am", "instrument", "-w", "-r",
            "-e", "controlToken", self.token, COMPONENT], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        process = self.bridge_process
        messages, number = self.messages, self.bridge_number
        def read():
            with (self.output / f"bridge-{number:02d}.log").open("w", encoding="utf-8") as log:
                for line in process.stdout:
                    log.write(line); log.flush()
                    if "pathfin.benchmark=" in line:
                        try:
                            messages.put(json.loads(line.split("pathfin.benchmark=", 1)[1]))
                        except json.JSONDecodeError:
                            messages.put({"kind": "ERROR", "error": "malformed bridge output"})
            messages.put({"kind": "PROCESS_END"})
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        try:
            message = self.messages.get(timeout=20)
        except queue.Empty:
            raise RuntimeError("bridge 未在 20 秒内发出 READY，详见 bridge 日志") from None
        if message.get("kind") != "READY" or message.get("targetPackage") != PACKAGE or not re.fullmatch(r"[a-f0-9]{32}", message.get("nonce", "")):
            raise RuntimeError("公开窗口 bridge 未形成正确的 READY 握手")
        self.nonce = message["nonce"]

    def control(self, command, request, **extras):
        values = []
        for name, value in extras.items():
            values.extend(("--es", name, value))
        self.shell("am", "broadcast", "--receiver-foreground", "-a", ACTION, "-p", PACKAGE,
                   "--es", "controlToken", self.token, "--es", "nonce", self.nonce,
                   "--es", "requestId", request, "--es", "command", command, *values)

    def stop_bridge(self):
        if self.bridge_process is None:
            return
        try:
            if self.bridge_process.poll() is None:
                if self.nonce:
                    self.control("stop", secrets.token_hex(16))
                else:
                    self.require_owned()
                    self.shell("am", "force-stop", PACKAGE)
                self.bridge_process.wait(timeout=5)
        finally:
            if self.bridge_process.poll() is None:
                self.bridge_process.terminate()
            self.reader.join(timeout=2)
            self.bridge_process = None

    def request_records(self):
        # 末行可能正由服务写入；完整行解析，保留原日志供复核。
        records = []
        for line in self.args.requests_log.read_text(encoding="utf-8").splitlines():
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return records

    def focused(self):
        dump = self.shell("dumpsys", "activity", "activities")
        if not re.search(r'(?:topResumedActivity|mResumedActivity)[^\n]*\b' + re.escape(PACKAGE) + r'/', dump):
            raise RuntimeError("verification 未位于前台；拒绝向其它应用输入")

    def tree(self):
        request = secrets.token_hex(16)
        self.control("snapshot", request)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                snapshot = self.messages.get(timeout=max(.01, deadline - time.monotonic()))
            except queue.Empty:
                raise RuntimeError("公开窗口采样响应超时，详见 bridge 日志") from None
            if snapshot.get("kind") == "PROCESS_END":
                raise RuntimeError("公开窗口 bridge 提前结束")
            if snapshot.get("requestId") != request:
                continue
            if snapshot.get("kind") != "SNAPSHOT" or snapshot.get("targetPackage") != PACKAGE or not snapshot.get("activityAlive"):
                raise RuntimeError("bridge 返回错误或目标归属不符")
            if snapshot.get("focused"):
                return snapshot
            time.sleep(.12)
            self.control("snapshot", request)
        raise RuntimeError("verification 自身窗口没有真实焦点")

    @staticmethod
    def find(tree, label):
        return next((node for node in tree["nodes"] if label in (node.get("text"), node.get("description"), node.get("hint"))), None)

    def wait(self, label=None, *, predicate=None, timeout=25):
        deadline = time.monotonic() + timeout
        last_snapshot = None
        while time.monotonic() < deadline:
            tree = self.tree()
            if tree.get("targetPackage") == PACKAGE:
                last_snapshot = tree
            node = self.find(tree, label) if label else None
            if node is not None or predicate and predicate(tree):
                return tree, node
            time.sleep(.12)
        if last_snapshot is not None:
            diagnostic = {"expectedLabel": label, "predicate": getattr(predicate, "__name__", None),
                          "snapshot": last_snapshot}
            (self.output / "wait-timeout.json").write_text(json.dumps(diagnostic, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        raise RuntimeError("未等到合成界面节点：" + str(label))

    @staticmethod
    def target(tree, node, attribute):
        rows = {row["id"]: row for row in tree["nodes"]}
        while node is not None:
            if node.get(attribute):
                return node
            node = rows.get(node.get("parent"))
        raise RuntimeError("控件没有可操作父节点")

    @staticmethod
    def descendant_control(tree, node, attribute):
        rows = {row["id"]: row for row in tree["nodes"]}
        while node is not None:
            candidates = []
            for row in tree["nodes"]:
                if not row.get(attribute):
                    continue
                parent = row
                while parent is not None:
                    if parent["id"] == node["id"]:
                        candidates.append(row)
                        break
                    parent = rows.get(parent.get("parent"))
            if len(candidates) == 1:
                return candidates[0]
            node = rows.get(node.get("parent"))
        raise RuntimeError("未定位唯一的真实表单控件")

    def tap(self, node):
        self.focused()
        coordinates = node.get("bounds", [])
        if len(coordinates) != 4 or not node.get("enabled"):
            raise RuntimeError("合成控件坐标或启用状态异常")
        left, top, right, bottom = coordinates
        self.shell("input", "tap", (left + right) // 2, (top + bottom) // 2)

    def stable_tree(self, label=None):
        previous, samples, since = None, 0, 0
        def stable(snapshot):
            nonlocal previous, samples, since
            geometry = tuple((tuple(node.get("bounds", [])), node.get("enabled")) for node in snapshot["nodes"])
            now = time.monotonic()
            if geometry == previous:
                samples += 1
            else:
                previous, samples, since = geometry, 1, now
            return samples >= 3 and now - since >= .35 and (label is None or self.find(snapshot, label) is not None)
        # 仅准备阶段使用：等待键盘/切页动画稳定，再以最后一份自身快照定位。
        return self.wait(predicate=stable)[0]

    def click(self, label):
        tree = self.stable_tree(label)
        node = self.find(tree, label)
        self.tap(self.target(tree, node, "clickable"))

    def field(self, label, text):
        tree = self.stable_tree(label)
        node = self.find(tree, label)
        self.tap(self.descendant_control(tree, node, "editable"))
        def focused(snapshot):
            node = self.find(snapshot, label)
            return node is not None and self.descendant_control(snapshot, node, "editable").get("focused")
        self.wait(predicate=focused, timeout=4)
        # 真实 tap 后通过公开 IME 协议一次性替换，不逐字符触发地址解析。
        self.input_text(label, text, commit=True)
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            if self.input_text(label, text, commit=False):
                break
            time.sleep(.1)
        else:
            raise RuntimeError("标准 IME 输入后的完整值回读不匹配：" + label)
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.stable_tree()

    def input_text(self, label, text, *, commit):
        request = secrets.token_hex(16)
        self.control("input" if commit else "verify", request, field=label, value=text)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                response = self.messages.get(timeout=max(.01, deadline - time.monotonic()))
            except queue.Empty:
                raise RuntimeError("公开 IME 输入响应超时，详见 bridge 日志") from None
            if response.get("kind") == "PROCESS_END":
                raise RuntimeError("公开 IME 输入 bridge 提前结束")
            if response.get("requestId") != request:
                continue
            if response.get("kind") != "INPUT" or response.get("accepted") is not True:
                raise RuntimeError("公开 IME 输入被拒绝：" + label)
            return response.get("matchesExpected") is True
        raise RuntimeError("公开 IME 输入响应超时")

    def launch(self):
        for permission in ("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK"):
            self.shell("pm", "grant", PACKAGE, permission)
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.start_bridge()

    def login(self):
        self.require_owned()
        self.stop_bridge()
        self.shell("pm", "clear", PACKAGE)
        self.launch()
        # 整段 IME commit 等同粘贴，由真实表单解析协议、主机、端口和路径。
        self.field("服务器地址", self.args.fixture_url)
        tree = self.stable_tree("允许 HTTP 明文连接与播放")
        label = self.find(tree, "允许 HTTP 明文连接与播放")
        switch = self.descendant_control(tree, label, "checkable")
        if not switch.get("checked"):
            self.tap(switch)
        def http_enabled(snapshot):
            label = self.find(snapshot, "允许 HTTP 明文连接与播放")
            return label is not None and self.descendant_control(snapshot, label, "checkable").get("checked") is True
        self.wait(predicate=http_enabled)
        self.click("连接")
        self.field("用户名", "pathfin")
        self.field("密码", "pathfin")
        self.click("进入媒体库")
        self.wait(LIBRARY)
        if self.args.blur:
            for label in ("更多", "设置", "外观"):
                self.click(label)
            tree = self.stable_tree("毛玻璃效果")
            label = self.find(tree, "毛玻璃效果")
            switch = self.descendant_control(tree, label, "checkable")
            if not switch.get("checked"):
                self.tap(switch)
            def blur_enabled(snapshot):
                label = self.find(snapshot, "毛玻璃效果")
                return label is not None and self.descendant_control(snapshot, label, "checkable").get("checked") is True
            self.wait(predicate=blur_enabled)
            for _ in range(2):
                self.shell("input", "keyevent", "KEYCODE_BACK")
                self.stable_tree()
            self.wait(LIBRARY)

    @staticmethod
    def browser_ready(tree):
        nodes = tree["nodes"]
        movie = any(node.get("text", "").startswith("Benchmark Movie ") for node in nodes)
        home = any(node.get("text") == "媒体库" for node in nodes)
        return movie and not home

    def enter(self):
        tree, node = self.wait(LIBRARY)
        target = self.target(tree, node, "clickable")
        started = time.monotonic_ns()
        self.tap(target)
        visible, _ = self.wait(predicate=self.browser_ready)
        titles = [node.get("text") for node in visible["nodes"] if node.get("text", "").startswith("Benchmark Movie ")]
        return started, (time.monotonic_ns() - started) / 1_000_000, titles

    def back(self):
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.wait(LIBRARY)

    def measure(self, round_index, scenario):
        started, entry_ms, titles = self.enter()
        # 先等首屏的固定图片尾请求稳定；滚动阶段不向 bridge 读取界面节点。
        time.sleep(1)
        self.focused()
        size = re.findall(r"(\d+)x(\d+)", self.report["device"]["screen"])[-1]
        width, height = map(int, size)
        scroll_started = time.monotonic_ns()
        segments = []
        for _ in range(self.args.swipes):
            self.shell("dumpsys", "gfxinfo", PACKAGE, "reset")
            self.shell("input", "swipe", width // 2, int(height * .82), width // 2, int(height * .30), 400)
            time.sleep(.18)
            segments.append(self.shell("dumpsys", "gfxinfo", PACKAGE, "framestats"))
        scroll_finished = time.monotonic_ns()
        # 手势采样完成后才读时钟；boottime 包含休眠时间，是 monotonic 完成时间的保守上界。
        device_uptime = self.shell("cat", "/proc/uptime").strip()
        completed_before_ns = uptime_upper_bound(device_uptime)
        raw = "\n".join(segments)
        self.focused()
        frame_stats = frames(raw, completed_before_ns)
        if not frame_stats["frames"]:
            raise RuntimeError("gfxinfo 没有有效帧；不能报告滚动性能")
        time.sleep(.8)
        finished = time.monotonic_ns()
        records = [row for row in self.request_records() if row.get("run_id") == self.fixture["RunId"] and
                   started <= row.get("monotonic_ns", 0) <= finished]
        pages = [row for row in records if row.get("route") == "/Items" and row.get("parent_is_library") and
                 not row.get("is_detail") and int(row.get("limit") or 0) >= 40]
        images = [row for row in records if row.get("image_bytes", 0)]
        result = {"round": round_index, "scenario": scenario, "entryVisibleUpperBoundMs": entry_ms,
                  "initialVisibleMovies": titles,
                  "scrollDurationMs": (scroll_finished - scroll_started) / 1_000_000, "swipes": self.args.swipes,
                  "deviceUptimeRaw": device_uptime, "gfxinfo": frame_stats,
                  "gestureSegments": [frames(segment, completed_before_ns) for segment in segments],
                  "browserPageRequests": len(pages),
                  "pageStarts": [int(row["start_index"] or 0) for row in pages],
                  "filterRequests": sum(row.get("route") == "/Items/Filters" for row in records),
                  "imageRequests": len(images), "imageBytes": sum(row["image_bytes"] for row in images)}
        stem = f"{round_index:02d}-{scenario}"
        (self.output / (stem + "-gfxinfo.txt")).write_text(raw, encoding="utf-8")
        (self.output / (stem + "-requests.jsonl")).write_text("".join(json.dumps(row) + "\n" for row in records), encoding="utf-8")
        self.results.append(result)
        self.save()
        print(json.dumps(result, ensure_ascii=False), flush=True)

    def save(self):
        (self.output / "summary.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def run(self):
        try:
            self.preflight()
            for index in range(1, self.args.rounds + 1):
                self.login()
                self.measure(index, "first-entry-after-login")
                self.back()
                self.stop_bridge()
                self.shell("am", "force-stop", PACKAGE)
                self.launch()
                self.measure(index, "process-restart")
                self.back()
                self.measure(index, "warm-reentry")
            self.report["passed"] = True
        except Exception as error:
            self.report.update(passed=False, failureKind="benchmark-tool-or-fixture", error=str(error))
            raise
        finally:
            cleanup_errors = []
            try:
                self.stop_bridge()
            except Exception as error:
                cleanup_errors.append(str(error))
            for package, owned, digest in ((BRIDGE, self.bridge_owned, self.bridge_digest), (PACKAGE, self.owned, self.digest)):
                try:
                    if owned:
                        if self.installed_hash(package) == digest:
                            self.adb_command("uninstall", package)
                            self.report.setdefault("cleanup", {})[package] = "own APK uninstalled"
                        else:
                            self.report.setdefault("cleanup", {})[package] = "APK replaced externally; not uninstalled"
                except Exception as error:
                    cleanup_errors.append(str(error))
            try:
                if self.reverse_owned:
                    self.adb_command("reverse", "--remove", self.reverse_port)
            except Exception as error:
                cleanup_errors.append(str(error))
            if cleanup_errors:
                self.report.update(passed=False, cleanupErrors=cleanup_errors)
            self.save()
            if cleanup_errors:
                raise RuntimeError("基准资源清理失败，请检查 summary.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--bridge", type=Path, required=True, help="library_benchmark/build.py 生成的同签公开窗口 bridge")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--fixture-url", required=True)
    parser.add_argument("--requests-log", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--swipes", type=int, default=16)
    parser.add_argument("--blur", action="store_true", help="通过正常设置界面开启毛玻璃效果并校验，用相同视觉设置比较")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", type=Path, default=Path.home() / "Android/Sdk/platform-tools/adb")
    parser.add_argument("--aapt2", type=Path, default=Path.home() / "Android/Sdk/build-tools/37.0.0/aapt2")
    args = parser.parse_args()
    parsed = urlsplit(args.fixture_url)
    try:
        address = ipaddress.ip_address(parsed.hostname or "")
    except ValueError:
        parser.error("fixture-url 必须为本机回环或私网 IPv4 地址")
    if parsed.scheme != "http" or address.version != 4 or not (address.is_private or address.is_loopback) or parsed.query or parsed.fragment or parsed.username:
        parser.error("fixture-url 只允许无查询/片段的私网 HTTP 合成服务")
    if not 1 <= args.rounds <= 10 or not 1 <= args.swipes <= 40:
        parser.error("rounds 范围为 1–10，swipes 范围为 1–40")
    args.fixture_url = args.fixture_url.rstrip("/")
    args.apk = args.apk.resolve(strict=True)
    args.bridge = args.bridge.resolve(strict=True)
    # fixture 在首个 /health 请求时才建立日志；由 preflight 的健康请求后验证归属。
    args.requests_log = args.requests_log.resolve()
    Benchmark(args).run()


if __name__ == "__main__":
    main()

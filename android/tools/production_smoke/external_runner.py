#!/usr/bin/env python3
"""维护者显式执行的真实 ADB 输入 runner；不注册/停止任何无障碍服务，不安装/清数据。"""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import math
from pathlib import Path
import queue
import re
import secrets
import shlex
import subprocess
import threading
import time


TARGET = "dev.pathfin.android"
COMPONENT = "dev.pathfin.android.production.smoke/dev.pathfin.smoke.ProductionSmokeInstrumentation"
ACTION = "dev.pathfin.android.production.smoke.ACK"
BASE = "http://127.0.0.1:8101/jellyfin"
MOVIE = "Pathfin · 合成双音轨与字幕"
EXPECTED_PHASES = ("LOGIN", "OPEN_DETAIL", "PLAY_EXO", "BACK_EXO", "CLEANUP")
COMPLETE_PASS = "PASS actual R8 Exo moving video, real BACK -> detail, one stop per session"
CLEANUP_PASS = "CLEANUP PASS: 本次合成服务器已从真实界面删除"


class EditorNotReady(AssertionError):
    """设备明确在写入前因真实焦点/InputConnection 未就绪而拒绝本次命令。"""


def parse_device_verdict(text, run_id, *, require_raw=False):
    """兼容 am 的格式化 stream 和 -r 原始 Bundle；完整验收链均不可省略。"""
    lines = []
    phases = []
    for raw in text.splitlines():
        line = re.sub(r"^INSTRUMENTATION_(?:STATUS|RESULT): stream=", "", raw).strip()
        lines.append(line)
        if "PF_SMOKE:" in line:
            try:
                message = json.loads(line.split("PF_SMOKE:", 1)[1])
            except json.JSONDecodeError:
                continue
            if isinstance(message, dict) and message.get("kind") == "PHASE":
                phases.append(message)
    reasons = []
    markers = [line[8:] for line in lines if line.startswith("RESULT: ")]
    if markers != ["PASS"]:
        reasons.append("缺少唯一最终 RESULT: PASS，或输出包含失败/多次运行")
    if any(line.startswith(("FAIL ", "CLEANUP FAIL:", "AUTOMATION FAIL:", "INSTRUMENTATION_FAILED:")) for line in lines):
        reasons.append("设备输出含明确失败")
    if COMPLETE_PASS not in lines:
        reasons.append("缺少 ExoPlayer 真实播放/系统返回/单次停止的完整验收")
    if CLEANUP_PASS not in lines:
        reasons.append("缺少真实界面移除本次合成账号的证据")
    keyguards = re.findall(r"^KEYGUARD before=(true|false) after=(true|false) \(never requested unlock\)$", "\n".join(lines), re.MULTILINE)
    if not keyguards or any(before != after for before, after in keyguards):
        reasons.append("缺少或违反锁屏状态保持证据")
    proof_pattern = re.compile(r"^PASS Exo session=(fixture-session-[0-9]+) movingFrameMeanDelta=([0-9]+(?:\.[0-9]+)?) systemBACK=detail start=1 stop=1$")
    proofs = set()
    for line in lines:
        match = proof_pattern.fullmatch(line)
        if match:
            proofs.add(match.groups())
    playback = {}
    if len(proofs) != 1:
        reasons.append("ExoPlayer 缺少唯一真实会话证据")
    else:
        session, delta = next(iter(proofs))
        if not math.isfinite(float(delta)) or float(delta) <= 2.0:
            reasons.append("ExoPlayer 动态像素断言未通过")
        playback = {"session": session, "movingFrameMeanDelta": float(delta)}
    if tuple(message.get("phase") for message in phases) != EXPECTED_PHASES:
        reasons.append("缺少或重复真实外部输入阶段握手")
    nonces = [message.get("nonce", "") for message in phases]
    if any(not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{32}", value) for value in nonces):
        reasons.append("阶段 nonce 无效或复用")
    elif len(set(nonces)) != len(nonces):
        reasons.append("阶段 nonce 无效或复用")
    if any(message.get("displayName") != "Production smoke " + run_id
           or not isinstance(message.get("snapshot"), dict)
           or message["snapshot"].get("targetPackage") != TARGET for message in phases):
        reasons.append("阶段归属不是本次唯一合成正式包")
    bundle = {}
    for field in ("passed", "syntheticAccountRemoved"):
        values = set(re.findall(r"^INSTRUMENTATION_RESULT:\s*" + field + r"=(true|false)$", text, re.MULTILINE))
        if values:
            bundle[field] = sorted(values)
            if values != {"true"}:
                reasons.append("原始 Bundle 明确拒绝 " + field)
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?[0-9]+)$", text, re.MULTILINE)
    if codes and codes != ["-1"]:
        reasons.append("原始 Instrumentation 返回码未成功")
    raw_format = bool(bundle or codes)
    if (require_raw or raw_format) and (set(bundle) != {"passed", "syntheticAccountRemoved"} or codes != ["-1"]):
        reasons.append("原始输出缺少完整成功 Bundle/返回码")
    return {"passed": not reasons, "format": "raw-bundle" if raw_format else "formatted-stream",
            "playback": playback, "phases": [message.get("phase") for message in phases],
            "cleanupConfirmed": CLEANUP_PASS in lines, "keyguardUnchanged": bool(keyguards) and all(a == b for a, b in keyguards),
            "bundleBooleans": bundle, "instrumentationCodes": codes, "reasons": reasons}


def reparse_existing(log_path, original_path, corrected_path):
    """只读既有证据，另写显式更正；绝不覆盖原 runner-result 或伪称重新跑过设备。"""
    log_path = log_path.expanduser().resolve(strict=True)
    original_path = original_path.expanduser().resolve(strict=True)
    corrected_path = corrected_path.expanduser().resolve()
    if corrected_path.exists() or corrected_path in (log_path, original_path):
        raise ValueError("更正结果必须使用尚不存在的独立文件，禁止覆盖历史证据")
    log_body = log_path.read_bytes(); original_body = original_path.read_bytes()
    original = json.loads(original_body)
    run_id = original.get("runId", "")
    if original.get("targetPackage") != TARGET or not re.fullmatch(r"[A-Za-z0-9_-]{8,48}", run_id):
        raise ValueError("历史结果不是固定正式包/合成 smoke")
    evidence = parse_device_verdict(log_body.decode("utf-8"), run_id)
    prior_host_errors = [error for error in original.get("errors", [])
                         if not (error.get("type") == "DeviceSmokeFailure"
                                 and error.get("message") == "设备未形成完整 PASS 与合成账号清理证据")]
    passed = evidence["passed"] and not prior_host_errors
    result = {"targetPackage": TARGET, "runId": run_id, "passed": passed,
              "evaluationKind": "offline-parser-correction-existing-device-output", "freshDeviceRun": False,
              "sourceLog": str(log_path), "sourceLogSha256": hashlib.sha256(log_body).hexdigest(),
              "originalRunnerResult": str(original_path), "originalRunnerResultSha256": hashlib.sha256(original_body).hexdigest(),
              "originalRunnerPassed": original.get("passed"), "originalResultUnchanged": original_path.read_bytes() == original_body,
              "priorHostErrors": prior_host_errors, "deviceEvidence": evidence}
    with corrected_path.open("x", encoding="utf-8") as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if passed else 1


class Runner:
    def __init__(self, args):
        self.args = args
        self.adb = [str(args.adb), "-s", args.serial]
        self.token = secrets.token_hex(32)
        self.messages = queue.Queue()
        self.phase = self.nonce = ""
        self.phase_error = False
        self.errors = []
        self.last_action = None
        self.input_trace = []
        self.video_artifacts = []
        self.lines = []
        self.output = args.output.expanduser().resolve()
        self.output.mkdir(parents=True, exist_ok=True)

    def command(self, *args, binary=False):
        result = subprocess.run(self.adb + list(args), capture_output=True, timeout=20)
        if result.returncode:
            raise RuntimeError("真实 ADB 操作失败（命令/环境不回显）")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, *args):
        return self.command("shell", shlex.join(str(arg) for arg in args))

    def control(self, command, field_label=None):
        arguments = ["am", "broadcast", "--receiver-foreground", "-a", ACTION, "-p", TARGET,
                   "--es", "controlToken", self.token, "--es", "phase", self.phase,
                   "--es", "nonce", self.nonce, "--es", "command", command]
        if field_label is not None:
            arguments += ["--es", "fieldLabel", field_label]
        self.shell(*arguments)

    def input_text(self, label, commit):
        command = "commitText" if commit else "verifyText"
        self.last_action = {"operation": command, "label": label}
        self.control(command, label)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            message = self.messages.get(timeout=max(.01, deadline-time.monotonic()))
            if message.get("kind") == "END" or (message.get("kind") == "PHASE"
                    and (message.get("phase"), message.get("nonce")) != (self.phase, self.nonce)):
                self.messages.put(message)
                raise AssertionError("标准文本输入时设备已结束或切换阶段")
            if (message.get("kind") == "TEXT_INPUT" and message.get("phase") == self.phase
                    and message.get("nonce") == self.nonce and message.get("fieldLabel") == label
                    and message.get("command") == command):
                result = message["input"]
                if not result.get("accepted"):
                    reason = result.get("reason", "未报告原因")
                    error = EditorNotReady if reason in (
                        "IllegalStateException: 正式包窗口没有真实焦点",
                        "IllegalStateException: 指定真实编辑字段未聚焦或被禁用",
                        "IllegalStateException: 真实窗口没有就绪的 InputConnection 编辑器",
                        "IllegalStateException: 真实编辑器没有 InputConnection",
                    ) else AssertionError
                    raise error("真实编辑器拒绝输入：" + label + "；" + reason)
                return result.get("matchesExpected", False)
        raise AssertionError("标准文本输入没有收到当前字段的回执：" + label)

    def snapshot(self):
        self.control("snapshot")
        deadline = time.monotonic() + 8
        last_unfocused = None
        while time.monotonic() < deadline:
            try:
                message = self.messages.get(timeout=max(.01, deadline - time.monotonic()))
            except queue.Empty:
                break
            if message.get("kind") == "END":
                self.messages.put(message)
                raise RuntimeError("设备测试进程在真实输入阶段提前结束")
            if message.get("kind") == "PHASE" and (message.get("phase"), message.get("nonce")) != (self.phase, self.nonce):
                self.messages.put(message)
                raise RuntimeError("设备已切换输入阶段，停止使用旧节点")
            if message.get("kind") == "SNAPSHOT" and message.get("phase") == self.phase and message.get("nonce") == self.nonce:
                snapshot = message["snapshot"]
                if snapshot.get("targetPackage") != TARGET or not snapshot.get("activityAlive"):
                    raise AssertionError("目标正式包已变化或 Activity 已结束，拒绝输入")
                if not snapshot.get("focused"):
                    # 字段切换可能短暂触发 IME/Autofill 窗口；只等待，不向失焦窗口输入。
                    last_unfocused = {"taskId": snapshot.get("taskId"),
                                      "ownWindowStates": snapshot.get("ownWindowStates", [])}
                    time.sleep(.15)
                    if time.monotonic() < deadline:
                        self.control("snapshot")
                    continue
                return snapshot
        if last_unfocused is not None:
            self.last_action = {"operation": "waitTargetFocus", "diagnostic": last_unfocused,
                                "previousAction": self.last_action}
            raise AssertionError("目标正式包在 8 秒内未恢复真实窗口焦点，未执行输入")
        raise AssertionError("没有收到当前阶段自己的 ViewStructure 快照")

    @staticmethod
    def find(snapshot, label, contains=False):
        for node in snapshot["nodes"]:
            values = [node.get(key, "") for key in ("text", "description", "hint")]
            if any(label in value if contains else label == value for value in values):
                return node
        return None

    @staticmethod
    def nearest(snapshot, node, capability):
        rows = {row["id"]: row for row in snapshot["nodes"]}
        for _ in range(8):
            if node is None:
                return None
            if node.get(capability):
                return node
            node = rows.get(node.get("parent"))
        return None

    def wait(self, label, timeout=8, contains=False, scroll=False):
        deadline = time.monotonic() + timeout
        stable = None
        stable_since = next_scroll = time.monotonic() + .4
        scrolls = 0
        sheet_stable = None
        sheet_since = 0
        expanded_owned_sheet = False
        while time.monotonic() < deadline:
            snapshot = self.snapshot()
            node = self.find(snapshot, label, contains)
            if node is not None:
                if not scroll:
                    return snapshot, node
                target = self.nearest(snapshot, node, "clickable")
                bounds = target.get("bounds", []) if target else []
                if target and target.get("enabled") and len(bounds) == 4 and min(bounds[:2]) >= 0 \
                        and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    identity = (snapshot.get("taskId"), target.get("window"), tuple(bounds))
                    settled_cleanup = expanded_owned_sheet and self.phase == "CLEANUP" and label == "从本机移除"
                    if settled_cleanup:
                        # 仅受控展开后的第一次移除定位延后；这是测试同步实验，不宣称 Compose 已空闲。
                        identity += (snapshot.get("focused"), json.dumps([
                            row for row in snapshot["nodes"] if row.get("window") == target.get("window")
                        ], ensure_ascii=False, sort_keys=True))
                    if identity != stable:
                        stable, stable_since = identity, time.monotonic()
                    elif time.monotonic() - stable_since >= (1.0 if settled_cleanup else .25):
                        return snapshot, node
                else:
                    stable = None
                next_scroll = time.monotonic() + .4
            else:
                stable = None
                if scroll and scrolls < 8 and time.monotonic() >= next_scroll and any(
                        row.get("scrollable") and row.get("enabled") for row in snapshot["nodes"]):
                    self.scroll(snapshot)
                    scrolls += 1
                    next_scroll = time.monotonic() + .35
                elif scroll and label == "从本机移除" and not expanded_owned_sheet:
                    sheet = self.owned_cleanup_sheet(snapshot)
                    if sheet != sheet_stable:
                        sheet_stable, sheet_since = sheet, time.monotonic()
                    elif sheet is not None and time.monotonic() - sheet_since >= .25:
                        _, _, bounds, scrim = sheet
                        left, top, right, bottom = bounds
                        x = (left + right) // 2
                        # 半展开 sheet 没有 scrollable；真实手势从本次账号弹层内部向遮罩方向展开。
                        self.shell("input", "swipe", x, top + (bottom-top)*4//5,
                                   x, scrim[1] + (bottom-scrim[1])//3, 280)
                        self.input_trace.append({"operation": "ownedCleanupSwipeCompleted", "phase": self.phase,
                                                 "monotonic": time.monotonic(), "bounds": list(bounds)})
                        expanded_owned_sheet = True
                        next_scroll = time.monotonic() + .4
            time.sleep(.1)
        raise AssertionError("真实节点未出现：" + label)

    def owned_cleanup_sheet(self, snapshot):
        if getattr(self, "phase", None) != "CLEANUP" or snapshot.get("targetPackage") != TARGET \
                or not snapshot.get("focused") or not snapshot.get("activityAlive"):
            return None
        rows = {row["id"]: row for row in snapshot["nodes"]}
        scrims = [row for row in rows.values() if row.get("description") == "Close sheet"
                  and row.get("clickable") and row.get("enabled")]
        if len(scrims) != 1:
            return None
        scrim = scrims[0]
        scrim_bounds = scrim.get("bounds", [])
        if len(scrim_bounds) != 4:
            return None

        def belongs(node, ancestor):
            for _ in range(32):
                if node is None:
                    break
                if node.get("id") == ancestor:
                    return True
                node = rows.get(node.get("parent"))
            return False

        candidates = []
        for row in rows.values():
            bounds = row.get("bounds", [])
            if row.get("id") == scrim["id"] or row.get("parent") != scrim.get("parent") \
                    or row.get("window") != scrim.get("window") or not row.get("enabled") or len(bounds) != 4:
                continue
            if min(bounds[:2]) < 0 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1] \
                    or bounds[0] != scrim_bounds[0] or bounds[2] != scrim_bounds[2] \
                    or abs(bounds[1] - scrim_bounds[3]) > 2 or bounds[1] <= scrim_bounds[1]:
                continue
            descendants = [node for node in rows.values() if belongs(node, row["id"])]
            if all(sum(node.get("text") == label for node in descendants) == 1
                   for label in (self.display_name, BASE)):
                candidates.append((snapshot.get("taskId"), row.get("window"), tuple(bounds), tuple(scrim_bounds)))
        return candidates[0] if len(candidates) == 1 else None

    def tap_node(self, snapshot, node):
        self.last_action = {"operation": "tap", "nodeId": node.get("id"), "parent": node.get("parent"),
                            "enabled": node.get("enabled"), "focusedWindow": snapshot.get("focused"),
                            "bounds": node.get("bounds")}
        if not snapshot.get("focused") or not node.get("enabled"):
            raise AssertionError("拒绝向失焦或禁用控件输入")
        left, top, right, bottom = node["bounds"]
        if right <= left or bottom <= top or min(left, top) < 0:
            raise AssertionError("真实节点坐标无效")
        self.input_trace.append({"operation": "tapRequested", "phase": getattr(self, "phase", None),
                                 "monotonic": time.monotonic(), "bounds": node["bounds"]})
        self.shell("input", "tap", (left + right) // 2, (top + bottom) // 2)
        time.sleep(.18)

    def click(self, label):
        snapshot, node = self.wait(label, scroll=True)
        target = self.nearest(snapshot, node, "clickable")
        if target is None:
            raise AssertionError("没有真实可点击父节点：" + label)
        self.tap_node(snapshot, target)

    def scroll(self, snapshot):
        scrolls = [row for row in snapshot["nodes"] if row.get("scrollable") and row.get("enabled")]
        if not scrolls:
            raise AssertionError("没有真实可滚动控件")
        row = max(scrolls, key=lambda node: (node["bounds"][2]-node["bounds"][0]) * (node["bounds"][3]-node["bounds"][1]))
        left, top, right, bottom = row["bounds"]
        x = (left + right) // 2
        self.shell("input", "swipe", x, top + (bottom-top)*4//5, x, top + (bottom-top)//5, 280)
        time.sleep(.2)

    def focus_field(self, label, deadline, *, tap_before_commit=False):
        stable = None
        stable_since = missing_since = time.monotonic()
        scrolls = 0
        while time.monotonic() < deadline:
            snapshot = self.snapshot()
            rows = {row["id"]: row for row in snapshot["nodes"]}
            fields = {}
            labels = [row for row in rows.values()
                      if label in (row.get("text"), row.get("hint"), row.get("description"))]
            # 与设备 InputConnection 守卫相同：标签必须属于该 editable，不能借用页面里其它输入框。
            for node in labels:
                for _ in range(24):
                    if node is None:
                        break
                    if node.get("editable"):
                        fields[node["id"]] = node
                        break
                    node = rows.get(node.get("parent"))
            node = next(iter(fields.values())) if len(fields) == 1 else None
            bounds = node.get("bounds", []) if node else []
            now = time.monotonic()
            if node and node.get("enabled") and len(bounds) == 4 and min(bounds[:2]) >= 0 \
                    and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                identity = (snapshot.get("taskId"), node.get("window"), tuple(bounds), bool(node.get("focused")))
                missing_since = now
                if identity != stable:
                    stable, stable_since = identity, now
                elif now - stable_since >= .25:
                    if node.get("focused") and not tap_before_commit:
                        return
                    self.tap_node(snapshot, node)
                    tap_before_commit = False
                    stable = None
            else:
                stable = None
                if not labels and now - missing_since >= .4 and scrolls < 9 and any(
                        row.get("scrollable") and row.get("enabled") for row in rows.values()):
                    self.scroll(snapshot)
                    scrolls += 1
                    missing_since = time.monotonic()
            time.sleep(.1)
        self.last_action = {"operation": "waitEditorReady", "label": label, "stableGeometry": stable}
        raise AssertionError("真实输入字段未在期限内获得唯一稳定位置与焦点：" + label)

    def field(self, label, value):
        if value not in (BASE, "pathfin", self.display_name):
            raise AssertionError("拒绝输入非编译范围内的合成地址/账号")
        deadline = time.monotonic() + 12
        committed = False
        while time.monotonic() < deadline:
            self.focus_field(label, deadline, tap_before_commit=not committed)
            try:
                matches = self.input_text(label, commit=not committed)
            except EditorNotReady:
                # 仅明确未写入的焦点拒绝可重取；超时、阶段变化或其它错误仍直接失败。
                time.sleep(.1)
                continue
            if not committed:
                committed = True
            elif matches:
                self.shell("input", "keyevent", "KEYCODE_BACK")
                time.sleep(.2)
                # BACK 只能收起本次编辑的 IME，不能悄悄退回上一连接步骤。
                self.wait("连接" if label == "服务器地址" else "进入媒体库")
                return
            time.sleep(.1)
        raise AssertionError("标准 IME 输入后的完整值回读不匹配：" + label)

    def screenshot(self, label):
        self.snapshot()  # 捕获前再次证明目标仍有真实焦点。
        body = self.command("exec-out", "screencap", "-p", binary=True)
        if not body.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("真实系统截图不是 PNG")
        (self.output / (label + ".png")).write_bytes(body)

    def login(self):
        self.field("服务器地址", BASE)
        for _ in range(9):
            snapshot = self.snapshot()
            label = self.find(snapshot, "允许 HTTP 明文连接与播放")
            if label:
                toggle = self.nearest(snapshot, label, "checkable")
                if toggle is None:
                    raise AssertionError("HTTP 许可不是实际开关")
                if not toggle.get("checked"):
                    self.tap_node(snapshot, toggle)
                break
            self.scroll(snapshot)
        else:
            raise AssertionError("找不到真实 HTTP 许可")
        self.click("连接")
        self.wait("用户名", timeout=20)
        self.field("用户名", "pathfin")
        self.field("密码", "pathfin")
        self.field("在本机显示为", self.display_name)
        self.click("进入媒体库")
        self.wait("切换服务器", timeout=20)

    def is_empty_install(self, snapshot):
        return all(self.find(snapshot, label) for label in ("Pathfin", "添加服务器", "服务器地址", "连接")) \
            and not self.find(snapshot, "切换服务器") and not self.find(snapshot, "返回")

    def cleanup(self):
        for _ in range(9):
            snapshot = self.snapshot()
            if self.is_empty_install(snapshot):
                return
            owned = "服务器操作 " + self.display_name
            if self.find(snapshot, "删除服务器？"):
                if self.find(snapshot, "从本机移除“" + self.display_name + "”的账号与地址", contains=True) is None:
                    raise AssertionError("删除确认不是本次唯一合成服务器")
                self.click("删除")
                self.wait("Pathfin", timeout=12)
                if not self.is_empty_install(self.snapshot()):
                    raise AssertionError("删除后没有回到完整的首次连接页")
                return
            if self.find(snapshot, owned):
                self.click(owned)
                self.click("从本机移除")
                # sheet 退场后才显示确认框；期间不能 BACK，否则会取消确认并重入账号菜单。
                self.wait("删除服务器？", timeout=8)
                continue
            if self.find(snapshot, "连接") and self.find(snapshot, "取消"):
                self.click("取消")
                continue
            self.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(.35)
        raise AssertionError("无法经真实界面清理本次合成账号；需维护者手动处理")

    def perform_phase(self, message):
        self.phase, self.nonce = message["phase"], message["nonce"]
        self.display_name = message["displayName"]
        if self.display_name != "Production smoke " + self.args.run_id:
            raise AssertionError("阶段唯一合成名称不匹配")
        print("真实外部输入：" + self.phase, flush=True)
        if self.phase == "LOGIN":
            self.screenshot("01-empty")
            self.login()
        elif self.phase == "OPEN_DETAIL":
            self.click(MOVIE)
            self.wait("播放选项")
            self.screenshot("02-detail")
        elif self.phase == "PLAY_EXO":
            self.click("播放选项")
            self.wait("从头播放")
            self.click("从头播放")
            self.wait("画中画", timeout=12)
            self.screenshot(self.phase.lower() + "-player-before-ack")
        elif self.phase == "BACK_EXO":
            self.screenshot(self.phase.lower() + "-playing")
            self.shell("input", "keyevent", "KEYCODE_BACK")
            self.wait("播放选项")
            self.wait(MOVIE)
            self.screenshot(self.phase.lower() + "-detail")
        elif self.phase == "CLEANUP":
            self.cleanup()
            self.screenshot("99-empty-after-cleanup")
        else:
            raise AssertionError("未知外部输入阶段")
        self.control("ack")

    def save_video_frame(self, message):
        player = message.get("player")
        if (player != "Exo" or message.get("width") != 320 or message.get("height") != 180
                or not isinstance(message.get("pngBase64"), str) or len(message["pngBase64"]) > 350000):
            raise ValueError("设备失败帧超出固定合成诊断范围")
        body = base64.b64decode(message["pngBase64"], validate=True)
        if (len(body) > 256 * 1024 or not body.startswith(b"\x89PNG\r\n\x1a\n") or len(body) < 24
                or int.from_bytes(body[16:20], "big") != 320 or int.from_bytes(body[20:24], "big") != 180):
            raise ValueError("设备失败帧不是固定尺寸的真实 PNG")
        path = self.output / ("video-frame-failure-" + player.lower() + ".png")
        if any(row["player"] == player for row in self.video_artifacts):
            raise ValueError("同一播放器不能重复导出失败帧")
        path.write_bytes(body)
        self.video_artifacts.append({"player": player, "stage": message.get("stage"),
                                     "path": str(path), "bytes": len(body)})

    def run(self):
        # -r 保留 Bundle 布尔值/结束码；默认的格式化 stream 会隐藏这些字段。
        instrumentation = ["am", "instrument", "-w", "-r", "-e", "acknowledgeFreshProductionInstall", "true",
                           "-e", "runId", self.args.run_id, "-e", "externalInput", "true",
                           "-e", "controlToken", self.token, COMPONENT]
        process = subprocess.Popen(self.adb + ["shell", shlex.join(instrumentation)], stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1)
        def read():
            with (self.output / "instrumentation.log").open("w", encoding="utf-8") as log:
                for line in process.stdout:
                    log.write(line); log.flush(); self.lines.append(line)
                    if "PF_SMOKE:" in line:
                        try:
                            message = json.loads(line.split("PF_SMOKE:", 1)[1])
                            if message.get("kind") == "VIDEO_FRAME":
                                try:
                                    self.save_video_frame(message)
                                except (ValueError, binascii.Error, OSError) as error:
                                    self.phase_error = True
                                    self.errors.append({"phase": self.phase, "type": "DiagnosticFrameError", "message": str(error)})
                            else:
                                self.messages.put(message)
                        except json.JSONDecodeError:
                            pass
                self.messages.put({"kind": "END"})
        threading.Thread(target=read, daemon=True).start()
        done = set()
        deadline = time.monotonic() + 420
        try:
            while time.monotonic() < deadline:
                message = self.messages.get(timeout=max(.01, deadline-time.monotonic()))
                if message.get("kind") == "END":
                    break
                identity = (message.get("phase"), message.get("nonce"))
                if message.get("kind") != "PHASE" or identity in done:
                    continue
                done.add(identity)
                try:
                    self.perform_phase(message)
                except Exception as error:
                    self.phase_error = True
                    self.errors.append({"phase": self.phase, "type": type(error).__name__,
                                        "message": str(error), "action": self.last_action})
                    print("外部真实输入失败：" + str(error), flush=True)
                    try:
                        self.control("fail")
                    except (OSError, RuntimeError, subprocess.TimeoutExpired):
                        print("失败握手未送达，设备日志仍是最终依据。", flush=True)
            process.wait(timeout=10)
        except (queue.Empty, subprocess.TimeoutExpired, OSError) as error:
            self.phase_error = True
            self.errors.append({"phase": self.phase, "type": type(error).__name__,
                                "message": str(error) or "外部 runner 或设备操作超时", "action": self.last_action})
            print("外部 runner 或设备操作超时；未形成完整验收。", flush=True)
        finally:
            if process.poll() is None:
                process.terminate()
                print("Runner 已结束自身 ADB 客户端；设备 instrumentation 可能仍在运行，维护者需检查本次新装正式包。", flush=True)
        text = "".join(self.lines)
        evidence = parse_device_verdict(text, self.args.run_id, require_raw=True)
        success = not self.phase_error and process.returncode == 0 and evidence["passed"]
        if not success and not self.errors:
            failure = "; ".join(evidence["reasons"]) or "ADB instrumentation 进程未正常结束"
            self.errors.append({"phase": "DEVICE", "type": "DeviceSmokeFailure", "message": failure})
        result = {"targetPackage": TARGET, "runId": self.args.run_id, "passed": success,
                  "mode": "external-real-adb-input", "output": str(self.output),
                  "textInput": "focused-editor-public-InputConnection",
                  "videoArtifacts": self.video_artifacts,
                  "inputTrace": self.input_trace,
                  "deviceEvidence": evidence, "adbExitCode": process.returncode,
                  "errors": self.errors, "automationServiceRegistered": False, "dataCleared": False}
        (self.output / "runner-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if success else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="明确设备序列号，禁止自动选设备")
    parser.add_argument("--adb", type=Path, default=Path("adb"))
    parser.add_argument("--run-id", default="production_smoke_"+secrets.token_hex(6))
    parser.add_argument("--acknowledge-fresh-install", action="store_true")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[3]/".superpowers/tmp/production-smoke-external")
    parser.add_argument("--reparse-log", type=Path, help="仅离线重解析原始输出，不执行 ADB 或重新跑设备")
    parser.add_argument("--original-result", type=Path, help="原 runner-result.json，默认位于原日志旁")
    parser.add_argument("--corrected-verdict", type=Path, help="另存更正判定，必须使用尚不存在的文件")
    args = parser.parse_args()
    if args.reparse_log is not None:
        if args.serial or args.acknowledge_fresh_install:
            parser.error("离线重解析不能同时指定设备操作参数")
        if args.corrected_verdict is None:
            parser.error("离线重解析必须指定 --corrected-verdict，禁止覆盖原判定")
        try:
            return reparse_existing(args.reparse_log,
                                    args.original_result or args.reparse_log.parent / "runner-result.json",
                                    args.corrected_verdict)
        except (OSError, ValueError, TypeError) as error:
            parser.error(str(error))
    if args.original_result is not None or args.corrected_verdict is not None:
        parser.error("历史结果参数只能与 --reparse-log 一起使用")
    if not args.acknowledge_fresh_install:
        parser.error("设备运行必须指定 --acknowledge-fresh-install")
    if not re.fullmatch(r"[A-Za-z0-9_-]{8,48}", args.run_id):
        parser.error("run-id 必须是 8..48 位 ASCII 安全字符")
    if not args.serial or args.serial.startswith("-"):
        parser.error("serial 无效")
    return Runner(args).run()


if __name__ == "__main__":
    raise SystemExit(main())

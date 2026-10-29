"""合成节点验证当前入口、唯一账号清理和版本门禁；不运行设备命令。"""
import importlib.util
import hashlib
from pathlib import Path
import queue
import tempfile
import unittest
from unittest import mock
import zipfile


DIRECTORY = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runner = load("production_smoke_navigation_runner", "external_runner.py")
build = load("production_smoke_navigation_build", "build.py")
DISPLAY_NAME = "Production smoke navigation_test"


def snapshot(*labels):
    return {"focused": True, "targetPackage": runner.TARGET,
            "nodes": [{"text": label, "enabled": True} for label in labels]}


def editor_snapshot(label, *, left=100, focused=False, enabled=True, duplicate=False):
    state = {"focused": True, "targetPackage": runner.TARGET, "activityAlive": True, "taskId": 7,
             "nodes": [{"id": 0, "parent": -1},
                       {"id": 1, "parent": 0, "window": 0, "bounds": [left, 200, left + 200, 260],
                        "editable": True, "enabled": enabled, "focused": focused},
                       {"id": 2, "parent": 1, "text": label}]}
    if duplicate:
        state["nodes"] += [{"id": 3, "parent": 0, "window": 0, "bounds": [20, 300, 220, 360],
                            "editable": True, "enabled": True, "focused": False},
                           {"id": 4, "parent": 3, "text": label}]
    return state


class Clock:
    def __init__(self):
        self.now = 0.0

    def sleep(self, seconds):
        self.now += seconds


class NavigationContractTest(unittest.TestCase):
    def setUp(self):
        self.subject = runner.Runner.__new__(runner.Runner)
        self.subject.display_name = DISPLAY_NAME
        self.subject.input_trace = []

    def test_click_scrolls_to_the_clipped_home_title_then_waits_for_its_current_bounds(self):
        clock = Clock()
        scrolled_at = None
        taps = []

        def current():
            # 实际540x960首页的根列表 bounds；片名首屏被裁掉但列表可滚动。
            nodes = [{"id": 8, "parent": -1, "window": 0, "bounds": [0, 0, 540, 960],
                      "scrollable": True, "enabled": True}]
            if scrolled_at is not None:
                left = 180 if clock.now - scrolled_at < .3 else 24
                nodes += [{"id": 20, "parent": 8, "window": 0, "bounds": [left, 450, left + 250, 550],
                           "clickable": True, "enabled": True},
                          {"id": 21, "parent": 20, "text": runner.MOVIE}]
            return {"focused": True, "targetPackage": runner.TARGET, "activityAlive": True,
                    "taskId": 7, "nodes": nodes}

        def shell(*args):
            nonlocal scrolled_at
            if args[:2] == ("input", "swipe"):
                self.assertIsNone(scrolled_at)
                scrolled_at = clock.now
            elif args[:2] == ("input", "tap"):
                self.assertEqual((149, 500), args[2:], "滚动后不能点动画中旧坐标")
                taps.append(args)

        with mock.patch.object(self.subject, "snapshot", side_effect=current), \
                mock.patch.object(self.subject, "shell", side_effect=shell), \
                mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
            self.subject.click(runner.MOVIE)
        self.assertIsNotNone(scrolled_at)
        self.assertEqual(1, len(taps))

    def test_cleanup_scrolls_the_owned_sheet_to_its_hidden_remove_action(self):
        self.exercise_owned_sheet_cleanup(confirmation_delay=0)

    def test_cleanup_waits_for_delayed_delete_confirmation_without_back_or_menu_reentry(self):
        self.exercise_owned_sheet_cleanup(confirmation_delay=.7)

    def test_cleanup_expansion_requires_one_second_of_whole_window_stability_before_one_tap(self):
        self.exercise_owned_sheet_cleanup(confirmation_delay=0, require_settle=True)

    def test_cleanup_never_replays_a_tap_when_the_confirmation_does_not_appear(self):
        self.exercise_owned_sheet_cleanup(confirmation_delay=0, ignore_remove_tap=True)

    def test_plain_remove_click_keeps_quarter_second_stability_without_owned_expansion(self):
        for phase in ("CLEANUP", "OPEN_DETAIL"):
            clock = Clock()
            self.subject.phase = phase
            state = {"focused": True, "targetPackage": runner.TARGET, "activityAlive": True,
                     "taskId": 7, "nodes": [{"id": 1, "parent": -1, "window": 0,
                     "bounds": [20, 800, 500, 880], "text": "从本机移除", "clickable": True, "enabled": True}]}
            taps = []
            with self.subTest(phase=phase), mock.patch.object(self.subject, "snapshot", return_value=state), \
                    mock.patch.object(self.subject, "shell", side_effect=lambda *args: taps.append(clock.now)), \
                    mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                    mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
                self.subject.click("从本机移除")
            self.assertEqual(1, len(taps))
            self.assertGreaterEqual(taps[0], .25)
            self.assertLess(taps[0], .6, "非本次受控展开路径不能全局增加一秒等待")

    def exercise_owned_sheet_cleanup(self, confirmation_delay, *, require_settle=False, ignore_remove_tap=False):
        clock = Clock()
        self.subject.phase = "CLEANUP"
        page = "servers"
        revealed = False
        actions = []
        confirmation_at = None
        swipe_ended_at = None

        def current():
            nonlocal page
            if page == "removing" and clock.now >= confirmation_at:
                page = "confirm"
            nodes = []
            if page == "servers":
                nodes = [{"id": 1, "parent": -1, "window": 0, "bounds": [20, 30, 120, 130],
                          "description": "服务器操作 " + DISPLAY_NAME, "enabled": True, "clickable": True}]
            elif page == "sheet":
                # v2 真实半展开弹层：所有节点都不是 scrollable，须先展开当前账号弹层。
                nodes = [{"id": 2, "parent": -1, "window": 0, "bounds": [0, 0, 540, 960]},
                         {"id": 3, "parent": 2, "window": 0, "bounds": [0, 0, 540, 480],
                          "description": "Close sheet", "enabled": True, "clickable": True},
                         {"id": 4, "parent": 2, "window": 0, "bounds": [0, 480, 540, 960], "enabled": True},
                         {"id": 9, "parent": 4, "text": DISPLAY_NAME},
                         {"id": 10, "parent": 4, "text": runner.BASE}]
                if revealed:
                    nodes += [{"id": 20, "parent": 4, "window": 0, "bounds": [20, 800, 500, 880],
                               "text": "从本机移除", "enabled": True, "clickable": True}]
                    # 移除行本身始终不动；其它内容在展开后600ms才结束语义/几何变化。
                    settling = require_settle and clock.now - swipe_ended_at < .6
                    nodes += [{"id": 21, "parent": 4, "window": 0, "bounds": [20, 600 if settling else 610, 500, 640],
                               "text": "显示名称" if settling else "已展开"}]
            elif page == "removing":
                # 实际移除入口先退场 sheet，完成退场动画后才创建确认框。
                nodes = [{"id": 9, "text": DISPLAY_NAME},
                         {"id": 10, "text": runner.BASE}, {"id": 11, "description": "Close sheet"}]
            elif page == "confirm":
                nodes = [{"id": 4, "parent": -1, "text": "删除服务器？"},
                         {"id": 5, "parent": -1, "text": "从本机移除“" + DISPLAY_NAME + "”的账号与地址"},
                         {"id": 6, "parent": -1, "window": 0, "bounds": [300, 500, 400, 600],
                          "text": "删除", "enabled": True, "clickable": True}]
            else:
                nodes = [{"id": i, "text": text} for i, text in enumerate(("Pathfin", "添加服务器", "服务器地址", "连接"))]
            return {"focused": True, "targetPackage": runner.TARGET, "activityAlive": True,
                    "taskId": 7, "nodes": nodes}

        def shell(*args):
            nonlocal page, revealed, confirmation_at, swipe_ended_at
            actions.append(args)
            if args[:2] == ("input", "swipe"):
                self.assertEqual("sheet", page, "只能滚动已确认归本次账号的操作抽屉")
                self.assertEqual((270, 864, 270, 320, 280), args[2:], "展开坐标须从当前弹层和遮罩推导")
                revealed = True
                swipe_ended_at = clock.now
            elif args[:2] == ("input", "tap"):
                if page == "sheet":
                    if require_settle:
                        self.assertGreaterEqual(clock.now - swipe_ended_at, 1.6,
                            "移除行不动也必须等完整窗口最后一次变化后连续稳定一秒")
                    if ignore_remove_tap:
                        return
                expected, next_page = {"servers": ((70, 80), "sheet"), "sheet": ((260, 840), "removing"),
                                       "confirm": ((350, 550), "empty")}[page]
                self.assertEqual(expected, args[2:])
                page = next_page
                if page == "removing":
                    confirmation_at = clock.now + confirmation_delay
            else:
                self.fail("本次已识别账号不应使用额外返回/清数据命令")

        with mock.patch.object(self.subject, "snapshot", side_effect=current), \
                mock.patch.object(self.subject, "shell", side_effect=shell), \
                mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
            if ignore_remove_tap:
                with self.assertRaisesRegex(AssertionError, "真实节点未出现：删除服务器"):
                    self.subject.cleanup()
            else:
                self.subject.cleanup()
        self.assertEqual("sheet" if ignore_remove_tap else "empty", page)
        self.assertEqual(1, sum(action[:2] == ("input", "swipe") for action in actions))
        self.assertEqual(1, sum(action == ("input", "tap", 260, 840) for action in actions),
                         "确认框未出现也不能重放第一次移除 tap")
        events = [event for event in self.subject.input_trace if event["operation"] == "ownedCleanupSwipeCompleted"
                  or (event["operation"] == "tapRequested" and event["bounds"] == [20, 800, 500, 880])]
        self.assertEqual(["ownedCleanupSwipeCompleted", "tapRequested"], [event["operation"] for event in events])
        self.assertGreaterEqual(events[1]["monotonic"] - events[0]["monotonic"], 1)

    def test_missing_remove_never_expands_an_unowned_or_unfocused_modal(self):
        for phase, name, address, focused in (("OPEN_DETAIL", DISPLAY_NAME, runner.BASE, True),
                ("CLEANUP", "另一个账号", runner.BASE, True),
                ("CLEANUP", DISPLAY_NAME, "http://other-server.invalid", True),
                ("CLEANUP", DISPLAY_NAME, runner.BASE, False)):
            clock = Clock()
            self.subject.phase = phase
            state = {"focused": focused, "targetPackage": runner.TARGET, "activityAlive": True,
                     "taskId": 7, "nodes": [
                         {"id": 2, "parent": -1, "window": 0, "bounds": [0, 0, 540, 960]},
                         {"id": 3, "parent": 2, "window": 0, "bounds": [0, 0, 540, 480],
                          "description": "Close sheet", "enabled": True, "clickable": True},
                         {"id": 4, "parent": 2, "window": 0, "bounds": [0, 480, 540, 960], "enabled": True},
                         {"id": 9, "parent": 4, "text": name}, {"id": 10, "parent": 4, "text": address}]}
            with self.subTest(phase=phase, name=name, address=address, focused=focused), \
                    mock.patch.object(self.subject, "snapshot", return_value=state), \
                    mock.patch.object(self.subject, "shell") as shell, \
                    mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                    mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
                with self.assertRaisesRegex(AssertionError, "真实节点未出现"):
                    self.subject.click("从本机移除")
                shell.assert_not_called()
                self.assertLess(clock.now, 8.2)

    def test_cleanup_uses_system_back_and_only_the_current_run_profile(self):
        pages = [snapshot("切换服务器", "更多"),
                 snapshot("服务器操作 " + DISPLAY_NAME, "服务器操作 另一个账号"),
                 snapshot("删除服务器？", "从本机移除“" + DISPLAY_NAME + "”的账号与地址，服务器上的媒体和观看记录会保留。"),
                 snapshot("Pathfin", "添加服务器", "服务器地址", "连接")]
        with mock.patch.object(self.subject, "snapshot", side_effect=pages), \
                mock.patch.object(self.subject, "click") as click, \
                mock.patch.object(self.subject, "wait") as wait, \
                mock.patch.object(self.subject, "shell") as shell, mock.patch.object(runner.time, "sleep"):
            self.subject.cleanup()
        self.assertEqual([mock.call("服务器操作 " + DISPLAY_NAME), mock.call("从本机移除"), mock.call("删除")],
                         click.call_args_list)
        shell.assert_called_once_with("input", "keyevent", "KEYCODE_BACK")
        self.assertEqual([mock.call("删除服务器？", timeout=8), mock.call("Pathfin", timeout=12)], wait.call_args_list)

    def test_cleanup_refuses_another_profiles_delete_confirmation(self):
        pages = [snapshot("切换服务器", "更多"), snapshot("服务器操作 " + DISPLAY_NAME),
                 snapshot("删除服务器？", "从本机移除“另一个账号”的账号与地址，服务器上的媒体和观看记录会保留。")]
        with mock.patch.object(self.subject, "snapshot", side_effect=pages), \
                mock.patch.object(self.subject, "click") as click, \
                mock.patch.object(self.subject, "wait") as wait, \
                mock.patch.object(self.subject, "shell") as shell, mock.patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(AssertionError, "删除确认不是本次唯一合成服务器"):
                self.subject.cleanup()
        self.assertEqual([mock.call("服务器操作 " + DISPLAY_NAME), mock.call("从本机移除")], click.call_args_list)
        shell.assert_called_once_with("input", "keyevent", "KEYCODE_BACK")
        wait.assert_called_once_with("删除服务器？", timeout=8)

    def test_missing_owned_profile_never_selects_another_account(self):
        with mock.patch.object(self.subject, "snapshot", return_value=snapshot("服务器操作 另一个账号")), \
                mock.patch.object(self.subject, "click") as click, \
                mock.patch.object(self.subject, "shell") as shell, mock.patch.object(runner.time, "sleep"):
            with self.assertRaisesRegex(AssertionError, "无法经真实界面清理本次合成账号"):
                self.subject.cleanup()
        click.assert_not_called()
        self.assertEqual([mock.call("input", "keyevent", "KEYCODE_BACK")] * 9, shell.call_args_list)

    def test_login_follows_address_then_credentials_and_waits_for_server_switcher(self):
        toggle = {"id": 1, "text": "允许 HTTP 明文连接与播放", "checkable": True, "checked": True}
        with mock.patch.object(self.subject, "snapshot", return_value={"nodes": [toggle]}), \
                mock.patch.object(self.subject, "field") as field, \
                mock.patch.object(self.subject, "click") as click, \
                mock.patch.object(self.subject, "wait") as wait, \
                mock.patch.object(self.subject, "shell", side_effect=AssertionError("不应执行设备命令")):
            self.subject.login()
        self.assertEqual([mock.call("连接"), mock.call("进入媒体库")], click.call_args_list)
        self.assertEqual([mock.call("服务器地址", runner.BASE), mock.call("用户名", "pathfin"),
                          mock.call("密码", "pathfin"), mock.call("在本机显示为", DISPLAY_NAME)], field.call_args_list)
        self.assertEqual([mock.call("用户名", timeout=20), mock.call("切换服务器", timeout=20)], wait.call_args_list)

    def test_field_waits_for_stable_live_bounds_before_tapping_and_reading_back(self):
        clock = Clock()
        focused_at = None
        writes = []
        taps = []

        def current():
            left = 700 if clock.now < .1 else 400 if clock.now < .2 else 100
            return editor_snapshot("用户名", left=left, focused=focused_at is not None)

        def shell(*args):
            nonlocal focused_at
            if args[:2] == ("input", "tap"):
                self.assertEqual((200, 230), args[2:], "不能点击动画中旧页面的坐标")
                taps.append(clock.now)
                focused_at = clock.now

        def input_text(label, commit):
            self.assertIsNotNone(focused_at)
            self.assertGreaterEqual(clock.now - focused_at, .25, "不能只见到一帧焦点就输入")
            writes.append(commit)
            return True

        with mock.patch.object(self.subject, "snapshot", side_effect=current), \
                mock.patch.object(self.subject, "shell", side_effect=shell), \
                mock.patch.object(self.subject, "input_text", side_effect=input_text), \
                mock.patch.object(self.subject, "wait") as wait, \
                mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
            self.subject.field("用户名", "pathfin")
        self.assertEqual(1, len(taps))
        self.assertEqual([True, False], writes, "提交后仍须通过独立 InputConnection 完整回读")
        wait.assert_called_once_with("进入媒体库")

    def test_field_reacquires_focus_after_commit_before_the_independent_readback(self):
        clock = Clock()
        focused = True
        committed_at = None
        refocused_at = None
        commands = []

        def current():
            return editor_snapshot("用户名", focused=focused)

        def shell(*args):
            nonlocal focused, refocused_at
            if args[:2] == ("input", "tap"):
                focused = True
                refocused_at = clock.now

        def input_text(label, commit):
            nonlocal focused, committed_at
            commands.append(commit)
            if commit:
                committed_at = clock.now
                focused = False  # IME/页面更新在已接受的 commit 后短暂撤走焦点。
                return False
            self.assertTrue(focused, "verifyText 前也要确认真实字段焦点")
            self.assertIsNotNone(refocused_at)
            self.assertGreaterEqual(clock.now - refocused_at, .25)
            self.assertGreater(clock.now, committed_at)
            return True

        with mock.patch.object(self.subject, "snapshot", side_effect=current), \
                mock.patch.object(self.subject, "shell", side_effect=shell), \
                mock.patch.object(self.subject, "input_text", side_effect=input_text), \
                mock.patch.object(self.subject, "wait"), \
                mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
            self.subject.field("用户名", "pathfin")
        self.assertEqual([True, False], commands, "重新获取焦点不能重放已经接受的 commit")

    def test_already_focused_field_still_gets_a_real_tap_so_back_only_hides_its_keyboard(self):
        clock = Clock()
        keyboard = False
        actions = []

        def shell(*args):
            nonlocal keyboard
            actions.append(args)
            if args[:2] == ("input", "tap"):
                self.assertFalse(keyboard)
                keyboard = True
            elif args == ("input", "keyevent", "KEYCODE_BACK"):
                self.assertTrue(keyboard, "焦点保留但 IME 已关闭时，BACK 会误退连接步骤")
                keyboard = False

        def input_text(label, commit):
            self.assertTrue(keyboard, "commit 前必须有一次稳定坐标上的真实 tap")
            return True

        with mock.patch.object(self.subject, "snapshot", side_effect=lambda: editor_snapshot("用户名", focused=True)), \
                mock.patch.object(self.subject, "shell", side_effect=shell), \
                mock.patch.object(self.subject, "input_text", side_effect=input_text), \
                mock.patch.object(self.subject, "wait") as wait, \
                mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
            self.subject.field("用户名", "pathfin")
        self.assertEqual([("input", "tap", 200, 230), ("input", "keyevent", "KEYCODE_BACK")], actions)
        self.assertFalse(keyboard)
        wait.assert_called_once_with("进入媒体库")

    def test_field_never_taps_disabled_or_ambiguous_editors_and_wait_is_bounded(self):
        for options in ({"enabled": False}, {"duplicate": True}):
            clock = Clock()
            with self.subTest(options=options), \
                    mock.patch.object(self.subject, "snapshot", side_effect=lambda: editor_snapshot("用户名", **options)), \
                    mock.patch.object(self.subject, "shell") as shell, \
                    mock.patch.object(self.subject, "input_text") as input_text, \
                    mock.patch.object(self.subject, "wait"), \
                    mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                    mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
                with self.assertRaises(AssertionError):
                    self.subject.field("用户名", "pathfin")
                self.assertLessEqual(clock.now, 12.2)
                shell.assert_not_called()
                input_text.assert_not_called()

    def test_field_keeps_synthetic_value_guard_before_any_device_operation(self):
        with mock.patch.object(self.subject, "snapshot") as read, \
                mock.patch.object(self.subject, "shell") as shell, \
                mock.patch.object(self.subject, "input_text") as input_text:
            with self.assertRaisesRegex(AssertionError, "拒绝输入非编译范围"):
                self.subject.field("用户名", "another-real-account")
        read.assert_not_called()
        shell.assert_not_called()
        input_text.assert_not_called()

    def test_field_retries_prewrite_focus_rejection_without_replaying_an_accepted_commit(self):
        clock = Clock()
        focused = True
        self.subject.phase, self.subject.nonce = "LOGIN", "a" * 32
        self.subject.messages = queue.Queue()
        commands = []

        def control(command, label):
            nonlocal focused
            commands.append(command)
            rejected = commands == ["commitText", "verifyText"]
            if rejected:
                focused = False  # 设备在前置守卫处拒绝，尚未操作 InputConnection。
            self.subject.messages.put({"kind": "TEXT_INPUT", "phase": self.subject.phase,
                "nonce": self.subject.nonce, "fieldLabel": label, "command": command,
                "input": {"accepted": not rejected, "matchesExpected": not rejected,
                          "reason": "IllegalStateException: 指定真实编辑字段未聚焦或被禁用"}})

        def shell(*args):
            nonlocal focused
            if args[:2] == ("input", "tap"):
                focused = True

        with mock.patch.object(self.subject, "snapshot", side_effect=lambda: editor_snapshot("用户名", focused=focused)), \
                mock.patch.object(self.subject, "control", side_effect=control), \
                mock.patch.object(self.subject, "shell", side_effect=shell), \
                mock.patch.object(self.subject, "wait"), \
                mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
            self.subject.field("用户名", "pathfin")
        self.assertEqual(["commitText", "verifyText", "verifyText"], commands)

    def test_field_never_replays_a_commit_with_unknown_or_partially_applied_failure(self):
        for reason in ("IllegalStateException: 标准 InputConnection 拒绝合成文本",
                       "IllegalStateException: 不能完整回读自己的合成字段", "SecurityException: wrong target"):
            clock = Clock()
            self.subject.phase, self.subject.nonce = "LOGIN", "b" * 32
            self.subject.messages = queue.Queue()
            commands = []

            def control(command, label):
                commands.append(command)
                self.subject.messages.put({"kind": "TEXT_INPUT", "phase": self.subject.phase,
                    "nonce": self.subject.nonce, "fieldLabel": label, "command": command,
                    "input": {"accepted": False, "reason": reason}})

            with self.subTest(reason=reason), \
                    mock.patch.object(self.subject, "snapshot", side_effect=lambda: editor_snapshot("用户名", focused=True)), \
                    mock.patch.object(self.subject, "control", side_effect=control), \
                    mock.patch.object(self.subject, "shell") as shell, \
                    mock.patch.object(runner.time, "monotonic", side_effect=lambda: clock.now), \
                    mock.patch.object(runner.time, "sleep", side_effect=clock.sleep):
                with self.assertRaisesRegex(AssertionError, "真实编辑器拒绝输入"):
                    self.subject.field("用户名", "pathfin")
                self.assertEqual(["commitText"], commands)
                shell.assert_called_once_with("input", "tap", 200, 230)

    def test_empty_install_requires_the_first_run_brand_and_address_form(self):
        self.assertTrue(self.subject.is_empty_install(snapshot("Pathfin", "添加服务器", "服务器地址", "连接")))
        self.assertFalse(self.subject.is_empty_install(snapshot("添加服务器", "服务器地址", "连接", "返回")))
        self.assertFalse(self.subject.is_empty_install(snapshot("Pathfin", "连接")))
        self.assertFalse(self.subject.is_empty_install(snapshot("Pathfin", "添加服务器", "服务器地址", "连接", "切换服务器")))

    def test_target_gate_binds_the_exact_selected_release_instead_of_a_historical_version(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "synthetic.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in ("lib/arm64-v8a/libffmpegJNI.so", "lib/arm64-v8a/libpathfin_ass_jni.so"):
                    archive.writestr(name, b"synthetic-version-gate-only")
            for code in (29, 31, 100):
                manifest = ('  A: package="dev.pathfin.android"\n'
                            '  A: android:versionName(0x0101021c)="0.1.0"\n'
                            '  A: android:versionCode(0x0101021b)=' + hex(code) + '\n')
                with self.subTest(code=code), mock.patch.object(build, "checked", return_value=manifest), \
                        mock.patch.object(build, "certificate", return_value="synthetic-certificate"):
                    target = build.verify_target(Path("aapt2"), Path("apksigner"), apk)
                    self.assertEqual(code, target["versionCode"])
                    self.assertEqual("0.1.0", target["versionName"])
                    self.assertEqual("synthetic-certificate", target["certificateSha256"])
                    self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), target["sha256"])

    def test_target_gate_still_rejects_test_packages_and_invalid_versions(self):
        for package, version, code, flags in (
            ("dev.pathfin.android.debug", "0.1.0", 31, ""),
            ("dev.pathfin.android.verification", "0.1.0", 31, ""),
            ("dev.pathfin.android", "", 31, ""),
            ("dev.pathfin.android", "0.1.0", 0, ""),
            ("dev.pathfin.android", "0.1.0", 31, "  A: android:debuggable(0x0101000f)=true\n"),
            ("dev.pathfin.android", "0.1.0", 31, "  A: android:testOnly(0x01010272)=0xffffffff\n"),
        ):
            manifest = (f'  A: package="{package}"\n'
                        f'  A: android:versionName(0x0101021c)="{version}"\n'
                        f'  A: android:versionCode(0x0101021b)={code}\n' + flags)
            with self.subTest(package=package, version=version, code=code, flags=flags), \
                    mock.patch.object(build, "checked", return_value=manifest):
                with self.assertRaises(RuntimeError):
                    build.verify_target(Path("aapt2"), Path("apksigner"), Path("not-opened.apk"))


if __name__ == "__main__":
    unittest.main()

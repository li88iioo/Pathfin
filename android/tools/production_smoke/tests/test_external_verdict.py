"""仅使用合成 Instrumentation 输出；不连接设备、不改历史验收证据。"""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


TOOL = Path(__file__).resolve().parents[1] / "external_runner.py"
spec = importlib.util.spec_from_file_location("production_smoke_external_runner", TOOL)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
RUN_ID = "production_smoke_parser_test"
LEGACY_ERROR = {"phase": "DEVICE", "type": "DeviceSmokeFailure",
                "message": "设备未形成完整 PASS 与合成账号清理证据"}


def formatted_log():
    phases = []
    for index, phase in enumerate(runner.EXPECTED_PHASES, 1):
        phases.append("PF_SMOKE:" + json.dumps({
            "kind": "PHASE", "phase": phase, "nonce": format(index, "032x"),
            "displayName": "Production smoke " + RUN_ID,
            "snapshot": {"targetPackage": runner.TARGET},
        }))
    summary = [
        "PASS Exo session=fixture-session-21 movingFrameMeanDelta=19.69 systemBACK=detail start=1 stop=1",
        runner.COMPLETE_PASS,
        runner.CLEANUP_PASS,
        "KEYGUARD before=true after=true (never requested unlock)",
    ]
    # 平台正常格式会再次输出完整最终 report；重复的同一会话证据不是第二个会话。
    return "\n".join(phases + summary + summary + ["RESULT: PASS", ""])


def raw_log():
    return ("INSTRUMENTATION_STATUS: stream=" + formatted_log()
            + "INSTRUMENTATION_RESULT: passed=true\n"
            + "INSTRUMENTATION_RESULT: syntheticAccountRemoved=true\n"
            + "INSTRUMENTATION_CODE: -1\n")


class ExternalVerdictTest(unittest.TestCase):
    def test_formatted_stream_retains_all_device_gates_without_hidden_bundle_keys(self):
        evidence = runner.parse_device_verdict(formatted_log(), RUN_ID)
        self.assertTrue(evidence["passed"], evidence["reasons"])
        self.assertEqual("formatted-stream", evidence["format"])
        self.assertEqual({}, evidence["bundleBooleans"])
        self.assertEqual(19.69, evidence["playback"]["movingFrameMeanDelta"])
        self.assertTrue(evidence["cleanupConfirmed"])
        self.assertTrue(evidence["keyguardUnchanged"])

    def test_new_live_raw_format_requires_both_bundle_flags_and_success_code(self):
        evidence = runner.parse_device_verdict(raw_log(), RUN_ID, require_raw=True)
        self.assertTrue(evidence["passed"], evidence["reasons"])
        self.assertEqual("raw-bundle", evidence["format"])
        self.assertEqual(["-1"], evidence["instrumentationCodes"])
        self.assertFalse(runner.parse_device_verdict(formatted_log(), RUN_ID, require_raw=True)["passed"])
        for removed in ("INSTRUMENTATION_RESULT: passed=true\n",
                        "INSTRUMENTATION_RESULT: syntheticAccountRemoved=true\n",
                        "INSTRUMENTATION_CODE: -1\n"):
            with self.subTest(removed=removed):
                self.assertFalse(runner.parse_device_verdict(raw_log().replace(removed, ""), RUN_ID, require_raw=True)["passed"])

    def test_explicit_negative_bundle_or_return_code_cannot_be_overridden_by_pass_text(self):
        for before, after in (("passed=true", "passed=false"),
                              ("syntheticAccountRemoved=true", "syntheticAccountRemoved=false"),
                              ("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")):
            with self.subTest(after=after):
                self.assertFalse(runner.parse_device_verdict(raw_log().replace(before, after), RUN_ID)["passed"])
        self.assertFalse(runner.parse_device_verdict(raw_log() + "INSTRUMENTATION_RESULT: passed=false\n", RUN_ID)["passed"])

    def test_missing_any_device_gate_rejects_an_apparently_successful_run(self):
        lines = formatted_log().splitlines()
        for prefix in ("PASS Exo ", runner.COMPLETE_PASS,
                       runner.CLEANUP_PASS, "KEYGUARD ", "RESULT: "):
            with self.subTest(prefix=prefix):
                damaged = "\n".join(line for line in lines if not line.startswith(prefix))
                self.assertFalse(runner.parse_device_verdict(damaged, RUN_ID)["passed"])

    def test_dynamic_pixels_stop_count_real_back_and_keyguard_remain_strict(self):
        for before, after in (("movingFrameMeanDelta=19.69", "movingFrameMeanDelta=2.0"),
                              ("start=1 stop=1", "start=1 stop=2"),
                              ("systemBACK=detail", "systemBACK=desktop"),
                              ("before=true after=true", "before=true after=false")):
            with self.subTest(after=after):
                self.assertFalse(runner.parse_device_verdict(formatted_log().replace(before, after), RUN_ID)["passed"])

    def test_multiple_different_playback_sessions_cannot_claim_one_run(self):
        conflicting = "PASS Exo session=fixture-session-22 movingFrameMeanDelta=19.69 systemBACK=detail start=1 stop=1\n"
        self.assertFalse(runner.parse_device_verdict(formatted_log() + conflicting, RUN_ID)["passed"])

    def test_wrong_phase_owner_order_or_nonce_cannot_reuse_another_run(self):
        for before, after in (("PLAY_EXO", "BACK_EXO"),
                              (format(2, "032x"), format(1, "032x")),
                              (json.dumps(format(1, "032x")), "null"),
                              (RUN_ID, RUN_ID + "_other"),
                              (runner.TARGET, runner.TARGET + ".debug"),
                              ('"snapshot": {"targetPackage": "dev.pathfin.android"}', '"snapshot": null')):
            with self.subTest(after=after):
                self.assertFalse(runner.parse_device_verdict(formatted_log().replace(before, after), RUN_ID)["passed"])

    def test_failure_or_multiple_final_results_cannot_be_overridden_by_pass(self):
        for added in ("FAIL actual playback timed out\n", "CLEANUP FAIL: still logged in\n",
                      "INSTRUMENTATION_FAILED: process crashed\n", "RESULT: FAIL\n",
                      "RESULT: PASS\n", formatted_log()):
            with self.subTest(added=added[:50]):
                self.assertFalse(runner.parse_device_verdict(formatted_log() + added, RUN_ID)["passed"])

    def test_quoted_node_text_does_not_replace_actual_final_result(self):
        quoted = formatted_log().replace("RESULT: PASS", '{"text":"RESULT: PASS"}')
        self.assertFalse(runner.parse_device_verdict(quoted, RUN_ID)["passed"])

    def test_offline_correction_preserves_original_bytes_and_records_log_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log, original, corrected = self.write_history(root)
            original_bytes = original.read_bytes()
            log_bytes = log.read_bytes()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, runner.reparse_existing(log, original, corrected))
            result = json.loads(corrected.read_text())
            self.assertTrue(result["passed"])
            self.assertFalse(result["freshDeviceRun"])
            self.assertFalse(result["originalRunnerPassed"])
            self.assertTrue(result["originalResultUnchanged"])
            self.assertEqual(hashlib.sha256(log_bytes).hexdigest(), result["sourceLogSha256"])
            self.assertEqual(hashlib.sha256(original_bytes).hexdigest(), result["originalRunnerResultSha256"])
            self.assertEqual(original_bytes, original.read_bytes())
            self.assertEqual(log_bytes, log.read_bytes())
            with self.assertRaises(ValueError):
                runner.reparse_existing(log, original, corrected)
            with self.assertRaises(ValueError):
                runner.reparse_existing(log, original, original)

    def test_offline_correction_keeps_actual_host_errors_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            log, original, corrected = self.write_history(Path(directory), extra_errors=[{
                "phase": "PLAY_EXO", "type": "AssertionError", "message": "真实输入失败"}])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, runner.reparse_existing(log, original, corrected))
            result = json.loads(corrected.read_text())
            self.assertFalse(result["passed"])
            self.assertEqual(1, len(result["priorHostErrors"]))

    def test_offline_cli_does_not_require_serial_or_execute_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            log, original, corrected = self.write_history(Path(directory))
            with mock.patch.object(sys, "argv", [str(TOOL), "--reparse-log", str(log),
                                                  "--corrected-verdict", str(corrected)]), \
                    mock.patch.object(subprocess, "Popen", side_effect=AssertionError("禁止离线执行设备命令")), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, runner.main())
            self.assertTrue(json.loads(corrected.read_text())["passed"])

    @staticmethod
    def write_history(root, extra_errors=()):
        log, original, corrected = (root / name for name in ("instrumentation.log", "runner-result.json", "runner-verdict-corrected.json"))
        log.write_text(formatted_log(), encoding="utf-8")
        original.write_text(json.dumps({"targetPackage": runner.TARGET, "runId": RUN_ID,
                                        "passed": False, "errors": [LEGACY_ERROR, *extra_errors]}), encoding="utf-8")
        return log, original, corrected


if __name__ == "__main__":
    unittest.main()

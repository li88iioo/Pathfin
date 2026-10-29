#!/usr/bin/env python3
"""验证发版正文选择与拒绝不完整日志的行为，不依赖 Android 构建环境。"""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from extract_release_notes import extract_notes


class ReleaseNotesTest(unittest.TestCase):
    def test_selects_only_requested_version(self):
        changelog = """# 更新日志

## [Unreleased]
- 尚未发布的功能。

## [0.2.0] - 2026-10-30
### 修复

- 修复字幕切换。
<!--
## 维护备注
隐藏说明不进入正文。
-->
- 保留播放位置。

## [0.1.0] - 2026-10-24
- 首版功能。

[Unreleased]: https://example.com/compare/v0.2.0...HEAD
[0.2.0]: https://example.com/compare/v0.1.0...v0.2.0
[0.1.0]: https://example.com/tag/v0.1.0
"""
        self.assertEqual("### 修复\n\n- 修复字幕切换。\n\n- 保留播放位置。\n", extract_notes(changelog, "0.2.0"))
        self.assertEqual("- 首版功能。\n", extract_notes(changelog, "0.1.0"))

    def test_rejects_missing_or_duplicate_version(self):
        section = "## [0.1.0] - 2026-10-30\n- 首版功能。\n"
        for changelog, version in ((section, "0.1.1"), (section * 2, "0.1.0")):
            with self.subTest(version=version, changelog=changelog):
                with self.assertRaisesRegex(ValueError, "有且仅有一个"):
                    extract_notes(changelog, version)

    def test_rejects_unready_or_invalid_date(self):
        for release_date in ("待发布", "2026-02-30", "2026-2-3"):
            with self.subTest(date=release_date):
                with self.assertRaisesRegex(ValueError, "发布日期"):
                    extract_notes(f"## [0.1.0] - {release_date}\n- 首版功能。", "0.1.0")

    def test_rejects_empty_notes_even_with_headings_comments_or_compare_links(self):
        for body in ("", "### 修复\n", "<!--\n- 尚未确认的变更\n-->",
                     "[0.1.0]: https://example.com/tag/v0.1.0"):
            with self.subTest(body=body):
                with self.assertRaisesRegex(ValueError, "缺少变更条目"):
                    extract_notes("## [0.1.0] - 2026-10-30\n" + body, "0.1.0")

    def test_rejects_non_release_versions(self):
        for version in ("v0.1.0", "0.1", "0.1.0-beta"):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ValueError, "X.Y.Z"):
                    extract_notes("", version)

    def test_cli_writes_utf8_notes_and_fails_without_output_for_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "CHANGELOG.md", root / "notes.md"
            command = [sys.executable, str(Path(__file__).with_name("extract_release_notes.py")),
                       str(source), "0.1.0", "--output", str(output)]
            source.write_text("## [0.1.0] - 待发布\n- 首版功能。\n", encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("发布日期", result.stderr)
            self.assertFalse(output.exists())

            source.write_bytes("## [0.1.0] - 2026-10-30\r\n- 首版功能。\r\n".encode("utf-8"))
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("- 首版功能。\n".encode("utf-8"), output.read_bytes())


if __name__ == "__main__":
    unittest.main()

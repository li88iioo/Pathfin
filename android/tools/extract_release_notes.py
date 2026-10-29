#!/usr/bin/env python3
"""从 CHANGELOG 的正式版本章节提取 Release 正文，缺失或待发布时阻止发版。"""

import argparse
from datetime import date
from pathlib import Path
import re


def extract_notes(changelog: str, version: str) -> str:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("版本号必须为 X.Y.Z")
    # 先移除维护备注，避免其中的标题被误认为版本边界。
    changelog = re.sub(r"<!--.*?-->", "", changelog, flags=re.DOTALL)
    sections = re.split(r"(?m)^##[ \t]+", changelog)[1:]
    selected = [section for section in sections if re.match(rf"\[{re.escape(version)}\](?:[ \t]|$)", section)]
    if len(selected) != 1:
        raise ValueError(f"CHANGELOG 必须有且仅有一个 [{version}] 版本章节，实际找到 {len(selected)} 个")
    title, _, body = selected[0].partition("\n")
    heading = re.fullmatch(rf"\[{re.escape(version)}\] - (\d{{4}}-\d{{2}}-\d{{2}})", title.strip())
    if not heading:
        raise ValueError(f"[{version}] 必须填写正式发布日期，标题格式：## [{version}] - YYYY-MM-DD")
    try:
        date.fromisoformat(heading[1])
    except ValueError as error:
        raise ValueError(f"[{version}] 的发布日期无效：{heading[1]}") from error
    # 文末版本比较链接不进入 Release，也不显示在应用内。
    body = re.sub(r"(?m)^\[(?:Unreleased|\d+\.\d+\.\d+)\]:[^\n]*$", "", body).strip()
    if not re.search(r"(?m)^[-*][ \t]+\S", body):
        raise ValueError(f"[{version}] 缺少变更条目，请使用 '- 内容' 列表")
    return body + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("changelog", type=Path)
    parser.add_argument("version", help="不带 v 前缀的正式版本号")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        notes = extract_notes(args.changelog.read_text(encoding="utf-8"), args.version)
        args.output.write_text(notes, encoding="utf-8")
    except (OSError, ValueError) as error:
        parser.exit(1, f"更新日志检查失败：{error}\n")


if __name__ == "__main__":
    main()

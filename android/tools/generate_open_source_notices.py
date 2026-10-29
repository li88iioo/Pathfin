#!/usr/bin/env python3
"""根据真实 releaseRuntimeClasspath 二进制、精确 POM 和已核对文本生成离线许可。

首次/升级：--artifacts resolved-artifacts.json --fetch
复现校验：python3 android/tools/generate_open_source_notices.py --check
只使用 Python 标准库；不执行 Gradle，不扫描源码包或 Javadoc 包。
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android/app/src/main/assets/open-source"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
LEGAL_NAME = re.compile(r"^(?:LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT)(?:[._-].*)?$", re.I)
GRADLE_INIT = '''gradle.projectsEvaluated {
    def app = gradle.rootProject.project(':app')
    app.tasks.register('pathfinReleaseArtifactPaths') {
        doLast {
            def artifacts = app.configurations.getByName('releaseRuntimeClasspath').incoming.artifactView {
                componentFilter { it instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            }.artifacts.artifacts.collect {
                def id = it.id.componentIdentifier
                [id: "${id.group}:${id.module}:${id.version}", type: it.file.name.tokenize('.').last(), file: it.file.absolutePath]
            }.sort { it.id }
            def target = new File(app.layout.buildDirectory.get().asFile, 'reports/open-source/release-artifacts.json')
            target.parentFile.mkdirs()
            target.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(artifacts))
            println('Exported ' + artifacts.size() + ' resolved runtime artifacts to ' + target)
        }
    }
}
'''


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def pom_file(coordinate: str) -> str:
    return "evidence/poms/" + coordinate.replace(":", "__") + ".pom"


def pom_url(coordinate: str) -> str:
    group, artifact, version = coordinate.split(":")
    base = "https://dl.google.com/dl/android/maven2" if group.startswith("androidx.") else "https://repo.maven.apache.org/maven2"
    return f"{base}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"


def xml_text(root: ET.Element, path: str) -> str:
    node = root.find(path, NS)
    return (node.text or "").strip() if node is not None else ""


def safe_url(value: str) -> str:
    url = urllib.parse.urlparse(value)
    return value if url.scheme in {"http", "https"} and url.hostname and not url.username and not url.password and "${" not in value else ""


def declared_spdx(name: str, url: str) -> str | None:
    # 匹配 POM 的实际声明；没有声明时必须查父 POM，绝不按 group 猜测。
    host = urllib.parse.urlparse(url).hostname or ""
    path = urllib.parse.urlparse(url).path.rstrip("/").lower()
    if (host in {"www.apache.org", "apache.org"} and path in {"/licenses/license-2.0", "/licenses/license-2.0.txt"}) or name.strip().lower() in {"apache-2.0", "apache 2.0", "apache license, version 2.0", "the apache software license, version 2.0", "the apache license, version 2.0"}:
        return "Apache-2.0"
    if host in {"opensource.org", "www.opensource.org"} and path == "/licenses/mit":
        return "MIT"
    if name.strip().lower() == "mit license":
        return "MIT"
    return None


class Generator:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.outputs: dict[str, bytes] = {}
        self.poms: dict[str, ET.Element] = {}
        self.issues: list[dict[str, str]] = []
        self.cache = Path(args.gradle_cache).expanduser()

    def read_pom(self, coordinate: str) -> ET.Element:
        if coordinate in self.poms:
            return self.poms[coordinate]
        relative = pom_file(coordinate)
        candidates = [ASSETS / relative]
        if self.args.pom_cache:
            candidates.append(Path(self.args.pom_cache) / Path(relative).name)
        group, artifact, version = coordinate.split(":")
        candidates.extend(sorted((self.cache / group / artifact / version).glob(f"*/{artifact}-{version}.pom")))
        data = next((path.read_bytes() for path in candidates if path.is_file()), None)
        if data is None and self.args.fetch:
            with urllib.request.urlopen(urllib.request.Request(pom_url(coordinate), headers={"User-Agent": "Pathfin-notices/1"}), timeout=30) as response:
                data = response.read()
        if data is None:
            raise ValueError(f"缺少精确 POM：{coordinate}（可使用 --fetch）")
        root = ET.fromstring(data)
        # 拒绝错误版本的缓存/下载响应；允许 group/version 继承。
        actual_artifact = xml_text(root, "m:artifactId")
        actual_group = xml_text(root, "m:groupId") or xml_text(root, "m:parent/m:groupId")
        actual_version = xml_text(root, "m:version") or xml_text(root, "m:parent/m:version")
        if (actual_group, actual_artifact, actual_version) != (group, artifact, version):
            raise ValueError(f"POM 坐标不匹配：{coordinate}")
        self.outputs[relative] = data
        self.poms[coordinate] = root
        return root

    def licenses(self, coordinate: str, seen: tuple[str, ...] = ()) -> list[dict[str, str]]:
        if coordinate in seen or len(seen) > 12:
            raise ValueError(f"POM 继承循环：{coordinate}")
        root = self.read_pom(coordinate)
        result = []
        for license_node in root.findall("m:licenses/m:license", NS):
            name, url = xml_text(license_node, "m:name"), xml_text(license_node, "m:url")
            spdx = declared_spdx(name, url)
            if spdx is None:
                self.issues.append({"component": coordinate, "reason": f"未识别许可声明：{name} / {url}"})
            result.append({"id": spdx or "UNKNOWN", "name": name, "url": safe_url(url), "declaredBy": coordinate, "evidence": pom_file(coordinate)})
        if result:
            return result
        parent = root.find("m:parent", NS)
        if parent is not None:
            parent_id = ":".join(xml_text(parent, "m:" + field) for field in ("groupId", "artifactId", "version"))
            return self.licenses(parent_id, seen + (coordinate,))
        self.issues.append({"component": coordinate, "reason": "精确 POM 及其父 POM 均无许可声明"})
        return []

    def text(self, data: bytes, title: str, kind: str, source: str) -> dict[str, str]:
        data.decode("utf-8")  # 不静默替换会丢失法律文本的非法字节。
        sha = digest(data)
        relative = f"texts/{sha}.txt"
        self.outputs[relative] = data
        return {"path": relative, "sha256": sha, "title": title, "kind": kind, "source": source}

    def binary_path(self, record: dict[str, str]) -> Path:
        direct = Path(record.get("file", ""))
        if direct.is_file():
            return direct
        group, artifact, version = record["id"].split(":")
        name = record.get("fileName") or Path(record["file"]).name
        candidates = sorted((self.cache / group / artifact / version).glob("*/" + name))
        expected = record.get("sha256")
        for path in candidates:
            if not expected or digest(path.read_bytes()) == expected:
                return path
        raise ValueError(f"缺少真实二进制：{record['id']} / {name}")

    def archive_texts(self, data: bytes, source: str, depth: int = 0) -> list[dict[str, str]]:
        texts = []
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for info in sorted(archive.infolist(), key=lambda item: item.filename):
                if info.is_dir():
                    continue
                path = info.filename
                if LEGAL_NAME.match(Path(path).name):
                    kind = "notice" if "NOTICE" in Path(path).name.upper() else "license"
                    texts.append(self.text(archive.read(info), Path(path).name, kind, source + "!/" + path))
                elif depth == 0 and (path == "classes.jar" or (path.startswith("libs/") and path.endswith(".jar"))):
                    texts.extend(self.archive_texts(archive.read(info), source + "!/" + path, depth + 1))
        return texts

    def generate(self) -> None:
        loaded = json.loads(Path(self.args.artifacts).read_text())
        records = loaded if isinstance(loaded, list) else loaded["artifacts"]
        record_count = len(records) if isinstance(loaded, list) else loaded["resolvedArtifactRecordCount"]
        evidence = json.loads((ASSETS / "upstream-evidence.json").read_text())
        self.outputs["evidence/upstream-checks.json"] = (ASSETS / "evidence/upstream-checks.json").read_bytes()
        supplements = {}
        canonical = None
        for item in evidence["texts"]:
            data = (ASSETS / item["path"]).read_bytes()
            if digest(data) != item["sha256"]:
                raise ValueError(f"上游文本校验失败：{item['path']}")
            self.outputs[item["path"]] = data
            ref = self.text(data, item["title"], item["kind"], item["url"])
            if item.get("canonicalLicense") == "Apache-2.0":
                canonical = ref
            for coordinate in item.get("components", []):
                supplements.setdefault(coordinate, []).append(ref)
        if canonical is None:
            raise ValueError("缺少官方 Apache-2.0 全文")
        by_coordinate: dict[str, dict] = {}
        inventory = []
        seen_artifacts = set()
        embedded_count = 0
        for record in sorted(records, key=lambda item: (item["id"], item.get("fileName", item.get("file", "")))):
            coordinate = record["id"]
            path = self.binary_path(record)
            if re.search(r"-(sources|javadoc|samples)\.", path.name):
                raise ValueError(f"非运行时归档：{path.name}")
            data = path.read_bytes()
            sha = digest(data)
            if record.get("sha256") and record["sha256"] != sha:
                raise ValueError(f"二进制 SHA-256 不匹配：{coordinate}")
            artifact_key = (coordinate, path.name, sha)
            if artifact_key in seen_artifacts:
                continue
            seen_artifacts.add(artifact_key)
            artifact = {"id": coordinate, "type": record["type"], "fileName": path.name, "sha256": sha}
            inventory.append(artifact)
            if coordinate not in by_coordinate:
                root = self.read_pom(coordinate)
                group, name, version = coordinate.split(":")
                licenses = self.licenses(coordinate)
                ids = sorted({item["id"] for item in licenses})
                homepage = safe_url(xml_text(root, "m:url"))
                links = [{"label": "精确版本 POM", "url": pom_url(coordinate)}]
                if homepage:
                    links.insert(0, {"label": "项目主页", "url": homepage})
                by_coordinate[coordinate] = {"id": coordinate, "name": xml_text(root, "m:name") or name, "group": group, "artifact": name, "version": version, "kind": "maven", "licenseIds": ids, "licenseDeclarations": licenses, "links": links, "texts": ([canonical] if "Apache-2.0" in ids else []) + supplements.get(coordinate, []), "artifacts": []}
            component = by_coordinate[coordinate]
            component["artifacts"].append(artifact)
            extracted = self.archive_texts(data, coordinate + "/" + path.name)
            embedded_count += len(extracted)
            component["texts"].extend(extracted)
        components = list(by_coordinate.values())
        for component in components:
            unique = {}
            for text in component["texts"]:
                unique.setdefault(text["sha256"], text)
            component["texts"] = list(unique.values())
            if not component["texts"]:
                self.issues.append({"component": component["id"], "reason": "许可有声明但尚缺可分发的完整正文"})
        self.outputs["runtime-artifacts.json"] = json_bytes({"schemaVersion": 1, "configuration": "releaseRuntimeClasspath", "resolvedArtifactRecordCount": record_count, "artifacts": inventory})
        manifest = {"schemaVersion": 1, "configuration": "releaseRuntimeClasspath", "resolvedArtifactRecordCount": record_count, "runtimeArtifactCount": len(inventory), "runtimeComponentCount": len(by_coordinate), "manualSourceCount": 0, "componentCount": len(components), "embeddedLegalEntryCount": embedded_count, "upstreamCatalogSha256": digest((ASSETS / "upstream-evidence.json").read_bytes()), "components": sorted(components, key=lambda item: item["id"].casefold()), "unresolved": self.issues, "evidenceFiles": [{"path": name, "sha256": digest(data), "bytes": len(data)} for name, data in sorted(self.outputs.items()) if name.startswith("evidence/")]}
        self.outputs["manifest.json"] = json_bytes(manifest)
        self.outputs["unresolved.json"] = json_bytes({"unresolved": self.issues})
        if self.args.check:
            different = [name for name, data in self.outputs.items() if not (ASSETS / name).is_file() or (ASSETS / name).read_bytes() != data]
            if different:
                raise ValueError("生成物已过期或被更改：" + ", ".join(different))
        else:
            for name, data in self.outputs.items():
                target = ASSETS / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        print(f"{'CHECK OK' if self.args.check else 'GENERATED'}: {record_count} resolved records, {len(inventory)} unique runtime artifacts, {len(components)} components; {len(self.outputs)} files; unresolved={len(self.issues)}")
        if self.issues:
            raise ValueError("仍有未确认的许可；请查看 assets/open-source/unresolved.json")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", default=str(ASSETS / "runtime-artifacts.json"), help="Gradle resolvedArtifacts JSON 或已生成的不含绝对路径清单")
    parser.add_argument("--gradle-cache", default=str(Path.home() / ".gradle/caches/modules-2/files-2.1"))
    parser.add_argument("--pom-cache", help="可选：精确坐标命名的 POM 缓存目录")
    parser.add_argument("--fetch", action="store_true", help="允许从 Google Maven/Maven Central 获取缺失的精确 POM")
    parser.add_argument("--check", action="store_true", help="只比较，不修改生成物；需本地存在相同运行时二进制")
    parser.add_argument("--print-gradle-init", action="store_true", help="输出可复用的真实 Release 产物导出脚本，然后退出")
    args = parser.parse_args()
    if args.print_gradle_init:
        print(GRADLE_INIT, end="")
        return 0
    try:
        Generator(args).generate()
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""打包应用源码，或生成独立 FFmpeg/ASS 的离线许可和匹配原生源码归档。"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
EPOCH = 1792972800
EXCLUDED = {".git", ".gradle", ".kotlin", ".idea", ".superpowers", "build", "build-native",
            "__pycache__", "jniLibs", ".cxx", "tmp", "temp"}
PRIVATE_NAMES = {"local.properties", "release.properties", "signing.properties", "keystore.properties",
                 "credentials.json", "instance.json", ".env"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".key", ".apk", ".aab", ".pyc"}
LICENSES = {
    "ffmpeg": ("FFmpeg audio/video decoders", ["LGPL-2.1-or-later"], ["LICENSE.md", "COPYING.LGPLv2.1"]),
    "libplacebo": ("libplacebo GLES renderer", ["LGPL-2.1-or-later"], ["LICENSE"]),
    "fast_float": ("fast_float", ["MIT"], ["LICENSE-MIT"]),
    "glad": ("glad and Khronos OpenGL/EGL specifications", ["MIT", "Apache-2.0"], ["LICENSE"]),
    "libass": ("libass", ["ISC"], ["COPYING"]),
    "freetype": ("FreeType", ["FTL"], ["LICENSE.TXT", "docs/FTL.TXT"]),
    "fribidi": ("GNU FriBidi", ["LGPL-2.1-or-later"], ["COPYING"]),
    "harfbuzz": ("HarfBuzz", ["MIT-Modern-Variant"], ["COPYING"]),
    "unibreak": ("libunibreak", ["Zlib"], ["LICENCE"]),
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n")


def selected_files(folder):
    return sorted(p for p in folder.rglob("*") if p.is_file() and
                  not set(p.relative_to(folder).parts) & EXCLUDED and p.name not in PRIVATE_NAMES and
                  not p.name.startswith(".env.") and p.suffix.lower() not in PRIVATE_SUFFIXES)


class NativeSources:
    def __init__(self, module, cache, sdk):
        self.name = module
        self.module = ROOT / "android" / module
        self.cache = cache.resolve()
        self.sdk = sdk.resolve()
        self.lock = json.loads((self.module / "sources.lock.json").read_text())
        self.build = json.loads((self.module / "native-build.json").read_text())
        self.sources = {row["name"]: row for row in self.lock["native"]["sources"]}
        self.assets = self.module / "src/main/assets" / ("open-source-" + module)
        self.compiled = {}
        self.generated = set()
        self.inputs = None

    def source(self, name):
        return self.cache / "src" / self.sources[name].get("destination", name)

    def verify(self):
        assert self.build["sourceLockSha256"] == sha((self.module / "sources.lock.json").read_bytes()), "原生构建与锁文件不同"
        assert self.build["recipeSha256"] == sha((self.module / "build_native.py").read_bytes()), "原生构建与构建脚本不同"
        for row in self.lock.get("files", []) + self.lock.get("implementationFiles", []) + self.lock.get("copiedHeaders", []):
            assert sha((self.module / row["path"]).read_bytes()) == row["sha256"], row["path"]
        for relative, digest in self.build.get("sourceInputs", {}).items():
            assert sha((self.module / relative).read_bytes()) == digest, relative
        for row in self.sources.values():
            archive = self.cache / "downloads" / row["archive"]
            assert sha(archive.read_bytes()) == row["sha256"], row["archive"]
            source = self.source(row["name"])
            # 原始文件逐字核对；生成的 configure/构建文件不冒充上游修改。
            with tarfile.open(archive) as bundle:
                for member in bundle:
                    if member.isfile():
                        relative = Path(*Path(member.name).parts[1:])
                        assert relative != Path(".")
                        assert (source / relative).read_bytes() == bundle.extractfile(member).read(), str(relative)
        ndk = self.sdk / "ndk" / self.build["ndkVersion"]
        assert "Pkg.Revision = " + self.build["ndkVersion"] in (ndk / "source.properties").read_text()
        tool = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
        libraries = self.module / "src/main/jniLibs/arm64-v8a"
        names = {row["file"] for row in self.build["libraries"]}
        assert names == {p.name for p in libraries.glob("*.so")}, "暂存库与原生清单不同"
        system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libjnigraphics.so", "libandroid.so", "libEGL.so", "libGLESv3.so"}
        for row in self.build["libraries"]:
            path = libraries / row["file"]
            assert path.stat().st_size == row["bytes"] and sha(path.read_bytes()) == row["sha256"], row["file"]
            header = subprocess.check_output([tool, "-h", path], text=True)
            assert re.search(r"Machine:\s+AArch64", header), row["file"]
            headers = subprocess.check_output([tool, "-lW", path], text=True)
            aligns = [int(line.split()[-1], 16) for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
            assert aligns == row["loadAlignments"] and min(aligns) >= 16384, row["file"]
            dynamic = subprocess.check_output([tool, "-dW", path], text=True)
            needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
            assert needed == row["needed"] and not set(needed) - names - system, row["file"]
        if self.name == "ffmpeg":
            config = (self.cache / "build/arm64-v8a/ffmpeg/config.h").read_text()
            for flag in ("GPL", "NONFREE", "VERSION3", "LGPLV3", "AVFORMAT", "AVDEVICE", "AVFILTER", "SWSCALE", "NETWORK"):
                assert f"#define CONFIG_{flag} 0" in config, flag
            assert sha((self.cache / "build/arm64-v8a/ffmpeg/config.h").read_bytes()) == self.build["configSha256"]
            components = self.cache / "build/arm64-v8a/ffmpeg/config_components.h"
            assert sha(components.read_bytes()) == self.build["componentConfigSha256"]
            assert set(re.findall(r"#define CONFIG_(\w+)_DECODER 1", components.read_text())) == {name.upper() for name in self.lock["native"]["decoders"]}
            assert not self.build["audioOnly"] and self.build["videoDecoders"] == self.lock["native"]["videoDecoders"]
            assert self.build["cxxRuntime"] == "c++_static" and self.build["renderer"] == self.lock["native"]["renderer"]
            assert sha((self.cache / "prefix/arm64-v8a/include/libplacebo/config.h").read_bytes()) == self.build["libplaceboConfigSha256"]
            assert sha((self.cache / "android-arm64.ini").read_bytes()) == self.build["crossFileSha256"]
            for name, digest in self.build["copiedAvformatHeaders"].items():
                assert sha((self.cache / "prefix/arm64-v8a/include/libavformat" / name).read_bytes()) == digest, name
                assert sha((self.source("ffmpeg") / "libavformat" / name).read_bytes()) == digest, name
        for name, digest in self.build["staticArchives"].items():
            assert sha((self.cache / "prefix/arm64-v8a/lib" / name).read_bytes()) == digest, name

    def text(self, data, title, source, kind="license"):
        digest = sha(data)
        target = self.assets / "texts" / (digest + ".txt")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return {"path": "texts/" + target.name, "sha256": digest, "title": title, "source": source, "kind": kind}

    def build_inputs(self):
        if self.inputs is not None:
            return self.inputs
        files = set()
        build_root = self.cache / "build/arm64-v8a"
        for compdb in build_root.rglob("compile_commands.json"):
            for row in json.loads(compdb.read_text()):
                path = (Path(row["directory"]) / row["file"]).resolve()
                if path.is_file():
                    files.add(path)
            # Ninja 在成功编译后把 .d 文件并入自身依赖数据库。
            if (compdb.parent / ".ninja_deps").is_file():
                ninja = self.sdk / "cmake/3.22.1/bin/ninja"
                dependencies = subprocess.check_output([ninja, "-C", compdb.parent, "-t", "deps"], text=True)
                for line in dependencies.splitlines():
                    if line.startswith("    "):
                        path = (compdb.parent / line.strip()).resolve()
                        if path.is_file():
                            files.add(path)
        for dependency in [*build_root.rglob("*.d"), *build_root.rglob("*.Po"), *build_root.rglob("*.Plo")]:
            for token in shlex.split(dependency.read_text(errors="replace").replace("\\\n", " "), comments=False):
                path = (build_root / dependency.relative_to(build_root).parts[0] / token.rstrip(":")).resolve()
                if path.is_file():
                    files.add(path)
        self.inputs = files
        return files

    def original_comments(self, name):
        source = self.source(name).resolve()
        children = [self.source(child).resolve() for child in self.sources if child != name and self.source(child).is_relative_to(source)]
        files = {path for path in self.build_inputs() if path.is_relative_to(source) and not any(path.is_relative_to(child) for child in children)}
        if name == "glad":
            generated = self.cache / "build/arm64-v8a/libplacebo/src/opengl/include/glad/gl.h"
            assert generated.is_file() and generated.resolve() in self.build_inputs(), "glad 生成头必须是实际编译依赖"
            files.add(generated.resolve())
            self.generated.add(generated)
        assert files, "没有找到实际编译输入: " + name
        def relative(path):
            return str(path.relative_to(source)) if path.is_relative_to(source) else "generated:" + str(path.relative_to(self.cache))
        self.compiled[name] = sorted(relative(p) for p in files)
        blocks = {}
        for path in sorted(files):
            if path.suffix.lower() not in {".c", ".cc", ".cpp", ".h", ".hh", ".hpp", ".s", ".asm"}:
                continue
            for match in re.finditer(r"/\*.*?\*/", path.read_text(errors="replace")[:24000], re.S):
                block = match.group()
                if re.search(r"copyright|SPDX-License-Identifier", block, re.I):
                    blocks.setdefault(block, []).append(relative(path))
        return ("Original copyright/license comments from actual build inputs.\nThe matching source archive retains the full files.\n" +
                "\n".join("\nFiles: " + ", ".join(paths) + "\n" + block for block, paths in sorted(blocks.items(), key=lambda pair: pair[1][0]))).encode()

    def notices(self):
        components = []
        for name, row in self.sources.items():
            title, licenses, paths = LICENSES[name]
            source = self.source(name)
            base = row["repository"] + "/blob/" + row["commit"] + "/"
            texts = [self.text((source / path).read_bytes(), path, base + path) for path in paths]
            if name == "glad":
                texts.append(self.text((self.module / "LICENSE").read_bytes(), "Apache License 2.0 (Khronos specifications)", "https://www.apache.org/licenses/LICENSE-2.0"))
            texts.append(self.text(self.original_comments(name), "实编译输入原始版权声明", row["repository"] + "/tree/" + row["commit"], "notice"))
            if name == "freetype":
                texts.append(self.text(("Portions of this software are copyright © 2026 The FreeType Project (https://freetype.org). All rights reserved.\n"
                    "This software is based in part on the work of the FreeType Team.\nFreeType is used under the FreeType Project License (FTL). No FreeType source files are modified.\n").encode(),
                    "FreeType 归属声明", base + "docs/FTL.TXT", "notice"))
            components.append({"id": "native:" + name + ":" + row["commit"], "name": title, "group": "native", "artifact": name,
                "version": row["ref"], "kind": "native", "licenseIds": licenses, "modifications": [], "texts": texts,
                "links": [{"label": "固定源码提交", "url": row["repository"] + "/tree/" + row["commit"]}],
                "sourceArchive": {key: row[key] for key in ("archive", "sha256", "commit", "url")}})
        if self.name == "ffmpeg":
            commit = self.lock["commit"]
            components.append({"id": "source:androidx-media3-ffmpeg:" + commit, "name": "AndroidX Media3 FFmpeg Audio Decoder",
                "group": "source-vendored", "artifact": "media3-decoder-ffmpeg", "kind": "source-vendored",
                "version": self.lock["media3Version"] + " / " + commit[:12], "licenseIds": ["Apache-2.0"],
                "links": [{"label": "固定上游源码", "url": self.lock["repository"] + "/tree/" + commit + "/libraries/decoder_ffmpeg"}],
                "modifications": ["Media3 音频 Java 保持原样；音频 JNI 修复 PCM 扩容追加、实际样本计数、初始化数据零填充和多声道平面输入。Pathfin 增加独立视频 decoder/JNI 和 libplacebo GLES 输出，保留 MediaCodec 优先。FFmpeg 启用固定音视频 decoder，不包含网络、容器、GPL/nonfree 或 version3 功能。"],
                "texts": [self.text((self.module / "LICENSE").read_bytes(), "Apache License 2.0", self.lock["repository"] + "/blob/" + commit + "/LICENSE")]})
        else:
            wrapper = self.lock["wrapper"]
            commit = wrapper["commit"]
            components.append({"id": "wrapper:libass-android:" + commit, "name": "libass-android MKV attachment discovery (adapted)",
                "group": "source-adaptation", "artifact": "pathfin-ass-overlay", "kind": "source-adaptation", "version": "v0.5.1 / " + commit[:12],
                "licenseIds": ["MIT"], "links": [{"label": "固定上游源码", "url": wrapper["repository"] + "/tree/" + commit}],
                "modifications": ["公开 Media3 raw TrackOutput、附件限额、独立 Canvas 和真实时钟；libass 与字体依赖直接静态链接入本模块 JNI。"],
                "texts": [self.text((self.module / "LICENSES/libass-android-MIT.txt").read_bytes(), "MIT License", wrapper["repository"] + "/blob/" + commit + "/LICENSE")]})
        ndk = self.sdk / "ndk" / self.build["ndkVersion"]
        toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
        static_runtime = self.name == "ffmpeg"
        components.append({"id": "native:android-ndk-libcxx" + ("-static:" if static_runtime else ":") + self.build["ndkVersion"],
                "name": "Android NDK libc++ " + ("static" if static_runtime else "shared") + " runtime",
                "group": "native", "artifact": "libc++_static.a" if static_runtime else "libc++_shared.so", "kind": "native-runtime", "version": self.build["ndkVersion"],
                "licenseIds": ["Apache-2.0 WITH LLVM-exception", "NCSA", "MIT"], "modifications": [],
                "links": [{"label": "LLVM 基础提交", "url": "https://github.com/llvm/llvm-project/tree/2d287f51eff2a5fbf84458a33f7fb2493cf67965"},
                          {"label": "Android LLVM 构建与补丁", "url": "https://android.googlesource.com/toolchain/llvm_android/+/9e20ac0b949feedcfe3da791189310f6ebcbc7a8"}],
                "texts": [self.text((ndk / "NOTICE.toolchain").read_bytes(), "NDK 原始 LLVM 许可与归属集合（含构建工具补充条款）", "Android NDK " + self.build["ndkVersion"] + "/NOTICE.toolchain"),
                          self.text((toolchain / "AndroidVersion.txt").read_bytes(), "NDK LLVM 版本", "Android NDK/AndroidVersion.txt", "notice"),
                          self.text((toolchain / "clang_source_info.md").read_bytes(), "NDK 对应 LLVM 提交和补丁来源", "Android NDK/clang_source_info.md", "notice")]})
        manifest = {"schemaVersion": 1, "componentCount": len(components), "components": sorted(components, key=lambda row: row["id"]),
            "unresolved": [], "correspondingSource": "pathfin-" + self.name + "-corresponding-source.tar",
            "buildPolicy": {"gpl": False, "nonfree": False, "projectLicenseSelected": False}}
        dump(self.assets / "manifest.json", manifest)
        dump(self.assets / "compiled-inputs.json", self.compiled)
        for name in ("native-build.json", "sources.lock.json"):
            (self.assets / name).write_bytes((self.module / name).read_bytes())
        referenced = {item["path"] for component in components for item in component["texts"]}
        for path in (self.assets / "texts").glob("*.txt"):
            if "texts/" + path.name not in referenced:
                path.unlink()

    def bundle(self, output):
        files = {"upstream/" + row["archive"]: self.cache / "downloads" / row["archive"] for row in self.sources.values()}
        for path in selected_files(self.module):
            if path.name != "source-distribution.json":
                files["module/" + path.relative_to(self.module).as_posix()] = path
        files["tools/package_source.py"] = Path(__file__).resolve()
        for path in (self.cache / "build/arm64-v8a").rglob("*"):
            if path.is_file() and path.name in {"config.h", "config_components.h", "config.mak", "meson-info.json", "intro-buildoptions.json"}:
                files["build-config/" + path.relative_to(self.cache / "build").as_posix()] = path
        if self.name == "ffmpeg":
            files["build-config/android-arm64.ini"] = self.cache / "android-arm64.ini"
        for path in self.generated:
            files["build-config/" + path.relative_to(self.cache / "build").as_posix()] = path
        # 保留桥接 object，允许重新链接静态 LGPL 依赖。
        for path in (self.module / "build-native/arm64-v8a").rglob("*.o"):
            files["relink/" + path.relative_to(self.module / "build-native/arm64-v8a").as_posix()] = path
        inventory = {name: {"bytes": path.stat().st_size, "sha256": sha(path.read_bytes())} for name, path in sorted(files.items())}
        inventory_data = (json.dumps({"schemaVersion": 1, "files": inventory}, sort_keys=True, indent=2) + "\n").encode()
        output.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(output, "w", format=tarfile.PAX_FORMAT) as archive:
            for name, path in sorted(files.items()):
                assert not path.is_symlink(), name
                info = tarfile.TarInfo(name)
                info.size = path.stat().st_size
                info.mode = 0o755 if path.suffix == ".py" else 0o644
                info.mtime = EPOCH
                with path.open("rb") as stream:
                    archive.addfile(info, stream)
            info = tarfile.TarInfo("SOURCE-INVENTORY.json")
            info.size = len(inventory_data)
            info.mode = 0o644
            info.mtime = EPOCH
            archive.addfile(info, io.BytesIO(inventory_data))
        distribution = {"schemaVersion": 1, "archive": output.name, "bytes": output.stat().st_size, "sha256": sha(output.read_bytes()),
            "lockedUpstreamArchives": len(self.sources), "sourceInventorySha256": sha(inventory_data),
            "pairedNativeBuildSha256": sha((self.module / "native-build.json").read_bytes()),
            "pairedSourceLockSha256": sha((self.module / "sources.lock.json").read_bytes()),
            "releaseRequirement": "Distribute this matching archive and SHA-256 alongside the APK containing the paired native libraries."}
        dump(self.module / "source-distribution.json", distribution)
        output.with_suffix(output.suffix + ".sha256").write_text(distribution["sha256"] + "  " + output.name + "\n")
        return distribution


def application_source(output):
    selected = [ROOT / "README.md", ROOT / ".gitignore"]
    selected += [p for folder in ("android", "docs") for p in selected_files(ROOT / folder)]
    inventory = []
    distributions = []
    for module in ("ffmpeg", "ass"):
        distribution = json.loads((ROOT / "android" / module / "source-distribution.json").read_text())
        build = (ROOT / "android" / module / "native-build.json").read_bytes()
        assert sha(build) == distribution["pairedNativeBuildSha256"], module
        distributions.append({"module": module, **distribution})
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(selected):
            assert not path.is_symlink(), f"源码归档不接受符号链接: {path.relative_to(ROOT)}"
            relative = path.relative_to(ROOT).as_posix()
            data = path.read_bytes()
            info = zipfile.ZipInfo("Pathfin/" + relative, date_time=(2026, 10, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if path.name == "gradlew" else 0o100644) << 16
            archive.writestr(info, data)
            inventory.append({"path": relative, "bytes": len(data), "sha256": sha(data)})
        payload = json.dumps({"schemaVersion": 1, "files": inventory, "nativeSources": distributions,
            "note": "原生 .so 不在应用源码 ZIP 内；两份原生归档含原始上游源码、构建脚本及实际二进制哈希。"}, ensure_ascii=False, indent=2).encode()
        info = zipfile.ZipInfo("Pathfin/SOURCE_MANIFEST.json", date_time=(2026, 10, 26, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, payload)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        for entry in inventory:
            assert sha(archive.read("Pathfin/" + entry["path"])) == entry["sha256"]
    digest = sha(output.read_bytes())
    output.with_suffix(output.suffix + ".sha256").write_text(digest + "  " + output.name + "\n")
    return {"file": str(output), "files": len(inventory), "bytes": output.stat().st_size, "sha256": digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--native", choices=("ffmpeg", "ass"))
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))))
    args = parser.parse_args()
    assert not args.output.exists(), "拒绝覆盖现有源码归档，请显式选择新的输出路径"
    if args.native:
        sources = NativeSources(args.native, args.cache or ROOT / (".superpowers/tmp/" + args.native + "-native"), args.sdk)
        sources.verify()
        sources.notices()
        result = sources.bundle(args.output)
    else:
        assert args.cache is None, "--cache 仅用于原生源码打包"
        result = application_source(args.output)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

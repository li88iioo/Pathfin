#!/usr/bin/env python3
"""只读核对 APK 原生库、独立源码归档及锁定 Release AAR 的实际字节。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(archive, name):
    item = archive.getmember(name)
    assert item.isfile(), f"需要普通归档文件: {name}"
    with archive.extractfile(item) as stream:
        return stream.read()


def verify_module(module, source, apk, android, sdk, temporary):
    folder = android / module
    distribution = json.loads((folder / "source-distribution.json").read_text())
    assert source.name == distribution["archive"] and source.stat().st_size == distribution["bytes"], module
    assert sha(source.read_bytes()) == distribution["sha256"], module
    matches = []
    with tarfile.open(source) as archive:
        inventory_data = read(archive, "SOURCE-INVENTORY.json")
        assert sha(inventory_data) == distribution["sourceInventorySha256"], module
        inventory = json.loads(inventory_data)["files"]
        members = archive.getmembers()
        assert len({m.name for m in members}) == len(members), "原生归档不能含重复文件名"
        assert {m.name for m in members} == set(inventory) | {"SOURCE-INVENTORY.json"}, "原生归档与完整库存不同"
        for name, entry in inventory.items():
            data = read(archive, name)
            assert len(data) == entry["bytes"] and sha(data) == entry["sha256"], name
            if name.startswith("module/"):
                local = folder / name.removeprefix("module/")
                assert local.is_file() and local.read_bytes() == data, name
            asset_prefix = "module/src/main/assets/open-source-" + module + "/"
            if name.startswith(asset_prefix):
                assert apk.read("assets/open-source-" + module + "/" + name.removeprefix(asset_prefix)) == data, name
        build_bytes = read(archive, "module/native-build.json")
        lock_bytes = read(archive, "module/sources.lock.json")
        assert sha(build_bytes) == distribution["pairedNativeBuildSha256"], module
        assert sha(lock_bytes) == distribution["pairedSourceLockSha256"], module
        root = "assets/open-source-" + module + "/"
        assert apk.read(root + "native-build.json") == build_bytes, "APK 与对应源码的构建报告不同: " + module
        assert apk.read(root + "sources.lock.json") == lock_bytes, "APK 与对应源码的锁文件不同: " + module
        build = json.loads(build_bytes)
        lock = json.loads(lock_bytes)
        assert build["sourceLockSha256"] == sha(lock_bytes)
        assert build["recipeSha256"] == sha(read(archive, "module/build_native.py"))
        for item in lock.get("files", []) + lock.get("implementationFiles", []) + lock.get("copiedHeaders", []):
            assert sha(read(archive, "module/" + item["path"])) == item["sha256"], item["path"]
        for relative, digest in build.get("sourceInputs", {}).items():
            assert sha(read(archive, "module/" + relative)) == digest, relative
        assert distribution["lockedUpstreamArchives"] == len(lock["native"]["sources"])
        for item in lock["native"]["sources"]:
            assert sha(read(archive, "upstream/" + item["archive"])) == item["sha256"], item["archive"]
        assert read(archive, "tools/package_source.py") == (android / "tools/package_source.py").read_bytes()
        expected = {"libffmpegJNI.so", "libavcodec.so", "libavutil.so", "libswresample.so"} if module == "ffmpeg" else {"libpathfin_ass_jni.so", "libc++_shared.so"}
        assert {row["file"] for row in build["libraries"]} == expected, module
        if module == "ffmpeg":
            assert not build["audioOnly"] and not build["gpl"] and not build["nonfree"]
            assert build["decoders"] == lock["native"]["decoders"]
            assert build["videoDecoders"] == lock["native"]["videoDecoders"]
            assert build["cxxRuntime"] == "c++_static" and build["renderer"] == lock["native"]["renderer"]
            assert set(build["staticArchives"]) == {"libplacebo.a"}
            config = read(archive, "build-config/arm64-v8a/ffmpeg/config.h").decode()
            components = read(archive, "build-config/arm64-v8a/ffmpeg/config_components.h").decode()
            assert sha(config.encode()) == build["configSha256"]
            assert sha(components.encode()) == build["componentConfigSha256"]
            for feature in ("GPL", "NONFREE", "VERSION3", "LGPLV3", "AVFORMAT", "AVDEVICE", "AVFILTER", "SWSCALE", "NETWORK"):
                assert f"#define CONFIG_{feature} 0" in config, feature
            assert "#define CONFIG_DOVI_RPUDEC 1" in config
            assert set(re.findall(r"#define CONFIG_(\w+)_DECODER 1", components)) == {codec.upper() for codec in build["decoders"]}
            assert sha(read(archive, "build-config/android-arm64.ini")) == build["crossFileSha256"]
            placebo = read(archive, "build-config/arm64-v8a/libplacebo/src/include/libplacebo/config.h")
            assert sha(placebo) == build["libplaceboConfigSha256"]
            for feature, enabled in {"OPENGL": True, "DOVI": True, "VULKAN": False, "D3D11": False, "LIBDOVI": False, "GLSLANG": False, "SHADERC": False, "LCMS": False}.items():
                assert bool(re.search(r"^#define PL_HAVE_" + feature + r"(?:\s+1)?\s*$", placebo.decode(), re.M)) == enabled, feature
        strip = sdk / "ndk" / build["ndkVersion"] / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
        assert strip.is_file(), "需要锁定版本的 NDK llvm-strip 重现 AGP 剥离符号"
        destination = temporary / module
        destination.mkdir()
        for library in build["libraries"]:
            name = "lib/arm64-v8a/" + library["file"]
            original = (folder / "src/main/jniLibs/arm64-v8a" / library["file"]).read_bytes()
            assert len(original) == library["bytes"] and sha(original) == library["sha256"], name
            stripped = destination / library["file"]
            stripped.write_bytes(original)
            subprocess.run([strip, "--strip-unneeded", stripped], check=True, capture_output=True)
            data = apk.read(name)
            assert stripped.read_bytes() == data, f"{name} 与锁定库经同 NDK 剥离符号后的字节不同"
            matches.append({"file": name, "module": module, "sourceBuildSha256": library["sha256"],
                            "apkSha256": sha(data), "ndkStripMatched": True})
    return matches, {"module": module, "file": source.name, "bytes": source.stat().st_size, "sha256": sha(source.read_bytes())}


def verify_maven_libraries(names, apk, android, sdk, gradle_cache, temporary):
    if not names:
        return []
    # Compose 等 AAR 自带的原生库按实际 Release 产物配对，不冒充本地编译的库。
    metadata = apk.read("assets/open-source/runtime-artifacts.json")
    assert metadata == (android / "app/src/main/assets/open-source/runtime-artifacts.json").read_bytes()
    artifacts = json.loads(metadata)["artifacts"]
    ndk = json.loads((android / "ffmpeg/native-build.json").read_text())["ndkVersion"]
    strip = sdk / "ndk" / ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    destination = temporary / "maven"
    destination.mkdir()
    matched = {}
    for row in artifacts:
        if row["type"] != "aar":
            continue
        group, artifact, version = row["id"].split(":")
        assert all("/" not in value and value != ".." for value in (group, artifact, version))
        filename = row["fileName"]
        assert Path(filename).name == filename
        folder = gradle_cache / "caches/modules-2/files-2.1" / group / artifact / version
        candidates = [path for path in folder.rglob(filename) if sha(path.read_bytes()) == row["sha256"]]
        assert candidates, "本地缺少已锁定的 Release AAR: " + row["id"]
        with zipfile.ZipFile(candidates[0]) as dependency:
            for entry in dependency.namelist():
                if not entry.startswith("jni/") or not entry.endswith(".so"):
                    continue
                name = "lib/" + entry.removeprefix("jni/")
                if name not in names:
                    continue
                assert name not in matched, "原生库有重复的 AAR 来源: " + name
                original = dependency.read(entry)
                file = destination / name.removeprefix("lib/")
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(original)
                subprocess.run([strip, "--strip-unneeded", file], check=True, capture_output=True)
                data = apk.read(name)
                assert file.read_bytes() == data, "APK 与锁定 AAR 的原生库不同: " + name
                matched[name] = {"file": name, "module": row["id"], "artifactSha256": row["sha256"],
                                 "artifactEntry": entry, "artifactLibrarySha256": sha(original),
                                 "apkSha256": sha(data), "ndkStripMatched": True}
    assert set(matched) == names, "APK 存在没有源码归档或锁定 AAR 来源的原生库: " + str(sorted(names - set(matched)))
    return [matched[name] for name in sorted(matched)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--ffmpeg-source", required=True, type=Path)
    parser.add_argument("--ass-source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))))
    parser.add_argument("--gradle-cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
    args = parser.parse_args()
    android = Path(__file__).resolve().parents[1]
    matches = []
    archives = []
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory(prefix="pathfin-pairing-") as temporary:
        for module, source in (("ffmpeg", args.ffmpeg_source), ("ass", args.ass_source)):
            result, distribution = verify_module(module, source, apk, android, args.sdk, Path(temporary))
            matches.extend(result)
            archives.append(distribution)
        packaged = {name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        matches.extend(verify_maven_libraries(packaged - {row["file"] for row in matches}, apk, android,
                                               args.sdk, args.gradle_cache, Path(temporary)))
        assert packaged == {row["file"] for row in matches}, "APK 原生库必须全部有匹配构建及来源"
    report = {"apkSha256": sha(args.apk.read_bytes()), "nativeLibraries": matches, "archives": archives,
              "matchingSources": True, "allPackagedLibrariesPaired": True}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

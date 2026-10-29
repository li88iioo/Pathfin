#!/usr/bin/env python3
"""Build pinned FFmpeg audio/video decoders and a libplacebo GLES renderer; no Gradle/ADB."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EPOCH = 1792972800
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so", "libGLESv3.so"}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ascii_cache(path: Path) -> Path:
    physical = path.resolve()
    physical.mkdir(parents=True, exist_ok=True)
    if str(physical).isascii():
        return physical
    alias = Path(tempfile.gettempdir()) / ("pathfin-ffmpeg-" + hashlib.sha256(str(physical).encode()).hexdigest()[:12])
    if alias.is_symlink():
        if alias.resolve() != physical:
            raise ValueError("Native cache alias points elsewhere")
    elif alias.exists():
        raise ValueError("Native cache alias already exists")
    else:
        alias.symlink_to(physical, target_is_directory=True)
    return alias


def prepare_source(cache: Path, item: dict, archive_caches: list[Path]) -> Path:
    archive = cache / "downloads" / item["archive"]
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        cached = next((folder / item["archive"] for folder in archive_caches if (folder / item["archive"]).is_file()), None)
        if cached is not None:
            shutil.copyfile(cached, archive)
        else:
            with urllib.request.urlopen(item["url"], timeout=60) as response:
                archive.write_bytes(response.read())
    if archive.stat().st_size != item["bytes"] or sha(archive) != item["sha256"]:
        raise ValueError("Source archive hash mismatch: " + item["name"])
    source = cache / "src" / item.get("destination", item["name"])
    marker = source / ".pathfin-source-sha256"
    if marker.is_file() and marker.read_text().strip() == item["sha256"]:
        with tarfile.open(archive) as bundle:
            for member in bundle:
                if member.isfile():
                    relative = Path(*Path(member.name).parts[1:])
                    if (source / relative).read_bytes() != bundle.extractfile(member).read():
                        raise ValueError("Modified upstream source: " + item["name"] + "/" + str(relative))
        return source
    if source.exists() and any(source.iterdir()):
        raise ValueError("Unrecognized source directory: " + str(source))
    with tempfile.TemporaryDirectory(prefix="extract-", dir=cache) as temporary:
        with tarfile.open(archive) as bundle:
            prefix = bundle.getnames()[0].split("/")[0]
            bundle.extractall(temporary, filter="data")
        source.parent.mkdir(parents=True, exist_ok=True)
        if source.exists():
            source.rmdir()
        (Path(temporary) / prefix).rename(source)
    marker.write_text(item["sha256"] + "\n")
    return source


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))))
    parser.add_argument("--cache", type=Path, default=ROOT / ".superpowers/tmp/ffmpeg-native")
    parser.add_argument("--archive-cache", action="append", type=Path, default=[], help="Reuse verified source archives from a downloads directory; never reuse its binaries")
    parser.add_argument("--jobs", type=int, choices=range(1, 5), default=2)
    args = parser.parse_args()
    lock = json.loads((HERE / "sources.lock.json").read_text())
    for item in lock["files"] + lock.get("implementationFiles", []):
        if sha(HERE / item["path"]) != item["sha256"]:
            raise ValueError("Locked source hash mismatch: " + item["path"])
    input_paths = sorted([HERE / "CMakeLists.txt", HERE / "build.gradle.kts", HERE / "video-consumer-rules.pro",
        *HERE.glob("src/main/cpp/*"), *HERE.glob("src/main/java/**/*.java"), *HERE.glob("src/main/java/**/*.kt")])
    source_inputs = {str(path.relative_to(HERE)): sha(path) for path in input_paths if path.is_file()}
    native = lock["native"]
    cache = ascii_cache(args.cache)
    sources = {item["name"]: prepare_source(cache, item, args.archive_cache) for item in native["sources"]}
    source = sources["ffmpeg"]
    ndk = args.sdk.resolve() / "ndk" / native["ndkVersion"]
    if "Pkg.Revision = " + native["ndkVersion"] not in (ndk / "source.properties").read_text():
        raise ValueError("NDK version differs from the source lock")
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    prefix = cache / "prefix/arm64-v8a"
    build_root = cache / "build/arm64-v8a"
    build = build_root / "ffmpeg"
    build.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, SOURCE_DATE_EPOCH=str(EPOCH), LC_ALL="C.UTF-8")
    venv = cache / "venv"
    env["PATH"] = str(venv / "bin") + os.pathsep + str(toolchain) + os.pathsep + env["PATH"]
    env["PKG_CONFIG_LIBDIR"] = str(prefix / "lib/pkgconfig")
    env["PKG_CONFIG_PATH"] = str(prefix / "lib/pkgconfig")
    flags = "-O2 -fPIC -ffunction-sections -fdata-sections -ffile-prefix-map=" + str(cache) + "=/pathfin/native/ffmpeg"
    command = [source / "configure", "--prefix=" + str(prefix), "--libdir=" + str(prefix / "lib"),
        "--target-os=android", "--arch=aarch64", "--cpu=armv8-a", "--enable-cross-compile",
        "--cc=" + str(toolchain / "aarch64-linux-android26-clang"),
        "--cxx=" + str(toolchain / "aarch64-linux-android26-clang++"),
        "--ar=" + str(toolchain / "llvm-ar"), "--nm=" + str(toolchain / "llvm-nm"),
        "--ranlib=" + str(toolchain / "llvm-ranlib"), "--strip=" + str(toolchain / "llvm-strip"),
        "--extra-cflags=" + flags, "--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,--gc-sections",
        "--disable-gpl", "--disable-nonfree", "--disable-version3", "--disable-autodetect",
        "--disable-everything", "--disable-avformat", "--disable-avdevice", "--disable-avfilter",
        "--disable-swscale", "--disable-network", "--disable-programs", "--disable-doc",
        "--disable-debug", "--enable-shared", "--disable-static", "--enable-pic",
        "--enable-avcodec", "--enable-avutil", "--enable-swresample",
        "--enable-decoder=" + ",".join(native["decoders"])]
    logs = cache / "logs"
    logs.mkdir(exist_ok=True)
    def run(name, arguments, cwd=None):
        print("BUILD " + name, flush=True)
        with (logs / (name + ".log")).open("ab") as stream:
            stream.write(("\n$ " + " ".join(map(str, arguments)) + "\n").encode())
            stream.flush()
            result = subprocess.run(list(map(str, arguments)), cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            print("\n".join((logs / (name + ".log")).read_text(errors="replace").splitlines()[-60:]), flush=True)
            raise RuntimeError(name + " failed; see " + str(logs / (name + ".log")))
    run("ffmpeg-configure", command, build)
    run("ffmpeg", ["make", "-j" + str(args.jobs)], build)
    run("ffmpeg-install", ["make", "install"], build)
    config = (build / "config.h").read_text()
    for feature, value in {"GPL": 0, "NONFREE": 0, "VERSION3": 0, "LGPLV3": 0, "AVFORMAT": 0,
                           "AVDEVICE": 0, "AVFILTER": 0, "SWSCALE": 0, "NETWORK": 0}.items():
        if f"#define CONFIG_{feature} {value}" not in config:
            raise ValueError("Unexpected FFmpeg build policy: " + feature)
    components = (build / "config_components.h").read_text()
    enabled_decoders = sorted(re.findall(r"#define CONFIG_(\w+)_DECODER 1", components))
    if set(enabled_decoders) != {name.upper() for name in native["decoders"]}:
        raise ValueError("The compiled decoder set differs from the lock: " + str(enabled_decoders))
    if "#define CONFIG_DOVI_RPUDEC 1" not in config:
        raise ValueError("HEVC must retain its Dolby Vision RPU decoder")
    # libplacebo's header-only AVFrame adapter declares unused AVStream helpers.
    # Install only their public headers, without building or linking libavformat.
    headers = prefix / "include/libavformat"
    headers.mkdir(parents=True, exist_ok=True)
    header_names = ("avformat.h", "avio.h", "version.h", "version_major.h")
    for name in header_names:
        shutil.copy2(source / "libavformat" / name, headers / name)
    if not (venv / "bin/python").is_file():
        run("host-tools-venv", [sys.executable, "-m", "venv", venv])
    run("host-tools", [venv / "bin/python", "-m", "pip", "install", "--only-binary=:all:", "--no-deps",
        *[name + "==" + version for name, version in native["hostTools"].items()]])
    host_versions = json.loads(subprocess.check_output([venv / "bin/python", "-c",
        "import importlib.metadata,json; print(json.dumps({name:importlib.metadata.version(name) for name in " + repr(list(native["hostTools"])) + "}))"], text=True, env=env))
    if host_versions != native["hostTools"]:
        raise ValueError("Native host tools differ from the lock")
    crossfile = cache / "android-arm64.ini"
    crossfile.write_text("\n".join([
        "[binaries]", "c = '" + str(toolchain / "aarch64-linux-android26-clang") + "'",
        "cpp = '" + str(toolchain / "aarch64-linux-android26-clang++") + "'",
        "ar = '" + str(toolchain / "llvm-ar") + "'", "strip = '" + str(toolchain / "llvm-strip") + "'",
        "pkg-config = ['pkg-config', '--static']", "", "[host_machine]", "system = 'android'",
        "cpu_family = 'aarch64'", "cpu = 'armv8-a'", "endian = 'little'", "", "[properties]",
        "needs_exe_wrapper = true", "pkg_config_libdir = '" + str(prefix / "lib/pkgconfig") + "'", "",
        "[built-in options]", "c_args = " + repr(flags.split()), "cpp_args = " + repr(flags.split()),
        "c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--gc-sections']",
        "cpp_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,--gc-sections', '-static-libstdc++']", ""]))
    placebo_build = build_root / "libplacebo"
    meson = venv / "bin/meson"
    setup = [meson, "setup", placebo_build, sources["libplacebo"], "--cross-file", crossfile,
        "--prefix", prefix, "--libdir", "lib", "--buildtype", "release", "--default-library", "static",
        "--wrap-mode", "nofallback", "-Dbackend_max_links=1", "-Db_staticpic=true",
        "-Dvulkan=disabled", "-Dd3d11=disabled", "-Dopengl=enabled", "-Dgl-proc-addr=enabled",
        "-Ddovi=enabled", "-Dlibdovi=disabled", "-Dshaderc=disabled", "-Dglslang=disabled", "-Dlcms=disabled",
        "-Ddemos=false", "-Dtests=false", "-Dbench=false", "-Dfuzz=false", "-Dunwind=disabled", "-Dxxhash=disabled"]
    if (placebo_build / "meson-private/coredata.dat").exists():
        setup.append("--reconfigure")
    run("libplacebo-configure", setup)
    run("libplacebo", [meson, "compile", "-C", placebo_build, "-j", str(args.jobs)])
    run("libplacebo-install", [meson, "install", "-C", placebo_build, "--no-rebuild"])
    placebo_config = (prefix / "include/libplacebo/config.h").read_text()
    for feature, enabled in {"OPENGL": True, "DOVI": True, "VULKAN": False, "D3D11": False, "LIBDOVI": False, "GLSLANG": False, "SHADERC": False, "LCMS": False}.items():
        if bool(re.search(r"^#define PL_HAVE_" + feature + r"(?:\s+1)?\s*$", placebo_config, re.M)) != enabled:
            raise ValueError("Unexpected libplacebo build policy: " + feature)
    cmake = args.sdk / "cmake/3.22.1/bin/cmake"
    jni = HERE / "build-native/arm64-v8a"
    run("jni-configure", [cmake, "-S", HERE, "-B", jni, "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=" + str(args.sdk / "cmake/3.22.1/bin/ninja"),
        "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
        "-DCMAKE_BUILD_TYPE=Release", "-DFFMPEG_PREFIX=" + str(prefix), "-DPATHFIN_LIBPLACEBO_PREFIX=" + str(prefix),
        "-DCMAKE_C_FLAGS=-ffile-prefix-map=" + str(HERE) + "=/pathfin/ffmpeg",
        "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=" + str(HERE) + "=/pathfin/ffmpeg"])
    run("jni", [cmake, "--build", jni, "--parallel", str(args.jobs)])
    staged = HERE / "src/main/jniLibs/arm64-v8a"
    staged.mkdir(parents=True, exist_ok=True)
    inventory = []
    binaries = [prefix / "lib" / ("lib" + name + ".so") for name in ("avcodec", "avutil", "swresample")] + [jni / "libffmpegJNI.so"]
    names = {path.name for path in binaries}
    for path in binaries:
        target = staged / path.name
        shutil.copy2(path, target)
        subprocess.run([toolchain / "llvm-strip", "--strip-unneeded", target], check=True)
        segments = subprocess.check_output([toolchain / "llvm-readelf", "-lW", target], text=True)
        aligns = [int(line.split()[-1], 16) for line in segments.splitlines() if line.lstrip().startswith("LOAD ")]
        dynamic = subprocess.check_output([toolchain / "llvm-readelf", "-dW", target], text=True)
        needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
        if not aligns or min(aligns) < 16384 or set(needed) - names - SYSTEM_LIBS:
            raise ValueError("Native alignment/dependency closure failed: " + target.name)
        inventory.append({"file": target.name, "bytes": target.stat().st_size, "sha256": sha(target),
                          "loadAlignments": aligns, "needed": needed})
    for path in staged.glob("*.so"):
        if path.name not in names:
            path.unlink()
    if source_inputs != {relative: sha(HERE / relative) for relative in source_inputs}:
        raise ValueError("Module source changed during native build")
    report = {"schemaVersion": 1, "media3Version": lock["media3Version"], "commit": lock["commit"],
        "abi": native["abi"], "androidApi": native["androidApi"], "ndkVersion": native["ndkVersion"],
        "ffmpegCommit": next(item["commit"] for item in native["sources"] if item["name"] == "ffmpeg"), "ffmpegLicense": "LGPL-2.1-or-later",
        "gpl": False, "nonfree": False, "audioOnly": False, "sourceDateEpoch": EPOCH,
        "videoDecoders": native["videoDecoders"], "cxxRuntime": native["cxxRuntime"], "renderer": native["renderer"],
        "hostTools": host_versions, "staticArchives": {"libplacebo.a": sha(prefix / "lib/libplacebo.a")},
        "sourceInputs": source_inputs,
        "copiedAvformatHeaders": {name: sha(headers / name) for name in header_names},
        "libplaceboConfigSha256": sha(prefix / "include/libplacebo/config.h"),
        "crossFileSha256": sha(crossfile),
        "decoders": native["decoders"], "libraries": inventory,
        "configSha256": sha(build / "config.h"), "componentConfigSha256": sha(build / "config_components.h"),
        "recipeSha256": sha(HERE / "build_native.py"), "sourceLockSha256": sha(HERE / "sources.lock.json")}
    (HERE / "native-build.json").write_text(json.dumps(report, indent=2) + "\n")
    assets = HERE / "src/main/assets/open-source-ffmpeg"
    assets.mkdir(parents=True, exist_ok=True)
    for name in ("sources.lock.json", "native-build.json"):
        shutil.copy2(HERE / name, assets / name)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

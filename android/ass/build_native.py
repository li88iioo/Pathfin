#!/usr/bin/env python3
"""Build the pinned libass/font stack into the ASS JNI; no external media engine, Gradle or ADB."""
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
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libjnigraphics.so"}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "/home/aio/Android/Sdk"))))
    parser.add_argument("--cache", type=Path, default=ROOT / ".superpowers/tmp/ass-native")
    parser.add_argument("--jobs", type=int, choices=range(1, 5), default=2)
    args = parser.parse_args()
    lock = json.loads((HERE / "sources.lock.json").read_text())
    for item in lock["implementationFiles"] + lock["copiedHeaders"]:
        if sha(HERE / item["path"]) != item["sha256"]:
            raise ValueError("ASS implementation hash mismatch: " + item["path"])
    native = lock["native"]
    physical = args.cache.resolve()
    physical.mkdir(parents=True, exist_ok=True)
    cache = physical
    if not str(physical).isascii():
        cache = Path(tempfile.gettempdir()) / ("pathfin-ass-" + hashlib.sha256(str(physical).encode()).hexdigest()[:12])
        if cache.is_symlink():
            if cache.resolve() != physical:
                raise ValueError("Native cache alias points elsewhere")
        elif cache.exists():
            raise ValueError("Native cache alias already exists")
        else:
            cache.symlink_to(physical, target_is_directory=True)
    for name in ("downloads", "src", "logs", "build/arm64-v8a", "prefix/arm64-v8a"):
        (cache / name).mkdir(parents=True, exist_ok=True)
    sources = {}
    for item in native["sources"]:
        archive = cache / "downloads" / item["archive"]
        if not archive.is_file():
            with urllib.request.urlopen(item["url"], timeout=60) as response:
                archive.write_bytes(response.read())
        if sha(archive) != item["sha256"]:
            raise ValueError("Source archive hash mismatch: " + item["name"])
        source = cache / "src" / item["name"]
        marker = source / ".pathfin-source-sha256"
        if not marker.is_file() or marker.read_text().strip() != item["sha256"]:
            if source.exists():
                raise ValueError("Unrecognized source directory: " + str(source))
            with tempfile.TemporaryDirectory(prefix="extract-", dir=cache) as temporary:
                with tarfile.open(archive) as bundle:
                    prefix = bundle.getnames()[0].split("/")[0]
                    bundle.extractall(temporary, filter="data")
                (Path(temporary) / prefix).rename(source)
            marker.write_text(item["sha256"] + "\n")
        sources[item["name"]] = source
    ndk = args.sdk.resolve() / "ndk" / native["ndkVersion"]
    if "Pkg.Revision = " + native["ndkVersion"] not in (ndk / "source.properties").read_text():
        raise ValueError("NDK version differs from the source lock")
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    prefix = cache / "prefix/arm64-v8a"
    build = cache / "build/arm64-v8a"
    venv = cache / "venv"
    env = dict(os.environ, SOURCE_DATE_EPOCH=str(EPOCH), LC_ALL="C.UTF-8",
               PKG_CONFIG_LIBDIR=str(prefix / "lib/pkgconfig"), PKG_CONFIG_PATH=str(prefix / "lib/pkgconfig"))
    env["PATH"] = str(venv / "bin") + os.pathsep + str(toolchain) + os.pathsep + env["PATH"]
    def run(name, command, cwd=None, extra=None):
        print("BUILD " + name, flush=True)
        log = cache / "logs" / (name + ".log")
        with log.open("ab") as stream:
            stream.write(("\n$ " + " ".join(map(str, command)) + "\n").encode())
            stream.flush()
            result = subprocess.run(list(map(str, command)), cwd=cwd, env=env if extra is None else dict(env, **extra),
                                    stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            print("\n".join(log.read_text(errors="replace").splitlines()[-60:]), flush=True)
            raise RuntimeError(name + " failed; see " + str(log))
    if not (venv / "bin/python").is_file():
        run("host-tools", [sys.executable, "-m", "venv", venv])
    run("host-tools", [venv / "bin/python", "-m", "pip", "install", "-r", HERE / "requirements-build.txt"])
    cross = cache / "android-arm64.ini"
    cflags = ["-O2", "-fPIC", "-ffunction-sections", "-fdata-sections", "-ffile-prefix-map=" + str(cache) + "=/pathfin/native/ass"]
    ldflags = ["-Wl,-z,max-page-size=16384", "-Wl,--gc-sections", "-lc++_shared"]
    cross.write_text("\n".join([
        "[binaries]", "c = '" + str(toolchain / "aarch64-linux-android26-clang") + "'",
        "cpp = '" + str(toolchain / "aarch64-linux-android26-clang++") + "'",
        "ar = '" + str(toolchain / "llvm-ar") + "'", "strip = '" + str(toolchain / "llvm-strip") + "'",
        "pkg-config = ['pkg-config', '--static']", "", "[host_machine]", "system = 'android'",
        "cpu_family = 'aarch64'", "cpu = 'armv8-a'", "endian = 'little'", "", "[properties]",
        "needs_exe_wrapper = true", "pkg_config_libdir = '" + str(prefix / "lib/pkgconfig") + "'", "",
        "[built-in options]", "c_args = " + repr(cflags), "cpp_args = " + repr(cflags),
        "c_link_args = " + repr(ldflags), "cpp_link_args = " + repr(ldflags), ""]))
    options = {
        "freetype": ["-Dharfbuzz=disabled", "-Dbrotli=disabled", "-Dbzip2=disabled", "-Dpng=disabled", "-Dzlib=disabled", "-Dtests=disabled"],
        "fribidi": ["-Ddocs=false", "-Dbin=false", "-Dtests=false"],
        "harfbuzz": ["-Dglib=disabled", "-Dgobject=disabled", "-Dcairo=disabled", "-Dchafa=disabled", "-Dpng=disabled", "-Dzlib=disabled", "-Dicu=disabled", "-Dgraphite2=disabled", "-Dfreetype=disabled", "-Dintrospection=disabled", "-Dtests=disabled", "-Ddocs=disabled", "-Dutilities=disabled", "-Dsubset=disabled", "-Draster=disabled", "-Dvector=disabled", "-Dgpu=disabled"],
        "libass": ["-Dfontconfig=disabled", "-Ddirectwrite=disabled", "-Dcoretext=disabled", "-Drequire-system-font-provider=false", "-Dlibunibreak=enabled", "-Dtest=disabled", "-Dcompare=disabled", "-Dprofile=disabled", "-Dfuzz=disabled", "-Dcheckasm=disabled"],
    }
    for name in ("freetype", "fribidi", "harfbuzz", "unibreak", "libass"):
        directory = build / name
        if name == "unibreak":
            if not (sources[name] / "configure").is_file():
                run(name, ["autoreconf", "-fi"], sources[name])
            directory.mkdir(exist_ok=True)
            extra = {"CC": str(toolchain / "aarch64-linux-android26-clang"), "CXX": str(toolchain / "aarch64-linux-android26-clang++"),
                     "AR": str(toolchain / "llvm-ar"), "RANLIB": str(toolchain / "llvm-ranlib"),
                     "CFLAGS": " ".join(cflags), "LDFLAGS": " ".join(ldflags)}
            run(name, [sources[name] / "configure", "--prefix=" + str(prefix), "--host=aarch64-linux-android",
                "--enable-static", "--disable-shared", "--with-pic"], directory, extra)
            run(name, ["make", "-j" + str(args.jobs)], directory, extra)
            run(name, ["make", "install"], directory, extra)
        else:
            command = ["meson", "setup", directory, sources[name], "--cross-file", cross,
                "--prefix", prefix, "--libdir", "lib", "--buildtype", "release", "--default-library", "static",
                "--wrap-mode", "nofallback", "-Dbackend_max_links=1", "-Db_staticpic=true"] + options[name]
            if (directory / "meson-private/coredata.dat").exists():
                command.append("--reconfigure")
            run(name, command)
            run(name, ["meson", "compile", "-C", directory, "-j", str(args.jobs)])
            run(name, ["meson", "install", "-C", directory, "--no-rebuild"])
    cmake = args.sdk / "cmake/3.22.1/bin/cmake"
    jni = HERE / "build-native/arm64-v8a"
    run("jni-configure", [cmake, "-S", HERE, "-B", jni, "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=" + str(args.sdk / "cmake/3.22.1/bin/ninja"),
        "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release", "-DLIBASS_PREFIX=" + str(prefix),
        "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=" + str(HERE) + "=/pathfin/ass"])
    run("jni", [cmake, "--build", jni, "--parallel", str(args.jobs)])
    destination = HERE / "src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    libraries = [jni / "libpathfin_ass_jni.so", toolchain.parent / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"]
    names = {p.name for p in libraries}
    inventory = []
    for library in libraries:
        target = destination / library.name
        shutil.copy2(library, target)
        subprocess.run([toolchain / "llvm-strip", "--strip-unneeded", target], check=True)
        headers = subprocess.check_output([toolchain / "llvm-readelf", "-lW", target], text=True)
        aligns = [int(line.split()[-1], 16) for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
        dynamic = subprocess.check_output([toolchain / "llvm-readelf", "-dW", target], text=True)
        needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
        if not aligns or min(aligns) < 16384 or set(needed) - names - SYSTEM_LIBS:
            raise ValueError("Native alignment/dependency closure failed: " + target.name)
        inventory.append({"file": target.name, "bytes": target.stat().st_size, "sha256": sha(target),
                          "loadAlignments": aligns, "needed": needed})
    for path in destination.glob("*.so"):
        if path.name not in names:
            path.unlink()
    inputs = {str(p.relative_to(HERE)): sha(p) for p in HERE.rglob("*") if p.is_file() and
        not set(p.relative_to(HERE).parts) & {"build", "build-native", "jniLibs", "__pycache__"} and
        (p.suffix in {".cpp", ".h", ".kt"} or p.name in {"CMakeLists.txt", "build_native.py", "build.gradle.kts", "sources.lock.json", "requirements-build.txt"})}
    report = {"schemaVersion": 1, "ndkVersion": native["ndkVersion"], "abi": native["abi"], "androidApi": native["androidApi"],
        "sourceDateEpoch": EPOCH, "libraries": inventory, "sourceInputs": inputs,
        "staticArchives": {p.name: sha(p) for p in sorted((prefix / "lib").glob("*.a"))},
        "recipeSha256": sha(HERE / "build_native.py"), "sourceLockSha256": sha(HERE / "sources.lock.json")}
    (HERE / "native-build.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

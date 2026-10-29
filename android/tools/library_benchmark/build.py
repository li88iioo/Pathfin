#!/usr/bin/env python3
"""构建最小公开窗口 bridge，以现有 debug.keystore 同签 verification release；不安装、不用生产密钥。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


SOURCE = Path(__file__).resolve().parent
TARGET = "dev.pathfin.android.verification"


def checked(command):
    result = subprocess.run([str(value) for value in command], capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(Path(str(command[0])).name + " failed: " + (result.stdout + result.stderr)[-4000:])
    return result.stdout


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def payload(path):
    with zipfile.ZipFile(path) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                if not re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I)}


def certificate(apksigner, path):
    result = checked([apksigner, "verify", "--print-certs", path])
    values = set(re.findall(r"(?:Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest: ([a-f0-9]{64})", result))
    if len(values) != 1:
        raise RuntimeError("APK 签名证书不唯一")
    return values.pop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target_apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=Path.home() / "Android/Sdk")
    parser.add_argument("--debug-keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    args = parser.parse_args()
    apk = args.target_apk.resolve(strict=True)
    keystore = args.debug_keystore.resolve(strict=True)
    output = args.output.resolve()
    if output == SOURCE or SOURCE in output.parents:
        parser.error("输出必须为源码目录之外的临时目录")
    tools = args.sdk / "build-tools/37.0.0"
    android_jar = args.sdk / "platforms/android-37.0/android.jar"
    manifest = checked([tools / "aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml"])
    package = re.search(r'^\s+A: package="([^"]+)"', manifest, re.M)
    if not package or package[1] != TARGET or re.search(r':(?:debuggable|testOnly)\([^)]*\)=(?:true|0xffffffff)', manifest):
        parser.error("只接受非 debuggable/testOnly 的 verification release，不能改签日常包")
    javac = shutil.which("javac")
    if not javac:
        parser.error("缺少 javac")
    output.mkdir(parents=True, exist_ok=False)
    classes, dex = output / "classes", output / "dex"
    classes.mkdir(); dex.mkdir()
    shared = SOURCE.parent / "production_smoke/src/dev/pathfin/smoke/TargetViewStructure.java"
    checked([javac, "--release", "8", "-encoding", "UTF-8", "-classpath", android_jar, "-d", classes,
             shared, SOURCE / "src/dev/pathfin/smoke/LibraryBenchmarkInstrumentation.java"])
    checked([tools / "d8", "--release", "--min-api", "29", "--lib", android_jar,
             "--output", dex, *sorted(classes.rglob("*.class"))])
    unsigned, aligned = output / "bridge-unsigned.apk", output / "bridge-aligned.apk"
    checked([tools / "aapt2", "link", "-I", android_jar, "--manifest", SOURCE / "AndroidManifest.xml",
             "--min-sdk-version", "29", "--target-sdk-version", "37", "-o", unsigned])
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in dex.glob("*.dex"):
            archive.write(file, file.name)
    checked([tools / "zipalign", "-f", "4", unsigned, aligned])
    bridge, target = output / "library-benchmark-bridge.apk", output / "verification-release.apk"
    for original, destination in ((aligned, bridge), (apk, target)):
        checked([tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
                 "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", destination, original])
    digest = certificate(tools / "apksigner", bridge)
    if certificate(tools / "apksigner", target) != digest or payload(apk) != payload(target):
        raise RuntimeError("同签或原始 release payload 不变校验失败")
    checked([tools / "zipalign", "-c", "-P", "16", "4", target])
    result = {"targetPackage": TARGET, "bridgePackage": TARGET + ".test", "inputApkSha256": sha(apk),
              "targetApk": str(target), "targetSha256": sha(target), "bridgeApk": str(bridge),
              "bridgeSha256": sha(bridge), "debugCertificateSha256": digest,
              "unchangedPayloadEntries": len(payload(apk)), "deviceRun": False}
    (output / "build-report.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""不使用 Gradle 构建平台 Instrumentation；签名可由维护者显式请求。

密码只取 PATHFIN_SMOKE_STORE_PASSWORD / PATHFIN_SMOKE_KEY_PASSWORD 环境变量。
不会安装、启动、卸载或清理任何应用；不会读取 release.properties。
"""

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
import zipfile


SOURCE = Path(__file__).resolve().parent
REPOSITORY = SOURCE.parents[2]
TARGET = "dev.pathfin.android"


def checked(command: list[str | Path], *, timeout: int = 90) -> str:
    result = subprocess.run([str(value) for value in command], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # 命令中没有密码；不回显进程环境、keystore 属性文件或用户路径参数。
        output = (result.stdout + "\n" + result.stderr)[-5000:]
        raise RuntimeError(f"{Path(str(command[0])).name} failed ({result.returncode}):\n{output}")
    return result.stdout


def certificate(apksigner: Path, apk: Path) -> str:
    output = checked([apksigner, "verify", "--verbose", "--print-certs", apk])
    digests = set(re.findall(r"(?:Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest: ([a-f0-9]{64})", output))
    if "Number of signers: 1" not in output or len(digests) != 1:
        raise RuntimeError("APK 必须有且仅有一个可验证的签名证书")
    return digests.pop()


def verify_target(aapt2: Path, apksigner: Path, apk: Path) -> dict:
    manifest = checked([aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"])
    package = re.search(r'^\s+A: package="([^"]+)"', manifest, re.MULTILINE)
    version = re.search(r':versionName\([^)]*\)="([^"]+)"', manifest)
    code = re.search(r':versionCode\([^)]*\)=(0x[0-9a-fA-F]+|[0-9]+)', manifest)
    if not package or package.group(1) != TARGET:
        raise RuntimeError("只允许目标正式包 dev.pathfin.android，禁止 .debug/.verification 或第三方包")
    if not version or not version.group(1).strip() or not code or int(code.group(1), 0) <= 0:
        raise RuntimeError("目标 APK 必须有有效的 versionName/versionCode")
    if re.search(r':(?:debuggable|testOnly)\([^)]*\)=(?:true|0xffffffff)', manifest):
        raise RuntimeError("目标 APK 不得 debuggable/testOnly")
    with zipfile.ZipFile(apk) as archive:
        required = {"lib/arm64-v8a/libffmpegJNI.so", "lib/arm64-v8a/libpathfin_ass_jni.so"}
        if not required.issubset(archive.namelist()):
            raise RuntimeError("目标 APK 缺少 ExoPlayer 的 ARM64 音频或字幕扩展")
    return {"versionCode": int(code.group(1), 0), "versionName": version.group(1),
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "certificateSha256": certificate(apksigner, apk)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "~/Android/Sdk"))).expanduser())
    parser.add_argument("--build-tools", type=Path, help="明确的 SDK build-tools/37.0.0 目录")
    parser.add_argument("--android-jar", type=Path, help="默认 SDK platforms/android-37.0/android.jar")
    parser.add_argument("--output", type=Path, default=REPOSITORY / ".superpowers/tmp/production-smoke")
    parser.add_argument("--target-apk", type=Path, required=True, help="待验收正式 APK；工具绑定它的版本、SHA-256 与证书")
    parser.add_argument("--keystore", type=Path, help="显式签名；必须是仓库外现有生产 keystore")
    parser.add_argument("--key-alias", help="生产 key alias；不在此工具里生成或复制密钥")
    args = parser.parse_args()
    tools = (args.build_tools or args.sdk / "build-tools/37.0.0").expanduser().resolve()
    android_jar = (args.android_jar or args.sdk / "platforms/android-37.0/android.jar").expanduser().resolve(strict=True)
    output = args.output.expanduser().resolve()
    if SOURCE == output or SOURCE in output.parents or output == REPOSITORY:
        parser.error("构建输出必须使用独立临时目录，不得覆盖工具源码或仓库根目录")
    for executable in ("aapt2", "d8", "zipalign", "apksigner"):
        if not (tools / executable).is_file():
            parser.error(f"缺少 {executable}；请明确 --build-tools")
    javac = shutil.which("javac")
    if not javac:
        parser.error("需要 JDK17 的 javac")
    signing = args.keystore is not None
    if signing != bool(args.key_alias) or signing and args.target_apk is None:
        parser.error("签名需同时提供 --keystore、--key-alias 和 --target-apk")
    if signing:
        keystore = args.keystore.expanduser().resolve(strict=True)
        if keystore == REPOSITORY or REPOSITORY in keystore.parents:
            parser.error("keystore 必须位于仓库之外")
        for variable in ("PATHFIN_SMOKE_STORE_PASSWORD", "PATHFIN_SMOKE_KEY_PASSWORD"):
            if not os.environ.get(variable):
                parser.error(f"签名密码必须通过 {variable} 环境变量提供")
    target_apk = args.target_apk.expanduser().resolve(strict=True)
    target = verify_target(tools / "aapt2", tools / "apksigner", target_apk)
    output.mkdir(parents=True, exist_ok=True)
    # 只删除本工具拥有且明确命名的编译子目录，不进行目录级 rmtree。
    classes, dex = output / "classes", output / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    for root, pattern in ((classes, "*.class"), (dex, "*.dex")):
        for stale in root.rglob(pattern):
            stale.unlink()
    sources = sorted((SOURCE / "src").rglob("*.java"))
    # 绑定本次明确选择的完整 APK；安装同版本号的另一个构建也不能误报已验收。
    binding = output / "SmokeTarget.java"
    binding.write_text("package dev.pathfin.smoke;\nfinal class SmokeTarget {\n"
                       f"    static final long VERSION_CODE = {target['versionCode']}L;\n"
                       f"    static final String VERSION_NAME = {json.dumps(target['versionName'])};\n"
                       f"    static final String SHA256 = {json.dumps(target['sha256'])};\n"
                       "}\n", encoding="utf-8")
    sources.append(binding)
    checked([javac, "--release", "8", "-encoding", "UTF-8", "-classpath", android_jar, "-d", classes, *sources])
    class_files = sorted(classes.rglob("*.class"))
    checked([tools / "d8", "--release", "--min-api", "26", "--lib", android_jar, "--output", dex, *class_files])
    unsigned = output / "production-smoke-unsigned.apk"
    aligned = output / "production-smoke-aligned.apk"
    checked([tools / "aapt2", "link", "-I", android_jar, "--manifest", SOURCE / "AndroidManifest.xml",
             "--min-sdk-version", "26", "--target-sdk-version", "37", "--version-code", "1", "--version-name", "0.1",
             "-o", unsigned])
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(dex.glob("*.dex")):
            archive.write(file, file.name)
    checked([tools / "zipalign", "-f", "4", unsigned, aligned])
    artifact = aligned
    if signing:
        artifact = output / "production-smoke-signed.apk"
        checked([tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", args.key_alias,
                 "--ks-pass", "env:PATHFIN_SMOKE_STORE_PASSWORD", "--key-pass", "env:PATHFIN_SMOKE_KEY_PASSWORD",
                 "--out", artifact, aligned])
        if certificate(tools / "apksigner", artifact) != target["certificateSha256"]:
            artifact.unlink(missing_ok=True)
            raise RuntimeError("工具与目标正式 APK 证书不同；已删除不可用的已签工具 APK")
    checked([tools / "zipalign", "-c", "4", artifact])
    manifest = checked([tools / "aapt2", "dump", "xmltree", artifact, "--file", "AndroidManifest.xml"])
    if not re.search(r':targetPackage\([^)]*\)="dev\.pathfin\.android"', manifest):
        raise RuntimeError("构建的 Instrumentation 目标不符合硬编码限制")
    result = {"toolPackage": "dev.pathfin.android.production.smoke", "targetPackage": TARGET,
              "target": target, "artifact": str(artifact), "bytes": artifact.stat().st_size,
              "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "signed": signing,
              "sameCertificateVerified": signing, "deviceRun": "not run by build.py"}
    (output / "build-report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"production smoke 构建失败：{error}", file=sys.stderr)
        raise SystemExit(1)

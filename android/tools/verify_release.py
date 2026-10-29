#!/usr/bin/env python3
"""只读检查正式 APK 的身份、签名、16KiB、离线许可和测试证书隔离。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def checked(arguments):
    result = subprocess.run([str(value) for value in arguments], capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(f"{Path(str(arguments[0])).name} failed: {result.stdout[-2000:]} {result.stderr[-2000:]}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--expected-certificate-sha256", required=True, help="已确认的生产签名证书 SHA-256，不能从待验 APK 自行推定")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    expected_certificate = args.expected_certificate_sha256.replace(":", "").lower()
    if not re.fullmatch(r"[a-f0-9]{64}", expected_certificate):
        parser.error("--expected-certificate-sha256 必须为完整 SHA-256 摘要")
    apk = args.apk.resolve(strict=True)
    # badging 会尝试解析框架自带图标，在没有 framework 资源表时误报；直接读最终二进制 Manifest。
    manifest = checked([args.build_tools / "aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml"])
    package = re.search(r'^\s+A: package="([^"]+)"', manifest, re.MULTILINE)
    version = re.search(r':versionName\([^)]*\)="([^"]+)"', manifest)
    def integer_attribute(name):
        found = re.search(rf':{name}\([^)]*\)=(0x[0-9a-fA-F]+|[0-9]+)', manifest)
        assert found, f"Manifest 缺少 {name}"
        return int(found.group(1), 0)
    assert package and package.group(1) == "dev.pathfin.android", "不是正式 Pathfin applicationId"
    assert version, "Manifest 缺少版本"
    assert not re.search(r':(?:debuggable|testOnly)\([^)]*\)=(?:true|0xffffffff)', manifest), "正式 APK 不得 debuggable/testOnly"
    assert integer_attribute("minSdkVersion") == 26 and integer_attribute("targetSdkVersion") == 37, "SDK 声明与本版支持范围不符"
    signature = checked([args.build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk])
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    certificates = set(re.findall(r"(?:Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest: ([a-f0-9]{64})", signature))
    assert "Number of signers: 1" in signature and len(certificates) == 1, "必须只有一个可验证发布签名证书"
    certificate = next(iter(certificates))
    assert certificate == expected_certificate, "APK 签名与预期生产证书不一致"
    checked([args.build_tools / "zipalign", "-c", "-P", "16", "4", apk])
    source = Path(__file__).resolve().parents[1]
    fixture_ca = (source / "app/src/verification/res/raw/pathfin_fixture_ca.pem").read_bytes()
    report = {"package": package.group(1), "versionCode": integer_attribute("versionCode"), "versionName": version.group(1),
              "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size,
              "certificateSha256": certificate, "expectedCertificateMatched": True, "debuggable": False, "zipAligned16KiB": True,
              "nativeLibraries": [], "offlineComponents": 0, "offlineTexts": 0, "testCaAbsent": True, "removedSyncEndpointsAbsent": integer_attribute("versionCode") >= 12, "removedDanmakuEndpointsAbsent": True,
              "removedDoubanIntegrationAbsent": True, "removedMpvAbsent": True, "nativeDependencies": {}}
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="pathfin-release-check-") as temporary:
        names = archive.namelist()
        fixture_roots = ("playback-video/", "ass-extension/", "continuous-tracks/", "ffmpeg-audio/",
                         "native-video/", "external-video/", "hevc-perf/", "multichannel-audio/")
        assert not any("anime4k" in name.lower() for name in names), "已移除的画质增强资源不得进入正式 APK"
        assert not any(name.startswith("assets/open-source-mpv/") for name in names), "已移除的内核资产不得进入正式 APK"
        assert not any("verification_network_security" in name or "pathfin_fixture_ca" in name or
                       any(name.startswith("assets/" + root) for root in fixture_roots) for name in names), "测试资源不得进入正式 APK"
        for name in names:
            data = archive.read(name)
            assert fixture_ca not in data, "测试 CA 不得进入正式 APK"
            if name.endswith(".dex"):
                assert not any(endpoint in data for endpoint in (b"api.dandanplay.net", b"danmaku-api.152468.xyz")), "已移除的弹幕服务端点不得进入正式 APK"
                assert not any(value in data for value in (b"movie.douban.com", b"requestDoubanRatings", b"DoubanRatingApi", b"DoubanRatingRow")), "已移除的豆瓣入口、偏好及请求实现不得进入正式 APK"
                assert b"Ldev/pathfin/android/VerificationDisplay;" not in data, "测试辅助类不得进入正式 APK"
                if report["versionCode"] >= 12:
                    assert not any(endpoint in data for endpoint in (b"auth.trakt.tv", b"api.trakt.tv")), "已移除的同步服务端点不得进入正式 APK"
            if name.startswith("lib/") and name.endswith(".so"):
                binary = Path(temporary) / Path(name).name
                binary.write_bytes(archive.read(name))
                headers = checked(["readelf", "--program-headers", "--wide", binary])
                segments = [line.split() for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
                assert segments and all(int(segment[-1], 16) >= 16384 for segment in segments), f"{name} ELF 不支持16KiB"
                dynamic = checked(["readelf", "--dynamic", "--wide", binary])
                report["nativeDependencies"][name] = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
                report["nativeLibraries"].append(name)
        required = {"libffmpegJNI.so", "libpathfin_ass_jni.so", "libavcodec.so", "libavutil.so", "libswresample.so", "libc++_shared.so"}
        packaged_arm64 = {Path(name).name for name in report["nativeLibraries"] if name.startswith("lib/arm64-v8a/")}
        assert required <= packaged_arm64, "缺少 ARM64 音频或字幕依赖"
        removed = {"libmpv.so", "libpathfin_mpv_jni.so", "libavformat.so", "libavfilter.so", "libavdevice.so", "libswscale.so"}
        assert not {Path(name).name for name in report["nativeLibraries"]} & removed, "正式 APK 残留已移除的播放器库"
        system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libjnigraphics.so", "libz.so",
                  "libEGL.so", "libGLESv3.so"}
        for name, needed in report["nativeDependencies"].items():
            abi = Path(name).parts[1]
            packaged = {Path(item).name for item in report["nativeLibraries"] if Path(item).parts[1] == abi}
            assert not set(needed) - packaged - system, f"{name} 原生依赖未闭合"
        ids = set()
        for root in ("open-source", "open-source-ass", "open-source-ffmpeg", "open-source-artwork"):
            manifest = json.loads(archive.read(f"assets/{root}/manifest.json"))
            assert manifest["schemaVersion"] == 1 and not manifest["unresolved"]
            assert manifest["componentCount"] == len(manifest["components"])
            for component in manifest["components"]:
                assert component["id"] not in ids
                ids.add(component["id"])
                assert component["texts"], f"缺少许可全文：{component['id']}"
                for text in component["texts"]:
                    path = text["path"]
                    assert re.fullmatch(r"texts/[a-f0-9]{64}\.txt", path)
                    assert hashlib.sha256(archive.read(f"assets/{root}/{path}")).hexdigest() == text["sha256"]
                    report["offlineTexts"] += 1
            report["offlineComponents"] += len(manifest["components"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

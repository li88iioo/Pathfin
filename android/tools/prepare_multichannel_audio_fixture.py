#!/usr/bin/env python3
"""用本机 FFmpeg 合成逐声道独立正弦波，核对完整 PCM；不下载或读取用户媒体。"""

from array import array
from dataclasses import dataclass
from pathlib import Path
import argparse
import hashlib
import json
import math
import shutil
import struct
import subprocess
import sys
import tempfile


@dataclass(frozen=True)
class Fixture:
    name: str
    codec: str
    muxer: str
    mime: str
    rate: int
    layout: str
    channels: tuple[str, ...]
    bitrate: str | None = None


SIX = ("FL", "FR", "FC", "LFE", "SL", "SR")
EIGHT = ("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
FREQUENCIES = (220, 330, 440, 55, 550, 660, 770, 880)
FIXTURES = (
    Fixture("ac3-5_1-48k", "ac3", "ac3", "audio/ac3", 48_000, "5.1(side)", SIX, "640k"),
    Fixture("eac3-5_1-48k", "eac3", "eac3", "audio/eac3", 48_000, "5.1(side)", SIX, "640k"),
    Fixture("dts-5_1-48k", "dca", "dts", "audio/vnd.dts", 48_000, "5.1(side)", SIX, "1536k"),
    Fixture("truehd-5_1-48k", "truehd", "truehd", "audio/true-hd", 48_000, "5.1(side)", SIX),
    Fixture("truehd-5_1-96k", "truehd", "truehd", "audio/true-hd", 96_000, "5.1(side)", SIX),
    Fixture("flac-7_1-48k", "flac", "flac", "audio/flac", 48_000, "7.1", EIGHT),
    Fixture("flac-7_1-96k", "flac", "flac", "audio/flac", 96_000, "7.1", EIGHT),
)


def run(command: list[str], *, data: bytes | None = None) -> bytes:
    result = subprocess.run(command, input=data, capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed ({result.returncode}):\n{result.stderr.decode(errors='replace')}")
    return result.stdout


def describe(path: Path) -> dict:
    content = path.read_bytes()
    return {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}


def channel_evidence(pcm: bytes, fixture: Fixture) -> tuple[int, list[dict]]:
    values = array("h")
    values.frombytes(pcm)
    if sys.byteorder != "little":
        values.byteswap()
    channels = len(fixture.channels)
    assert len(values) % channels == 0
    frames = len(values) // channels
    assert fixture.rate * 2 <= frames <= fixture.rate * 2.1, (fixture.name, frames)
    result = []
    for index, label in enumerate(fixture.channels):
        samples = values[index::channels]
        peak = max(abs(value) for value in samples)
        rms = math.sqrt(sum(value * value for value in samples) / frames)
        frequency = FREQUENCIES[index]
        # 去掉编码器起始延迟，用一整秒核对该声道独有的频率；LFE 为 55 Hz。
        window = samples[fixture.rate // 4:fixture.rate // 4 + fixture.rate]
        sine = sum(value * math.sin(2 * math.pi * frequency * frame / fixture.rate)
                   for frame, value in enumerate(window))
        cosine = sum(value * math.cos(2 * math.pi * frequency * frame / fixture.rate)
                     for frame, value in enumerate(window))
        coherence = math.hypot(sine, cosine) / len(window) / rms
        assert peak > 1_000 and rms > 500, (fixture.name, label, peak, rms)
        assert coherence > 0.45, (fixture.name, label, frequency, coherence)
        result.append({"label": label, "frequencyHz": frequency, "peakS16": peak,
                       "rmsS16": round(rms, 4), "frequencyCoherence": round(coherence, 6)})
    return frames, result


def generate(directory: Path, fixture: Fixture, ffmpeg: str, ffprobe: str) -> dict:
    raw = directory / f"{fixture.name}.raw"
    expression = "|".join(f"0.12*sin(2*PI*{frequency}*t)"
                          for frequency in FREQUENCIES[:len(fixture.channels)])
    source = f"aevalsrc={expression}:s={fixture.rate}:d=2:c={fixture.layout}"
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", source,
               "-af", "aformat=sample_fmts=s16", "-map", "0:a:0", "-c:a", fixture.codec,
               "-threads", "1", "-strict", "-2"]
    if fixture.codec in ("truehd", "flac"):
        command += ["-sample_fmt", "s16p" if fixture.codec == "truehd" else "s16"]
    if fixture.bitrate:
        command += ["-b:a", fixture.bitrate]
    run(command + ["-fflags", "+bitexact", "-f", fixture.muxer, str(raw)])
    probe = json.loads(run([ffprobe, "-v", "error", "-show_packets", "-show_streams", "-of", "json", str(raw)]))
    stream = probe["streams"][0]
    assert int(stream["sample_rate"]) == fixture.rate, (fixture.name, stream)
    assert stream["channels"] == len(fixture.channels), (fixture.name, stream)
    assert stream["codec_name"] == ("dts" if fixture.codec == "dca" else fixture.codec)
    encoded = raw.read_bytes()
    framed = directory / f"{fixture.name}.packets"
    packets = bytearray()
    for packet in probe["packets"]:
        start, length = int(packet["pos"]), int(packet["size"])
        body = encoded[start:start + length]
        assert len(body) == length and length > 0
        time_us = round(float(packet.get("pts_time", 0)) * 1_000_000)
        packets += struct.pack("<Iq", length, time_us) + body
    framed.write_bytes(packets)
    container = directory / f"{fixture.name}.mka"
    run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(raw), "-map", "0:a:0", "-c", "copy",
         "-fflags", "+bitexact", "-map_metadata", "-1", str(container)])
    container_probe = json.loads(run([ffprobe, "-v", "error", "-show_streams", "-of", "json", str(container)]))["streams"][0]
    assert int(container_probe["sample_rate"]) == fixture.rate
    assert container_probe["channels"] == len(fixture.channels)
    pcm = run([ffmpeg, "-v", "error", "-i", str(container), "-map", "0:a:0", "-c:a", "pcm_s16le", "-f", "s16le", "-"])
    frames, evidence = channel_evidence(pcm, fixture)
    files = [framed, container]
    initialization = None
    if fixture.codec == "flac":
        # Media3 提供包含 marker + STREAMINFO metadata 的初始化数据，JNI 从中提取 34 字节。
        assert encoded[:4] == b"fLaC" and encoded[4] & 0x7f == 0 and int.from_bytes(encoded[5:8], "big") == 34
        init = directory / f"{fixture.name}.init"
        init.write_bytes(encoded[:42])
        files.append(init)
        initialization = init.name
    return {"id": fixture.name, "decoder": fixture.codec, "mime": fixture.mime, "sampleRate": fixture.rate,
            "channels": len(fixture.channels), "channelLayout": stream.get("channel_layout"),
            "sourceDurationMs": 2_000, "packets": len(probe["packets"]), "hostDecodedFrames": frames,
            "hostDecodedPcmBytes": len(pcm), "channelSignals": evidence, "initialization": initialization,
            "files": {path.name: describe(path) for path in files}}


def verify(directory: Path) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text())
    assert manifest["schemaVersion"] == 1
    assert [item["id"] for item in manifest["fixtures"]] == [item.name for item in FIXTURES]
    for item in manifest["fixtures"]:
        for name, expected in item["files"].items():
            assert describe(directory / name) == expected, f"夹具损坏或 manifest 过期：{name}"
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] /
                        "android/app/src/androidTest/assets/multichannel-audio")
    parser.add_argument("--verify", action="store_true", help="只校验现有夹具大小、SHA-256 和矩阵")
    arguments = parser.parse_args()
    directory = arguments.output.resolve()
    if arguments.verify:
        manifest = verify(directory)
    else:
        ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
        if not ffmpeg or not ffprobe:
            raise SystemExit("需要本机 ffmpeg 和 ffprobe；不会下载工具或媒体")
        with tempfile.TemporaryDirectory(prefix="pathfin-multichannel-") as temporary:
            staging = Path(temporary)
            fixtures = [generate(staging, fixture, ffmpeg, ffprobe) for fixture in FIXTURES]
            manifest = {"schemaVersion": 1, "generator": "android/tools/prepare_multichannel_audio_fixture.py",
                        "ffmpeg": run([ffmpeg, "-version"]).decode().splitlines()[0], "fixtures": fixtures,
                        "notCovered": [{"codec": "truehd", "channels": 8,
                                        "reason": "本机 FFmpeg TrueHD 编码器最多支持 5.1(side)，无自有 7.1 编码源；未下载媒体或伪造声道。"}]}
            directory.mkdir(parents=True, exist_ok=True)
            for fixture in fixtures:
                for name in fixture["files"]:
                    shutil.copyfile(staging / name, directory / name)
            (directory / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
        verify(directory)
    print(json.dumps({"fixtures": [{"id": item["id"], "frames": item["hostDecodedFrames"],
                                    "channels": item["channels"], "sampleRate": item["sampleRate"]}
                                   for item in manifest["fixtures"]], "notCovered": manifest["notCovered"]},
                     indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

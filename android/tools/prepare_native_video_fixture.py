#!/usr/bin/env python3
"""生成自有 10bit H.264/B 帧与色格；CPU sRGB 参考来自真正解码，不下载媒体。"""

from array import array
from decimal import Decimal
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile


WIDTH, HEIGHT, RATE, FRAMES = 192, 128, 12, 24
NAMES = ("hi10p-bframes", "hi10p-color-grid")
COLORS = ((220, 48, 48), (48, 220, 48), (48, 48, 220), (220, 220, 48),
          (0, 0, 0), (255, 255, 255), (112, 112, 112), (32, 32, 32))
POINTS = [(column * 48 + 24, row * 64 + 32) for row in range(2) for column in range(4)]
REFERENCE_MODEL = "CPU YUV420P10 limited BT709 matrix -> display BT1886 (gamma 2.4) -> sRGB OETF -> RGBA8"


def run(command: list[str]) -> bytes:
    result = subprocess.run(command, capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed ({result.returncode}):\n{result.stderr.decode(errors='replace')}")
    return result.stdout


def describe(path: Path) -> dict:
    content = path.read_bytes()
    return {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}


def us(value: str) -> int:
    return int(Decimal(value) * 1_000_000)


def source_rgb(x: int, y: int, frame: int, moving: bool) -> tuple[float, ...]:
    color = COLORS[y // 64 * 4 + x // 48]
    # 两片均有分段亮度变化，旧帧/旧 Surface 无法仅凭“非黑”通过。
    change = (frame // 6 - 1) * 5
    if moving and abs(x - (12 + frame * 7) % WIDTH) < 10 and 16 <= y < 112:
        color = (200, 176, 144)
    if color in ((0, 0, 0), (255, 255, 255)):
        change = 0
    return tuple(max(0, min(255, value + change)) / 255 for value in color)


def source_yuv(path: Path, moving: bool) -> None:
    with path.open("wb") as output:
        for frame in range(FRAMES):
            luma, cb, cr = array("H"), array("H"), array("H")
            for y in range(HEIGHT):
                for x in range(WIDTH):
                    red, green, blue = source_rgb(x, y, frame, moving)
                    value = .2126 * red + .7152 * green + .0722 * blue
                    # 明确保留非零低位，不先把源色格量化成 8bit 再贴 10bit 标签。
                    luma.append(max(64, min(940, round(64 + 876 * value) + (x + y + frame) % 4)))
            for y in range(0, HEIGHT, 2):
                for x in range(0, WIDTH, 2):
                    pixels = [source_rgb(x + dx, y + dy, frame, moving)
                              for dy in range(2) for dx in range(2)]
                    red, green, blue = (sum(pixel[channel] for pixel in pixels) / 4 for channel in range(3))
                    value = .2126 * red + .7152 * green + .0722 * blue
                    cb.append(round(512 + 896 * (blue - value) / (2 * (1 - .0722))))
                    cr.append(round(512 + 896 * (red - value) / (2 * (1 - .2126))))
            for plane in (luma, cb, cr):
                if sys.byteorder != "little":
                    plane.byteswap()
                output.write(plane.tobytes())


def initialization(annex_b: bytes) -> bytes:
    markers = list(re.finditer(b"\x00\x00(?:\x00)?\x01", annex_b))
    parameters = []
    for index, marker in enumerate(markers):
        end = markers[index + 1].start() if index + 1 < len(markers) else len(annex_b)
        nal = annex_b[marker.end():end]
        if nal and nal[0] & 31 in (7, 8) and nal not in parameters:
            parameters.append(nal)
    assert {nal[0] & 31 for nal in parameters} == {7, 8}
    return b"".join(b"\x00\x00\x00\x01" + nal for nal in parameters)


def reference_points(rgba: bytes) -> list[dict]:
    stride = WIDTH * HEIGHT * 4
    assert len(rgba) == FRAMES * stride
    frames = []
    for frame in range(FRAMES):
        samples = []
        for x, y in POINTS:
            colors = [rgba[frame * stride + ((y + dy) * WIDTH + x + dx) * 4:
                           frame * stride + ((y + dy) * WIDTH + x + dx) * 4 + 4]
                      for dy in range(-2, 3) for dx in range(-2, 3)]
            assert all(color[3] == 255 for color in colors)
            samples.append({"x": x, "y": y,
                            "rgb": [round(sum(color[channel] for color in colors) / len(colors), 3)
                                    for channel in range(3)]})
        frames.append({"frame": frame, "samples": samples})
    return frames


def cpu_srgb(yuv: bytes) -> bytes:
    """独立的显示参考：libplacebo 将 AVCOL_TRC_BT709 映射为 BT1886，不能反 BT709 OETF。"""
    pixels = array("H")
    pixels.frombytes(yuv)
    if sys.byteorder != "little":
        pixels.byteswap()
    plane_size = WIDTH * HEIGHT
    frame_size = plane_size * 3 // 2
    assert len(pixels) == FRAMES * frame_size
    assert max(pixels) <= 1023
    assert any(value & 3 for value in pixels), "实际 CPU 解码结果必须保留 10bit 的非零低位"

    def srgb(code: float) -> int:
        linear = max(0.0, min(1.0, code)) ** 2.4
        encoded = 12.92 * linear if linear <= .0031308 else 1.055 * linear ** (1 / 2.4) - .055
        return max(0, min(255, round(encoded * 255)))

    rgba = bytearray()
    for frame in range(FRAMES):
        start = frame * frame_size
        for y in range(HEIGHT):
            for x in range(WIDTH):
                chroma = y // 2 * (WIDTH // 2) + x // 2
                luma = (pixels[start + y * WIDTH + x] - 64) / 876
                cb = (pixels[start + plane_size + chroma] - 512) / 896
                cr = (pixels[start + plane_size * 5 // 4 + chroma] - 512) / 896
                # BT.709 的 Kr=0.2126、Kb=0.0722；源与目标均为 BT.709/sRGB primaries。
                red = luma + 2 * (1 - .2126) * cr
                blue = luma + 2 * (1 - .0722) * cb
                green = (luma - .2126 * red - .0722 * blue) / .7152
                rgba.extend((srgb(red), srgb(green), srgb(blue), 255))
    return bytes(rgba)


def generate(directory: Path, name: str, ffmpeg: str, ffprobe: str) -> dict:
    raw = directory / f"{name}.yuv"
    source_yuv(raw, name == "hi10p-bframes")
    movie = directory / f"{name}.mp4"
    run([ffmpeg, "-v", "error", "-y", "-f", "rawvideo", "-pixel_format", "yuv420p10le",
         "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", str(RATE),
         "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv", "-i", str(raw),
         "-frames:v", str(FRAMES), "-an", "-c:v", "libx264", "-profile:v", "high10",
         "-preset", "medium", "-crf", "14", "-pix_fmt", "yuv420p10le", "-threads", "1",
         "-x264-params", "bframes=3:b-adapt=0:b-pyramid=none:keyint=24:min-keyint=24:scenecut=0:threads=1:lookahead_threads=1:colorprim=bt709:transfer=bt709:colormatrix=bt709",
         "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
         "-color_range", "tv", "-fflags", "+bitexact", "-map_metadata", "-1",
         "-video_track_timescale", "12000", "-movflags", "+faststart", str(movie)])
    probe = json.loads(run([ffprobe, "-v", "error", "-select_streams", "v:0",
                           "-show_streams", "-show_frames", "-of", "json", str(movie)]))
    stream, frames = probe["streams"][0], probe["frames"]
    assert stream["codec_name"] == "h264" and stream["profile"] == "High 10"
    assert stream["pix_fmt"] == "yuv420p10le" and int(stream["has_b_frames"]) > 0
    assert len(frames) == FRAMES and "B" in [frame["pict_type"] for frame in frames[-4:]]
    frame_times = [us(frame["best_effort_timestamp_time"]) for frame in frames]
    assert frame_times == sorted(set(frame_times)) and frame_times[0] == 0
    assert stream["color_space"] == stream["color_transfer"] == stream["color_primaries"] == "bt709"

    # h264_mp4toannexb 对应 Media3 的 NAL 转换；包顺序仍为解码顺序，PTS 保持重排。
    annex = directory / f"{name}.h264"
    run([ffmpeg, "-v", "error", "-y", "-i", str(movie), "-map", "0:v:0", "-c:v", "copy",
         "-bsf:v", "h264_mp4toannexb", "-f", "h264", str(annex)])
    packets = json.loads(run([ffprobe, "-v", "error", "-select_streams", "v:0",
                             "-show_packets", "-of", "json", str(movie)]))["packets"]
    spans = json.loads(run([ffprobe, "-v", "error", "-show_packets", "-show_entries", "packet=pos,size",
                           "-of", "json", str(annex)]))["packets"]
    assert len(packets) == len(spans) == FRAMES
    encoded = annex.read_bytes()
    framed = directory / f"{name}.packets"
    with framed.open("wb") as output:
        for packet, span in zip(packets, spans):
            start, length = int(span["pos"]), int(span["size"])
            body = encoded[start:start + length]
            assert len(body) == length and length > 0
            output.write(struct.pack("<Iq", length, us(packet["pts_time"])))
            output.write(body)
    packet_times = [us(packet["pts_time"]) for packet in packets]
    assert packet_times != sorted(packet_times), "夹具必须真正包含帧重排"
    init = directory / f"{name}.init"
    init.write_bytes(initialization(encoded))
    codec_string = "avc1." + init.read_bytes()[5:8].hex()
    assert codec_string.startswith("avc1.6e"), codec_string
    decoded_yuv = run([ffmpeg, "-v", "error", "-threads", "1", "-i", str(movie), "-map", "0:v:0",
                       "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-"])
    annex_yuv = run([ffmpeg, "-v", "error", "-threads", "1", "-f", "h264", "-i", str(annex),
                     "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-"])
    assert annex_yuv == decoded_yuv, "Annex B 真解码应与 MP4 CPU 参考逐字节一致"
    rgba = cpu_srgb(decoded_yuv)
    reference = directory / f"{name}.reference.json"
    reference.write_text(json.dumps({"frameTimesUs": frame_times,
                                    "frames": reference_points(rgba)}, indent=2) + "\n")
    return {"id": name, "mime": "video/avc", "codecs": codec_string, "profile": stream["profile"],
            "pixelFormat": stream["pix_fmt"], "width": WIDTH, "height": HEIGHT, "frameRate": RATE,
            "bitDepth": int(stream["bits_per_raw_sample"]), "durationMs": 2_000,
            "hostDecodedFrames": len(frames), "packetCount": len(packets),
            "bFrames": sum(frame["pict_type"] == "B" for frame in frames),
            "tailFrameTypes": [frame["pict_type"] for frame in frames[-4:]],
            "packetTimesUs": packet_times, "frameTimesUs": frame_times,
            "cpuReference": {"model": REFERENCE_MODEL, "format": "rgba", "range": "full", "transfer": "sRGB",
                             "sourceTransferTag": "bt709", "sourceDisplayTransfer": "BT1886",
                             "decodedYuvSha256": hashlib.sha256(decoded_yuv).hexdigest(),
                             "sampleRadius": 2, "bytes": len(rgba), "sha256": hashlib.sha256(rgba).hexdigest()},
            "files": {path.name: describe(path) for path in (movie, framed, init, reference)}}


def verify(directory: Path) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text())
    assert manifest["schemaVersion"] == 1
    assert [fixture["id"] for fixture in manifest["fixtures"]] == list(NAMES)
    for fixture in manifest["fixtures"]:
        assert fixture["hostDecodedFrames"] == fixture["packetCount"] == FRAMES
        assert fixture["bitDepth"] == 10 and fixture["bFrames"] > 0
        for name, expected in fixture["files"].items():
            assert describe(directory / name) == expected, f"夹具损坏或 manifest 过期：{name}"
        data = memoryview((directory / f"{fixture['id']}.packets").read_bytes())
        times = []
        while data:
            size, time_us = struct.unpack_from("<Iq", data)
            assert 0 < size <= len(data) - 12
            times.append(time_us)
            data = data[12 + size:]
        assert times == fixture["packetTimesUs"]
        assert sorted(times) == fixture["frameTimesUs"]
        reference = json.loads((directory / f"{fixture['id']}.reference.json").read_text())
        assert reference["frameTimesUs"] == fixture["frameTimesUs"]
        assert len(reference["frames"]) == FRAMES
        if fixture["id"] == "hi10p-color-grid":
            for frame in reference["frames"]:
                assert max(frame["samples"][4]["rgb"]) <= 1, "黑格必须映射到 sRGB 黑，不能把 limited 当 full"
                assert min(frame["samples"][5]["rgb"]) >= 254, "白格必须映射到 sRGB 白，不能压低动态范围"
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "app/src/androidTest/assets/native-video")
    parser.add_argument("--verify", action="store_true", help="只检查夹具哈希、包帧数、时戳和参考矩阵")
    arguments = parser.parse_args()
    directory = arguments.output.resolve()
    if arguments.verify:
        manifest = verify(directory)
    else:
        ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
        if not ffmpeg or not ffprobe:
            raise SystemExit("需要本机 ffmpeg（libx264 10bit）与 ffprobe；不下载工具或媒体")
        with tempfile.TemporaryDirectory(prefix="pathfin-native-video-") as temporary:
            staging = Path(temporary)
            fixtures = [generate(staging, name, ffmpeg, ffprobe) for name in NAMES]
            manifest = {"schemaVersion": 1, "generator": "android/tools/prepare_native_video_fixture.py",
                        "ffmpeg": run([ffmpeg, "-version"]).decode().splitlines()[0], "fixtures": fixtures,
                        "notCovered": ["4K 播放性能", "Dolby Vision Profile 5 色彩正确性", "HDR tone mapping"]}
            directory.mkdir(parents=True, exist_ok=True)
            for fixture in fixtures:
                for name in fixture["files"]:
                    shutil.copyfile(staging / name, directory / name)
            (directory / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
        verify(directory)
    print(json.dumps({"fixtures": [{"id": item["id"], "frames": item["hostDecodedFrames"],
                                    "bFrames": item["bFrames"], "bitDepth": item["bitDepth"],
                                    "bytes": sum(file["bytes"] for file in item["files"].values())}
                                   for item in manifest["fixtures"]], "notCovered": manifest["notCovered"]},
                     indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""生成自有 MPEG-2 隔行/B 帧与 MP2 音频，验证 MKV、TS、PS 和 HLS。"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


WIDTH, HEIGHT, RATE, FRAMES = 192, 128, 25, 150
FILES = ("interlaced.mkv", "interlaced.ts", "interlaced.mpg", "interlaced.m3u8")


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout


def describe(path, ffprobe):
    streams = json.loads(run([ffprobe, "-v", "error", "-show_streams", "-of", "json", path]))["streams"]
    video = next(stream for stream in streams if stream["codec_type"] == "video")
    audio = next(stream for stream in streams if stream["codec_type"] == "audio")
    frames = json.loads(run([ffprobe, "-v", "error", "-select_streams", "v:0", "-show_frames",
                            "-show_entries", "frame=pict_type,interlaced_frame,top_field_first", "-of", "json", path]))["frames"]
    assert video["codec_name"] == "mpeg2video" and audio["codec_name"] == "mp2"
    assert audio["channels"] == 2 and int(audio["sample_rate"]) == 48_000
    assert video["pix_fmt"] == "yuv420p" and video["profile"] == "Main"
    assert (video["width"], video["height"]) == (WIDTH, HEIGHT)
    assert len(frames) == FRAMES and any(frame["pict_type"] == "B" for frame in frames)
    assert all(frame["interlaced_frame"] == 1 and frame["top_field_first"] == 1 for frame in frames)
    content = path.read_bytes()
    if path.suffix == ".mpg":
        assert content[:4] == b"\x00\x00\x01\xba" and content[4] & 0xC0 == 0x40, "必须是 MPEG-2 Systems pack header"
    return {"file": path.name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(),
            "container": {".mkv": "matroska", ".ts": "mpegts", ".mpg": "mpeg2-ps", ".m3u8": "hls"}[path.suffix],
            "videoCodec": video["codec_name"], "audioCodec": audio["codec_name"], "profile": video["profile"],
            "audioChannels": audio["channels"], "audioSampleRate": int(audio["sample_rate"]),
            "width": WIDTH, "height": HEIGHT, "rate": RATE, "frames": len(frames), "bitDepth": 8,
            "bFrames": sum(frame["pict_type"] == "B" for frame in frames),
            "interlacedFrames": sum(frame["interlaced_frame"] for frame in frames),
            "fieldOrder": "top-first", "sampleAspectRatio": video.get("sample_aspect_ratio", "unspecified")}


def verify(directory, ffprobe):
    manifest = json.loads((directory / "manifest.json").read_text())
    assert manifest["schemaVersion"] == 1
    assert {item["file"] for item in manifest["fixtures"]} == set(FILES)
    for item in manifest["fixtures"]:
        assert describe(directory / item["file"], ffprobe) == item, item["file"]
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "app/src/androidTest/assets/native-video/mpeg2")
    parser.add_argument("--verify", action="store_true")
    arguments = parser.parse_args()
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise SystemExit("需要本机 ffmpeg 和 ffprobe；不下载任何媒体或工具")
    directory = arguments.output.resolve()
    if not arguments.verify:
        with tempfile.TemporaryDirectory(prefix="pathfin-mpeg2-") as temporary:
            staging = Path(temporary)
            master = staging / "interlaced.mpg"
            # 50fps 源的相邻时刻分别构成上下场，不能只给逐行视频贴隔行标记。
            run([ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=192x128:rate=50:duration=6",
                 "-f", "lavfi", "-i", "sine=frequency=659:sample_rate=48000:duration=6",
                 "-vf", "tinterlace=mode=interleave_top,setsar=1", "-c:v", "mpeg2video", "-pix_fmt", "yuv420p",
                 "-flags", "+ilme+ildct", "-top", "1", "-bf", "2", "-g", "12", "-b:v", "300k",
                 "-maxrate", "600k", "-bufsize", "600k", "-threads", "1", "-c:a", "mp2", "-b:a", "96k", "-ac", "2", "-ar", "48000",
                 "-map_metadata", "-1", "-fflags", "+bitexact", "-t", "6", "-f", "vob", master])
            for name, container in (("interlaced.mkv", "matroska"), ("interlaced.ts", "mpegts")):
                run([ffmpeg, "-v", "error", "-y", "-fflags", "+genpts", "-i", master, "-map", "0:v:0", "-map", "0:a:0",
                     "-c", "copy", "-map_metadata", "-1", "-fflags", "+bitexact", "-f", container, staging / name])
            (staging / "interlaced.m3u8").write_text("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:7\n"
                                                   "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                                                   "#EXTINF:6.01,\ninterlaced.ts\n#EXT-X-ENDLIST\n")
            manifest = {"schemaVersion": 1, "generator": "android/tools/prepare_mpeg2_fixture.py",
                        "fixtures": [describe(staging / name, ffprobe) for name in FILES],
                        "notCovered": ["真实用户媒体", "4K 性能", "去隔行画质", "VOB 的 LPCM 与 DVD 字幕"]}
            directory.mkdir(parents=True, exist_ok=True)
            for name in FILES:
                shutil.copyfile(staging / name, directory / name)
            (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    manifest = verify(directory, ffprobe)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

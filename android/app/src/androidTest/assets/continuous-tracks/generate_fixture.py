#!/usr/bin/env python3
"""仅用本机 ffmpeg 生成自有色块/正弦波/字幕，第二集音轨与字幕顺序反转。"""

from pathlib import Path
import json
import shutil
import subprocess


def run(command):
    subprocess.run(command, check=True, capture_output=True, timeout=90)


def main():
    directory = Path(__file__).resolve().parent
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise RuntimeError("需要本机 ffmpeg/ffprobe；不会下载媒体")
    for name, text in (("english", "PATHFIN ENGLISH"), ("spanish", "PATHFIN SPANISH")):
        (directory / f"{name}.srt").write_text(f"1\n00:00:00,000 --> 00:00:23,500\n{text}\n\n", encoding="utf-8")
    first, second = directory / "episode-one.mkv", directory / "episode-two.mkv"
    run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
         "-f", "lavfi", "-i", "color=c=0x25364a:s=320x180:r=4",
         "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
         "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
         "-i", str(directory / "english.srt"), "-i", str(directory / "spanish.srt"),
         "-t", "24", "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-map", "3:s:0", "-map", "4:s:0",
         "-c:v", "libx264", "-preset", "ultrafast", "-crf", "32", "-pix_fmt", "yuv420p", "-g", "4",
         "-c:a", "aac", "-b:a", "24k", "-c:s", "srt",
         "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "title=English main",
         "-metadata:s:a:1", "language=jpn", "-metadata:s:a:1", "title=Japanese main",
         "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English captions",
         "-metadata:s:s:1", "language=spa", "-metadata:s:s:1", "title=Spanish captions",
         "-disposition:a:0", "default", "-disposition:a:1", "0",
         "-disposition:s:0", "default", "-disposition:s:1", "0", str(first)])
    run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(first),
         "-map", "0:v:0", "-map", "0:a:1", "-map", "0:a:0", "-map", "0:s:1", "-map", "0:s:0", "-c", "copy",
         "-disposition:a:0", "0", "-disposition:a:1", "default",
         "-disposition:s:0", "0", "-disposition:s:1", "default", str(second)])
    evidence = {}
    for path, expected_audio, expected_subtitle in ((first, ["eng", "jpn"], ["eng", "spa"]), (second, ["jpn", "eng"], ["spa", "eng"])):
        result = subprocess.run([ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
                                check=True, capture_output=True, text=True, timeout=20)
        data = json.loads(result.stdout)
        types = {kind: [s for s in data["streams"] if s["codec_type"] == kind] for kind in ("audio", "subtitle")}
        assert [s["tags"]["language"] for s in types["audio"]] == expected_audio
        assert [s["tags"]["language"] for s in types["subtitle"]] == expected_subtitle
        assert 23 <= float(data["format"]["duration"]) <= 25
        evidence[path.name] = {kind: [{"language": s["tags"]["language"], "label": s["tags"]["title"]} for s in streams]
                               for kind, streams in types.items()}
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    main()

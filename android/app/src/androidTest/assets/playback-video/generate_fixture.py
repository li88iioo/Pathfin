#!/usr/bin/env python3
"""只生成自有测试图和静止 H.264；不下载或读取用户媒体。需要本机 ffmpeg。"""
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
WIDTH, HEIGHT = 320, 180


def pattern() -> bytes:
    pixels = bytearray()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            color = (18 + x // 3, 24 + y // 2, 42 + x // 5)
            if 24 + y // 2 < x < 56 + y // 2:
                color = (226, 177, 106)
            if 174 < x < 286 and 28 < y < 148:
                edge = (x - 174) // 11 + (y - 28) // 11
                color = (232, 236, 242) if edge % 2 else (23, 31, 46)
            if abs(x - 160) + abs(y - 90) < 36:
                color = (88, 167, 220)
            pixels.extend(color)
    return f"P6\n{WIDTH} {HEIGHT}\n255\n".encode() + pixels


def bitmap_subtitle() -> bytes:
    # At 2s draw a 2x2 white PGS object, then clear the display at 19s.
    segments = [
        (2, 0x16, "0780 0438 10 0000 80 00 00 01 0000 00 00 000a 000a"),
        (2, 0x14, "0000 01 eb 80 80 ff"), (2, 0x15, "0000 00 c0 000008 0002 0002 01010101"),
        (2, 0x80, ""), (19, 0x16, "0780 0438 10 0001 00 00 00 00"), (19, 0x80, ""),
    ]
    packets = bytearray()
    for seconds, kind, hex_data in segments:
        payload = bytes.fromhex(hex_data)
        packets += struct.pack(">2sIIBH", b"PG", seconds * 90000, 0, kind, len(payload)) + payload
    return bytes(packets)


def main() -> None:
    (ROOT / "bitmap.sup").write_bytes(bitmap_subtitle())
    subprocess.run([
        "ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=navy:size=160x90:rate=5:duration=24",
        "-fix_sub_duration", "-i", str(ROOT / "bitmap.sup"), "-map", "0:v", "-map", "1:s", "-c:v", "libx264",
        "-threads:v", "1", "-g", "5", "-c:s", "dvdsub", "-metadata:s:s:0", "title=VobSub", "-t", "24",
        "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:s", "+bitexact", str(ROOT / "vobsub.mkv"),
    ], check=True, timeout=30)
    with tempfile.TemporaryDirectory(prefix="pathfin-playback-pattern-") as temporary:
        image = Path(temporary) / "pattern.ppm"
        image.write_bytes(pattern())
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-loop", "1", "-i", str(image),
            "-t", "120", "-r", "12", "-c:v", "libx264", "-threads", "2", "-preset", "ultrafast",
            "-qp", "0", "-g", "24", "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart",
            str(ROOT / "still.mp4"),
        ], check=True, timeout=90)
    print("已生成 320×180 / SDR / 120 秒静止测试图，不含用户内容。")


if __name__ == "__main__":
    main()

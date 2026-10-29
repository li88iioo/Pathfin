#!/usr/bin/env python3
"""只生成自有测试图和静止 H.264；不下载或读取用户媒体。需要本机 ffmpeg。"""
from pathlib import Path
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


def main() -> None:
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

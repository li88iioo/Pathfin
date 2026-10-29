#!/usr/bin/env python3
"""生成自有 SDR→HDR10/HLG 测试图案；真实转换传递函数，不只修改颜色标签。"""
from pathlib import Path
import json
import shutil
import subprocess


def main() -> None:
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise RuntimeError("需要本机 ffmpeg（libx265/zscale）和 ffprobe，不下载任何媒体")
    output = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/hdr-playback"
    output.mkdir(parents=True, exist_ok=True)
    samples = []
    for name, transfer in [("sdr", "bt709"), ("hdr10", "smpte2084"), ("hlg", "arib-std-b67")]:
        hdr = name != "sdr"
        color = "bt2020" if hdr else "bt709"
        matrix = "bt2020nc" if hdr else "bt709"
        pixels = "yuv420p10le" if hdr else "yuv420p"
        filters = "format=yuv420p"
        if hdr:
            filters = ("zscale=primariesin=bt709:transferin=bt709:matrixin=bt709:rangein=limited:"
                       f"primaries=bt2020:transfer={transfer}:matrix=bt2020nc:range=limited:npl=100,"
                       "format=yuv420p10le")
        parameters = f"pools=2:frame-threads=1:log-level=error:colorprim={color}:transfer={transfer}:colormatrix={matrix}"
        if name == "hdr10":
            parameters += (":hdr10=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)"
                           "WP(15635,16450)L(10000000,1):max-cll=1000,400")
        target = output / f"{name}.mp4"
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-filter_threads", "1",
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=3", "-an",
                        "-vf", filters, "-c:v", "libx265", "-preset", "fast", "-crf", "28",
                        "-pix_fmt", pixels, "-x265-params", parameters,
                        "-color_primaries", color, "-color_trc", transfer, "-colorspace", matrix,
                        "-color_range", "tv", "-tag:v", "hvc1", "-movflags", "+faststart", str(target)],
                       check=True, timeout=90)
        info = json.loads(subprocess.check_output([ffprobe, "-v", "error", "-select_streams", "v:0",
                                                  "-show_streams", "-of", "json", str(target)]))["streams"][0]
        assert info["pix_fmt"] == pixels and info["color_transfer"] == transfer
        assert info["color_primaries"] == color and info["color_space"] == matrix
        assert info["codec_name"] == "hevc" and info["width"] == 640 and info["height"] == 360
        samples.append({"file": target.name, "codec": info["codec_name"], "profile": info["profile"],
                        "pixelFormat": pixels, "transfer": transfer, "primaries": color,
                        "matrix": matrix, "frames": int(info["nb_frames"]), "bytes": target.stat().st_size})
    (output / "manifest.json").write_text(json.dumps(samples, indent=2) + "\n")
    print(json.dumps(samples, indent=2))


if __name__ == "__main__":
    main()

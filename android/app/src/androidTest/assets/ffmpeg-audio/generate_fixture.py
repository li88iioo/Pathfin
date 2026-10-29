#!/usr/bin/env python3
"""Generate owned sine waves and framed decoder packets; no external media is downloaded."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import subprocess
import tempfile


def run(command):
    return subprocess.run(command, check=True, capture_output=True, timeout=60)


def main():
    directory = Path(__file__).resolve().parent
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise SystemExit("ffmpeg and ffprobe are required")
    manifest = {}
    for codec, muxer, mime in (("ac3", "ac3", "audio/ac3"), ("eac3", "eac3", "audio/eac3"),
                               ("dca", "dts", "audio/vnd.dts"), ("truehd", "truehd", "audio/true-hd")):
        with tempfile.TemporaryDirectory(prefix="pathfin-audio-") as temp:
            raw = Path(temp) / "audio.raw"
            run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                 "sine=frequency=440:sample_rate=48000:duration=2", "-ac", "2", "-c:a", codec,
                 "-strict", "-2", "-f", muxer, str(raw)])
            probe = json.loads(run([ffprobe, "-v", "error", "-show_packets", "-show_streams", "-of", "json", str(raw)]).stdout)
            stream = probe["streams"][0]
            encoded = raw.read_bytes()
            # Little-endian packet size + PTS, followed by exactly one decoder access unit.
            packets = bytearray()
            for packet in probe["packets"]:
                offset, length = int(packet["pos"]), int(packet["size"])
                timestamp = round(float(packet.get("pts_time", 0)) * 1_000_000)
                data = encoded[offset:offset + length]
                assert len(data) == length
                packets += struct.pack("<Iq", length, timestamp) + data
            framed = directory / f"{codec}.packets"
            framed.write_bytes(packets)
            container = directory / f"{codec}.mka"
            run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(raw), "-map", "0:a:0",
                 "-c", "copy", "-fflags", "+bitexact", "-map_metadata", "-1", str(container)])
            pcm = run([ffmpeg, "-v", "error", "-i", str(container), "-f", "s16le", "-"]).stdout
            samples = struct.unpack("<" + "h" * (len(pcm) // 2), pcm)
            assert max(abs(x) for x in samples) > 1000
            assert stream["sample_rate"] == "48000" and stream["channels"] == 2
            manifest[codec] = {"mime": mime, "sampleRate": 48000, "channels": 2,
                "packets": len(probe["packets"]), "hostDecodedPcmBytes": len(pcm),
                "files": {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                          for p in (framed, container)}}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""真实 HLS / DASH 双内核回归夹具，仅提供本机合成媒体。

API 8121/jellyfin、媒体 8122，只绑定 127.0.0.1。主代理可分别 adb reverse
这两个端口后运行 ManifestPlaybackFlowTest；本脚本不运行 ADB 或读取用户会话。
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import signal
import subprocess
import sys
import threading
from pathlib import Path
from urllib.parse import urlsplit

from fixture_server import (
    ApiHandler, BaseHandler, FixtureServer, FixtureState, ITEM_ID, SERVER_ID,
    SOURCE_ID, SENSITIVE_HEADER_NAMES,
)


DURATION_SECONDS = 120
SEGMENT_SECONDS = 4
MEDIA_KEY = "fixture-manifest-only"
FORMATS = {"hls": ("master.m3u8", "application/vnd.apple.mpegurl"), "dash": ("manifest.mpd", "application/dash+xml")}


def _ffmpeg(arguments: list[str], output: Path) -> None:
    executable = shutil.which("ffmpeg")
    if executable is None:
        raise RuntimeError("找不到本机 ffmpeg；不会下载媒体")
    result = subprocess.run([executable, "-hide_banner", "-loglevel", "error", "-y", *arguments],
                            capture_output=True, text=True, timeout=180, check=False)
    if result.returncode:
        (output / "ffmpeg-error.log").write_text(result.stderr, encoding="utf-8")
        raise RuntimeError("ffmpeg 无法生成合成分片，详见输出目录 ffmpeg-error.log")


def prepare(output: Path) -> Path:
    """固定四秒 GOP，避免 seek 回归实际仍只访问一个长 GOP 分片。"""
    output.mkdir(parents=True, exist_ok=True)
    sample = output / "segmented-source.mp4"
    if not sample.is_file() or sample.stat().st_size == 0:
        temporary = output / "segmented-source.generating.mp4"
        try:
            _ffmpeg([
                "-filter_threads", "1", "-filter_complex_threads", "1",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=12",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
                "-t", str(DURATION_SECONDS), "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0",
                "-c:v", "libx264", "-threads:v", "2", "-preset", "ultrafast", "-crf", "30",
                "-pix_fmt", "yuv420p", "-g", "48", "-keyint_min", "48", "-sc_threshold", "0",
                "-c:a", "aac", "-threads:a", "1", "-b:a", "48k", "-movflags", "+faststart",
                "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=jpn", str(temporary),
            ], output)
            temporary.replace(sample)
        finally:
            temporary.unlink(missing_ok=True)
    hls, dash = output / "hls", output / "dash"
    hls.mkdir(exist_ok=True)
    dash.mkdir(exist_ok=True)
    if not (hls / "master.m3u8").is_file():
        _ffmpeg([
            "-i", str(sample), "-map", "0:v:0", "-map", "0:a:0", "-map", "0:a:1", "-c", "copy",
            "-f", "hls", "-hls_time", str(SEGMENT_SECONDS), "-hls_playlist_type", "vod", "-hls_list_size", "0",
            "-hls_flags", "independent_segments", "-hls_segment_filename", str(hls / "segment_%v_%05d.ts"),
            "-master_pl_name", "master.m3u8", "-var_stream_map",
            "v:0,agroup:audio,name:video a:0,agroup:audio,default:yes,language:en,name:eng a:1,agroup:audio,language:ja,name:jpn",
            str(hls / "playlist_%v.m3u8"),
        ], output)
    if not (dash / "manifest.mpd").is_file():
        _ffmpeg([
            "-i", str(sample), "-map", "0:v:0", "-map", "0:a:0", "-map", "0:a:1", "-c", "copy",
            "-f", "dash", "-seg_duration", str(SEGMENT_SECONDS), "-use_template", "1", "-use_timeline", "1",
            "-init_seg_name", "init-$RepresentationID$.m4s", "-media_seg_name", "segment-$RepresentationID$-$Number%05d$.m4s",
            "-adaptation_sets", "id=0,streams=0 id=1,streams=1 id=2,streams=2", str(dash / "manifest.mpd"),
        ], output)
    for path in (hls / "master.m3u8", dash / "manifest.mpd"):
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError("合成媒体清单未完整生成")
    return sample


class ManifestState(FixtureState):
    def __init__(self, args, sample: Path):
        super().__init__(args, sample)
        self.format = "hls"
        self.media_requests: list[dict] = []
        self.events: list[dict] = []

    def reset(self, format_name: str):
        if format_name not in FORMATS:
            raise ValueError("Unknown synthetic manifest format")
        with self.lock:
            self.format = format_name
            self.media_requests.clear()
            self.events.clear()
            self.position_ticks = 0
            self.played = False

    def log(self, service, route, method, headers, **fields):
        super().log(service, route, method, headers, **fields)
        with self.lock:
            if fields.get("event"):
                self.events.append({key: fields[key] for key in ("event", "play_session_id", "position_ticks", "is_paused")})
                self.events = self.events[-128:]
            if service == "manifest-media":
                self.media_requests.append({"route": route, "method": method, **fields})
                self.media_requests = self.media_requests[-1024:]

    def source(self):
        with self.lock:
            format_name = self.format
        file_name = FORMATS[format_name][0]
        return {
            "Id": SOURCE_ID, "Name": f"Synthetic {format_name.upper()}", "Protocol": "Http", "Container": format_name,
            "Path": f"http://127.0.0.1:{self.media_port}/{format_name}/{file_name}", "IsRemote": True,
            "RunTimeTicks": DURATION_SECONDS * 10_000_000, "SupportsDirectPlay": True,
            "SupportsDirectStream": True, "SupportsTranscoding": False, "RequiredHttpHeaders": {"X-Media-Key": MEDIA_KEY},
            "MediaStreams": [
                {"Type": "Video", "Index": 0, "Codec": "h264", "Width": 320, "Height": 180, "AverageFrameRate": 12, "VideoRange": "SDR"},
                {"Type": "Audio", "Index": 1, "Codec": "aac", "Language": "eng", "Channels": 1, "IsDefault": True},
                {"Type": "Audio", "Index": 2, "Codec": "aac", "Language": "jpn", "Channels": 1},
            ],
        }

    def movie(self):
        item = super().movie()
        item.update({"Name": "Pathfin · 合成 HLS / DASH 分片", "RunTimeTicks": DURATION_SECONDS * 10_000_000,
                     "Overview": "本机合成的四秒分片、测试图与双正弦音轨，无用户媒体。", "MediaSources": [self.source()]})
        return item

    def playback_info(self):
        result = super().playback_info()
        result["MediaSources"] = [self.source()]
        return result

    def status(self):
        with self.lock:
            return {"Id": SERVER_ID, "ServerName": "Pathfin manifest fixture", "Version": "12.3.0", "Fixture": True,
                    "ManifestFixture": True, "Format": self.format, "DurationSeconds": DURATION_SECONDS,
                    "SegmentSeconds": SEGMENT_SECONDS, "Events": list(self.events), "MediaRequests": list(self.media_requests)}


class ManifestApiHandler(ApiHandler):
    def do_GET(self):
        route, _ = self._route()
        if route in ("/", "/health", "/System/Info/Public", "/_fixture/status"):
            self.send_json(self.state.status())
        else:
            super().do_GET()

    def do_POST(self):
        route, _ = self._route()
        if route != "/_fixture/reset":
            return super().do_POST()
        body = self.read_json()
        if body is None:
            return
        if set(body) != {"Format"} or body["Format"] not in FORMATS:
            self.send_json({"Error": "Only hls/dash synthetic formats are supported"}, 400)
            return
        self.state.reset(body["Format"])
        self.send_json(self.state.status())


class ManifestMediaHandler(BaseHandler):
    def handle(self):
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError):
            # 解码器取消预读 / seek 时也可能在下一次 keep-alive read 断开，不是服务故障。
            pass

    def do_GET(self):
        self.serve_media(False)

    def do_HEAD(self):
        self.serve_media(True)

    def serve_media(self, head: bool):
        path = urlsplit(self.path).path
        match = re.fullmatch(r"/(hls|dash)/([A-Za-z0-9_.-]{1,100})", path)
        file = (self.state.sample.parent / match[1] / match[2]) if match else None
        known = file is not None and file.is_file() and file.suffix in (".m3u8", ".mpd", ".ts", ".m4s", ".mp4")
        leak = any(self.headers.get_all(name) for name in SENSITIVE_HEADER_NAMES)
        source_header = self.headers.get("X-Media-Key") == MEDIA_KEY
        status = 403 if leak or not source_header else 200 if known else 404
        kind, segment, video = "unknown", None, False
        if known:
            kind = "manifest" if file.suffix in (".m3u8", ".mpd") else "init" if file.name.startswith("init-") else "segment"
            if kind == "segment":
                number = re.search(r"(?:_|-)(\d{5})\.(?:ts|m4s)$", file.name)
                segment = int(number[1]) if number else None
                if segment is not None and match[1] == "dash":
                    segment -= 1  # Normalize HLS startNumber=0 and DASH startNumber=1 for assertions.
                video = file.name.startswith("segment_video_") or file.name.startswith("segment-0-")
        def record(successful: bool):
            self.state.log("manifest-media", path if known else "/unknown", "HEAD" if head else "GET", self.headers,
                           format=match[1] if match else "unknown", kind=kind, segment=segment, video=video,
                           credential_leak=leak, source_header_present=source_header, range_present=bool(self.headers.get("Range")),
                           successful=successful)
        if status != 200:
            record(False)
            self.send_json({"Error": "Synthetic source headers rejected" if status == 403 else "Synthetic media not found"}, status, head)
            return
        size = file.stat().st_size
        start, end = 0, size - 1
        range_header = self.headers.get("Range")
        if range_header:
            parsed = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
            try:
                if parsed is None or not any(parsed.groups()):
                    raise ValueError()
                left, right = parsed.groups()
                if left:
                    start, end = int(left), min(int(right), size - 1) if right else size - 1
                else:
                    if int(right) <= 0:
                        raise ValueError()
                    start = max(0, size - int(right))
                if start >= size or start > end:
                    raise ValueError()
            except ValueError:
                record(False)
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        record(True)
        mime = {".m3u8": FORMATS["hls"][1], ".mpd": FORMATS["dash"][1], ".ts": "video/mp2t", ".m4s": "video/mp4", ".mp4": "video/mp4"}[file.suffix]
        self.send_response(206 if range_header else 200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if range_header:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head:
            return
        try:
            with file.open("rb") as source:
                source.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = source.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Seek / stop legitimately cancels a previous segment read.


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-port", type=int, default=8121)
    parser.add_argument("--media-port", type=int, default=8122)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / ".superpowers/tmp/dev10/manifests")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if not all(1024 <= port <= 65535 for port in (args.api_port, args.media_port)) or args.api_port == args.media_port:
        parser.error("API / 媒体端口必须有效且不同")
    if {args.api_port, args.media_port} & {8097, 8098, 8101, 8102}:
        parser.error("不能占用其他合成 HTTP / TLS / 多轨回归端口")
    args.output = args.output.expanduser().resolve()
    args.bind_address, args.media_tls_cert, args.base_path, args.media_route = "127.0.0.1", None, "/jellyfin", "sample.mp4"
    try:
        sample = prepare(args.output)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"合成分片生成失败：{error}", file=sys.stderr)
        return 1
    if args.prepare_only:
        print(f"已生成真实 HLS / DASH 与双音轨：{args.output}")
        return 0
    state, servers = ManifestState(args, sample), []
    try:
        servers.append(FixtureServer(args.api_port, ManifestApiHandler, state))
        servers.append(FixtureServer(args.media_port, ManifestMediaHandler, state))
    except OSError as error:
        for server in servers:
            server.server_close()
        print(f"无法启动分片回归端口：{error}", file=sys.stderr)
        return 1
    stopped = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    print(f"合成分片 API http://127.0.0.1:{args.api_port}/jellyfin；媒体 http://127.0.0.1:{args.media_port}", flush=True)
    print(f"测试账号 pathfin/pathfin；仅布尔鉴权标志的日志：{state.log_path}", flush=True)
    try:
        stopped.wait()
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(timeout=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

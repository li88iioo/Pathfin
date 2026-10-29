#!/usr/bin/env python3
"""独立的合成多轨播放夹具，不替换 fixture_server.py 的默认 HTTP/TLS 测试。

生成 120 秒测试图、440/660Hz 双 AAC 音轨、内封 mov_text 与外挂 SRT/VTT/ASS。
API 默认 8101/jellyfin，原始媒体默认 8102；仅绑定回环，不读取或下载用户媒体。
"""

from __future__ import annotations

import argparse
import json
import shutil
import signal
import subprocess
import sys
import threading
from pathlib import Path

from fixture_server import ApiHandler, FixtureServer, FixtureState, MediaHandler, ITEM_ID, SOURCE_ID, SERVER_ID


DURATION_SECONDS = 120
MEDIA_KEY = "fixture-media-only"
SUBTITLE_FILES = {4: ("srt", "application/x-subrip"), 5: ("vtt", "text/vtt"), 6: ("ass", "text/x-ssa")}


def prepare(output: Path) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    srt = "1\n00:00:00,000 --> 00:01:59,000\nPATHFIN SRT\n\n"
    (output / "external.srt").write_text(srt, encoding="utf-8")
    (output / "external.vtt").write_text("WEBVTT\n\n00:00.000 --> 01:59.000\nPATHFIN VTT\n\n", encoding="utf-8")
    (output / "internal.srt").write_text(srt.replace("PATHFIN SRT", "PATHFIN INTERNAL"), encoding="utf-8")
    (output / "external.ass").write_text(
        "[Script Info]\nScriptType: v4.00+\nPlayResX: 320\nPlayResY: 180\n\n"
        "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
        "Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1\n\n"
        "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        "Dialogue: 0,0:00:00.00,0:01:59.00,Default,,0,0,0,,PATHFIN ASS\n",
        encoding="utf-8",
    )
    sample = output / "multitrack.mp4"
    if sample.is_file() and sample.stat().st_size > 0:
        return sample
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("找不到 ffmpeg；不会下载任何媒体。")
    temporary = output / "multitrack.generating.mp4"
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=12",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
        "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
        "-i", str(output / "internal.srt"), "-t", str(DURATION_SECONDS),
        "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-map", "3:s:0",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "48k", "-c:s", "mov_text", "-movflags", "+faststart",
        "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "handler_name=English 440Hz",
        "-metadata:s:a:1", "language=jpn", "-metadata:s:a:1", "handler_name=Japanese 660Hz",
        "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "handler_name=Internal fixture",
        "-disposition:a:0", "default", "-disposition:a:1", "0", "-disposition:s:0", "0", str(temporary),
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120, check=False)
        if result.returncode:
            raise RuntimeError("ffmpeg 无法生成合成多轨媒体；请检查 H.264/AAC/mov_text 编码器。")
        temporary.replace(sample)
    finally:
        temporary.unlink(missing_ok=True)
    return sample


class MultiTrackState(FixtureState):
    def __init__(self, args, sample):
        super().__init__(args, sample)
        self.events: list[dict] = []
        self.subtitle_requests: list[dict] = []

    def log(self, service, route, method, headers, **fields):
        super().log(service, route, method, headers, **fields)
        if service == "api" and fields.get("event"):
            with self.lock:
                self.events.append({key: fields[key] for key in ("event", "play_session_id", "position_ticks", "is_paused")})
                self.events = self.events[-256:]

    def media_source(self):
        streams = [
            {"Type": "Video", "Index": 0, "Codec": "h264", "Width": 320, "Height": 180, "VideoRange": "SDR", "BitDepth": 8, "AverageFrameRate": 12},
            {"Type": "Audio", "Index": 1, "Codec": "aac", "Language": "eng", "Channels": 1, "SampleRate": 48000, "IsDefault": True},
            {"Type": "Audio", "Index": 2, "Codec": "aac", "Language": "jpn", "Channels": 1, "SampleRate": 48000},
            {"Type": "Subtitle", "Index": 3, "Codec": "mov_text", "Language": "eng", "IsExternal": False},
        ]
        for index, (extension, _) in SUBTITLE_FILES.items():
            streams.append({
                "Type": "Subtitle", "Index": index, "Codec": extension, "Language": "eng",
                "DisplayTitle": f"{extension.upper()} 合成外挂", "IsExternal": True, "IsDefault": False,
                "DeliveryUrl": f"/Videos/{ITEM_ID}/{SOURCE_ID}/Subtitles/{index}/Stream.{extension}?api_key=fixture-token",
            })
        return {
            "Id": SOURCE_ID, "Name": "Pathfin synthetic multitrack", "Protocol": "Http", "Container": "mp4",
            "Path": f"http://127.0.0.1:{self.media_port}/sample.mp4", "IsRemote": True,
            "RunTimeTicks": DURATION_SECONDS * 10_000_000, "SupportsDirectPlay": True,
            "SupportsDirectStream": True, "SupportsTranscoding": False,
            "RequiredHttpHeaders": {"X-Media-Key": MEDIA_KEY}, "MediaStreams": streams,
        }

    def movie(self):
        item = super().movie()
        item.update({
            "Name": "Pathfin · 合成双音轨与字幕", "RunTimeTicks": DURATION_SECONDS * 10_000_000,
            "Overview": "本机生成的测试图、两个正弦音轨和测试字幕，无用户影片。",
            "MediaSources": [self.media_source()],
            "Chapters": [{"Name": "测试章节一", "StartPositionTicks": 0}, {"Name": "测试章节二", "StartPositionTicks": 600_000_000}],
        })
        return item

    def playback_info(self):
        result = super().playback_info()
        result["MediaSources"] = [self.media_source()]
        return result

    def status(self):
        with self.lock:
            return {
                "Id": SERVER_ID, "ServerName": "Pathfin multitrack fixture", "Version": "12.3.0", "Fixture": True,
                "MultiTrackFixture": True, "DurationSeconds": DURATION_SECONDS,
                "Events": list(self.events), "SubtitleRequests": list(self.subtitle_requests),
            }


class MultiTrackApiHandler(ApiHandler):
    def do_GET(self):
        route, query = self._route()
        if route in ("/", "/health", "/System/Info/Public"):
            self.send_json(self.state.status())
            return
        for index, (extension, mime) in SUBTITLE_FILES.items():
            if route == f"/Videos/{ITEM_ID}/{SOURCE_ID}/Subtitles/{index}/Stream.{extension}":
                if not self._check_auth():
                    return
                source_header_leak = bool(self.headers.get("X-Media-Key"))
                query_token_leak = "api_key" in query
                self.state.log("subtitle", f"/Subtitles/{index}/Stream.{extension}", "GET", self.headers,
                               source_header_leak=source_header_leak, query_token_leak=query_token_leak)
                with self.state.lock:
                    self.state.subtitle_requests.append({"index": index, "authorized": True, "source_header_leak": source_header_leak,
                                                         "query_token_leak": query_token_leak})
                if source_header_leak or query_token_leak:
                    self.send_json({"Error": "Subtitle header/query scope was not isolated"}, 403)
                    return
                body = (self.state.sample.parent / f"external.{extension}").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
        super().do_GET()


class MultiTrackMediaHandler(MediaHandler):
    def serve_media(self, head):
        if self.headers.get("X-Media-Key") != MEDIA_KEY:
            self.send_json({"Error": "Synthetic media source header missing"}, 403, head)
            return
        super().serve_media(head)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-port", type=int, default=8101)
    parser.add_argument("--media-port", type=int, default=8102)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / ".superpowers/tmp/dev9/multitrack")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if not (1 <= args.api_port <= 65535 and 1 <= args.media_port <= 65535) or args.api_port == args.media_port:
        parser.error("API/媒体端口必须有效且不同")
    if {args.api_port, args.media_port} & {8097, 8098}:
        parser.error("不能占用原有默认 HTTP/TLS 回归端口 8097/8098")
    args.output = args.output.expanduser().resolve()
    args.bind_address, args.media_tls_cert, args.base_path, args.media_route = "127.0.0.1", None, "/jellyfin", "sample.mp4"
    try:
        sample = prepare(args.output)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"合成夹具生成失败：{error}", file=sys.stderr)
        return 1
    if args.prepare_only:
        print(f"合成多轨媒体与 SRT/VTT/ASS 已生成：{sample}")
        return 0
    state = MultiTrackState(args, sample)
    servers = []
    try:
        servers.append(FixtureServer(args.api_port, MultiTrackApiHandler, state))
        servers.append(FixtureServer(args.media_port, MultiTrackMediaHandler, state))
    except OSError as error:
        for server in servers:
            server.server_close()
        print(f"无法启动独立多轨端口：{error}", file=sys.stderr)
        return 1
    stopped = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    print(f"合成多轨 API http://127.0.0.1:{args.api_port}/jellyfin；媒体 http://127.0.0.1:{args.media_port}/sample.mp4", flush=True)
    print(f"测试账号 pathfin/pathfin；安全日志 {state.log_path}", flush=True)
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

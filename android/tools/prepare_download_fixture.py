#!/usr/bin/env python3
"""独立回环后台下载夹具：流式生成字节、临时原始地址、失败/取消与安全统计。

默认 API 8111/jellyfin，媒体 8112；需由 root adb reverse 相同端口。
不读取或下载任何用户媒体，不记录 token/请求全文。16MiB 的确定性媒体字节约9秒传完。
首次失败模式在2MiB主动断流；每次 PlaybackInfo 都签发不同 URL，旧 URL 不可新建连接。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import signal
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

TOKEN = "pathfin-download-flow-fixture-token"
SOURCE_ID = "fixture-source"
ITEM_PATTERN = re.compile(r"bg-[a-f0-9-]{36}")
CHUNK_SIZE = 64 * 1024
PATTERN = bytes(range(251)) * (CHUNK_SIZE // 251 + 2)


def block(offset: int, size: int) -> bytes:
    start = offset % 251
    return PATTERN[start : start + size]


def digest(size: int) -> str:
    result = hashlib.sha256()
    for offset in range(0, size, CHUNK_SIZE):
        result.update(block(offset, min(CHUNK_SIZE, size - offset)))
    return result.hexdigest()


class State:
    def __init__(self, api_port: int, media_port: int):
        self.api_port = api_port
        self.media_port = media_port
        self.lock = threading.RLock()
        self.items: dict[str, dict] = {}

    def prepare(self, request: dict) -> dict:
        item = request.get("itemId", "")
        size = int(request.get("size", 16 * 1024 * 1024))
        delay_ms = int(request.get("delayMs", 35))
        if not ITEM_PATTERN.fullmatch(item) or not 1024 <= size <= 128 * 1024 * 1024 or not 0 <= delay_ms <= 250:
            raise ValueError("Invalid synthetic fixture parameters")
        with self.lock:
            if item in self.items:
                raise ValueError("Fixture item id must be unique")
            value = {
                "ItemId": item, "Bytes": size, "Sha256": digest(size), "DelayMs": delay_ms,
                "FailFirst": request.get("failFirst") is True,
                "DetailRequests": 0, "PlaybackRequests": 0, "SourceSerial": 0,
                "Attempts": [], "HeaderLeaks": 0,
            }
            self.items[item] = value
            return self.status(item)

    def status(self, item: str | None = None) -> dict:
        with self.lock:
            if item is None:
                return {"DownloadFlowFixture": True, "ApiPort": self.api_port, "MediaPort": self.media_port, "Items": len(self.items)}
            value = self.items.get(item)
            if value is None:
                raise KeyError(item)
            return {key: value[key] for key in ("ItemId", "Bytes", "Sha256", "DetailRequests", "PlaybackRequests", "HeaderLeaks")} | {
                "Attempts": [dict(attempt) for attempt in value["Attempts"]],
                "ActiveStreams": sum(1 for attempt in value["Attempts"] if attempt["Active"]),
            }


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, port, state, media=False):
        super().__init__(("127.0.0.1", port), Handler)
        self.state, self.media = state, media


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def json(self, value: dict, status=200):
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def route(self):
        parsed = urlsplit(self.path)
        return parsed.path.removeprefix("/jellyfin"), parse_qs(parsed.query)

    @property
    def state(self):
        return self.server.state

    def authorized(self):
        if self.headers.get("X-Emby-Token") == TOKEN or f'Token="{TOKEN}"' in self.headers.get("Authorization", ""):
            return True
        self.json({"Error": "Synthetic account auth required"}, 401)
        return False

    def do_GET(self):
        route, query = self.route()
        if route in ("/health", "/System/Info/Public"):
            self.json(self.state.status())
            return
        if route == "/__fixture__/status":
            try:
                self.json(self.state.status(query.get("item", [None])[0]))
            except KeyError:
                self.json({"Error": "Unknown synthetic item"}, 404)
            return
        match = re.fullmatch(r"/Items/(bg-[a-f0-9-]{36})", route)
        if match and not self.server.media:
            if not self.authorized():
                return
            item = match.group(1)
            with self.state.lock:
                value = self.state.items.get(item)
                if value is None:
                    self.json({"Error": "Unknown synthetic item"}, 404)
                    return
                value["DetailRequests"] += 1
                path = f"http://127.0.0.1:{self.state.media_port}/unresolved/{item}.mp4"
                self.json({
                    "Id": item, "Name": "Pathfin synthetic background download", "Type": "Movie", "CanDownload": True,
                    "Path": path, "Container": "mp4",
                    "MediaSources": [{"Id": SOURCE_ID, "Protocol": "Http", "Path": path, "Container": "mp4", "Size": value["Bytes"]}],
                })
            return
        match = re.fullmatch(r"/stream/(bg-[a-f0-9-]{36})/(\d+)\.mp4", route)
        if match and self.server.media:
            self.stream(match.group(1), int(match.group(2)))
            return
        self.json({"Error": "Unknown fixture route"}, 404)

    def do_POST(self):
        route, _ = self.route()
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 16 * 1024:
                raise ValueError("Invalid request length")
            body = self.rfile.read(length)
            request = json.loads(body) if body else {}
        except (ValueError, json.JSONDecodeError):
            self.json({"Error": "Invalid fixture request"}, 400)
            return
        if route == "/__fixture__/prepare":
            try:
                self.json(self.state.prepare(request))
            except ValueError as error:
                self.json({"Error": str(error)}, 400)
            return
        match = re.fullmatch(r"/Items/(bg-[a-f0-9-]{36})/PlaybackInfo", route)
        if match and not self.server.media:
            if not self.authorized():
                return
            item = match.group(1)
            with self.state.lock:
                value = self.state.items.get(item)
                if value is None:
                    self.json({"Error": "Unknown synthetic item"}, 404)
                    return
                value["PlaybackRequests"] += 1
                value["SourceSerial"] += 1
                serial = value["SourceSerial"]
                path = f"http://127.0.0.1:{self.state.media_port}/stream/{item}/{serial}.mp4"
                self.json({"PlaySessionId": f"synthetic-download-{serial}", "MediaSources": [{
                    "Id": SOURCE_ID, "Protocol": "Http", "Path": path, "Container": "mp4", "Size": value["Bytes"],
                    "SupportsDirectPlay": True, "SupportsDirectStream": True,
                    "RequiredHttpHeaders": {"X-Fixture-Download": item},
                }]})
            return
        self.json({"Error": "Unknown fixture route"}, 404)

    def stream(self, item, serial):
        with self.state.lock:
            value = self.state.items.get(item)
            if value is None or value["SourceSerial"] != serial:
                self.json({"Error": "Expired synthetic URL"}, 410)
                return
            leak = bool(self.headers.get("Authorization") or self.headers.get("X-Emby-Token"))
            if leak:
                value["HeaderLeaks"] += 1
            if leak or self.headers.get("X-Fixture-Download") != item or self.headers.get("Range"):
                self.json({"Error": "Credential scope or fresh whole-file contract failed"}, 403)
                return
            attempt = {"Serial": serial, "SentBytes": 0, "Active": True, "Completed": False,
                       "ClientDisconnected": False, "ForcedFailure": value["FailFirst"] and len(value["Attempts"]) == 0}
            value["Attempts"].append(attempt)
            total, delay_ms = value["Bytes"], value["DelayMs"]
        try:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(total))
            self.send_header("Content-Disposition", 'attachment; filename="synthetic-download.mp4"')
            self.end_headers()
            for offset in range(0, total, CHUNK_SIZE):
                data = block(offset, min(CHUNK_SIZE, total - offset))
                self.wfile.write(data)
                self.wfile.flush()
                with self.state.lock:
                    attempt["SentBytes"] += len(data)
                if attempt["ForcedFailure"] and attempt["SentBytes"] >= 2 * 1024 * 1024:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                if delay_ms:
                    time.sleep(delay_ms / 1000)
            with self.state.lock:
                attempt["Completed"] = True
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            with self.state.lock:
                attempt["ClientDisconnected"] = True
        finally:
            with self.state.lock:
                attempt["Active"] = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-port", type=int, default=8111)
    parser.add_argument("--media-port", type=int, default=8112)
    args = parser.parse_args()
    if not 1 <= args.api_port <= 65535 or not 1 <= args.media_port <= 65535 or args.api_port == args.media_port:
        parser.error("端口必须有效且不同")
    if {args.api_port, args.media_port} & {8097, 8098, 8101, 8102}:
        parser.error("不能占用既有播放回归端口")
    state = State(args.api_port, args.media_port)
    servers = [Server(args.api_port, state), Server(args.media_port, state, media=True)]
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    print(f"DOWNLOAD_FLOW_FIXTURE_READY api=http://127.0.0.1:{args.api_port}/jellyfin media={args.media_port}", flush=True)
    try:
        stopped.wait()
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

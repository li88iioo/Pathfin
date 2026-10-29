#!/usr/bin/env python3
"""Pathfin 开发专用双端口测试服务；不能替代真实 Jellyfin/STRM 验收。

默认仅使用 Python 标准库与本机 ffmpeg，不下载媒体。测试账号为 pathfin / pathfin。
可选 --with-images 使用 Pillow 生成 UI 截图测试海报，不改变原有播放片库。
API 与片源默认分别监听回环地址；可显式绑定私网接口做真实 LAN 权限回归。
"""

from __future__ import annotations

import argparse
import hashlib
import ssl
import ipaddress
import json
import re
import random
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit


USER_ID = "fixture-user"
LIBRARY_ID = "fixture-library"
ITEM_ID = "fixture-movie"
GENRE_ID = "fixture-genre"
FIXTURE_GENRE = "测试类型"
FIXTURE_TAG = "本机自制"
SOURCE_ID = "fixture-source"
SERVER_ID = "pathfin-fixture-server"
TEST_TOKEN = "fixture-token"
DURATION_TICKS = 150_000_000
SENSITIVE_HEADER_NAMES = ("authorization", "x-emby-token", "x-emby-authorization", "x-mediabrowser-token")
LEGACY_HEADER_NAMES = ("x-emby-token", "x-emby-authorization", "x-mediabrowser-token")
PRIVATE_BIND_NETWORKS = tuple(ipaddress.IPv4Network(network) for network in (
    "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
))
EXTERNAL_MEDIA_ROUTE = "/_fixture-external/sample.mp4"


def parse_bind_address(value: str) -> str:
    """只允许显式回环/RFC1918 IPv4 接口，禁止 wildcard、公网与 DNS 绑定。"""
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise argparse.ArgumentTypeError("bind-address 必须为回环或 RFC1918 私网 IPv4 地址") from error
    if not any(address in network for network in PRIVATE_BIND_NETWORKS):
        raise argparse.ArgumentTypeError("bind-address 只允许回环或 RFC1918 私网 IPv4 地址，不能使用 0.0.0.0 或公网地址")
    return str(address)


def prepare_sample(output: Path) -> Path:
    """生成测试画面和正弦音；所有媒体均为本机程序生成。"""
    sample = output / "sample.mp4"
    if sample.is_file() and sample.stat().st_size > 0:
        return sample
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("找不到 ffmpeg，无法生成自制测试媒体。请先安装 ffmpeg；本脚本不会下载影片。")
    temporary = output / "sample.generating.mp4"
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-t", "15", "-c:v", "libx264", "-preset", "ultrafast",
        "-tune", "fastdecode", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", str(temporary),
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90, check=False)
    except subprocess.TimeoutExpired as error:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("ffmpeg 生成测试媒体超时。") from error
    if result.returncode != 0:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("ffmpeg 无法生成 H.264/AAC 测试媒体，请检查本机 libx264 与 AAC 编码器。")
    temporary.replace(sample)
    return sample


def prepare_preview_images(output: Path) -> dict[str, Path]:
    """按需生成明确标识为 TEST LIBRARY 的原创几何海报，仅供截图工具使用。"""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as error:
        raise RuntimeError("--with-images 需要 Pillow；默认认证与播放测试不依赖 Pillow。") from error

    directory = output / "ui-posters"
    directory.mkdir(parents=True, exist_ok=True)
    palettes = (
        ((38, 93, 101), (164, 191, 170), (235, 219, 161)),
        ((43, 48, 77), (112, 115, 155), (224, 159, 121)),
        ((177, 105, 69), (232, 180, 127), (248, 228, 168)),
        ((68, 92, 121), (168, 189, 191), (224, 207, 173)),
        ((81, 109, 94), (174, 189, 147), (240, 220, 174)),
        ((109, 74, 92), (183, 134, 143), (242, 204, 177)),
        ((54, 94, 123), (126, 171, 184), (238, 218, 175)),
        ((75, 68, 112), (145, 137, 175), (231, 191, 137)),
    )
    identifiers = [f"preview-movie-{index:02d}" for index in range(1, 9)] + [
        "preview-library-movies", "preview-library-shows", "preview-library-documentaries",
    ]
    try:
        font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
        label_font = ImageFont.truetype(font_path, 20)
        title_font = ImageFont.truetype(font_path, 42)
        number_font = ImageFont.truetype(font_path, 88)
    except OSError:
        label_font = title_font = number_font = ImageFont.load_default()

    images = {}
    for index, identifier in enumerate(identifiers):
        dark, light, accent = palettes[index % len(palettes)]
        poster = Image.new("RGB", (420, 630), dark)
        draw = ImageDraw.Draw(poster)
        for y in range(630):
            fraction = y / 629
            color = tuple(round(a + (b - a) * fraction) for a, b in zip(light, dark))
            draw.line((0, y, 419, y), fill=color)
        sun_x = 95 + (index % 3) * 100
        sun_y = 185 + (index % 2) * 65
        draw.ellipse((sun_x - 64, sun_y - 64, sun_x + 64, sun_y + 64), fill=accent)
        ridge = [(0, 355), (95, 245 + index % 3 * 30), (180, 330), (295, 225), (420, 345), (420, 630), (0, 630)]
        draw.polygon(ridge, fill=tuple(round(channel * 0.76) for channel in dark))
        draw.polygon([(0, 440), (145, 355), (265, 410), (420, 325), (420, 630), (0, 630)],
                     fill=tuple(round(channel * 0.54) for channel in dark))
        draw.text((28, 26), "PATHFIN / UI PREVIEW", fill=(245, 244, 235), font=label_font)
        draw.text((28, 438), "TEST", fill=(245, 244, 235), font=title_font)
        draw.text((28, 486), "LIBRARY", fill=(245, 244, 235), font=title_font)
        draw.text((292, 520), f"{index + 1:02d}", fill=accent, font=number_font)
        path = directory / f"{identifier}.png"
        poster.save(path, format="PNG", optimize=True)
        images[identifier] = path
    for name, ink in (("preview-white-logo", (255, 255, 255, 255)), ("preview-dark-logo", (18, 24, 32, 255))):
        logo = Image.new("RGBA", (640, 180), (0, 0, 0, 0))
        ink_draw = ImageDraw.Draw(logo)
        ink_draw.text((12, 8), "PATHFIN", fill=ink, font=number_font)
        ink_draw.text((16, 112), "WHITE / DARK LOGO TEST", fill=ink, font=label_font)
        path = directory / f"{name}.png"
        logo.save(path, format="PNG", optimize=True)
        images[name] = path
    return images


def prepare_benchmark_images(output: Path, count: int) -> dict[str, Path]:
    """独立 URL、独立像素的可重复解码负载，不用重复小图冒充媒体库滚动。"""
    from PIL import Image, ImageDraw

    directory = output / "benchmark-posters-v1"
    directory.mkdir(parents=True, exist_ok=True)
    images = {}
    for index in range(count):
        identifier = f"benchmark-movie-{index + 1:04d}"
        path = directory / f"{identifier}.jpg"
        if not path.is_file():
            pixels = random.Random(index).randbytes(420 * 630 * 3)
            poster = Image.frombytes("RGB", (420, 630), pixels)
            overlay = Image.new("RGB", poster.size, ((index * 47) % 180, (index * 71) % 180, (index * 19) % 180))
            poster = Image.blend(poster, overlay, 0.35)
            draw = ImageDraw.Draw(poster)
            draw.rectangle((20, 460, 400, 600), fill=(15, 20, 30))
            draw.text((36, 490), f"PATHFIN BENCHMARK / {index + 1:04d}", fill=(255, 255, 255))
            poster.save(path, format="JPEG", quality=86, optimize=True)
        images[identifier] = path
    return images


class FixtureState:
    def __init__(self, args: argparse.Namespace, sample: Path, preview_images: dict[str, Path] | None = None, *,
                 benchmark_items: int = 0, api_delay_ms: int = 0, image_delay_ms: int = 0) -> None:
        self.bind_address = args.bind_address
        self.api_port = args.api_port
        self.media_port = args.media_port
        self.media_scheme = "https" if args.media_tls_cert else "http"
        self.base_path = "/" + args.base_path.strip("/") if args.base_path.strip("/") else ""
        self.media_route = args.media_route
        self.sample = sample
        self.preview_images = preview_images or {}
        self.benchmark_items = benchmark_items
        self.api_delay_ms = api_delay_ms
        self.image_delay_ms = image_delay_ms
        self.benchmark_images = prepare_benchmark_images(args.output, self.benchmark_items) if self.benchmark_items else {}
        inventory = [(key, hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_size)
                     for key, path in self.benchmark_images.items()]
        self.benchmark_image_bytes = sum(row[2] for row in inventory)
        self.benchmark_digest = hashlib.sha256(json.dumps(inventory).encode()).hexdigest()
        self.log_path = args.output / "requests.jsonl"
        self.run_id = uuid.uuid4().hex[:12]
        self.position_ticks = 30_000_000
        self.is_favorite = False
        self.played = False
        self.play_count = 0
        self.play_session_count = 0
        self.lock = threading.Lock()

    def log(self, service: str, route: str, method: str, headers, **fields) -> None:
        header_flags = {name: bool(headers.get(name)) for name in SENSITIVE_HEADER_NAMES}
        record = {
            "time": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "monotonic_ns": time.monotonic_ns(),
            "run_id": self.run_id,
            "service": service,
            "method": method,
            "route": route,
            "auth_header_present": header_flags,
            **fields,
        }
        with self.lock:
            with self.log_path.open("a", encoding="utf-8") as destination:
                destination.write(json.dumps(record, ensure_ascii=False) + "\n")
        if fields.get("credential_leak"):
            print("ERROR: 媒体请求携带了 Jellyfin/Authorization 鉴权头；详情见 requests.jsonl。", flush=True)

    def movie(self) -> dict:
        with self.lock:
            user_data = self._user_data_locked()
        return {
            "Id": ITEM_ID,
            "Name": "Pathfin · 原始路径播放测试",
            "Type": "Movie",
            "IsFolder": False,
            "Overview": "由本机生成的 15 秒 H.264/AAC 测试图与正弦音。此数据由开发测试服务提供，并非真实 Jellyfin 验收。",
            "ProductionYear": 2026,
            "PremiereDate": "2026-01-01T00:00:00Z",
            "DateCreated": "2026-01-02T00:00:00Z",
            "Genres": [FIXTURE_GENRE],
            "Tags": [FIXTURE_TAG],
            "RunTimeTicks": DURATION_TICKS,
            "ImageTags": {},
            "MediaSourceCount": 1,
            "UserData": user_data,
        }

    def _user_data_locked(self) -> dict:
        """调用者持有状态锁，保证收藏、已看和进度来自同一个快照。"""
        return {
            "IsFavorite": self.is_favorite,
            "Played": self.played,
            "PlaybackPositionTicks": self.position_ticks,
            "PlayedPercentage": 100.0 if self.played else self.position_ticks / DURATION_TICKS * 100,
            "PlayCount": self.play_count,
        }

    def movies(self) -> list[dict]:
        if not self.benchmark_items:
            return [self.movie()]
        template = self.movie()
        return [dict(template, Id=f"benchmark-movie-{index + 1:04d}",
                     Name=f"Benchmark Movie {index + 1:04d}", SortName=f"Benchmark Movie {index + 1:04d}",
                     ProductionYear=1990 + index % 37, CommunityRating=5.0 + index % 50 / 10,
                     PremiereDate=f"{1990 + index % 37}-01-01T00:00:00Z",
                     DateCreated=f"2026-01-{1 + index % 28:02d}T{index % 24:02d}:00:00Z",
                     ImageTags={"Primary": "benchmark-v1"}, PrimaryImageAspectRatio=2 / 3)
                for index in range(self.benchmark_items)]

    def update_user_data(self, operation: str, enabled: bool) -> dict:
        with self.lock:
            if operation == "favorite":
                self.is_favorite = enabled
            elif operation == "played":
                self.played = enabled
                self.play_count = max(1, self.play_count) if enabled else 0
                self.position_ticks = 0
            else:
                raise ValueError("Unknown fixture user-data operation")
            return self._user_data_locked()

    @staticmethod
    def genre() -> dict:
        return {
            "Id": GENRE_ID, "Name": FIXTURE_GENRE, "Type": "Genre",
            "IsFolder": True, "ChildCount": 1, "ImageTags": {},
        }

    @staticmethod
    def library() -> dict:
        return {
            "Id": LIBRARY_ID, "Name": "测试电影", "Type": "CollectionFolder",
            "IsFolder": True, "CollectionType": "movies", "ImageTags": {},
        }

    def playback_info(self) -> dict:
        with self.lock:
            self.play_session_count += 1
            session_id = f"fixture-session-{self.play_session_count}"
        return {
            "PlaySessionId": session_id,
            "MediaSources": [{
                "Id": SOURCE_ID,
                "Name": "原始 STRM HTTP 测试源",
                "Path": f"{self.media_scheme}://{self.bind_address}:{self.media_port}/{self.media_route}",
                "Protocol": "Http",
                "Container": "strm",
                "IsRemote": True,
                "RunTimeTicks": DURATION_TICKS,
                "SupportsDirectPlay": True,
                "SupportsDirectStream": True,
                "SupportsTranscoding": False,
                "RequiredHttpHeaders": {},
                "MediaStreams": [
                    {"Type": "Video", "Codec": "h264", "Index": 0, "Width": 640, "Height": 360},
                    {"Type": "Audio", "Codec": "aac", "Index": 1, "Channels": 1, "Language": "und"},
                ],
            }],
        }


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port: int, handler: type[BaseHTTPRequestHandler], state: FixtureState) -> None:
        self.state = state
        super().__init__((state.bind_address, port), handler)


class BaseHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def state(self) -> FixtureState:
        return self.server.state

    def log_message(self, format: str, *args) -> None:
        # 禁用默认日志，避免把完整查询字符串或授权头写到终端。
        return

    def send_json(self, value: dict | list[dict], status: int = 200, head: bool = False) -> None:
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def read_json(self) -> dict | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 1_048_576:
                self.send_json({"Error": "Invalid request body length"}, 400)
                self.close_connection = True
                return None
            body = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(body, dict):
                raise ValueError("Object required")
            return body
        except (ValueError, UnicodeDecodeError):
            self.send_json({"Error": "Invalid JSON object"}, 400)
            return None

    def serve_sample(self, head: bool = False) -> None:
        """复用同一份 Range 实现；鉴权由 API/外部媒体端点各自处理。"""
        size = self.state.sample.stat().st_size
        start, end = 0, size - 1
        range_header = self.headers.get("Range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
            try:
                if match is None or not any(match.groups()):
                    raise ValueError("Invalid range")
                left, right = match.groups()
                if left:
                    start = int(left)
                    end = min(int(right), size - 1) if right else size - 1
                else:
                    suffix = int(right)
                    if suffix <= 0:
                        raise ValueError("Empty suffix")
                    start, end = max(0, size - suffix), size - 1
                if start >= size or start > end:
                    raise ValueError("Unsatisfiable range")
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        self.send_response(206 if range_header else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(end - start + 1))
        if range_header:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head:
            return
        try:
            with self.state.sample.open("rb") as media:
                media.seek(start)
                remaining = end - start + 1
                while remaining:
                    chunk = media.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            # Seek 或播放器退出会主动取消旧 HTTP 读取。
            return


class ApiHandler(BaseHandler):
    def _route(self) -> tuple[str, dict]:
        parsed = urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        prefix = self.state.base_path
        if prefix:
            if path == prefix:
                path = "/"
            elif path.startswith(prefix + "/"):
                path = path[len(prefix):]
            else:
                path = "/unknown"
        return path, parse_qs(parsed.query)

    def _authorization_parameters(self) -> dict[str, str] | None:
        # 严格模拟 Jellyfin 12 默认配置：旧 X-Emby 认证不再填充客户端身份。
        if any(self.headers.get_all(name) for name in LEGACY_HEADER_NAMES):
            return None
        authorization = self.headers.get_all("Authorization", [])
        if len(authorization) != 1:
            return None
        value = authorization[0]
        parameter = r'[A-Za-z][A-Za-z0-9]*="[^"\r\n]*"'
        if not re.fullmatch(rf"MediaBrowser\s+{parameter}(?:\s*,\s*{parameter})*", value, re.IGNORECASE):
            return None
        pairs = re.findall(r'([A-Za-z][A-Za-z0-9]*)="([^"\r\n]*)"', value)
        parameters = {name.lower(): unquote(content) for name, content in pairs}
        if len(parameters) != len(pairs):
            return None
        if not all(parameters.get(name, "").strip() for name in ("client", "device", "deviceid", "version")):
            return None
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", parameters["deviceid"]):
            return None
        return parameters

    def _check_auth(self) -> bool:
        parameters = self._authorization_parameters()
        if parameters is None:
            self.send_json({"Error": "Standard MediaBrowser Authorization identity required"}, 400)
            return False
        if parameters.get("token") == TEST_TOKEN:
            return True
        self.send_json({"Error": "Fixture authentication required"}, 401)
        return False

    def _movie_items(self, query: dict) -> list[dict]:
        items = self.state.movies()

        def value(key: str) -> str:
            return query.get(key, [""])[0]

        if value("ids"):
            items = [item for item in items if item["Id"] in value("ids").split(",")]
        if value("parentId") and value("parentId") != LIBRARY_ID:
            items = []
        if value("includeItemTypes") and "Movie" not in value("includeItemTypes").split(","):
            items = []
        if value("searchTerm"):
            items = [item for item in items if value("searchTerm").casefold() in item["Name"].casefold()]
        for parameter, field in (("isFavorite", "IsFavorite"), ("isPlayed", "Played")):
            if parameter in query:
                boolean = value(parameter).casefold()
                if boolean not in ("true", "false"):
                    raise ValueError("Invalid user-data filter")
                items = [item for item in items if item["UserData"][field] == (boolean == "true")]
        filters = set(value("filters").split(","))
        if "IsResumable" in filters:
            items = [item for item in items if not item["UserData"]["Played"] and 0 < item["UserData"]["PlaybackPositionTicks"] < DURATION_TICKS]
        for parameter, field, expected in (
            ("IsFavorite", "IsFavorite", True), ("IsNotFavorite", "IsFavorite", False),
            ("IsPlayed", "Played", True), ("IsUnplayed", "Played", False),
        ):
            if parameter in filters:
                items = [item for item in items if item["UserData"][field] == expected]
        for parameter, field in (("genres", "Genres"), ("tags", "Tags")):
            if value(parameter):
                selected = {entry.casefold() for entry in value(parameter).split("|")}
                items = [item for item in items if selected.intersection(entry.casefold() for entry in item[field])]
        if value("years"):
            items = [item for item in items if str(item["ProductionYear"]) in value("years").split(",")]
        if value("genreIds") and GENRE_ID not in value("genreIds").split(","):
            items = []
        if any(value(parameter) for parameter in ("personIds", "studioIds", "officialRatings", "seriesStatus")):
            items = []
        if value("minPremiereDate"):
            try:
                minimum = datetime.fromisoformat(value("minPremiereDate").replace("Z", "+00:00"))
                items = [item for item in items if datetime.fromisoformat(item["PremiereDate"].replace("Z", "+00:00")) >= minimum]
            except (ValueError, TypeError) as error:
                raise ValueError("Invalid premiere-date filter") from error
        if self.state.benchmark_items:
            field = {"SortName": "SortName", "DateCreated": "DateCreated", "PremiereDate": "PremiereDate",
                     "ProductionYear": "ProductionYear", "CommunityRating": "CommunityRating"}.get(value("sortBy").split(",")[0], "SortName")
            items.sort(key=lambda item: (item[field], item["Id"]), reverse=value("sortOrder").casefold() == "descending")
        return items

    def _send_page(self, items: list[dict], query: dict, count_current_page: bool = False) -> None:
        try:
            start = max(0, int(query.get("startIndex", ["0"])[0]))
            limit = min(100, max(1, int(query.get("limit", ["40"])[0])))
        except ValueError:
            self.send_json({"Error": "Invalid pagination"}, 400)
            return
        count_enabled = query.get("enableTotalRecordCount", ["true"])[0].casefold()
        if count_enabled not in ("true", "false"):
            self.send_json({"Error": "Invalid total-count option"}, 400)
            return
        page = items[start:start + limit]
        total = len(page) if count_current_page and count_enabled == "false" else len(items)
        self.send_json({"Items": page, "TotalRecordCount": total, "StartIndex": start})

    def _send_latest(self, items: list[dict], query: dict) -> None:
        try:
            limit = min(100, max(1, int(query.get("limit", ["20"])[0])))
        except ValueError:
            self.send_json({"Error": "Invalid latest limit"}, 400)
            return
        if query.get("groupItems", ["true"])[0].casefold() not in ("true", "false"):
            self.send_json({"Error": "Invalid latest grouping"}, 400)
            return
        # fixture 仅有电影，不伪造 Series 分组；真实分组契约由隔离 Jellyfin 检查。
        self.send_json(items[:limit])

    def _send_user_data(self, route: str, query: dict, method: str) -> None:
        self.state.log("api", route, method, self.headers)
        if query.get("userId", [""])[0] != USER_ID:
            self.send_json({"Error": "Fixture user not found"}, 404)
            return
        operation = "favorite" if route.startswith("/UserFavoriteItems/") else "played"
        self.send_json(self.state.update_user_data(operation, method == "POST"))

    def do_GET(self) -> None:
        route, query = self._route()
        if route == EXTERNAL_MEDIA_ROUTE:
            self.serve_external_media(head=False)
            return
        # 截图专用只读图片入口；原始播放 fixture 的认证和媒体路由保持独立。
        remote_preview = re.fullmatch(r"/preview-images/(preview-[a-z0-9-]+)", route)
        if remote_preview and remote_preview.group(1) in self.state.preview_images:
            if any(self.headers.get(name) for name in SENSITIVE_HEADER_NAMES):
                self.send_json({"Error": "Remote preview received credentials"}, 403)
            else:
                self._send_preview_image(self.state.preview_images[remote_preview.group(1)])
            return
        image_match = re.fullmatch(r"/Items/(preview-[a-z0-9-]+)/Images/(Primary|Logo|Backdrop|Thumb)(?:/\d+)?", route)
        image_key = ("preview-white-logo" if image_match and image_match.group(2) == "Logo" else image_match.group(1)) if image_match else None
        preview_image = self.state.preview_images.get(image_key)
        benchmark_match = re.fullmatch(r"/Items/(benchmark-movie-[0-9]{4})/Images/(Primary|Backdrop|Thumb)(?:/\d+)?", route)
        benchmark_image = self.state.benchmark_images.get(benchmark_match.group(1)) if benchmark_match else None
        preview_item = re.fullmatch(r"/Items/(preview-movie-[0-9]+)(/.*)?", route)
        similar_route = f"/Items/{ITEM_ID}/Similar"
        video_route = f"/Videos/{ITEM_ID}/stream"
        known = route in ("/", "/health", "/System/Info/Public", "/Users/Me", "/UserViews", "/Items", "/Items/Latest", "/UserItems/Resume",
                          "/Items/Filters", "/Genres", "/Persons", "/Shows/NextUp", similar_route, video_route)
        logged_route = "/Items/{benchmark}/Images/Primary" if benchmark_image else "/Items/{ui-preview}/Images/Primary" if preview_image else route if known else "/unknown"
        fields = {}
        if self.state.benchmark_items:
            fields = {"benchmark": True, "parent_is_library": query.get("parentId", [""])[0] == LIBRARY_ID,
                      "start_index": query.get("startIndex", [None])[0], "limit": query.get("limit", [None])[0],
                      "is_detail": bool(query.get("ids")), "image_bytes": benchmark_image.stat().st_size if benchmark_image else 0}
        self.state.log("api", logged_route, "GET", self.headers, **fields)
        if route in ("/", "/health", "/System/Info/Public"):
            info = {"Id": SERVER_ID, "ServerName": "Pathfin development fixture", "Version": "12.3.0", "Fixture": True}
            if self.state.benchmark_items:
                info.update(BenchmarkItems=self.state.benchmark_items, BenchmarkImageBytes=self.state.benchmark_image_bytes,
                            BenchmarkSha256=self.state.benchmark_digest, ApiDelayMs=self.state.api_delay_ms,
                            ImageDelayMs=self.state.image_delay_ms, RunId=self.state.run_id)
            self.send_json(info)
            return
        if not self._check_auth():
            return
        if self.state.benchmark_items:
            time.sleep((self.state.image_delay_ms if benchmark_image else self.state.api_delay_ms) / 1000)
        if route == video_route:
            self.serve_sample()
        elif preview_image:
            self._send_preview_image(preview_image)
        elif benchmark_image:
            self._send_preview_image(benchmark_image, cache=True)
        elif preview_item and preview_item.group(1) in self.state.preview_images:
            identifier, tail = preview_item.group(1), preview_item.group(2) or ""
            if not tail:
                self.send_json({"Id": identifier, "Name": "Pathfin image preview", "Type": "Movie", "CanDelete": True})
            elif tail == "/ExternalIdInfos":
                self.send_json([{"Name": "TheMovieDb", "Key": "Tmdb"}, {"Name": "IMDb", "Key": "Imdb"}])
            elif tail == "/Images":
                self.send_json([{"ImageType": kind, "ImageIndex": index, "ImageTag": "preview-v1", "Width": width, "Height": height, "Size": 131072}
                                for kind, index, width, height in (("Primary", 0, 420, 630), ("Logo", 0, 640, 180), ("Backdrop", 0, 1260, 708), ("Backdrop", 1, 1260, 708))])
            elif tail == "/RemoteImages/Providers":
                self.send_json([{"Name": name, "SupportedImages": ["Primary", "Backdrop", "Logo", "Thumb", "Banner", "Disc", "Art"]}
                                for name in ("Fixture Studio", "Fixture Archive")])
            elif tail == "/RemoteImages":
                kind = query.get("type", ["Primary"])[0]
                provider = query.get("providerName", [None])[0]
                names = [provider] if provider in ("Fixture Studio", "Fixture Archive") else ["Fixture Studio", "Fixture Archive"]
                results = []
                for index in range(8):
                    image_id = f"preview-movie-{index + 1:02d}" if kind != "Logo" else "preview-white-logo"
                    origin = f"http://{self.state.bind_address}:{self.state.api_port}{self.state.base_path}"
                    results.append({"Type": kind, "ProviderName": names[index % len(names)], "Width": 420, "Height": 630,
                                    "Language": "zh", "Url": f"{origin}/preview-images/{image_id}?variant={index}",
                                    "ThumbnailUrl": f"{origin}/preview-images/{image_id}?thumb={index}"})
                start = max(0, int(query.get("startIndex", ["0"])[0]))
                limit = min(48, max(1, int(query.get("limit", ["24"])[0])))
                self.send_json({"Images": results[start:start + limit], "TotalRecordCount": len(results)})
            else:
                self.send_json({"Error": "Preview operation is not supported"}, 404)
        elif route == "/Users/Me":
            self.send_json({"Id": USER_ID, "Name": "pathfin", "ServerId": SERVER_ID})
        elif route == "/UserViews":
            self.send_json({"Items": [self.state.library()], "TotalRecordCount": 1})
        elif route in ("/Items/Latest", "/UserItems/Resume"):
            if query.get("userId", [""])[0] != USER_ID:
                self.send_json({"Error": "Fixture user not found"}, 404)
                return
            try:
                items = self._movie_items(query)
            except ValueError as error:
                self.send_json({"Error": str(error)}, 400)
                return
            if route == "/Items/Latest":
                self._send_latest(items, query)
            else:
                items = [item for item in items if not item["UserData"]["Played"] and
                         0 < item["UserData"]["PlaybackPositionTicks"] < DURATION_TICKS]
                self._send_page(items, query, count_current_page=True)
        elif route in ("/Items", "/Items/Filters", "/Genres"):
            try:
                items = self._movie_items(query)
            except ValueError as error:
                self.send_json({"Error": str(error)}, 400)
                return
            if route == "/Items":
                self._send_page(items, query)
            elif route == "/Genres":
                self._send_page([self.state.genre()] if items else [], query)
            else:
                self.send_json({
                    "Genres": sorted({genre for item in items for genre in item["Genres"]}),
                    "Tags": sorted({tag for item in items for tag in item["Tags"]}),
                    "Years": sorted({item["ProductionYear"] for item in items}, reverse=True),
                    "OfficialRatings": [],
                })
        elif route in ("/Persons", "/Shows/NextUp", similar_route):
            self._send_page([], query)
        else:
            self.send_json({"Error": "Fixture route not found"}, 404)

    def _send_preview_image(self, image: Path, cache: bool = False) -> None:
        body = image.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg" if image.suffix == ".jpg" else "image/png")
        self.send_header("Cache-Control", "public, max-age=3600" if cache else "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self) -> None:
        route, _ = self._route()
        if route == EXTERNAL_MEDIA_ROUTE:
            self.serve_external_media(head=True)
        else:
            self.send_json({"Error": "Fixture route not found"}, 404, head=True)

    def serve_external_media(self, head: bool) -> None:
        # LAN 重定向通过 API 端口形成不同 origin，但仍只暴露本机合成媒体。
        leak = any(self.headers.get(name) for name in SENSITIVE_HEADER_NAMES)
        self.state.log("media", EXTERNAL_MEDIA_ROUTE, "HEAD" if head else "GET", self.headers,
                       credential_leak=leak, range_requested=bool(self.headers.get("Range")))
        if leak:
            self.send_json({"Error": "Jellyfin credentials reached an external media origin"}, 403, head)
            return
        self.serve_sample(head)

    def do_POST(self) -> None:
        route, query = self._route()
        body = self.read_json()
        if body is None:
            return
        playback_route = f"/Items/{ITEM_ID}/PlaybackInfo"
        event_routes = {"/Sessions/Playing": "start", "/Sessions/Playing/Progress": "progress", "/Sessions/Playing/Stopped": "stop"}
        user_data_routes = (f"/UserFavoriteItems/{ITEM_ID}", f"/UserPlayedItems/{ITEM_ID}")
        known = route == "/Users/AuthenticateByName" or route == playback_route or route in event_routes or route in user_data_routes
        if route == "/Users/AuthenticateByName":
            self.state.log("api", route, "POST", self.headers)
            if self._authorization_parameters() is None:
                self.send_json({"Error": "Standard MediaBrowser Authorization identity required"}, 400)
                return
            if body.get("Username") != "pathfin" or body.get("Pw") != "pathfin":
                self.send_json({"Error": "Invalid fixture credentials"}, 401)
                return
            self.send_json({
                "AccessToken": TEST_TOKEN, "ServerId": SERVER_ID,
                "User": {"Id": USER_ID, "Name": "pathfin"},
                "SessionInfo": {"Id": "fixture-auth-session"},
            })
            return
        if not self._check_auth():
            self.state.log("api", route if known else "/unknown", "POST", self.headers, unauthorized=True)
            return
        if route in user_data_routes:
            self._send_user_data(route, query, "POST")
        elif route == playback_route:
            self.state.log("api", route, "POST", self.headers)
            if body.get("MediaSourceId") not in (None, "", SOURCE_ID):
                self.send_json({"Error": "Fixture media source not found"}, 404)
                return
            self.send_json(self.state.playback_info())
        elif route in event_routes:
            try:
                ticks = int(body.get("PositionTicks", 0))
                if ticks < 0 or ticks > 2**63 - 1:
                    raise ValueError("Invalid ticks")
            except (ValueError, TypeError):
                self.send_json({"Error": "Invalid playback ticks"}, 400)
                return
            item_matches = body.get("ItemId") == ITEM_ID
            source_matches = body.get("MediaSourceId") == SOURCE_ID
            session_id = body.get("PlaySessionId", "")
            session_matches = isinstance(session_id, str) and bool(re.fullmatch(r"fixture-session-\d+", session_id))
            self.state.log(
                "api", route, "POST", self.headers,
                event=event_routes[route], position_ticks=ticks,
                is_paused=bool(body.get("IsPaused", False)),
                item_matches=item_matches, source_matches=source_matches,
                session_matches=session_matches,
                play_session_id=session_id if session_matches else "invalid",
            )
            if not (item_matches and source_matches and session_matches):
                self.send_json({"Error": "Playback identity mismatch"}, 400)
                return
            with self.state.lock:
                self.state.position_ticks = min(ticks, DURATION_TICKS)
                if ticks >= DURATION_TICKS:
                    self.state.played = True
                    self.state.play_count = max(1, self.state.play_count)
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            self.state.log("api", "/unknown", "POST", self.headers)
            self.send_json({"Error": "Fixture route not found"}, 404)

    def do_DELETE(self) -> None:
        route, query = self._route()
        if self.read_json() is None:
            return
        known = route in (f"/UserFavoriteItems/{ITEM_ID}", f"/UserPlayedItems/{ITEM_ID}")
        if not self._check_auth():
            self.state.log("api", route if known else "/unknown", "DELETE", self.headers, unauthorized=True)
            return
        if known:
            self._send_user_data(route, query, "DELETE")
        else:
            self.state.log("api", "/unknown", "DELETE", self.headers)
            self.send_json({"Error": "Fixture route not found"}, 404)


class MediaHandler(BaseHandler):
    def do_GET(self) -> None:
        self.serve_media(head=False)

    def do_HEAD(self) -> None:
        self.serve_media(head=True)

    def serve_media(self, head: bool) -> None:
        route = urlsplit(self.path).path
        known = route in ("/sample.mp4", "/redirect.mp4", "/redirect-cross-origin.mp4", "/expired.mp4", "/missing.mp4")
        leak = any(self.headers.get(name) for name in SENSITIVE_HEADER_NAMES)
        self.state.log("media", route if known else "/unknown", "HEAD" if head else "GET", self.headers,
                       credential_leak=leak, range_requested=bool(self.headers.get("Range")))
        if leak:
            self.send_json({"Error": "Jellyfin credentials reached an external media origin"}, 403, head)
            return
        if route in ("/redirect.mp4", "/redirect-cross-origin.mp4"):
            if route == "/redirect.mp4":
                target = "/sample.mp4"
            elif self.state.bind_address == "127.0.0.1":
                target = f"{self.state.media_scheme}://localhost:{self.state.media_port}/sample.mp4"
            else:
                target = f"http://{self.state.bind_address}:{self.state.api_port}{self.state.base_path}{EXTERNAL_MEDIA_ROUTE}"
            self.send_response(302)
            self.send_header("Location", target)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if route == "/expired.mp4":
            self.send_json({"Error": "Fixture expired URL"}, 403, head)
            return
        if route != "/sample.mp4":
            self.send_json({"Error": "Fixture media not found"}, 404, head)
            return
        self.serve_sample(head)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Pathfin 开发专用假 Jellyfin + 外部 HTTP 媒体服务；不代表真实服务验收。",
        epilog=(
            "示例：python3 android/tools/fixture_server.py --prepare-only\n"
            "      python3 android/tools/fixture_server.py --api-port 8097 --media-port 8098\n"
            "      python3 android/tools/fixture_server.py --bind-address 192.168.1.10\n"
            "测试账号：pathfin / pathfin。默认回环模式需转发 API 和媒体端口；LAN 模式使用显式接口地址。\n"
            "API 与媒体默认只绑定 127.0.0.1，不接受 wildcard/公网绑定；只提供合成测试数据，不读取用户影片。\n"
            "日志仅写请求路由、鉴权头是否存在和测试进度，不记录密码或完整 URL。"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--bind-address", type=parse_bind_address, default="127.0.0.1",
                        help="显式绑定回环或 RFC1918 私网 IPv4 接口，默认 127.0.0.1；不支持 0.0.0.0")
    parser.add_argument("--api-port", type=int, default=8097, help="假 Jellyfin API 端口，默认 8097")
    parser.add_argument("--media-port", type=int, default=8098, help="独立媒体端口，默认 8098")
    parser.add_argument("--media-tls-cert", type=Path, help="本地合成媒体 HTTPS 证书；只允许回环绑定")
    parser.add_argument("--media-tls-key", type=Path, help="对应合成媒体 HTTPS 私钥，不用于生产")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / ".superpowers/tmp/fixtures",
                        help="自制媒体和 requests.jsonl 的目录，默认项目 .superpowers/tmp/fixtures/")
    parser.add_argument("--base-path", default="", help="可选 API 子路径，例如 /jellyfin")
    parser.add_argument("--media-route", choices=("sample.mp4", "redirect.mp4", "redirect-cross-origin.mp4", "expired.mp4", "missing.mp4"),
                        default="sample.mp4", help="PlaybackInfo.Path 使用的媒体测试路由")
    parser.add_argument("--prepare-only", action="store_true", help="只生成 15 秒 H.264/AAC 媒体，不启动服务")
    parser.add_argument("--with-images", action="store_true", help="额外生成并提供 UI 截图测试海报（需要 Pillow）")
    parser.add_argument("--benchmark-items", type=int, default=0, help="启用 240–2000 项合成电影及独立海报的滚动基准；默认关闭，需要 Pillow")
    parser.add_argument("--api-delay-ms", type=int, default=0, help="基准 API 固定延迟，默认 0ms")
    parser.add_argument("--image-delay-ms", type=int, default=0, help="基准海报固定延迟，默认 0ms")
    args = parser.parse_args()
    if not (1 <= args.api_port <= 65535 and 1 <= args.media_port <= 65535):
        parser.error("端口必须在 1–65535 之间")
    if args.api_port == args.media_port:
        parser.error("API 和媒体必须使用不同端口，以验证凭据隔离")
    if args.benchmark_items != 0 and not 240 <= args.benchmark_items <= 2000:
        parser.error("benchmark-items 必须为 0 或 240–2000")
    if not all(0 <= delay <= 5000 for delay in (args.api_delay_ms, args.image_delay_ms)):
        parser.error("基准延迟必须为 0–5000ms")
    if bool(args.media_tls_cert) != bool(args.media_tls_key):
        parser.error("media-tls-cert 与 media-tls-key 必须同时提供")
    if args.media_tls_cert and args.bind_address != "127.0.0.1":
        parser.error("本地 TLS 验证只允许回环绑定")
    if "?" in args.base_path or "#" in args.base_path or ".." in args.base_path.split("/"):
        parser.error("base-path 必须为普通 URL 路径")
    args.output = args.output.expanduser().resolve()
    return args


def main() -> int:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        sample = prepare_sample(args.output)
        preview_images = prepare_preview_images(args.output) if args.with_images else {}
    except (OSError, RuntimeError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1
    if args.prepare_only:
        print(f"已生成本机自制的 15 秒 H.264/AAC 测试媒体：{sample}")
        if preview_images:
            print(f"已生成 {len(preview_images)} 张 UI 测试海报：{args.output / 'ui-posters'}")
        return 0
    try:
        state = FixtureState(args, sample, preview_images, benchmark_items=args.benchmark_items,
                             api_delay_ms=args.api_delay_ms, image_delay_ms=args.image_delay_ms)
    except (ImportError, OSError, RuntimeError) as error:
        print(f"无法准备基准图片：{error}", file=sys.stderr)
        return 1
    servers = []
    try:
        servers.append(FixtureServer(args.api_port, ApiHandler, state))
        servers.append(FixtureServer(args.media_port, MediaHandler, state))
        if args.media_tls_cert:
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.minimum_version = ssl.TLSVersion.TLSv1_2
            tls.load_cert_chain(args.media_tls_cert, args.media_tls_key)
            servers[-1].socket = tls.wrap_socket(servers[-1].socket, server_side=True)
    except OSError as error:
        for server in servers:
            server.server_close()
        print(f"无法监听本机测试端口：{error}", file=sys.stderr)
        return 1
    stopped = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()
    print("开发测试服务已启动；此服务不等同于真实 Jellyfin 验收。", flush=True)
    print(f"API：http://{state.bind_address}:{args.api_port}{state.base_path}；媒体：{state.media_scheme}://{state.bind_address}:{args.media_port}", flush=True)
    print(f"测试账号：pathfin / pathfin；安全请求日志：{state.log_path}", flush=True)
    print("按 Ctrl+C 停止。", flush=True)
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

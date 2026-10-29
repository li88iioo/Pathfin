#!/usr/bin/env python3
"""不用 Gradle / ADB 验证新增合成夹具：真实清单、头部隔离、Range 与跨片解码。"""

from __future__ import annotations

import argparse
import http.client
import json
import shutil
import subprocess
import threading
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from fixture_server import FixtureServer
from prepare_manifest_fixture import FORMATS, MEDIA_KEY, ManifestApiHandler, ManifestMediaHandler, ManifestState, prepare


class ManifestFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.output = Path(__file__).resolve().parents[2] / ".superpowers/tmp/dev10/manifests"
        sample = prepare(cls.output)
        args = argparse.Namespace(bind_address="127.0.0.1", api_port=0, media_port=0, media_tls_cert=None,
                                  base_path="/jellyfin", media_route="sample.mp4", output=cls.output)
        cls.state = ManifestState(args, sample)
        cls.api = FixtureServer(0, ManifestApiHandler, cls.state)
        cls.media = FixtureServer(0, ManifestMediaHandler, cls.state)
        cls.state.api_port, cls.state.media_port = cls.api.server_port, cls.media.server_port
        cls.threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (cls.api, cls.media)]
        for thread in cls.threads:
            thread.start()

    @classmethod
    def tearDownClass(cls):
        for server in (cls.api, cls.media):
            server.shutdown()
            server.server_close()
        for thread in cls.threads:
            thread.join(timeout=2)

    def request(self, path: str, headers: dict | None = None, method="GET"):
        connection = http.client.HTTPConnection("127.0.0.1", self.media.server_port, timeout=3)
        try:
            connection.request(method, path, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_master_playlists_reference_two_languages_and_thirty_video_segments(self):
        master = (self.output / "hls/master.m3u8").read_text(encoding="utf-8")
        self.assertIn('LANGUAGE="en"', master)
        self.assertIn('LANGUAGE="ja"', master)
        playlist = (self.output / "hls/playlist_video.m3u8").read_text(encoding="utf-8")
        self.assertEqual(30, playlist.count("#EXTINF:4.000000,"))
        self.assertIn("#EXT-X-ENDLIST", playlist)
        root = ET.parse(self.output / "dash/manifest.mpd").getroot()
        namespace = {"d": "urn:mpeg:dash:schema:mpd:2011"}
        representations = root.findall(".//d:Representation", namespace)
        self.assertEqual(3, len(representations))
        languages = {item.attrib.get("lang") for item in root.findall(".//d:AdaptationSet", namespace)}
        self.assertTrue({"eng", "jpn"}.issubset(languages))
        timeline = representations[0].find("d:SegmentTemplate/d:SegmentTimeline/d:S", namespace)
        self.assertEqual("29", timeline.attrib["r"])

    def test_media_requires_source_key_rejects_identity_and_supports_real_ranges(self):
        self.state.reset("hls")
        self.assertEqual(403, self.request("/hls/master.m3u8")[0])
        headers = {"X-Media-Key": MEDIA_KEY}
        self.assertEqual(403, self.request("/hls/master.m3u8", {**headers, "Authorization": "fixture-identity-only"})[0])
        self.assertEqual(200, self.request("/hls/master.m3u8", headers)[0])
        status, response, data = self.request("/hls/segment_video_00000.ts", {**headers, "Range": "bytes=0-15"})
        self.assertEqual(206, status)
        self.assertEqual(16, len(data))
        self.assertTrue(response["Content-Range"].startswith("bytes 0-15/"))
        expected = (self.output / "hls/segment_video_00000.ts").read_bytes()
        self.assertEqual(expected[:16], data)
        self.assertEqual(expected[-8:], self.request("/hls/segment_video_00000.ts", {**headers, "Range": "bytes=-8"})[2])
        self.assertEqual(416, self.request("/hls/segment_video_00000.ts", {**headers, "Range": "bytes=999999999-"})[0])
        self.assertEqual(200, self.request("/hls/master.m3u8", headers, "HEAD")[0])
        self.assertEqual(404, self.request("/hls/../segmented-source.mp4", headers)[0])
        snapshot = self.state.status()["MediaRequests"]
        self.assertTrue(any(item["credential_leak"] and not item["successful"] for item in snapshot))
        self.assertTrue(any(item["range_present"] and not item["successful"] for item in snapshot))

    def test_both_http_manifests_decode_video_and_audio_after_seek_to_late_segment(self):
        ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
        self.assertIsNotNone(ffmpeg)
        self.assertIsNotNone(ffprobe)
        for format_name, (file_name, _) in FORMATS.items():
            with self.subTest(format=format_name):
                self.state.reset(format_name)
                uri = f"http://127.0.0.1:{self.media.server_port}/{format_name}/{file_name}"
                probe = subprocess.run([ffprobe, "-v", "error", "-headers", f"X-Media-Key: {MEDIA_KEY}\r\n", "-i", uri,
                                        "-show_entries", "stream=codec_type:stream_tags=language", "-of", "json"],
                                       capture_output=True, text=True, timeout=30, check=False)
                self.assertEqual(0, probe.returncode, probe.stderr)
                streams = json.loads(probe.stdout)["streams"]
                self.assertTrue(any(item["codec_type"] == "video" for item in streams))
                self.assertGreaterEqual(sum(item["codec_type"] == "audio" for item in streams), 2)
                result = subprocess.run([
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-threads", "2", "-ss", "84",
                    "-headers", f"X-Media-Key: {MEDIA_KEY}\r\n", "-i", uri, "-t", "0.5",
                    "-map", "0:v:0", "-map", "0:a:0", "-threads:v", "2", "-f", "null", "-",
                    "-progress", "pipe:1", "-nostats",
                ], capture_output=True, text=True, timeout=30, check=False)
                self.assertEqual(0, result.returncode, result.stderr)
                frames = [int(line.split("=", 1)[1].strip()) for line in result.stdout.splitlines() if line.startswith("frame=")]
                self.assertGreater(max(frames, default=0), 0, "必须实际解码目标位置的帧")
                requests = self.state.status()["MediaRequests"]
                self.assertTrue(any(item["video"] and item["kind"] == "segment" and item["segment"] in range(20, 23) for item in requests))
                self.assertTrue(all(item["successful"] and item["source_header_present"] and not item["credential_leak"] for item in requests))


if __name__ == "__main__":
    unittest.main()

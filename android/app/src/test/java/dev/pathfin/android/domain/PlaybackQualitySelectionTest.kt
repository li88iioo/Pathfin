package dev.pathfin.android.domain

import dev.pathfin.android.data.JellyfinApi
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackQualitySelectionTest {
    private val session = Session(ServerConfig("https://media.example/jellyfin"), "quality-fixture-token",
        "fixture-user", "Fixture", "fixture-device", serverId = "fixture-server")
    private val item = MediaItem("fixture-film", "Fixture film", "Movie")

    @Test fun boundedQualityNegotiatesBitrateAndDisablesUnchangedVideoDelivery() {
        val requested = AtomicReference<JSONObject>()
        val api = api { request -> requested.set(request) }
        for ((quality, bitrate) in listOf(PlaybackQuality.HIGH to 12_000_000,
                PlaybackQuality.STANDARD to 4_000_000, PlaybackQuality.DATA_SAVER to 1_500_000)) {
            runBlocking { api.playbackInfo(session, item.id, "version-two", 120_000_000, quality) }
            val body = requireNotNull(requested.get())
            assertEquals(bitrate, body.getInt("MaxStreamingBitrate"))
            assertEquals(bitrate, body.getJSONObject("DeviceProfile").getInt("MaxStreamingBitrate"))
            assertFalse("限码率时不能直放原文件", body.getBoolean("EnableDirectPlay"))
            assertFalse("限码率时不能优先原文件直接流", body.getBoolean("EnableDirectStream"))
            assertFalse("不能复制原视频后标记为低码率", body.getBoolean("AllowVideoStreamCopy"))
            assertTrue(body.getBoolean("EnableTranscoding"))
            assertEquals("version-two", body.getString("MediaSourceId"))
            assertEquals(120_000_000L, body.getLong("StartTimeTicks"))
            assertEquals(session.userId, body.getString("UserId"))
        }
    }

    @Test fun originalNegotiationKeepsDirectPlaybackAndDoesNotInventABitrateLimit() {
        val requested = AtomicReference<JSONObject>()
        val api = api { requested.set(it) }
        runBlocking { api.playbackInfo(session, item.id, startTimeTicks = -1) }
        val body = requireNotNull(requested.get())
        assertTrue(body.getBoolean("EnableDirectPlay"))
        assertTrue(body.getBoolean("EnableDirectStream"))
        assertTrue(body.getBoolean("AllowVideoStreamCopy"))
        assertTrue(body.getBoolean("EnableTranscoding"))
        assertFalse(body.has("MaxStreamingBitrate"))
        assertFalse(body.has("MediaSourceId"))
        assertEquals(0L, body.getLong("StartTimeTicks"))
    }

    @Test fun boundedQualityUsesOnlyTheSelectedVersionsTrustedTranscodingUrl() {
        val source = MediaSource("version-two", path = "https://cdn.example/original.mkv?signature=unchanged",
            container = "mpd", directStreamUrl = "/Videos/fixture-film/stream.mkv?Static=true",
            transcodingUrl = "/jellyfin/Videos/fixture-film/master.m3u8?api_key=quality-fixture-token&VideoBitrate=4000000",
            supportsDirectPlay = true, supportsDirectStream = true,
            requiredHttpHeaders = mapOf("Authorization" to "Bearer external-source-secret"))
        val info = PlaybackInfo(listOf(source), "play-session")
        for (quality in PlaybackQuality.entries.filter { it.maxBitrate != null }) {
            for (preferOriginal in listOf(true, false)) {
                val stream = PlaybackResolver.resolve(session, item, info, source.id, preferOriginal, quality)
                assertEquals(PlayMethod.TRANSCODE, stream.method)
                assertEquals(source.id, stream.sourceId)
                assertEquals("https://media.example/jellyfin/Videos/fixture-film/master.m3u8?VideoBitrate=4000000", stream.uri)
                assertEquals("application/x-mpegURL", stream.mimeType)
                assertTrue(stream.trustedServer)
                assertEquals(PlaybackHeaders.serverHeaders(session), stream.headers)
                assertFalse(stream.headers.values.any { "external-source-secret" in it })
                assertFalse(stream.uri.contains(session.accessToken))
            }
        }
    }

    @Test fun unavailableOrUntrustedTranscodingNeverFallsBackToOriginalOrDirectStream() {
        val invalid = listOf(null, "", "https://untrusted.example/master.m3u8",
            "//untrusted.example/master.m3u8", "/../outside/master.m3u8",
            "https://media.example/outside/master.m3u8", "/Videos/fixture-film/master.m3u8#fragment")
        for (quality in PlaybackQuality.entries.filter { it.maxBitrate != null }) {
            for (transcodingUrl in invalid) {
                val source = MediaSource("version", path = "https://cdn.example/original.mp4", protocol = "Http",
                    directStreamUrl = "/Videos/fixture-film/stream?Static=true", transcodingUrl = transcodingUrl,
                    supportsDirectPlay = true, supportsDirectStream = true)
                val error = assertThrows("$quality / $transcodingUrl", IllegalArgumentException::class.java) {
                    PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id,
                        preferOriginal = true, quality = quality)
                }
                assertTrue(error.message.orEmpty().contains("此播放质量"))
                assertFalse(error.message.orEmpty().contains(session.accessToken))
            }
        }
    }

    @Test fun anotherVersionsTranscodeAndServerErrorsCannotBypassTheSelectedVersion() {
        val selected = MediaSource("selected", path = "https://cdn.example/original.mp4", supportsDirectPlay = true)
        val other = MediaSource("other", transcodingUrl = "/Videos/fixture-film/master.m3u8")
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(selected, other), "play"), selected.id,
                quality = PlaybackQuality.STANDARD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(other), "play"), "missing-version",
                quality = PlaybackQuality.STANDARD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(other), "play", "NotAllowed"), other.id,
                quality = PlaybackQuality.STANDARD)
        }
    }

    @Test fun originalQualityStillHonorsOriginalPathPreferenceWithoutRewritingSignedSource() {
        val source = MediaSource("version", path = "https://cdn.example/a%2Fb/original.mp4?sig=Ab%2Bcd&x=1&x=2",
            protocol = "Http", supportsDirectPlay = true, directStreamUrl = "/Videos/fixture-film/stream?Static=true",
            transcodingUrl = "/Videos/fixture-film/master.m3u8")
        val info = PlaybackInfo(listOf(source), "play")
        val original = PlaybackResolver.resolve(session, item, info, source.id, quality = PlaybackQuality.ORIGINAL)
        assertEquals(source.path, original.uri)
        assertEquals(PlayMethod.DIRECT_PLAY, original.method)
        assertFalse(original.trustedServer)
        val server = PlaybackResolver.resolve(session, item, info, source.id, preferOriginal = false,
            quality = PlaybackQuality.ORIGINAL)
        assertEquals(PlayMethod.DIRECT_STREAM, server.method)
        assertTrue(server.trustedServer)
    }

    private fun api(onRequest: (JSONObject) -> Unit): JellyfinApi = JellyfinApi(OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/jellyfin/Items/${item.id}/PlaybackInfo", request.url.encodedPath)
            assertTrue(request.header("Authorization").orEmpty().contains("Token=\"${session.accessToken}\""))
            onRequest(JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""{"MediaSources":[],"PlaySessionId":"quality-play"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build())
}

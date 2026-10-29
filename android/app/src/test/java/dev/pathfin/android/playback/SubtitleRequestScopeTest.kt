package dev.pathfin.android.playback

import dev.pathfin.android.domain.*
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SubtitleRequestScopeTest {
    private val serverSubtitle = "https://jellyfin.example/jellyfin/Videos/item/source/Subtitles/4/Stream.srt"
    private val session = Session(ServerConfig("https://jellyfin.example/jellyfin"), "jf-private-token", "user", "User", "device")
    private val sourceHeaders = mapOf("Authorization" to "Bearer media-only", "X-Media-Key" to "media-key", "Referer" to "https://media.example/watch")
    private val source = MediaSource("source", path = "https://media.example/video.mp4", requiredHttpHeaders = sourceHeaders)
    private val playback = PlaybackRequest(MediaItem("item", "Fixture", "Movie"), PlaybackInfo(listOf(source), "session"), source,
        ResolvedPlayback(source.path!!, sourceHeaders, PlayMethod.DIRECT_PLAY, source.id, false), 0, session)

    @Test fun registeredJellyfinSubtitleGetsServerAuthEvenWhenVideoIsExternal() {
        val sent = intercept(serverSubtitle, serverSubtitle)
        assertTrue(checkNotNull(sent.header("Authorization")).contains("Token=\"jf-private-token\""))
        assertNull(sent.header("X-Media-Key")); assertNull(sent.header("Referer")); assertNull(sent.header("Cookie"))
    }

    @Test fun subtitleLookingPathWithoutExactOriginalCallMatchDoesNotGainServerAuth() {
        val sent = intercept(serverSubtitle.replace("/4/", "/5/"), serverSubtitle.replace("/4/", "/5/"))
        assertNull(sent.header("Authorization"))
        assertNull(sent.header("X-Media-Key"))
        val redirectedUnregistered = intercept("https://other.example/start", serverSubtitle)
        assertNull(redirectedUnregistered.header("Authorization"))
    }

    @Test fun registeredServerSubtitleRedirectsLoseIdentityAndMediaHeaders() {
        for (target in listOf("https://cdn.example/Stream.srt", "https://jellyfin.example/outside/Stream.srt", "https://media.example/Stream.srt")) {
            val sent = intercept(serverSubtitle, target)
            assertNull(sent.header("Authorization")); assertNull(sent.header("X-Media-Key")); assertNull(sent.header("Cookie"))
        }
    }

    @Test fun externalSubtitleCannotAdoptJellyfinIdentityAfterRedirectingBackToServer() {
        val initial = "https://subtitles.example/Stream.srt"
        val sent = intercept(initial, serverSubtitle, setOf(initial))
        assertNull(sent.header("Authorization")); assertNull(sent.header("X-Media-Key"))
    }

    @Test fun sourceHeadersOnlyApplyWhenSubtitleAndVideoActuallyShareOrigin() {
        val sameOrigin = "https://media.example/Stream.srt"
        val same = intercept(sameOrigin, sameOrigin, setOf(sameOrigin))
        assertEquals("Bearer media-only", same.header("Authorization"))
        assertEquals("media-key", same.header("X-Media-Key"))
        val otherOrigin = "https://subtitles.example/Stream.srt"
        val other = intercept(otherOrigin, otherOrigin, setOf(otherOrigin))
        assertNull(other.header("Authorization")); assertNull(other.header("X-Media-Key")); assertNull(other.header("Referer"))
        val redirect = intercept(sameOrigin, otherOrigin, setOf(sameOrigin))
        assertNull(redirect.header("Authorization")); assertNull(redirect.header("X-Media-Key"))
    }

    @Test fun initialHttpSubtitleIsIndependentOfVideoSchemeButHttpsCannotDowngrade() {
        val initial = "http://subtitles.example/Stream.srt"
        assertEquals(initial, intercept(initial, initial, setOf(initial)).url.toString())
        assertThrows(IOException::class.java) { intercept(serverSubtitle, "http://cdn.example/Stream.srt") }
        val secure = "https://subtitles.example/Stream.srt"
        assertThrows(IOException::class.java) { intercept(secure, initial, setOf(secure)) }
    }

    @Test fun subtitleRedirectResponseCannotDowngradeOrLeakEncodedServerToken() {
        val initial = "http://subtitles.example/Stream.srt"
        assertThrows(IOException::class.java) {
            intercept(initial, "https://subtitles.example/Stream.srt", setOf(initial), downgrade = true)
        }
        assertThrows(IOException::class.java) {
            intercept(serverSubtitle, "https://cdn.example/Stream.srt?token=jf%252Dprivate%252Dtoken")
        }
    }

    /** 原始 Call 与当前请求分开，覆盖重定向沿用旧请求头的真实边界。终端不访问外网。 */
    private fun intercept(initial: String, current: String, registered: Set<String> = setOf(serverSubtitle), downgrade: Boolean = false): Request {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(current).build()) }
            .addInterceptor(ScopedPlaybackInterceptor(playback, registered))
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (downgrade) 302 else 200).message("Fixture")
                    .apply { if (downgrade) header("Location", "http://subtitles.example/downgraded.srt") }
                    .body("".toResponseBody()).build()
            }.build()
        try {
            client.newCall(Request.Builder().url(initial).header("Authorization", "Bearer inherited")
                .header("X-Media-Key", "inherited-key").header("Cookie", "private=value").build()).execute().close()
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        return checkNotNull(sent)
    }
}

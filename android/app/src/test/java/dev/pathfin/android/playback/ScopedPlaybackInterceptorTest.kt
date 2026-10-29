package dev.pathfin.android.playback

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ScopedPlaybackInterceptorTest {
    private val session = Session(ServerConfig("https://jellyfin.example/jellyfin"), "jf-private-token", "user", "User", "device")

    @Test fun inheritedServerCredentialsAreRemovedAtForeignTarget() {
        val playback = request(trustedServer = true)
        val sent = intercept(playback, Request.Builder().url("https://other.example/video.mkv")
            .header("X-Emby-Token", session.accessToken)
            .header("X-Emby-Authorization", "MediaBrowser inherited")
            .header("Authorization", "inherited-secret")
            .header("Cookie", "private=value")
            .build())
        assertNull(sent.header("X-Emby-Token"))
        assertNull(sent.header("X-Emby-Authorization"))
        assertNull(sent.header("Authorization"))
        assertNull(sent.header("Cookie"))
    }

    @Test fun serverCredentialsAreRecomputedForOriginalServerOnly() {
        val sent = intercept(request(trustedServer = true), Request.Builder()
            .url("https://jellyfin.example/jellyfin/Videos/item/stream")
            .header("X-Emby-Token", "stale-token")
            .build())
        assertNull(sent.header("X-Emby-Token"))
        assertNull(sent.header("X-Emby-Authorization"))
        val authorization = checkNotNull(sent.header("Authorization"))
        assertTrue(authorization.startsWith("MediaBrowser "))
        assertTrue(authorization.contains("Token=\"${session.accessToken}\""))
    }

    @Test fun sourceHeadersAreAppliedToSameOriginSegmentsButNotRedirects() {
        val required = mapOf("Authorization" to "Bearer media-only", "Referer" to "https://media.example/watch", "X-Media-Key" to "source-key")
        val playback = request(trustedServer = false, headers = required)
        val segment = intercept(playback, Request.Builder().url("https://media.example/segment.ts").build())
        assertEquals("Bearer media-only", segment.header("Authorization"))
        assertEquals("source-key", segment.header("X-Media-Key"))
        assertNull(segment.header("X-Emby-Token"))
        val redirect = intercept(playback, segment.newBuilder().url("https://cdn.example/segment.ts").build())
        assertNull(redirect.header("Authorization"))
        assertNull(redirect.header("Referer"))
        assertNull(redirect.header("X-Media-Key"))
    }

    @Test(expected = IOException::class)
    fun httpsSourceCannotRequestHttpTarget() {
        intercept(request(trustedServer = false), Request.Builder().url("http://media.example/video.mkv").build())
    }

    @Test fun httpsRedirectDowngradeIsRejectedBeforeNextHopEvenWhenCleartextWasAllowed() {
        val playback = request(trustedServer = false).let {
            it.copy(session = it.session.copy(server = it.session.server.copy(allowInsecureHttp = true)))
        }
        var responses = 0
        assertThrows(IOException::class.java) {
            intercept(playback, Request.Builder().url("https://media.example/video.mkv").build()) { incoming ->
                responses++
                Response.Builder().request(incoming).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", "http://media.example/redirected.mkv").body("".toResponseBody()).build()
            }
        }
        assertEquals("只处理 HTTPS 响应，不能向 HTTP 发出下一跳", 1, responses)
    }

    @Test fun httpInitialSourceCannotPermitALaterHttpsResponseToDowngrade() {
        val playback = request(trustedServer = false).let {
            it.copy(stream = it.stream.copy(uri = "http://media.example/video.mkv"))
        }
        assertThrows(IOException::class.java) {
            intercept(playback, Request.Builder().url("https://media.example/upgraded.mkv").build()) { incoming ->
                Response.Builder().request(incoming).protocol(Protocol.HTTP_1_1).code(307).message("Redirect")
                    .header("Location", "http://media.example/again.mkv").body("".toResponseBody()).build()
            }
        }
    }

    @Test fun redirectedUrlCannotSendEncodedJellyfinCredentialToExternalHost() {
        assertThrows(IOException::class.java) {
            intercept(request(trustedServer = false), Request.Builder()
                .url("https://cdn.example/video?api_key=jf%252Dprivate%252Dtoken").build())
        }
    }

    private fun request(trustedServer: Boolean, headers: Map<String, String> = emptyMap()): PlaybackRequest {
        val source = MediaSource("source", path = "https://media.example/video.mkv", requiredHttpHeaders = headers)
        val stream = ResolvedPlayback(
            if (trustedServer) "https://jellyfin.example/jellyfin/Videos/item/stream" else source.path!!,
            headers,
            PlayMethod.DIRECT_PLAY,
            source.id,
            trustedServer,
        )
        return PlaybackRequest(MediaItem("item", "Movie", "Movie"), PlaybackInfo(listOf(source), "play-session"), source, stream, 0, session)
    }

    /** 无真实网络的终止拦截器；模拟每一跳携带旧 Header 时的实际请求重写。 */
    private fun intercept(playback: PlaybackRequest, incoming: Request, response: (Request) -> Response = { request ->
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body("".toResponseBody()).build()
    }): Request {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(ScopedPlaybackInterceptor(playback))
            .addInterceptor { chain ->
                sent = chain.request()
                response(chain.request())
            }.build()
        try {
            client.newCall(incoming).execute().close()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        return checkNotNull(sent)
    }
}

package dev.pathfin.android.casting

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CastRelayTest {
    private val video = ByteArray(1024) { ((it % 200) + 20).toByte() }

    @Test fun selectedDeviceReadsRealBytesWithScopedSourceHeadersAndOriginalSignedQuery() {
        withUpstream({ exchange ->
            assertEquals("/movie.mp4?signature=a%2Fb%2Bc&expires=2000000000", exchange.requestURI.toASCIIString())
            assertEquals("Bearer media-only", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("source-cookie", exchange.requestHeaders.getFirst("Cookie"))
            assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
            assertNull(exchange.requestHeaders.getFirst("X-Emby-Authorization"))
            assertFalse(exchange.requestHeaders.toString().contains("synthetic-jf-token"))
            exchange.video(video)
        }) { origin ->
            val playback = playback("$origin/movie.mp4?signature=a%2Fb%2Bc&expires=2000000000", headers = mapOf(
                "Authorization" to "Bearer media-only", "Cookie" to "source-cookie", "X-Emby-Token" to "synthetic-jf-token", "X-Injected" to "bad\r\nvalue"))
            CastRelay("127.0.0.1", "127.0.0.1", playback, allowLoopback = true).use { relay ->
                assertFalse(relay.url.contains("signature")); assertFalse(relay.url.contains("synthetic-jf-token"))
                val response = read(relay.url)
                assertEquals(200, response.code); assertArrayEquals(video, response.body)
                assertEquals("no-store", response.headers["Cache-Control"])
                repeat(100) { if (relay.bytesServed < video.size) Thread.sleep(2) }
                assertEquals(video.size.toLong(), relay.bytesServed)
            }
        }
    }

    @Test fun rangeHeadUnsatisfiableAndIgnoredRangePreserveActualHttpSemantics() {
        val ranges = CopyOnWriteArrayList<String>()
        withUpstream({ exchange ->
            val range = exchange.requestHeaders.getFirst("Range"); ranges += range ?: "none"
            when {
                exchange.requestMethod == "HEAD" -> {
                    exchange.responseHeaders.add("Content-Type", "video/mp4"); exchange.responseHeaders.add("Content-Length", video.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                }
                range == "bytes=0-31" -> { exchange.responseHeaders.add("Content-Range", "bytes 0-31/1024"); exchange.video(video.copyOf(32), 206) }
                range == "bytes=9999-" -> { exchange.responseHeaders.add("Content-Range", "bytes */1024"); exchange.sendResponseHeaders(416, -1) }
                else -> exchange.video(video)
            }
        }) { origin -> CastRelay("127.0.0.1", "127.0.0.1", playback("$origin/movie.mp4"), allowLoopback = true).use { relay ->
            val part = read(relay.url, range = "bytes=0-31")
            assertEquals(206, part.code); assertEquals("bytes 0-31/1024", part.headers["Content-Range"]); assertArrayEquals(video.copyOf(32), part.body)
            val head = read(relay.url, method = "HEAD")
            assertEquals(200, head.code); assertEquals("1024", head.headers["Content-Length"]); assertTrue(head.body.isEmpty())
            val ignored = read(relay.url, range = "bytes=10-20")
            assertEquals("上游忽略 Range 时不能假造 206", 200, ignored.code); assertArrayEquals(video, ignored.body)
            val beyond = read(relay.url, range = "bytes=9999-")
            assertEquals(416, beyond.code); assertEquals("bytes */1024", beyond.headers["Content-Range"])
            val beforeInvalid = ranges.size
            for (invalid in listOf("bytes=0-1,2-3", "bytes=3-2", "bytes=-0", "bytes=-", "bytes=9223372036854775808-")) assertEquals(416, read(relay.url, range = invalid).code)
            assertEquals("非法 Range 不到上游", beforeInvalid, ranges.size)
        } }
    }

    @Test fun randomLeaseIsFixedToOneDeviceAndCannotBeAnOpenProxyOrSurviveClose() {
        val requests = AtomicInteger()
        withUpstream({ exchange -> requests.incrementAndGet(); exchange.video(video) }) { origin ->
            val relay = CastRelay("127.0.0.1", "127.0.0.1", playback("$origin/movie.mp4"), allowLoopback = true)
            try {
                assertEquals(404, read(relay.url + "?url=http://8.8.8.8/private").code)
                assertEquals(405, read(relay.url, method = "POST").code)
                assertEquals(0, requests.get())
                assertEquals(200, read(relay.url).code)
            } finally { relay.close() }
            assertThrows(IOException::class.java) { read(relay.url) }
            CastRelay("127.0.0.1", "127.0.0.2", playback("$origin/movie.mp4"), allowLoopback = true).use { foreign ->
                assertThrows(IOException::class.java) { read(foreign.url) }
            }
            assertEquals("未选中地址不能访问源", 1, requests.get())
        }
    }

    @Test fun redirectNeverForwardsServerIdentityOrSourceHeadersToAnotherOrigin() {
        val received = CopyOnWriteArrayList<Headers>()
        withUpstream({ exchange ->
            received += Headers.Builder().apply { exchange.requestHeaders.forEach { (name, values) -> values.forEach { add(name, it) } } }.build()
            exchange.video(video)
        }) { destination ->
            withUpstream({ exchange ->
                assertEquals("Bearer media-only", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals("origin-secret", exchange.requestHeaders.getFirst("Cookie"))
                exchange.responseHeaders.add("Location", "$destination/movie.mp4"); exchange.sendResponseHeaders(302, -1)
            }) { source -> CastRelay("127.0.0.1", "127.0.0.1", playback("$source/movie.mp4", headers = mapOf(
                "Authorization" to "Bearer media-only", "Cookie" to "origin-secret", "Referer" to "$source/page")), allowLoopback = true).use { relay ->
                assertEquals(200, read(relay.url).code)
            } }
            withUpstream({ exchange ->
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                assertTrue(authorization.contains("MediaBrowser")); assertTrue(authorization.contains("synthetic-jf-token"))
                exchange.responseHeaders.add("Location", "$destination/movie.mp4"); exchange.sendResponseHeaders(307, -1)
            }) { source ->
                val request = playback("$source/jf/movie.mp4").let { it.copy(session = it.session.copy(server = ServerConfig("$source/jf", true)), stream = it.stream.copy(trustedServer = true)) }
                CastRelay("127.0.0.1", "127.0.0.1", request, allowLoopback = true).use { assertEquals(200, read(it.url).code) }
            }
        }
        assertEquals(2, received.size)
        for (headers in received) {
            for (name in listOf("Authorization", "Cookie", "Referer", "X-Emby-Token", "X-Emby-Authorization")) assertNull(headers[name])
            assertFalse(headers.toString().contains("synthetic-jf-token"))
        }
    }

    @Test fun httpsRedirectDowngradeIsRejectedBeforeAnHttpHop() {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "http://127.0.0.1:1/movie.mp4").body("".toResponseBody()).build()
        }.build()
        val request = playback("https://media.example/movie.mp4").let { it.copy(session = it.session.copy(server = it.session.server.copy(allowInsecureHttp = true))) }
        CastRelay("127.0.0.1", "127.0.0.1", request, client, allowLoopback = true).use { relay -> assertEquals(502, read(relay.url).code) }
        assertEquals("HTTP 下一跳未发出", 1, requests.get())
    }

    @Test fun manifestsWrongMimeAndDisguisedPlaylistsNeverReachTheRenderer() {
        for (source in listOf("http://127.0.0.1/movie.m3u8", "http://127.0.0.1/movie.mpd", "http://127.0.0.1/movie.strm")) {
            assertThrows(CastException::class.java) { CastRelay("127.0.0.1", "127.0.0.1", playback(source), allowLoopback = true) }
        }
        val mode = AtomicInteger()
        withUpstream({ exchange ->
            val body = if (mode.get() == 0) "#EXTM3U\nhttps://media.example/private-segment?signature=secret" else "<MPD><Period/></MPD>"
            exchange.video(body.encodeToByteArray(), mime = if (mode.get() == 0) "video/mp4" else "application/dash+xml")
        }) { origin -> CastRelay("127.0.0.1", "127.0.0.1", playback("$origin/movie.mp4"), allowLoopback = true).use { relay ->
            assertEquals(415, read(relay.url).code); mode.incrementAndGet(); assertEquals(415, read(relay.url).code)
            assertEquals(0L, relay.bytesServed)
        } }
    }

    @Test fun unsafeCredentialUrlCannotBeForwardedEvenWhenEncoded() {
        val requests = AtomicInteger()
        withUpstream({ exchange -> requests.incrementAndGet(); exchange.video(video) }) { origin ->
            CastRelay("127.0.0.1", "127.0.0.1", playback("$origin/movie.mp4?key=synthetic%252Djf%252Dtoken"), allowLoopback = true).use { relay ->
                assertEquals(502, read(relay.url).code); assertEquals(0L, relay.bytesServed)
            }
        }
        assertEquals(0, requests.get())
    }

    private data class Read(val code: Int, val headers: Headers, val body: ByteArray)
    private fun read(url: String, method: String = "GET", range: String? = null): Read {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val request = Request.Builder().url(url).apply { if (method == "POST") post(ByteArray(0).toRequestBody())
            else method(method, null); range?.let { header("Range", it) } }.build()
        try { return client.newCall(request).execute().use { Read(it.code, it.headers, it.body.bytes()) } }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun playback(url: String, headers: Map<String, String> = emptyMap()): PlaybackRequest {
        val source = MediaSource("source", url, "Http", "mp4", requiredHttpHeaders = headers)
        val session = Session(ServerConfig("https://jellyfin.example/jf"), "synthetic-jf-token", "synthetic-user", "Synthetic", "synthetic-device")
        return PlaybackRequest(MediaItem("item", "Synthetic movie", "Movie"), PlaybackInfo(listOf(source), "synthetic-play"), source,
            ResolvedPlayback(url, headers, PlayMethod.DIRECT_PLAY, source.id, trustedServer = false), 0, session)
    }
    private fun withUpstream(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); server.executor = executor
        server.createContext("/") { exchange -> try { handler(exchange) } catch (error: Throwable) {
            failure.compareAndSet(null, error); runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally { exchange.close() } }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0); executor.shutdownNow(); failure.get()?.let { throw it } }
    }
    private fun HttpExchange.video(bytes: ByteArray, code: Int = 200, mime: String = "video/mp4") {
        responseHeaders.add("Content-Type", mime); responseHeaders.add("Accept-Ranges", "bytes")
        sendResponseHeaders(code, bytes.size.toLong()); responseBody.use { it.write(bytes) }
    }
}

package dev.pathfin.android.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.PlaybackResolver
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实际 HTTP 请求经过生产 network interceptor；不以 Header Map 断言代替重定向请求。 */
class PlaybackHttpSourceTest {
    @Test fun httpsServerCanPlayExplicitHttpSourceAndRedirectsNeverLeakCredentials() {
        val executor = Executors.newCachedThreadPool()
        val origin = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply { this.executor = executor }
        val cdn = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply { this.executor = executor }
        val failures = AtomicReference<Throwable?>()
        val originRequests = AtomicInteger()
        val segmentRequests = AtomicInteger()
        val cdnRequests = AtomicInteger()
        val bytes = byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val session = Session(ServerConfig("https://jellyfin.example/jellyfin"), "synthetic-jellyfin-token", "user", "Synthetic", "device")
        val signedPath = "/video?sig=A%2fb%2B%3D&x=1&x=2"
        fun url(server: HttpServer, path: String) = "http://127.0.0.1:${server.address.port}$path"
        fun route(server: HttpServer, path: String, handler: (HttpExchange) -> Unit) {
            server.createContext(path) { exchange ->
                try { handler(exchange) }
                catch (error: Throwable) {
                    failures.compareAndSet(null, error)
                    runCatching { exchange.sendResponseHeaders(500, -1) }
                } finally { exchange.close() }
            }
        }
        fun redirect(exchange: HttpExchange, target: String) {
            exchange.responseHeaders.add("Location", target)
            exchange.sendResponseHeaders(302, -1)
        }
        fun requireSourceHeaders(exchange: HttpExchange) {
            assertEquals("Bearer media-only", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("source-only", exchange.requestHeaders.getFirst("X-Media-Key"))
            assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
            assertNull(exchange.requestHeaders.getFirst("Cookie"))
            assertFalse(exchange.requestHeaders.values.flatten().any { it.contains(session.accessToken) })
        }
        route(origin, "/video") { exchange ->
            originRequests.incrementAndGet()
            assertEquals("签名及重复参数必须原样发出", signedPath, exchange.requestURI.toASCIIString())
            requireSourceHeaders(exchange)
            redirect(exchange, "/segment")
        }
        route(origin, "/segment") { exchange ->
            segmentRequests.incrementAndGet()
            requireSourceHeaders(exchange)
            redirect(exchange, url(cdn, "/media"))
        }
        route(cdn, "/media") { exchange ->
            cdnRequests.incrementAndGet()
            for (header in listOf("Authorization", "X-Emby-Token", "X-Media-Key", "Referer", "Cookie"))
                assertNull("跨 origin 必须剥离 $header", exchange.requestHeaders.getFirst(header))
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        origin.start(); cdn.start()
        val source = MediaSource("source", url(origin, signedPath), protocol = "Http", requiredHttpHeaders = mapOf(
            "Authorization" to "Bearer media-only", "X-Media-Key" to "source-only",
            "X-Emby-Token" to session.accessToken, "Cookie" to "token=${session.accessToken}",
        ))
        val info = PlaybackInfo(listOf(source), "play")
        val item = MediaItem("item", "Synthetic", "Movie")
        val stream = PlaybackResolver.resolve(session, item, info, source.id)
        val playback = PlaybackRequest(item, info, source, stream, 0, session)
        val client = OkHttpClient.Builder().addNetworkInterceptor(ScopedPlaybackInterceptor(playback)).build()
        try {
            assertFalse("HTTPS 服务器无需开放自身 HTTP 才能播放明确的外部源", session.server.allowInsecureHttp)
            client.newCall(Request.Builder().url(stream.uri)
                .header("Authorization", "MediaBrowser Token=\"${session.accessToken}\"")
                .header("Cookie", "private=${session.accessToken}").build()).execute().use { response ->
                assertTrue(response.isSuccessful)
                assertTrue(bytes.contentEquals(response.body.bytes()))
            }
            failures.get()?.let { throw AssertionError("合成媒体端点断言失败", it) }
            assertEquals(1, originRequests.get())
            assertEquals(1, segmentRequests.get())
            assertEquals(1, cdnRequests.get())
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            origin.stop(0); cdn.stop(0); executor.shutdownNow()
            failures.get()?.let { throw AssertionError("合成媒体端点断言失败", it) }
        }
    }
}

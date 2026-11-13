package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvGroupSourceTest {
    private val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV1\nhttp://a.example/1\n#EXTINF:-1 group-title=\"重庆\",CQTV新闻\nhttp://a.example/2\n"

    @Test fun adminReadsTunerPlaylistWithItsUserAgentButWithoutJellyfinCredentialsAndCachesIt() {
        val tunerRequests = CopyOnWriteArrayList<HttpExchange>()
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u","UserAgent":"qqlive"},
                    {"Type":"hdhomerun","Url":"http://hdhr.invalid"}]}""")
                "/tuner.m3u" -> { tunerRequests += exchange; exchange.send(playlist) }
                else -> exchange.send("", 404)
            }
        }) { session, source ->
            val groups = runBlocking { source.refresh(session, JellyfinApi()) }
            assertEquals(listOf("央视", "重庆"), groups?.order)
            val tuner = tunerRequests.single().requestHeaders
            assertEquals("qqlive", tuner.getFirst("User-Agent"))
            for (header in listOf("Authorization", "X-Emby-Token", "X-Emby-Authorization"))
                assertNull("调谐器不是 Jellyfin，不能带 $header", tuner.getFirst(header))
            assertEquals("缓存只含频道名与分组", groups, runBlocking { source.cached(session) })
        }
    }

    @Test fun nonAdminNeverReadsServerConfiguration() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ paths += it.requestURI.path; it.send("{}") }) { session, source ->
            assertNull(runBlocking { source.refresh(session.copy(isAdministrator = false), JellyfinApi()) })
            assertNull(runBlocking { source.cached(session) })
            assertTrue(paths.isEmpty())
        }
    }

    @Test fun unreachableTunerKeepsTheCachedGroups() {
        var tunerUp = true
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> if (tunerUp) exchange.send(playlist) else exchange.send("", 503)
            }
        }) { session, source ->
            val first = runBlocking { source.refresh(session, JellyfinApi()) }
            tunerUp = false
            assertNull("读取失败不返回残缺结果", runBlocking { source.refresh(session, JellyfinApi()) })
            assertEquals("离家时沿用上次分组", first, runBlocking { source.cached(session) })
        }
    }

    @Test fun serverWithoutM3uTunerHasNoGroups() = withServer({ it.send("""{"TunerHosts":[{"Type":"m3u","Url":"/config/local.m3u"}]}""") }) { session, source ->
        assertNull("本地文件路径无法从客户端读取", runBlocking { source.refresh(session, JellyfinApi()) })
        assertNull(runBlocking { source.cached(session) })
    }

    private fun HttpExchange.base() = "http://127.0.0.1:${localAddress.port}"

    private fun HttpExchange.send(body: String, code: Int = 200) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    private fun withServer(route: (HttpExchange) -> Unit, block: (Session, LiveTvGroupSource) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newCachedThreadPool { Thread(it, "pathfin-livetv-groups-test").apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { route(exchange) } catch (error: Throwable) {
                failure.compareAndSet(null, error); runCatching { exchange.sendResponseHeaders(500, -1); exchange.close() }
            }
        }
        server.start()
        val directory = Files.createTempDirectory("live-tv-groups").toFile()
        try {
            block(Session(ServerConfig("http://127.0.0.1:${server.address.port}/jf", true), "fixture-token", "admin", "Admin",
                "device", serverId = "server-a", isAdministrator = true), LiveTvGroupSource(directory))
        } finally {
            server.stop(0); executor.shutdownNow(); directory.deleteRecursively()
            failure.get()?.let { throw AssertionError("fixture assertion failed", it) }
        }
    }
}

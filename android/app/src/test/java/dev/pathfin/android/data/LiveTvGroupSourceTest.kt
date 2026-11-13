package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.LiveTvGroups
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test fun unreachableOrInvalidTunerResponseKeepsTheCachedGroups() {
        val response = AtomicReference<String?>(playlist)
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> response.get()?.let { exchange.send(it) } ?: exchange.send("", 503)
            }
        }) { session, source ->
            val first = runBlocking { source.refresh(session, JellyfinApi()) }
            for (failed in listOf(null, "", "<html><body>Sign in</body></html>")) {
                response.set(failed)
                assertNull("网络失败或无效正文不能作为空分组", runBlocking { source.refresh(session, JellyfinApi()) })
                assertEquals("读取失败时沿用上次分组", first, runBlocking { source.cached(session) })
            }
        }
    }

    @Test fun successfulRemovalOfGroupAttributesOrAllTunersReplacesTheCachedGroups() {
        val currentPlaylist = AtomicReference<String?>(playlist)
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send(if (currentPlaylist.get() == null) """{"TunerHosts":[]}"""
                    else """{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> exchange.send(currentPlaylist.get().orEmpty())
            }
        }) { session, source ->
            // 两种真实配置变化都成功读完：保留频道但删除 group-title，或移除全部调谐器。
            for (next in listOf("#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://a.example/1\n", "\uFEFF#EXTM3U\n", "http://a.example/1\n", null)) {
                currentPlaylist.set(playlist)
                assertEquals(listOf("央视", "重庆"), runBlocking { source.refresh(session, JellyfinApi()) }?.order)
                currentPlaylist.set(next)
                assertEquals("成功的空分组应替换旧值", LiveTvGroups(), runBlocking { source.refresh(session, JellyfinApi()) })
                assertEquals("磁盘不能继续恢复已删除的分组", LiveTvGroups(), runBlocking { source.cached(session) })
            }
        }
    }

    @Test fun unreadableTunerKeepsTheCacheInsteadOfPublishingPartialHttpGroups() {
        var addLocalTuner = false
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> {
                    val localTuner = if (addLocalTuner) """,{"Type":"m3u","Url":"/config/local.m3u"}""" else ""
                    exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}$localTuner]}""")
                }
                else -> exchange.send(if (addLocalTuner) playlist.replace("央视", "残缺分组") else playlist)
            }
        }) { session, source ->
            val first = runBlocking { source.refresh(session, JellyfinApi()) }
            addLocalTuner = true
            assertNull("任何一个 M3U 无法读取时不能只返回其余播放列表", runBlocking { source.refresh(session, JellyfinApi()) })
            assertEquals("完整分组缓存保持不变", first, runBlocking { source.cached(session) })
        }
    }

    @Test fun oversizedGroupCacheKeepsThePreviousReadableGroupsWhileReturningTheFreshResult() {
        val currentPlaylist = AtomicReference(playlist)
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> exchange.send(currentPlaylist.get())
            }
        }) { session, source ->
            val first = runBlocking { source.refresh(session, JellyfinApi()) }
            currentPlaylist.set("#EXTM3U\n#EXTINF:-1 group-title=\"${"影".repeat(800_000)}\",CCTV1\nhttp://a.example/1\n")
            val fresh = runBlocking { source.refresh(session, JellyfinApi()) }
            assertEquals("在线读取仍成功返回新分组", 800_000, fresh?.entries?.single()?.second?.length)
            assertEquals("超过缓存读取上限时保留原文件", first, runBlocking { source.cached(session) })
        }
    }

    @Test fun retainedAccountsSharePlaylistGroupsAcrossUsersAndRemoveOnlyUnreferencedServers() {
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> exchange.send(playlist)
            }
        }) { session, source ->
            val otherServer = session.copy(serverId = "server-b")
            val first = runBlocking { source.refresh(session, JellyfinApi()) }
            runBlocking { source.refresh(otherServer, JellyfinApi()) }
            source.retainAccounts(listOf(session.copy(userId = "remaining-user", accessToken = "other-token")))
            assertEquals("同服务器还有其他账号时保留公共分组", first, runBlocking { source.cached(session) })
            assertNull("最后一个资料已移除的服务器不再保留分组", runBlocking { source.cached(otherServer) })
            source.retainAccounts(emptyList())
            assertNull(runBlocking { source.cached(session) })
        }
    }

    @Test fun cancelledPlaylistResponseCannotRecreateGroupsAfterAccountCleanup() {
        val downloads = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> {
                    if (downloads.incrementAndGet() == 2) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "playlist response was not released" }
                    }
                    exchange.send(playlist)
                }
            }
        }) { session, source ->
            runBlocking {
                source.refresh(session, JellyfinApi())
                val pending = async(Dispatchers.Default) { source.refresh(session, JellyfinApi()) }
                try {
                    assertTrue("第二次播放列表请求必须实际到达", started.await(5, TimeUnit.SECONDS))
                    pending.cancel()
                    source.retainAccounts(emptyList())
                    release.countDown()
                    withTimeout(5_000) { pending.join() }
                    assertNull("已经取消的迟到响应不能复活被清理的分组", source.cached(session))
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun deniedOrCancelledNetworkAccessPropagatesWithoutDownloadingOrChangingTheCache() {
        val permissionFailure = AtomicReference<Throwable?>()
        val downloads = AtomicInteger()
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> { downloads.incrementAndGet(); exchange.send(playlist) }
            }
        }, ensureNetworkAccess = { permissionFailure.get()?.let { throw it } }) { session, source ->
            val first = runBlocking { source.refresh(session, JellyfinApi()) }
            assertEquals(1, downloads.get())
            for (failure in listOf(LocalNetworkPermissionDenied(), CancellationException("permission request cancelled"))) {
                permissionFailure.set(failure)
                val actual = runCatching { runBlocking { source.refresh(session, JellyfinApi()) } }.exceptionOrNull()
                assertSame("权限拒绝或取消应原样传播", failure, actual)
                assertEquals("授权未通过不能发起播放列表请求", 1, downloads.get())
                assertEquals("拒绝与取消不能清除已缓存的分组", first, runBlocking { source.cached(session) })
            }
        }
    }

    @Test fun playlistDownloadWaitsForNetworkPermissionAndResumesAfterItIsGranted() {
        val permissionRequested = CompletableDeferred<String>()
        val granted = CompletableDeferred<Unit>()
        val downloads = AtomicInteger()
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/jf/System/Configuration/livetv" -> exchange.send("""{"TunerHosts":[{"Type":"m3u","Url":"${exchange.base()}/tuner.m3u"}]}""")
                else -> { downloads.incrementAndGet(); exchange.send(playlist) }
            }
        }, ensureNetworkAccess = { url -> permissionRequested.complete(url); granted.await() }) { session, source ->
            runBlocking {
                val refresh = async { source.refresh(session, JellyfinApi()) }
                try {
                    assertEquals("按调谐器地址请求权限", session.server.baseUrl.substringBeforeLast("/jf") + "/tuner.m3u",
                        withTimeout(5_000) { permissionRequested.await() })
                    assertEquals("系统授权尚未返回时不能提前下载", 0, downloads.get())
                    granted.complete(Unit)
                    assertEquals(listOf("央视", "重庆"), withTimeout(5_000) { refresh.await() }?.order)
                    assertEquals(1, downloads.get())
                } finally { granted.complete(Unit) }
            }
        }
    }

    private fun HttpExchange.base() = "http://127.0.0.1:${localAddress.port}"

    private fun HttpExchange.send(body: String, code: Int = 200) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    private fun withServer(route: (HttpExchange) -> Unit, ensureNetworkAccess: suspend (String) -> Unit = {},
        block: (Session, LiveTvGroupSource) -> Unit) {
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
                "device", serverId = "server-a", isAdministrator = true), LiveTvGroupSource(directory, ensureNetworkAccess = ensureNetworkAccess))
        } finally {
            server.stop(0); executor.shutdownNow(); directory.deleteRecursively()
            failure.get()?.let { throw AssertionError("fixture assertion failed", it) }
        }
    }
}

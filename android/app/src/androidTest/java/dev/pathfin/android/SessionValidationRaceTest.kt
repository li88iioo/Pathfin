package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 冷启动真实会话恢复与回环 HTTP：头像更新不撤销相同认证身份的在途请求。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SessionValidationRaceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun consoleOwnerSurvivesNavigationAndIsDiscardedWithItsRouteOnAuthenticationFailure() = withRestoredApp(administrator = true) { app, fixture ->
        fixture.validation.awaitRequest()
        await(app, fixture) { !it.restoringSession && it.session != null }
        main { app.openServerAdministration() }
        val restored = checkNotNull(app.state.value.administration)
        completeValidation(app, fixture)
        val validated = checkNotNull(app.state.value.administration)
        assertNotSame(restored, validated)
        assertEquals(app.state.value.session, validated.session)
        main { validated.page = "activity"; app.back(); app.openServerAdministration() }
        assertEquals(Route.ServerAdministration, app.state.value.route)
        assertSame("真实路由重进必须保留控制台子页状态", validated, app.state.value.administration)
        assertEquals("activity", app.state.value.administration?.page)
        main { app.authenticationExpired(validated.session); app.dismissReauthentication() }
        assertNull(app.state.value.administration)
        assertNull(app.state.value.reauthenticationProfile)
        assertEquals("退出登录恢复提示后不能留下没有状态的控制台", Route.Home, app.state.value.route)
        main { app.openServerAdministration() }
        assertNotSame(validated, app.state.value.administration)
        assertEquals("overview", app.state.value.administration?.page)
    }

    @Test fun liveTvOwnerSurvivesNavigationButIsReplacedAfterValidationAndExpiredCredentials() = withRestoredApp { app, fixture ->
        fixture.validation.awaitRequest()
        await(app, fixture) { !it.restoringSession && it.session != null }
        main { app.openLiveTv() }
        val restored = checkNotNull(await(app, fixture) { it.route == Route.LiveTv }.liveTv)
        completeValidation(app, fixture)
        val validated = checkNotNull(app.state.value.liveTv)
        assertNotSame("验证后的会话必须成为频道请求所有者", restored, validated)
        assertEquals(app.state.value.session, validated.session)
        main { validated.query = "四川"; app.back() }
        assertEquals(Route.Home, app.state.value.route)
        main { app.openLiveTv() }
        val returned = await(app, fixture) { it.route == Route.LiveTv }
        assertSame("真实路由退出重进应复用同一份频道状态", validated, returned.liveTv)
        assertEquals("四川", returned.liveTv?.query)

        main { app.authenticationExpired(validated.session) }
        await(app, fixture) { it.reauthenticationProfile != null }
        main { app.dismissReauthentication() }
        assertNull(app.state.value.liveTv)
        assertNull(app.state.value.reauthenticationProfile)
        assertEquals("退出恢复登录提示后不能遗留没有状态的空白直播路由", Route.Home, app.state.value.route)
        main { app.openLiveTv() }
        val fresh = checkNotNull(await(app, fixture) { it.route == Route.LiveTv }.liveTv)
        assertNotSame(validated, fresh)
        assertEquals("", fresh.query)
    }

    @Test fun detailRequestedBeforeUserValidationStillCompletesAfterAvatarChanges() = withRestoredApp { app, fixture ->
        val item = searchBeforeValidation(app, fixture)
        main { app.open(item) }
        fixture.detail.awaitRequest()
        val pending = main { requestJob(app, "detailJob") }
        assertTrue(app.state.value.detailLoading)

        completeValidation(app, fixture)
        fixture.detail.release()
        join(pending)
        val result = app.state.value
        assertFalse("头像补全不能让已结束的详情请求遗留 loading", result.detailLoading)
        assertNull(result.detailError)
        assertEquals("应发布真实完整详情，不能停留在搜索简版条目", FULL_OVERVIEW, result.details[ITEM]?.overview)
        assertEquals(Route.Detail(ITEM), result.route)
        assertEquals(NEW_AVATAR, result.session?.userImageTag)
    }

    @Test fun playbackRequestedBeforeUserValidationStillCommitsAfterAvatarChanges() = withRestoredApp { app, fixture ->
        val item = searchBeforeValidation(app, fixture)
        main { app.preparePlayback(item) }
        fixture.playback.awaitRequest()
        val pending = main { requestJob(app, "playJob") }
        assertTrue(app.state.value.preparingPlay)
        assertEquals(ITEM, app.state.value.pendingPlayback?.item?.id)

        completeValidation(app, fixture)
        fixture.playback.release()
        join(pending)
        val result = app.state.value
        assertFalse("头像补全不能让已结束的播放准备遗留 busy", result.preparingPlay)
        assertNull(result.pendingPlayback)
        assertNull(result.playbackError)
        assertEquals(ITEM, result.activePlayback?.item?.id)
        assertEquals("validation-play-$ITEM", result.activePlayback?.info?.playSessionId)
        assertEquals(OLD_TOKEN, result.activePlayback?.session?.accessToken)
        assertEquals(NEW_AVATAR, result.session?.userImageTag)
    }

    @Test fun renewingCredentialsCancelsOldDetailAndPlaybackInsteadOfAcceptingTheirLateResponses() = withRestoredApp { app, fixture ->
        val item = searchBeforeValidation(app, fixture)
        main { app.open(item); app.preparePlayback(item) }
        fixture.detail.awaitRequest()
        fixture.playback.awaitRequest()
        val oldJobs = main { listOf(requestJob(app, "detailJob"), requestJob(app, "playJob")) }
        fixture.validation.expectCancellation.set(true)
        fixture.detail.expectCancellation.set(true)
        fixture.playback.expectCancellation.set(true)
        main { app.login(fixture.origin, USER_NAME, "renewed-password", allowHttp = true) }
        await(app, fixture) { it.session?.accessToken == NEW_TOKEN && !it.loginBusy && it.home.loaded && !it.home.loading }

        fixture.validation.release(); fixture.detail.release(); fixture.playback.release()
        oldJobs.forEach(::join)
        fixture.detail.awaitResponseAttempt()
        fixture.playback.awaitResponseAttempt()
        main {
            assertEquals(NEW_TOKEN, app.state.value.session?.accessToken)
            assertNull("新凭据激活后旧准备不能恢复播放器", app.state.value.activePlayback)
            assertNull(app.state.value.pendingPlayback)
            assertFalse(app.state.value.preparingPlay)
            assertFalse(app.state.value.detailLoading)
            assertFalse("旧凭据的迟到详情不能写入新会话缓存", ITEM in app.state.value.details)
            app.open(MediaItem(FRESH_ITEM, "Fresh summary", "Movie"))
        }
        val fresh = await(app, fixture) { !it.detailLoading && it.details[FRESH_ITEM]?.overview == FULL_OVERVIEW }
        assertEquals(NEW_TOKEN, fresh.session?.accessToken)
        assertEquals(Route.Detail(FRESH_ITEM), fresh.route)
        assertFalse(ITEM in fresh.details)
    }

    private fun searchBeforeValidation(app: AppViewModel, fixture: Fixture): MediaItem {
        fixture.validation.awaitRequest()
        val restored = await(app, fixture) { !it.restoringSession && it.session?.accessToken == OLD_TOKEN }
        assertFalse("本用例必须覆盖启动验证期间仍可操作的页面", restored.loginBusy)
        assertEquals(OLD_AVATAR, restored.session?.userImageTag)
        main {
            app.selectTab(Route.Search)
            app.toggleSearchCategory(DiscoveryCategory.MOVIES)
            app.search("validation")
        }
        return await(app, fixture) {
            it.search.results.sections[DiscoveryCategory.MOVIES]?.let { page -> !page.loading && page.items.isNotEmpty() } == true
        }.search.results.sections.getValue(DiscoveryCategory.MOVIES).items.single()
    }

    private fun completeValidation(app: AppViewModel, fixture: Fixture) {
        fixture.validation.release()
        val updated = await(app, fixture) { it.session?.userImageTag == NEW_AVATAR && it.home.loaded && !it.home.loading }
        assertEquals("头像更新应保留认证凭据", OLD_TOKEN, updated.session?.accessToken)
        assertEquals(USER_ID, updated.session?.userId)
    }

    private fun withRestoredApp(administrator: Boolean = false, block: (AppViewModel, Fixture) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName == TARGET) { "会话竞态回归只允许独立 verification 包" }
        val preferences = AppPreferencesStore(context)
        val originalPreferences = preferences.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var app: AppViewModel? = null
        clearSyntheticSessions(context)
        preferences.save(AppPreferences(openLastServer = true))
        try {
            Fixture(administrator).use { fixture ->
                SessionStore(context).save(Session(ServerConfig(fixture.origin, true), OLD_TOKEN, USER_ID, USER_NAME,
                    DEVICE_ID, SERVER_ID, userImageTag = OLD_AVATAR, isAdministrator = administrator))
                try {
                    val model = main {
                        val provider = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                        provider[AppViewModel::class.java].also { app = it }
                    }
                    block(model, fixture)
                    fixture.assertHealthy()
                } finally {
                    fixture.allowCancellation()
                    main { app?.playerClosed(0); owner.viewModelStore.clear() }
                    fixture.releaseAll()
                }
            }
        } finally {
            main { owner.viewModelStore.clear() }
            clearSyntheticSessions(context)
            preferences.save(originalPreferences)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(app: AppViewModel, fixture: Fixture, predicate: (AppState) -> Boolean): AppState = runBlocking {
        withTimeout(WAIT) { app.state.first { fixture.assertHealthy(); predicate(it) } }
    }

    /** 只观察实际请求协程完成，避免依赖网络写完与 Main 发布结果间的任意延时。 */
    private fun requestJob(app: AppViewModel, field: String): Job = checkNotNull(
        AppViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(app) as? Job)

    private fun join(job: Job) = runBlocking { withTimeout(WAIT) { job.join() } }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        val expectCancellation = AtomicBoolean()
        fun block() { entered.countDown(); check(released.await(WAIT, TimeUnit.MILLISECONDS)) { "合成响应未释放" } }
        fun release() = released.countDown()
        fun finish() = finished.countDown()
        fun awaitRequest() = assertTrue("请求必须已到达回环服务", entered.await(WAIT, TimeUnit.MILLISECONDS))
        fun awaitResponseAttempt() = assertTrue("迟到响应必须实际尝试完成", finished.await(WAIT, TimeUnit.MILLISECONDS))
    }

    private class Fixture(private val administrator: Boolean) : Closeable {
        val validation = Gate()
        val detail = Gate()
        val playback = Gate()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val executor = Executors.newCachedThreadPool { operation -> Thread(operation, "pathfin-validation-race-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val origin = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                executor.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures += error }
        }, "pathfin-validation-race-accept").apply { isDaemon = true; start() }

        fun assertHealthy() { failures.peek()?.let { throw AssertionError("会话验证竞态 fixture 失败", it) } }
        fun allowCancellation() { listOf(validation, detail, playback).forEach { it.expectCancellation.set(true) } }
        fun releaseAll() { validation.release(); detail.release(); playback.release() }

        private fun serve(socket: Socket) {
            var gate: Gate? = null
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT.toInt()
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val request = reader.readLine()?.split(' ') ?: return
                    check(request.size == 3)
                    val headers = mutableMapOf<String, String>()
                    var complete = false
                    repeat(64) { if (!complete) {
                        val line = reader.readLine() ?: throw EOFException("合成请求头未完成")
                        check(line.length <= 8192)
                        if (line.isEmpty()) complete = true else {
                            check(':' in line)
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                    } }
                    check(complete)
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    check(length in 0..65_536)
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val size = reader.read(body, read, length - read)
                        if (size < 0) throw EOFException("合成请求体未完成")
                        read += size
                    }
                    val uri = URI(request[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }
                    if (uri.path != "/Users/AuthenticateByName") check(headers["authorization"].orEmpty().let {
                        it.contains(OLD_TOKEN) || it.contains(NEW_TOKEN)
                    }) { "合成认证头缺失" }
                    gate = when {
                        uri.path == "/Users/Me" -> validation
                        uri.path == "/Items" && query["ids"] == ITEM -> detail
                        uri.path == "/Items/$ITEM/PlaybackInfo" -> playback
                        else -> null
                    }
                    gate?.block()
                    val response = when {
                        uri.path == "/Users/Me" -> user()
                        uri.path == "/Users/AuthenticateByName" -> {
                            check(request[0] == "POST" && JSONObject(String(body)).getString("Pw") == "renewed-password")
                            JSONObject().put("AccessToken", NEW_TOKEN).put("ServerId", SERVER_ID).put("User", user())
                        }
                        uri.path == "/UserViews" || uri.path == "/UserItems/Resume" || uri.path == "/Shows/NextUp" || uri.path.endsWith("/Similar") -> page()
                        uri.path == "/Items/Latest" -> JSONArray()
                        uri.path == "/Items/$ITEM/PlaybackInfo" -> JSONObject().put("PlaySessionId", "validation-play-$ITEM")
                            .put("MediaSources", JSONArray().put(JSONObject().put("Id", "validation-source")
                                .put("Path", "/synthetic/validation.mp4").put("Protocol", "File").put("Container", "mp4")
                                .put("SupportsDirectPlay", true)))
                        uri.path == "/Items" && query["ids"] != null -> page(item(query.getValue("ids"), full = true))
                        uri.path == "/Items" -> page(item(ITEM, full = false))
                        else -> error("未定义竞态测试接口：${request[0]} ${uri.path}")
                    }
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get() && !(error is IOException && gate?.expectCancellation?.get() == true)) failures += error
            } finally {
                gate?.finish()
                sockets.remove(socket)
            }
        }

        private fun user() = JSONObject().put("Id", USER_ID).put("Name", USER_NAME).put("PrimaryImageTag", NEW_AVATAR)
            .put("Policy", JSONObject().put("IsAdministrator", administrator))
        private fun item(id: String, full: Boolean) = JSONObject().put("Id", id).put("Name", "Validation $id").put("Type", "Movie")
            .put("Overview", if (full) FULL_OVERVIEW else "").put("RunTimeTicks", 600_000_000L)
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseAll()
            server.close()
            sockets.toList().forEach { runCatching { it.close() } }
            executor.shutdownNow()
            accept.join(1000)
            assertHealthy()
        }
    }

    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val WAIT = 20_000L
        const val USER_ID = "validation-user"
        const val USER_NAME = "validation-fixture"
        const val DEVICE_ID = "validation-device"
        const val SERVER_ID = "validation-server"
        const val OLD_TOKEN = "validation-old-token"
        const val NEW_TOKEN = "validation-new-token"
        const val OLD_AVATAR = "cached-avatar"
        const val NEW_AVATAR = "fresh-avatar"
        const val ITEM = "validation-movie"
        const val FRESH_ITEM = "fresh-credential-movie"
        const val FULL_OVERVIEW = "Full detail returned after user validation"
        fun clearSyntheticSessions(context: Context) {
            check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
        }
    }
}

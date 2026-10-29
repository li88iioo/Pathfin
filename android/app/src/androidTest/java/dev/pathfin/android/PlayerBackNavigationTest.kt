package dev.pathfin.android

import android.content.Context
import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * MainActivity 的真实 Media3 播放与返回路由回归。
 * 仅 verification 包；先启动 fixture_server.py，并 reverse 8097/8098。
 * 进程内代理只转发到通过 Fixture 标记检查的固定回环服务，按合成播放会话统计停止上报。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerBackNavigationTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private lateinit var fixture: RecordingApiProxy
    private val isolatedStorage = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(context.packageName == VERIFICATION_PACKAGE) { "播放器返回回归只允许 verification 包" }
                val preferences = AppPreferencesStore(context)
                val previous = preferences.load()
                clearSyntheticSession(context)
                preferences.save(AppPreferences())
                try {
                    RecordingApiProxy().use { proxy ->
                        fixture = proxy
                        base.evaluate()
                    }
                } finally {
                    clearSyntheticSession(context)
                    preferences.save(previous)
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test fun topReturnKeepsOriginalDetailsAndReportsStopOnce() = verifyReturn(ReturnPath.TOP)

    @Test fun systemDispatcherReturnsFromFocusedPlayerToOriginalDetails() = verifyReturn(ReturnPath.DISPATCHER)

    @Test fun focusedPlayerConsumesCompletePhysicalBackPairWithoutClosingActivity() = verifyReturn(ReturnPath.PHYSICAL)

    @Test fun rotationRetainsPlayerAndPhysicalBackStillReturnsToOriginalDetails() = verifyReturn(ReturnPath.AFTER_ROTATION)

    @Test fun actualSystemBackKeyReturnsToDetails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.externalInput") == "true")
        verifyReturn(ReturnPath.NATIVE_KEY)
    }

    @Test fun actualEdgeGestureReturnsToDetails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.externalInput") == "true")
        verifyReturn(ReturnPath.NATIVE_GESTURE)
    }

    private fun verifyReturn(path: ReturnPath) {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = compose.runOnIdle { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        try {
            compose.runOnIdle {
                app.setPreferences(AppPreferences(originalPathEnabled = true))
                app.login(fixture.baseUrl, "pathfin", "pathfin", true, "Playback return fixture")
            }
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                val state = app.state.value
                state.loginError?.let { throw AssertionError("合成服务登录失败：$it") }
                state.home.error?.let { throw AssertionError("合成媒体库加载失败：$it") }
                state.session != null && state.home.loaded && !state.home.loading
            }
            compose.runOnIdle {
                val home = app.state.value.home
                val movie = (home.libraryItems.values.flatten() + home.recent + home.resume).first { it.id == ITEM_ID }
                app.open(movie)
            }
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                val state = app.state.value
                state.detailError?.let { throw AssertionError("合成详情加载失败：$it") }
                state.route == Route.Detail(ITEM_ID) && !state.detailLoading && state.details[ITEM_ID] != null
            }
            val detailRoutes = app.state.value.routes
            compose.runOnIdle { app.preparePlayback(app.state.value.details.getValue(ITEM_ID), fromStart = true) }
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                app.state.value.playbackError?.let { throw AssertionError("合成媒体准备失败：$it") }
                playback.state.value.errorMessage?.let { throw AssertionError("合成媒体播放失败：$it") }
                app.state.value.activePlayback != null && playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
            }
            val playSessionId = checkNotNull(app.state.value.activePlayback).info.playSessionId
            assertTrue("只允许固定合成播放会话", playSessionId.matches(Regex("fixture-session-\\d+")))
            val player = compose.runOnIdle {
                checkNotNull(playback.player).also { it.pause() }
            }
            // 真实问题发生在闲置 TLS socket 的 close_notify，不只断言已有首帧。
            // 显式 TLS 模式必须等待媒体完全读取并归还 HTTPS keep-alive 连接。
            val tlsClient = if (InstrumentationRegistry.getArguments().getString("pathfin.tlsMedia") == "true") {
                assertTrue("TLS 回归必须使用实际 HTTPS 媒体", checkNotNull(app.state.value.activePlayback).stream.uri.startsWith("https://"))
                val field = PlaybackViewModel::class.java.getDeclaredField("active").apply { isAccessible = true }
                val owner = checkNotNull(compose.runOnIdle { field.get(playback) })
                val clientField = owner.javaClass.getDeclaredField("mediaClient").apply { isAccessible = true }
                val client = clientField.get(owner) as OkHttpClient
                compose.waitUntil(WAIT_MS) { client.connectionPool.idleConnectionCount() > 0 }
                client
            } else null
            drainReports(playback)
            assertEquals("实际播放只应开始一次", 1, fixture.eventCount(START, playSessionId))
            assertEquals(0, fixture.eventCount(STOP, playSessionId))

            when (path) {
                ReturnPath.TOP -> showControlsThenReturn()
                ReturnPath.DISPATCHER -> compose.runOnIdle {
                    focusPlayer(compose.activity)
                    compose.activity.onBackPressedDispatcher.onBackPressed()
                }
                ReturnPath.PHYSICAL -> physicalBack(app)
                ReturnPath.AFTER_ROTATION -> {
                    rotateWhilePaused(app, playback, player)
                    physicalBack(app)
                }
                ReturnPath.NATIVE_KEY, ReturnPath.NATIVE_GESTURE -> requestExternalBack(path)
            }
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                app.state.value.activePlayback == null && playback.player == null
            }
            drainReports(playback)
            if (tlsClient != null) compose.waitUntil(WAIT_MS) {
                tlsClient.connectionPool.connectionCount() == 0 && tlsClient.dispatcher.executorService.isShutdown
            }
            compose.runOnIdle {
                val activity = compose.activity
                assertFalse("播放器返回不能结束 MainActivity", activity.isFinishing)
                assertFalse("播放器返回不能销毁 MainActivity", activity.isDestroyed)
                assertTrue("返回后应用必须仍在前台", activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                assertEquals("返回必须保留原详情和导航深度", detailRoutes, app.state.value.routes)
                assertEquals(Route.Detail(ITEM_ID), app.state.value.route)
                assertNotNull(app.state.value.session)
                assertNull("退出播放应释放 Media3 播放器", playback.player)
            }
            compose.onNodeWithContentDescription("返回").assertVerificationDisplayed(compose)
            assertEquals("返回、Compose effect 和 PlayerView 的退出路径只能停止同一会话一次", 1,
                fixture.eventCount(STOP, playSessionId))
            assertEquals("重建和返回不能意外重新播放", 1, fixture.eventCount(START, playSessionId))
        } finally {
            File(compose.activity.filesDir, "native-back-request.json").delete()
            File(compose.activity.filesDir, "native-back-ack.json").delete()
            // 失败时也只清理本测试播放；不让 15 秒视频自然结束干扰后续回归。
            runCatching { compose.runOnIdle {
                playback.stop()
                app.playerClosed(playback.lastPositionMs)
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } }
            runCatching { drainReports(playback) }
        }
    }

    private fun showControlsThenReturn() {
        if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag("player-back")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player-video").performVerificationTouchInput(compose) { click(androidx.compose.ui.geometry.Offset(width * .5f, height * .3f)) }
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("player-back")).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithTag("player-back").performVerificationClick(compose)
    }

    private fun requestExternalBack(path: ReturnPath) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = InstrumentationRegistry.getArguments().getString("pathfin.externalInputRunId")
        val requestId = UUID.randomUUID().toString()
        var focusedSince: Long? = null
        var focusedWindow: View? = null
        compose.waitUntil(WAIT_MS) {
            compose.runOnIdle {
                val activity = compose.activity
                val decor = activity.window.decorView
                val ready = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !activity.isFinishing && !activity.isDestroyed && decor.isAttachedToWindow &&
                    decor.hasWindowFocus() && decor.width > 0 && decor.height > 0
                if (!ready || focusedWindow !== decor) { focusedSince = null; focusedWindow = decor }
                else if (focusedSince == null) focusedSince = SystemClock.uptimeMillis()
                ready && SystemClock.uptimeMillis() - (focusedSince ?: Long.MAX_VALUE) >= 500
            }
        }
        val request = compose.runOnIdle {
            val activity = compose.activity
            focusPlayer(compose.activity)
            val decor = activity.window.decorView
            check(decor.hasWindowFocus()) { "外部输入前验证窗口失去系统焦点" }
            val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val navigationMode = Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1)
            if (path == ReturnPath.NATIVE_GESTURE) {
                check(!locked) { "真实边缘返回需要已解锁设备，showWhenLocked 不能验证锁屏下的系统手势" }
                check(navigationMode !in setOf(0, 1)) { "真实边缘返回需要手势导航，当前 navigation_mode=$navigationMode" }
            }
            val location = IntArray(2).also(decor::getLocationOnScreen)
            JSONObject().apply {
                put("version", 2)
                put("run_id", runId.orEmpty())
                put("id", requestId)
                put("type", if (path == ReturnPath.NATIVE_KEY) "key" else "gesture")
                put("package", context.packageName)
                put("activity", MainActivity::class.java.name)
                put("window_focused", true)
                put("focused_for_ms", SystemClock.uptimeMillis() - checkNotNull(focusedSince))
                put("display_id", decor.display?.displayId ?: 0)
                put("window_left", location[0]); put("window_top", location[1])
                put("width", decor.width); put("height", decor.height)
                put("device_locked", locked); put("navigation_mode", navigationMode)
            }
        }
        // IO 在测试线程完成，AtomicFile 确保外部脚本不能读到半份 JSON。
        val file = AtomicFile(File(context.filesDir, "native-back-request.json"))
        val output = file.startWrite()
        try { output.write(request.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        // 无 runId 保留人工读取 marker 后注入的模式；自动脚本必须回执本次单次 input 的状态。
        if (runId != null) compose.waitUntil(WAIT_MS) {
            val ack = runCatching {
                val ackFile = File(context.filesDir, "native-back-ack.json")
                if (!ackFile.isFile || ackFile.length() !in 1L..8192L) null else JSONObject(ackFile.readText())
            }.getOrNull()
            if (ack == null || ack.optString("run_id") != runId || ack.optString("id") != requestId) false
            else {
                check(ack.optString("status") == "input_sent" && ack.optInt("returncode", -1) == 0) {
                    "系统输入未确认成功：${ack.optString("error").take(512)}"
                }
                true
            }
        }
    }

    private fun rotateWhilePaused(app: AppViewModel, playback: PlaybackViewModel, player: ExoPlayer) {
        val previous = compose.runOnIdle { compose.activity }
        val target = if (previous.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        compose.runOnIdle {
            previous.requestedOrientation = if (target == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(WAIT_MS) {
            compose.runOnIdle {
                val current = compose.activity
                current === previous && current.resources.configuration.orientation == target &&
                    current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        compose.runOnIdle {
            assertSame("方向变化应由同一 Activity 处理，避免 PiP/旋转重建视频窗口", previous, compose.activity)
            assertSame("方向改变不能创建第二个 ExoPlayer", player, playback.player)
            assertFalse("方向改变不能恢复已暂停的播放", player.playWhenReady)
        }
        // configChanges 处理普通旋转；显式重建仍独立验证原有 ViewModel/播放器恢复合同。
        compose.activityRule.scenario.recreate()
        compose.waitUntil(WAIT_MS) {
            compose.runOnIdle {
                val current = compose.activity
                current !== previous && current.resources.configuration.orientation == target &&
                    current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        compose.runOnIdle {
            assertSame("方向改变后保留 AppViewModel", app, ViewModelProvider(compose.activity)[AppViewModel::class.java])
            assertSame("方向改变后保留播放器所有者", playback, ViewModelProvider(compose.activity)[PlaybackViewModel::class.java])
            assertSame("方向改变不能创建第二个 ExoPlayer", player, playback.player)
            assertFalse("方向改变不能恢复已暂停的播放", player.playWhenReady)
            assertNotNull(app.state.value.activePlayback)
        }
    }

    private fun physicalBack(app: AppViewModel) = compose.runOnIdle {
        val activity = compose.activity
        focusPlayer(activity)
        val down = SystemClock.uptimeMillis()
        assertTrue("必须消费播放器 BACK DOWN", activity.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)))
        assertNotNull("BACK DOWN 不应提前关闭播放", app.state.value.activePlayback)
        assertTrue("长按重复 DOWN 也应被消费", activity.dispatchKeyEvent(KeyEvent(down, down + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1)))
        assertTrue("必须消费完整的 BACK UP，不能泄漏到 Activity 默认返回", activity.dispatchKeyEvent(KeyEvent(down, down + 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)))
    }

    private fun focusPlayer(activity: MainActivity) {
        fun find(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        val view = checkNotNull(find(activity.window.decorView)) { "真实 PlayerView 尚未挂载" }
        view.isFocusableInTouchMode = true
        assertTrue("原生 PlayerView 应能取得焦点", view.requestFocus())
        assertTrue("返回时焦点必须位于原生 PlayerView 内", view.hasFocus())
    }

    private fun drainReports(playback: PlaybackViewModel) {
        runBlocking { withTimeout(WAIT_MS) { playback.awaitPendingReports() } }
        fixture.assertHealthy()
    }

    /** 不修改正式 API/fixture；只记录固定回环上真实送达的合成会话事件。 */
    private class RecordingApiProxy : Closeable {
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).build()
        private val verifiedOrigin = verifyFixture()
        private val reports = ConcurrentLinkedQueue<Pair<String, String>>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-return-proxy").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept()
                sockets.add(socket)
                executor.execute { serve(socket) }
            } catch (error: SocketException) {
                if (!closed.get()) failures.add(error)
            }
        }, "pathfin-return-accept").apply { isDaemon = true; start() }

        private fun verifyFixture(): String {
            client.newCall(Request.Builder().url("$FIXTURE_ORIGIN/health").build()).execute().use { response ->
                check(response.code == 200) { "固定回环合成服务未就绪" }
                val json = JSONObject(response.body.string())
                check(json.opt("Fixture") == true && json.opt("Id") == "pathfin-fixture-server") { "拒绝连接非合成 Jellyfin 服务" }
            }
            return FIXTURE_ORIGIN
        }

        fun eventCount(path: String, session: String) = reports.count { it.first == path && it.second == session }

        fun assertHealthy() { failures.peek()?.let { throw AssertionError("播放器回环代理失败", it) } }

        private fun serve(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = DataInputStream(connection.getInputStream())
                    val request = line(input).split(' ')
                    check(request.size == 3 && request[0] in setOf("GET", "POST", "DELETE"))
                    val target = request[1]
                    check(target.startsWith('/') && !target.startsWith("//"))
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val header = line(input)
                        if (header.isEmpty()) break
                        check(headers.size < 128)
                        val colon = header.indexOf(':'); check(colon > 0)
                        headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                    }
                    check("transfer-encoding" !in headers)
                    val size = headers["content-length"]?.toInt() ?: 0
                    check(size in 0..1_048_576)
                    val body = ByteArray(size).also(input::readFully)
                    val upstream = Request.Builder().url(verifiedOrigin + target).apply {
                        headers.filterKeys { it !in setOf("host", "connection", "content-length", "accept-encoding") }.forEach { (name, value) -> header(name, value) }
                        method(request[0], if (request[0] == "GET") null else body.toRequestBody(headers["content-type"]?.toMediaTypeOrNull()))
                    }.build()
                    client.newCall(upstream).execute().use { response ->
                        val source = response.body.source()
                        check(!source.request(8_388_609))
                        val bytes = source.readByteArray()
                        val path = target.substringBefore('?')
                        if (path in REPORT_PATHS) {
                            check(response.code == 204)
                            val event = JSONObject(body.decodeToString())
                            check(event.opt("ItemId") == ITEM_ID && event.opt("MediaSourceId") == "fixture-source")
                            val session = event.getString("PlaySessionId")
                            check(session.matches(Regex("fixture-session-\\d+")))
                            reports.add(path to session)
                        }
                        val type = response.header("Content-Type") ?: "application/json"
                        connection.getOutputStream().apply {
                            write(("HTTP/1.1 ${response.code} Fixture\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(bytes); flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) failures.add(error)
            } finally { sockets.remove(socket) }
        }

        private fun line(input: DataInputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 32_768) {
                val value = input.read()
                if (value < 0) throw EOFException("回环请求头未结束")
                if (value == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(value)
            }
            error("回环请求头过长")
        }

        override fun close() {
            closed.set(true)
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            accept.join(1_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private enum class ReturnPath { TOP, DISPATCHER, PHYSICAL, AFTER_ROTATION, NATIVE_KEY, NATIVE_GESTURE }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val FIXTURE_ORIGIN = "http://127.0.0.1:8097"
        const val ITEM_ID = "fixture-movie"
        const val WAIT_MS = 20_000L
        const val START = "/Sessions/Playing"
        const val STOP = "/Sessions/Playing/Stopped"
        val REPORT_PATHS = setOf(START, "/Sessions/Playing/Progress", STOP)

        fun clearSyntheticSession(context: Context) {
            val store = SessionStore(context)
            store.loadProfiles().forEach { store.removeProfile(it.id) }
            store.clear()
        }
    }
}

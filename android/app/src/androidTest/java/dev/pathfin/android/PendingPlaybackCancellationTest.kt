package dev.pathfin.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.playback.PlaybackViewModel
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 真实 MainActivity/Exo + 可控回环响应；返回不能被迟到的下一集或版本准备重新打开。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PendingPlaybackCancellationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolatedStorage = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == TARGET) { "待播放取消回归只允许 verification 包" }
            val store = AppPreferencesStore(context)
            val previous = store.load()
            clearSyntheticSession()
            store.save(preferences())
            try { base.evaluate() }
            finally { clearSyntheticSession(); store.save(previous) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test fun backWhileTheNextEpisodeIsBlockedCannotReopenPlaybackAfterItsLateResponse() {
        fixture(blockDetail = true).use { fixture ->
            val active = startFirstEpisode(fixture)
            try {
                main {
                    active.app.prepareEpisodePlayback(episode(TWO))
                    assertEquals("点击下一集即同步发布待播目标", TWO, active.app.state.value.pendingPlayback?.item?.id)
                    assertTrue(active.app.state.value.pendingPlayback?.loadDetails == true)
                }
                fixture.nextDetail.awaitEntered()
                val pending = pendingJob(active.app)
                main { assertTrue(active.app.state.value.preparingPlay) }
                fixture.nextDetail.expectCancellation.set(true)
                main { compose.activity.onBackPressedDispatcher.onBackPressed() }
                // v2 标准调度器会排队执行 PredictiveBackHandler collector；实际关闭后才允许迟到响应返回。
                await(fixture) { main {
                    pending.isCancelled && active.app.state.value.pendingPlayback == null &&
                        active.app.state.value.activePlayback == null && active.playback.player == null && active.player.isReleased
                } }
                fixture.nextDetail.release()
                fixture.nextDetail.awaitResponseAttempt()
                join(pending)
                assertReturnedWithoutPlayback(active, fixture)
                assertEquals("已返回的迟到详情不能继续请求下一集 PlaybackInfo", 0, fixture.nextPlaybackRequests.get())
                assertEquals(0, fixture.nextMediaRequests.get())
            } finally { stop(active) }
        }
    }

    @Test fun sourceSelectionCommitsBeforeAccountStorageAndClosingCancelsTheQueuedTimestamp() {
        fixture(multipleSources = true).use { fixture ->
            val active = startFirstEpisode(fixture)
            val storage = AppViewModel::class.java.getDeclaredField("storageMutex").apply { isAccessible = true }
                .get(active.app) as Mutex
            var locked = false
            try {
                join(viewModelJob(active.app, "lastPlayedJob"))
                val before = SessionStore(context).loadProfiles().single().lastPlayedAt
                main { active.app.preparePlayback(episode(TWO), fromStart = true) }
                await(fixture) { active.app.state.value.sourceSelection?.info?.sources?.size == 2 && !active.app.state.value.preparingPlay }
                runBlocking { withTimeout(WAIT) { storage.lock() } }; locked = true
                main {
                    val state = active.app.state.value
                    assertEquals(TWO, state.pendingPlayback?.item?.id)
                    assertEquals(TWO, state.sourceSelection?.item?.id)
                    assertFalse(state.preparingPlay)
                    active.app.chooseSource("source-$TWO-first")
                }
                // 持有真实存储锁也必须完成选源并实际起播；正常绘制以移除旧选源 Dialog。
                await(fixture) { main {
                    val state = active.app.state.value
                    state.playbackError?.let { throw AssertionError("选源提交失败：$it") }
                    active.playback.state.value.errorMessage?.let { throw AssertionError("第二集真实起播失败：$it") }
                    state.activePlayback?.item?.id == TWO && state.pendingPlayback == null && state.sourceSelection == null &&
                        !state.preparingPlay && active.playback.player !== active.player &&
                        active.playback.state.value.hasRenderedFrame && active.playback.player?.isPlaying == true
                } }
                VerificationDisplay.awaitWindowReady(compose, "selected-player-before-storage-return",
                    compose.onNodeWithTag("playback-back-motion"))
                val nextPlayer = main { checkNotNull(active.playback.player) }
                val timestamp = main {
                    assertTrue("提交及起播时真实存储锁仍须被测试持有", storage.isLocked)
                    assertTrue("系统返回前必须是宿主 Activity 的真实窗口焦点，不能让旧选源 Dialog 接收", compose.activity.hasWindowFocus())
                    val job = checkNotNull(AppViewModel::class.java.getDeclaredField("lastPlayedJob")
                        .apply { isAccessible = true }.get(active.app) as Job?)
                    assertTrue("时间戳确实在等待真实存储锁", job.isActive)
                    job
                }
                runBlocking { withTimeout(WAIT) { active.playback.awaitPendingReports() } }
                assertEquals(1, fixture.eventCount("start", ONE))
                assertEquals("切集必须恰好停止原会话一次", 1, fixture.eventCount("stop", ONE))
                assertEquals("持锁期间第二集也必须真实建立一次会话", 1, fixture.eventCount("start", TWO))
                assertEquals(0, fixture.eventCount("stop", TWO))
                assertTrue("第二集必须已经读取真实媒体，而不是仅提交 VM 状态", fixture.nextMediaRequests.get() > 0)

                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                // 取消完成前不释放存储锁，不能让排队写入抢先落盘。
                await(fixture) { main {
                    val state = active.app.state.value
                    timestamp.isCancelled && state.route == Route.Detail(ONE) && state.activePlayback == null &&
                        state.pendingPlayback == null && state.sourceSelection == null && !state.preparingPlay &&
                        active.playback.player == null && active.player.isReleased && nextPlayer.isReleased && !compose.activity.isFinishing
                } }
                assertTrue("返回必须取消尚未落盘的时间戳任务", timestamp.isCancelled)
                storage.unlock(); locked = false
                join(timestamp)
                runBlocking { withTimeout(WAIT) { active.playback.awaitPendingReports() } }
                assertEquals("取消后的排队时间戳不能修改账号资料", before, SessionStore(context).loadProfiles().single().lastPlayedAt)
                assertEquals("版本选择信息确实经过真实 API", 1, fixture.nextPlaybackRequests.get())
                assertEquals(1, fixture.eventCount("start", ONE))
                assertEquals(1, fixture.eventCount("stop", ONE))
                assertEquals(1, fixture.eventCount("start", TWO))
                assertEquals("系统返回必须恰好停止第二集一次", 1, fixture.eventCount("stop", TWO))
            } finally {
                if (locked) storage.unlock()
                stop(active)
            }
        }
    }

    @Test fun closingBeforeTheFirstPlaybackExistsAlsoCancelsItsLatePreparation() {
        fixture(blockPlayback = true).use { fixture ->
            val app = login(fixture)
            try {
                main {
                    app.preparePlayback(episode(TWO), fromStart = true)
                    assertEquals(TWO, app.state.value.pendingPlayback?.item?.id)
                    assertTrue(app.state.value.preparingPlay)
                    assertNull(app.state.value.activePlayback)
                }
                fixture.nextPlayback.awaitEntered()
                val pending = pendingJob(app)
                main { assertNull(app.state.value.activePlayback); assertTrue(app.state.value.preparingPlay) }
                fixture.nextPlayback.expectCancellation.set(true)
                main { app.playerClosed(0) }
                fixture.nextPlayback.release()
                fixture.nextPlayback.awaitResponseAttempt()
                join(pending)
                await(fixture) {
                    val state = app.state.value
                    !state.preparingPlay && state.pendingPlayback == null && state.sourceSelection == null && state.activePlayback == null && state.playbackError == null
                }
                assertEquals("尚未建立播放器时关闭也不能被迟到的响应起播", 0, fixture.nextMediaRequests.get())
                assertEquals(0, fixture.eventCount("start", TWO))
            } finally { main { app.playerClosed(0) } }
        }
    }

    @Test fun confirmedDeletionCancelsThePendingEpisodesBlockedPlaybackInfo() {
        fixture(blockPlayback = true, allowDeletes = true).use { fixture ->
            val app = login(fixture)
            try {
                main { app.preparePlayback(episode(TWO), fromStart = true) }
                fixture.nextPlayback.awaitEntered()
                fixture.nextPlayback.expectCancellation.set(true)
                val pending = pendingJob(app)
                main { app.deleteMedia(episode(TWO)) }
                fixture.delete.awaitEntered()
                main {
                    assertEquals("DELETE 未确认前不能取消正在准备的媒体", TWO, app.state.value.pendingPlayback?.item?.id)
                    assertTrue(pending.isActive)
                }
                fixture.delete.release()
                await(fixture) { "delete/$TWO" !in app.state.value.actionBusy }
                main {
                    assertTrue("删除确认后必须取消该条目的在途播放准备", pending.isCancelled)
                    assertNull(app.state.value.pendingPlayback)
                    assertNull(app.state.value.sourceSelection)
                    assertFalse(app.state.value.preparingPlay)
                }
                fixture.nextPlayback.release()
                fixture.nextPlayback.awaitResponseAttempt()
                join(pending)
                assertNull(app.state.value.activePlayback)
                assertEquals(listOf(TWO), fixture.deletedIds.toList())
                assertEquals(0, fixture.nextMediaRequests.get())
                assertEquals(0, fixture.eventCount("start", TWO))
            } finally { main { app.playerClosed(0) } }
        }
    }

    @Test fun deletingTheParentSeasonClearsAWaitingSourceSelection() {
        fixture(multipleSources = true, allowDeletes = true).use { fixture ->
            val app = login(fixture)
            try {
                main { app.preparePlayback(episode(TWO), fromStart = true) }
                await(fixture) { app.state.value.sourceSelection?.info?.sources?.size == 2 }
                val source = checkNotNull(app.state.value.sourceSelection).info.sources.first().id
                main { app.deleteMedia(MediaItem(SEASON, "Pending season", "Season", seriesId = SERIES)) }
                fixture.delete.awaitEntered()
                fixture.delete.release()
                await(fixture) { "delete/$SEASON" !in app.state.value.actionBusy }
                main {
                    assertNull("父季删除必须清除版本选择的待播身份", app.state.value.pendingPlayback)
                    assertNull(app.state.value.sourceSelection)
                    assertFalse(app.state.value.preparingPlay)
                    app.chooseSource(source)
                    assertNull("旧版本入口不能重新启动已删除父季中的单集", app.state.value.activePlayback)
                }
                assertEquals(listOf(SEASON), fixture.deletedIds.toList())
                assertEquals(0, fixture.nextMediaRequests.get())
                assertEquals(0, fixture.eventCount("start", TWO))
            } finally { main { app.playerClosed(0) } }
        }
    }

    @Test fun deletingAnotherEpisodeKeepsPreparationButDeletingItsSeriesCancelsIt() {
        fixture(blockPlayback = true, allowDeletes = true).use { fixture ->
            val app = login(fixture)
            try {
                main { app.preparePlayback(episode(TWO), fromStart = true) }
                fixture.nextPlayback.awaitEntered()
                fixture.nextPlayback.expectCancellation.set(true)
                val pending = pendingJob(app)
                main { app.deleteMedia(episode(ONE)) }
                fixture.delete.awaitEntered()
                fixture.delete.release()
                await(fixture) { "delete/$ONE" !in app.state.value.actionBusy }
                main {
                    assertEquals("同剧同季的其他集被删不能取消当前目标", TWO, app.state.value.pendingPlayback?.item?.id)
                    assertTrue(pending.isActive)
                    app.deleteMedia(MediaItem(SERIES, "Pending series", "Series"))
                }
                await(fixture) { SERIES in fixture.deletedIds && "delete/$SERIES" !in app.state.value.actionBusy }
                main {
                    assertTrue("父剧删除必须取消其单集的在途播放准备", pending.isCancelled)
                    assertNull(app.state.value.pendingPlayback)
                    assertNull(app.state.value.sourceSelection)
                    assertFalse(app.state.value.preparingPlay)
                }
                fixture.nextPlayback.release()
                fixture.nextPlayback.awaitResponseAttempt()
                join(pending)
                assertNull(app.state.value.activePlayback)
                assertEquals(listOf(ONE, SERIES), fixture.deletedIds.toList())
                assertEquals(0, fixture.nextMediaRequests.get())
                assertEquals(0, fixture.eventCount("start", TWO))
            } finally { main { app.playerClosed(0) } }
        }
    }

    private fun fixture(blockDetail: Boolean = false, blockPlayback: Boolean = false, multipleSources: Boolean = false,
        allowDeletes: Boolean = false): Fixture {
        check(context.packageName == TARGET)
        val video = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        return Fixture(video, blockDetail, blockPlayback, multipleSources, allowDeletes = allowDeletes)
    }

    private fun login(fixture: Fixture): AppViewModel {
        val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        await(fixture) { !app.state.value.restoringSession }
        main { app.login(fixture.origin, "pending-fixture-user", "pending-fixture-password", true, "Pending playback fixture") }
        await(fixture) {
            app.state.value.loginError?.let { throw AssertionError("合成登录失败：$it") }
            val state = app.state.value
            state.session?.serverId == SERVER_ID && state.home.loaded && !state.home.loading
        }
        return app
    }

    private fun startFirstEpisode(fixture: Fixture): Active {
        val app = login(fixture)
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        main { app.open(episode(ONE)) }
        await(fixture) { !app.state.value.detailLoading && ONE in app.state.value.details }
        main { app.preparePlayback(app.state.value.details.getValue(ONE), fromStart = true) }
        await(fixture) {
            app.state.value.playbackError?.let { throw AssertionError("合成起播失败：$it") }
            playback.state.value.errorMessage?.let { throw AssertionError("真实 Exo 起播失败：$it") }
            main { app.state.value.activePlayback?.item?.id == ONE && playback.state.value.hasRenderedFrame && playback.player?.isPlaying == true }
        }
        return Active(app, playback, main { checkNotNull(playback.player) })
    }

    private fun pendingJob(app: AppViewModel): Job = viewModelJob(app, "playJob")

    private fun viewModelJob(app: AppViewModel, field: String): Job = main {
        checkNotNull(AppViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(app) as Job?)
    }

    private fun join(job: Job) = runBlocking { withTimeout(WAIT) { job.join() } }

    private fun assertReturnedWithoutPlayback(active: Active, fixture: Fixture) {
        await(fixture) { main {
            val state = active.app.state.value
            state.route == Route.Detail(ONE) && state.activePlayback == null && !state.preparingPlay &&
                state.pendingPlayback == null && state.sourceSelection == null && state.playbackError == null &&
                active.playback.player == null && active.player.isReleased && !compose.activity.isFinishing
        } }
        runBlocking { withTimeout(WAIT) { active.playback.awaitPendingReports() } }
        assertEquals(1, fixture.eventCount("start", ONE))
        assertEquals("返回必须恰好停止原会话一次", 1, fixture.eventCount("stop", ONE))
        assertEquals("迟到的下一集不能开始会话", 0, fixture.eventCount("start", TWO))
        fixture.assertHealthy()
    }

    private fun stop(active: Active) {
        main { active.playback.stop(); active.app.playerClosed(active.playback.lastPositionMs) }
        runBlocking { withTimeout(WAIT) { active.playback.awaitPendingReports() } }
    }

    private fun await(fixture: Fixture, condition: () -> Boolean) = compose.waitUntil(WAIT) {
        fixture.assertHealthy()
        condition()
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun clearSyntheticSession() {
        check(context.packageName == TARGET)
        val store = SessionStore(context)
        val profiles = store.loadProfiles()
        check(profiles.all { it.session.serverId == SERVER_ID && it.session.accessToken == TOKEN }) { "存在本测试以外的账号，拒绝清理" }
        profiles.forEach { store.removeProfile(it.id) }
        store.clear()
    }

    private data class Active(val app: AppViewModel, val playback: PlaybackViewModel, val player: ExoPlayer)

    internal class ResponseGate(block: Boolean) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(if (block) 1 else 0)
        val finished = CountDownLatch(1)
        val expectCancellation = AtomicBoolean()
        fun block() { entered.countDown(); check(released.await(WAIT, TimeUnit.MILLISECONDS)) { "合成响应未释放" } }
        fun release() = released.countDown()
        fun awaitEntered() = assertTrue("请求必须实际到达独立回环服务", entered.await(WAIT, TimeUnit.MILLISECONDS))
        fun awaitResponseAttempt() = assertTrue("必须完成迟到响应尝试后才能断言不会重入", finished.await(WAIT, TimeUnit.MILLISECONDS))
    }

    internal class Fixture(private val video: ByteArray, blockDetail: Boolean = false, blockPlayback: Boolean = false,
        private val multipleSources: Boolean = false, blockSeries: Boolean = false,
        private val externalSource: String? = null, private val cloudScenario: CloudScenario? = null,
        private val negotiationVideo: JSONObject? = null, private val allowDeletes: Boolean = false,
        private val reverseProxyPayload: Boolean = false) : Closeable {
        enum class CloudScenario { ORIGINAL_ONLY, WITH_H264_RENDITIONS }

        private val cloudProxy = if (reverseProxyPayload && externalSource != null)
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build() else null
        val seriesResolution = ResponseGate(blockSeries)
        val playbackStatus = AtomicInteger(200)
        val nextDetail = ResponseGate(blockDetail)
        val nextPlayback = ResponseGate(blockPlayback)
        val delete = ResponseGate(true)
        val deletedIds = ConcurrentLinkedQueue<String>()
        val playbackBodies = ConcurrentLinkedQueue<JSONObject>()
        val playbackSourceSuffix = AtomicReference("")
        val acceptVideoNegotiation = AtomicBoolean(true)
        val nextPlaybackRequests = AtomicInteger()
        val nextMediaRequests = AtomicInteger()
        val cloudVariantRequests = AtomicInteger()
        val cloudResolveStatus = AtomicInteger(200)
        val cloudExtensionProtocol = AtomicReference("mediaflux.playback.v1")
        val cloudResolutions = ConcurrentLinkedQueue<String>()
        val cloudMediaRequests = ConcurrentLinkedQueue<String>()
        val serverMediaRequests = AtomicInteger()
        val transcodeMediaRequests = AtomicInteger()
        private val events = ConcurrentLinkedQueue<Pair<String, String>>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-pending-playback-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val origin = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                workers.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures += error }
        }, "pathfin-pending-playback-accept").apply { isDaemon = true; start() }

        fun eventCount(event: String, id: String) = events.count { it.first == event && it.second == id }
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("待播放取消 fixture 失败", it) } }

        private fun serve(socket: Socket) {
            var gate: ResponseGate? = null
            var media = false
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT.toInt()
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val line = reader.readLine() ?: return
                    val parts = line.split(' ')
                    check(parts.size == 3 && parts[2] == "HTTP/1.1")
                    val headers = mutableMapOf<String, String>()
                    var ended = false
                    repeat(64) { if (!ended) {
                        val header = reader.readLine() ?: throw EOFException("合成请求头未完成")
                        check(header.length <= 8_192)
                        if (header.isEmpty()) ended = true else {
                            check(':' in header)
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                    } }
                    check(ended)
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    check(length in 0..65_536)
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val size = reader.read(body, offset, length - offset)
                        if (size < 0) throw EOFException("合成请求体未完成")
                        offset += size
                    }
                    val uri = URI(parts[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        val pair = it.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    val method = parts[0]
                    val forwardedCloudView = cloudProxy != null && uri.rawPath == URI(externalSource!!).rawPath &&
                        query["view"] in setOf("variants", "resolve")
                    val cloud = forwardedCloudView || uri.path.startsWith("/playgy/") || uri.path.startsWith("/cloud-media/")
                    media = uri.path.startsWith("/media/") || uri.path.startsWith("/cloud-media/")
                    if (cloud) {
                        check(cloudScenario != null || forwardedCloudView)
                        check(listOf("authorization", "x-emby-token", "x-emby-authorization", "x-mediabrowser-token", "cookie")
                            .none { headers.containsKey(it) } && headers.values.none { TOKEN in it || "proxy-secret" in it } &&
                            !uri.toString().contains(TOKEN)) { "云盘请求不能携带 Jellyfin 或反代凭据" }
                    } else if (uri.path != "/Users/AuthenticateByName" && uri.path != "/System/Info/Public")
                        check(headers["authorization"].orEmpty().contains(TOKEN)) { "合成认证头缺失" }
                    gate = when {
                        method == "GET" && uri.path == "/Items" && query["parentId"] == "pending-series" -> seriesResolution
                        method == "GET" && uri.path == "/Items" && query["ids"] == TWO -> nextDetail
                        method == "POST" && uri.path == "/Items/$TWO/PlaybackInfo" -> nextPlayback.also { nextPlaybackRequests.incrementAndGet() }
                        allowDeletes && method == "DELETE" && uri.path in setOf("/Items/$ONE", "/Items/$TWO", "/Items/$SERIES", "/Items/$SEASON") -> delete
                        else -> null
                    }
                    gate?.block()
                    if (forwardedCloudView) {
                        check(method == "GET" && headers["accept"] == "application/json")
                        val advertised = externalSource!!.toHttpUrl()
                        check(query["v"] == advertised.queryParameter("v") && query["sig"] == advertised.queryParameter("sig"))
                        val upstream = advertised.newBuilder().encodedQuery(uri.rawQuery).build()
                        if (query["view"] == "variants") cloudVariantRequests.incrementAndGet()
                        else cloudResolutions += requireNotNull(query["quality"])
                        cloudProxy!!.newCall(Request.Builder().url(upstream).header("Accept", "application/json").build()).execute().use { response ->
                            respond(connection, response.code, response.body.bytes(), response.header("Content-Type") ?: "application/json")
                        }
                    } else if (media) {
                        check(method == "GET" || method == "HEAD")
                        if (uri.path.startsWith("/media/")) serverMediaRequests.incrementAndGet()
                        if (uri.path == "/media/$TWO.mp4") nextMediaRequests.incrementAndGet()
                        if (uri.path.startsWith("/cloud-media/")) cloudMediaRequests += uri.path.substringAfterLast('/').removeSuffix(".mp4")
                        if (uri.path.startsWith("/media/transcoded/")) transcodeMediaRequests.incrementAndGet()
                        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) ?: error("无效合成 Range") }
                        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
                        check(start in video.indices && end >= start)
                        respond(connection, if (range == null) 200 else 206, video.copyOfRange(start, end + 1), "video/mp4",
                            if (range == null) null else "bytes $start-$end/${video.size}", head = method == "HEAD")
                    } else {
                        val status = when {
                            gate === nextPlayback -> playbackStatus.get()
                            uri.path.startsWith("/playgy/") && query["view"] == "resolve" -> cloudResolveStatus.get()
                            else -> 200
                        }
                        val response = if (status == 200) response(method, uri.path, query, String(body)) else JSONObject().put("Message", "Synthetic preparation unavailable")
                        respond(connection, if (status != 200) status else if (response == null) 204 else 200,
                            response?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0), "application/json")
                    }
                }
            } catch (error: Throwable) {
                // 只有测试主动取消的完整请求及已验证的媒体连接允许正常断开；协议断言不能吞掉。
                if (!closed.get() && !(error is IOException && (media || gate?.expectCancellation?.get() == true))) failures += error
            } finally {
                gate?.finished?.countDown()
                sockets.remove(socket)
            }
        }

        private fun response(method: String, path: String, query: Map<String, String>, body: String): Any? = when {
            path == "/Users/AuthenticateByName" -> {
                check(method == "POST")
                val credentials = JSONObject(body)
                check(credentials.getString("Username") == "pending-fixture-user" && credentials.getString("Pw") == "pending-fixture-password")
                JSONObject().put("AccessToken", TOKEN).put("ServerId", SERVER_ID)
                    .put("User", user())
            }
            path == "/System/Info/Public" -> JSONObject().put("Id", SERVER_ID).put("ServerName", "Pending fixture").put("Version", "12.3.0")
            path == "/Users/Me" -> user()
            allowDeletes && path in setOf("/Items/$ONE", "/Items/$TWO", "/Items/$SERIES", "/Items/$SEASON") -> {
                val id = path.removePrefix("/Items/")
                when (method) {
                    "GET" -> item(id).put("CanDelete", true)
                    "DELETE" -> { deletedIds += id; null }
                    else -> error("无效合成媒体删除方法：$method")
                }
            }
            path == "/UserViews" -> page(JSONObject().put("Id", "pending-library").put("Name", "Pending episodes").put("Type", "CollectionFolder").put("CollectionType", "tvshows").put("IsFolder", true))
            path == "/Items/Latest" -> JSONArray().put(item(ONE))
            path == "/UserItems/Resume" || path == "/Shows/NextUp" || path.endsWith("/Similar") -> page()
            path == "/Shows/pending-series/Episodes" -> page(item(ONE), item(TWO))
            path == "/Items" -> query["ids"]?.let { page(item(it)) } ?: page(item(ONE), item(TWO))
            method == "POST" && path.startsWith("/Items/") && path.endsWith("/PlaybackInfo") -> {
                val request = JSONObject(body)
                playbackBodies += request
                val id = path.removePrefix("/Items/").substringBefore('/')
                val transcoding = cloudScenario != null && !request.optBoolean("EnableDirectPlay", true)
                val selected = request.optString("MediaSourceId")
                val negotiated = acceptVideoNegotiation.get() && negotiationVideo != null && selected.isNotBlank() &&
                    request.getJSONObject("DeviceProfile").getJSONArray("CodecProfiles").let { profiles ->
                        (0 until profiles.length()).map(profiles::getJSONObject).filter { it.optString("Codec") == "h264" }
                            .any { profile -> profile.getJSONArray("Conditions").let { conditions ->
                                (0 until conditions.length()).map(conditions::getJSONObject).any {
                                    it.optString("Property") == "VideoFramerate" &&
                                        it.optString("Value").toDoubleOrNull() == kotlin.math.ceil(negotiationVideo.getDouble("AverageFrameRate"))
                                }
                            } }
                    }
                val sources = JSONArray().put(source(id, "first", transcoding, negotiated))
                if (id == TWO && multipleSources) sources.put(source(id, "second", transcoding, negotiated))
                JSONObject().put("MediaSources", sources).put("PlaySessionId", "pending-session-$id")
            }
            method == "GET" && path.startsWith("/playgy/") && cloudScenario != null -> cloudResponse(path, query)
            method == "DELETE" && path == "/Videos/ActiveEncodings" && (cloudScenario != null || negotiationVideo != null) -> null
            method == "POST" && path.startsWith("/Sessions/Playing") -> {
                val event = when (path) { "/Sessions/Playing" -> "start"; "/Sessions/Playing/Progress" -> "progress"; "/Sessions/Playing/Stopped" -> "stop"; else -> error("未知上报路径") }
                val report = JSONObject(body)
                val id = report.getString("ItemId")
                check(id == ONE || id == TWO)
                check(report.getString("PlaySessionId") == "pending-session-$id")
                events += event to id
                null
            }
            else -> error("待播放取消 fixture 未定义接口：$method $path")
        }

        private fun user() = JSONObject().put("Id", "pending-user").put("Name", "Pending fixture").apply {
            if (allowDeletes) put("Policy", JSONObject().put("EnableContentDeletion", true))
        }

        private fun item(id: String) = JSONObject().put("Id", id).put("Name", "Pending $id")
            .put("Type", when (id) { SERIES -> "Series"; SEASON -> "Season"; else -> "Episode" })
            .put("SeriesId", SERIES).put("SeasonId", SEASON).put("SeriesName", "Pending series").put("ParentIndexNumber", 1)
            .put("IndexNumber", if (id == ONE) 1 else 2).put("RunTimeTicks", 240_000_000L).put("ImageTags", JSONObject())
        private fun source(id: String, version: String, transcoding: Boolean = false, negotiated: Boolean = false): JSONObject {
            val cloudOrigin = if (reverseProxyPayload) "http://127.0.0.1" else origin
            val cloudUrl = externalSource ?: if (cloudScenario != null || reverseProxyPayload)
                "$cloudOrigin/playgy/pending/disk/file/$version?v=1&sig=${"a".repeat(64)}" else null
            return JSONObject()
            .put("Id", "source-$id-$version").put("Name", version + playbackSourceSuffix.get())
            .put("Path", if (reverseProxyPayload) "$origin/mf-proxy/$id/$version${playbackSourceSuffix.get()}/stream" else cloudUrl ?: "/synthetic/$id.mp4")
            .put("Protocol", if (!reverseProxyPayload && cloudUrl != null) "Http" else "File").put("Container", "mp4")
            .put("DirectStreamUrl", "$origin/media/$id.mp4").put("SupportsDirectPlay", true).put("SupportsDirectStream", true)
            .apply {
                if (reverseProxyPayload) {
                    put("MediaFluxPlayback", JSONObject().put("protocol", cloudExtensionProtocol.get()).put("source_url", cloudUrl))
                    put("RequiredHttpHeaders", JSONObject().put("Authorization", "Bearer proxy-secret")
                        .put("Cookie", "proxy=private").put("X-Emby-Token", TOKEN))
                }
                negotiationVideo?.let { video ->
                    put("MediaStreams", JSONArray().put(JSONObject(video.toString())))
                    put("SupportsDirectPlay", negotiated).put("SupportsDirectStream", negotiated)
                    if (!negotiated) {
                        remove("DirectStreamUrl")
                        put("TranscodingUrl", "$origin/media/transcoded/$id.mp4?MediaSourceId=source-$id-$version")
                    }
                }
                if (cloudScenario != null) {
                    val stream = JSONObject().put("Type", "Video").put("Index", 0)
                    if (multipleSources && version == "first") {
                        stream.put("Codec", "h264").put("Width", 320).put("Height", 180)
                    } else {
                        // 尺寸必然超出真实手机的解码上限，避免假定每台设备都不支持 DV5。
                        stream.put("Codec", "hevc").put("Profile", "Main 10").put("Width", 65_536).put("Height", 65_536)
                            .put("BitDepth", 10).put("VideoRange", "HDR").put("VideoRangeType", "DOVI").put("DvProfile", 5)
                    }
                    put("MediaStreams", JSONArray().put(stream))
                    if (transcoding) put("TranscodingUrl", "$origin/media/transcoded/$id.mp4?MediaSourceId=source-$id-$version")
                }
            }
        }

        private fun cloudResponse(path: String, query: Map<String, String>): JSONObject {
            val original = if (multipleSources && path.substringAfterLast('/') == "first")
                cloudVariant("original", original = true, width = 320, height = 180)
                else cloudVariant("original", original = true, width = 3_840, height = 2_160, codec = "hevc")
            val variants = listOf(original) + if (cloudScenario == CloudScenario.WITH_H264_RENDITIONS) listOf(
                cloudVariant("standard", width = 1_280, height = 720), cloudVariant("high", width = 1_920, height = 1_080),
            ) else emptyList()
            return JSONObject().put("protocol", "mediaflux.playback.v1").apply {
                when (query["view"]) {
                    "variants" -> { cloudVariantRequests.incrementAndGet(); put("variants", JSONArray(variants)) }
                    "resolve" -> {
                        val quality = requireNotNull(query["quality"])
                        cloudResolutions += quality
                        put("variant", variants.single { it.getString("id") == quality })
                        put("url", "$origin/cloud-media/$quality.mp4")
                    }
                    else -> error("未知云盘播放请求")
                }
            }
        }

        private fun cloudVariant(id: String, original: Boolean = false, width: Int, height: Int, codec: String = "h264") =
            JSONObject().put("id", id).put("original", original).put("label", id).put("width", width).put("height", height)
                .put("video_codec", codec).put("audio_codec", "aac").put("transport", "file").put("mime_type", "video/mp4")
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size)
        private fun respond(socket: Socket, status: Int, body: ByteArray, type: String, range: String? = null, head: Boolean = false) {
            val header = buildString {
                append("HTTP/1.1 $status Synthetic\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nAccept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); if (!head) write(body); flush() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            seriesResolution.release(); nextDetail.release(); nextPlayback.release(); delete.release()
            server.close()
            // 先停止派发连接，避免最后一次 accept 把任务交给已关闭的线程池。
            accept.join(1_000)
            sockets.toList().forEach { runCatching { it.close() } }
            check(!accept.isAlive) { "待播放取消 fixture 接收线程未停止" }
            workers.shutdownNow()
            cloudProxy?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
            assertHealthy()
        }
    }

    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val SERVER_ID = "pending-playback-fixture"
        const val TOKEN = "pending-playback-synthetic-token"
        const val ONE = "pending-episode-one"
        const val TWO = "pending-episode-two"
        const val SERIES = "pending-series"
        const val SEASON = "pending-season"
        const val WAIT = 25_000L
        fun preferences() = AppPreferences(originalPathEnabled = false,
            subtitlesEnabled = false, exoAssEnabled = false, serverIntroSkipEnabled = false,
            autoPlayNextEpisode = false, showClock = false, showBattery = false)
        fun episode(id: String) = MediaItem(id, "Pending $id", "Episode", seriesId = SERIES, seasonId = SEASON, runTimeTicks = 240_000_000L)
    }
}

package dev.pathfin.android

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.PlaybackResolver
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PlaybackViewModel
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 自制静音 WAV 与进程内回环 API；无需 Python fixture、adb reverse 或真实服务器。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackReportingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun declaredDashContainerSelectsActualDashMediaSourceForOpaqueUrl() {
        val context = instrumentation.targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "播放回归只允许独立 verification 包" }
        val session = Session(ServerConfig("https://fixture.invalid"), "test-token", "test-user", "Test", "test-device")
        for (container in listOf("mpd", "DASH")) {
            val source = MediaSource(
                "dash-source", path = "https://fixture.invalid/opaque?sig=Ab%2fCd%2B==", container = container,
            )
            val media = MediaItem("dash-item", "DASH 播放测试", "Movie")
            val stream = PlaybackResolver.resolve(session, media, PlaybackInfo(listOf(source), "test-play"), source.id)
            val item = PlayerMediaItem.Builder().setUri(stream.uri).setMimeType(stream.mimeType).build()
            val mediaSource = DefaultMediaSourceFactory(context).createMediaSource(item)
            assertTrue("无扩展名 $container 必须交给 DASH 清单解析器", mediaSource is DashMediaSource)
            assertEquals("选择解析器不能改写已签名地址", source.path, mediaSource.mediaItem.localConfiguration?.uri.toString())
        }
    }

    @Test
    fun backgroundPauseReportsOnceAndPreservesPausedSeekAndStopEvents() {
        val context = instrumentation.targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "播放回归只允许独立 verification 包" }
        val preferences = AppPreferencesStore(context)
        val previousPreferences = preferences.load()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        var playback: PlaybackViewModel? = null
        preferences.save(AppPreferences())
        try {
            ReportingFixture().use { fixture ->
                // 保持真实前台 Activity，使 AudioFocus 与设备的正常播放条件一致。
                ActivityScenario.launch(MainActivity::class.java).use {
                    try {
                        lateinit var model: PlaybackViewModel
                        instrumentation.runOnMainSync {
                            model = ViewModelProvider(
                                owner,
                                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application),
                            )[PlaybackViewModel::class.java]
                            playback = model
                            model.play(fixture.request(), AppPreferences(introSkipSeconds = 5, outroSkipSeconds = 5))
                        }
                        runBlocking {
                            withTimeout(WAIT_MS) {
                                model.state.first { state ->
                                    fixture.assertHealthy()
                                    state.errorMessage?.let { throw AssertionError("静音样本播放失败：$it") }
                                    state.isPlaying
                                }
                            }
                        }
                        drainReports(model, fixture)
                        assertEquals(1, fixture.events("/Sessions/Playing").size)
                        assertEquals("音乐必须忽略视频跳片头偏好", 0L, fixture.events("/Sessions/Playing").single().getLong("PositionTicks"))
                        assertEquals(0, fixture.events("/Sessions/Playing/Progress").size)

                        instrumentation.runOnMainSync { model.pauseForBackground() }
                        drainReports(model, fixture)
                        val paused = fixture.events("/Sessions/Playing/Progress")
                        assertEquals("从实际播放进入后台只能发送一次暂停进度", 1, paused.size)
                        assertTrue(paused.single().getBoolean("IsPaused"))

                        instrumentation.runOnMainSync { model.pauseForBackground() }
                        drainReports(model, fixture)
                        assertEquals("已经暂停时仍应显式保存当前进度", 2, fixture.events("/Sessions/Playing/Progress").size)

                        instrumentation.runOnMainSync { checkNotNull(model.player).seekTo(SEEK_MS) }
                        drainReports(model, fixture)
                        val afterSeek = fixture.events("/Sessions/Playing/Progress")
                        assertEquals("暂停时 Seek 仍需单独上报", 3, afterSeek.size)
                        assertEquals(SEEK_MS * 10_000, afterSeek.last().getLong("PositionTicks"))
                        assertTrue(afterSeek.last().getBoolean("IsPaused"))

                        instrumentation.runOnMainSync { model.stop(); model.stop() }
                        drainReports(model, fixture)
                        assertEquals("暂停与 Seek 不能创建第二次开始上报", 1, fixture.events("/Sessions/Playing").size)
                        val stopped = fixture.events("/Sessions/Playing/Stopped")
                        assertEquals("重复退出只能发送一次停止上报", 1, stopped.size)
                        assertEquals(SEEK_MS * 10_000, stopped.single().getLong("PositionTicks"))
                    } finally {
                        playback?.let { model ->
                            instrumentation.runOnMainSync { model.stop() }
                            drainReports(model, fixture)
                        }
                        instrumentation.runOnMainSync { owner.viewModelStore.clear() }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            preferences.save(previousPreferences)
        }
    }

    @Test
    fun slowProgressDoesNotQueueObsoleteSeeksAheadOfFinalStopAndTheNextSessionStart() = withReportingModel { model, fixture, _ ->
        modelStart(model, fixture.request())
        awaitPlaying(model, fixture)
        drainReports(model, fixture)
        val first = fixture.blockNextProgress()
        instrumentation.runOnMainSync { model.pauseForBackground() }
        first.awaitRequest()

        listOf(2_000L, 5_000L, 11_000L, 17_000L).forEach { seekPaused(model, it) }
        instrumentation.runOnMainSync {
            model.stop()
            assertEquals(17_000L, model.lastPositionMs)
            model.play(fixture.request("next-play", "next-wav"))
        }
        awaitPlaying(model, fixture)
        assertEquals("旧进度请求未结束前不能抢先发送下一会话开始", 1, fixture.events("/Sessions/Playing").size)
        assertEquals("在发请求等待期间不应另起并行上报", 1, fixture.events("/Sessions/Playing/Progress").size)

        first.release()
        drainReports(model, fixture)
        assertEquals("过期的待发 seek 应由最终停止位置取代", 1, fixture.events("/Sessions/Playing/Progress").size)
        val stopped = fixture.events("/Sessions/Playing/Stopped").single()
        assertEquals("test-play", stopped.getString("PlaySessionId"))
        assertEquals(17_000L * 10_000, stopped.getLong("PositionTicks"))
        assertEquals("start/stop 顺序必须反映真实播放会话，不能被合并或重排",
            listOf("/Sessions/Playing" to "test-play", "/Sessions/Playing/Progress" to "test-play",
                "/Sessions/Playing/Stopped" to "test-play", "/Sessions/Playing" to "next-play"),
            fixture.sequence().map { it.first to it.second.getString("PlaySessionId") })
    }

    @Test
    fun aPendingReportBarrierKeepsThePriorProgressBoundaryWhileLaterSeeksCollapseIntoStop() = withReportingModel { model, fixture, _ ->
        modelStart(model, fixture.request())
        awaitPlaying(model, fixture)
        drainReports(model, fixture)
        val first = fixture.blockNextProgress()
        instrumentation.runOnMainSync { model.pauseForBackground() }
        first.awaitRequest()
        seekPaused(model, 2_000L)

        runBlocking {
            // UNDISPATCHED 保证屏障已经真实入队才发起后续 seek；不依赖线程调度碰运气。
            val priorReports = async(start = CoroutineStart.UNDISPATCHED) { model.awaitPendingReports() }
            assertFalse("在发 Progress 未完成时屏障必须保持等待", priorReports.isCompleted)
            seekPaused(model, 7_000L)
            seekPaused(model, 13_000L)
            instrumentation.runOnMainSync { model.stop() }
            first.release()
            withTimeout(WAIT_MS) { priorReports.await(); model.awaitPendingReports() }
        }
        fixture.assertHealthy()
        val progress = fixture.events("/Sessions/Playing/Progress")
        assertEquals("屏障之前的最新进度不能被屏障之后的 seek 或 stop 淘汰", 2, progress.size)
        assertEquals(2_000L * 10_000, progress.last().getLong("PositionTicks"))
        val stopped = fixture.events("/Sessions/Playing/Stopped").single()
        assertEquals(13_000L * 10_000, stopped.getLong("PositionTicks"))
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"),
            fixture.sequence().map { it.first })
    }

    @Test
    fun slowProgressWithoutStoppingSendsOnlyTheLatestPausedSnapshotIncludingABackwardSeek() = withReportingModel { model, fixture, _ ->
        modelStart(model, fixture.request())
        awaitPlaying(model, fixture)
        drainReports(model, fixture)
        val first = fixture.blockNextProgress()
        instrumentation.runOnMainSync { model.pauseForBackground() }
        first.awaitRequest()
        listOf(5_000L, 17_000L, 2_000L).forEach { seekPaused(model, it) }
        assertEquals(1, fixture.events("/Sessions/Playing/Progress").size)

        first.release()
        drainReports(model, fixture)
        val progress = fixture.events("/Sessions/Playing/Progress")
        assertEquals("没有 stop 时仍应只发送一个最新待发快照", 2, progress.size)
        assertEquals(2_000L * 10_000, progress.last().getLong("PositionTicks"))
        assertTrue(progress.last().getBoolean("IsPaused"))
        assertTrue(fixture.events("/Sessions/Playing/Stopped").isEmpty())
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Progress"), fixture.sequence().map { it.first })
    }

    @Test
    fun clearingViewModelDrainsTheFinalStopAndAwaitAfterCloseWaitsForTheActualHttpResponse() = withReportingModel { model, fixture, viewModelStore ->
        modelStart(model, fixture.request())
        awaitPlaying(model, fixture)
        drainReports(model, fixture)
        val first = fixture.blockNextProgress()
        instrumentation.runOnMainSync { model.pauseForBackground() }
        first.awaitRequest()
        seekPaused(model, 17_000L)
        instrumentation.runOnMainSync { viewModelStore.clear() }
        assertEquals(17_000L, model.lastPositionMs)
        assertTrue(fixture.events("/Sessions/Playing/Stopped").isEmpty())

        runBlocking {
            val drained = async(start = CoroutineStart.UNDISPATCHED) { model.awaitPendingReports() }
            assertFalse("队列关闭不代表最终 stop 已发完，必须等待独立 worker", drained.isCompleted)
            first.release()
            withTimeout(WAIT_MS) { drained.await() }
        }
        fixture.assertHealthy()
        val stopped = fixture.events("/Sessions/Playing/Stopped").single()
        assertEquals(17_000L * 10_000, stopped.getLong("PositionTicks"))
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), fixture.sequence().map { it.first })
    }

    private fun withReportingModel(block: (PlaybackViewModel, ReportingFixture, ViewModelStore) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName == VERIFICATION_PACKAGE)
        val store = AppPreferencesStore(context)
        val previous = store.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        store.save(AppPreferences())
        try {
            ReportingFixture().use { fixture ->
                ActivityScenario.launch(MainActivity::class.java).use {
                    lateinit var model: PlaybackViewModel
                    instrumentation.runOnMainSync {
                        val provider = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                        model = provider[PlaybackViewModel::class.java]
                    }
                    try { block(model, fixture, owner.viewModelStore) }
                    finally {
                        fixture.releaseProgressGates()
                        instrumentation.runOnMainSync { model.stop() }
                        drainReports(model, fixture)
                        instrumentation.runOnMainSync { owner.viewModelStore.clear() }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            store.save(previous)
        }
    }

    private fun modelStart(model: PlaybackViewModel, request: PlaybackRequest) = instrumentation.runOnMainSync {
        model.play(request, AppPreferences(serverIntroSkipEnabled = false))
    }

    private fun awaitPlaying(model: PlaybackViewModel, fixture: ReportingFixture) {
        runBlocking { withTimeout(WAIT_MS) { model.state.first { state ->
            fixture.assertHealthy()
            state.errorMessage?.let { throw AssertionError("静音样本播放失败：$it") }
            state.isPlaying
        } } }
        // isPlaying 在 Listener 发布后才排入 start；等 Main 完成本轮回调再放屏障。
        instrumentation.waitForIdleSync()
    }

    private fun seekPaused(model: PlaybackViewModel, positionMs: Long) {
        instrumentation.runOnMainSync {
            model.seekToPosition(positionMs)
            model.pauseForBackground()
            assertEquals("测试必须真正移动 Player 到目标位置", positionMs, checkNotNull(model.player).currentPosition)
        }
        instrumentation.waitForIdleSync()
    }

    private fun drainReports(model: PlaybackViewModel, fixture: ReportingFixture) {
        runBlocking { withTimeout(WAIT_MS) { model.awaitPendingReports() } }
        fixture.assertHealthy()
    }

    private class ProgressGate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun awaitRequest() = assertTrue("Progress 请求必须真实进入回环服务", entered.await(WAIT_MS, TimeUnit.MILLISECONDS))
        fun hold() { entered.countDown(); check(released.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "合成 Progress 响应未释放" } }
        fun release() = released.countDown()
    }

    private class ReportingFixture : Closeable {
        private val reports = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val nextProgressGate = AtomicReference<ProgressGate?>()
        private val progressGates = ConcurrentLinkedQueue<ProgressGate>()
        private val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "pathfin-playback-response").apply { isDaemon = true }
        }
        private val server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        private val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val sample = silentWav()
        private val acceptThread = Thread({
            while (!closed.get()) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    executor.execute { serve(socket) }
                } catch (failure: SocketException) {
                    if (!closed.get()) failures.add(failure)
                }
            }
        }, "pathfin-playback-accept").apply { isDaemon = true; start() }

        fun request(playSessionId: String = "test-play", itemId: String = "wav-item"): PlaybackRequest {
            val session = Session(
                ServerConfig(baseUrl, allowInsecureHttp = true), "test-token", "test-user", "Test", "test-device",
            )
            val source = MediaSource("wav-source", path = "$baseUrl/silence.wav", protocol = "Http", container = "wav")
            val info = PlaybackInfo(listOf(source), playSessionId)
            val item = MediaItem(itemId, "静音播放上报测试", "Audio")
            return PlaybackRequest(
                item, info, source,
                PlaybackResolver.resolve(session, item, info, source.id), 0, session,
            )
        }

        fun events(path: String): List<JSONObject> = reports.filter { it.first == path }.map { it.second }
        fun sequence(): List<Pair<String, JSONObject>> = reports.toList()
        fun blockNextProgress() = ProgressGate().also {
            check(nextProgressGate.compareAndSet(null, it))
            progressGates += it
        }
        fun releaseProgressGates() = progressGates.forEach { it.release() }

        fun assertHealthy() {
            failures.peek()?.let { throw AssertionError("播放回环服务执行失败", it) }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val request = input.readLine()?.split(' ') ?: return
                    check(request.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: throw EOFException("HTTP 请求头未完成")
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        check(separator > 0)
                        headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                    }
                    val path = request[1].substringBefore('?')
                    if (request[0] == "GET" && path == "/silence.wav") {
                        val range = headers["range"]
                        val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        check(start in sample.indices)
                        val rangeHeader = if (range == null) "" else "Content-Range: bytes $start-${sample.lastIndex}/${sample.size}\r\n"
                        val output = connection.getOutputStream()
                        output.write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n" +
                            "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\n" + rangeHeader +
                            "Content-Length: ${sample.size - start}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(sample, start, sample.size - start)
                        output.flush()
                    } else {
                        check(request[0] == "POST" && path in REPORT_PATHS) { "未定义测试路由：${request[0]} $path" }
                        val length = headers["content-length"]?.toInt() ?: 0
                        check(length in 1..32_768)
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val read = input.read(body, offset, length - offset)
                            if (read < 0) throw EOFException("HTTP 请求体未完成")
                            offset += read
                        }
                        reports.add(path to JSONObject(String(body)))
                        if (path == "/Sessions/Playing/Progress") nextProgressGate.getAndSet(null)?.hold()
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }
            } catch (failure: IOException) {
                // 暂停/Seek/释放会取消样本读取；API 失败会通过上报数量断言体现。
                if (!closed.get() && failure !is SocketException) failures.add(failure)
            } catch (failure: Throwable) {
                if (!closed.get()) failures.add(failure)
            } finally {
                sockets.remove(socket)
            }
        }

        override fun close() {
            closed.set(true)
            releaseProgressGates()
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            acceptThread.join(1_000)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val WAIT_MS = 20_000L
        const val SEEK_MS = 5_000L
        val REPORT_PATHS = setOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped")

        /** 60 秒、16 kHz、单声道 PCM；全零样本不会向测试手机发出声音。 */
        fun silentWav(): ByteArray {
            val sampleRate = 16_000
            val dataSize = sampleRate * 2 * 60
            return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
                putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
            }.array()
        }
    }
}

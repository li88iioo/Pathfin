package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.PlayerScreen
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 完全进程内合成 Jellyfin + 自有 MP4；不读取账号/偏好，不依赖用户服务或额外端口 reverse。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackSegmentsFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun blockedSegmentsDoNotBlockFirstFrameAndLateResultCannotRestoreStoppedPlayback() {
        withFixture(blockSegments = true) { fixture, playback ->
            show(playback)
            main { playback.play(fixture.request(), preferences()) }
            assertTrue(fixture.segmentRequested.await(5, TimeUnit.SECONDS))
            awaitFrame(playback)
            assertEquals(1L, fixture.releaseSegments.count)
            main { assertNull(playback.state.value.introPrompt); playback.stop() }
            fixture.releaseSegments.countDown()
            assertTrue(fixture.segmentResponseAttempted.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            main { assertNull(playback.player); assertNull(playback.state.value.introPrompt); assertFalse(playback.state.value.isPlaying) }
        }
    }

    @Test fun serverAutoSkipWaitsWhilePausedAndOnlySeeksOnceEvenAfterRewind() {
        withFixture(blockSegments = true) { fixture, playback ->
            show(playback)
            main { playback.play(fixture.request(), preferences().copy(autoSkipIntro = true)) }
            awaitFrame(playback)
            val player = main { checkNotNull(playback.player).also { it.pause(); it.seekTo(1_000) } }
            awaitPausedPosition(playback, 1_000)
            val jumps = AtomicInteger()
            main { player.addListener(seekCounter(8_000, jumps)) }
            fixture.releaseSegments.countDown()
            compose.waitUntil(WAIT) { playback.state.value.introPrompt != null }
            SystemClock.sleep(1_100)
            main { assertFalse(player.isPlaying); assertTrue(player.currentPosition < 2_000); assertEquals(0, jumps.get()); player.play() }
            compose.waitUntil(WAIT) { jumps.get() == 1 && main { player.currentPosition >= 7_800 } }
            main { player.pause(); player.seekTo(1_000) }
            awaitPausedPosition(playback, 1_000)
            main { player.play() }
            compose.waitUntil(WAIT) { main { player.isPlaying && player.currentPosition >= 1_600 } }
            main { assertTrue(player.currentPosition < 5_000); assertEquals(1, jumps.get()); assertNull(playback.state.value.introPrompt) }
        }
    }

    @Test fun manualIntroPromptHidesInLockAndPipThenActuallySeeksAndCanBeUsedOnReplay() {
        withFixture { fixture, playback ->
            val pip = mutableStateOf(false)
            compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop, isInPictureInPicture = pip.value) } }
            main { playback.play(fixture.request(), preferences()) }
            awaitFrame(playback)
            main { playback.player?.pause() }
            compose.waitUntil(WAIT) { playback.state.value.introPrompt != null }
            compose.onNodeWithTag("player-skip-intro").assertVerificationDisplayed(compose)
            main { playback.setControlsLocked(true) }
            compose.onNodeWithTag("player-skip-intro").assertDoesNotExist()
            main { playback.setControlsLocked(false); pip.value = true }
            compose.onNodeWithTag("player-skip-intro").assertDoesNotExist()
            main { pip.value = false }
            compose.onNodeWithTag("player-skip-intro").performVerificationClick(compose)
            compose.waitUntil(WAIT) { main { playback.player?.currentPosition?.let { it >= 7_800 } == true } }
            compose.onNodeWithTag("player-skip-intro").assertDoesNotExist()
            main { playback.play(fixture.request(), preferences()) }
            awaitFrame(playback)
            compose.waitUntil(WAIT) { playback.state.value.introPrompt != null }
            compose.onNodeWithTag("player-skip-intro").assertVerificationDisplayed(compose)
        }
    }

    @Test fun nextEpisodePromptUsesRemainingTimeAndRealActionResetsForSameEpisodeReplay() {
        withFixture { fixture, playback ->
            val preferences = preferences().copy(serverIntroSkipEnabled = false, nextEpisodePromptSeconds = 30)
            compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop,
                onNext = { playback.play(fixture.request(NEXT_ITEM), preferences) }) } }
            main { playback.play(fixture.request(), preferences) }
            awaitFrame(playback)
            val duration = main { checkNotNull(playback.player).also { it.pause() }.duration }
            main { playback.seekToPosition(duration - 31_000) }
            awaitPausedPosition(playback, duration - 31_000)
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
            main { playback.seekToPosition(duration - 20_000) }
            awaitPausedPosition(playback, duration - 20_000)
            compose.waitUntil(WAIT) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("player-next-prompt")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player-next-prompt-dismiss").performVerificationClick(compose)
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
            main { playback.setControlsLocked(true); playback.setControlsLocked(false) }
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
            main { playback.play(fixture.request(), preferences) }
            awaitFrame(playback)
            main { playback.player?.pause(); playback.seekToPosition(checkNotNull(playback.player).duration - 10_000) }
            compose.waitUntil(WAIT) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("player-next-prompt-action")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player-next-prompt-action").performVerificationClick(compose)
            awaitFrame(playback)
            main { assertEquals("Synthetic next", playback.state.value.title); assertTrue(checkNotNull(playback.player).currentPosition < 5_000) }
            main { playback.play(fixture.request(), preferences.copy(nextEpisodePromptSeconds = 0)) }
            awaitFrame(playback)
            val end = main { checkNotNull(playback.player).duration - 5_000 }
            main { playback.player?.pause(); playback.seekToPosition(end) }
            awaitPausedPosition(playback, end)
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
        }
    }

    @Test fun longPressUsesActualBaseSpeedAndDisablingSlideStillAllowsHoldAndRestoration() {
        withFixture { fixture, playback ->
            show(playback)
            for (slide in listOf(true, false)) {
                main { playback.play(fixture.request(), preferences().copy(serverIntroSkipEnabled = false, longPressSlideSpeedEnabled = slide)) }
                awaitFrame(playback)
                main {
                    val player = checkNotNull(playback.player)
                    playback.setPlaybackSpeed(1.5f)
                    playback.beginTemporarySpeed()
                    assertEquals(3.75f, player.playbackParameters.speed, .001f)
                    playback.updateTemporarySpeedBySlide(1f)
                    assertEquals(if (slide) 4f else 3.75f, player.playbackParameters.speed, .001f)
                    playback.endTemporarySpeed()
                    assertEquals(1.5f, player.playbackParameters.speed, .001f)
                    playback.beginTemporarySpeed()
                    playback.pauseForBackground()
                    assertEquals(1.5f, player.playbackParameters.speed, .001f)
                    assertNull(playback.state.value.temporarySpeed)
                }
            }
        }
    }

    @Test fun exoRapidDraggingProducesRealSeeksBeforeReleaseOnlyWhenEnabledAndAlwaysCommitsTheEnd() {
        withFixture { fixture, playback ->
            show(playback)
            for (rapid in listOf(false, true)) {
                main { playback.play(fixture.request(), preferences().copy(serverIntroSkipEnabled = false, rapidSeekWhileDragging = rapid)) }
                awaitFrame(playback)
                main { playback.player?.pause() }
                val seeks = AtomicInteger()
                main { checkNotNull(playback.player).addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    }
                }) }
                val slider = compose.onNodeWithTag("player-seek-slider")
                slider.performVerificationTouchInput(compose) {
                    down(Offset(width * .1f, height / 2f))
                    moveTo(Offset(width * .3f, height / 2f))
                    moveTo(Offset(width * .5f, height / 2f))
                }
                if (rapid) compose.waitUntil(WAIT) { seeks.get() > 0 } else { compose.waitForIdle(); assertEquals(0, seeks.get()) }
                slider.performVerificationTouchInput(compose) { moveTo(Offset(width * .8f, height / 2f)); up() }
                compose.waitUntil(WAIT) { main { checkNotNull(playback.player).let { it.playbackState == Player.STATE_READY && it.currentPosition > it.duration * .65 } } }
                if (!rapid) assertEquals("关闭拖动预览时应只在松手 seek 一次", 1, seeks.get())
                main { assertFalse(checkNotNull(playback.player).isPlaying); assertNull(playback.state.value.errorMessage) }
            }
        }
    }

    private fun show(playback: PlaybackViewModel) { compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop) } } }
    private fun preferences() = AppPreferences(subtitlesEnabled = false,
        autoPlayNextEpisode = false, serverIntroSkipEnabled = true, autoSkipIntro = false, introSkipSeconds = 0,
        introPromptSeconds = 0, showClock = false, showBattery = false)

    private fun withFixture(blockSegments: Boolean = false, block: (Fixture, PlaybackViewModel) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val fixture = Fixture(blockSegments)
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        var failure: Throwable? = null
        try { block(fixture, playback) } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                try {
                    fixture.releaseSegments.countDown()
                    main { playback.stop() }
                    runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } }
                } finally { fixture.close() }
                fixture.failures.firstOrNull()?.let { throw AssertionError("合成片段服务请求不正确", it) }
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun awaitFrame(playback: PlaybackViewModel) = compose.waitUntil(WAIT) {
        playback.state.value.errorMessage?.let { throw AssertionError("合成片段播放失败：$it") }
        playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
    }
    private fun awaitPausedPosition(playback: PlaybackViewModel, position: Long) = compose.waitUntil(WAIT) {
        main { playback.player?.let { !it.isPlaying && it.playbackState == Player.STATE_READY && kotlin.math.abs(it.currentPosition - position) <= 300 } == true }
    }
    private fun seekCounter(target: Long, counter: AtomicInteger) = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && kotlin.math.abs(newPosition.positionMs - target) <= 150) counter.incrementAndGet()
        }
    }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    private class Fixture(blockSegments: Boolean) : AutoCloseable {
        private val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("playback-video/still.mp4").use { it.readBytes() }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closed = AtomicBoolean()
        private val playIds = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val segmentRequested = CountDownLatch(1)
        val releaseSegments = CountDownLatch(if (blockSegments) 1 else 0)
        val segmentResponseAttempted = CountDownLatch(1)
        private val origin = "http://127.0.0.1:${server.localPort}"
        private val session = Session(ServerConfig(origin, true), "synthetic-segments-token", "synthetic-user", "Synthetic", "synthetic-device")

        init {
            Thread({ while (!closed.get()) try {
                val socket = server.accept()
                sockets += socket
                workers.execute {
                    try { serve(socket) } catch (_: IOException) { /* 客户端退出/取消允许关闭合成连接。 */ }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    catch (error: Throwable) { failures += error }
                    finally { sockets -= socket; runCatching { socket.close() } }
                }
            } catch (error: IOException) { if (!closed.get()) failures += error }
            }, "Pathfin-segments-fixture").apply { isDaemon = true }.start()
        }

        fun request(itemId: String = ITEM): PlaybackRequest {
            val source = MediaSource("synthetic-source", path = "$origin/media.mp4", protocol = "Http", container = "mp4", supportsDirectPlay = true)
            val item = MediaItem(itemId, if (itemId == NEXT_ITEM) "Synthetic next" else "Synthetic segments", "Episode", runTimeTicks = 1_200_000_000L)
            return PlaybackRequest(item, PlaybackInfo(listOf(source), "synthetic-play-${playIds.incrementAndGet()}"), source,
                ResolvedPlayback("$origin/media.mp4", emptyMap(), PlayMethod.DIRECT_PLAY, source.id, true, "video/mp4"), 0, session)
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = reader.readLine() ?: return
            val request = line.split(' ')
            check(request.size == 3 && request[2] == "HTTP/1.1")
            val headers = mutableMapOf<String, String>()
            var complete = false
            repeat(48) {
                if (!complete) {
                    val header = reader.readLine() ?: return
                    check(header.length <= 8_192)
                    if (header.isEmpty()) complete = true else headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                }
            }
            check(complete)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            check(contentLength in 0..32_768)
            repeat(contentLength) { if (reader.read() < 0) return }
            val path = request[1].substringBefore('?')
            if (request[0] == "POST" && path.startsWith("/Sessions/Playing")) { respond(socket, 204, ByteArray(0)); return }
            check(request[0] == "GET")
            check(headers["authorization"].orEmpty().contains("synthetic-segments-token"))
            if (path.startsWith("/MediaSegments/")) {
                check(request[1].substringAfter('?', "") == "includeSegmentTypes=Intro")
                segmentRequested.countDown()
                check(releaseSegments.await(30, TimeUnit.SECONDS)) { "测试未释放合成片段请求" }
                val id = path.substringAfterLast('/')
                val segment = JSONObject().put("Id", "33333333333333333333333333333333").put("ItemId", id).put("Type", "Intro")
                    .put("StartTicks", 0L).put("EndTicks", 80_000_000L)
                val body = JSONObject().put("Items", JSONArray().put(segment)).put("TotalRecordCount", 1).put("StartIndex", 0).toString().toByteArray()
                try { respond(socket, 200, body, "application/json") } finally { segmentResponseAttempted.countDown() }
                return
            }
            check(path == "/media.mp4")
            val range = headers["range"]?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) ?: throw AssertionError("Unexpected range") }
            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            if (start !in bytes.indices || end < start) { respond(socket, 416, ByteArray(0)); return }
            val rangeHeader = if (range == null) null else "bytes $start-$end/${bytes.size}"
            respond(socket, if (range == null) 200 else 206, bytes.copyOfRange(start, end + 1), "video/mp4", rangeHeader)
        }

        private fun respond(socket: Socket, status: Int, body: ByteArray, type: String = "application/json", range: String? = null) {
            val header = buildString {
                append("HTTP/1.1 $status Synthetic\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nAccept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseSegments.countDown()
            runCatching { server.close() }
            sockets.toList().forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    private companion object {
        const val WAIT = 20_000L
        const val ITEM = "22222222222222222222222222222222"
        const val NEXT_ITEM = "44444444444444444444444444444444"
    }
}

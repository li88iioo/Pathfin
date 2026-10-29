package dev.pathfin.android

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
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
import dev.pathfin.ass.ExoAssExtension
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 专测真实 VM/Screen 的扩展所有权与回退；像素/字体/动画正确性另由扩展专属实播测试验证。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ExoAssPlayerIntegrationTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun realAssOwnerSurvivesLateOldOwnerDetachAfterChangingPlayer() {
        assumeTrue("本项要求真实受支持 ABI；不能把基础字幕回退当成扩展已启用", ExoAssExtension.supportedOnDevice())
        withFixture { fixture, playback ->
            show(playback, preferences())
            main { playback.play(fixture.request(), preferences()) }
            awaitAss(playback)
            val oldPlayer = main { checkNotNull(playback.player) }
            val oldOwner = main { checkNotNull(playback.assExtensionFor(oldPlayer)) }
            main { playback.play(fixture.request(), preferences()) }
            awaitAss(playback)
            main {
                val current = checkNotNull(playback.player)
                val currentOwner = checkNotNull(playback.assExtensionFor(current))
                assertNotSame(oldPlayer, current)
                assertNotSame(oldOwner, currentOwner)
                assertNull(playback.assExtensionFor(oldPlayer))
                val view = checkNotNull(playerView(compose.activity.window.decorView))
                assertSame(current, view.player)
                val canvas = checkNotNull(view.findViewWithTag<View>("pathfin.libass.overlay"))
                assertNotNull(canvas.parent)
                // 模拟旧 AndroidView 延迟释放；旧 owner 必须不能拆掉同一 PlayerView 的新画布。
                oldOwner.detach(view)
                assertSame(canvas, view.findViewWithTag<View>("pathfin.libass.overlay"))
                assertNotNull(canvas.parent)
                assertSame(currentOwner, playback.assExtensionFor(current))
                assertNull(playback.state.value.errorMessage)
                playback.stop()
                oldOwner.detach(view)
                assertNull(playback.player)
            }
        }
    }

    @Test fun disablingExtensionUsesActualBaseSsaCuesWithNoExtensionOwner() {
        withFixture { fixture, playback ->
            val preferences = preferences().copy(exoAssEnabled = false)
            val cueSeen = AtomicBoolean()
            show(playback, preferences)
            main {
                playback.play(fixture.request(), preferences)
                checkNotNull(playback.player).addListener(object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) { if (cueGroup.cues.isNotEmpty()) cueSeen.set(true) }
                })
            }
            compose.waitUntil(WAIT) {
                failOnPlaybackError(playback)
                playback.state.value.hasRenderedFrame && cueSeen.get()
            }
            main {
                val player = checkNotNull(playback.player)
                assertTrue(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
                assertNull(playback.assExtensionFor(player))
                assertNull(playback.state.value.assStatus)
            }
        }
    }

    @Test fun disabledExtensionAtInitialPausedSeekShowsTheActualOverlappingBaseSsaCue() {
        withFixture { fixture, playback ->
            val preferences = preferences().copy(exoAssEnabled = false)
            show(playback, preferences)
            main {
                playback.play(fixture.request().copy(startPositionMs = 16_000), preferences)
                checkNotNull(playback.player).pause()
                playback.setPlaybackSpeed(1.5f)
            }
            compose.waitUntil(WAIT) {
                failOnPlaybackError(playback)
                main { playback.player?.let { it.playbackState == Player.STATE_READY && !it.playWhenReady &&
                    kotlin.math.abs(it.currentPosition - 16_000) <= 300 } == true }
            }
            awaitBaseSsa(playback, expectedText = "SEEK")
            main {
                val player = checkNotNull(playback.player)
                assertFalse(player.isPlaying)
                assertFalse(player.playWhenReady)
                assertTrue(kotlin.math.abs(player.currentPosition - 16_000) <= 300)
                assertEquals(1.5f, player.playbackParameters.speed, .001f)
                assertNull(playback.assExtensionFor(player))
            }
        }
    }

    @Test fun attachmentFailureRebuildsBaseSsaOnceAtCurrentPausedPositionAndIgnoresOldOwner() {
        assumeTrue("挂载失败回归需要先实际启用扩展", ExoAssExtension.supportedOnDevice())
        withFixture { fixture, playback ->
            show(playback, preferences())
            main { playback.play(fixture.request(), preferences()) }
            awaitAss(playback)
            val before = main {
                val player = checkNotNull(playback.player)
                player.pause()
                playback.seekToPosition(16_000)
                player
            }
            compose.waitUntil(WAIT) { main { before.playbackState == Player.STATE_READY && kotlin.math.abs(before.currentPosition - 16_000) <= 300 } }
            val oldOwner = main { checkNotNull(playback.assExtensionFor(before)) }
            main { playback.onAssAttachmentFailure(oldOwner) }
            compose.waitUntil(WAIT) {
                failOnPlaybackError(playback)
                main { playback.player?.let { it !== before && it.playbackState == Player.STATE_READY && !it.playWhenReady && kotlin.math.abs(it.currentPosition - 16_000) <= 300 } == true }
            }
            awaitBaseSsa(playback, expectedText = "SEEK")
            main {
                val restored = checkNotNull(playback.player)
                val generation = playback.state.value.playerGeneration
                assertTrue(kotlin.math.abs(restored.currentPosition - 16_000) <= 300)
                assertFalse(restored.isPlaying)
                assertFalse(restored.playWhenReady)
                assertNull(playback.assExtensionFor(restored))
                assertTrue(playback.state.value.assStatus.orEmpty().contains("已回退基础字幕"))
                playback.onAssAttachmentFailure(oldOwner)
                assertSame(restored, playback.player)
                assertEquals(generation, playback.state.value.playerGeneration)
            }
            compose.waitForIdle()
            main { assertNull(playback.assExtensionFor(playback.player)); assertFalse(checkNotNull(playback.player).playWhenReady) }
        }
    }

    @Test fun runtimeControllerFailureRebuildsActualBaseSsaOnlyOnceWithoutLosingPausedPositionOrSpeed() {
        assumeTrue("运行期失败回归需要先实际启用扩展", ExoAssExtension.supportedOnDevice())
        withFixture { fixture, playback ->
            show(playback, preferences())
            main { playback.play(fixture.request(), preferences()) }
            awaitAss(playback)
            val before = main {
                checkNotNull(playback.player).also {
                    it.pause()
                    playback.setPlaybackSpeed(1.5f)
                    playback.setControlsLocked(true)
                    playback.seekToPosition(16_000)
                }
            }
            compose.waitUntil(WAIT) { main { before.playbackState == Player.STATE_READY && kotlin.math.abs(before.currentPosition - 16_000) <= 300 } }
            val oldOwner = main { checkNotNull(playback.assExtensionFor(before)) }
            val generation = playback.state.value.playerGeneration
            assertFalse(oldOwner.failed)
            injectControllerFailure(oldOwner)
            assertTrue(oldOwner.failed)
            compose.waitUntil(WAIT) {
                failOnPlaybackError(playback)
                main { playback.player?.let { it !== before && it.playbackState == Player.STATE_READY &&
                    !it.playWhenReady && kotlin.math.abs(it.currentPosition - 16_000) <= 300 } == true }
            }
            // 不能只通过“已回退”状态：禁用扩展重建后必须实际收到基础 SSA 文本字幕。
            awaitBaseSsa(playback, expectedText = "SEEK")
            val restored = main {
                checkNotNull(playback.player).also {
                    assertTrue(kotlin.math.abs(it.currentPosition - 16_000) <= 300)
                    assertFalse(it.isPlaying)
                    assertFalse(it.playWhenReady)
                    assertNull(playback.assExtensionFor(it))
                    assertEquals(1.5f, it.playbackParameters.speed, .001f)
                    assertTrue(playback.state.value.controlsLocked)
                    assertEquals(generation + 1, playback.state.value.playerGeneration)
                    assertTrue(playback.state.value.assStatus.orEmpty().contains("已回退基础字幕"))
                }
            }
            injectControllerFailure(oldOwner)
            main { playback.onAssAttachmentFailure(oldOwner) }
            compose.waitForIdle()
            main {
                assertSame(restored, playback.player)
                assertEquals(generation + 1, playback.state.value.playerGeneration)
                assertFalse(restored.playWhenReady)
                assertNull(playback.assExtensionFor(restored))
            }
        }
    }

    private fun preferences() = AppPreferences(
        exoAssEnabled = true, serverIntroSkipEnabled = false, introPromptSeconds = 0,
        showClock = false, showBattery = false, subtitlesEnabled = true)
    private fun show(playback: PlaybackViewModel, preferences: AppPreferences) {
        compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop, preferences = preferences) } }
    }
    private fun awaitAss(playback: PlaybackViewModel) = compose.waitUntil(WAIT) {
        failOnPlaybackError(playback)
        main { playback.player?.let { it.isPlaying && it.currentPosition >= 3_000 && playback.state.value.hasRenderedFrame &&
            playback.state.value.assStatus?.contains("已启用") == true } == true }
    }
    private fun awaitBaseSsa(playback: PlaybackViewModel, expectedText: String) = compose.waitUntil(WAIT) {
        failOnPlaybackError(playback)
        main { playback.player?.currentCues?.cues?.any { it.text?.contains(expectedText) == true } == true }
    }

    /** 只在测试注入模块唯一错误入口；生产 VM 使用公开 failed 标志，无反射或错误开关。 */
    private fun injectControllerFailure(extension: ExoAssExtension) {
        val field = ExoAssExtension::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val controller = checkNotNull(field.get(extension))
        controller.javaClass.getDeclaredMethod("fail").apply { isAccessible = true }.invoke(controller)
    }
    private fun failOnPlaybackError(playback: PlaybackViewModel) { playback.state.value.errorMessage?.let { throw AssertionError("合成 ASS 实播失败：$it") } }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    private fun withFixture(block: (Fixture, PlaybackViewModel) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val fixture = Fixture()
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        var failure: Throwable? = null
        try { block(fixture, playback) } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                try { main { playback.stop() }; runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } } }
                finally { fixture.close() }
                fixture.failures.firstOrNull()?.let { throw AssertionError("合成 ASS 请求不正确", it) }
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private class Fixture : AutoCloseable {
        private val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("ass-extension/embedded-font.mkv").use { it.readBytes() }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(3)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closed = AtomicBoolean()
        private val playIds = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        private val origin = "http://127.0.0.1:${server.localPort}"
        private val session = Session(ServerConfig(origin, true), "synthetic-ass-token", "synthetic-user", "Synthetic", "synthetic-device")

        init {
            Thread({ while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                workers.execute {
                    try { serve(socket) } catch (_: IOException) { }
                    catch (error: Throwable) { failures += error }
                    finally { sockets -= socket; runCatching { socket.close() } }
                }
            } catch (error: IOException) { if (!closed.get()) failures += error }
            }, "Pathfin-ASS-integration-fixture").apply { isDaemon = true }.start()
        }

        fun request(): PlaybackRequest {
            val source = MediaSource("synthetic-ass-source", path = "$origin/fixture.mkv", protocol = "Http", container = "mkv", supportsDirectPlay = true)
            val item = MediaItem("11111111111111111111111111111111", "Synthetic ASS", "Movie", runTimeTicks = 240_000_000L)
            return PlaybackRequest(item, PlaybackInfo(listOf(source), "synthetic-ass-${playIds.incrementAndGet()}"), source,
                ResolvedPlayback("$origin/fixture.mkv", emptyMap(), PlayMethod.DIRECT_PLAY, source.id, true, "video/x-matroska"), 0, session)
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = reader.readLine() ?: return
            val request = line.split(' ')
            check(request.size == 3)
            val headers = mutableMapOf<String, String>()
            var complete = false
            repeat(48) { if (!complete) {
                val header = reader.readLine() ?: return
                check(header.length <= 8_192)
                if (header.isEmpty()) complete = true else headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            } }
            check(complete)
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            check(length in 0..32_768)
            repeat(length) { if (reader.read() < 0) return }
            if (request[0] == "POST" && request[1].startsWith("/Sessions/Playing")) {
                respond(socket, 204, ByteArray(0), "application/json"); return
            }
            check(request[0] == "GET" && request[1] == "/fixture.mkv")
            check(headers["authorization"].orEmpty().contains("synthetic-ass-token"))
            val range = headers["range"]?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) ?: throw AssertionError("Unexpected media range") }
            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            if (start !in bytes.indices || end < start) { respond(socket, 416, ByteArray(0), "video/x-matroska"); return }
            respond(socket, if (range == null) 200 else 206, bytes.copyOfRange(start, end + 1), "video/x-matroska",
                if (range == null) null else "bytes $start-$end/${bytes.size}")
        }

        private fun respond(socket: Socket, status: Int, body: ByteArray, type: String, range: String? = null) {
            val header = buildString {
                append("HTTP/1.1 $status Synthetic\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nAccept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            sockets.toList().forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    private companion object {
        const val WAIT = 30_000L
        fun playerView(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) playerView(view.getChildAt(index))?.let { return it }
            return null
        }
    }
}

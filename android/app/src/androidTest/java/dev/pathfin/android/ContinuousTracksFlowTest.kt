package dev.pathfin.android

import androidx.compose.ui.test.click

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
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

/** 真实 Exo + UI 手选 + 两集自有多轨 MKV；不读取已保存账号或全局偏好。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ContinuousTracksFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun manualAudioAndSubtitleSurviveTheNextEpisodeWithReversedTrackOrderAndOverrideLanguages() {
        withFixture { fixture, playback ->
            val preferences = preferences()
            show(fixture, playback, preferences)
            main { playback.play(fixture.request(ONE), preferences) }
            awaitPlayback(playback)
            pause(playback)
            val firstAudio = chooseTrack(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            val firstSubtitle = chooseTrack(playback, C.TRACK_TYPE_TEXT, "Spanish captions")
            main { playback.player?.play() }
            awaitSelected(playback, "Japanese main", "Spanish captions", "PATHFIN SPANISH")
            pause(playback)
            nextFromUi()
            awaitPlayback(playback)
            awaitSelected(playback, "Japanese main", "Spanish captions", "PATHFIN SPANISH")
            main {
                val owner = checkNotNull(playback.player)
                val secondAudio = tracks(owner, C.TRACK_TYPE_AUDIO).single { it.format.label == "Japanese main" }
                val secondSubtitle = tracks(owner, C.TRACK_TYPE_TEXT).single { it.format.label == "Spanish captions" }
                assertNotEquals(firstAudio.tag, secondAudio.tag)
                assertNotEquals(firstSubtitle.tag, secondSubtitle.tag)
                assertTrue(secondAudio.selected)
                assertTrue(secondSubtitle.selected)
                assertTrue(owner.trackSelectionParameters.preferredAudioLanguages.contains("en"))
                assertTrue(owner.trackSelectionParameters.preferredTextLanguages.contains("en"))
                assertEquals("Synthetic episode two", playback.state.value.title)
            }
        }
    }

    @Test fun manualSubtitleOffSurvivesNextEpisodeAndChoosingAutomaticClearsTheRememberedOff() {
        withFixture { fixture, playback ->
            val preferences = preferences()
            show(fixture, playback, preferences)
            main { playback.play(fixture.request(ONE), preferences) }
            awaitPlayback(playback)
            pause(playback)
            chooseOff()
            compose.waitUntil(WAIT) { main { C.TRACK_TYPE_TEXT in checkNotNull(playback.player).trackSelectionParameters.disabledTrackTypes } }
            nextFromUi()
            awaitPlayback(playback)
            main {
                val owner = checkNotNull(playback.player)
                assertTrue(C.TRACK_TYPE_TEXT in owner.trackSelectionParameters.disabledTrackTypes)
                assertTrue(owner.currentCues.cues.isEmpty())
                assertTrue(tracks(owner, C.TRACK_TYPE_TEXT).none { it.selected })
                assertTrue(owner.trackSelectionParameters.preferredTextLanguages.contains("en"))
            }
            pause(playback)
            ensureControls()
            compose.onNodeWithTag("player-subtitles").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-track-auto"))
            compose.onNodeWithTag("player-track-auto").performClick()
            nextFromUi()
            awaitPlayback(playback)
            awaitSelected(playback, "English main", "English captions", "PATHFIN ENGLISH")
            main { assertFalse(C.TRACK_TYPE_TEXT in checkNotNull(playback.player).trackSelectionParameters.disabledTrackTypes) }
        }
    }

    @Test fun disabledPreferenceExplicitStopAndChangingSeriesOrAccountDoNotCarryManualTracks() {
        withFixture { fixture, playback ->
            show(fixture, playback, preferences())
            val resetCases = listOf("disabled", "stop", "series", "account")
            for (boundary in resetCases) {
                val preferences = preferences().copy(keepTracksForContinuousPlayback = boundary != "disabled")
                main { playback.stop(); playback.play(fixture.request(ONE), preferences) }
                awaitPlayback(playback)
                pause(playback)
                chooseTrack(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
                chooseOff()
                val next = fixture.request(TWO).let { request -> when (boundary) {
                    "series" -> request.copy(item = request.item.copy(seriesId = "different-synthetic-series"))
                    "account" -> request.copy(session = request.session.copy(userId = "different-synthetic-user"))
                    else -> request
                } }
                main { if (boundary == "stop") playback.stop(); playback.play(next, preferences) }
                awaitPlayback(playback)
                awaitSelected(playback, "English main", "English captions", "PATHFIN ENGLISH")
                main { assertFalse("$boundary must clear manual subtitle off", C.TRACK_TYPE_TEXT in checkNotNull(playback.player).trackSelectionParameters.disabledTrackTypes) }
            }
        }
    }

    @Test fun sameEpisodeRetryRetainsManualChoicesWithoutEnablingContinuousInheritance() {
        withFixture { fixture, playback ->
            val preferences = preferences().copy(keepTracksForContinuousPlayback = false)
            show(fixture, playback, preferences)
            main { playback.play(fixture.request(ONE), preferences) }
            awaitPlayback(playback)
            pause(playback)
            chooseTrack(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            chooseTrack(playback, C.TRACK_TYPE_TEXT, "Spanish captions")
            main { playback.player?.play() }
            awaitSelected(playback, "Japanese main", "Spanish captions", "PATHFIN SPANISH")
            val before = main { checkNotNull(playback.player) }
            main { playback.retry() }
            compose.waitUntil(WAIT) { main { playback.player !== before } }
            awaitPlayback(playback)
            awaitSelected(playback, "Japanese main", "Spanish captions", "PATHFIN SPANISH")
            assertEquals(1, fixture.playbackInfoRequests.get())
        }
    }

    @Test fun choosingAutomaticWhileTheNextEpisodeLoadsInvalidatesTheOldPendingSubtitle() =
        newChoiceWinsBeforeDelayedTracks(disabled = false)

    @Test fun turningSubtitlesOffWhileTheNextEpisodeLoadsInvalidatesTheOldPendingSubtitle() =
        newChoiceWinsBeforeDelayedTracks(disabled = true)

    @Test fun naturalCompletionHandsManualChoicesToTheNextEpisodeAfterTheOldPlayerHasBeenReleased() =
        naturalCompletionHandoff(explicitStop = false)

    @Test fun returningOrStoppingAfterNaturalCompletionDiscardsTheOneTimeHandoff() =
        naturalCompletionHandoff(explicitStop = true)

    private fun naturalCompletionHandoff(explicitStop: Boolean) {
        withFixture { fixture, playback ->
            val preferences = preferences()
            show(fixture, playback, preferences)
            main { playback.play(fixture.request(ONE), preferences) }
            awaitPlayback(playback)
            pause(playback)
            chooseTrack(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            chooseTrack(playback, C.TRACK_TYPE_TEXT, "Spanish captions")
            main {
                val owner = checkNotNull(playback.player)
                playback.seekToPosition(owner.duration - 200)
                owner.play()
            }
            compose.waitUntil(WAIT) { assertHealthy(playback); playback.state.value.ended }
            main {
                assertNull("必须经过真实自然结束释放，不能只在旧 active 存在时切集", playback.player)
                if (explicitStop) playback.stop()
                playback.play(fixture.request(TWO), preferences)
            }
            awaitPlayback(playback)
            if (explicitStop) awaitSelected(playback, "English main", "English captions", "PATHFIN ENGLISH")
            else awaitSelected(playback, "Japanese main", "Spanish captions", "PATHFIN SPANISH")
        }
    }

    private fun newChoiceWinsBeforeDelayedTracks(disabled: Boolean) {
        withFixture(blockSecond = true) { fixture, playback ->
            val preferences = preferences()
            show(fixture, playback, preferences)
            main { playback.play(fixture.request(ONE), preferences) }
            awaitPlayback(playback)
            pause(playback)
            chooseTrack(playback, C.TRACK_TYPE_TEXT, "Spanish captions")
            val before = main { checkNotNull(playback.player) }
            nextFromUi()
            assertTrue(fixture.secondRequested.await(5, TimeUnit.SECONDS))
            main {
                val current = checkNotNull(playback.player)
                assertNotSame(before, current)
                assertTrue(current.currentTracks.groups.isEmpty())
                // 与真实 UI 相同的记录→应用顺序；旧候选尚未到达，不能反向覆盖新选择。
                playback.onManualTrackSelection(current, C.TRACK_TYPE_TEXT, null, disabled)
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled).build()
            }
            fixture.releaseSecond.countDown()
            awaitPlayback(playback)
            if (disabled) main {
                val current = checkNotNull(playback.player)
                assertTrue(C.TRACK_TYPE_TEXT in current.trackSelectionParameters.disabledTrackTypes)
                assertTrue(current.currentCues.cues.isEmpty())
                assertTrue(tracks(current, C.TRACK_TYPE_TEXT).none { it.selected })
            } else awaitSelected(playback, "English main", "English captions", "PATHFIN ENGLISH")
        }
    }

    private fun preferences() = AppPreferences(
        keepTracksForContinuousPlayback = true, preferredAudioLanguage = "en", preferredSubtitleLanguage = "en",
        exoAssEnabled = false, subtitlesEnabled = true, serverIntroSkipEnabled = false, introPromptSeconds = 0,
        showClock = false, showBattery = false)

    private fun show(fixture: Fixture, playback: PlaybackViewModel, preferences: AppPreferences) {
        compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop, preferences = preferences,
            onNext = { playback.play(fixture.request(if (playback.state.value.title == "Synthetic episode one") TWO else ONE), preferences) }) } }
    }

    private fun nextFromUi() { ensureControls(); compose.onNodeWithTag("player-next").performClick() }
    private fun pause(playback: PlaybackViewModel) {
        main { playback.player?.pause() }
        compose.waitUntil(WAIT) { main { playback.player?.playWhenReady == false } }
        ensureControls()
    }
    private fun chooseOff() {
        ensureControls()
        compose.onNodeWithTag("player-subtitles").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-track-off"))
        compose.onNodeWithTag("player-track-off").performClick()
    }
    private fun chooseTrack(playback: PlaybackViewModel, type: Int, label: String): TrackRef {
        ensureControls()
        val choice = main { tracks(checkNotNull(playback.player), type).single { it.format.label == label } }
        compose.onNodeWithTag(if (type == C.TRACK_TYPE_AUDIO) "player-audio" else "player-subtitles").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(choice.tag))
        compose.onNodeWithTag(choice.tag).performClick()
        compose.waitUntil(WAIT) { main { tracks(checkNotNull(playback.player), type).any { it.format.label == label && it.selected } } }
        return choice
    }
    private fun ensureControls() {
        if (compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player-video").performTouchInput { click(Offset(width * .5f, height * .3f)) }
            compose.waitUntil(WAIT) { compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
    private fun awaitPlayback(playback: PlaybackViewModel) = compose.waitUntil(WAIT) {
        assertHealthy(playback)
        main { playback.player?.let { it.isPlaying && playback.state.value.hasRenderedFrame &&
            tracks(it, C.TRACK_TYPE_AUDIO).size == 2 && tracks(it, C.TRACK_TYPE_TEXT).size == 2 } == true }
    }
    private fun awaitSelected(playback: PlaybackViewModel, audio: String, subtitle: String, text: String) = compose.waitUntil(WAIT) {
        assertHealthy(playback)
        main { playback.player?.let { owner ->
            owner.audioFormat?.label == audio && tracks(owner, C.TRACK_TYPE_AUDIO).any { it.format.label == audio && it.selected } &&
                tracks(owner, C.TRACK_TYPE_TEXT).any { it.format.label == subtitle && it.selected } &&
                owner.currentCues.cues.any { it.text?.contains(text) == true }
        } == true }
    }
    private fun assertHealthy(playback: PlaybackViewModel) { playback.state.value.errorMessage?.let { throw AssertionError("Synthetic track playback failed: $it") } }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    private fun withFixture(blockSecond: Boolean = false, block: (Fixture, PlaybackViewModel) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val fixture = Fixture(blockSecond)
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        var failure: Throwable? = null
        try { block(fixture, playback) } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                try { fixture.releaseSecond.countDown(); main { playback.stop() }; runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } } }
                finally { fixture.close() }
                fixture.failures.firstOrNull()?.let { throw AssertionError("Synthetic track request failed", it) }
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private data class TrackRef(val tag: String, val format: Format, val selected: Boolean)
    private fun tracks(player: Player, type: Int): List<TrackRef> = buildList {
        player.currentTracks.groups.forEachIndexed { groupIndex, group -> if (group.type == type) repeat(group.length) { index ->
            if (group.isTrackSupported(index)) add(TrackRef("player-track-$groupIndex:$index", group.getTrackFormat(index), group.isTrackSelected(index)))
        } }
    }

    private class Fixture(blockSecond: Boolean) : AutoCloseable {
        private val files = mapOf("/one.mkv" to "episode-one.mkv", "/two.mkv" to "episode-two.mkv").mapValues { (_, name) ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("continuous-tracks/$name").use { it.readBytes() }
        }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val sources = ConcurrentHashMap<String, MediaSource>()
        private val closed = AtomicBoolean()
        private val playIds = AtomicInteger()
        val playbackInfoRequests = AtomicInteger()
        val secondRequested = CountDownLatch(1)
        val releaseSecond = CountDownLatch(if (blockSecond) 1 else 0)
        val failures = CopyOnWriteArrayList<Throwable>()
        private val origin = "http://127.0.0.1:${server.localPort}"
        private val session = Session(ServerConfig(origin, true), "synthetic-continuity-token", "synthetic-user", "Synthetic", "synthetic-device", "synthetic-server")

        init {
            Thread({ while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                workers.execute {
                    try { serve(socket) } catch (_: IOException) { }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    catch (error: Throwable) { failures += error }
                    finally { sockets -= socket; runCatching { socket.close() } }
                }
            } catch (error: IOException) { if (!closed.get()) failures += error }
            }, "Pathfin-continuous-tracks-fixture").apply { isDaemon = true }.start()
        }

        fun request(id: String): PlaybackRequest {
            val path = if (id == ONE) "/one.mkv" else "/two.mkv"
            val source = MediaSource("synthetic-source-$id", path = origin + path, protocol = "Http", container = "mkv", supportsDirectPlay = true)
            sources[id] = source
            val item = MediaItem(id, if (id == ONE) "Synthetic episode one" else "Synthetic episode two", "Episode",
                seriesId = "synthetic-continuity-series", runTimeTicks = 240_000_000L)
            return PlaybackRequest(item, PlaybackInfo(listOf(source), "synthetic-tracks-${playIds.incrementAndGet()}"), source,
                ResolvedPlayback(checkNotNull(source.path), emptyMap(), PlayMethod.DIRECT_PLAY, source.id, true, "video/x-matroska"), 0, session)
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = reader.readLine() ?: return
            val request = line.split(' ')
            check(request.size == 3 && request[2] == "HTTP/1.1")
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
            check(headers["authorization"].orEmpty().contains("synthetic-continuity-token"))
            val path = request[1].substringBefore('?')
            if (request[0] == "POST" && path.startsWith("/Sessions/Playing")) {
                respond(socket, 204, ByteArray(0), "application/json"); return
            }
            if (request[0] == "POST" && path.startsWith("/Items/") && path.endsWith("/PlaybackInfo")) {
                val source = checkNotNull(sources[path.removePrefix("/Items/").substringBefore('/')])
                playbackInfoRequests.incrementAndGet()
                // 模拟 Jellyfin 自己的 File 源与受认证的 DirectStreamUrl；Http Path 会被正确
                // 识别为 STRM 原始源，随后播放器不会给它附加 Jellyfin 账号凭据。
                val dto = JSONObject().put("Id", source.id).put("Path", "/synthetic/${source.id}.mkv")
                    .put("Protocol", "File").put("DirectStreamUrl", source.path).put("Container", "mkv")
                    .put("SupportsDirectPlay", true).put("SupportsDirectStream", true)
                val body = JSONObject().put("MediaSources", JSONArray().put(dto)).put("PlaySessionId", "synthetic-retry-${playIds.incrementAndGet()}")
                respond(socket, 200, body.toString().toByteArray(), "application/json"); return
            }
            check(request[0] == "GET")
            val bytes = checkNotNull(files[path])
            if (path == "/two.mkv") {
                secondRequested.countDown()
                check(releaseSecond.await(30, TimeUnit.SECONDS)) { "Test did not release the second synthetic episode" }
            }
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
            releaseSecond.countDown()
            runCatching { server.close() }
            sockets.toList().forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    private companion object {
        const val WAIT = 30_000L
        const val ONE = "11111111111111111111111111111111"
        const val TWO = "22222222222222222222222222222222"
    }
}

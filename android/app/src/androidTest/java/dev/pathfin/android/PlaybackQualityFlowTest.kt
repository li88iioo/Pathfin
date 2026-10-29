package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackQuality
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 自有回环 PlaybackInfo + 真实 Exo。协商后的素材反转轨道顺序，不依赖真实服务器或转码器。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackQualityFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun qualityNegotiationKeepsTheOldPlayerAndUsesTheLatestPausedPositionSpeedAndManualTracks() = verifyQualityContinuity(true)

    @Test fun qualitySwitchWithAssDisabledAlsoKeepsLongSubtitlesAtThePausedNonZeroPosition() = verifyQualityContinuity(false)

    private fun verifyQualityContinuity(assEnabled: Boolean) {
        withFixture(assEnabled) { fixture, playback ->
            preparePaused(playback, 9_000, 1.25f)
            choose(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            choose(playback, C.TRACK_TYPE_TEXT, "Spanish captions")
            awaitSelected(playback, "Japanese main", "Spanish captions")
            val old = main { checkNotNull(playback.player) }
            val gate = fixture.blockNextInfo()
            main { playback.changeQuality(PlaybackQuality.HIGH) }
            gate.awaitEntered()
            main {
                assertSame("协商期间不能释放原播放器", old, playback.player)
                assertEquals(PlaybackQuality.ORIGINAL, playback.state.value.quality)
                assertTrue(playback.state.value.isChangingQuality)
                playback.seekToPosition(11_000)
                playback.setPlaybackSpeed(1.75f)
                playback.setControlsLocked(true)
            }
            awaitPosition(playback, 11_000)
            gate.release()
            awaitReady(playback, PlaybackQuality.HIGH)
            awaitSelected(playback, "Japanese main", "Spanish captions")
            main {
                assertNotSame(old, playback.player)
                assertPausedAt(playback, 11_000, 1.75f)
                assertTrue(playback.state.value.controlsLocked)
                assertEquals(PlayMethod.TRANSCODE, playback.currentRequest?.stream?.method)
            }
            val request = fixture.playbackRequests.single()
            assertRequestedQuality(request, PlaybackQuality.HIGH)
            assertEquals("真实转码协商必须指定手选日语音轨", 2, request.getInt("AudioStreamIndex"))
            assertEquals("字幕选轨必须随服务器协商保留", 4, request.getInt("SubtitleStreamIndex"))
            assertTrue(abs(request.getLong("StartTimeTicks") - 90_000_000L) <= 2_000_000)
            assertTrue("必须实际打开协商返回的新媒体地址", fixture.transcodedMediaRequests.get() > 0)
            main { playback.changeQuality(PlaybackQuality.ORIGINAL) }
            awaitReady(playback, PlaybackQuality.ORIGINAL)
            awaitSelected(playback, "Japanese main", "Spanish captions")
            main {
                assertPausedAt(playback, 11_000, 1.75f)
                assertEquals(PlayMethod.DIRECT_STREAM, playback.currentRequest?.stream?.method)
                assertTrue(playback.state.value.controlsLocked)
            }
            assertEquals(2, fixture.playbackRequests.size)
            assertRequestedQuality(fixture.playbackRequests.last(), PlaybackQuality.ORIGINAL)
        }
    }

    @Test fun failedNegotiationKeepsTheOriginalInstanceAndTheSameQualityCanBeRetried() {
        withFixture { fixture, playback ->
            preparePaused(playback, 8_000, 1.5f)
            val old = main { checkNotNull(playback.player) }
            fixture.infoStatus.set(503)
            compose.onNodeWithTag("player-more").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-tools"))
            compose.onNodeWithTag("player-tools").performClick()
            VerificationDisplay.awaitWindowReady(compose, "quality-options",
                compose.onNodeWithTag("player-quality-standard"))
            if (InstrumentationRegistry.getArguments().getString("pathfin.playerPreviews") == "true") {
                ExternalScreenshotCheckpoint.capture("player-quality")
            }
            compose.onNodeWithTag("player-quality-standard").performClick()
            compose.waitUntil(WAIT) { !playback.state.value.isChangingQuality && playback.state.value.qualityError != null }
            main {
                assertSame(old, playback.player)
                assertEquals(PlaybackQuality.ORIGINAL, playback.currentRequest?.quality)
                assertNull(playback.state.value.errorMessage)
                assertPausedAt(playback, 8_000, 1.5f)
            }
            assertEquals(0, fixture.transcodedMediaRequests.get())
            assertRequestedQuality(fixture.playbackRequests.single(), PlaybackQuality.STANDARD)
            fixture.infoStatus.set(200)
            compose.onNodeWithTag("player-quality-standard").performClick()
            awaitReady(playback, PlaybackQuality.STANDARD)
            main { assertPausedAt(playback, 8_000, 1.5f); assertNull(playback.state.value.qualityError) }
            assertEquals("失败后必须允许重新协商同一个选项", 2, fixture.playbackRequests.size)
        }
    }

    @Test fun failedNewMediaRestoresTheOriginalSourceOnceWithPausedPositionSpeedAndTracksThenAllowsAnotherChange() {
        withFixture { fixture, playback ->
            preparePaused(playback, 10_000, 1.75f)
            choose(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            choose(playback, C.TRACK_TYPE_TEXT, "Spanish captions")
            awaitSelected(playback, "Japanese main", "Spanish captions")
            val originalRequests = fixture.originalMediaRequests.get()
            fixture.failTranscodedMedia.set(true)
            main { playback.changeQuality(PlaybackQuality.DATA_SAVER) }
            compose.waitUntil(WAIT) {
                main { fixture.failedMediaRequests.get() > 0 && playback.currentRequest?.quality == PlaybackQuality.ORIGINAL &&
                    playback.player?.playbackState == Player.STATE_READY && playback.state.value.errorMessage == null &&
                    !playback.state.value.isChangingQuality && playback.state.value.qualityError != null }
            }
            awaitSelected(playback, "Japanese main", "Spanish captions")
            main { assertPausedAt(playback, 10_000, 1.75f) }
            assertTrue("失败回滚必须实际重新准备原媒体", fixture.originalMediaRequests.get() > originalRequests)
            assertEquals("回滚不能重复请求失败质量", 1, fixture.playbackRequests.count {
                it.optInt("MaxStreamingBitrate") == PlaybackQuality.DATA_SAVER.maxBitrate
            })
            val requestsAfterRecovery = fixture.playbackRequests.size
            fixture.failTranscodedMedia.set(false)
            main { playback.changeQuality(PlaybackQuality.DATA_SAVER) }
            awaitReady(playback, PlaybackQuality.DATA_SAVER)
            awaitSelected(playback, "Japanese main", "Spanish captions")
            main { assertPausedAt(playback, 10_000, 1.75f); assertNull(playback.state.value.qualityError) }
            assertEquals(requestsAfterRecovery + 1, fixture.playbackRequests.size)
        }
    }

    @Test fun audioOnlySurvivesFailedQualityRollbackSuccessfulChangeAndExplicitRetryWithoutWaitingForVideo() {
        withFixture(assEnabled = false) { fixture, playback ->
            preparePaused(playback, 9_000, 1.5f)
            compose.onNodeWithTag("player-more").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-audio-only"))
            val audioOnly = compose.onNodeWithTag("player-audio-only")
            VerificationDisplay.awaitWindowReady(compose, "quality-audio-only", audioOnly)
            audioOnly.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            val original = main {
                val owner = checkNotNull(playback.player)
                assertTrue(C.TRACK_TYPE_VIDEO in owner.trackSelectionParameters.disabledTrackTypes)
                playback.setControlsLocked(true)
                owner
            }
            fixture.failTranscodedMedia.set(true)
            main { playback.changeQuality(PlaybackQuality.DATA_SAVER) }
            compose.waitUntil(WAIT) { fixture.failedMediaRequests.get() > 0 &&
                playback.currentRequest?.quality == PlaybackQuality.ORIGINAL && playback.player !== original }
            awaitAudioOnlyReady(playback, PlaybackQuality.ORIGINAL)
            main {
                assertTrue(original.isReleased)
                assertPausedAt(playback, 9_000, 1.5f)
                assertTrue(playback.state.value.controlsLocked)
                assertNotNull(playback.state.value.qualityError)
            }
            assertEquals("失败质量只能尝试一次", 1, fixture.playbackRequests.count {
                it.optInt("MaxStreamingBitrate") == PlaybackQuality.DATA_SAVER.maxBitrate
            })
            fixture.failTranscodedMedia.set(false)
            val rollback = main { checkNotNull(playback.player) }
            main { playback.changeQuality(PlaybackQuality.HIGH) }
            awaitAudioOnlyReady(playback, PlaybackQuality.HIGH)
            main {
                assertTrue(rollback.isReleased)
                assertPausedAt(playback, 9_000, 1.5f)
                assertTrue(playback.state.value.controlsLocked)
                assertNull(playback.state.value.qualityError)
            }
            val beforeRetry = main { checkNotNull(playback.player) }
            val requests = fixture.playbackRequests.size
            val mediaRequests = fixture.transcodedMediaRequests.get()
            main { playback.retry() }
            compose.waitUntil(WAIT) { playback.player !== beforeRetry }
            awaitAudioOnlyReady(playback, PlaybackQuality.HIGH)
            main {
                assertTrue(beforeRetry.isReleased)
                assertPausedAt(playback, 9_000, 1.5f)
                assertTrue(playback.state.value.controlsLocked)
            }
            assertEquals("显式重试只重新协商一次", requests + 1, fixture.playbackRequests.size)
            assertTrue("重建后必须实际请求新媒体", fixture.transcodedMediaRequests.get() > mediaRequests)
        }
    }

    @Test fun refreshingServerSubtitlesAddsTheNewTrackWithoutLosingManualAudioSubtitleOffOrPausedParameters() {
        withFixture { fixture, playback ->
            preparePaused(playback, 7_000, 1.25f)
            choose(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            main {
                val owner = checkNotNull(playback.player)
                playback.onManualTrackSelection(owner, C.TRACK_TYPE_TEXT, null, true)
                owner.trackSelectionParameters = owner.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            fixture.includeUploadedSubtitle.set(true)
            val updated = main { checkNotNull(playback.currentRequest).item.copy(name = "Synthetic updated subtitle list") }
            main { playback.refreshSubtitles(updated) }
            compose.waitUntil(WAIT) {
                assertHealthy(playback)
                main { playback.currentRequest?.item == updated && playback.player?.let { owner ->
                    owner.playbackState == Player.STATE_READY && owner.currentTracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.getTrackFormat(it).label == UPLOADED }
                    }
                } == true }
            }
            main {
                val owner = checkNotNull(playback.player)
                assertPausedAt(playback, 7_000, 1.25f)
                assertTrue(selected(owner, C.TRACK_TYPE_AUDIO, "Japanese main"))
                assertTrue(C.TRACK_TYPE_TEXT in owner.trackSelectionParameters.disabledTrackTypes)
                assertTrue(owner.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
                assertTrue(owner.currentCues.cues.isEmpty())
                assertTrue(checkNotNull(playback.currentRequest).source.mediaStreams.any { it.displayTitle == UPLOADED })
            }
            assertRequestedQuality(fixture.playbackRequests.single(), PlaybackQuality.ORIGINAL)
        }
    }

    @Test fun subtitleRefreshDuringBlockedNegotiationIsCoalescedToTheLatestItemAndRunsAfterTheNewSourceIsReady() {
        withFixture { fixture, playback ->
            preparePaused(playback, 6_000, 1.5f)
            choose(playback, C.TRACK_TYPE_AUDIO, "Japanese main")
            val gate = fixture.blockNextInfo()
            main { playback.changeQuality(PlaybackQuality.HIGH) }
            gate.awaitEntered()
            fixture.includeUploadedSubtitle.set(true)
            val latest = main {
                val original = checkNotNull(playback.currentRequest).item
                playback.refreshSubtitles(original.copy(name = "Superseded subtitle update"))
                original.copy(name = "Latest subtitle update").also(playback::refreshSubtitles)
            }
            gate.release()
            compose.waitUntil(WAIT) {
                assertHealthy(playback)
                main { playback.currentRequest?.item == latest && playback.currentRequest?.quality == PlaybackQuality.HIGH &&
                    !playback.state.value.isChangingQuality && playback.player?.let { owner ->
                        owner.playbackState == Player.STATE_READY && owner.currentTracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.getTrackFormat(it).label == UPLOADED }
                        }
                    } == true }
            }
            main {
                assertPausedAt(playback, 6_000, 1.5f)
                assertTrue(selected(checkNotNull(playback.player), C.TRACK_TYPE_AUDIO, "Japanese main"))
            }
            assertEquals("同一协商期间的多个字幕回调应合并一次补刷新", 2, fixture.playbackRequests.size)
            fixture.playbackRequests.forEach { assertRequestedQuality(it, PlaybackQuality.HIGH) }
        }
    }

    @Test fun systemBackDuringQualityNegotiationCancelsTheRequestAndItsLateResponseCannotRecreatePlayback() {
        withFixture { fixture, playback ->
            val gate = fixture.blockNextInfo()
            main { playback.changeQuality(PlaybackQuality.HIGH) }
            gate.awaitEntered()
            val pending = main {
                PlaybackViewModel::class.java.getDeclaredField("qualityJob").apply { isAccessible = true }.get(playback) as Job
            }
            main {
                compose.activity.onBackPressedDispatcher.onBackPressed()
                assertNull(playback.player)
                assertNull(playback.currentRequest)
                assertFalse(compose.activity.isFinishing)
            }
            gate.release()
            gate.awaitResponseAttempt()
            runBlocking { withTimeout(10_000) { pending.join() } }
            compose.waitForIdle()
            main {
                assertTrue(pending.isCancelled)
                assertNull(playback.player)
                assertNull(playback.currentRequest)
                assertFalse(playback.state.value.isChangingQuality)
                assertFalse(compose.activity.isFinishing)
            }
            assertEquals("退出后的协商响应不能再打开新媒体", 0, fixture.transcodedMediaRequests.get())
        }
    }

    private fun preparePaused(playback: PlaybackViewModel, position: Long, speed: Float) {
        main { checkNotNull(playback.player).pause(); playback.seekToPosition(position); playback.setPlaybackSpeed(speed) }
        awaitPosition(playback, position)
    }

    private fun choose(playback: PlaybackViewModel, type: Int, label: String) = main {
        val owner = checkNotNull(playback.player)
        val candidates = owner.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) && group.getTrackFormat(it).label == label }
                .map { group to it }
        }
        val (group, index) = candidates.single()
        playback.onManualTrackSelection(owner, type, group.getTrackFormat(index), false)
        owner.trackSelectionParameters = owner.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type).setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }

    private fun awaitPosition(playback: PlaybackViewModel, position: Long) = compose.waitUntil(WAIT) {
        assertHealthy(playback)
        main { playback.player?.let { it.playbackState == Player.STATE_READY && abs(it.currentPosition - position) <= 250 } == true }
    }

    private fun awaitReady(playback: PlaybackViewModel, quality: PlaybackQuality) = compose.waitUntil(WAIT) {
        assertHealthy(playback)
        main { playback.currentRequest?.quality == quality && !playback.state.value.isChangingQuality &&
            playback.player?.playbackState == Player.STATE_READY && playback.state.value.hasRenderedFrame }
    }

    private fun awaitAudioOnlyReady(playback: PlaybackViewModel, quality: PlaybackQuality) = compose.waitUntil(WAIT) {
        assertHealthy(playback)
        main {
            val owner = playback.player
            if (playback.currentRequest?.quality != quality || playback.state.value.isChangingQuality ||
                owner?.playbackState != Player.STATE_READY) false else {
                assertTrue("重建后必须继续禁用视频", C.TRACK_TYPE_VIDEO in owner.trackSelectionParameters.disabledTrackTypes)
                assertFalse("仅音频不应恢复视频首帧", playback.state.value.hasRenderedFrame)
                assertTrue(owner.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
                assertTrue(owner.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                true
            }
        }
    }

    private fun awaitSelected(playback: PlaybackViewModel, audio: String, subtitle: String) {
        try {
            compose.waitUntil(WAIT) {
                assertHealthy(playback)
                main { playback.player?.let { owner -> selected(owner, C.TRACK_TYPE_AUDIO, audio) &&
                    selected(owner, C.TRACK_TYPE_TEXT, subtitle) && owner.currentCues.cues.any { it.text?.contains("PATHFIN SPANISH") == true }
                } == true }
            }
        } catch (failure: Throwable) {
            val diagnostic = main {
                val owner = playback.player
                val active = PlaybackViewModel::class.java.getDeclaredField("active").apply { isAccessible = true }.get(playback)
                fun remembered(field: String): Any? = active?.let {
                    it.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(it)
                }
                "quality=${playback.state.value.quality},generation=${playback.state.value.playerGeneration}," +
                    "state=${owner?.playbackState},position=${owner?.currentPosition},paused=${owner?.playWhenReady == false}," +
                    "speed=${owner?.playbackParameters?.speed},manual=${remembered("manualTracks")}," +
                    "pending=${remembered("pendingManualTrackTypes")}," +
                    "tracks=${owner?.currentTracks?.groups?.map { group -> (0 until group.length).map { index ->
                        val format = group.getTrackFormat(index)
                        "type=${group.type},id=${format.id},label=${format.label},language=${format.language}," +
                            "role=${format.roleFlags},flags=${format.selectionFlags},supported=${group.isTrackSupported(index)},selected=${group.isTrackSelected(index)}"
                    } }},disabled=${owner?.trackSelectionParameters?.disabledTrackTypes}," +
                    "cues=${owner?.currentCues?.cues?.map { it.text }}," +
                    "error=${playback.state.value.errorMessage},qualityError=${playback.state.value.qualityError}"
            }
            throw AssertionError("质量切换后实际手选轨道/字幕不完整：$diagnostic", failure)
        }
    }

    private fun selected(owner: Player, type: Int, label: String): Boolean = owner.currentTracks.groups.any { group ->
        group.type == type && (0 until group.length).any { group.isTrackSelected(it) && group.getTrackFormat(it).label == label }
    }

    private fun assertPausedAt(playback: PlaybackViewModel, position: Long, speed: Float) {
        val owner = checkNotNull(playback.player)
        assertFalse(owner.playWhenReady)
        assertFalse(owner.isPlaying)
        assertTrue("position=${owner.currentPosition}, expected=$position", abs(owner.currentPosition - position) <= 250)
        assertEquals(speed, owner.playbackParameters.speed, .001f)
    }

    private fun assertRequestedQuality(body: JSONObject, quality: PlaybackQuality) {
        assertEquals(SOURCE, body.getString("MediaSourceId"))
        assertEquals("quality-user", body.getString("UserId"))
        assertEquals(quality.maxBitrate == null, body.getBoolean("EnableDirectPlay"))
        assertEquals(quality.maxBitrate == null, body.getBoolean("EnableDirectStream"))
        assertEquals(quality.maxBitrate == null, body.getBoolean("AllowVideoStreamCopy"))
        assertTrue(body.getBoolean("EnableTranscoding"))
        if (quality.maxBitrate != null) {
            assertEquals(quality.maxBitrate, body.getInt("MaxStreamingBitrate"))
            assertEquals(quality.maxBitrate, body.getJSONObject("DeviceProfile").getInt("MaxStreamingBitrate"))
        } else {
            assertFalse(body.has("MaxStreamingBitrate"))
        }
    }

    private fun assertHealthy(playback: PlaybackViewModel) {
        playback.state.value.errorMessage?.let { throw AssertionError("自有质量切换实播失败：$it") }
    }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    private fun withFixture(assEnabled: Boolean = true, block: (Fixture, PlaybackViewModel) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val fixture = Fixture()
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        val preferences = AppPreferences(originalPathEnabled = false,
            exoAssEnabled = assEnabled, preferredAudioLanguage = "en", preferredSubtitleLanguage = "en",
            subtitlesEnabled = true, serverIntroSkipEnabled = false, introPromptSeconds = 0, showClock = false, showBattery = false)
        var failure: Throwable? = null
        try {
            compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop, preferences = preferences) } }
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback, PlaybackQuality.ORIGINAL)
            block(fixture, playback)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                try {
                    fixture.releaseGates()
                    main { playback.stop() }
                    runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } }
                } finally { fixture.close() }
                fixture.failures.firstOrNull()?.let { throw AssertionError("自有质量切换 HTTP 夹具收到非法请求", it) }
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val responseAttempted = CountDownLatch(1)
        fun awaitEntered() { assertTrue("没有收到质量协商请求", entered.await(10, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
        fun awaitResponseAttempt() { assertTrue("迟到协商响应没有结束", responseAttempted.await(10, TimeUnit.SECONDS)) }
        fun awaitRelease() { entered.countDown(); check(released.await(30, TimeUnit.SECONDS)) { "未放行合成协商响应" } }
        fun responded() { responseAttempted.countDown() }
    }

    private class Fixture : AutoCloseable {
        private val files = listOf("episode-one.mkv", "episode-two.mkv").map { name ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("continuous-tracks/$name").use { it.readBytes() }
        }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val gates = CopyOnWriteArrayList<Gate>()
        private val nextInfoGate = AtomicReference<Gate?>()
        private val closed = AtomicBoolean()
        private val origin = "http://127.0.0.1:${server.localPort}"
        private val session = Session(ServerConfig(origin, true), "quality-token", "quality-user", "Synthetic", "quality-device", "quality-server")
        private val mediaStreams = listOf(
            MediaStream(index = 1, type = "Audio", codec = "aac", language = "eng", title = "English main", channels = 1, sampleRate = 48_000),
            MediaStream(index = 2, type = "Audio", codec = "aac", language = "jpn", title = "Japanese main", channels = 1, sampleRate = 48_000),
            MediaStream(index = 3, type = "Subtitle", codec = "subrip", language = "eng", title = "English captions"),
            MediaStream(index = 4, type = "Subtitle", codec = "subrip", language = "spa", title = "Spanish captions"),
        )
        val infoStatus = AtomicInteger(200)
        val failTranscodedMedia = AtomicBoolean()
        val includeUploadedSubtitle = AtomicBoolean()
        val playbackRequests = CopyOnWriteArrayList<JSONObject>()
        val originalMediaRequests = AtomicInteger()
        val transcodedMediaRequests = AtomicInteger()
        val failedMediaRequests = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()

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
            }, "Pathfin-quality-fixture").apply { isDaemon = true }.start()
        }

        fun request(): PlaybackRequest {
            val source = MediaSource(SOURCE, path = "/synthetic/quality.mkv", protocol = "File", container = "mkv",
                directStreamUrl = "$origin/original.mkv", supportsDirectPlay = true, supportsDirectStream = true, mediaStreams = mediaStreams)
            return PlaybackRequest(MediaItem(ITEM, "Synthetic quality test", "Movie", runTimeTicks = 240_000_000L),
                PlaybackInfo(listOf(source), "quality-original"), source,
                ResolvedPlayback("$origin/original.mkv", emptyMap(), PlayMethod.DIRECT_STREAM, SOURCE, true, "video/x-matroska"), 0, session)
        }

        fun blockNextInfo(): Gate = Gate().also { check(nextInfoGate.compareAndSet(null, it)); gates += it }
        fun releaseGates() { gates.forEach(Gate::release) }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = (reader.readLine() ?: return).split(' ')
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
            val body = CharArray(length)
            var read = 0
            while (read < length) { val count = reader.read(body, read, length - read); if (count < 0) return; read += count }
            check(headers["authorization"].orEmpty().contains("quality-token"))
            val path = request[1].substringBefore('?')
            if (request[0] == "POST" && path.startsWith("/Sessions/Playing")) {
                respond(socket, 204, ByteArray(0), "application/json"); return
            }
            if (request[0] == "DELETE" && path == "/Videos/ActiveEncodings") {
                val query = java.net.URI(request[1]).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                check(query["deviceId"] == "quality-device")
                check(query["playSessionId"]?.removePrefix("quality-negotiated-")?.toIntOrNull()?.let { it in 1..playbackRequests.size } == true)
                respond(socket, 204, ByteArray(0), "application/json"); return
            }
            if (request[0] == "POST" && path == "/Items/$ITEM/PlaybackInfo") {
                val json = JSONObject(String(body))
                playbackRequests += json
                val gate = nextInfoGate.getAndSet(null)
                // 协商响应反映请求到达时的服务端字幕列表，不能因稍后上传而凭空变新。
                val uploadedSubtitle = includeUploadedSubtitle.get()
                try {
                    gate?.awaitRelease()
                    val status = infoStatus.get()
                    if (status != 200) { respond(socket, status, "{}".toByteArray(), "application/json"); return }
                    val bitrate = json.optInt("MaxStreamingBitrate", 0)
                    val dto = JSONObject().put("Id", SOURCE).put("Path", "/synthetic/quality.mkv").put("Protocol", "File")
                        .put("Container", "mkv").put("SupportsDirectPlay", true).put("SupportsDirectStream", true)
                        .put("DirectStreamUrl", "/original.mkv").put("TranscodingUrl", "/quality-$bitrate.mkv")
                    val tracks = JSONArray(mediaStreams.map { stream -> JSONObject().put("Index", stream.index)
                        .put("Type", stream.type).put("Codec", stream.codec).put("Language", stream.language)
                        .put("Title", stream.title).put("Channels", stream.channels).put("SampleRate", stream.sampleRate) })
                    dto.put("MediaStreams", tracks).put("DefaultAudioStreamIndex", json.optInt("AudioStreamIndex", 1))
                        .put("DefaultSubtitleStreamIndex", json.optInt("SubtitleStreamIndex", 3))
                    if (uploadedSubtitle) tracks.put(JSONObject()
                        .put("Index", 10).put("Type", "Subtitle").put("Codec", "subrip").put("Language", "fr")
                        .put("DisplayTitle", UPLOADED).put("IsExternal", true).put("DeliveryMethod", "External")
                        .put("DeliveryUrl", "/uploaded.srt"))
                    val result = JSONObject().put("MediaSources", JSONArray().put(dto))
                        .put("PlaySessionId", "quality-negotiated-${playbackRequests.size}")
                    respond(socket, 200, result.toString().toByteArray(), "application/json")
                } finally { gate?.responded() }
                return
            }
            check(request[0] == "GET")
            if (path == "/uploaded.srt") {
                respond(socket, 200, "1\n00:00:00,000 --> 00:00:24,000\nPATHFIN UPLOADED SUBTITLE\n".toByteArray(), "application/x-subrip"); return
            }
            val bytes = when {
                path == "/original.mkv" -> { originalMediaRequests.incrementAndGet(); files[0] }
                Regex("/quality-[0-9]+\\.mkv").matches(path) -> {
                    transcodedMediaRequests.incrementAndGet()
                    if (failTranscodedMedia.get()) {
                        failedMediaRequests.incrementAndGet()
                        respond(socket, 404, ByteArray(0), "video/x-matroska"); return
                    }
                    files[1]
                }
                else -> throw AssertionError("Unexpected owned fixture path $path")
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
            releaseGates()
            runCatching { server.close() }
            sockets.toList().forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    private companion object {
        const val WAIT = 30_000L
        const val ITEM = "33333333333333333333333333333333"
        const val SOURCE = "quality-source"
        const val UPLOADED = "Uploaded French captions"
    }
}

package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackDelivery
import dev.pathfin.android.domain.PlaybackHeaders
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.PlayerScreen
import dev.pathfin.ass.ExoAssExtension
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 自有回环协商及真实 Media3；断言实际媒体请求、首帧和上报，不替换 Player 或触发其私有监听。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackRecoveryFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun malformedOriginalRecoversThroughServerStreamAndKeepsTheLatestPausedControlsAndSubtitleOff() =
        verifyServerRecovery(audioOnly = false)

    @Test fun malformedOriginalRecoversWithoutRestoringVideoDisabledDuringNegotiation() =
        verifyServerRecovery(audioOnly = true)

    private fun verifyServerRecovery(audioOnly: Boolean) {
        withFixture(Scenario.SERVER_RECOVERS) { fixture, playback, preferences ->
            val gate = fixture.blockNextInfo()
            main {
                playback.play(fixture.request(positionMs = 6_000), preferences)
                checkNotNull(playback.player).pause()
            }
            gate.awaitEntered()
            val previous = main {
                val owner = checkNotNull(playback.player)
                assertTrue(playback.state.value.isPreparing)
                assertNull(playback.state.value.errorMessage)
                // 畸形源没有可seek的时间线；验证保留已有6秒续播点，不假设错误态还能拖动进度。
                playback.setPlaybackSpeed(1.75f)
                playback.setControlsLocked(true)
                playback.onManualTrackSelection(owner, C.TRACK_TYPE_TEXT, null, true)
                owner.trackSelectionParameters = owner.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
                owner
            }
            assertFalse("协商放行前不能提前打开新媒体", fixture.mediaRoutes.contains(SERVER_ROUTE))
            val latestSelection = fixture.blockNextInfo()
            gate.release()
            latestSelection.awaitEntered()
            assertEquals("阻塞期间关闭字幕需要恰好重新协商一次相同模式", listOf(PlaybackDelivery.SERVER_STREAM, PlaybackDelivery.SERVER_STREAM),
                fixture.infoRequests.map { it.delivery })
            val superseded = fixture.infoRequests.first()
            val latest = fixture.infoRequests.last()
            assertFalse("首轮请求发出时尚未手动关闭字幕", superseded.body.has("SubtitleStreamIndex"))
            assertEquals("补协商必须把字幕关闭传给服务器", -1, latest.body.getInt("SubtitleStreamIndex"))
            assertFalse("首轮旧选轨响应不能安装为媒体源", fixture.mediaRoutes.contains(SERVER_ROUTE))
            main {
                assertSame("最新选轨协商返回前必须保留原播放器", previous, playback.player)
                assertNotEquals(superseded.playSessionId, playback.currentRequest?.info?.playSessionId)
            }
            latestSelection.release()
            awaitReady(playback, PlaybackDelivery.SERVER_STREAM, audioOnly)
            main {
                val owner = checkNotNull(playback.player)
                assertNotSame(previous, owner)
                assertEquals("只允许安装最新选轨的协商响应", latest.playSessionId, playback.currentRequest?.info?.playSessionId)
                assertFalse(owner.playWhenReady)
                assertFalse(owner.isPlaying)
                assertTrue("position=${owner.currentPosition}", abs(owner.currentPosition - 6_000) <= 250)
                assertEquals(1.75f, owner.playbackParameters.speed, .001f)
                assertTrue(playback.state.value.controlsLocked)
                assertTrue(C.TRACK_TYPE_TEXT in owner.trackSelectionParameters.disabledTrackTypes)
                assertEquals(audioOnly, C.TRACK_TYPE_VIDEO in owner.trackSelectionParameters.disabledTrackTypes)
                if (audioOnly) assertTrue(owner.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                assertTrue(owner.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
                assertTrue(owner.currentCues.cues.isEmpty())
                assertEquals(PlayMethod.DIRECT_STREAM, playback.currentRequest?.stream?.method)
                assertTrue(checkNotNull(playback.currentRequest).stream.trustedServer)
                owner.play()
            }
            awaitPlaying(playback, audioOnly)
            stopAndAwaitReports(playback)
            assertEquals(listOf(PlaybackDelivery.SERVER_STREAM, PlaybackDelivery.SERVER_STREAM), fixture.infoRequests.map { it.delivery })
            fixture.infoRequests.forEach { assertFlags(it, PlaybackDelivery.SERVER_STREAM) }
            assertTrue(fixture.mediaRoutes.contains(ORIGINAL_ROUTE))
            assertTrue(fixture.mediaRoutes.contains(SERVER_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(TRANSCODE_ROUTE))
            assertStartStop(fixture, latest.playSessionId, "DirectStream")
            assertBefore(fixture, "media:$ORIGINAL_ROUTE", "info:SERVER_STREAM")
            assertBefore(fixture, "info:SERVER_STREAM", "media:$SERVER_ROUTE")
            assertTrue("普通直流不能停止转码任务", fixture.transcodeStops.isEmpty())
        }
    }

    @Test fun malformedServerStreamEscalatesOnceToActualTranscodeAndStopsOnlyItsOwnEncodingSession() {
        withFixture(Scenario.TRANSCODE_RECOVERS) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback, PlaybackDelivery.TRANSCODE)
            awaitPlaying(playback)
            main { assertEquals(PlayMethod.TRANSCODE, playback.currentRequest?.stream?.method) }
            stopAndAwaitReports(playback)
            assertEquals(listOf(PlaybackDelivery.SERVER_STREAM, PlaybackDelivery.TRANSCODE), fixture.infoRequests.map { it.delivery })
            fixture.infoRequests.forEach { assertFlags(it, it.delivery) }
            assertTrue(fixture.mediaRoutes.containsAll(listOf(ORIGINAL_ROUTE, SERVER_ROUTE, TRANSCODE_ROUTE)))
            assertBefore(fixture, "media:$SERVER_ROUTE", "info:TRANSCODE")
            assertBefore(fixture, "info:TRANSCODE", "media:$TRANSCODE_ROUTE")
            val sessionId = fixture.infoRequests.last().playSessionId
            assertStartStop(fixture, sessionId, "Transcode")
            assertEquals("结束转码只能清理当前设备当前播放会话", listOf(EncodingStop(DEVICE, sessionId)), fixture.transcodeStops.toList())
        }
    }

    @Test fun failedRemuxLabeledTranscodeStillEscalatesToForcedTranscodeAndReleasesBothDistinctEncodingSessionsOnce() {
        withFixture(Scenario.REMUX_THEN_TRANSCODE) { fixture, playback, preferences ->
            val firstInfo = fixture.blockNextInfo()
            main { playback.play(fixture.request(), preferences) }
            firstInfo.awaitEntered()
            val forcedInfo = fixture.blockNextInfo()
            firstInfo.release()
            forcedInfo.awaitEntered()
            main {
                assertEquals("重封装不能被误认为已强制音视频转码", PlaybackDelivery.SERVER_STREAM, playback.currentRequest?.delivery)
                assertEquals(PlayMethod.TRANSCODE, playback.currentRequest?.stream?.method)
                assertTrue(playback.state.value.isPreparing)
                assertNull(playback.state.value.errorMessage)
            }
            assertTrue("必须真实请求并拒绝不兼容的重封装媒体", fixture.mediaRoutes.contains(SERVER_ROUTE))
            forcedInfo.release()
            awaitReady(playback, PlaybackDelivery.TRANSCODE)
            awaitPlaying(playback)
            main {
                val owner = checkNotNull(playback.player)
                assertEquals(PlayMethod.TRANSCODE, playback.currentRequest?.stream?.method)
                assertTrue("强制转码后必须选中可解码音轨", owner.currentTracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any {
                        group.isTrackSupported(it) && group.isTrackSelected(it)
                    }
                })
            }
            runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } }
            assertEquals("重封装失败后应只追加一次强制转码协商", listOf(PlaybackDelivery.SERVER_STREAM, PlaybackDelivery.TRANSCODE),
                fixture.infoRequests.map { it.delivery })
            fixture.infoRequests.forEach { assertFlags(it, it.delivery) }
            val remuxSession = fixture.infoRequests.first().playSessionId
            val transcodeSession = fixture.infoRequests.last().playSessionId
            assertNotEquals(remuxSession, transcodeSession)
            assertEquals("未真正起播的旧重封装会话也必须回收", listOf(EncodingStop(DEVICE, remuxSession)), fixture.transcodeStops.toList())
            assertTrue("失败重封装不能虚报播放事件", fixture.reports.none { it.body.getString("PlaySessionId") == remuxSession })
            assertBefore(fixture, "media:$SERVER_ROUTE", "info:TRANSCODE")
            assertBefore(fixture, "info:TRANSCODE", "media:$TRANSCODE_ROUTE")
            stopAndAwaitReports(playback)
            assertStartStop(fixture, transcodeSession, "Transcode")
            assertEquals("旧重封装和最终转码各自只能清理一次", listOf(EncodingStop(DEVICE, remuxSession), EncodingStop(DEVICE, transcodeSession)),
                fixture.transcodeStops.toList())
            assertEquals("清理会话不能重新进入恢复循环", 2, fixture.infoRequests.size)
        }
    }

    @Test fun assAttachmentFallbackReusesTheLiveTranscodeSessionWithoutStoppingItsReportOrEncoderUntilFinalExit() =
        verifyAssFallback(audioOnly = false)

    @Test fun audioOnlySurvivesAssFallbackWithoutRepeatingThePlaybackOrTranscodeSession() =
        verifyAssFallback(audioOnly = true)

    private fun verifyAssFallback(audioOnly: Boolean) {
        assumeTrue("转码中的 ASS 回退回归要求先实际启用原生扩展", ExoAssExtension.supportedOnDevice())
        withFixture(Scenario.TRANSCODE_RECOVERS, assEnabled = true) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback, PlaybackDelivery.TRANSCODE)
            awaitPlaying(playback)
            runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } }
            val sessionId = fixture.infoRequests.last().playSessionId
            val originalMediaRequests = fixture.mediaRoutes.count { it == TRANSCODE_ROUTE }
            assertEquals(listOf(sessionId), fixture.reports.filter { it.event == "start" }.map { it.body.getString("PlaySessionId") })
            assertTrue(fixture.reports.none { it.event == "stop" })
            assertTrue(fixture.transcodeStops.isEmpty())
            val previous = main {
                val owner = checkNotNull(playback.player)
                if (audioOnly) {
                    owner.trackSelectionParameters = owner.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    playback.setPlaybackSpeed(1.25f)
                    playback.setControlsLocked(true)
                }
                playback.onAssAttachmentFailure(checkNotNull(playback.assExtensionFor(owner)))
                owner
            }
            compose.waitUntil(WAIT) {
                playback.state.value.errorMessage?.let { throw AssertionError("转码中的 ASS 回退实播失败：$it") }
                main { playback.player?.let { owner ->
                    owner !== previous && owner.playbackState == Player.STATE_READY &&
                        playback.currentRequest?.info?.playSessionId == sessionId
                } == true }
            }
            awaitReady(playback, PlaybackDelivery.TRANSCODE, audioOnly)
            awaitPlaying(playback, audioOnly)
            runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } }
            main {
                assertNull(playback.assExtensionFor(playback.player))
                assertEquals(PlaybackDelivery.TRANSCODE, playback.currentRequest?.delivery)
                assertEquals(PlayMethod.TRANSCODE, playback.currentRequest?.stream?.method)
                assertTrue(previous.isReleased)
                val owner = checkNotNull(playback.player)
                assertEquals(audioOnly, C.TRACK_TYPE_VIDEO in owner.trackSelectionParameters.disabledTrackTypes)
                if (audioOnly) {
                    assertTrue(owner.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                    assertEquals(1.25f, owner.playbackParameters.speed, .001f)
                    assertTrue(playback.state.value.controlsLocked)
                }
            }
            assertTrue("ASS 回退必须实际重新准备相同的转码媒体", fixture.mediaRoutes.count { it == TRANSCODE_ROUTE } > originalMediaRequests)
            assertEquals("本地字幕渲染器回退不应重新协商播放会话", 2, fixture.infoRequests.size)
            assertEquals("同一转码会话重建播放器不能重复 start", 1, fixture.reports.count { it.event == "start" })
            assertTrue("重建播放器期间不能把仍在播放的会话上报为停止", fixture.reports.none { it.event == "stop" })
            assertTrue("重建播放器期间不能终止仍在复用的服务器编码器", fixture.transcodeStops.isEmpty())
            stopAndAwaitReports(playback)
            assertStartStop(fixture, sessionId, "Transcode")
            assertEquals("最终退出只能清理一次当前转码会话", listOf(EncodingStop(DEVICE, sessionId)), fixture.transcodeStops.toList())
        }
    }

    @Test fun threeMalformedContainersEndWithAnErrorAfterTwoEscalationsRatherThanLoopingOrReportingFalseStarts() {
        withFixture(Scenario.NOTHING_DECODES) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitTerminalError(playback)
            main {
                assertEquals(PlaybackDelivery.TRANSCODE, playback.currentRequest?.delivery)
                assertFalse(playback.state.value.isPreparing)
                assertFalse(playback.state.value.isPlaying)
                assertFalse(playback.state.value.hasRenderedFrame)
            }
            stopAndAwaitReports(playback)
            assertEquals("容器失败最多只升级两次", listOf(PlaybackDelivery.SERVER_STREAM, PlaybackDelivery.TRANSCODE),
                fixture.infoRequests.map { it.delivery })
            fixture.infoRequests.forEach { assertFlags(it, it.delivery) }
            assertTrue(fixture.mediaRoutes.containsAll(listOf(ORIGINAL_ROUTE, SERVER_ROUTE, TRANSCODE_ROUTE)))
            assertTrue("没有真正播放的源不能虚报 start/stop", fixture.reports.isEmpty())
            assertEquals(listOf(EncodingStop(DEVICE, fixture.infoRequests.last().playSessionId)), fixture.transcodeStops.toList())
        }
    }

    @Test fun systemBackCancelsBlockedRecoveryHttpAndItsLateResponseCannotRecreatePlayerOrStartMedia() {
        withFixture(Scenario.SERVER_RECOVERS) { fixture, playback, preferences ->
            val gate = fixture.blockNextInfo()
            main { playback.play(fixture.request(), preferences) }
            gate.awaitEntered()
            main {
                compose.activity.onBackPressedDispatcher.onBackPressed()
                assertNull(playback.player)
                assertNull(playback.currentRequest)
                assertFalse(compose.activity.isFinishing)
            }
            gate.awaitClientDisconnected()
            gate.release()
            gate.awaitResponseAttempt()
            stopAndAwaitReports(playback)
            compose.waitForIdle()
            main {
                assertNull(playback.player)
                assertNull(playback.currentRequest)
                assertFalse(playback.state.value.isPreparing)
                assertFalse(compose.activity.isFinishing)
            }
            assertEquals(1, fixture.infoRequests.size)
            assertFalse("退出后不能打开迟到的协商媒体", fixture.mediaRoutes.contains(SERVER_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(TRANSCODE_ROUTE))
            assertTrue(fixture.reports.isEmpty())
        }
    }

    @Test fun switchingItemsCancelsTheOldRecoveryAndAReleasedLateResponseCannotReplaceTheNewActualVideo() {
        withFixture(Scenario.SERVER_RECOVERS) { fixture, playback, preferences ->
            val gate = fixture.blockNextInfo()
            main { playback.play(fixture.request(), preferences) }
            gate.awaitEntered()
            val next = fixture.nextItemRequest()
            main { playback.play(next, preferences) }
            gate.awaitClientDisconnected()
            awaitReady(playback, PlaybackDelivery.AUTOMATIC)
            gate.release()
            gate.awaitResponseAttempt()
            awaitPlaying(playback)
            main { assertEquals(next.item.id, playback.currentRequest?.item?.id) }
            stopAndAwaitReports(playback)
            assertFalse("旧媒体的协商结果不能抢占新一集", fixture.mediaRoutes.contains(SERVER_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(TRANSCODE_ROUTE))
            assertStartStop(fixture, NEXT_SESSION, "DirectPlay", NEXT_ITEM)
        }
    }

    @Test fun unsupportedAudioTracksWithPlayableVideoAlsoRecoverInsteadOfContinuingAsSilentSuccess() {
        withFixture(Scenario.UNSUPPORTED_AUDIO) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback, PlaybackDelivery.SERVER_STREAM)
            awaitPlaying(playback)
            main {
                val owner = checkNotNull(playback.player)
                assertTrue("恢复后必须实际选中可解码音轨", owner.currentTracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any {
                        group.isTrackSupported(it) && group.isTrackSelected(it)
                    }
                })
                assertNull(playback.state.value.decodeWarning)
            }
            stopAndAwaitReports(playback)
            assertEquals(listOf(PlaybackDelivery.SERVER_STREAM), fixture.infoRequests.map { it.delivery })
            assertFlags(fixture.infoRequests.single(), PlaybackDelivery.SERVER_STREAM)
            assertTrue(fixture.mediaRoutes.contains(ORIGINAL_ROUTE))
            assertTrue(fixture.mediaRoutes.contains(SERVER_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(TRANSCODE_ROUTE))
            assertStartStop(fixture, fixture.infoRequests.single().playSessionId, "DirectStream")
        }
    }

    @Test fun unauthorizedMediaDoesNotAttemptNegotiationOrEscalateToTranscoding() {
        withFixture(Scenario.UNAUTHORIZED) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitTerminalError(playback)
            stopAndAwaitReports(playback)
            assertTrue(fixture.mediaRoutes.contains(ORIGINAL_ROUTE))
            assertTrue("401 不能通过新协商或昂贵转码掩盖鉴权错误", fixture.infoRequests.isEmpty())
            assertFalse(fixture.mediaRoutes.contains(SERVER_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(TRANSCODE_ROUTE))
            assertTrue(fixture.reports.isEmpty())
            assertTrue(fixture.transcodeStops.isEmpty())
        }
    }

    @Test fun expiredExternalAddressRefreshesTheSameRouteOnceAndActuallyPlaysTheFreshOriginalWithoutTranscoding() {
        withFixture(Scenario.EXPIRED_ORIGINAL) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback, PlaybackDelivery.AUTOMATIC)
            awaitPlaying(playback)
            main {
                assertEquals(PlayMethod.DIRECT_PLAY, playback.currentRequest?.stream?.method)
                assertFalse(checkNotNull(playback.currentRequest).stream.trustedServer)
            }
            stopAndAwaitReports(playback)
            assertEquals(listOf(PlaybackDelivery.AUTOMATIC), fixture.infoRequests.map { it.delivery })
            assertFlags(fixture.infoRequests.single(), PlaybackDelivery.AUTOMATIC)
            assertTrue(fixture.mediaRoutes.contains(ORIGINAL_ROUTE))
            assertTrue(fixture.mediaRoutes.contains(REFRESHED_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(SERVER_ROUTE))
            assertFalse(fixture.mediaRoutes.contains(TRANSCODE_ROUTE))
            assertStartStop(fixture, fixture.infoRequests.single().playSessionId, "DirectPlay")
        }
    }

    private fun awaitReady(playback: PlaybackViewModel, delivery: PlaybackDelivery, audioOnly: Boolean = false) = compose.waitUntil(WAIT) {
        playback.state.value.errorMessage?.let { throw AssertionError("自有恢复媒体没有成功播放：$it") }
        main { playback.currentRequest?.delivery == delivery && playback.player?.playbackState == Player.STATE_READY &&
            (if (audioOnly) !playback.state.value.hasRenderedFrame &&
                C.TRACK_TYPE_VIDEO in checkNotNull(playback.player).trackSelectionParameters.disabledTrackTypes
            else playback.state.value.hasRenderedFrame) && !playback.state.value.isPreparing }
    }

    private fun awaitPlaying(playback: PlaybackViewModel, audioOnly: Boolean = false) = compose.waitUntil(WAIT) {
        playback.state.value.errorMessage?.let { throw AssertionError("恢复后的媒体实播失败：$it") }
        main { playback.player?.isPlaying == true &&
            (if (audioOnly) !playback.state.value.hasRenderedFrame &&
                C.TRACK_TYPE_VIDEO in checkNotNull(playback.player).trackSelectionParameters.disabledTrackTypes
            else playback.state.value.hasRenderedFrame) }
    }

    private fun awaitTerminalError(playback: PlaybackViewModel) = compose.waitUntil(WAIT) {
        playback.state.value.errorMessage != null && !playback.state.value.isPreparing
    }

    private fun stopAndAwaitReports(playback: PlaybackViewModel) {
        main { playback.stop() }
        runBlocking { withTimeout(10_000) { playback.awaitPendingReports() } }
    }

    private fun assertFlags(request: InfoRequest, delivery: PlaybackDelivery) {
        val body = request.body
        assertEquals(SOURCE, body.getString("MediaSourceId"))
        assertEquals(USER, body.getString("UserId"))
        assertEquals(delivery == PlaybackDelivery.AUTOMATIC, body.getBoolean("EnableDirectPlay"))
        assertEquals(delivery != PlaybackDelivery.TRANSCODE, body.getBoolean("EnableDirectStream"))
        assertEquals(delivery != PlaybackDelivery.TRANSCODE, body.getBoolean("AllowVideoStreamCopy"))
        assertEquals(delivery != PlaybackDelivery.TRANSCODE, body.getBoolean("AllowAudioStreamCopy"))
        assertTrue(body.getBoolean("EnableTranscoding"))
        assertFalse("原画自动恢复不应偷偷降低用户所选质量", body.has("MaxStreamingBitrate"))
    }

    private fun assertStartStop(fixture: Fixture, playSessionId: String, method: String, item: String = ITEM) {
        val starts = fixture.reports.filter { it.event == "start" }
        val stops = fixture.reports.filter { it.event == "stop" }
        assertEquals("只能上报真正起播的会话", 1, starts.size)
        assertEquals(1, stops.size)
        assertEquals(listOf("start", "stop"), fixture.reports.filter { it.event != "progress" }.map { it.event })
        fixture.reports.forEach {
            assertEquals(item, it.body.getString("ItemId"))
            assertEquals(SOURCE, it.body.getString("MediaSourceId"))
            assertEquals(playSessionId, it.body.getString("PlaySessionId"))
        }
        assertEquals(method, starts.single().body.getString("PlayMethod"))
        assertTrue(stops.single().body.getLong("PositionTicks") >= starts.single().body.getLong("PositionTicks"))
    }

    private fun assertBefore(fixture: Fixture, first: String, second: String) {
        val events = fixture.events.toList()
        assertTrue("事件顺序应为 $first → $second，实际 $events", events.indexOf(first) >= 0 && events.indexOf(second) > events.indexOf(first))
    }

    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    private fun withFixture(scenario: Scenario, assEnabled: Boolean = false, block: (Fixture, PlaybackViewModel, AppPreferences) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val fixture = Fixture(scenario)
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        val preferences = AppPreferences(originalPathEnabled = true, exoAssEnabled = assEnabled,
            preferredAudioLanguage = "en", preferredSubtitleLanguage = "en", subtitlesEnabled = true,
            serverIntroSkipEnabled = false, introPromptSeconds = 0, showClock = false, showBattery = false)
        var failure: Throwable? = null
        try {
            compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop, preferences = preferences) } }
            block(fixture, playback, preferences)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                try {
                    fixture.releaseGates()
                    stopAndAwaitReports(playback)
                } finally { fixture.close() }
                fixture.failures.firstOrNull()?.let { throw AssertionError("恢复 HTTP 夹具收到非法请求", it) }
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private enum class Scenario {
        SERVER_RECOVERS, TRANSCODE_RECOVERS, REMUX_THEN_TRANSCODE, NOTHING_DECODES, UNSUPPORTED_AUDIO, UNAUTHORIZED, EXPIRED_ORIGINAL,
    }

    private data class InfoRequest(val delivery: PlaybackDelivery, val body: JSONObject, val playSessionId: String)
    private data class Report(val event: String, val body: JSONObject)
    private data class EncodingStop(val deviceId: String?, val playSessionId: String?)

    /** 同时观察客户端确实断开 HTTP，而不是只检查一个测试可见的 Job 字段。 */
    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val clientDisconnected = CountDownLatch(1)
        private val responseAttempted = CountDownLatch(1)
        fun awaitEntered() { assertTrue("未收到恢复协商", entered.await(10, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
        fun awaitClientDisconnected() { assertTrue("退出/换集后没有取消在途 HTTP", clientDisconnected.await(10, TimeUnit.SECONDS)) }
        fun awaitResponseAttempt() { assertTrue("迟到的协商响应没有结束", responseAttempted.await(10, TimeUnit.SECONDS)) }
        fun responded() { responseAttempted.countDown() }
        fun awaitRelease(socket: Socket) {
            socket.soTimeout = 100
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (released.count != 0L && clientDisconnected.count != 0L && System.nanoTime() < deadline) {
                try {
                    check(socket.getInputStream().read() == -1) { "协商请求不应在请求体结束后继续发送数据" }
                    clientDisconnected.countDown()
                } catch (_: SocketTimeoutException) { }
                catch (_: IOException) { clientDisconnected.countDown() }
            }
            check(released.await(30, TimeUnit.SECONDS)) { "未放行自有恢复响应" }
            socket.soTimeout = 10_000
        }
    }

    private class Fixture(private val scenario: Scenario) : AutoCloseable {
        private val valid = InstrumentationRegistry.getInstrumentation().context.assets
            .open("continuous-tracks/episode-one.mkv").use { it.readBytes() }
        private val malformed = "PATHFIN OWNED MALFORMED CONTAINER\n".repeat(256).toByteArray()
        private val unsupportedAudio by lazy { withoutSupportedAudio(valid) }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val gates = CopyOnWriteArrayList<Gate>()
        private val nextInfoGate = AtomicReference<Gate?>()
        private val closed = AtomicBoolean()
        private val origin = "http://127.0.0.1:${server.localPort}"
        private val session = Session(ServerConfig(origin, true), TOKEN, USER, "Synthetic recovery", DEVICE, "recovery-server")
        val infoRequests = CopyOnWriteArrayList<InfoRequest>()
        val reports = CopyOnWriteArrayList<Report>()
        val mediaRoutes = CopyOnWriteArrayList<String>()
        val transcodeStops = CopyOnWriteArrayList<EncodingStop>()
        val events = CopyOnWriteArrayList<String>()
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
            }, "Pathfin-recovery-fixture").apply { isDaemon = true }.start()
        }

        fun request(positionMs: Long = 0): PlaybackRequest = originalRequest(ITEM, ORIGINAL_ROUTE, "recovery-original", positionMs)
        fun nextItemRequest(): PlaybackRequest = originalRequest(NEXT_ITEM, NEXT_ROUTE, NEXT_SESSION, 0)

        private fun originalRequest(item: String, route: String, playSessionId: String, positionMs: Long): PlaybackRequest {
            // 服务器恢复用例来自真实 File 流；只有过期外部地址用例保留原始 HTTP 语义。
            val external = scenario == Scenario.EXPIRED_ORIGINAL
            val source = MediaSource(SOURCE, path = if (external) "$origin$route" else "/library/$item.mkv",
                protocol = if (external) "Http" else "File", container = "mkv",
                directStreamUrl = "$origin$SERVER_ROUTE", supportsDirectPlay = true, supportsDirectStream = true)
            return PlaybackRequest(MediaItem(item, "Owned recovery fixture", "Movie", runTimeTicks = 240_000_000L),
                PlaybackInfo(listOf(source), playSessionId), source,
                ResolvedPlayback("$origin$route", if (external) emptyMap() else PlaybackHeaders.serverHeaders(session),
                    PlayMethod.DIRECT_PLAY, SOURCE, !external, "video/x-matroska"), positionMs, session)
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
            val uri = URI(request[1])
            val path = uri.path
            val external = scenario == Scenario.EXPIRED_ORIGINAL && path in setOf(ORIGINAL_ROUTE, REFRESHED_ROUTE, NEXT_ROUTE)
            if (external) {
                check(headers["authorization"].isNullOrEmpty()) { "原始片源不能携带 Jellyfin 凭据" }
                check(headers["x-emby-token"].isNullOrEmpty())
            } else check(headers["authorization"].orEmpty().contains(TOKEN)) { "服务器请求缺少自有凭据" }

            if (request[0] == "POST" && path.startsWith("/Sessions/Playing")) {
                val event = when (path) {
                    "/Sessions/Playing" -> "start"
                    "/Sessions/Playing/Progress" -> "progress"
                    "/Sessions/Playing/Stopped" -> "stop"
                    else -> error("Unexpected owned report path")
                }
                val json = JSONObject(String(body))
                reports += Report(event, json)
                events += "report:$event:${json.getString("PlaySessionId")}"
                respond(socket, 204, ByteArray(0), "application/json")
                return
            }
            if (request[0] == "DELETE" && path == "/Videos/ActiveEncodings") {
                val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                check(query.keys == setOf("deviceId", "playSessionId")) { "转码清理必须严格限定设备和会话" }
                val stop = EncodingStop(query["deviceId"], query["playSessionId"])
                transcodeStops += stop
                events += "encoding-stop:${stop.playSessionId}"
                respond(socket, 204, ByteArray(0), "application/json")
                return
            }
            if (request[0] == "POST" && path == "/Items/$ITEM/PlaybackInfo") {
                val json = JSONObject(String(body))
                val delivery = when {
                    !json.getBoolean("EnableDirectStream") -> PlaybackDelivery.TRANSCODE
                    !json.getBoolean("EnableDirectPlay") -> PlaybackDelivery.SERVER_STREAM
                    else -> PlaybackDelivery.AUTOMATIC
                }
                val info = InfoRequest(delivery, json, "recovery-${delivery.name.lowercase()}-${infoRequests.size + 1}")
                infoRequests += info
                events += "info:${delivery.name}"
                val gate = nextInfoGate.getAndSet(null)
                try {
                    gate?.awaitRelease(socket)
                    val external = scenario == Scenario.EXPIRED_ORIGINAL
                    val dto = JSONObject().put("Id", SOURCE)
                        .put("Path", if (external) "$origin$REFRESHED_ROUTE" else "/library/$ITEM.mkv")
                        .put("Protocol", if (external) "Http" else "File")
                        .put("Container", "mkv").put("SupportsDirectPlay", delivery == PlaybackDelivery.AUTOMATIC)
                        .put("SupportsDirectStream", delivery != PlaybackDelivery.TRANSCODE)
                    if (scenario == Scenario.REMUX_THEN_TRANSCODE && delivery == PlaybackDelivery.SERVER_STREAM) {
                        dto.put("TranscodingUrl", SERVER_ROUTE)
                    } else dto.put("DirectStreamUrl", SERVER_ROUTE).put("TranscodingUrl", TRANSCODE_ROUTE)
                    val response = JSONObject().put("MediaSources", JSONArray().put(dto)).put("PlaySessionId", info.playSessionId)
                    respond(socket, 200, response.toString().toByteArray(), "application/json")
                } finally { gate?.responded() }
                return
            }
            check(request[0] == "GET")
            check(path in setOf(ORIGINAL_ROUTE, REFRESHED_ROUTE, SERVER_ROUTE, TRANSCODE_ROUTE, NEXT_ROUTE)) { "Unexpected owned media route" }
            mediaRoutes += path
            events += "media:$path"
            if (path == ORIGINAL_ROUTE && scenario == Scenario.UNAUTHORIZED) {
                respond(socket, 401, ByteArray(0), "video/x-matroska"); return
            }
            if (path == ORIGINAL_ROUTE && scenario == Scenario.EXPIRED_ORIGINAL) {
                respond(socket, 403, ByteArray(0), "video/x-matroska"); return
            }
            val bytes = when (path) {
                ORIGINAL_ROUTE -> if (scenario == Scenario.UNSUPPORTED_AUDIO) unsupportedAudio else malformed
                SERVER_ROUTE -> if (scenario in setOf(Scenario.TRANSCODE_RECOVERS, Scenario.REMUX_THEN_TRANSCODE, Scenario.NOTHING_DECODES)) malformed else valid
                TRANSCODE_ROUTE -> if (scenario == Scenario.NOTHING_DECODES) malformed else valid
                REFRESHED_ROUTE, NEXT_ROUTE -> valid
                else -> error("Unreachable media route")
            }
            val range = headers["range"]?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) ?: error("Unexpected owned media range") }
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
        const val ITEM = "44444444444444444444444444444444"
        const val NEXT_ITEM = "55555555555555555555555555555555"
        const val SOURCE = "recovery-source"
        const val USER = "recovery-user"
        const val TOKEN = "owned-recovery-token"
        const val DEVICE = "owned-recovery-device"
        const val NEXT_SESSION = "recovery-next-original"
        const val ORIGINAL_ROUTE = "/original.mkv"
        const val REFRESHED_ROUTE = "/refreshed-original.mkv"
        const val SERVER_ROUTE = "/server-stream.mkv"
        const val TRANSCODE_ROUTE = "/transcoded.mkv"
        const val NEXT_ROUTE = "/next-original.mkv"

        /** 原位改写两条音轨为 Media3 不支持的 12-bit PCM；视频与所有 Seek/Cues 偏移保持不变。 */
        fun withoutSupportedAudio(bytes: ByteArray): ByteArray {
            val result = bytes.copyOf()
            val segment = elements(bytes, 0, bytes.size).single { it.id == 0x18538067 }
            val tracks = elements(bytes, segment.data, segment.end).single { it.id == 0x1654AE6B }
            var rewritten = 0
            elements(bytes, tracks.data, tracks.end).filter { it.id == 0xAE }.forEach { entry ->
                val fields = elements(bytes, entry.data, entry.end)
                val type = fields.single { it.id == 0x83 }
                if (bytes[type.data].toInt() != 2) return@forEach
                val number = fields.single { it.id == 0xD7 }
                val uid = fields.single { it.id == 0x73C5 }
                val audio = element(0xE1,
                    element(0xB5, ByteBuffer.allocate(8).putDouble(48_000.0).array()) +
                        element(0x9F, byteArrayOf(1)) + element(0x6264, byteArrayOf(12)))
                val replacement = bytes.copyOfRange(number.start, number.end) + bytes.copyOfRange(uid.start, uid.end) +
                    element(0x83, byteArrayOf(2)) + element(0x86, "A_PCM/INT/LIT".toByteArray()) + audio
                val padding = entry.end - entry.data - replacement.size
                check(padding in 2..128) { "自有音轨头不能原位改写" }
                val padded = replacement + element(0xEC, ByteArray(padding - 2))
                check(padded.size == entry.end - entry.data)
                padded.copyInto(result, entry.data)
                rewritten += 1
            }
            check(rewritten == 2) { "自有测试素材必须含两条音轨" }
            return result
        }

        private data class Element(val id: Int, val start: Int, val data: Int, val end: Int)

        private fun elements(bytes: ByteArray, start: Int, end: Int): List<Element> {
            val result = mutableListOf<Element>()
            var offset = start
            while (offset < end) {
                val elementStart = offset
                fun variable(removeMarker: Boolean): Long {
                    check(offset < end)
                    val first = bytes[offset].toInt() and 0xff
                    var marker = 0x80
                    var length = 1
                    while (first and marker == 0) { marker = marker ushr 1; length += 1; check(length <= 8) }
                    check(offset + length <= end)
                    var value = (if (removeMarker) first and (marker - 1) else first).toLong()
                    offset += 1
                    repeat(length - 1) { value = (value shl 8) or (bytes[offset++].toLong() and 0xff) }
                    return value
                }
                val id = variable(false).toInt()
                val size = variable(true)
                val data = offset
                val elementEnd = minOf(end.toLong(), data + size).toInt()
                check(elementEnd >= data)
                result += Element(id, elementStart, data, elementEnd)
                offset = elementEnd
            }
            return result
        }

        private fun element(id: Int, payload: ByteArray): ByteArray {
            check(payload.size < 127)
            val idLength = when { id <= 0xff -> 1; id <= 0xffff -> 2; id <= 0xffffff -> 3; else -> 4 }
            return ByteArrayOutputStream().apply {
                for (index in idLength - 1 downTo 0) write((id ushr (index * 8)) and 0xff)
                write(0x80 or payload.size)
                write(payload)
            }.toByteArray()
        }
    }
}

package dev.pathfin.android

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.PendingPlaybackCancellationTest.Fixture
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.playback.DevicePlaybackCapabilities
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 独立回环数据 + 真 MainActivity/Exo：立即进入准备页，但准备成功前不建立播放会话。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackPreparationFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolatedStorage = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == TARGET) { "准备播放回归只允许 verification 包" }
            val preferences = AppPreferencesStore(context)
            val previous = preferences.load()
            clearSyntheticSession()
            preferences.save(AppPreferences(originalPathEnabled = false,
                subtitlesEnabled = false, exoAssEnabled = false, serverIntroSkipEnabled = false,
                autoPlayNextEpisode = false, showClock = false, showBattery = false))
            try { base.evaluate() }
            finally { clearSyntheticSession(); preferences.save(previous) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test fun completedDetailPrefetchIsConsumedOnceWithoutAnotherPlaybackRequest() {
        withFixture { fixture, app, _ ->
            openDetail(fixture, app)
            await(fixture) { fixture.nextPlaybackRequests.get() == 1 }
            fixture.nextPlayback.awaitResponseAttempt()
            main { app.preparePlayback(app.state.value.details.getValue(TWO), fromStart = true) }
            await(fixture) { app.state.value.activePlayback?.item?.id == TWO }
            assertEquals("详情预取必须继续缩短起播，不应无效地再查一次", 1, fixture.nextPlaybackRequests.get())
        }
    }

    @Test fun mediaChangesDiscardPrefetchedSourceInformationBeforeTheNextPlay() {
        withFixture { fixture, app, _ ->
            openDetail(fixture, app)
            await(fixture) { fixture.nextPlaybackRequests.get() == 1 }
            fixture.nextPlayback.awaitResponseAttempt()
            fixture.playbackSourceSuffix.set("-updated")
            main { app.mediaChanged(checkNotNull(app.state.value.session), TWO) }
            await(fixture) { !app.state.value.detailLoading }
            main { app.preparePlayback(app.state.value.details.getValue(TWO), fromStart = true) }
            await(fixture) { app.state.value.activePlayback?.item?.id == TWO }
            assertEquals("媒体/字幕管理成功后不能继续消费旧媒体源", 2, fixture.nextPlaybackRequests.get())
            assertEquals("first-updated", main { app.state.value.activePlayback?.source?.name })
        }
    }

    @Test fun changingOriginalPathPreferenceDiscardsThePreviousPreflight() {
        withFixture { fixture, app, _ ->
            openDetail(fixture, app)
            await(fixture) { fixture.nextPlaybackRequests.get() == 1 }
            fixture.nextPlayback.awaitResponseAttempt()
            main {
                app.setPreferences(app.state.value.preferences.copy(originalPathEnabled = true))
                app.preparePlayback(app.state.value.details.getValue(TWO), fromStart = true)
            }
            await(fixture) { app.state.value.activePlayback?.item?.id == TWO }
            assertEquals("直连偏好变化必须重新按当前模式准备来源", 2, fixture.nextPlaybackRequests.get())
        }
    }

    @Test fun leavingDetailBeforeThePrefetchDelayDoesNotProbeTheOldPage() {
        assertDelayedPrefetchCancelled { it.back() }
    }

    @Test fun openingServerManagementBeforeThePrefetchDelayDoesNotProbeTheHiddenDetail() {
        assertDelayedPrefetchCancelled { it.showServers() }
    }

    @Test fun removingTheAccountBeforeThePrefetchDelayDoesNotProbeTheOldSession() {
        assertDelayedPrefetchCancelled { it.logout() }
    }

    private fun assertDelayedPrefetchCancelled(navigate: (AppViewModel) -> Unit) {
        withFixture { fixture, app, _ ->
            fixture.nextDetail.expectCancellation.set(true)
            main { app.open(episode()); navigate(app) }
            android.os.SystemClock.sleep(900)
            assertEquals("离开详情后不能再向服务器探测旧条目", 0, fixture.nextPlaybackRequests.get())
            assertNeverPlayed(fixture)
        }
    }

    @Test fun selectedVersionGetsItsExactVideoProfileAfterUnselectedAutomaticPreparation() {
        withFixture(multipleSources = true, negotiationVideo = locallySupportedVideoBeyondStaticProfile()) { fixture, app, _ ->
            main { app.preparePlayback(episode(), fromStart = true) }
            await(fixture) { app.state.value.sourceSelection != null && !app.state.value.preparingPlay }
            assertEquals("未选版本只允许一次静态声明", 1, fixture.nextPlaybackRequests.get())
            assertFalse(fixture.playbackBodies.single().has("MediaSourceId"))
            val selectedId = "source-$TWO-second"
            main { app.chooseSource(selectedId) }
            await(fixture) {
                app.state.value.playbackError?.let { throw AssertionError(it) }
                app.state.value.activePlayback?.source?.id == selectedId
            }
            assertEquals("选定可本机解码版本后应只追加一次精确协商", 2, fixture.nextPlaybackRequests.get())
            assertEquals(selectedId, fixture.playbackBodies.last().getString("MediaSourceId"))
            assertTrue("本机支持的所选版本必须发精确直放协商，而不是能力拒绝后的转码请求：${fixture.playbackBodies.last()}",
                fixture.playbackBodies.last().optBoolean("EnableDirectPlay"))
            assertNotEquals("不能因为未选版本时没有视频预检，就误走服务器转码", PlayMethod.TRANSCODE,
                main { app.state.value.activePlayback?.stream?.method })
        }
    }

    @Test fun explicitVersionWhoseExactVideoProfileWasAlreadyRejectedIsNotNegotiatedAgain() {
        withFixture(multipleSources = true, negotiationVideo = locallySupportedVideoBeyondStaticProfile()) { fixture, app, _ ->
            fixture.acceptVideoNegotiation.set(false)
            val selectedId = "source-$TWO-second"
            main { app.preparePlayback(episode(), fromStart = true, sourceId = selectedId) }
            await(fixture) {
                app.state.value.playbackError?.let { throw AssertionError(it) }
                app.state.value.activePlayback?.source?.id == selectedId
            }
            assertEquals("明确版本已做一次精确协商，服务器拒绝后不能再重复", 2, fixture.nextPlaybackRequests.get())
            assertTrue(fixture.playbackBodies.all { it.optString("MediaSourceId") == selectedId })
            assertEquals("精确视频声明仍须尊重服务器的音频/容器/字幕拒绝", PlayMethod.TRANSCODE,
                main { app.state.value.activePlayback?.stream?.method })
        }
    }

    private fun locallySupportedVideoBeyondStaticProfile(): JSONObject {
        // 刻意超过设备静态声明的帧率，但保留本地扩展可解的尺寸/位深；不依赖特定手机的厂商 profile。
        val frameRate = (DevicePlaybackCapabilities.current.video["h264"]?.maxFrameRate ?: 60.0) + 1.0
        assertTrue("真实 High10 精确协商场景要求可加载的本地 H264 扩展，不能把无原生能力的环境当成协商缺陷",
            dev.pathfin.ffmpeg.video.NativeVideoRenderer.supportsSource("h264", null, 320, 180, 10))
        return JSONObject().put("Type", "Video").put("Index", 0).put("Codec", "h264")
            .put("Profile", "High 10").put("Width", 320).put("Height", 180).put("BitDepth", 10)
            .put("Level", 40).put("AverageFrameRate", frameRate).put("VideoRangeType", "SDR")
    }

    @Test fun clickKeepsDetailWithSpinningPlayButtonAndBackCancelsWithoutStartingOrStopping() {
        withFixture(blockPlayback = true) { fixture, app, playback ->
            openDetail(fixture, app)
            val route = main { app.state.value.route }
            main {
                app.preparePlayback(app.state.value.details.getValue(TWO), fromStart = true)
                val pending = checkNotNull(app.state.value.pendingPlayback)
                assertEquals(TWO, pending.item.id)
                assertTrue(pending.fromStart)
                assertTrue(app.state.value.preparingPlay)
                assertNull(app.state.value.activePlayback)
                assertNull("尚未取得播放信息，不能预建播放器", playback.player)
            }
            fixture.nextPlayback.awaitEntered()
            // 准备期间留在详情页，播放按钮转圈；不再切到过渡页。
            await(fixture) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail-play-spinner")).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("detail-play-spinner").assertIsDisplayed()
            compose.onNodeWithTag("playback-preparation").assertDoesNotExist()
            val job = pendingJob(app)
            fixture.nextPlayback.expectCancellation.set(true)
            main { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle {
                val state = app.state.value
                val view = compose.activity.window.decorView
                val window = android.graphics.Rect()
                val visible = view.getGlobalVisibleRect(window)
                assertTrue("放行 PlaybackInfo 前，返回必须已经取消准备：" +
                    "pending=${state.pendingPlayback?.item?.id} active=${state.activePlayback?.item?.id} " +
                    "preparing=${state.preparingPlay} route=${state.route} player=${playback.player} " +
                    "isFinishing=${compose.activity.isFinishing} focus=${view.hasWindowFocus()} " +
                    "window=$visible:$window size=${view.width}x${view.height}",
                    state.pendingPlayback == null && state.activePlayback == null && !state.preparingPlay &&
                        state.route == route && playback.player == null && !compose.activity.isFinishing)
            }
            fixture.nextPlayback.release()
            fixture.nextPlayback.awaitResponseAttempt()
            join(job)
            await(fixture) { main {
                app.state.value.pendingPlayback == null && app.state.value.activePlayback == null && !app.state.value.preparingPlay &&
                    app.state.value.route == route && playback.player == null && !compose.activity.isFinishing
            } }
            assertNeverPlayed(fixture)
        }
    }

    @Test fun preparationFailureRetainsTheTargetAndVisibleRetryFetchesFreshInfoBeforeActualExoPlayback() {
        withFixture(blockPlayback = true) { fixture, app, playback ->
            openDetail(fixture, app)
            fixture.playbackStatus.set(503)
            val pending = main {
                app.preparePlayback(app.state.value.details.getValue(TWO), fromStart = true)
                checkNotNull(app.state.value.pendingPlayback)
            }
            fixture.nextPlayback.awaitEntered()
            fixture.nextPlayback.release()
            await(fixture) { !app.state.value.preparingPlay && app.state.value.playbackError != null }
            main {
                assertEquals(pending.id, app.state.value.pendingPlayback?.id)
                assertNull(app.state.value.activePlayback)
                assertNull(playback.player)
            }
            compose.onNodeWithTag("detail-play-spinner").assertDoesNotExist()
            assertNeverPlayed(fixture)
            fixture.playbackStatus.set(200)
            // 打开详情时会预取一次 PlaybackInfo，这里只核对重试本身发出了新请求。
            val requestsBeforeRetry = fixture.nextPlaybackRequests.get()
            compose.onNodeWithTag("playback-preparation-retry").assertIsDisplayed().performClick()
            await(fixture) { main {
                app.state.value.playbackError?.let { throw AssertionError("重试准备失败：$it") }
                playback.state.value.errorMessage?.let { throw AssertionError("真实 Exo 起播失败：$it") }
                app.state.value.pendingPlayback == null && app.state.value.activePlayback?.item?.id == TWO &&
                    playback.state.value.hasRenderedFrame && playback.player?.isPlaying == true
            } }
            assertEquals("重试必须重新请求 PlaybackInfo，不能复用失败尝试的临时播放信息", requestsBeforeRetry + 1, fixture.nextPlaybackRequests.get())
            // 首帧/playing 由 Main 发布，Jellyfin start 随后经独立 IO 队列发送；检查真实服务器前等待该队列屏障。
            awaitReports(playback)
            assertEquals(1, fixture.eventCount("start", TWO))
            val player = main { checkNotNull(playback.player) }
            main { compose.activity.onBackPressedDispatcher.onBackPressed() }
            await(fixture) { main {
                app.state.value.route == Route.Detail(TWO) && app.state.value.activePlayback == null &&
                    playback.player == null && player.isReleased && !compose.activity.isFinishing
            } }
            awaitReports(playback)
            assertEquals(1, fixture.eventCount("stop", TWO))
        }
    }

    @Test fun seriesResolutionAlsoPublishesImmediatelyAndAppBackCancelsWithoutPoppingThePage() {
        withFixture(blockSeries = true) { fixture, app, playback ->
            val route = main { app.state.value.route }
            main {
                app.playSeries(MediaItem("pending-series", "Synthetic series", "Series"), fromStart = true)
                assertEquals("pending-series", app.state.value.pendingPlayback?.item?.id)
                assertTrue(app.state.value.preparingPlay)
                assertNull(app.state.value.activePlayback)
            }
            fixture.seriesResolution.awaitEntered()
            val job = pendingJob(app)
            fixture.seriesResolution.expectCancellation.set(true)
            main {
                app.back()
                assertEquals(route, app.state.value.route)
                assertNull(app.state.value.pendingPlayback)
            }
            fixture.seriesResolution.release()
            fixture.seriesResolution.awaitResponseAttempt()
            join(job)
            main { assertNull(app.state.value.activePlayback); assertNull(playback.player) }
            assertEquals(0, fixture.nextPlaybackRequests.get())
            assertNeverPlayed(fixture)
        }
    }

    @Test fun removingTheAccountWhilePreparingPreventsALatePlaybackResponseFromRestoringIt() {
        withFixture(blockPlayback = true) { fixture, app, playback ->
            main { app.preparePlayback(episode(), fromStart = true) }
            fixture.nextPlayback.awaitEntered()
            val job = pendingJob(app)
            fixture.nextPlayback.expectCancellation.set(true)
            main { app.logout() }
            await(fixture) { app.state.value.session == null && app.state.value.profiles.isEmpty() && !app.state.value.loginBusy }
            fixture.nextPlayback.release()
            fixture.nextPlayback.awaitResponseAttempt()
            join(job)
            main {
                assertNull(app.state.value.pendingPlayback)
                assertNull(app.state.value.activePlayback)
                assertNull(app.state.value.playbackError)
                assertNull(playback.player)
                assertTrue(app.state.value.showServers)
            }
            assertTrue("迟到响应不能重新写入已删除的合成账号", SessionStore(context).loadProfiles().isEmpty())
            assertNeverPlayed(fixture)
        }
    }

    @Test fun aCancelledAttemptCannotReplaceANewerActualPlaybackInTheSameAccount() {
        withFixture(blockPlayback = true) { fixture, app, playback ->
            val firstId = main {
                app.preparePlayback(episode(), fromStart = true)
                checkNotNull(app.state.value.pendingPlayback).id
            }
            fixture.nextPlayback.awaitEntered()
            val cancelled = pendingJob(app)
            fixture.nextPlayback.expectCancellation.set(true)
            main {
                app.back()
                app.preparePlayback(MediaItem(ONE, "Synthetic first episode", "Episode", seriesId = "pending-series",
                    runTimeTicks = 240_000_000L), fromStart = true)
                val pending = checkNotNull(app.state.value.pendingPlayback)
                assertTrue("同账号的新尝试也必须有独立身份", pending.id > firstId)
                assertEquals(ONE, pending.item.id)
            }
            await(fixture) { main {
                app.state.value.playbackError?.let { throw AssertionError("后续真实起播失败：$it") }
                playback.state.value.errorMessage?.let { throw AssertionError("后续真实 Exo 失败：$it") }
                app.state.value.activePlayback?.item?.id == ONE &&
                    playback.state.value.hasRenderedFrame && playback.player?.isPlaying == true
            } }
            val request = main { checkNotNull(app.state.value.activePlayback) }
            val player = main { playback.player }
            fixture.nextPlayback.release()
            fixture.nextPlayback.awaitResponseAttempt()
            join(cancelled)
            main {
                assertSame("旧请求的迟到结束不能覆盖新播放入口", request, app.state.value.activePlayback)
                assertSame(player, playback.player)
                assertNull(app.state.value.pendingPlayback)
                assertNull(app.state.value.playbackError)
            }
            awaitReports(playback)
            assertEquals(1, fixture.eventCount("start", ONE))
            assertEquals(0, fixture.eventCount("start", TWO))
            assertEquals(0, fixture.eventCount("stop", TWO))
            assertEquals(0, fixture.nextMediaRequests.get())
        }
    }

    private fun withFixture(blockPlayback: Boolean = false, blockSeries: Boolean = false,
        multipleSources: Boolean = false, negotiationVideo: JSONObject? = null,
        block: (Fixture, AppViewModel, PlaybackViewModel) -> Unit) {
        val video = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        Fixture(video, blockPlayback = blockPlayback, blockSeries = blockSeries,
            multipleSources = multipleSources, negotiationVideo = negotiationVideo).use { fixture ->
            val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
            val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
            try {
                await(fixture) { !app.state.value.restoringSession }
                main { app.login(fixture.origin, "pending-fixture-user", "pending-fixture-password", true, "Pending playback fixture") }
                await(fixture) {
                    app.state.value.loginError?.let { throw AssertionError("合成登录失败：$it") }
                    app.state.value.session?.serverId == SERVER_ID && app.state.value.home.loaded && !app.state.value.home.loading
                }
                block(fixture, app, playback)
            } finally {
                fixture.nextDetail.expectCancellation.set(true)
                fixture.nextPlayback.expectCancellation.set(true)
                fixture.seriesResolution.expectCancellation.set(true)
                main { playback.stop(); app.playerClosed(playback.lastPositionMs) }
                fixture.nextDetail.release(); fixture.nextPlayback.release(); fixture.seriesResolution.release()
                awaitReports(playback)
            }
        }
    }

    private fun openDetail(fixture: Fixture, app: AppViewModel) {
        main { app.open(episode()) }
        await(fixture) { app.state.value.route == Route.Detail(TWO) && !app.state.value.detailLoading && TWO in app.state.value.details }
    }

    private fun assertNeverPlayed(fixture: Fixture) {
        assertEquals(0, fixture.eventCount("start", ONE))
        assertEquals(0, fixture.eventCount("start", TWO))
        assertEquals("没有建立过播放器，不能报告停止", 0, fixture.eventCount("stop", ONE) + fixture.eventCount("stop", TWO))
        assertEquals(0, fixture.nextMediaRequests.get())
        fixture.assertHealthy()
    }

    private fun pendingJob(app: AppViewModel): Job = main {
        checkNotNull(AppViewModel::class.java.getDeclaredField("playJob").apply { isAccessible = true }.get(app) as Job?)
    }
    private fun join(job: Job) = runBlocking { withTimeout(WAIT) { job.join() } }
    private fun awaitReports(playback: PlaybackViewModel) = runBlocking { withTimeout(WAIT) { playback.awaitPendingReports() } }
    private fun await(fixture: Fixture, condition: () -> Boolean) = compose.waitUntil(WAIT) { fixture.assertHealthy(); condition() }
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

    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val SERVER_ID = "pending-playback-fixture"
        const val TOKEN = "pending-playback-synthetic-token"
        const val ONE = "pending-episode-one"
        const val TWO = "pending-episode-two"
        const val WAIT = 25_000L
        fun episode() = MediaItem(TWO, "Synthetic pending episode", "Episode", seriesId = "pending-series", runTimeTicks = 240_000_000L)
    }
}

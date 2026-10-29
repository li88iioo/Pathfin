package dev.pathfin.android

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.SystemBackGestureProgress
import dev.pathfin.android.ui.Route
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 与系统KEY/边缘握手组互补：真实Media3验证预测手势取消、锁定首次解锁、完成单stop。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PredictiveBackPlaybackTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createVerificationComposeRule<MainActivity>()
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val isolatedStorage = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == "dev.pathfin.android.verification")
            check(status().optBoolean("MultiTrackFixture")) { "必须先启动8101/8102自制媒体夹具" }
            val preferences = AppPreferencesStore(context)
            val previous = preferences.load()
            clearSyntheticSession()
            preferences.save(AppPreferences(
                showClock = false, showBattery = false))
            try { base.evaluate() }
            finally {
                clearSyntheticSession(); preferences.save(previous)
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test fun cancellingAndUnlockingKeepTheActualPlayerAndOnlyCompletedBackReportsStop() {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = compose.runOnIdle { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        var session: String? = null
        try {
            compose.runOnIdle { app.login(BASE, "pathfin", "pathfin", true, "Synthetic predictive-back fixture") }
            compose.waitUntil(WAIT) { app.state.value.session != null && app.state.value.home.loaded && !app.state.value.home.loading }
            compose.runOnIdle { app.open((app.state.value.home.libraryItems.values.flatten() + app.state.value.home.recent).first { it.id == ITEM }) }
            compose.waitUntil(WAIT) { !app.state.value.detailLoading && ITEM in app.state.value.details }
            compose.runOnIdle { app.preparePlayback(app.state.value.details.getValue(ITEM), fromStart = true) }
            compose.waitUntil(WAIT) {
                playback.state.value.errorMessage?.let { throw AssertionError("合成真实播放失败：$it") }
                app.state.value.activePlayback != null && playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
            }
            val request = checkNotNull(app.state.value.activePlayback)
            check(request.stream.uri == "http://127.0.0.1:8102/sample.mp4" && request.session.accessToken == "fixture-token")
            session = request.info.playSessionId
            val player = compose.runOnIdle { checkNotNull(playback.player) }
            val initialBounds = compose.onNodeWithTag("playback-back-motion").fetchSemanticsNode().boundsInRoot
            startAndProgress(.7f)
            compose.waitUntil { progress() > .6f }
            assertEquals("系统返回过程中播放器图层不能缩放或位移", initialBounds,
                compose.onNodeWithTag("playback-back-motion").fetchSemanticsNode().boundsInRoot)
            assertSame(player, compose.runOnIdle { playback.player })
            assertSame(request, app.state.value.activePlayback)
            assertEquals(0, stopCount(session))
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.waitUntil { progress() == 0f }
            assertSame(request, app.state.value.activePlayback)
            assertTrue(compose.runOnIdle { player.isPlaying })
            assertEquals(0, stopCount(session))

            compose.onNodeWithTag("player-lock").performClick()
            compose.waitUntil { playback.state.value.controlsLocked }
            startAndProgress(.8f)
            compose.waitUntil { progress() > .7f }
            assertEquals("锁定时系统手势仍不能改变播放器图层", initialBounds,
                compose.onNodeWithTag("playback-back-motion").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil { !playback.state.value.controlsLocked }
            assertSame("首次完成仅解锁，仍保留原节目", request, app.state.value.activePlayback)
            assertSame(player, compose.runOnIdle { playback.player })
            assertEquals(0, stopCount(session))

            startAndProgress(.75f)
            compose.waitUntil { progress() > .7f }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(WAIT) { app.state.value.activePlayback == null && playback.player == null }
            assertEquals(Route.Detail(ITEM), app.state.value.route)
            assertTrue(compose.runOnIdle { player.isReleased })
            assertFalse(compose.activity.isFinishing)
            drain(playback)
            assertEquals("一次完成只能上报一次停止", 1, stopCount(session))
        } finally {
            compose.runOnIdle {
                playback.stop()
                if (app.state.value.activePlayback != null) app.playerClosed(playback.lastPositionMs)
            }
            drain(playback)
            session?.let { assertEquals("测试清理不可二次停止上报", 1, stopCount(it)) }
        }
    }

    private fun startAndProgress(fraction: Float) = compose.runOnIdle {
        compose.activity.onBackPressedDispatcher.apply {
            dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatchOnBackProgressed(BackEventCompat(150f, 200f, fraction, BackEventCompat.EDGE_LEFT))
        }
    }
    private fun progress() = compose.onNodeWithTag("playback-back-motion").fetchSemanticsNode().config[SystemBackGestureProgress]
    private fun status(): JSONObject = client.newCall(Request.Builder().url("$BASE/health").build()).execute().use {
        check(it.isSuccessful); JSONObject(it.body.string())
    }
    private fun stopCount(session: String): Int {
        val events = status().getJSONArray("Events")
        return (0 until events.length()).count { events.getJSONObject(it).let { row ->
            row.optString("event") == "stop" && row.optString("play_session_id") == session
        } }
    }
    private fun drain(playback: PlaybackViewModel) = runBlocking { withTimeout(WAIT) { playback.awaitPendingReports() } }
    private fun clearSyntheticSession() {
        val store = SessionStore(context)
        check(store.loadProfiles().all { it.session.serverId == "pathfin-fixture-server" && it.session.accessToken == "fixture-token" }) { "存在非合成账号，拒绝清理" }
        store.loadProfiles().forEach { store.removeProfile(it.id) }; store.clear()
    }
    private companion object {
        const val BASE = "http://127.0.0.1:8101/jellyfin"
        const val ITEM = "fixture-movie"
        const val WAIT = 25_000L
    }
}

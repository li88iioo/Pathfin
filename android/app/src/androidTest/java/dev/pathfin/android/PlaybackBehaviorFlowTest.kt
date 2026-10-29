package dev.pathfin.android

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/** 实播自制 fixture 视频，覆盖真正的 Exo 音量/seek 参数、响应体速率与播放中跳片尾。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackBehaviorFlowTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "dev.pathfin.android.verification") { "播放偏好回归只允许 verification 包" }
            verifySyntheticFixture()
            val store = AppPreferencesStore(context)
            val previous = store.load()
            clearSyntheticSession(context)
            store.save(AppPreferences())
            try { base.evaluate() }
            finally { clearSyntheticSession(context); store.save(previous) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test fun attenuationAndKeyframeSeekUseSnapshotWhileSpeedComesFromActualMediaReads() {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = compose.runOnIdle { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        val samples = CopyOnWriteArrayList<Long>()
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        observer.launch { playback.state.collect { it.transferBytesPerSecond?.let(samples::add) } }
        try {
            openFixture(app, AppPreferences(audioGainPercent = 50, exactSeek = false, showBufferSpeed = true))
            awaitFirstFrame(app, playback)
            compose.runOnIdle {
                val player = checkNotNull(playback.player)
                player.pause()
                assertEquals(.5f, player.volume, .001f)
                assertEquals(SeekParameters.CLOSEST_SYNC, player.seekParameters)
                assertNull(playback.state.value.audioGainStatus)
                assertTrue(playback.state.value.showBufferSpeed)
            }
            compose.waitUntil(WAIT_MS) { samples.any { it > 0 } }
            compose.waitUntil(WAIT_MS) {
                compose.runOnIdle { playback.player?.let { it.bufferedPosition >= it.duration - 250 } == true }
            }
            compose.waitUntil(WAIT_MS) { samples.lastOrNull() == 0L }
            compose.runOnIdle {
                // 设置页明确写明下次起播生效；当前播放不能中途悄悄改变快照。
                app.setPreferences(AppPreferences(audioGainPercent = 100, exactSeek = true, showBufferSpeed = false))
                val player = checkNotNull(playback.player)
                assertEquals(.5f, player.volume, .001f)
                assertEquals(SeekParameters.CLOSEST_SYNC, player.seekParameters)
                assertTrue(playback.state.value.showBufferSpeed)
                playback.stop()
                assertNull(playback.state.value.transferBytesPerSecond)
            }
        } finally {
            observer.cancel()
            compose.runOnIdle { playback.stop(); app.logout() }
        }
    }

    @Test fun pausedOutroWaitsForRealPlayingThenSeeksToReliableEndOnlyOnce() {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = compose.runOnIdle { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        val endingSeeks = AtomicInteger()
        try {
            openFixture(app, AppPreferences(audioGainPercent = 150, outroSkipSeconds = 3, exactSeek = true,
                autoPlayNextEpisode = false))
            awaitFirstFrame(app, playback)
            val player = compose.runOnIdle {
                checkNotNull(playback.player).also {
                    it.pause()
                    assertEquals(SeekParameters.EXACT, it.seekParameters)
                    assertTrue(it.duration >= 14_000)
                    assertEquals(1f, it.volume, .001f)
                }
            }
            compose.waitUntil(WAIT_MS) {
                compose.runOnIdle { player.audioSessionId > 0 && playback.state.value.audioGainStatus?.contains("等待") != true }
            }
            val gainApplied = compose.runOnIdle {
                playback.state.value.audioGainStatus?.let { assertTrue("不支持音效时必须明确回退", it.contains("已恢复 100% 原音量")) }
                assertNull(playback.state.value.transferBytesPerSecond)
                player.seekTo(player.duration - 2_000)
                playback.state.value.audioGainStatus == null
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "Pathfin Exo gain150: LoudnessEnhancerApplied=$gainApplied; fallbackToOriginal=${!gainApplied}\n")
            })
            compose.waitUntil(WAIT_MS) {
                compose.runOnIdle { player.playbackState == Player.STATE_READY && player.currentPosition >= player.duration - 2_250 }
            }
            // 跨过两个实际 500 ms 检查窗，暂停时仍不能跳片尾。
            SystemClock.sleep(1_100)
            compose.runOnIdle {
                assertFalse(playback.state.value.ended)
                assertTrue(playback.player === player)
                assertFalse(player.isPlaying)
                assertTrue(player.currentPosition < player.duration - 1_000)
                val duration = player.duration
                player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs >= duration - 100) endingSeeks.incrementAndGet()
                    }
                })
                player.play()
            }
            // 跳到片尾即播放结束，没有下一集时直接退出播放器。
            compose.waitUntil(WAIT_MS) { endingSeeks.get() > 0 && app.state.value.activePlayback == null }
            assertEquals("片尾设置必须实际发出一次到结尾的 seek，不能只等自然结束", 1, endingSeeks.get())
            compose.runOnIdle {
                assertNull(playback.state.value.errorMessage)
                assertNull(playback.player)
                assertFalse("跳片尾不能改变自动下一集偏好", app.state.value.preferences.autoPlayNextEpisode)
                assertEquals(Route.Detail(ITEM_ID), app.state.value.route)
                assertFalse(compose.activity.isFinishing)
            }
        } finally { compose.runOnIdle { playback.stop(); app.logout() } }
    }

    private fun openFixture(app: AppViewModel, preferences: AppPreferences) {
        compose.runOnIdle { app.login(FIXTURE_ORIGIN, "pathfin", "pathfin", true, "Playback preferences fixture") }
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.loginError?.let { throw AssertionError("合成服务登录失败：$it") }
            state.home.error?.let { throw AssertionError("合成媒体库加载失败：$it") }
            state.session != null && state.home.loaded && !state.home.loading
        }
        compose.runOnIdle {
            app.setPreferences(preferences)
            val home = app.state.value.home
            app.open((home.libraryItems.values.flatten() + home.recent + home.resume).first { it.id == ITEM_ID })
        }
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.detailError?.let { throw AssertionError("合成详情加载失败：$it") }
            state.route == Route.Detail(ITEM_ID) && !state.detailLoading && state.details[ITEM_ID] != null
        }
        compose.runOnIdle { app.preparePlayback(app.state.value.details.getValue(ITEM_ID), fromStart = true) }
    }

    private fun awaitFirstFrame(app: AppViewModel, playback: PlaybackViewModel) {
        compose.waitUntil(WAIT_MS) {
            app.state.value.playbackError?.let { throw AssertionError("合成播放准备失败：$it") }
            playback.state.value.errorMessage?.let { throw AssertionError("合成播放失败：$it") }
            playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
        }
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val ITEM_ID = "fixture-movie"
        const val FIXTURE_ORIGIN = "http://127.0.0.1:8097"
        fun clearSyntheticSession(context: Context) {
            SessionStore(context).apply { loadProfiles().forEach { removeProfile(it.id) }; clear() }
        }
        fun verifySyntheticFixture() {
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(5, TimeUnit.SECONDS).build()
            try {
                client.newCall(Request.Builder().url("$FIXTURE_ORIGIN/System/Info/Public").build()).execute().use { response ->
                    check(response.isSuccessful)
                    val info = JSONObject(response.body.string())
                    check(info.optBoolean("Fixture") && info.optString("Id") == "pathfin-fixture-server") { "只允许固定回环合成 fixture" }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}

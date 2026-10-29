package dev.pathfin.android

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 真实 Media3 与触摸回归，使用 fixture 自制的 15 秒视频，不代表真实 Jellyfin 验收。
 * 先启动 tools/fixture_server.py，并 adb reverse tcp:8097 tcp:8097。
 * 外层规则在 Activity 启动前校验独立验证包；只保存合成设置和固定测试账号。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackPreferencesTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val isolatedStorage = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = instrumentation.targetContext
                check(context.packageName == "dev.pathfin.android.verification") {
                    "播放器触摸测试只允许独立 verification 包"
                }
                clearTestAccount(context)
                AppPreferencesStore(context).save(TEST_PREFERENCES)
                try {
                    base.evaluate()
                } finally {
                    AppPreferencesStore(context).save(AppPreferences())
                    clearTestAccount(context)
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test
    fun serverPlaybackHonorsLongPressReleaseDoubleTapAndVisibleTimeBar() {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = compose.runOnIdle { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        try {
            loginAndOpenMovie(app)
            compose.waitUntil(WAIT_MS) {
                app.state.value.playbackError?.let { throw AssertionError("测试媒体准备失败：$it") }
                playback.state.value.errorMessage?.let { throw AssertionError("测试媒体播放失败：$it") }
                playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
            }
            val playerView = compose.runOnIdle {
                assertFalse(app.state.value.preferences.originalPathEnabled)
                assertTrue("关闭原始地址后必须通过受鉴权的服务器播放", checkNotNull(app.state.value.activePlayback).stream.trustedServer)
                val player = checkNotNull(playback.player)
                assertTrue("从头播放应应用配置的两秒片头跳过", player.currentPosition >= 2_000)
                assertTrue("fixture 视频必须具有足够时长", player.duration >= 14_000)
                player.pause()
                checkNotNull(findPlayerView(compose.activity.window.decorView)).also {
                    assertFalse("播放器由可交互的Compose控制层统一管理", it.useController)
                    assertTrue("画面必须绑定唯一播放器", it.player === player)
                }
            }

            // 用真实屏幕 DOWN 持续到系统长按回调，分别验证 UP 与 CANCEL 恢复原速。
            holdAndRelease(playback, playerView, MotionEvent.ACTION_UP)
            holdAndRelease(playback, playerView, MotionEvent.ACTION_CANCEL)

            pauseAt(playback, 8_000)
            doubleTap(videoPoint(playerView, 0.80f))
            waitForPosition(playback, 13_000)
            pauseAt(playback, 13_000)
            doubleTap(videoPoint(playerView, 0.20f))
            waitForPosition(playback, 8_000)

            // 触摸真实显示的进度条，保留经Window分发的事件，不直接调用seek方法。
            pauseAt(playback, 1_000)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("player-seek-slider")).fetchSemanticsNodes().isNotEmpty()
            }
            val bounds = compose.onNodeWithTag("player-seek-slider").readVerificationNode(compose) { it.boundsInWindow }
            tap(Point(bounds.center.x, bounds.center.y))
            compose.waitUntil(WAIT_MS) {
                compose.runOnIdle {
                    val player = checkNotNull(playback.player)
                    player.playbackState == Player.STATE_READY && player.currentPosition in 5_000L..10_000L
                }
            }
            compose.runOnIdle {
                assertFalse("拖点进度不能意外恢复播放", checkNotNull(playback.player).playWhenReady)
                assertEquals("触摸结束必须恢复长按之前的倍速", BASE_SPEED, checkNotNull(playback.player).playbackParameters.speed, 0.001f)
                assertNull(playback.state.value.temporarySpeed)
            }
        } finally {
            compose.runOnIdle {
                playback.stop()
                app.logout()
            }
        }
    }

    private fun loginAndOpenMovie(app: AppViewModel) {
        compose.connectServer("http://127.0.0.1:8097", "pathfin", "pathfin", allowHttp = true)
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.loginError?.let { throw AssertionError("fixture 登录失败：$it") }
            state.home.error?.let { throw AssertionError("fixture 媒体库读取失败：$it") }
            state.session != null && state.home.loaded && !state.home.loading
        }
        compose.onAllNodesWithText(MOVIE_NAME).onFirst().performScrollTo().performVerificationClick(compose)
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.detailError?.let { throw AssertionError("fixture 详情读取失败：$it") }
            !state.detailLoading && state.details[ITEM_ID] != null
        }
        val resumed = app.state.value.details.getValue(ITEM_ID).positionTicks > 0
        if (resumed) compose.onNodeWithContentDescription("播放选项").performScrollTo().performVerificationClick(compose)
        compose.onNodeWithText(if (resumed) "从头播放" else "播放").performScrollTo().performVerificationClick(compose)
    }

    private fun holdAndRelease(playback: PlaybackViewModel, view: PlayerView, release: Int) {
        finishTapWindow()
        compose.runOnIdle {
            checkNotNull(playback.player).apply {
                setPlaybackSpeed(BASE_SPEED)
                seekTo(500)
                play()
            }
        }
        compose.waitUntil(WAIT_MS) {
            compose.runOnIdle { playback.player?.isPlaying == true && checkNotNull(playback.player).currentPosition < 5_000 }
        }
        val point = videoPoint(view, 0.50f)
        val downTime = SystemClock.uptimeMillis()
        sendTouch(MotionEvent.ACTION_DOWN, point, downTime)
        try {
            compose.waitUntil(3_000) {
                compose.runOnIdle {
                    playback.state.value.temporarySpeed == TEMP_SPEED * BASE_SPEED &&
                        playback.player?.playbackParameters?.speed == TEMP_SPEED * BASE_SPEED
                }
            }
            compose.runOnIdle {
                assertEquals("实际长按必须改变 ExoPlayer 播放速度", TEMP_SPEED * BASE_SPEED, checkNotNull(playback.player).playbackParameters.speed, 0.001f)
            }
        } finally {
            sendTouch(release, point, downTime)
        }
        compose.waitUntil(WAIT_MS) {
            compose.runOnIdle {
                playback.state.value.temporarySpeed == null && playback.player?.playbackParameters?.speed == BASE_SPEED
            }
        }
        compose.runOnIdle {
            assertEquals("${if (release == MotionEvent.ACTION_UP) "松手" else "取消"}必须恢复之前的速度", BASE_SPEED, checkNotNull(playback.player).playbackParameters.speed, 0.001f)
            checkNotNull(playback.player).pause()
        }
    }

    private fun pauseAt(playback: PlaybackViewModel, position: Long) {
        finishTapWindow()
        compose.runOnIdle {
            checkNotNull(playback.player).apply {
                pause()
                seekTo(position)
            }
        }
        waitForPosition(playback, position)
    }

    private fun waitForPosition(playback: PlaybackViewModel, expected: Long) {
        compose.waitUntil(WAIT_MS) {
            playback.state.value.errorMessage?.let { throw AssertionError("测试媒体播放失败：$it") }
            compose.runOnIdle {
                val player = checkNotNull(playback.player)
                player.playbackState == Player.STATE_READY && !player.playWhenReady &&
                    player.currentPosition in (expected - 350)..(expected + 350)
            }
        }
    }

    private fun videoPoint(view: PlayerView, fraction: Float): Point = compose.runOnIdle {
        view.hideController()
        val rect = Rect()
        assertTrue("PlayerView 必须在屏幕内", view.getGlobalVisibleRect(rect))
        assertTrue(rect.height() > 200 && rect.width() > 200)
        // 上方 35% 的画面避开顶栏、中央按钮与底部时间条。
        Point(rect.left + rect.width() * fraction, rect.top + rect.height() * 0.35f)
    }

    private fun doubleTap(point: Point) {
        tap(point)
        SystemClock.sleep(60)
        tap(point)
    }

    private fun tap(point: Point) {
        val downTime = SystemClock.uptimeMillis()
        sendTouch(MotionEvent.ACTION_DOWN, point, downTime)
        SystemClock.sleep(30)
        sendTouch(MotionEvent.ACTION_UP, point, downTime)
    }

    private fun sendTouch(action: Int, point: Point, downTime: Long) {
        // 从应用窗口分发触摸，完整经过 Compose/AndroidView/原生控制器，避免系统注入权限差异。
        // 不直接调用手势处理或播放器操作方法。
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            compose.runOnUiThread {
                val origin = IntArray(2)
                compose.activity.window.decorView.getLocationOnScreen(origin)
                event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                val handled = compose.activity.dispatchTouchEvent(event)
                // CANCEL 的返回值不表示是否执行清理，恢复速度由下方状态断言验证。
                if (action == MotionEvent.ACTION_DOWN) assertTrue("窗口必须接收触摸起点", handled)
            }
        } finally { event.recycle() }
    }

    private fun finishTapWindow() {
        // 使用系统双击阈值，避免两次独立手势被 GestureDetector 识别成同一组点击。
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 60)
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun clearTestAccount(context: Context) {
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private data class Point(val x: Float, val y: Float)

    private companion object {
        const val WAIT_MS = 20_000L
        const val ITEM_ID = "fixture-movie"
        const val MOVIE_NAME = "Pathfin · 原始路径播放测试"
        const val TEMP_SPEED = 3f
        const val BASE_SPEED = 1.25f
        val TEST_PREFERENCES = AppPreferences(
            originalPathEnabled = false,
            longPressSpeedEnabled = true,
            longPressSpeed = TEMP_SPEED,
            seekSeconds = 5,
            introSkipSeconds = 2,
            preferredAudioLanguage = "en",
            preferredSubtitleLanguage = "zh",
            subtitlesEnabled = false,
            bufferPreset = dev.pathfin.android.domain.BufferPreset.LARGE,
        )
    }
}

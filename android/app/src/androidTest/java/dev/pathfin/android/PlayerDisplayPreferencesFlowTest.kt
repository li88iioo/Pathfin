package dev.pathfin.android

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.ui.player.PlayerDisplayPreferencesEffect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真 Activity 窗口与真实 ExoPlayer；只验证设备实际提供的模式，不把 60Hz 模拟器当作 120Hz 面板。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerDisplayPreferencesFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun activeLockRequestsARealSameResolutionModeAndRestoresOnDisableExitAndDisposal() {
        verificationOnly()
        val active = mutableStateOf(false)
        val enabled = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        lateinit var player: ExoPlayer
        var savedMode = 0
        var savedRefresh = 0f
        var expectedModeId = 0
        compose.runOnUiThread {
            val window = compose.activity.window
            savedMode = window.attributes.preferredDisplayModeId
            savedRefresh = window.attributes.preferredRefreshRate
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            expectedModeId = expectedMode()
            player = ExoPlayer.Builder(compose.activity)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS).build()
        }
        try {
            compose.setContent {
                if (mounted.value) PlayerDisplayPreferencesEffect(player, active.value, enabled.value)
                Box { }
            }
            assertState(0, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, player, savedRefresh)
            compose.runOnIdle { enabled.value = true }
            assertState(0, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, player, savedRefresh)
            compose.runOnIdle { active.value = true }
            assertState(expectedModeId, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player, savedRefresh)
            assertTrue("真实模式 id 应从无偏好 0 发生改变", expectedModeId > 0)

            compose.runOnIdle { enabled.value = false }
            assertState(0, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, player, savedRefresh)
            compose.runOnIdle { enabled.value = true }
            assertState(expectedModeId, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player, savedRefresh)
            compose.runOnIdle { active.value = false }
            assertState(0, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, player, savedRefresh)
            compose.runOnIdle { active.value = true }
            assertState(expectedModeId, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player, savedRefresh)
            compose.runOnIdle { mounted.value = false }
            assertState(0, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, player, savedRefresh)
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                if (!player.isReleased) player.release()
                compose.activity.window.attributes = compose.activity.window.attributes.apply { preferredDisplayModeId = savedMode }
            }
        }
    }

    @Test fun replacingAndReleasingAPlayerRestoresOnlyItsOwnStrategyAndKeepsTheWindowLease() {
        verificationOnly()
        lateinit var first: ExoPlayer
        lateinit var second: ExoPlayer
        val player = mutableStateOf<Player?>(null)
        val mounted = mutableStateOf(true)
        var savedMode = 0
        var baselineMode = 0
        var targetMode = 0
        compose.runOnUiThread {
            val window = compose.activity.window
            savedMode = window.attributes.preferredDisplayModeId
            baselineMode = checkNotNull(window.decorView.display).mode.modeId
            window.attributes = window.attributes.apply { preferredDisplayModeId = baselineMode }
            targetMode = expectedMode()
            first = ExoPlayer.Builder(compose.activity)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS).build()
            second = ExoPlayer.Builder(compose.activity)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
            player.value = first
        }
        try {
            compose.setContent {
                if (mounted.value) PlayerDisplayPreferencesEffect(player.value, active = true, lockHighRefreshRate = true)
                Box { }
            }
            compose.runOnIdle {
                assertEquals(targetMode, compose.activity.window.attributes.preferredDisplayModeId)
                assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, first.videoChangeFrameRateStrategy)
                player.value = second
            }
            compose.runOnIdle {
                assertEquals("旧内核只恢复自己的策略", C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, first.videoChangeFrameRateStrategy)
                assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, second.videoChangeFrameRateStrategy)
                assertEquals("换集不得撤销同一窗口的请求", targetMode, compose.activity.window.attributes.preferredDisplayModeId)
                first.release()
                // 先释放当前 Player 再销毁组合，必须仍能恢复窗口且不访问已释放 Player 的其他 API。
                second.release()
                mounted.value = false
            }
            compose.runOnIdle {
                assertEquals("原窗口明确设置的非零模式也须还原", baselineMode, compose.activity.window.attributes.preferredDisplayModeId)
            }
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                if (!first.isReleased) first.release()
                if (!second.isReleased) second.release()
                compose.activity.window.attributes = compose.activity.window.attributes.apply { preferredDisplayModeId = savedMode }
            }
        }
    }

    @Test fun aGenericMedia3PlayerUsesOnlyTheWindowAndLeavesExoSpecificPolicyUntouched() {
        verificationOnly()
        lateinit var exo: ExoPlayer
        lateinit var generic: Player
        val enabled = mutableStateOf(true)
        var savedMode = 0
        var targetMode = 0
        compose.runOnUiThread {
            val window = compose.activity.window
            savedMode = window.attributes.preferredDisplayModeId
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            targetMode = expectedMode()
            exo = ExoPlayer.Builder(compose.activity)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS).build()
            generic = ForwardingPlayer(exo)
        }
        try {
            compose.setContent { PlayerDisplayPreferencesEffect(generic, active = true, lockHighRefreshRate = enabled.value) }
            compose.runOnIdle {
                assertEquals(targetMode, compose.activity.window.attributes.preferredDisplayModeId)
                assertEquals("非 Exo 内核不调用猜测的专有策略", C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, exo.videoChangeFrameRateStrategy)
                enabled.value = false
            }
            compose.runOnIdle {
                assertEquals(0, compose.activity.window.attributes.preferredDisplayModeId)
                assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, exo.videoChangeFrameRateStrategy)
            }
        } finally {
            compose.runOnIdle { enabled.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                if (!exo.isReleased) exo.release()
                compose.activity.window.attributes = compose.activity.window.attributes.apply { preferredDisplayModeId = savedMode }
            }
        }
    }

    private fun assertState(modeId: Int, strategy: Int, player: ExoPlayer, originalPreferredRefresh: Float) = compose.runOnIdle {
        val attributes = compose.activity.window.attributes
        assertEquals("真实当前 Activity 的窗口请求", modeId, attributes.preferredDisplayModeId)
        assertEquals(strategy, player.videoChangeFrameRateStrategy)
        assertEquals("不能顺带改 preferredRefreshRate", originalPreferredRefresh, attributes.preferredRefreshRate, 0f)
    }

    private fun expectedMode(): Int {
        val display = checkNotNull(compose.activity.window.decorView.display)
        val current = display.mode
        val compatible = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                it.modeId > 0 && it.refreshRate.isFinite() && it.refreshRate >= current.refreshRate
        }
        assertTrue("系统必须明确提供当前物理分辨率的有效模式", compatible.isNotEmpty())
        val maximum = compatible.maxOf { it.refreshRate }
        val fastest = compatible.filter { it.refreshRate == maximum }
        val selected = fastest.firstOrNull { it.modeId == current.modeId } ?: fastest.minBy { it.modeId }
        Log.i("PathfinDisplayTest", "physical=${current.physicalWidth}x${current.physicalHeight}, actualHz=${current.refreshRate}, maxSupportedHz=$maximum, requestedModeId=${selected.modeId}")
        return selected.modeId
    }

    private fun verificationOnly() = check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
}

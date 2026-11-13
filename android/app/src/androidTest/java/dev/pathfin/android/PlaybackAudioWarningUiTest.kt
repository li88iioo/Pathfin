package dev.pathfin.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.playback.PlaybackUiState
import dev.pathfin.android.ui.player.AudioOnlyCover
import dev.pathfin.android.ui.player.PlaybackPrompts
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackAudioWarningUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun audioOnlyCoverTracksSelectionEventsWithoutPollingPlaybackPosition() {
        lateinit var player: WarningPlayer
        lateinit var observed: PositionReadProbe
        compose.runOnUiThread { player = WarningPlayer(); observed = PositionReadProbe(player) }
        try {
            compose.setContent { PathfinTheme(ThemePreference.DARK) { AudioOnlyCover(observed) } }
            compose.onNodeWithTag("player-audio-only-cover").assertDoesNotExist()
            assertPositionReadsStayIdle(observed)
            compose.runOnIdle { player.setAudioOnly(true) }
            compose.onNodeWithText("仅播放音频").assertExists()
            assertPositionReadsStayIdle(observed)
            compose.runOnIdle { player.setAudioOnly(false) }
            compose.onNodeWithTag("player-audio-only-cover").assertDoesNotExist()
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun inactiveNextEpisodePromptsDoNotPollButEligibleCountdownStillUpdates() {
        lateinit var player: WarningPlayer
        lateinit var observed: PositionReadProbe
        val state = mutableStateOf(PlaybackUiState())
        val hasNext = mutableStateOf(false)
        compose.runOnUiThread { player = WarningPlayer(positionMs = 115_000); observed = PositionReadProbe(player) }
        try {
            compose.setContent { PathfinTheme(ThemePreference.DARK) {
                PlaybackPrompts(observed, state.value, visible = true, showStatus = false,
                    onSkipIntro = {}, onDismissIntro = {}, onRetrySegments = {}, onDismissSegmentsError = {},
                    onNext = if (hasNext.value) ({}) else null)
            } }
            assertPositionReadsStayIdle(observed)
            compose.runOnIdle { hasNext.value = true; state.value = state.value.copy(live = true) }
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
            assertPositionReadsStayIdle(observed)
            compose.runOnIdle { state.value = state.value.copy(live = false, nextEpisodePromptSeconds = 0) }
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
            assertPositionReadsStayIdle(observed)
            compose.runOnIdle { state.value = state.value.copy(nextEpisodePromptSeconds = 30) }
            compose.onNodeWithTag("player-next-prompt").assertExists()
            val before = compose.runOnIdle { observed.positionReads.get() }
            compose.mainClock.advanceTimeBy(750)
            compose.runOnIdle { assertTrue("需要下一集倒计时时应继续采样位置", observed.positionReads.get() > before) }
            compose.onNodeWithTag("player-next-prompt-dismiss").performVerificationClick(compose)
            compose.onNodeWithTag("player-next-prompt").assertDoesNotExist()
            assertPositionReadsStayIdle(observed)
            compose.runOnIdle { state.value = state.value.copy(playerGeneration = 1) }
            compose.onNodeWithTag("player-next-prompt").assertExists()
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun actualDecodeWarningIsVisibleWithoutControlsAndDismissalSurvivesOrdinaryStateUpdates() {
        lateinit var player: WarningPlayer
        val state = mutableStateOf(PlaybackUiState())
        compose.runOnUiThread { player = WarningPlayer() }
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.DARK) {
                    PlaybackPrompts(player, state.value, visible = true, showStatus = false,
                        onSkipIntro = {}, onDismissIntro = {}, onRetrySegments = {}, onDismissSegmentsError = {}, onNext = null)
                }
            }
            compose.onNodeWithTag("player-decode-warning").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(decodeWarning = "当前 DTS 音轨无法解码，请切换音轨或播放器") }
            compose.onNodeWithText("当前 DTS 音轨无法解码，请切换音轨或播放器").assertExists()
            compose.onNodeWithTag("player-decode-warning-dismiss").performVerificationClick(compose)
            compose.runOnIdle { state.value = state.value.copy(isBuffering = true) }
            compose.onNodeWithTag("player-decode-warning").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(playerGeneration = 1) }
            compose.onNodeWithTag("player-decode-warning").assertExists()
            compose.runOnIdle { state.value = state.value.copy(decodeWarning = null) }
            compose.onNodeWithTag("player-decode-warning").assertDoesNotExist()
        } finally { compose.runOnUiThread { player.release() } }
    }

    private fun assertPositionReadsStayIdle(player: PositionReadProbe) {
        val before = compose.runOnIdle { player.positionReads.get() }
        // LaunchedEffect的delay沿Compose测试时钟推进，跨过三个原250ms采样周期。
        compose.mainClock.advanceTimeBy(750)
        compose.runOnIdle { assertEquals("不需要倒计时的覆盖层不能常驻读取播放位置", before, player.positionReads.get()) }
    }

    private class PositionReadProbe(player: Player) : ForwardingPlayer(player) {
        val positionReads = AtomicInteger()
        override fun getCurrentPosition(): Long {
            positionReads.incrementAndGet()
            return super.getCurrentPosition()
        }
    }

    private class WarningPlayer(private val positionMs: Long = 1_000) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var parameters = TrackSelectionParameters.DEFAULT
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TRACKS).build())
            .setPlaylist(listOf(MediaItemData.Builder("warning-fixture").setDurationUs(120_000_000).build()))
            .setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(positionMs).setTrackSelectionParameters(parameters).build()
        fun setAudioOnly(enabled: Boolean) {
            parameters = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled).build()
            invalidateState()
        }
    }
}

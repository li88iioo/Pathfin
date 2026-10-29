package dev.pathfin.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.playback.PlaybackUiState
import dev.pathfin.android.ui.player.PlaybackPrompts
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackAudioWarningUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

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
            compose.onNodeWithTag("player-decode-warning-dismiss").performClick()
            compose.runOnIdle { state.value = state.value.copy(isBuffering = true) }
            compose.onNodeWithTag("player-decode-warning").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(playerGeneration = 1) }
            compose.onNodeWithTag("player-decode-warning").assertExists()
            compose.runOnIdle { state.value = state.value.copy(decodeWarning = null) }
            compose.onNodeWithTag("player-decode-warning").assertDoesNotExist()
        } finally { compose.runOnUiThread { player.release() } }
    }

    private class WarningPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TRACKS).build())
            .setPlaylist(listOf(MediaItemData.Builder("warning-fixture").setDurationUs(120_000_000).build()))
            .setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(1_000).build()
    }
}

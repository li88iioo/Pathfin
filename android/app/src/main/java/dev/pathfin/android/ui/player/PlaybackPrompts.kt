package dev.pathfin.android.ui.player

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.media3.common.C
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.PathfinIcons
import dev.pathfin.android.ui.AppSafeDrawingInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.domain.nextEpisodePromptRemainingSeconds
import dev.pathfin.android.playback.PlaybackUiState
import dev.pathfin.android.playback.pathfinReleased

/** 独立覆盖层只订阅提示需要的位置，不使视频/手势父布局每 250ms 重组。 */
@UnstableApi
@Composable
internal fun PlaybackPrompts(
    player: Player?,
    state: PlaybackUiState,
    visible: Boolean,
    showStatus: Boolean,
    onSkipIntro: () -> Unit,
    onDismissIntro: () -> Unit,
    onRetrySegments: () -> Unit,
    onDismissSegmentsError: () -> Unit,
    onNext: (() -> Unit)?,
) {
    // 始终保留此槽位：锁屏、PiP 或工具页短暂隐藏不能重置本集的关闭选择。
    var nextDismissed by rememberSaveable(state.playerGeneration) { mutableStateOf(false) }
    var decodeWarningDismissed by rememberSaveable(state.playerGeneration, state.decodeWarning) { mutableStateOf(false) }
    if (!visible || player == null || player.pathfinReleased()) return
    val snapshot = rememberPlayerControlSnapshot(player)
    val nextSeconds = if (nextDismissed) null else nextEpisodePromptRemainingSeconds(
        snapshot.positionMs, snapshot.durationMs, state.nextEpisodePromptSeconds, onNext != null, player.isCurrentMediaItemLive,
    )
    val error = state.mediaSegmentsError?.takeIf { showStatus && state.introPrompt == null }
    val decodeWarning = state.decodeWarning?.takeUnless { decodeWarningDismissed }
    if (state.introPrompt == null && nextSeconds == null && error == null && decodeWarning == null) return
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets)) {
        // 固定预留底部传输控制区；加载/进度变化不会推动按钮。
        val landscape = maxWidth >= 600.dp && maxHeight < 500.dp
        // 控制层显示时避开底栏，横屏再让出右侧倍速竖列；控制层隐藏时下沉到靠近底边。
        val endInset = if (landscape && showStatus) 72.dp else 16.dp
        val bottomInset = when { !showStatus -> 28.dp; landscape -> 120.dp; else -> 176.dp }
        Column(Modifier.align(Alignment.BottomEnd).padding(start = 16.dp, end = endInset).padding(bottom = bottomInset),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.introPrompt?.let {
                PromptPill("player-intro-prompt", "player-intro-dismiss", tr("关闭片头提示"), onDismissIntro) {
                    PromptButton("player-skip-intro", onSkipIntro) {
                        Icon(PathfinIcons.SkipNext, null, Modifier.size(18.dp), tint = PlayerText)
                        Text(tr("跳过片头"), color = PlayerText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            nextSeconds?.let { seconds ->
                // 胶囊背景随本集剩余时间从左向右填充强调色，越接近结尾越满。
                val total = state.nextEpisodePromptSeconds.coerceAtLeast(1)
                val progress = (1f - seconds / total.toFloat()).coerceIn(0f, 1f)
                PromptPill("player-next-prompt", "player-next-prompt-dismiss", tr("关闭下一集提示"), { nextDismissed = true }) {
                    PromptButton("player-next-prompt-action", { nextDismissed = true; onNext?.invoke() }, progress) {
                        Text(tr("下一集"), color = PlayerText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(tr("本集剩余 %1\$s 秒", seconds), Modifier.testTag("player-next-prompt-time"), color = PlayerSecondary, fontSize = 13.sp, maxLines = 1)
                        Icon(PathfinIcons.SkipNext, null, Modifier.size(18.dp), tint = PlayerText)
                    }
                }
            }
            decodeWarning?.let { message ->
                PromptPill("player-decode-warning", "player-decode-warning-dismiss", tr("关闭解码提示"), { decodeWarningDismissed = true }) {
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(PlayerSurface)
                        .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(message, Modifier.widthIn(max = 280.dp), color = PlayerText, fontSize = 13.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let { message ->
                PromptPill("player-segments-error", "player-segments-dismiss", tr("关闭片头错误提示"), onDismissSegmentsError) {
                    PromptButton("player-segments-retry", onRetrySegments) {
                        Text(message, Modifier.widthIn(max = 220.dp), color = PlayerSecondary, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(tr("重试"), color = playerAccent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 一组提示：左侧小圆钮关闭，右侧主胶囊；整组靠右下对齐，不铺满宽度。 */
@Composable
private fun PromptPill(tag: String, dismissTag: String, dismissLabel: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(PlayerSurface).border(1.dp, Color.White.copy(alpha = .14f), CircleShape)
            .clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = dismissLabel }.testTag(dismissTag),
            contentAlignment = Alignment.Center) {
            Icon(PathfinIcons.Close, null, Modifier.size(16.dp), tint = PlayerSecondary)
        }
        content()
    }
}

/** 主胶囊：深色半透明底、细白边；progress 大于 0 时以强调色从左侧填充。 */
@Composable
private fun PromptButton(tag: String, onClick: () -> Unit, progress: Float = 0f, content: @Composable RowScope.() -> Unit) {
    val accent = playerAccent()
    Box(Modifier.height(48.dp).clip(CircleShape).background(PlayerSurface).border(1.dp, Color.White.copy(alpha = .18f), CircleShape)
        .clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        if (progress > 0f) Box(Modifier.matchParentSize().drawBehind {
            drawRect(accent.copy(alpha = .38f), size = size.copy(width = size.width * progress))
        })
        Row(Modifier.fillMaxHeight().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** 仅播放音频时盖住停在最后一帧的画面：黑底、居中音频图标与一行说明；不拦截手势，控制栏照常可用。 */
@UnstableApi
@Composable
internal fun AudioOnlyCover(player: Player?) {
    if (player == null || player.pathfinReleased()) return
    val snapshot = rememberPlayerControlSnapshot(player)
    if (C.TRACK_TYPE_VIDEO !in snapshot.selection.disabledTrackTypes) return
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player-audio-only-cover"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PathfinIcons.Audio, null, Modifier.size(40.dp), tint = PlayerSecondary)
            Text(tr("仅播放音频"), color = PlayerText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(tr("在“更多”里可恢复画面"), color = PlayerSecondary, fontSize = 12.5.sp)
        }
    }
}

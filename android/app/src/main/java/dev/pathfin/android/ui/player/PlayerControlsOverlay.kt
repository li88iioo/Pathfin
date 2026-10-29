package dev.pathfin.android.ui.player

import androidx.compose.ui.platform.LocalWindowInfo
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.PathfinSpinner
import dev.pathfin.android.ui.PathfinAppearance
import dev.pathfin.android.ui.MediaNavigationIcons
import dev.pathfin.android.ui.PathfinIcons
import dev.pathfin.android.ui.LocalPosterLoader
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.pathfin.android.ui.AppSafeDrawingInsets
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.pathfin.android.playback.PlaybackUiState
import dev.pathfin.android.playback.RapidSeekThrottle
import dev.pathfin.android.playback.SubtitlePresentation
import dev.pathfin.android.playback.formatPlaybackTransferRate
import dev.pathfin.android.playback.pathfinReleased

/** 暗角独立于按钮与面板，打开侧栏时保留视频已有的明暗关系。 */
@Composable
internal fun PlayerControlsBackdrop() {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = with(LocalDensity.current) { windowSize.width.toDp() >= 600.dp && windowSize.height.toDp() < 500.dp }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (landscape) 130.dp else 170.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .62f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (landscape) 180.dp else 250.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))))
    }
}

/** 所有交互处于视频之上的同一控制层；透明按钮不向视频手势层透传触摸。 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerControlsOverlay(
    player: Player?,
    state: PlaybackUiState,
    showLoading: Boolean,
    resizeMode: PlayerResizeMode,
    seekSeconds: Int,
    clock: String?,
    battery: String?,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onKeepVisibleChange: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onResizeModeChange: (PlayerResizeMode) -> Unit,
    onLock: () -> Unit,
    onShowQuality: () -> Unit,
    onEnterPictureInPicture: (() -> Unit)? = null,
    onToggleOrientation: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onShowEpisodes: (() -> Unit)? = null,
    onShowChapters: (() -> Unit)? = null,
    onScreenshot: (() -> Unit)? = null,
    onManualTrackSelection: (Player, Int, Format?, Boolean) -> Unit = { _, _, _, _ -> },
    onSetSleepTimer: (Int?) -> Unit = {},
    sleepTimerLabel: String? = null,
    audioGainPercent: Int = 100,
    onAudioGainChange: (Int) -> Unit = {},
    onAddSubtitle: (() -> Unit)? = null,
    onManageSubtitles: (() -> Unit)? = null,
    subtitlePresentation: SubtitlePresentation = SubtitlePresentation(),
    onSubtitlePresentationChange: (SubtitlePresentation) -> Unit = {},
    titleLogo: ImageRequest? = null,
) {
    val snapshot = rememberPlayerControlSnapshot(player)
    var panel by remember(player) { mutableStateOf<PlayerControlPanel?>(null) }
    var scrubbing by remember(player) { mutableStateOf(false) }
    var scrubPosition by remember(player) { mutableFloatStateOf(0f) }
    val rapidSeek = remember(player) { RapidSeekThrottle() }
    val sliderInteraction = remember { MutableInteractionSource() }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    DisposableEffect(panel != null || scrubbing) {
        onKeepVisibleChange(panel != null || scrubbing)
        onDispose { onKeepVisibleChange(false) }
    }
    val interaction: (() -> Unit) -> Unit = { action -> onInteraction(); action() }
    val showPanel: (PlayerControlPanel) -> Unit = { target ->
        onInteraction()
        panel = target
    }
    val hasAudio = snapshot.tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
    val showSubtitles = !state.subtitlesBurnedIn
    val transportEnabled = player != null && !player.pathfinReleased() && state.errorMessage == null && !state.ended
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val headerTitle = snapshot.displayTitle.ifBlank { state.title }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets)) {
        val availableWidth = maxWidth
        val landscape = availableWidth >= 600.dp && maxHeight < 500.dp
        // 影院式控制层：画面中部保持通透，只在顶部与底部压两道暗角托住白色细线图标。
        // 打开任何侧面板时控制层整体隐藏，面板下面不透出按钮。
        if (panel == null) Box(Modifier.fillMaxSize().testTag("player-controls")) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PlayerRoundButton(PathfinIcons.Back, tr("返回"), "player-back", onClick = { interaction(onBack) })
                Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (landscape) PlayerHeaderTitle(headerTitle, titleLogo)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(if (availableWidth < 380.dp) 0.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (onEnterPictureInPicture != null) PlayerRoundButton(PathfinIcons.PictureInPicture, tr("画中画"), "player-pip",
                        onClick = { interaction(onEnterPictureInPicture) })
                    PlayerRoundButton(PathfinIcons.AspectRatio, tr("画面比例"), "player-aspect",
                        onClick = { showPanel(PlayerControlPanel.ASPECT) })
                    if (onToggleOrientation != null) PlayerRoundButton(if (landscape) PathfinIcons.RotateToPortrait else PathfinIcons.RotateToLandscape,
                        if (landscape) tr("切换到竖屏") else tr("切换到横屏"), "player-orientation",
                        onClick = { interaction(onToggleOrientation) })
                    if (onShowChapters != null) PlayerRoundButton(PathfinIcons.List, tr("章节"), "player-chapters",
                        onClick = { interaction(onShowChapters) })
                    PlayerRoundButton(MediaNavigationIcons.More, tr("更多"), "player-more",
                        onClick = { showPanel(PlayerControlPanel.MORE) })
                }
            }
            // 时间、电量、网速统一放在右上工具栏正下方，横竖屏同一位置。
            Box(Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 60.dp)) {
                PlayerStatusLine(clock, battery, state)
            }
            if (state.errorMessage == null && !state.ended) {
                val sideSize = if (availableWidth < 360.dp || largeText) 48.dp else 56.dp
                val transportWidth = if (largeText) 174.dp else (availableWidth * .56f).coerceIn(200.dp, 280.dp)
                Row(Modifier.align(Alignment.Center).offset(x = if (largeText) (-12).dp else 0.dp)
                    .width(transportWidth).testTag("player-transport"), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerRoundButton(PathfinIcons.SeekBack, tr("快退 %1\$s 秒", seekSeconds), "player-seek-back", size = sideSize,
                        enabled = transportEnabled && snapshot.canSeekBack, onClick = { interaction(onSeekBack) })
                    // 唯一带底的按钮：淡磨砂圆托住实心三角，是整层的视觉重心。
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))
                        .clickable(enabled = transportEnabled, role = Role.Button) { interaction(onTogglePlay) }
                        .semantics { contentDescription = if (snapshot.playWhenReady) tr("暂停") else tr("播放") }
                        .testTag("player-play-pause"), contentAlignment = Alignment.Center) {
                        if (showLoading) PathfinSpinner(Color.White, Modifier.size(30.dp))
                        else Icon(if (snapshot.playWhenReady) PathfinIcons.PauseGlyph else PathfinIcons.PlayGlyph, null,
                            Modifier.size(34.dp), tint = if (transportEnabled) PlayerText else PlayerDisabled)
                    }
                    PlayerRoundButton(PathfinIcons.SeekForward, tr("快进 %1\$s 秒", seekSeconds), "player-seek-forward", size = sideSize,
                        enabled = transportEnabled && snapshot.canSeekForward, onClick = { interaction(onSeekForward) })
                }
                if (landscape) Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerRoundButton(PathfinIcons.Camera, tr("保存视频截图"), "player-capture",
                        enabled = onScreenshot != null, onClick = { onScreenshot?.let { interaction(it) } })
                    PlayerRoundButton(PathfinIcons.LockOpen, tr("锁定屏幕"), "player-lock", onClick = { interaction(onLock) })
                }
            }
            val speedVisible = state.errorMessage == null && !state.ended
            val speedEnabled = transportEnabled && snapshot.canChangeSpeed
            val speedWidth = if (largeText) 72.dp else 48.dp
            val bottomToolsWidth = if (landscape) { if (showSubtitles) 104.dp else 52.dp } else 0.dp
            val timeLayout = rememberPlayerTimeLayout(snapshot.durationMs, (availableWidth * .45f - bottomToolsWidth).coerceAtLeast(80.dp))
            // 竖屏横排在底栏；横屏竖排在画面右侧中部，与左侧截图 / 锁定对称，底栏只留音轨与字幕。
            val speedLabel: @Composable () -> Unit = {
                    Box(Modifier.height(48.dp).width(speedWidth).clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = speedEnabled, role = Role.Button) { showPanel(PlayerControlPanel.SPEED) }
                        .testTag("player-speed")
                        .semantics { contentDescription = tr("播放速度 %1\$s", playerSpeed(snapshot.speed)) },
                        contentAlignment = Alignment.Center) {
                        Text(playerSpeed(snapshot.speed), color = if (speedEnabled) PlayerText else PlayerDisabled,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
            }
            val speedControls: @Composable () -> Unit = {
                if (speedVisible) Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeedStep(increase = false, enabled = speedEnabled, speed = snapshot.speed, interaction = interaction, onChange = onSpeedChange)
                    speedLabel()
                    SpeedStep(increase = true, enabled = speedEnabled, speed = snapshot.speed, interaction = interaction, onChange = onSpeedChange)
                }
            }
            if (landscape && speedVisible) Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                SpeedStep(increase = true, enabled = speedEnabled, speed = snapshot.speed, interaction = interaction, onChange = onSpeedChange)
                speedLabel()
                SpeedStep(increase = false, enabled = speedEnabled, speed = snapshot.speed, interaction = interaction, onChange = onSpeedChange)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)
                .testTag("player-bottom-controls")) {
                if (!landscape || state.title != headerTitle || onShowEpisodes != null || onPrevious != null || onNext != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onShowEpisodes != null || onPrevious != null || onNext != null) {
                            PlayerRoundButton(PathfinIcons.SkipPrevious, tr("上一集"), "player-previous", enabled = onPrevious != null,
                                onClick = { onPrevious?.let { interaction(it) } })
                            PlayerRoundButton(PathfinIcons.SkipNext, tr("下一集"), "player-next", enabled = onNext != null,
                                onClick = { onNext?.let { interaction(it) } })
                            Spacer(Modifier.width(8.dp))
                        }
                        // 标题即选集入口：有选集时右侧带一个小箭头提示可点。
                        Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = onShowEpisodes != null) { onShowEpisodes?.let { interaction(it) } }
                            .padding(horizontal = 4.dp).testTag("player-title"), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.title, Modifier.weight(1f, fill = false), color = PlayerText, fontSize = 16.sp,
                                lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (onShowEpisodes != null) Icon(PathfinIcons.ChevronDown, tr("选集"), Modifier.size(16.dp), tint = PlayerSecondary)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    val displayedPosition = if (scrubbing) scrubPosition.toLong() else snapshot.positionMs
                    Text("${playerTime(displayedPosition)} / ${if (snapshot.durationMs > 0) playerTime(snapshot.durationMs) else "--:--"}",
                        Modifier.width(timeLayout.width).testTag("player-time"), color = PlayerText, style = timeLayout.style,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    val sliderEnabled = transportEnabled && snapshot.canSeek
                    val progressColor = PathfinAppearance.accent
                    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = progressColor,
                        inactiveTrackColor = Color.White.copy(alpha = .2f))
                    Slider(value = if (scrubbing) scrubPosition else snapshot.positionMs.coerceAtMost(snapshot.durationMs.coerceAtLeast(1)).toFloat(),
                        onValueChange = { position ->
                            onInteraction()
                            if (!scrubbing) rapidSeek.reset()
                            scrubbing = true
                            scrubPosition = position
                            if (state.rapidSeekWhileDragging && player is ExoPlayer && sliderEnabled &&
                                rapidSeek.shouldSeek(position.toLong(), SystemClock.elapsedRealtime())) onSeekTo(position.toLong())
                        },
                        onValueChangeFinished = {
                            if (scrubbing) onSeekTo(scrubPosition.toLong())
                            scrubbing = false
                            rapidSeek.reset()
                            onInteraction()
                        }, valueRange = 0f..snapshot.durationMs.coerceAtLeast(1).toFloat(), enabled = sliderEnabled,
                        colors = colors, interactionSource = sliderInteraction,
                        // 细轨道 + 小圆点：拖动时圆点放大，静止时几乎不打扰画面。
                        thumb = {
                            val dragging by sliderInteraction.collectIsDraggedAsState()
                            Box(Modifier.size(if (dragging || scrubbing) 16.dp else 12.dp).clip(CircleShape)
                                .background(if (sliderEnabled) Color.White else PlayerDisabled))
                        },
                        track = { slider ->
                            Canvas(Modifier.fillMaxWidth().height(32.dp).testTag("player-progress-track")) {
                                val maximum = snapshot.durationMs.coerceAtLeast(1).toFloat()
                                val thickness = 3.dp.toPx()
                                val top = (size.height - thickness) / 2
                                fun segment(color: Color, fraction: Float) {
                                    val width = size.width * fraction.coerceIn(0f, 1f)
                                    drawRoundRect(color, Offset(if (rightToLeft) size.width - width else 0f, top), Size(width, thickness), CornerRadius(thickness / 2))
                                }
                                segment(Color.White.copy(alpha = .2f), 1f)
                                segment(Color.White.copy(alpha = .36f), snapshot.bufferedPositionMs / maximum)
                                segment(if (sliderEnabled) progressColor else Color.White.copy(alpha = .35f), slider.value / maximum)
                            }
                        }, modifier = Modifier.weight(1f).height(48.dp).testTag("player-seek-slider").semantics { contentDescription = tr("播放进度") })
                    if (landscape) {
                        Spacer(Modifier.width(8.dp))
                        TrackButtons(hasAudio, showSubtitles, interaction, { showPanel(PlayerControlPanel.AUDIO) },
                            { showPanel(PlayerControlPanel.SUBTITLES) })
                    }
                }
                if (!landscape) Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerRoundButton(PathfinIcons.LockOpen, tr("锁定屏幕"), "player-lock", onClick = { interaction(onLock) })
                    speedControls()
                    TrackButtons(hasAudio, showSubtitles, interaction, { showPanel(PlayerControlPanel.AUDIO) },
                        { showPanel(PlayerControlPanel.SUBTITLES) })
                }
            }
        }
        panel?.let { selected ->
            PlayerControlPopup(selected, player, snapshot, resizeMode, state.sourceLabel,
                onDismiss = { panel = null; onInteraction() },
                onSpeedChange = onSpeedChange, onResizeModeChange = onResizeModeChange,
                onPanelChange = { panel = it }, onShowQuality = onShowQuality,
                showSpeed = state.errorMessage != null || state.ended,
                showScreenshot = !landscape || state.errorMessage != null || state.ended,
                onScreenshot = onScreenshot, onManualTrackSelection = onManualTrackSelection,
                onSetSleepTimer = onSetSleepTimer, sleepTimerLabel = sleepTimerLabel,
                audioGainPercent = audioGainPercent, onAudioGainChange = onAudioGainChange, onAddSubtitle = onAddSubtitle,
                onManageSubtitles = onManageSubtitles,
                subtitlePresentation = subtitlePresentation, onSubtitlePresentationChange = onSubtitlePresentationChange)
        }
    }
}

/** 顶栏标题下的一行弱色状态：时间、电量、网速与字幕/增益提示合并，不再单独铺黑底标签。 */
/** 全屏标题：优先显示 logo 图；没有 logo、读取中或失败时显示文字。 */
@Composable
private fun PlayerHeaderTitle(title: String, logo: ImageRequest?) {
    var logoReady by remember(logo) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.CenterStart) {
        Text(title, Modifier.fillMaxWidth().testTag("player-media-title").then(if (logoReady) Modifier.alpha(0f) else Modifier),
            color = PlayerText, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (logo != null) AsyncImage(logo, title, imageLoader = LocalPosterLoader.current,
            modifier = Modifier.widthIn(max = 240.dp).fillMaxHeight().testTag("player-media-logo"),
            contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            onState = { logoReady = it is AsyncImagePainter.State.Success })
    }
}

@Composable
private fun PlayerStatusLine(clock: String?, battery: String?, state: PlaybackUiState) {
    if (clock == null && battery == null && !state.showBufferSpeed && state.audioGainStatus == null && state.assStatus == null) return
    Row(Modifier.testTag("player-status-line"), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (clock != null) Text(clock, color = PlayerSecondary, fontSize = 12.sp, maxLines = 1)
        if (battery != null) PlayerBattery(battery)
        if (state.showBufferSpeed) PlayerTransferRate(state.transferBytesPerSecond)
        state.audioGainStatus?.let { PlayerStatusMessage(it, "player-audio-gain-status") }
        state.assStatus?.let { PlayerStatusMessage(it, "player-ass-status") }
    }
}

/** 电池图标里按实际电量画一截填充，文字百分比跟在后面，一眼看出显示的是电量。 */
@Composable
private fun PlayerBattery(label: String) {
    val level = label.filter(Char::isDigit).toIntOrNull()?.coerceIn(0, 100) ?: 0
    Row(Modifier.testTag("player-battery"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(width = 18.dp, height = 18.dp)) {
            Icon(PathfinIcons.Battery, null, Modifier.fillMaxSize(), tint = PlayerSecondary)
            Box(Modifier.align(Alignment.CenterStart).padding(start = 3.dp).width(11.dp * (level / 100f)).height(5.5.dp)
                .clip(RoundedCornerShape(1.dp)).background(if (level <= 15) Color(0xFFFF6B5E) else PlayerSecondary))
        }
        Text(label, color = PlayerSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun PlayerStatusMessage(message: String, tag: String) {
    Text(message, color = PlayerSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(tag))
}

@Composable
private fun PlayerTransferRate(bytesPerSecond: Long?) {
    Box(Modifier.widthIn(min = 56.dp).heightIn(min = 20.dp).testTag("player-buffer-speed-slot"), contentAlignment = Alignment.CenterStart) {
        bytesPerSecond?.let { Text(formatPlaybackTransferRate(it), color = PlayerSecondary, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("player-buffer-speed")) }
    }
}

@Composable
private fun SpeedStep(increase: Boolean, enabled: Boolean, speed: Float, interaction: (() -> Unit) -> Unit, onChange: (Float) -> Unit) {
    PlayerRoundButton(if (increase) PathfinIcons.Plus else PathfinIcons.Minus,
        if (increase) tr("加快播放速度") else tr("减慢播放速度"), if (increase) "player-speed-increase" else "player-speed-decrease",
        enabled = enabled && if (increase) speed < 4f else speed > .25f,
        onClick = { interaction { onChange(((kotlin.math.round(speed * 10) + if (increase) 1 else -1) / 10f).coerceIn(.25f, 4f)) } })
}

@Composable
private fun TrackButtons(hasAudio: Boolean, showSubtitles: Boolean, interaction: (() -> Unit) -> Unit,
    onAudio: () -> Unit, onSubtitles: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerRoundButton(PathfinIcons.Audio, tr("音轨"), "player-audio", enabled = hasAudio,
            onClick = { interaction(onAudio) })
        if (showSubtitles) PlayerRoundButton(PathfinIcons.Subtitles, tr("字幕"), "player-subtitles",
            onClick = { interaction(onSubtitles) })
    }
}

/** 播放器深色层的统一配色：控制层、菜单与侧面板共用。 */
internal val PlayerText = Color(0xFFF4F4F6)
internal val PlayerSecondary = Color(0xFFF4F4F6).copy(alpha = .62f)
internal val PlayerDisabled = Color(0xFFF4F4F6).copy(alpha = .3f)
internal val PlayerSurface = Color(0xFF1C1D20).copy(alpha = .94f)
internal val PlayerDivider = Color.White.copy(alpha = .08f)

/** 裸白色细线图标按钮：48dp 起的触控区，不带底色，依靠顶部/底部暗角保证可读。 */
@Composable
internal fun PlayerRoundButton(icon: ImageVector, label: String, tag: String, modifier: Modifier = Modifier,
    size: Dp = 48.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(if (size >= 56.dp) 30.dp else 24.dp), tint = if (enabled) PlayerText else PlayerDisabled)
    }
}

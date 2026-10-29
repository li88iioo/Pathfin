package dev.pathfin.android.ui.player

import androidx.compose.foundation.layout.Box
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.PathfinSlider
import dev.pathfin.android.ui.PlayerSidePanel
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import dev.pathfin.android.ui.PathfinAppearance
import androidx.compose.ui.graphics.lerp
import dev.pathfin.android.ui.PathfinIcons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.playback.SubtitlePresentation
import java.util.Locale
import kotlin.math.roundToInt

internal enum class PlayerControlPanel { AUDIO, SUBTITLES, SPEED, ASPECT, INFO, MORE, SLEEP, GAIN, SUBTITLE_LAYOUT, SUBTITLE_DELAY }

internal val PlayerOptionShape = RoundedCornerShape(6.dp)
internal val PlayerOptionFill = Color.White.copy(alpha = .035f)
internal val PlayerSelectedOptionFill = Color.Black.copy(alpha = .20f)

@UnstableApi
internal enum class PlayerResizeMode(private val labelKey: String) {
    FIT("原始比例"),
    ZOOM("裁切填满"),
    FILL("拉伸填满");

    val label: String get() = tr(labelKey)
}

/** 播放控制共用侧面板，切换选项不改变视频布局。 */
@UnstableApi
@Composable
internal fun PlayerControlPopup(
    panel: PlayerControlPanel,
    player: Player?,
    snapshot: PlayerControlSnapshot,
    resizeMode: PlayerResizeMode,
    sourceLabel: String,
    onDismiss: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onResizeModeChange: (PlayerResizeMode) -> Unit,
    onPanelChange: (PlayerControlPanel) -> Unit,
    onShowQuality: () -> Unit,
    showSpeed: Boolean,
    showScreenshot: Boolean,
    onScreenshot: (() -> Unit)?,
    onManualTrackSelection: (Player, Int, Format?, Boolean) -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    sleepTimerLabel: String?,
    audioGainPercent: Int,
    onAudioGainChange: (Int) -> Unit,
    onAddSubtitle: (() -> Unit)?,
    onManageSubtitles: (() -> Unit)?,
    subtitlePresentation: SubtitlePresentation,
    onSubtitlePresentationChange: (SubtitlePresentation) -> Unit,
) {
    // 所有控制弹层统一为右侧渐变侧面板（竖屏从底部升起），标题在面板顶部，不再锚在按钮旁浮出。
    val title = when (panel) {
        PlayerControlPanel.MORE -> tr("更多")
        PlayerControlPanel.AUDIO -> tr("音轨")
        PlayerControlPanel.SUBTITLES -> tr("字幕")
        PlayerControlPanel.SPEED -> tr("倍速")
        PlayerControlPanel.ASPECT -> tr("画面尺寸")
        PlayerControlPanel.SLEEP -> sleepTimerLabel?.let { tr("定时关闭 · 剩余 %1\$s", it) } ?: tr("定时关闭")
        PlayerControlPanel.GAIN -> tr("音量增益")
        PlayerControlPanel.SUBTITLE_LAYOUT -> tr("字幕缩放与位置")
        PlayerControlPanel.SUBTITLE_DELAY -> tr("字幕时间偏移")
        PlayerControlPanel.INFO -> tr("播放信息")
    }
    val info = panel == PlayerControlPanel.INFO
    PlayerSidePanel(title, onDismiss, closeLabel = if (info) tr("关闭媒体信息") else tr("关闭%1\$s", title),
        closeTag = if (info) "player-info-close" else "playback-panel-close") {
        if (info) PlayerTechnicalInformation(snapshot, sourceLabel)
        else if (panel == PlayerControlPanel.SPEED) PlayerSpeedGrid(snapshot.speed) { onSpeedChange(it); onDismiss() }
        else if (panel == PlayerControlPanel.MORE) PlayerSettingsPanel(buildList {
            if (showSpeed) add(MoreEntry(PathfinIcons.Speed, tr("播放速度"), "player-menu-speed") { onPanelChange(PlayerControlPanel.SPEED) })
            add(MoreEntry(PathfinIcons.Info, tr("媒体信息"), "player-menu-information") { onPanelChange(PlayerControlPanel.INFO) })
            add(MoreEntry(PathfinIcons.Volume, tr("音量增益"), "player-audio-gain") { onPanelChange(PlayerControlPanel.GAIN) })
            add(MoreEntry(PathfinIcons.Quality, tr("播放质量"), "player-tools") { onDismiss(); onShowQuality() })
            // 仅播放音频：关闭视频轨，解码与画面渲染一并停止；再次点按恢复画面。
            val audioOnly = C.TRACK_TYPE_VIDEO in snapshot.selection.disabledTrackTypes
            add(MoreEntry(if (audioOnly) PathfinIcons.Video else PathfinIcons.Audio, if (audioOnly) tr("恢复画面") else tr("仅播放音频"), "player-audio-only",
                enabled = player != null) {
                player?.let { selectPlayerTrack(it, C.TRACK_TYPE_VIDEO, null, disabled = !audioOnly) }
                onDismiss()
            })
            if (showScreenshot) add(MoreEntry(PathfinIcons.Camera, tr("保存截图"), "player-screenshot", enabled = onScreenshot != null) {
                onDismiss(); onScreenshot?.invoke() })
        }, sleepTimerLabel, onSleep = { onPanelChange(PlayerControlPanel.SLEEP) })
        else LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("player-popup-${panel.name.lowercase(Locale.ROOT)}"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (panel) {
                    PlayerControlPanel.AUDIO, PlayerControlPanel.SUBTITLES -> {
                        val type = if (panel == PlayerControlPanel.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                        val choices = trackChoices(snapshot.tracks, type)
                        val disabled = type in snapshot.selection.disabledTrackTypes
                        val automatic = !disabled && snapshot.selection.overrides.values.none { it.type == type }
                        if (panel == PlayerControlPanel.SUBTITLES) {
                            item("subtitle-sources") { PlayerMenuPair(
                                { PlayerMenuAction(PathfinIcons.Folder, tr("本机导入"), "player-add-subtitle", it, onAddSubtitle != null) {
                                    onDismiss(); onAddSubtitle?.invoke() } },
                                { PlayerMenuAction(PathfinIcons.Search, tr("在线搜索"), "player-manage-subtitles", it, onManageSubtitles != null) {
                                    onDismiss(); onManageSubtitles?.invoke() } }) }
                            menuHeading(tr("字幕轨道"))
                        }
                        // 自动模式下只标“自动选择”并注明实际选中的轨道；手动指定后只标那一条，避免两行同时显示选中。
                        val current = choices.firstOrNull { it.selected }?.label
                        if (choices.isNotEmpty()) item("automatic") { PlayerChoiceRow(tr("自动选择"), automatic, "player-track-auto",
                            detail = if (automatic) current?.let { tr("当前：%1\$s", it) } else null) {
                            player?.let { onManualTrackSelection(it, type, null, false); selectPlayerTrack(it, type, null) }
                            onDismiss()
                        } }
                        if (choices.isNotEmpty()) item("off") { PlayerChoiceRow(if (type == C.TRACK_TYPE_AUDIO) tr("关闭音轨") else tr("关闭字幕"), disabled, "player-track-off") {
                            player?.let { onManualTrackSelection(it, type, null, true); selectPlayerTrack(it, type, null, disabled = true) }
                            onDismiss()
                        } }
                        items(choices, key = { it.id }) { choice ->
                            PlayerChoiceRow(choice.label + if (choice.supported) "" else tr(" · 设备不支持"),
                                selected = !disabled && !automatic && choice.selected, tag = "player-track-${choice.id}", enabled = choice.supported) {
                                player?.let {
                                    onManualTrackSelection(it, type, choice.group.getFormat(choice.index), false)
                                    selectPlayerTrack(it, type, choice)
                                }
                                onDismiss()
                            }
                        }
                        if (choices.isEmpty()) item("no-tracks") {
                            Text(if (type == C.TRACK_TYPE_TEXT) tr("当前没有可切换的字幕轨道") else tr("当前没有可用音轨"),
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = PlayerSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                        if (panel == PlayerControlPanel.SUBTITLES) {
                            menuHeading(tr("显示与同步"))
                            item("subtitle-adjust") { PlayerMenuPair(
                                { PlayerMenuAction(PathfinIcons.Subtitles, tr("缩放与位置"), "player-subtitle-layout", it) {
                                    onPanelChange(PlayerControlPanel.SUBTITLE_LAYOUT) } },
                                { PlayerMenuAction(PathfinIcons.Clock, tr("时间偏移"), "player-subtitle-delay", it) {
                                    onPanelChange(PlayerControlPanel.SUBTITLE_DELAY) } }) }
                        }
                    }
                    PlayerControlPanel.SPEED -> Unit
                    PlayerControlPanel.ASPECT -> {
                        items(PlayerResizeMode.entries) { mode ->
                            PlayerChoiceRow(mode.label, resizeMode == mode, "player-resize-${mode.name.lowercase(Locale.ROOT)}") {
                                onResizeModeChange(mode); onDismiss()
                            }
                        }
                    }
                    PlayerControlPanel.INFO -> Unit
                    PlayerControlPanel.MORE -> Unit
                    PlayerControlPanel.SLEEP -> {
                        item { PlayerChoiceRow(tr("关闭定时"), sleepTimerLabel == null, "player-sleep-off") { onSetSleepTimer(null); onDismiss() } }
                        items(listOf(5, 15, 30, 45, 60, 90, 120)) { minutes ->
                            PlayerMenuAction(PathfinIcons.Clock, tr("%1\$s 分钟后", minutes), "player-sleep-$minutes") { onSetSleepTimer(minutes); onDismiss() }
                        }
                    }
                    PlayerControlPanel.GAIN -> {
                        item { PlayerValueSlider("$audioGainPercent%", audioGainPercent.toFloat(), 0f..200f, 19, "player-gain-slider") { onAudioGainChange(it.roundToInt()) } }
                        item { Text(tr("超过100%可能造成失真"), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = PlayerSecondary, fontSize = 12.sp) }
                        item { PlayerMenuAction(PathfinIcons.Audio, tr("恢复100%"), "player-gain-reset") { onAudioGainChange(100) } }
                    }
                    PlayerControlPanel.SUBTITLE_LAYOUT -> {
                        item { PlayerValueSlider(tr("缩放 %1\$s%%", (subtitlePresentation.scale * 100).roundToInt()), subtitlePresentation.scale, .5f..2f, 14, "player-subtitle-scale-slider") {
                            onSubtitlePresentationChange(subtitlePresentation.copy(scale = it))
                        } }
                        item { PlayerValueSlider(tr("垂直位置 %1\$s%%", subtitlePresentation.positionPercent), subtitlePresentation.positionPercent.toFloat(), 0f..100f, 19, "player-subtitle-position-slider") {
                            onSubtitlePresentationChange(subtitlePresentation.copy(positionPercent = it.roundToInt()))
                        } }
                        item { PlayerMenuAction(PathfinIcons.Subtitles, tr("恢复默认"), "player-subtitle-layout-reset") {
                            onSubtitlePresentationChange(subtitlePresentation.copy(scale = 1f, positionPercent = 100))
                        } }
                    }
                    PlayerControlPanel.SUBTITLE_DELAY -> {
                        information(tr("偏移"), String.format(Locale.ROOT, tr("%+.1f 秒"), subtitlePresentation.delayMs / 1000f))
                        item { PlayerMenuAction(PathfinIcons.Clock, tr("提前0.5秒"), "player-subtitle-earlier") {
                            onSubtitlePresentationChange(subtitlePresentation.copy(delayMs = subtitlePresentation.delayMs - 500))
                        } }
                        item { PlayerMenuAction(PathfinIcons.Clock, tr("延后0.5秒"), "player-subtitle-later") {
                            onSubtitlePresentationChange(subtitlePresentation.copy(delayMs = subtitlePresentation.delayMs + 500))
                        } }
                        item { PlayerMenuAction(PathfinIcons.Subtitles, tr("重置偏移"), "player-subtitle-delay-reset") {
                            onSubtitlePresentationChange(subtitlePresentation.copy(delayMs = 0))
                        } }
                    }
            }
        }
    }
}

private class MoreEntry(val icon: ImageVector, val label: String, val tag: String, val enabled: Boolean = true,
    val onClick: () -> Unit)

/** 快捷操作轻量平铺；带当前状态的设置用整行，避免大卡片挤占视频。 */
@Composable
private fun PlayerSettingsPanel(entries: List<MoreEntry>, sleepTimerLabel: String?, onSleep: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().testTag("player-popup-more"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(entries.chunked(4), key = { row -> row.first().tag }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { entry ->
                    Column(Modifier.weight(1f).heightIn(min = 76.dp).clip(PlayerOptionShape)
                        .clickable(enabled = entry.enabled, role = Role.Button, onClick = entry.onClick)
                        .testTag(entry.tag).padding(horizontal = 2.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        Icon(entry.icon, null, Modifier.size(24.dp), tint = if (entry.enabled) PlayerText else PlayerDisabled)
                        Text(entry.label, fontSize = 13.sp, lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (entry.enabled) PlayerText else PlayerDisabled)
                    }
                }
                // 末行补齐空位，所有入口保持同一宽度，不因数量不足被拉宽。
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item("sleep-timer") {
            Row(Modifier.fillMaxWidth().clip(PlayerOptionShape)
                .background(PlayerOptionFill)
                .clickable(role = Role.Button, onClick = onSleep).testTag("player-sleep-timer")
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("定时关闭"), Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = PlayerText)
                Text(sleepTimerLabel ?: tr("未开启"), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, color = PlayerSecondary)
                Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PlayerSecondary)
            }
        }
    }
}

/** 倍速：三列轻底选项，当前值以暗底和强调色显示。 */
@Composable
private fun PlayerSpeedGrid(current: Float, onChoose: (Float) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().testTag("player-popup-speed"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)) { speed ->
            val selected = kotlin.math.abs(current - speed) < .001f
            Box(Modifier.heightIn(min = 60.dp).clip(PlayerOptionShape)
                .background(if (selected) PlayerSelectedOptionFill else PlayerOptionFill)
                .selectable(selected, role = Role.RadioButton) { onChoose(speed) }.testTag("player-speed-$speed"),
                contentAlignment = Alignment.Center) {
                Text(playerSpeed(speed), Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) playerAccent() else PlayerText,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}


/** Runtime fields only; unavailable codec/container metadata is omitted rather than invented. */
@UnstableApi
@Composable
private fun PlayerTechnicalInformation(snapshot: PlayerControlSnapshot, source: String) {
    Box(Modifier.fillMaxWidth().testTag("player-popup-info")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 12.dp, bottom = 16.dp)) {
            Text(source.ifBlank { tr("正在准备") }, fontSize = 12.5.sp, lineHeight = 18.sp, color = PlayerSecondary)
            // 与其他面板同宽；大字号改为上下排列，避免为信息页扩宽整个暗区。
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2) {
                Column(Modifier.weight(1f)) {
                    TechnicalHeading(tr("播放"), top = 6.dp)
                    TechnicalValue(tr("播放器"), "ExoPlayer")
                    TechnicalValue(tr("缓冲时长"), "${((snapshot.bufferedPositionMs - snapshot.positionMs).coerceAtLeast(0) / 1000)} s")
                    snapshot.videoFormat?.let { video -> if (video.width > 0 && video.height > 0) TechnicalValue(tr("分辨率"), "${video.width} × ${video.height}") }
                    snapshot.sourceBitrate?.let { TechnicalValue(tr("码率"), technicalBitrate(it)) }
                    if (snapshot.container != null || snapshot.sourceBytes != null) {
                        TechnicalHeading(tr("媒体源"))
                        snapshot.container?.let { TechnicalValue(tr("封装容器"), it.uppercase(Locale.ROOT)) }
                        snapshot.sourceBytes?.let { TechnicalValue(tr("文件大小"), technicalSize(it)) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    snapshot.videoFormat?.let { video ->
                        TechnicalHeading(tr("视频"), top = 6.dp)
                        video.sampleMimeType?.let { TechnicalValue(tr("编解码器"), codecLabel(it)) }
                        val range = when {
                            video.sampleMimeType == "video/dolby-vision" -> "Dolby Vision"
                            video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "PQ"
                            video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                            video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR -> "SDR"
                            else -> null
                        }
                        range?.let { TechnicalValue(tr("动态范围"), it) }
                        if (video.bitrate > 0) TechnicalValue(tr("码率"), technicalBitrate(video.bitrate))
                        if (video.frameRate > 0) TechnicalValue(tr("帧率"), String.format(Locale.ROOT, "%.2f fps", video.frameRate))
                        video.colorInfo?.lumaBitdepth?.takeIf { it > 0 }?.let { TechnicalValue(tr("位深"), it.toString()) }
                    }
                    snapshot.audioFormat?.let { audio ->
                        TechnicalHeading(tr("音频"), top = if (snapshot.videoFormat == null) 6.dp else 10.dp)
                        audio.sampleMimeType?.let { TechnicalValue(tr("编解码器"), codecLabel(it)) }
                        if (audio.channelCount > 0) TechnicalValue(tr("声道"), audio.channelCount.toString())
                        if (audio.sampleRate > 0) TechnicalValue(tr("采样率"), "${audio.sampleRate} Hz")
                        if (audio.bitrate > 0) TechnicalValue(tr("码率"), technicalBitrate(audio.bitrate))
                    }
                }
            }
            if (snapshot.videoFormat == null && snapshot.audioFormat == null) TechnicalValue(tr("轨道"), tr("正在读取媒体轨道…"))
        }
    }
}

@Composable private fun TechnicalHeading(text: String, top: Dp = 10.dp) {
    Text(text, Modifier.padding(top = top, bottom = 3.dp), fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = .4.sp, color = PlayerSecondary)
}
@Composable private fun TechnicalValue(label: String, value: String) {
    // 标签弱色靠左、数值主色靠右，扫读时一眼对齐。
    Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 1.5.dp, bottom = 1.5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 12.5.sp, lineHeight = 18.sp, color = PlayerSecondary, maxLines = 1)
        Text(value, Modifier.weight(1f), fontSize = 12.5.sp, lineHeight = 18.sp, color = PlayerText, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
private fun technicalBitrate(value: Int) = String.format(Locale.ROOT, "%.2f Mbps", value / 1_000_000f)
private fun technicalSize(value: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble(); var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.2f %s", size, units[unit])
}
/** 深色菜单上的选中色：由全站强调色提亮得到，壁纸取色在播放器里同样生效。 */
@Composable
internal fun playerAccent(): Color = lerp(PathfinAppearance.accent, Color.White, .32f)

private fun LazyListScope.menuHeading(title: String) = item { Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
    fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp, color = PlayerSecondary) }
private fun LazyListScope.information(label: String, value: String) = item { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("$label:", color = PlayerSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    Text(value, Modifier.weight(1f), color = PlayerText, fontSize = 13.sp, lineHeight = 19.sp)
} }

@Composable
private fun PlayerSectionLabel(title: String) { Text(title, Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 6.dp),
    fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp, color = PlayerSecondary) }

@Composable
private fun PlayerChoiceRow(label: String, selected: Boolean, tag: String, enabled: Boolean = true, detail: String? = null, onClick: () -> Unit) {
    val accent = playerAccent()
    Row(Modifier.fillMaxWidth().clip(PlayerOptionShape)
        .background(if (selected) PlayerSelectedOptionFill else PlayerOptionFill)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .testTag(tag).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (!enabled) PlayerDisabled else if (selected) accent else PlayerText)
            if (detail != null) Text(detail, fontSize = 12.sp, lineHeight = 17.sp, color = if (enabled) PlayerSecondary else PlayerDisabled,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(PathfinIcons.Check, null, Modifier.fillMaxSize(), tint = if (enabled) accent else PlayerDisabled)
        }
    }
}

@Composable
private fun PlayerMenuAction(icon: ImageVector, label: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(PlayerOptionShape).background(PlayerOptionFill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) PlayerSecondary else PlayerDisabled)
        Text(label, Modifier.weight(1f), color = if (enabled) PlayerText else PlayerDisabled, fontSize = 15.sp, lineHeight = 21.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 两个同级入口并排各占一半，减少面板纵向滚动。 */
@Composable
private fun PlayerMenuPair(start: @Composable (Modifier) -> Unit, end: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        start(Modifier.weight(1f))
        end(Modifier.weight(1f))
    }
}

@Composable
private fun PlayerValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, tag: String, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(label, color = PlayerText, fontSize = 14.sp, lineHeight = 20.sp)
        PathfinSlider(draft, { draft = it }, Modifier.fillMaxWidth().height(48.dp).testTag(tag), onValueChangeFinished = { onCommit(draft) },
            range = range, steps = steps, activeColor = playerAccent(), inactiveColor = Color.White.copy(alpha = .2f))
    }
}

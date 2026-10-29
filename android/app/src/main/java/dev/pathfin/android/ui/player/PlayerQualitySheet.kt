package dev.pathfin.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.PlaybackQualityOption
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.PathfinIcons
import dev.pathfin.android.ui.PlayerSidePanel

@Composable
internal fun PlayerQualitySheet(
    selected: PlaybackQuality,
    changing: Boolean,
    error: String?,
    enabled: Boolean,
    onSelect: (PlaybackQuality) -> Unit,
    onDismiss: () -> Unit,
    cloudOptions: List<PlaybackQualityOption>? = null,
) {
    val options = cloudOptions ?: PlaybackQuality.entries.map { PlaybackQualityOption(it, it.description) }
    PlayerSidePanel(tr("播放质量"), onDismiss, closeLabel = tr("关闭播放质量")) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp).testTag("player-quality-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when {
                error != null -> error
                changing -> tr("正在切换播放质量")
                cloudOptions != null -> tr("使用云盘已有画质，不经过 Jellyfin 转码")
                !enabled -> tr("此类媒体由服务器决定播放质量")
                else -> tr("降低质量可节省流量，需要服务器支持视频转码")
            }, Modifier.fillMaxWidth().heightIn(min = 44.dp), color = PlayerSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
            options.forEach { option ->
                val quality = option.quality
                val checked = quality == selected
                val optionEnabled = enabled && !changing && option.available
                val accent = playerAccent()
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(PlayerOptionShape)
                    .background(if (checked) PlayerSelectedOptionFill else PlayerOptionFill)
                    .selectable(checked, enabled = optionEnabled, role = Role.RadioButton) { onSelect(quality) }
                    .padding(horizontal = 16.dp, vertical = 12.dp).testTag("player-quality-${quality.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(quality.label, color = if (!optionEnabled) PlayerDisabled else if (checked) accent else PlayerText,
                            fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                        Text(option.description, color = if (optionEnabled) PlayerSecondary else PlayerDisabled,
                            fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (checked) Icon(PathfinIcons.Check, null, Modifier.fillMaxSize(), tint = if (optionEnabled) accent else PlayerDisabled)
                    }
                }
            }
        }
    }
}

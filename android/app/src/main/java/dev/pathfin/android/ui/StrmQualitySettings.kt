package dev.pathfin.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.i18n.tr

@Composable
internal fun StrmQualitySettings(
    preferences: AppPreferences,
    onChange: (AppPreferences) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
) {
    var open by remember { mutableStateOf(false) }
    var quality by remember(preferences.strmDefaultQuality, open) { mutableStateOf(preferences.strmDefaultQuality) }
    SettingsRow(tr("STRM 默认画质"), preferences.strmDefaultQuality.label, tag = "strm-default-quality",
        enabled = preferences.originalPathEnabled, position = position) { open = true }
    if (open && preferences.originalPathEnabled) AppDialog({ open = false }, tr("STRM 默认画质"),
        subtitle = tr("仅在开启 STRM 原始地址时生效；本地媒体和直播不受影响。"), actions = {
            DialogAction(tr("取消"), { open = false }, Modifier.weight(1f))
            DialogAction(tr("保存"), { onChange(preferences.copy(strmDefaultQuality = quality)); open = false },
                Modifier.weight(1f), role = DialogActionRole.Primary)
        }) {
        GroupCard(container = PathfinAppearance.canvas) {
            PlaybackQuality.entries.forEach { option ->
                SettingsOptionRow(option.label, option == quality, tag = "strm-default-quality-${option.name.lowercase()}",
                    icon = option.icon) {
                    quality = option
                }
            }
        }
    }
}

private val PlaybackQuality.icon get() = when (this) {
    PlaybackQuality.ORIGINAL -> PathfinIcons.QualityOriginal
    PlaybackQuality.HIGH -> PathfinIcons.QualityHigh
    PlaybackQuality.STANDARD -> PathfinIcons.QualityStandard
    PlaybackQuality.DATA_SAVER -> PathfinIcons.QualitySaver
}

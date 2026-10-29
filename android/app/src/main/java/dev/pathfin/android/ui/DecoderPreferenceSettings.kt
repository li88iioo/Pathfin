package dev.pathfin.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.i18n.tr

@Composable
internal fun DecoderPreferenceSettings(
    preferences: AppPreferences,
    onChange: (AppPreferences) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
) {
    var open by remember { mutableStateOf(false) }
    val options = linkedMapOf("AUTO" to tr("系统默认"), "HARDWARE" to tr("硬件优先"), "SOFTWARE" to tr("软件优先"))
    SettingsRow(tr("音频解码"), options[preferences.exoAudioDecoder] ?: tr("系统默认"), position = position) { open = true }
    if (open) SettingsChoiceDialog(tr("音频解码"), onDismiss = { open = false }, note = tr("使用系统解码器，下次播放生效。")) {
        options.forEach { (value, label) ->
            val icon = when (value) { "HARDWARE" -> PathfinIcons.Chip; "SOFTWARE" -> PathfinIcons.Code; else -> PathfinIcons.Sliders }
            SettingsOptionRow(label, preferences.exoAudioDecoder == value, icon = icon) {
                onChange(preferences.copy(exoAudioDecoder = value)); open = false
            }
        }
    }
}

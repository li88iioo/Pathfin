package dev.pathfin.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.pathfin.android.i18n.tr

/** 只列实际提供的界面语言，语言名用各自的自称；Auto采用系统应用语言机制。 */
@Composable
internal fun AppLocaleSettings(position: GroupPosition = GroupPosition.SINGLE) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var open by remember { mutableStateOf(false) }
    if (Build.VERSION.SDK_INT < 33) {
        SettingsRow(tr("语言"), tr("跟随系统 · Android 13 起可单独设置"), position = position)
        return
    }
    val manager = context.getSystemService(LocaleManager::class.java)
    val selected = remember(configuration, open) { manager.applicationLocales.toLanguageTags() }
    val options = listOf("" to tr("Auto · 跟随系统"), "zh-Hans" to "简体中文", "en" to "English")
    val current = options.firstOrNull { it.first == selected } ?: options.first()
    SettingsRow(tr("语言"), current.second, position = position) { open = true }
    if (open) SettingsChoiceDialog(tr("语言"), onDismiss = { open = false }, note = tr("目前提供简体中文和英语。")) {
        options.forEach { (tag, label) ->
            SettingsOptionRow(label, current.first == tag) {
                open = false
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
            }
        }
    }
}

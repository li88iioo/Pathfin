package dev.pathfin.android.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.i18n.tr

/** 与媒体库其余选项共用唯一偏好状态及保存入口。 */
@Composable
internal fun LibraryPersonalizationRows(preferences: AppPreferences, onPreferences: (AppPreferences) -> Unit) {
    Column {
        SettingsSwitch(tr("成人分级封面模糊"), preferences.blurAdultCovers,
            if (Build.VERSION.SDK_INT >= 31) tr("成人及 18+ 分级") else tr("成人及 18+ 分级使用遮盖"),
            tag = "settings-blur-adult-covers", position = GroupPosition.SINGLE) {
            onPreferences(preferences.copy(blurAdultCovers = it))
        }
    }
}

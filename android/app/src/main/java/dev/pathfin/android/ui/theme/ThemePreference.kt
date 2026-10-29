package dev.pathfin.android.ui.theme

import dev.pathfin.android.i18n.tr

enum class ThemePreference(private val labelKey: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    val label: String get() = tr(labelKey)
}

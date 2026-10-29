package dev.pathfin.android.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import dev.pathfin.android.i18n.tr

enum class ThemePreference(private val labelKey: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    val label: String get() = tr(labelKey)
}

class ThemeStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE)
    private val legacy = context.applicationContext
        .getSharedPreferences("pathfin_appearance", Context.MODE_PRIVATE)

    fun load(): ThemePreference = (preferences.getString("theme", null) ?: legacy.getString("mode", null))
        ?.let { saved -> ThemePreference.entries.find { it.name == saved } }
        ?: ThemePreference.SYSTEM

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun save(mode: ThemePreference) {
        check(preferences.edit().putString("theme", mode.name).commit()) { tr("无法保存主题") }
    }
}

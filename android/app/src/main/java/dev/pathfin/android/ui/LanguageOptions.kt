package dev.pathfin.android.ui

import dev.pathfin.android.i18n.tr
import java.util.Locale

private val preferredLanguageCodes =
    (Locale.getISOLanguages().toList() + listOf("fil", "zh-Hans", "zh-Hant", "pt-BR", "pt-PT"))
        .distinct()

internal fun preferredLanguageOptions(): List<Pair<String, String>> = listOf("" to tr("自动")) +
    preferredLanguageCodes.map { it to languageName(it) }.sortedBy { it.second }

internal fun languageName(code: String): String {
    if (code.isEmpty()) return tr("自动")
    if (code !in preferredLanguageCodes) return code
    val locale = Locale.forLanguageTag(code)
    return formatLanguageLabel(code, locale.getDisplayName(Locale.getDefault()), locale.getDisplayName(locale))
}

/** 系统缺少语言译名时会回显代码；代码只保留一次，中文名与本地名相同也只显示一次。 */
internal fun formatLanguageLabel(code: String, translatedName: String, nativeName: String): String {
    val names = listOf(translatedName.trim(), nativeName.trim())
        .filter { it.isNotEmpty() && !it.equals(code, ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
    return when {
        names.isEmpty() -> code
        code.isEmpty() -> names.joinToString(" · ")
        else -> "${names.joinToString(" · ")} ($code)"
    }
}

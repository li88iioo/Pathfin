package dev.pathfin.android.i18n

import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale

/**
 * 界面文案以中文原文为键：中文是默认语言，英文环境查 i18n/en.tsv。
 * 不走 strings.xml，是为了让 ViewModel、数据层错误和 JVM 测试都能直接取到同一份文案。
 */
fun tr(text: String): String = lookup(text)

/** 模板使用 %1$s 占位；未翻译的键回退为中文原文。 */
fun tr(template: String, vararg args: Any?): String = String.format(Locale.getDefault(), lookup(template), *args)

/** 语言切换后调用：读过 tr() 的 Composable 会随之重组，界面树与滚动状态原样保留。 */
fun onLocaleChanged() { localeEpoch.intValue++ }

private val localeEpoch = mutableIntStateOf(0)

private fun lookup(text: String): String {
    localeEpoch.intValue // 只为在组合中登记读取；语言仍以系统默认 Locale 为准
    return if (Locale.getDefault().language == "en") English.table[text] ?: text else text
}

private object English {
    val table: Map<String, String> by lazy {
        val stream = English::class.java.classLoader?.getResourceAsStream("i18n/en.tsv") ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotEmpty).associate { line ->
                val tab = line.indexOf('\t')
                unescape(line.substring(0, tab)) to unescape(line.substring(tab + 1))
            }
        }
    }

    private fun unescape(value: String): String =
        if ('\\' !in value) value else buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c != '\\' || i == value.length) { append(c); continue }
                when (val next = value[i++]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append(next)
                }
            }
        }
}

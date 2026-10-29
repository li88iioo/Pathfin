package dev.pathfin.android.i18n

import dev.pathfin.android.domain.AccentColor
import dev.pathfin.android.domain.BufferPreset
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaIdentificationType
import dev.pathfin.android.domain.MediaImageType
import dev.pathfin.android.domain.MetadataRefreshMode
import dev.pathfin.android.domain.MetadataRefreshScope
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.PlayerOrientation
import dev.pathfin.android.domain.ServerTaskState
import dev.pathfin.android.ui.PosterStyle
import dev.pathfin.android.ui.player.PlayerResizeMode
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L10nTest {
    private val previous = Locale.getDefault()
    @After fun restoreLocale() = Locale.setDefault(previous)

    @Test fun chineseAndOtherLocalesKeepTheSourceText() {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ROOT, Locale.JAPANESE)) {
            Locale.setDefault(locale)
            assertEquals("设置", tr("设置"))
            assertEquals("剩 5 分", tr("剩 %1\$s 分", 5))
            assertEquals("垂直位置 80%", tr("垂直位置 %1\$s%%", 80))
        }
    }

    @Test fun englishLocalesTranslate() {
        for (locale in listOf(Locale.ENGLISH, Locale.US, Locale.UK)) {
            Locale.setDefault(locale)
            assertEquals("Settings", tr("设置"))
            assertEquals("5 min left", tr("剩 %1\$s 分", 5))
            assertEquals("Vertical position 80%", tr("垂直位置 %1\$s%%", 80))
            assertEquals("Episode 3 · Pilot", tr("第%1\$s集 · %2\$s", 3, "Pilot"))
        }
    }

    @Test fun englishKeepsLineBreaksInKeysAndValues() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("\nThe task will run on this server and may affect ongoing playback.",
            tr("\n任务将在此服务器执行，可能影响正在进行的播放。"))
    }

    @Test fun untranslatedTextFallsBackToChinese() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("不存在的文案", tr("不存在的文案"))
        assertEquals("不存在 7", tr("不存在 %1\$s", 7))
        assertEquals("", tr(""))
    }

    @Test fun languageNamesStayInTheirOwnScript() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("简体中文", tr("简体中文"))
    }

    @Test fun everySourceTextHasAnEnglishTranslation() {
        val table = englishTable()
        val missing = sourceKeys().filter { it !in table }
        assertTrue("缺少英文：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun translationsKeepPlaceholders() {
        val placeholder = Regex("%\\d+\\\$s|%%")
        val broken = englishTable().filter { (zh, en) ->
            placeholder.findAll(zh).map { it.value }.sorted().toList() != placeholder.findAll(en).map { it.value }.sorted().toList()
        }
        assertTrue("占位符不一致：$broken", broken.isEmpty())
    }

    @Test fun enumLabelsFollowTheCurrentLanguage() {
        // 枚举只初始化一次：名称必须在读取时翻译，切换语言后才会跟着变。
        val labels = {
            AccentColor.entries.map { it.label } + PlayerOrientation.entries.map { it.label } +
                BufferPreset.entries.map { it.label } + PlaybackQuality.entries.flatMap { listOf(it.label, it.description) } +
                DownloadNetwork.entries.map { it.label } + LibraryTab.entries.map { it.label } + LibrarySort.entries.map { it.label } +
                DiscoveryCategory.entries.map { it.label } + MediaIdentificationType.entries.map { it.label } +
                MediaImageType.entries.map { it.label } + MetadataRefreshMode.entries.map { it.label } + MetadataRefreshScope.entries.map { it.label } +
                ServerTaskState.entries.map { it.label } + ThemePreference.entries.map { it.label } +
                PosterStyle.entries.map { it.label } + PlayerResizeMode.entries.map { it.label }
        }
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val chinese = labels()
        Locale.setDefault(Locale.ENGLISH)
        val english = labels()
        val table = englishTable()
        val untranslated = chinese.zip(english).filter { (zh, en) -> table[zh] != en }
        assertTrue("枚举名称未随语言切换：$untranslated", untranslated.isEmpty())
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals(chinese, labels())
    }

    private fun englishTable(): Map<String, String> {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        return try {
            val lines = File("src/main/resources/i18n/en.tsv").readLines()
            lines.filter(String::isNotEmpty).associate { line ->
                val (zh, en) = line.split('\t', limit = 2).map { it.replace("\\n", "\n") }
                zh to tr(zh).also { assertEquals("en.tsv 与 tr 读取不一致", en, it) }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    /** 扫描源码里 tr("…") 的第一个参数，按 Kotlin 转义还原为运行时字符串。 */
    private fun sourceKeys(): Set<String> {
        val call = Regex("""(?<![\w.])tr\("((?:[^"\\]|\\.)*)"""")
        return File("src/main/java").walk().filter { it.extension == "kt" && it.name != "L10n.kt" }
            .flatMap { file -> call.findAll(file.readText()).map { unescapeKotlin(it.groupValues[1]) } }
            .toSet()
    }

    private fun unescapeKotlin(value: String) = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(value) {
        val escape = it.groupValues[1]
        when {
            escape.length == 5 -> escape.substring(1).toInt(16).toChar().toString()
            escape == "n" -> "\n"
            escape == "t" -> "\t"
            else -> escape
        }
    }
}

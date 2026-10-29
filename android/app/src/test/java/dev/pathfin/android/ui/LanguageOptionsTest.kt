package dev.pathfin.android.ui

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageOptionsTest {
    private val previousLocale = Locale.getDefault()
    @After fun restoreLocale() = Locale.setDefault(previousLocale)

    @Test fun unavailableLanguageNamesKeepTheCodeOnce() {
        assertEquals("aeb", formatLanguageLabel("aeb", "aeb", "aeb"))
        assertEquals("zz", formatLanguageLabel("zz", "ZZ", "zz"))
        assertEquals("xyz", formatLanguageLabel("xyz", "", " "))
    }

    @Test fun equalTranslatedAndNativeNamesAreNotRepeated() {
        assertEquals("中文 (zh)", formatLanguageLabel("zh", "中文", "中文"))
        assertEquals("English (en)", formatLanguageLabel("en", " English ", "english"))
    }

    @Test fun aMissingTranslationDoesNotDuplicateTheCodeAroundTheAvailableName() {
        assertEquals("English (en)", formatLanguageLabel("en", "en", "English"))
        assertEquals("英语 (en)", formatLanguageLabel("en", "英语", "en"))
    }

    @Test fun distinctTranslationsAndNativeNamesRemainSearchable() {
        assertEquals("英语 · English (en)", formatLanguageLabel("en", "英语", "English"))
        assertEquals("葡萄牙语（巴西） · português (Brasil) (pt-BR)",
            formatLanguageLabel("pt-BR", "葡萄牙语（巴西）", "português (Brasil)"))
    }

    @Test fun automaticAndUnknownSelectionsHaveStableNames() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("自动", languageName(""))
        assertEquals("unknown-code", languageName("unknown-code"))
    }

    @Test fun languageOptionsAndSelectedNamesFollowLocaleChanges() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val chinese = preferredLanguageOptions()
        assertEquals("" to "自动", chinese.first())
        assertTrue(languageName("de").contains("德语"))

        Locale.setDefault(Locale.ENGLISH)
        val english = preferredLanguageOptions()
        assertEquals("" to "Auto", english.first())
        assertTrue(languageName("de").contains("German"))
        assertTrue(english.single { it.first == "de" }.second.contains("German"))
        assertTrue(english.single { it.first == "ja" }.second.contains("Japanese"))
        assertEquals(chinese.map { it.first }.toSet(), english.map { it.first }.toSet())

        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals(chinese, preferredLanguageOptions())
        assertEquals("自动", languageName(""))
        assertTrue(languageName("de").contains("德语"))
    }
}

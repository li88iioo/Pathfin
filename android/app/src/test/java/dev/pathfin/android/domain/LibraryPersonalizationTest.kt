package dev.pathfin.android.domain

import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LibraryPersonalizationTest {
    private val previousLocale = Locale.getDefault()
    @Before fun startInEnglish() = Locale.setDefault(Locale.ENGLISH)
    @After fun restoreLocale() = Locale.setDefault(previousLocale)

    @Test fun explicitAdultRatingsMatchRegardlessOfCaseOrKnownRegionPrefix() {
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            Locale.setDefault(locale)
            listOf("18", "18+", "18A", "R18+", "R-18", "X18+", "FSK 18", "FSK ab 18",
                "TV-MA", " nc-17 ", "成人", "限制级", "18禁", "CN:成人", "19+", "GB-18", "UK:18", "DE:FSK-18", "US:tv-ma", "AU-R18+")
                .forEach { assertTrue("$locale 应识别成人分级：$it", AdultContentRatings.matches(it)) }
        }
    }

    @Test fun youngerUnknownOrAmbiguousRatingsNeverBlurBySubstring() {
        listOf("PG-13", "TV-14", "13", "13+", "15", "15+", "16", "16+", "FSK 16", "R", "NR", "UR", "PG", "G",
            "US-PG-13", "DE:16", "18 months", "118", "218+", "rating18", "unrated adult", "XX:18", "", "   ", "x".repeat(65))
            .forEach { assertFalse("不得误伤分级：$it", AdultContentRatings.matches(it)) }
        assertFalse(AdultContentRatings.matches(null))
    }
}

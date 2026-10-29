package dev.pathfin.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserColumnsTest {
    @Test
    fun automaticPhoneDensityKeepsThreePostersAndTwoBackdrops() {
        assertEquals(listOf(PosterStyle.POSTER, PosterStyle.BACKDROP), PosterStyle.entries)
        for (width in listOf(320f, 360f, 412f)) {
            assertEquals(3, BrowserColumns.resolve(PosterStyle.POSTER, width, 1f))
            assertEquals(2, BrowserColumns.resolve(PosterStyle.BACKDROP, width, 1f))
        }
    }

    @Test
    fun tabletUsesMoreColumnsAndExposesFirstPosterTitleWithinThreeHundredDp() {
        val columns = BrowserColumns.resolve(PosterStyle.POSTER, 960f, 1f)
        assertEquals(6, columns)
        assertEquals(4, BrowserColumns.resolve(PosterStyle.BACKDROP, 960f, 1f))
        // 实际网格左右各16dp、间隔12dp，单行主名与副行保持固定高度。
        // 960dp平板的首行应在300dp内同时露出海报、标题和年份；旧固定三列会超过500dp。
        val cardWidth = (960f - 32f - (columns - 1) * 12f) / columns
        assertTrue(cardWidth / PosterStyle.POSTER.aspectRatio + 22f + 18f + 7f <= 300f)
        for (style in PosterStyle.entries) {
            assertEquals(8, BrowserColumns.resolve(style, 7680f, 1f))
        }
    }

    @Test
    fun widerWindowsIncreaseAutomaticDensityWithoutExceedingEightColumns() {
        for (style in PosterStyle.entries) {
            val columns = listOf(240f, 360f, 600f, 960f, 1600f, 7680f).map { BrowserColumns.resolve(style, it, 1f) }
            assertTrue(columns.zipWithNext().all { (left, right) -> right >= left })
            assertTrue(columns.all { it in 1..8 })
        }
    }

    @Test
    fun largeFontLimitsActualCardWidthInsteadOfForcingEveryDeviceToTwoColumns() {
        val phone = BrowserColumns.resolve(PosterStyle.POSTER, 360f, 1.5f)
        val tablet = BrowserColumns.resolve(PosterStyle.POSTER, 960f, 1.5f)
        assertEquals(2, phone)
        assertEquals(4, tablet)
        for ((width, columns) in listOf(360f to phone, 960f to tablet)) {
            val cardWidth = (width - 32f - (columns - 1) * 12f) / columns
            assertTrue("大字号下应保留约六个汉字的宽度", cardWidth >= 88f * 1.5f)
        }
        assertEquals(3, BrowserColumns.resolve(PosterStyle.POSTER, 960f, 2.1f))
    }

    @Test
    fun splitWindowAndInvalidMeasurementsNeverProduceZeroOrNegativeColumns() {
        assertEquals(3, BrowserColumns.resolve(PosterStyle.POSTER, 360f, 1f))
        assertEquals(2, BrowserColumns.resolve(PosterStyle.POSTER, 240f, 1f))
        assertEquals(1, BrowserColumns.resolve(PosterStyle.POSTER, 0f, 2f))
        assertEquals(3, BrowserColumns.resolve(PosterStyle.POSTER, Float.NaN, Float.NaN))
    }

}

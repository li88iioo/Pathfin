package dev.pathfin.android.ui.theme

import androidx.compose.ui.graphics.Color
import dev.pathfin.android.domain.AccentColor
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    private val extremes = mapOf(
        "red" to Color(0xFFFF0000), "yellow" to Color(0xFFFFFF00), "green" to Color(0xFF00FF00),
        "cyan" to Color(0xFF00FFFF), "blue" to Color(0xFF0000FF), "magenta" to Color(0xFFFF00FF),
        "orange" to Color(0xFFFF8800), "deep-navy" to Color(0xFF0A1440), "pastel-pink" to Color(0xFFF7C6D9),
        "brand" to DefaultWallpaperAccent,
    ) + presets()

    private fun presets() = AccentColor.entries.mapNotNull { preset -> preset.argb?.let { "preset-${preset.name}" to Color(it) } }

    @Test fun accentKeepsReadableContrastOnButtonAndSelectedBackground() {
        for (dark in listOf(false, true)) for ((name, color) in extremes) {
            val p = accentPalette(color, dark)
            assertTrue("$name dark=$dark 主操作前景对比度 ${contrast(p.accent, p.onAccent)}", contrast(p.accent, p.onAccent) >= 4.5f)
            assertTrue("$name dark=$dark 选中底文字对比度 ${contrast(p.accent, p.accentSoft)}", contrast(p.accent, p.accentSoft) >= 4.5f)
        }
    }

    @Test fun accentTextStaysReadableOnPageCanvas() {
        for ((name, color) in extremes) {
            assertTrue(name, contrast(accentPalette(color, false).accent, Color(0xFFF5F6F8)) >= 4.5f)
            assertTrue(name, contrast(accentPalette(color, false).accent, Color.White) >= 4.5f)
            assertTrue(name, contrast(accentPalette(color, true).accent, Color(0xFF0F0F11)) >= 4.5f)
        }
    }

    @Test fun accentIsVividButNotFluorescentAndKeepsItsHue() {
        for (dark in listOf(false, true)) for ((name, color) in extremes) {
            val p = accentPalette(color, dark)
            val (hue, saturation, _) = hsl(p.accent)
            assertTrue("$name dark=$dark 主色饱和度 $saturation 须一眼可辨且不过荧光", saturation in 0.44f..0.79f)
            assertEquals("$name 主色须保留壁纸色相", hsl(color).first, hue, 3f)
        }
    }

    @Test fun selectedFillIsVisibleWhileWashStaysQuiet() {
        for ((name, color) in extremes) {
            val light = accentPalette(color, false)
            val dark = accentPalette(color, true)
            assertTrue("$name 浅色选中底保持浅色", light.accentSoft.luminance() > 0.7f)
            assertTrue("$name 深色选中底保持深色", dark.accentSoft.luminance() < 0.06f)
            assertTrue("$name 选中底须看得出色相", hsl(light.accentSoft).second >= 0.44f && hsl(dark.accentSoft).second >= 0.3f)
            assertTrue("$name 选中底须与画布拉开", contrast(light.accentSoft, Color(0xFFF5F6F8)) >= 1.05f &&
                contrast(dark.accentSoft, Color(0xFF0F0F11)) > 1.1f)
            assertTrue("$name 环境光不透明度不得高于 12%", light.accentWash.alpha <= 0.12f && dark.accentWash.alpha <= 0.12f)
            assertTrue("$name 投影为半透明", light.accentShadow.alpha in 0.1f..0.5f && dark.accentShadow.alpha in 0.1f..0.5f)
            assertEquals(1f, light.accentSoft.alpha)
        }
    }

    @Test fun achromaticWallpapersFallBackToBrandBlue() {
        val cream = Color(0xFFFFF8F0)
        for (dark in listOf(false, true)) {
            val brand = accentPalette(DefaultWallpaperAccent, dark)
            for (color in listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFF1A1B1C), cream)) {
                val palette = accentPalette(color, dark)
                assertTrue("$color 须标记为无彩色来源，供设置页说明", palette.achromaticSource)
                assertEquals("$color dark=$dark", brand, palette.copy(achromaticSource = false))
            }
        }
    }

    @Test fun defaultBrandStaysCloseToTheExistingBlue() {
        assertFalse(accentPalette(DefaultWallpaperAccent, false).achromaticSource)
        val (hue, _, _) = hsl(accentPalette(DefaultWallpaperAccent, false).accent)
        assertEquals(hsl(DefaultWallpaperAccent).first, hue, 2f)
        assertEquals(accentPalette(DefaultWallpaperAccent, true), accentPalette(DefaultWallpaperAccent, true))
    }

    @Test fun presetsAreChromaticAndStayDistinctAfterCorrection() {
        assertEquals("默认预置色即品牌蓝", DefaultWallpaperAccent, Color(AccentColor.PATHFIN.argb!!))
        assertEquals("壁纸取色不带固定色值", null, AccentColor.WALLPAPER.argb)
        for (dark in listOf(false, true)) {
            val hues = presets().map { (name, color) ->
                val palette = accentPalette(color, dark)
                assertFalse("$name 不得退回默认蓝", palette.achromaticSource)
                name to hsl(palette.accent).first
            }
            for ((i, a) in hues.withIndex()) for (b in hues.drop(i + 1)) {
                val distance = kotlin.math.abs(a.second - b.second).let { minOf(it, 360f - it) }
                assertTrue("${a.first} 与 ${b.first} dark=$dark 色相只差 $distance°，选项难以区分", distance >= 20f)
            }
        }
    }

    private fun hsl(color: Color): Triple<Float, Float, Float> {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val l = (max + min) / 2f
        val d = max - min
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when (max) {
            color.red -> 60f * (((color.green - color.blue) / d).mod(6f))
            color.green -> 60f * ((color.blue - color.red) / d + 2f)
            else -> 60f * ((color.red - color.green) / d + 4f)
        }
        return Triple(h, s, l)
    }
}

package dev.pathfin.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 由壁纸主体色派生的强调色令牌：
 * accent 用于进度、选中图标/文字、状态点和主操作；accentSoft 是看得出色相的选中底；
 * accentWash 是页面角落环境光（不透明度 ≤ 12%）；accentShadow 是主操作的彩色投影。
 */
@Immutable
data class AccentPalette(
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentWash: Color,
    val accentShadow: Color,
    /** 输入的壁纸色没有可用色相（黑白灰或系统单色主题），已退回品牌蓝。 */
    val achromaticSource: Boolean = false,
)

internal val DefaultWallpaperAccent = Color(0xFF2563EB)

private const val MinChroma = 0.10f
private const val TextContrast = 4.5f
private const val SoftLightSeparation = 1.06f
private const val SoftDarkSeparation = 1.15f

fun accentPalette(wallpaperAccentColor: Color, dark: Boolean): AccentPalette {
    // 黑白灰及近白近黑壁纸没有可靠色相，退回品牌蓝；高饱和的红黄绿压到可读区间。
    val achromatic = wallpaperAccentColor.chroma() < MinChroma
    val source = if (achromatic) DefaultWallpaperAccent else wallpaperAccentColor
    val (hue, saturation) = source.hueAndSaturation()
    // 强调色是中性界面里唯一的彩色，须一眼可辨：饱和度抬到中高区间，只削掉荧光级的极端值。
    val sat = saturation.coerceIn(0.45f, 0.78f)
    val onAccent = if (dark) Color(0xFF0F0F11) else Color.White
    val canvas = if (dark) Color(0xFF0F0F11) else Color(0xFFF5F6F8)
    // 选中底按明度与画布拉开：黄、青等高亮色相须再压暗，才不会在浅灰画布上糊成一片。
    val softSat = if (dark) minOf(sat, 0.45f) else minOf(sat, 0.65f)
    var softLightness = if (dark) 0.19f else 0.92f
    while (softLightness in 0.12f..0.95f &&
        contrast(Color.hsl(hue, softSat, softLightness), canvas) < (if (dark) SoftDarkSeparation else SoftLightSeparation)
    ) softLightness += if (dark) 0.01f else -0.01f
    val soft = Color.hsl(hue, softSat, softLightness.coerceIn(0.12f, 0.95f))
    // 浅色逐步压暗、深色逐步提亮，直到主色在按钮前景、选中底与画布上都达到正文对比度。
    val step = if (dark) 0.02f else -0.02f
    var lightness = if (dark) 0.66f else 0.50f
    while (lightness in 0.16f..0.9f) {
        val candidate = Color.hsl(hue, sat, lightness)
        if (listOf(onAccent, soft, canvas).all { contrast(candidate, it) >= TextContrast }) break
        lightness += step
    }
    return AccentPalette(
        accent = Color.hsl(hue, sat, lightness.coerceIn(0.16f, 0.9f)),
        onAccent = onAccent,
        accentSoft = soft,
        accentWash = Color.hsl(hue, minOf(sat, 0.70f), if (dark) 0.55f else 0.60f, if (dark) 0.11f else 0.09f),
        accentShadow = Color.hsl(hue, sat, 0.42f, if (dark) 0.45f else 0.30f),
        achromaticSource = achromatic,
    )
}

val LocalAccentPalette = staticCompositionLocalOf { accentPalette(DefaultWallpaperAccent, dark = false) }

internal fun contrast(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

private fun Color.chroma(): Float = maxOf(red, green, blue) - minOf(red, green, blue)

private fun Color.hueAndSaturation(): Pair<Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (delta == 0f) return 0f to 0f
    val lightness = (max + min) / 2f
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta).mod(6f))
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return hue to saturation.coerceIn(0f, 1f)
}

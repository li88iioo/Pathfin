package dev.pathfin.android.ui.theme

import dev.pathfin.android.domain.AccentColor

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// 中性面不带暖/冷偏色：浅色为浅灰画布 + 纯白卡片，深色为近黑画布 + 抬亮一档的卡片，层次靠明度差而不是色相。
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFD), onPrimaryContainer = Color(0xFF2563EB),
    inversePrimary = Color(0xFF8FB0FF),
    secondary = Color(0xFF5B5CF0), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF2FF), onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFF0D9488), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1), onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF5F6F8), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE8EAEE), onSurfaceVariant = Color(0xFF5E6368),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF26282B), inverseOnSurface = Color(0xFFF5F6F8),
    error = Color(0xFFDC2626), onError = Color.White,
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFF8C929D), outlineVariant = Color(0xFFE6E8EC),
    scrim = Color.Black,
    surfaceBright = Color.White, surfaceDim = Color(0xFFE6E8EC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1F4),
    surfaceContainerHigh = Color(0xFFE9EBEF),
    surfaceContainerHighest = Color(0xFFE3E6EA),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF6E9BFF), onPrimary = Color(0xFF00296B),
    primaryContainer = Color(0xFF1A2540), onPrimaryContainer = Color(0xFF8FB0FF),
    inversePrimary = Color(0xFF2563EB),
    secondary = Color(0xFF818CF8), onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF312E81), onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF2DD4BF), onTertiary = Color(0xFF134E4A),
    tertiaryContainer = Color(0xFF115E59), onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0F0F11), onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF0F0F11), onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF2A2A2F), onSurfaceVariant = Color(0xFFA9A9B2),
    surfaceTint = Color(0xFF6E9BFF),
    inverseSurface = Color(0xFFEDEDF0), inverseOnSurface = Color(0xFF1B1B1F),
    error = Color(0xFFF87171), onError = Color(0xFF7F1D1D),
    errorContainer = Color(0xFF3A1F22), onErrorContainer = Color(0xFFFEE2E2),
    outline = Color(0xFF6E6E77), outlineVariant = Color(0xFF2A2A2F),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C2C31), surfaceDim = Color(0xFF0F0F11),
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF222226),
    surfaceContainerHigh = Color(0xFF29292E),
    surfaceContainerHighest = Color(0xFF313136),
)

private val PathfinShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val PathfinTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
)

@Composable
fun PathfinTheme(mode: ThemePreference, accentColor: AccentColor = AccentColor.PATHFIN, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 壁纸取色在配置变化及回前台时重新读取；系统只提供这一个颜色，中性面全部使用固定令牌，保证每页色温一致。
    val (scheme, palette) = remember(context, configuration, dark, resumeGeneration, accentColor) {
        val source = accentColor.argb?.let { Color(it) } ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { dynamicLightColorScheme(context).primary } catch (_: Resources.NotFoundException) { null }
        } else null
        val base = if (dark) FallbackDark else FallbackLight
        val palette = accentPalette(source ?: DefaultWallpaperAccent, dark)
        base.copy(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accent,
            surfaceTint = palette.accent,
        ) to palette
    }
    MaterialTheme(colorScheme = scheme, shapes = PathfinShapes, typography = PathfinTypography) {
        CompositionLocalProvider(LocalAccentPalette provides palette, LocalIndication provides SoftPressIndication, content = content)
    }
}

package dev.pathfin.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.pathfin.android.ui.theme.LocalAccentPalette

internal val LocalPageTopInset = staticCompositionLocalOf { 0.dp }

/** 双击标题栏的计数；列表页记下进入时的值，只在之后变化时滚回顶部。 */
internal val LocalScrollToTopRequests = staticCompositionLocalOf<androidx.compose.runtime.State<Int>> { androidx.compose.runtime.mutableIntStateOf(0) }

/**
 * 页面设计令牌。强调色族由 [LocalAccentPalette] 按壁纸主体色派生（未开启壁纸配色时以品牌蓝为输入），
 * 并同步写入 MaterialTheme.colorScheme.primary 系列。
 */
internal object PathfinAppearance {
    @Composable
    fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val canvas: Color @Composable get() = MaterialTheme.colorScheme.surface
    val card: Color @Composable get() = if (isDark()) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest
    val textMain: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val textSub: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val textMuted: Color @Composable get() = if (isDark()) Color(0xFF7C7C85) else Color(0xFF8C929D)
    // 浅色实灰分隔线放在半透明玻璃上会发白，在玻璃面板里换成随前景走的半透明细线。
    val divider: Color @Composable get() = when {
        isDark() -> Color(0x1AFFFFFF)
        LocalOnGlass.current -> Color.Black.copy(alpha = 0.08f)
        else -> Color(0xFFECEEF2)
    }
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val onAccent: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
    // 实色浅填充放在半透明玻璃上像贴了一块，在玻璃面板里换成随前景走的半透明填充。
    val softFill: Color @Composable get() = when {
        LocalOnGlass.current -> if (isDark()) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
        isDark() -> Color(0xFF232327)
        else -> Color(0xFFEEF0F3)
    }
    /** 图片读取中的占位：与页面同一色调的浅块，玻璃面板里换成半透明填充。 */
    val imageLoading: Color @Composable get() = when {
        LocalOnGlass.current -> softFill
        isDark() -> Color(0xFF26272C)
        else -> Color(0xFFE6E8EC)
    }
    val segmentTrack: Color @Composable get() = if (isDark()) Color(0xFF2C2C31) else Color(0xFFE5E7EB)
    val accentSoft: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val accentWash: Color @Composable get() = LocalAccentPalette.current.accentWash
    val accentShadow: Color @Composable get() = LocalAccentPalette.current.accentShadow
    val tintRed: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
    /** 半透明玻璃（底栏、菜单）的极淡细边：卡片描边压在深色画面上会是一圈刺眼的白框。 */
    val glassBorder: BorderStroke @Composable get() = BorderStroke(
        0.5.dp,
        if (isDark()) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
    )
    val cardBorder: BorderStroke @Composable get() = BorderStroke(
        1.dp,
        if (isDark()) Color(0x14FFFFFF) else Color(0xFFECEEF2),
    )

}

@Composable
internal fun PageTitleBar(modifier: Modifier = Modifier,
    height: Dp, visible: Boolean = true, dark: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    TopBarBlurSurface(modifier, height = height, visible = visible, dark = dark) {
        CompositionLocalProvider(LocalContentColor provides when {
            dark -> Color.White
            visible -> MaterialTheme.colorScheme.onSurface
            else -> LocalContentColor.current
        }) {
            content()
        }
    }
}

package dev.pathfin.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
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
    val divider: Color @Composable get() = if (isDark()) Color(0x1AFFFFFF) else Color(0xFFECEEF2)
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val onAccent: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
    val softFill: Color @Composable get() = if (isDark()) Color(0xFF232327) else Color(0xFFEEF0F3)
    val segmentTrack: Color @Composable get() = if (isDark()) Color(0xFF2C2C31) else Color(0xFFE5E7EB)
    val accentSoft: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val accentWash: Color @Composable get() = LocalAccentPalette.current.accentWash
    val accentShadow: Color @Composable get() = LocalAccentPalette.current.accentShadow
    val tintRed: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
    val cardBorder: BorderStroke @Composable get() = BorderStroke(
        1.dp,
        if (isDark()) Color(0x14FFFFFF) else Color(0xFFECEEF2),
    )

    @Composable
    fun switchColors(): SwitchColors = SwitchDefaults.colors(
        checkedThumbColor = onAccent,
        checkedTrackColor = accent,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = if (isDark()) Color(0xFFA9A9B2) else Color(0xFF7A7A83),
        uncheckedTrackColor = softFill,
        uncheckedBorderColor = if (isDark()) Color(0xFF3A3A40) else Color(0xFFD6D6DC),
        disabledCheckedThumbColor = onAccent.copy(alpha = 0.6f),
        disabledCheckedTrackColor = accent.copy(alpha = 0.4f),
        disabledUncheckedThumbColor = textMuted.copy(alpha = 0.4f),
        disabledUncheckedTrackColor = softFill.copy(alpha = 0.5f),
        disabledUncheckedBorderColor = Color.Transparent,
    )
}

@Composable
internal fun PageTitleBar(modifier: Modifier = Modifier,
    height: Dp, visible: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    TopBarBlurSurface(modifier, height = height, visible = visible) {
        CompositionLocalProvider(LocalContentColor provides if (visible) MaterialTheme.colorScheme.onSurface else LocalContentColor.current) {
            content()
        }
    }
}

@Composable
internal fun elevatedCardColor(): Color = PathfinAppearance.card

@Composable
internal fun elevatedCardBorder(highlighted: Boolean = false): BorderStroke =
    if (highlighted) BorderStroke(1.dp, PathfinAppearance.accent.copy(alpha = 0.45f))
    else PathfinAppearance.cardBorder

@Composable
internal fun dialogSurfaceColor(): Color = PathfinAppearance.card

@Composable
internal fun refinedTextFieldColors(): TextFieldColors {
    val dark = PathfinAppearance.isDark()
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = if (dark) Color(0xFF232327) else Color.White,
        unfocusedContainerColor = PathfinAppearance.softFill.copy(alpha = if (dark) 0.7f else 0.65f),
        disabledContainerColor = PathfinAppearance.softFill.copy(alpha = 0.35f),
        focusedBorderColor = PathfinAppearance.accent,
        unfocusedBorderColor = if (dark) Color(0xFF33333A) else Color(0xFFE5E7EB),
        focusedLabelColor = PathfinAppearance.accent,
        unfocusedLabelColor = PathfinAppearance.textSub,
    )
}

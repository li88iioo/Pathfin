package dev.pathfin.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class PlayerTimeLayout(val width: Dp, val style: TextStyle)

/** 按完整时长一次预留宽度，位置跳动及横竖切换不依赖文字的最小内在宽度。 */
@Composable
internal fun rememberPlayerTimeLayout(durationMs: Long, maximumWidth: Dp): PlayerTimeLayout {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(durationMs, maximumWidth, density, measurer) {
        val end = if (durationMs > 0) playerTime(durationMs) else "--:--"
        val sample = "$end / $end"
        val base = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            lineHeight = 16.sp, letterSpacing = 0.sp)
        val paddingPx = with(density) { 8.dp.toPx() }
        val availablePx = with(density) { maximumWidth.toPx() }.coerceAtLeast(paddingPx + 1f)
        val measured = measurer.measure(sample, style = base, softWrap = false, maxLines = 1)
        // 大字号和长时长先保留滑条触摸空间，仅在时间本身放不下时缩放该行。
        val scale = ((availablePx - paddingPx) / measured.size.width.coerceAtLeast(1)).coerceIn(.1f, 1f)
        val style = base.copy(fontSize = (12 * scale).sp, lineHeight = (16 * scale).sp)
        val fitted = measurer.measure(sample, style = style, softWrap = false, maxLines = 1)
        PlayerTimeLayout(with(density) { (fitted.size.width + paddingPx).coerceAtMost(availablePx).toDp() }, style)
    }
}

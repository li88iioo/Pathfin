package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** 每个切页页面独立提供，进出中的两页不能共用采样源或标题范围。 */
internal val LocalTopBarBlurState = compositionLocalOf<TopBarBlurState?> { null }

@Stable
internal class TopBarBlurState internal constructor(
    internal val source: HazeState,
    private val enabledState: State<Boolean>,
) {
    private data class Region(val top: Dp, val bottom: Dp, val secondaryRow: Boolean)

    private val regions = mutableStateMapOf<Any, Region>()

    val enabled: Boolean get() = enabledState.value
    internal val minimumTop: Dp get() = regions.values.minOfOrNull { it.top } ?: 0.dp
    internal val maximumBottom: Dp get() = regions.values.maxOfOrNull { it.bottom } ?: 0.dp
    internal val hasSecondaryRow: Boolean get() = regions.values.any { it.secondaryRow }

    internal fun updateRegion(owner: Any, top: Dp, bottom: Dp, secondaryRow: Boolean = false) {
        regions[owner] = Region(top, bottom, secondaryRow)
    }

    internal fun removeRegion(owner: Any) {
        regions.remove(owner)
    }

}

@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun rememberTopBarBlurState(enabled: Boolean): TopBarBlurState {
    val source = rememberHazeState()
    val enabledState = rememberUpdatedState(enabled)
    return remember(source, enabledState) { TopBarBlurState(source, enabledState) }
}

/**
 * 只放在正文容器上，标题背景和前景控件都必须在采样范围之外。
 * 套在外层采样源里的页内采样源（如连接页底图、服务器主卡拼贴）须用负 zIndex，否则 Haze 会把它当作祖先层级排除。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun Modifier.topBarBlurSource(zIndex: Float = 0f): Modifier {
    val state = LocalTopBarBlurState.current
    return if (state?.enabled == true) hazeSource(state.source, zIndex) else this
}

/**
 * height 和 topOffset 是页面坐标中的已知栏高、起点，仅决定材质范围，不参与布局测量。
 * 正文和前景保持原尺寸；关闭、透明或还没有采样源时也不改变触摸范围。
 */
@Composable
internal fun TopBarBlurSurface(
    modifier: Modifier = Modifier,
    topOffset: Dp = 0.dp,
    height: Dp,
    visible: Boolean = true,
    secondaryRow: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = LocalTopBarBlurState.current
    val density = LocalDensity.current
    val layoutHeight = with(density) { height.roundToPx().coerceAtLeast(0).toDp() }
    val knownBottom = topOffset + layoutHeight
    val owner = remember { Any() }

    if (state != null) {
        DisposableEffect(state, owner) {
            onDispose { state.removeRegion(owner) }
        }
        SideEffect {
            if (visible) state.updateRegion(owner, topOffset, knownBottom, secondaryRow)
            else state.removeRegion(owner)
        }
    }

    Box(modifier) {
        if (visible) {
            if (state?.enabled == true) {
                TopBarBlurMaterial(state, topOffset, knownBottom)
            } else {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface))
            }
        }
        content()
    }
}

/** 底栏共享本页正文源，但不参与顶部渐隐范围；前景按钮保持固定主题色。 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun NavigationBarSurface(
    modifier: Modifier = Modifier,
    tintAlpha: Float = 0.80f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().clipToBounds().frostedCard(tintAlpha))
        content()
    }
}

/** 浮在内容上的菜单、输入框、分组卡与次要按钮：比底栏更透、糊得更开，让底下的颜色透出来。 */
@Composable
internal fun Modifier.frostedControl(card: Color = PathfinAppearance.card): Modifier = frostedCard(0.50f, card, 32.dp)

/** 承载大段文字的抽屉与对话框：浓度接近底栏保证可读，模糊半径与浮层控件一致。 */
@Composable
internal fun Modifier.frostedPanel(card: Color = PathfinAppearance.card): Modifier = frostedCard(0.88f, card, 32.dp)

/** 当前页面是否启用毛玻璃；播放器等没有采样源的界面恒为 false。 */
internal val frostedEnabled: Boolean @Composable get() = LocalTopBarBlurState.current?.enabled == true

/** 采样本页正文的毛玻璃卡片底，底栏与浮层菜单共用；模糊关闭或无采样源时铺 fallback（默认实色卡片）。 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun Modifier.frostedCard(tintAlpha: Float = 0.80f, card: Color = PathfinAppearance.card, radius: Dp = 20.dp,
    fallback: Color = card): Modifier {
    val state = LocalTopBarBlurState.current
    val canvas = PathfinAppearance.canvas
    return if (state?.enabled == true) hazeBlur(
        input = HazeInput.Sources(state.source, retention = HazeSourceRetention.ClearWhenUnavailable),
        style = HazeBlurStyle {
            backgroundColor(canvas)
            colorEffects(listOf(HazeColorEffect.tint(card.copy(alpha = tintAlpha))))
            blurRadius(radius)
            noiseFactor(0f)
            fallbackColorEffect(HazeColorEffect.tint(fallback))
            progressive(null)
            mask(null)
        },
        performanceMode = HazePerformanceMode.Quality,
    ) else background(fallback)
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun BoxScope.TopBarBlurMaterial(
    state: TopBarBlurState,
    topOffset: Dp,
    knownBottom: Dp,
) {
    val canvas = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val bounds by remember(state, topOffset, knownBottom) {
        derivedStateOf {
            (minOf(state.minimumTop, topOffset) - topOffset) to
                (maxOf(state.maximumBottom, knownBottom) - topOffset)
        }
    }
    val start = with(density) { bounds.first.toPx() }
    val end = with(density) { bounds.second.toPx() }.coerceAtLeast(start + 1f)
    val hasToolbar by remember(state) { derivedStateOf { state.hasSecondaryRow } }
    val fadeHeight = with(density) { 28.dp.roundToPx() }
    val ramp = remember(hasToolbar) {
        if (hasToolbar) List(17) { index ->
            val t = index / 16f
            t * t * (3f - 2f * t)
        } else listOf(0f, 1f)
    }
    // 工具栏控件后方保留模糊以消除片名细节，只在最后28dp平滑收边。
    // 遮色独立贯穿整栏渐隐，不能与这个遮罩相乘，否则会重新形成底部浓白雾带。
    val fade = remember(start, end, hasToolbar, fadeHeight, ramp) {
        val colors = ramp.map { Color.Black.copy(alpha = 1f - it) }
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val bottom = maxOf(end, size.height)
                return LinearGradientShader(
                    from = Offset(0f, if (hasToolbar) maxOf(start, bottom - fadeHeight)
                        else start + (bottom - start) * 0.60f),
                    to = Offset(0f, bottom),
                    colors = colors,
                )
            }
        }
    }
    val veil = remember(canvas, start, end) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader = LinearGradientShader(
                from = Offset(0f, start),
                to = Offset(0f, maxOf(end, size.height)),
                colors = listOf(canvas, canvas.copy(alpha = 0f)),
            )
        }
    }

    Box(
        Modifier.matchParentSize()
            .clipToBounds()
            .hazeBlur(
                input = HazeInput.Sources(state.source, retention = HazeSourceRetention.ClearWhenUnavailable),
                style = HazeBlurStyle {
                    backgroundColor(canvas)
                    colorEffects(emptyList())
                    blurRadius(20.dp)
                    noiseFactor(0f)
                    fallbackColorEffect(HazeColorEffect.tint(canvas))
                    progressive(null)
                    mask(fade)
                },
                performanceMode = HazePerformanceMode.Quality,
            )
            .drawBehind { drawRect(veil) },
    )
}

package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 底部抽屉画在主窗口的顶层，而不是每次开关都新建、销毁一个系统窗口：
 * 省掉窗口创建/拆除的跨进程开销，毛玻璃也直接采样同一窗口里的页面。
 */
internal class SheetHostState {
    internal val entries = mutableStateListOf<SheetEntry>()
}

internal class SheetEntry {
    var locals by mutableStateOf<CompositionLocalContext?>(null)
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

internal val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/** 由最外层 PathfinTheme 提供；嵌套主题复用外层宿主，抽屉总在整窗最上层。 */
@Composable
internal fun SheetHost(content: @Composable () -> Unit) {
    if (LocalSheetHost.current != null) {
        content()
        return
    }
    val host = remember { SheetHostState() }
    CompositionLocalProvider(LocalSheetHost provides host) {
        // 宿主本身不读抽屉列表：开关抽屉只重组 SheetEntries，不会把整页 content 再执行一遍。
        Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
            Box(Modifier.fillMaxSize().then(SheetBackdropSemantics(host))
                .focusProperties { onEnter = { if (host.entries.isNotEmpty()) cancelFocusChange() } }
                .focusGroup(), propagateMinConstraints = true) { content() }
            SheetEntries(host)
        }
    }
}

@Composable
private fun SheetEntries(host: SheetHostState) {
    host.entries.forEach { entry ->
        key(entry) {
            val locals = entry.locals
            val body = entry.content
            if (locals != null && body != null) CompositionLocalProvider(locals) { body() }
        }
    }
}

/** 抽屉打开时底页对读屏不可见，如同原先的独立窗口；只失效语义，不触发底页重组。 */
private data class SheetBackdropSemantics(val host: SheetHostState) : ModifierNodeElement<SheetBackdropSemanticsNode>() {
    override fun InspectorInfo.inspectableProperties() {
        name = "sheetBackdropSemantics"
        properties["hidden"] = host.entries.isNotEmpty()
    }

    override fun create() = SheetBackdropSemanticsNode(host)
    override fun update(node: SheetBackdropSemanticsNode) {
        node.host = host
        node.refresh()
    }
}

private class SheetBackdropSemanticsNode(var host: SheetHostState) : Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
    private var hidden = false
    override val shouldClearDescendantSemantics: Boolean get() = hidden

    override fun onAttach() = refresh()

    override fun onObservedReadsChanged() = refresh()

    fun refresh() {
        var next = false
        observeReads { next = host.entries.isNotEmpty() }
        if (next != hidden) {
            hidden = next
            invalidateSemantics()
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() = Unit
}

/**
 * App 内所有底部弹层的入口。dismissEnabled 为 false 时（如正在写入），拖动、遮罩与返回都不能关闭。
 * 打开与关闭都有滑动动画；调用方直接移出组合时与系统弹层一样立即消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { AppSafeDrawingInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = checkNotNull(LocalSheetHost.current) { "AppModalBottomSheet 必须位于 PathfinTheme 内" }
    val locals = currentCompositionLocalContext
    val entry = remember { SheetEntry() }
    SideEffect {
        entry.locals = locals
        entry.content = {
            SheetLayout(onDismissRequest, { host.entries.lastOrNull() === entry }, modifier, dismissEnabled, shape, containerColor, contentColor, scrimColor,
                dragHandle, contentWindowInsets, content)
        }
    }
    DisposableEffect(host, entry) {
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}

private enum class SheetPosition { Hidden, Expanded }

private val SheetMotion = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetLayout(
    onDismissRequest: () -> Unit,
    isTopSheet: () -> Boolean,
    modifier: Modifier,
    dismissEnabled: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    scrimColor: Color,
    dragHandle: @Composable (() -> Unit)?,
    contentWindowInsets: @Composable () -> WindowInsets,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { AnchoredDraggableState(SheetPosition.Hidden) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestEnabled by rememberUpdatedState(dismissEnabled)
    val focusManager = LocalFocusManager.current
    val paneFocus = remember { FocusRequester() }
    val active = isTopSheet()
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    val flingVelocity = with(LocalDensity.current) { 125.dp.toPx() }

    // 同窗抽屉接管焦点，但不自动打开键盘；分组边界同时阻止 Tab 回到底页或下层抽屉。
    LaunchedEffect(active) {
        if (active) {
            focusManager.clearFocus(force = true)
            paneFocus.requestFocus()
        }
    }
    // 忙态变化重新接管动画并回到展开位置，写入开始时不能继续滑出屏幕。
    // opened 记录滑入曾经启动，覆盖滑入中途被返回打断、settledValue 仍为 Hidden 的情况。
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(state, dismissEnabled) {
        snapshotFlow { sheetHeight > 0f }.first { it }
        try {
            state.animateTo(SheetPosition.Expanded, SheetMotion)
        } finally {
            opened = true
        }
    }
    LaunchedEffect(state) {
        // 同一隐藏状态只通知一次；业务暂时拒绝关闭后，恢复展开仍保留下一次关闭的监听。
        snapshotFlow { opened && latestEnabled && !state.isAnimationRunning && !state.offset.isNaN() && state.offset >= sheetHeight - 0.5f }
            .collect { hidden -> if (hidden) latestDismiss() }
    }
    fun close() {
        if (latestEnabled) scope.launch { state.animateTo(SheetPosition.Hidden, SheetMotion) }
    }
    suspend fun settle(velocity: Float) {
        val target = when {
            velocity > flingVelocity -> SheetPosition.Hidden
            velocity < -flingVelocity -> SheetPosition.Expanded
            state.requireOffset() > sheetHeight / 2 -> SheetPosition.Hidden
            else -> SheetPosition.Expanded
        }
        state.animateTo(target, SheetMotion)
    }
    // 内部列表滚到顶后继续下拉才移动抽屉；上推不会把抽屉推过展开位置。
    val nestedScroll = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (latestEnabled && available.y < 0 && source == NestedScrollSource.UserInput)
                    Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val moved = if (latestEnabled) state.dispatchRawDelta(available.y) else 0f
                // 列表到底后消费上推剩余量，不把它继续交给边缘拉伸；下拉仍只消费抽屉实际位移。
                return Offset(0f, if (available.y < 0f) available.y else moved)
            }

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (latestEnabled && available.y < 0 && state.requireOffset() > 0f) { settle(available.y); available }
                else Velocity.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (latestEnabled && state.offset > 0f) {
                    settle(available.y)
                    return available
                }
                return Velocity(0f, available.y.coerceAtMost(0f))
            }
        }
    }
    BackHandler(enabled = active) { close() }

    val insets = contentWindowInsets()
    val tint = if (PathfinAppearance.isDark()) 0.50f else 0.72f
    Box(Modifier.fillMaxSize()
        .focusProperties { onEnter = { if (!isTopSheet()) cancelFocusChange() } }.focusGroup()) {
        Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    val visible = if (sheetHeight <= 0f || state.offset.isNaN()) 0f
                        else (1f - state.offset / sheetHeight).coerceIn(0f, 1f)
                    drawRect(scrimColor.copy(alpha = scrimColor.alpha * visible))
                }
                .pointerInput(Unit) { detectTapGestures { close() } }
                .semantics { onClick(tr("关闭")) { close(); true } },
        )
        Column(
            modifier.align(Alignment.BottomCenter).widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxWidth()
                .focusRequester(paneFocus).focusable()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val height = placeable.height.toFloat()
                    if (height != sheetHeight) {
                        sheetHeight = height
                        state.updateAnchors(DraggableAnchors {
                            SheetPosition.Expanded at 0f
                            SheetPosition.Hidden at height
                        })
                    }
                    layout(placeable.width, placeable.height) {
                        val offset = state.offset.takeUnless(Float::isNaN) ?: height
                        placeable.place(0, offset.roundToInt())
                    }
                }
                .nestedScroll(nestedScroll)
                .anchoredDraggable(state, Orientation.Vertical, enabled = dismissEnabled,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = SheetMotion))
                .clip(shape)
                .frostedCard(tint, containerColor, 32.dp)
                // 抽屉贴底，状态栏高度不属于它；内部按整窗安全区避让时只保留左右、底部与键盘。
                .consumeWindowInsets(AppSafeDrawingInsets.only(WindowInsetsSides.Top))
                .windowInsetsPadding(insets),
        ) {
            dragHandle?.let { handle -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { handle() } }
            CompositionLocalProvider(LocalContentColor provides contentColor, LocalOnGlass provides frostedEnabled) {
                Column(content = content)
            }
        }
    }
}

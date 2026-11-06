@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeSource
import dev.pathfin.android.i18n.tr

internal val LocalPageRefreshEnabled = compositionLocalOf { true }

/** 刷新只覆盖指示器，不替换列表，也不为后台请求占据页面高度。 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun RefreshablePage(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshOnEntry: Boolean = true,
    enabled: Boolean = true,
    indicatorTopInset: Dp = LocalPageTopInset.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullEnabled = LocalAppPreferences.current.pullToRefreshEnabled
    val canRefresh = enabled && LocalPageRefreshEnabled.current
    val pullState = rememberPullToRefreshState()
    val indicatorBlur = if (frostedEnabled) rememberTopBarBlurState() else null
    val pulling by remember(pullState) { derivedStateOf { pullState.distanceFraction > 0f } }
    var requested by remember { mutableStateOf(false) }
    val currentBusy by rememberUpdatedState(refreshing)
    val currentRefresh by rememberUpdatedState(onRefresh)
    val currentEntry by rememberUpdatedState(refreshOnEntry)
    val currentPullEnabled by rememberUpdatedState(pullEnabled && canRefresh)
    val busyScroll = remember(pullState) { object : NestedScrollConnection {
        // M3 忙态不消费下拉；在内容区截住剩余手势，避免它被外层 sheet 当成关闭。
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (currentPullEnabled && (currentBusy || pullState.isAnimating) && source == NestedScrollSource.UserInput)
                Offset(0f, available.y.coerceAtLeast(0f)) else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (currentPullEnabled && (currentBusy || pullState.isAnimating))
                Velocity(0f, available.y.coerceAtLeast(0f)) else Velocity.Zero
    } }
    val owner = LocalLifecycleOwner.current
    // addObserver 会立即补发当前生命周期；本地已负责首读的页面跳过这次补发。
    DisposableEffect(owner, pullEnabled, canRefresh) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!pullEnabled && canRefresh && (!firstResume || currentEntry) && !currentBusy) currentRefresh()
                firstResume = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(requested, refreshing) {
        if (requested && !refreshing) {
            // 包括瞬时完成和无需请求的空搜索，避免指示器等待不存在的状态变更。
            withFrameNanos { }
            requested = false
        }
    }
    val request: () -> Unit = {
        if (pullEnabled && canRefresh && !currentBusy && !requested) {
            requested = true
            currentRefresh()
        }
    }
    val gestureRefreshing = requested && refreshing
    Box(modifier.testTag("refreshable-page")
        .nestedScroll(busyScroll)
        .pullToRefresh(gestureRefreshing, pullState, enabled = pullEnabled && canRefresh && !refreshing, onRefresh = request)
        .semantics {
            if (pullEnabled && canRefresh) customActions = listOf(CustomAccessibilityAction(tr("刷新内容")) { request(); true })
        }) {
        // 指示器独占采样源，正文继续继承页面状态，避免弹层前景混入标题栏和面板的背景。
        Box(Modifier.fillMaxSize().then(if (pullEnabled && canRefresh && indicatorBlur != null)
            Modifier.hazeSource(indicatorBlur.source) else Modifier),
            content = content)
        // 未下拉时不挂载效果，Haze 会释放无人采样的整页图层。
        if (pullEnabled && canRefresh && (gestureRefreshing || pulling)) {
            CompositionLocalProvider(LocalTopBarBlurState provides indicatorBlur) {
                RefreshIndicator(pullState, gestureRefreshing, Modifier.align(Alignment.TopCenter).padding(top = indicatorTopInset))
            }
        }
    }
}

/**
 * 下拉指示器：毛玻璃小圆加细描边，细线进度环随下拉量画满，刷新中换成细线转圈。
 * 从标题栏下方原位淡入：跟手下滑并由小放大，收起时原路缩小淡出，不做顶边裁切。
 */
@Composable
private fun RefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier) {
    Box(
        modifier.padding(top = 8.dp)
            .graphicsLayer {
                val fraction = (if (refreshing) 1f else state.distanceFraction).coerceIn(0f, 1f)
                translationY = fraction * 52.dp.toPx()
                alpha = fraction
                scaleX = 0.72f + 0.28f * fraction
                scaleY = scaleX
            }
            .size(38.dp)
            .clip(CircleShape)
            .frostedControl()
            .border(PathfinAppearance.glassBorder, CircleShape)
            .testTag("pull-refresh-indicator"),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) PathfinSpinner(PathfinAppearance.accent, Modifier.size(20.dp))
        else PathfinProgressRing(state.distanceFraction, Modifier.size(20.dp))
    }
}

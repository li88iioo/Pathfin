@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.pathfin.android.i18n.tr

internal val LocalPageRefreshEnabled = compositionLocalOf { true }

/** 刷新只覆盖指示器，不替换列表，也不为后台请求占据页面高度。 */
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
        content()
        if (pullEnabled && canRefresh) RefreshIndicator(pullState, gestureRefreshing,
            Modifier.align(Alignment.TopCenter).padding(top = indicatorTopInset))
    }
}

/**
 * 下拉指示器：细线进度环随下拉量画满，刷新中换成细线转圈。它位于页面采样源内部无法采样自身，
 * 毛玻璃开启时改为半透明卡片底加细描边、去掉投影，与周围的毛玻璃控件同一质感。
 */
@Composable
private fun RefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier) {
    val frosted = frostedEnabled
    val card = PathfinAppearance.card
    PullToRefreshDefaults.IndicatorBox(state, refreshing, modifier.testTag("pull-refresh-indicator"), shape = CircleShape,
        containerColor = if (frosted) card.copy(alpha = 0.72f) else card,
        elevation = if (frosted) 0.dp else PullToRefreshDefaults.Elevation) {
        Box(Modifier.matchParentSize().border(PathfinAppearance.cardBorder, CircleShape))
        if (refreshing) PathfinSpinner(PathfinAppearance.accent, Modifier.size(20.dp))
        else PathfinProgressRing(state.distanceFraction, Modifier.size(20.dp))
    }
}

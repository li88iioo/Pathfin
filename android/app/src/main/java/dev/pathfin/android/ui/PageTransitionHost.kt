package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics

internal val LocalPageActive = compositionLocalOf { true }

private class PageEntry(val key: String, val depth: Int, val direction: Int, var snapshot: AppState)

private val rootTabs = setOf(Route.Home, Route.Search, Route.Favorites)
private val AppState.isRootTab: Boolean
    get() = session != null && !showServers && routes.size == 1 && route in rootTabs

/** 路由是动画目标，网络数据更新不启动动画；离场页只保留最后一帧的数据与滚动状态。 */
@Composable
internal fun PageTransitionHost(state: AppState, content: @Composable (AppState) -> Unit) {
    // 启动恢复是首屏初始化，不是从默认 Home 返回服务器页；等会话就绪后再创建路由动画。
    // MainActivity 的首帧门控会在这期间保留系统启动页。
    if (state.restoringSession) return
    key(state.currentProfileId) {
        // 三个底栏页面属于同一层级，在这个目标内按需保留组合；进入详情仍沿用原有推入/推出。
        val pageKey = if (state.isRootTab) "root-tabs" else "${state.showServers}/${state.route.key}"
        val previous = remember { arrayOf<PageEntry?>(null) }
        val entry = remember(pageKey) {
            val old = previous[0]
            val depth = if (state.showServers && state.routes.size <= 1) 0 else state.routes.size
            val direction = when {
                old == null || old.depth == depth -> 0
                depth > old.depth -> 1
                else -> -1
            }
            PageEntry(pageKey, depth, direction, state).also { previous[0] = it }
        }
        SideEffect { entry.snapshot = state }
        val transition = updateTransition(entry, label = "page-navigation")
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            contentKey = { it.key },
            transitionSpec = {
                val direction = targetState.direction
                if (direction == 0) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                else {
                    val timing = tween<androidx.compose.ui.unit.IntOffset>(
                        durationMillis = if (direction > 0) 280 else 300,
                        easing = if (direction > 0) CubicBezierEasing(.2f, 0f, 0f, 1f) else CubicBezierEasing(.4f, 0f, .2f, 1f),
                    )
                    (slideInHorizontally(timing) { it * direction } togetherWith
                        slideOutHorizontally(timing) { -it * direction }).using(null)
                }
            },
        ) { page ->
            val active = page === entry
            val displayed = if (active) state else page.snapshot
            if (displayed.isRootTab) RootTabPages(displayed, active, content)
            else PageContent(displayed, active, content)
        }
        // 已提交的返回正在离场时，再次返回不能越过刚露出的页面。
        BackHandler(transition.isRunning && entry.direction < 0) { }
    }
}

/** 只保留访问过的三个根页；隐藏页不测量、不绘制、不接收触摸，也不触发页面刷新。 */
@Composable
private fun RootTabPages(state: AppState, active: Boolean, content: @Composable (AppState) -> Unit) {
    val pages = remember { linkedMapOf<Route, AppState>() }
    pages.putIfAbsent(state.route, state)
    val selectedIndex = pages.keys.indexOf(state.route)
    Layout(modifier = Modifier.fillMaxSize(), content = {
        pages.forEach { (route, previous) ->
            key(route) {
                val snapshot = when {
                    route == state.route -> state
                    // 搜索离开后释放结果与输入保存域，不能把上一轮输入留在隐藏页并在返回时重新提交。
                    route == Route.Search && previous.searchVisitId != state.searchVisitId ->
                        previous.copy(search = state.search, searchVisitId = state.searchVisitId)
                    else -> previous
                }
                SideEffect { pages[route] = snapshot }
                PageContent(snapshot, active && route == state.route, content)
            }
        }
    }) { measurables, constraints ->
        val page = measurables[selectedIndex].measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) { page.place(0, 0) }
    }
}

@Composable
private fun PageContent(state: AppState, active: Boolean, content: @Composable (AppState) -> Unit) {
    CompositionLocalProvider(LocalPageActive provides active,
        LocalPageRefreshEnabled provides (active && !state.loginBusy && state.reauthenticationProfile == null)) {
        Box(Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })) {
            content(state)
        }
    }
}

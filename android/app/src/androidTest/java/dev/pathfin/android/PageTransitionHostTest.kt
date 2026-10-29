package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.PageTransitionHost
import dev.pathfin.android.ui.Route
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 实际动画帧、指针与返回 dispatcher；仅合成页面，不创建会话或请求网络。 */
@RunWith(AndroidJUnit4::class)
class PageTransitionHostTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val state = mutableStateOf(AppState(currentProfileId = "synthetic-transition-profile"))
    private val clicks = mutableMapOf<String, Int>()
    private var backs = 0
    private var rootList: LazyListState? = null

    @Test fun restoringAnEmptySessionShowsTheFirstPageWithoutAFalseBackTransition() {
        state.value = AppState(restoringSession = true)
        show()
        page(Route.Home, unmerged = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.value = AppState(showServers = true) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag(CANVAS).fetchSemanticsNode().boundsInRoot
            assertEquals("首次恢复的服务器页应直接占满窗口，不从默认 Home 推出", viewport,
                page(Route.Home).fetchSemanticsNode().boundsInRoot)
            compose.onAllNodesWithTag("page/${Route.Home.key}", useUnmergedTree = true).assertCountEquals(1)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun pushAndPopUseOppositeDirectionsAndOutgoingPagesCannotReceiveTouchOrAccessibility() {
        show()
        val canvas = compose.onNodeWithTag(CANVAS)
        val viewport = canvas.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            navigate(listOf(Route.Home, Route.Settings))
            val incoming = page(Route.Settings).fetchSemanticsNode().boundsInRoot
            val outgoing = page(Route.Home, unmerged = true).fetchSemanticsNode().boundsInRoot
            assertTrue("推入页应从右侧进入", incoming.left > viewport.left + 1f && incoming.left < viewport.right)
            assertTrue("原页应向左离场", outgoing.right < viewport.right - 1f && outgoing.right > viewport.left)
            page(Route.Home).assertDoesNotExist()
            compose.onNodeWithText("home:initial").assertDoesNotExist()
            // 指针真实落在还看得到的离场页上；不能借 semantics 点击绕过 pointer shield。
            val point = Offset((outgoing.left + outgoing.right) / 2f - viewport.left, viewport.height / 2f)
            canvas.performTouchInput { click(point) }
            compose.runOnIdle { assertEquals("离场页不能响应点击", 0, clicks[Route.Home.key] ?: 0) }
            finishTransition()
            page(Route.Settings).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals("当前页仍须可以真实点击", 1, clicks[Route.Settings.key]) }

            navigate(listOf(Route.Home))
            val returning = page(Route.Home).fetchSemanticsNode().boundsInRoot
            val leaving = page(Route.Settings, unmerged = true).fetchSemanticsNode().boundsInRoot
            assertTrue("返回页应从左侧进入", returning.right < viewport.right - 1f && returning.right > viewport.left)
            assertTrue("被推出页应向右离场", leaving.left > viewport.left + 1f)
            page(Route.Settings).assertDoesNotExist()
            finishTransition()
            page(Route.Home).assertIsDisplayed()
            page(Route.Settings, unmerged = true).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun aDataUpdateChangesTheVisiblePageWithoutRestartingItsRouteAnimation() {
        show()
        compose.mainClock.autoAdvance = false
        try {
            navigate(listOf(Route.Home, Route.Settings))
            val before = page(Route.Settings).fetchSemanticsNode().boundsInRoot.left
            assertTrue("必须在实际推入过程中更新数据", before > 1f)
            compose.runOnIdle { state.value = state.value.copy(notice = "updated") }
            compose.mainClock.advanceTimeByFrame()
            val after = page(Route.Settings).fetchSemanticsNode().boundsInRoot.left
            assertTrue("更新同一路由数据不能把页面重新推回右侧", after <= before)
            compose.onNodeWithText("settings:updated").assertExists()
            // 从原动画开始累计已超过280ms；若数据更新重启，旧页此时仍会被保留。
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            page(Route.Home, unmerged = true).assertDoesNotExist()
            assertEquals(compose.onNodeWithTag(CANVAS).fetchSemanticsNode().boundsInRoot,
                page(Route.Settings).fetchSemanticsNode().boundsInRoot)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun rapidBackDuringPopDoesNotSkipAPageAndReturningRestoresTheSavedListAnchor() {
        show(listOnHome = true)
        compose.onNodeWithTag(ROOT_LIST, useUnmergedTree = true).performScrollToIndex(18)
        val anchor = compose.runOnIdle { checkNotNull(rootList).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset } }
        assertEquals(18, anchor.first)
        compose.mainClock.autoAdvance = false
        try {
            navigate(listOf(Route.Home, Route.Settings))
            finishTransition()
            navigate(listOf(Route.Home, Route.Settings, Route.SettingsPage("general")))
            finishTransition()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            assertEquals(listOf(Route.Home, Route.Settings), state.value.routes)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("返回动画期间再次返回不能跳过刚露出的页面", 1, backs)
                assertEquals(listOf(Route.Home, Route.Settings), state.value.routes)
            }
            finishTransition()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            finishTransition()
            compose.runOnIdle {
                assertEquals(2, backs)
                assertEquals(listOf(Route.Home), state.value.routes)
                assertEquals("与生产相同 SaveableStateHolder 应恢复原列表位置", anchor,
                    checkNotNull(rootList).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
            }
            compose.onNodeWithTag("root-row/18", useUnmergedTree = true).assertIsDisplayed()
            assertFalse(compose.activity.isFinishing)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returningBeforePushCompletesReactivatesTheOriginalPageWithoutDuplicatingItsSavedState() {
        show(listOnHome = true)
        compose.onNodeWithTag(ROOT_LIST, useUnmergedTree = true).performScrollToIndex(18)
        val anchor = compose.runOnIdle { checkNotNull(rootList).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset } }
        compose.mainClock.autoAdvance = false
        try {
            navigate(listOf(Route.Home, Route.Settings))
            assertTrue("必须在推进尚未完成时返回", page(Route.Settings).fetchSemanticsNode().boundsInRoot.left > 1f)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle {
                assertEquals(1, backs)
                assertEquals(listOf(Route.Home), state.value.routes)
                state.value = state.value.copy(notice = "returned-live")
            }
            compose.mainClock.advanceTimeByFrame()
            finishTransition()
            page(Route.Home).assertIsDisplayed()
            compose.onNodeWithTag("root-row/18", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals("Synthetic row 18:returned-live")
            compose.runOnIdle {
                assertEquals("中断后只能恢复同一个列表位置", anchor,
                    checkNotNull(rootList).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
            }
            page(Route.Home).performTouchInput { click(center) }
            compose.runOnIdle {
                assertEquals("重新显示的原页必须真正恢复交互", 1, clicks[Route.Home.key])
                state.value = state.value.copy(notice = "fresh-update")
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("root-row/18", useUnmergedTree = true).assertTextEquals("Synthetic row 18:fresh-update")
            page(Route.Settings, unmerged = true).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun show(listOnHome: Boolean = false) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        compose.setContent {
            val savedState = rememberSaveableStateHolder()
            BackHandler(state.value.routes.size > 1) {
                backs++
                state.value = state.value.copy(routes = state.value.routes.dropLast(1))
            }
            Box(Modifier.size(320.dp, 300.dp).testTag(CANVAS)) {
                PageTransitionHost(state.value) { snapshot ->
                    savedState.SaveableStateProvider("${snapshot.currentProfileId}/${snapshot.route.key}") {
                        Box(Modifier.fillMaxSize().testTag("page/${snapshot.route.key}")
                            .background(if (snapshot.route == Route.Home) Color.LightGray else Color.White)
                            .clickable { clicks[snapshot.route.key] = (clicks[snapshot.route.key] ?: 0) + 1 }) {
                            if (snapshot.route == Route.Home && listOnHome) {
                                val list = rememberLazyListState()
                                SideEffect { rootList = list }
                                LazyColumn(Modifier.fillMaxSize().testTag(ROOT_LIST), state = list) {
                                    items(60, key = { it }) { index ->
                                        Text("Synthetic row $index${snapshot.notice?.let { ":$it" }.orEmpty()}",
                                            Modifier.fillMaxWidth().height(48.dp).testTag("root-row/$index"))
                                    }
                                }
                            } else Text("${snapshot.route.key}:${snapshot.notice ?: "initial"}")
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "page-transition-initial", compose.onNodeWithTag(CANVAS))
    }

    private fun navigate(routes: List<Route>) {
        compose.runOnIdle { state.value = state.value.copy(routes = routes) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }
    private fun finishTransition() {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }
    private fun page(route: Route, unmerged: Boolean = false) = compose.onNodeWithTag("page/${route.key}", useUnmergedTree = unmerged)
    private companion object {
        const val CANVAS = "transition-canvas"
        const val ROOT_LIST = "transition-root-list"
    }
}

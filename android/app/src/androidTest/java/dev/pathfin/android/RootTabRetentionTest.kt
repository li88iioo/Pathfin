package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPageActive
import dev.pathfin.android.ui.LocalPageRefreshEnabled
import dev.pathfin.android.ui.PageTransitionHost
import dev.pathfin.android.ui.RefreshablePage
import dev.pathfin.android.ui.ReleaseDepartedPageStates
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.SearchState
import dev.pathfin.android.ui.savedPageKey
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 有会话的真实根导航：验证保留组合的收益及隐藏页的交互、请求与保存域边界。 */
@RunWith(AndroidJUnit4::class)
class RootTabRetentionTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val state = mutableStateOf(AppState(session = session("first"), currentProfileId = "first", searchVisitId = "search-0"))
    private val mounts = mutableMapOf<String, Int>()
    private val disposals = mutableMapOf<String, Int>()
    private val measurements = mutableMapOf<String, Int>()
    private val clicks = mutableMapOf<String, Int>()
    private val refreshes = mutableMapOf<String, Int>()
    private val capabilities = mutableMapOf<String, Pair<Boolean, Boolean>>()
    private var searchVisit = 0

    @Test fun visitedTabsRetainTheirCompositionAndHiddenPagesCannotReceiveLayoutTouchOrAccessibility() {
        show()
        assertEquals(1, mounts[Route.Home.key])
        selectTab(Route.Favorites)
        page(Route.Home).assertDoesNotExist()
        page(Route.Favorites).assertVerificationDisplayed(compose)
        val hiddenHomeMeasurements = measurements[Route.Home.key]
        compose.runOnIdle { state.value = state.value.copy(notice = "favorites-update") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("隐藏页不参与活动页的数据重测", hiddenHomeMeasurements, measurements[Route.Home.key])
            assertEquals(false to false, capabilities[Route.Home.key])
            assertEquals(true to true, capabilities[Route.Favorites.key])
        }
        compose.onNodeWithTag(CANVAS).performVerificationTouchInput(compose) { click(center) }
        compose.runOnIdle {
            assertEquals(1, clicks[Route.Favorites.key])
            assertEquals(0, clicks[Route.Home.key] ?: 0)
        }
        selectTab(Route.Home)
        compose.onNodeWithTag(CANVAS).performVerificationTouchInput(compose) { click(center) }
        compose.runOnIdle {
            assertEquals("返回已访问页不能重新建立组合", 1, mounts[Route.Home.key])
            assertEquals(1, mounts[Route.Favorites.key])
            assertEquals(0, disposals[Route.Home.key] ?: 0)
            assertEquals(1, clicks[Route.Home.key])
        }
        page(Route.Favorites).assertDoesNotExist()
        selectTab(Route.Favorites)
        compose.onNodeWithTag("label/${Route.Favorites.key}").assertTextEquals("favorites:1:favorites-update")
        compose.runOnIdle { assertEquals(1, mounts[Route.Favorites.key]) }
    }

    @Test fun automaticForegroundRefreshOnlyRunsOnTheVisibleRetainedTab() {
        show(automaticRefresh = true)
        compose.runOnIdle { assertEquals(1, refreshes[Route.Home.key]) }
        selectTab(Route.Favorites)
        compose.runOnIdle {
            assertEquals(1, refreshes[Route.Home.key])
            assertEquals(1, refreshes[Route.Favorites.key])
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("隐藏首页不能因App回前台而刷新", 1, refreshes[Route.Home.key])
            assertEquals("当前收藏页仍需正常自动刷新", 2, refreshes[Route.Favorites.key])
            assertEquals(1, mounts[Route.Home.key])
        }
        selectTab(Route.Home)
        compose.runOnIdle {
            assertEquals(2, refreshes[Route.Home.key])
            assertEquals(2, refreshes[Route.Favorites.key])
        }
    }

    @Test fun completedDetailNavigationReleasesRetainedTabsAndProfileChangesStartIndependentPages() {
        show()
        selectTab(Route.Search)
        selectTab(Route.Home)
        val detail = Route.Detail("synthetic-detail")
        navigate(listOf(Route.Home, detail))
        compose.runOnIdle {
            assertEquals("离开根页动画完成后释放首页", 1, disposals[Route.Home.key])
            assertEquals("离开根页同时释放隐藏的搜索页", 1, disposals[Route.Search.key])
        }
        page(Route.Home, unmerged = true).assertDoesNotExist()
        page(detail).assertVerificationDisplayed(compose)
        navigate(listOf(Route.Home))
        compose.runOnIdle { assertEquals(2, mounts[Route.Home.key]) }
        compose.runOnIdle {
            state.value = AppState(session = session("second"), currentProfileId = "second", searchVisitId = "other-search")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("切换服务器账号释放上一账号根页", 2, disposals[Route.Home.key])
            assertEquals(3, mounts[Route.Home.key])
        }
        compose.onNodeWithTag(CANVAS).performVerificationTouchInput(compose) { click(center) }
        compose.onNodeWithTag("label/home").assertTextEquals("home:1:initial")
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun leavingSearchClearsItsSavedInputBeforeTheRetainedSearchPageIsReactivated() {
        show()
        selectTab(Route.Search)
        compose.onNodeWithTag(SEARCH_INPUT).performTextReplacement("old search input")
        compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals("old search input")
        selectTab(Route.Favorites)
        compose.onNodeWithTag(SEARCH_INPUT).assertDoesNotExist()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        selectTab(Route.Search)
        compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals("")
        compose.runOnIdle {
            assertEquals("只重置搜索内容保存域，不重建根页外壳", 1, mounts[Route.Search.key])
            assertEquals(true to true, capabilities[Route.Search.key])
        }
        compose.onNodeWithTag(SEARCH_INPUT).performTextReplacement("new search input")
        selectTab(Route.Home)
        selectTab(Route.Search)
        compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals("")
        compose.runOnIdle { assertEquals(1, mounts[Route.Home.key]) }
    }

    @Test fun returningDuringRootPushRestoresOneSavedPageAndItsActiveInteraction() {
        show()
        selectTab(Route.Search)
        selectTab(Route.Home)
        compose.onNodeWithTag(CANVAS).performVerificationTouchInput(compose) { click(center) }
        compose.onNodeWithTag("label/home").assertTextEquals("home:1:initial")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.value = state.value.copy(routes = listOf(Route.Home, Route.Detail("interrupt-detail"))) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            assertTrue("必须在推进未完成时返回", page(Route.Detail("interrupt-detail")).readVerificationNode(compose) { it.boundsInRoot }.left > 1f)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { state.value = state.value.copy(notice = "returned-live") }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            page(Route.Home).assertVerificationDisplayed(compose)
            compose.onNodeWithTag("label/home").assertTextEquals("home:1:returned-live")
            compose.runOnIdle {
                assertEquals(listOf(Route.Home), state.value.routes)
                assertEquals(true to true, capabilities[Route.Home.key])
            }
            compose.onNodeWithTag(CANVAS).performVerificationTouchInput(compose) { click(center) }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("label/home").assertTextEquals("home:2:returned-live")
            page(Route.Detail("interrupt-detail"), unmerged = true).assertDoesNotExist()
            assertFalse(compose.activity.isFinishing)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returningDuringSearchPushKeepsTheInputWithinTheSameSearchVisit() {
        show()
        selectTab(Route.Search)
        compose.onNodeWithTag(SEARCH_INPUT).performTextReplacement("keep this search visit")
        val visit = state.value.searchVisitId
        compose.mainClock.autoAdvance = false
        try {
            val detail = Route.Detail("search-interrupt-detail")
            compose.runOnIdle { state.value = state.value.copy(routes = listOf(Route.Search, detail)) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            assertTrue(page(detail).readVerificationNode(compose) { it.boundsInRoot }.left > 1f)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag(SEARCH_INPUT).assertVerificationDisplayed(compose).assertTextEquals("keep this search visit")
            compose.runOnIdle {
                assertEquals(visit, state.value.searchVisitId)
                assertEquals(listOf(Route.Search), state.value.routes)
                assertEquals(true to true, capabilities[Route.Search.key])
            }
            compose.onNodeWithTag(SEARCH_INPUT).performVerificationTouchInput(compose) { click(center) }
            compose.onNodeWithTag(SEARCH_INPUT).assertIsFocused().performTextReplacement("edited after return")
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals("edited after return")
            page(detail, unmerged = true).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun show(automaticRefresh: Boolean = false) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        compose.setContent {
            val savedState = rememberSaveableStateHolder()
            ReleaseDepartedPageStates(state.value, savedState)
            BackHandler(state.value.routes.size > 1) { state.value = state.value.copy(routes = state.value.routes.dropLast(1)) }
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides AppPreferences(pullToRefreshEnabled = !automaticRefresh)) {
                    Box(Modifier.size(320.dp, 300.dp).testTag(CANVAS)) {
                        PageTransitionHost(state.value) { snapshot ->
                            val route = snapshot.route
                            val id = route.key
                            val active = LocalPageActive.current
                            val canRefresh = LocalPageRefreshEnabled.current
                            DisposableEffect(snapshot.currentProfileId, route) {
                                mounts[id] = (mounts[id] ?: 0) + 1
                                onDispose { disposals[id] = (disposals[id] ?: 0) + 1 }
                            }
                            SideEffect { capabilities[id] = active to canRefresh }
                            Box(Modifier.fillMaxSize().testTag("page/$id").background(Color.White)
                                .layout { measurable, constraints ->
                                    measurements[id] = (measurements[id] ?: 0) + 1
                                    val measured = measurable.measure(constraints)
                                    layout(measured.width, measured.height) { measured.place(0, 0) }
                                }) {
                                savedState.SaveableStateProvider(snapshot.savedPageKey()) {
                                    RefreshablePage(false, { refreshes[id] = (refreshes[id] ?: 0) + 1 }, Modifier.fillMaxSize()) {
                                        if (route == Route.Search) {
                                            var input by rememberSaveable { mutableStateOf(snapshot.search.query) }
                                            BasicTextField(input, { input = it }, Modifier.fillMaxSize().testTag(SEARCH_INPUT))
                                        } else {
                                            var selected by rememberSaveable { mutableIntStateOf(0) }
                                            Text("$id:$selected:${snapshot.notice ?: "initial"}", Modifier.fillMaxSize()
                                                .testTag("label/$id").clickable {
                                                    selected++
                                                    clicks[id] = (clicks[id] ?: 0) + 1
                                                })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "retained-root-initial", compose.onNodeWithTag(CANVAS))
    }

    private fun selectTab(route: Route) {
        compose.runOnIdle {
            val current = state.value
            state.value = if (route != Route.Search && Route.Search in current.routes) {
                current.copy(routes = listOf(route), search = SearchState(), searchVisitId = "search-${++searchVisit}")
            } else current.copy(routes = listOf(route))
        }
        compose.waitForIdle()
    }

    private fun navigate(routes: List<Route>) {
        compose.runOnIdle { state.value = state.value.copy(routes = routes) }
        compose.waitForIdle()
    }

    private fun page(route: Route, unmerged: Boolean = false) = compose.onNodeWithTag("page/${route.key}", useUnmergedTree = unmerged)

    private companion object {
        const val CANVAS = "retained-root-canvas"
        const val SEARCH_INPUT = "retained-search-input"
        fun session(user: String) = Session(ServerConfig("https://fixture.invalid"), "synthetic-unused-token", user, user, "fixture-device")
    }
}

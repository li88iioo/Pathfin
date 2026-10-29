package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.LibraryFilterOptions
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BrowserMediaCard
import dev.pathfin.android.ui.AutomaticGridPagination
import dev.pathfin.android.ui.BrowserScreen
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LocalPageRefreshEnabled
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PaginationStatus
import dev.pathfin.android.ui.PosterStyle
import dev.pathfin.android.ui.rememberPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 生产浏览组件的局部反馈回归；不连接网络、不读写账号或偏好。 */
@RunWith(AndroidJUnit4::class)
class BrowserFeedbackTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == VERIFICATION_PACKAGE) {
                    "浏览组件回归只允许独立 verification 包"
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test
    fun retryFilterOptionsKeepsDraftAndDoesNotRefreshMedia() {
        val state = mutableStateOf(BrowserUiState(
            filterOptions = LibraryFilterOptions(genres = listOf("科幻")),
            filtersError = FILTER_ERROR,
        ))
        var mediaRefreshes = 0
        var filterRetries = 0
        var applied: LibraryQuery? = null
        showBrowser(state, onQuery = { applied = it }, onRetry = { mediaRefreshes++ }, onRetryFilters = {
            filterRetries++
            state.value = state.value.copy(filtersLoading = true)
        })

        compose.onNodeWithTag("browser-filter").performClick()
        // 等底部面板完成入场再注入坐标点击，避免在仍移动的窗口上点到旧位置。
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasTestTag("browser-filter-group/类型"))
        compose.onNodeWithTag("browser-filter-values/类型").performScrollToNode(hasText("科幻"))
        compose.onNodeWithTag("browser-filter-value/类型/科幻").performClick().assertIsSelected()
        compose.runOnIdle { assertNull("确认前不应把筛选草稿应用到媒体查询", applied) }
        val filters = compose.onNode(hasTestTag("refreshable-page") and hasAnyDescendant(hasTestTag("browser-filter-options")))
        compose.onNodeWithTag("browser-filter-options").performScrollToIndex(0)
        pullPageToRefresh(compose, filters, "filter-options-reload")
        pullPageToRefresh(compose, filters, "filter-options-busy")
        compose.onNodeWithTag("browser-filter-retry").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, filterRetries)
            assertEquals("筛选重试只能重新读取选项，不能重新请求媒体", 0, mediaRefreshes)
            assertNull(applied)
            state.value = state.value.copy(
                filterOptions = LibraryFilterOptions(genres = listOf("纪录片")),
                filtersLoading = false,
                filtersError = null,
            )
        }

        compose.onNodeWithText(FILTER_ERROR).assertDoesNotExist()
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasText("科幻"))
        compose.onNodeWithText("科幻").assertIsSelected()
        compose.onNodeWithText("纪录片").assertIsDisplayed()
        compose.onNodeWithTag("browser-filter-apply").performClick()
        compose.runOnIdle {
            assertEquals(setOf("科幻"), applied?.genres)
            assertEquals(1, filterRetries)
            assertEquals(0, mediaRefreshes)
        }
    }

    @Test
    fun emptyResultCanPullToRefreshAndDisablesRepeatedRefreshWhileLoading() {
        val state = mutableStateOf(BrowserUiState())
        var refreshes = 0
        showBrowser(state, onRetry = {
            refreshes++
            state.value = state.value.copy(content = state.value.content.copy(loading = true))
        })
        compose.onNodeWithText("没有找到内容").assertIsDisplayed()
        pullPageToRefresh(compose)
        compose.runOnIdle {
            assertEquals("下拉刷新应只触发一次请求", 1, refreshes)
            assertTrue("刷新回调应立即进入加载态", state.value.content.loading)
        }
        // 已确认空结果继续原位呈现，长刷新也不能退回整页加载面板。
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("没有找到内容").assertIsDisplayed()
        compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
        compose.onNodeWithTag("browser-empty-refresh").assertDoesNotExist()
        pullPageToRefresh(compose, stage = "empty-result-busy")
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state.value = state.value.copy(content = state.value.content.copy(loading = false))
        }
        compose.onNodeWithTag("browser-empty-refresh").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun childCountsNeverSubstituteMissingOrZeroUnplayedCounts() {
        val api = JellyfinApi()
        val cards = listOf(
            MediaItem("unknown", "未返回观看数量", "Series", childCount = 24),
            MediaItem("zero", "没有未看内容", "Series", childCount = 12, unplayedItemCount = 0),
            MediaItem("positive", "有未看内容", "Series", childCount = 10, unplayedItemCount = 3),
        )
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides rememberPosterLoader(session)) {
                    Surface(Modifier.fillMaxSize()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            cards.forEach { item ->
                                BrowserMediaCard(item, session, api, PosterStyle.POSTER, Modifier.width(96.dp), onOpen = {})
                            }
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText("24").assertCountEquals(0)
        compose.onAllNodesWithText("12").assertCountEquals(0)
        compose.onNodeWithContentDescription("未观看 3 项", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun categoryWaitKeepsItsVisibleLabelCountAndScrollUntilNewResultsStartAtTheTop() {
        val original = LibraryQuery(itemTypes = setOf("Movie"))
        val previous = List(120) { MediaItem("old-$it", "旧结果$it", "Movie") }
        val state = mutableStateOf(BrowserUiState(
            query = original, contentQuery = original,
            content = ContentState(items = previous, total = previous.size, nextIndex = previous.size),
        ))
        var opened: MediaItem? = null
        showBrowser(state, onQuery = { requested ->
            state.value = state.value.copy(query = requested, content = state.value.content.copy(loading = true))
        }, onOpen = { opened = it })
        compose.onNodeWithTag("browser-grid").performScrollToIndex(60)
        val previousBounds = compose.onNodeWithText("旧结果60").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("browser-category").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("browser-tab/MEDIA").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        compose.onNodeWithTag("browser-category").assertTextContains(LibraryTab.ALL.label)
        compose.onNodeWithTag("browser-count").assert(hasAnyDescendant(hasText("120")))
        assertEquals("等待分类回包时旧列表不能先回顶", previousBounds,
            compose.onNodeWithText("旧结果60").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("旧结果60").performClick()
        compose.runOnIdle { assertEquals("等待期间点击仍打开当前可见媒体", previous[60], opened) }

        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(2_600)
            compose.onNodeWithTag("browser-category").assertTextContains(LibraryTab.ALL.label)
            assertEquals(previousBounds, compose.onNodeWithText("旧结果60").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
            compose.runOnIdle {
                val next = List(90) { MediaItem("new-$it", "新结果$it", "Movie") }
                state.value = state.value.withContent(state.value.query,
                    ContentState(items = next, total = next.size, nextIndex = next.size))
            }
            compose.mainClock.advanceTimeByFrame()
            // 新集合仍有足够深的内容，显示第0项才能证明回顶属于新结果的首帧。
            compose.onNodeWithText("新结果0").assertIsDisplayed()
            compose.onNodeWithText("旧结果60").assertDoesNotExist()
            compose.onNodeWithTag("browser-category").assertTextContains(LibraryTab.MEDIA.label)
            compose.onNodeWithTag("browser-count").assert(hasAnyDescendant(hasText("90")))
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun fastCacheMissAndNetworkResultDoNotFlashAnEmptyOrLoadingPanel() {
        val state = mutableStateOf(BrowserUiState(content = ContentState(loading = true), restoringCache = true))
        compose.mainClock.autoAdvance = false
        try {
            showBrowser(state)
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
            compose.onNodeWithText("没有找到内容").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { state.value = state.value.copy(restoringCache = false) }
            // 磁盘未命中、网络尚未返回的中间帧是此前真实闪现的窗口。
            repeat(4) {
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
                compose.onNodeWithText("没有找到内容").assertDoesNotExist()
            }
            compose.runOnIdle {
                state.value = state.value.copy(content = ContentState(
                    items = listOf(MediaItem("fast", "快速返回的电影", "Movie")), total = 1, nextIndex = 1))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("快速返回的电影").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
            compose.onNodeWithText("没有找到内容").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun slowFirstReadShowsFeedbackAndCachedCardsDisplayImmediatelyWhileRevalidationContinues() {
        val state = mutableStateOf(BrowserUiState(content = ContentState(loading = true), restoringCache = true))
        compose.mainClock.autoAdvance = false
        try {
            showBrowser(state)
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(500)
            // 读盘本身较慢时也需要反馈，不能无限停留在空白页。
            compose.onNodeWithText("正在读取媒体").assertIsDisplayed()
            compose.onNodeWithText("没有找到内容").assertDoesNotExist()
            // 旧查询仍在等待，新的快速查询不能继承它已经可见的加载反馈。
            compose.runOnIdle {
                state.value = state.value.copy(query = state.value.query.copy(nameInitial = "B"), restoringCache = false)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
            compose.onNodeWithText("没有找到内容").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle {
                state.value = state.value.copy(restoringCache = false, content = ContentState(
                    items = listOf(MediaItem("cached", "缓存中的电影", "Movie")), total = 1, nextIndex = 1, loading = true))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("缓存中的电影").assertIsDisplayed()
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
            val titleBounds = compose.onNodeWithText("缓存中的电影").fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("缓存中的电影").assertIsDisplayed()
            compose.runOnIdle { state.value = state.value.copy(content = state.value.content.copy(loading = false)) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("网络校验结束不能替换卡片或移动其标题", titleBounds,
                compose.onNodeWithText("缓存中的电影").fetchSemanticsNode().boundsInRoot)

            // 真正的空结果随回包呈现，不需要等反馈延时耗尽。
            compose.runOnIdle {
                state.value = state.value.copy(query = state.value.query.copy(nameInitial = "C"),
                    content = ContentState(loading = true))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { state.value = state.value.copy(content = ContentState()) }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("没有找到内容").assertIsDisplayed()
            compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun failedFirstReadKeepsTheErrorAndAllowsPullToRetryBeforeShowingTheRealEmptyResult() {
        val state = mutableStateOf(BrowserUiState(content = ContentState(loading = true)))
        var retries = 0
        showBrowser(state, onRetry = {
            retries++
            state.value = state.value.copy(content = state.value.content.copy(loading = true))
        })
        compose.runOnIdle {
            state.value = state.value.copy(content = ContentState(error = "媒体库读取失败"))
        }
        compose.onNodeWithText("媒体库读取失败").assertIsDisplayed()
        compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
        compose.onNodeWithText("没有找到内容").assertDoesNotExist()
        pullPageToRefresh(compose, stage = "first-page-retry")
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("媒体库读取失败").assertIsDisplayed()
        compose.onNodeWithText("正在读取媒体").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(content = ContentState()) }
        compose.onNodeWithText("媒体库读取失败").assertDoesNotExist()
        compose.onNodeWithText("没有找到内容").assertIsDisplayed()
    }

    @Test
    fun largeLibraryPreparesEachHundredItemPageWithoutDrainingTheLibraryOrIgnoringDisabledPagesAndErrors() {
        val status = mutableStateOf(PaginationStatus(itemCount = 40, nextIndex = 40, total = 1200))
        val drawerClosed = mutableStateOf(true)
        val pageVisible = mutableStateOf(true)
        val requestedCursors = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalPageRefreshEnabled provides pageVisible.value) {
                val grid = rememberLazyGridState()
                AutomaticGridPagination(grid, status.value, "same-query", onMore = {
                    requestedCursors += status.value.nextIndex
                    status.value = status.value.copy(appending = true)
                }, enabled = drawerClosed.value, minimumAheadItems = 100, screensAhead = 2)
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.size(300.dp, 320.dp).testTag("prefetch-grid"), state = grid) {
                    items(status.value.itemCount) { Box(Modifier.fillMaxWidth().height(80.dp)) }
                }
            }
        }
        fun completeAppend() = compose.runOnIdle {
            val current = status.value
            assertTrue("测试只能完成实际已经触发的追加", current.appending)
            val next = (current.nextIndex + 100).coerceAtMost(current.total)
            status.value = current.copy(itemCount = next, nextIndex = next, appending = false, failed = false,
                hasMore = next < current.total)
        }
        val grid = compose.onNodeWithTag("prefetch-grid")
        compose.runOnIdle {
            assertEquals("首批 40 项一旦布局就准备下一页，无须滚到不完整的末行", listOf(40), requestedCursors)
            status.value = status.value.copy(appending = false)
        }
        compose.runOnIdle {
            assertEquals("同一游标不能因忙态变化而重发", listOf(40), requestedCursors)
            status.value = status.value.copy(appending = true)
        }
        completeAppend()
        compose.runOnIdle {
            assertEquals(140, status.value.itemCount)
            assertEquals("补够后立即停止，不把 1200 项整库拉完", listOf(40), requestedCursors)
            drawerClosed.value = false
        }
        grid.performScrollToIndex(102)
        compose.runOnIdle {
            assertEquals("抽屉开启时，即使已接近末尾也不取下一页", listOf(40), requestedCursors)
            drawerClosed.value = true
        }
        compose.runOnIdle { assertEquals("抽屉关闭后接续原游标", listOf(40, 140), requestedCursors) }
        completeAppend()
        compose.runOnIdle {
            assertEquals("每次补 100 项后都停止在足够的缓冲区", listOf(40, 140), requestedCursors)
            pageVisible.value = false
        }
        grid.performScrollToIndex(201)
        compose.runOnIdle {
            assertEquals("隐藏页不能因滚动位置变化发请求", listOf(40, 140), requestedCursors)
            pageVisible.value = true
        }
        compose.runOnIdle {
            assertEquals("页面恢复可见后继续读取", listOf(40, 140, 240), requestedCursors)
            status.value = status.value.copy(appending = false, failed = true)
        }
        grid.performScrollToIndex(216)
        compose.runOnIdle {
            assertEquals("追加失败后停止自动请求", listOf(40, 140, 240), requestedCursors)
            // 模拟用户显式重试清除错误；不能在失败期间自行循环重试。
            status.value = status.value.copy(failed = false)
        }
        compose.runOnIdle { assertEquals(listOf(40, 140, 240, 240), requestedCursors) }
        completeAppend()

        var nextCursor = 340
        var expectedRequests = 4
        while (nextCursor < 1200) {
            compose.runOnIdle { assertEquals("没有继续滚动时不能连发追加", expectedRequests, requestedCursors.size) }
            grid.performScrollToIndex(nextCursor - 36)
            expectedRequests++
            compose.runOnIdle {
                assertEquals("提前量内只发一次下一游标", expectedRequests, requestedCursors.size)
                assertEquals(nextCursor, requestedCursors.last())
            }
            completeAppend()
            nextCursor = (nextCursor + 100).coerceAtMost(1200)
        }
        grid.performScrollToIndex(1199)
        compose.runOnIdle {
            assertEquals(1200, status.value.itemCount)
            assertEquals(1200, status.value.nextIndex)
            assertTrue("尾页已明确没有下一页", !status.value.hasMore)
            assertEquals("尾页不足 100 项也能停止；除显式失败重试外每个游标只请求一次",
                listOf(40, 140, 240, 240) + (340..1140 step 100).toList(), requestedCursors)
        }
    }

    private fun showBrowser(
        state: MutableState<BrowserUiState>,
        onQuery: (LibraryQuery) -> Unit = {},
        onRetry: () -> Unit = {},
        onRetryFilters: () -> Unit = {},
        onOpen: (MediaItem) -> Unit = {},
    ) {
        val api = JellyfinApi()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides rememberPosterLoader(session)) {
                    Surface(Modifier.fillMaxSize()) {
                        BrowserScreen(
                            state.value, session, api,
                            onQueryChange = onQuery, onStyleChange = {}, onOpen = onOpen,
                            onRetry = onRetry, onMore = {}, onRetryFilters = onRetryFilters,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val FILTER_ERROR = "筛选选项读取失败，请重新读取"
        val session = Session(ServerConfig("https://browser-feedback.invalid"), "fixture-token", "fixture-user", "fixture", "fixture-device")
    }
}

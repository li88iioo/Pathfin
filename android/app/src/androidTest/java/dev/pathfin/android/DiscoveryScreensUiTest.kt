package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.DiscoveryState
import dev.pathfin.android.ui.DiscoveryGridContent
import dev.pathfin.android.ui.EmptyPanel
import dev.pathfin.android.ui.FavoritesContent
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalNavigationBottomInset
import dev.pathfin.android.ui.LocalPageActive
import dev.pathfin.android.ui.LocalPageTopInset
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.LocalTopBarBlurState
import dev.pathfin.android.ui.SearchContent
import dev.pathfin.android.ui.SearchState
import dev.pathfin.android.ui.rememberTopBarBlurState
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 合成媒体测试分组预览、推荐直达、多选与纵向分页，不读取用户账号。 */
@RunWith(AndroidJUnit4::class)
class DiscoveryScreensUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val api = JellyfinApi()
    private val session = Session(ServerConfig("https://discovery-ui.invalid"),
        "synthetic-token", "synthetic-user", "合成账号", "synthetic-device")
    private lateinit var loader: ImageLoader
    private val movie = MediaItem("movie", "合成电影", "Movie", year = 2026)

    @Before fun prepare() {
        check(context.packageName == "dev.pathfin.android.verification")
        loader = ImageLoader.Builder(context).diskCache(null).build()
    }

    @After fun close() { loader.shutdown() }

    @Test fun favoritesShowOnlyNonEmptyTypesAndKeepTheOriginalItemAction() {
        val person = MediaItem("person", "合成演员", "Person")
        val state = DiscoveryState(requested = true, sections = mapOf(
            DiscoveryCategory.MOVIES to page(movie),
            DiscoveryCategory.SERIES to ContentState(),
            DiscoveryCategory.PEOPLE to page(person),
            DiscoveryCategory.ALBUMS to ContentState(),
        ))
        var opened: MediaItem? = null
        show {
            FavoritesContent(state, session, api, {}, {}, { opened = it })
        }
        assertVisible("favorites-movies", compose.onNodeWithTag("discovery-section/MOVIES", true))
        assertVisible("favorites-people", compose.onNodeWithTag("discovery-section/PEOPLE", true))
        compose.onNodeWithTag("discovery-section/SERIES", true).assertDoesNotExist()
        compose.onNodeWithTag("discovery-section/ALBUMS", true).assertDoesNotExist()
        compose.onNodeWithText("收藏").assertDoesNotExist()
        compose.onNodeWithText("排序").assertDoesNotExist()
        touch("favorite-movie", compose.onNodeWithTag("discovery-item/MOVIES/movie"))
        compose.runOnIdle { assertSame(movie, opened) }
    }

    @Test fun serverRecommendationOpensItsMediaWithoutSubmittingOrChangingTheQuery() {
        val recommendation = movie.copy(id = "recommendation", name = "服务器推荐的片名")
        val state = mutableStateOf(SearchState(suggestions = page(recommendation), suggestionsLoaded = true))
        val submitted = mutableListOf<String>()
        var opened: MediaItem? = null
        show {
            SearchContent(state.value, session, api,
                onSearch = { term -> submitted += term; state.value = state.value.copy(query = term) },
                onToggleCategory = {}, onRefresh = {}, onShowAll = {}, onOpen = { opened = it })
        }
        assertVisible("recommendations-heading", compose.onNodeWithText("推荐搜索"))
        touch("recommendation-open", compose.onNodeWithTag("search-suggestion/recommendation"))
        compose.runOnIdle {
            assertSame(recommendation, opened)
            assertEquals("", state.value.query)
            assertTrue("打开推荐条目不能发起文字搜索", submitted.isEmpty())
        }
        assertVisible("recommendations-still-present", compose.onNodeWithText("推荐搜索"))
    }

    @Test fun typingSubmittingFilteringRefreshingAndBlurKeepTheLargeFontToolbarInPlace() {
        val content = page(movie)
        val state = mutableStateOf(SearchState(query = "合成", results = DiscoveryState(
            requested = true, sections = mapOf(DiscoveryCategory.MOVIES to content))))
        val submitted = mutableListOf<String>()
        val blur = mutableStateOf(false)
        show(fontScale = 1.6f, theme = ThemePreference.DARK) {
            CompositionLocalProvider(LocalTopBarBlurState provides rememberTopBarBlurState(blur.value)) {
                SearchContent(state.value, session, api,
                    onSearch = { term -> submitted += term; state.value = state.value.copy(query = term) },
                    onToggleCategory = { state.value = state.value.copy(categories = setOfNotNull(it)) },
                    onRefresh = {}, onShowAll = {}, onOpen = {})
            }
        }
        val input = bounds("search-query")
        val filters = bounds("search-filters")
        val card = bounds("discovery-item/MOVIES/movie")
        assertTrue("结果滚动区必须延伸到标题后方", bounds("search-results").top < input.top)
        for (enabled in listOf(true, false)) {
            compose.runOnIdle { blur.value = enabled }
            assertEquals("模糊开关不能移动搜索框", input, bounds("search-query"))
            assertEquals("模糊开关不能移动筛选栏", filters, bounds("search-filters"))
            assertEquals("模糊开关不能移动首个结果", card, bounds("discovery-item/MOVIES/movie"))
        }
        compose.runOnIdle {
            state.value = state.value.copy(results = state.value.results.copy(sections = mapOf(
                DiscoveryCategory.MOVIES to content.copy(loading = true))))
        }
        assertEquals("刷新不能移动搜索框", input, bounds("search-query"))
        assertEquals("刷新不能移动筛选栏", filters, bounds("search-filters"))
        assertEquals("刷新不能替换或移动旧卡片", card, bounds("discovery-item/MOVIES/movie"))
        compose.onNodeWithTag("search-filter/MOVIES").performVerificationClick(compose).assertIsOn()
        compose.runOnIdle { assertEquals(setOf(DiscoveryCategory.MOVIES), state.value.categories) }
        compose.onNodeWithTag("search-query").performTextReplacement("合成电影")
        compose.onNodeWithTag("search-query").performImeAction()
        compose.runOnIdle { assertEquals("合成电影", submitted.last()) }
    }

    @Test fun outgoingSearchPageCancelsPendingDebounceUntilItBecomesActiveAgain() {
        val active = mutableStateOf(true)
        val state = mutableStateOf(SearchState(query = "原查询"))
        val submitted = mutableListOf<String>()
        show {
            CompositionLocalProvider(LocalPageActive provides active.value) {
                SearchContent(state.value, session, api,
                    onSearch = { submitted += it; state.value = state.value.copy(query = it) },
                    onToggleCategory = {}, onRefresh = {}, onShowAll = {}, onOpen = {})
            }
        }
        val input = compose.onNodeWithTag("search-query")
        assertVisible("search-before-transition", input)
        compose.mainClock.autoAdvance = false
        try {
            input.performTextReplacement("尚未提交的输入")
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { active.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(700)
            compose.runOnIdle { assertTrue("出场页的输入不能污染子页查询", submitted.isEmpty()) }
            compose.runOnIdle { active.value = true }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { assertEquals(listOf("尚未提交的输入"), submitted) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun filtersCanCombineMoviesSeasonsAndEpisodesAndAllResetsTheSelection() {
        val state = mutableStateOf(SearchState(suggestionsLoaded = true))
        val changes = mutableListOf<DiscoveryCategory?>()
        show {
            SearchContent(state.value, session, api, onSearch = {}, onToggleCategory = { category ->
                changes += category
                val selected = state.value.categories
                state.value = state.value.copy(categories = when {
                    category == null -> emptySet()
                    category in selected -> selected - category
                    else -> selected + category
                })
            }, onRefresh = {}, onShowAll = {}, onOpen = {})
        }
        compose.onNodeWithTag("search-filter/all").assertIsOn()
        selectFilter("MOVIES")
        compose.onNodeWithTag("search-filter/MOVIES").assertIsOn()
        compose.onNodeWithTag("search-filter/all").assertIsOff()
        selectFilter("SEASONS")
        compose.onNodeWithTag("search-filter/SEASONS").assertIsOn()
        selectFilter("EPISODES")
        compose.onNodeWithTag("search-filter/EPISODES").assertIsOn()
        compose.runOnIdle {
            assertEquals(setOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS, DiscoveryCategory.EPISODES), state.value.categories)
        }
        selectFilter("EPISODES")
        compose.onNodeWithTag("search-filter/EPISODES").assertIsOff()
        compose.runOnIdle { assertEquals(setOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS), state.value.categories) }
        selectFilter("all")
        compose.onNodeWithTag("search-filter/all").assertIsOn()
        compose.runOnIdle {
            assertTrue(state.value.categories.isEmpty())
            assertEquals(listOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS, DiscoveryCategory.EPISODES,
                DiscoveryCategory.EPISODES, null), changes)
        }
        compose.onNodeWithTag("search-filters").performScrollToNode(hasTestTag("search-filter/SEASONS"))
        compose.onNodeWithTag("search-filter/SEASONS").assertIsOff()
    }

    @Test fun previewsStopAtSixAndOnlyShowMoreWhenNeeded() {
        val movies = List(8) { movie.copy(id = "preview-$it", name = "预览电影 $it") }
        val content = ContentState(items = movies, total = 20, nextIndex = movies.size, queryKey = "favorites")
        val state = mutableStateOf(DiscoveryState(requested = true, sections = mapOf(DiscoveryCategory.MOVIES to content)))
        val openedGroups = mutableListOf<DiscoveryCategory>()
        show { FavoritesContent(state.value, session, api, {}, { openedGroups += it }, {}) }
        val header = bounds("discovery-section/MOVIES")
        touch("preview-show-all", compose.onNodeWithTag("discovery-more/MOVIES"))
        compose.runOnIdle { assertEquals(listOf(DiscoveryCategory.MOVIES), openedGroups) }
        compose.onNodeWithTag("discovery-row/MOVIES").performScrollToIndex(5)
        assertVisible("preview-sixth-item", compose.onNodeWithTag("discovery-item/MOVIES/preview-5"))
        compose.onNodeWithTag("discovery-item/MOVIES/preview-6").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(sections = mapOf(DiscoveryCategory.MOVIES to content.copy(
                items = movies.take(6), total = 6, nextIndex = 6)))
        }
        compose.onNodeWithTag("discovery-more/MOVIES").assertDoesNotExist()
        assertEquals("更多入口变化不能挪动分组标题", header, bounds("discovery-section/MOVIES"))
        compose.runOnIdle {
            state.value = state.value.copy(sections = mapOf(DiscoveryCategory.MOVIES to content.copy(
                items = movies.take(6), total = 7, nextIndex = 6)))
        }
        assertVisible("preview-total-has-more", compose.onNodeWithTag("discovery-more/MOVIES"))
        compose.onNodeWithText("加载更多").assertDoesNotExist()
    }

    @Test fun fullCategoryGridAppendsAutomaticallyOpensItemsAndRefreshesWithoutReplacingCards() {
        val movies = List(30) { movie.copy(id = "grid-$it", name = "完整电影 $it") }
        val state = mutableStateOf(ContentState(items = movies.take(24), total = movies.size, nextIndex = 24, queryKey = "grid"))
        var appends = 0
        var refreshes = 0
        var opened: MediaItem? = null
        show {
            DiscoveryGridContent(DiscoveryCategory.MOVIES, state.value, session, api,
                onRefresh = { refreshes++; state.value = state.value.copy(loading = true) },
                onMore = { appends++; state.value = state.value.copy(loadingMore = true) },
                onOpen = { opened = it })
        }
        val grid = compose.onNodeWithTag("discovery-grid/MOVIES")
        assertVisible("discovery-grid-first-frame", grid)
        grid.performScrollToIndex(23)
        compose.waitUntil(5_000) { compose.runOnIdle { appends == 1 } }
        compose.runOnIdle {
            assertEquals(movies.take(24), state.value.items)
            assertTrue(state.value.loadingMore)
            state.value = ContentState(items = movies, total = movies.size, nextIndex = movies.size, queryKey = "grid")
        }
        grid.performScrollToIndex(29)
        touch("discovery-grid-open-last", compose.onNodeWithTag("discovery-item/MOVIES/grid-29"))
        compose.runOnIdle { assertSame(movies.last(), opened); assertEquals(1, appends) }
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        compose.onNodeWithText("排序").assertDoesNotExist()
        compose.onNodeWithTag("search-filters").assertDoesNotExist()
        grid.performScrollToIndex(0)
        val firstCard = bounds("discovery-item/MOVIES/grid-0")
        grid.performVerificationTouchInput(compose) {
            swipeDown()
        }
        compose.waitUntil(5_000) { compose.runOnIdle { refreshes == 1 } }
        compose.runOnIdle { assertEquals(movies, state.value.items); assertTrue(state.value.loading) }
        assertEquals("下拉刷新不能替换或移动已有卡片", firstCard, bounds("discovery-item/MOVIES/grid-0"))
    }

    @Test fun errorsRemainVisibleWithoutCreatingAnEmptyCategory() {
        var retries = 0
        val state = DiscoveryState(requested = true, sections = mapOf(
            DiscoveryCategory.MOVIES to page(movie),
            DiscoveryCategory.SERIES to ContentState(error = "服务器暂时不可用"),
        ))
        show { FavoritesContent(state, session, api, { retries++ }, {}, {}) }
        assertVisible("discovery-error-movies", compose.onNodeWithTag("discovery-section/MOVIES", true))
        compose.onNodeWithTag("discovery-section/SERIES", true).assertDoesNotExist()
        assertVisible("discovery-error-message", compose.onNodeWithText("剧集：服务器暂时不可用"))
        touch("discovery-error-retry", compose.onNodeWithText("重试"))
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun emptyPanelLoadingDoesNotDependOnItsTitleAndKeepsItsBounds() {
        val loading = mutableStateOf(false)
        val title = mutableStateOf("Loading media")
        show {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().testTag("empty-panel-frame")) {
                    EmptyPanel(title.value, "状态说明", loading = loading.value)
                }
            }
        }
        assertVisible("empty-panel-static", compose.onNodeWithText(title.value))
        val progress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate) and
            hasAnyAncestor(hasTestTag("empty-panel-frame"))
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
        val frame = bounds("empty-panel-frame")
        compose.runOnIdle { loading.value = true }
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
        assertEquals("切换加载状态不能改变面板占位", frame, bounds("empty-panel-frame"))
        compose.runOnIdle { title.value = "暂无内容" }
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
        val chineseFrame = bounds("empty-panel-frame")
        compose.runOnIdle { loading.value = false }
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
        assertEquals("同一中文文案下加载结束仍保留相同占位", chineseFrame, bounds("empty-panel-frame"))
    }

    private fun page(item: MediaItem) = ContentState(items = listOf(item), total = 1, nextIndex = 1)

    private fun selectFilter(category: String) {
        val tag = "search-filter/$category"
        compose.onNodeWithTag("search-filters").performScrollToNode(hasTestTag(tag))
        touch("select-filter-$category", compose.onNodeWithTag(tag))
    }

    private fun assertVisible(stage: String, target: SemanticsNodeInteraction) {
        VerificationDisplay.awaitWindowReady(compose, stage, target)
        target.assertVerificationDisplayed(compose, stage)
    }

    private fun touch(stage: String, target: SemanticsNodeInteraction) {
        assertVisible(stage, target)
        target.performVerificationClick(compose)
    }

    private fun bounds(tag: String): Rect {
        return compose.onNodeWithTag(tag, true).readVerificationNode(compose) { it.boundsInRoot }
    }

    private fun show(
        fontScale: Float = 1f,
        theme: ThemePreference = ThemePreference.LIGHT,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale),
                LocalPosterLoader provides loader, LocalAppPreferences provides AppPreferences(),
                LocalPageTopInset provides 24.dp, LocalNavigationBottomInset provides 88.dp) {
                PathfinTheme(theme) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }
}

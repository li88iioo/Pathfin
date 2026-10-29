package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.LibraryFilterOptions
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BrowserMediaCard
import dev.pathfin.android.ui.BrowserScreen
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PosterStyle
import dev.pathfin.android.ui.rememberPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 操作真实浏览组件；不读取账号、不连接媒体服务。 */
@RunWith(AndroidJUnit4::class)
class BrowserReferenceUiTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification") {
                "参考浏览回归只允许独立 verification 包"
            }
            base.evaluate()
        }
    } }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test
    fun movieCategoryDrawerPreservesExplicitFiltersWhenEnteringAndLeavingFavorites() {
        val original = LibraryQuery(itemTypes = setOf("Movie"), tags = setOf("冒险"), years = setOf(2021),
            played = false, sort = LibrarySort.TITLE, descending = false)
        val state = mutableStateOf(BrowserUiState(query = original))
        showBrowser(state)
        openCategories()
        compose.onNodeWithTag("browser-tab/UPCOMING").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/TAGS").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/MEDIA").assertDoesNotExist()
        Espresso.pressBack()

        // 收藏页的限制来自 tab，不能把 null/false/true 任一种显式筛选改写到其它页。
        listOf(null, false, true).forEach { explicitFavorite ->
            compose.runOnIdle { state.value = state.value.copy(query = original.copy(favorite = explicitFavorite)) }
            openCategories()
            compose.onNodeWithTag("browser-tab/FAVORITES").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(original.copy(tab = LibraryTab.FAVORITES, favorite = explicitFavorite), state.value.query) }
            openCategories()
            compose.onNodeWithTag("browser-tab/FAVORITES").assertIsSelected()
            compose.onNodeWithTag("browser-tab/ALL").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(original.copy(favorite = explicitFavorite), state.value.query) }
        }
    }

    @Test
    fun mixedLibraryKeepsDirectoryAndMediaCategoriesWhileFavoritesRetainScope() {
        val original = LibraryQuery(itemTypes = setOf("Folder", "Movie", "Series"), recursive = false,
            sort = LibrarySort.TITLE, descending = false, tags = setOf("已选标签"))
        val state = mutableStateOf(BrowserUiState(query = original))
        showBrowser(state, libraryType = null)
        openCategories()
        listOf("UPCOMING", "GENRES", "TAGS").forEach { category ->
            compose.onNodeWithTag("browser-tab/$category").assertDoesNotExist()
        }
        compose.onNodeWithTag("browser-tab/RESUME").assertIsDisplayed()
        compose.onNodeWithTag("browser-tab/MEDIA").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(original.copy(tab = LibraryTab.MEDIA), state.value.query) }
        openCategories()
        compose.onNodeWithTag("browser-tab/MEDIA").assertIsSelected()
        compose.onNodeWithTag("browser-tab/FAVORITES").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original.copy(tab = LibraryTab.FAVORITES), state.value.query) }
        openCategories()
        compose.onNodeWithTag("browser-tab/ALL").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("回到全部仍保留目录范围与既有筛选", original, state.value.query) }
    }

    @Test
    fun televisionCategoriesExposeUpcomingWithoutMediaOrTags() {
        val original = LibraryQuery(itemTypes = setOf("Series"))
        val state = mutableStateOf(BrowserUiState(query = original))
        showBrowser(state, libraryType = "tvshows")
        openCategories()
        compose.onNodeWithTag("browser-tab/UPCOMING").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(original.copy(tab = LibraryTab.UPCOMING), state.value.query) }
        openCategories()
        compose.onNodeWithTag("browser-tab/UPCOMING").assertIsSelected()
        compose.onNodeWithTag("browser-tab/MEDIA").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/TAGS").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/GENRES").assertIsDisplayed()
    }

    @Test
    fun scopedCreditsAndGenreResultsOnlySwitchFavoritesWithoutChangingTheirOriginalScope() {
        val original = LibraryQuery(personId = "synthetic-person", itemTypes = setOf("Movie"),
            sort = LibrarySort.PREMIERE_DATE, descending = true, tags = setOf("保留标签"))
        val state = mutableStateOf(BrowserUiState(query = original))
        showBrowser(state, libraryType = null, libraryCategories = false)
        listOf(original, original.copy(personId = null, genreId = "synthetic-genre", itemTypes = setOf("Series"))).forEach { scope ->
            compose.runOnIdle { state.value = state.value.copy(query = scope) }
            openCategories()
            listOf("MEDIA", "RESUME", "UPCOMING", "COLLECTIONS", "GENRES", "TAGS").forEach { category ->
                compose.onNodeWithTag("browser-tab/$category").assertDoesNotExist()
            }
            compose.onNodeWithTag("browser-tab/FAVORITES").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("收藏必须保留人物/类型与原媒体范围", scope.copy(tab = LibraryTab.FAVORITES), state.value.query) }
            openCategories()
            compose.onNodeWithTag("browser-tab/FAVORITES").assertIsSelected()
            compose.onNodeWithTag("browser-tab/ALL").performClick()
            compose.runOnIdle { assertEquals("回全部必须恢复同一人物/类型作品查询", scope, state.value.query) }
        }
    }

    @Test
    fun filterTagDraftSurvivesOptionRetryAndOnlyConfirmationChangesQuery() {
        val original = LibraryQuery(itemTypes = setOf("Movie"), tags = setOf("已选标签"),
            genres = setOf("冒险"), years = setOf(2021), played = false)
        val state = mutableStateOf(BrowserUiState(query = original,
            filterOptions = LibraryFilterOptions(tags = listOf("新标签")), filtersError = "标签选项暂时不可用"))
        var optionRetries = 0
        var mediaRefreshes = 0
        var queryChanges = 0
        showBrowser(state, onQuery = {
            queryChanges++
            state.value = state.value.copy(query = it)
        }, onRetry = { mediaRefreshes++ }, onRetryFilters = {
            optionRetries++
            state.value = state.value.copy(filtersLoading = true)
        })
        openFilters()
        scrollTag("新标签")
        compose.onNodeWithTag("browser-filter-value/标签/新标签").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(original, state.value.query) }
        compose.onNodeWithTag("browser-filter-options").performScrollToIndex(0)
        val tagsPage = compose.onNode(hasTestTag("refreshable-page") and hasAnyDescendant(hasTestTag("browser-filter-options")))
        pullPageToRefresh(compose, tagsPage, "tag-options-reload")
        pullPageToRefresh(compose, tagsPage, "tag-options-busy")
        compose.onNodeWithTag("browser-filter-retry").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, optionRetries)
            assertEquals(0, mediaRefreshes)
            assertEquals(0, queryChanges)
            state.value = state.value.copy(filterOptions = LibraryFilterOptions(tags = listOf("重试后标签")), filtersLoading = false, filtersError = null)
        }
        scrollTag("新标签")
        compose.onNodeWithTag("browser-filter-value/标签/新标签").assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithTag("browser-filter-apply").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, state.value.query) }

        // 放弃的草稿没有写入查询；重新进入可以选择新读取的选项。
        openFilters()
        scrollTag("重试后标签")
        compose.onNodeWithTag("browser-filter-value/标签/重试后标签").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("browser-filter-apply").performClick()
        compose.runOnIdle {
            assertEquals(original.copy(tags = setOf("已选标签", "重试后标签")), state.value.query)
            assertEquals(1, queryChanges)
            assertEquals(0, mediaRefreshes)
        }
    }

    @Test
    fun emptyFilterOptionsCanBeReloadedByARealPullWithoutApplyingAQuery() {
        val original = LibraryQuery(itemTypes = setOf("Movie"))
        val state = mutableStateOf(BrowserUiState(query = original, filterOptions = LibraryFilterOptions()))
        var optionRequests = 0
        var mediaRequests = 0
        showBrowser(state, onQuery = { throw AssertionError("刷新空标签不能应用查询") }, onRetry = { mediaRequests++ },
            onRetryFilters = { optionRequests++; state.value = state.value.copy(filtersLoading = true) })
        openFilters()
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasTestTag("browser-filter-all/标签"))
        compose.onNodeWithTag("browser-filter-all/标签").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("browser-filter-options").performScrollToIndex(0)
        val page = compose.onNode(hasTestTag("refreshable-page") and hasAnyDescendant(hasTestTag("browser-filter-options")))
        pullPageToRefresh(compose, page, "empty-tags-physical-pull")
        compose.runOnIdle {
            assertEquals(1, optionRequests)
            assertEquals(0, mediaRequests)
            assertEquals(original, state.value.query)
        }
        pullPageToRefresh(compose, page, "empty-tags-busy-pull")
        compose.runOnIdle { assertEquals(1, optionRequests) }
    }

    @Test
    fun longerPosterTitleDoesNotMoveYearOrChangeItToFullMetadata() {
        val item = mutableStateOf(MediaItem("poster", "短标题", "Movie", year = 2021, runTimeTicks = 72_000_000_000,
            indexNumber = 2, parentIndexNumber = 1))
        val api = JellyfinApi()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides rememberPosterLoader(session)) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            BrowserMediaCard(item.value, session, api, PosterStyle.POSTER, Modifier.width(112.dp), onOpen = {})
                        }
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("poster-subtitle/poster", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("2021", useUnmergedTree = true).assertExists()
        compose.runOnIdle { item.value = item.value.copy(name = "这是一段需要两行并且最终会省略的中文电影长标题") }
        val after = compose.onNodeWithTag("poster-subtitle/poster", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("poster-title/poster", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("长标题应实际换为两行", 2, layouts.single().lineCount)
        assertEquals("标题变长不得把副文案和下一行卡片向下推", before.top, after.top, .5f)
        assertEquals(before.height, after.height, .5f)
        compose.onNodeWithText("2021", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2021 · 第 1 季 · 第 2 集 · 120 分钟", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun openFilters() {
        val button = compose.onNodeWithTag("browser-filter")
        VerificationDisplay.awaitWindowReady(compose, "browser-filter-drawer", button)
        button.performClick()
        VerificationDisplay.awaitWindowReady(compose, "browser-filter-dialog", compose.onNodeWithTag("browser-filter-sheet"))
    }

    private fun openCategories() {
        val button = compose.onNodeWithTag("browser-category")
        VerificationDisplay.awaitWindowReady(compose, "browser-category-drawer", button)
        button.performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun scrollTag(text: String) {
        compose.onNodeWithTag("browser-filter-options")
            .performScrollToNode(hasTestTag("browser-filter-value/标签/$text"))
    }

    private fun showBrowser(
        state: MutableState<BrowserUiState>,
        onQuery: (LibraryQuery) -> Unit = { state.value = state.value.copy(query = it) },
        onRetry: () -> Unit = {},
        onRetryFilters: () -> Unit = {},
        libraryType: String? = "movies",
        libraryCategories: Boolean = true,
    ) {
        val api = JellyfinApi()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides rememberPosterLoader(session)) {
                    Surface(Modifier.fillMaxSize()) {
                        BrowserScreen(state.value, session, api, onQueryChange = onQuery, onStyleChange = {}, 
                            onOpen = {}, onRetry = onRetry, onMore = {}, onRetryFilters = onRetryFilters, libraryType = libraryType, libraryCategories = libraryCategories)
                    }
                }
            }
        }
    }

    private companion object {
        val session = Session(ServerConfig("https://browser-reference.invalid"), "fixture-token", "fixture-user", "fixture", "fixture-device")
    }
}

package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
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
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PosterStyle
import dev.pathfin.android.ui.rememberPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实浏览组件；只用合成 DTO，不读取用户账号或系统字号设置。 */
@RunWith(AndroidJUnit4::class)
class BrowserToolbarUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun musicLibraryDoesNotExposeVideoOnlyCategoriesOrSeriesStatus() {
        val state = mutableStateOf(BrowserUiState(query = LibraryQuery(itemTypes = setOf("MusicAlbum")),
            filterOptions = LibraryFilterOptions(listOf("爵士"), listOf("无损"), listOf(2024), emptyList())))
        show(state, libraryType = "music")

        openSheet("browser-category", "browser-tab/ALL")
        compose.onNodeWithTag("browser-tab/RESUME").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/UPCOMING").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/COLLECTIONS").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/GENRES").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("browser-tab/TAGS").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab/FAVORITES").assertVerificationDisplayed(compose)

        Espresso.pressBack()
        openSheet("browser-filter", "browser-filter-apply")
        compose.onNodeWithTag("browser-filter-choice/剧集状态/全部").assertDoesNotExist()
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasTestTag("browser-filter-value/类型/爵士"))
        compose.onNodeWithTag("browser-filter-value/类型/爵士").assertVerificationDisplayed(compose)
    }

    @Test fun sortingUsesExplicitDirectionAndSelectingCurrentSortDoesNotInvertIt() {
        val original = LibraryQuery(itemTypes = setOf("Movie"), genres = setOf("科幻"), years = setOf(2021))
        val state = mutableStateOf(BrowserUiState(query = original, content = ContentState(total = 1234)))
        show(state)
        assertToolbarOrder()
        compose.onNodeWithTag("browser-sort-direction-icon", true).assertContentDescriptionEquals("当前降序")
        compose.onNodeWithTag("browser-tab/ALL").assertDoesNotExist()
        compose.onNodeWithTag("browser-count").assert(hasAnyDescendant(hasText("1234")))

        openSheet("browser-sort", "browser-sort-option/DATE_CREATED")
        compose.onNodeWithTag("browser-sort-option/DATE_CREATED").assertIsSelected().performVerificationClick(compose)
        compose.runOnIdle { assertEquals("重复选择当前排序不能反转升降序", original, state.value.query) }
        openSheet("browser-sort", "browser-sort-option/DATE_CREATED")
        compose.onNodeWithTag("browser-sort-direction").assertTextContains("降序排序").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(original.copy(descending = false), state.value.query) }
        compose.onNodeWithTag("browser-sort-direction-icon", true).assertContentDescriptionEquals("当前升序")
        openSheet("browser-sort", "browser-sort-option/TITLE")
        compose.onNodeWithTag("browser-sort-option/TITLE").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(original.copy(sort = LibrarySort.TITLE, descending = false), state.value.query) }
        openSheet("browser-sort", "browser-sort-option/TITLE")
        compose.onNodeWithTag("browser-sort-direction").assertTextContains("升序排序").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(original.copy(sort = LibrarySort.TITLE, descending = true), state.value.query) }
        compose.onNodeWithTag("browser-sort-direction-icon", true).assertContentDescriptionEquals("当前降序")
    }

    @Test fun fixedCategoriesHideUnsupportedControlsAndGenresKeepSupportedAlphabetNavigation() {
        val original = LibraryQuery(itemTypes = setOf("Series"), sort = LibrarySort.COMMUNITY_RATING,
            descending = true, tags = setOf("保留标签"), nameInitial = "B", played = false)
        val state = mutableStateOf(BrowserUiState(query = original))
        show(state, libraryType = "tvshows", showIndex = true)
        VerificationDisplay.awaitWindowReady(compose, "category-capabilities", compose.onNodeWithTag("browser-toolbar"))
        val stylePosition = bounds("browser-style")

        listOf(LibraryTab.RESUME to "播放日期", LibraryTab.UPCOMING to "首映日期").forEach { (tab, fixedLabel) ->
            compose.runOnIdle { state.value = state.value.copy(query = original.copy(tab = tab)) }
            compose.onNodeWithTag("browser-sort").assertHasNoClickAction().assert(hasAnyDescendant(hasText(fixedLabel)))
            compose.onNodeWithTag("browser-sort-direction-icon", true).assertDoesNotExist()
            compose.onNodeWithTag("browser-filter").assertDoesNotExist()
            compose.onNodeWithTag("browser-reset").assertDoesNotExist()
            compose.onNodeWithTag("library-index-activation").assertDoesNotExist()
            compose.onNodeWithTag("browser-category").assertHasClickAction()
            compose.onNodeWithTag("browser-style").assertHasClickAction()
            assertEquals("隐藏无效筛选时布局切换入口仍应固定", stylePosition, bounds("browser-style"))
            compose.runOnIdle { assertEquals("分类不应悄悄清空原查询", original.copy(tab = tab), state.value.query) }
        }

        compose.runOnIdle { state.value = state.value.copy(query = original.copy(tab = LibraryTab.GENRES)) }
        compose.onNodeWithTag("browser-sort").assertHasNoClickAction().assert(hasAnyDescendant(hasText("标题")))
        compose.onNodeWithTag("browser-sort-direction-icon", true).assertDoesNotExist()
        compose.onNodeWithTag("browser-filter").assertDoesNotExist()
        compose.onNodeWithTag("library-index-activation").assertExists()
        compose.onNodeWithTag("browser-reset").assertVerificationDisplayed(compose).performVerificationClick(compose)
        compose.runOnIdle {
            assertEquals("类型页只能清除受支持的字母索引，原媒体筛选留待返回全部恢复",
                original.copy(tab = LibraryTab.GENRES, nameInitial = null), state.value.query)
        }
        compose.onNodeWithTag("browser-reset").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(query = state.value.query.copy(tab = LibraryTab.ALL))
        }
        compose.onNodeWithTag("browser-sort").assertHasClickAction()
        compose.onNodeWithTag("browser-sort-direction-icon", true).assertContentDescriptionEquals("当前降序")
        compose.onNodeWithTag("browser-filter").assertIsEnabled()
        compose.onNodeWithTag("browser-reset").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("library-index-activation").assertExists()
        compose.runOnIdle { assertEquals(original.copy(nameInitial = null), state.value.query) }
    }

    @Test fun styleButtonTogglesPosterAndBackdropInPlaceWithoutChangingTheLibraryQuery() {
        val original = LibraryQuery(tab = LibraryTab.FAVORITES, itemTypes = setOf("Movie"), played = false)
        val state = mutableStateOf(BrowserUiState(query = original))
        show(state)
        val style = compose.onNodeWithTag("browser-style")
        VerificationDisplay.awaitWindowReady(compose, "browser-style", style)
        repeat(6) { index ->
            val poster = index % 2 == 0
            style.assertContentDescriptionEquals(if (poster) "封面样式：竖幅" else "封面样式：横幅")
            // performClick 注入真实触摸；网格重排后等实际窗口绘制完成，再取按钮的命中位置。
            VerificationDisplay.awaitWindowReady(compose, "browser-style-$index", style)
            style.performVerificationClick(compose)
            compose.runOnIdle {
                assertEquals(if (poster) PosterStyle.BACKDROP else PosterStyle.POSTER, state.value.style)
                assertEquals(original, state.value.query)
            }
            // 直接切换，不再弹出布局抽屉。
            compose.onNodeWithTag("browser-style/POSTER").assertDoesNotExist()
        }
        style.assertContentDescriptionEquals("封面样式：竖幅")
    }

    @Test fun resetAppearsOnlyWithActiveFiltersOrLetterAndClearsBothButKeepsSortAndScope() {
        val plain = LibraryQuery(tab = LibraryTab.ALL, sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Movie"))
        val state = mutableStateOf(BrowserUiState(query = plain))
        show(state)
        VerificationDisplay.awaitWindowReady(compose, "browser-toolbar", compose.onNodeWithTag("browser-category"))
        compose.onNodeWithTag("browser-reset").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(query = plain.copy(nameInitial = "B")) }
        compose.onNodeWithTag("browser-reset").assertVerificationDisplayed(compose)
        compose.runOnIdle { state.value = state.value.copy(query = plain.copy(nameInitial = "B", played = false, genres = setOf("科幻"))) }
        compose.onNodeWithTag("browser-reset").assertVerificationDisplayed(compose).performVerificationClick(compose)
        compose.runOnIdle { assertEquals("只清筛选与字母，排序和类型保持", plain, state.value.query) }
        compose.onNodeWithTag("browser-reset").assertDoesNotExist()
    }

    @Test fun filterResetIsADraftUntilConfirmationAndDismissalKeepsEveryOriginalFilter() {
        val original = LibraryQuery(tab = LibraryTab.ALL, sort = LibrarySort.TITLE, descending = false,
            itemTypes = setOf("Movie"), played = false, favorite = true, genres = setOf("科幻"), tags = setOf("冒险"),
            years = setOf(2021), officialRatings = setOf("PG"))
        val state = mutableStateOf(BrowserUiState(query = original,
            filterOptions = LibraryFilterOptions(listOf("科幻", "纪录片"), listOf("冒险"), listOf(2021), listOf("PG"))))
        show(state)
        openSheet("browser-filter", "browser-filter-apply")
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasTestTag("browser-filter-value/类型/科幻"))
        compose.onNodeWithTag("browser-filter-value/类型/科幻").assertIsSelected()
        compose.onNodeWithTag("browser-filter-reset").assertVerificationDisplayed(compose).performVerificationClick(compose)
        compose.runOnIdle { assertEquals("重置仅修改抽屉草稿", original, state.value.query) }
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasTestTag("browser-filter-all/类型"))
        compose.onNodeWithTag("browser-filter-all/类型").assertIsSelected()
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(original, state.value.query) }
        openSheet("browser-filter", "browser-filter-apply")
        compose.onNodeWithTag("browser-filter-options").performScrollToNode(hasTestTag("browser-filter-value/发行年份/2021"))
        compose.onNodeWithTag("browser-filter-value/发行年份/2021").assertIsSelected()
        compose.onNodeWithTag("browser-filter-reset").performVerificationClick(compose)
        compose.onNodeWithTag("browser-filter-apply").assertVerificationDisplayed(compose).performVerificationClick(compose)
        compose.runOnIdle { assertEquals(original.copy(played = null, favorite = null, genres = emptySet(), tags = emptySet(),
            years = emptySet(), officialRatings = emptySet()), state.value.query) }
    }

    @Test fun toolbarAndSingleLineCardsRemainReadableInNarrowLargeTextAndLandscapeWindows() {
        val viewport = mutableStateOf(DpSize(300.dp, 640.dp))
        val fontScale = mutableStateOf(1.6f)
        val item = MediaItem("layout-series", "需要省略但绝不能挤出第二行的中文系列长标题", "Series",
            year = 2021, childCount = 2, communityRating = 8.5)
        val state = mutableStateOf(BrowserUiState(content = ContentState(items = listOf(item), total = 1, nextIndex = 1)))
        show(state, viewport, fontScale)
        assertControlsDoNotOverlap(expanded = true)
        assertCardText(item.id, "共2季 · 2021")
        compose.runOnIdle { viewport.value = DpSize(720.dp, 360.dp); fontScale.value = 1.3f }
        compose.waitForIdle()
        // 等新视口首绘完成再断言命中区。
        VerificationDisplay.awaitWindowReady(compose, "landscape-toolbar",
            compose.onNodeWithTag("browser-style"), compose.onNodeWithTag("browser-filter"))
        assertControlsDoNotOverlap(expanded = false)
        assertToolbarOrder()
        val toolbarBounds = bounds("browser-toolbar")
        val grid = compose.onNodeWithTag("browser-grid")
        VerificationDisplay.awaitWindowReady(compose, "landscape-library-grid", grid)
        // 横屏海报可高于视口；验证真实滚动后资料可达，不要求把整个卡片压缩进首屏。
        grid.performVerificationTouchInput(compose) { swipeUp() }
        compose.waitForIdle()
        assertTrue("横屏应实际滚动到海报下方资料", grid.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertCardText(item.id, "共2季 · 2021")
        assertEquals("网格滚动不能移动固定工具栏", toolbarBounds,
            bounds("browser-toolbar"))
    }

    private fun assertCardText(id: String, subtitle: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("poster-title/$id", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val title = layouts.single()
        assertEquals(1, title.lineCount)
        assertEquals(TextAlign.Start, title.layoutInput.style.textAlign)
        compose.onNodeWithTag("poster-subtitle/$id", true).assertTextEquals(subtitle).assertVerificationDisplayed(compose)
    }

    private fun assertToolbarOrder() {
        val nodes = listOf("browser-sort", "browser-count", "browser-category", "browser-style", "browser-filter")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        val bounds = compose.runOnIdle { nodes.map { it.boundsInRoot } }
        bounds.forEach { assertTrue("工具必须具有可见尺寸", it.width > 0f && it.height > 0f) }
        bounds.zipWithNext().forEach { (first, second) -> assertTrue("工具栏顺序必须为排序/数量/分类/布局/筛选", first.right <= second.left + 1f) }
        assertTrue("数量必须紧邻排序，不被弹性空白推向分类", bounds[1].left - bounds[0].right <= 4f * compose.density.density + 1f)
    }

    private fun assertControlsDoNotOverlap(expanded: Boolean) {
        VerificationDisplay.awaitWindowReady(compose, "toolbar-layout-$expanded", compose.onNodeWithTag("browser-toolbar"))
        val toolbarNode = compose.onNodeWithTag("browser-toolbar").fetchSemanticsNode()
        val controls = listOf("browser-sort", "browser-count", "browser-category", "browser-style", "browser-filter")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        // 同一 UI 帧读取坐标，避开窗口尺寸改变时的可见矩形缓存竞争。
        val (toolbar, nodes) = compose.runOnIdle { toolbarNode.boundsInRoot to controls.map { it.boundsInRoot } }
        nodes.forEach { assertTrue("工具必须具有可见尺寸", it.width > 0f && it.height > 0f) }
        nodes.forEach { assertTrue("工具必须保持在工具栏可见边界内：$it，工具栏 $toolbar", it.left >= toolbar.left - 1 && it.right <= toolbar.right + 1) }
        nodes.indices.forEach { a -> (a + 1 until nodes.size).forEach { b ->
            val overlap = nodes[a].intersect(nodes[b])
            assertTrue("大字号/横屏不能使工具命中区重叠", overlap.width <= 1f || overlap.height <= 1f)
        } }
        if (expanded) assertTrue(nodes[3].top >= nodes.take(3).maxOf(Rect::bottom) - 1f)
    }

    private fun bounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return compose.runOnIdle { node.boundsInRoot }
    }

    private fun openSheet(button: String, expected: String) {
        val node = compose.onNodeWithTag(button)
        VerificationDisplay.awaitWindowReady(compose, button, node)
        node.assertIsEnabled().performVerificationClick(compose)
        val anchorTag = when (button) {
            "browser-sort" -> "browser-sort-sheet"
            "browser-category" -> "browser-category-sheet"
            "browser-filter" -> "browser-filter-sheet"
            else -> error("未知浏览抽屉")
        }
        // ModalBottomSheet 拥有独立窗口；宿主 toolbar 的就绪不能代替新弹层首绘与焦点。
        val anchor = compose.onNodeWithTag(anchorTag)
        VerificationDisplay.awaitWindowReady(compose, "$button-dialog", anchor)
        anchor.assertVerificationDisplayed(compose)
        compose.onNodeWithTag(expected).assertVerificationDisplayed(compose)
    }

    private fun show(state: MutableState<BrowserUiState>, viewport: MutableState<DpSize> = mutableStateOf(DpSize(390.dp, 760.dp)),
        fontScale: MutableState<Float> = mutableStateOf(1f), libraryType: String? = "movies", showIndex: Boolean = false) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val api = JellyfinApi()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(viewport.value) then DeviceConfigurationOverride.FontScale(fontScale.value)) {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides rememberPosterLoader(SESSION)) {
                        Surface(Modifier.fillMaxSize()) {
                            BrowserScreen(state.value, SESSION, api,
                                onQueryChange = { state.value = state.value.copy(query = it) },
                                onStyleChange = { state.value = state.value.copy(style = it) },
                                onOpen = {}, onRetry = {}, onMore = {}, libraryType = libraryType, showIndex = showIndex)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val SESSION = Session(ServerConfig("https://browser-toolbar.invalid"), "synthetic-toolbar-token", "synthetic-user", "fixture", "synthetic-device")
    }
}

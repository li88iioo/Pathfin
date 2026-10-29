package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MetadataRefreshMode
import dev.pathfin.android.domain.MetadataRefreshOptions
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BatchMediaAction
import dev.pathfin.android.ui.BrowserScreen
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LibrarySelectionBar
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PosterStyle
import dev.pathfin.android.ui.rememberLibrarySelection
import dev.pathfin.android.ui.rememberPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 媒体库多选：真实网格与选择标题栏，只用合成条目，不连接服务器。 */
@RunWith(AndroidJUnit4::class)
class LibrarySelectionUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private var selection = mutableStateOf(emptySet<String>())
    private val pageKey = mutableStateOf("library")
    private val browser = mutableStateOf(BrowserUiState(content = ContentState(items = ITEMS, total = ITEMS.size)))
    private val opened = mutableListOf<String>()
    private val actions = mutableListOf<BatchMediaAction>()
    private val canDelete = mutableStateOf(true)
    private val canRefresh = mutableStateOf(true)

    @Test fun longPressStartsSelectionTapsToggleAndCancelOrBackLeaves() {
        show()
        title("Alpha").performClick()
        compose.runOnIdle { assertEquals("未选中时点按仍打开详情", listOf("alpha"), opened) }

        title("Alpha").performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-count").assertTextEquals("已选择 1 项")
        title("Beta").performClick()
        title("Alpha").performClick()
        compose.runOnIdle {
            assertEquals(setOf("beta"), selection.value)
            assertEquals("选择态的点按只增减选中，不打开详情", listOf("alpha"), opened)
        }

        compose.onNodeWithTag("library-selection-cancel").performClick()
        compose.onNodeWithTag("library-selection-count").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptySet<String>(), selection.value) }

        title("Gamma").performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-count").assertTextEquals("已选择 1 项")
        Espresso.pressBack()
        compose.onNodeWithTag("library-selection-count").assertDoesNotExist()
        compose.runOnIdle { assertEquals("返回键只退出选择", emptySet<String>(), selection.value) }
    }

    @Test fun menuFollowsPermissionsAndEmitsTheChosenBatchActions() {
        canDelete.value = false; canRefresh.value = false
        show()
        compose.runOnIdle { selection.value = setOf("alpha", "beta") }
        openMenu()
        compose.onNodeWithTag("library-selection-played").assertExists()
        compose.onNodeWithTag("library-selection-unplayed").assertExists()
        compose.onNodeWithTag("library-selection-refresh").assertDoesNotExist()
        compose.onNodeWithTag("library-selection-delete").assertDoesNotExist()
        compose.onNodeWithTag("library-selection-unplayed").performClick()
        compose.runOnIdle { assertEquals(listOf<BatchMediaAction>(BatchMediaAction.Played(false)), actions) }

        canDelete.value = true; canRefresh.value = true
        selection.value = setOf("alpha", "beta")
        openMenu()
        compose.onNodeWithTag("library-selection-delete").performClick()
        compose.onNodeWithText("确认删除已选的 2 项？", substring = true).assertExists()
        compose.onNodeWithTag("library-selection-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(BatchMediaAction.Delete, actions.last()) }

        selection.value = setOf("alpha")
        openMenu()
        compose.onNodeWithTag("library-selection-refresh").performClick()
        // 默认是扫描，此时两个替换选项不可用。
        compose.onNodeWithTag("library-refresh-scan").assertIsSelected()
        compose.onNodeWithTag("library-refresh-replace-images").assertIsNotEnabled()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsNotEnabled()
        compose.onNodeWithTag("library-refresh-missing").performClick()
        compose.onNodeWithTag("library-refresh-replace-images").assertIsEnabled().performClick()
        compose.onNodeWithTag("library-refresh-confirm").performClick()
        compose.runOnIdle {
            assertEquals(BatchMediaAction.Refresh(MetadataRefreshOptions(MetadataRefreshMode.FULL_REFRESH,
                MetadataRefreshMode.FULL_REFRESH, replaceAllImages = true)), actions.last())
        }
    }

    @Test fun resultContextChangesClearSelectionWithoutRecreatingThePage() {
        show()
        val original = browser.value.query
        for (query in listOf(original.copy(sort = LibrarySort.TITLE), original.copy(nameInitial = "B"),
                original.copy(years = setOf(2026)), original.copy(tab = LibraryTab.GENRES))) {
            compose.runOnIdle { browser.value = browser.value.copy(query = original) }
            title("Alpha").performTouchInput { longClick() }
            compose.onNodeWithTag("library-selection-count").assertTextEquals("已选择 1 项")
            compose.runOnIdle { browser.value = browser.value.copy(query = query) }
            compose.onNodeWithTag("library-selection-count").assertDoesNotExist()
            compose.runOnIdle { assertEquals(emptySet<String>(), selection.value) }
        }
        compose.runOnIdle { browser.value = browser.value.copy(query = original) }
        title("Alpha").performTouchInput { longClick() }
        compose.runOnIdle { pageKey.value = "other-library" }
        compose.onNodeWithTag("library-selection-count").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptySet<String>(), selection.value) }
    }

    @Test fun appendingResultsAndChangingPosterStylePreserveSelection() {
        show()
        title("Alpha").performTouchInput { longClick() }
        compose.runOnIdle {
            browser.value = browser.value.copy(content = ContentState(items = ITEMS + MediaItem("delta", "Delta", "Movie"), total = 4))
        }
        compose.onNodeWithTag("library-selection-count").assertTextEquals("已选择 1 项")
        compose.onNodeWithTag("browser-style").performClick()
        compose.runOnIdle {
            assertEquals(PosterStyle.BACKDROP, browser.value.style)
            assertEquals(setOf("alpha"), selection.value)
        }
        title("Beta").performClick()
        compose.onNodeWithTag("library-selection-count").assertTextEquals("已选择 2 项")
        compose.runOnIdle { assertEquals(setOf("alpha", "beta"), selection.value) }
    }

    @Test fun classificationEntrypointsNeverBecomeSelectedEvenWithAStaleSelection() {
        show()
        for ((query, type) in listOf(LibraryQuery(tab = LibraryTab.GENRES) to "Genre", LibraryQuery(itemTypes = setOf("Person")) to "Person")) {
            compose.runOnIdle {
                browser.value = BrowserUiState(query = query, content = ContentState(items = listOf(MediaItem("entry", "Entry", type)), total = 1))
            }
            title("Entry").performTouchInput { longClick() }
            compose.runOnIdle { assertEquals(emptySet<String>(), selection.value) }
            compose.runOnIdle { selection.value = setOf("stale-id") }
            title("Entry").performTouchInput { longClick() }
            title("Entry").performClick()
            compose.runOnIdle {
                assertEquals(setOf("stale-id"), selection.value)
                assertEquals("entry", opened.last())
            }
        }
    }

    private fun title(name: String) = compose.onAllNodesWithText(name, substring = false).onFirst()

    private fun openMenu() {
        compose.onNodeWithTag("library-selection-more").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("library-selection-played").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun show() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val api = JellyfinApi()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides rememberPosterLoader(SESSION)) {
                    val pageSelection = rememberLibrarySelection(pageKey.value, browser.value.query)
                    SideEffect { selection = pageSelection }
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            if (pageSelection.value.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
                                LibrarySelectionBar(pageSelection.value.size, busy = false, canDelete = canDelete.value,
                                    canRefresh = canRefresh.value, onCancel = { pageSelection.value = emptySet() }) { action ->
                                    pageSelection.value = emptySet(); actions += action
                                }
                            }
                            BrowserScreen(browser.value, SESSION, api, onQueryChange = { browser.value = browser.value.copy(query = it) },
                                onStyleChange = { browser.value = browser.value.copy(style = it) },
                                onOpen = { opened += it.id }, onRetry = {}, onMore = {}, libraryType = "movies",
                                selection = pageSelection.value, onSelectionChange = { pageSelection.value = it })
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        val SESSION = Session(ServerConfig("https://library-selection.invalid"), "synthetic-selection-token", "synthetic-user", "fixture", "synthetic-device")
        val ITEMS = listOf("Alpha", "Beta", "Gamma").map { MediaItem(it.lowercase(), it, "Movie") }
    }
}

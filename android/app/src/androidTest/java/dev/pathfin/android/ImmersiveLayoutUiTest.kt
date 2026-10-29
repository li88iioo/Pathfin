package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import coil3.ImageLoader
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppSystemBars
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 完整页面外壳的窗口边界回归：背景延展到系统栏，实际控件留在安全区。 */
class ImmersiveLayoutUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private var loader: ImageLoader? = null
    private lateinit var viewModel: AppViewModel

    @After fun release() {
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        loader?.shutdown()
    }

    @Test fun connectionCanvasReachesWindowTopWhileWordmarkAvoidsVisibleAndHiddenStatusBars() {
        val state = showApp(AppState(showServers = true))
        assertConnectionBounds(hideStatusBar = false)
        compose.runOnIdle {
            state.value = state.value.copy(preferences = state.value.preferences.copy(hideSystemStatusBar = true))
        }
        assertConnectionBounds(hideStatusBar = true)
    }

    @Test fun floatingNavigationReachesTheWindowBottomAndKeepsTabsAboveSystemGestures() {
        val session = Session(ServerConfig("https://layout.fixture.invalid"), "synthetic-token", "synthetic-user", "布局测试", "synthetic-device")
        val profile = ServerProfile(id = "synthetic-profile", displayName = "布局测试", session = session, icon = "🎬")
        val libraries = List(4) { index -> MediaItem("layout-library-$index", "测试媒体库 $index", "CollectionFolder", isFolder = true) }
        val movies = libraries.mapIndexed { index, _ -> MediaItem("layout-movie-$index", "测试影片 $index", "Movie", year = 2026) }
        val state = showApp(AppState(session = session, profiles = listOf(profile), currentProfileId = profile.id,
            home = HomeState(libraries = libraries, libraryItems = libraries.mapIndexed { index, library -> library.id to listOf(movies[index]) }.toMap(),
                loaded = true, librariesLoaded = true, resumeLoaded = true, nextUpLoaded = true, complete = true)))
        val background = "app-floating-navigation"
        val material = "app-dock-surface"
        assertNavigationBounds(background)
        compose.onNodeWithTag("home-list").performScrollToIndex(libraries.size + 1)
        val subtitleTag = "poster-subtitle/${movies.last().id}"
        assertContentAboveNavigation(subtitleTag, material, useUnmergedTree = true)
        val oldNavigation = bounds(background)
        val oldContent = bounds(subtitleTag, useUnmergedTree = true)
        compose.runOnIdle { state.value = state.value.copy(preferences = state.value.preferences.copy(blurTopBar = true)) }
        assertNavigationBounds(background)
        assertContentAboveNavigation(subtitleTag, material, useUnmergedTree = true)
        assertEquals("模糊开关不能移动真实底栏", oldNavigation, bounds(background))
        assertEquals("模糊开关不能重排已滚至末尾的首页内容", oldContent, bounds(subtitleTag, useUnmergedTree = true))
        compose.onNodeWithTag("app-tab-bar").assertDoesNotExist()
    }

    @Test fun managerNavigationLeavesTheLastServerReachableWithVisibleAndHiddenSystemBars() {
        val session = Session(ServerConfig("https://layout.fixture.invalid"), "synthetic-token", "synthetic-user", "布局测试", "synthetic-device")
        val profiles = List(12) { index -> ServerProfile(id = "layout-server-$index", displayName = "测试服务器 $index",
            session = session.copy(userId = "synthetic-user-$index"), icon = "🎬") }
        val state = showApp(AppState(showServers = true, session = session, profiles = profiles, currentProfileId = profiles.first().id,
            home = HomeState(loaded = true, librariesLoaded = true)))
        val lastTag = "server-row/${profiles.last().id}"

        for (hidden in listOf(false, true)) {
            compose.runOnIdle { state.value = state.value.copy(preferences = state.value.preferences.copy(hideSystemStatusBar = hidden, blurTopBar = false)) }
            assertNavigationBounds("app-manager-navigation", "服务器", checkHomeAction = false)
            compose.onNodeWithTag("server-list").performScrollToIndex(profiles.size + 1)
            assertContentAboveNavigation(lastTag, "app-dock-surface")
            assertEquals("服务器正文必须延展到导航背后的系统底边", bounds("browser-back-motion").bottom, bounds("server-list").bottom, 0.5f)
            val oldContent = bounds(lastTag)
            val oldNavigation = bounds("app-manager-navigation")

            compose.runOnIdle { state.value = state.value.copy(preferences = state.value.preferences.copy(blurTopBar = true)) }
            assertNavigationBounds("app-manager-navigation", "服务器", checkHomeAction = false)
            assertContentAboveNavigation(lastTag, "app-dock-surface")
            assertEquals("模糊开关不能移动服务器导航", oldNavigation, bounds("app-manager-navigation"))
            assertEquals("模糊开关不能移动服务器列表末项", oldContent, bounds(lastTag))
        }
        // 用真实触摸打开末项操作，避免只验证被导航遮挡的语义节点仍然存在。
        compose.onNodeWithContentDescription("服务器操作 ${profiles.last().displayName}").performTouchInput { click() }
        compose.onNodeWithText("显示名称").assertIsDisplayed()
    }

    private fun showApp(initial: AppState): MutableState<AppState> {
        check(compose.activity.packageName == "dev.pathfin.android.verification")
        val state = mutableStateOf(initial)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        }
        val images = ImageLoader.Builder(compose.activity).diskCache(null).build().also { loader = it }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides state.value.preferences) {
                    AppSystemBars()
                    Surface(Modifier.fillMaxSize()) {
                        PathfinApp(viewModel, state.value, rememberSaveableStateHolder(), posterLoader = images)
                    }
                }
            }
        }
        return state
    }

    private fun assertConnectionBounds(hideStatusBar: Boolean) {
        val page = compose.onNodeWithTag("server-address-page")
        val wordmark = compose.onNodeWithText("Pathfin", substring = false)
        VerificationDisplay.awaitWindowReady(compose, "connection-insets-$hideStatusBar", page, wordmark)
        val root = bounds("browser-back-motion")
        assertEquals("连接页画布必须从窗口顶端开始", root.top, bounds("server-address-page").top, 0.5f)
        assertEquals("连接页画布必须延展到窗口底端", root.bottom, bounds("server-address-page").bottom, 0.5f)
        val wordmarkNode = wordmark.fetchSemanticsNode()
        compose.runOnIdle {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            val cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout()).top
            val status = if (hideStatusBar) 0 else insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            assertTrue("字标应避开状态栏和屏幕缺口", wordmarkNode.boundsInRoot.top >= root.top + maxOf(cutout, status))
        }
        compose.onNodeWithTag("login-server").assertIsDisplayed()
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
    }

    private fun assertNavigationBounds(tag: String, tabDescription: String = "首页", checkHomeAction: Boolean = true) {
        val navigation = compose.onNodeWithTag(tag)
        val home = compose.onNodeWithContentDescription(tabDescription)
        VerificationDisplay.awaitWindowReady(compose, "navigation-insets-$tag", navigation, home)
        val root = bounds("browser-back-motion")
        assertEquals("导航背景应延展到系统手势区底部", root.bottom, bounds(tag).bottom, 0.5f)
        val homeNode = home.fetchSemanticsNode()
        compose.runOnIdle {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
                .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("导航触摸区域不得覆盖系统手势条", homeNode.boundsInRoot.bottom <= root.bottom - insets.bottom + 0.5f)
            // 同一 UI 线程读取宿主的真实可见区域，避免 Compose 可见矩形缓存跨线程竞争。
            val view = checkNotNull(homeNode.root as? ViewRootForTest).view
            val localVisible = android.graphics.Rect()
            assertTrue("导航宿主必须有真实可见区域", view.getLocalVisibleRect(localVisible))
            val origin = IntArray(2).also(view::getLocationInWindow)
            val viewport = Rect(localVisible.left + origin[0].toFloat(), localVisible.top + origin[1].toFloat(),
                localVisible.right + origin[0].toFloat(), localVisible.bottom + origin[1].toFloat())
            val homeArea = homeNode.boundsInWindow
            assertTrue("首页触摸区域必须已测量", homeArea.width > 0f && homeArea.height > 0f)
            assertEquals("首页触摸区域必须完整显示在窗口内", homeArea, homeArea.intersect(viewport))
            if (checkHomeAction) {
                viewModel.selectTab(Route.Settings)
                assertEquals(Route.Settings, viewModel.state.value.route)
            }
        }
        if (checkHomeAction) {
            home.performTouchInput {
                compose.runOnUiThread { visibleSize }
                click()
            }
            compose.runOnIdle { assertEquals("真实触摸必须能进入首页", Route.Home, viewModel.state.value.route) }
        }
    }

    private fun assertContentAboveNavigation(contentTag: String, navigationTag: String, useUnmergedTree: Boolean = false) {
        compose.onNodeWithTag(contentTag, useUnmergedTree).assertIsDisplayed()
        assertTrue("列表末项必须完整露出在底栏上方", bounds(contentTag, useUnmergedTree).bottom <= bounds(navigationTag).top + 0.5f)
    }

    private fun bounds(tag: String, useUnmergedTree: Boolean = false): Rect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree).fetchSemanticsNode()
        return compose.runOnIdle { node.boundsInRoot }
    }
}

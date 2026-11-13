package dev.pathfin.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect as PixelRect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.test.filters.SdkSuppress
import kotlin.math.roundToInt
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
    private val drawPulse = mutableIntStateOf(0)
    private var onAppDraw: ((AppState) -> Unit)? = null

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
        val profile = ServerProfile(id = "synthetic-profile", displayName = "布局测试", session = session)
        val libraries = List(4) { index -> MediaItem("layout-library-$index", "测试媒体库 $index", "CollectionFolder", isFolder = true) }
        val movies = libraries.mapIndexed { index, _ -> MediaItem("layout-movie-$index", "测试影片 $index", "Movie", year = 2026) }
        val state = showApp(AppState(session = session, profiles = listOf(profile), currentProfileId = profile.id,
            home = HomeState(libraries = libraries, libraryItems = libraries.mapIndexed { index, library -> library.id to listOf(movies[index]) }.toMap(),
                loaded = true, librariesLoaded = true, resumeLoaded = true, nextUpLoaded = true, complete = true)))
        val background = "app-floating-navigation"
        val material = "app-dock-surface"
        compose.onNodeWithText(profile.displayName).assertDoesNotExist()
        compose.onNodeWithContentDescription("切换服务器").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("home-more").assertVerificationDisplayed(compose)
        assertNavigationBounds(background)
        // 没有观看记录也没有剧照时不显示氛围区：首项是库标签，其后各库一栏，滚到最后一栏。
        compose.onNodeWithTag("home-list").performScrollToIndex(libraries.size)
        val subtitleTag = "poster-subtitle/${movies.last().id}"
        assertContentAboveNavigation(subtitleTag, material, useUnmergedTree = true)
        compose.onNodeWithTag("app-tab-bar").assertDoesNotExist()
    }

    @Test fun managerNavigationLeavesTheLastServerReachableWithVisibleAndHiddenSystemBars() {
        val session = Session(ServerConfig("https://layout.fixture.invalid"), "synthetic-token", "synthetic-user", "布局测试", "synthetic-device")
        val profiles = List(12) { index -> ServerProfile(id = "layout-server-$index", displayName = "测试服务器 $index",
            session = session.copy(userId = "synthetic-user-$index")) }
        val state = showApp(AppState(showServers = true, session = session, profiles = profiles, currentProfileId = profiles.first().id,
            home = HomeState(loaded = true, librariesLoaded = true)))
        val lastTag = "server-row/${profiles.last().id}"

        for (hidden in listOf(false, true)) {
            compose.runOnIdle { state.value = state.value.copy(preferences = state.value.preferences.copy(hideSystemStatusBar = hidden)) }
            assertNavigationBounds("app-manager-navigation", "服务器", checkHomeAction = false)
            compose.onNodeWithTag("server-list").performScrollToIndex(profiles.size + 1)
            assertContentAboveNavigation(lastTag, "app-dock-surface")
            assertEquals("服务器正文必须延展到导航背后的系统底边", bounds("browser-back-motion").bottom, bounds("server-list").bottom, 0.5f)
        }
        // 用真实触摸打开末项操作，避免只验证被导航遮挡的语义节点仍然存在。
        compose.onNodeWithContentDescription("服务器操作 ${profiles.last().displayName}").performVerificationClick(compose)
        compose.onNodeWithText("显示名称").assertVerificationDisplayed(compose)
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun returningToHomeKeepsTheAmbientHeaderDarkFromTheFirstCommittedFrame() {
        val state = showApp(homeApp())
        VerificationDisplay.awaitWindowReady(compose, "home-return-initial", compose.onNodeWithTag("home-more"))
        val baseline = homeHeaderFrames(state).average()
        assertTrue("固定深灰的首页顶栏必须已就绪：$baseline", baseline < 0.35)
        val traces = linkedMapOf<String, List<Float>>()
        val paths = listOf(listOf(Route.Search), listOf(Route.Favorites), listOf(Route.LiveTv),
            listOf(Route.LiveTv, Route.Search), listOf(Route.LiveTv, Route.Favorites), listOf(Route.Settings))
        for (path in paths) {
            path.forEach { homeHeaderFrames(state, it) }
            traces[path.joinToString("/") { it.key }] = homeHeaderFrames(state)
        }
        assertTrue("返回首页不能出现高于稳定背景的浅色首帧：baseline=$baseline traces=$traces",
            traces.values.flatten().all { it < baseline + 0.15f })
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun returningHomePreservesPartialAndDeepScrollWithTheCorrectHeaderFromTheFirstFrame() {
        val initial = homeApp()
        val state = showApp(initial)
        VerificationDisplay.awaitWindowReady(compose, "home-scroll-initial", compose.onNodeWithTag("home-more"))
        val list = compose.onNodeWithTag("home-list")
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 48f * compose.activity.resources.displayMetrics.density) }
        compose.waitForIdle()
        val partial = bounds("home-ambient").bottom
        val dark = homeHeaderFrames(state).average()
        assertTrue("局部滚动仍有封面覆盖顶栏：$dark", dark < .35)
        for (route in listOf(Route.LiveTv, Route.Search, Route.Favorites, Route.Settings)) {
            homeHeaderFrames(state, route)
            val frames = homeHeaderFrames(state)
            assertTrue("$route 返回封面内不能闪浅色：$frames", frames.all { it < dark + .15 })
            assertEquals("$route 返回不能重置部分滚动位置", partial, bounds("home-ambient").bottom, .5f)
        }
        val anchor = "library-shelf/${initial.home.libraries[1].id}"
        list.performScrollToKey(anchor)
        val before = bounds(anchor).top
        val light = homeHeaderFrames(state).average()
        assertTrue("封面滚出后顶栏应恢复浅色：$light", light > dark + .2)
        for (route in listOf(Route.LiveTv, Route.Search, Route.Favorites, Route.Settings)) {
            homeHeaderFrames(state, route)
            val frames = homeHeaderFrames(state)
            assertTrue("$route 返回非封面区不能闪深色：$frames", frames.all { it > light - .15 })
            assertEquals("$route 返回不能重置深度滚动位置", before, bounds(anchor).top, .5f)
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun emptyPendingLoadedAndFailedHomeUseTheCorrectHeaderWithoutAnIntermediateWrongFrame() {
        val initial = homeApp()
        val empty = HomeState(loaded = true, complete = true, librariesLoaded = true, resumeLoaded = true, nextUpLoaded = true)
        val state = showApp(initial.copy(home = empty))
        VerificationDisplay.awaitWindowReady(compose, "home-empty-initial", compose.onNodeWithTag("home-more"))
        val light = homeHeaderFrames(state).average()
        assertTrue("没有氛围区的首页应为浅色：$light", light > .5)
        homeHeaderFrames(state, Route.Settings)
        assertTrue(homeHeaderFrames(state).all { it > light - .15 })
        val pending = homeHeaderFrames(state) { it.copy(home = HomeState(loading = true)) }
        assertTrue("氛围占位首帧就应使用深色：$pending", pending.all { it < light - .2 })
        val loaded = homeHeaderFrames(state) { it.copy(home = initial.home) }
        assertTrue("内容到达后保持深色：$loaded", loaded.all { it < light - .2 })
        val cleared = homeHeaderFrames(state) { it.copy(home = empty) }
        assertTrue("氛围区移除时直接恢复浅色：$cleared", cleared.all { it > light - .15 })
        val failed = homeHeaderFrames(state) { it.copy(home = HomeState(error = "合成加载失败")) }
        assertTrue("加载失败无封面时不能强制深色：$failed", failed.all { it > light - .15 })
    }

    private fun homeApp(): AppState {
        val session = Session(ServerConfig("https://home-return.fixture.invalid"), "synthetic-token",
            "synthetic-user", "返回测试", "synthetic-device")
        val profile = ServerProfile(id = "synthetic-home-return", displayName = "返回测试", session = session)
        val movie = MediaItem("home-return-movie", "合成续播影片", "Movie", positionTicks = 10, runTimeTicks = 100)
        val libraries = List(6) { MediaItem("home-return-library-$it", "合成媒体库 $it", "CollectionFolder", isFolder = true) }
        return AppState(session = session, profiles = listOf(profile), currentProfileId = profile.id,
            home = HomeState(resume = listOf(movie), libraries = libraries,
                libraryItems = libraries.associate { it.id to listOf(movie.copy(id = "movie-${it.id}")) },
                loaded = true, complete = true, librariesLoaded = true, resumeLoaded = true, nextUpLoaded = true))
    }

    private fun homeHeaderFrames(state: MutableState<AppState>, selected: Route = Route.Home,
        update: (AppState) -> AppState = { it }): List<Float> {
        val root = compose.onNodeWithTag("browser-back-motion").fetchSemanticsNode()
        val region = compose.runOnIdle {
            val view = checkNotNull(root.root as? ViewRootForTest).view
            val origin = IntArray(2).also(view::getLocationInWindow)
            val status = requireNotNull(ViewCompat.getRootWindowInsets(view))
                .getInsets(WindowInsetsCompat.Type.statusBars()).top
            val x = (root.boundsInRoot.left + root.boundsInRoot.width * 0.6f).roundToInt() + origin[0]
            val y = root.boundsInRoot.top.roundToInt() + origin[1] + status +
                (20 * view.resources.displayMetrics.density).roundToInt()
            PixelRect(x - 2, y - 2, x + 3, y + 3)
        }
        val samples = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val view = compose.activity.window.decorView
        val handler = Handler(Looper.getMainLooper())
        var pending = false
        var drawnState: AppState? = null
        lateinit var expected: AppState
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!pending && samples.size < 8) {
                pending = true
                drawnState = null
                view.viewTreeObserver.registerFrameCommitCallback {
                    // 同一路由数据更新也必须排除更新前的帧。
                    if (drawnState == expected) {
                        val bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(compose.activity.window, region, bitmap, { result ->
                            if (result == PixelCopy.SUCCESS) {
                                val pixels = IntArray(25)
                                bitmap.getPixels(pixels, 0, 5, 0, 0, 5, 5)
                                samples += pixels.map { (Color.red(it) + Color.green(it) + Color.blue(it)) / 765f }.average().toFloat()
                            } else failures += result
                            bitmap.recycle()
                            pending = false
                            drawPulse.intValue++
                        }, handler)
                    } else {
                        pending = false
                        drawPulse.intValue++
                    }
                }
            }
            true
        }
        try {
            compose.runOnUiThread {
                onAppDraw = { drawnState = it }
                view.viewTreeObserver.addOnPreDrawListener(listener)
                expected = update(state.value).copy(routes = listOf(selected))
                state.value = expected
                drawPulse.intValue++
            }
            compose.waitUntil(10_000) { samples.size == 8 || failures.isNotEmpty() }
            assertTrue("首页首帧像素复制失败：$failures", failures.isEmpty())
        } finally {
            compose.runOnUiThread {
                onAppDraw = null
                view.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        return samples.toList()
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
                val snapshot = state.value
                CompositionLocalProvider(LocalAppPreferences provides snapshot.preferences) {
                    AppSystemBars()
                    Surface(Modifier.fillMaxSize().drawWithContent {
                        drawPulse.intValue
                        drawContent()
                        onAppDraw?.invoke(snapshot)
                    }) {
                        PathfinApp(viewModel, snapshot, rememberSaveableStateHolder(), posterLoader = images)
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
        compose.onNodeWithTag("login-server").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("login-submit").assertVerificationDisplayed(compose)
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
            home.performVerificationClick(compose)
            compose.runOnIdle { assertEquals("真实触摸必须能进入首页", Route.Home, viewModel.state.value.route) }
        }
    }

    private fun assertContentAboveNavigation(contentTag: String, navigationTag: String, useUnmergedTree: Boolean = false) {
        compose.onNodeWithTag(contentTag, useUnmergedTree).assertVerificationDisplayed(compose)
        assertTrue("列表末项必须完整露出在底栏上方", bounds(contentTag, useUnmergedTree).bottom <= bounds(navigationTag).top + 0.5f)
    }

    private fun bounds(tag: String, useUnmergedTree: Boolean = false): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree).readVerificationNode(compose) { it.boundsInRoot }
}

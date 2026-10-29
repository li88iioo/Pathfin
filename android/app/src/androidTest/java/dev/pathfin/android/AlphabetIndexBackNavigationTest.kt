package dev.pathfin.android

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BrowserScreen
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.AppBackHandler
import dev.pathfin.android.ui.SystemBackGestureProgress
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仅合成 verification 窗口：外部 runner 的真实右边缘输入经过 SystemUI，
 * 以生产系统返回进度证明手势已到达；不启用任何预测性视觉变换，也不直接调用 dispatcher。
 * 需要已解锁、亮屏、手势导航、非零系统动画；不修改安全、导航或超时设置。
 * 仅 pathfin.externalEdgeInput=true 启用；请求与已观察阶段通过 sendStatus 交接，
 * 不建立或销毁 UiAutomation，避免干扰 Compose/Espresso 持有的系统服务。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class AlphabetIndexBackNavigationTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun rightEdgeCancellationAndCompletionNeverQueryTheAlphabetAndReturnExactlyOnce() {
        val fixture = showBrowser()
        val task = compose.runOnIdle { compose.activity.taskId }

        performSystemRightBack("browser-cancel", cancel = true) {
            assertEquals("系统接管后、抬手之前不能提交字母查询", emptyList<LibraryQuery>(), fixture.queries)
            assertEquals(Page.BROWSER, fixture.page.value)
            assertTrue(fixture.backs.isEmpty())
        }
        compose.waitUntil(WAIT_MS) { systemProgress() == 0f }
        assertUnchangedBrowser(fixture, expectedBacks = emptyList())

        // 第二次验证已唤出的浮层；第一次从默认隐藏的边缘区开始。
        revealIndex()
        performSystemRightBack("browser-complete", cancel = false) {
            assertEquals("完成返回前不能因触摸边缘提交字母查询", emptyList<LibraryQuery>(), fixture.queries)
            assertEquals(Page.BROWSER, fixture.page.value)
            assertTrue(fixture.backs.isEmpty())
        }
        compose.waitUntil(WAIT_MS) { fixture.page.value == Page.LANDING }
        compose.onNodeWithTag("alphabet-back-landing").assertIsDisplayed()
        assertQuiet(fixture, expectedPage = Page.LANDING, expectedBacks = listOf(Page.BROWSER))
        compose.runOnIdle {
            assertEquals("完成一次系统手势只能导航一次", listOf(Page.BROWSER), fixture.backs)
            assertEquals(task, compose.activity.taskId)
            assertFalse("返回由生产容器消费，不能退出合成验证 Activity", compose.activity.isFinishing)
        }
    }

    @Test fun returningFromSyntheticDetailLeavesNoPendingAlphabetSelectionOnTheBrowser() {
        val fixture = showBrowser()
        // 打开只含合成文本的详情，不建立 AppViewModel 或读取任何账号存储。
        compose.onNodeWithText(ITEM.name).performClick()
        VerificationDisplay.awaitWindowReady(compose, "alphabet-detail", compose.onNodeWithTag("alphabet-back-detail"))
        compose.runOnIdle { assertEquals(Page.DETAIL, fixture.page.value) }

        // 详情没有侧栏，仍在与库页 M 字符相同的屏幕坐标执行真实系统返回。
        performSystemRightBack("detail-complete", cancel = false, geometry = fixture.edge) {
            assertTrue(fixture.queries.isEmpty())
            assertEquals(Page.DETAIL, fixture.page.value)
            assertTrue(fixture.backs.isEmpty())
        }
        compose.waitUntil(WAIT_MS) { fixture.page.value == Page.BROWSER }
        VerificationDisplay.awaitWindowReady(compose, "alphabet-detail-return", compose.onNodeWithTag("library-index-activation"))
        assertUnchangedBrowser(fixture, expectedBacks = listOf(Page.DETAIL))

        performSystemRightBack("returned-browser-complete", cancel = false) {
            assertEquals("详情返回后不能残留旧手势提交", emptyList<LibraryQuery>(), fixture.queries)
            assertEquals(Page.BROWSER, fixture.page.value)
            assertEquals(listOf(Page.DETAIL), fixture.backs)
        }
        compose.waitUntil(WAIT_MS) { fixture.page.value == Page.LANDING }
        assertQuiet(fixture, Page.LANDING, listOf(Page.DETAIL, Page.BROWSER))
    }

    private enum class Page { BROWSER, DETAIL, LANDING }

    private class Fixture {
        val original = LibraryQuery(itemTypes = setOf("Movie"), sort = LibrarySort.TITLE, descending = false,
            genres = setOf("SyntheticGenre"), tags = setOf("SyntheticTag"), years = setOf(2021))
        val browser = mutableStateOf(BrowserUiState(query = original, content = ContentState(
            items = listOf(ITEM), total = 1, nextIndex = 1)))
        val page = mutableStateOf(Page.BROWSER)
        val queries = mutableListOf<LibraryQuery>()
        val backs = mutableListOf<Page>()
        lateinit var edge: Edge
    }

    private fun showBrowser(): Fixture {
        assumeTrue("需使用外部真实边缘输入 runner，并传入 pathfin.externalEdgeInput=true；普通测试运行不模拟系统返回",
            InstrumentationRegistry.getArguments().getString("pathfin.externalEdgeInput") == "true")
        check(context.packageName == TARGET) { "字母栏系统返回回归只允许 verification 包" }
        checkSystemEnvironment()
        val fixture = Fixture()
        val session = Session(ServerConfig("https://alphabet-system-back.invalid"), "synthetic-edge-token",
            "synthetic-edge-user", "fixture", "synthetic-edge-device")
        val api = JellyfinApi()
        val loader = ImageLoader.Builder(context).diskCache(null).build()
        compose.setContent {
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides loader) {
                    Surface(Modifier.fillMaxSize()) {
                        AppBackHandler(
                            enabled = fixture.page.value != Page.LANDING,
                            contentKey = fixture.page.value,
                            onBack = {
                                val old = fixture.page.value
                                fixture.backs += old
                                fixture.page.value = if (old == Page.DETAIL) Page.BROWSER else Page.LANDING
                            },
                            modifier = Modifier.fillMaxSize(),
                            testTag = MOTION_TAG,
                        ) {
                            when (fixture.page.value) {
                                Page.BROWSER -> BrowserScreen(fixture.browser.value, session, api,
                                    onQueryChange = { query ->
                                        fixture.queries += query
                                        fixture.browser.value = fixture.browser.value.copy(query = query)
                                    },
                                    onStyleChange = {}, onOpen = { fixture.page.value = Page.DETAIL },
                                    onRetry = {}, onMore = {}, libraryType = "movies", showIndex = true)
                                Page.DETAIL -> Box(Modifier.fillMaxSize().testTag("alphabet-back-detail"), contentAlignment = Alignment.Center) {
                                    Text("Synthetic detail")
                                }
                                Page.LANDING -> Box(Modifier.fillMaxSize().testTag("alphabet-back-landing"), contentAlignment = Alignment.Center) {
                                    Text("Synthetic parent")
                                }
                            }
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "alphabet-system-back", compose.onNodeWithTag("library-index-activation"))
        fixture.edge = rightEdgeOverAlphabet()
        assertUnchangedBrowser(fixture, expectedBacks = emptyList())
        return fixture
    }

    private fun checkSystemEnvironment() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) {
            "真实边缘返回需要已解锁设备；锁屏上显示测试窗口不能替代系统手势验证"
        }
        check(context.getSystemService(PowerManager::class.java).isInteractive) { "真实边缘返回需要亮屏设备" }
        val secureMode = Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1)
        val resourceId = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        val resourceMode = if (resourceId == 0) -1 else context.resources.getInteger(resourceId)
        check(secureMode == 2 || secureMode == -1 && resourceMode == 2) {
            "真实右边缘返回需要手势导航；当前 secure=$secureMode resource=$resourceMode，不修改系统设置"
        }
        check(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
            "需要非零系统动画才能观察真实系统返回进度；不修改系统动画设置"
        }
    }

    private data class Edge(val startX: Float, val y: Float, val finishX: Float)

    private fun rightEdgeOverAlphabet(): Edge {
        val rail = compose.onNodeWithTag("library-index-activation").fetchSemanticsNode().boundsInWindow
        return compose.runOnIdle {
            val activity = compose.activity
            val decor = activity.window.decorView
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && decor.hasWindowFocus()) {
                "注入真实系统手势前窗口必须已恢复并持有系统焦点"
            }
            val window = activity.windowManager.currentWindowMetrics.bounds
            val insets = checkNotNull(decor.rootWindowInsets)
            val right = insets.getInsets(WindowInsets.Type.systemGestures()).right
            check(right > 0 && insets.getInsets(WindowInsets.Type.tappableElement()).right == 0) {
                "窗口右侧没有可验证的系统返回手势区，不能用普通应用滑动代替"
            }
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            check(origin[0] + decor.width == window.right) { "非完整右边缘窗口不能验证系统返回冲突" }
            val density = context.resources.displayMetrics.density
            val startX = window.right - minOf(8f * density, right * .5f)
            check(startX > origin[0] + rail.left && startX < origin[0] + rail.right) {
                "系统起点未覆盖字母栏，不能将未命中字母栏的手势计为冲突回归通过"
            }
            Edge(startX, origin[1] + rail.center.y,
                startX - minOf(window.width() * .55f, 300f * density))
        }
    }

    private fun performSystemRightBack(stage: String, cancel: Boolean, geometry: Edge = rightEdgeOverAlphabet(), during: () -> Unit) {
        val request = JSONObject().put("stage", stage).put("startX", geometry.startX)
            .put("y", geometry.y).put("finishX", geometry.finishX).put("cancel", cancel)
        instrumentation.sendStatus(2, Bundle().apply { putString("pathfin.edgeRequest", request.toString()) })
        try {
            // 输入由外部 shell 发出；尚未观察到系统进度前，runner 不得发送 UP。
            compose.waitUntil(EXTERNAL_INPUT_WAIT_MS) { systemProgress() > 0f }
        } catch (failure: Throwable) {
            throw AssertionError("$stage：外部右边缘输入未产生系统返回进度；SystemUI 接管尚未验证", failure)
        }
        // 保持同一次真实按压，检查 DOWN 或系统接管后没有迟到的字母选择。
        SystemClock.sleep(140)
        compose.runOnIdle { during() }
        val heldProgress = systemProgress()
        assertTrue("确认外部抬手前，系统返回必须仍处于进行中，不能已提前取消", heldProgress > 0f)
        Log.i(LOG_TAG, "$stage system-progress=$heldProgress cancelling=$cancel")
        instrumentation.sendStatus(2, Bundle().apply { putString("pathfin.edgeObserved", stage) })
        // runner 此时才正常抬手，或在同一次按压内折返边缘再抬手。
        // 外部命令成功不代替下面的生产状态、导航次数与零查询断言。
        compose.waitUntil(EXTERNAL_INPUT_WAIT_MS) { systemProgress() == 0f }
    }

    private fun systemProgress(): Float = compose.onNodeWithTag(MOTION_TAG).fetchSemanticsNode().config[SystemBackGestureProgress]

    private fun revealIndex() {
        val action = compose.onNodeWithTag("library-index-activation").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "显示字母索引" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.onNodeWithTag("library-index").assertIsDisplayed()
    }

    private fun assertUnchangedBrowser(fixture: Fixture, expectedBacks: List<Page>) {
        assertQuiet(fixture, Page.BROWSER, expectedBacks)
        compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
        compose.onNodeWithTag("library-index").assertDoesNotExist()
        compose.onNodeWithTag("library-index-activation").assertIsDisplayed()
        compose.runOnIdle { assertEquals("边缘返回不能改变原父库查询的类型、排序和筛选", fixture.original, fixture.browser.value.query) }
    }

    private fun assertQuiet(fixture: Fixture, expectedPage: Page, expectedBacks: List<Page>) {
        // 有界静稳观察，检查迟到选择/重复导航；不依赖被移除的动画或防抖。
        SystemClock.sleep(400)
        compose.runOnIdle {
            assertEquals(expectedPage, fixture.page.value)
            assertEquals(expectedBacks, fixture.backs)
            assertEquals("系统返回及取消均不得提交任何字母查询", emptyList<LibraryQuery>(), fixture.queries)
        }
    }

    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val MOTION_TAG = "alphabet-system-back-motion"
        const val LOG_TAG = "PathfinAlphabetSystemBack"
        const val WAIT_MS = 5_000L
        const val EXTERNAL_INPUT_WAIT_MS = 15_000L
        val ITEM = MediaItem("alphabet-back-synthetic-item", "Synthetic edge movie", "Movie", year = 2021)
    }
}

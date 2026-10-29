package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.ui.HomeScreen
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.RefreshablePage
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实触摸与 Activity 生命周期驱动生产容器；没有替代手势的回调调用。 */
@RunWith(AndroidJUnit4::class)
class RefreshGestureTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val preferences = mutableStateOf(AppPreferences())
    private val loading = mutableStateOf(false)
    private val active = mutableStateOf(true)
    private val labels = mutableStateOf((0 until 50).map { "Synthetic row $it" })
    private val requests = AtomicInteger()
    private lateinit var listState: LazyListState

    @Before fun verificationOnly() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
    }

    @Test fun defaultPullModeWaitsForAGestureAndBusyPullCannotOverlap() {
        assertTrue("默认使用下拉模式", AppPreferences().pullToRefreshEnabled)
        show()
        val before = position(0)
        assertEquals("默认进入页面不能额外请求", 0, requests.get())
        pullPageToRefresh(compose, stage = "default-enabled-pull")
        compose.waitUntil(5_000) { requests.get() == 1 }
        assertTrue(loading.value)
        assertPosition(before, position(0), "真实下拉读取期间")
        compose.onNodeWithText("Synthetic row 0").assertIsDisplayed()
        pullPageToRefresh(compose, stage = "busy-pull-no-overlap")
        assertEquals(1, requests.get())
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        assertPosition(before, position(0), "读取完成")
        pullPageToRefresh(compose, stage = "next-independent-pull")
        compose.waitUntil(5_000) { requests.get() == 2 }
    }

    @Test fun disablingPullLoadsOnEntryButRealTouchDoesNotRefresh() {
        preferences.value = AppPreferences(pullToRefreshEnabled = false)
        show()
        compose.waitUntil(5_000) { requests.get() == 1 }
        compose.runOnIdle { loading.value = false }
        val before = position(0)
        pullPageToRefresh(compose, stage = "disabled-real-pull")
        assertEquals("关闭下拉后真实下拉不能请求", 1, requests.get())
        assertFalse(loading.value)
        compose.onAllNodesWithTag("pull-refresh-indicator").assertCountEquals(0)
        assertPosition(before, position(0), "关闭手势")
    }

    @Test fun disabledModeForegroundRefreshKeepsAScrolledAnchorAndNeverOverlapsBusyWork() {
        preferences.value = AppPreferences(pullToRefreshEnabled = false)
        show(refreshOnEntry = false)
        assertEquals("页面自己已首读时不能再重复首读", 0, requests.get())
        compose.onNodeWithTag("refresh-list").performScrollToIndex(12)
        val before = position(12)
        assertEquals(12, before.index)
        resumeActivity()
        compose.waitUntil(5_000) { requests.get() == 1 }
        assertTrue(loading.value)
        assertPosition(before, position(12), "回前台读取期间")
        resumeActivity()
        compose.runOnIdle { assertEquals("未完成请求不能因再次前台而重叠", 1, requests.get()) }
        compose.runOnIdle {
            labels.value = labels.value.map { "$it updated" }
            loading.value = false
        }
        assertPosition(before, position(12), "真实内容更新后")
        compose.onNodeWithText("Synthetic row 12 updated").assertIsDisplayed()
        resumeActivity()
        compose.waitUntil(5_000) { requests.get() == 2 }
        assertPosition(before, position(12), "下次独立前台读取")
    }

    @Test fun aDisabledPageDoesNotRequestUntilItsActualRefreshCapabilityBecomesAvailable() {
        preferences.value = AppPreferences(pullToRefreshEnabled = false)
        active.value = false
        show()
        assertEquals(0, requests.get())
        pullPageToRefresh(compose, stage = "page-read-disabled")
        resumeActivity()
        assertEquals("页面本身无读取能力时不能自动请求", 0, requests.get())
        compose.runOnIdle { active.value = true }
        compose.waitUntil(5_000) { requests.get() == 1 }
        assertTrue(loading.value)
    }

    @Test fun anInitiallyFailedHomeStillAutomaticallyRetriesWhenPullIsDisabled() {
        val session = Session(ServerConfig("https://fixture.invalid"), "synthetic-unused-token", "fixture-user", "fixture", "fixture-device")
        val api = JellyfinApi()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides AppPreferences(pullToRefreshEnabled = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        HomeScreen(HomeState(loaded = false, loading = loading.value, error = "合成首页读取失败"),
                            session, api, {}, retry = { requests.incrementAndGet(); loading.value = true })
                    }
                }
            }
        }
        compose.waitUntil(5_000) { requests.get() == 1 }
        compose.onNodeWithText("合成首页读取失败").assertIsDisplayed()
        assertTrue(loading.value)
        resumeActivity()
        assertEquals("自动重试未完成时不能重复请求", 1, requests.get())
    }

    private fun show(refreshOnEntry: Boolean = true) {
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides preferences.value) {
                    Surface(Modifier.fillMaxSize()) {
                        RefreshablePage(loading.value, {
                            requests.incrementAndGet()
                            loading.value = true
                        }, Modifier.fillMaxSize(), refreshOnEntry = refreshOnEntry, enabled = active.value) {
                            listState = rememberLazyListState()
                            LazyColumn(Modifier.fillMaxSize().testTag("refresh-list"), state = listState,
                                contentPadding = PaddingValues(bottom = 20.dp)) {
                                items(labels.value.indices.toList(), key = { it }) { index ->
                                    Text(labels.value[index], Modifier.fillMaxWidth().height(56.dp)
                                        .testTag("refresh-row/$index").padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "refresh-container-ready", compose.onNodeWithTag("refreshable-page"))
    }

    private fun resumeActivity() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        VerificationDisplay.awaitWindowReady(compose, "refresh-real-foreground", compose.onNodeWithTag("refreshable-page"))
    }

    private data class Position(val index: Int, val offset: Int, val bounds: Rect)
    private fun position(index: Int): Position {
        compose.waitForIdle()
        val node = compose.onNodeWithTag("refresh-row/$index").assertIsDisplayed().fetchSemanticsNode()
        return compose.runOnIdle { Position(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, node.boundsInRoot) }
    }
    private fun assertPosition(expected: Position, actual: Position, phase: String) {
        assertEquals("$phase 首项索引", expected.index, actual.index)
        assertEquals("$phase 首项偏移", expected.offset, actual.offset)
        assertEquals("$phase 内容高度", expected.bounds.height, actual.bounds.height, .5f)
        assertEquals("$phase 内容锚点", expected.bounds.top, actual.bounds.top, .5f)
    }
}

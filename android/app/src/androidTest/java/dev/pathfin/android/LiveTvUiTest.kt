package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.LiveTvGroupSource
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.LiveTvScreen
import dev.pathfin.android.ui.LiveTvState
import dev.pathfin.android.ui.LocalPageActive
import dev.pathfin.android.ui.LocalPageRefreshEnabled
import dev.pathfin.android.ui.LocalNoticeHost
import dev.pathfin.android.ui.PathfinSnackbarHost
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.time.Instant
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 仅隔离验证包；所有服务器响应都是内存合成数据，不读取真实账号或频道。 */
@RunWith(AndroidJUnit4::class)
class LiveTvUiTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test fun searchFindsRealChannelAndGuideOnlyOffersCurrentLiveProgramme() {
        val fixture = Fixture()
        val played = AtomicReference<MediaItem?>()
        show(fixture) { played.set(it) }
        waitFor("live-tv-channel-seagull")
        openSearch()
        compose.onNodeWithTag("live-tv-search").performTextInput("海鸥")
        compose.onNodeWithTag("live-tv-channel-harbour").assertDoesNotExist()
        // 整行点按即播放。
        compose.onNodeWithTag("live-tv-channel-seagull").performVerificationClick(compose)
        compose.runOnIdle { assertEquals("seagull", played.get()?.id); assertEquals("TvChannel", played.get()?.type) }
        openGuide("seagull")
        waitFor("live-tv-program-current")
        compose.onNodeWithTag("live-tv-watch-program-current").assertExists()
        compose.onNodeWithTag("live-tv-program-future").performScrollTo().assertVerificationDisplayed(compose)
        compose.onNodeWithTag("live-tv-watch-program-future").assertDoesNotExist()
        compose.onNodeWithTag("live-tv-program-past").performScrollTo().assertVerificationDisplayed(compose)
        compose.onNodeWithTag("live-tv-watch-program-past").assertDoesNotExist()
        assertTrue(fixture.paths.contains("/proxy/LiveTv/Programs"))
    }

    @Test fun refreshKeepsChannelAndItsPositionWhileResponseWaits() {
        val fixture = Fixture(blockRefresh = true)
        try {
            show(fixture)
            waitFor("live-tv-channel-harbour")
            val channels = compose.onNodeWithTag("live-tv-channel-list")
            channels.performScrollToIndex(0)
            val before = channelBounds()
            // 从频道网格的首列下拉，避免沿用旧单列页面的父容器中心坐标。
            pullPageToRefresh(compose, page = channels, stage = "live-tv-retained-channel-refresh", horizontalFraction = .25f)
            compose.onNodeWithTag("live-tv-refresh").assertDoesNotExist()
            compose.waitUntil(5_000) { fixture.refreshStarted.count == 0L }
            compose.onNodeWithTag("live-tv-channel-harbour").assertVerificationDisplayed(compose)
            val during = channelBounds()
            assertEquals(before.top.value, during.top.value, .5f)
            assertEquals((before.bottom - before.top).value, (during.bottom - during.top).value, .5f)
        } finally { fixture.releaseRefresh.countDown() }
    }

    @Test fun deniedLiveTvAccessDoesNotShowPlaybackOrRequestChannels() {
        val fixture = Fixture(allowed = false)
        show(fixture)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("没有直播电视权限").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("没有直播电视权限").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("live-tv-channel-harbour").assertDoesNotExist()
        assertEquals(listOf("/proxy/Users/Me"), fixture.paths.toList())
    }

    @Test fun freshReentryRetainsChannelsScrollAndSearchWithoutRequestingChannelsAgain() {
        Fixture(paginated = true).use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-channel-0")
            openSearch()
            compose.onNodeWithTag("live-tv-search").performTextInput("合成频道 6")
            compose.onNodeWithTag("live-tv-search").performImeAction()
            val list = compose.onNodeWithTag("live-tv-channel-list")
            list.performScrollToNode(hasTestTag("live-tv-channel-channel-60"))
            VerificationDisplay.awaitWindowReady(compose, "live-tv-retained-position", list)
            val before = channelBounds("live-tv-channel-channel-60")
            val requests = fixture.paths.size

            reenter(host)
            VerificationDisplay.awaitWindowReady(compose, "live-tv-reentered-position", list)
            compose.onNodeWithTag("live-tv-search").assertTextContains("合成频道 6")
            compose.onNodeWithTag("live-tv-channel-channel-60").assertVerificationDisplayed(compose)
            assertSameBounds(before, channelBounds("live-tv-channel-channel-60"))
            assertNoNewRequests(fixture.channelPages, 1)
            assertEquals(requests, fixture.paths.size)
            assertEquals("整份频道一次读完", listOf(0), fixture.channelPages.requests.map { it.startIndex })
        }
    }

    @Test fun channelsSpanningSeveralPagesArePublishedOnlyOnceAllPagesArrive() {
        Fixture(paginated = true, channelTotal = 150).use { fixture ->
            fixture.channelPages.holdAt.set(100)
            show(fixture)
            compose.waitUntil(WAIT_MS) { fixture.channelPages.heldStarted.count == 0L }
            // 第二页未到时不发布第一页，列表只显示占位行。
            compose.onNodeWithTag("live-tv-channel-channel-0").assertDoesNotExist()
            fixture.channelPages.releaseHeld.countDown()
            waitFor("live-tv-channel-channel-0")
            compose.onNodeWithTag("live-tv-channel-list").performScrollToNode(hasTestTag("live-tv-channel-channel-149"))
            compose.onNodeWithTag("live-tv-channel-channel-149").assertVerificationDisplayed(compose)
            assertNoNewRequests(fixture.channelPages, 2)
            assertEquals(listOf(0, 100), fixture.channelPages.requests.map { it.startIndex })
        }
    }

    @Test fun searchingFromDeepInTheGridShowsResultsFromTheTopAndReentryKeepsTheResultPosition() {
        Fixture(paginated = true, channelTotal = 150).use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-channel-0")
            val grid = compose.onNodeWithTag("live-tv-channel-list")
            grid.performScrollToNode(hasTestTag("live-tv-channel-channel-120"))
            compose.runOnIdle { assertTrue("先停在网格中部", host.state.list.firstVisibleItemIndex > 0) }
            fun assertAtTop(stage: String) {
                compose.onNodeWithTag("live-tv-channel-channel-1").assertVerificationDisplayed(compose)
                compose.runOnIdle {
                    assertEquals("$stage 结果应从顶部开始", 0 to 0,
                        host.state.list.firstVisibleItemIndex to host.state.list.firstVisibleItemScrollOffset)
                }
            }
            openSearch()
            compose.onNodeWithTag("live-tv-search").performTextInput("合成频道 1")
            assertAtTop("输入搜索词")
            grid.performScrollToNode(hasTestTag("live-tv-channel-channel-140"))
            compose.onNodeWithTag("live-tv-search").performTextReplacement("合成频道 1")
            compose.runOnIdle { assertTrue("同一搜索词不重置位置", host.state.list.firstVisibleItemIndex > 0) }
            compose.onNodeWithTag("live-tv-search").performTextInput("4")
            compose.onNodeWithTag("live-tv-channel-channel-14").assertVerificationDisplayed(compose)
            compose.runOnIdle { assertEquals(0 to 0, host.state.list.firstVisibleItemIndex to host.state.list.firstVisibleItemScrollOffset) }
            compose.onNodeWithTag("live-tv-search").performTextReplacement("合成频道 1")
            assertAtTop("修改搜索词")
            grid.performScrollToNode(hasTestTag("live-tv-channel-channel-140"))
            lateinit var position: Pair<Int, Int>
            compose.runOnIdle { position = host.state.list.firstVisibleItemIndex to host.state.list.firstVisibleItemScrollOffset }
            reenter(host)
            compose.onNodeWithTag("live-tv-channel-channel-140").assertVerificationDisplayed(compose)
            compose.runOnIdle {
                assertEquals("重新进入保留搜索结果的位置", position,
                    host.state.list.firstVisibleItemIndex to host.state.list.firstVisibleItemScrollOffset)
            }
        }
    }

    @Test fun cancellingSearchRestoresTheOverviewPositionOnTheFirstFrameWithoutMovingCards() {
        Fixture(paginated = true, channelTotal = 150).use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-channel-0")
            val grid = compose.onNodeWithTag("live-tv-channel-list")
            grid.performScrollToNode(hasTestTag("live-tv-channel-channel-120"))
            val anchor = "live-tv-channel-channel-120"
            VerificationDisplay.awaitWindowReady(compose, "live-tv-overview-before-search", compose.onNodeWithTag(anchor))
            fun position() = compose.runOnIdle { host.state.list.firstVisibleItemIndex to host.state.list.firstVisibleItemScrollOffset }
            val overview = position()
            val before = channelBounds(anchor)
            fun cancelAndAssertFirstFrame(stage: String) {
                compose.onNodeWithTag("live-tv-search").performTextInput("合成频道 1")
                compose.onNodeWithTag("live-tv-channel-channel-1").assertVerificationDisplayed(compose)
                grid.performScrollToNode(hasTestTag("live-tv-channel-channel-140"))
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("live-tv-search-close").performVerificationClick(compose)
                    compose.mainClock.advanceTimeByFrame()
                    compose.mainClock.advanceTimeByFrame()
                    assertEquals("$stage 取消后的首帧就回到搜索前位置", overview, position())
                    assertSameBounds(before, channelBounds(anchor))
                    compose.mainClock.advanceTimeBy(600)
                    assertSameBounds(before, channelBounds(anchor))
                } finally { compose.mainClock.autoAdvance = true }
                compose.onNodeWithTag("live-tv-search").assertDoesNotExist()
                assertSameBounds(before, channelBounds(anchor))
                assertEquals(overview, position())
            }
            openSearch()
            cancelAndAssertFirstFrame("搜索中")
            // 搜索中离开再回来，仍能回到进入搜索前的总览位置。
            openSearch()
            compose.onNodeWithTag("live-tv-search").performTextInput("合成频道 1")
            reenter(host)
            compose.onNodeWithTag("live-tv-search").performTextClearance()
            compose.onNodeWithTag(anchor).assertVerificationDisplayed(compose)
            assertSameBounds(before, channelBounds(anchor))
            assertEquals("重新进入后清空搜索也回到原位", overview, position())
        }
    }

    @Test fun withoutLastWatchedTheSearchSitsInTheTabRowAndOpeningItDoesNotMoveTheTabs() {
        Fixture(rich = true, watched = false, session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User",
            "device", serverId = "server", isAdministrator = true)).use { fixture ->
            show(fixture, groups = true)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("live-tv-tab-夜航").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("live-tv-resume").assertDoesNotExist()
            // 没有封面时不留只有放大镜的空页头，放大镜在分组标签行里，标签紧贴状态栏下方。
            compose.onNodeWithTag("live-tv-header-row").assertDoesNotExist()
            compose.onNodeWithTag("live-tv-search-open").assert(hasAnyAncestor(hasTestTag("live-tv-channel-list")))
            val tabs = channelBounds("live-tv-tabs")
            assertTrue("标签行应在顶部，而不是空页头下方：$tabs", tabs.top.value < 24f)
            openSearch()
            compose.onNodeWithTag("live-tv-header-row").assertExists()
            compose.onAllNodesWithTag("live-tv-search-open").assertCountEquals(0)
            assertSameBounds(tabs, channelBounds("live-tv-tabs"))
            compose.onNodeWithTag("live-tv-search-close").performVerificationClick(compose)
            compose.waitForIdle()
            compose.onNodeWithTag("live-tv-header-row").assertDoesNotExist()
            compose.onAllNodesWithTag("live-tv-search-open").assertCountEquals(1)
            assertSameBounds(tabs, channelBounds("live-tv-tabs"))
        }
    }

    @Test fun withoutLastWatchedOrGroupsTheSearchMovesToTheHeaderOnlyAfterTheFirstRowScrollsAway() {
        Fixture(paginated = true, channelTotal = 150).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            compose.onNodeWithText("全部频道").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("live-tv-header-row").assertDoesNotExist()
            compose.onNodeWithTag("live-tv-search-open").assert(hasAnyAncestor(hasTestTag("live-tv-channel-list")))
            val grid = compose.onNodeWithTag("live-tv-channel-list")
            grid.performScrollToNode(hasTestTag("live-tv-channel-channel-120"))
            compose.waitForIdle()
            // 深处仍能一键搜索：页头带着放大镜出现，且只有这一个放大镜。
            compose.onAllNodesWithTag("live-tv-search-open").assertCountEquals(1)
            compose.onNodeWithTag("live-tv-search-open").assert(hasAnyAncestor(hasTestTag("live-tv-header-row")))
            openSearch()
            compose.onNodeWithTag("live-tv-search").performTextInput("远方")
            compose.onNodeWithTag("live-tv-channel-channel-80").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("live-tv-search-close").performVerificationClick(compose)
            compose.onNodeWithTag("live-tv-channel-channel-120").assertVerificationDisplayed(compose)
            grid.performScrollToIndex(0)
            compose.waitForIdle()
            compose.onNodeWithTag("live-tv-header-row").assertDoesNotExist()
            compose.onNodeWithTag("live-tv-search-open").assert(hasAnyAncestor(hasTestTag("live-tv-channel-list")))
        }
    }

    @Test fun successfulEmptyChannelsRemainLoadedAcrossReentry() {
        Fixture(empty = true).use { fixture ->
            val host = show(fixture)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("暂无直播频道").fetchSemanticsNodes().isNotEmpty() }
            val requests = fixture.paths.toList()
            repeat(2) {
                reenter(host)
                compose.onNodeWithText("暂无直播频道").assertVerificationDisplayed(compose)
                compose.onNodeWithText("正在读取直播频道").assertDoesNotExist()
            }
            assertNoNewRequests(fixture.channelPages, 1)
            assertEquals(requests, fixture.paths.toList())
        }
    }

    @Test fun staleReentryRefreshesInBackgroundAndFailedRefreshKeepsTheLastGoodChannels() {
        Fixture(blockRefresh = true).use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-harbour")
            VerificationDisplay.awaitWindowReady(compose, "live-tv-before-stale-reentry", compose.onNodeWithTag("live-tv-channel-list"))
            val before = channelBounds()
            try {
                host.clock.addAndGet(60_001)
                reenter(host)
                compose.waitUntil(WAIT_MS) { fixture.refreshStarted.count == 0L }
                compose.onNodeWithText("港湾电视").assertVerificationDisplayed(compose)
                compose.onNodeWithText("正在读取直播频道").assertDoesNotExist()
                assertSameBounds(before, channelBounds())
            } finally { fixture.releaseRefresh.countDown() }
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("刷新 · 港湾电视").fetchSemanticsNodes().isNotEmpty() }

            fixture.channelStatus.set(503)
            host.clock.addAndGet(60_001)
            reenter(host)
            compose.waitUntil(WAIT_MS) { fixture.channelPages.requests.size == 3 }
            assertNoNewRequests(fixture.channelPages, 3)
            compose.onNodeWithText("刷新 · 港湾电视").assertVerificationDisplayed(compose)
            compose.onNodeWithText("正在读取直播频道").assertDoesNotExist()
            assertEquals(listOf(0, 0, 0), fixture.channelPages.requests.map { it.startIndex })
            waitFor("app-notice")
            assertSameBounds(before, channelBounds())
            fixture.channelStatus.set(200)
            compose.onNodeWithText("重试").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.channelPages.requests.size == 4 && !host.state.refreshing && host.state.error == null }
            assertNoNewRequests(fixture.channelPages, 4)
            assertSameBounds(before, channelBounds())
        }
    }

    @Test fun retainedHiddenLivePageDoesNotShowATardyRefreshFailure() {
        Fixture(blockRefresh = true).use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-harbour")
            compose.runOnIdle { host.state.load() }
            compose.waitUntil(WAIT_MS) { fixture.refreshStarted.count == 0L }
            compose.runOnIdle { host.active = false; fixture.channelStatus.set(503) }
            fixture.releaseRefresh.countDown()
            compose.waitUntil(WAIT_MS) { !host.state.refreshing && host.state.error != null }
            val error = compose.runOnIdle { requireNotNull(host.state.error) }
            compose.onNodeWithText(error).assertDoesNotExist()
            compose.onNodeWithTag("app-notice").assertDoesNotExist()
            compose.runOnIdle { assertNull("失活页不能把迟到错误排进全局通知", host.notice.currentSnackbarData) }
            compose.onNodeWithTag("live-tv-channel-harbour").assertVerificationDisplayed(compose)
        }
    }

    @Test fun retainedHiddenLivePageClosesItsGuideDoesNotHandleBackOrRefreshAndReentryHonorsTtl() {
        Fixture().use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-seagull")
            openGuide("seagull")
            waitFor("live-tv-program-current")
            compose.runOnIdle { host.active = false; host.clock.addAndGet(60_001) }
            compose.onNodeWithTag("live-tv-program-current").assertDoesNotExist()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle {
                assertEquals("隐藏直播页不能吃掉当前页的返回键", 1, host.backgroundBacks.get())
                assertTrue(host.visible)
            }
            assertNoNewRequests(fixture.channelPages, 1)
            compose.runOnIdle { host.active = true }
            compose.waitUntil(WAIT_MS) { fixture.channelPages.requests.size == 2 }
            compose.onNodeWithTag("live-tv-program-current").assertDoesNotExist()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("live-tv-screen").assertDoesNotExist()
            assertEquals("回到直播后返回键重新生效", 1, host.backgroundBacks.get())
        }
    }

    @Test fun retainedLiveSearchReleasesFocusWhenHiddenAndKeepsItsQueryOnReturn() {
        Fixture().use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-seagull")
            openSearch()
            compose.onNodeWithTag("live-tv-search").performTextInput("海鸥")
            compose.runOnIdle { host.active = false }
            compose.onAllNodes(isFocused()).assertCountEquals(0)
            compose.runOnIdle { host.active = true }
            compose.onNodeWithTag("live-tv-search").assertTextContains("海鸥")
            compose.onAllNodes(isFocused()).assertCountEquals(0)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("live-tv-search").assertDoesNotExist()
            compose.onNodeWithTag("live-tv-screen").assertExists()
        }
    }

    @Test fun staleAccessDenialClearsChannelsAndExpiredAuthenticationIsReportedOnce() {
        Fixture().use { denied ->
            val host = show(denied)
            waitFor("live-tv-channel-harbour")
            denied.channelStatus.set(403)
            host.clock.addAndGet(60_001)
            reenter(host)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("live-tv-channel-harbour").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("live-tv-channel-harbour").assertDoesNotExist()
            assertNoNewRequests(denied.channelPages, 2)
            assertEquals(0, host.authExpired.get())

            Fixture(session = denied.session.copy(accessToken = "replacement-token"), channelPrefix = "fresh-").use { expired ->
                compose.runOnIdle { host.fixture = expired }
                waitFor("live-tv-channel-fresh-harbour")
                expired.channelStatus.set(401)
                host.clock.addAndGet(60_001)
                reenter(host)
                compose.waitUntil(WAIT_MS) { host.authExpired.get() == 1 }
                assertNoNewRequests(expired.channelPages, 2)
                assertEquals(1, host.authExpired.get())
                assertEquals(2, denied.channelPages.requests.size)
            }
        }
    }

    @Test fun aDelayedOldSessionResponseCannotReplaceTheNewServersChannels() {
        Fixture(channelPrefix = "old-").use { previous ->
            previous.channelPages.holdAt.set(0)
            val host = show(previous)
            compose.waitUntil(WAIT_MS) { previous.channelPages.heldStarted.count == 0L }
            val replacement = previous.session.copy(server = ServerConfig("https://replacement.invalid/proxy"),
                accessToken = "replacement-token", userId = "replacement-user")
            Fixture(session = replacement, channelPrefix = "new-").use { current ->
                compose.runOnIdle { host.fixture = current }
                waitFor("live-tv-channel-new-harbour")
                previous.channelPages.releaseHeld.countDown()
                compose.waitUntil(WAIT_MS) { previous.channelPages.heldReturned.count == 0L }
                assertNoNewRequests(current.channelPages, 1)
                compose.onNodeWithTag("live-tv-channel-new-harbour").assertVerificationDisplayed(compose)
                compose.onNodeWithTag("live-tv-channel-old-harbour").assertDoesNotExist()
                assertEquals(0, host.authExpired.get())
            }
        }
    }

    @Test fun guideAppendsOnScrollAndRefreshKeepsTheReadRangeAfterAnAppendFailure() = exerciseGuidePagination()

    @Test fun overviewResumesLastWatchedAndSearchHidesTheCover() {
        val fixture = Fixture(rich = true)
        val played = AtomicReference<MediaItem?>()
        show(fixture) { played.set(it) }
        waitFor("live-tv-resume")
        compose.onNodeWithText("上次观看").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("live-tv-resume-play").performVerificationClick(compose)
        compose.runOnIdle { assertEquals("harbour", played.get()?.id) }
        // 直播页不单列收藏；没有直播源分组时也没有分组标签。
        compose.onAllNodesWithText("收藏").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithTag("live-tv-tabs").assertDoesNotExist()
        compose.onNodeWithText("全部频道").assertExists()
        openSearch()
        compose.onNodeWithTag("live-tv-search").performTextInput("海鸥")
        waitFor("live-tv-channel-seagull")
        compose.onNodeWithTag("live-tv-resume").assertDoesNotExist()
        compose.onNodeWithTag("live-tv-channel-harbour").assertDoesNotExist()
    }

    @Test fun groupTabsFilterTheListInPlaceAndAllIsTheDefault() {
        val fixture = Fixture(rich = true, session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User",
            "device", serverId = "server", isAdministrator = true))
        show(fixture, groups = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("live-tv-tab-夜航").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("live-tv-tab-全部").assertIsSelected()
        listOf("harbour", "seagull", "lighthouse").forEach { compose.onNodeWithTag("live-tv-channel-$it").assertExists() }
        val requests = fixture.channelPages.requests.size
        compose.onNodeWithTag("live-tv-tab-夜航").performVerificationClick(compose)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("live-tv-channel-seagull").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("live-tv-channel-lighthouse").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("live-tv-channel-harbour").assertDoesNotExist()
        // 选组时不显示分组标题，标签本身就是标题。
        compose.onNodeWithText("沿海").assertExists()
        compose.onAllNodesWithText("夜航").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithTag("live-tv-tab-全部").performVerificationClick(compose)
        waitFor("live-tv-channel-seagull")
        compose.onNodeWithTag("live-tv-channel-harbour").assertExists()
        // 在频道行滑动，不从整页中心误滑到可横向滚动的标签条。
        val coastal = compose.onNodeWithTag("live-tv-channel-seagull")
        coastal.performScrollTo().performVerificationTouchInput(compose) { swipeLeft() }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("live-tv-channel-lighthouse").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("live-tv-tab-沿海").assertIsSelected()
        coastal.performScrollTo().performVerificationTouchInput(compose) { swipeRight() }
        waitFor("live-tv-channel-lighthouse")
        compose.onNodeWithTag("live-tv-tab-全部").assertIsSelected()
        coastal.performScrollTo().performVerificationTouchInput(compose) { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("live-tv-tab-全部").assertIsSelected()
        // 点击第二个标签后也能右滑返回，不能沿用创建手势时的选中索引。
        compose.onNodeWithTag("live-tv-tab-沿海").performVerificationClick(compose)
        compose.onNodeWithTag("live-tv-tab-沿海").assertIsSelected()
        coastal.performScrollTo().performVerificationTouchInput(compose) { swipeRight() }
        waitFor("live-tv-channel-lighthouse")
        compose.onNodeWithTag("live-tv-tab-全部").assertIsSelected()
        // 末尾左滑不循环；随后连续右滑，应逐组回到全部。
        compose.onNodeWithTag("live-tv-tab-夜航").performVerificationClick(compose)
        val last = compose.onNodeWithTag("live-tv-channel-lighthouse")
        last.performScrollTo().performVerificationTouchInput(compose) { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("live-tv-tab-夜航").assertIsSelected()
        last.performVerificationTouchInput(compose) { swipeRight() }
        waitFor("live-tv-channel-seagull")
        compose.onNodeWithTag("live-tv-tab-沿海").assertIsSelected()
        compose.onNodeWithTag("live-tv-channel-lighthouse").assertDoesNotExist()
        coastal.performScrollTo().performVerificationTouchInput(compose) { swipeRight() }
        waitFor("live-tv-channel-lighthouse")
        compose.onNodeWithTag("live-tv-tab-全部").assertIsSelected()
        assertNoNewRequests(fixture.channelPages, requests)
    }

    @Test fun playbackReturnRetainsTheSelectedTabOffsetAndChannelPosition() {
        val labels = listOf("沿海城市电视与公共文化综合频道", "夜航资讯电影与纪录片综合频道")
        Fixture(rich = true, groupNames = labels,
            session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User",
                "device", serverId = "server", isAdministrator = true)).use { fixture ->
            val played = AtomicReference<MediaItem?>()
            val host = show(fixture, groups = true) { played.set(it) }
            val tabTag = "live-tv-tab-${labels.last()}"
            waitFor(tabTag)
            val tab = compose.onNodeWithTag(tabTag)
            tab.performScrollTo().performVerificationClick(compose)
            tab.assertIsSelected()
            val channel = compose.onNodeWithTag("live-tv-channel-lighthouse")
            channel.performScrollTo().performVerificationClick(compose)
            compose.runOnIdle { assertEquals("lighthouse", played.get()?.id) }
            VerificationDisplay.awaitWindowReady(compose, "live-tv-before-playback-return", tab, channel)
            assertTrue("标签条必须已横向滚动", channelBounds("live-tv-tab-全部").left.value < 0f)
            val tabBefore = channelBounds(tabTag)
            val channelBefore = channelBounds("live-tv-channel-lighthouse")
            val requests = fixture.paths.toList()

            repeat(2) {
                reenter(host)
                VerificationDisplay.awaitWindowReady(compose, "live-tv-playback-return", tab, channel)
                tab.assertIsSelected()
                tab.assertVerificationDisplayed(compose)
                assertSameBounds(tabBefore, channelBounds(tabTag))
                assertSameBounds(channelBefore, channelBounds("live-tv-channel-lighthouse"))
                compose.onNodeWithTag("live-tv-channel-seagull").assertDoesNotExist()
                assertEquals(requests, fixture.paths.toList())
            }
        }
    }

    @Test fun removingAllPlaylistGroupsClearsTheSelectedGroupAfterRefresh() {
        Fixture(rich = true, session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User",
            "device", serverId = "server", isAdministrator = true)).use { fixture ->
            show(fixture, groups = true)
            waitFor("live-tv-tab-夜航")
            compose.onNodeWithTag("live-tv-tab-夜航").performVerificationClick(compose)
            compose.onNodeWithTag("live-tv-channel-lighthouse").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("live-tv-channel-seagull").assertDoesNotExist()

            fixture.playlistGroupsEnabled.set(false)
            val grid = compose.onNodeWithTag("live-tv-channel-list")
            grid.performScrollToIndex(0)
            pullPageToRefresh(compose, page = grid, stage = "live-tv-remove-playlist-groups", horizontalFraction = .25f)
            compose.waitUntil(WAIT_MS) {
                fixture.playlistRequests.get() == 2 && compose.onAllNodesWithTag("live-tv-tabs").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("live-tv-tab-夜航").assertDoesNotExist()
            compose.onNodeWithText("全部频道").assertExists()
            listOf("harbour", "seagull", "lighthouse").forEach { id ->
                grid.performScrollToNode(hasTestTag("live-tv-channel-$id"))
                compose.onNodeWithTag("live-tv-channel-$id").assertVerificationDisplayed(compose)
            }
        }
    }

    @Test fun cancellingLiveTvStateCancelsPendingGroupNetworkAuthorizationBeforeDownloading() {
        Fixture(rich = true, session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User",
            "device", serverId = "server", isAdministrator = true)).use { fixture ->
            val started = AtomicInteger()
            val cancelled = AtomicInteger()
            fixture.ensureGroupNetworkAccess = {
                started.incrementAndGet()
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
            val host = show(fixture, groups = true)
            waitFor("live-tv-channel-seagull")
            compose.waitUntil(WAIT_MS) { started.get() == 1 }
            compose.runOnIdle { host.state.cancel() }
            compose.waitUntil(WAIT_MS) { cancelled.get() == 1 }
            assertEquals("取消授权等待后不能再下载播放列表", 0, fixture.playlistRequests.get())
            compose.onNodeWithTag("live-tv-channel-seagull").assertVerificationDisplayed(compose)
        }
    }

    @Test fun longPressOpensTheGuideWithoutPlaying() {
        val played = AtomicReference<MediaItem?>()
        show(Fixture()) { played.set(it) }
        waitFor("live-tv-channel-seagull")
        compose.onNodeWithTag("live-tv-channel-seagull").performVerificationTouchInput(compose) { longClick() }
        waitFor("live-tv-program-current")
        compose.runOnIdle { assertEquals(null, played.get()) }
    }

    @Test fun rowsWithoutPlaybackRightsOpenTheGuideInsteadOfPlaying() {
        val played = AtomicReference<MediaItem?>()
        show(Fixture(playable = false)) { played.set(it) }
        waitFor("live-tv-channel-seagull")
        compose.onNodeWithTag("live-tv-channel-seagull").assertTextContains("未获播放权限")
        compose.onNodeWithTag("live-tv-channel-seagull").performVerificationClick(compose)
        waitFor("live-tv-program-current")
        compose.onNodeWithTag("live-tv-watch-program-current").assertExists()
        compose.runOnIdle { assertEquals(null, played.get()) }
    }

    @Test fun channelsKeepTheRawCursorWhenAdjacentPagesRepeatAnItem() = exerciseRawCursor(programs = false)

    @Test fun guideKeepsTheRawCursorWhenAdjacentPagesRepeatAnItem() = exerciseRawCursor(programs = true)

    @Test fun channelRefreshDoesNotPublishItsFirstPageWhenTheSecondPageFails() = exerciseFailedRangeRefresh(programs = false)

    @Test fun guideRefreshDoesNotPublishItsFirstPageWhenTheSecondPageFails() = exerciseFailedRangeRefresh(programs = true)

    @Test fun channelRefreshKeepsItsSnapshotWhenTotalsChangeBetweenFragments() = exerciseFailedRangeRefresh(programs = false, changingTotal = true)

    @Test fun guideRefreshKeepsItsSnapshotWhenTotalsChangeBetweenFragments() = exerciseFailedRangeRefresh(programs = true, changingTotal = true)

    private fun exerciseRawCursor(programs: Boolean) {
        Fixture(paginated = true, channelTotal = 250).use { fixture ->
            val probe = if (programs) fixture.programPages else fixture.channelPages
            probe.repeatBoundary.set(true)
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            if (!programs) {
                // 频道整份读取：游标按服务端原始条数推进，重复项去重后不再多读。
                assertNoNewRequests(probe, 3)
                assertEquals("去重后的行数不能作为后续分页游标", listOf(0, 100, 200), probe.requests.map { it.startIndex })
                return
            }
            openGuide("channel-0")
            waitFor("live-tv-program-program-0")
            scrollEnd("live-tv-program-list", "live-tv-program-program-79")
            scrollEnd("live-tv-program-list", "live-tv-program-program-159")
            scrollEnd("live-tv-program-list", "live-tv-program-program-160")
            assertEquals("去重后的行数不能作为后续分页游标", listOf(0, 80, 160), probe.requests.map { it.startIndex })
            assertNoNewRequests(probe, 3)
        }
    }

    private fun exerciseFailedRangeRefresh(programs: Boolean, changingTotal: Boolean = false) {
        if (!programs) return exerciseFailedChannelRefresh(changingTotal)
        Fixture(paginated = true).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            openGuide("channel-0")
            waitFor("live-tv-program-program-0")
            val probe = fixture.programPages
            val size = 80
            val listTag = "live-tv-program-list"
            val prefix = "live-tv-program-program-"
            probe.failAt.set(size * 2)
            scrollEnd(listTag, "$prefix${size - 1}")
            scrollEnd(listTag, "$prefix${size * 2 - 1}")
            compose.waitUntil(WAIT_MS) { probe.requests.size == 3 }
            assertNoNewRequests(probe, 3)

            probe.failAt.set(-1)
            if (changingTotal) probe.refreshedRemainderTotal.set(size * 2 + 2) else probe.failRefreshAt.set(size)
            val list = compose.onNodeWithTag(listTag)
            list.performScrollToIndex(0)
            pullPageToRefresh(compose, page = list, stage = "live-tv-failed-range-$programs")
            compose.waitUntil(WAIT_MS) { probe.requests.size == 5 }
            list.performScrollToIndex(0)
            compose.onNodeWithTag("live-tv-program-error").assertVerificationDisplayed(compose)
            list.performScrollToNode(hasTestTag("$prefix${size * 2 - 1}"))
            compose.onNodeWithTag("$prefix${size * 2 - 1}").assertVerificationDisplayed(compose)
            list.performScrollToNode(hasTestTag("${prefix}0"))
            compose.onNodeWithText(fixture.originalName(programs, 0)).assertVerificationDisplayed(compose)
            compose.onAllNodesWithText(fixture.refreshedName(programs, 0)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            repeat(2) { list.performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertNoNewRequests(probe, 5)
            assertEquals(listOf(0, size, size * 2, 0, size), probe.requests.map { it.startIndex })
        }
    }

    /** 频道刷新整份成功才替换：第二页失败或总数中途变化时保留原列表。 */
    private fun exerciseFailedChannelRefresh(changingTotal: Boolean) {
        Fixture(paginated = true, channelTotal = 150).use { fixture ->
            val host = show(fixture)
            waitFor("live-tv-channel-channel-0")
            val probe = fixture.channelPages
            if (changingTotal) probe.refreshedRemainderTotal.set(152) else probe.failRefreshAt.set(100)
            val list = compose.onNodeWithTag("live-tv-channel-list")
            list.performScrollToIndex(0)
            pullPageToRefresh(compose, page = list, stage = "live-tv-failed-channel-range")
            compose.waitUntil(WAIT_MS) { probe.requests.size == 4 && host.state.error != null && !host.state.refreshing }
            compose.onNodeWithText(fixture.originalName(false, 0)).assertVerificationDisplayed(compose)
            compose.onAllNodesWithText(fixture.refreshedName(false, 0)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            assertNoNewRequests(probe, 4)
            assertEquals(listOf(0, 100, 0, 100), probe.requests.map { it.startIndex })
        }
    }

    private fun exerciseGuidePagination() {
        Fixture(paginated = true).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            val guide = compose.onNodeWithTag("live-tv-channel-channel-0")
            VerificationDisplay.awaitWindowReady(compose, "live-tv-open-guide", guide)
            openGuide("channel-0")
            waitFor("live-tv-program-program-0")
            val probe = fixture.programPages
            val pageSize = 80
            val listTag = "live-tv-program-list"
            val prefix = "live-tv-program-program-"
            val lastRead = pageSize * 2 - 1
            probe.failAt.set(pageSize * 2)
            scrollEnd(listTag, "$prefix${pageSize - 1}")
            compose.waitUntil(WAIT_MS) { probe.requests.any { it.startIndex == pageSize } }
            scrollEnd(listTag, "$prefix$lastRead")
            compose.waitUntil(WAIT_MS) { probe.requests.any { it.startIndex == pageSize * 2 } }
            compose.onNodeWithTag("$prefix$lastRead").assertVerificationDisplayed(compose)
            repeat(3) { compose.onNodeWithTag(listTag).performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertNoNewRequests(probe, 3)
            assertEquals(listOf(0, pageSize, pageSize * 2), probe.requests.map { it.startIndex })

            probe.failAt.set(-1)
            probe.holdRefresh.set(true)
            probe.holdRemainder.set(true)
            val list = compose.onNodeWithTag(listTag)
            list.performScrollToIndex(0)
            pullPageToRefresh(compose, page = list, stage = "live-tv-$listTag-range-recovery")
            compose.waitUntil(WAIT_MS) { probe.refreshStarted.count == 0L }
            list.performScrollToNode(hasTestTag("$prefix$lastRead"))
            compose.onNodeWithTag("$prefix$lastRead").assertVerificationDisplayed(compose)
            probe.releaseRefresh.countDown()
            // 默认页长重读时会经过第二页；若合并为较大页长，则可直接完整发布。
            compose.waitUntil(WAIT_MS) { probe.remainderStarted.count == 0L ||
                compose.onAllNodesWithText(fixture.refreshedName(true, lastRead)).fetchSemanticsNodes().isNotEmpty() }
            if (probe.remainderStarted.count == 0L) {
                list.performScrollToNode(hasTestTag("$prefix$lastRead"))
                compose.onNodeWithTag("$prefix$lastRead").assertVerificationDisplayed(compose)
            }
            probe.releaseRemainder.countDown()
            compose.waitUntil(WAIT_MS) { runCatching {
                list.performScrollToNode(hasTestTag("$prefix$lastRead"))
                compose.onNodeWithText(fixture.refreshedName(true, lastRead)).assertVerificationDisplayed(compose)
                true
            }.getOrDefault(false) }
            scrollEnd(listTag, "$prefix$lastRead")
            compose.waitUntil(WAIT_MS) { runCatching {
                list.performScrollToNode(hasTestTag("$prefix${pageSize * 2}")); true
            }.getOrDefault(false) }
            compose.onNodeWithTag("$prefix${pageSize * 2}").assertVerificationDisplayed(compose)
            val terminalRequests = probe.requests.size
            repeat(3) { list.performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertNoNewRequests(probe, terminalRequests)
            compose.onNodeWithTag("live-tv-program-more").assertDoesNotExist()
        }
    }

    private fun scrollEnd(listTag: String, lastTag: String) {
        val list = compose.onNodeWithTag(listTag)
        compose.waitUntil(WAIT_MS) { runCatching {
            list.performScrollToNode(hasTestTag(lastTag))
            compose.onNodeWithTag(lastTag).assertVerificationDisplayed(compose)
            true
        }.getOrDefault(false) }
        VerificationDisplay.awaitWindowReady(compose, "live-tv-scroll-$lastTag", list)
        list.performVerificationTouchInput(compose) { swipeUp() }
        compose.waitForIdle()
    }

    private fun assertNoNewRequests(probe: PageProbe, expected: Int) {
        val until = SystemClock.elapsedRealtime() + 750
        while (SystemClock.elapsedRealtime() < until) {
            compose.waitForIdle()
            assertEquals("停留/重复滚动不能重试失败页或越过总数：${probe.requests}", expected, probe.requests.size)
            SystemClock.sleep(25)
        }
    }

    private fun channelBounds(tag: String = "live-tv-channel-harbour") = compose.onNodeWithTag(tag).readVerificationNode(compose) { node ->
        if (!node.layoutInfo.isPlaced) DpRect(Dp.Unspecified, Dp.Unspecified, Dp.Unspecified, Dp.Unspecified)
        else with(node.layoutInfo.density) {
            val position = node.positionInRoot
            DpRect(position.x.toDp(), position.y.toDp(), (position.x + node.size.width).toDp(), (position.y + node.size.height).toDp())
        }
    }

    private fun assertSameBounds(before: DpRect, after: DpRect) {
        assertEquals(before.left.value, after.left.value, .5f)
        assertEquals(before.top.value, after.top.value, .5f)
        assertEquals(before.right.value, after.right.value, .5f)
        assertEquals(before.bottom.value, after.bottom.value, .5f)
    }

    /** 宫格里没有节目单按钮，长按频道打开。 */
    private fun openGuide(channelId: String) =
        compose.onNodeWithTag("live-tv-channel-$channelId").performVerificationTouchInput(compose) { longClick() }

    /** 搜索框默认收起成按钮，输入前先展开。 */
    private fun openSearch() {
        compose.onNodeWithTag("live-tv-search-open").performVerificationClick(compose)
        waitFor("live-tv-search")
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }

    private class ScreenHost(fixture: Fixture) {
        lateinit var state: LiveTvState
        var fixture by mutableStateOf(fixture)
        var visible by mutableStateOf(true)
        var active by mutableStateOf(true)
        val backgroundBacks = AtomicInteger()
        val clock = AtomicLong(1_000)
        val authExpired = AtomicInteger()
        val notice = SnackbarHostState()
    }

    private fun show(fixture: Fixture, initialQuery: String = "", groups: Boolean = false, onPlay: (MediaItem) -> Unit = {}): ScreenHost {
        val host = ScreenHost(fixture)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val current = host.fixture
            val state = remember(current) {
                LiveTvState(current.session, current.api, scope, { host.authExpired.incrementAndGet() }, clock = host.clock::get,
                    groupSource = if (groups) current.groupSource(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("live-tv-groups-test").apply { deleteRecursively() }) else null)
                    .also { it.query = initialQuery; host.state = it }
            }
            DisposableEffect(state) { onDispose { state.cancel() } }
            PathfinTheme(ThemePreference.LIGHT) { Surface {
                BackHandler(!host.active) { host.backgroundBacks.incrementAndGet() }
                CompositionLocalProvider(LocalPageActive provides host.active, LocalPageRefreshEnabled provides host.active,
                    LocalNoticeHost provides host.notice) {
                    Box(Modifier.fillMaxSize()) {
                        if (host.visible) LiveTvScreen(state, { host.visible = false }, onPlay)
                        if (host.active) PathfinSnackbarHost(host.notice, Modifier.align(Alignment.BottomCenter))
                    }
                }
            } }
        }
        return host
    }

    private fun reenter(host: ScreenHost) {
        compose.runOnIdle { host.visible = false }
        compose.onNodeWithTag("live-tv-screen").assertDoesNotExist()
        compose.runOnIdle { host.visible = true }
        waitFor("live-tv-screen")
    }

    private data class PageRequest(val startIndex: Int, val limit: Int)

    private class PageProbe(blockRefresh: Boolean = false) : Closeable {
        val requests = CopyOnWriteArrayList<PageRequest>()
        val failAt = AtomicInteger(-1)
        val failRefreshAt = AtomicInteger(-1)
        val refreshedRemainderTotal = AtomicInteger(-1)
        val repeatBoundary = AtomicBoolean()
        val holdAt = AtomicInteger(-1)
        val heldStarted = CountDownLatch(1)
        val releaseHeld = CountDownLatch(1)
        val heldReturned = CountDownLatch(1)
        val holdRefresh = AtomicBoolean(blockRefresh)
        val holdRemainder = AtomicBoolean()
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val remainderStarted = CountDownLatch(1)
        val releaseRemainder = CountDownLatch(1)
        @Volatile var refreshed = false
        fun total(request: PageRequest, normal: Int): Int =
            if (refreshed && request.startIndex > 0 && refreshedRemainderTotal.get() >= 0) refreshedRemainderTotal.get() else normal
        fun read(url: HttpUrl): PageRequest {
            val request = PageRequest(requireNotNull(url.queryParameter("startIndex")).toInt(), requireNotNull(url.queryParameter("limit")).toInt())
            check(request.startIndex >= 0 && request.limit in 1..100)
            requests += request
            if (request.startIndex == holdAt.get()) {
                heldStarted.countDown(); check(releaseHeld.await(10, TimeUnit.SECONDS)); heldReturned.countDown()
            }
            if (request.startIndex == 0 && requests.count { it.startIndex == 0 } > 1) {
                refreshed = true
                if (holdRefresh.get()) { refreshStarted.countDown(); check(releaseRefresh.await(10, TimeUnit.SECONDS)) }
            } else if (request.startIndex > 0 && refreshed && holdRemainder.get()) {
                remainderStarted.countDown(); check(releaseRemainder.await(10, TimeUnit.SECONDS))
            }
            return request
        }
        override fun close() { releaseRefresh.countDown(); releaseRemainder.countDown(); releaseHeld.countDown() }
    }

    private class Fixture(allowed: Boolean = true, blockRefresh: Boolean = false, private val paginated: Boolean = false,
        private val empty: Boolean = false, private val rich: Boolean = false, private val watched: Boolean = true, playable: Boolean = true,
        private val channelTotal: Int = 81,
        val session: Session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User", "device"),
        private val channelPrefix: String = "", private val groupNames: List<String> = listOf("沿海", "夜航")) : Closeable {
        val paths = CopyOnWriteArrayList<String>()
        val channelPages = PageProbe(blockRefresh)
        val programPages = PageProbe()
        val channelStatus = AtomicInteger(200)
        val playlistGroupsEnabled = AtomicBoolean(true)
        val playlistRequests = AtomicInteger()
        var ensureGroupNetworkAccess: suspend (String) -> Unit = {}
        val refreshStarted get() = channelPages.refreshStarted
        val releaseRefresh get() = channelPages.releaseRefresh
        val api = JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath; paths += path
            assertTrue(chain.request().header("Authorization")?.contains(session.accessToken) == true)
            val now = Instant.now()
            var code = 200
            val body = when (path) {
                "/proxy/Users/Me" -> JSONObject().put("Id", session.userId).put("Policy", JSONObject().put("IsDisabled", false).put("EnableLiveTvAccess", allowed).put("EnableMediaPlayback", playable))
                "/proxy/LiveTv/Info" -> JSONObject().put("IsEnabled", true)
                "/proxy/System/Configuration/livetv" -> JSONObject().put("TunerHosts", JSONArray().put(JSONObject()
                    .put("Type", "m3u").put("Url", "https://tuner.invalid/list.m3u")))
                "/proxy/LiveTv/Channels" -> {
                    val request = channelPages.read(chain.request().url)
                    if (channelStatus.get() != 200) { code = channelStatus.get(); JSONObject() }
                    else if (request.startIndex == channelPages.failAt.get() || (channelPages.refreshed && request.startIndex == channelPages.failRefreshAt.get())) { code = 503; JSONObject() }
                    else if (empty) page()
                    else if (paginated) page(request, channelPages.total(request, channelTotal)) { index ->
                        val sourceIndex = if (channelPages.repeatBoundary.get() && request.startIndex > 0 && index == request.startIndex) index - 1 else index
                        channel("channel-$sourceIndex", channelName(sourceIndex, channelPages.refreshed))
                    }
                    else if (rich) page(
                        // 港湾：收藏、正在播体育、上次看过；海鸥：正在播新闻；灯塔：没有节目信息。
                        channel("harbour", "港湾电视").put("UserData", JSONObject().put("IsFavorite", true).apply { if (watched) put("LastPlayedDate", now.minusSeconds(3600).toString()) })
                            .put("CurrentProgram", program("harbour-now", now.minusSeconds(600), now.plusSeconds(600), "harbour").put("IsSports", true)),
                        channel("seagull", "海鸥电视").put("CurrentProgram", program("seagull-now", now.minusSeconds(300), now.plusSeconds(900)).put("IsNews", true)),
                        channel("lighthouse", "灯塔电视"))
                    else page(channel("${channelPrefix}harbour", (if (channelPages.refreshed) "刷新 · " else "") + "港湾电视"),
                        channel("${channelPrefix}seagull", (if (channelPages.refreshed) "刷新 · " else "") + "海鸥电视"))
                }
                "/proxy/LiveTv/Programs" -> {
                    val request = programPages.read(chain.request().url)
                    if (request.startIndex == programPages.failAt.get() || (programPages.refreshed && request.startIndex == programPages.failRefreshAt.get())) { code = 503; JSONObject() }
                    else if (paginated) {
                        val from = Instant.parse(requireNotNull(chain.request().url.queryParameter("minEndDate")))
                        assertEquals("channel-0", chain.request().url.queryParameter("channelIds"))
                        page(request, programPages.total(request, 161)) { index ->
                            val sourceIndex = if (programPages.repeatBoundary.get() && request.startIndex in 1 until 160 && index == request.startIndex) index - 1 else index
                            program("program-$sourceIndex", from.plusSeconds(sourceIndex * 300L), from.plusSeconds((sourceIndex + 1) * 300L), "channel-0")
                                .put("Name", programName(sourceIndex, programPages.refreshed))
                        }
                    } else page(program("current", now.minusSeconds(600), now.plusSeconds(600)),
                        program("future", now.plusSeconds(600), now.plusSeconds(1200)), program("past", now.minusSeconds(1200), now.minusSeconds(600)))
                }
                else -> throw AssertionError("Unexpected synthetic endpoint")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unavailable")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())

        /** 合成调谐器播放列表：港湾、海鸥在“沿海”，灯塔在“夜航”。 */
        fun groupSource(directory: java.io.File) = LiveTvGroupSource(directory, OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "tuner.invalid")
            playlistRequests.incrementAndGet()
            val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"${groupNames[0]}\",港湾电视\nhttp://a/1\n" +
                "#EXTINF:-1 group-title=\"${groupNames[0]}\",海鸥电视\nhttp://a/2\n#EXTINF:-1 group-title=\"${groupNames[1]}\",灯塔电视\nhttp://a/3\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (playlistGroupsEnabled.get()) playlist else playlist.replace(Regex(" group-title=\"[^\"]*\""), ""))
                    .toResponseBody("audio/x-mpegurl".toMediaType())).build()
        }.build(), ensureNetworkAccess = { ensureGroupNetworkAccess(it) })

        private fun channel(id: String, title: String) = JSONObject().put("Id", id).put("Name", title).put("Type", "TvChannel")
        private fun program(id: String, start: Instant, end: Instant, channelId: String = "seagull") = JSONObject().put("Id", id).put("ChannelId", channelId).put("Name", "合成节目 $id").put("StartDate", start.toString()).put("EndDate", end.toString())
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size).put("StartIndex", 0)
        private fun page(request: PageRequest, total: Int, item: (Int) -> JSONObject): JSONObject = JSONObject()
            .put("Items", JSONArray((request.startIndex until minOf(request.startIndex + request.limit, total)).map(item)))
            .put("TotalRecordCount", total).put("StartIndex", request.startIndex)
        private fun channelName(index: Int, refreshed: Boolean) = (if (refreshed) "刷新 · " else "") + if (index == 80) "远方频道" else "合成频道 $index"
        private fun programName(index: Int, refreshed: Boolean) = (if (refreshed) "刷新 · " else "") + "合成节目 $index"
        fun refreshedName(programs: Boolean, index: Int) = if (programs) programName(index, true) else channelName(index, true)
        fun originalName(programs: Boolean, index: Int) = if (programs) programName(index, false) else channelName(index, false)
        override fun close() { channelPages.close(); programPages.close() }
    }
    private companion object { const val WAIT_MS = 10_000L }
}

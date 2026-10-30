package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.LiveTvScreen
import dev.pathfin.android.ui.LiveTvState
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
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        compose.onNodeWithTag("live-tv-search").performTextInput("海鸥")
        compose.onNodeWithTag("live-tv-channel-harbour").assertDoesNotExist()
        compose.onNodeWithTag("live-tv-play-seagull").performVerificationClick(compose)
        compose.runOnIdle { assertEquals("seagull", played.get()?.id); assertEquals("TvChannel", played.get()?.type) }
        compose.onNodeWithTag("live-tv-guide-seagull").performVerificationClick(compose)
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
        compose.onNodeWithTag("live-tv-play-harbour").assertDoesNotExist()
        assertEquals(listOf("/proxy/Users/Me"), fixture.paths.toList())
    }

    @Test fun freshReentryRetainsLoadedPagesScrollAndSearchWithoutRequestingChannelsAgain() {
        Fixture(paginated = true).use { fixture ->
            fixture.channelPages.holdAt.set(40)
            val host = show(fixture)
            waitFor("live-tv-channel-channel-0")
            scrollEnd("live-tv-channel-list", "live-tv-channel-channel-39")
            compose.waitUntil(WAIT_MS) { fixture.channelPages.heldStarted.count == 0L }
            // 先关闭自动追加再释放第二页，固定已读范围；收起输入法后比较返回位置。
            compose.onNodeWithTag("live-tv-search").performTextInput("合成频道")
            compose.onNodeWithTag("live-tv-search").performImeAction()
            fixture.channelPages.releaseHeld.countDown()
            val list = compose.onNodeWithTag("live-tv-channel-list")
            compose.waitUntil(WAIT_MS) { runCatching {
                list.performScrollToNode(hasTestTag("live-tv-channel-channel-60")); true
            }.getOrDefault(false) }
            assertEquals(listOf(0, 40), fixture.channelPages.requests.map { it.startIndex })
            VerificationDisplay.awaitWindowReady(compose, "live-tv-retained-position", list)
            val before = channelBounds("live-tv-channel-channel-60")
            val requests = fixture.paths.size

            reenter(host)
            VerificationDisplay.awaitWindowReady(compose, "live-tv-reentered-position", list)
            compose.onNodeWithTag("live-tv-search").assertTextContains("合成频道")
            compose.onNodeWithTag("live-tv-channel-channel-60").assertVerificationDisplayed(compose)
            assertSameBounds(before, channelBounds("live-tv-channel-channel-60"))
            compose.onNodeWithText("正在读取直播频道").assertDoesNotExist()
            assertNoNewRequests(fixture.channelPages, 2)
            assertEquals(requests, fixture.paths.size)

            compose.onNodeWithTag("live-tv-search").performTextReplacement("合成频道 60")
            reenter(host)
            compose.onNodeWithTag("live-tv-search").assertTextContains("合成频道 60")
            compose.onNodeWithTag("live-tv-channel-channel-60").assertVerificationDisplayed(compose)
            assertNoNewRequests(fixture.channelPages, 2)
            assertEquals(requests, fixture.paths.size)

            compose.onNodeWithTag("live-tv-search").performTextClearance()
            scrollEnd("live-tv-channel-list", "live-tv-channel-channel-79")
            compose.waitUntil(WAIT_MS) { fixture.channelPages.requests.size == 3 }
            list.performScrollToNode(hasTestTag("live-tv-channel-channel-80"))
            compose.onNodeWithTag("live-tv-channel-channel-80").assertVerificationDisplayed(compose)
            assertEquals(listOf(0, 40, 80), fixture.channelPages.requests.map { it.startIndex })
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
        }
    }

    @Test fun staleReentryWaitsForThePendingAppendThenRefreshesTheWholeRetainedRange() {
        Fixture(paginated = true, blockRefresh = true).use { fixture ->
            val probe = fixture.channelPages
            probe.holdAt.set(40)
            val host = show(fixture)
            waitFor("live-tv-channel-channel-0")
            try {
                scrollEnd("live-tv-channel-list", "live-tv-channel-channel-39")
                compose.waitUntil(WAIT_MS) { probe.heldStarted.count == 0L }
                // 保留同样的结果集合，同时禁止追加完成后的自动分页干扰待验证的整段刷新。
                val search = compose.onNodeWithTag("live-tv-search")
                search.performTextInput("合成频道")
                search.performImeAction()
                val list = compose.onNodeWithTag("live-tv-channel-list")
                list.performScrollToNode(hasTestTag("live-tv-channel-channel-39"))
                VerificationDisplay.awaitWindowReady(compose, "live-tv-pending-append-position", list)
                val before = channelBounds("live-tv-channel-channel-39")

                host.clock.addAndGet(60_001)
                reenter(host)
                VerificationDisplay.awaitWindowReady(compose, "live-tv-pending-append-reentry", list)
                compose.onNodeWithTag("live-tv-channel-channel-39").assertVerificationDisplayed(compose)
                compose.onNodeWithText("正在读取直播频道").assertDoesNotExist()
                assertSameBounds(before, channelBounds("live-tv-channel-channel-39"))
                assertEquals(listOf(0, 40), probe.requests.map { it.startIndex })

                probe.holdAt.set(-1)
                probe.releaseHeld.countDown()
                compose.waitUntil(WAIT_MS) { probe.refreshStarted.count == 0L }
                // 已完成的追加必须先发布，随后只重读一次此前已载入的完整80条范围。
                assertEquals(listOf(0, 40, 0), probe.requests.map { it.startIndex })
                compose.onNodeWithText("搜索已加载的 80 个频道", substring = true).assertVerificationDisplayed(compose)
                compose.onNodeWithText(fixture.originalName(false, 39)).assertVerificationDisplayed(compose)
                assertSameBounds(before, channelBounds("live-tv-channel-channel-39"))
                compose.onNodeWithText("正在读取直播频道").assertDoesNotExist()
            } finally {
                probe.releaseHeld.countDown()
                probe.releaseRefresh.countDown()
            }
            compose.waitUntil(WAIT_MS) {
                probe.requests.size == 4 && compose.onAllNodesWithText(fixture.refreshedName(false, 39)).fetchSemanticsNodes().isNotEmpty()
            }
            assertNoNewRequests(probe, 4)
            assertEquals(listOf(0, 40, 0, 40), probe.requests.map { it.startIndex })
            assertEquals(2, fixture.paths.count { it.endsWith("Users/Me") })
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
            compose.onNodeWithTag("live-tv-play-harbour").assertDoesNotExist()
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

    @Test fun channelsAppendOnScrollAndRefreshKeepsTheReadRangeAfterAnAppendFailure() =
        exercisePagination(programs = false)

    @Test fun guideAppendsOnScrollAndRefreshKeepsTheReadRangeAfterAnAppendFailure() =
        exercisePagination(programs = true)

    @Test fun channelSearchExplainsItsLoadedScopeAndOnlyExplicitlySearchesTheWholeLibrary() {
        Fixture(paginated = true).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            assertEquals(listOf(0), fixture.channelPages.requests.map { it.startIndex })
            compose.onNodeWithTag("live-tv-search").performTextInput("远方频道")
            compose.onNodeWithText("搜索已加载的", substring = true).assertVerificationDisplayed(compose)
            compose.onNodeWithText("没有匹配的频道").assertVerificationDisplayed(compose)
            repeat(3) { compose.onNodeWithTag("live-tv-channel-list").performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertNoNewRequests(fixture.channelPages, 1)
            // 搜索全部是用户明确触发的操作，不能被无匹配结果时的自动分页取代。
            val all = compose.onNodeWithTag("live-tv-more").performScrollTo()
            VerificationDisplay.awaitWindowReady(compose, "live-tv-search-all", all)
            all.performVerificationClick(compose)
            waitFor("live-tv-channel-channel-80")
            compose.onNodeWithTag("live-tv-channel-channel-80").assertVerificationDisplayed(compose)
            assertEquals(listOf(0, 40, 80), fixture.channelPages.requests.map { it.startIndex })
            assertNoNewRequests(fixture.channelPages, 3)
        }
    }

    @Test fun channelsKeepTheRawCursorWhenAdjacentPagesRepeatAnItem() = exerciseRawCursor(programs = false)

    @Test fun guideKeepsTheRawCursorWhenAdjacentPagesRepeatAnItem() = exerciseRawCursor(programs = true)

    @Test fun channelRefreshDoesNotPublishItsFirstPageWhenTheSecondPageFails() = exerciseFailedRangeRefresh(programs = false)

    @Test fun guideRefreshDoesNotPublishItsFirstPageWhenTheSecondPageFails() = exerciseFailedRangeRefresh(programs = true)

    @Test fun channelRefreshKeepsItsSnapshotWhenTotalsChangeBetweenFragments() = exerciseFailedRangeRefresh(programs = false, changingTotal = true)

    @Test fun guideRefreshKeepsItsSnapshotWhenTotalsChangeBetweenFragments() = exerciseFailedRangeRefresh(programs = true, changingTotal = true)

    @Test fun stoppingExplicitWholeChannelSearchDoesNotRequestTheFollowingPage() {
        Fixture(paginated = true).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            fixture.channelPages.holdAt.set(40)
            compose.onNodeWithTag("live-tv-search").performTextInput("远方频道")
            compose.onNodeWithTag("live-tv-more").performScrollTo().performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.channelPages.heldStarted.count == 0L }
            val stop = compose.onNodeWithTag("live-tv-cancel-search").performScrollTo()
            VerificationDisplay.awaitWindowReady(compose, "live-tv-stop-search", stop)
            stop.performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { runCatching { compose.onNodeWithTag("live-tv-more").assertIsEnabled(); true }.getOrDefault(false) }
            fixture.channelPages.releaseHeld.countDown()
            assertNoNewRequests(fixture.channelPages, 2)
            assertEquals(listOf(0, 40), fixture.channelPages.requests.map { it.startIndex })
            compose.onNodeWithTag("live-tv-channel-channel-80").assertDoesNotExist()
        }
    }

    private fun exerciseRawCursor(programs: Boolean) {
        Fixture(paginated = true).use { fixture ->
            val probe = if (programs) fixture.programPages else fixture.channelPages
            probe.repeatBoundary.set(true)
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            if (programs) {
                compose.onNodeWithTag("live-tv-guide-channel-0").performVerificationClick(compose)
                waitFor("live-tv-program-program-0")
            }
            val size = if (programs) 80 else 40
            val listTag = if (programs) "live-tv-program-list" else "live-tv-channel-list"
            val prefix = if (programs) "live-tv-program-program-" else "live-tv-channel-channel-"
            scrollEnd(listTag, "$prefix${size - 1}")
            scrollEnd(listTag, "$prefix${size * 2 - 1}")
            scrollEnd(listTag, "$prefix${size * 2}")
            assertEquals("去重后的行数不能作为后续分页游标", listOf(0, size, size * 2), probe.requests.map { it.startIndex })
            assertNoNewRequests(probe, 3)
        }
    }

    private fun exerciseFailedRangeRefresh(programs: Boolean, changingTotal: Boolean = false) {
        Fixture(paginated = true).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            if (programs) {
                compose.onNodeWithTag("live-tv-guide-channel-0").performVerificationClick(compose)
                waitFor("live-tv-program-program-0")
            }
            val probe = if (programs) fixture.programPages else fixture.channelPages
            val size = if (programs) 80 else 40
            val listTag = if (programs) "live-tv-program-list" else "live-tv-channel-list"
            val prefix = if (programs) "live-tv-program-program-" else "live-tv-channel-channel-"
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

    private fun exercisePagination(programs: Boolean) {
        Fixture(paginated = true).use { fixture ->
            show(fixture)
            waitFor("live-tv-channel-channel-0")
            if (programs) {
                val guide = compose.onNodeWithTag("live-tv-guide-channel-0")
                VerificationDisplay.awaitWindowReady(compose, "live-tv-open-guide", guide)
                guide.performVerificationClick(compose)
                waitFor("live-tv-program-program-0")
            }
            val probe = if (programs) fixture.programPages else fixture.channelPages
            val pageSize = if (programs) 80 else 40
            val listTag = if (programs) "live-tv-program-list" else "live-tv-channel-list"
            val prefix = if (programs) "live-tv-program-program-" else "live-tv-channel-channel-"
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
                compose.onAllNodesWithText(fixture.refreshedName(programs, lastRead)).fetchSemanticsNodes().isNotEmpty() }
            if (probe.remainderStarted.count == 0L) {
                list.performScrollToNode(hasTestTag("$prefix$lastRead"))
                compose.onNodeWithTag("$prefix$lastRead").assertVerificationDisplayed(compose)
            }
            probe.releaseRemainder.countDown()
            compose.waitUntil(WAIT_MS) { runCatching {
                list.performScrollToNode(hasTestTag("$prefix$lastRead"))
                compose.onNodeWithText(fixture.refreshedName(programs, lastRead)).assertVerificationDisplayed(compose)
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
            compose.onNodeWithTag(if (programs) "live-tv-program-more" else "live-tv-more").assertDoesNotExist()
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

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }

    private class ScreenHost(fixture: Fixture) {
        var fixture by mutableStateOf(fixture)
        var visible by mutableStateOf(true)
        val clock = AtomicLong(1_000)
        val authExpired = AtomicInteger()
    }

    private fun show(fixture: Fixture, onPlay: (MediaItem) -> Unit = {}): ScreenHost {
        val host = ScreenHost(fixture)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val current = host.fixture
            val state = remember(current) {
                LiveTvState(current.session, current.api, scope, { host.authExpired.incrementAndGet() }, clock = host.clock::get)
            }
            DisposableEffect(state) { onDispose { state.cancel() } }
            PathfinTheme(ThemePreference.LIGHT) { Surface {
                if (host.visible) LiveTvScreen(state, { host.visible = false }, onPlay)
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
        private val empty: Boolean = false,
        val session: Session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User", "device"),
        private val channelPrefix: String = "") : Closeable {
        val paths = CopyOnWriteArrayList<String>()
        val channelPages = PageProbe(blockRefresh)
        val programPages = PageProbe()
        val channelStatus = AtomicInteger(200)
        val refreshStarted get() = channelPages.refreshStarted
        val releaseRefresh get() = channelPages.releaseRefresh
        val api = JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath; paths += path
            assertTrue(chain.request().header("Authorization")?.contains(session.accessToken) == true)
            val now = Instant.now()
            var code = 200
            val body = when (path) {
                "/proxy/Users/Me" -> JSONObject().put("Id", session.userId).put("Policy", JSONObject().put("IsDisabled", false).put("EnableLiveTvAccess", allowed).put("EnableMediaPlayback", true))
                "/proxy/LiveTv/Info" -> JSONObject().put("IsEnabled", true)
                "/proxy/LiveTv/Channels" -> {
                    val request = channelPages.read(chain.request().url)
                    if (channelStatus.get() != 200) { code = channelStatus.get(); JSONObject() }
                    else if (request.startIndex == channelPages.failAt.get() || (channelPages.refreshed && request.startIndex == channelPages.failRefreshAt.get())) { code = 503; JSONObject() }
                    else if (empty) page()
                    else if (paginated) page(request, channelPages.total(request, 81)) { index ->
                        val sourceIndex = if (channelPages.repeatBoundary.get() && request.startIndex in 1 until 80 && index == request.startIndex) index - 1 else index
                        channel("channel-$sourceIndex", channelName(sourceIndex, channelPages.refreshed))
                    }
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

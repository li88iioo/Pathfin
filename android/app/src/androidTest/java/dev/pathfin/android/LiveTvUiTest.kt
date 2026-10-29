package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.LiveTvScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.time.Instant
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        compose.onNodeWithTag("live-tv-play-seagull").performClick()
        compose.runOnIdle { assertEquals("seagull", played.get()?.id); assertEquals("TvChannel", played.get()?.type) }
        compose.onNodeWithTag("live-tv-guide-seagull").performClick()
        waitFor("live-tv-program-current")
        compose.onNodeWithTag("live-tv-watch-program-current").assertExists()
        compose.onNodeWithTag("live-tv-program-future").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("live-tv-watch-program-future").assertDoesNotExist()
        compose.onNodeWithTag("live-tv-program-past").performScrollTo().assertIsDisplayed()
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
            val before = compose.onNodeWithTag("live-tv-channel-harbour").getUnclippedBoundsInRoot()
            // 从频道网格的首列下拉，避免沿用旧单列页面的父容器中心坐标。
            pullPageToRefresh(compose, page = channels, stage = "live-tv-retained-channel-refresh", horizontalFraction = .25f)
            compose.onNodeWithTag("live-tv-refresh").assertDoesNotExist()
            compose.waitUntil(5_000) { fixture.refreshStarted.count == 0L }
            compose.onNodeWithTag("live-tv-channel-harbour").assertIsDisplayed()
            val during = compose.onNodeWithTag("live-tv-channel-harbour").getUnclippedBoundsInRoot()
            assertEquals(before.top.value, during.top.value, .5f)
            assertEquals((before.bottom - before.top).value, (during.bottom - during.top).value, .5f)
        } finally { fixture.releaseRefresh.countDown() }
    }

    @Test fun deniedLiveTvAccessDoesNotShowPlaybackOrRequestChannels() {
        val fixture = Fixture(allowed = false)
        show(fixture)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("没有直播电视权限").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("没有直播电视权限").assertIsDisplayed()
        compose.onNodeWithTag("live-tv-play-harbour").assertDoesNotExist()
        assertEquals(listOf("/proxy/Users/Me"), fixture.paths.toList())
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
            compose.onNodeWithText("搜索已加载的", substring = true).assertIsDisplayed()
            compose.onNodeWithText("没有匹配的频道").assertIsDisplayed()
            repeat(3) { compose.onNodeWithTag("live-tv-channel-list").performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertNoNewRequests(fixture.channelPages, 1)
            // 搜索全部是用户明确触发的操作，不能被无匹配结果时的自动分页取代。
            val all = compose.onNodeWithTag("live-tv-more").performScrollTo()
            VerificationDisplay.awaitWindowReady(compose, "live-tv-search-all", all)
            all.performClick()
            waitFor("live-tv-channel-channel-80")
            compose.onNodeWithTag("live-tv-channel-channel-80").assertIsDisplayed()
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
            compose.onNodeWithTag("live-tv-more").performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { fixture.channelPages.heldStarted.count == 0L }
            val stop = compose.onNodeWithTag("live-tv-cancel-search").performScrollTo()
            VerificationDisplay.awaitWindowReady(compose, "live-tv-stop-search", stop)
            stop.performClick()
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
                compose.onNodeWithTag("live-tv-guide-channel-0").performClick()
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
                compose.onNodeWithTag("live-tv-guide-channel-0").performClick()
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
            compose.onNodeWithTag("$prefix${size * 2 - 1}").assertIsDisplayed()
            list.performScrollToNode(hasTestTag("${prefix}0"))
            compose.onNodeWithText(fixture.originalName(programs, 0)).assertIsDisplayed()
            compose.onAllNodesWithText(fixture.refreshedName(programs, 0)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            repeat(2) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
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
                guide.performClick()
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
            compose.onNodeWithTag("$prefix$lastRead").assertIsDisplayed()
            repeat(3) { compose.onNodeWithTag(listTag).performTouchInput { swipeUp() }; compose.waitForIdle() }
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
            compose.onNodeWithTag("$prefix$lastRead").assertIsDisplayed()
            probe.releaseRefresh.countDown()
            // 默认页长重读时会经过第二页；若合并为较大页长，则可直接完整发布。
            compose.waitUntil(WAIT_MS) { probe.remainderStarted.count == 0L ||
                compose.onAllNodesWithText(fixture.refreshedName(programs, lastRead)).fetchSemanticsNodes().isNotEmpty() }
            if (probe.remainderStarted.count == 0L) {
                list.performScrollToNode(hasTestTag("$prefix$lastRead"))
                compose.onNodeWithTag("$prefix$lastRead").assertIsDisplayed()
            }
            probe.releaseRemainder.countDown()
            compose.waitUntil(WAIT_MS) { runCatching {
                list.performScrollToNode(hasTestTag("$prefix$lastRead"))
                compose.onNodeWithText(fixture.refreshedName(programs, lastRead)).assertIsDisplayed()
                true
            }.getOrDefault(false) }
            scrollEnd(listTag, "$prefix$lastRead")
            compose.waitUntil(WAIT_MS) { runCatching {
                list.performScrollToNode(hasTestTag("$prefix${pageSize * 2}")); true
            }.getOrDefault(false) }
            compose.onNodeWithTag("$prefix${pageSize * 2}").assertIsDisplayed()
            val terminalRequests = probe.requests.size
            repeat(3) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertNoNewRequests(probe, terminalRequests)
            compose.onNodeWithTag(if (programs) "live-tv-program-more" else "live-tv-more").assertDoesNotExist()
        }
    }

    private fun scrollEnd(listTag: String, lastTag: String) {
        val list = compose.onNodeWithTag(listTag)
        compose.waitUntil(WAIT_MS) { runCatching {
            list.performScrollToNode(hasTestTag(lastTag))
            compose.onNodeWithTag(lastTag).assertIsDisplayed()
            true
        }.getOrDefault(false) }
        VerificationDisplay.awaitWindowReady(compose, "live-tv-scroll-$lastTag", list)
        list.performTouchInput { swipeUp() }
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

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }

    private fun show(fixture: Fixture, onPlay: (MediaItem) -> Unit = {}) {
        compose.setContent { PathfinTheme(ThemePreference.LIGHT) { Surface {
            LiveTvScreen(fixture.session, fixture.api, {}, onPlay)
        } } }
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
            if (request.startIndex == holdAt.get()) { heldStarted.countDown(); check(releaseHeld.await(10, TimeUnit.SECONDS)) }
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

    private class Fixture(allowed: Boolean = true, blockRefresh: Boolean = false, private val paginated: Boolean = false) : Closeable {
        val session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User", "device")
        val paths = CopyOnWriteArrayList<String>()
        val channelPages = PageProbe(blockRefresh)
        val programPages = PageProbe()
        val refreshStarted get() = channelPages.refreshStarted
        val releaseRefresh get() = channelPages.releaseRefresh
        val api = JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath; paths += path
            val now = Instant.now()
            var code = 200
            val body = when (path) {
                "/proxy/Users/Me" -> JSONObject().put("Id", "user").put("Policy", JSONObject().put("IsDisabled", false).put("EnableLiveTvAccess", allowed).put("EnableMediaPlayback", true))
                "/proxy/LiveTv/Info" -> JSONObject().put("IsEnabled", true)
                "/proxy/LiveTv/Channels" -> {
                    val request = channelPages.read(chain.request().url)
                    if (request.startIndex == channelPages.failAt.get() || (channelPages.refreshed && request.startIndex == channelPages.failRefreshAt.get())) { code = 503; JSONObject() }
                    else if (paginated) page(request, channelPages.total(request, 81)) { index ->
                        val sourceIndex = if (channelPages.repeatBoundary.get() && request.startIndex in 1 until 80 && index == request.startIndex) index - 1 else index
                        channel("channel-$sourceIndex", channelName(sourceIndex, channelPages.refreshed))
                    }
                    else page(channel("harbour", "港湾电视"), channel("seagull", "海鸥电视"))
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

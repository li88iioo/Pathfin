package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import dev.pathfin.android.createVerificationComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.MediaIdentificationScreen
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.ServerAdministrationScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import coil3.ImageLoader
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 页面直连进程内回环服务；不接个人账号、不依赖 ADB reverse，不修改生产包数据。 */
@RunWith(AndroidJUnit4::class)
class ManagementFeaturesUiTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification") {
                "管理功能回归只允许 verification 独立包"
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Composable
    private fun ManagementContent(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val loader = remember(context) { ImageLoader.Builder(context).diskCache { null }.build() }
        DisposableEffect(loader) { onDispose { loader.shutdown() } }
        PathfinTheme(ThemePreference.LIGHT) {
            CompositionLocalProvider(LocalPosterLoader provides loader, content = content)
        }
    }

    @Test fun identificationRequiresConfirmationAndBlocksBackOnlyWhileApplying() {
        Fixture().use { fixture ->
            val shown = mutableStateOf(true); val changed = AtomicInteger(); val backs = AtomicInteger()
            val api = JellyfinApi(); val session = fixture.session()
            compose.setContent { ManagementContent {
                if (shown.value) MediaIdentificationScreen(MediaItem("item", "Synthetic title", "Movie"), session, api,
                    onBack = { backs.incrementAndGet(); shown.value = false }, onChanged = { changed.incrementAndGet() })
            } }
            search(fixture)
            scrollIdentification("identification-result")
            compose.onNodeWithTag("identification-result").performClick()
            compose.onNodeWithText("应用识别结果？").assertIsDisplayed()
            compose.onNodeWithText("取消", substring = false).performClick()
            assertEquals(0, fixture.applies.get())
            compose.onNodeWithTag("identification-result").performClick()
            compose.onNodeWithTag("identification-replace-images").assertIsOff()
            compose.onNodeWithTag("identification-confirm").performClick()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.applies.get() == 1 }
            compose.onNodeWithTag("identification-back").assertIsNotEnabled()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(0, backs.get()); assertEquals(0, changed.get())
            assertEquals("false", fixture.replaceImages.get())
            fixture.allowApply.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); changed.get() == 1 }
            scrollIdentification("identification-success")
            compose.onNodeWithTag("identification-success").assertIsDisplayed()
            assertEquals(1, fixture.applies.get())
            // 结果页的返回先回到搜索条件，再次返回才离开识别页。
            compose.onNodeWithTag("identification-back").assertIsEnabled().performClick()
            compose.onNodeWithTag("identification-list").assertIsDisplayed()
            assertEquals(0, backs.get())
            compose.onNodeWithTag("identification-back").performClick()
            compose.waitUntil(WAIT_MS) { !shown.value }
            assertEquals(1, backs.get())
        }
    }

    @Test fun searchCanRetryAndSwitchingAccountDropsTheOldCandidates() {
        Fixture().use { fixture ->
            fixture.failSearch.set(true)
            val session = mutableStateOf(fixture.session()); val api = JellyfinApi()
            compose.setContent { ManagementContent {
                MediaIdentificationScreen(MediaItem("item", "Synthetic title", "Series"), session.value, api, {}, {})
            } }
            scrollIdentification("identification-search")
            compose.onNodeWithTag("identification-search").performClick()
            compose.waitUntil(WAIT_MS) { fixture.searches.get() == 1 && exists("identification-error") }
            compose.onNodeWithTag("identification-search").assertIsEnabled()
            fixture.failSearch.set(false)
            compose.onNodeWithTag("identification-list").performScrollToIndex(0)
            val page = compose.onNodeWithTag("refreshable-page")
            // 列表的内容左右各留 16dp；8dp 仍在可滚动列表内，避开文本框的选择/焦点手势。
            val gutterFraction = (8f * compose.activity.resources.displayMetrics.density) / page.fetchSemanticsNode().boundsInRoot.width
            pullPageToRefresh(compose, page, "identification-read-recovers", horizontalFraction = gutterFraction)
            awaitIdentification(fixture, "second-http-request") { fixture.searches.get() == 2 }
            var lastScrollFailure: Throwable? = null
            try {
                awaitIdentification(fixture, "result-visible") {
                    runCatching {
                        scrollIdentification("identification-result")
                        compose.onNodeWithTag("identification-result").assertIsDisplayed()
                    }.onFailure { lastScrollFailure = it }.isSuccess
                }
            } catch (failure: Throwable) {
                lastScrollFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
            compose.runOnIdle { session.value = fixture.session().copy(userId = "another-synthetic-user") }
            compose.onNodeWithTag("identification-result").assertDoesNotExist()
            assertEquals(0, fixture.applies.get())
            scrollIdentification("identification-year")
            compose.onNodeWithTag("identification-year").performTextReplacement("invalid-year")
            scrollIdentification("identification-search")
            compose.onNodeWithTag("identification-search").assertIsNotEnabled()
            assertEquals(2, fixture.searches.get())
        }
    }

    @Test fun personIdentificationListsServerProviderIdsPrefilledAndSearchesWithoutYear() {
        Fixture().use { fixture ->
            val api = JellyfinApi(); val session = fixture.session()
            val person = MediaItem("item", "Synthetic actor", "Person", providerIds = mapOf("Tmdb" to "85718", "Douban" to "not-offered"))
            compose.setContent { ManagementContent { MediaIdentificationScreen(person, session, api, {}, {}) } }
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); exists("identification-id/Imdb") }
            compose.onNodeWithText("识别人物").assertIsDisplayed()
            compose.onNodeWithTag("identification-year").assertDoesNotExist()
            scrollIdentification("identification-id/Tmdb")
            compose.onNodeWithTag("identification-id/Tmdb").assertTextContains("85718")
            scrollIdentification("identification-id/Imdb")
            compose.onNodeWithTag("identification-id/Imdb").performTextReplacement("nm1395602")
            search(fixture)
            compose.onNodeWithText("选择匹配结果").assertIsDisplayed()
            compose.onNodeWithTag("identification-result-image", useUnmergedTree = true).assertExists()
            val info = requireNotNull(fixture.lastSearchInfo.get())
            assertFalse("人物识别不带年份", info.has("Year"))
            val ids = info.getJSONObject("ProviderIds")
            assertEquals("只发送服务器提供的编号", setOf("Tmdb", "Imdb"), ids.keys().asSequence().toSet())
            assertEquals("85718", ids.getString("Tmdb")); assertEquals("nm1395602", ids.getString("Imdb"))
        }
    }

    @Test fun failedNewIdentificationQueryCannotApplyCandidatesFromThePreviousQuery() {
        Fixture().use { fixture ->
            val changed = AtomicInteger()
            val api = JellyfinApi(); val session = fixture.session()
            fixture.candidateName.set("Synthetic match A")
            compose.setContent { ManagementContent {
                MediaIdentificationScreen(MediaItem("item", "Query A", "Movie"), session, api, {}, { changed.incrementAndGet() })
            } }
            search(fixture)
            scrollIdentification("identification-result")
            compose.onNodeWithText("Synthetic match A").assertIsDisplayed()

            fixture.failSearch.set(true)
            scrollIdentification("identification-name")
            compose.onNodeWithTag("identification-name").performTextReplacement("Query B")
            compose.onNodeWithTag("identification-show-results").assertDoesNotExist()
            scrollIdentification("identification-search")
            compose.onNodeWithTag("identification-search").performClick()
            awaitIdentification(fixture, "new-query-failed") { fixture.searches.get() == 2 && exists("identification-error") }
            assertEquals("Query B", fixture.lastQuery.get())
            // 旧候选若保留展示也不能再可点击；允许实现直接清空旧结果。
            runCatching { scrollIdentification("identification-result") }
            compose.onAllNodes(hasTestTag("identification-result") and isEnabled()).assertCountEquals(0)
            assertEquals("新查询失败不能把上一查询的候选写入媒体", 0, fixture.applies.get())
            assertEquals(0, changed.get())

            fixture.failSearch.set(false)
            fixture.candidateName.set("Synthetic match B")
            scrollIdentification("identification-search")
            compose.onNodeWithTag("identification-search").assertIsEnabled().performClick()
            awaitIdentification(fixture, "new-query-recovered") {
                fixture.searches.get() == 3 && runCatching {
                    scrollIdentification("identification-result")
                    compose.onNodeWithText("Synthetic match B").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            scrollIdentification("identification-result")
            compose.onNodeWithText("Synthetic match B").assertIsDisplayed()
            compose.onAllNodesWithText("Synthetic match A").assertCountEquals(0)
            compose.onNodeWithTag("identification-result").performClick()
            compose.onNodeWithText("应用识别结果？").assertIsDisplayed()
            fixture.allowApply.countDown()
            compose.onNodeWithTag("identification-confirm").performClick()
            awaitIdentification(fixture, "only-new-candidate-applied") { changed.get() == 1 }
            assertEquals(1, fixture.applies.get())
            assertEquals("Synthetic match B", fixture.appliedName.get())
        }
    }

    @Test fun acceptedTaskWithFailedReloadRetriesOnlyReadsAndRestartAlwaysConfirms() {
        Fixture().use { fixture ->
            val shown = mutableStateOf(true); val api = JellyfinApi(); val session = fixture.session()
            compose.setContent { ManagementContent {
                if (shown.value) ServerAdministrationScreen(session, api, onBack = { shown.value = false })
            } }
            waitForTask(fixture)
            scrollAdministration(hasTestTag("server-task-task"))
            compose.onNodeWithTag("server-task-task").performClick()
            compose.onNodeWithText("运行计划任务？").assertIsDisplayed()
            compose.onNodeWithText("返回", substring = false).performClick()
            compose.waitUntil(WAIT_MS) { !exists("server-task-confirm") }
            VerificationDisplay.awaitWindowReady(compose, "task-dialog-dismissed", compose.onNodeWithTag("server-task-task"))
            assertEquals(0, fixture.runs.get())
            fixture.failTaskReads.set(true)
            compose.onNodeWithTag("server-task-task").performClick()
            awaitDialog("server-task-confirm", "task-dialog-reopened")
            compose.onNodeWithTag("server-task-confirm").performClick()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.runs.get() == 1 && exists("server-administration-error") }
            assertEquals(0, fixture.cancels.get())
            compose.onNodeWithText("操作已接受，但重新读取失败。请刷新状态，不要重复提交。").assertIsDisplayed()
            val reads = fixture.taskReads.get()
            compose.onNodeWithTag("server-administration-list").performScrollToIndex(0)
            pullPageToRefresh(compose, stage = "accepted-task-read-fails")
            compose.waitUntil(WAIT_MS) { fixture.taskReads.get() > reads }
            assertEquals(1, fixture.runs.get())
            fixture.failTaskReads.set(false)
            compose.waitUntil(WAIT_MS) { exists("server-administration-error") }
            compose.onNodeWithTag("server-administration-list").performScrollToIndex(0)
            pullPageToRefresh(compose, stage = "accepted-task-read-recovers")
            compose.waitUntil(WAIT_MS) { !exists("server-administration-error") }
            scrollAdministration(hasTestTag("server-task-task"))
            compose.onNodeWithTag("server-task-task").performClick()
            compose.onNodeWithText("取消正在运行的任务？").assertIsDisplayed()
            awaitDialog("server-task-confirm", "task-cancel-dialog")
            compose.onNodeWithTag("server-task-confirm").performClick()
            compose.waitUntil(WAIT_MS) { fixture.cancels.get() == 1 }
            scrollAdministration(hasTestTag("server-restart"))
            compose.waitUntil(WAIT_MS) {
                runCatching { compose.onNodeWithTag("server-restart").assertIsEnabled(); true }.getOrDefault(false)
            }
            compose.onNodeWithTag("server-restart").performClick()
            compose.onNodeWithText("重启此 Jellyfin 服务器？").assertIsDisplayed()
            compose.onNodeWithText("取消", substring = false).performClick()
            compose.waitUntil(WAIT_MS) { !exists("server-restart-confirm") }
            VerificationDisplay.awaitWindowReady(compose, "restart-dialog-dismissed", compose.onNodeWithTag("server-restart"))
            assertEquals(0, fixture.restarts.get())
            compose.onNodeWithTag("server-restart").performClick()
            awaitDialog("server-restart-confirm", "restart-dialog-reopened")
            compose.onNodeWithTag("server-restart-confirm").performClick()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.restarts.get() == 1 && !exists("server-restart-confirm") }
            compose.onNodeWithTag("server-restart").assertIsNotEnabled()
            assertEquals(1, fixture.runs.get()); assertEquals(1, fixture.cancels.get())
            compose.runOnIdle { shown.value = false }
        }
    }

    @Test fun lateTaskPollCannotOverwriteANewerAcceptedRun() {
        assertAcceptedTaskSurvivesAnOlderPoll(failedReload = false)
    }

    @Test fun lateTaskPollCannotUndoAcceptedStateWhenItsReloadFails() {
        assertAcceptedTaskSurvivesAnOlderPoll(failedReload = true)
    }

    private fun assertAcceptedTaskSurvivesAnOlderPoll(failedReload: Boolean) {
        Fixture().use { fixture ->
            fixture.backgroundTaskRunning.set(true)
            val shown = mutableStateOf(true); val api = JellyfinApi(); val session = fixture.session()
            compose.setContent { ManagementContent {
                if (shown.value) ServerAdministrationScreen(session, api, onBack = { shown.value = false })
            } }
            waitForTask(fixture)
            fixture.holdNextTaskRead.set(true)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.taskPollStarted.count == 0L }
            fixture.failTaskReads.set(failedReload)
            scrollAdministration(hasTestTag("server-task-task"))
            compose.onNodeWithTag("server-task-task").performClick()
            awaitDialog("server-task-confirm", "task-run-with-poll-in-flight")
            compose.onNodeWithTag("server-task-confirm").performClick()
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                fixture.runs.get() == 1 && fixture.taskReads.get() >= 3 &&
                    runCatching {
                        compose.onNodeWithTag("server-task-task").assertIsEnabled()
                            .assertContentDescriptionEquals("取消Synthetic scan")
                        true
                    }.getOrDefault(false) && (!failedReload || exists("server-administration-error"))
            }
            fixture.releaseTaskPoll.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.taskPollDelivered.count == 0L }
            // 响应已发出后观察一段 UI 帧，防止在旧响应尚未解析时就提前通过。
            val until = SystemClock.elapsedRealtime() + 750
            while (SystemClock.elapsedRealtime() < until) {
                compose.waitForIdle(); fixture.assertHealthy()
                compose.onNodeWithTag("server-task-task").assertContentDescriptionEquals("取消Synthetic scan")
                if (failedReload) compose.onNodeWithText("操作已接受，但重新读取失败。请刷新状态，不要重复提交。").assertIsDisplayed()
                assertEquals("旧读取不能自动重放运行", 1, fixture.runs.get())
                assertEquals("旧读取不能自动发起取消", 0, fixture.cancels.get())
                SystemClock.sleep(25)
            }
            compose.runOnIdle { shown.value = false }
        }
    }

    @Test fun activityPaginationAndLogReadingUseRealReadRequests() {
        Fixture().use { fixture ->
            val shown = mutableStateOf(true); val api = JellyfinApi(); val session = fixture.session()
            compose.setContent { ManagementContent {
                if (shown.value) ServerAdministrationScreen(session, api, onBack = { shown.value = false })
            } }
            waitForTask(fixture)
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).performClick()
            scrollActivityEnd(29)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityAppends.get() == 1 && runCatching {
                scrollAdministration(hasText("Synthetic event 30", substring = false)); true
            }.getOrDefault(false) }
            compose.onNodeWithText("Synthetic event 30", substring = false).assertIsDisplayed()
            compose.onNodeWithTag("server-activity-more").assertDoesNotExist()
            val terminalRequests = fixture.activityRequests.size
            repeat(3) { compose.onNodeWithTag("server-administration-list").performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, terminalRequests)
            assertEquals(listOf(0, 30), fixture.activityRequests.map { it.first })
            assertEquals(0, fixture.runs.get()); assertEquals(0, fixture.restarts.get())
            compose.onNodeWithTag("server-administration-back").performClick()
            scrollAdministration(hasTestTag("server-logs-open"))
            compose.onNodeWithTag("server-logs-open").performClick()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); exists("server-log-file") }
            compose.onNodeWithTag("server-log-file").performClick()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); compose.onAllNodesWithText("synthetic safe log").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("synthetic safe log").assertIsDisplayed()
            compose.onAllNodesWithText(TOKEN, substring = true).assertCountEquals(0)
            assertEquals(1, fixture.logReads.get())
            compose.runOnIdle { shown.value = false }
        }
    }

    @Test fun activityAppendFailureStopsAutomaticRetriesAndRefreshKeepsTheReadRange() {
        Fixture(activityCount = 61).use { fixture ->
            val shown = mutableStateOf(true)
            val session = fixture.session(); val api = JellyfinApi()
            compose.setContent { ManagementContent {
                if (shown.value) ServerAdministrationScreen(session, api, onBack = { shown.value = false })
            } }
            waitForTask(fixture)
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).performClick()
            fixture.failActivityAt.set(60)
            scrollActivityEnd(29)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRequests.any { it.first == 30 } }
            scrollActivityEnd(59)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRequests.any { it.first == 60 } }
            compose.onNodeWithText("Synthetic event 59", substring = false).assertIsDisplayed()
            repeat(3) { compose.onNodeWithTag("server-administration-list").performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, 3)
            assertEquals(listOf(0, 30, 60), fixture.activityRequests.map { it.first })

            fixture.failActivityAt.set(-1)
            fixture.holdActivityRefresh.set(true)
            fixture.holdActivityRemainder.set(true)
            val list = compose.onNodeWithTag("server-administration-list")
            list.performScrollToIndex(0)
            pullPageToRefresh(compose, page = list, stage = "activity-restore-read-range")
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRefreshStarted.count == 0L }
            scrollAdministration(hasText("Synthetic event 59", substring = false))
            compose.onNodeWithText("Synthetic event 59", substring = false).assertIsDisplayed()
            fixture.releaseActivityRefresh.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRemainderStarted.count == 0L ||
                compose.onAllNodesWithText("Refreshed synthetic event 59", substring = false).fetchSemanticsNodes().isNotEmpty() }
            if (fixture.activityRemainderStarted.count == 0L) {
                scrollAdministration(hasText("Synthetic event 59", substring = false) or hasText("Refreshed synthetic event 59", substring = false))
            }
            fixture.releaseActivityRemainder.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); runCatching {
                scrollAdministration(hasText("Refreshed synthetic event 59", substring = false))
                compose.onNodeWithText("Refreshed synthetic event 59", substring = false).assertIsDisplayed()
                true
            }.getOrDefault(false) }
            scrollActivityEnd(59, refreshed = true)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); runCatching {
                scrollAdministration(hasText("Refreshed synthetic event 60", substring = false)); true
            }.getOrDefault(false) }
            compose.onNodeWithText("Refreshed synthetic event 60", substring = false).assertIsDisplayed()
            val terminalRequests = fixture.activityRequests.size
            repeat(3) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, terminalRequests)
            compose.onNodeWithTag("server-activity-more").assertDoesNotExist()
            assertEquals(0, fixture.runs.get()); assertEquals(0, fixture.cancels.get()); assertEquals(0, fixture.restarts.get())
            compose.runOnIdle { shown.value = false }
        }
    }

    @Test fun activityPaginationUsesRawOffsetsWhenPagesOverlap() {
        Fixture(activityCount = 61).use { fixture ->
            fixture.repeatActivityBoundary.set(true)
            openActivities(fixture)
            scrollActivityEnd(29)
            scrollActivityEnd(59)
            scrollActivityEnd(60)
            assertEquals("跨页去重不能将下一页从 60 倒退到 59", listOf(0, 30, 60), fixture.activityRequests.map { it.first })
            assertActivityRequestsStayAt(fixture, 3)
        }
    }

    @Test fun anEmptyActivityAppendStopsWithoutRetryingOrReplacingTheExistingRows() {
        Fixture(activityCount = 61).use { fixture ->
            fixture.emptyActivityAt.set(30)
            openActivities(fixture)
            scrollActivityEnd(29)
            compose.waitUntil(WAIT_MS) { fixture.activityRequests.size == 2 }
            compose.onNodeWithText("Synthetic event 29", substring = false).assertIsDisplayed()
            repeat(3) { compose.onNodeWithTag("server-administration-list").performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, 2)
            compose.onNodeWithTag("server-administration-error").assertDoesNotExist()
            assertEquals(listOf(0, 30), fixture.activityRequests.map { it.first })
        }
    }

    @Test fun activityRangeRefreshKeepsItsSnapshotWhenTheSecondFragmentFails() = exerciseActivityRangeFailure("http")

    @Test fun activityRangeRefreshKeepsItsSnapshotWhenTotalsChangeBetweenFragments() = exerciseActivityRangeFailure("total")

    @Test fun activityRangeRefreshKeepsItsSnapshotWhenAnEmptyFragmentDoesNotCoverTheReadRange() = exerciseActivityRangeFailure("empty")

    @Test fun activityRangeRefreshAcceptsAConsistentlySmallerResult() {
        Fixture(activityCount = 61).use { fixture ->
            openActivities(fixture)
            fixture.failActivityAt.set(60)
            scrollActivityEnd(29)
            scrollActivityEnd(59)
            compose.waitUntil(WAIT_MS) { fixture.activityRequests.size == 3 }
            assertActivityRequestsStayAt(fixture, 3)

            fixture.failActivityAt.set(-1)
            fixture.refreshedActivityTotal.set(45)
            val list = compose.onNodeWithTag("server-administration-list")
            list.performScrollToIndex(0)
            pullPageToRefresh(compose, page = list, stage = "activity-range-shrinks")
            scrollActivityEnd(44, refreshed = true)
            assertActivityRequestsStayAt(fixture, 5)
            compose.onNodeWithTag("server-administration-error").assertDoesNotExist()
            assertEquals(listOf(0, 30, 60, 0, 30), fixture.activityRequests.map { it.first })
        }
    }

    private fun exerciseActivityRangeFailure(mode: String) {
        Fixture(activityCount = 61).use { fixture ->
            openActivities(fixture)
            fixture.failActivityAt.set(60)
            scrollActivityEnd(29)
            scrollActivityEnd(59)
            compose.waitUntil(WAIT_MS) { fixture.activityRequests.size == 3 }
            assertActivityRequestsStayAt(fixture, 3)

            fixture.failActivityAt.set(if (mode == "http") 30 else -1)
            if (mode == "total") fixture.refreshedActivityRemainderTotal.set(62)
            if (mode == "empty") fixture.emptyActivityAt.set(30)
            val list = compose.onNodeWithTag("server-administration-list")
            list.performScrollToIndex(0)
            pullPageToRefresh(compose, page = list, stage = "activity-range-failure-$mode")
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRequests.size == 5 }
            scrollAdministration(hasText("Synthetic event 59", substring = false))
            compose.onNodeWithText("Synthetic event 59", substring = false).assertIsDisplayed()
            scrollAdministration(hasText("Synthetic event 0", substring = false))
            compose.onNodeWithText("Synthetic event 0", substring = false).assertIsDisplayed()
            compose.onAllNodesWithText("Refreshed synthetic event 0", substring = false).assertCountEquals(0)
            compose.onNodeWithTag("server-administration-error").assertIsDisplayed()
            repeat(2) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, 5)
            assertEquals(listOf(0, 30, 60, 0, 30), fixture.activityRequests.map { it.first })
        }
    }

    private fun openActivities(fixture: Fixture) {
        val session = fixture.session(); val api = JellyfinApi()
        compose.setContent { ManagementContent { ServerAdministrationScreen(session, api, {}) } }
        waitForTask(fixture)
        scrollAdministration(hasText("更多", substring = false))
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            compose.onAllNodes(hasText("更多", substring = false) and isEnabled()).fetchSemanticsNodes().size == 1
        }
        val more = compose.onNodeWithText("更多", substring = false)
        VerificationDisplay.awaitWindowReady(compose, "activity-open", more)
        more.performClick()
    }

    @Test fun nonAdministratorCannotReadAdministrativeData() {
        Fixture().use { fixture ->
            val api = JellyfinApi(); val session = fixture.session().copy(isAdministrator = false)
            compose.setContent { ManagementContent { ServerAdministrationScreen(session, api, {}) } }
            compose.onNodeWithText("需要管理员权限").assertIsDisplayed()
            compose.waitForIdle()
            assertEquals(0, fixture.total.get())
        }
    }

    private fun search(fixture: Fixture) {
        scrollIdentification("identification-search")
        compose.onNodeWithTag("identification-search").performClick()
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            fixture.searches.get() > 0 && runCatching { scrollIdentification("identification-result"); true }.getOrDefault(false)
        }
    }
    private fun waitForTask(fixture: Fixture) {
        compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.taskReads.get() > 0 && exists("server-task-task") }
        compose.waitUntil(WAIT_MS) { runCatching { compose.onNodeWithTag("server-task-task").assertIsEnabled(); true }.getOrDefault(false) }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitDialog(tag: String, stage: String) {
        compose.waitUntil(WAIT_MS) { exists(tag) }
        VerificationDisplay.awaitWindowReady(compose, stage, compose.onNodeWithTag(tag))
    }
    private fun awaitIdentification(fixture: Fixture, stage: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); condition() }
        } catch (failure: Throwable) {
            val searchEnabled = runCatching { compose.onNodeWithTag("identification-search").assertIsEnabled(); true }.getOrDefault(false)
            val focused = listOf("identification-name", "identification-year", "identification-id/Tmdb", "identification-id/Imdb").filter { tag ->
                compose.onAllNodes(hasTestTag(tag) and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            throw AssertionError("识别回归 stage=$stage searches=${fixture.searches.get()} searchEnabled=$searchEnabled " +
                "errorVisible=${exists("identification-error")} resultComposed=${exists("identification-result")} focused=$focused", failure)
        }
    }
    /** 表单字段在搜索条件页，候选在结果页；需要表单字段时先从结果页返回。 */
    private fun scrollIdentification(tag: String) {
        val formTag = tag in setOf("identification-name", "identification-year", "identification-search") || tag.startsWith("identification-id/")
        if (formTag && exists("identification-results")) { compose.onNodeWithTag("identification-back").performClick(); compose.waitForIdle() }
        val list = if (exists("identification-results")) "identification-results" else "identification-list"
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
    }
    private fun scrollAdministration(matcher: SemanticsMatcher) = compose.onNodeWithTag("server-administration-list").performScrollToNode(matcher)
    private fun scrollActivityEnd(index: Int, refreshed: Boolean = false) {
        compose.waitUntil(WAIT_MS) { runCatching {
            scrollAdministration(hasText(if (refreshed) "Refreshed synthetic event $index" else "Synthetic event $index", substring = false))
            true
        }.getOrDefault(false) }
        val list = compose.onNodeWithTag("server-administration-list")
        VerificationDisplay.awaitWindowReady(compose, "activity-scroll-$index", list)
        list.performTouchInput { swipeUp() }
        compose.waitForIdle()
    }
    private fun assertActivityRequestsStayAt(fixture: Fixture, expected: Int) {
        val until = SystemClock.elapsedRealtime() + 750
        while (SystemClock.elapsedRealtime() < until) {
            compose.waitForIdle(); fixture.assertHealthy()
            assertEquals("活动追加失败或已到总数时不能重复请求：${fixture.activityRequests}", expected, fixture.activityRequests.size)
            SystemClock.sleep(25)
        }
    }

    private class Fixture(private val activityCount: Int = 31) : Closeable {
        val total = AtomicInteger(); val searches = AtomicInteger(); val applies = AtomicInteger()
        val taskReads = AtomicInteger(); val runs = AtomicInteger(); val cancels = AtomicInteger(); val restarts = AtomicInteger()
        val activityAppends = AtomicInteger(); val logReads = AtomicInteger()
        val activityRequests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val failActivityAt = AtomicInteger(-1)
        val emptyActivityAt = AtomicInteger(-1)
        val repeatActivityBoundary = AtomicBoolean()
        val refreshedActivityTotal = AtomicInteger(-1); val refreshedActivityRemainderTotal = AtomicInteger(-1)
        val holdActivityRefresh = AtomicBoolean(); val holdActivityRemainder = AtomicBoolean()
        val activityRefreshStarted = CountDownLatch(1); val releaseActivityRefresh = CountDownLatch(1)
        val activityRemainderStarted = CountDownLatch(1); val releaseActivityRemainder = CountDownLatch(1)
        @Volatile private var refreshedActivity = false
        val failSearch = AtomicBoolean(); val failTaskReads = AtomicBoolean(); val running = AtomicBoolean()
        val backgroundTaskRunning = AtomicBoolean(); val holdNextTaskRead = AtomicBoolean()
        val taskPollStarted = CountDownLatch(1); val releaseTaskPoll = CountDownLatch(1); val taskPollDelivered = CountDownLatch(1)
        val allowApply = CountDownLatch(1); val replaceImages = AtomicReference<String?>()
        val candidateName = AtomicReference("Synthetic match"); val appliedName = AtomicReference<String?>(); val lastQuery = AtomicReference<String?>()
        val lastSearchInfo = AtomicReference<JSONObject?>()
        private val failure = AtomicReference<Throwable?>()
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var closed = false
        val baseUrl = "http://127.0.0.1:${server.localPort}/proxy"
        init { executor.execute {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                executor.execute {
                    try { serve(socket) } catch (error: Throwable) { if (!closed) failure.compareAndSet(null, error) }
                    finally { sockets -= socket; socket.close() }
                }
            }
        } }
        fun session() = Session(ServerConfig(baseUrl, true), TOKEN, "synthetic-user", "Synthetic", "synthetic-device", isAdministrator = true)
        fun assertHealthy() { failure.get()?.let { throw AssertionError("合成管理服务断言失败", it) } }

        @Suppress("DEPRECATION")
        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream())
            val start = input.readLine() ?: return
            val parts = start.split(' '); val method = parts[0]; val uri = java.net.URI(parts[1]); val path = uri.path
            val headers = mutableMapOf<String, String>()
            while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
            val bytes = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0); input.readFully(bytes)
            assertTrue(headers["authorization"].orEmpty().contains(TOKEN)); total.incrementAndGet()
            var code = 200; var body = ""; var contentType = "application/json"; var heldTaskPoll = false
            when {
                path.startsWith("/proxy/Items/RemoteSearch/Apply/") -> {
                    assertEquals("POST", method); applies.incrementAndGet()
                    replaceImages.set(uri.query.substringAfter("replaceAllImages="))
                    val candidate = JSONObject(bytes.decodeToString())
                    appliedName.set(candidate.getString("Name"))
                    assertEquals("42", candidate.getJSONObject("ProviderIds").getString("Tmdb"))
                    check(allowApply.await(15, TimeUnit.SECONDS)); code = 204
                }
                path.endsWith("/ExternalIdInfos") -> {
                    assertEquals("GET", method)
                    body = """[{"Name":"TheMovieDb","Key":"Tmdb"},{"Name":"IMDb","Key":"Imdb"}]"""
                }
                path.startsWith("/proxy/Items/RemoteSearch/") -> {
                    assertEquals("POST", method); searches.incrementAndGet()
                    lastQuery.set(JSONObject(bytes.decodeToString()).getJSONObject("SearchInfo").getString("Name"))
                    lastSearchInfo.set(JSONObject(bytes.decodeToString()).getJSONObject("SearchInfo"))
                    if (failSearch.get()) { code = 503; body = "{}" } else body = JSONArray().put(JSONObject()
                        .put("Name", candidateName.get()).put("ProductionYear", 2024).put("ProviderIds", JSONObject().put("Tmdb", "42"))
                        .put("ImageUrl", "https://image.invalid/poster.jpg")).toString()
                }
                path == "/proxy/System/Info" -> body = """{"ServerName":"Synthetic Jellyfin","Version":"12.3.0","CanSelfRestart":true}"""
                path == "/proxy/ScheduledTasks" -> {
                    taskReads.incrementAndGet()
                    if (failTaskReads.get() && runs.get() > 0) { code = 503; body = "{}" }
                    else {
                        val result = JSONArray("""[{"Id":"task","Name":"Synthetic scan","Key":"RefreshLibrary","State":"${if (running.get()) "Running" else "Idle"}"}]""")
                        if (backgroundTaskRunning.get()) result.put(JSONObject().put("Id", "background").put("Name", "Synthetic background task")
                            .put("Key", "ExtractChapterImages").put("State", "Running"))
                        body = result.toString()
                    }
                    if (holdNextTaskRead.compareAndSet(true, false)) {
                        heldTaskPoll = true; taskPollStarted.countDown()
                        check(releaseTaskPoll.await(15, TimeUnit.SECONDS))
                    }
                }
                path == "/proxy/ScheduledTasks/Running/task" -> {
                    if (method == "POST") { runs.incrementAndGet(); running.set(true) }
                    else { assertEquals("DELETE", method); cancels.incrementAndGet(); running.set(false) }; code = 204
                }
                path == "/proxy/System/ActivityLog/Entries" -> {
                    val query = uri.query.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                    val startIndex = query["startIndex"]!!.toInt(); val limit = query["limit"]!!.toInt()
                    activityRequests += startIndex to limit
                    if (startIndex > 0) activityAppends.incrementAndGet()
                    if (startIndex == 0 && activityRequests.count { it.first == 0 } > 1) {
                        refreshedActivity = true
                        if (holdActivityRefresh.get()) { activityRefreshStarted.countDown(); check(releaseActivityRefresh.await(15, TimeUnit.SECONDS)) }
                    } else if (startIndex > 0 && refreshedActivity && holdActivityRemainder.get()) {
                        activityRemainderStarted.countDown(); check(releaseActivityRemainder.await(15, TimeUnit.SECONDS))
                    }
                    if (startIndex == failActivityAt.get()) { code = 503; body = "{}" } else {
                        val items = JSONArray()
                        val currentTotal = if (refreshedActivity && startIndex > 0 && refreshedActivityRemainderTotal.get() >= 0) refreshedActivityRemainderTotal.get()
                            else if (refreshedActivity && refreshedActivityTotal.get() >= 0) refreshedActivityTotal.get() else activityCount
                        if (startIndex != emptyActivityAt.get()) for (index in startIndex until minOf(startIndex + limit, currentTotal)) {
                            val sourceIndex = if (repeatActivityBoundary.get() && startIndex == 30 && index == startIndex) index - 1 else index
                            items.put(JSONObject().put("Id", sourceIndex + 1)
                                .put("Name", if (refreshedActivity) "Refreshed synthetic event $sourceIndex" else "Synthetic event $sourceIndex").put("Date", "2026-10-24T00:00:00Z"))
                        }
                        body = JSONObject().put("Items", items).put("TotalRecordCount", currentTotal).put("StartIndex", startIndex).toString()
                    }
                }
                path == "/proxy/System/Logs" -> body = """[{"Name":"synthetic.log","Size":100}]"""
                path == "/proxy/System/Logs/Log" -> { logReads.incrementAndGet(); contentType = "text/plain"; body = "synthetic safe log\nAuthorization: Bearer $TOKEN\n" }
                path == "/proxy/System/Restart" -> { assertEquals("POST", method); restarts.incrementAndGet(); code = 204 }
                else -> error("合成服务收到未知路径")
            }
            val payload = body.toByteArray()
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $code ${if (code < 400) "OK" else "Unavailable"}\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(payload); output.flush()
            if (heldTaskPoll) taskPollDelivered.countDown()
        }
        override fun close() {
            closed = true; allowApply.countDown(); releaseActivityRefresh.countDown(); releaseActivityRemainder.countDown(); releaseTaskPoll.countDown(); server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }; executor.shutdownNow()
            assertHealthy()
        }
    }
    private companion object { const val WAIT_MS = 10_000L; const val TOKEN = "management-ui-synthetic-token" }
}

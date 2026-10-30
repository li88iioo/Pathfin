package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.pathfin.android.ui.ServerAdministrationState
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import coil3.ImageLoader
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicLong
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
            compose.onNodeWithTag("identification-result").performVerificationClick(compose)
            compose.onNodeWithText("应用识别结果？").assertVerificationDisplayed(compose)
            compose.onNodeWithText("取消", substring = false).performVerificationClick(compose)
            assertEquals(0, fixture.applies.get())
            compose.onNodeWithTag("identification-result").performVerificationClick(compose)
            compose.onNodeWithTag("identification-replace-images").assertIsOff()
            compose.onNodeWithTag("identification-confirm").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.applies.get() == 1 }
            compose.onNodeWithTag("identification-back").assertIsNotEnabled()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(0, backs.get()); assertEquals(0, changed.get())
            assertEquals("false", fixture.replaceImages.get())
            fixture.allowApply.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); changed.get() == 1 }
            scrollIdentification("identification-success")
            compose.onNodeWithTag("identification-success").assertVerificationDisplayed(compose)
            assertEquals(1, fixture.applies.get())
            // 结果页的返回先回到搜索条件，再次返回才离开识别页。
            compose.onNodeWithTag("identification-back").assertIsEnabled().performVerificationClick(compose)
            compose.onNodeWithTag("identification-list").assertVerificationDisplayed(compose)
            assertEquals(0, backs.get())
            compose.onNodeWithTag("identification-back").performVerificationClick(compose)
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
            compose.onNodeWithTag("identification-search").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.searches.get() == 1 && exists("identification-error") }
            compose.onNodeWithTag("identification-search").assertIsEnabled()
            fixture.failSearch.set(false)
            compose.onNodeWithTag("identification-list").performScrollToIndex(0)
            val page = compose.onNodeWithTag("refreshable-page")
            // 列表的内容左右各留 16dp；8dp 仍在可滚动列表内，避开文本框的选择/焦点手势。
            val gutterFraction = (8f * compose.activity.resources.displayMetrics.density) / page.readVerificationNode(compose) { it.boundsInRoot }.width
            pullPageToRefresh(compose, page, "identification-read-recovers", horizontalFraction = gutterFraction)
            awaitIdentification(fixture, "second-http-request") { fixture.searches.get() == 2 }
            var lastScrollFailure: Throwable? = null
            try {
                awaitIdentification(fixture, "result-visible") {
                    runCatching {
                        scrollIdentification("identification-result")
                        compose.onNodeWithTag("identification-result").assertVerificationDisplayed(compose)
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
            compose.onNodeWithText("识别人物").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("identification-year").assertDoesNotExist()
            scrollIdentification("identification-id/Tmdb")
            compose.onNodeWithTag("identification-id/Tmdb").assertTextContains("85718")
            scrollIdentification("identification-id/Imdb")
            compose.onNodeWithTag("identification-id/Imdb").performTextReplacement("nm1395602")
            search(fixture)
            compose.onNodeWithText("选择匹配结果").assertVerificationDisplayed(compose)
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
            compose.onNodeWithText("Synthetic match A").assertVerificationDisplayed(compose)

            fixture.failSearch.set(true)
            scrollIdentification("identification-name")
            compose.onNodeWithTag("identification-name").performTextReplacement("Query B")
            compose.onNodeWithTag("identification-show-results").assertDoesNotExist()
            scrollIdentification("identification-search")
            compose.onNodeWithTag("identification-search").performVerificationClick(compose)
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
            compose.onNodeWithTag("identification-search").assertIsEnabled().performVerificationClick(compose)
            awaitIdentification(fixture, "new-query-recovered") {
                fixture.searches.get() == 3 && runCatching {
                    scrollIdentification("identification-result")
                    compose.onNodeWithText("Synthetic match B").assertVerificationDisplayed(compose)
                    true
                }.getOrDefault(false)
            }
            scrollIdentification("identification-result")
            compose.onNodeWithText("Synthetic match B").assertVerificationDisplayed(compose)
            compose.onAllNodesWithText("Synthetic match A").assertCountEquals(0)
            compose.onNodeWithTag("identification-result").performVerificationClick(compose)
            compose.onNodeWithText("应用识别结果？").assertVerificationDisplayed(compose)
            fixture.allowApply.countDown()
            compose.onNodeWithTag("identification-confirm").performVerificationClick(compose)
            awaitIdentification(fixture, "only-new-candidate-applied") { changed.get() == 1 }
            assertEquals(1, fixture.applies.get())
            assertEquals("Synthetic match B", fixture.appliedName.get())
        }
    }

    @Test fun acceptedTaskWithFailedReloadRetriesOnlyReadsAndRestartAlwaysConfirms() {
        Fixture().use { fixture ->
            val host = showAdministration(fixture)
            waitForTask(fixture)
            scrollAdministration(hasTestTag("server-task-task"))
            compose.onNodeWithTag("server-task-task").performVerificationClick(compose)
            compose.onNodeWithText("运行计划任务？").assertVerificationDisplayed(compose)
            compose.onNodeWithText("返回", substring = false).performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { !exists("server-task-confirm") }
            VerificationDisplay.awaitWindowReady(compose, "task-dialog-dismissed", compose.onNodeWithTag("server-task-task"))
            assertEquals(0, fixture.runs.get())
            fixture.failTaskReads.set(true)
            compose.onNodeWithTag("server-task-task").performVerificationClick(compose)
            awaitDialog("server-task-confirm", "task-dialog-reopened")
            compose.onNodeWithTag("server-task-confirm").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.runs.get() == 1 && exists("server-administration-error") }
            assertEquals(0, fixture.cancels.get())
            compose.onNodeWithText("操作已接受，但重新读取失败。请刷新状态，不要重复提交。").assertVerificationDisplayed(compose)
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
            compose.onNodeWithTag("server-task-task").performVerificationClick(compose)
            compose.onNodeWithText("取消正在运行的任务？").assertVerificationDisplayed(compose)
            awaitDialog("server-task-confirm", "task-cancel-dialog")
            compose.onNodeWithTag("server-task-confirm").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.cancels.get() == 1 }
            scrollAdministration(hasTestTag("server-restart"))
            compose.waitUntil(WAIT_MS) {
                runCatching { compose.onNodeWithTag("server-restart").assertIsEnabled(); true }.getOrDefault(false)
            }
            compose.onNodeWithTag("server-restart").performVerificationClick(compose)
            compose.onNodeWithText("重启此 Jellyfin 服务器？").assertVerificationDisplayed(compose)
            compose.onNodeWithText("取消", substring = false).performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { !exists("server-restart-confirm") }
            VerificationDisplay.awaitWindowReady(compose, "restart-dialog-dismissed", compose.onNodeWithTag("server-restart"))
            assertEquals(0, fixture.restarts.get())
            compose.onNodeWithTag("server-restart").performVerificationClick(compose)
            awaitDialog("server-restart-confirm", "restart-dialog-reopened")
            compose.onNodeWithTag("server-restart-confirm").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.restarts.get() == 1 && !exists("server-restart-confirm") }
            waitForAdministrationIdle(host, fixture)
            compose.onNodeWithTag("server-restart").assertIsNotEnabled()
            assertEquals(1, fixture.runs.get()); assertEquals(1, fixture.cancels.get())
            val requests = fixture.total.get()
            reenterAdministration(host)
            compose.onNodeWithTag("server-restart").assertIsNotEnabled()
            assertAdministrationRequestsStayAt(fixture, requests)
            assertEquals("重进不能再次提交重启", 1, fixture.restarts.get())
            compose.runOnIdle { host.shown.value = false }
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
            val host = showAdministration(fixture)
            waitForTask(fixture)
            fixture.holdNextTaskRead.set(true)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.taskPollStarted.count == 0L }
            fixture.failTaskReads.set(failedReload)
            scrollAdministration(hasTestTag("server-task-task"))
            compose.onNodeWithTag("server-task-task").performVerificationClick(compose)
            awaitDialog("server-task-confirm", "task-run-with-poll-in-flight")
            compose.onNodeWithTag("server-task-confirm").performVerificationClick(compose)
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
                if (failedReload) compose.onNodeWithText("操作已接受，但重新读取失败。请刷新状态，不要重复提交。").assertVerificationDisplayed(compose)
                assertEquals("旧读取不能自动重放运行", 1, fixture.runs.get())
                assertEquals("旧读取不能自动发起取消", 0, fixture.cancels.get())
                SystemClock.sleep(25)
            }
            compose.runOnIdle { host.shown.value = false }
        }
    }

    @Test fun activityPaginationAndLogReadingUseRealReadRequests() {
        Fixture().use { fixture ->
            val host = showAdministration(fixture)
            waitForTask(fixture)
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).performVerificationClick(compose)
            scrollActivityEnd(29)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityAppends.get() == 1 && runCatching {
                scrollAdministration(hasText("Synthetic event 30", substring = false)); true
            }.getOrDefault(false) }
            compose.onNodeWithText("Synthetic event 30", substring = false).assertVerificationDisplayed(compose)
            compose.onNodeWithTag("server-activity-more").assertDoesNotExist()
            val terminalRequests = fixture.activityRequests.size
            repeat(3) { compose.onNodeWithTag("server-administration-list").performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, terminalRequests)
            assertEquals(listOf(0, 30), fixture.activityRequests.map { it.first })
            assertEquals(0, fixture.runs.get()); assertEquals(0, fixture.restarts.get())
            compose.onNodeWithTag("server-administration-back").performVerificationClick(compose)
            scrollAdministration(hasTestTag("server-logs-open"))
            compose.onNodeWithTag("server-logs-open").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); exists("server-log-file") }
            compose.onNodeWithTag("server-log-file").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); compose.onAllNodesWithText("synthetic safe log").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("synthetic safe log").assertVerificationDisplayed(compose)
            compose.onAllNodesWithText(TOKEN, substring = true).assertCountEquals(0)
            assertEquals(1, fixture.logReads.get())
            compose.runOnIdle { host.shown.value = false }
        }
    }

    @Test fun activityAppendFailureStopsAutomaticRetriesAndRefreshKeepsTheReadRange() {
        Fixture(activityCount = 61).use { fixture ->
            val host = showAdministration(fixture)
            waitForTask(fixture)
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).performVerificationClick(compose)
            fixture.failActivityAt.set(60)
            scrollActivityEnd(29)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRequests.any { it.first == 30 } }
            scrollActivityEnd(59)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRequests.any { it.first == 60 } }
            compose.onNodeWithText("Synthetic event 59", substring = false).assertVerificationDisplayed(compose)
            repeat(3) { compose.onNodeWithTag("server-administration-list").performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
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
            compose.onNodeWithText("Synthetic event 59", substring = false).assertVerificationDisplayed(compose)
            fixture.releaseActivityRefresh.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.activityRemainderStarted.count == 0L ||
                compose.onAllNodesWithText("Refreshed synthetic event 59", substring = false).fetchSemanticsNodes().isNotEmpty() }
            if (fixture.activityRemainderStarted.count == 0L) {
                scrollAdministration(hasText("Synthetic event 59", substring = false) or hasText("Refreshed synthetic event 59", substring = false))
            }
            fixture.releaseActivityRemainder.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); runCatching {
                scrollAdministration(hasText("Refreshed synthetic event 59", substring = false))
                compose.onNodeWithText("Refreshed synthetic event 59", substring = false).assertVerificationDisplayed(compose)
                true
            }.getOrDefault(false) }
            scrollActivityEnd(59, refreshed = true)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); runCatching {
                scrollAdministration(hasText("Refreshed synthetic event 60", substring = false)); true
            }.getOrDefault(false) }
            compose.onNodeWithText("Refreshed synthetic event 60", substring = false).assertVerificationDisplayed(compose)
            val terminalRequests = fixture.activityRequests.size
            repeat(3) { list.performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, terminalRequests)
            compose.onNodeWithTag("server-activity-more").assertDoesNotExist()
            assertEquals(0, fixture.runs.get()); assertEquals(0, fixture.cancels.get()); assertEquals(0, fixture.restarts.get())
            compose.runOnIdle { host.shown.value = false }
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
            compose.onNodeWithText("Synthetic event 29", substring = false).assertVerificationDisplayed(compose)
            repeat(3) { compose.onNodeWithTag("server-administration-list").performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
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
            compose.onNodeWithText("Synthetic event 59", substring = false).assertVerificationDisplayed(compose)
            scrollAdministration(hasText("Synthetic event 0", substring = false))
            compose.onNodeWithText("Synthetic event 0", substring = false).assertVerificationDisplayed(compose)
            compose.onAllNodesWithText("Refreshed synthetic event 0", substring = false).assertCountEquals(0)
            compose.onNodeWithTag("server-administration-error").assertVerificationDisplayed(compose)
            repeat(2) { list.performVerificationTouchInput(compose) { swipeUp() }; compose.waitForIdle() }
            assertActivityRequestsStayAt(fixture, 5)
            assertEquals(listOf(0, 30, 60, 0, 30), fixture.activityRequests.map { it.first })
        }
    }

    @Test fun aFreshAdministrationSnapshotKeepsItsSubpageAndActualScrollPositionWithoutRequests() {
        Fixture().use { fixture ->
            val host = showAdministration(fixture)
            waitForAdministrationIdle(host, fixture)
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).performVerificationClick(compose)
            scrollActivityEnd(29)
            scrollActivityEnd(30)
            waitForAdministrationIdle(host, fixture)
            compose.onNodeWithText("Synthetic event 30", substring = false).assertVerificationDisplayed(compose)
            val state = checkNotNull(host.state.get())
            lateinit var position: Pair<Int, Int>
            compose.runOnIdle { position = state.list.firstVisibleItemIndex to state.list.firstVisibleItemScrollOffset }
            val requests = fixture.total.get()

            reenterAdministration(host)

            compose.onNodeWithText("Synthetic event 30", substring = false).assertVerificationDisplayed(compose)
            compose.runOnIdle {
                assertSame("同一会话继续使用同一个状态所有者", state, host.state.get())
                assertEquals("activity", state.page)
                assertEquals(position, state.list.firstVisibleItemIndex to state.list.firstVisibleItemScrollOffset)
            }
            assertAdministrationRequestsStayAt(fixture, requests)
            assertEquals(listOf(0, 30), fixture.activityRequests.map { it.first })
        }
    }

    @Test fun reenteringDuringTheFirstReadJoinsItAndAllowsTheReadyActivityPage() {
        Fixture(activityCount = 1).use { fixture ->
            fixture.holdNextInfoRead.set(true)
            val host = showAdministration(fixture)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.infoReadStarted.count == 0L &&
                exists("server-task-task") && compose.onAllNodesWithText("Synthetic event 0", substring = false).fetchSemanticsNodes().isNotEmpty() }
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).assertIsEnabled().performVerificationClick(compose)
            compose.onNodeWithText("Synthetic event 0", substring = false).assertVerificationDisplayed(compose)
            reenterAdministration(host)
            assertEquals("首读未结束时不能重发概览", 1, fixture.infoReads.get())
            fixture.releaseInfoRead.countDown()
            waitForAdministrationIdle(host, fixture)
            assertEquals("activity", checkNotNull(host.state.get()).page)
            compose.onNodeWithText("Synthetic event 0", substring = false).assertVerificationDisplayed(compose)
            assertAdministrationRequestsStayAt(fixture, 3)
            assertEquals(1, fixture.infoReads.get()); assertEquals(1, fixture.taskReads.get())
            assertEquals(listOf(0), fixture.activityRequests.map { it.first })
        }
    }

    @Test fun anExpiredActivityReadFailureKeepsTheOldRowsAndDoesNotRefreshOtherPages() {
        Fixture(activityCount = 1).use { fixture ->
            val host = showAdministration(fixture)
            waitForAdministrationIdle(host, fixture)
            scrollAdministration(hasText("更多", substring = false))
            compose.onNodeWithText("更多", substring = false).performVerificationClick(compose)
            compose.onNodeWithText("Synthetic event 0", substring = false).assertVerificationDisplayed(compose)
            fixture.failActivityAt.set(0)
            host.clock.addAndGet(30_001L)

            reenterAdministration(host)

            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); exists("server-administration-error") }
            waitForAdministrationIdle(host, fixture)
            compose.onNodeWithText("Synthetic event 0", substring = false).assertVerificationDisplayed(compose)
            compose.onAllNodesWithText("Refreshed synthetic event 0", substring = false).assertCountEquals(0)
            assertEquals(1, fixture.infoReads.get()); assertEquals(1, fixture.taskReads.get())
            assertEquals(listOf(0, 0), fixture.activityRequests.map { it.first })
            assertAdministrationRequestsStayAt(fixture, 4)
        }
    }

    @Test fun leavingAdministrationStopsItsRealTaskPoll() {
        Fixture().use { fixture ->
            fixture.backgroundTaskRunning.set(true)
            val host = showAdministration(fixture)
            waitForAdministrationIdle(host, fixture)
            fixture.holdNextTaskRead.set(true)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.taskPollStarted.count == 0L }
            val taskReadsAtExit = fixture.taskReads.get()
            assertTrue("至少执行过初读与一次真实轮询", taskReadsAtExit >= 2)
            fixture.allowCancelledResponse.set(true)
            compose.runOnIdle { host.shown.value = false }
            compose.waitUntil(WAIT_MS) { !exists("server-administration-screen") }
            fixture.releaseTaskPoll.countDown()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.taskPollDelivered.count == 0L }
            val requests = fixture.total.get()
            assertAdministrationRequestsStayAt(fixture, requests, durationMs = 4_300L)
            assertEquals("离开后不能再轮询", taskReadsAtExit, fixture.taskReads.get())
            assertEquals(0, fixture.runs.get()); assertEquals(0, fixture.cancels.get()); assertEquals(0, fixture.restarts.get())
        }
    }

    @Test fun replacingTheSessionRejectsAnOldUnauthorizedResponseAndOnlyReportsTheCurrentFailure() {
        Fixture(activityCount = 1).use { previous ->
            previous.infoStatus.set(401); previous.holdNextInfoRead.set(true)
            val host = showAdministration(previous)
            compose.waitUntil(WAIT_MS) { previous.assertHealthy(); previous.infoReadStarted.count == 0L }
            previous.allowCancelledResponse.set(true)
            Fixture(activityCount = 1).use { current ->
                current.serverName.set("Replacement synthetic server")
                compose.runOnIdle { host.session.value = current.session() }
                waitForAdministrationIdle(host, current)
                compose.onAllNodesWithText("Replacement synthetic server").onFirst().assertVerificationDisplayed(compose)
                previous.releaseInfoRead.countDown()
                compose.waitUntil(WAIT_MS) { previous.assertHealthy(); previous.infoReadDelivered.count == 0L }
                assertAdministrationRequestsStayAt(current, 3)
                assertEquals("旧会话401不能要求新会话重登", 0, host.authExpired.get())
                compose.onAllNodesWithText("Replacement synthetic server").onFirst().assertVerificationDisplayed(compose)

                current.infoStatus.set(401)
                host.clock.addAndGet(30_001L)
                reenterAdministration(host)
                compose.waitUntil(WAIT_MS) { current.assertHealthy(); host.authExpired.get() == 1 }
                waitForAdministrationIdle(host, current)
                assertAdministrationRequestsStayAt(current, 6)
                assertEquals(1, host.authExpired.get())
                assertEquals(0, current.runs.get()); assertEquals(0, current.restarts.get())
            }
        }
    }

    @Test fun aDelayedLogReadCannotPopulateAnotherFileSelectedBeforeItFinishes() {
        Fixture(secondLog = true).use { fixture ->
            val host = showAdministration(fixture)
            waitForAdministrationIdle(host, fixture)
            scrollAdministration(hasTestTag("server-logs-open"))
            compose.onNodeWithTag("server-logs-open").performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); compose.onAllNodesWithTag("server-log-file").fetchSemanticsNodes().size == 2 }
            waitForAdministrationIdle(host, fixture)
            fixture.holdNextLogRead.set(true)
            compose.onNodeWithText("synthetic.log", substring = false).performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.logReadStarted.count == 0L }

            compose.onNodeWithTag("server-administration-back").performVerificationClick(compose)
            compose.onNodeWithText("synthetic-second.log", substring = false).assertVerificationDisplayed(compose).performVerificationClick(compose)
            compose.runOnIdle {
                val state = checkNotNull(host.state.get())
                assertEquals("log", state.page)
                assertEquals("synthetic-second.log", state.logFile?.name)
            }
            assertEquals("第二个文件等待已有短读结束", listOf("synthetic.log"), fixture.logRequests.toList())
            fixture.releaseLogRead.countDown()

            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                compose.onAllNodesWithText("synthetic safe log", substring = false).assertCountEquals(0)
                compose.onAllNodesWithText("second synthetic safe log", substring = false).fetchSemanticsNodes().isNotEmpty()
            }
            waitForAdministrationIdle(host, fixture)
            compose.onNodeWithText("second synthetic safe log", substring = false).assertVerificationDisplayed(compose)
            compose.onAllNodesWithText("synthetic safe log", substring = false).assertCountEquals(0)
            compose.onAllNodesWithText(TOKEN, substring = true).assertCountEquals(0)
            assertEquals(listOf("synthetic.log", "synthetic-second.log"), fixture.logRequests.toList())
            assertAdministrationRequestsStayAt(fixture, 6)
            assertEquals(0, fixture.runs.get()); assertEquals(0, fixture.cancels.get()); assertEquals(0, fixture.restarts.get())
        }
    }

    private class AdministrationHost(session: Session) {
        val session = mutableStateOf(session)
        val shown = mutableStateOf(true)
        val clock = AtomicLong(1_000L)
        val authExpired = AtomicInteger()
        val state = AtomicReference<ServerAdministrationState?>()
    }

    private fun showAdministration(fixture: Fixture, session: Session = fixture.session()): AdministrationHost {
        val host = AdministrationHost(session)
        val api = JellyfinApi()
        compose.setContent { ManagementContent {
            val scope = rememberCoroutineScope()
            val current = host.session.value
            val state = remember(current) {
                ServerAdministrationState(current, api, scope, { host.authExpired.incrementAndGet() }, clock = host.clock::get)
            }
            DisposableEffect(state) {
                host.state.set(state)
                onDispose { state.cancel() }
            }
            if (host.shown.value) ServerAdministrationScreen(state, onBack = { host.shown.value = false })
        } }
        return host
    }

    private fun reenterAdministration(host: AdministrationHost) {
        compose.runOnIdle { host.shown.value = false }
        compose.waitUntil(WAIT_MS) { !exists("server-administration-screen") }
        compose.runOnIdle { host.shown.value = true }
        compose.waitUntil(WAIT_MS) { exists("server-administration-screen") }
        compose.waitForIdle()
    }

    private fun waitForAdministrationIdle(host: AdministrationHost, fixture: Fixture) {
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            val state = host.state.get()
            state != null && state.session == host.session.value && !state.loading && !state.writing &&
                fixture.infoReads.get() > 0 && fixture.taskReads.get() > 0 && fixture.activityRequests.isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun assertAdministrationRequestsStayAt(fixture: Fixture, expected: Int, durationMs: Long = 750L) {
        val until = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < until) {
            compose.waitForIdle(); fixture.assertHealthy()
            assertEquals("重复进入、失败或离页不能额外读取控制台", expected, fixture.total.get())
            SystemClock.sleep(25)
        }
    }

    private fun openActivities(fixture: Fixture) {
        showAdministration(fixture)
        waitForTask(fixture)
        scrollAdministration(hasText("更多", substring = false))
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            compose.onAllNodes(hasText("更多", substring = false) and isEnabled()).fetchSemanticsNodes().size == 1
        }
        val more = compose.onNodeWithText("更多", substring = false)
        VerificationDisplay.awaitWindowReady(compose, "activity-open", more)
        more.performVerificationClick(compose)
    }

    @Test fun nonAdministratorCannotReadAdministrativeData() {
        Fixture().use { fixture ->
            showAdministration(fixture, fixture.session().copy(isAdministrator = false))
            compose.onNodeWithText("需要管理员权限").assertVerificationDisplayed(compose)
            compose.waitForIdle()
            assertEquals(0, fixture.total.get())
        }
    }

    private fun search(fixture: Fixture) {
        scrollIdentification("identification-search")
        compose.onNodeWithTag("identification-search").performVerificationClick(compose)
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
        if (formTag && exists("identification-results")) { compose.onNodeWithTag("identification-back").performVerificationClick(compose); compose.waitForIdle() }
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
        list.performVerificationTouchInput(compose) { swipeUp() }
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

    private class Fixture(private val activityCount: Int = 31, private val secondLog: Boolean = false) : Closeable {
        val total = AtomicInteger(); val searches = AtomicInteger(); val applies = AtomicInteger()
        val infoReads = AtomicInteger(); val infoStatus = AtomicInteger(200)
        val serverName = AtomicReference("Synthetic Jellyfin")
        val holdNextInfoRead = AtomicBoolean(); val allowCancelledResponse = AtomicBoolean()
        val infoReadStarted = CountDownLatch(1); val releaseInfoRead = CountDownLatch(1); val infoReadDelivered = CountDownLatch(1)
        val taskReads = AtomicInteger(); val runs = AtomicInteger(); val cancels = AtomicInteger(); val restarts = AtomicInteger()
        val activityAppends = AtomicInteger(); val logReads = AtomicInteger()
        val logRequests = CopyOnWriteArrayList<String>()
        val holdNextLogRead = AtomicBoolean(); val logReadStarted = CountDownLatch(1); val releaseLogRead = CountDownLatch(1)
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
            var code = 200; var body = ""; var contentType = "application/json"; var heldTaskPoll = false; var heldInfoRead = false
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
                path == "/proxy/System/Info" -> {
                    infoReads.incrementAndGet(); code = infoStatus.get()
                    body = JSONObject().put("ServerName", serverName.get()).put("Version", "12.3.0").put("CanSelfRestart", true).toString()
                    if (holdNextInfoRead.compareAndSet(true, false)) {
                        heldInfoRead = true; infoReadStarted.countDown()
                        check(releaseInfoRead.await(15, TimeUnit.SECONDS))
                    }
                }
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
                path == "/proxy/System/Logs" -> {
                    val files = JSONArray().put(JSONObject().put("Name", "synthetic.log").put("Size", 100))
                    if (secondLog) files.put(JSONObject().put("Name", "synthetic-second.log").put("Size", 100))
                    body = files.toString()
                }
                path == "/proxy/System/Logs/Log" -> {
                    val name = uri.query.substringAfter("name=")
                    assertTrue(name == "synthetic.log" || secondLog && name == "synthetic-second.log")
                    logRequests += name; logReads.incrementAndGet(); contentType = "text/plain"
                    body = (if (name == "synthetic.log") "synthetic safe log" else "second synthetic safe log") + "\nAuthorization: Bearer $TOKEN\n"
                    if (holdNextLogRead.compareAndSet(true, false)) {
                        logReadStarted.countDown(); check(releaseLogRead.await(15, TimeUnit.SECONDS))
                    }
                }
                path == "/proxy/System/Restart" -> { assertEquals("POST", method); restarts.incrementAndGet(); code = 204 }
                else -> error("合成服务收到未知路径")
            }
            val payload = body.toByteArray()
            try {
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $code ${if (code < 400) "OK" else "Unavailable"}\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(payload); output.flush()
            } catch (cancelled: IOException) {
                if (!allowCancelledResponse.get()) throw cancelled
            } finally {
                if (heldTaskPoll) taskPollDelivered.countDown()
                if (heldInfoRead) infoReadDelivered.countDown()
            }
        }
        override fun close() {
            closed = true; allowApply.countDown(); releaseActivityRefresh.countDown(); releaseActivityRemainder.countDown(); releaseTaskPoll.countDown(); releaseInfoRead.countDown(); releaseLogRead.countDown(); server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }; executor.shutdownNow()
            assertHealthy()
        }
    }
    private companion object { const val WAIT_MS = 10_000L; const val TOKEN = "management-ui-synthetic-token" }
}

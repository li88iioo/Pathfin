package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.SubtitleManagementSheet
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleManagementUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun detailManagementIsDisabledWhenServerManagementIsForbidden() {
        val fixture = Fixture(canManage = false)
        show(fixture)
        compose.waitUntil(WAIT_MS) { fixture.userReads.get() > 0 }
        VerificationDisplay.awaitWindowReady(compose, "subtitle-management-forbidden", compose.onNodeWithTag("subtitle-open-search"))
        compose.onNodeWithTag("subtitle-upload-file").assertIsNotEnabled()
        compose.onNodeWithTag("subtitle-open-search").assertIsNotEnabled()
        compose.onNodeWithTag("subtitle-local-import").assertDoesNotExist()
        compose.onNodeWithText("此账号没有服务器字幕管理权限。").assertExists()
        assertEquals(0, fixture.searches.get())
        assertEquals(0, fixture.writes.get())
    }

    @Test fun playerOpensOnlineSearchDirectlyAndSearchesOnce() {
        val fixture = Fixture(canManage = true)
        show(fixture, playerMode = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("subtitle-search-result").fetchSemanticsNodes().size == 1 }
        assertEquals("进入即按默认语言搜索一次", 1, fixture.searches.get())
        compose.onNodeWithTag("subtitle-open-search").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-upload-file").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-management-back").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-search-submit").assertIsEnabled().performClick()
        compose.waitUntil(WAIT_MS) { fixture.searches.get() == 2 }
    }

    @Test fun playerSearchExplainsForbiddenManagementWithoutRequestingProviders() {
        val fixture = Fixture(canManage = false)
        show(fixture, playerMode = true)
        compose.waitUntil(WAIT_MS) { fixture.userReads.get() > 0 }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("此账号没有服务器字幕管理权限。").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("subtitle-search-submit").assertIsNotEnabled()
        assertEquals(0, fixture.searches.get())
    }

    @Test fun searchRunsOnOpenAndDownloadRequiresConfirmationAndDoesNotTreatAnUnchanged204AsSuccess() {
        val fixture = Fixture(canManage = true)
        val changed = AtomicInteger()
        show(fixture, onChanged = { changed.incrementAndGet() })
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("subtitle-open-search") and isEnabled()).fetchSemanticsNodes().size == 1 }
        VerificationDisplay.awaitWindowReady(compose, "subtitle-management-search", compose.onNodeWithTag("subtitle-open-search"))
        compose.onNodeWithTag("subtitle-open-search").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("subtitle-search-result").fetchSemanticsNodes().size == 1 }
        assertEquals(1, fixture.searches.get())
        assertEquals(0, fixture.writes.get())
        compose.onNodeWithTag("subtitle-search-result").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, fixture.writes.get())
        compose.onNodeWithTag("subtitle-search-result").performClick()
        compose.onNodeWithTag("subtitle-download-confirm").performClick()
        compose.waitUntil(WAIT_MS) { fixture.writes.get() == 1 && fixture.userReads.get() >= 2 &&
            compose.onAllNodes(hasContentDescription("返回字幕") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        assertEquals("204 但轨道未变化时，不能重载播放器", 0, changed.get())
        compose.onNodeWithTag("subtitle-search-result").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回字幕").performClick()
        compose.onNodeWithText("等待服务器更新字幕列表").assertExists()
        compose.onNodeWithText("检查更新").performClick()
        compose.waitUntil(WAIT_MS) { fixture.userReads.get() >= 3 &&
            compose.onAllNodes(hasText("检查更新") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        assertEquals("主动检查得到同一字幕目录时也不能重载", 0, changed.get())
        compose.onNodeWithText("等待服务器更新字幕列表").assertExists()
        fixture.publishDownloadedTrack = true
        compose.onNodeWithText("检查更新").performClick()
        compose.waitUntil(WAIT_MS) { changed.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("等待服务器更新字幕列表").assertDoesNotExist()
        compose.onNodeWithText("Downloaded Chinese").assertExists()
        assertEquals("实际观察到轨道变化只通知一次", 1, changed.get())
        assertEquals(1, fixture.writes.get())
    }

    @Test fun delayedServerWriteIsDetectedAutomaticallyEvenAfterThePanelCloses() {
        val fixture = Fixture(canManage = true)
        val changed = AtomicInteger()
        val open = androidx.compose.runtime.mutableStateOf(true)
        show(fixture, onChanged = { changed.incrementAndGet() }, open = open)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("subtitle-open-search") and isEnabled()).fetchSemanticsNodes().size == 1 }
        VerificationDisplay.awaitWindowReady(compose, "subtitle-management-auto-verify", compose.onNodeWithTag("subtitle-open-search"))
        compose.onNodeWithTag("subtitle-open-search").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("subtitle-search-result").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("subtitle-search-result").performClick()
        compose.onNodeWithTag("subtitle-download-confirm").performClick()
        compose.waitUntil(WAIT_MS) { fixture.writes.get() == 1 && fixture.userReads.get() >= 2 }
        assertEquals(0, changed.get())
        compose.runOnIdle { open.value = false }
        fixture.publishDownloadedTrack = true
        compose.waitUntil(15_000) { changed.get() == 1 }
        val reads = fixture.userReads.get()
        Thread.sleep(4_500)
        assertEquals("确认变化后停止轮询且只通知一次", 1, changed.get())
        assertEquals(reads, fixture.userReads.get())
    }

    @Test fun unchangedDownloadTimesOutWithoutRepeatingTheWriteAndExplicitlyEndingTheWaitAllowsAnotherConfirmation() {
        val fixture = Fixture(canManage = true)
        val changed = AtomicInteger()
        show(fixture, onChanged = { changed.incrementAndGet() })
        confirmFirstDownload(fixture, "subtitle-management-unconfirmed")

        // 使用实际生产轮询间隔；不能通过测试配置缩短等待，或把 204 当成已保存。
        compose.waitUntil(45_000) {
            fixture.userReads.get() >= 17 &&
                compose.onAllNodes(hasText("未确认", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("结束等待") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        assertEquals("轮询耗尽不能自动重新提交下载", 1, fixture.writes.get())
        assertEquals("没有观察到新轨道，不能通知播放器重建", 0, changed.get())
        compose.onNodeWithTag("subtitle-search-result").assertIsNotEnabled()

        val readsBeforeCheck = fixture.userReads.get()
        compose.onNodeWithTag("subtitle-management-content").performScrollToNode(hasText("检查更新"))
        compose.onNodeWithText("检查更新").assertIsEnabled().performClick()
        compose.waitUntil(WAIT_MS) {
            fixture.userReads.get() > readsBeforeCheck &&
                compose.onAllNodes(hasText("检查更新") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        assertEquals("检查未确认结果只能读取目录", 1, fixture.writes.get())
        assertEquals(0, changed.get())

        compose.onNodeWithText("结束等待").performClick()
        compose.onNodeWithText("结束等待").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-search-result").assertIsEnabled()
        assertEquals("解除等待本身不能提交下载", 1, fixture.writes.get())
        compose.onNodeWithContentDescription("返回字幕").performClick()
        compose.onNodeWithTag("subtitle-upload-file").assertIsEnabled()
        compose.onNodeWithTag("subtitle-open-search").performClick()
        compose.onNodeWithTag("subtitle-management-content").performScrollToNode(hasTestTag("subtitle-search-result"))
        compose.onNodeWithTag("subtitle-search-result").assertIsEnabled().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals("再次选择后取消确认也不能重放写入", 1, fixture.writes.get())

        compose.onNodeWithTag("subtitle-search-result").performClick()
        fixture.publishDownloadedTrack = true
        compose.onNodeWithTag("subtitle-download-confirm").performClick()
        compose.waitUntil(WAIT_MS) { fixture.writes.get() == 2 && changed.get() == 1 }
        compose.onNodeWithContentDescription("返回字幕").performClick()
        compose.onNodeWithText("Downloaded Chinese").assertExists()
        assertEquals("必须由用户再次确认后才发送第二次下载", 2, fixture.writes.get())
        assertEquals(1, changed.get())
    }

    @Test fun anAcceptedWriteWithAnInitialReadFailureStillConfirmsTheDelayedTrackWithoutPostingAgain() {
        val fixture = Fixture(canManage = true, failFirstReadAfterWrite = true)
        val changed = AtomicInteger()
        show(fixture, onChanged = { changed.incrementAndGet() })
        confirmFirstDownload(fixture, "subtitle-management-read-recovery")
        compose.waitUntil(WAIT_MS) { fixture.failedReads.get() == 1 }
        assertEquals(1, fixture.writes.get())
        assertEquals("读取失败不能当作实际新增字幕", 0, changed.get())

        fixture.publishDownloadedTrack = true
        compose.waitUntil(WAIT_MS) { changed.get() == 1 }
        compose.onNodeWithContentDescription("返回字幕").performClick()
        compose.onNodeWithText("Downloaded Chinese").assertExists()
        compose.onNodeWithText("等待服务器更新字幕列表").assertDoesNotExist()
        compose.onNodeWithTag("subtitle-upload-file").assertIsEnabled()
        assertEquals("第一次重读失败后，只继续确认而不重新 POST", 1, fixture.writes.get())
        assertEquals(1, fixture.failedReads.get())
        assertEquals(1, changed.get())
    }

    @Test fun endingAnInFlightConfirmationPreventsItsLateDirectoryFromChangingTheNextOperation() {
        val fixture = Fixture(canManage = true, holdFirstReadAfterWrite = true)
        val changed = AtomicInteger()
        show(fixture, onChanged = { changed.incrementAndGet() })
        try {
            confirmFirstDownload(fixture, "subtitle-management-verification-ownership")
            compose.waitUntil(WAIT_MS) { fixture.blockedReadStarted.count == 0L }
            compose.onNodeWithText("结束等待").assertIsEnabled().performClick()
            compose.onNodeWithTag("subtitle-search-result").assertIsEnabled().performClick()
            compose.onNodeWithTag("subtitle-download-confirm").performClick()
            compose.waitUntil(WAIT_MS) {
                fixture.writes.get() == 2 && fixture.userReads.get() >= 3 &&
                    compose.onAllNodes(hasText("检查更新") and isEnabled()).fetchSemanticsNodes().size == 1
            }

            // 旧读取返回一个足以满足旧匹配条件的目录，不能发布它或清除第二次操作的等待。
            val readsBeforeRelease = fixture.userReads.get()
            fixture.releaseBlockedRead.countDown()
            compose.waitUntil(WAIT_MS) {
                fixture.blockedReadFinished.count == 0L && fixture.userReads.get() > readsBeforeRelease &&
                    compose.onAllNodes(hasText("检查更新") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.waitForIdle()
            compose.onNodeWithTag("subtitle-search-result").assertIsNotEnabled()
            compose.onNodeWithContentDescription("返回字幕").performClick()
            compose.onNodeWithText("等待服务器更新字幕列表").assertExists()
            compose.onNodeWithText("Obsolete Chinese").assertDoesNotExist()
            compose.onNodeWithTag("subtitle-upload-file").assertIsNotEnabled()
            assertEquals("结束旧等待后，旧 GET 不能发布过期目录", 0, changed.get())
            assertEquals("结束等待和旧响应均不能自动重放下载", 2, fixture.writes.get())

            fixture.publishDownloadedTrack = true
            compose.onNodeWithText("检查更新").assertIsEnabled().performClick()
            compose.waitUntil(WAIT_MS) { changed.get() == 1 }
            compose.onNodeWithText("Downloaded Chinese").assertExists()
            compose.onNodeWithText("等待服务器更新字幕列表").assertDoesNotExist()
            compose.onNodeWithTag("subtitle-upload-file").assertIsEnabled()
            assertEquals(2, fixture.writes.get())
            assertEquals(1, changed.get())
        } finally {
            fixture.releaseBlockedRead.countDown()
        }
    }

    @Test fun playbackUsesTheDarkPlayerPanelEvenWhenTheAppThemeIsLight() {
        val fixture = Fixture(canManage = true)
        show(fixture, playerMode = true)
        compose.onNodeWithTag("subtitle-management-close").assertExists()
        compose.onNodeWithTag("subtitle-management").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Subtitle fixture").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("播放器编辑器的文字必须切换到深色面板的浅色前景", layouts.single().layoutInput.style.color.luminance() > .4f)
    }

    @Test fun authenticationExpiryAfterAnAcceptedWriteStartsRecoveryWithoutResubmittingTheWrite() {
        val fixture = Fixture(canManage = true, expireAfterWrite = true)
        val expired = AtomicInteger()
        val changed = AtomicInteger()
        show(fixture, onChanged = { changed.incrementAndGet() }, onAuthExpired = { expired.incrementAndGet() })
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("subtitle-open-search") and isEnabled()).fetchSemanticsNodes().size == 1 }
        VerificationDisplay.awaitWindowReady(compose, "subtitle-management-write-expired", compose.onNodeWithTag("subtitle-open-search"))
        compose.onNodeWithTag("subtitle-open-search").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("subtitle-search-result").fetchSemanticsNodes().size == 1 }
        VerificationDisplay.awaitWindowReady(compose, "subtitle-management-write-expired-result", compose.onNodeWithTag("subtitle-search-result"))
        compose.onNodeWithTag("subtitle-search-result").performClick()
        VerificationDisplay.awaitWindowReady(compose, "subtitle-management-write-expired-confirm", compose.onNodeWithTag("subtitle-download-confirm"))
        compose.onNodeWithTag("subtitle-download-confirm").assertIsEnabled().performClick()
        compose.waitUntil(WAIT_MS) { fixture.writes.get() == 1 }
        assertEquals("必须先观察到字幕 POST，才能检查其后的 401 恢复", 1, fixture.writes.get())
        compose.waitUntil(WAIT_MS) { expired.get() > 0 }
        compose.waitForIdle()
        assertEquals(1, fixture.writes.get())
        assertEquals(0, changed.get())
        assertEquals(1, expired.get())
        compose.onNodeWithTag("subtitle-search-result").assertIsNotEnabled()
    }

    private fun confirmFirstDownload(fixture: Fixture, stage: String) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag("subtitle-open-search") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        VerificationDisplay.awaitWindowReady(compose, stage, compose.onNodeWithTag("subtitle-open-search"))
        compose.onNodeWithTag("subtitle-open-search").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("subtitle-search-result").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("subtitle-search-result").performClick()
        compose.onNodeWithTag("subtitle-download-confirm").performClick()
        compose.waitUntil(WAIT_MS) { fixture.writes.get() == 1 && fixture.userReads.get() >= 2 }
    }

    private fun show(fixture: Fixture, onDismiss: () -> Unit = {}, onChanged: () -> Unit = {}, playerMode: Boolean = false,
        onAuthExpired: () -> Unit = {}, open: androidx.compose.runtime.State<Boolean> = androidx.compose.runtime.mutableStateOf(true)) {
        val session = Session(ServerConfig("https://subtitle-fixture.invalid"), "subtitle-fixture-token", "subtitle-user", "Synthetic", "subtitle-device")
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            PathfinTheme(ThemePreference.LIGHT) {
                if (open.value) SubtitleManagementSheet(session, MediaItem(ITEM, "Subtitle fixture", "Episode"), fixture.api,
                    onDismiss = onDismiss, onChanged = { onChanged() }, verificationScope = scope,
                    playerMode = playerMode, onAuthExpired = onAuthExpired)
            }
        }
    }

    private class Fixture(canManage: Boolean, expireAfterWrite: Boolean = false, failFirstReadAfterWrite: Boolean = false,
        holdFirstReadAfterWrite: Boolean = false) {
        val userReads = AtomicInteger()
        val searches = AtomicInteger()
        val writes = AtomicInteger()
        val failedReads = AtomicInteger()
        private val remainingReadFailures = AtomicInteger(if (failFirstReadAfterWrite) 1 else 0)
        private val remainingBlockedReads = AtomicInteger(if (holdFirstReadAfterWrite) 1 else 0)
        val blockedReadStarted = CountDownLatch(1)
        val releaseBlockedRead = CountDownLatch(1)
        val blockedReadFinished = CountDownLatch(1)
        @Volatile var publishDownloadedTrack = false
        val api = JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val response = when {
                request.url.encodedPath == "/Users/Me" -> {
                    userReads.incrementAndGet()
                    if (expireAfterWrite && writes.get() > 0) { status = 401; "{}" }
                    else if (writes.get() > 0 && remainingReadFailures.compareAndSet(1, 0)) {
                        failedReads.incrementAndGet(); status = 500; "{}"
                    }
                    else """{"Id":"subtitle-user","Policy":{"IsAdministrator":false,"EnableSubtitleManagement":$canManage}}"""
                }
                request.url.encodedPath == "/Items" -> {
                    val tracks = if (writes.get() > 0 && remainingBlockedReads.compareAndSet(1, 0)) {
                        blockedReadStarted.countDown()
                        try {
                            check(releaseBlockedRead.await(30, TimeUnit.SECONDS)) { "未释放字幕旧目录读取" }
                            """{"Index":2,"Type":"Subtitle","Codec":"srt","Language":"zho","DisplayTitle":"Obsolete Chinese","IsExternal":true}"""
                        } finally { blockedReadFinished.countDown() }
                    } else if (publishDownloadedTrack)
                        """{"Index":3,"Type":"Subtitle","Codec":"srt","Language":"zho","DisplayTitle":"Downloaded Chinese","IsExternal":true}""" else ""
                    """{"Items":[{"Id":"$ITEM","Name":"Subtitle fixture","Type":"Episode","MediaSources":[{"Id":"$ITEM","MediaStreams":[$tracks]}]}],"TotalRecordCount":1}"""
                }
                request.method == "GET" && "RemoteSearch" in request.url.pathSegments -> {
                    searches.incrementAndGet()
                    """[{"Id":"fixture-subtitle","Name":"Fixture Chinese subtitle","ProviderName":"Fixture","Format":"srt","ThreeLetterISOLanguageName":"zho"}]"""
                }
                else -> { writes.incrementAndGet(); status = 204; "" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }

    companion object {
        private const val ITEM = "00112233445566778899aabbccddeeff"
        private const val WAIT_MS = 10_000L
    }
}

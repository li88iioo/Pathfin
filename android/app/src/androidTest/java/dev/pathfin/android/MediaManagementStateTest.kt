package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.MediaToolsScreen
import dev.pathfin.android.ui.LocalPageTopInset
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 真实媒体管理 UI 的提交/草稿回归；账号、媒体及 API 全部在测试进程内合成。 */
@RunWith(AndroidJUnit4::class)
class MediaManagementStateTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == VERIFICATION_PACKAGE) {
                "媒体管理状态回归只允许 verification 包"
            }
            base.evaluate()
        }
    } }

    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test fun successfulSaveWithFailedReadIsCommittedOnceAndRetryOnlyReads() = withScreen { fixture, changed, _ ->
        val formBounds = bounds("metadata-form")
        val saveBounds = bounds("metadata-save")
        compose.onNodeWithTag("metadata-title").performTextReplacement(SAVED_TITLE)
        compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("metadata-save").assertIsEnabled().performVerificationClick(compose)

        waitForText(COMMITTED_READ_FAILURE, fixture)
        assertEquals("错误反馈不能重新布局正文", formBounds, bounds("metadata-form"))
        assertEquals("错误反馈不能移动保存按钮", saveBounds, bounds("metadata-save"))
        assertEquals("保存成功应立即通知缓存失效，不等待后续 GET", 1, changed.get())
        assertEquals(1, fixture.posts.get())
        assertEquals(SAVED_TITLE, fixture.savedTitle())
        assertTrue("提交后的 GET 必须确实失败", fixture.failedGets.get() > 0)
        compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("metadata-save").assertIsNotEnabled()
        scrollTo(hasTestTag("metadata-title"))
        compose.onNodeWithTag("metadata-title").assert(!hasSetTextAction())

        val failedReads = fixture.failedGets.get()
        compose.onNodeWithTag("metadata-form").performScrollToIndex(0)
        pullPageToRefresh(compose, stage = "committed-metadata-read-fails")
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            fixture.failedGets.get() > failedReads && compose.onAllNodesWithText(READ_FAILURE).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("失败的重新加载不能重发已成功的 POST", 1, fixture.posts.get())
        assertEquals("重新加载也不能重复触发写入通知", 1, changed.get())
        compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("metadata-save").assertIsNotEnabled()

        val successfulReads = fixture.successfulGets.get()
        fixture.failReads.set(false)
        compose.onNodeWithTag("metadata-form").performScrollToIndex(0)
        pullPageToRefresh(compose, stage = "committed-metadata-read-recovers")
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            fixture.successfulGets.get() > successfulReads &&
                compose.onAllNodesWithText(READ_FAILURE).fetchSemanticsNodes().isEmpty()
        }
        scrollTo(hasTestTag("metadata-title"))
        compose.waitUntil(WAIT_MS) {
            compose.onNodeWithTag("metadata-title").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText)
        }
        assertEquals("恢复读取后应显示服务器已经保存的值", SAVED_TITLE, titleText())
        compose.onNodeWithTag("metadata-title").assert(hasSetTextAction()).performTextReplacement("Editable after reload")
        compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("metadata-save").assertIsEnabled()
        assertEquals("恢复读取后仍不能偷偷重放写入", 1, fixture.posts.get())
        assertEquals(1, changed.get())
        fixture.assertHealthy()
    }

    @Test fun invalidNumericDraftsRequireDiscardAndReopeningRestoresServerValues() = withScreen { fixture, changed, shown ->
        for (label in listOf("年份", "社区评分（0–10）", "影评人评分（0–100）")) {
            val field = editableField(label)
            scrollTo(field)
            assertEquals("合成服务器的 $label 初始为空", "", fieldText(field))
            compose.onNode(field).performTextReplacement("invalid-number")
            compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose, "invalid-draft-save-remains-visible")
            compose.onNodeWithTag("metadata-save").assertIsNotEnabled()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("放弃未保存的修改？").assertVerificationDisplayed(compose)
            assertTrue("无效数字也属于未保存草稿，不能直接退出", shown.value)
            compose.onNodeWithText("继续编辑").performVerificationClick(compose)
            scrollTo(field)
            assertEquals("继续编辑应保留原始无效输入", "invalid-number", fieldText(field))
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("放弃修改", substring = false).performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { !shown.value }
            compose.onNodeWithTag("management-reopen").assertVerificationDisplayed(compose).performVerificationClick(compose)
            waitForEditor(fixture)
            assertEquals("重新进入应使用服务器标题", ORIGINAL_TITLE, titleText())
            scrollTo(field)
            assertEquals("已明确放弃的 $label 草稿不能在重新进入时恢复", "", fieldText(field))
            assertEquals("无效草稿和放弃操作都不能写服务器", 0, fixture.posts.get())
            assertEquals(0, changed.get())
            fixture.assertHealthy()
        }
        // 干净表单返回不再弹出丢弃确认，父路由应正常接收到返回。
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(WAIT_MS) { !shown.value }
        compose.onNodeWithTag("management-reopen").assertVerificationDisplayed(compose)
        assertFalse("只操作无效草稿时不应进入读失败模式", fixture.failReads.get())
    }

    @Test fun anOldMetadataResponseCannotOverwriteMetadataSavedWhileItWasInFlight() = withScreen { fixture, changed, _ ->
        fixture.failReadsAfterSave.set(false)
        val readsBeforeRefresh = fixture.successfulGets.get()
        assertEquals("文本编辑首次只读取完整文档一次", 1, readsBeforeRefresh)
        assertEquals("文本编辑不应读取无关图片", 0, fixture.imageLists.get())
        fixture.blockNextMetadata().use { oldRead ->
            compose.onNodeWithTag("metadata-form").performScrollToIndex(0)
            pullPageToRefresh(compose, stage = "metadata-old-document-response-pending")
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); oldRead.requestArrived }
            assertEquals("旧完整文档响应已拍快照并真实等待", readsBeforeRefresh + 1, fixture.successfulGets.get())
            assertEquals(ORIGINAL_TITLE, fixture.savedTitle())
            assertEquals(ORIGINAL_TITLE, titleText())

            compose.onNodeWithTag("metadata-title").assert(hasSetTextAction()).performTextReplacement(SAVED_TITLE)
            val readsBeforeSave = fixture.successfulGets.get()
            compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("metadata-save").assertIsEnabled().performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                fixture.posts.get() == 1 && changed.get() == 1 && fixture.successfulGets.get() > readsBeforeSave
            }
            scrollTo(hasTestTag("metadata-title"))
            compose.waitUntil(WAIT_MS) {
                compose.onNodeWithTag("metadata-title").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) &&
                    titleText() == SAVED_TITLE
            }
            assertFalse("提交后重新读取必须成功", fixture.failReads.get())
            assertEquals(SAVED_TITLE, fixture.savedTitle())
            assertFalse("旧文档响应仍未释放，不能把提交后 GET 当成旧读取已经完成", oldRead.responseWritten)

            // 保存期间指示器被 busy 隐藏；等恢复后确认旧真实刷新仍在进行。
            val spinning = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate) and
                hasAnyAncestor(hasTestTag("pull-refresh-indicator"))
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(spinning, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            oldRead.release()
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); oldRead.responseWritten }
            // HTTP 写完不代表 Main 已应用结果。spinner 退出证明 read/finally 已处理，再检验标题。
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(spinning, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            assertEquals("旧 read 返回后不得覆盖已成功保存的新标题", SAVED_TITLE, titleText())
            assertEquals(SAVED_TITLE, fixture.savedTitle())
            compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose, "invalid-draft-save-remains-visible")
            compose.onNodeWithTag("metadata-save").assertIsNotEnabled()
            assertEquals("旧读取完成不能重发写操作", 1, fixture.posts.get())
            assertEquals(1, changed.get())
            assertEquals("刷新和保存不能顺带读取图片", 0, fixture.imageLists.get())
            scrollTo(hasTestTag("metadata-title"))
            compose.onNodeWithTag("metadata-title").assert(hasSetTextAction()).performTextReplacement("Editable after stale read")
            compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("metadata-save").assertIsEnabled()
        }
    }

    @Test fun metadataScrollsBehindTheTitleInsetWhileSaveStaysFixed() = withScreen(topInset = 72.dp) { fixture, changed, _ ->
        val formBounds = bounds("metadata-form")
        val headerBounds = bounds("metadata-editor-header")
        val backBounds = bounds("metadata-back")
        val titleBounds = bounds("metadata-page-title")
        val saveBounds = bounds("metadata-save")
        assertEquals("标题栏应避开顶部安全区", formBounds.top + with(compose.density) { 72.dp.toPx() }, headerBounds.top, 1f)
        assertTrue("表单滚动容器应延伸到标题栏背后", formBounds.top < headerBounds.top)
        assertEquals("返回与标题应处于同一行", titleBounds.center.y, backBounds.center.y, 1f)
        assertEquals("保存与标题应处于同一行", titleBounds.center.y, saveBounds.center.y, 1f)
        assertTrue("返回、标题和保存应从左到右排列", backBounds.right <= titleBounds.left && titleBounds.right <= saveBounds.left)
        assertTrue("保存应位于标题栏右侧", saveBounds.center.x > headerBounds.center.x &&
            saveBounds.right <= headerBounds.right && headerBounds.right - saveBounds.right <= with(compose.density) { 16.dp.toPx() })
        assertEquals("保存按钮宽度应保持固定", with(compose.density) { 88.dp.toPx() }, saveBounds.width, 1f)
        compose.onNodeWithTag("metadata-title").performTextReplacement("Unsaved scrolling fixture")
        compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose).assertIsEnabled()
        compose.onNodeWithText("有未保存的修改", substring = false).assertDoesNotExist()

        compose.onNodeWithTag("metadata-form").performVerificationTouchInput(compose) { swipeUp() }
        compose.waitForIdle()
        assertTrue("必须通过真实手势滚动正文", compose.onNodeWithTag("metadata-form").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertEquals("滚动不得改变表单视口", formBounds, bounds("metadata-form"))
        assertEquals("标题栏不能随正文滚走", headerBounds, bounds("metadata-editor-header"))
        assertEquals("返回按钮不能随正文滚走", backBounds, bounds("metadata-back"))
        assertEquals("页面标题不能随正文滚走", titleBounds, bounds("metadata-page-title"))
        assertEquals("保存按钮应保持固定位置", saveBounds, bounds("metadata-save"))
        compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose).assertIsEnabled()
        assertEquals("滚动和草稿不能触发服务器写入", 0, fixture.posts.get())
        assertEquals(0, changed.get())
        fixture.assertHealthy()
    }

    @Test fun externalIdEditsAndClearingSurviveSavedStateRestorationAndSaveExactlyOnce() {
        val restoration = StateRestorationTester(compose)
        withScreen(restoration = restoration, providerIds = mapOf("Imdb" to "tt-original")) { fixture, changed, _ ->
            fixture.failReadsAfterSave.set(false)
            val imdb = hasTestTag("metadata-id/Imdb")
            val tmdb = hasTestTag("metadata-id/Tmdb")
            scrollTo(imdb)
            compose.onNode(imdb).performTextReplacement("")
            scrollTo(tmdb)
            compose.onNode(tmdb).performTextReplacement("85718")
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(WAIT_MS) {
                fixture.assertHealthy()
                compose.onAllNodesWithTag("metadata-form").fetchSemanticsNodes().isNotEmpty()
            }
            scrollTo(hasTestTag("metadata-title"))
            waitForEditor(fixture)
            scrollTo(imdb)
            assertEquals("明确清空的编号不能在重建后恢复旧值", "", fieldText(imdb))
            scrollTo(tmdb)
            assertEquals("新增编号草稿必须恢复", "85718", fieldText(tmdb))
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("放弃未保存的修改？").assertVerificationDisplayed(compose)
            compose.onNodeWithText("继续编辑").performVerificationClick(compose)
            compose.onNodeWithTag("metadata-save").assertIsEnabled().performVerificationClick(compose)
            compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.posts.get() == 1 && changed.get() == 1 }
            val saved = fixture.savedProviderIds()
            assertEquals(setOf("Tmdb"), saved.keys().asSequence().toSet())
            assertEquals("85718", saved.getString("Tmdb"))
            assertEquals(1, fixture.posts.get())
        }
    }

    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect =
        compose.onNodeWithTag(tag).readVerificationNode(compose) { it.boundsInRoot }

    @Suppress("DEPRECATION") // 与生产窗口一致，验证旧版系统上的 adjustResize 行为。
    private fun withScreen(topInset: Dp = 0.dp, restoration: StateRestorationTester? = null,
        providerIds: Map<String, String> = emptyMap(), block: (MetadataFixture, AtomicInteger, MutableState<Boolean>) -> Unit) {
        MetadataFixture(providerIds).use { fixture ->
            val changed = AtomicInteger()
            val shown = mutableStateOf(true)
            val session = Session(ServerConfig(fixture.baseUrl, true), "management-state-token", "state-user", "Synthetic", "state-device", isAdministrator = true)
            val item = MediaItem(ITEM_ID, ORIGINAL_TITLE, "Movie")
            val api = JellyfinApi()
            // 与生产 MainActivity 的 adjustResize 一致，底部输入聚焦不能把整个测试窗口向上平移。
            compose.runOnUiThread { compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            val content: @Composable () -> Unit = {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPageTopInset provides topInset) {
                    // 与 PathfinApp 的非缓存管理路由一致，离开时实际卸载 Screen。
                    BackHandler(enabled = shown.value) { shown.value = false }
                    if (shown.value) MediaToolsScreen(item, session, api, "metadata",
                        onChanged = { changed.incrementAndGet() })
                    else Button({ shown.value = true }, Modifier.testTag("management-reopen")) { Text("重新打开管理页") }
                    }
                }
            }
            if (restoration == null) compose.setContent(content) else restoration.setContent(content)
            try {
                waitForEditor(fixture)
                block(fixture, changed, shown)
            } finally {
                // 先卸载页面以取消读取，再关闭本测试的回环服务。
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
            }
        }
    }

    private fun waitForEditor(fixture: MetadataFixture) {
        compose.waitUntil(WAIT_MS) {
            fixture.assertHealthy()
            compose.onAllNodesWithText("标题").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("metadata-title").assertVerificationDisplayed(compose)
    }

    private fun waitForText(text: String, fixture: MetadataFixture) = compose.waitUntil(WAIT_MS) {
        fixture.assertHealthy()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollTo(matcher: SemanticsMatcher) = compose.onNodeWithTag("metadata-form").performScrollToNode(matcher)
    private fun editableField(label: String) = hasContentDescription(label) and hasSetTextAction()
    private fun titleText() = compose.onNodeWithTag("metadata-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun fieldText(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private class MetadataFixture(private val providerIds: Map<String, String> = emptyMap()) : Closeable {
        val posts = AtomicInteger()
        val successfulGets = AtomicInteger()
        val failedGets = AtomicInteger()
        val imageLists = AtomicInteger()
        val failReads = AtomicBoolean()
        val failReadsAfterSave = AtomicBoolean(true)
        private val nextMetadata = AtomicReference<MetadataReadGate?>(null)
        private val document = AtomicReference(JSONObject().put("Id", ITEM_ID).put("Name", ORIGINAL_TITLE).put("Type", "Movie")
            .put("ProductionYear", JSONObject.NULL).put("CommunityRating", JSONObject.NULL).put("CriticRating", JSONObject.NULL)
            .put("Genres", org.json.JSONArray().put("Fixture")).put("Tags", org.json.JSONArray().put("Synthetic"))
            .put("ProviderIds", JSONObject(providerIds))
            .put("CanDelete", false).put("CanDownload", false).toString())
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val closed = AtomicBoolean()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-metadata-state").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets.add(socket)
                executor.execute { serve(socket) }
            } catch (failure: SocketException) { if (!closed.get()) failures.add(failure) }
        }, "pathfin-metadata-state-accept").apply { isDaemon = true; start() }

        fun savedTitle() = JSONObject(document.get()).getString("Name")
        fun savedProviderIds() = JSONObject(document.get()).getJSONObject("ProviderIds")
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("元数据状态回环服务失败", it) } }
        fun blockNextMetadata() = MetadataReadGate().also { check(nextMetadata.compareAndSet(null, it)) }

        private fun serve(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = DataInputStream(connection.getInputStream())
                    val request = line(input).split(' ')
                    check(request.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(input); if (header.isEmpty()) break
                        check(headers.size < 128)
                        val colon = header.indexOf(':'); check(colon > 0)
                        headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                    }
                    check(headers["authorization"]?.contains("management-state-token") == true)
                    check("transfer-encoding" !in headers)
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..1_048_576)
                    val body = ByteArray(length).also(input::readFully)
                    val path = request[1].substringBefore('?')
                    var metadataGate: MetadataReadGate? = null
                    val (code, json) = when {
                        request[0] == "GET" && path == "/Items/$ITEM_ID" -> if (failReads.get()) {
                            failedGets.incrementAndGet(); 503 to "{}"
                        } else {
                            val snapshot = document.get()
                            successfulGets.incrementAndGet()
                            metadataGate = nextMetadata.getAndSet(null)
                            metadataGate?.block()
                            200 to snapshot
                        }
                        // 元数据页的外部编号清单；空清单表示没有可编辑的编号。
                        request[0] == "GET" && path == "/Items/$ITEM_ID/ExternalIdInfos" -> 200 to
                            if (providerIds.isEmpty()) "[]" else """[{"Name":"IMDb","Key":"Imdb"},{"Name":"TheMovieDb","Key":"Tmdb"}]"""
                        request[0] == "GET" && path == "/Items/$ITEM_ID/Images" -> {
                            imageLists.incrementAndGet()
                            200 to "[]"
                        }
                        request[0] == "POST" && path == "/Items/$ITEM_ID" -> {
                            val value = JSONObject(body.decodeToString())
                            check(value.opt("Id") == ITEM_ID && value.opt("Name") is String)
                            document.set(value.toString()); posts.incrementAndGet()
                            // 提交已经成功；只有之后的重新读取失败，不制造含混的提交超时。
                            failReads.set(failReadsAfterSave.get())
                            204 to ""
                        }
                        else -> error("元数据状态测试不允许该请求")
                    }
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write(("HTTP/1.1 $code Fixture\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                    metadataGate?.markResponseWritten()
                }
            } catch (failure: Throwable) { if (!closed.get()) failures.add(failure) }
            finally { sockets.remove(socket) }
        }

        private fun line(input: DataInputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 32_768) {
                val value = input.read()
                if (value < 0) throw EOFException("元数据回环请求头未结束")
                if (value == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(value)
            }
            error("元数据回环请求头过长")
        }

        override fun close() {
            closed.set(true); server.close()
            nextMetadata.getAndSet(null)?.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow(); accept.join(1_000)
        }
    }

    private class MetadataReadGate : Closeable {
        private val arrived = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        private val written = AtomicBoolean()
        val requestArrived get() = arrived.count == 0L
        val responseWritten get() = written.get()
        fun block() {
            arrived.countDown()
            check(proceed.await(WAIT_MS * 3, TimeUnit.MILLISECONDS)) { "旧文档响应门闩未按期释放" }
        }
        fun release() { proceed.countDown() }
        fun markResponseWritten() { written.set(true) }
        override fun close() = release()
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val ITEM_ID = "metadata-state-item"
        const val ORIGINAL_TITLE = "Pathfin metadata state fixture"
        const val SAVED_TITLE = "Pathfin metadata state saved"
        const val WAIT_MS = 15_000L
        const val COMMITTED_READ_FAILURE = "元数据已保存，但重新读取失败；可重试加载，请勿重复提交。"
        const val READ_FAILURE = "服务器暂时无法处理请求，请稍后重试"
    }
}

package dev.pathfin.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.MediaToolsScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

/** 实际编辑页和受鉴权回环 API；不读取持久账号、不请求用户服务器。 */
@RunWith(AndroidJUnit4::class)
class MediaMetadataPresentationTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val componentTargetsOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            check(target == "dev.pathfin.android.debug" || target == "dev.pathfin.android.verification") {
                "合成媒体编辑回归只允许开发包或 verification 包，实际为 $target"
            }
            base.evaluate()
        }
    } }
    // 独立 ComponentActivity 只使用内存 Session、图片加载器和回环 API；不放宽其它测试的保护。
    @get:Rule val rules: TestRule = RuleChain.outerRule(componentTargetsOnly).around(compose)

    @Test fun separateImageEditorUsesCompactCardsAndConfirmationWithoutSavingMetadataDrafts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).diskCache(null).build()
        val shown = mutableStateOf(true)
        val page = mutableStateOf("metadata")
        val changed = AtomicInteger()
        var failure: Throwable? = null
        Fixture().use { fixture ->
            val session = Session(ServerConfig(fixture.baseUrl, true), TOKEN, "presentation-user", "Synthetic", "presentation-device", isAdministrator = true)
            val api = JellyfinApi()
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader) {
                        BackHandler(enabled = shown.value) { shown.value = false }
                        if (shown.value) key(page.value) {
                            MediaToolsScreen(MediaItem(ITEM_ID, TITLE, "Movie"), session, api, page.value,
                                onChanged = { changed.incrementAndGet() })
                        }
                    }
                }
            }
            try {
                compose.waitUntil(WAIT_MS) {
                    fixture.assertHealthy()
                    compose.onAllNodesWithTag("metadata-title").fetchSemanticsNodes().isNotEmpty()
                }
                VerificationDisplay.awaitWindowReady(compose, "metadata-presentation", compose.onNodeWithTag("metadata-title"))
                compose.onNodeWithTag("metadata-page-title").assertIsDisplayed()
                compose.onNodeWithTag("metadata-back").assertIsDisplayed()
                compose.onNodeWithTag("metadata-save").assertIsNotEnabled()
                compose.onNodeWithText("有未保存的修改", substring = false).assertDoesNotExist()
                compose.onNodeWithTag("media-image-strip").assertDoesNotExist()
                compose.onNodeWithTag("media-tools-images-tab").assertDoesNotExist()
                assertEquals("元数据页不能等待或读取图片列表", 0, fixture.imageLists.get())
                compose.onNodeWithText("操作", substring = false).assertDoesNotExist()
                compose.onNodeWithTag("metadata-path").assertIsDisplayed()
                assertEquals("https://source.example/Film.strm", compose.onNodeWithTag("metadata-path")
                    .fetchSemanticsNode().config[SemanticsProperties.Text].single().text)

                compose.onNodeWithTag("metadata-title").performTextReplacement(DRAFT)
                compose.onNodeWithTag("metadata-save").assertIsEnabled()
                compose.onNodeWithText("有未保存的修改", substring = false).assertDoesNotExist()
                compose.onNodeWithTag("metadata-back").performClick()
                waitForText("放弃未保存的修改？")
                compose.onNodeWithText("继续编辑", substring = false).performClick()
                VerificationDisplay.awaitWindowReady(compose, "metadata-draft-continued", compose.onNodeWithTag("metadata-title"))
                assertEquals(DRAFT, compose.onNodeWithTag("metadata-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                assertTrue("继续编辑必须保留草稿和当前编辑页", shown.value)
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                waitForText("放弃未保存的修改？")
                compose.onNodeWithText("放弃修改", substring = false).performClick()
                compose.waitUntil(WAIT_MS) { !shown.value }

                // 图片编辑是另一个明确入口，真正卸载文本编辑页后再挂载图片页。
                compose.runOnIdle { page.value = "images"; shown.value = true }
                compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.imageLists.get() == 1 && fixture.imageReads.get() > 0 }
                compose.onNodeWithTag("media-image/Primary").assertIsDisplayed()
                compose.onNodeWithTag("media-image/Logo").assertIsDisplayed()
                val cover = compose.onNodeWithTag("media-image/Primary").fetchSemanticsNode().boundsInRoot
                val logo = compose.onNodeWithTag("media-image/Logo").fetchSemanticsNode().boundsInRoot
                assertEquals("两种图片应处于同一紧凑行", cover.top, logo.top, 1f)
                assertTrue("图片卡应为两列", cover.right <= logo.left)
                compose.onNodeWithTag("media-image-strip").assertDoesNotExist()
                compose.onNodeWithTag("metadata-form").assertDoesNotExist()
                for (type in listOf("Primary", "Logo", "Thumb", "Banner", "Disc", "Art")) {
                    compose.onNodeWithTag("media-images").performScrollToNode(hasTestTag("media-image/$type"))
                    compose.onNodeWithTag("media-image/$type").assertIsDisplayed()
                    compose.onNodeWithTag("media-image-search/$type").assertIsEnabled()
                    compose.onNodeWithTag("media-image-upload/$type").assertIsEnabled()
                }
                compose.onNodeWithTag("media-images").performScrollToNode(hasTestTag("media-image-upload/Backdrop"))
                compose.onNodeWithTag("media-image-upload/Backdrop").assertIsEnabled()
                openBackdropDeletion()
                waitForText("删除背景图？")
                compose.onNodeWithText("取消", substring = false).performClick()
                VerificationDisplay.awaitWindowReady(compose, "metadata-image-delete-cancelled", compose.onNodeWithTag("media-image-delete/Backdrop/0"))
                assertEquals(0, fixture.deletions.size)
                assertEquals(0, changed.get())
                assertEquals(0, fixture.metadataPosts.get())

                // 确认框使用的是先前看到的图；网页端替换后不能删除占用相同索引的新图。
                fixture.backdropTag.set("replacement-backdrop")
                openBackdropDeletion()
                waitForText("删除背景图？")
                compose.onNodeWithText("删除", substring = false).performClick()
                waitForText("图片列表已变化，请重新选择后删除")
                compose.waitForIdle()
                assertEquals("拒绝旧选择后必须发布本次校验的新列表", 2, fixture.imageLists.get())
                assertEquals("不能自动重放删除", 0, fixture.deletions.size)
                assertEquals("没有写入不能宣称媒体已变化", 0, changed.get())
                assertEquals(0, fixture.metadataPosts.get())

                // 对当前新图再次明确确认，才允许单次 DELETE 与成功后的列表重读。
                openBackdropDeletion()
                waitForText("删除背景图？")
                compose.onNodeWithText("删除", substring = false).performClick()
                compose.waitUntil(WAIT_MS) {
                    fixture.assertHealthy()
                    fixture.deletions.size == 1 && fixture.imageLists.get() >= 4 && changed.get() == 1 &&
                        compose.onAllNodesWithText("图片已删除").fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                assertEquals(listOf("Backdrop/0"), fixture.deletions.toList())
                assertEquals("图片操作不能顺带保存元数据草稿", 0, fixture.metadataPosts.get())
                compose.runOnIdle { shown.value = false }
                compose.waitForIdle()
                compose.runOnIdle { page.value = "metadata"; shown.value = true }
                compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); compose.onAllNodesWithTag("metadata-title").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("已明确放弃的草稿不能在独立页面重新进入时恢复", TITLE,
                    compose.onNodeWithTag("metadata-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                compose.onNodeWithTag("metadata-save").assertIsNotEnabled()
                assertEquals(0, fixture.metadataPosts.get())
                fixture.assertHealthy()
            } catch (error: Throwable) {
                failure = error
                recordFailure(error)
                throw error
            } finally {
                try { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); loader.shutdown() }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }

    private fun openBackdropDeletion() {
        compose.onNodeWithTag("media-images").performScrollToNode(hasTestTag("media-image-delete/Backdrop/0"))
        VerificationDisplay.awaitWindowReady(compose, "metadata-image-inline-delete", compose.onNodeWithTag("media-image-delete/Backdrop/0"))
        compose.onNodeWithTag("media-image-delete/Backdrop/0").performClick()
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }

    private fun recordFailure(failure: Throwable) {
        runCatching {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val trees = roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
            val bounds = listOf("media-images", "media-image-delete/Backdrop/0").joinToString { tag ->
                val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                "$tag root=${node.boundsInRoot} window=${node.boundsInWindow}"
            }
            val window = compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                val visible = Rect().also(decor::getWindowVisibleDisplayFrame)
                val navigation = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.navigationBars())
                "decor=${decor.width}x${decor.height} visible=$visible navigation=$navigation"
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val image = File(instrumentation.targetContext.cacheDir, "media-metadata-failure.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            Log.e("PathfinMetadataPresentation", "$bounds $window screenshot=${image.absolutePath}\n$trees", failure)
        }.onFailure { failure.addSuppressed(it) }
    }

    private class Fixture : Closeable {
        val deletions = CopyOnWriteArrayList<String>()
        val metadataPosts = AtomicInteger()
        val imageReads = AtomicInteger()
        val imageLists = AtomicInteger()
        val backdropTag = AtomicReference("synthetic-Backdrop")
        private val closed = AtomicBoolean()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "metadata-presentation-http").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets.add(socket)
                executor.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures.add(error) }
        }, "metadata-presentation-accept").apply { isDaemon = true; start() }

        fun assertHealthy() { failures.peek()?.let { throw AssertionError("元数据图片回环服务失败", it) } }

        private fun serve(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = DataInputStream(connection.getInputStream())
                    val first = line(input) ?: return
                    val request = first.split(' '); check(request.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(input) ?: throw EOFException("请求头未结束")
                        if (header.isEmpty()) break
                        val separator = header.indexOf(':'); check(separator > 0 && headers.size < 128)
                        headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
                    }
                    check(headers["authorization"]?.contains(TOKEN) == true) { "回环请求必须带当前合成账号鉴权" }
                    check("transfer-encoding" !in headers)
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..1_048_576)
                    input.readFully(ByteArray(length))
                    val path = request[1].substringBefore('?')
                    var contentType = "application/json"
                    val (status, bytes) = when {
                        request[0] == "GET" && path == "/Items/$ITEM_ID" -> 200 to JSONObject().put("Id", ITEM_ID)
                            .put("Name", TITLE).put("Type", "Movie").put("CanDelete", false).put("CanDownload", false)
                            .put("Path", "https://source-user:source-password@source.example/Film.strm?signature=private#fragment").toString().toByteArray()
                        request[0] == "GET" && path == "/Items/$ITEM_ID/ExternalIdInfos" -> 200 to JSONArray().toString().toByteArray()
                        request[0] == "GET" && path == "/Items/$ITEM_ID/Images" -> {
                            imageLists.incrementAndGet()
                            val images = JSONArray()
                            for (type in listOf("Primary", "Backdrop", "Logo")) if ("$type/0" !in deletions) {
                                images.put(JSONObject().put("ImageType", type).put("ImageIndex", 0)
                                    .put("ImageTag", if (type == "Backdrop") backdropTag.get() else "synthetic-$type").put("Width", 1).put("Height", 1))
                            }
                            200 to images.toString().toByteArray()
                        }
                        request[0] == "GET" && path in listOf("Primary", "Backdrop", "Logo").map { "/Items/$ITEM_ID/Images/$it/0" } -> {
                            imageReads.incrementAndGet(); contentType = "image/png"
                            200 to Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jvxsAAAAASUVORK5CYII=")
                        }
                        request[0] == "DELETE" && path == "/Items/$ITEM_ID/Images/Backdrop/0" -> {
                            deletions += "Backdrop/0"; 204 to ByteArray(0)
                        }
                        request[0] == "POST" && path == "/Items/$ITEM_ID" -> {
                            metadataPosts.incrementAndGet(); 204 to ByteArray(0)
                        }
                        else -> error("图片回归不允许该 API")
                    }
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) { if (!closed.get()) failures.add(error) }
            finally { sockets.remove(socket) }
        }

        private fun line(input: DataInputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 32_768) {
                val value = input.read()
                if (value < 0) { if (bytes.size() == 0) return null; throw EOFException("请求行未结束") }
                if (value == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(value)
            }
            error("请求头过长")
        }

        override fun close() {
            closed.set(true); server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow(); accept.join(1_000)
        }
    }

    private companion object {
        const val ITEM_ID = "metadata-presentation-item"
        const val TITLE = "Pathfin metadata presentation fixture"
        const val DRAFT = "Unsaved synthetic title"
        const val TOKEN = "synthetic-metadata-presentation-token"
        const val WAIT_MS = 15_000L
    }
}

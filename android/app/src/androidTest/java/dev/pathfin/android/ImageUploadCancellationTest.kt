package dev.pathfin.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.MediaToolsScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 生产图片上传 callback + 跨 APK 授权的真实慢 DocumentsProvider；不访问用户文件。 */
@RunWith(AndroidJUnit4::class)
class ImageUploadCancellationTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == "dev.pathfin.android.verification")
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test fun backCancelsImagePreparationWhileTheProviderIsStillOpening() =
        exerciseCancellation("DELAY_OPEN")

    @Test fun backCancelsImagePreparationWhileTheProviderReadIsBlocked() =
        exerciseCancellation("BLOCK_READ")

    @Test fun cancelledPreparationClosesTheValidImageReturnedByAnUncooperativeProvider() =
        exerciseCancellation("DELAY_OPEN_IGNORE_CANCEL")

    @Test fun validSafImageUploadsItsRealPngAndOnlyTheServerWriteBlocksBack() {
        grantImage("NORMAL").use { document ->
            UploadFixture(blockUpload = true).use { fixture ->
                val editor = showEditor(document, fixture)
                try {
                    val upload = compose.onNodeWithTag("media-image-upload/Primary")
                    VerificationDisplay.awaitWindowReady(compose, "image-upload-normal", upload)
                    upload.performVerificationClick(compose)
                    compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); fixture.uploads.get() == 1 && editor.busy.value }
                    upload.assertIsNotEnabled()
                    assertEquals("image/png", fixture.uploadedMime.get())
                    val bytes = requireNotNull(fixture.uploadedImage.get())
                    val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try { assertEquals(1, image.width); assertEquals(1, image.height) }
                    finally { image.recycle() }
                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    compose.runOnIdle { assertTrue(editor.shown.value); assertEquals(0, editor.backs.get()); assertEquals(0, editor.changed.get()) }

                    fixture.releaseUpload()
                    compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); editor.changed.get() == 1 && !editor.busy.value }
                    upload.assertIsEnabled()
                    assertEquals(1, fixture.uploads.get())
                } finally {
                    fixture.releaseUpload()
                    compose.runOnIdle { editor.shown.value = false }
                    compose.waitForIdle()
                }
            }
        }
    }

    @Test fun imageLargerThanTenMiBIsRejectedBeforeAnyUpload() =
        exerciseRejectedImage("图片不能超过10 MB", imageBytes = 10 * 1024 * 1024 + 1)

    @Test fun pngMimeWithoutImageContentCannotBeUploaded() =
        exerciseRejectedImage("请选择有效的 JPEG、PNG、WebP 或 GIF 图片", payload = "not-a-png".toByteArray())

    private fun exerciseRejectedImage(message: String, imageBytes: Int = 0, payload: ByteArray? = null) {
        grantImage("NORMAL", imageBytes, payload).use { document ->
            UploadFixture().use { fixture ->
                val editor = showEditor(document, fixture)
                try {
                    val upload = compose.onNodeWithTag("media-image-upload/Primary")
                    VerificationDisplay.awaitWindowReady(compose, "image-upload-rejected", upload)
                    upload.performVerificationClick(compose)
                    compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText(message).assertVerificationDisplayed(compose)
                    upload.assertIsEnabled()
                    compose.runOnIdle { assertFalse(editor.busy.value); assertEquals(0, editor.changed.get()) }
                    assertEquals("未通过本地检查不能发送图片", 0, fixture.uploads.get())
                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    compose.waitUntil(2_000) { !editor.shown.value && !editor.busy.value }
                    compose.onNodeWithTag("image-editor-closed").assertVerificationDisplayed(compose)
                    assertEquals(1, editor.backs.get())
                } finally {
                    compose.runOnIdle { editor.shown.value = false }
                    compose.waitForIdle()
                }
            }
        }
    }

    private fun exerciseCancellation(behavior: String) {
        grantImage(behavior).use { document ->
            UploadFixture().use { fixture ->
                val editor = showEditor(document, fixture)
                try {
                    val delayedOpen = behavior.startsWith("DELAY_OPEN")
                    val returnsAfterCancellation = behavior == "DELAY_OPEN_IGNORE_CANCEL"
                    val upload = compose.onNodeWithTag("media-image-upload/Primary")
                    VerificationDisplay.awaitWindowReady(compose, "image-upload-$behavior", upload)
                    upload.performVerificationClick(compose)
                    await("必须真正进入受控 Provider，不能只切换 UI 忙态") {
                        val status = document.status()
                        if (delayedOpen) status.getBoolean("openStarted") && !status.getBoolean("opened")
                        else status.getBoolean("opened") && status.getString("clientLink")?.let(::hasOwnDescriptor) == true && blockedInRead()
                    }
                    var openingWorker: Thread? = null
                    if (returnsAfterCancellation) await("取消前须定位正在进行本次 open 的 SAF worker") {
                        openingWorker = findOpeningWorker()
                        openingWorker != null
                    }
                    upload.assertIsNotEnabled()
                    assertEquals(1, editor.launches.get())
                    compose.runOnIdle { assertFalse("准备文件不能阻止关闭抽屉", editor.busy.value) }
                    assertEquals("文件没有读完之前不能发送上传", 0, fixture.uploads.get())

                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    compose.waitUntil(2_000) { !editor.shown.value && !editor.busy.value }
                    compose.onNodeWithTag("image-editor-closed").assertVerificationDisplayed(compose)
                    assertEquals("一次返回只能离开图片编辑一次", 1, editor.backs.get())
                    assertEquals(0, editor.changed.get())
                    if (returnsAfterCancellation) {
                        await("忽略取消的 Provider 仍须实际收到取消信号") {
                            val status = document.status()
                            status.getBoolean("signalReceived") && status.getBoolean("signalCancelled")
                        }
                        assertFalse("取消不能提前放行此模式的 open", document.status().getBoolean("opened"))
                        assertFalse("本次 worker 在放行前仍应阻塞", workerIsIdleOrExited(requireNotNull(openingWorker)))
                    } else if (behavior == "DELAY_OPEN") await("返回应把取消信号实际送到慢 open") {
                        val status = document.status()
                        status.getBoolean("signalReceived") && status.getBoolean("signalCancelled") && status.getBoolean("openCancelled")
                    } else {
                        val link = requireNotNull(document.status().getString("clientLink"))
                        await("返回应关闭阻塞读取持有的真实 FD") { !hasOwnDescriptor(link) }
                    }
                    // 取消成功之后才允许返回 PNG；迟到的有效图片不能产生服务器写操作。
                    document.release()
                    if (behavior == "BLOCK_READ") await("迟到数据应看到客户端已关闭") { document.status().getBoolean("writerFailed") }
                    if (returnsAfterCancellation) await("本次 worker 须处理迟到 PNG 并释放真实客户端 FD") {
                        val status = document.status()
                        status.getBoolean("opened") && !status.getBoolean("openCancelled") &&
                            workerIsIdleOrExited(requireNotNull(openingWorker)) &&
                            status.getString("clientLink")?.let { !hasOwnDescriptor(it) } == true
                    }
                    instrumentation.waitForIdleSync()
                    fixture.assertHealthy()
                    assertEquals("返回取消后不能继续上传迟到的图片", 0, fixture.uploads.get())
                    assertEquals(0, editor.changed.get())
                } finally {
                    // 旧代码复现失败也释放门闩并卸载页面，不能把阻塞线程留给后续用例。
                    compose.runOnIdle { editor.shown.value = false }
                    compose.waitForIdle()
                    document.release()
                }
            }
        }
    }

    private class Editor {
        val shown = mutableStateOf(true)
        val busy = mutableStateOf(false)
        val changed = AtomicInteger()
        val backs = AtomicInteger()
        val launches = AtomicInteger()
    }

    private fun showEditor(document: Document, fixture: UploadFixture): Editor {
        val editor = Editor()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(compose.activity, input)
                assertEquals("必须调用生产 OpenDocument 合约", Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertTrue(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty().contains("image/png"))
                editor.launches.incrementAndGet()
                // URI 权限来自真实跨 APK ActivityResult；这里只替代系统选择器交互。
                assertTrue("生产 OpenDocument 回调必须收到所选 URI", dispatchResult(requestCode, Activity.RESULT_OK,
                    Intent().setDataAndType(document.uri, "image/png")))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val api = JellyfinApi()
        compose.setContent { PathfinTheme(ThemePreference.LIGHT) {
            BackHandler { editor.backs.incrementAndGet(); editor.shown.value = false }
            if (editor.shown.value) CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MediaToolsScreen(MediaItem("item", "Synthetic image item", "Movie"), fixture.session, api, "images",
                    onChanged = { editor.changed.incrementAndGet() }, onBusyChanged = { editor.busy.value = it })
            } else Text("图片编辑已关闭", Modifier.testTag("image-editor-closed"))
        } }
        compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); compose.onAllNodesWithTag("media-images").fetchSemanticsNodes().isNotEmpty() }
        return editor
    }

    private fun grantImage(behavior: String, imageBytes: Int = 0, payload: ByteArray? = null): Document {
        val key = "image-document-${UUID.randomUUID()}"
        val result = CompletableFuture<Uri>()
        lateinit var unregister: () -> Unit
        compose.activityRule.scenario.onActivity { activity ->
            val launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) { returned ->
                val uri = returned.data?.data
                if (returned.resultCode == Activity.RESULT_OK && uri != null) result.complete(uri)
                else result.completeExceptionally(AssertionError("夹具必须返回图片文档及真实 URI 权限"))
            }
            unregister = launcher::unregister
            launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/png")
                .setComponent(ComponentName(instrumentation.context.packageName, VerificationSettingsDocumentsProvider.GrantActivity::class.java.name))
                .putExtra(VerificationSettingsDocumentsProvider.BEHAVIOR_EXTRA, behavior)
                .putExtra(VerificationSettingsDocumentsProvider.MIME_EXTRA, "image/png")
                .putExtra(VerificationSettingsDocumentsProvider.IMAGE_BYTES_EXTRA, imageBytes)
                .apply { if (payload != null) putExtra(VerificationSettingsDocumentsProvider.PAYLOAD_EXTRA, payload) })
        }
        val uri = result.get(15, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { unregister() }
        assertEquals(VerificationSettingsDocumentsProvider.authority(instrumentation.context), uri.authority)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        assertTrue("Android 必须真实记录本次持久读写授权", context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        })
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags))
        assertEquals("image/png", context.contentResolver.getType(uri))
        VerificationDisplay.prepare()
        return Document(uri)
    }

    private inner class Document(val uri: Uri) : Closeable {
        private val id = DocumentsContract.getDocumentId(uri)
        fun status(): Bundle = requireNotNull(context.contentResolver.call(uri, PREFIX + "status", id, null))
        fun release() { context.contentResolver.call(uri, PREFIX + "release", id, null) }
        override fun close() {
            context.contentResolver.call(uri, PREFIX + "delete", id, null)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
    }

    private fun hasOwnDescriptor(link: String): Boolean {
        check(link.matches(Regex("(?:pipe|socket):\\[\\d+\\]")))
        return File("/proc/self/fd").list().orEmpty().any { id -> runCatching { Os.readlink("/proc/self/fd/$id") == link }.getOrDefault(false) }
    }

    private fun blockedInRead(): Boolean = Thread.getAllStackTraces().any { (thread, stack) ->
        thread.name.startsWith("PathfinSafTransfer-") && stack.any { it.className.endsWith("FileInputStream") && it.methodName.startsWith("read") }
    }

    private fun findOpeningWorker(): Thread? = Thread.getAllStackTraces().entries.singleOrNull { (thread, stack) ->
        thread.name.startsWith("PathfinSafTransfer-") && stack.any {
            it.className == "dev.pathfin.android.data.SafDocumentTransfer\$Operation" && it.methodName == "open"
        }
    }?.key

    private fun workerIsIdleOrExited(worker: Thread): Boolean {
        if (!worker.isAlive) return true
        val stack = worker.stackTrace
        return stack.none { it.className.startsWith("dev.pathfin.android.data.SafDocumentTransfer") } &&
            stack.any { it.className == "java.util.concurrent.ThreadPoolExecutor" && it.methodName == "getTask" }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        throw AssertionError(message)
    }

    private class UploadFixture(blockUpload: Boolean = false) : Closeable {
        val uploads = AtomicInteger()
        val uploadedImage = AtomicReference<ByteArray?>()
        val uploadedMime = AtomicReference<String?>()
        private val uploadRelease = CountDownLatch(if (blockUpload) 1 else 0)
        private val failure = AtomicReference<Throwable?>()
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var closed = false
        val session = Session(ServerConfig("http://127.0.0.1:${server.localPort}", true), "image-upload-synthetic-token", "user", "Synthetic", "device", isAdministrator = true)
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
        fun assertHealthy() { failure.get()?.let { throw AssertionError("合成图片服务断言失败", it) } }
        fun releaseUpload() { uploadRelease.countDown() }
        @Suppress("DEPRECATION") private fun serve(socket: Socket) {
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            val parts = requireNotNull(input.readLine()).split(' ')
            val headers = mutableMapOf<String, String>()
            while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
            assertTrue(headers["authorization"].orEmpty().contains(session.accessToken))
            val count = headers["content-length"]?.toIntOrNull() ?: 0
            check(count in 0..1_048_576)
            val content = ByteArray(count)
            input.readFully(content)
            val path = java.net.URI(parts[1]).path
            val body = when {
                parts[0] == "GET" && path == "/Items/item" -> """{"Id":"item","Name":"Synthetic image item","Type":"Movie","CanDelete":false,"CanDownload":false}"""
                parts[0] == "GET" && path == "/Items/item/Images" -> "[]"
                parts[0] == "POST" && path == "/Items/item/Images/Primary" -> {
                    uploadedImage.set(Base64.getDecoder().decode(content))
                    uploadedMime.set(headers["content-type"])
                    uploads.incrementAndGet()
                    check(uploadRelease.await(WAIT_MS * 2, TimeUnit.MILLISECONDS)) { "合成上传响应未释放" }
                    "{}"
                }
                else -> error("合成图片服务收到未知操作")
            }.toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body); flush()
            }
        }
        override fun close() {
            releaseUpload()
            closed = true; server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            executor.shutdownNow(); assertHealthy()
        }
    }

    private companion object { const val WAIT_MS = 5_000L; const val PREFIX = "pathfin.settings." }
}

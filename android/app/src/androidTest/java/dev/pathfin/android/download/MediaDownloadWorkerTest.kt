package dev.pathfin.android.download

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.VerificationDisplay
import dev.pathfin.android.VerificationSettingsDocumentsProvider
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.DownloadNetwork
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 实际 HTTP→Worker→content URI；只在独立包内创建并删除 IS_PENDING 的合成测试文件。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MediaDownloadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun realFileIsSavedAndCompletionSurvivesStoreRecreation() = runBlocking {
        Fixture().use { fixture ->
            withTask(fixture) { task ->
                val foreground = AtomicReference<ForegroundInfo?>()
                val result = worker(task, foreground).doWork()
                assertEquals(ListenableWorker.Result.success(workDataOf(MediaDownloadWorker.TASK_ID to task.id)), result)
                val completed = requireNotNull(DownloadStore(context).get(task.id))
                assertEquals(DownloadStatus.COMPLETED, completed.status)
                assertEquals(fixture.bytes.size.toLong(), completed.downloadedBytes)
                assertArrayEquals(fixture.bytes, context.contentResolver.openInputStream(android.net.Uri.parse(task.destinationUri))!!.use { it.readBytes() })
                val info = requireNotNull(foreground.get())
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
                assertTrue("下载通知操作应为取消下载；actual=${info.notification.actions.map { "${it.title} [${it.title?.javaClass?.name}]" }}；" +
                    "defaultLocale=${Locale.getDefault().toLanguageTag()}；contextLocales=${context.resources.configuration.locales.toLanguageTags()}",
                    info.notification.actions.any { it.title == "取消下载" })
                assertEquals(1, fixture.detailRequests.get())
                assertEquals(1, fixture.downloadRequests.get())
                val stored = context.getSharedPreferences("pathfin_downloads_v1", Context.MODE_PRIVATE).getString("tasks", "").orEmpty()
                assertFalse(stored.contains("download-test-secret"))
                assertFalse(stored.contains(fixture.baseUrl))
            }
        }
    }

    @Test fun interruptionClearsThePartialFileAndTheNextAttemptFetchesAFreshSource() = runBlocking {
        Fixture(slowFirstDownload = true).use { fixture ->
            withTask(fixture) { task ->
                val first = launch(Dispatchers.Default) { worker(task, AtomicReference()).doWork() }
                try {
                    withTimeout(10_000) {
                        while ((DownloadStore(context).get(task.id)?.downloadedBytes ?: 0) == 0L) delay(30)
                    }
                    withTimeout(5_000) { first.cancelAndJoin() }
                    val interrupted = requireNotNull(DownloadStore(context).get(task.id))
                    assertEquals(DownloadStatus.QUEUED, interrupted.status)
                    assertEquals(0L, interrupted.downloadedBytes)
                    assertEquals(0, context.contentResolver.openInputStream(android.net.Uri.parse(task.destinationUri))!!.use { it.readBytes().size })
                    val result = worker(interrupted, AtomicReference()).doWork()
                    assertEquals(ListenableWorker.Result.success(workDataOf(MediaDownloadWorker.TASK_ID to task.id)), result)
                    assertEquals(2, fixture.detailRequests.get())
                    assertEquals(2, fixture.downloadRequests.get())
                    assertArrayEquals(fixture.bytes, context.contentResolver.openInputStream(android.net.Uri.parse(task.destinationUri))!!.use { it.readBytes() })
                    assertEquals(DownloadStatus.COMPLETED, DownloadStore(context).get(task.id)?.status)
                } finally { first.cancelAndJoin() }
            }
        }
    }

    @Test fun removingAccountDuringTransferCancelsTheCallAndCannotCompleteOrReviveTheTask() = runBlocking {
        Fixture(slowFirstDownload = true).use { fixture ->
            withTask(fixture) { task ->
                val first = launch(Dispatchers.Default) { worker(task, AtomicReference()).doWork() }
                try {
                    withTimeout(10_000) {
                        while ((DownloadStore(context).get(task.id)?.downloadedBytes ?: 0) == 0L) delay(30)
                    }
                    SessionStore(context).removeProfile(task.profileId)
                    MediaDownloads.cancelProfile(context, task.profileId)
                    withTimeout(5_000) { first.join() }
                    assertTrue("删除账号应取消实际媒体传输", first.isCancelled)
                    val cancelled = requireNotNull(DownloadStore(context).get(task.id))
                    assertEquals(DownloadStatus.CANCELLED, cancelled.status)
                    assertEquals(0L, cancelled.downloadedBytes)
                    assertEquals(0, context.contentResolver.openInputStream(android.net.Uri.parse(task.destinationUri))!!.use { it.readBytes().size })
                    assertEquals(1, fixture.downloadRequests.get())
                    // 独立重新执行旧 WorkData 也不能重新取得片源或将记录变回 QUEUED。
                    worker(cancelled, AtomicReference()).doWork()
                    assertEquals(DownloadStatus.CANCELLED, DownloadStore(context).get(task.id)?.status)
                    assertEquals(1, fixture.downloadRequests.get())
                    withTask(fixture) { other ->
                        assertEquals(ListenableWorker.Result.success(workDataOf(MediaDownloadWorker.TASK_ID to other.id)),
                            worker(other, AtomicReference()).doWork())
                        assertEquals(DownloadStatus.COMPLETED, DownloadStore(context).get(other.id)?.status)
                    }
                } finally { first.cancelAndJoin() }
            }
        }
    }

    @Test fun missingAccountWithoutUiCancellationIsPermanentlyCancelledBeforeNetworking() = runBlocking {
        Fixture().use { fixture ->
            withTask(fixture) { task ->
                SessionStore(context).removeProfile(task.profileId)
                val failure = runCatching { worker(task, AtomicReference()).doWork() }.exceptionOrNull()
                assertTrue("Worker 必须独立检测移除的账号", failure is CancellationException)
                assertEquals(DownloadStatus.CANCELLED, DownloadStore(context).get(task.id)?.status)
                assertEquals(0, fixture.detailRequests.get())
                assertEquals(0, fixture.downloadRequests.get())
            }
        }
    }

    @Test fun cancellingSlowSafOpenEndsWorkerWaitingAndCancelsTheRealProvider() = runBlocking {
        Fixture().use { fixture -> withSafTask(fixture, "DELAY_OPEN") { task, uri ->
            val result = AtomicReference<ListenableWorker.Result?>()
            val first = launch(Dispatchers.Default) { result.set(worker(task, AtomicReference()).doWork()) }
            try {
                awaitSaf { safStatus(uri).getBoolean("openStarted") }
                withTimeout(3_000) { first.cancelAndJoin() }
                awaitSaf { safStatus(uri).getBoolean("openCancelled") }
                assertTrue("取消必须经真实 CancellationSignal 到达 Provider", safStatus(uri).getBoolean("signalCancelled"))
                assertEquals(null, result.get())
                assertEquals(DownloadStatus.QUEUED, DownloadStore(context).get(task.id)?.status)
                assertEquals(1, fixture.downloadRequests.get())
            } finally {
                safCall(uri, "release")
                withTimeout(5_000) { first.cancelAndJoin() }
            }
        } }
    }

    @Test fun cancellingBlockedSafWriteClosesItsDescriptorAndCannotComplete() = runBlocking {
        Fixture().use { fixture -> withSafTask(fixture, "BLOCK_WRITE") { task, uri ->
            val result = AtomicReference<ListenableWorker.Result?>()
            val first = launch(Dispatchers.Default) { result.set(worker(task, AtomicReference()).doWork()) }
            try {
                awaitSaf { safStatus(uri).getBoolean("opened") && Thread.getAllStackTraces().values.any { stack ->
                    stack.any { it.className.endsWith("FileOutputStream") && it.methodName == "write" } &&
                        stack.any { it.className.contains("MediaDownloadClient") }
                } }
                val link = requireNotNull(safStatus(uri).getString("clientLink"))
                assertTrue(hasOwnDescriptor(link))
                withTimeout(3_000) { first.cancelAndJoin() }
                awaitSaf { !hasOwnDescriptor(link) }
                assertEquals(null, result.get())
                assertEquals(DownloadStatus.QUEUED, DownloadStore(context).get(task.id)?.status)
                assertEquals(1, fixture.downloadRequests.get())
            } finally {
                safCall(uri, "release")
                withTimeout(5_000) { first.cancelAndJoin() }
            }
        } }
    }

    @Test fun cancelledLateSafOpenKeepsTheTargetLeasedUntilItActuallyFinishes() = runBlocking {
        Fixture().use { fixture -> withSafTask(fixture, "DELAY_WRITE_OPEN_IGNORE_CANCEL") { task, uri ->
            val store = DownloadStore(context)
            val first = launch(Dispatchers.Default) { worker(task, AtomicReference()).doWork() }
            try {
                awaitSaf { safStatus(uri).getBoolean("openStarted") }
                withTimeout(3_000) { first.cancelAndJoin() }
                awaitSaf { safStatus(uri).getBoolean("signalCancelled") }
                val retry = requireNotNull(store.update(task.id, task.workId) {
                    it.copy(workId = UUID.randomUUID().toString(), status = DownloadStatus.QUEUED, error = null)
                })
                val blocked = withTimeout(3_000) { worker(retry, AtomicReference()).doWork() }
                assertEquals("旧 open 未结束时，重试应返回可恢复失败而非等待或新写入", ListenableWorker.Result.failure(), blocked)
                assertEquals(DownloadStatus.FAILED, store.get(task.id)?.status)
                assertEquals(1, safStatus(uri).getInt("opens"))
                assertEquals("目标仍被占用时不能重复获取片源", 1, fixture.downloadRequests.get())
                safCall(uri, "release")
                awaitSaf { safStatus(uri).getBoolean("opened") && safStatus(uri).getBoolean("writeComplete") }
                // Provider 的迟到 FD 关闭后，原 worker 不能再清空新代次完成的文件。
                withTimeout(5_000) {
                    while (store.get(task.id)?.status != DownloadStatus.COMPLETED) {
                        val current = requireNotNull(store.get(task.id))
                        val next = requireNotNull(store.update(task.id, current.workId) {
                            it.copy(workId = UUID.randomUUID().toString(), status = DownloadStatus.QUEUED, error = null)
                        })
                        worker(next, AtomicReference()).doWork()
                        if (store.get(task.id)?.status != DownloadStatus.COMPLETED) delay(25)
                    }
                }
                assertEquals(2, fixture.downloadRequests.get())
                awaitSaf { safStatus(uri).getInt("bytesWritten") == fixture.bytes.size }
                assertEquals(fixture.bytes.size.toLong(), store.get(task.id)?.downloadedBytes)
            } finally {
                safCall(uri, "release")
                withTimeout(5_000) { first.cancelAndJoin() }
            }
        } }
    }

    @Test fun networkReplacementWaitsForThePreviousTargetToCloseWithinTheHandoffBudget() = runBlocking {
        Fixture().use { fixture -> withSafTask(fixture, "DELAY_WRITE_OPEN_IGNORE_CANCEL") { task, uri ->
            val store = DownloadStore(context)
            val first = launch(Dispatchers.Default) { worker(task, AtomicReference()).doWork() }
            try {
                awaitSaf { safStatus(uri).getBoolean("openStarted") }
                val replacement = store.replaceNetwork(DownloadNetwork.UNMETERED).single().second
                withTimeout(3_000) { first.cancelAndJoin() }
                awaitSaf { safStatus(uri).getBoolean("signalCancelled") }
                val next = async(Dispatchers.Default) { worker(replacement, AtomicReference()).doWork() }
                try {
                    // 旧 open 即将结束时，正常重排等待真实租约交接，而非立即失败或开始并发取流。
                    delay(150)
                    assertFalse("租约交接预算内不能直接将正常重排标为失败", next.isCompleted)
                    assertEquals(1, fixture.downloadRequests.get())
                    assertEquals(1, safStatus(uri).getInt("opens"))
                    safCall(uri, "release")
                    val result = withTimeout(5_000) { next.await() }
                    assertEquals(ListenableWorker.Result.success(workDataOf(MediaDownloadWorker.TASK_ID to task.id)), result)
                    assertEquals(DownloadStatus.COMPLETED, store.get(task.id)?.status)
                    assertEquals(replacement.workId, store.get(task.id)?.workId)
                    assertEquals(2, fixture.downloadRequests.get())
                    awaitSaf { safStatus(uri).getInt("bytesWritten") == fixture.bytes.size }
                } finally { safCall(uri, "release"); withTimeout(5_000) { next.cancelAndJoin() } }
            } finally { safCall(uri, "release"); withTimeout(5_000) { first.cancelAndJoin() } }
        } }
    }

    private fun worker(task: DownloadTask, foreground: AtomicReference<ForegroundInfo?>): MediaDownloadWorker =
        TestListenableWorkerBuilder<MediaDownloadWorker>(context)
            .setId(UUID.fromString(task.workId)).setInputData(workDataOf(MediaDownloadWorker.TASK_ID to task.id))
            .setForegroundUpdater { _, _, info ->
                foreground.set(info)
                CallbackToFutureAdapter.getFuture<Void> { completer -> completer.set(null); "download-foreground-test" }
            }.build()

    private suspend fun withTask(fixture: Fixture, destination: Uri? = null, block: suspend (DownloadTask) -> Unit) {
        check(context.packageName == "dev.pathfin.android.verification") { "真实下载回归只允许 verification 独立测试包" }
        val id = UUID.randomUUID().toString()
        val uri = destination ?: context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Pathfin-verification-$id.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })
        assertNotNull(uri)
        val profileId = "download-verification-$id"
        try {
            SessionStore(context).upsertProfile(ServerProfile(profileId, Session(
                ServerConfig(fixture.baseUrl, allowInsecureHttp = true), "download-test-secret", "user", "Test", "test-device", canDownload = true,
            ), "Download verification"))
            val task = DownloadTask(id, UUID.randomUUID().toString(), profileId, "item", "Test movie", uri.toString())
            DownloadStore(context).insert(task)
            block(task)
        } finally {
            DownloadStore(context).remove(id)
            SessionStore(context).removeProfile(profileId)
            if (destination == null && uri != null) context.contentResolver.delete(uri, null, null)
        }
    }

    private suspend fun withSafTask(fixture: Fixture, behavior: String, block: suspend (DownloadTask, Uri) -> Unit) {
        check(context.packageName == "dev.pathfin.android.verification")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        VerificationDisplay.prepare()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val result = CompletableFuture<Uri>()
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("download-saf-${UUID.randomUUID()}",
                    ActivityResultContracts.StartActivityForResult()) { returned ->
                    val uri = returned.data?.data
                    if (returned.resultCode == Activity.RESULT_OK && uri != null) result.complete(uri)
                    else result.completeExceptionally(AssertionError("下载夹具必须返回真实 URI 授权"))
                }
                launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .setComponent(ComponentName(instrumentation.context.packageName, VerificationSettingsDocumentsProvider.GrantActivity::class.java.name))
                    .putExtra(VerificationSettingsDocumentsProvider.BEHAVIOR_EXTRA, behavior))
            }
            val uri = result.get(15, TimeUnit.SECONDS)
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try { withTask(fixture, uri) { block(it, uri) } }
            finally { safCall(uri, "release"); safCall(uri, "delete") }
        }
    }

    private fun safCall(uri: Uri, operation: String): Bundle? = context.contentResolver.call(uri,
        "pathfin.settings.$operation", DocumentsContract.getDocumentId(uri), null)
    private fun safStatus(uri: Uri): Bundle = requireNotNull(safCall(uri, "status"))
    private suspend fun awaitSaf(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(20) }
    private fun hasOwnDescriptor(link: String): Boolean = File("/proc/self/fd").list().orEmpty().any { id ->
        runCatching { Os.readlink("/proc/self/fd/$id") == link }.getOrDefault(false)
    }

    private class Fixture(private val slowFirstDownload: Boolean = false) : Closeable {
        val bytes = ByteArray(8192) { (it % 251).toByte() }
        val detailRequests = AtomicInteger()
        val downloadRequests = AtomicInteger()
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}/jellyfin"
        private val executor = Executors.newCachedThreadPool()
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        @Volatile private var closed = false

        init {
            executor.execute {
                while (!closed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += socket
                    executor.execute { runCatching { serve(socket) }; sockets -= socket }
                }
            }
        }

        private fun serve(socket: Socket) = socket.use {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = reader.readLine() ?: return@use
            val path = first.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            while (!reader.readLine().isNullOrEmpty()) { }
            val output = socket.getOutputStream()
            if (path == "/jellyfin/Items/item") {
                detailRequests.incrementAndGet()
                val body = JSONObject().put("Id", "item").put("Name", "Test movie").put("Type", "Movie")
                    .put("Path", "/media/movie.mp4").put("Container", "mp4").put("CanDownload", true)
                    .put("MediaSources", JSONArray().put(JSONObject().put("Id", "source").put("Protocol", "File")
                        .put("Path", "/media/movie.mp4").put("Container", "mp4").put("Size", bytes.size)))
                    .toString().toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(body)
            } else if (path == "/jellyfin/Items/item/Download") {
                val attempt = downloadRequests.incrementAndGet()
                val size = if (slowFirstDownload && attempt == 1) 16 * 1024 * 1024 else bytes.size
                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                if (slowFirstDownload && attempt == 1) {
                    val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
                    repeat(size / chunk.size) { output.write(chunk); output.flush(); Thread.sleep(40) }
                } else output.write(bytes)
            } else output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }

        override fun close() {
            closed = true
            server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}

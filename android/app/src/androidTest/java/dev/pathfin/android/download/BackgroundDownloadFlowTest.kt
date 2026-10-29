package dev.pathfin.android.download

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 enqueue → WorkManager → SystemForegroundService → 跨 APK、持久授权的 SAF 文档。
 * 先启动 prepare_download_fixture.py 并 reverse 8111/8112，给 verification 通知权限。
 * 正常4项。外部进程回归用 -e pathfin.downloadRestartPhase prepare / verify 分两次运行，见计划。
 * 从不取消其他任务、清空存储或删除非本用例创建的文档。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackgroundDownloadFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val store get() = DownloadStore(context)
    private val workManager get() = WorkManager.getInstance(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val api: String get() {
        val value = InstrumentationRegistry.getArguments().getString("pathfin.downloadApi") ?: DEFAULT_API
        val parsed = value.toHttpUrl()
        check(parsed.scheme == "http" && parsed.host == "127.0.0.1" && parsed.encodedPath == "/jellyfin" && parsed.query == null && parsed.fragment == null) {
            "后台下载回归只允许独立回环夹具 /jellyfin"
        }
        return value
    }

    @Test fun actualForegroundServiceReportsProgressAndFinishesAfterTheActivityGoesHome() = normalRun {
        withDownload { fixture, scenario ->
            val task = begin(fixture)
            val active = awaitProgress(task)
            val notification = awaitForegroundNotification(task)
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertTrue(notification.extras.getInt(Notification.EXTRA_PROGRESS) in 1..99)
            assertTrue(notification.actions.any { it.title.toString() == "取消下载" })
            assertRealForegroundService()
            sendActivityHome(scenario)
            await("测试 Activity 应进入后台") { scenario.state == Lifecycle.State.CREATED }
            await("后台时必须继续更新真实下载进度") {
                val current = store.get(task.id)
                current?.status == DownloadStatus.DOWNLOADING && current.downloadedBytes > active.downloadedBytes + 256 * 1024
            }
            awaitCompleted(task, fixture)
            assertEquals("完成后仍由后台传输，测试未重开 Activity", Lifecycle.State.CREATED, scenario.state)
            await("真实下载完成通知") { notifications.activeNotifications.any { it.id == completionNotificationId(task) } }
        }
    }

    @Test fun actualNotificationCancelStopsTheSocketAndTruncatesThePartialDocument() = normalRun {
        withDownload { fixture, _ ->
            val task = begin(fixture)
            awaitProgress(task)
            val cancellation = awaitForegroundNotification(task).actions.single { it.title.toString() == "取消下载" }.actionIntent
            assertTrue(length(fixture.uri) > 0)
            cancellation.send()
            awaitCancelledAndCleared(task, fixture)
            val status = awaitStreamStopped(fixture.itemId)
            assertFalse(status.getJSONArray("Attempts").getJSONObject(0).getBoolean("Completed"))
            assertTrue("系统取消必须真正关闭慢流连接", status.getJSONArray("Attempts").getJSONObject(0).getBoolean("ClientDisconnected"))
            runBlocking { MediaDownloads.recover(context) }
            assertEquals("通知取消必须落为唯一持久状态，恢复不得复活", DownloadStatus.CANCELLED, DownloadStore(context).get(task.id)?.status)
            assertEquals(WorkInfo.State.CANCELLED, work(task)?.state)
            assertEquals("恢复不能获取另一条临时地址", status.getInt("PlaybackRequests"), status(fixture.itemId).getInt("PlaybackRequests"))
        }
    }

    @Test fun aRealFailedWorkerClearsItsOutputAndRetryObtainsANewTemporaryUrl() = normalRun {
        withDownload(failFirst = true) { fixture, _ ->
            val first = begin(fixture)
            awaitProgress(first)
            await("首次慢流断开必须得到 FAILED，而非误报完成") {
                work(first)?.state == WorkInfo.State.FAILED && store.get(first.id)?.status == DownloadStatus.FAILED
            }
            assertEquals(0L, store.get(first.id)!!.downloadedBytes)
            assertEquals("失败半成品应清空", 0L, length(fixture.uri))
            runBlocking { MediaDownloads.retry(context, first.id) }
            val retried = requireNotNull(store.get(first.id))
            assertNotEquals(first.workId, retried.workId)
            awaitCompleted(retried, fixture)
            val status = status(fixture.itemId)
            assertEquals(2, status.getInt("DetailRequests"))
            assertEquals(2, status.getInt("PlaybackRequests"))
            val attempts = status.getJSONArray("Attempts")
            assertEquals(2, attempts.length())
            assertTrue(attempts.getJSONObject(0).getBoolean("ForcedFailure"))
            assertNotEquals(attempts.getJSONObject(0).getInt("Serial"), attempts.getJSONObject(1).getInt("Serial"))
            assertTrue(attempts.getJSONObject(1).getBoolean("Completed"))
        }
    }

    @Test fun removingTheActualSavedAccountCancelsSystemWorkAndCannotRestartItsOldStream() = normalRun {
        withDownload { fixture, _ ->
            val task = begin(fixture)
            awaitProgress(task)
            awaitForegroundNotification(task)
            SessionStore(context).removeProfile(fixture.profileId)
            runBlocking { MediaDownloads.recover(context) }
            awaitCancelledAndCleared(task, fixture)
            val stopped = awaitStreamStopped(fixture.itemId)
            assertTrue(stopped.getJSONArray("Attempts").getJSONObject(0).getBoolean("ClientDisconnected"))
            val failure = runCatching { runBlocking { MediaDownloads.retry(context, task.id) } }.exceptionOrNull()
            assertTrue("移除账号后不能重新取得片源", failure is IllegalStateException)
            assertEquals(stopped.getInt("PlaybackRequests"), status(fixture.itemId).getInt("PlaybackRequests"))
            assertEquals(DownloadStatus.CANCELLED, store.get(task.id)?.status)
        }
    }

    /** root 收到 READY 后在外部停止 verification 进程；此方法从不主动杀进程。 */
    @Test fun prepareExternalProcessRestartHandshake() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(RESTART_PHASE) == "prepare")
        checkPrerequisites()
        try {
            withDownload(size = 64L * 1024 * 1024, delayMs = 80) { fixture, _ ->
                val task = begin(fixture)
                awaitProgress(task)
                awaitForegroundNotification(task)
                assertRealForegroundService()
                checkpoint().writeText(JSONObject().put("taskId", task.id).put("workId", task.workId)
                    .put("profileId", fixture.profileId).put("itemId", fixture.itemId).put("uri", fixture.uri.toString())
                    .put("bytes", fixture.expectedBytes).put("sha256", fixture.expectedSha256).toString())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "PATHFIN_DOWNLOAD_RESTART_READY\n")
                    putString("pathfin.download.task", task.id)
                    putString("pathfin.download.checkpoint", checkpoint().name)
                })
                val deadline = SystemClock.elapsedRealtime() + 45_000
                while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                fail("root 未在45秒握手窗口内停止外部 verification 进程；已清理合成任务")
            }
        } finally {
            // 若 root 真的停止本进程，此 finally 不会执行，checkpoint/持久授权用于下一次验证。
            checkpoint().delete()
            closeClient()
        }
    }

    @Test fun verifyCompletionAfterExternalProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(RESTART_PHASE) == "verify")
        checkPrerequisites()
        check(checkpoint().isFile) { "必须先运行 prepare 握手并由 root 外部停止进程" }
        val value = JSONObject(checkpoint().readText())
        val fixture = Fixture(value.getString("profileId"), value.getString("itemId"), Uri.parse(value.getString("uri")),
            value.getLong("bytes"), value.getString("sha256"))
        try {
            assertPersistedGrant(fixture.uri)
            val task = requireNotNull(store.get(value.getString("taskId")))
            assertEquals(value.getString("workId"), task.workId)
            awaitCompleted(task, fixture, timeout = 180_000)
            val restored = status(fixture.itemId)
            assertTrue("新进程必须重新获取临时地址", restored.getInt("PlaybackRequests") >= 2)
            val attempts = restored.getJSONArray("Attempts")
            assertTrue(attempts.length() >= 2)
            assertNotEquals(attempts.getJSONObject(0).getInt("Serial"), attempts.getJSONObject(attempts.length() - 1).getInt("Serial"))
        } finally {
            cleanup(fixture)
            checkpoint().delete()
            closeClient()
        }
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    fun notificationPermissionDeniedStillAllowsActualForegroundDownload() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.notificationsDenied") == "true")
        checkPrerequisites(requireNotifications = false)
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            "必须由外部仅撤销 verification 通知权限后运行，不能把已授权测试当作拒权验证"
        }
        try {
            withDownload { fixture, scenario ->
                val task = begin(fixture)
                awaitProgress(task)
                assertRealForegroundService()
                sendActivityHome(scenario)
                await("Activity 应进入后台") { scenario.state == Lifecycle.State.CREATED }
                awaitCompleted(task, fixture)
            }
        } finally { closeClient() }
    }

    /** 启动系统桌面并由下方生命周期/持续进度断言确认真实后台传输。 */
    private fun sendActivityHome(scenario: ActivityScenario<ComponentActivity>) {
        scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun normalRun(block: () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString(RESTART_PHASE) == null)
        checkPrerequisites()
        try { block() } finally { closeClient() }
    }

    private fun checkPrerequisites(requireNotifications: Boolean = true) {
        check(context.packageName == VerificationDownloadDocumentsProvider.TARGET_PACKAGE) { "只能操作 verification 测试包" }
        dev.pathfin.android.VerificationDisplay.prepare()
        check(!requireNotifications || Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "请 root 先授予 verification POST_NOTIFICATIONS，回归必须检查真实通知"
        }
        check(LocalNetworkPermission.isGranted(context)) { "请先授予 verification 局域网访问权限" }
        check(jsonRequest("/health").optBoolean("DownloadFlowFixture")) { "只能使用专属 8111/8112 合成下载夹具" }
    }

    private fun withDownload(failFirst: Boolean = false, size: Long = 16L * 1024 * 1024, delayMs: Int = 35,
        block: (Fixture, ActivityScenario<ComponentActivity>) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val id = UUID.randomUUID().toString()
            val uri = createGrantedDocument(scenario, id)
            val profileId = "background-download-$id"
            var fixture = Fixture(profileId, "bg-$id", uri, size, "")
            try {
                val info = jsonRequest("/__fixture__/prepare", JSONObject().put("itemId", fixture.itemId).put("size", size).put("delayMs", delayMs).put("failFirst", failFirst))
                fixture = fixture.copy(expectedBytes = info.getLong("Bytes"), expectedSha256 = info.getString("Sha256"))
                SessionStore(context).upsertProfile(ServerProfile(profileId, Session(
                    ServerConfig(api, allowInsecureHttp = true), FIXTURE_TOKEN, "fixture-user", "Synthetic user", "fixture-device",
                    serverId = "background-download-fixture", canDownload = true,
                ), "Synthetic background download"))
                await("授权返回后恢复前台 Activity") { scenario.state == Lifecycle.State.RESUMED }
                block(fixture, scenario)
            } finally { cleanup(fixture) }
        }
    }

    private fun createGrantedDocument(scenario: ActivityScenario<ComponentActivity>, id: String): Uri {
        val result = CompletableFuture<Uri>()
        scenario.onActivity { activity ->
            val launcher = activity.activityResultRegistry.register("download-document-$id", ActivityResultContracts.StartActivityForResult()) { returned ->
                val uri = returned.data?.data
                if (returned.resultCode == Activity.RESULT_OK && uri != null) result.complete(uri)
                else result.completeExceptionally(AssertionError("测试文档 Activity 未返回真实 URI 授权"))
            }
            launcher.launch(Intent().setComponent(ComponentName(instrumentation.context.packageName,
                VerificationDownloadDocumentsProvider.GrantActivity::class.java.name))
                .putExtra(VerificationDownloadDocumentsProvider.DOCUMENT_ID_EXTRA, "$id.mp4"))
        }
        val uri = result.get(15, TimeUnit.SECONDS)
        val expectedAuthority = VerificationDownloadDocumentsProvider.authority(instrumentation.context)
        check(uri.scheme == "content" && uri.authority == expectedAuthority && DocumentsContract.getDocumentId(uri) == "$id.mp4")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertPersistedGrant(uri)
        return uri
    }

    private fun assertPersistedGrant(uri: Uri) {
        assertTrue("必须由 Android 真正记录持久读写授权", context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        })
    }

    private fun begin(fixture: Fixture): DownloadTask {
        val id = runBlocking { MediaDownloads.enqueue(context, fixture.profileId, fixture.itemId, "Synthetic ${fixture.itemId}", fixture.uri) }
        return requireNotNull(store.get(id))
    }

    private fun awaitProgress(task: DownloadTask): DownloadTask {
        await("真实 WorkManager 下载进度") {
            val current = store.get(task.id)
            checkNotFailed(current)
            current?.status == DownloadStatus.DOWNLOADING && current.downloadedBytes >= 512 * 1024 &&
                work(task)?.let { it.state == WorkInfo.State.RUNNING && it.progress.getLong("bytes", 0) > 0 } == true
        }
        return requireNotNull(store.get(task.id))
    }

    private fun awaitForegroundNotification(task: DownloadTask): Notification {
        await("系统实际发布下载前台通知") {
            notifications.activeNotifications.any { it.id == notificationId(task) &&
                it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 && it.notification.extras.getInt(Notification.EXTRA_PROGRESS) in 1..99 }
        }
        return notifications.activeNotifications.single { it.id == notificationId(task) }.notification
    }

    @Suppress("DEPRECATION")
    private fun assertRealForegroundService() {
        val services = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        assertTrue("必须实际运行系统 WorkManager 前台服务", services.any {
            it.service.packageName == context.packageName && it.service.className == "androidx.work.impl.foreground.SystemForegroundService" && it.foreground
        })
    }

    private fun awaitCompleted(task: DownloadTask, fixture: Fixture, timeout: Long = WAIT_MS) {
        await("真实下载应完整完成", timeout) {
            val value = store.get(task.id)
            checkNotFailed(value)
            value?.status == DownloadStatus.COMPLETED && work(task)?.state == WorkInfo.State.SUCCEEDED
        }
        val complete = requireNotNull(DownloadStore(context).get(task.id))
        assertEquals(fixture.expectedBytes, complete.downloadedBytes)
        assertEquals(fixture.expectedBytes, complete.totalBytes)
        assertEquals(fixture.expectedBytes, length(fixture.uri))
        assertEquals("完整流式文件必须逐字节匹配 SHA-256", fixture.expectedSha256, sha256(fixture.uri))
        val server = awaitStreamStopped(fixture.itemId)
        assertEquals(0, server.getInt("HeaderLeaks"))
        val attempts = server.getJSONArray("Attempts")
        assertEquals(fixture.expectedBytes, attempts.getJSONObject(attempts.length() - 1).getLong("SentBytes"))
        val persisted = context.getSharedPreferences("pathfin_downloads_v1", Context.MODE_PRIVATE).getString("tasks", "").orEmpty()
        assertFalse(persisted.contains(FIXTURE_TOKEN))
        assertFalse(persisted.contains("/stream/"))
    }

    private fun awaitCancelledAndCleared(task: DownloadTask, fixture: Fixture) {
        await("真实系统任务取消后清空半成品") {
            work(task)?.state == WorkInfo.State.CANCELLED && store.get(task.id)?.let {
                it.status == DownloadStatus.CANCELLED && it.downloadedBytes == 0L
            } == true && length(fixture.uri) == 0L
        }
    }

    private fun awaitStreamStopped(itemId: String): JSONObject {
        await("服务端慢流连接应停止") { status(itemId).getInt("ActiveStreams") == 0 }
        return status(itemId)
    }

    private fun checkNotFailed(task: DownloadTask?) {
        if (task?.status == DownloadStatus.FAILED) throw AssertionError("合成下载失败：${task.error}")
    }

    private fun work(task: DownloadTask): WorkInfo? = workManager.getWorkInfoById(UUID.fromString(task.workId)).get(5, TimeUnit.SECONDS)
    private fun notificationId(task: DownloadTask) = (UUID.fromString(task.workId).hashCode() and 0x0fffffff) + 100
    private fun completionNotificationId(task: DownloadTask) = notificationId(task) xor 0x40000000
    private fun length(uri: Uri): Long = context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
    private fun checkpoint() = File(context.filesDir, "background-download-restart.json")
    private fun status(itemId: String): JSONObject = jsonRequest("/__fixture__/status?item=$itemId")

    private fun jsonRequest(path: String, body: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(api + path).apply {
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "独立下载夹具请求失败：HTTP ${response.code}" }
            JSONObject(response.body.string())
        }
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanup(fixture: Fixture) {
        val tasks = store.all().filter { it.profileId == fixture.profileId && it.itemId == fixture.itemId }
        tasks.forEach { task ->
            runCatching { runBlocking { MediaDownloads.cancel(context, task.id, task.workId) } }
            runCatching { workManager.cancelWorkById(UUID.fromString(task.workId)).result.get(5, TimeUnit.SECONDS) }
            store.remove(task.id)
            notifications.cancel(notificationId(task))
            notifications.cancel(completionNotificationId(task))
        }
        SessionStore(context).removeProfile(fixture.profileId)
        val expectedAuthority = VerificationDownloadDocumentsProvider.authority(instrumentation.context)
        check(fixture.uri.authority == expectedAuthority && DocumentsContract.getDocumentId(fixture.uri).matches(Regex("[a-f0-9-]{36}\\.mp4")))
        try { DocumentsContract.deleteDocument(context.contentResolver, fixture.uri) }
        finally {
            runCatching { context.contentResolver.releasePersistableUriPermission(fixture.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            assertFalse("用例创建的持久授权必须回收", context.contentResolver.persistedUriPermissions.any { it.uri == fixture.uri })
        }
    }

    private fun await(message: String, timeout: Long = WAIT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(75)
        }
        fail(message)
    }

    private fun closeClient() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    private data class Fixture(val profileId: String, val itemId: String, val uri: Uri, val expectedBytes: Long, val expectedSha256: String)
    private companion object {
        const val DEFAULT_API = "http://127.0.0.1:8111/jellyfin"
        const val FIXTURE_TOKEN = "pathfin-download-flow-fixture-token"
        const val RESTART_PHASE = "pathfin.downloadRestartPhase"
        const val WAIT_MS = 45_000L
    }
}

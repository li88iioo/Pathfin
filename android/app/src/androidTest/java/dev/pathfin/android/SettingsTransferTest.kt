package dev.pathfin.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.ImportedSettings
import dev.pathfin.android.data.SettingsTransfer
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真 DocumentsProvider / Binder CancellationSignal / 管道 FD；只允许 verification UID。 */
@RunWith(AndroidJUnit4::class)
class SettingsTransferTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun cancellingDelayedOpenEndsMainWaitingAndReachesTheProviderCancellationSignal() = fixture("DELAY_OPEN") { document ->
        val imports = AtomicInteger()
        TransferRun { SettingsTransfer.read(context, document.uri); imports.incrementAndGet() }.use { run ->
            run.awaitProvider("Provider 必须真正进入慢 open") { document.status().getBoolean("openStarted") }
            assertTrue("真实 openDocument 必须收到非空 CancellationSignal", document.status().getBoolean("signalReceived"))
            run.cancelAndAssertImmediate()
            assertEquals("取消后不允许调用导入", 0, imports.get())
            await("CancellationSignal 必须实际到达跨进程 Provider") {
                val status = document.status()
                status.getBoolean("signalCancelled") && status.getBoolean("openCancelled")
            }
            assertFalse("延迟 open 被取消不应返回 FD", document.status().getBoolean("opened"))
        }
    }

    @Test fun cancellingBlockedReadClosesItsRealDescriptorAndCannotImportAfterProviderReturns() = fixture("BLOCK_READ") { document ->
        val imports = AtomicInteger()
        TransferRun { SettingsTransfer.read(context, document.uri); imports.incrementAndGet() }.use { run ->
            run.awaitProvider("必须真正阻塞在已经打开的文件读取") {
                document.status().getBoolean("opened") && transferBlockedIn("FileInputStream", "read")
            }
            val clientLink = requireNotNull(document.status().getString("clientLink"))
            assertTrue("读取须持有实际管道 FD", hasOwnDescriptor(clientLink))
            run.cancelAndAssertImmediate()
            assertEquals(0, imports.get())
            await("后台取消必须实际关闭本次读 FD") { !hasOwnDescriptor(clientLink) }
            // 取消返回后才允许 Provider 写有效备份。读 FD 必须已关闭，迟到数据也不能导入。
            document.release()
            await("Provider 写入端必须观察到客户端 FD 已关闭") { document.status().getBoolean("writerFailed") }
            instrumentation.waitForIdleSync()
            assertEquals(0, imports.get())
            assertExecutorBounds()
        }
    }

    @Test fun cancellingBlockedExportClosesItsSocketAndNeverReportsSuccess() = fixture("BLOCK_WRITE") { document ->
        val preferences = AppPreferences(preferredSubtitleLanguage = "x".repeat(60_000))
        TransferRun { SettingsTransfer.write(context, document.uri, preferences, ThemePreference.DARK) }.use { run ->
            run.awaitProvider("必须真正阻塞在小发送缓冲区的写入") {
                document.status().getBoolean("opened") && transferBlockedIn("FileOutputStream", "write")
            }
            val clientLink = requireNotNull(document.status().getString("clientLink"))
            assertTrue("写入须持有实际 socket FD", hasOwnDescriptor(clientLink))
            run.cancelAndAssertImmediate()
            assertEquals("取消导出不能报告成功", 0, run.successes.get())
            await("后台取消必须实际关闭本次写 FD") { !hasOwnDescriptor(clientLink) }
            document.release()
            await("Provider 必须观察到取消后的 EOF") { document.status().getBoolean("writeComplete") }
            val written = document.status().getInt("bytesWritten")
            assertTrue("应确实写入过部分数据", written > 0)
            assertTrue("取消必须中断大于发送缓冲区的备份", written < 60_000)
            assertExecutorBounds()
        }
    }

    @Test fun realSafExportCanBeReadBackWithTheSamePreferencesAndTheme() = fixture("NORMAL") { document ->
        val expected = AppPreferences(openLastServer = false, imageCacheSizeMb = 2048, seekSeconds = 40, subtitlesEnabled = false)
        runBlocking { withTimeout(10_000) { withContext(Dispatchers.Main) {
            SettingsTransfer.write(context, document.uri, expected, ThemePreference.LIGHT)
        } } }
        await("真实导出 FD 应完成关闭并产生 EOF") { document.status().getBoolean("writeComplete") }
        val restored = runBlocking { withTimeout(10_000) { withContext(Dispatchers.Main) { SettingsTransfer.read(context, document.uri) } } }
        assertEquals(ImportedSettings(expected, ThemePreference.LIGHT), restored)
    }

    @Test fun wrongFormatAndMalformedJsonCannotBecomeImportedSettings() {
        listOf(
            "{\"format\":\"other\",\"theme\":\"DARK\",\"settings\":{}}".toByteArray(),
            "{broken".toByteArray(),
        ).forEach { payload -> fixture("NORMAL", payload) { document ->
            val result = runCatching { runBlocking { withTimeout(10_000) { SettingsTransfer.read(context, document.uri) } } }
            assertTrue("格式错误备份必须失败", result.isFailure)
            assertFalse("数据错误不能伪装成取消或超时", result.exceptionOrNull() is CancellationException)
        } }
    }

    @Test fun validSettingsAt64KiBAreAcceptedAndOneMoreByteIsRejected() {
        val json = "{\"format\":\"pathfin-settings-v1\",\"theme\":\"DARK\",\"settings\":{\"seekSeconds\":25}}".toByteArray(Charsets.UTF_8)
        listOf(65_536, 65_537).forEach { size ->
            // 两份都是同一有效 v1 备份，仅尾随合法 JSON 空白的长度不同。
            val payload = json + ByteArray(size - json.size) { ' '.code.toByte() }
            assertEquals(size, payload.size)
            fixture("NORMAL", payload) { document ->
                val result = runCatching { runBlocking { withTimeout(10_000) { SettingsTransfer.read(context, document.uri) } } }
                if (size == 65_536) {
                    assertEquals(ImportedSettings(AppPreferences(seekSeconds = 25), ThemePreference.DARK), result.getOrThrow())
                } else {
                    assertTrue("有效 JSON 超出一字节也必须拒绝", result.exceptionOrNull() is IllegalArgumentException)
                    assertEquals("设置备份不能超过 64 KiB", result.exceptionOrNull()?.message)
                }
            }
        }
    }

    private inner class TransferRun(action: suspend () -> Unit) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val waiting = AtomicBoolean()
        private val finished = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        val successes = AtomicInteger()
        private lateinit var job: Job
        init {
            instrumentation.runOnMainSync {
                job = scope.launch {
                    waiting.set(true)
                    try { action(); successes.incrementAndGet() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { failure.set(error) }
                    finally { waiting.set(false); finished.countDown() }
                }
            }
        }

        fun awaitProvider(message: String, predicate: () -> Boolean) = await(message) {
            failure.get()?.let { throw AssertionError("设置传输意外失败", it) }
            predicate()
        }

        fun cancelAndAssertImmediate() {
            assertTrue("取消前 Main 协程应仍在等待文件操作", waiting.get())
            val started = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { job.cancel() }
            assertTrue("取消必须立即结束 UI 等待，不能等待 Provider 释放", finished.await(750, TimeUnit.MILLISECONDS))
            assertTrue("取消耗时不能取决于 Provider", SystemClock.elapsedRealtime() - started < 750)
            assertFalse(waiting.get())
            assertTrue(job.isCancelled)
            assertEquals(0, successes.get())
            assertNull(failure.get())
        }

        override fun close() { instrumentation.runOnMainSync { scope.cancel() } }
    }

    private class Document(private val context: Context, private val authority: String, val uri: Uri) : Closeable {
        private val id = DocumentsContract.getDocumentId(uri)
        fun status(): Bundle = requireNotNull(context.contentResolver.call(uri, PREFIX + "status", id, null))
        fun release() { context.contentResolver.call(uri, PREFIX + "release", id, null) }
        override fun close() {
            context.contentResolver.call(uri, PREFIX + "delete", id, null)
            assertFalse("删除夹具须移除本次持久授权", context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            assertEquals("本用例 URI 授权必须收回", PackageManager.PERMISSION_DENIED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
    }

    private fun fixture(behavior: String, payload: ByteArray? = null, block: (Document) -> Unit) {
        check(context.packageName == "dev.pathfin.android.verification")
        VerificationDisplay.prepare()
        val authority = VerificationSettingsDocumentsProvider.authority(instrumentation.context)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val result = CompletableFuture<Uri>()
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("settings-document-${UUID.randomUUID()}",
                    ActivityResultContracts.StartActivityForResult()) { returned ->
                    val uri = returned.data?.data
                    if (returned.resultCode == Activity.RESULT_OK && uri != null) result.complete(uri)
                    else result.completeExceptionally(AssertionError("夹具 Activity 必须返回真实 URI 授权"))
                }
                launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .setComponent(ComponentName(instrumentation.context.packageName,
                        VerificationSettingsDocumentsProvider.GrantActivity::class.java.name))
                    .putExtra(VerificationSettingsDocumentsProvider.BEHAVIOR_EXTRA, behavior)
                    .apply { if (payload != null) putExtra(VerificationSettingsDocumentsProvider.PAYLOAD_EXTRA, payload) })
            }
            val uri = result.get(15, TimeUnit.SECONDS)
            check(uri.scheme == "content" && uri.authority == authority)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            assertTrue("必须由 Android 实际记录持久读写授权", context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            })
            assertEquals("夹具须授予真实读写 URI 权限", PackageManager.PERMISSION_GRANTED,
                context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags))
            Document(context, authority, uri).use { document ->
                await("授权返回后恢复前台测试 Activity") { scenario.state == Lifecycle.State.RESUMED }
                block(document)
            }
        }
    }

    private fun transferBlockedIn(classSuffix: String, method: String): Boolean = Thread.getAllStackTraces().any { (thread, stack) ->
        thread.name.startsWith("PathfinSafTransfer-") && stack.any { it.className.endsWith(classSuffix) && it.methodName == method }
    }

    private fun hasOwnDescriptor(link: String): Boolean {
        check(link.matches(Regex("(?:pipe|socket):\\[\\d+\\]")))
        return File("/proc/self/fd").list().orEmpty().any { id ->
            runCatching { Os.readlink("/proc/self/fd/$id") == link }.getOrDefault(false)
        }
    }

    private fun assertExecutorBounds() {
        val threads = Thread.getAllStackTraces().keys.filter { it.isAlive }.map { it.name }
        assertTrue("传输线程必须有界", threads.count { it.startsWith("PathfinSafTransfer-") } <= 2)
        assertTrue("open 取消线程必须有界", threads.count { it.startsWith("PathfinSafCancel-") } <= 1)
        assertTrue("FD 清理线程必须有界", threads.count { it.startsWith("PathfinSafClose-") } <= 1)
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; SystemClock.sleep(20) }
        assertTrue(message, predicate())
    }

    private companion object { const val PREFIX = "pathfin.settings." }
}

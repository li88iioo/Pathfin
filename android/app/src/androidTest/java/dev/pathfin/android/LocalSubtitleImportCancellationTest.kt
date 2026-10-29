package dev.pathfin.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.playback.LocalSubtitleImporter
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 SAF 授权和跨进程慢 Provider；不访问用户文档，也不依赖文件扩展名。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LocalSubtitleImportCancellationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun cancellingDelayedSubtitleOpenEndsTheCallerBeforeTheProviderReturns() = cancelImport("DELAY_OPEN")
    @Test fun cancellingBlockedSubtitleReadCannotLeaveACopyOrPublishALateTrack() = cancelImport("BLOCK_READ")

    @Test fun realUtf16DocumentIsCopiedAsUtf8WithItsActualDisplayName() = document("NORMAL") { uri ->
        val subtitle = runBlocking { LocalSubtitleImporter.read(context, uri) }
        try {
            assertEquals(SRT, subtitle.file.readText())
            assertEquals("application/x-subrip", subtitle.mimeType)
            assertEquals(DocumentsContract.getDocumentId(uri), subtitle.label)
            assertEquals("srt", subtitle.file.extension)
            assertEquals(File(context.cacheDir, "player-subtitles"), subtitle.file.parentFile)
        } finally { subtitle.file.delete() }
    }

    private fun cancelImport(behavior: String) = document(behavior) { uri ->
        val previous = cachedFiles()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val finished = CountDownLatch(1)
        val completed = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        lateinit var job: Job
        instrumentation.runOnMainSync {
            job = scope.launch {
                try {
                    val subtitle = LocalSubtitleImporter.read(context, uri)
                    completed.incrementAndGet()
                    LocalSubtitleImporter.delete(listOf(subtitle.file))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }
        }
        try {
            await("字幕操作必须真正进入跨进程文件读取") {
                failure.get()?.let { throw AssertionError("字幕导入意外失败", it) }
                if (behavior == "DELAY_OPEN") status(uri).getBoolean("openStarted") else
                    status(uri).getBoolean("opened") && Thread.getAllStackTraces().any { (thread, stack) ->
                        thread.name.startsWith("PathfinSafTransfer-") && stack.any { it.className.endsWith("FileInputStream") && it.methodName == "read" }
                    }
            }
            assertTrue(status(uri).getBoolean("signalReceived"))
            instrumentation.runOnMainSync { job.cancel() }
            assertTrue("取消不能等待慢 Provider 返回", finished.await(750, TimeUnit.MILLISECONDS))
            assertTrue(job.isCancelled)
            assertEquals(0, completed.get())
            assertNull(failure.get())
            await("取消信号必须传到真实 Provider") { status(uri).getBoolean("signalCancelled") }
            if (behavior == "BLOCK_READ") {
                call(uri, "release")
                await("取消后 FD 必须关闭，使迟到写入失败") { status(uri).getBoolean("writerFailed") }
            } else await("慢 open 应被取消") { status(uri).getBoolean("openCancelled") }
            assertEquals("取消不得在播放器缓存中遗留字幕", previous, cachedFiles())
        } finally {
            instrumentation.runOnMainSync { scope.cancel() }
            call(uri, "release")
        }
    }

    private fun document(behavior: String, action: (Uri) -> Unit) {
        check(context.packageName == "dev.pathfin.android.verification")
        VerificationDisplay.prepare()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val granted = CompletableFuture<Uri>()
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("subtitle-document-${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data
                    if (result.resultCode == Activity.RESULT_OK && uri != null) granted.complete(uri)
                    else granted.completeExceptionally(AssertionError("未收到字幕文档授权"))
                }
                launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .setComponent(ComponentName(instrumentation.context.packageName, VerificationSettingsDocumentsProvider.GrantActivity::class.java.name))
                    .putExtra(VerificationSettingsDocumentsProvider.BEHAVIOR_EXTRA, behavior)
                    .putExtra(VerificationSettingsDocumentsProvider.PAYLOAD_EXTRA, SRT.toByteArray(Charsets.UTF_16)))
            }
            val uri = granted.get(15, TimeUnit.SECONDS)
            try {
                await("授权后测试 Activity 应恢复") { scenario.state == Lifecycle.State.RESUMED }
                action(uri)
            } finally { call(uri, "delete") }
        }
    }

    private fun cachedFiles(): Set<String> = File(context.cacheDir, "player-subtitles").list().orEmpty().toSet()
    private fun call(uri: Uri, operation: String) = context.contentResolver.call(uri, "pathfin.settings.$operation", DocumentsContract.getDocumentId(uri), null)
    private fun status(uri: Uri) = requireNotNull(call(uri, "status"))
    private fun await(message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; SystemClock.sleep(20) }
        assertTrue(message, predicate())
    }

    companion object { private const val SRT = "1\n00:00:01,000 --> 00:00:02,000\n测试字幕\n" }
}

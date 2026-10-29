package dev.pathfin.android.data

import android.content.Context
import android.content.ContentProviderClient
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import dev.pathfin.android.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * SAF 的 open/read/write 都可能等待远程 Provider。取消立即结束调用方等待，后台独立取消 open、
 * 关闭 FD；所有调用者共用有界执行器，不使用取消后仍等待阻塞子任务的 withContext(IO)。
 */
internal object SafDocumentTransfer {
    private const val MAX_OPERATIONS = 6
    private val documentSlots = Semaphore(MAX_OPERATIONS)
    private val streamSlots = Semaphore(MAX_OPERATIONS)
    private val transfers = executor(2, "PathfinSafTransfer")
    // 视频下载可长时间占满线程；独立 admission 让字幕、图片和设置仍能正常读写。
    private val streams = executor(2, "PathfinSafStream")
    // 某 Provider 的取消 Binder 卡住，也不能占住关闭另一操作 FD 的线程。
    private val cancellations = executor(1, "PathfinSafCancel", MAX_OPERATIONS * 2)
    private val closures = executor(1, "PathfinSafClose", MAX_OPERATIONS * 2)

    /** onFinished 在线程、取消信号与 FD 清理全部结束后调用；取消调用方等待不会提前释放资源租约。 */
    suspend fun <T> transfer(
        longRunning: Boolean = false,
        onFinished: () -> Unit = {},
        block: (Operation) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) { onFinished(); return@suspendCancellableCoroutine }
        val slots = if (longRunning) streamSlots else documentSlots
        val executor = if (longRunning) streams else transfers
        if (!slots.tryAcquire()) {
            onFinished()
            continuation.resumeWithException(IOException(tr("文件服务正忙，请稍后重试")))
            return@suspendCancellableCoroutine
        }
        val operation = Operation(executor, slots, onFinished)
        continuation.invokeOnCancellation { operation.cancel() }
        val task = object : FutureTask<Unit>(Callable {
            // cancel/done 可能抢在 Callable 真正进入前执行；这种迟到任务不能再次使用已归还的租约。
            if (!operation.begin()) return@Callable Unit
            var result = runCatching { operation.ensureActive(); block(operation) }
            try { operation.closeResources() }
            catch (error: Throwable) {
                result.exceptionOrNull()?.addSuppressed(error) ?: run { result = Result.failure(error) }
            } finally {
                try { operation.closeProvider() }
                catch (error: Throwable) {
                    result.exceptionOrNull()?.addSuppressed(error) ?: run { result = Result.failure(error) }
                } finally { operation.workerFinished() }
            }
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            Unit
        }) {
            override fun done() {
                // 队列里的任务被取消时 Callable 不会执行，也必须归还 admission slot。
                if (!operation.started) operation.workerFinished()
            }
        }
        operation.attach(task)
        if (!task.isCancelled) {
            try { executor.execute(task); if (task.isCancelled) executor.remove(task) }
            catch (error: RejectedExecutionException) {
                operation.workerFinished()
                if (continuation.isActive) continuation.resumeWithException(IOException(tr("文件服务正忙，请稍后重试"), error))
            }
        }
    }

    class Operation internal constructor(
        private val executor: ThreadPoolExecutor,
        private val slots: Semaphore,
        private val onFinished: () -> Unit,
    ) {
        private val signal = CancellationSignal()
        private val resources = AtomicReference<OwnedResources?>()
        private val coroutine = AtomicReference<Job?>()
        // 仅 transfer 线程持有/释放，直到本次 open/read/write 已返回才释放 Provider 引用。
        private var provider: ContentProviderClient? = null
        private val lock = Any()
        @Volatile private var cancelled = false
        @Volatile var started = false
        @Volatile private var task: FutureTask<Unit>? = null
        private var finished = false
        private var pendingParts = 1

        fun readBytes(context: Context, uri: Uri, maximumBytes: Int, limitMessage: String): ByteArray {
            require(maximumBytes in 1 until Int.MAX_VALUE)
            val descriptor = open(context, uri, "r")
            val owned = own(descriptor)
            val stream = descriptor.createInputStream().also(owned::ownStream)
            val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
            val buffer = ByteArray(minOf(maximumBytes + 1, 16 * 1024))
            while (true) {
                ensureActive()
                // 只多读一个字节区分恰好上限和超限，不能信任 Provider 声明的大小。
                val size = stream.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size() + 1))
                if (size < 0) break
                require(size <= maximumBytes - output.size()) { limitMessage }
                if (size > 0) output.write(buffer, 0, size)
            }
            ensureActive()
            return output.toByteArray()
        }

        fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
            val stream = openOutputStream(context, uri, "wt")
            ensureActive()
            stream.write(bytes)
            stream.flush()
            ensureActive()
        }

        /** 输出流和 FD 统一由 Operation 关闭；调用方只负责流式 write/flush。 */
        fun openOutputStream(context: Context, uri: Uri, mode: String = "rwt"): OutputStream {
            val descriptor = open(context, uri, mode)
            val owned = own(descriptor)
            return descriptor.createOutputStream().also(owned::ownStream).also { ensureActive() }
        }

        /**
         * 在本次有界线程桥接 suspend API/进度。显式持有 Job，取消时即使线程正在阻塞读网络，
         * 也能立刻取消其 HTTP 看护子协程；不能只依赖 interrupt 到下次挂起才被 runBlocking 察觉。
         */
        fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T = kotlinx.coroutines.runBlocking {
            val job = checkNotNull(coroutineContext[Job])
            check(coroutine.compareAndSet(null, job))
            try { ensureActive(); block() }
            finally { coroutine.compareAndSet(job, null) }
        }

        /** 复用已打开文档的 Provider 与取消信号，不在 UI 线程等待远程文件名查询。 */
        fun readDisplayName(uri: Uri): String? {
            ensureActive()
            val client = checkNotNull(provider) { tr("请先读取文档") }
            val name = client.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
            }
            ensureActive()
            return name
        }

        fun attach(value: FutureTask<Unit>) {
            synchronized(lock) { task = value; if (cancelled) value.cancel(true) }
        }

        fun begin(): Boolean = synchronized(lock) {
            if (finished || cancelled) false else { started = true; true }
        }

        private fun open(context: Context, uri: Uri, mode: String): AssetFileDescriptor {
            ensureActive()
            val client = requireNotNull(context.contentResolver.acquireUnstableContentProviderClient(uri)) {
                tr("文件服务不可用")
            }.also { provider = it }
            ensureActive()
            // ContentResolver 的 "r" 会转 typed */*；DocumentsProvider 在该分支调用无
            // CancellationSignal 的 openAssetFile。直接调用公开 client API 才能把取消传到
            // openDocument；仍由平台验证原 URI grant，并保留 AFD 的 offset/length 语义。
            return requireNotNull(client.openAssetFile(uri, mode, signal)) { tr("文件服务未返回可读写文件") }
        }

        fun closeProvider() { provider?.close(); provider = null }

        private fun own(descriptor: AssetFileDescriptor): OwnedResources {
            val owned = OwnedResources(descriptor)
            check(resources.compareAndSet(null, owned))
            ensureActive()
            return owned
        }

        fun ensureActive() { if (cancelled) throw CancellationException(tr("文件传输已取消")) }

        fun cancel() {
            val needsCleanup = synchronized(lock) {
                if (cancelled) return
                cancelled = true
                (!finished).also { if (it) pendingParts += 2 }
            }
            coroutine.get()?.cancel()
            task?.let { it.cancel(true); executor.remove(it) }
            if (needsCleanup) {
                // 两类各最多6个未归还 slot，清理队列覆盖总上限；挂起清理不能放行无限新任务。
                cancellations.execute { try { runCatching { signal.cancel() } } finally { partFinished() } }
                closures.execute { try { runCatching { closeResources() } } finally { partFinished() } }
            }
        }

        fun closeResources() { resources.getAndSet(null)?.close() }

        fun workerFinished() = synchronized(lock) {
            if (!finished) { finished = true; partFinished() }
        }

        private fun partFinished() = synchronized(lock) {
            pendingParts--
            if (pendingParts == 0) { slots.release(); onFinished() }
        }
    }

    private class OwnedResources(private val descriptor: AssetFileDescriptor) : Closeable {
        private val closed = AtomicBoolean()
        @Volatile private var stream: Closeable? = null

        fun ownStream(value: Closeable) {
            stream = value
            // 取消若抢在创建 stream 前关闭了 AFD，迟到的 stream 仍由当前 IO 线程关闭。
            if (closed.get()) { value.close(); throw CancellationException(tr("文件传输已取消")) }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                // 先关闭 FD 唤醒阻塞 read/write；不要让 stream 的 close 阻挡该步骤。
                try { descriptor.close() } finally { stream?.close() }
            }
        }
    }

    private fun executor(count: Int, name: String, capacity: Int = MAX_OPERATIONS): ThreadPoolExecutor {
        val sequence = AtomicInteger()
        val factory = ThreadFactory { runnable -> Thread(runnable, "$name-${sequence.incrementAndGet()}").apply { isDaemon = true } }
        return ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity), factory,
            ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
}

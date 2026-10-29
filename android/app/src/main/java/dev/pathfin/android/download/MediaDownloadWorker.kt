package dev.pathfin.android.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import dev.pathfin.android.R
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.LocalNetworkPermissionDenied
import dev.pathfin.android.data.NetworkErrorMessages
import dev.pathfin.android.data.SafDocumentTransfer
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** WorkData 仅含 taskId；重试及进程重启均从加密会话重新取得临时下载地址。 */
class MediaDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val sessionStore by lazy { SessionStore(applicationContext) }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(TASK_ID) ?: return@withContext Result.failure()
        val store = DownloadStore(applicationContext)
        val task = store.get(taskId) ?: return@withContext Result.failure()
        if (task.workId != id.toString() || !task.isActive) return@withContext Result.success()
        val lease = acquireOutputLease(task.destinationUri)
        if (lease == null) {
            store.update(task.id, task.workId) {
                if (!it.isActive) it else it.copy(status = DownloadStatus.FAILED, error = tr("目标文件仍在关闭，请稍后重试"))
            }?.takeIf { it.status == DownloadStatus.FAILED }?.let(::notifyFailed)
            return@withContext Result.failure()
        }
        val outputOpened = AtomicBoolean()
        val writeFinished = CompletableDeferred<Unit>()
        try {
            ensureCurrent(store, task)
            setForeground(foregroundInfo(task, 0, null))
            val session = sessionStore.loadProfiles().firstOrNull { it.id == task.profileId }?.session
                ?: throw ProfileRemoved()
            requirePermission(session.server.baseUrl)
            store.update(task.id, task.workId) {
                if (!it.isActive) throw CancellationException("Download cancelled")
                it.copy(status = DownloadStatus.DOWNLOADING, downloadedBytes = 0, totalBytes = null, error = null)
            }
            lease.retain()
            val receipt = SafDocumentTransfer.transfer(longRunning = true, onFinished = {
                lease.release()
                writeFinished.complete(Unit)
            }) { operation ->
                // 协程 Job、HTTP 看护与 SAF signal/FD 共用 Operation 取消；输出留在本次流式线程。
                operation.runBlocking {
                    val source = JellyfinApi().downloadSource(session, task.itemId, task.sourceId)
                    ensureCurrent(store, task)
                    store.update(task.id, task.workId) {
                        if (!it.isActive) throw CancellationException("Download cancelled")
                        it.copy(fileName = downloadDisplayName(source.fileName), mimeType = source.mimeType,
                            totalBytes = source.expectedBytes?.takeIf { bytes -> bytes > 0 })
                    }
                    MediaDownloadClient(requireNetworkPermission = ::requirePermission).copy(session, source, {
                        ensureCurrent(store, task)
                        try {
                            operation.openOutputStream(applicationContext, task.destinationUri.toUri())
                                .also { outputOpened.set(true) }
                        } catch (_: IOException) {
                            throw DownloadFailure(tr("无法打开保存位置，请检查目标文件与剩余空间"))
                        }
                    }) { bytes, total ->
                        currentCoroutineContext().ensureActive()
                        ensureCurrent(store, task)
                        val updated = store.update(task.id, task.workId) {
                            if (!it.isActive) throw CancellationException("Download cancelled")
                            it.copy(status = DownloadStatus.DOWNLOADING, downloadedBytes = bytes, totalBytes = total)
                        } ?: throw CancellationException("Download replaced")
                        setProgress(workDataOf("bytes" to bytes, "total" to (total ?: -1L)))
                        setForeground(foregroundInfo(updated, bytes, total))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            ensureCurrent(store, task)
            val completed = store.update(task.id, task.workId) {
                if (!it.isActive) throw CancellationException("Download cancelled")
                it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = receipt.bytes,
                    totalBytes = receipt.totalBytes, mimeType = receipt.mimeType, error = null)
            } ?: throw CancellationException("Download replaced")
            notifyCompleted(completed)
            Result.success(workDataOf(TASK_ID to task.id))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                val cleared = !outputOpened.get() || clearPartial(task, lease, writeFinished)
                val accountRemoved = cancelled is ProfileRemoved || runCatching {
                    sessionStore.loadProfiles().none { it.id == task.profileId }
                }.getOrDefault(false)
                val cancelledByUser = runCatching {
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id).get()?.state == WorkInfo.State.CANCELLED
                }.getOrDefault(false)
                store.update(task.id, task.workId) {
                    if (it.status == DownloadStatus.COMPLETED) it else it.copy(
                        status = if (it.status == DownloadStatus.CANCELLED || cancelledByUser || accountRemoved) DownloadStatus.CANCELLED else DownloadStatus.QUEUED,
                        downloadedBytes = if (cleared) 0 else it.downloadedBytes,
                        error = when {
                            !cleared -> tr("任务已停止，目标中可能保留未完成文件")
                            accountRemoved -> tr("账号已从本机移除，下载已停止")
                            it.status == DownloadStatus.CANCELLED -> it.error ?: tr("已取消；重试会从头重新下载")
                            else -> tr("任务已中断，恢复或重试后会从头下载")
                        },
                    )
                }
            }
            throw cancelled
        } catch (error: Exception) {
            val cleared = !outputOpened.get() || clearPartial(task, lease, writeFinished)
            store.update(task.id, task.workId) {
                if (it.status == DownloadStatus.CANCELLED || it.status == DownloadStatus.COMPLETED) it else it.copy(
                    status = DownloadStatus.FAILED, downloadedBytes = if (cleared) 0 else it.downloadedBytes,
                    error = downloadError(error) + if (cleared) "" else tr("；目标中可能保留未完成文件"),
                )
            }
            store.get(task.id)?.takeIf { it.status == DownloadStatus.FAILED }?.let(::notifyFailed)
            Result.failure(workDataOf(TASK_ID to task.id))
        } finally { lease.release() }
    }

    private fun ensureCurrent(store: DownloadStore, task: DownloadTask) {
        val current = store.get(task.id)
        if (current == null || current.workId != task.workId || !current.isActive) throw CancellationException("Download cancelled or replaced")
        if (sessionStore.loadProfiles().none { it.id == task.profileId }) throw ProfileRemoved()
    }

    private class ProfileRemoved : CancellationException("Download profile removed")

    private suspend fun requirePermission(url: String) {
        if (!LocalNetworkPermission.isGranted(applicationContext) && LocalNetworkPermission.isRequired(url)) {
            throw LocalNetworkPermissionDenied()
        }
    }

    /** 不删除用户文档；旧写入真正关闭后才清空，迟到的清理仍持租约，不能截断下一代下载。 */
    private suspend fun clearPartial(task: DownloadTask, lease: OutputLease, writeFinished: CompletableDeferred<Unit>): Boolean {
        if (withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { writeFinished.await(); true } != true) return false
        lease.retain()
        return try {
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                SafDocumentTransfer.transfer(longRunning = true, onFinished = lease::release) { operation ->
                    operation.openOutputStream(applicationContext, task.destinationUri.toUri()).flush()
                    operation.ensureActive()
                }
                true
            } == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private class OutputLease(private val uri: String) {
        private val references = AtomicInteger(1)
        val finished = CompletableDeferred<Unit>()
        fun retain() { references.incrementAndGet() }
        fun release() {
            if (references.decrementAndGet() == 0) { outputLeases.remove(uri, this); finished.complete(Unit) }
        }
    }

    /** 网络重排可接续正常取消；只等固定总预算，迟到 open/清空没有真正结束时绝不取片源。 */
    private suspend fun acquireOutputLease(uri: String): OutputLease? {
        val next = OutputLease(uri)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_HANDOFF_TIMEOUT_MS)
        while (true) {
            currentCoroutineContext().ensureActive()
            val current = outputLeases.putIfAbsent(uri, next) ?: return next
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            val waitMillis = (remaining + 999_999L) / 1_000_000L
            if (withTimeoutOrNull(waitMillis) { current.finished.await(); true } != true) return null
        }
    }

    private fun foregroundInfo(task: DownloadTask, bytes: Long, total: Long?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("媒体下载"), NotificationManager.IMPORTANCE_LOW))
        val percent = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it * 100).toInt().coerceIn(0, 100) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(task.displayName)
            .setContentText(downloadProgressText(bytes, total))
            .setSubText(tr("Pathfin 下载"))
            .setProgress(100, percent ?: 0, percent == null)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(R.drawable.ic_stat_close, tr("取消下载"), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .apply { launchIntent()?.let(::setContentIntent) }.build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }

    private fun notifyCompleted(task: DownloadTask) {
        val open = PendingIntent.getActivity(applicationContext, notificationId, MediaDownloads.openIntent(task),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifyResult(NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download_done).setContentTitle(task.displayName)
            .setContentText(tr("下载完成 · %1\$s，点击用本机播放器打开", formatDownloadSize(task.downloadedBytes)))
            .setSubText(tr("Pathfin 下载")).setContentIntent(open).setAutoCancel(true)
            .addAction(R.drawable.ic_stat_download_done, tr("打开"), open))
    }

    /** 后台失败也要告知，否则用户只会在下次打开下载页时才发现。 */
    private fun notifyFailed(task: DownloadTask) {
        notifyResult(NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download_failed).setContentTitle(tr("下载失败：%1\$s", task.displayName))
            .setContentText(task.error ?: tr("下载失败，可在下载页重试"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.error ?: tr("下载失败，可在下载页重试")))
            .setSubText(tr("Pathfin 下载")).setAutoCancel(true)
            .apply { launchIntent()?.let(::setContentIntent) })
    }

    private fun notifyResult(builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val notifications = NotificationManagerCompat.from(applicationContext)
            if (notifications.areNotificationsEnabled()) notifications.notify(notificationId xor 0x40000000, builder.build())
        }
    }

    private fun launchIntent(): PendingIntent? =
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let { intent ->
            PendingIntent.getActivity(applicationContext, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

    private fun downloadProgressText(bytes: Long, total: Long?): String = when {
        bytes == 0L -> tr("正在准备下载")
        total != null && total > 0 -> "${(bytes.toDouble() / total * 100).toInt().coerceIn(0, 100)}% · ${formatDownloadSize(bytes)} / ${formatDownloadSize(total)}"
        else -> tr("已下载 %1\$s", formatDownloadSize(bytes))
    }

    private val notificationId: Int get() = (id.hashCode() and 0x0fffffff) + 100

    companion object {
        internal const val TASK_ID = "task_id"
        private const val CHANNEL_ID = "pathfin_media_downloads"
        private const val CLEANUP_TIMEOUT_MS = 1_000L
        private const val OUTPUT_HANDOFF_TIMEOUT_MS = 1_000L
        private val outputLeases = ConcurrentHashMap<String, OutputLease>()

        private fun downloadError(error: Exception): String = when (error) {
            is LocalNetworkPermissionDenied -> tr("缺少局域网访问权限，请回到服务器页面允许后重试下载")
            is DownloadFailure -> error.message ?: tr("下载失败，请重试")
            is ApiException -> when (error.statusCode) {
                401 -> tr("登录已失效，请重新登录后重试下载")
                403 -> tr("该账号没有下载此媒体的权限")
                404 -> tr("媒体不存在或片源已失效")
                else -> tr("无法取得可下载的实际媒体文件，请检查该媒体是否支持下载")
            }
            is SecurityException -> tr("无法写入保存位置，请重新选择文件并检查系统授权")
            is IOException -> NetworkErrorMessages.connection(error)
            else -> tr("下载未完成，请打开 Pathfin 后重试")
        }
    }
}

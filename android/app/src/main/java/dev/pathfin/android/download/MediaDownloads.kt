package dev.pathfin.android.download

import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.i18n.tr
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** DownloadStore 是任务真值；WorkManager 仅执行由已保存任务和网络偏好派生的计划。 */
object MediaDownloads {
    private val queueMutex = Mutex()
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Application 冷启动与通知共用进程级命令，页面和旧 Worker 的取消不截断持久提交。 */
    fun restore(context: Context): Job = launchCommand { recover(context.applicationContext) }
    internal fun launchCommand(block: suspend () -> Unit): Job = commands.launch {
        runCatching { block() }.onFailure { Log.w("PathfinDownload", "Download command incomplete; will reconcile on next entry") }
    }

    suspend fun recover(context: Context, taskId: String? = null): Unit = withContext(Dispatchers.IO) {
        queueMutex.withLock { withContext(NonCancellable) { synchronize(context, taskId) } }
    }

    suspend fun enqueue(
        context: Context,
        profileId: String,
        itemId: String,
        displayName: String,
        destinationUri: Uri,
        sourceId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            require(profileId.isNotBlank() && itemId.isNotBlank()) { tr("下载媒体标识无效") }
            DownloadDirectories.requireDestination(context, destinationUri)
            check(SessionStore(context).loadProfiles().any { it.id == profileId }) { tr("该账号已从本机移除，请重新登录后下载") }
            val task = DownloadTask(
                id = UUID.randomUUID().toString(), workId = UUID.randomUUID().toString(),
                profileId = profileId, itemId = itemId, displayName = downloadDisplayName(displayName),
                destinationUri = destinationUri.toString(), sourceId = sourceId,
            )
            val store = DownloadStore(context)
            store.insert(task)
            withContext(NonCancellable) { synchronize(context, task.id) }
            task.id
        }
    }

    /** 未指定默认目录时由 UI 继续系统“另存为”；指定后仅创建新文件。 */
    suspend fun enqueueToDirectory(
        context: Context,
        profileId: String,
        itemId: String,
        displayName: String,
        fileName: String,
        sourceId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val tree = AppPreferencesStore(context).load().downloadDirectoryUri?.toUri() ?: return@withContext null
        val destination = DownloadDirectories.create(context, tree, fileName)
        try {
            enqueue(context, profileId, itemId, displayName, destination, sourceId)
        } catch (error: Exception) {
            // 已落盘的失败任务仍需原目标供重试；只清理未进入任务列表的本次新文档。
            if (DownloadStore(context).all().none { it.destinationUri == destination.toString() })
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            throw error
        }
    }

    fun observe(context: Context): Flow<List<DownloadTask>> {
        val plans = WorkManager.getInstance(context).getWorkInfosByTagFlow(WORK_TAG)
            .map { work -> work.map { Triple(it.id, it.state, it.constraints) }.toSet() }
            .distinctUntilChanged().onEach { runCatching { recover(context) } }
        // UI直接读取持久结果；字节进度不会触发解密账号/全队列重排，单项失败也不会中断列表。
        return combine(DownloadStore(context).observe(), plans) { tasks, _ -> tasks.sortedByDescending { it.createdAt } }
            .distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    suspend fun cancel(context: Context, taskId: String, workId: String): Unit = withContext(Dispatchers.IO) {
        val task = DownloadStore(context).cancel(taskId, workId) ?: return@withContext
        if (task.status == DownloadStatus.CANCELLED)
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workId)).result.get()
    }

    suspend fun retry(context: Context, taskId: String, destinationUri: Uri? = null) = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            val store = DownloadStore(context)
            val task = store.get(taskId) ?: throw IllegalStateException(tr("下载记录已移除"))
            val workManager = WorkManager.getInstance(context)
            val info = workManager.getWorkInfoById(UUID.fromString(task.workId)).get()
            check(task.status != DownloadStatus.COMPLETED) { tr("文件已保存，无需重新下载") }
            check(!task.isActive || info == null || info.state.isFinished) { tr("任务仍在下载中") }
            val destination = destinationUri ?: task.destinationUri.toUri()
            DownloadDirectories.requireDestination(context, destination)
            check(store.all().none { it.id != task.id && it.destinationUri == destination.toString() && it.isActive }) { tr("该目标文件已有下载任务") }
            check(SessionStore(context).loadProfiles().any { it.id == task.profileId }) { tr("该账号已从本机移除，请重新登录后下载") }
            withContext(NonCancellable) {
                startAttempt(context, task, downloadConstraints(AppPreferencesStore(context).load().downloadNetwork), destination.toString())
            }
        }
    }

    /** 仅移除本机记录；用户通过 SAF 保存的文件继续保留。 */
    suspend fun remove(context: Context, taskId: String) = withContext(Dispatchers.IO) {
        val store = DownloadStore(context)
        val task = store.get(taskId) ?: return@withContext
        val info = WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(task.workId)).get()
        check(!task.isActive || info == null || info.state.isFinished) { tr("请先取消正在进行的下载") }
        store.remove(taskId)
    }

    fun open(context: Context, task: DownloadTask): Boolean = runCatching {
        check(task.status == DownloadStatus.COMPLETED)
        context.startActivity(openIntent(task))
        true
    }.getOrDefault(false)

    internal fun openIntent(task: DownloadTask): Intent {
        val uri = task.destinationUri.toUri()
        require(uri.scheme == "content")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, task.mimeType ?: "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = ClipData.newRawUri("media", uri) }
    }

    private fun synchronize(context: Context, taskId: String? = null) {
        val store = DownloadStore(context)
        val tasks = if (taskId == null) store.all() else listOfNotNull(store.get(taskId))
        if (tasks.isEmpty()) return
        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosByTag(WORK_TAG).get().associateBy { it.id.toString() }
        val constraints = downloadConstraints(AppPreferencesStore(context).load().downloadNetwork)
        val profiles = if (tasks.any { it.isActive }) SessionStore(context).loadProfiles().mapTo(mutableSetOf()) { it.id } else emptySet()
        tasks.map { task ->
            runCatching {
                val info = work[task.workId]
                if (task.isActive && task.profileId !in profiles) {
                    try { store.cancel(task.id, task.workId, tr("账号已从本机移除，下载已停止")) }
                    finally { manager.cancelUniqueWork(workName(task)).result.get() }
                    return@runCatching
                }
                when {
                    !task.isActive -> {
                        // Worker 先提交 COMPLETED/FAILED 再返回 Result；这段窗口不能把正常收尾改成系统取消。
                        if (task.status == DownloadStatus.CANCELLED && info?.state?.isFinished != true)
                            manager.cancelUniqueWork(workName(task)).result.get()
                    }
                    info?.state?.isFinished == true -> store.update(task.id, task.workId) {
                        if (!it.isActive) it else it.copy(status = if (info.state == WorkInfo.State.CANCELLED)
                            DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                            error = tr("任务已停止；重试会从头重新下载"))
                    }
                    info == null || info.constraints != constraints -> startAttempt(context, task, constraints)
                    info.state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) && task.status == DownloadStatus.DOWNLOADING ->
                        store.update(task.id, task.workId) { if (it.isActive) it.copy(status = DownloadStatus.QUEUED) else it }
                }
            }
        }.firstOrNull { it.isFailure }?.getOrThrow()
    }

    /** 恢复与显式重试只提交一次最终代次；活动阶段可推进，但取消/完成后的快照不得被旧意图复活。 */
    private fun startAttempt(context: Context, original: DownloadTask, constraints: Constraints, destination: String = original.destinationUri) {
        val store = DownloadStore(context)
        val manager = WorkManager.getInstance(context)
        val eligible = if (original.isActive) setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) else setOf(original.status)
        var task = original
        runCatching {
            task = store.update(original.id, original.workId) {
                if (it.status in eligible) it.nextAttempt(destination) else it
            }?.takeIf { it.workId != original.workId } ?: return
            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>().setId(UUID.fromString(task.workId))
                .setInputData(workDataOf(MediaDownloadWorker.TASK_ID to task.id))
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .build()
            manager.enqueueUniqueWork(workName(task), ExistingWorkPolicy.REPLACE, request).result.get()
        }.onFailure {
            // 无法提交新代次时仍停止旧计划，不能沿旧ANY约束继续取流。
            runCatching { manager.cancelUniqueWork(workName(task)).result.get() }
            runCatching { store.update(task.id, task.workId) {
                if (it.isActive) it.copy(status = DownloadStatus.FAILED, error = tr("无法安排后台下载，请重试")) else it
            } }
        }.getOrThrow()
    }

    internal fun cancelIntent(context: Context, task: DownloadTask): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, DownloadCancelReceiver::class.java).setData(Uri.parse("pathfin-download:${task.id}/${task.workId}"))
            .putExtra(MediaDownloadWorker.TASK_ID, task.id).putExtra("workId", task.workId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun workName(task: DownloadTask) = "pathfin-download-${task.id}"

    internal const val WORK_TAG = "pathfin-media-download"
}

/** 先撤销持久任务，再停止系统计划；冷启动或迟到恢复均不能复活通知取消的任务。 */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(MediaDownloadWorker.TASK_ID)?.takeIf(String::isNotBlank) ?: return
        val workId = intent.getStringExtra("workId")?.takeIf(String::isNotBlank) ?: return
        val pending = goAsync()
        MediaDownloads.launchCommand { MediaDownloads.cancel(context.applicationContext, taskId, workId) }
            .invokeOnCompletion { pending.finish() }
    }
}

internal fun downloadConstraints(network: DownloadNetwork): Constraints = Constraints.Builder()
    .setRequiredNetworkType(if (network == DownloadNetwork.UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .build()

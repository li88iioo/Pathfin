package dev.pathfin.android.download

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** UI 与持久下载任务之间的入口。网络地址及凭据只由执行中的 Worker 持有。 */
object MediaDownloads {
    // 偏好先落盘再重排；入队读策略到系统排程之间必须与重排互斥。
    private val queueMutex = Mutex()

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
                network = AppPreferencesStore(context).load().downloadNetwork,
            )
            val store = DownloadStore(context)
            store.insert(task)
            try {
                schedule(context, task)
            } catch (error: Exception) {
                store.update(task.id, task.workId) { it.copy(status = DownloadStatus.FAILED, error = tr("无法安排后台下载，请重试")) }
                throw IllegalStateException(tr("无法安排后台下载，请重试"), error)
            }
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

    /** 偏好保存成功后调用；新约束立即作用于当前队列，已完成和手动取消的记录不变。 */
    suspend fun reconfigureNetwork(context: Context, network: DownloadNetwork): Unit = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            val store = DownloadStore(context)
            val manager = WorkManager.getInstance(context)
            val changed = try { store.replaceNetwork(network) }
            catch (error: Exception) {
                // 无法写盘时也先停止旧策略，避免“仅不计费”已选中却仍消耗计费流量。
                store.all().filter { it.isActive && it.network != network }.forEach { task ->
                    runCatching { manager.cancelWorkById(UUID.fromString(task.workId)).result.get() }
                }
                throw IllegalStateException(tr("无法更新下载队列，请检查本机存储空间后重试"), error)
            }
            var failure: Exception? = null
            changed.forEach { (previous, next) ->
                try {
                    manager.cancelWorkById(UUID.fromString(previous.workId)).result.get()
                    // 用户同时取消或移除账号时不能重新复活任务。
                    if (store.get(next.id)?.let { it.workId == next.workId && it.isActive } == true) schedule(context, next)
                } catch (error: Exception) {
                    store.update(next.id, next.workId) {
                        if (it.isActive) it.copy(status = DownloadStatus.FAILED, error = tr("网络规则已更改，任务重排失败，请重试")) else it
                    }
                    if (failure == null) failure = error
                }
            }
            failure?.let { throw IllegalStateException(tr("下载网络规则已保存，部分任务未能重新排队，请在下载任务中重试"), it) }
        }
    }

    fun observe(context: Context): Flow<List<DownloadTask>> = combine(
        DownloadStore(context).observe(), WorkManager.getInstance(context).getWorkInfosByTagFlow(WORK_TAG),
    ) { tasks, work ->
        val states = work.associateBy { it.id.toString() }
        tasks.map { task -> reconcile(task, states[task.workId]) }.sortedByDescending { it.createdAt }
    }.flowOn(Dispatchers.IO)

    suspend fun cancel(context: Context, taskId: String) = withContext(Dispatchers.IO) {
        val task = DownloadStore(context).cancel(taskId) ?: return@withContext
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(task.workId)).result.get()
        Unit
    }

    /** 移除本机账号后调用。保留已完成文件及其他账号，先持久撤销再停止系统任务。 */
    suspend fun cancelProfile(context: Context, profileId: String): Unit = withContext(Dispatchers.IO) {
        require(profileId.isNotBlank()) { tr("账号标识无效") }
        val cancelled = DownloadStore(context).cancelProfile(profileId)
        if (cancelled.isEmpty()) return@withContext
        val manager = WorkManager.getInstance(context)
        var failure: Exception? = null
        cancelled.forEach { task ->
            try { manager.cancelWorkById(UUID.fromString(task.workId)).result.get() }
            catch (error: Exception) { if (failure == null) failure = error }
        }
        failure?.let { throw IllegalStateException(tr("下载已标记停止，但系统任务取消未完成，请重新打开应用确认"), it) }
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
            val next = store.update(task.id, task.workId) {
                it.copy(workId = UUID.randomUUID().toString(), destinationUri = destination.toString(), status = DownloadStatus.QUEUED,
                    downloadedBytes = 0, totalBytes = null, error = null, network = AppPreferencesStore(context).load().downloadNetwork)
            } ?: throw IllegalStateException(tr("下载记录已更新，请重试"))
            try {
                schedule(context, next)
            } catch (error: Exception) {
                store.update(next.id, next.workId) { it.copy(status = DownloadStatus.FAILED, error = tr("无法安排后台下载，请重试")) }
                throw IllegalStateException(tr("无法安排后台下载，请重试"), error)
            }
            Unit
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

    private fun schedule(context: Context, task: DownloadTask) {
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>().setId(UUID.fromString(task.workId))
            .setInputData(workDataOf(MediaDownloadWorker.TASK_ID to task.id))
            .addTag(WORK_TAG)
            .setConstraints(downloadConstraints(task.network))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("pathfin-download-${task.id}", ExistingWorkPolicy.REPLACE, request).result.get()
    }

    private fun reconcile(task: DownloadTask, info: WorkInfo?): DownloadTask {
        if (!task.isActive) return task
        return when (info?.state) {
            WorkInfo.State.CANCELLED -> task.copy(status = DownloadStatus.CANCELLED, error = tr("任务已停止；重试会从头重新下载"))
            WorkInfo.State.FAILED -> task.copy(status = DownloadStatus.FAILED, error = task.error ?: tr("下载未完成，请重试"))
            WorkInfo.State.SUCCEEDED -> task.copy(status = DownloadStatus.FAILED, error = tr("文件未完整保存，请重新下载"))
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> task.copy(status = DownloadStatus.QUEUED)
            else -> task
        }
    }

    internal const val WORK_TAG = "pathfin-media-download"
}

internal fun downloadConstraints(network: DownloadNetwork): Constraints = Constraints.Builder()
    .setRequiredNetworkType(if (network == DownloadNetwork.UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .build()

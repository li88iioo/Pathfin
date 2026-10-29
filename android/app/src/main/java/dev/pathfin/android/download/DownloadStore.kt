package dev.pathfin.android.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.i18n.tr
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 所有写入在 IO 执行；commit 后才把新任务交给 WorkManager。 */
internal class DownloadStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pathfin_downloads_v1", Context.MODE_PRIVATE)

    fun all(): List<DownloadTask> = synchronized(lock) { read() }
    fun get(id: String): DownloadTask? = all().firstOrNull { it.id == id }

    fun insert(task: DownloadTask) = synchronized(lock) {
        val tasks = read()
        check(tasks.none { it.destinationUri == task.destinationUri && it.isActive }) { tr("该目标文件已有下载任务") }
        write(tasks + task)
    }

    /** workId 隔离重试代次，迟到的旧 Worker 不能覆盖新任务或用户取消状态。 */
    fun update(id: String, workId: String, transform: (DownloadTask) -> DownloadTask): DownloadTask? = synchronized(lock) {
        val tasks = read()
        val current = tasks.firstOrNull { it.id == id && it.workId == workId } ?: return@synchronized null
        val next = transform(current).copy(updatedAt = System.currentTimeMillis())
        write(tasks.map { if (it.id == id) next else it })
        next
    }

    fun remove(id: String) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    /** 按任务 ID 撤销最新代次，网络规则重排与用户取消同时发生时也不能漏取消。 */
    fun cancel(id: String): DownloadTask? = synchronized(lock) {
        val tasks = read()
        val current = tasks.firstOrNull { it.id == id && it.isActive } ?: return@synchronized null
        val cancelled = current.copy(status = DownloadStatus.CANCELLED,
            error = tr("已取消；重试会从头重新下载"), updatedAt = System.currentTimeMillis())
        write(tasks.map { if (it.id == id) cancelled else it })
        cancelled
    }

    /** 先原子撤销账号的全部在途任务；Worker 的迟到进度不能使它们复活。 */
    fun cancelProfile(profileId: String): List<DownloadTask> = synchronized(lock) {
        val tasks = read()
        val cancelled = tasks.filter { it.profileId == profileId && it.isActive }.map {
            it.copy(status = DownloadStatus.CANCELLED, error = tr("账号已从本机移除，下载已停止"), updatedAt = System.currentTimeMillis())
        }
        if (cancelled.isNotEmpty()) {
            val replacements = cancelled.associateBy { it.id }
            write(tasks.map { replacements[it.id] ?: it })
        }
        cancelled
    }

    /** 一次落盘替换代次，正在停止的 Worker 不能覆盖新约束或新进度。 */
    fun replaceNetwork(network: DownloadNetwork): List<Pair<DownloadTask, DownloadTask>> = synchronized(lock) {
        val tasks = read()
        val changed = tasks.filter { it.isActive && it.network != network }.map { previous ->
            previous to previous.copy(workId = UUID.randomUUID().toString(), network = network,
                status = DownloadStatus.QUEUED, downloadedBytes = 0, totalBytes = null, error = null,
                updatedAt = System.currentTimeMillis())
        }
        if (changed.isNotEmpty()) {
            val replacements = changed.associate { it.second.id to it.second }
            write(tasks.map { replacements[it.id] ?: it })
        }
        changed
    }

    fun observe(): Flow<List<DownloadTask>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(all())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(all())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun read(): List<DownloadTask> = runCatching {
        DownloadTaskCodec.decode(preferences.getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    private fun write(tasks: List<DownloadTask>) {
        check(preferences.edit().putString(KEY, DownloadTaskCodec.encode(tasks)).commit()) { tr("无法保存下载任务，请检查本机存储空间") }
    }

    private companion object {
        val lock = Any()
        const val KEY = "tasks"
    }
}

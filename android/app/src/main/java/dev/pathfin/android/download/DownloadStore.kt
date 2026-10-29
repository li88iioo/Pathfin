package dev.pathfin.android.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.pathfin.android.i18n.tr
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
        val next = transform(current)
        write(tasks.map { if (it.id == id) next else it })
        next
    }

    fun remove(id: String) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    /** 只撤销调用方持有的代次；已撤销结果仍可用于重试未完成的系统停止。 */
    fun cancel(id: String, workId: String, reason: String = tr("已取消；重试会从头重新下载")): DownloadTask? = update(id, workId) {
        if (it.isActive) it.copy(status = DownloadStatus.CANCELLED, error = reason) else it
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

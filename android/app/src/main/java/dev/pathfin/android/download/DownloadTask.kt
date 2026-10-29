package dev.pathfin.android.download

import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.i18n.tr
import org.json.JSONArray
import org.json.JSONObject

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

/** 只保存本机目标与媒体标识；片源 URL、HTTP Header 和登录凭据不进入任务记录。 */
data class DownloadTask(
    val id: String,
    val workId: String,
    val profileId: String,
    val itemId: String,
    val displayName: String,
    val destinationUri: String,
    val sourceId: String? = null,
    val network: DownloadNetwork = DownloadNetwork.ANY,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val fileName: String = displayName,
    val mimeType: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let {
        (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
    }
    val queueDescription: String get() = if (network == DownloadNetwork.UNMETERED)
        tr("等待不计费网络或系统开始下载") else tr("等待网络或系统开始下载")
}

internal object DownloadTaskCodec {
    fun encode(tasks: List<DownloadTask>): String = JSONArray().apply {
        tasks.forEach { task ->
            put(JSONObject()
                .put("id", task.id).put("workId", task.workId)
                .put("profileId", task.profileId).put("itemId", task.itemId)
                .put("displayName", task.displayName).put("destinationUri", task.destinationUri)
                .put("sourceId", task.sourceId).put("network", task.network.name).put("status", task.status.name)
                .put("downloadedBytes", task.downloadedBytes).put("totalBytes", task.totalBytes)
                .put("fileName", task.fileName).put("mimeType", task.mimeType)
                .put("error", task.error).put("createdAt", task.createdAt).put("updatedAt", task.updatedAt))
        }
    }.toString()

    fun decode(value: String): List<DownloadTask> {
        val array = JSONArray(value)
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                DownloadTask(
                    id = json.getString("id"), workId = json.getString("workId"),
                    profileId = json.getString("profileId"), itemId = json.getString("itemId"),
                    displayName = json.getString("displayName"), destinationUri = json.getString("destinationUri"),
                    sourceId = json.optionalString("sourceId"), status = DownloadStatus.valueOf(json.getString("status")),
                    network = DownloadNetwork.entries.firstOrNull { it.name == json.optString("network") } ?: DownloadNetwork.ANY,
                    downloadedBytes = json.optLong("downloadedBytes").coerceAtLeast(0),
                    totalBytes = if (json.isNull("totalBytes")) null else json.optLong("totalBytes").takeIf { it >= 0 },
                    fileName = json.optString("fileName", json.getString("displayName")), mimeType = json.optionalString("mimeType"),
                    error = json.optionalString("error"), createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
                )
            }.getOrNull()
        }
    }

    private fun JSONObject.optionalString(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() }
}

internal fun downloadDisplayName(value: String): String = value
    .filter { it >= ' ' && it != '\u007f' && it != '/' && it != '\\' }
    .trim().take(180).ifBlank { tr("媒体文件") }

/** 下载页与系统通知共用的大小文案。 */
internal fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes.toDouble() / (1024L * 1024))
    bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.0f KB", bytes.toDouble() / 1024)
    else -> "$bytes B"
}

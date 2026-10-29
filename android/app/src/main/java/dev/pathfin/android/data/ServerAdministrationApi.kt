package dev.pathfin.android.data

import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 只读入口不会执行任务；所有变更由明确的运行/取消/重启调用发起。 */
class ServerAdministrationApi(private val api: JellyfinApi = JellyfinApi()) {
    suspend fun info(session: Session): ServerAdministrationInfo {
        requireAdministrator(session)
        val json = api.managementObject(session, ManagementOperation.ADMIN_INFO, "System/Info")
        val version = json.text("Version") ?: throw ApiException(null, tr("服务器信息缺少版本，请重试"))
        return ServerAdministrationInfo(json.text("ServerName") ?: "Jellyfin", version,
            json.text("ProductName") ?: "Jellyfin", json.text("OperatingSystemDisplayName") ?: json.text("OperatingSystem"),
            json.opt("HasPendingRestart") == true, json.opt("IsShuttingDown") == true, json.opt("CanSelfRestart") != false)
    }

    suspend fun tasks(session: Session): List<ServerScheduledTask> {
        requireAdministrator(session)
        val array = api.managementArray(session, ManagementOperation.ADMIN_TASKS, "ScheduledTasks")
        return objects(array, 500).map { raw ->
            val id = raw.text("Id") ?: throw ApiException(null, tr("计划任务缺少标识，请重试"))
            identifier(id)
            val last = raw.optJSONObject("LastExecutionResult")
            ServerScheduledTask(id, raw.text("Name") ?: tr("未命名任务"), raw.text("Description").orEmpty(), raw.text("Category").orEmpty(),
                when (raw.opt("State")) { "Idle" -> ServerTaskState.IDLE; "Running" -> ServerTaskState.RUNNING
                    "Cancelling" -> ServerTaskState.CANCELLING; null -> ServerTaskState.UNKNOWN; else -> ServerTaskState.UNKNOWN },
                (raw.opt("CurrentProgressPercentage") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 },
                last?.text("StartTimeUtc"), last?.text("EndTimeUtc"), last?.text("Status"), raw.text("Key").orEmpty())
        }
    }

    suspend fun runTask(session: Session, taskId: String) = taskAction(session, taskId, true)
    suspend fun cancelTask(session: Session, taskId: String) = taskAction(session, taskId, false)
    private suspend fun taskAction(session: Session, taskId: String, run: Boolean) {
        requireAdministrator(session); identifier(taskId)
        api.managementObject(session, if (run) ManagementOperation.ADMIN_TASK_RUN else ManagementOperation.ADMIN_TASK_CANCEL,
            "ScheduledTasks/Running/$taskId", method = if (run) "POST" else "DELETE")
    }

    suspend fun activity(session: Session, startIndex: Int = 0, limit: Int = 30): ServerActivityPage {
        requireAdministrator(session)
        require(startIndex >= 0 && limit in 1..100) { tr("活动日志分页无效") }
        val json = api.managementObject(session, ManagementOperation.ADMIN_ACTIVITY, "System/ActivityLog/Entries",
            query = mapOf("startIndex" to startIndex.toString(), "limit" to limit.toString()))
        val array = json.optJSONArray("Items") ?: throw ApiException(null, tr("服务器返回的活动日志格式不正确"))
        val items = objects(array, limit).map { raw ->
            val id = (raw.opt("Id") as? Number)?.toLong() ?: throw ApiException(null, tr("活动日志缺少标识"))
            ServerActivity(id, redact(session, raw.text("Name")) ?: tr("服务器活动"), redact(session, raw.text("Overview") ?: raw.text("ShortOverview")),
                raw.text("Date"), raw.text("Severity"))
        }
        val total = (json.opt("TotalRecordCount") as? Number)?.toInt()?.takeIf { it >= startIndex + items.size }
            ?: throw ApiException(null, tr("服务器返回的活动日志总数无效"))
        return ServerActivityPage(items, total, startIndex)
    }

    suspend fun logs(session: Session): List<ServerLogFile> {
        requireAdministrator(session)
        return objects(api.managementArray(session, ManagementOperation.ADMIN_LOGS, "System/Logs"), 2000).map { raw ->
            val name = raw.text("Name") ?: throw ApiException(null, tr("系统日志缺少名称"))
            logName(name)
            ServerLogFile(name, raw.text("DateModified"), (raw.opt("Size") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0)
        }
    }

    suspend fun readLog(session: Session, file: ServerLogFile): ServerLogContent {
        requireAdministrator(session); logName(file.name)
        val text = api.managementText(session, ManagementOperation.ADMIN_LOG_READ, "System/Logs/Log",
            query = mapOf("name" to file.name), maxBytes = MAX_LOG_BYTES)
        return withContext(Dispatchers.Default) {
            ServerLogContent(redact(session, text).orEmpty(), text.toByteArray(Charsets.UTF_8).size >= MAX_LOG_BYTES)
        }
    }

    /** 调用方必须独立确认；请求失败不能自动重放重启。 */
    suspend fun restart(session: Session) {
        requireAdministrator(session)
        api.managementObject(session, ManagementOperation.ADMIN_RESTART, "System/Restart", method = "POST")
    }

    private fun requireAdministrator(session: Session) { if (!session.isAdministrator) throw ApiException(403, tr("只有服务器管理员可以管理服务器")) }
    private fun identifier(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { tr("计划任务标识无效") } }
    private fun logName(value: String) { require(value.length in 1..255 && value !in listOf(".", "..") && value.none { it.isISOControl() || it in "/\\" }) { tr("系统日志名称无效") } }
    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotBlank)
    private fun objects(array: JSONArray, maximum: Int): List<JSONObject> {
        if (array.length() > maximum) throw ApiException(null, tr("服务器返回的列表过大，请缩小查询范围"))
        return (0 until array.length()).map { array.optJSONObject(it) ?: throw ApiException(null, tr("服务器返回的列表格式不正确")) }
    }

    private fun redact(session: Session, raw: String?): String? {
        if (raw == null) return null
        var text = raw
        if (session.accessToken.isNotEmpty()) {
            text = text.replace(session.accessToken, tr("[已隐藏凭据]"))
                .replace(URLEncoder.encode(session.accessToken, "UTF-8"), tr("[已隐藏凭据]"), ignoreCase = true)
        }
        text = text.replace(Regex("(?i)([?&](?:api_key|access_token|token|x-emby-token)=)[^&\\s\\\"<>]+"), tr("$1[已隐藏]"))
        return text.replace(Regex("(?i)((?:authorization|x-emby-token|password)\\s*[:=]\\s*)[^\\r\\n]+"), tr("$1[已隐藏]"))
    }

    companion object { const val MAX_LOG_BYTES = 512 * 1024 }
}

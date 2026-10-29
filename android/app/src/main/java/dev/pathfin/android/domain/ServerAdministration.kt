package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

data class ServerAdministrationInfo(
    val name: String,
    val version: String,
    val productName: String,
    val operatingSystem: String?,
    val hasPendingRestart: Boolean,
    val isShuttingDown: Boolean,
    val canSelfRestart: Boolean,
)

enum class ServerTaskState(private val labelKey: String) {
    IDLE("未运行"), RUNNING("运行中"), CANCELLING("正在取消"), UNKNOWN("未知状态");

    val label: String get() = tr(labelKey)
}

data class ServerScheduledTask(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val state: ServerTaskState,
    val progressPercent: Double?,
    val lastStartedAt: String?,
    val lastEndedAt: String?,
    val lastResult: String?,
    val key: String = "",
)

data class ServerActivity(
    val id: Long,
    val name: String,
    val overview: String?,
    val date: String?,
    val severity: String?,
)

data class ServerActivityPage(val items: List<ServerActivity>, val total: Int, val startIndex: Int)

data class ServerLogFile(val name: String, val modifiedAt: String?, val sizeBytes: Long)

data class ServerLogContent(val text: String, val truncated: Boolean)

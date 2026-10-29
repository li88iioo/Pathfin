package dev.pathfin.android.diagnostics

import android.content.Context
import android.os.Build
import dev.pathfin.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常写入应用私有目录，只在用户主动复制或反馈时离开本机。
 * 日志内容固定英文，便于在 Issue 中检索；写入前隐藏凭据和服务器地址。
 */
object CrashLog {
    internal const val MAX_BYTES = 256 * 1024
    internal const val ENTRY_MARK = "===== Pathfin crash"
    private const val FILE_NAME = "crash_log.txt"
    private val lock = Any()

    fun install(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler(previous) { thread, error ->
            append(file, entry(thread.name, error, Date()))
        })
    }

    internal fun handler(previous: Thread.UncaughtExceptionHandler?, record: (Thread, Throwable) -> Unit) =
        Thread.UncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }

    fun read(context: Context): String? = synchronized(lock) {
        file(context).takeIf(File::isFile)?.readText()?.takeIf(String::isNotBlank)
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        runCatching { file(context).let { !it.exists() || it.delete() } }.getOrDefault(false)
    }

    fun deviceInfo(): String = buildString {
        appendLine("App: Pathfin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.APPLICATION_ID}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    internal fun entry(thread: String, error: Throwable, time: Date): String = redact(buildString {
        appendLine("$ENTRY_MARK ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(time)} =====")
        appendLine(deviceInfo())
        appendLine("Thread: $thread")
        appendLine(error.stackTraceToString().trimEnd())
        appendLine()
    })

    /** 隐藏令牌、密码、URL 账号和主机名；崩溃日志可能被公开贴到 Issue。 */
    internal fun redact(text: String): String = text
        .replace(Regex("(?i)((?:authorization|x-emby-authorization|cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+"), "$1[hidden]")
        .replace(Regex("(?i)(\\b(?:api_?key|access_?token|token|x-emby-token|x-mediabrowser-token|pw|(?:current|new)?password)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^&\\s,;\"'<>}\\]]+)"), "$1[hidden]")
        .replace(Regex("(?i)([?&](?:sign|signature|auth_key|authkey|x-amz-signature|x-amz-credential|x-amz-security-token|x-oss-signature|x-oss-credential|x-oss-security-token)=)[^&\\s\"'<>]+"), "$1[hidden]")
        .replace(Regex("(?i)\\b(https?|wss?)://[^\\s/?#\"'<>]+"), "$1://[host]")
        .replace(Regex("(?i)(unable to resolve host\\s+)[\"'][^\"']+[\"']"), "$1[host]")
        .replace(Regex("(?i)(unknownhostexception:\\s*)(?!unable\\b)[^\\s:]+"), "$1[host]")
        .replace(Regex("(?i)(failed to connect to\\s+)[^\\s(]+"), "$1[host]")
        .replace(Regex("(?<=/)(?:\\d{1,3}\\.){3}\\d{1,3}(?=[:\\s)]|$)"), "[host]")

    /** 新记录追加在末尾，超出上限时整条丢弃最旧的记录；单条超限只保留开头的异常与栈顶。 */
    internal fun bounded(existing: String, entry: String): String {
        var log = existing + entry
        while (log.toByteArray().size > MAX_BYTES) {
            val next = log.indexOf(ENTRY_MARK, 1)
            if (next < 0) return entry.take(MAX_BYTES / 4)
            log = log.substring(next)
        }
        return log
    }

    /** 取最近的若干条记录，从整条记录开头截起；最后一条本身超长时保留它的开头。 */
    fun tail(log: String, maxChars: Int): String {
        if (log.length <= maxChars) return log
        val mark = log.indexOf(ENTRY_MARK, log.length - maxChars)
        return if (mark >= 0) log.substring(mark) else log.substring(log.lastIndexOf(ENTRY_MARK).coerceAtLeast(0)).take(maxChars)
    }

    private fun append(file: File, entry: String) = synchronized(lock) {
        file.writeText(bounded(file.takeIf(File::isFile)?.readText().orEmpty(), entry))
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}

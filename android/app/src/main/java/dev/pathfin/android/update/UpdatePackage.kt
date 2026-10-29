package dev.pathfin.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import dev.pathfin.android.BuildConfig
import dev.pathfin.android.i18n.tr
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 更新链路自己的可读失败；其他网络异常统一提示连接问题，不暴露底层地址。 */
internal class UpdateFailure(message: String) : Exception(message)

private const val APK_MIME = "application/vnd.android.package-archive"
private const val LATEST_RELEASE = "https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest"
private val userAgent = "Pathfin-Android/${BuildConfig.VERSION_NAME}"

/** 不允许 HTTPS 降级；GitHub 资产会跳转到其 CDN，仍保持 HTTPS。 */
private val updateClient by lazy {
    OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
}

/** 读取最新正式 Release；仓库尚未发布返回 null。 */
internal suspend fun fetchLatestRelease(): AppRelease? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(LATEST_RELEASE).header("User-Agent", userAgent)
        .header("Accept", "application/vnd.github+json").build()
    updateClient.newCall(request).await { response ->
        when {
            response.code == 404 -> null
            response.code == 403 || response.code == 429 -> throw UpdateFailure(tr("GitHub 请求过于频繁，请稍后重试"))
            !response.isSuccessful -> throw UpdateFailure(tr("检查更新失败（HTTP %1\$s）", response.code))
            else -> ReleaseParser.parse(JSONObject(response.body.string()))
        }
    }
}

/** 写入 .part，长度与摘要都吻合才改名为 .apk；任何失败都删除半成品。 */
internal suspend fun downloadRelease(release: AppRelease, directory: File, onProgress: (Long) -> Unit): File =
    withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { tr("无法创建更新目录") }
        val partial = File(directory, "pathfin-${release.version}.part")
        val apk = File(directory, "pathfin-${release.version}.apk")
        var complete = false
        try {
            val request = Request.Builder().url(release.apkUrl).header("User-Agent", userAgent).build()
            val coroutine = currentCoroutineContext()
            updateClient.newCall(request).await { response ->
                if (response.code != 200) throw UpdateFailure(tr("下载失败（HTTP %1\$s）", response.code))
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var reported = 0L
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= release.apkSize) { tr("安装包大小异常，请重新下载") }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (bytes - reported >= 256 * 1024) { reported = bytes; onProgress(bytes) }
                        }
                    }
                }
                check(bytes == release.apkSize) { tr("安装包下载不完整，请重新下载") }
                release.sha256?.let { expected ->
                    check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { tr("安装包校验失败，请重新下载") }
                }
                onProgress(bytes)
            }
            apk.delete()
            check(partial.renameTo(apk)) { tr("无法保存安装包") }
            complete = true
            apk
        } finally {
            partial.delete()
            if (!complete) apk.delete()
        }
    }

/** 父任务取消时关闭阻塞请求；流与文件仍在原协程退出前完成清理。 */
private suspend fun <T> Call.await(block: (okhttp3.Response) -> T): T = coroutineScope {
    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@await.cancel() }
    }
    try {
        execute().use(block)
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        cancellation.cancel()
    }
}

internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** 包名、签名必须与当前应用一致，版本码必须更高；否则系统安装器也会拒绝或造成降级。 */
internal fun checkUpgrade(installed: ApkIdentity, candidate: ApkIdentity) {
    check(candidate.packageName == installed.packageName) { tr("安装包与当前应用不是同一个包，已拒绝安装") }
    check(candidate.signers.isNotEmpty() && candidate.signers == installed.signers) { tr("安装包签名与当前应用不一致，已拒绝安装") }
    check(candidate.versionCode > installed.versionCode) { tr("安装包版本不高于当前版本，已拒绝安装") }
}

internal object UpdateFiles {
    fun directory(context: Context) = File(context.filesDir, "updates")

    fun verify(context: Context, apk: File) {
        check(apk.isFile) { tr("安装包不存在，请重新下载") }
        val candidate = archiveIdentity(context, apk) ?: error(tr("安装包损坏或无法解析，请重新下载"))
        checkUpgrade(installedIdentity(context), candidate)
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 已安装、无法解析或超过一天的安装包都删除；正在交给安装器的新版本保留到安装完成。 */
    fun cleanup(context: Context, keepPartial: Boolean = false) {
        val files = directory(context).listFiles() ?: return
        val installed = installedIdentity(context).versionCode
        val stale = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        files.forEach { file ->
            val obsolete = when (file.extension) {
                "part" -> !keepPartial
                "apk" -> file.lastModified() < stale || (archiveIdentity(context, file)?.versionCode ?: 0) <= installed
                else -> true
            }
            if (obsolete) file.delete()
        }
    }

    private fun installedIdentity(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, signingFlags).identity()

    private fun archiveIdentity(context: Context, apk: File) =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags)?.identity()

    @Suppress("DEPRECATION")
    private val signingFlags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity() = ApkIdentity(
        packageName = packageName,
        versionCode = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
        signers = (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
            .orEmpty().map { it.toCharsString() }.toSet(),
    )
}

class UpdateFileProvider : FileProvider()

/** 覆盖安装完成后系统会发出此广播：立即删除已安装的安装包。 */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try { runCatching { UpdateFiles.cleanup(context.applicationContext) } } finally { pending.finish() }
        }.start()
    }
}

package dev.pathfin.android.download

import dev.pathfin.android.domain.DownloadHeaders
import dev.pathfin.android.domain.DownloadMimeType
import dev.pathfin.android.domain.DownloadSource
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class DownloadFailure(message: String) : IOException(message)
internal data class DownloadReceipt(val bytes: Long, val totalBytes: Long?, val mimeType: String)

/** 手动处理重定向，每次都从空请求重新计算 Header；绝不盲跟携带认证的 302。 */
internal class MediaDownloadClient(
    client: OkHttpClient = OkHttpClient(),
    private val requireNetworkPermission: suspend (String) -> Unit = {},
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).build()

    /** 在调用方的流式 IO 线程执行；输出由该次 SAF Operation 托管，完成关闭后才能宣称保存成功。 */
    suspend fun copy(
        session: Session,
        source: DownloadSource,
        openOutput: () -> OutputStream,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): DownloadReceipt {
        if (source.isShortcutFile) throw DownloadFailure(tr("这是 STRM 快捷文件，无法当作影片下载"))
        var target = source.url
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) { hop ->
            requireTarget(session, source, target)
            if (!visited.add(target)) throw DownloadFailure(tr("下载地址存在循环重定向，请检查片源"))
            requireNetworkPermission(target)
            val request = Request.Builder().url(target).apply {
                DownloadHeaders.forUrl(session, source, target).forEach { (name, value) -> header(name, value) }
                header("Accept-Encoding", "identity")
            }.build()
            val result = withResponse(request) { response ->
                if (response.code in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) throw DownloadFailure(tr("下载地址重定向次数过多"))
                    val location = response.header("Location") ?: throw DownloadFailure(tr("服务器未返回有效下载地址"))
                    val next = response.request.url.resolve(location)?.toString()
                        ?: throw DownloadFailure(tr("服务器返回的下载地址无效"))
                    if (response.request.url.scheme == "https" && ServerUrls.httpUri(next)?.scheme.equals("http", true)) {
                        throw DownloadFailure(tr("下载地址从 HTTPS 降级到 HTTP，已阻止不安全重定向"))
                    }
                    Hop.Redirect(next)
                } else {
                    requireSuccessfulResponse(response)
                    if (response.header("Content-Encoding")?.equals("identity", true) == false) {
                        throw DownloadFailure(tr("服务器返回了压缩传输内容，无法确认实际媒体文件"))
                    }
                    val body = response.body
                    val length = body.contentLength().takeIf { it >= 0 }
                    val total = length ?: source.expectedBytes?.takeIf { it > 0 }
                    val input = body.byteStream().buffered()
                    input.mark(SNIFF_BYTES + 1)
                    val prefix = ByteArray(SNIFF_BYTES)
                    var prefixSize = 0
                    while (prefixSize < prefix.size) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(prefix, prefixSize, prefix.size - prefixSize)
                        if (count < 0) break
                        prefixSize += count
                    }
                    input.reset()
                    DownloadContent.validate(source, response.request.url.toString(), response.header("Content-Type"),
                        response.header("Content-Disposition"), prefix.copyOf(prefixSize))
                    var downloaded = 0L
                    var lastProgress = System.nanoTime()
                    onProgress(0, total)
                    val output = openOutput()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        try { output.write(buffer, 0, count) }
                        catch (_: IOException) { throw DownloadFailure(tr("无法保存文件，请检查目标位置与剩余空间")) }
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastProgress >= TimeUnit.MILLISECONDS.toNanos(750)) {
                            onProgress(downloaded, total)
                            lastProgress = now
                        }
                    }
                    try { output.flush() }
                    catch (_: IOException) { throw DownloadFailure(tr("无法完成文件保存，请检查目标位置与剩余空间")) }
                    if (downloaded == 0L) throw DownloadFailure(tr("服务器返回了空文件，未完成下载"))
                    if (total != null && downloaded != total) throw DownloadFailure(tr("文件传输不完整，请重新下载"))
                    onProgress(downloaded, total ?: downloaded)
                    Hop.Complete(DownloadReceipt(downloaded, total ?: downloaded,
                        DownloadContent.mimeType(response.header("Content-Type"), source)))
                }
            }
            when (result) {
                is Hop.Redirect -> target = result.url
                is Hop.Complete -> return result.receipt
            }
        }
        throw DownloadFailure(tr("下载地址重定向次数过多"))
    }

    private fun requireTarget(session: Session, source: DownloadSource, target: String) {
        val parsed = ServerUrls.httpUri(target) ?: throw DownloadFailure(tr("仅支持下载 HTTP(S) 实际媒体文件"))
        if (parsed.scheme.equals("http", true) && !session.server.allowInsecureHttp) {
            val explicitExternalHttp = !source.trustedServer &&
                ServerUrls.httpUri(source.url)?.scheme.equals("http", true) && !ServerUrls.isWithinBase(session.server, source.url)
            if (!explicitExternalHttp) throw DownloadFailure(tr("服务器使用 HTTP，请先在服务器设置中明确允许"))
        }
        if (!ServerUrls.isWithinBase(session.server, target) && session.accessToken.isNotEmpty()) {
            var decoded = target
            repeat(2) { decoded = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded) }
            if (target.contains(session.accessToken) || decoded.contains(session.accessToken)) {
                throw DownloadFailure(tr("下载地址包含服务器凭据，已阻止发送至外部站点"))
            }
        }
    }

    private fun requireSuccessfulResponse(response: Response) {
        when {
            response.code == 401 -> throw DownloadFailure(tr("登录已失效，请重新登录后重试下载"))
            response.code == 403 -> throw DownloadFailure(tr("该账号没有下载此媒体的权限"))
            response.code == 404 -> throw DownloadFailure(tr("媒体文件不存在或下载地址已失效"))
            response.code == 206 -> throw DownloadFailure(tr("服务器只返回了部分文件，请重试下载"))
            !response.isSuccessful -> throw DownloadFailure(tr("服务器未能提供完整文件（HTTP %1\$s），请稍后重试", response.code))
        }
    }

    /** 子协程在父任务取消时立即关闭 Call，避免阻塞读取拖延取消。 */
    private suspend fun <T> withResponse(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            // SAF open/write 必须留在 Operation 的同一线程；另起 IO 子线程会使 runBlocking
            // 被 interrupt 后先退出，而迟到写入还活着，导致 URI 租约提前归还。
            call.execute().use { block(it) }
        } catch (error: IOException) {
            // Call.cancel() 常以 SocketException 结束阻塞读取，仍须保留任务取消语义。
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellation.cancel()
        }
    }

    private sealed interface Hop {
        data class Redirect(val url: String) : Hop
        data class Complete(val receipt: DownloadReceipt) : Hop
    }

    private companion object {
        const val MAX_REDIRECTS = 8
        const val SNIFF_BYTES = 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** 200 响应也可能是登录页、播放清单或 .strm，不能标记为下载完成。 */
internal object DownloadContent {
    fun validate(source: DownloadSource, url: String, contentType: String?, disposition: String?, prefix: ByteArray) {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase()
        val name = source.fileName.lowercase()
        val shortcut = listOf(".strm", ".m3u", ".m3u8", ".mpd")
        if (source.isShortcutFile || shortcut.any { path.endsWith(it) || name.endsWith(it) } ||
            disposition.orEmpty().contains(Regex("(?i)\\.(strm|m3u8?|mpd)(?:[\\\"';\\s]|$)"))) {
            throw DownloadFailure(tr("该片源是快捷文件或流媒体清单，暂不支持离线下载"))
        }
        if (mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml") ||
            mime == "application/json" || mime.endsWith("+json") || mime == "text/html" || mime == "application/xhtml+xml") {
            throw DownloadFailure(tr("服务器返回了播放清单或网页，未获得实际媒体文件"))
        }
        val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t').lowercase()
        if (text.startsWith("#extm3u") || text.startsWith("<mpd") || text.startsWith("<?xml") ||
            text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("{") ||
            text.startsWith("https://") || text.startsWith("http://")) {
            throw DownloadFailure(tr("服务器返回的内容不是实际媒体文件，未完成下载"))
        }
    }

    fun mimeType(contentType: String?, source: DownloadSource): String {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) return mime
        source.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }?.let { return it }
        return DownloadMimeType.fromFile(source.fileName) ?: "video/*"
    }
}

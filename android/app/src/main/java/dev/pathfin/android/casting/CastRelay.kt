package dev.pathfin.android.casting

import dev.pathfin.android.domain.PlaybackHeaders
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 单片源、单设备、随机路径的内存转发；构造及 close 必须在 IO 调度器。 */
internal class CastRelay(
    bindAddress: String,
    private val deviceAddress: String,
    private val playback: PlaybackRequest,
    client: OkHttpClient = OkHttpClient(),
    allowLoopback: Boolean = false,
) : Closeable {
    val mimeType = progressiveMime(playback)
    private val closed = AtomicBoolean(false)
    private val bytes = AtomicLong()
    val bytesServed: Long get() = bytes.get()
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "pathfin-cast-relay").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cache(null).connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val server = ServerSocket()
    private val path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes)) + "/media"
    val url: String

    init {
        val local = localIpv4(bindAddress, allowLoopback) ?: throw CastException(tr("本机局域网地址无效"))
        if (localIpv4(deviceAddress, allowLoopback) == null) throw CastException(tr("DLNA 设备地址无效"))
        try {
            server.reuseAddress = false
            server.bind(InetSocketAddress(local, 0), 8)
            url = "http://$bindAddress:${server.localPort}$path"
            Thread(::accept, "pathfin-cast-accept").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            close()
            throw CastException(tr("无法建立局域网临时媒体转发"))
        }
    }

    private fun accept() {
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            sockets += socket
            if (closed.get() || socket.inetAddress.hostAddress != deviceAddress) {
                socket.close(); sockets -= socket; continue
            }
            try { workers.execute { socket.use { serve(it) }; sockets -= socket } }
            catch (_: java.util.concurrent.RejectedExecutionException) { socket.close(); sockets -= socket }
        }
    }

    private fun serve(socket: Socket) {
        var headersSent = false
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val raw = ByteArrayOutputStream()
            var tail = 0
            while (raw.size() < MAX_HEADERS && !closed.get()) {
                val value = input.read()
                if (value < 0) return
                raw.write(value)
                tail = (tail shl 8) or value
                if (tail == 0x0d0a0d0a) break
            }
            if (raw.size() >= MAX_HEADERS || closed.get()) { reply(socket, 431); return }
            val lines = raw.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val request = lines.first().split(' ')
            if (request.size != 3 || request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) { reply(socket, 400); return }
            val method = request[0]
            if (method !in listOf("GET", "HEAD")) { reply(socket, 405); return }
            if (request[1] != path) { reply(socket, 404); return }
            val headers = linkedMapOf<String, String>()
            for (line in lines.drop(1).takeWhile(String::isNotEmpty)) {
                val name = line.substringBefore(':').lowercase(Locale.ROOT)
                if (':' !in line || !name.matches(Regex("[!#$%&'*+.^_`|~0-9a-z-]+")) ||
                    line.any { it.code < 0x20 && it != '\t' } || headers.put(name, line.substringAfter(':').trim()) != null) {
                    reply(socket, 400); return
                }
            }
            // 请求体及复用连接均不支持，避免请求走私和任意代理。
            if (headers.containsKey("transfer-encoding") || headers["content-length"]?.let { it != "0" } == true) { reply(socket, 400); return }
            val range = headers["range"]
            if (range != null && !validRange(range)) { reply(socket, 416); return }
            upstream(method, range).use { response ->
                if (closed.get()) return
                if (response.code !in listOf(200, 206, 416)) { reply(socket, 502); return }
                if (response.code == 416) {
                    val contentRange = response.header("Content-Range")?.takeIf { it.matches(Regex("bytes \\*/[0-9]{1,19}")) }
                    reply(socket, 416, contentRange?.let { mapOf("Content-Range" to it) }.orEmpty()); return
                }
                val responseMime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                if (isManifestMime(responseMime) || responseMime?.let {
                        !it.startsWith("video/") && !it.startsWith("audio/") && it !in listOf("application/octet-stream", "binary/octet-stream", "application/vnd.rn-realmedia")
                    } == true) { reply(socket, 415); return }
                if (method == "GET") {
                    // 仅 peek 少量内容，不消耗实际媒体；防止伪装 MIME 的 HLS/DASH/HTML 被传给设备。
                    val peek = response.peekBody(512).bytes()
                    val charset = when {
                        peek.size >= 2 && peek[0] == 0xff.toByte() && peek[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                        peek.size >= 2 && peek[0] == 0xfe.toByte() && peek[1] == 0xff.toByte() -> Charsets.UTF_16BE
                        else -> Charsets.UTF_8
                    }
                    val prefix = peek.toString(charset).trimStart { it == '\uFEFF' || it.isWhitespace() }
                    if (prefix.startsWith("#EXTM3U", true) || Regex("^<\\??(?:xml|MPD|html|!DOCTYPE)", RegexOption.IGNORE_CASE).containsMatchIn(prefix) ||
                        prefix.startsWith("http://") || prefix.startsWith("https://")) { reply(socket, 415); return }
                }
                val forwarded = linkedMapOf("Content-Type" to (responseMime?.takeIf { it.startsWith("video/") || it.startsWith("audio/") } ?: mimeType),
                    "Connection" to "close", "Cache-Control" to "no-store", "transferMode.dlna.org" to "Streaming")
                response.header("Content-Length")?.takeIf { it.matches(Regex("[0-9]{1,19}")) && it.toLongOrNull() != null }
                    ?.let { forwarded["Content-Length"] = it }
                if (response.code == 206) {
                    val contentRange = response.header("Content-Range")?.takeIf { validContentRange(it) }
                        ?: run { reply(socket, 502); return }
                    forwarded["Content-Range"] = contentRange
                }
                if (response.header("Accept-Ranges").equals("bytes", true)) forwarded["Accept-Ranges"] = "bytes"
                val output = socket.getOutputStream()
                headersSent = true
                output.write(header(response.code, forwarded)); output.flush()
                if (method == "HEAD") return
                val source = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (!closed.get()) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (closed.get()) break
                    output.write(buffer, 0, read); output.flush()
                    bytes.addAndGet(read.toLong())
                }
            }
        } catch (_: Exception) {
            // 仅固定本地错误；异常可能包含签名片源，禁止日志、转发或显示 exception.message。
            if (!closed.get() && !headersSent) runCatching { reply(socket, 502) }
        }
    }

    private fun upstream(method: String, range: String?): Response {
        var target = playback.stream.uri.toHttpUrlOrNull() ?: throw CastException(tr("媒体地址无效"))
        var previousScheme = target.scheme
        repeat(6) { hop ->
            if (closed.get()) throw IOException("Relay revoked")
            if (ServerUrls.httpUri(target.toString()) == null || target.encodedUsername.isNotEmpty() || target.encodedPassword.isNotEmpty() ||
                target.fragment != null || PlaybackHeaders.containsExternalServerCredential(playback.session, target.toString())) throw CastException(tr("媒体地址不安全"))
            if (target.scheme == "http" && (previousScheme == "https" || !PlaybackHeaders.allowsCleartext(playback))) throw CastException(tr("已阻止媒体连接降级"))
            val builder = Request.Builder().url(target).method(method, null).header("Accept-Encoding", "identity")
            PlaybackHeaders.forUrl(playback, target.toString()).forEach { (name, value) -> builder.header(name, value) }
            if (range != null) builder.header("Range", range)
            val response = client.newCall(builder.build()).execute()
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            response.close()
            if (hop == 5 || location.isNullOrBlank()) throw CastException(tr("媒体重定向过多或无效"))
            previousScheme = target.scheme
            target = target.resolve(location) ?: throw CastException(tr("媒体重定向无效"))
        }
        throw CastException(tr("媒体重定向过多"))
    }

    /** 先撤销可见 lease；仅关闭无 TLS 的入站 sockets，后续 close 在 IO 清理上游客户端。 */
    fun revoke() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            sockets.toList().forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        revoke()
        client.dispatcher.cancelAll()
        workers.shutdownNow()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun reply(socket: Socket, code: Int, fields: Map<String, String> = emptyMap()) {
        socket.getOutputStream().apply { write(header(code, fields + mapOf("Content-Length" to "0", "Connection" to "close", "Cache-Control" to "no-store"))); flush() }
    }

    companion object {
        private const val MAX_HEADERS = 16 * 1024
        private fun header(code: Int, fields: Map<String, String>): ByteArray {
            val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed";
                415 -> "Unsupported Media Type"; 416 -> "Range Not Satisfiable"; 431 -> "Request Header Fields Too Large"; else -> "Bad Gateway" }
            return ("HTTP/1.1 $code $reason\r\n" + fields.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1)
        }
        internal fun validRange(value: String): Boolean {
            val match = Regex("bytes=([0-9]{0,19})-([0-9]{0,19})").matchEntire(value) ?: return false
            val (start, end) = match.destructured
            if (start.isEmpty()) return end.toLongOrNull()?.let { it > 0 } == true
            val first = start.toLongOrNull() ?: return false
            return end.isEmpty() || end.toLongOrNull()?.let { it >= first } == true
        }
        private fun validContentRange(value: String): Boolean {
            val match = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19}|\\*)").matchEntire(value) ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val end = match.groupValues[2].toLongOrNull() ?: return false
            val total = match.groupValues[3]
            return end >= start && (total == "*" || total.toLongOrNull()?.let { it > end } == true)
        }
        private fun isManifestMime(value: String?): Boolean = value?.let {
            "mpegurl" in it || "dash+xml" in it || it.startsWith("text/") || it in listOf("application/xml", "application/json")
        } == true

        internal fun progressiveMime(request: PlaybackRequest): String {
            val url = request.stream.uri.toHttpUrlOrNull() ?: throw CastException(tr("媒体地址无效"))
            val path = url.encodedPath.lowercase(Locale.ROOT)
            val container = request.source.container.orEmpty().lowercase(Locale.ROOT)
            val supplied = request.stream.mimeType?.lowercase(Locale.ROOT)
            if (isManifestMime(supplied) || path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".strm") ||
                container in listOf("m3u8", "hls", "mpd", "dash")) throw CastException(tr("DLNA 暂不支持 HLS/DASH 清单或 STRM 文本，请选择直接媒体文件"))
            if (supplied?.matches(Regex("(?:video|audio)/[a-z0-9.+-]{1,64}")) == true) return supplied
            val extension = path.substringAfterLast('.', "").takeIf { '/' !in it }
            return when (extension?.takeIf { it in FORMATS } ?: container) {
                "mp4", "m4v", "mov" -> "video/mp4"
                "mkv", "mk3d" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "ts", "m2ts", "mts" -> "video/mp2t"
                "mpeg", "mpg", "vob" -> "video/mpeg"
                "wmv" -> "video/x-ms-wmv"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "m4a", "aac" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> throw CastException(tr("无法确定此片源的直接媒体格式，请更换可直接播放的媒体文件"))
            }
        }
        private val FORMATS = setOf("mp4", "m4v", "mov", "mkv", "mk3d", "webm", "avi", "ts", "m2ts", "mts", "mpeg", "mpg", "vob", "wmv", "mp3", "flac", "m4a", "aac", "wav")
    }
}

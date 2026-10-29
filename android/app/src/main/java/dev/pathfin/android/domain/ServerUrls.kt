package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.net.URI
import java.util.Locale

/** 服务端地址与媒体地址的边界；不使用字符串前缀判断 origin。 */
object ServerUrls {
    fun normalize(baseUrl: String, allowInsecureHttp: Boolean = false): ServerConfig =
        normalize(ServerConfig(baseUrl, allowInsecureHttp))

    fun normalize(config: ServerConfig): ServerConfig {
        val input = config.baseUrl.trim()
        val parsed = httpUri(input)
            ?: throw IllegalArgumentException(tr("请输入完整的 HTTP 或 HTTPS 服务器地址"))
        require(parsed.rawQuery == null && parsed.rawFragment == null) {
            tr("服务器地址不能包含查询参数或片段")
        }
        require(!hasUnsafePath(parsed)) { tr("服务器地址包含无效的路径") }
        require(parsed.scheme.equals("https", true) || config.allowInsecureHttp) {
            tr("请先确认允许 HTTP 明文连接")
        }
        val scheme = parsed.scheme.lowercase(Locale.ROOT)
        val host = parsed.host.lowercase(Locale.ROOT).let {
            if (it.contains(':') && !it.startsWith('[')) "[$it]" else it
        }
        val port = if (parsed.port == -1 || parsed.port == defaultPort(scheme)) "" else ":${parsed.port}"
        return config.copy(baseUrl = "$scheme://$host$port${parsed.rawPath.orEmpty().trimEnd('/')}")
    }

    fun endpoint(config: ServerConfig, path: String): String {
        val base = normalize(config).baseUrl
        require(path.isNotEmpty() && !path.startsWith("//") && !path.contains('\\')) {
            tr("无效的服务器接口路径")
        }
        val relative = runCatching { URI(path) }.getOrNull()
        require(relative != null && !relative.isAbsolute && relative.rawFragment == null && !hasUnsafePath(relative)) {
            tr("无效的服务器接口路径")
        }
        return "$base/${path.trimStart('/')}"
    }

    fun isWithinBase(config: ServerConfig, url: String): Boolean {
        val normalized = runCatching { normalize(config) }.getOrNull() ?: return false
        val base = httpUri(normalized.baseUrl) ?: return false
        val target = httpUri(url) ?: return false
        if (!sameOrigin(base, target) || hasUnsafePath(target)) return false
        val prefix = base.path.orEmpty().trimEnd('/')
        val path = target.path.orEmpty()
        return prefix.isEmpty() || path == prefix || path.startsWith("$prefix/")
    }

    /** HTTP URL 验证不会重建字符串，因此签名参数、重复 query 与编码保持原样。 */
    internal fun httpUri(value: String): URI? {
        if (value.isEmpty() || value.any { it <= ' ' || it == '\\' }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.port < -1 || uri.port > 65_535) return null
        return uri
    }

    internal fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, true) && first.host.equals(second.host, true) &&
            effectivePort(first) == effectivePort(second)

    internal fun sameOrigin(first: String, second: String): Boolean {
        val left = httpUri(first) ?: return false
        val right = httpUri(second) ?: return false
        return sameOrigin(left, right)
    }

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) defaultPort(uri.scheme) else uri.port

    private fun defaultPort(scheme: String): Int = if (scheme.equals("https", true)) 443 else 80

    private fun hasUnsafePath(uri: URI): Boolean {
        val decoded = uri.path.orEmpty()
        return decoded.contains('\\') || decoded.any { it < ' ' } ||
            decoded.split('/').any { it == "." || it == ".." }
    }
}

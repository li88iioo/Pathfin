package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class ExternalSubtitle(
    val index: Int,
    val uri: String,
    val mimeType: String,
    val language: String?,
    val label: String,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isHearingImpaired: Boolean,
)

/** 只接入 Jellyfin 明确返回的外挂文本字幕，不猜测文件路径或构造字幕接口。 */
object ExternalSubtitleResolver {
    fun resolve(request: PlaybackRequest): List<ExternalSubtitle> {
        val server = runCatching { ServerUrls.normalize(request.session.server) }.getOrNull() ?: return emptyList()
        return request.source.mediaStreams.asSequence().filter {
            it.type.equals("Subtitle", true) && it.isExternal && it.index != null
        }.mapNotNull { stream ->
            val index = stream.index?.takeIf { it >= 0 } ?: return@mapNotNull null
            val uri = resolveUrl(server, stream.deliveryUrl) ?: return@mapNotNull null
            if (PlaybackHeaders.containsExternalServerCredential(request.session, uri)) return@mapNotNull null
            val mime = mimeType(uri, stream.codec) ?: return@mapNotNull null
            ExternalSubtitle(
                index = index,
                uri = uri,
                mimeType = mime,
                language = stream.language?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) },
                label = (stream.displayTitle ?: stream.title)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }?.take(256) ?: tr("外挂字幕 %1\$s", index + 1),
                isDefault = stream.isDefault,
                isForced = stream.isForced,
                isHearingImpaired = stream.isHearingImpaired,
            )
        }.distinctBy { it.index }.distinctBy { it.uri }.take(64).toList()
    }

    private fun resolveUrl(server: ServerConfig, raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.length > 16_384 || raw.startsWith("//") ||
            raw.any { it <= ' ' || it == '\\' }) return null
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        if (parsed.rawFragment != null || parsed.rawUserInfo != null || unsafePath(parsed.rawPath.orEmpty())) return null
        val resolved = if (parsed.isAbsolute) {
            if (ServerUrls.httpUri(raw) == null) return null
            raw
        } else {
            if (parsed.rawAuthority != null || parsed.rawPath.isNullOrEmpty()) return null
            val base = URI(server.baseUrl)
            val prefix = base.rawPath.orEmpty().trimEnd('/')
            val path = parsed.rawPath
            if (prefix.isNotEmpty() && (path == prefix || path.startsWith("$prefix/"))) {
                "${base.scheme}://${base.rawAuthority}$raw"
            } else runCatching { ServerUrls.endpoint(server, raw) }.getOrNull() ?: return null
        }
        if (ServerUrls.httpUri(resolved) == null) return null
        if (!ServerUrls.isWithinBase(server, resolved)) return resolved
        // 同服务器认证只走受 origin/base 限制的 Header；不让 token 出现在字幕 URI/播放器元数据中。
        val query = URI(resolved).rawQuery?.split('&')?.filterNot { parameter ->
            var name = parameter.substringBefore('=')
            repeat(3) { name = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name) }
            name.lowercase(Locale.ROOT) in AUTH_QUERY_NAMES
        }?.joinToString("&")
        return resolved.substringBefore('?') + if (query.isNullOrEmpty()) "" else "?$query"
    }

    private fun unsafePath(rawPath: String): Boolean {
        var path = rawPath
        repeat(4) {
            if (path.any { it <= ' ' || it == '\\' } || path.split('/').any { it == "." || it == ".." }) return true
            path = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrElse { return true }
        }
        return false
    }

    private fun mimeType(uri: String, codec: String?): String? {
        val file = URI(uri).path.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
        val extension = file.substringAfterLast('.', "")
        // DeliveryUrl 可能是服务端转换后的 VTT，以实际交付格式为准。
        val format = extension.ifEmpty { codec?.lowercase(Locale.ROOT).orEmpty() }
        return when (format) {
            "srt", "subrip" -> "application/x-subrip"
            "vtt", "webvtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> null
        }
    }

    private val AUTH_QUERY_NAMES = setOf("api_key", "apikey", "token", "access_token", "x-emby-token", "x-mediabrowser-token")
}

/** 仅供已通过原始 Call URL 白名单的外挂字幕使用；不能套用主视频的认证作用域。 */
internal object SubtitleHeaders {
    fun forUrl(request: PlaybackRequest, initialUrl: String, currentUrl: String): Map<String, String> {
        if (PlaybackHeaders.containsExternalServerCredential(request.session, currentUrl)) return emptyMap()
        return if (ServerUrls.isWithinBase(request.session.server, initialUrl)) {
            if (ServerUrls.isWithinBase(request.session.server, currentUrl)) PlaybackHeaders.serverHeaders(request.session)
            else emptyMap()
        } else {
            if (!request.stream.trustedServer && ServerUrls.sameOrigin(request.stream.uri, initialUrl) &&
                ServerUrls.sameOrigin(initialUrl, currentUrl)) PlaybackHeaders.sourceHeaders(request.session, request.stream.headers)
            else emptyMap()
        }
    }

    fun allowsCleartext(request: PlaybackRequest, initialUrl: String): Boolean {
        val server = request.session.server
        if (runCatching { ServerUrls.normalize(server) }.isFailure) return false
        return if (ServerUrls.isWithinBase(server, initialUrl)) server.allowInsecureHttp
        else ServerUrls.httpUri(initialUrl)?.scheme.equals("http", true)
    }
}

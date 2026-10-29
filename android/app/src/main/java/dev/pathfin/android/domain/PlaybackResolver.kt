package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object PlaybackResolver {
    /** 明确选择的视频原画直连；与服务器本地文件和主动选择的码率档分开。 */
    fun prefersOriginalHttp(item: MediaItem, source: MediaSource, preferOriginal: Boolean,
        quality: PlaybackQuality, delivery: PlaybackDelivery): Boolean =
        preferOriginal && quality == PlaybackQuality.ORIGINAL && delivery == PlaybackDelivery.AUTOMATIC &&
            item.type in setOf("Movie", "Episode", "Video") &&
            (source.protocol.isNullOrBlank() || source.protocol.equals("Http", true)) &&
            source.path?.let { ServerUrls.httpUri(it) != null } == true

    fun isOriginalHttpPlayback(request: PlaybackRequest): Boolean =
        !request.stream.trustedServer && request.stream.method == PlayMethod.DIRECT_PLAY &&
            prefersOriginalHttp(request.item, request.source, true, request.quality, request.delivery)

    fun resolve(
        session: Session,
        item: MediaItem,
        info: PlaybackInfo,
        sourceId: String,
        preferOriginal: Boolean = true,
        quality: PlaybackQuality = PlaybackQuality.ORIGINAL,
        delivery: PlaybackDelivery = PlaybackDelivery.AUTOMATIC,
    ): ResolvedPlayback {
        require(info.errorCode.isNullOrBlank()) { tr("服务器无法为该媒体提供播放信息") }
        val source = when {
            sourceId.isNotBlank() -> info.sources.firstOrNull { it.id == sourceId }
            info.sources.size == 1 -> info.sources.single()
            else -> null
        } ?: throw IllegalArgumentException(tr("所选媒体版本不存在，请重新选择"))

        val effectiveDelivery = maxOf(delivery, info.delivery)
        if (quality.maxBitrate != null || effectiveDelivery == PlaybackDelivery.TRANSCODE) {
            val url = serverMediaUrl(session.server, source.transcodingUrl)
                ?: throw IllegalArgumentException(tr("服务器或当前账号无法提供此播放质量，请使用原画"))
            return serverResult(session, source, url, PlayMethod.TRANSCODE)
        }

        val original = source.path?.takeIf { ServerUrls.httpUri(it) != null }
        val compatibleProtocol = source.protocol.isNullOrBlank() || source.protocol.equals("Http", true)
        val direct = serverMediaUrl(session.server, source.directStreamUrl)
        val transcode = serverMediaUrl(session.server, source.transcodingUrl)
        // 用户已明确选择原始HTTP视频时，本机实际能力决定是否可播，不能被服务器替代流覆盖。
        // 其他媒体保留服务器协商；直播仍遵守独立的“直连直播源”选择。
        val originalAllowed = prefersOriginalHttp(item, source, preferOriginal, quality, effectiveDelivery) ||
            source.supportsDirectPlay || (direct == null && transcode == null) || item.type == "TvChannel"
        if (effectiveDelivery == PlaybackDelivery.AUTOMATIC && preferOriginal && originalAllowed && original != null && compatibleProtocol) {
            // allowInsecureHttp 只确认 Jellyfin 自身的明文连接；外部初始 HTTP 是服务端明确提供的片源。
            ServerUrls.normalize(session.server)
            require(!PlaybackHeaders.containsExternalServerCredential(session, original)) { tr("片源地址包含服务器凭据，已阻止发送至外部站点") }
            return ResolvedPlayback(
                uri = original,
                headers = PlaybackHeaders.sourceHeaders(session, source.requiredHttpHeaders),
                method = PlayMethod.DIRECT_PLAY,
                sourceId = source.id,
                trustedServer = false,
                mimeType = mimeType(source.container, original),
            )
        }

        if (direct != null) return serverResult(session, source, direct, PlayMethod.DIRECT_STREAM)
        if (transcode != null) return serverResult(session, source, transcode, PlayMethod.TRANSCODE)
        if (effectiveDelivery == PlaybackDelivery.AUTOMATIC && (source.supportsDirectPlay || source.supportsDirectStream)) {
            require(item.id.isNotBlank()) { tr("媒体信息缺少标识") }
            val endpoint = if (item.type == "Audio") "Audio" else "Videos"
            val url = ServerUrls.endpoint(session.server, "$endpoint/${encode(item.id)}/stream") +
                "?Static=true&MediaSourceId=${encode(source.id)}&DeviceId=${encode(session.deviceId)}"
            return serverResult(session, source, url, if (source.supportsDirectPlay) PlayMethod.DIRECT_PLAY else PlayMethod.DIRECT_STREAM)
        }
        throw IllegalArgumentException(tr("该媒体源没有可播放的 HTTP 地址"))
    }

    private fun serverResult(session: Session, source: MediaSource, url: String, method: PlayMethod) =
        ResolvedPlayback(
            uri = url,
            headers = PlaybackHeaders.serverHeaders(session),
            method = method,
            sourceId = source.id,
            trustedServer = true,
            mimeType = mimeType(if (method == PlayMethod.TRANSCODE) null else source.container, url),
        )

    private fun serverMediaUrl(config: ServerConfig, raw: String?): String? {
        if (raw.isNullOrBlank() || raw.startsWith("//") || raw.contains('\\')) return null
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        if (parsed.rawFragment != null || parsed.rawUserInfo != null) return null
        val normalized = runCatching { ServerUrls.normalize(config) }.getOrNull() ?: return null
        val result = if (parsed.isAbsolute) raw else {
            val base = URI(normalized.baseUrl)
            val prefix = base.rawPath.orEmpty().trimEnd('/')
            val rawPath = parsed.rawPath.orEmpty()
            if (rawPath.isBlank()) return null
            if (prefix.isNotEmpty() && (rawPath == prefix || rawPath.startsWith("$prefix/"))) {
                "${base.scheme}://${base.rawAuthority}$raw"
            } else "${normalized.baseUrl}/${raw.trimStart('/')}"
        }
        if (!ServerUrls.isWithinBase(normalized, result)) return null
        // 服务端提供的流地址可能携带 api_key；转移到受 origin 约束的 Header。
        val uri = URI(result)
        val keptQuery = uri.rawQuery?.split('&')?.filterNot {
            val name = it.substringBefore('=').lowercase(Locale.ROOT)
            name in setOf("api_key", "token", "access_token", "x-emby-token")
        }?.joinToString("&")
        return result.substringBefore('?') + if (!keptQuery.isNullOrEmpty()) "?$keptQuery" else ""
    }

    private fun mimeType(container: String?, uri: String): String? {
        val path = runCatching { URI(uri).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") || container.equals("m3u8", true) -> "application/x-mpegURL"
            path.endsWith(".mpd") || container.equals("mpd", true) || container.equals("dash", true) -> "application/dash+xml"
            else -> null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** 每个媒体请求（包括分片和重定向）均重新计算 Header，不能复用首次请求的凭据。 */
object PlaybackHeaders {
    fun forUrl(request: PlaybackRequest, url: String): Map<String, String> {
        val parsed = ServerUrls.httpUri(url) ?: return emptyMap()
        if (containsExternalServerCredential(request.session, url)) return emptyMap()
        if (parsed.scheme.equals("http", true) && !allowsCleartext(request)) return emptyMap()
        return if (request.stream.trustedServer) {
            if (ServerUrls.isWithinBase(request.session.server, url)) serverHeaders(request.session) else emptyMap()
        } else {
            if (ServerUrls.sameOrigin(request.stream.uri, url))
                sourceHeaders(request.session, request.stream.headers) else emptyMap()
        }
    }

    fun serverHeaders(session: Session): Map<String, String> =
        JellyfinAuthorization.headers(session.deviceId, session.accessToken)

    /** 只允许已确认的服务器 HTTP，或明确以外部 HTTP 起播的源；重定向降级另由拦截器拒绝。 */
    internal fun allowsCleartext(request: PlaybackRequest): Boolean {
        val server = request.session.server
        if (ServerUrls.httpUri(server.baseUrl)?.scheme.equals("http", true) && !server.allowInsecureHttp) return false
        return server.allowInsecureHttp || (!request.stream.trustedServer &&
            ServerUrls.httpUri(request.stream.uri)?.scheme.equals("http", true) &&
            !ServerUrls.isWithinBase(server, request.stream.uri))
    }

    internal fun containsExternalServerCredential(session: Session, url: String): Boolean {
        if (session.accessToken.isEmpty() || ServerUrls.isWithinBase(session.server, url)) return false
        var decoded = url
        repeat(3) {
            if (decoded.contains(session.accessToken)) return true
            decoded = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
        }
        return false
    }

    internal fun sourceHeaders(session: Session, headers: Map<String, String>): Map<String, String> {
        val prohibited = setOf(
            "host", "connection", "content-length", "transfer-encoding", "proxy-authorization",
            "proxy-authenticate", "upgrade", "te", "trailer", "keep-alive", "range", "accept-encoding",
            "x-emby-token", "x-emby-authorization", "x-mediabrowser-token",
        )
        val namePattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        return headers.filter { (name, value) ->
            namePattern.matches(name) && name.lowercase(Locale.ROOT) !in prohibited &&
                value.all { it == '\t' || it.code in 0x20..0x7e } &&
                !value.contains("MediaBrowser", true) &&
                (session.accessToken.isEmpty() || !value.contains(session.accessToken))
        }
    }
}

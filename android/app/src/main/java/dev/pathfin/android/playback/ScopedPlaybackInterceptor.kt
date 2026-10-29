package dev.pathfin.android.playback

import dev.pathfin.android.domain.PlaybackHeaders
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.SubtitleHeaders
import dev.pathfin.android.i18n.tr
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** 网络拦截器逐跳执行，包括 302 与 HLS/DASH 分段请求；鉴权范围由 URL 决定。 */
internal class ScopedPlaybackInterceptor(
    private val playback: PlaybackRequest,
    resolvedSubtitleUris: Set<String> = emptySet(),
) : Interceptor {
    private val subtitleUris = resolvedSubtitleUris.mapNotNull { it.toHttpUrlOrNull()?.toString() }.toSet()
    private val managedHeaders = playback.stream.headers.keys +
        playback.source.requiredHttpHeaders.keys +
        setOf("Authorization", "X-Emby-Authorization", "X-Emby-Token", "X-MediaBrowser-Token", "Cookie", "Cookie2")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val initialUrl = chain.call().request().url
        // 字幕接口形似视频分片，必须匹配我们实际解析的初始 URL，不能按路径猜测而授予认证。
        val subtitleCall = initialUrl.toString() in subtitleUris
        val cleartextAllowed = if (subtitleCall) SubtitleHeaders.allowsCleartext(playback, initialUrl.toString())
            else PlaybackHeaders.allowsCleartext(playback)
        if (request.url.scheme == "http" &&
            (initialUrl.isHttps || !cleartextAllowed)) {
            throw IOException(tr("已阻止未经允许的 HTTP 连接或 HTTPS 降级跳转"))
        }
        if (PlaybackHeaders.containsExternalServerCredential(playback.session, request.url.toString()))
            throw IOException(tr("片源地址包含服务器凭据，已阻止发送至外部站点"))
        val builder = request.newBuilder()
        // 重定向会复制部分原请求头；先清除旧作用域的值，再按当前目标重新注入。
        managedHeaders.forEach(builder::removeHeader)
        val scopedHeaders = if (subtitleCall) SubtitleHeaders.forUrl(playback, initialUrl.toString(), request.url.toString())
            else PlaybackHeaders.forUrl(playback, request.url.toString())
        scopedHeaders.forEach { (name, value) ->
            builder.header(name, value)
        }
        val response = chain.proceed(builder.build())
        // 检查每一跳的响应，HTTP→HTTPS→HTTP 也不能绕过。此时尚未发出下一跳请求。
        if (response.code in REDIRECT_CODES && response.request.url.isHttps &&
            response.header("Location")?.let(response.request.url::resolve)?.scheme == "http") {
            response.close()
            throw IOException(tr("已阻止片源从 HTTPS 降级到 HTTP"))
        }
        return response
    }

    private companion object { val REDIRECT_CODES = setOf(301, 302, 303, 307, 308) }
}

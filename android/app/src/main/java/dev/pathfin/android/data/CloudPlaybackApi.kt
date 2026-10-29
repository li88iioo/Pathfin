package dev.pathfin.android.data

import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** 独立无账号客户端：只能从当前签名 STRM 发现画质，不复用 Jellyfin 认证会话。 */
class CloudPlaybackApi(
    client: OkHttpClient = OkHttpClient(),
    private val videoSupport: (MediaStream) -> Boolean? = { null },
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun discover(session: Session, source: MediaSource): CloudPlayback? {
        if (!source.protocol.isNullOrBlank() && !source.protocol.equals("Http", true)) return null
        val url = source.path?.takeIf { isCandidate(it) } ?: return null
        require(!PlaybackHeaders.containsExternalServerCredential(session, url)) { tr("片源地址包含服务器凭据") }
        val json = readJson(url, "variants", discovery = true) ?: return null
        val values = json.optJSONArray("variants") ?: error(tr("云盘画质列表不完整"))
        val variants = (0 until values.length()).map { index -> parseVariant(requireNotNull(values.optJSONObject(index)) { tr("云盘画质条目不完整") }, source) }
        require(variants.isNotEmpty() && variants.count { it.original && it.id == "original" } == 1 &&
            variants.map { it.id }.distinct().size == variants.size) { tr("云盘画质列表无效") }
        return CloudPlayback(url, variants)
    }

    suspend fun select(request: PlaybackRequest, quality: PlaybackQuality): PlaybackRequest {
        val cloud = requireNotNull(request.cloudPlayback) { tr("当前媒体未提供云盘画质") }
        val variant = cloud.variant(quality)?.takeIf { it.playable }
            ?: throw IllegalArgumentException(tr("该媒体没有可用的%1\$s云盘档位，请选择其他画质", quality.label))
        val json = readJson(cloud.sourceUrl, "resolve", variant.id) ?: error(tr("云盘画质解析不可用"))
        val selected = parseVariant(requireNotNull(json.optJSONObject("variant")) { tr("云盘画质条目不完整") }, request.source)
        require(selected.id == variant.id && selected.original == variant.original && selected.playable) { tr("云盘返回的画质与选择不一致") }
        val uri = json.optString("url")
        require(ServerUrls.httpUri(uri) != null && !PlaybackHeaders.containsExternalServerCredential(request.session, uri)) {
            tr("云盘返回的播放地址无效")
        }
        return request.copy(
            info = request.info.copy(delivery = PlaybackDelivery.AUTOMATIC, audioStreamIndex = null, subtitleStreamIndex = null),
            stream = ResolvedPlayback(uri, emptyMap(), PlayMethod.DIRECT_PLAY, request.source.id, false, selected.mimeType),
            quality = quality,
            cloudPlayback = cloud.copy(selectedId = selected.id, variants = cloud.variants.map { if (it.id == selected.id) selected else it }),
        )
    }

    private fun parseVariant(json: JSONObject, source: MediaSource): CloudVariant {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: error(tr("云盘画质缺少资源标识"))
        val original = json.optBoolean("original")
        val transport = json.optString("transport")
        require(transport in setOf("file", "hls") && (id == "original") == original) { tr("云盘画质格式无效") }
        val mime = if (transport == "hls") "application/x-mpegURL" else json.optString("mime_type", "application/octet-stream")
        val width = json.optInt("width").takeIf { it > 0 }
        val height = json.optInt("height").takeIf { it > 0 }
        fun codec(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        val video = codec("video_codec")
        // 原画与该 MediaSource 是同一文件；云列表的简略 HEVC 标签不能覆盖 DV/位深等已知限制。
        // 压缩云档是独立资源，不能继承原画的 DV 属性。
        val originalVideo = if (original) source.mediaStreams.firstOrNull { it.type.equals("Video", true) } else null
        val format = originalVideo?.copy(codec = originalVideo.codec ?: video,
            width = originalVideo.width ?: width, height = originalVideo.height ?: height)
            ?: MediaStream(type = "Video", codec = video, width = width, height = height)
        return CloudVariant(id, original, json.optString("label"), width, height, video, codec("audio_codec"),
            json.optLong("bitrate").takeIf { it > 0 }, transport, mime,
            playable = videoSupport(format) != false)
    }

    private suspend fun readJson(sourceUrl: String, view: String, quality: String? = null, discovery: Boolean = false): JSONObject? {
        val url = requireNotNull(sourceUrl.toHttpUrlOrNull()).newBuilder().setQueryParameter("view", view)
            .removeAllQueryParameters("quality").apply { quality?.let { addQueryParameter("quality", it) } }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException(tr("无法读取云盘画质，请检查网络后重试")))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            // 旧 MediaFlux 忽略 view 会直接302到整部视频；不能跟随或读取视频当JSON。
                            val unsupported = discovery && (response.code in setOf(301, 302, 303, 307, 308, 404, 405) ||
                                response.isSuccessful && response.body.contentType()?.subtype?.contains("json") != true)
                            val data = if (unsupported) null else {
                                check(response.isSuccessful) { when (response.code) {
                                    401, 403 -> tr("STRM 播放签名无效，请检查 MediaFlux 地址")
                                    404 -> tr("所选云盘画质已不可用，请重新打开媒体")
                                    503 -> tr("MediaFlux 云盘未登录")
                                    else -> tr("云盘画质请求失败（HTTP %1\$s），请重试", response.code)
                                } }
                                val source = response.body.source()
                                check(!source.request(256 * 1024L + 1)) { tr("云盘画质响应过大") }
                                val json = try { JSONObject(source.readUtf8()) }
                                catch (_: org.json.JSONException) {
                                    if (discovery) null else throw IllegalStateException(tr("云盘画质响应无法识别"))
                                }
                                when {
                                    json?.optString("protocol") == "mediaflux.playback.v1" -> json
                                    discovery -> null
                                    else -> error(tr("云盘画质协议不匹配"))
                                }
                            }
                            if (continuation.isActive) continuation.resume(data)
                        } catch (error: Exception) {
                            val safe = if (error is IllegalStateException) error.message else tr("云盘画质响应无法识别")
                            if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException(safe))
                        }
                    }
                }
            })
        }
    }

    companion object {
        fun isCandidate(raw: String): Boolean {
            if (ServerUrls.httpUri(raw) == null) return false
            val url = raw.toHttpUrlOrNull() ?: return false
            val segments = url.pathSegments
            val index = segments.indexOf("playgy")
            return index >= 0 && segments.size >= index + 5 && url.queryParameter("v") == "1" &&
                url.queryParameter("sig")?.matches(Regex("[a-fA-F0-9]{64}")) == true
        }
    }
}

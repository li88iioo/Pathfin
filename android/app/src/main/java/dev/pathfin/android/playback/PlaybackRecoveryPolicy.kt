package dev.pathfin.android.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackDelivery
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.PlaybackResolver
import javax.net.ssl.SSLException

internal enum class PlaybackFailure { FORMAT, TRANSIENT, TERMINAL }

/** 同一播放入口共享预算。网络仅重新协商一次，格式问题单向升级，不形成循环。 */
internal class PlaybackRecoveryPolicy {
    private var refreshed = false

    fun next(request: PlaybackRequest, failure: PlaybackFailure): PlaybackDelivery? {
        if (request.item.type !in setOf("Movie", "Episode", "Video", "MusicVideo", "Audio")) return null
        return when (failure) {
            PlaybackFailure.TERMINAL -> null
            PlaybackFailure.TRANSIENT -> if (refreshed) null else request.delivery.also { refreshed = true }
            PlaybackFailure.FORMAT -> when {
                request.cloudPlayback != null || PlaybackResolver.isOriginalHttpPlayback(request) -> null
                request.quality.maxBitrate != null || request.delivery == PlaybackDelivery.TRANSCODE -> null
                // TranscodingUrl也可能只copy视频/重封装；只有禁止copy的协商才已强制转码。
                request.delivery == PlaybackDelivery.SERVER_STREAM || request.stream.method != PlayMethod.DIRECT_PLAY -> PlaybackDelivery.TRANSCODE
                else -> PlaybackDelivery.SERVER_STREAM
            }
        }
    }
}

/** 只把明确的媒体错误升级；凭据、TLS、DRM等问题不能靠转码绕过。 */
@UnstableApi
internal fun playbackFailure(error: PlaybackException, cloudUrl: Boolean = false): PlaybackFailure {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is SSLException) return PlaybackFailure.TERMINAL
        if (cause is HttpDataSource.InvalidResponseCodeException) return when (cause.responseCode) {
            408, 429, 500, 502, 503, 504 -> PlaybackFailure.TRANSIENT
            // 过期404/410可能来自一次性源地址；刷新不会改变鉴权作用域或强制转码。
            404, 410 -> PlaybackFailure.TRANSIENT
            401, 403 -> if (cloudUrl) PlaybackFailure.TRANSIENT else PlaybackFailure.TERMINAL
            else -> PlaybackFailure.TERMINAL
        }
        cause = cause.cause
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackFailure.FORMAT
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackFailure.TRANSIENT
        else -> PlaybackFailure.TERMINAL
    }
}

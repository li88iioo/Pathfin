package dev.pathfin.android.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import dev.pathfin.ffmpeg.video.NativeVideoRenderer

/** 保留系统硬解优先；视频补解的顺序与音频扩展偏好相互独立。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PathfinRenderersFactory(context: Context, private val hardwareVideo: Boolean) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
        val firstVideo = out.size
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        val local = NativeVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener)
        if (hardwareVideo) out.add(local) else out.add(firstVideo, local)
    }
}

package dev.pathfin.android.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.concurrent.ConcurrentHashMap

enum class VideoDecoderKind { HARDWARE, SYSTEM_SOFTWARE, FFMPEG, UNKNOWN }

/** 仅排序系统已经声明可用的解码器；不丢弃secure/tunneling条件或虚构FFmpeg能力。 */
@UnstableApi
internal class PreferredMediaCodecSelector(
    private val audioPreference: String,
    private val preferHardwareVideo: Boolean,
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {
    // 选择器在播放线程记录系统信息，初始化事件在主线程按实际解码器名称查询。
    private val videoDecoders = ConcurrentHashMap<String, MediaCodecInfo>()

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        val available = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (MimeTypes.isVideo(mimeType)) available.forEach { videoDecoders.putIfAbsent(it.name, it) }
        return orderPreferredCodecs(available, mimeType, audioPreference, preferHardwareVideo,
            hardware = { it.hardwareAccelerated }, software = { it.softwareOnly })
    }

    fun videoDecoderKind(name: String): VideoDecoderKind {
        if (name.startsWith("pathfin.ffmpeg.") && name.endsWith(".gpu-sdr")) return VideoDecoderKind.FFMPEG
        val codec = videoDecoders[name] ?: return VideoDecoderKind.UNKNOWN
        return when {
            codec.hardwareAccelerated == codec.softwareOnly -> VideoDecoderKind.UNKNOWN
            codec.hardwareAccelerated -> VideoDecoderKind.HARDWARE
            else -> VideoDecoderKind.SYSTEM_SOFTWARE
        }
    }
}

/** 稳定排序保留系统的同类优先级及全部后备项，首选不支持时仍能继续尝试。 */
internal fun <T> orderPreferredCodecs(codecs: List<T>, mime: String, audio: String, hardwareVideo: Boolean,
    hardware: (T) -> Boolean, software: (T) -> Boolean): List<T> = when {
    mime.startsWith("audio/") && audio == "HARDWARE" -> codecs.sortedByDescending(hardware)
    mime.startsWith("audio/") && audio == "SOFTWARE" -> codecs.sortedByDescending(software)
    mime.startsWith("video/") -> codecs.sortedByDescending(if (hardwareVideo) hardware else software)
    else -> codecs
}

package dev.pathfin.android.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** 仅排序系统已经声明可用的解码器；不丢弃secure/tunneling条件或虚构FFmpeg能力。 */
@UnstableApi
internal class PreferredMediaCodecSelector(
    private val audioPreference: String,
    private val preferHardwareVideo: Boolean,
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        val available = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return orderPreferredCodecs(available, mimeType, audioPreference, preferHardwareVideo,
            hardware = { it.hardwareAccelerated }, software = { it.softwareOnly })
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

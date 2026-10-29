package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import dev.pathfin.android.i18n.tr

/**
 * UHD 原盘的 Dolby Vision Profile 7 基础层就是 HDR10 HEVC，Media3 却不为它回退到 HEVC 解码器。
 * 设备没有 DV 解码器时改按 HEVC 解基础层（增强层 NAL 由解码器忽略）；Profile 5 无兼容基础层，保持原样。
 */
@UnstableApi
internal fun dolbyVisionBaseLayerFormat(format: Format, hasDolbyVisionDecoder: Boolean = DevicePlaybackCapabilities.current.supportsDolbyVisionProfile(7)): Format {
    if (hasDolbyVisionDecoder || format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
    val parts = format.codecs?.split('.').orEmpty()
    if (parts.getOrNull(0) !in setOf("dvhe", "dvh1") || parts.getOrNull(1) != "07") return format
    // DV 与 HEVC 的 profile/level 不同，使用基础层 SPS 的真实参数保留解码器能力校验。
    val codecs = runCatching { NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) }.getOrNull()
    return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(codecs).build()
}

/**
 * 有视频轨却没有一条可解码时提示，不能继续表现成只有声音的正常播放。
 * “超出声明能力”（解码器标称档次/分辨率不足）Media3 仍会尝试解码且多数能播，直播 HLS 常见，不算无法解码；
 * 已被选中正在渲染的视频轨同样不提示。
 */
@UnstableApi
internal fun unsupportedVideoMessage(tracks: Tracks): String? {
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    val playable = video.any { group ->
        group.isSelected || (0 until group.length).any { index ->
            group.getTrackSupport(index).let { it == C.FORMAT_HANDLED || it == C.FORMAT_EXCEEDS_CAPABILITIES }
        }
    }
    if (video.isEmpty() || playable) return null
    val format = video.first().getTrackFormat(0)
    val codec = when (format.sampleMimeType) {
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VC1 -> "VC-1"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase() ?: tr("未知编码")
    }
    return tr("当前视频轨（%1\$s）本机无法解码，仅能播放声音。", codec)
}

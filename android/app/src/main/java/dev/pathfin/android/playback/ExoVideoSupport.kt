package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.i18n.tr

/** 转码地址也可能只复制视频。仍抽出 P7 时保留服务器探测到的基础层兼容限制。 */
internal fun PlaybackRequest.allowsDolbyVisionProfile7BaseLayer(): Boolean =
    sourceProfile7Tracks().none { it.isDolbyVisionEnhancementOnly || it.dvBlSignalCompatibilityId == 0 }

internal fun PlaybackRequest.hasOnlySeparateProfile7EnhancementTracks(): Boolean =
    sourceProfile7Tracks().let { tracks -> tracks.isNotEmpty() && tracks.all { it.isDolbyVisionEnhancementOnly } }

private fun PlaybackRequest.sourceProfile7Tracks(): List<MediaStream> =
    if (cloudPlayback?.selectedId?.let { it != "original" } == true) emptyList()
    else source.mediaStreams.filter { it.type.equals("Video", true) && it.dvProfile == 7 }

@UnstableApi
internal fun Format.isDolbyVisionProfile7(): Boolean = sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
    (codecs?.startsWith("dvhe.07.") == true || codecs?.startsWith("dvh1.07.") == true)

/**
 * Media3 1.11.1 未为 P7 回退 HEVC。只有设备无法处理当前 DV 轨时才输出兼容的基础层。
 */
@UnstableApi
internal fun dolbyVisionBaseLayerFormat(format: Format, supportsDolbyVision: (Format) -> Boolean): Format {
    if (!format.isDolbyVisionProfile7() || format.drmInitData != null ||
        format.cryptoType != C.CRYPTO_TYPE_NONE) return format
    val color = format.colorInfo
    if (hasIncompatibleDolbyVisionBaseColor(color?.colorRange, color?.colorTransfer) || supportsDolbyVision(format)) return format
    // DV 与 HEVC 的 profile/level 不同，使用基础层 SPS 的真实参数保留解码器能力校验。
    val codecs = runCatching { NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) }.getOrNull()
    return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(codecs).build()
}

/** P7/P10 的 full-range PQ 或未标记传递函数的全范围基础层可能是 IPTPQc2。 */
@UnstableApi
internal fun hasIncompatibleDolbyVisionBaseColor(range: Int?, transfer: Int?): Boolean =
    range == C.COLOR_RANGE_FULL && (transfer == null || transfer == Format.NO_VALUE || transfer == C.COLOR_TRANSFER_ST2084)

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

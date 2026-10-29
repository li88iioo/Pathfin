package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import dev.pathfin.android.i18n.tr

/** 系统优先时仍保留 FFmpeg 后备；软件优先才把 FFmpeg 放在 MediaCodec 前面。 */
@UnstableApi
internal fun ffmpegAudioExtensionMode(audioPreference: String): Int =
    if (audioPreference == "SOFTWARE") DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON

/** 不支持的音轨可能只被选择器忽略而没有 onPlayerError，不能继续表现成正常的无声播放。 */
@UnstableApi
internal fun unsupportedAudioMessage(tracks: Tracks): String? {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.isEmpty() || audio.any { group -> (0 until group.length).any(group::isTrackSupported) }) return null
    val codecs = audio.flatMap { group -> (0 until group.length).map { index ->
        when (group.getTrackFormat(index).sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            else -> null
        }
    } }.filterNotNull().distinct()
    val label = codecs.takeIf { it.isNotEmpty() }?.joinToString(" / ", tr("（"), tr("）")).orEmpty()
    return tr("当前音轨%1\$s无法解码，可选择其他音轨或使用服务器转码。", label)
}

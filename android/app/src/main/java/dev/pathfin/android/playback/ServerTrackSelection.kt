package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import java.util.Locale

/** 容器 TrackNumber 和转码后的 Format.id 都不是 Jellyfin 的原始轨道索引。 */
@UnstableApi
internal fun serverTrackIndex(source: MediaSource, type: Int, format: Format?): Int? {
    if (format == null || type !in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) return null
    val id = format.id.orEmpty()
    if (id.startsWith("local-") || id.startsWith("pathfin-local-")) return null
    val serverType = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"
    val candidates = source.mediaStreams.filter { it.type.equals(serverType, true) && it.index != null && it.index >= 0 }
    if (id.startsWith("external-subtitle-")) {
        if (type != C.TRACK_TYPE_TEXT) return null
        val index = id.removePrefix("external-subtitle-").toIntOrNull() ?: return null
        return candidates.singleOrNull { it.isExternal && it.index == index }?.index
    }

    val selected = StableTrackIdentity.create(format.language, format.label, 0, false) ?: return null
    val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
    val mime = normalizedMime(if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType)
    return candidates.filter { stream ->
        // 外挂字幕的 app 自有 id 必须精确核对，不能与容器内同语言的字幕混选。
        if (type == C.TRACK_TYPE_TEXT && stream.isExternal) return@filter false
        val language = StableTrackIdentity.create(stream.language, null, 0, false)?.language
        val labels = listOfNotNull(stream.title, stream.displayTitle).mapNotNull {
            StableTrackIdentity.create(null, it, 0, false)?.label
        }
        if (selected.language != null && selected.language != language) return@filter false
        if (selected.label != null && selected.label !in labels) return@filter false
        val serverMime = codecMime(stream.codec)
        if (mime != null && serverMime != null && mime != serverMime) return@filter false
        if (type == C.TRACK_TYPE_AUDIO && !matchesAudioProperties(stream, format)) return@filter false
        if (type == C.TRACK_TYPE_TEXT && stream.isForced != forced) return@filter false
        if (format.roleFlags and C.ROLE_FLAG_CAPTION != 0 && !stream.isHearingImpaired) return@filter false
        true
    }.singleOrNull()?.index
}

@UnstableApi
private fun matchesAudioProperties(stream: MediaStream, format: Format): Boolean =
    (format.channelCount <= 0 || stream.channels == null || stream.channels <= 0 || stream.channels == format.channelCount) &&
        (format.sampleRate <= 0 || stream.sampleRate == null || stream.sampleRate <= 0 || stream.sampleRate == format.sampleRate)

@UnstableApi
private fun normalizedMime(mime: String?): String? = when (val normalized = mime?.lowercase(Locale.ROOT)) {
    MimeTypes.AUDIO_E_AC3_JOC -> MimeTypes.AUDIO_E_AC3
    MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> MimeTypes.AUDIO_DTS
    else -> normalized
}

@UnstableApi
private fun codecMime(codec: String?): String? = when (codec?.lowercase(Locale.ROOT)) {
    "aac" -> MimeTypes.AUDIO_AAC
    "mp3" -> MimeTypes.AUDIO_MPEG
    "mp2" -> MimeTypes.AUDIO_MPEG_L2
    "ac3" -> MimeTypes.AUDIO_AC3
    "eac3" -> MimeTypes.AUDIO_E_AC3
    "truehd" -> MimeTypes.AUDIO_TRUEHD
    "dts", "dca" -> MimeTypes.AUDIO_DTS
    "opus" -> MimeTypes.AUDIO_OPUS
    "vorbis" -> MimeTypes.AUDIO_VORBIS
    "flac" -> MimeTypes.AUDIO_FLAC
    "alac" -> MimeTypes.AUDIO_ALAC
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "mov_text" -> MimeTypes.APPLICATION_TX3G
    "hdmv_pgs_subtitle", "pgssub" -> MimeTypes.APPLICATION_PGS
    "dvd_subtitle", "dvdsub" -> MimeTypes.APPLICATION_VOBSUB
    else -> null
}

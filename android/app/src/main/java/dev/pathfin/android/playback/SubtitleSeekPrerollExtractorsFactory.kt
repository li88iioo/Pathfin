package dev.pathfin.android.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput

/**
 * 内嵌 SRT、SSA/ASS 和 WebVTT 在非零位置初始化/重建时，也要读到此前仍可能活跃的字幕包。
 *
 * 仅使用 Matroska 已有的文本索引，不扫描或猜测包位置。回读有时间、字节双重上限；
 * 保留原视频 seek 时间，解码器仍丢弃目标之前的视频帧，暂停时不会偷偷推进播放。
 * 同一层也把设备无法直解的 Dolby Vision Profile 7 视频轨改按 HEVC 基础层输出。
 */
@UnstableApi
@androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
internal class SubtitleSeekPrerollExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: MutableMap<String, MutableList<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: androidx.media3.extractor.text.SubtitleParser.Factory): ExtractorsFactory = apply {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use setSubtitleParserFactory instead")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
        delegate.setParseHagcMetadata(parseHagcMetadata)
    }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map { extractor ->
        // ASS 的 Matroska 提取器已有原始包/字体包装，不能用具体类判断丢掉其索引。
        // 实际 seekMap 仍须实现 TrackAwareSeekMap；其他容器仅透传且不会回读。
        if (extractor is SubtitleSeekPrerollExtractor) extractor else SubtitleSeekPrerollExtractor(extractor)
    }.toTypedArray()
}

@UnstableApi
private class SubtitleSeekPrerollExtractor(private val delegate: Extractor) : Extractor by delegate {
    // format 在提取线程更新，SeekMap 在播放线程读取；一次发布不可变快照，避免跨线程迭代。
    @Volatile private var subtitleTrackIds: Set<Int> = emptySet()

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type == C.TRACK_TYPE_VIDEO) return object : TrackOutput by track {
                    override fun format(format: Format) = track.format(dolbyVisionBaseLayerFormat(format))
                }
                if (type != C.TRACK_TYPE_TEXT) return track
                return object : TrackOutput by track {
                    override fun format(format: Format) {
                        subtitleTrackIds = if (supportsSubtitleSeekPreroll(format) &&
                            (id in subtitleTrackIds || subtitleTrackIds.size < MAX_SUBTITLE_TRACKS)) subtitleTrackIds + id
                            else subtitleTrackIds - id
                        track.format(format)
                    }
                }
            }

            override fun seekMap(seekMap: SeekMap) {
                if (seekMap !is TrackAwareSeekMap) { output.seekMap(seekMap); return }
                output.seekMap(object : SeekMap {
                    override fun isSeekable(): Boolean = seekMap.isSeekable
                    override fun getDurationUs(): Long = seekMap.durationUs
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                        subtitlePrerollSeekPoints(seekMap, timeUs, subtitleTrackIds)
                })
            }
        })
    }

    private companion object { const val MAX_SUBTITLE_TRACKS = 32 }
}

/** Media3 将提取时解析的文本改为 CUES，并在 codecs 中保留实际字幕 MIME。 */
@UnstableApi
internal fun supportsSubtitleSeekPreroll(format: Format): Boolean {
    val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
    return mime == MimeTypes.APPLICATION_SUBRIP || mime == MimeTypes.TEXT_SSA || mime == MimeTypes.TEXT_VTT
}

/** 不改变 SeekPoint.timeUs（包含关键帧模式），仅将物理读取位置移到有界的既有字幕索引。 */
@UnstableApi
internal fun subtitlePrerollSeekPoints(seekMap: TrackAwareSeekMap, timeUs: Long, subtitleTrackIds: Set<Int>): SeekMap.SeekPoints {
    val original = seekMap.getSeekPoints(timeUs)
    if (timeUs <= 0 || !seekMap.isSeekable || subtitleTrackIds.isEmpty()) return original

    fun withPreroll(point: SeekPoint): SeekPoint {
        if (point.timeUs < 0 || point.position <= 0) return point
        var position = point.position
        for (id in subtitleTrackIds) {
            // 不接受 TrackAwareSeekMap 对缺少字幕索引时回退到视频索引的猜测。
            if (!seekMap.isSeekable(id)) continue
            val subtitle = seekMap.getSeekPoints(timeUs, id).first
            if (subtitle.timeUs < 0 || subtitle.timeUs > timeUs ||
                timeUs - subtitle.timeUs > MAX_SUBTITLE_PREROLL_US || subtitle.position < 0 ||
                subtitle.position >= position || point.position - subtitle.position > MAX_SUBTITLE_PREROLL_BYTES) continue
            position = subtitle.position
        }
        return if (position == point.position) point else SeekPoint(point.timeUs, position)
    }
    return SeekMap.SeekPoints(withPreroll(original.first), withPreroll(original.second))
}

internal const val MAX_SUBTITLE_PREROLL_US = 30_000_000L
internal const val MAX_SUBTITLE_PREROLL_BYTES = 2L * 1024 * 1024

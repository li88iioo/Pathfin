package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream

/** 正数延迟字幕，负数提前；仅属于当前播放会话。 */
data class SubtitlePresentation(val scale: Float = 1f, val positionPercent: Int = 100, val delayMs: Long = 0) {
    fun normalized() = copy(scale = if (scale.isFinite()) scale.coerceIn(.5f, 2f) else 1f,
        positionPercent = positionPercent.coerceIn(0, 100), delayMs = delayMs.coerceIn(-60_000, 60_000))
}

/** Exact capabilities only: a directory prefix or another content provider is never sufficient. */
internal fun isPlaybackDataUriAllowed(uri: String, ownedSubtitleUris: Set<String>): Boolean =
    when (uri.substringBefore(':', "").lowercase(java.util.Locale.ROOT)) {
        "http", "https" -> true
        "file" -> uri in ownedSubtitleUris
        else -> false
    }

/**
 * Shift only text sample timestamps, including transcoded cues. Video/audio clocks stay intact.
 * A new stream captures its delay once. The owner re-prepares at the current position on a
 * delay change, so TextRenderer's discarded cue history is never reused with a different clock.
 */
@UnstableApi
internal fun subtitleTimingRenderers(delegate: RenderersFactory, delayUs: () -> Long): RenderersFactory =
    object : RenderersFactory by delegate {
        override fun createRenderers(eventHandler: android.os.Handler,
            videoRendererEventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
            audioRendererEventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
            textRendererOutput: androidx.media3.exoplayer.text.TextOutput,
            metadataRendererOutput: androidx.media3.exoplayer.metadata.MetadataOutput): Array<Renderer> =
            delegate.createRenderers(eventHandler, videoRendererEventListener, audioRendererEventListener,
                textRendererOutput, metadataRendererOutput).map { renderer ->
                if (renderer.trackType == C.TRACK_TYPE_TEXT) SubtitleTimingRenderer(renderer, delayUs) else renderer
            }.toTypedArray()
    }

@UnstableApi
private class SubtitleTimingRenderer(private val delegate: Renderer, private val delayUs: () -> Long) : Renderer by delegate {
    private var originalStream: SampleStream? = null
    override fun enable(configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean, startPositionUs: Long,
        offsetUs: Long, mediaPeriodId: MediaPeriodId) {
        originalStream = stream
        delegate.enable(configuration, formats, SubtitleTimingStream(stream, delayUs()), positionUs,
            joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long,
        offsetUs: Long, mediaPeriodId: MediaPeriodId) {
        originalStream = stream
        delegate.replaceStream(formats, SubtitleTimingStream(stream, delayUs()), startPositionUs, offsetUs, mediaPeriodId)
    }
    // ExoPlayer compares this identity with MediaPeriod's stream when replacing tracks.
    override fun getStream(): SampleStream? = originalStream
    override fun disable() { delegate.disable(); originalStream = null }
}

@UnstableApi
internal class SubtitleTimingStream(private val delegate: SampleStream, private val delayUs: Long) : SampleStream by delegate {
    // Kotlin delegation does not forward Java interface default methods.
    override fun getFlags() = delegate.flags
    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = delegate.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream && buffer.timeUs != C.TIME_UNSET) {
            buffer.timeUs += delayUs
        } else if (result == C.RESULT_FORMAT_READ) {
            formatHolder.format?.let { format ->
                if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE)
                    formatHolder.format = format.buildUpon().setSubsampleOffsetUs(format.subsampleOffsetUs + delayUs).build()
            }
        }
        return result
    }
    override fun skipData(positionUs: Long) = delegate.skipData(positionUs - delayUs)
}

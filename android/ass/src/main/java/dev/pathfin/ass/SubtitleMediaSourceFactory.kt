package dev.pathfin.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.MaskingMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * Standard and libass subtitles share one lazy external-source pipeline.
 *
 * Media3 1.11.1's null-Format SubtitleExtractor never publishes the actual cue-end seek map:
 * a long cue at timestamp zero is estimated as a 10 ms source, and seeking past it clears the
 * queue without reloading. A complete Format fixes that at the extractor. Deferring creation of
 * its real period preserves the other contract: an unselected subtitle never opens a connection.
 */
@UnstableApi
class SubtitleMediaSourceFactory(
    private val dataSource: DataSource.Factory,
    extractors: ExtractorsFactory,
    private val parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : ForwardingMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors, parsers)) {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        if (subtitles.isEmpty()) return super.createMediaSource(mediaItem)
        val primary = super.createMediaSource(mediaItem.buildUpon().setSubtitleConfigurations(emptyList())
            .setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET).build())
        val sources = listOf(primary) + subtitles.map { subtitle ->
            val format = Format.Builder().setSampleMimeType(subtitle.mimeType).setLanguage(subtitle.language)
                .setSelectionFlags(subtitle.selectionFlags).setRoleFlags(subtitle.roleFlags)
                .setLabel(subtitle.label).setId(subtitle.id).build()
            require(parsers.supportsFormat(format)) { "不支持的外挂字幕格式" }
            val cuesFormat = format.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(format.sampleMimeType)
                .setCueReplacementBehavior(parsers.getCueReplacementBehavior(format)).build()
            val child = ProgressiveMediaSource.Factory(dataSource, ExtractorsFactory {
                arrayOf(SubtitleExtractor(parsers.create(format), format))
            }).createMediaSource(MediaItem.fromUri(subtitle.uri))
            LazySubtitleSource(child, cuesFormat)
        }
        // Keep the video's duration, then clip all clocks together, including the ASS renderer.
        val merged = MergingMediaSource(*sources.toTypedArray())
        val clipping = mediaItem.clippingConfiguration
        val result = if (clipping.startPositionUs != 0L || clipping.endPositionUs != C.TIME_END_OF_SOURCE ||
            clipping.relativeToDefaultPosition) ClippingMediaSource.Builder(merged)
            .setClippingConfiguration(clipping).build() else merged
        return object : WrappingMediaSource(result) {
            override fun getMediaItem() = mediaItem
            override fun canUpdateMediaItem(updatedMediaItem: MediaItem) = false
            override fun getInitialTimeline(): Timeline? = super.getInitialTimeline()?.let {
                TimelineWithUpdatedMediaItem.create(it, mediaItem)
            }
            override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
                refreshSourceInfo(TimelineWithUpdatedMediaItem.create(newTimeline, mediaItem))
            }
        }
    }
}

/** Public MaskingMediaSource owns all real source/period preparation and cancellation. */
@UnstableApi
private class LazySubtitleSource(child: MediaSource, private val format: Format) :
    WrappingMediaSource(MaskingMediaSource(child, true)) {
    private val masking get() = mediaSource as MaskingMediaSource
    override fun getInitialTimeline(): Timeline = masking.timeline
    override fun prepareSourceInternal() {
        super.prepareSourceInternal()
        refreshSourceInfo(masking.timeline)
    }
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        SelectedSubtitlePeriod(format, { positionUs -> masking.createPeriod(id, allocator, positionUs) }, masking::releasePeriod)
    override fun releasePeriod(mediaPeriod: MediaPeriod) = (mediaPeriod as SelectedSubtitlePeriod).release()
}

/**
 * Publishes the known single track, then hands samples, seeks and buffering to the official period
 * when selected. It neither fetches text nor parses/encodes cues nor invents a subtitle timestamp.
 */
@UnstableApi
private class SelectedSubtitlePeriod(
    format: Format,
    private val createChild: (Long) -> MediaPeriod,
    private val releaseChild: (MediaPeriod) -> Unit,
) : MediaPeriod, MediaPeriod.Callback {
    private val groups = TrackGroupArray(TrackGroup("0", format))
    private var callback: MediaPeriod.Callback? = null
    private var child: MediaPeriod? = null
    private var childReady = false
    private var selectedStream: WaitingSubtitleStream? = null
    private var childStream: SampleStream? = null
    private var positionUs = 0L
    private var released = false
    private var prerollFlags = false
    private var endPositionUs = C.TIME_END_OF_SOURCE

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        this.positionUs = positionUs
        callback.onPrepared(this)
    }
    override fun maybeThrowPrepareError() = Unit
    override fun getTrackGroups() = groups

    override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
        streams: Array<out SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
        this.positionUs = positionUs
        val index = selections.indexOfFirst { it != null }
        val previous = selectedStream
        // The source has one text track, so only one renderer can select it.
        selectedStream = if (index < 0) null
            else if (previous != null && streams[index] === previous && mayRetainStreamFlags[index]) previous
            else WaitingSubtitleStream()
        @Suppress("UNCHECKED_CAST") val writable = streams as Array<SampleStream?>
        for (i in writable.indices) {
            writable[i] = if (i == index) selectedStream else null
            if (i == index && selectedStream !== previous) streamResetFlags[i] = true
        }
        if (selectedStream == null) {
            releaseSelectedChild()
            return positionUs
        }
        if (child == null) {
            val period = createChild(positionUs)
            child = period
            if (prerollFlags) enablePrerollFlags(period)
            period.prepare(this, positionUs)
        }
        if (childReady) selectChild(retain = selectedStream === previous)
        return this.positionUs
    }

    private fun selectChild(retain: Boolean) {
        val period = checkNotNull(child)
        val selection: ExoTrackSelection? = if (selectedStream != null)
            FixedTrackSelection(period.trackGroups[0], 0) else null
        val streams = arrayOf(childStream)
        positionUs = period.selectTracks(arrayOf(selection), booleanArrayOf(retain && childStream != null),
            streams, BooleanArray(1), positionUs)
        childStream = streams[0]
        selectedStream?.delegate = childStream
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        if (released || child !== mediaPeriod) return
        childReady = true
        mediaPeriod.setEndPositionUs(endPositionUs)
        selectChild(retain = false)
        callback?.onContinueLoadingRequested(this)
    }
    override fun onContinueLoadingRequested(source: MediaPeriod) {
        if (!released && child === source) callback?.onContinueLoadingRequested(this)
    }
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        if (childReady) child?.discardBuffer(positionUs, toKeyframe)
    }
    override fun readDiscontinuity(): Long = if (childReady) child!!.readDiscontinuity() else C.TIME_UNSET
    override fun seekToUs(positionUs: Long): Long {
        this.positionUs = if (childReady) child!!.seekToUs(positionUs) else positionUs
        return this.positionUs
    }
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        if (childReady) child!!.getAdjustedSeekPositionUs(positionUs, seekParameters) else positionUs
    override fun getBufferedPositionUs(): Long = when {
        selectedStream == null -> C.TIME_END_OF_SOURCE
        childReady -> child!!.bufferedPositionUs
        else -> positionUs
    }
    override fun getNextLoadPositionUs(): Long = when {
        selectedStream == null -> C.TIME_END_OF_SOURCE
        childReady -> child!!.nextLoadPositionUs
        else -> positionUs
    }
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
        selectedStream != null && child?.continueLoading(loadingInfo) == true
    override fun isLoading(): Boolean = selectedStream != null && child?.isLoading == true
    override fun reevaluateBuffer(positionUs: Long) { if (childReady) child?.reevaluateBuffer(positionUs) }
    override fun setEndPositionUs(endPositionUs: Long): Long {
        this.endPositionUs = endPositionUs
        return if (childReady) child!!.setEndPositionUs(endPositionUs) else endPositionUs
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun setUsesStreamPrerollFlags() {
        prerollFlags = true
        child?.let { enablePrerollFlags(it) }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    private fun enablePrerollFlags(period: MediaPeriod) = period.setUsesStreamPrerollFlags()

    fun release() {
        released = true
        selectedStream?.delegate = null
        selectedStream = null
        releaseSelectedChild()
    }

    private fun releaseSelectedChild() {
        child?.let(releaseChild)
        child = null
        childStream = null
        childReady = false
    }

    private inner class WaitingSubtitleStream : SampleStream {
        var delegate: SampleStream? = null
        override fun isReady() = delegate?.isReady == true
        override fun maybeThrowError() {
            if (!released && selectedStream === this) {
                child?.maybeThrowPrepareError()
                delegate?.maybeThrowError()
            }
        }
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            delegate?.readData(holder, buffer, readFlags) ?: C.RESULT_NOTHING_READ
        override fun skipData(positionUs: Long) = delegate?.skipData(positionUs) ?: 0
        override fun getFlags() = delegate?.flags ?: 0
    }
}

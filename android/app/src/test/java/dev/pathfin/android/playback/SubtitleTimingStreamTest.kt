package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.*
import org.junit.Test

class SubtitleTimingStreamTest {
    @Test fun timingKeepsTheActualStreamPrerollAndDurationContract() {
        val source = FakeStream()
        source.streamFlags = SampleStream.FLAG_HAS_PREROLL or SampleStream.FLAG_STRICT_DURATION
        assertEquals(source.streamFlags, SubtitleTimingStream(source, 0).flags)
        assertEquals(source.streamFlags, SubtitleTimingStream(source, 500_000).flags)
    }
    @Test fun shiftingSamplesDoesNotMoveTheUnderlyingMediaSeekOrEndMarker() {
        val source = FakeStream()
        val stream = SubtitleTimingStream(source, 500_000)
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
        assertEquals(12_500_000, buffer.timeUs)
        stream.skipData(16_000_000)
        assertEquals(15_500_000, source.skippedAt)
        source.ended = true
        buffer.clear()
        stream.readData(holder, buffer, 0)
        assertTrue(buffer.isEndOfStream)
        assertEquals(12_000_000, buffer.timeUs)
    }

    @Test fun earlierSamplesAndAbsoluteSubsampleOffsetsShiftTogetherButRelativeSentinelDoesNot() {
        val source = FakeStream()
        val stream = SubtitleTimingStream(source, -500_000)
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        stream.readData(holder, buffer, 0)
        assertEquals(11_500_000, buffer.timeUs)
        source.format = Format.Builder().setSampleMimeType("text/x-ssa").setSubsampleOffsetUs(2_000_000).build()
        stream.readData(holder, buffer, 0)
        assertEquals(1_500_000, holder.format!!.subsampleOffsetUs)
        source.format = source.format!!.buildUpon().setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE).build()
        stream.readData(holder, buffer, 0)
        assertEquals(Format.OFFSET_SAMPLE_RELATIVE, holder.format!!.subsampleOffsetUs)
    }

    @Test fun presentationRejectsNonFiniteScaleAndBoundsOffsets() {
        assertEquals(SubtitlePresentation(1f, 0, 60_000), SubtitlePresentation(Float.NaN, -1, Long.MAX_VALUE).normalized())
    }

    @Test fun onlyAnExactOwnedSubtitleCapabilityAllowsLocalData() {
        val owned = setOf("file:///data/user/0/dev.pathfin/cache/player-subtitles/pathfin-local-1.srt")
        assertTrue(isPlaybackDataUriAllowed(owned.single(), owned))
        assertTrue(isPlaybackDataUriAllowed("https://media.fixture.invalid/video.m3u8", owned))
        assertTrue(isPlaybackDataUriAllowed("http://127.0.0.1:8102/sample.mp4", emptySet()))
        for (uri in listOf(
            "file:///data/user/0/dev.pathfin/cache/player-subtitles/pathfin-local-2.srt",
            "file:///data/user/0/dev.pathfin/cache/player-subtitles/../secret",
            owned.single() + "?ignored=true", owned.single() + "#fragment",
            owned.single().replace("pathfin-local-1", "%70athfin-local-1"),
            "content://media/external/downloads/1", "asset:///subtitle.ass", "data:text/plain,private", 
            "file:///etc/hosts", "cache/player-subtitles/pathfin-local-1.srt")) {
            assertFalse(uri, isPlaybackDataUriAllowed(uri, owned))
        }
        assertFalse(isPlaybackDataUriAllowed(owned.single(), emptySet()))
    }

    private class FakeStream : SampleStream {
        var streamFlags = 0
        override fun getFlags() = streamFlags
        var format: Format? = null
        var ended = false
        var skippedAt = -1L
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            format?.let { formatHolder.format = it; return C.RESULT_FORMAT_READ }
            buffer.timeUs = 12_000_000
            if (ended) buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            return C.RESULT_BUFFER_READ
        }
        override fun skipData(positionUs: Long): Int { skippedAt = positionUs; return 1 }
    }
}

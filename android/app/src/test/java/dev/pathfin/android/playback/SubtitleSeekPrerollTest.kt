package dev.pathfin.android.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SubtitleSeekPrerollTest {
    @Test fun embeddedTextAndMedia3ParsedCuesRetainTheirPrerollEligibility() {
        for (mime in listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT)) {
            assertTrue(mime, supportsSubtitleSeekPreroll(Format.Builder().setSampleMimeType(mime).build()))
            assertTrue("parsed $mime", supportsSubtitleSeekPreroll(Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(mime).build()))
        }
    }

    @Test fun bitmapUnknownAndUnidentifiedParsedCuesNeverTriggerPreroll() {
        for (mime in listOf(null, "application/pgs", "application/vobsub", "application/dvbsubs", "text/plain")) {
            assertFalse("raw $mime", supportsSubtitleSeekPreroll(Format.Builder().setSampleMimeType(mime).build()))
            assertFalse("parsed $mime", supportsSubtitleSeekPreroll(Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(mime).build()))
        }
        assertFalse(supportsSubtitleSeekPreroll(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP).build()))
    }

    @Test fun srtLongCueAtZeroIsReadAfterNonzeroPlayerRecreationWithoutChangingVideoPosition() {
        // 自有 MKV 的 SRT 包从 0.021s 延续 23.5s，9.021s 视频关键帧已位于后一个 cluster。
        val text = mapOf(
            4 to Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
            5 to Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(MimeTypes.APPLICATION_SUBRIP).build(),
        )
        val trackIds = text.filterValues(::supportsSubtitleSeekPreroll).keys
        val map = IndexedMap(SeekMap.SeekPoints(SeekPoint(9_021_000, 64_078)),
            mapOf(4 to SeekPoint(21_000, 1_274), 5 to SeekPoint(21_000, 1_274)))
        val result = subtitlePrerollSeekPoints(map, 9_500_000, trackIds)
        assertEquals(9_021_000L, result.first.timeUs)
        assertEquals(1_274L, result.first.position)
        assertEquals(result.first, result.second)
        assertEquals(setOf(4, 5), map.requestedTrackIds.toSet())
    }

    @Test fun srtFormatDoesNotBypassTimeByteOrMissingIndexLimits() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP).build()
        assertTrue(supportsSubtitleSeekPreroll(format))
        val target = MAX_SUBTITLE_PREROLL_US + 21_001L
        val outsideTime = IndexedMap(SeekMap.SeekPoints(SeekPoint(target, 64_078)), mapOf(4 to SeekPoint(21_000, 1_274)))
        val outsideBytes = IndexedMap(SeekMap.SeekPoints(SeekPoint(9_021_000, MAX_SUBTITLE_PREROLL_BYTES + 1_275)),
            mapOf(4 to SeekPoint(21_000, 1_274)))
        val missingIndex = IndexedMap(SeekMap.SeekPoints(SeekPoint(9_021_000, 64_078)), emptyMap())
        assertEquals(outsideTime.points, subtitlePrerollSeekPoints(outsideTime, target, setOf(4)))
        assertEquals(outsideBytes.points, subtitlePrerollSeekPoints(outsideBytes, 9_500_000, setOf(4)))
        assertEquals(missingIndex.points, subtitlePrerollSeekPoints(missingIndex, 9_500_000, setOf(4)))
        assertTrue(missingIndex.requestedTrackIds.isEmpty())
    }

    @Test fun opaqueAssStyleExtractorStillPublishesConvertedTextAndTheRealTrackIndex() {
        val map = IndexedMap(SeekMap.SeekPoints(SeekPoint(9_021_000, 64_078)),
            mapOf(4 to SeekPoint(21_000, 1_274)))
        val indexed = IndexedTextExtractor(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP).build(), map)
        // RawAssExtractor 同样隐藏实际 Matroska 类型，但转码输出原样传递 TrackAwareSeekMap。
        val opaque = object : Extractor by indexed {}
        val factory = SubtitleSeekPrerollExtractorsFactory(ExtractorsFactory { arrayOf<Extractor>(opaque) })
        var published: SeekMap? = null
        factory.createExtractors().single().init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { published = seekMap }
        })
        val result = checkNotNull(published).getSeekPoints(9_500_000)
        assertEquals(9_021_000L, result.first.timeUs)
        assertEquals(1_274L, result.first.position)
        assertEquals(setOf(4), map.requestedTrackIds.toSet())
    }

    @Test fun anAlreadyWrappedExtractorIsNeverWrappedAgain() {
        val extractor = IndexedTextExtractor(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(), indexedMap())
        val wrapped = SubtitleSeekPrerollExtractorsFactory(ExtractorsFactory { arrayOf<Extractor>(extractor) })
            .createExtractors().single()
        val wrappedAgain = SubtitleSeekPrerollExtractorsFactory(ExtractorsFactory { arrayOf(wrapped) })
            .createExtractors().single()
        assertSame(wrapped, wrappedAgain)
    }

    @Test fun physicalReadIncludesThePriorSubtitleButKeepsTheVideoSeekTimes() {
        val map = indexedMap()
        val result = subtitlePrerollSeekPoints(map, 16_000_000, setOf(2))
        assertEquals(16_000_000, result.first.timeUs)
        assertEquals(18_000_000, result.second.timeUs)
        assertEquals(8_000, result.first.position)
        assertEquals(8_000, result.second.position)
        assertEquals(listOf(2, 2), map.requestedTrackIds)
    }

    @Test fun noTextIndexNeverFallsBackToTheBeginningOrTheVideoIndex() {
        val map = indexedMap(subtitles = emptyMap())
        val original = map.points
        val result = subtitlePrerollSeekPoints(map, 16_000_000, setOf(2))
        assertEquals(original, result)
        assertTrue(map.requestedTrackIds.isEmpty())
    }

    @Test fun noTextTrackOrZeroStartPreservesTheOriginalSeekMapResult() {
        val map = indexedMap()
        assertSame(map.points, subtitlePrerollSeekPoints(map, 16_000_000, emptySet()))
        assertSame(map.points, subtitlePrerollSeekPoints(map, 0, setOf(2)))
        assertTrue(map.requestedTrackIds.isEmpty())
    }

    @Test fun aLongSubtitleGapCannotTriggerAnUnboundedPreroll() {
        val targetUs = 3_600_000_000L
        val map = IndexedMap(SeekMap.SeekPoints(SeekPoint(targetUs, 50_000)),
            mapOf(2 to SeekPoint(0, 10)))
        assertEquals(map.points, subtitlePrerollSeekPoints(map, targetUs, setOf(2)))
    }

    @Test fun byteBudgetPreventsLargeMediaReadsEvenForARecentSubtitle() {
        val map = IndexedMap(SeekMap.SeekPoints(SeekPoint(16_000_000, MAX_SUBTITLE_PREROLL_BYTES + 11)),
            mapOf(2 to SeekPoint(12_000_000, 10)))
        assertEquals(map.points, subtitlePrerollSeekPoints(map, 16_000_000, setOf(2)))
    }

    @Test fun inclusiveBudgetsPermitOnlyTheDocumentedMaximumRead() {
        val targetUs = MAX_SUBTITLE_PREROLL_US + 1_000_000
        val map = IndexedMap(SeekMap.SeekPoints(SeekPoint(targetUs, MAX_SUBTITLE_PREROLL_BYTES + 10)),
            mapOf(2 to SeekPoint(1_000_000, 10)))
        val result = subtitlePrerollSeekPoints(map, targetUs, setOf(2))
        assertEquals(targetUs, result.first.timeUs)
        assertEquals(10, result.first.position)
    }

    @Test fun invalidOrFutureIndexPointsCannotMoveASeekOrExpandTheBudget() {
        val map = indexedMap(subtitles = mapOf(
            2 to SeekPoint(17_000_000, 1),
            3 to SeekPoint(12_000_000, -1),
            4 to SeekPoint(-1, 1),
            5 to SeekPoint(12_000_000, 50_000),
        ))
        assertEquals(map.points, subtitlePrerollSeekPoints(map, 16_000_000, setOf(2, 3, 4, 5)))
    }

    @Test fun multipleTextTracksUseOnlyTheEarliestEligibleExistingCluster() {
        val map = indexedMap(subtitles = mapOf(
            2 to SeekPoint(12_000_000, 8_000),
            3 to SeekPoint(10_000_000, 7_000),
            4 to SeekPoint(17_000_000, 1),
        ))
        val result = subtitlePrerollSeekPoints(map, 16_000_000, setOf(2, 3, 4))
        assertEquals(7_000, result.first.position)
        assertEquals(7_000, result.second.position)
    }

    private fun indexedMap(subtitles: Map<Int, SeekPoint> = mapOf(2 to SeekPoint(12_000_000, 8_000))) =
        IndexedMap(SeekMap.SeekPoints(SeekPoint(16_000_000, 10_000), SeekPoint(18_000_000, 20_000)), subtitles)

    private class IndexedTextExtractor(private val format: Format, private val map: SeekMap) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = true
        override fun init(output: ExtractorOutput) {
            output.track(4, C.TRACK_TYPE_TEXT).format(format)
            output.endTracks()
            output.seekMap(map)
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private class IndexedMap(val points: SeekMap.SeekPoints, private val subtitles: Map<Int, SeekPoint>) : TrackAwareSeekMap {
        val requestedTrackIds = mutableListOf<Int>()
        override fun isSeekable(): Boolean = true
        override fun isSeekable(trackId: Int): Boolean = trackId in subtitles
        override fun getDurationUs(): Long = 4_000_000_000L
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints = points
        override fun getSeekPoints(timeUs: Long, trackId: Int): SeekMap.SeekPoints {
            check(trackId in subtitles) { "Missing text index must not fall back to a primary/video index" }
            requestedTrackIds += trackId
            return SeekMap.SeekPoints(checkNotNull(subtitles[trackId]))
        }
    }
}

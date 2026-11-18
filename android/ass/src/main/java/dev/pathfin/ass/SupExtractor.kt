package dev.pathfin.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput

/** Blu-ray SUP stores a 90 kHz timestamp before each PGS segment. Media3 expects one display set per sample. */
@UnstableApi
internal class SupExtractor(private val format: Format, private val parsers: SubtitleParser.Factory) : Extractor {
    private val header = ParsableByteArray(13)
    private val sample = ParsableByteArray()
    private val index = IndexSeekMap(longArrayOf(0), longArrayOf(0), C.TIME_UNSET)
    private lateinit var track: TrackOutput
    private var sampleTimeUs = C.TIME_UNSET
    private var samplePosition = 0L
    private var size = 0

    override fun sniff(input: ExtractorInput): Boolean =
        input.peekFully(header.data, 0, 2, true) && header.data[0] == 0x50.toByte() && header.data[1] == 0x47.toByte()

    override fun init(output: ExtractorOutput) {
        val transcoder = SubtitleTranscodingExtractorOutput(output, parsers)
        track = transcoder.track(0, C.TRACK_TYPE_TEXT)
        track.format(format)
        transcoder.endTracks()
        // ProgressiveMediaPeriod queries on the playback thread while loading extends the index.
        transcoder.seekMap(object : SeekMap {
            override fun getDurationUs() = C.TIME_UNSET
            override fun isSeekable() = true
            override fun getSeekPoints(timeUs: Long) = synchronized(index) { index.getSeekPoints(timeUs) }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val position = input.position
        if (!input.readFully(header.data, 0, 13, true)) {
            if (size != 0) malformed()
            return Extractor.RESULT_END_OF_INPUT
        }
        header.position = 0
        if (header.readUnsignedShort() != 0x5047) malformed()
        val pts = header.readUnsignedInt()
        header.skipBytes(4) // DTS
        val type = header.readUnsignedByte()
        val length = header.readUnsignedShort()
        if (type == 0x16) {
            if (size != 0) malformed()
            sampleTimeUs = pts * 1_000 / 90
            samplePosition = position
        }
        if (sampleTimeUs == C.TIME_UNSET || size + 3 + length > MAX_SAMPLE_BYTES) malformed()
        val end = size + 3 + length
        if (end > sample.capacity()) sample.ensureCapacity(maxOf(end, sample.capacity() * 2).coerceAtMost(MAX_SAMPLE_BYTES))
        header.data.copyInto(sample.data, size, 10, 13)
        input.readFully(sample.data, size + 3, length)
        if (type == 0x15 && length >= 11 && (sample.data[size + 6].toInt() and 0x80) != 0) {
            sample.setLimit(end)
            sample.position = size + 7
            val objectBytes = sample.readUnsignedInt24()
            val pixels = sample.readUnsignedShort().toLong() * sample.readUnsignedShort()
            if (objectBytes > MAX_SAMPLE_BYTES || pixels > 4096L * 2304) malformed()
        }
        size = end
        if (type == 0x80) {
            sample.setLimit(size)
            sample.position = 0
            track.sampleData(sample, size)
            track.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
            synchronized(index) {
                if (!index.isTimeUsInIndex(sampleTimeUs, 1_000_000)) index.addSeekPoint(sampleTimeUs, samplePosition)
            }
            size = 0
            sampleTimeUs = C.TIME_UNSET
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        size = 0
        sampleTimeUs = C.TIME_UNSET
    }

    override fun release() = Unit

    private fun malformed(): Nothing = throw ParserException.createForMalformedContainer("Invalid SUP display set", null)

    private companion object { const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024 }
}

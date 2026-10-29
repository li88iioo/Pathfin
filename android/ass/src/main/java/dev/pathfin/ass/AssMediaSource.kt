/*
 * MKV attachment handling derived from peerless2012/libass-android, MIT.
 * Copyright (c) 2025 peerless2012. See LICENSES/libass-android-MIT.txt.
 * Pathfin replaces private-field reflection with public Media3 1.11.1 TrackOutput wrappers,
 * bounds all copied data, and keeps the caller's network DataSource unchanged.
 */
package dev.pathfin.ass

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

@UnstableApi
internal class AssSubtitleParserFactory(
    private val controller: AssController,
    private val embeddedPackets: Boolean,
) : SubtitleParser.Factory {
    private val base = DefaultSubtitleParserFactory()
    override fun supportsFormat(format: Format) = base.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format) = base.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser {
        val fallback = base.create(format)
        if (format.sampleMimeType != MimeTypes.TEXT_SSA ||
            (!embeddedPackets && format.initializationData.isNotEmpty())) return fallback
        val track = controller.openTrack(format) ?: return fallback
        return object : SubtitleParser {
            override fun getCueReplacementBehavior() = fallback.cueReplacementBehavior
            override fun reset() { fallback.reset() }
            override fun parse(data: ByteArray, offset: Int, length: Int,
                               outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
                val active = !controller.failed && !track.failed && (embeddedPackets ||
                    length in 1..AssController.MAX_TEXT && controller.external(track, data.copyOfRange(offset, offset + length)))
                if (active) {
                    // Keep the standard text pipeline's readiness/end semantics. Actual animated pixels
                    // are rendered every playback tick by libass, not these empty cues.
                    output.accept(CuesWithTiming(emptyList(), 0, 0))
                } else fallback.parse(data, offset, length, outputOptions, output)
            }
        }
    }
}

@UnstableApi
internal class AssExtractorsFactory(private val controller: AssController) : ExtractorsFactory {
    private val base = DefaultExtractorsFactory().setSubtitleParserFactory(AssSubtitleParserFactory(controller, false))
    private var transcode = true
    override fun createExtractors(): Array<Extractor> = replace(base.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: MutableMap<String, MutableList<String>>): Array<Extractor> =
        replace(base.createExtractors(uri, responseHeaders))
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        base.setSubtitleParserFactory(subtitleParserFactory)
    }
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        transcode = textTrackTranscodingEnabled
        base.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        base.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }
    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply { base.setParseHagcMetadata(parseHagcMetadata) }
    private fun replace(extractors: Array<Extractor>): Array<Extractor> = extractors.map { extractor ->
        if (transcode && extractor is MatroskaExtractor) {
            val parsers = AssSubtitleParserFactory(controller, true)
            RawAssExtractor(FontMatroskaExtractor(parsers, controller), parsers, controller)
        } else extractor
    }.toTypedArray()
}

/** Matroska emits its public raw SSA sample contract; wrap before the public transcoder. */
@UnstableApi
private class RawAssExtractor(
    private val raw: Extractor,
    private val parsers: SubtitleParser.Factory,
    private val controller: AssController,
) : Extractor by raw {
    private var transcoder: SubtitleTranscodingExtractorOutput? = null
    override fun init(output: ExtractorOutput) {
        val converted = SubtitleTranscodingExtractorOutput(output, parsers)
        transcoder = converted
        raw.init(object : ExtractorOutput by converted {
            override fun track(id: Int, type: Int): TrackOutput {
                val delegate = converted.track(id, type)
                return if (type == C.TRACK_TYPE_TEXT) RawAssTrackOutput(delegate, controller) else delegate
            }
        })
    }
    override fun seek(position: Long, timeUs: Long) {
        raw.seek(position, timeUs)
        transcoder?.resetSubtitleParsers()
        controller.discontinuity()
    }
}

@UnstableApi
private class RawAssTrackOutput(
    private val delegate: TrackOutput,
    private val controller: AssController,
) : TrackOutput by delegate {
    private var format: Format? = null
    private val pending = ByteArrayOutputStream()
    override fun format(format: Format) {
        this.format = format.takeIf { it.sampleMimeType == MimeTypes.TEXT_SSA }
        delegate.format(format)
    }
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (format != null && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            checkSize(length)
            pending.write(data.data, data.position, length)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }
    override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (format == null || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN)
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        // The raw Matroska SSA path normally supplies a complete ParsableByteArray. Still respect
        // TrackOutput's partial-read contract for other public extractors/callers.
        val buffer = ByteArray(minOf(length, 64 * 1024))
        val read = input.read(buffer, 0, buffer.size)
        if (read == C.RESULT_END_OF_INPUT) {
            if (!allowEndOfInput) throw EOFException()
            return read
        }
        checkSize(read)
        pending.write(buffer, 0, read)
        delegate.sampleData(ParsableByteArray(buffer, read), read, sampleDataPart)
        return read
    }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        format?.let { current ->
            val bytes = pending.toByteArray()
            val end = bytes.size - offset
            val start = end - size
            if (start >= 0 && end in 0..bytes.size && cryptoData == null) {
                val track = controller.openTrack(current)
                if (track != null) controller.chunk(track, bytes.copyOfRange(start, end), timeUs)
            }
            pending.reset()
            if (offset in 1..bytes.size) pending.write(bytes, bytes.size - offset, offset)
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
    private fun checkSize(length: Int) {
        if (length < 0 || length > MAX_SAMPLE || pending.size().toLong() + length > MAX_SAMPLE)
            throw IOException("ASS 字幕单个样本超过安全大小限制")
    }
    companion object { private const val MAX_SAMPLE = 1024 * 1024 }
}

/** MIT-derived EBML attachment discovery, with bounded reads and no filesystem extraction. */
@UnstableApi
private class FontMatroskaExtractor(parsers: SubtitleParser.Factory, private val controller: AssController) :
    MatroskaExtractor(parsers, FLAG_EMIT_RAW_SUBTITLE_DATA) {
    private var name: String? = null
    private var fontBytes = 0L
    override fun getElementType(id: Int): Int = when (id) {
        ATTACHMENTS, ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
        FILE_NAME, FILE_MIME, FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
        else -> super.getElementType(id)
    }
    override fun isLevel1Element(id: Int) = id == ATTACHMENTS || super.isLevel1Element(id)
    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == ATTACHED_FILE) name = null else super.startMasterElement(id, contentPosition, contentSize)
    }
    override fun endMasterElement(id: Int) {
        if (id == ATTACHED_FILE) name = null else super.endMasterElement(id)
    }
    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        when (id) {
            FILE_NAME -> {
                if (contentSize in 1..512) {
                    val bytes = ByteArray(contentSize)
                    input.readFully(bytes, 0, contentSize)
                    name = String(bytes, Charsets.UTF_8).trimEnd('\u0000').takeIf(AssPacket::safeFontName)
                } else { name = null; input.skipFully(contentSize) }
            }
            FILE_MIME -> input.skipFully(contentSize) // extension identifies a font even if MIME is absent.
            FILE_DATA -> {
                val filename = name
                if (filename != null && contentSize in 1..AssController.MAX_FONT &&
                    fontBytes + contentSize <= AssController.MAX_FONTS) {
                    fontBytes += contentSize
                    val bytes = ByteArray(contentSize)
                    input.readFully(bytes, 0, contentSize)
                    controller.addFont(filename, bytes)
                } else input.skipFully(contentSize)
            }
            else -> super.binaryElement(id, contentSize, input)
        }
    }
    companion object {
        private const val ATTACHMENTS = 0x1941A469
        private const val ATTACHED_FILE = 0x61A7
        private const val FILE_NAME = 0x466E
        private const val FILE_MIME = 0x4660
        private const val FILE_DATA = 0x465C
    }
}

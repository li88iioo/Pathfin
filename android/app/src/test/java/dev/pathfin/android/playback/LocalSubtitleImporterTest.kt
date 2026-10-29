package dev.pathfin.android.playback

import org.junit.Assert.*
import org.junit.Test

class LocalSubtitleImporterTest {
    @Test fun identifiedFormatsUseTheMedia3MimeTypesFromTheSharedSubtitleParser() {
        assertEquals("application/x-subrip", LocalSubtitleImporter.identify("1\n00:00:01,000 --> 00:00:02,000\n字幕".toByteArray()).second)
        assertEquals("text/vtt", LocalSubtitleImporter.identify("WEBVTT\n\n00:01.000 --> 00:02.000\n字幕".toByteArray()).second)
        assertEquals("text/x-ssa", LocalSubtitleImporter.identify("[Script Info]\n[Events]".toByteArray()).second)
    }
    @Test fun identifiesRealContentRatherThanTrustingAFilename() {
        assertEquals("srt", LocalSubtitleImporter.identify("1\n00:00:02,000 --> 00:00:22,000\nLONG CUE\n".toByteArray()).first)
        assertEquals("vtt", LocalSubtitleImporter.identify("\uFEFFWEBVTT\n\n00:02.000 --> 00:22.000\nLONG CUE\n".toByteArray()).first)
        assertEquals("ass", LocalSubtitleImporter.identify("[Script Info]\nScriptType: v4.00+\n[Events]\n".toByteArray(Charsets.UTF_16)).first)
    }
    @Test fun rejectsBinaryEmptyAndOversizeInputBeforeCreatingAFile() {
        for (input in listOf(byteArrayOf(), byteArrayOf(0xff.toByte(), 0, 0x01), ByteArray(LocalSubtitleImporter.MAX_BYTES + 1))) {
            assertNotNull(runCatching { LocalSubtitleImporter.identify(input) }.exceptionOrNull())
        }
    }
}

package dev.pathfin.android.domain

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SubtitleUploadFileTest {
    @Test fun formatComesFromContentAndUtf16FilesAreNormalizedToUtf8() {
        val file = SubtitleUploadFile.prepare("1\n00:00:01,000 --> 00:00:02,000\n字幕".toByteArray(Charsets.UTF_16))
        assertEquals("srt", file.format)
        assertTrue(file.content.toString(Charsets.UTF_8).endsWith("字幕"))
        assertEquals("vtt", SubtitleUploadFile.prepare("\uFEFFWEBVTT\n\n00:01.000 --> 00:02.000\nSubtitle".toByteArray()).format)
        assertEquals("ass", SubtitleUploadFile.prepare("[Script Info]\nTitle: test\n[Events]\n".toByteArray()).format)
    }

    @Test fun emptyBinaryMalformedAndOversizedInputsAreRejected() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28), "WEBVTT\u0000".toByteArray(),
                "not subtitles".toByteArray(), ByteArray(SubtitleUploadFile.MAX_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { SubtitleUploadFile.prepare(bytes) }
        }
    }

    @Test fun normalizedLanguageIsAThreeLetterCodeAndCannotChangeTheApiPath() {
        assertEquals("zho", SubtitleLanguages.normalize(" ZHO "))
        for (value in listOf("zh", "../", "eng/subtitles", "en%67", "123")) {
            assertThrows(IllegalArgumentException::class.java) { SubtitleLanguages.normalize(value) }
        }
    }

    @Test fun subtitleLanguageLabelsFollowLocaleChangesWithoutChangingCodes() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            val chinese = SubtitleLanguages.common
            assertEquals("英语", chinese.single { it.code == "eng" }.label)

            Locale.setDefault(Locale.ENGLISH)
            val english = SubtitleLanguages.common
            assertEquals("English", english.single { it.code == "eng" }.label)
            assertEquals("Unspecified", english.single { it.code == "und" }.label)
            assertEquals(chinese.map { it.code }, english.map { it.code })

            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals(chinese, SubtitleLanguages.common)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

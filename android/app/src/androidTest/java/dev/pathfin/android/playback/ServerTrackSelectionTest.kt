package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServerTrackSelectionTest {
    @Test fun aNonDefaultLanguageUsesTheServerIndexRatherThanTheMatroskaTrackNumber() {
        val source = source(audio(1, "eng", isDefault = true), audio(4, "jpn"))
        val selected = audioFormat("ja").setId("5").build()
        assertEquals(4, serverTrackIndex(source, C.TRACK_TYPE_AUDIO, selected))
    }

    @Test fun normalizedLabelsDistinguishMainAndCommentaryInTheSameLanguage() {
        val source = source(audio(1, "eng", title = "Main"), audio(2, "eng", title = "Director   Commentary"))
        assertEquals(2, serverTrackIndex(source, C.TRACK_TYPE_AUDIO,
            audioFormat("en").setLabel("  director commentary ").setRoleFlags(C.ROLE_FLAG_COMMENTARY).build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, audioFormat("en").build()))
    }

    @Test fun aServerDisplayTitleCanProvideTheExactLabelWhenTheRawTitleDoesNot() {
        val source = source(audio(2, "eng", title = "Main").copy(displayTitle = "English main"),
            audio(3, "eng", title = "Commentary").copy(displayTitle = "English commentary"))
        assertEquals(2, serverTrackIndex(source, C.TRACK_TYPE_AUDIO, audioFormat("eng").setLabel("English main").build()))
    }

    @Test fun ambiguousMetadataNeverUsesAFormatIdToGuessTheOriginalIndex() {
        val source = source(audio(1, "eng"), audio(2, "eng"))
        for (id in listOf("1", "2", "1/0", "audio_0")) {
            assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, audioFormat("eng").setId(id).build()))
        }
        assertNull(serverTrackIndex(source(audio(1, "eng")), C.TRACK_TYPE_AUDIO,
            Format.Builder().setId("1").setSampleMimeType(MimeTypes.AUDIO_AAC).build()))
    }

    @Test fun knownCodecChannelsAndSampleRateExcludeConflictingTracksWithoutScoring() {
        val source = source(audio(1, "eng"), audio(2, "eng").copy(codec = "ac3"),
            audio(3, "eng").copy(channels = 6), audio(4, "eng").copy(sampleRate = 44_100))
        assertEquals(1, serverTrackIndex(source, C.TRACK_TYPE_AUDIO,
            audioFormat("en").setChannelCount(2).setSampleRate(48_000).build()))
        assertEquals(2, serverTrackIndex(source, C.TRACK_TYPE_AUDIO,
            audioFormat("en").setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(2).setSampleRate(48_000).build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, audioFormat("en").build()))
    }

    @Test fun theAppOwnedExternalSubtitleIdMapsExactlyEvenWhenDeliveryConvertedItsCodec() {
        val source = source(subtitle(5, "eng"), subtitle(9, "eng").copy(isExternal = true, codec = "ass"))
        val selected = Format.Builder().setId("external-subtitle-9").setSampleMimeType(MimeTypes.TEXT_VTT).build()
        assertEquals(9, serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected.buildUpon().setId("external-subtitle-5").build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected.buildUpon().setId("external-subtitle-99").build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected.buildUpon().setId("external-subtitle-bad").build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, selected))
    }

    @Test fun localSubtitlesCannotBecomeASelectionOfAServerSubtitleWithTheSameDescription() {
        val source = source(subtitle(5, "eng", title = "English"))
        for (id in listOf("local-selected", "pathfin-local-selected")) {
            val selected = Format.Builder().setId(id).setLanguage("en").setLabel("English")
                .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()
            assertNull(serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected))
        }
    }

    @Test fun forcedAndHearingImpairedMetadataKeepDifferentSubtitleTracksDistinct() {
        val source = source(subtitle(5, "eng"), subtitle(6, "eng").copy(isForced = true),
            subtitle(7, "eng").copy(isHearingImpaired = true))
        val selected = Format.Builder().setLanguage("en").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP)
        assertEquals(6, serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected.setSelectionFlags(C.SELECTION_FLAG_FORCED).build()))
        assertEquals(7, serverTrackIndex(source, C.TRACK_TYPE_TEXT,
            selected.setSelectionFlags(0).setRoleFlags(C.ROLE_FLAG_CAPTION).build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_TEXT, selected.setRoleFlags(0).build()))
    }

    @Test fun contradictoryOrMissingIdentityIsNotRepairedByAUniqueCodecOrTrackNumber() {
        val source = source(audio(1, "eng", title = "Main"))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, audioFormat("en").setLabel("Commentary").build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, audioFormat("ja").setLabel("Main").build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_VIDEO, audioFormat("en").build()))
        assertNull(serverTrackIndex(source, C.TRACK_TYPE_AUDIO, null))
        assertNull(serverTrackIndex(source(audio(1, "eng").copy(index = null), audio(-1, "eng")),
            C.TRACK_TYPE_AUDIO, audioFormat("en").build()))
    }

    private fun source(vararg streams: MediaStream) = MediaSource("source", mediaStreams = streams.toList())
    private fun audio(index: Int, language: String, title: String? = null, isDefault: Boolean = false) =
        MediaStream(index = index, type = "Audio", language = language, title = title, codec = "aac",
            channels = 2, sampleRate = 48_000, isDefault = isDefault)
    private fun subtitle(index: Int, language: String, title: String? = null) =
        MediaStream(index = index, type = "Subtitle", language = language, title = title, codec = "subrip")
    private fun audioFormat(language: String) = Format.Builder().setLanguage(language).setSampleMimeType(MimeTypes.AUDIO_AAC)
}

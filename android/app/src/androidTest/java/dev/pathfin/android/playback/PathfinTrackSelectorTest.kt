package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 经过真实 MappingTrackSelector 入口，保证手动覆盖、关闭以及自动回退都在同一条选轨链路。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PathfinTrackSelectorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun serverAudioDefaultUsesItsStableIdentityRatherThanItsIndexAsATrackPosition() {
        val english = audioGroup("english", "en", isDefault = true)
        val japanese = audioGroup("japanese", "ja")
        val source = source(audio(1, "eng"), audio(44, "jpn")).copy(defaultAudioStreamIndex = 44)
        withSelector(source) { selector ->
            assertEquals("japanese", select(selector, english, japanese).audioId)
        }
    }

    @Test fun anAvailablePreferredAudioLanguageWinsAndAnUnavailableOneFallsBackToTheServer() {
        val english = audioGroup("english", "en", isDefault = true)
        val japanese = audioGroup("japanese", "ja")
        val chinese = audioGroup("chinese", "zh-Hant")
        val source = source(audio(1, "eng"), audio(44, "jpn"), audio(70, "zho")).copy(defaultAudioStreamIndex = 44)
        withSelector(source, configure = { setPreferredAudioLanguage("zh") }) { selector ->
            assertEquals("chinese", select(selector, english, japanese, chinese).audioId)
            selector.setParameters(selector.buildUponParameters().setPreferredAudioLanguage("fr"))
            assertEquals("japanese", select(selector, english, japanese, chinese).audioId)
        }
    }

    @Test fun subtitlePreferenceWinsOverServerDefaultIncludingTheServersDisabledState() {
        val english = subtitleGroup("english", "en", isDefault = true)
        val japanese = subtitleGroup("japanese", "ja")
        val chinese = subtitleGroup("chinese", "zh-Hant")
        val source = source(subtitle(5, "eng"), subtitle(90, "jpn"), subtitle(100, "zho"))
        withSelector(source.copy(defaultSubtitleStreamIndex = 90), configure = { setPreferredTextLanguage("zh") }) { selector ->
            assertEquals("chinese", select(selector, english, japanese, chinese).subtitleId)
            selector.setParameters(selector.buildUponParameters().setPreferredTextLanguage("fr"))
            assertEquals("japanese", select(selector, english, japanese, chinese).subtitleId)
        }
        withSelector(source.copy(defaultSubtitleStreamIndex = -1), configure = { setPreferredTextLanguage("zh") }) { selector ->
            assertEquals("chinese", select(selector, english, japanese, chinese).subtitleId)
            selector.setParameters(selector.buildUponParameters().setPreferredTextLanguage("fr"))
            assertNull(select(selector, english, japanese, chinese).subtitleId)
        }
    }

    @Test fun manualAudioAndSubtitleOverridesWinAndReturningToAutomaticUsesTheServerAgain() {
        val english = audioGroup("english", "en", isDefault = true)
        val japanese = audioGroup("japanese", "ja")
        val textTrack = subtitleGroup("subtitle", "en", isDefault = true)
        val source = source(audio(1, "eng"), audio(44, "jpn"), subtitle(80, "eng"))
            .copy(defaultAudioStreamIndex = 44, defaultSubtitleStreamIndex = -1)
        withSelector(source, configure = {
            setOverrideForType(TrackSelectionOverride(english, 0))
            setOverrideForType(TrackSelectionOverride(textTrack, 0))
        }) { selector ->
            val manual = select(selector, english, japanese, textTrack)
            assertEquals("english", manual.audioId)
            assertEquals("subtitle", manual.subtitleId)
            selector.setParameters(selector.buildUponParameters().clearOverrides())
            val automatic = select(selector, english, japanese, textTrack)
            assertEquals("japanese", automatic.audioId)
            assertNull(automatic.subtitleId)
        }
    }

    @Test fun aManualSubtitleOffRemainsOffUntilAutomaticIsRestored() {
        val textTrack = subtitleGroup("subtitle", "ja")
        withSelector(source(subtitle(80, "jpn")).copy(defaultSubtitleStreamIndex = 80), configure = {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }) { selector ->
            assertNull(select(selector, textTrack).subtitleId)
            selector.setParameters(selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false))
            assertEquals("subtitle", select(selector, textTrack).subtitleId)
        }
    }

    @Test fun unsupportedOrAmbiguousActualTracksCannotBeForcedByServerDefaults() {
        val english = audioGroup("english", "en", isDefault = true)
        val japanese = audioGroup("japanese", "ja")
        val source = source(audio(1, "eng"), audio(44, "jpn")).copy(defaultAudioStreamIndex = 44)
        withSelector(source) { selector ->
            assertEquals("english", select(selector, english, japanese,
                unsupported = mapOf("japanese" to C.FORMAT_EXCEEDS_CAPABILITIES)).audioId)
            val duplicate = audioGroup("japanese-copy", "ja")
            assertEquals("english", select(selector, english, japanese, duplicate).audioId)
        }
        val conflicting = source.copy(mediaStreams = source.mediaStreams + audio(45, "jpn"))
        withSelector(conflicting) { selector ->
            assertEquals("english", select(selector, english, japanese).audioId)
        }
    }

    @Test fun serverAudioDefaultCannotBypassThePlayersAudioConstraints() {
        val english = audioGroup("english", "en", isDefault = true)
        val japanese = audioGroup("japanese", "ja", channels = 6)
        val source = source(audio(1, "eng"), audio(44, "jpn").copy(channels = 6)).copy(defaultAudioStreamIndex = 44)
        withSelector(source, configure = { setMaxAudioChannelCount(2) }) { selector ->
            assertEquals("english", select(selector, english, japanese).audioId)
        }
    }

    @Test fun forcedSubtitlesFollowTheAudioThatWasActuallySelectedFromTheServer() {
        val english = audioGroup("english", "en", isDefault = true)
        val japanese = audioGroup("japanese", "ja")
        val englishText = TrackGroup(subtitleGroup("english-forced", "en").getFormat(0)
            .buildUpon().setSelectionFlags(C.SELECTION_FLAG_FORCED).build())
        val japaneseText = TrackGroup(subtitleGroup("japanese-forced", "ja").getFormat(0)
            .buildUpon().setSelectionFlags(C.SELECTION_FLAG_FORCED).build())
        val source = source(audio(1, "eng"), audio(44, "jpn")).copy(defaultAudioStreamIndex = 44)
        withSelector(source) { selector ->
            val result = select(selector, english, japanese, englishText, japaneseText)
            assertEquals("japanese", result.audioId)
            assertEquals("japanese-forced", result.subtitleId)
        }
    }

    @Test fun missingServerDefaultsKeepMedia3DefaultsAndExplicitOffSuppressesThem() {
        val audioTrack = audioGroup("english-audio", "en", isDefault = true)
        val textTrack = subtitleGroup("english-subtitle", "en", isDefault = true)
        val source = source(audio(1, "eng"), subtitle(8, "eng"))
        withSelector(source) { selector ->
            val result = select(selector, audioTrack, textTrack)
            assertEquals("english-audio", result.audioId)
            assertEquals("english-subtitle", result.subtitleId)
        }
        withSelector(source.copy(defaultSubtitleStreamIndex = -1)) { selector ->
            assertNull(select(selector, audioTrack, textTrack).subtitleId)
        }
    }

    @Test fun externalSubtitleDefaultsUseTheirExactServerIndexEvenAfterDeliveryChangesTheirCodec() {
        val textTrack = TrackGroup(Format.Builder().setId("external-subtitle-90")
            .setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val source = source(subtitle(90, "jpn").copy(isExternal = true)).copy(defaultSubtitleStreamIndex = 90)
        withSelector(source) { selector ->
            assertEquals("external-subtitle-90", select(selector, textTrack).subtitleId)
        }
    }

    @Test fun requiredOffloadCannotBeOverriddenByServerDefaults() {
        val audioTrack = audioGroup("english", "en", isDefault = true)
        val textTrack = subtitleGroup("subtitle", "ja")
        val source = source(audio(1, "eng"), subtitle(90, "jpn"))
            .copy(defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = 90)
        withSelector(source, configure = {
            setAudioOffloadPreferences(AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED).build())
        }) { selector ->
            val result = select(selector, audioTrack, textTrack)
            assertNull(result.audioId)
            assertNull(result.subtitleId)
        }
    }

    private fun withSelector(
        source: MediaSource,
        configure: DefaultTrackSelector.Parameters.Builder.() -> Unit = {},
        verify: (PathfinTrackSelector) -> Unit,
    ) {
        val selector = PathfinTrackSelector(context, source)
        selector.setParameters(selector.buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setPreferredAudioLanguages().setPreferredTextLanguages().apply(configure))
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        try { verify(selector) } finally { selector.release() }
    }

    private fun select(
        selector: PathfinTrackSelector,
        vararg groups: TrackGroup,
        unsupported: Map<String, Int> = emptyMap(),
    ): TrackSelectorResult {
        selector.onParametersActivated(selector.parameters)
        val renderers = arrayOf<RendererCapabilities>(
            TestRenderer(C.TRACK_TYPE_AUDIO, unsupported), TestRenderer(C.TRACK_TYPE_TEXT, unsupported),
        )
        return selector.selectTracks(renderers, TrackGroupArray(*groups), MediaPeriodId("test-period"), Timeline.EMPTY)
    }

    private class TestRenderer(private val type: Int, private val unsupported: Map<String, Int>) : RendererCapabilities {
        override fun getName() = "TestRenderer-$type"
        override fun getTrackType() = type
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            if (MimeTypes.getTrackType(format.sampleMimeType) != type) C.FORMAT_UNSUPPORTED_TYPE
            else unsupported[format.id] ?: C.FORMAT_HANDLED,
        )
    }

    private val TrackSelectorResult.audioId get() = selections[0]?.selectedFormat?.id
    private val TrackSelectorResult.subtitleId get() = selections[1]?.selectedFormat?.id
    private fun source(vararg streams: MediaStream) = MediaSource("source", mediaStreams = streams.toList())
    private fun audio(index: Int, language: String) = MediaStream(index = index, type = "Audio", language = language,
        codec = "aac", channels = 2, sampleRate = 48_000)
    private fun subtitle(index: Int, language: String) = MediaStream(index = index, type = "Subtitle", language = language, codec = "subrip")
    private fun audioGroup(id: String, language: String, isDefault: Boolean = false, channels: Int = 2) = TrackGroup(
        Format.Builder().setId(id).setLanguage(language).setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(channels).setSampleRate(48_000)
            .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0).build(),
    )
    private fun subtitleGroup(id: String, language: String, isDefault: Boolean = false) = TrackGroup(
        Format.Builder().setId(id).setLanguage(language).setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0).build(),
    )
}

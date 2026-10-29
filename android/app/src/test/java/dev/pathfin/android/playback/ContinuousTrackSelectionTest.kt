package dev.pathfin.android.playback

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import org.junit.Assert.*
import org.junit.Test

class ContinuousTrackSelectionTest {
    @Test fun stableMetadataMatchesAfterOrderChangesAndDoesNotContainOldTrackCoordinates() {
        val english = identity("eng", "English main")
        val japanese = identity("jpn", "Japanese main")
        assertEquals(1, uniqueRememberedTrackIndex(japanese, listOf(english, japanese)))
        assertEquals(0, uniqueRememberedTrackIndex(japanese, listOf(japanese, english)))
        assertNull(uniqueRememberedTrackIndex(japanese, listOf(english)))
    }

    @Test fun duplicateDescriptionsAreAmbiguousAndUnidentifiedTracksAreNeverGuessed() {
        val japanese = identity("ja", "Japanese main")
        assertNull(uniqueRememberedTrackIndex(japanese, listOf(japanese, japanese)))
        assertNull(StableTrackIdentity.create(null, null, 0, false))
        assertNull(StableTrackIdentity.create("und", "  ", 0, false))
        assertNull(StableTrackIdentity.create("a".repeat(65), "x".repeat(513), 0, false))
        assertEquals(0, uniqueRememberedTrackIndex(japanese, listOf(japanese, null)))
    }

    @Test fun languageAliasesWhitespaceAndCaseAreStableButRolesForcedAndLabelsRemainDistinct() {
        val original = identity("en-US", "  English   SDH ", role = 64)
        assertEquals(original, identity("eng_US", "english sdh", role = 64))
        assertNotEquals(original, identity("en-GB", "english sdh", role = 64))
        assertNotEquals(original, identity("en-US", "english commentary", role = 64))
        assertNotEquals(original, identity("en-US", "english sdh", role = 0))
        assertNotEquals(original, identity("en-US", "english sdh", role = 64, forced = true))
        assertNotEquals(identity("zh-Hans", null), identity("zh-Hant", null))
    }

    @Test fun explicitAutomaticSelectionClearsOnlyItsTypeAndSubtitleOffIsRememberedSeparately() {
        val audio = RememberedTrack.Selected(identity("ja", "Japanese main"))
        val subtitle = RememberedTrack.Selected(identity("es", "Spanish captions"))
        val selected = ManualTrackSelections().with(RememberedTrackType.AUDIO, audio)
            .with(RememberedTrackType.SUBTITLE, subtitle)
        val automaticAudio = selected.with(RememberedTrackType.AUDIO, null)
        assertNull(automaticAudio.audio)
        assertEquals(subtitle, automaticAudio.subtitle)
        val disabled = selected.with(RememberedTrackType.SUBTITLE, RememberedTrack.Disabled)
        assertEquals(audio, disabled.audio)
        assertEquals(RememberedTrack.Disabled, disabled.subtitle)
        assertNull(disabled.with(RememberedTrackType.SUBTITLE, null).subtitle)
        assertNull(selected.with(RememberedTrackType.AUDIO, RememberedTrack.Disabled).audio)
    }

    @Test fun continuityRequiresAnEnabledDistinctEpisodeInTheSameIdentifiedSeries() {
        val first = request("episode-1")
        val next = request("episode-2")
        assertTrue(canInheritManualTracks(first, next, true))
        assertFalse(canInheritManualTracks(first, next, false))
        assertFalse(canInheritManualTracks(first, first, true))
        assertFalse(canInheritManualTracks(first, next.copy(item = next.item.copy(type = "Movie")), true))
        assertFalse(canInheritManualTracks(first, next.copy(item = next.item.copy(seriesId = "other-series")), true))
        assertFalse(canInheritManualTracks(first.copy(item = first.item.copy(seriesId = null)), next, true))
        assertFalse(canInheritManualTracks(first.copy(item = first.item.copy(seriesId = "")), next, true))
    }

    @Test fun accountProfileServerIdentityAndBasePathBoundariesDoNotLeakSelections() {
        val first = request("episode-1")
        val next = request("episode-2")
        assertFalse(canInheritManualTracks(first, next.copy(profileId = "another-profile"), true))
        assertFalse(canInheritManualTracks(first, next.copy(profileId = null), true))
        assertFalse(canInheritManualTracks(first, next.copy(session = next.session.copy(userId = "another-user")), true))
        assertFalse(canInheritManualTracks(first, next.copy(session = next.session.copy(serverId = "another-server")), true))
        assertFalse(canInheritManualTracks(first, next.copy(session = next.session.copy(server = ServerConfig("https://other.example/jellyfin"))), true))
        assertFalse(canInheritManualTracks(first, next.copy(session = next.session.copy(server = ServerConfig("https://media.example/other-base"))), true))
        assertTrue(canInheritManualTracks(first, next.copy(session = next.session.copy(accessToken = "refreshed-synthetic-token",
            server = ServerConfig("HTTPS://MEDIA.EXAMPLE:443/jellyfin/"))), true))
    }

    private fun identity(language: String?, label: String?, role: Int = 0, forced: Boolean = false) =
        checkNotNull(StableTrackIdentity.create(language, label, role, forced))

    private fun request(id: String): PlaybackRequest {
        val session = Session(ServerConfig("https://media.example/jellyfin"), "synthetic-token", "synthetic-user", "Synthetic", "synthetic-device", "synthetic-server")
        val source = MediaSource("source", path = "https://media.example/jellyfin/stream.mkv")
        return PlaybackRequest(MediaItem(id, id, "Episode", seriesId = "synthetic-series"), PlaybackInfo(listOf(source), "synthetic-play-$id"),
            source, ResolvedPlayback(checkNotNull(source.path), emptyMap(), PlayMethod.DIRECT_PLAY, source.id, true),
            0, session, profileId = "synthetic-profile")
    }
}

package dev.pathfin.android.data

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.subtitlePreferenceKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersistentPlaybackPreferencesTest {
    private val key = request().subtitlePreferenceKey()

    @Test fun ratioSubtitleAndDownloadChoicesSurviveSerializationIndependently() {
        for (ratio in listOf("FIT", "FILL", "ZOOM")) {
            for (network in DownloadNetwork.entries) {
                val preferences = AppPreferences(videoResizeMode = ratio, subtitleScale = 1.35f,
                    subtitlePositionPercent = 73, subtitleDelays = mapOf(key to -2_350L),
                    downloadNetwork = network,
                    downloadDirectoryUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
                    playbackSpeed = 1.75f, playerBrightness = .37f,
                    playerOrientation = dev.pathfin.android.domain.PlayerOrientation.PORTRAIT_SENSOR, rememberBrightness = false,
                    liveTvEnabled = false, liveTvDirectPlay = true)
                val encoded = AppPreferencesStore.encode(preferences)
                val restored = AppPreferencesStore.decode(JSONObject(encoded.toString()))
                assertEquals(preferences, restored)
                assertFalse(encoded.toString().contains("fixture-token"))
                assertFalse(encoded.toString().contains("fixture-password"))
            }
        }
    }

    @Test fun playerOrientationBrightnessAndLiveTvDefaultsAndChoicesPersist() {
        val defaults = AppPreferencesStore.decode(JSONObject())
        assertEquals("默认跟随传感器", dev.pathfin.android.domain.PlayerOrientation.SENSOR, defaults.playerOrientation)
        assertTrue("默认记忆亮度", defaults.rememberBrightness)
        assertFalse("默认关闭直播电视", defaults.liveTvEnabled)
        assertFalse("默认直播仍走服务器", defaults.liveTvDirectPlay)
        for (orientation in dev.pathfin.android.domain.PlayerOrientation.entries) {
            val encoded = AppPreferencesStore.encode(defaults.copy(playerOrientation = orientation))
            assertEquals(orientation, AppPreferencesStore.decode(JSONObject(encoded.toString())).playerOrientation)
        }
        assertEquals(dev.pathfin.android.domain.PlayerOrientation.SENSOR,
            AppPreferencesStore.decode(JSONObject().put("playerOrientation", "UPSIDE_DOWN")).playerOrientation)
    }

    @Test fun absentOrUnknownChoicesUseUsefulDefaults() {
        val defaults = AppPreferencesStore.decode(JSONObject())
        assertEquals("FIT", defaults.videoResizeMode)
        assertEquals(1f, defaults.subtitleScale, 0f)
        assertEquals(100, defaults.subtitlePositionPercent)
        assertTrue(defaults.subtitleDelays.isEmpty())
        assertEquals(DownloadNetwork.ANY, defaults.downloadNetwork)
        assertNull(defaults.downloadDirectoryUri)
        val unknown = AppPreferencesStore.decode(JSONObject().put("videoResizeMode", "UNKNOWN")
            .put("downloadNetwork", "UNKNOWN"))
        assertEquals("FIT", unknown.videoResizeMode)
        assertEquals(DownloadNetwork.ANY, unknown.downloadNetwork)
    }

    @Test fun subtitlePresentationIsBoundedDuringBothWriteAndImport() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "not-a-number")) {
            val imported = AppPreferencesStore.decode(JSONObject().put("subtitleScale", bad))
            assertEquals(bad, 1f, imported.subtitleScale, 0f)
        }
        val high = AppPreferencesStore.decode(JSONObject().put("subtitleScale", 100)
            .put("subtitlePositionPercent", Int.MAX_VALUE))
        assertEquals(2f, high.subtitleScale, 0f)
        assertEquals(100, high.subtitlePositionPercent)
        val low = AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(videoResizeMode = "INVALID",
            subtitleScale = .01f, subtitlePositionPercent = Int.MIN_VALUE)))
        assertEquals("FIT", low.videoResizeMode)
        assertEquals(.5f, low.subtitleScale, 0f)
        assertEquals(0, low.subtitlePositionPercent)
        val nonFinite = AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(subtitleScale = Float.NaN)))
        assertEquals(1f, nonFinite.subtitleScale, 0f)
    }

    @Test fun delayMapDropsInvalidKeysAndZeroOffsetsAndBoundsValuesAndStoredCount() {
        val otherKey = request().copy(source = request().source.copy(id = "other-version")).subtitlePreferenceKey()
        val invalidKey = "https://media.example/fixture-film?api_key=fixture-token"
        val values = linkedMapOf(key to Long.MAX_VALUE, otherKey to Long.MIN_VALUE,
            "a".repeat(64) to 0L, "A".repeat(64) to 1L, invalidKey to 2L)
        val restored = AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(subtitleDelays = values)))
        assertEquals(mapOf(key to 60_000L, otherKey to -60_000L), restored.subtitleDelays)
        val imported = AppPreferencesStore.decode(JSONObject().put("subtitleDelays", JSONObject(values)))
        assertEquals(restored.subtitleDelays, imported.subtitleDelays)
        val many = (1..300).associate { it.toString(16).padStart(64, '0') to it.toLong() }
        val capped = AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(subtitleDelays = many)))
        assertEquals(256, capped.subtitleDelays.size)
        assertTrue(capped.subtitleDelays.keys.all { it in many })
        assertTrue(capped.subtitleDelays.values.all { it in 1L..300L })
        val importedMany = AppPreferencesStore.decode(JSONObject().put("subtitleDelays", JSONObject(many)))
        assertEquals(256, importedMany.subtitleDelays.size)
    }

    @Test fun downloadDestinationAcceptsPersistedDocumentUrisAndRejectsOtherSchemesAndOversizedValues() {
        val valid = "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FPathfin"
        assertEquals(valid, AppPreferencesStore.decode(JSONObject().put("downloadDirectoryUri", valid)).downloadDirectoryUri)
        for (invalid in listOf("", "file:///storage/emulated/0/Movies", "https://media.example/private",
                "/storage/emulated/0/Movies", "content://" + "a".repeat(4096))) {
            assertNull(invalid, AppPreferencesStore.decode(JSONObject().put("downloadDirectoryUri", invalid)).downloadDirectoryUri)
            assertNull(invalid, AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(downloadDirectoryUri = invalid))).downloadDirectoryUri)
        }
        assertNull(AppPreferencesStore.decode(JSONObject().put("downloadDirectoryUri", JSONObject.NULL)).downloadDirectoryUri)
    }

    @Test fun subtitleDelayIdentityIsolatesServerAccountItemAndVersionWithoutDependingOnTokenOrPlayer() {
        val base = request()
        val isolated = listOf(base.copy(session = base.session.copy(serverId = "another-server")),
            base.copy(session = base.session.copy(userId = "another-account")),
            base.copy(item = base.item.copy(id = "another-film")),
            base.copy(source = base.source.copy(id = "another-version")))
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertEquals(isolated.size + 1, (isolated.map { it.subtitlePreferenceKey() } + key).toSet().size)
        assertEquals(key, base.copy(startPositionMs = 123_456, quality = PlaybackQuality.DATA_SAVER,
            session = base.session.copy(accessToken = "renewed-token", deviceId = "different-device", userName = "Renamed"))
            .subtitlePreferenceKey())
        assertEquals(key, base.copy(session = base.session.copy(server = ServerConfig("https://public.example/jellyfin")))
            .subtitlePreferenceKey())
    }

    @Test fun missingServerIdsUseTheServerAddressAndLengthPrefixedPartsAvoidAmbiguousKeys() {
        val base = request().let { it.copy(session = it.session.copy(serverId = "")) }
        assertNotEquals(base.subtitlePreferenceKey(), base.copy(session = base.session.copy(
            server = ServerConfig("https://other.example/jellyfin"))).subtitlePreferenceKey())
        val first = request().let { it.copy(session = it.session.copy(serverId = "ab", userId = "c")) }
        val second = first.copy(session = first.session.copy(serverId = "a", userId = "bc"))
        assertNotEquals(first.subtitlePreferenceKey(), second.subtitlePreferenceKey())
    }

    private fun request(): PlaybackRequest {
        val session = Session(ServerConfig("https://media.example/jellyfin"), "fixture-token",
            "fixture-user", "Fixture", "fixture-device", serverId = "fixture-server")
        val source = MediaSource("fixture-version")
        return PlaybackRequest(MediaItem("fixture-film", "Fixture film", "Movie"), PlaybackInfo(listOf(source), "play"),
            source, ResolvedPlayback("https://media.example/jellyfin/Videos/fixture-film/stream", emptyMap(),
                PlayMethod.DIRECT_PLAY, source.id, true), 0, session)
    }
}

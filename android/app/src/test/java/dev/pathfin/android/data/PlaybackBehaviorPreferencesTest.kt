package dev.pathfin.android.data

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackTuning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackBehaviorPreferencesTest {
    @Test fun oldSettingsKeepOriginalAudioNoOutroExactSeekAndNoSpeedDisplay() {
        val value = AppPreferencesStore.decode(JSONObject())
        assertEquals(100, value.audioGainPercent)
        assertEquals(0, value.outroSkipSeconds)
        assertTrue(value.exactSeek)
        assertFalse(value.showBufferSpeed)
        assertFalse(value.blurAdultCovers)
        assertFalse(value.hideSystemStatusBar)
        assertTrue(value.pullToRefreshEnabled)
        assertEquals(AppPreferences(), value)
    }

    @Test fun allPreferencesRoundTripWithoutChangingIndependentPlaybackChoices() {
        val value = AppPreferences(audioGainPercent = 175, outroSkipSeconds = 90, exactSeek = false, showBufferSpeed = true,
            autoPlayNextEpisode = true, blurAdultCovers = true,
            hideSystemStatusBar = true, pullToRefreshEnabled = false)
        val restored = AppPreferencesStore.decode(AppPreferencesStore.encode(value))
        assertEquals(value, restored)
        val tuning = PlaybackTuning.from(restored)
        assertEquals(175, tuning.audioGainPercent)
        assertEquals(90_000L, tuning.outroSkipMs)
        assertFalse(tuning.exactSeek)
        assertTrue(tuning.showBufferSpeed)
        assertTrue(restored.autoPlayNextEpisode)
    }

    @Test fun importExportAndPlaybackSnapshotsClampUntrustedNumbers() {
        val high = AppPreferencesStore.decode(JSONObject().put("audioGainPercent", Int.MAX_VALUE).put("outroSkipSeconds", Int.MAX_VALUE)
            .put("hideSystemStatusBar", true).put("blurAdultCovers", true))
        assertEquals(200, high.audioGainPercent)
        assertEquals(180, high.outroSkipSeconds)
        assertTrue(high.hideSystemStatusBar)
        assertTrue(high.blurAdultCovers)
        assertTrue(high.pullToRefreshEnabled)
        val low = AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(audioGainPercent = -1, outroSkipSeconds = -1)))
        assertEquals(0, low.audioGainPercent)
        assertEquals(0, low.outroSkipSeconds)
        val tuning = PlaybackTuning.from(AppPreferences(audioGainPercent = 999, outroSkipSeconds = 999))
        assertEquals(200, tuning.audioGainPercent)
        assertEquals(180_000L, tuning.outroSkipMs)
    }

    @Test fun outroUsesReliablePlayingDurationAndCannotFireTwiceOrSkipAShortClip() {
        val tuning = PlaybackTuning.from(AppPreferences(outroSkipSeconds = 30))
        assertNull(tuning.outroEndPositionMs(false, 90_000, 120_000, false))
        assertNull(tuning.outroEndPositionMs(true, 89_999, 120_000, false))
        assertEquals(120_000L, tuning.outroEndPositionMs(true, 90_000, 120_000, false))
        assertNull(tuning.outroEndPositionMs(true, 90_000, 120_000, true))
        assertNull(tuning.outroEndPositionMs(true, 0, 0, false))
        assertNull(tuning.outroEndPositionMs(true, 0, -1, false))
        assertNull(tuning.outroEndPositionMs(true, 0, 30_000, false))
        assertNull(tuning.outroEndPositionMs(true, 0, 20_000, false))
        assertNull(tuning.outroEndPositionMs(true, -1, 120_000, false))
        assertNull(tuning.outroEndPositionMs(true, 120_000, 120_000, false))
        assertNull(PlaybackTuning.from(AppPreferences()).outroEndPositionMs(true, 119_000, 120_000, false))
    }
}

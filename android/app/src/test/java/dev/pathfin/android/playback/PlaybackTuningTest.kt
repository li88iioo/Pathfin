package dev.pathfin.android.playback

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.TimeUnits
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackTuningTest {
    private val previousLocale = Locale.getDefault()
    @Before fun useChineseLabels() = Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    @After fun restoreLocale() = Locale.setDefault(previousLocale)

    @Test fun runtimeBoundsKeepMedia3BufferAndSeekParametersValid() {
        val low = PlaybackTuning.from(AppPreferences(
            seekSeconds = Int.MIN_VALUE, introSkipSeconds = Int.MIN_VALUE, longPressSpeed = Float.NaN,
        ))
        assertEquals("默认自动档 60 秒", 60_000, low.maxBufferMs)
        assertEquals(15_000, low.minBufferMs)
        assertEquals(64 * 1_024 * 1_024, low.targetBufferBytes)
        assertEquals(5_000L, low.seekIncrementMs)
        assertEquals(0L, low.introSkipMs)
        assertEquals(2.5f, low.longPressSpeed, 0f)

        val high = PlaybackTuning.from(AppPreferences(
            bufferPreset = dev.pathfin.android.domain.BufferPreset.HUGE,
            seekSeconds = Int.MAX_VALUE, introSkipSeconds = Int.MAX_VALUE, longPressSpeed = 10f,
        ))
        assertEquals(180_000, high.maxBufferMs)
        assertTrue(high.minBufferMs <= high.maxBufferMs)
        assertTrue(high.minBufferMs >= 5_000)
        assertEquals(256 * 1_024 * 1_024, high.targetBufferBytes)
        assertEquals(60_000L, high.seekIncrementMs)
        assertEquals(180_000L, high.introSkipMs)
        assertEquals(4f, high.longPressSpeed, 0f)
    }

    @Test fun introSkipOnlyAppliesWithoutAResumePosition() {
        val tuning = PlaybackTuning.from(AppPreferences(introSkipSeconds = 90))
        val duration = TimeUnits.msToTicks(180_000)
        assertEquals(90_000L, tuning.initialPositionMs(0, duration))
        assertEquals(90_000L, tuning.initialPositionMs(-1, duration))
        assertEquals(1L, tuning.initialPositionMs(1, duration))
        assertEquals(120_000L, tuning.initialPositionMs(120_000, duration))
    }

    @Test fun introSkipCannotImmediatelyCompleteShortMedia() {
        val tuning = PlaybackTuning.from(AppPreferences(introSkipSeconds = 90))
        assertEquals(0L, tuning.initialPositionMs(0, TimeUnits.msToTicks(45_000)))
        assertEquals(0L, tuning.initialPositionMs(0, TimeUnits.msToTicks(90_000)))
        assertEquals(90_000L, tuning.initialPositionMs(0, 0))
        assertEquals(0L, PlaybackTuning.from(AppPreferences()).initialPositionMs(-1, 0))
    }

    @Test fun preferencesSnapshotPreservesPlaybackPolicyAndLanguageSelection() {
        val tuning = PlaybackTuning.from(AppPreferences(
            originalPathEnabled = false, longPressSpeedEnabled = false, longPressSpeed = 3f,
            preferredAudioLanguage = " ja ", preferredSubtitleLanguage = "zh_Hans", subtitlesEnabled = false,
        ))
        assertFalse(tuning.preferOriginal)
        assertFalse(tuning.longPressSpeedEnabled)
        assertEquals(3f, tuning.longPressSpeed, 0f)
        assertEquals("ja", tuning.audioLanguage)
        assertEquals("zh-Hans", tuning.subtitleLanguage)
        assertFalse(tuning.subtitlesEnabled)
        val invalid = PlaybackTuning.from(AppPreferences(preferredAudioLanguage = "\r\n", preferredSubtitleLanguage = "zh;en"))
        assertNull(invalid.audioLanguage)
        assertNull(invalid.subtitleLanguage)
    }

    @Test fun sourceLabelSeparatesStrmLiveDirectAndJellyfinDirectStreamTranscode() {
        val channel = dev.pathfin.android.domain.MediaItem("c", "CCTV-1", "TvChannel")
        val movie = dev.pathfin.android.domain.MediaItem("m", "Movie", "Movie")
        val play = dev.pathfin.android.domain.PlayMethod.DIRECT_PLAY
        val stream = dev.pathfin.android.domain.PlayMethod.DIRECT_STREAM
        val transcode = dev.pathfin.android.domain.PlayMethod.TRANSCODE
        assertEquals("STRM 原始地址", playbackSourceLabel(movie, trustedServer = false, play))
        assertEquals("直播源直连", playbackSourceLabel(channel, trustedServer = false, play))
        assertEquals("Jellyfin · 直接播放", playbackSourceLabel(movie, trustedServer = true, play))
        assertEquals("Jellyfin · 直接串流（不转码）", playbackSourceLabel(movie, trustedServer = true, stream))
        assertEquals("Jellyfin · 转码", playbackSourceLabel(movie, trustedServer = true, transcode))
        assertEquals("Jellyfin 直播 · 转码", playbackSourceLabel(channel, trustedServer = true, transcode))
        assertEquals("Jellyfin 直播 · 直接串流（不转码）", playbackSourceLabel(channel, trustedServer = true, stream))
    }
}

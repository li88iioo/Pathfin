package dev.pathfin.android.playback

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.IntroSkipHistory
import dev.pathfin.android.domain.MediaSegment
import dev.pathfin.android.domain.MediaSegmentType
import dev.pathfin.android.domain.nextEpisodePromptRemainingSeconds
import org.junit.Assert.*
import org.junit.Test

class PlaybackPromptPolicyTest {
    @Test fun serverBoundariesTakePriorityAndUseAnExclusiveEnd() {
        val history = IntroSkipHistory()
        val segments = listOf(segment(10_000, 30_000))
        assertNull(history.activeIntro(segments, 9_999, 120_000, 60_000))
        assertEquals(30_000L, history.activeIntro(segments, 10_000, 120_000, 60_000)?.endMs)
        assertTrue(history.activeIntro(segments, 29_999, 120_000, 60_000)?.fromServer == true)
        assertNull(history.activeIntro(segments, 30_000, 120_000, 60_000))
    }

    @Test fun pendingServerRequestCannotBePreemptedByAClientFallback() {
        val history = IntroSkipHistory()
        assertNull(history.activeIntro(emptyList(), 1_000, 120_000, 60_000, serverPending = true))
        val fallback = history.activeIntro(emptyList(), 1_000, 120_000, 60_000)
        assertNotNull(fallback)
        assertFalse(checkNotNull(fallback).fromServer)
        assertEquals(60_000L, fallback.endMs)
        assertNull(history.activeIntro(emptyList(), 1_000, 120_000, 0))
    }

    @Test fun consumedOrDismissedSegmentDoesNotReappearOnRewindButNewPlaybackCanUseIt() {
        val history = IntroSkipHistory()
        val values = listOf(segment(0, 20_000))
        val prompt = checkNotNull(history.activeIntro(values, 1_000, 120_000, 0))
        assertTrue(history.consume(prompt))
        assertFalse(history.consume(prompt))
        assertNull(history.activeIntro(values, 1_000, 120_000, 0))
        assertNotNull(IntroSkipHistory().activeIntro(values, 1_000, 120_000, 0))
    }

    @Test fun unknownShortOrWholeMediaRangesCannotCompleteTheVideo() {
        val history = IntroSkipHistory()
        assertNull(history.activeIntro(listOf(segment(0, 20_000)), 1_000, 0, 0))
        assertNull(history.activeIntro(listOf(segment(0, 20_000)), 1_000, -9223372036854775807L, 0))
        assertNull(history.activeIntro(listOf(segment(0, 20_000)), 1_000, 20_000, 0))
        assertNull(history.activeIntro(listOf(segment(0, 20_000)), 1_000, 19_000, 0))
        assertNull(history.activeIntro(emptyList(), 1_000, 20_000, 20_000))
        assertNull(history.activeIntro(listOf(segment(0, 20_000).copy(type = MediaSegmentType.OUTRO)), 1_000, 120_000, 0))
    }

    @Test fun nextPromptRequiresARealNeighborKnownDurationAndEnabledThreshold() {
        assertNull(nextEpisodePromptRemainingSeconds(89_999, 120_000, 30, true))
        assertEquals(30, nextEpisodePromptRemainingSeconds(90_000, 120_000, 30, true))
        assertEquals(30, nextEpisodePromptRemainingSeconds(90_001, 120_000, 30, true))
        assertEquals(1, nextEpisodePromptRemainingSeconds(119_999, 120_000, 30, true))
        assertNull(nextEpisodePromptRemainingSeconds(120_000, 120_000, 30, true))
        assertNull(nextEpisodePromptRemainingSeconds(119_000, 120_000, 0, true))
        assertNull(nextEpisodePromptRemainingSeconds(119_000, 120_000, 30, false))
        assertNull(nextEpisodePromptRemainingSeconds(119_000, 120_000, 30, true, isLive = true))
        assertNull(nextEpisodePromptRemainingSeconds(0, 0, 30, true))
    }

    @Test fun longPressMultipliesTheActualBaseAndKeepsTheFourTimesCeiling() {
        assertEquals(3.75f, checkNotNull(temporaryPlaybackSpeed(1.5f, 2.5f)), 0f)
        assertEquals(4f, checkNotNull(temporaryPlaybackSpeed(2f, 2.5f)), 0f)
        assertEquals(.625f, checkNotNull(temporaryPlaybackSpeed(.25f, 2.5f)), 0f)
        assertNull(temporaryPlaybackSpeed(Float.NaN, 2f))
        assertNull(temporaryPlaybackSpeed(1f, Float.POSITIVE_INFINITY))
        assertNull(temporaryPlaybackSpeed(0f, 2f))
    }

    @Test fun rapidPreviewSeekUsesAMonotonicWindowAndResetsForANewDrag() {
        val throttle = RapidSeekThrottle()
        assertTrue(throttle.shouldSeek(1_000, 1_000))
        assertFalse(throttle.shouldSeek(2_000, 1_149))
        assertTrue(throttle.shouldSeek(3_000, 1_150))
        assertFalse(throttle.shouldSeek(3_000, 1_400))
        assertTrue(throttle.shouldSeek(4_000, 1_400))
        throttle.reset()
        assertTrue(throttle.shouldSeek(5_000, 1_401))
    }

    @Test fun promptAndInteractionSettingsKeepSafeDefaultsAndClamps() {
        val defaults = PlaybackTuning.from(AppPreferences())
        assertTrue(defaults.serverIntroSkipEnabled)
        assertFalse(defaults.autoSkipIntro)
        assertEquals(0L, defaults.introPromptMs)
        assertEquals(30, defaults.nextEpisodePromptSeconds)
        assertTrue(defaults.longPressSlideSpeedEnabled)
        assertFalse(defaults.rapidSeekWhileDragging)
        assertTrue(defaults.exoAssEnabled)
        assertFalse(defaults.keepTracksForContinuousPlayback)
        assertEquals("AUTO", defaults.exoAudioDecoder)
        assertTrue(defaults.preferHardwareVideoDecoder)
        val bounded = PlaybackTuning.from(AppPreferences(introPromptSeconds = Int.MAX_VALUE, nextEpisodePromptSeconds = Int.MIN_VALUE,
            longPressSlideSpeedEnabled = false, rapidSeekWhileDragging = true))
        assertEquals(180_000L, bounded.introPromptMs)
        assertEquals(0, bounded.nextEpisodePromptSeconds)
        assertFalse(bounded.longPressSlideSpeedEnabled)
        assertTrue(bounded.rapidSeekWhileDragging)
        assertFalse(PlaybackTuning.from(AppPreferences(exoAssEnabled = false)).exoAssEnabled)
        assertTrue(PlaybackTuning.from(AppPreferences(keepTracksForContinuousPlayback = true)).keepTracksForContinuousPlayback)
        val codecs = PlaybackTuning.from(AppPreferences(exoAudioDecoder = "SOFTWARE", preferHardwareVideoDecoder = false))
        assertEquals("SOFTWARE", codecs.exoAudioDecoder)
        assertFalse(codecs.preferHardwareVideoDecoder)
        assertEquals("AUTO", PlaybackTuning.from(AppPreferences(exoAudioDecoder = "UNKNOWN")).exoAudioDecoder)
    }

    private fun segment(startMs: Long, endMs: Long) = MediaSegment("11111111111111111111111111111111", "22222222222222222222222222222222",
        MediaSegmentType.INTRO, startMs * 10_000, endMs * 10_000)
}

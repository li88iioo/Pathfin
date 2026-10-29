package dev.pathfin.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionTrackerTest {
    @Test fun failedPreparationDoesNotReportPlayback() {
        val tracker = PlaybackSessionTracker()
        assertNull(tracker.progress(12_000, true))
        assertNull(tracker.stopped(12_000, true))
        assertNull(tracker.started(12_000))
    }

    @Test fun startsAndStopsOnlyOnceAcrossPauseRetryAndExit() {
        val tracker = PlaybackSessionTracker()
        assertEquals(PlaybackReport("start", 20_000, false), tracker.started(20_000))
        assertNull(tracker.started(20_500))
        assertEquals(PlaybackReport("progress", 28_000, true), tracker.progress(28_000, true))
        assertNull(tracker.started(28_000))
        assertEquals(PlaybackReport("stop", 30_000, false), tracker.stopped(30_000, false))
        assertNull(tracker.stopped(31_000, true))
        assertNull(tracker.progress(31_000, true))
    }

    @Test fun separateSessionsDoNotShareState() {
        val old = PlaybackSessionTracker()
        val current = PlaybackSessionTracker()
        old.started(100)
        old.stopped(1000, true)
        assertEquals("start", current.started(2000)?.event)
        assertEquals("stop", current.stopped(5000, true)?.event)
    }

    @Test fun invalidNegativePositionsNeverReachServer() {
        val tracker = PlaybackSessionTracker()
        assertEquals(0L, tracker.started(-1)?.positionMs)
        assertEquals(0L, tracker.progress(Long.MIN_VALUE, true)?.positionMs)
        assertEquals(0L, tracker.stopped(-200, true)?.positionMs)
    }
}

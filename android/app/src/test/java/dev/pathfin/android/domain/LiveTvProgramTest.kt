package dev.pathfin.android.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LiveTvProgramTest {
    private val start = Instant.parse("2026-10-24T00:00:00Z")
    private val program = LiveTvProgram("p", "channel", "Programme", start = start, end = start.plusSeconds(3600))

    @Test fun airingHasInclusiveStartExclusiveEndAndRealClampedProgress() {
        assertFalse(program.isAiring(start.minusNanos(1)))
        assertTrue(program.isAiring(start))
        assertTrue(program.isAiring(start.plusSeconds(3599)))
        assertFalse(program.isAiring(start.plusSeconds(3600)))
        assertEquals(0f, program.progress(start.minusSeconds(60))!!, 0f)
        assertEquals(.5f, program.progress(start.plusSeconds(1800))!!, .00001f)
        assertEquals(1f, program.progress(start.plusSeconds(7200))!!, 0f)
    }

    @Test fun missingOrReversedTimesDoNotInventAiringOrProgress() {
        for (invalid in listOf(program.copy(start = null), program.copy(end = null), program.copy(end = start), program.copy(end = start.minusSeconds(1)))) {
            assertFalse(invalid.isAiring(start))
            assertNull(invalid.progress(start))
        }
    }

    @Test fun extremeValidInstantCannotOverflowProgress() {
        val longProgram = program.copy(start = Instant.MIN, end = Instant.MAX)
        val progress = longProgram.progress(Instant.EPOCH)!!
        assertTrue(progress.isFinite())
        assertTrue(progress in 0f..1f)
    }
}

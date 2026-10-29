package dev.pathfin.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUnitsTest {
    @Test fun convertsTicksWithout32BitOverflow() {
        assertEquals(90_000_000_000L, TimeUnits.msToTicks(9_000_000L))
        assertEquals(9_000_000L, TimeUnits.ticksToMs(90_000_000_000L))
    }

    @Test fun negativeTimesAreZeroAndOverflowIsSaturated() {
        assertEquals(0L, TimeUnits.msToTicks(-1))
        assertEquals(0L, TimeUnits.ticksToMs(-1))
        assertEquals(Long.MAX_VALUE / 10_000L * 10_000L, TimeUnits.msToTicks(Long.MAX_VALUE))
    }
}

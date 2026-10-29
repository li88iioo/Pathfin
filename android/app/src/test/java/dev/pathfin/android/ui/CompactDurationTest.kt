package dev.pathfin.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactDurationTest {
    private fun minutes(value: Long) = value * 60_000

    @Test fun hoursAndMinutesAreShownWithoutSeconds() {
        assertEquals("1 小时 12 分", compactDuration(minutes(72) + 59_000))
        assertEquals("2 小时", compactDuration(minutes(120)))
        assertEquals("10 小时 5 分", compactDuration(minutes(605)))
    }

    @Test fun shortDurationsRoundDownToWholeMinutes() {
        assertEquals("46 分", compactDuration(minutes(46) + 30_000))
        assertEquals("1 分", compactDuration(minutes(1)))
        assertEquals("59 分", compactDuration(minutes(60) - 1))
    }

    @Test fun underOneMinuteAndInvalidValuesStayReadable() {
        assertEquals("不到 1 分", compactDuration(59_999))
        assertEquals("不到 1 分", compactDuration(0))
        assertEquals("不到 1 分", compactDuration(-5_000))
    }
}

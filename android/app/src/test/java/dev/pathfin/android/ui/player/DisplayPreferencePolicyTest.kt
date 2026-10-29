package dev.pathfin.android.ui.player

import org.junit.Assert.*
import org.junit.Test

class DisplayPreferencePolicyTest {
    private val current = DisplayModeSpec(1, 1080, 2400, 60f)

    @Test fun highestModeKeepsPhysicalResolutionInsteadOfChoosingAnotherResolutionOrRotatingIt() {
        val expected = current.copy(id = 3, refreshRate = 120f)
        val modes = listOf(current, current.copy(id = 2, refreshRate = 90f), expected,
            current.copy(id = 4, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 144f),
            current.copy(id = 5, physicalWidth = 2400, physicalHeight = 1080, refreshRate = 165f))
        assertEquals(expected, highestRefreshRateMode(current, modes))
    }

    @Test fun singleSixtyHertzModeStaysAtTheRealDeviceCapability() {
        assertEquals(current, highestRefreshRateMode(current, listOf(current)))
    }

    @Test fun equalRatesPreferTheCurrentModeAndRemainDeterministicWithoutIt() {
        val high = current.copy(id = 9, refreshRate = 119.88f)
        val sameRate = listOf(high.copy(id = 5), high, high.copy(id = 3))
        assertEquals(high, highestRefreshRateMode(high, sameRate))
        assertEquals(high.copy(id = 3), highestRefreshRateMode(current, sameRate.reversed()))
        assertEquals(high.copy(id = 3), highestRefreshRateMode(current, sameRate))
    }

    @Test fun malformedAndLowerRateModesDoNotCauseAnUnsupportedOrLowerRequest() {
        val invalid = listOf(current.copy(id = 0, refreshRate = 240f),
            current.copy(id = 2, refreshRate = Float.NaN), current.copy(id = 3, refreshRate = Float.POSITIVE_INFINITY),
            current.copy(id = 4, refreshRate = 0f), current.copy(id = 5, refreshRate = -1f),
            current.copy(id = 6, physicalHeight = 0, refreshRate = 240f), current.copy(id = 7, refreshRate = 30f))
        assertNull(highestRefreshRateMode(current, invalid))
        assertNull(highestRefreshRateMode(current, emptyList()))
        assertNull(highestRefreshRateMode(current.copy(refreshRate = Float.NaN), listOf(current)))
        assertNull(highestRefreshRateMode(current.copy(physicalWidth = 0), listOf(current)))
    }
}

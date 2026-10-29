package dev.pathfin.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPaginationTest {
    @Test fun defaultKeepsOneVisibleScreenAheadForOtherGridConsumers() {
        assertFalse(gridNeedsNextPage(40, 0, 11))
        assertFalse(gridNeedsNextPage(80, 45, 56))
        assertTrue(gridNeedsNextPage(80, 57, 68))
        assertFalse(gridNeedsNextPage(120, 48, 71))
        assertTrue(gridNeedsNextPage(120, 72, 95))
    }

    @Test fun phoneImmediatelyFillsTheSmallFirstBatchThenKeepsOneHundredItemsAhead() {
        assertTrue(gridNeedsNextPage(40, 0, 11, minimumAheadItems = 100, screensAhead = 2))
        assertFalse(gridNeedsNextPage(140, 0, 11, minimumAheadItems = 100, screensAhead = 2)) // 追加 100 项后足够，不连续加载整库。
        assertFalse(gridNeedsNextPage(140, 27, 38, minimumAheadItems = 100, screensAhead = 2)) // 距尾部 101 项。
        assertTrue(gridNeedsNextPage(140, 28, 39, minimumAheadItems = 100, screensAhead = 2)) // 距尾部 100 项，恰好提前。
        assertTrue(gridNeedsNextPage(140, 29, 40, minimumAheadItems = 100, screensAhead = 2))
    }

    @Test fun widerGridsUseTwoVisibleScreensInsteadOfThePhoneFloor() {
        assertFalse(gridNeedsNextPage(300, 119, 178, minimumAheadItems = 100, screensAhead = 2)) // 可见 60 项，尚余 121 项。
        assertTrue(gridNeedsNextPage(300, 120, 179, minimumAheadItems = 100, screensAhead = 2)) // 两屏 120 项，大于最低余量。
    }

    @Test fun missingOrInvalidLayoutDoesNotRequestAndLargeCountsDoNotOverflow() {
        assertFalse(gridNeedsNextPage(0, -1, -1))
        assertFalse(gridNeedsNextPage(40, -1, -1))
        assertFalse(gridNeedsNextPage(40, 0, -1))
        assertFalse(gridNeedsNextPage(40, 8, 7))
        assertFalse(gridNeedsNextPage(40, 0, 40))
        assertTrue(gridNeedsNextPage(3, 0, 2))
        assertFalse(gridNeedsNextPage(Int.MAX_VALUE, 0, 11))
        assertTrue(gridNeedsNextPage(Int.MAX_VALUE, 0, Int.MAX_VALUE - 1))
    }
}

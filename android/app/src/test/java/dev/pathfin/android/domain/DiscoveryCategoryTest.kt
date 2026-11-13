package dev.pathfin.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCategoryTest {
    @Test fun liveTvCategoriesFollowTheLiveTvSetting() {
        assertTrue(DiscoveryCategory.CHANNELS in searchDiscoveryCategories.forLiveTv(true))
        assertTrue(DiscoveryCategory.PROGRAMS in searchDiscoveryCategories.forLiveTv(true))
        assertTrue("收藏里有频道", DiscoveryCategory.CHANNELS in favoriteDiscoveryCategories.forLiveTv(true))
        val off = searchDiscoveryCategories.forLiveTv(false)
        assertFalse(DiscoveryCategory.CHANNELS in off || DiscoveryCategory.PROGRAMS in off)
        assertFalse(DiscoveryCategory.CHANNELS in favoriteDiscoveryCategories.forLiveTv(false))
        assertEquals("其他类型不受影响", searchDiscoveryCategories.size - 2, off.size)
    }
}

package dev.pathfin.android.ui

import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarBlurStateTest {
    @Test
    fun removingSecondaryRowRestoresThePrimaryHeadersMaterialRange() {
        val state = TopBarBlurState(HazeState())
        val title = Any()
        val actions = Any()
        state.updateRegion(title, 0.dp, 84.dp)
        state.updateRegion(actions, 84.dp, 140.dp, secondaryRow = true)

        assertEquals(0.dp, state.minimumTop)
        assertEquals(140.dp, state.maximumBottom)
        assertTrue(state.hasSecondaryRow)

        state.removeRegion(actions)
        assertEquals(84.dp, state.maximumBottom)
        assertFalse(state.hasSecondaryRow)

        state.removeRegion(title)
        assertEquals(0.dp, state.maximumBottom)
    }

    @Test
    fun enteringAndLeavingPagesKeepIndependentSourcesAndMaterialRanges() {
        val previous = TopBarBlurState(HazeState())
        val next = TopBarBlurState(HazeState())
        previous.updateRegion(Any(), 0.dp, 84.dp)
        next.updateRegion(Any(), 0.dp, 140.dp)

        assertNotSame(previous.source, next.source)
        assertEquals(84.dp, previous.maximumBottom)
        assertEquals(140.dp, next.maximumBottom)
    }
}

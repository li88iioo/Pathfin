package dev.pathfin.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarBlurStateTest {
    @Test
    fun removingSecondaryRowRestoresThePrimaryHeadersMaterialRange() {
        val state = TopBarBlurState(HazeState(), mutableStateOf(true))
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
        val previous = TopBarBlurState(HazeState(), mutableStateOf(true))
        val next = TopBarBlurState(HazeState(), mutableStateOf(true))
        previous.updateRegion(Any(), 0.dp, 84.dp)
        next.updateRegion(Any(), 0.dp, 140.dp)

        assertNotSame(previous.source, next.source)
        assertEquals(84.dp, previous.maximumBottom)
        assertEquals(140.dp, next.maximumBottom)
    }

    @Test
    fun changingThePreferenceKeepsThePageSourceAndRegisteredGeometry() {
        val enabled = mutableStateOf(true)
        val source = HazeState()
        val state = TopBarBlurState(source, enabled)
        state.updateRegion(Any(), 0.dp, 84.dp)

        enabled.value = false
        assertFalse(state.enabled)
        assertSame(source, state.source)
        assertEquals(84.dp, state.maximumBottom)

        enabled.value = true
        assertTrue(state.enabled)
        assertSame(source, state.source)
        assertEquals(84.dp, state.maximumBottom)
    }
}

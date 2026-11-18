package dev.pathfin.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVolumeGestureTest {
    @Test fun crossesTheSystemMaximumInBothDirectionsWithinOneGesture() {
        val output = SystemVolume(10, maximum = 20)
        val gesture = PlayerVolumeGesture(output.step, minimum = 0, maximum = 20, initialGain = 100)

        assertEquals(90, gesture.update(.4f, output::setStep))
        assertEquals(18, output.step)
        assertEquals(100, gesture.gainPercent)

        assertTrue(gesture.update(.49f, output::setStep) <= 100)
        assertEquals(100, gesture.gainPercent)

        assertEquals(100, gesture.update(.5f, output::setStep))
        assertEquals(20, output.step)
        assertEquals(100, gesture.gainPercent)

        assertEquals(150, gesture.update(1f, output::setStep))
        assertEquals(20, output.step)
        assertEquals(150, gesture.gainPercent)

        assertEquals(200, gesture.update(2f, output::setStep))
        assertEquals(200, gesture.gainPercent)

        assertEquals(100, gesture.update(.5f, output::setStep))
        assertEquals(20, output.step)
        assertEquals(100, gesture.gainPercent)

        assertEquals(75, gesture.update(.25f, output::setStep))
        assertEquals(15, output.step)
        assertEquals(100, gesture.gainPercent)

        assertEquals(0, gesture.update(-1f, output::setStep))
        assertEquals(0, output.step)
    }

    @Test fun successiveGesturesContinueFromTheCompletedBoost() {
        val output = SystemVolume(15, maximum = 20)
        val first = PlayerVolumeGesture(output.step, minimum = 0, maximum = 20, initialGain = 100)
        assertEquals(125, first.update(.5f, output::setStep))

        val second = PlayerVolumeGesture(output.step, minimum = 0, maximum = 20, initialGain = first.gainPercent)
        assertEquals(150, second.update(.25f, output::setStep))
        assertEquals(20, output.step)
        assertEquals(150, second.gainPercent)

        val third = PlayerVolumeGesture(output.step, minimum = 0, maximum = 20, initialGain = second.gainPercent)
        assertEquals(75, third.update(-.75f, output::setStep))
        assertEquals(15, output.step)
        assertEquals(100, third.gainPercent)
    }

    @Test fun aStoredBoostAnchorsTheGestureOnlyAtTheSystemMaximum() {
        val atMaximum = SystemVolume(10, maximum = 10)
        val boosted = PlayerVolumeGesture(atMaximum.step, minimum = 0, maximum = 10, initialGain = 150)
        assertEquals(175, boosted.update(.25f, atMaximum::setStep))

        val belowMaximum = SystemVolume(4, maximum = 10)
        val normal = PlayerVolumeGesture(belowMaximum.step, minimum = 0, maximum = 10, initialGain = 150)
        assertEquals(60, normal.update(.2f, belowMaximum::setStep))
        assertEquals(6, belowMaximum.step)
        assertEquals(100, normal.gainPercent)
    }

    @Test fun displayAndBoostFollowTheActualSystemStepWhenTheRequestedMaximumIsCapped() {
        val output = SystemVolume(3, maximum = 6)
        val gesture = PlayerVolumeGesture(output.step, minimum = 0, maximum = 10, initialGain = 100)

        assertEquals(40, gesture.update(.14f, output::setStep))
        assertEquals(4, output.step)
        assertEquals(60, gesture.update(1f, output::setStep))
        assertEquals(6, output.step)
        assertEquals(100, gesture.gainPercent)
    }

    @Test fun aRouteWithoutSoftwareBoostCanStillAdjustSystemMediaVolume() {
        val output = SystemVolume(5, maximum = 10)
        val gesture = PlayerVolumeGesture(output.step, minimum = 0, maximum = 10, initialGain = 150, allowBoost = false)

        assertEquals(100, gesture.update(1f, output::setStep))
        assertEquals(10, output.step)
        assertEquals(150, gesture.gainPercent)

        assertEquals(30, gesture.update(-.2f, output::setStep))
        assertEquals(3, output.step)
        assertEquals(150, gesture.gainPercent)
    }

    @Test fun aFixedSystemRangeDoesNotEnableSoftwareBoost() {
        val output = SystemVolume(5, maximum = 5)
        val gesture = PlayerVolumeGesture(output.step, minimum = 5, maximum = 5, initialGain = 100)

        assertTrue(gesture.update(1f, output::setStep) in 0..100)
        assertEquals(5, output.step)
        assertEquals(100, gesture.gainPercent)
    }

    private class SystemVolume(initialStep: Int, private val maximum: Int) {
        var step = initialStep
            private set

        fun setStep(requested: Int): Int {
            step = requested.coerceAtMost(maximum)
            return step
        }
    }
}

package dev.pathfin.android.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioGainTest {
    @Test fun originalAndAttenuatedVolumeNeverCreateAnAudioEffect() {
        val gain = PlaybackAudioGain { error("原音量和衰减不能创建音效") }
        assertEquals(1f, gain.apply(7, 100).volume, 0f)
        assertEquals(.5f, gain.apply(7, 50).volume, 0f)
        assertEquals(.2f, gain.apply(7, 50, .4f).volume, .0001f)
        assertEquals(0f, gain.apply(7, -1).volume, 0f)
        assertNull(gain.apply(0, 100).status)
        gain.close()
    }

    @Test fun amplificationUsesRealMillibelScaleAndPreservesBaseDucking() {
        val effect = FakeEnhancer()
        var session = 0
        PlaybackAudioGain { id -> session = id; effect }.use { gain ->
            val result = gain.apply(42, 200, .2f)
            assertEquals(42, session)
            assertEquals(602, effect.millibels)
            assertTrue(effect.enabledState)
            assertEquals(.2f, result.volume, 0f)
            assertNull(result.status)
            gain.apply(42, 150)
            assertEquals(352, effect.millibels)
        }
        assertFalse(effect.enabledState)
        assertEquals(1, effect.closes)
    }

    @Test fun audioSessionReplacementAndSwitchingToAttenuationReleaseOldEffects() {
        val created = mutableListOf<FakeEnhancer>()
        PlaybackAudioGain { FakeEnhancer().also(created::add) }.use { gain ->
            gain.apply(1, 125)
            gain.apply(1, 150)
            assertEquals(1, created.size)
            gain.apply(2, 150)
            assertEquals(2, created.size)
            assertEquals(1, created[0].closes)
            assertFalse(created[0].enabledState)
            assertEquals(.5f, gain.apply(2, 50).volume, 0f)
            assertEquals(1, created[1].closes)
            assertFalse(created[1].enabledState)
        }
    }

    @Test fun unavailableSessionWaitsAtOriginalVolumeWithoutPretendingEnhancement() {
        var creations = 0
        PlaybackAudioGain { creations++; FakeEnhancer() }.use { gain ->
            val waiting = gain.apply(0, 200)
            assertEquals(0, creations)
            assertEquals(1f, waiting.volume, 0f)
            assertTrue(waiting.status.orEmpty().contains("等待音频会话"))
            assertNull(gain.apply(3, 200).status)
            assertEquals(1, creations)
        }
    }

    @Test fun unsupportedOrFailedEffectsRestoreOriginalVolumeWithSafeStatus() {
        PlaybackAudioGain { throw UnsupportedOperationException("synthetic private detail") }.use { gain ->
            val result = gain.apply(1, 200)
            assertEquals(1f, result.volume, 0f)
            assertTrue(result.status.orEmpty().contains("已恢复 100% 原音量"))
            assertFalse(result.status.orEmpty().contains("private"))
        }
        for (failGain in listOf(false, true)) {
            val effect = FakeEnhancer(failGain = failGain, failEnable = !failGain)
            PlaybackAudioGain { effect }.use { gain ->
                val result = gain.apply(2, 150)
                assertEquals(1f, result.volume, 0f)
                assertNotNull(result.status)
                assertEquals(1, effect.closes)
                assertFalse(effect.enabledState)
            }
        }
    }

    @Test fun closingIsTerminalAndReleaseErrorsCannotResurrectAnEffect() {
        val effect = FakeEnhancer(failClose = true)
        var creations = 0
        val gain = PlaybackAudioGain { creations++; effect }
        gain.apply(1, 200)
        gain.close()
        gain.close()
        val result = gain.apply(2, 200)
        assertEquals(1, creations)
        assertEquals(1, effect.closes)
        assertEquals(1f, result.volume, 0f)
        assertNotNull(result.status)
    }

    private class FakeEnhancer(
        private val failGain: Boolean = false,
        private val failEnable: Boolean = false,
        private val failClose: Boolean = false,
    ) : PlaybackAudioGain.Enhancer {
        var millibels = 0
        var enabledState = false
        var closes = 0
        override fun setTargetGain(millibels: Int) {
            if (failGain) throw IllegalStateException("synthetic gain failure")
            this.millibels = millibels
        }
        override fun setEnabled(enabled: Boolean) {
            if (enabled && failEnable) throw UnsupportedOperationException("synthetic unavailable effect")
            this.enabledState = enabled
        }
        override fun close() { closes++; if (failClose) error("synthetic release failure") }
    }
}

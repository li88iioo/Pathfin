package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class PlaybackPcmProcessorTest {
    @Test fun speakerMixesCenterAndSurroundsFrom51And71IntoStereo() {
        for (channels in listOf(6, 8)) {
            val processor = PlaybackPcmProcessor(speakerOutput = true)
            assertEquals(format(2), processor.configure(format(channels)))
            processor.flush(StreamMetadata.DEFAULT)

            val center = process(processor, impulse(channels, 2))
            assertTrue("The center channel must reach both speakers", center[0] > 0)
            assertEquals(center[0], center[1])

            for (leftChannel in 4 until channels step 2) {
                val left = process(processor, impulse(channels, leftChannel))
                val right = process(processor, impulse(channels, leftChannel + 1))
                assertTrue("Surround channels must reach the corresponding side", left[0] > 0)
                assertEquals(0, left[1].toInt())
                assertEquals(0, right[0].toInt())
                assertEquals(left[0], right[1])
            }
        }
    }

    @Test fun stereoOnTheSpeakerAnd71OnAnExternalOutputKeepTheirSamplesAt100Percent() {
        val stereo = shortArrayOf(820, -1090, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(stereo, unchangedOutput(speakerOutput = true, channels = 2, samples = stereo))

        val surround = shortArrayOf(10, 20, 30, -40, -50, 60, 70, -80)
        assertArrayEquals(surround, unchangedOutput(speakerOutput = false, channels = 8, samples = surround))
    }

    @Test fun routeReconfigurationKeepsTheOriginalChannelsAndRespectsAnExplicitLfeMask() {
        val processor = PlaybackPcmProcessor(speakerOutput = false)
        val format = format(3)
        processor.channelMask = android.media.AudioFormat.CHANNEL_OUT_STEREO or android.media.AudioFormat.CHANNEL_OUT_LOW_FREQUENCY
        val bass = impulse(3, 2)
        for (speaker in listOf(false, true, false)) {
            processor.speakerOutput = speaker
            assertEquals(if (speaker) 2 else 3, processor.configure(format).channelCount)
            processor.flush(StreamMetadata.DEFAULT)
            assertArrayEquals(if (speaker) shortArrayOf(0, 0) else bass, process(processor, bass))
        }
    }

    @Test fun anAmplificationChangeTakesEffectWithoutReconfiguringAndProtectsPeaks() {
        val processor = PlaybackPcmProcessor(speakerOutput = true)
        assertEquals(format(2), processor.configure(format(2)))
        processor.flush(StreamMetadata.DEFAULT)

        processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())

        val quiet = shortArrayOf(4_000, -6_000)
        assertArrayEquals(quiet, process(processor, quiet))
        processor.gainPercent = 150
        assertArrayEquals(shortArrayOf(6_000, -9_000), process(processor, quiet))
        processor.gainPercent = 200
        assertArrayEquals(shortArrayOf(8_000, -12_000), process(processor, quiet))

        val peak = process(processor, shortArrayOf(26_000, -26_000))
        assertTrue("Positive peaks must not wrap around", peak[0].toInt() in 26_001..Short.MAX_VALUE.toInt())
        assertTrue("Negative peaks must not wrap around", peak[1].toInt() in Short.MIN_VALUE.toInt()..-26_001)

        processor.gainPercent = 100
        assertArrayEquals(quiet, process(processor, quiet))
    }

    private fun unchangedOutput(speakerOutput: Boolean, channels: Int, samples: ShortArray): ShortArray {
        val processor = PlaybackPcmProcessor(speakerOutput)
        val inputFormat = format(channels)
        val outputFormat = processor.configure(inputFormat)
        if (outputFormat == AudioFormat.NOT_SET) {
            assertFalse(processor.isActive)
            return samples
        }
        assertEquals(inputFormat, outputFormat)
        processor.flush(StreamMetadata.DEFAULT)
        return process(processor, samples)
    }

    private fun format(channels: Int) = AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT)

    private fun impulse(channels: Int, activeChannel: Int) =
        ShortArray(channels).apply { this[activeChannel] = 8_000 }

    private fun process(processor: PlaybackPcmProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        val output = processor.output.asShortBuffer()
        return ShortArray(output.remaining()).also { output.get(it) }
    }
}

package dev.pathfin.android.playback

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** 在播放线程处理线性 PCM。仅内置扬声器折叠声道，增益按帧联动限峰以保留声像。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackPcmProcessor(speakerOutput: Boolean, initialGainPercent: Int = 100) : BaseAudioProcessor() {
    @Volatile var speakerOutput = speakerOutput
    @Volatile var configuredChannels = 0
        private set
    @Volatile var encodedOutput = false
    @Volatile var gainPercent: Int = initialGainPercent
    var channelMask = 0
    private var pendingWeights = FloatArray(0)
    private var weights = pendingWeights
    private var limiter = 1f
    private var release = 1f

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(input)
        configuredChannels = input.channelCount
        pendingWeights = if (speakerOutput && input.channelCount > 2) stereoWeights(input.channelCount) else FloatArray(0)
        return if (pendingWeights.isEmpty()) input else AudioProcessor.AudioFormat(input.sampleRate, 2, input.encoding)
    }

    @android.annotation.SuppressLint("InlinedApi") // 较新系统才会传入这些声道位；低版本照常按实际掩码混音。
    private fun stereoWeights(channels: Int): FloatArray {
        var mask = channelMask.takeIf { it > 0 && Integer.bitCount(it) == channels } ?: Util.getAudioTrackChannelConfig(channels)
        if (Integer.bitCount(mask) != channels) return FloatArray(0)
        val weights = FloatArray(channels * 2)
        for (channel in 0 until channels) {
            val position = Integer.lowestOneBit(mask)
            mask = mask xor position
            val index = channel * 2
            when (position) {
                AudioFormat.CHANNEL_OUT_FRONT_LEFT -> weights[index] = 1f
                AudioFormat.CHANNEL_OUT_FRONT_RIGHT -> weights[index + 1] = 1f
                AudioFormat.CHANNEL_OUT_LOW_FREQUENCY, AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2 -> Unit
                AudioFormat.CHANNEL_OUT_BACK_LEFT, AudioFormat.CHANNEL_OUT_SIDE_LEFT, AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER,
                AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT, AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT,
                AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT, AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT, AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT -> weights[index] = SURROUND
                AudioFormat.CHANNEL_OUT_BACK_RIGHT, AudioFormat.CHANNEL_OUT_SIDE_RIGHT, AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER,
                AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT, AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT,
                AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT, AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT, AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT -> weights[index + 1] = SURROUND
                else -> { weights[index] = SURROUND; weights[index + 1] = SURROUND }
            }
        }
        return weights
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        weights = pendingWeights
        limiter = 1f
        release = (1.0 - exp(-1.0 / (0.05 * inputAudioFormat.sampleRate))).toFloat()
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val gain = gainPercent.coerceIn(100, 200) / 100f
        val channels = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(input.remaining() / inputAudioFormat.bytesPerFrame * outputAudioFormat.bytesPerFrame)
        if (weights.isEmpty() && gain == 1f) {
            output.put(input)
            limiter = 1f
        } else if (weights.isEmpty()) {
            while (input.hasRemaining()) {
                var peak = 0f
                val start = input.position()
                for (channel in 0 until channels) peak = max(peak, abs(input.getShort(start + channel * 2).toFloat()))
                val scale = limitedScale(peak, gain)
                repeat(channels) { output.putShort((input.short * scale).roundToInt().toShort()) }
            }
        } else {
            while (input.hasRemaining()) {
                var left = 0f
                var right = 0f
                for (channel in 0 until channels) {
                    val sample = input.short.toFloat()
                    left += sample * weights[channel * 2]
                    right += sample * weights[channel * 2 + 1]
                }
                val scale = limitedScale(max(abs(left), abs(right)), gain)
                output.putShort((left * scale).roundToInt().toShort())
                output.putShort((right * scale).roundToInt().toShort())
            }
        }
        output.flip()
    }

    private fun limitedScale(peak: Float, gain: Float): Float {
        val magnitude = peak * gain
        val target = if (magnitude > Short.MAX_VALUE) Short.MAX_VALUE / magnitude else 1f
        limiter = minOf(target, limiter + (1f - limiter) * release)
        return gain * limiter
    }

    private companion object { const val SURROUND = 0.70710677f }
}

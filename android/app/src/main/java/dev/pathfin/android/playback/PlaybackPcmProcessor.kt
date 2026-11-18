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
import kotlin.math.pow
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
    private var targetPercent = 100
    private var targetGain = 1f
    private var currentGain = 1f
    private var rampStep = 0f
    private var rampFrames = 0

    // 100% 是单位增益；增强区每 10 格增加 1.8 dB，留给限峰器处理没有余量的瞬态。
    private fun amplitude(percent: Int): Float = 10.0.pow((percent - 100) * 0.18 / 20.0).toFloat()

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
        targetPercent = gainPercent.coerceIn(100, 200)
        targetGain = amplitude(targetPercent)
        currentGain = targetGain
        rampFrames = 0
        limiter = 1f
        release = (1.0 - exp(-1.0 / (0.05 * inputAudioFormat.sampleRate))).toFloat()
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val percent = gainPercent.coerceIn(100, 200)
        if (percent != targetPercent) {
            targetPercent = percent
            targetGain = amplitude(percent)
            rampFrames = (inputAudioFormat.sampleRate / 100).coerceAtLeast(1)
            rampStep = (targetGain - currentGain) / rampFrames
        }
        val channels = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(input.remaining() / inputAudioFormat.bytesPerFrame * outputAudioFormat.bytesPerFrame)
        if (weights.isEmpty() && targetGain == 1f && rampFrames == 0) {
            output.put(input)
            limiter = 1f
        } else if (weights.isEmpty()) {
            while (input.hasRemaining()) {
                var peak = 0f
                val start = input.position()
                for (channel in 0 until channels) peak = max(peak, abs(input.getShort(start + channel * 2).toFloat()))
                val scale = limitedScale(peak)
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
                val scale = limitedScale(max(abs(left), abs(right)))
                output.putShort((left * scale).roundToInt().toShort())
                output.putShort((right * scale).roundToInt().toShort())
            }
        }
        output.flip()
    }

    private fun limitedScale(peak: Float): Float {
        if (rampFrames > 0) {
            rampFrames--
            currentGain = if (rampFrames == 0) targetGain else currentGain + rampStep
        }
        val magnitude = peak * currentGain
        val target = if (magnitude > Short.MAX_VALUE) Short.MAX_VALUE / magnitude else 1f
        limiter = minOf(target, limiter + (1f - limiter) * release)
        return currentGain * limiter
    }

    private companion object { const val SURROUND = 0.70710677f }
}

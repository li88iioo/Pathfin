package dev.pathfin.android.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import dev.pathfin.android.i18n.tr
import java.io.Closeable
import kotlin.math.log10
import kotlin.math.roundToInt

/** 每个 Exo 播放会话独占一个效果；100% 不创建 AudioEffect，低于 100% 用 player.volume 衰减。 */
internal class PlaybackAudioGain(private val createEnhancer: (Int) -> Enhancer = ::AndroidEnhancer) : Closeable {
    internal interface Enhancer : Closeable {
        fun setTargetGain(millibels: Int)
        fun setEnabled(enabled: Boolean)
    }
    internal data class Result(val volume: Float, val status: String? = null)
    private var effect: Enhancer? = null
    private var effectSession = 0
    private var closed = false

    fun apply(audioSessionId: Int, gainPercent: Int, baseVolume: Float = 1f): Result {
        val base = baseVolume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        val gain = gainPercent.coerceIn(0, 200)
        if (closed) return Result(base, tr("音量增强已停止"))
        if (gain <= 100) {
            releaseEffect()
            return Result(base * gain / 100f)
        }
        if (audioSessionId <= 0) {
            releaseEffect()
            return Result(base, tr("正在等待音频会话以启用音量增益…"))
        }
        return try {
            if (effect == null || effectSession != audioSessionId) {
                releaseEffect()
                effect = createEnhancer(audioSessionId)
                effectSession = audioSessionId
            }
            // 振幅百分比换算为毫贝：20 log10(gain / 100) dB × 100。
            checkNotNull(effect).setTargetGain((2_000 * log10(gain / 100.0)).roundToInt())
            checkNotNull(effect).setEnabled(true)
            Result(base)
        } catch (_: Exception) {
            releaseEffect()
            Result(base, tr("此设备无法启用音量增益，已恢复 100% 原音量"))
        }
    }

    private fun releaseEffect() {
        val previous = effect
        effect = null
        effectSession = 0
        if (previous != null) {
            runCatching { previous.setEnabled(false) }
            runCatching { previous.close() }
        }
    }

    override fun close() { closed = true; releaseEffect() }

    private class AndroidEnhancer(audioSessionId: Int) : Enhancer {
        private val delegate = LoudnessEnhancer(audioSessionId)
        override fun setTargetGain(millibels: Int) { delegate.setTargetGain(millibels) }
        override fun setEnabled(enabled: Boolean) { check(delegate.setEnabled(enabled) == AudioEffect.SUCCESS) }
        override fun close() { delegate.release() }
    }
}

package dev.pathfin.android.playback

import android.app.Activity
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import kotlin.math.roundToInt

/** 系统媒体音量到顶后，同一段滑动的剩余位移继续调节 PCM 增益。 */
internal class PlayerVolumeGesture(
    val initialStep: Int,
    private val minimum: Int,
    private val maximum: Int,
    val initialGain: Int,
    private val allowBoost: Boolean = true,
) {
    var gainPercent = initialGain
        private set

    private fun systemPercent(step: Int) = (step - minimum) * 100f / (maximum - minimum)
    private val startPercent = when {
        maximum <= minimum -> 0f
        allowBoost && initialStep == maximum && initialGain > 100 -> initialGain.toFloat()
        else -> systemPercent(initialStep)
    }

    fun update(fraction: Float, setStep: (Int) -> Int): Int {
        if (maximum <= minimum) return 0
        val target = (startPercent + fraction * 100f).coerceIn(0f, if (allowBoost) 200f else 100f)
        val requested = minimum + ((maximum - minimum) * (target.coerceAtMost(100f) / 100f)).roundToInt()
        val actual = setStep(requested)
        // 耳机安全上限等可能拒绝最高档，读回成功才进入增强区。
        if (allowBoost) gainPercent = if (actual == maximum) target.roundToInt().coerceAtLeast(100) else 100
        return if (allowBoost && gainPercent > 100) gainPercent else systemPercent(actual).roundToInt()
    }
}

internal class PlayerLevelGestures(
    private val activity: Activity,
    savedBrightness: Float,
    private val onBrightnessCommitted: (Float) -> Unit,
    private val onGainPreview: (Int) -> Unit,
    private val onGainCommitted: (Int) -> Unit,
    private val feedback: (Boolean, Int) -> Unit,
) : AutoCloseable {
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessBefore = originalBrightness
    private var effectiveBefore = .5f
    private var volume: PlayerVolumeGesture? = null
    private var active: Boolean? = null

    init {
        // 进入播放器即套用上次手势调好的亮度；系统亮度在 close 时恢复。
        if (savedBrightness in .02f..1f) activity.window.attributes = activity.window.attributes.apply { screenBrightness = savedBrightness }
    }

    fun begin(brightness: Boolean, gainPercent: Int, allowBoost: Boolean) {
        active = brightness
        if (brightness) {
            brightnessBefore = activity.window.attributes.screenBrightness
            effectiveBefore = brightnessBefore.takeIf { it >= 0 } ?: runCatching {
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            }.getOrDefault(.5f)
        } else {
            val media = AudioManager.STREAM_MUSIC
            val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(media) else 0
            volume = PlayerVolumeGesture(audio.getStreamVolume(media), minimum, audio.getStreamMaxVolume(media), gainPercent, allowBoost)
        }
    }

    fun update(brightness: Boolean, fraction: Float) {
        if (active != brightness || !fraction.isFinite()) return
        if (brightness) {
            val level = (effectiveBefore + fraction).coerceIn(.02f, 1f)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = level }
            feedback(true, (level * 100).toInt())
        } else volume?.let { gesture ->
            val previous = gesture.gainPercent
            val percent = gesture.update(fraction, ::setVolume)
            if (gesture.gainPercent != previous) onGainPreview(gesture.gainPercent)
            feedback(false, percent)
        }
    }

    private fun setVolume(target: Int): Int {
        val media = AudioManager.STREAM_MUSIC
        val current = audio.getStreamVolume(media)
        if (current == target) return current
        runCatching { audio.setStreamVolume(media, target, 0) }
        return audio.getStreamVolume(media)
    }

    fun end(cancelled: Boolean) {
        if (active == true) {
            if (cancelled) activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightnessBefore }
            else activity.window.attributes.screenBrightness.takeIf { it in .02f..1f }?.let(onBrightnessCommitted)
        }
        volume?.let { gesture ->
            if (cancelled) setVolume(gesture.initialStep)
            if (gesture.gainPercent != gesture.initialGain) {
                if (cancelled) onGainPreview(gesture.initialGain) else onGainCommitted(gesture.gainPercent)
            }
        }
        volume = null
        active = null
    }

    override fun close() {
        end(cancelled = true)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
    }
}

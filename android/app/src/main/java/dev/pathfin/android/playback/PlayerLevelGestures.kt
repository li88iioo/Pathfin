package dev.pathfin.android.playback

import android.app.Activity
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** 亮度只作用于当前窗口；媒体音量继续受系统/耳机安全音量上限约束。 */
/** feedback 参数：是否为亮度，以及当前百分比（0–100），由界面画成带图标的电平条。 */
internal class PlayerLevelGestures(private val activity: Activity, savedBrightness: Float,
    private val onBrightnessCommitted: (Float) -> Unit, private val feedback: (Boolean, Int) -> Unit) : AutoCloseable {
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessBefore = originalBrightness
    private var effectiveBefore = .5f
    private var volumeBefore = 0
    private var active: Boolean? = null

    init {
        // 进入播放器即套用上次手势调好的亮度；系统亮度在 close 时恢复。
        if (savedBrightness in .02f..1f) activity.window.attributes = activity.window.attributes.apply { screenBrightness = savedBrightness }
    }

    fun begin(brightness: Boolean) {
        active = brightness
        if (brightness) {
            brightnessBefore = activity.window.attributes.screenBrightness
            effectiveBefore = brightnessBefore.takeIf { it >= 0 } ?: runCatching {
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            }.getOrDefault(.5f)
        } else volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun update(brightness: Boolean, fraction: Float) {
        if (active != brightness || !fraction.isFinite()) return
        if (brightness) {
            val level = (effectiveBefore + fraction).coerceIn(.02f, 1f)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = level }
            feedback(true, (level * 100).toInt())
        } else {
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val target = (volumeBefore + fraction * (maximum - minimum)).toInt().coerceIn(minimum, maximum)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            feedback(false, if (maximum > 0) actual * 100 / maximum else 0)
        }
    }

    fun end(cancelled: Boolean) {
        if (!cancelled && active == true) activity.window.attributes.screenBrightness.takeIf { it in .02f..1f }?.let(onBrightnessCommitted)
        if (cancelled) when (active) {
            true -> activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightnessBefore }
            false -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0) }
            null -> Unit
        }
        active = null
    }

    override fun close() {
        end(cancelled = true)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
    }
}

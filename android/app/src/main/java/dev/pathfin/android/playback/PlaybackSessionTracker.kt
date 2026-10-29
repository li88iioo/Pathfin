package dev.pathfin.android.playback

/** 每个实际播放会话独占一个实例；只有第一次真正播放后才允许发送进度与结束。 */
internal class PlaybackSessionTracker {
    private var started = false
    private var stopped = false

    fun started(positionMs: Long): PlaybackReport? {
        if (started || stopped) return null
        started = true
        return PlaybackReport("start", positionMs.coerceAtLeast(0), isPaused = false)
    }

    fun progress(positionMs: Long, isPaused: Boolean): PlaybackReport? {
        if (!started || stopped) return null
        return PlaybackReport("progress", positionMs.coerceAtLeast(0), isPaused)
    }

    fun stopped(positionMs: Long, isPaused: Boolean): PlaybackReport? {
        if (stopped) return null
        stopped = true
        if (!started) return null
        return PlaybackReport("stop", positionMs.coerceAtLeast(0), isPaused)
    }
}

internal data class PlaybackReport(val event: String, val positionMs: Long, val isPaused: Boolean)

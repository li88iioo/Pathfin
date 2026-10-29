package dev.pathfin.android.domain

/** Jellyfin 时间使用 100ns ticks；所有负时间均视为零。 */
object TimeUnits {
    private const val TICKS_PER_MS = 10_000L

    fun ticksToMs(ticks: Long): Long = ticks.coerceAtLeast(0L) / TICKS_PER_MS

    fun msToTicks(milliseconds: Long): Long =
        milliseconds.coerceIn(0L, Long.MAX_VALUE / TICKS_PER_MS) * TICKS_PER_MS
}

package dev.pathfin.android.domain

/** Jellyfin 的真实媒体片段；时间单位为 100ns ticks。 */
data class MediaSegment(
    val id: String,
    val itemId: String,
    val type: MediaSegmentType,
    val startTicks: Long,
    val endTicks: Long,
)

enum class MediaSegmentType(val apiValue: String) {
    UNKNOWN("Unknown"), COMMERCIAL("Commercial"), PREVIEW("Preview"), RECAP("Recap"), OUTRO("Outro"), INTRO("Intro"),
}

data class IntroSkipPrompt(val key: String, val startMs: Long, val endMs: Long, val fromServer: Boolean)

/** 一次播放内已跳过/关闭的片段不再自动跳或反复提示；重新播放创建新的历史。 */
internal class IntroSkipHistory {
    private val consumed = mutableSetOf<String>()

    fun activeIntro(
        segments: List<MediaSegment>,
        positionMs: Long,
        durationMs: Long,
        clientIntroMs: Long,
        serverPending: Boolean = false,
    ): IntroSkipPrompt? {
        if (positionMs < 0 || durationMs <= 0 || positionMs >= durationMs) return null
        val server = segments.asSequence().filter { it.type == MediaSegmentType.INTRO && it.startTicks >= 0 && it.endTicks > it.startTicks }
            .map { IntroSkipPrompt("server:${it.id.lowercase()}", TimeUnits.ticksToMs(it.startTicks), TimeUnits.ticksToMs(it.endTicks), true) }
            .filter { it.endMs > it.startMs && it.endMs < durationMs }
            .sortedWith(compareBy(IntroSkipPrompt::startMs, IntroSkipPrompt::endMs)).toList()
        // 已有有效服务端边界时，不再用客户端固定秒数覆盖它；请求未完成时也不抢先跳。
        val candidates = if (server.isNotEmpty()) server else if (!serverPending && clientIntroMs in 1 until durationMs)
            listOf(IntroSkipPrompt("client:$clientIntroMs", 0, clientIntroMs, false)) else emptyList()
        return candidates.firstOrNull { it.key !in consumed && positionMs >= it.startMs && positionMs < it.endMs }
    }

    fun consume(prompt: IntroSkipPrompt): Boolean = consumed.add(prompt.key)
}

/** 这是提示出现阈值，不伪称剩余秒数后一定自动换集；换集仍由现有动作/偏好决定。 */
internal fun nextEpisodePromptRemainingSeconds(
    positionMs: Long,
    durationMs: Long,
    promptSeconds: Int,
    hasNext: Boolean,
    isLive: Boolean = false,
): Int? {
    val threshold = promptSeconds.coerceIn(0, 180)
    if (!hasNext || isLive || threshold == 0 || durationMs <= 0 || positionMs < 0 || positionMs >= durationMs) return null
    val remaining = durationMs - positionMs
    if (remaining > threshold * 1_000L) return null
    return ((remaining - 1) / 1_000 + 1).toInt()
}

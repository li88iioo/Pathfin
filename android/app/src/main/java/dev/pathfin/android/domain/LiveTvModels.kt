package dev.pathfin.android.domain

import java.time.Duration
import java.time.Instant

/** 直播权限来自当前账号的 Users/Me，直播源状态来自 LiveTv/Info。 */
data class LiveTvCapabilities(
    val canView: Boolean,
    val canPlay: Boolean,
    val isConfigured: Boolean,
)

data class LiveTvChannel(
    val item: MediaItem,
    val number: String? = null,
    val currentProgram: LiveTvProgram? = null,
) {
    val id: String get() = item.id
    val name: String get() = item.name
}

data class LiveTvProgram(
    val id: String,
    val channelId: String,
    val name: String,
    val episodeTitle: String? = null,
    val overview: String = "",
    val start: Instant? = null,
    val end: Instant? = null,
    val isRepeat: Boolean = false,
) {
    fun isAiring(now: Instant): Boolean = start != null && end != null && end > start && now >= start && now < end

    /** 只按有序的真实服务端起止时间计算进度；缺失时间不伪造百分比。 */
    fun progress(now: Instant): Float? {
        val from = start ?: return null
        val until = end ?: return null
        if (until <= from) return null
        val elapsed = Duration.between(from, now)
        val total = Duration.between(from, until)
        return ((elapsed.seconds.toDouble() + elapsed.nano / 1_000_000_000.0) /
            (total.seconds.toDouble() + total.nano / 1_000_000_000.0)).coerceIn(0.0, 1.0).toFloat()
    }
}

/** 分页游标按服务端原始返回条数推进，不使用 UI 去重后的列表长度。 */
data class LiveTvPage<T>(val items: List<T>, val totalCount: Int, val startIndex: Int) {
    val nextIndex: Int get() = startIndex + items.size
    val hasMore: Boolean get() = items.isNotEmpty() && nextIndex < totalCount
}

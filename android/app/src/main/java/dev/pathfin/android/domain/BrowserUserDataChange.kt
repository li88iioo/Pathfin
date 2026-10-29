package dev.pathfin.android.domain

/** 只有依赖变化字段的查询需要重新计算成员或顺序；普通列表直接更新卡片。 */
data class BrowserUserDataChange(val item: MediaItem, val data: MediaUserData, val kind: Kind) {
    enum class Kind { FAVORITE, PLAYED, PLAYBACK }

    private val favoriteChanged = data.isFavorite?.let { it != item.isFavorite } == true
    private val playbackChanged = kind != Kind.FAVORITE || data.played?.let { it != item.played } == true ||
        data.positionTicks?.let { it != item.positionTicks } == true || data.playCount?.let { it != item.playCount } == true

    fun apply(candidate: MediaItem): MediaItem = if (candidate.id == item.id) data.applyTo(candidate) else candidate

    fun invalidates(query: LibraryQuery): Boolean {
        val types = query.resultItemTypes
        // 显式标记整剧/整季会改变子项，即使父项 played 不变；收藏响应附带的播放字段不代表级联操作。
        val cascade = kind == Kind.PLAYED && item.type in setOf("Series", "Season")
        if (cascade && (types.isEmpty() || types.any { it in setOf("Episode", "Season") })) return true
        if (types.isNotEmpty() && item.type !in types) return false
        return favoriteChanged && (query.favorite != null || query.tab == LibraryTab.FAVORITES) ||
            playbackChanged && (query.played != null || query.tab == LibraryTab.RESUME || query.sort == LibrarySort.DATE_PLAYED)
    }
}

/** 同一在途读取期间可能有多次写入；缺省字段不能擦掉较早写入的字段。 */
fun MediaUserData.after(previous: MediaUserData?) = MediaUserData(
    isFavorite = isFavorite ?: previous?.isFavorite,
    played = played ?: previous?.played,
    positionTicks = positionTicks ?: previous?.positionTicks,
    playedPercentage = playedPercentage ?: previous?.playedPercentage,
    unplayedItemCount = unplayedItemCount ?: previous?.unplayedItemCount,
    playCount = playCount ?: previous?.playCount,
)

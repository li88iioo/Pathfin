package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

/** 搜索与收藏共用的媒体分组；类型与服务端 BaseItemKind 对应。 */
enum class DiscoveryCategory(val itemType: String, private val labelKey: String) {
    MOVIES("Movie", "电影"),
    SERIES("Series", "剧集"),
    COLLECTIONS("BoxSet", "合集"),
    PEOPLE("Person", "演员"),
    EPISODES("Episode", "集"),
    SEASONS("Season", "季"),
    ALBUMS("MusicAlbum", "专辑"),
    MUSIC("Audio", "音乐"),
    ARTISTS("MusicArtist", "歌手"),
    // 与官方 Web 搜索一致：节目按 LiveTvProgram、频道按 TvChannel 查询。
    PROGRAMS("LiveTvProgram", "节目"),
    CHANNELS("TvChannel", "频道");

    val label: String get() = tr(labelKey)
}

val searchDiscoveryCategories = DiscoveryCategory.entries.toList()
val favoriteDiscoveryCategories = listOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SERIES,
    DiscoveryCategory.COLLECTIONS, DiscoveryCategory.PEOPLE, DiscoveryCategory.EPISODES,
    DiscoveryCategory.SEASONS, DiscoveryCategory.ALBUMS, DiscoveryCategory.MUSIC, DiscoveryCategory.CHANNELS)
private val liveTvDiscoveryCategories = setOf(DiscoveryCategory.PROGRAMS, DiscoveryCategory.CHANNELS)

/** 未启用直播电视时，搜索与收藏不出现节目和频道。 */
fun List<DiscoveryCategory>.forLiveTv(enabled: Boolean): List<DiscoveryCategory> =
    if (enabled) this else filterNot { it in liveTvDiscoveryCategories }

package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

enum class LibraryTab(private val labelKey: String) {
    ALL("全部"), MEDIA("媒体"), RESUME("继续播放"), UPCOMING("即将播出"), COLLECTIONS("合集"), GENRES("类型"), FAVORITES("收藏");

    val label: String get() = tr(labelKey)
}

/** 分类由媒体库决定，不随筛选面板中临时选择的媒体类型增减。 */
fun availableLibraryTabs(libraryType: String?, libraryCategories: Boolean = true): List<LibraryTab> {
    if (!libraryCategories) return listOf(LibraryTab.ALL, LibraryTab.FAVORITES)
    val type = libraryType?.lowercase()
    val videoTabs = listOf(LibraryTab.ALL, LibraryTab.RESUME, LibraryTab.COLLECTIONS, LibraryTab.GENRES, LibraryTab.FAVORITES)
    return when {
        type == "movies" -> videoTabs
        type == "tvshows" -> videoTabs.toMutableList().apply { add(2, LibraryTab.UPCOMING) }
        type in setOf("music", "musicvideos", "homevideos", "boxsets") ->
            listOf(LibraryTab.ALL, LibraryTab.GENRES, LibraryTab.FAVORITES)
        else -> listOf(LibraryTab.ALL, LibraryTab.MEDIA, LibraryTab.RESUME, LibraryTab.COLLECTIONS, LibraryTab.FAVORITES)
    }
}

/** 这些虚拟库没有 Web 最近入库栏目；仍保留媒体库卡片入口。 */
val MediaItem.supportsLatestItems: Boolean
    get() = collectionType?.lowercase() !in setOf("livetv", "folders", "playlists", "boxsets", "channels")

/** apiValue 只使用 Jellyfin ItemSortBy 枚举；最新上线按入库时间排序。 */
enum class LibrarySort(private val labelKey: String, val apiValue: String) {
    DATE_LAST_CONTENT_ADDED("更新日期", "DateLastContentAdded,SortName"),
    DATE_ADDED("最新上线", "DateCreated"),
    DATE_CREATED("创建日期", "DateCreated"),
    COMMUNITY_RATING("社区评分", "CommunityRating"),
    TITLE("标题", "SortName"),
    PREMIERE_DATE("首映日期", "PremiereDate"),
    OFFICIAL_RATING("官方分级", "OfficialRating"),
    YEAR("年份", "ProductionYear"),
    CRITIC_RATING("影评人评分", "CriticRating"),
    DATE_PLAYED("播放日期", "DatePlayed");

    val label: String get() = tr(labelKey)
}

/** 库查询和收藏分类共用；null 布尔值表示不限制，而非 false。 */
data class LibraryQuery(
    val tab: LibraryTab = LibraryTab.ALL,
    val sort: LibrarySort = LibrarySort.DATE_CREATED,
    val descending: Boolean = true,
    val played: Boolean? = null,
    val favorite: Boolean? = null,
    val seriesStatus: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val years: Set<Int> = emptySet(),
    val officialRatings: Set<String> = emptySet(),
    val itemTypes: Set<String> = emptySet(),
    val personId: String? = null,
    val artistId: String? = null,
    val studioId: String? = null,
    val genreId: String? = null,
    /** 系列起播按季/集编号选择，避免按标题选错第一集。 */
    val episodeOrder: Boolean = false,
    /** 库内搜索作为独立查询的一部分，刷新和分页始终保留同一搜索词。 */
    val searchTerm: String = "",
    /** null 为全部；0 为 ASCII 数字，# 沿用服务器小于 A 的分组，A–Z 为排序名首字母。 */
    val nameInitial: String? = null,
    /** false 只浏览父目录的直接子项；搜索与跨层分类使用 true。 */
    val recursive: Boolean = true,
)

/** 请求与缓存失效使用相同的分类类型范围。 */
val LibraryQuery.resultItemTypes: Set<String>
    get() = when (tab) {
        LibraryTab.MEDIA -> setOf("Movie", "Series")
        LibraryTab.RESUME -> if (itemTypes == setOf("Series")) setOf("Episode") else itemTypes - "Folder"
        LibraryTab.UPCOMING -> setOf("Episode")
        LibraryTab.COLLECTIONS -> setOf("BoxSet")
        else -> itemTypes
    }

/** 仅用于首次打开媒体库；用户后续选择的查询仍由浏览状态保留。 */
fun MediaItem.defaultLibraryQuery(): LibraryQuery = when {
    type == "MusicAlbum" -> LibraryQuery(itemTypes = setOf("Audio"), sort = LibrarySort.TITLE, descending = false)
    type.equals("BoxSet", ignoreCase = true) -> LibraryQuery()
    type.equals("Folder", ignoreCase = true) -> LibraryQuery(
        sort = LibrarySort.TITLE, descending = false, recursive = false,
    )
    collectionType.equals("tvshows", ignoreCase = true) -> LibraryQuery(
        sort = LibrarySort.TITLE, descending = false,
        itemTypes = setOf("Series"),
    )
    collectionType.equals("movies", ignoreCase = true) -> LibraryQuery(sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Movie"))
    collectionType.equals("music", ignoreCase = true) -> LibraryQuery(itemTypes = setOf("MusicAlbum"))
    collectionType.equals("boxsets", ignoreCase = true) -> LibraryQuery(itemTypes = setOf("BoxSet"))
    collectionType.equals("homevideos", ignoreCase = true) -> LibraryQuery(itemTypes = setOf("Video"))
    type.equals("CollectionFolder", ignoreCase = true) -> LibraryQuery(
        sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Folder", "Movie", "Series"), recursive = false,
    )
    else -> LibraryQuery(itemTypes = setOf("Movie", "Series"))
}

/** 搜索仅继承库本身的默认类型，不继承用户正在浏览的分类、筛选或收藏限制。 */
fun MediaItem.librarySearchQuery(term: String): LibraryQuery = defaultLibraryQuery().copy(
    tab = LibraryTab.ALL,
    itemTypes = when {
        type.equals("Folder", ignoreCase = true) -> emptySet()
        collectionType.equals("music", ignoreCase = true) -> setOf("MusicAlbum", "MusicArtist", "Audio")
        else -> defaultLibraryQuery().itemTypes - "Folder"
    },
    searchTerm = term.trim(),
    recursive = true,
)

data class LibraryFilterOptions(
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
)

package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

/** 远程识别只覆盖 Jellyfin 明确提供搜索端点的条目类型。 */
enum class MediaIdentificationType(val apiValue: String, private val labelKey: String) {
    MOVIE("Movie", "电影"), SERIES("Series", "剧集"), BOX_SET("BoxSet", "合集"), BOOK("Book", "图书"),
    MUSIC_ALBUM("MusicAlbum", "音乐专辑"), MUSIC_ARTIST("MusicArtist", "音乐人"),
    MUSIC_VIDEO("MusicVideo", "音乐视频"), PERSON("Person", "人物"), TRAILER("Trailer", "预告片");

    val label: String get() = tr(labelKey)

    /** 与 Jellyfin 官方识别表单一致：人物与合集不按年份搜索。 */
    val searchesByYear: Boolean get() = this != PERSON && this != BOX_SET

    companion object {
        fun forItem(item: MediaItem): MediaIdentificationType? = entries.firstOrNull { it.apiValue == item.type }
    }
}

data class MediaIdentificationQuery(
    val name: String = "",
    val year: Int? = null,
    val providerIds: Map<String, String> = emptyMap(),
)

/** 原始候选仅在当前账号内存中保留；Apply 保留嵌套 Artists 等未展示字段。 */
class MediaIdentificationResult internal constructor(
    val key: String,
    val name: String,
    val productionYear: Int?,
    val providerIds: Map<String, String>,
    val searchProviderName: String?,
    val overview: String?,
    /** 提供程序给出的海报或头像（http/https），供结果页区分同名条目。 */
    val imageUrl: String?,
    internal val originalJson: String,
    internal val server: ServerConfig,
    internal val userId: String,
    internal val itemId: String,
    internal val itemType: MediaIdentificationType,
)

/** Jellyfin Apply 会重新获取元数据；这里只提供服务端真实支持的图片替换选项。 */
data class MediaIdentificationOptions(val replaceAllImages: Boolean = false)

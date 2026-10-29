package dev.pathfin.android.domain

data class ServerConfig(val baseUrl: String, val allowInsecureHttp: Boolean = false)

data class Session(
    val server: ServerConfig,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val deviceId: String,
    val serverId: String = "",
    val userImageTag: String? = null,
    val isAdministrator: Boolean = false,
    val canDownload: Boolean = false,
    val canDelete: Boolean = false,
) {
    /** 资料补全不会更换认证；地址、服务器、账号、凭据或设备变更才隔离在途响应。 */
    fun hasSameAuthentication(other: Session?): Boolean = other != null &&
        server == other.server && serverId == other.serverId && userId == other.userId &&
        accessToken == other.accessToken && deviceId == other.deviceId
}

data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val overview: String = "",
    val year: Int? = null,
    val runTimeTicks: Long = 0L,
    val positionTicks: Long = 0L,
    val imageTag: String? = null,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val isFolder: Boolean = false,
    val backdropTag: String? = null,
    val backdropItemId: String? = null,
    val communityRating: Double? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val childCount: Int? = null,
    val isFavorite: Boolean = false,
    val played: Boolean = false,
    val playedPercentage: Double? = null,
    val unplayedItemCount: Int? = null,
    val playCount: Int = 0,
    val bannerTag: String? = null,
    val logoTag: String? = null,
    val logoItemId: String? = null,
    val backdropTags: List<String> = emptyList(),
    val people: List<MediaPerson> = emptyList(),
    val studios: List<MediaStudio> = emptyList(),
    val externalUrls: List<ExternalLink> = emptyList(),
    val criticRating: Double? = null,
    val premiereDate: String? = null,
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val seasonId: String? = null,
    val collectionType: String? = null,
    val primaryImageItemId: String? = null,
    val canDownload: Boolean? = null,
    val canDelete: Boolean? = null,
    val chapters: List<MediaChapter> = emptyList(),
    val mediaSources: List<MediaSource> = emptyList(),
    val providerIds: Map<String, String> = emptyMap(),
    /** 仅来自服务器有效 EndDate 的年份；不根据完结状态或当前时间推断。 */
    val endYear: Int? = null,
    /** 原名（人物多为外文原名）。 */
    val originalTitle: String? = null,
    /** 人物的出生地（Jellyfin 以 ProductionLocations 保存）。 */
    val productionLocations: List<String> = emptyList(),
    /** 人物的去世日期；剧集仍用 endYear 表示完结年份。 */
    val endDate: String? = null,
)

data class MediaChapter(val name: String, val startPositionTicks: Long, val imageTag: String? = null, val imageIndex: Int = -1)

data class MediaPerson(val id: String, val name: String, val role: String? = null, val type: String = "", val imageTag: String? = null)
data class MediaStudio(val id: String, val name: String)
data class ExternalLink(val name: String, val url: String)
data class PublicServerInfo(val id: String, val name: String, val version: String)

/** 登录页可见的公开账号；服务器关闭公开列表时为空。 */
data class PublicUser(val id: String, val name: String, val imageTag: String? = null)

/** 可选字段保持未返回的数据；服务端写入成功后再应用到各页面缓存。 */
data class MediaUserData(
    val isFavorite: Boolean? = null,
    val played: Boolean? = null,
    val positionTicks: Long? = null,
    val playedPercentage: Double? = null,
    val unplayedItemCount: Int? = null,
    val playCount: Int? = null,
) {
    fun applyTo(item: MediaItem): MediaItem = item.copy(
        isFavorite = isFavorite ?: item.isFavorite,
        played = played ?: item.played,
        positionTicks = positionTicks ?: item.positionTicks,
        playedPercentage = playedPercentage ?: item.playedPercentage,
        unplayedItemCount = unplayedItemCount ?: item.unplayedItemCount,
        playCount = playCount ?: item.playCount,
    )
}

data class MediaPage(val items: List<MediaItem>, val totalCount: Int)

data class MediaSource(
    val id: String,
    val path: String? = null,
    val protocol: String? = null,
    val container: String? = null,
    val name: String? = null,
    val directStreamUrl: String? = null,
    val transcodingUrl: String? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val requiredHttpHeaders: Map<String, String> = emptyMap(),
    val mediaStreams: List<MediaStream> = emptyList(),
    val bitRate: Int? = null,
    val size: Long? = null,
    val runTimeTicks: Long? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    val defaultAudioStreamIndex: Int? = null,
)

/** 仅保留服务器实际返回的轨道描述；未知编解码、分辨率和 HDR 信息保持 null。 */
data class MediaStream(
    val index: Int? = null,
    val type: String? = null,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
    val isExternal: Boolean = false,
    val deliveryUrl: String? = null,
    val deliveryMethod: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isHearingImpaired: Boolean = false,
    val profile: String? = null,
    val level: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val averageFrameRate: Double? = null,
    val realFrameRate: Double? = null,
    val bitDepth: Int? = null,
    val bitRate: Int? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    val sampleRate: Int? = null,
    val videoRange: String? = null,
    val videoRangeType: String? = null,
    val videoDoViTitle: String? = null,
    val dvProfile: Int? = null,
    val colorRange: String? = null,
    val colorSpace: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val pixelFormat: String? = null,
)

data class PlaybackInfo(
    val sources: List<MediaSource>,
    val playSessionId: String,
    val errorCode: String? = null,
    val delivery: PlaybackDelivery = PlaybackDelivery.AUTOMATIC,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
)

enum class PlayMethod(val apiValue: String) {
    DIRECT_PLAY("DirectPlay"), DIRECT_STREAM("DirectStream"), TRANSCODE("Transcode")
}

data class ResolvedPlayback(
    val uri: String,
    val headers: Map<String, String>,
    val method: PlayMethod,
    val sourceId: String,
    val trustedServer: Boolean,
    val mimeType: String? = null,
)

data class PlaybackRequest(
    val item: MediaItem,
    val info: PlaybackInfo,
    val source: MediaSource,
    val stream: ResolvedPlayback,
    val startPositionMs: Long,
    val session: Session,
    val profileId: String? = null,
    val quality: PlaybackQuality = PlaybackQuality.ORIGINAL,
    val cloudPlayback: CloudPlayback? = null,
) {
    val delivery: PlaybackDelivery get() = info.delivery
}

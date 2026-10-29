package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.util.Locale

/** 条目能力必须由服务器返回；未知能力按不可操作处理。后端仍进行最终鉴权。 */
data class MediaManagementCapabilities(
    val canEditMetadata: Boolean = false,
    val canEditImages: Boolean = false,
    val canRefresh: Boolean = false,
    val canScanLibrary: Boolean = false,
    val canDelete: Boolean = false,
    val canDownload: Boolean = false,
)

data class MediaMetadata(
    val title: String,
    val originalTitle: String = "",
    val sortTitle: String = "",
    val overview: String = "",
    val productionYear: Int? = null,
    val premiereDate: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val officialRating: String? = null,
    val customRating: String? = null,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val lockData: Boolean = false,
    /** 人物的去世日期与出生地（Jellyfin 的 EndDate / ProductionLocations）。 */
    val endDate: String? = null,
    val productionLocations: List<String> = emptyList(),
    /** 外部编号（TheMovieDb、IMDb、豆瓣等）；未在表单中出现的键原样保留。 */
    val providerIds: Map<String, String> = emptyMap(),
)

/** 原始对象仅保存在内存中，保存时保留未编辑字段，并绑定读取它的服务器和用户。 */
class EditableMediaMetadata internal constructor(
    val itemId: String,
    val metadata: MediaMetadata,
    val capabilities: MediaManagementCapabilities,
    internal val originalJson: String,
    internal val server: ServerConfig,
    internal val userId: String,
    val path: String? = null,
)

enum class MediaImageType(val apiValue: String, private val labelKey: String) {
    PRIMARY("Primary", "海报"), BACKDROP("Backdrop", "背景图"), BANNER("Banner", "横幅"), LOGO("Logo", "标志"),
    THUMB("Thumb", "缩略图"), ART("Art", "艺术图"), DISC("Disc", "碟片"), BOX("Box", "封面盒"),
    BOX_REAR("BoxRear", "封底"), SCREENSHOT("Screenshot", "截图"), MENU("Menu", "菜单"),
    CHAPTER("Chapter", "章节"), PROFILE("Profile", "头像");

    val label: String get() = tr(labelKey)
}

data class MediaImage(
    val type: MediaImageType,
    val index: Int,
    val tag: String,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0,
)

data class MediaImageProvider(val name: String, val supportedTypes: Set<MediaImageType>)

/** 提供商地址可能含短期签名，仅驻留当前编辑页；下载请求始终发给绑定的 Jellyfin。 */
class RemoteMediaImage internal constructor(
    val url: String,
    val thumbnailUrl: String?,
    val type: MediaImageType,
    val providerName: String,
    val width: Int?,
    val height: Int?,
    val language: String?,
    internal val itemId: String,
    internal val server: ServerConfig,
    internal val userId: String,
)

data class RemoteMediaImagePage(
    val images: List<RemoteMediaImage>,
    val totalCount: Int,
    val startIndex: Int,
    /** 以服务器原始返回数推进游标，不因 UI 去重而重复读取同一页。 */
    val returnedCount: Int,
) {
    val hasMore: Boolean get() = returnedCount > 0 && startIndex.toLong() + returnedCount < totalCount
}

enum class MetadataRefreshMode(val apiValue: String, private val labelKey: String) {
    NONE("None", "不刷新"), VALIDATION_ONLY("ValidationOnly", "仅验证"), DEFAULT("Default", "补充缺失"),
    FULL_REFRESH("FullRefresh", "完整刷新");

    val label: String get() = tr(labelKey)
}

data class MetadataRefreshOptions(
    val metadataMode: MetadataRefreshMode = MetadataRefreshMode.DEFAULT,
    val imageMode: MetadataRefreshMode = MetadataRefreshMode.DEFAULT,
    val replaceAllMetadata: Boolean = false,
    val replaceAllImages: Boolean = false,
    val regenerateTrickplay: Boolean = false,
)

/** 与 Jellyfin 网页端「刷新元数据」的三种方式一致；替换选项只对后两种生效。 */
enum class MetadataRefreshScope(private val labelKey: String) {
    SCAN("扫描新的和有修改的文件"), MISSING("搜索缺少的元数据"), REPLACE_ALL("覆盖所有元数据");

    val label: String get() = tr(labelKey)

    fun options(replaceImages: Boolean, replaceTrickplay: Boolean): MetadataRefreshOptions = when (this) {
        SCAN -> MetadataRefreshOptions()
        MISSING, REPLACE_ALL -> MetadataRefreshOptions(MetadataRefreshMode.FULL_REFRESH, MetadataRefreshMode.FULL_REFRESH,
            replaceAllMetadata = this == REPLACE_ALL, replaceAllImages = replaceImages, regenerateTrickplay = replaceTrickplay)
    }
}

/** 含短期地址与必要 Header，仅供当前下载执行，不能持久化或写入日志。 */
class DownloadSource(
    val url: String,
    val headers: Map<String, String>,
    val fileName: String,
    val mimeType: String? = null,
    val expectedBytes: Long? = null,
    val trustedServer: Boolean = false,
    val isShortcutFile: Boolean = false,
)

/** 下载准备和实际文件接收共用容器映射；编码名称不替代文件容器。 */
internal object DownloadMimeType {
    fun fromFile(path: String, container: String? = null, isAudio: Boolean = false): String? {
        val extension = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        for (format in listOfNotNull(container, extension)) {
            val mime = when (format.trim().lowercase(Locale.ROOT)) {
                "mp4" -> if (isAudio) "audio/mp4" else "video/mp4"
                "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> if (isAudio) "audio/webm" else "video/webm"
                "ts", "m2ts" -> "video/mp2t"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "wav", "wave" -> "audio/wav"
                // RFC 7845：.opus 文件使用 Ogg 容器，而非 RTP 的 audio/opus。
                "ogg", "oga", "opus" -> "audio/ogg"
                else -> null
            }
            if (mime != null) return mime
        }
        return if (isAudio) "audio/*" else null
    }
}

/** 下载器对每次请求及重定向都重新计算凭据边界。 */
object DownloadHeaders {
    fun forUrl(session: Session, source: DownloadSource, url: String): Map<String, String> {
        val parsed = ServerUrls.httpUri(url) ?: return emptyMap()
        return if (source.trustedServer) {
            if (parsed.scheme.equals("http", true) && !session.server.allowInsecureHttp) return emptyMap()
            if (ServerUrls.isWithinBase(session.server, url)) PlaybackHeaders.serverHeaders(session) else emptyMap()
        } else {
            if (ServerUrls.sameOrigin(source.url, url)) PlaybackHeaders.sourceHeaders(session, source.headers) else emptyMap()
        }
    }
}

/** 服务器为该条目类型提供的外部编号（ExternalIdInfos）；识别表单与元数据编辑为每一项给出一个输入框。 */
data class ExternalIdField(val key: String, val name: String)

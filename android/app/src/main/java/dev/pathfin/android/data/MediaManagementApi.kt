package dev.pathfin.android.data

import dev.pathfin.android.domain.DownloadMimeType
import dev.pathfin.android.domain.DownloadSource
import dev.pathfin.android.domain.EditableMediaMetadata
import dev.pathfin.android.domain.ExternalIdField
import dev.pathfin.android.domain.MediaImage
import dev.pathfin.android.domain.MediaImageType
import dev.pathfin.android.domain.MediaImageProvider
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaManagementCapabilities
import dev.pathfin.android.domain.MediaMetadata
import dev.pathfin.android.domain.MetadataRefreshMode
import dev.pathfin.android.domain.MetadataRefreshOptions
import dev.pathfin.android.domain.PlaybackHeaders
import dev.pathfin.android.domain.RemoteMediaImage
import dev.pathfin.android.domain.RemoteMediaImagePage
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 日志仅使用这些固定操作名，不包含条目、路径、用户或表单值。 */
internal enum class ManagementOperation(val traceLabel: String) {
    SUBTITLE_READ("subtitle_read"), SUBTITLE_POLICY("subtitle_policy"), SUBTITLE_SEARCH("subtitle_search"),
    SUBTITLE_UPLOAD("subtitle_upload"), SUBTITLE_DOWNLOAD("subtitle_download"), SUBTITLE_DELETE("subtitle_delete"),
    ITEM_CAPABILITIES("manage_capabilities"), METADATA_READ("manage_metadata_read"), METADATA_SAVE("manage_metadata_save"),
    IMAGE_LIST("manage_image_list"), IMAGE_UPLOAD("manage_image_upload"), IMAGE_DELETE("manage_image_delete"),
    IMAGE_PROVIDERS("manage_image_providers"), IMAGE_SEARCH("manage_image_search"), IMAGE_APPLY("manage_image_apply"),
    ITEM_REFRESH("manage_refresh"), ITEM_DELETE("manage_delete"), DOWNLOAD_PREPARE("download_prepare"),
    IDENTIFY_SEARCH("identify_search"), IDENTIFY_APPLY("identify_apply"), EXTERNAL_IDS("manage_external_ids"), ADMIN_INFO("admin_info"),
    ADMIN_TASKS("admin_tasks"), ADMIN_TASK_RUN("admin_task_run"), ADMIN_TASK_CANCEL("admin_task_cancel"),
    ADMIN_ACTIVITY("admin_activity"), ADMIN_LOGS("admin_logs"), ADMIN_LOG_READ("admin_log_read"), ADMIN_RESTART("admin_restart"),
}

/** 删除确认中的索引已变化；调用方用本次校验列表恢复展示，必须重新选择图片。 */
internal class MediaImageSelectionChanged(val images: List<MediaImage>) :
    IllegalArgumentException(tr("图片列表已变化，请重新选择后删除"))

/** 使用 Jellyfin 自身鉴权；所有写入必须由调用者明确触发，读取不会自动执行写入。 */
class MediaManagementApi(private val api: JellyfinApi = JellyfinApi()) {
    fun capabilities(session: Session, item: MediaItem): MediaManagementCapabilities = MediaManagementCapabilities(
        canEditMetadata = session.isAdministrator,
        canEditImages = session.isAdministrator,
        canRefresh = session.isAdministrator,
        canScanLibrary = session.isAdministrator,
        canDelete = session.canDelete && item.canDelete == true,
        canDownload = session.canDownload && item.canDownload == true,
    )

    suspend fun readCapabilities(session: Session, itemId: String): MediaManagementCapabilities =
        capabilities(session, JsonMapping.mediaItem(readItem(session, itemId, ManagementOperation.ITEM_CAPABILITIES)))

    suspend fun editableMetadata(session: Session, itemId: String): EditableMediaMetadata {
        requireAdministrator(session)
        val json = readItem(session, itemId, ManagementOperation.METADATA_READ)
        return EditableMediaMetadata(itemId, metadata(json), capabilities(session, JsonMapping.mediaItem(json)),
            json.toString(), session.server, session.userId, (json.opt("Path") as? String)?.takeIf(String::isNotBlank))
    }

    /**
     * 该条目可填写的外部编号，来自服务器已启用的元数据提供程序；识别表单与元数据编辑共用。
     * 服务器已按条目过滤；Type 描述编号用途（如 Album / ReleaseGroup），并非条目的 Type。
     */
    suspend fun externalIds(session: Session, item: MediaItem): List<ExternalIdField> {
        requireAdministrator(session)
        requireIdentifier(item.id)
        val array = api.managementArray(session, ManagementOperation.EXTERNAL_IDS, "Items/${item.id}/ExternalIdInfos")
        return (0 until array.length()).mapNotNull { index ->
            val raw = array.optJSONObject(index) ?: throw ApiException(null, tr("服务器返回的外部编号格式不正确"))
            val key = (raw.opt("Key") as? String)?.takeIf { it.matches(PROVIDER_KEY) } ?: return@mapNotNull null
            val name = (raw.opt("Name") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) } ?: key
            ExternalIdField(key, name)
        }.distinctBy { it.key }.take(20)
    }

    suspend fun saveMetadata(session: Session, document: EditableMediaMetadata, metadata: MediaMetadata) {
        requireAdministrator(session)
        require(document.server == session.server && document.userId == session.userId) { tr("编辑内容与当前服务器账号不一致，请重新打开编辑页") }
        requireIdentifier(document.itemId)
        val before = document.metadata
        validateMetadata(metadata, before)
        val json = JSONObject(document.originalJson)
        if (json.opt("Id") != document.itemId) throw ApiException(null, tr("编辑内容的媒体标识不一致，请重新打开编辑页"))
        fun set(name: String, changed: Boolean, value: Any?) { if (changed) json.put(name, value ?: JSONObject.NULL) }
        set("Name", metadata.title != before.title, metadata.title.trim())
        set("OriginalTitle", metadata.originalTitle != before.originalTitle, metadata.originalTitle.trim().takeIf(String::isNotEmpty))
        set("ForcedSortName", metadata.sortTitle != before.sortTitle, metadata.sortTitle.trim().takeIf(String::isNotEmpty))
        set("Overview", metadata.overview != before.overview, metadata.overview)
        set("ProductionYear", metadata.productionYear != before.productionYear, metadata.productionYear)
        set("PremiereDate", metadata.premiereDate != before.premiereDate, apiDate(metadata.premiereDate))
        set("EndDate", metadata.endDate != before.endDate, apiDate(metadata.endDate, tr("去世日期")))
        set("ProductionLocations", metadata.productionLocations != before.productionLocations, JSONArray(metadata.productionLocations))
        set("Genres", metadata.genres != before.genres, JSONArray(metadata.genres.map(String::trim)))
        set("Tags", metadata.tags != before.tags, JSONArray(metadata.tags.map(String::trim)))
        set("OfficialRating", metadata.officialRating != before.officialRating, metadata.officialRating?.trim()?.takeIf(String::isNotEmpty))
        set("CustomRating", metadata.customRating != before.customRating, metadata.customRating?.trim()?.takeIf(String::isNotEmpty))
        set("CommunityRating", metadata.communityRating != before.communityRating, metadata.communityRating)
        set("CriticRating", metadata.criticRating != before.criticRating, metadata.criticRating)
        set("LockData", metadata.lockData != before.lockData, metadata.lockData)
        if (metadata.providerIds != before.providerIds) {
            val ids = json.optJSONObject("ProviderIds") ?: JSONObject()
            (before.providerIds.keys - metadata.providerIds.keys).forEach(ids::remove)
            metadata.providerIds.forEach { (key, value) ->
                if (value != before.providerIds[key]) ids.put(key, value.trim())
            }
            json.put("ProviderIds", ids)
        }
        api.managementObject(session, ManagementOperation.METADATA_SAVE, "Items/${document.itemId}", body = json)
    }

    suspend fun images(session: Session, itemId: String): List<MediaImage> {
        requireIdentifier(itemId)
        val array = api.managementArray(session, ManagementOperation.IMAGE_LIST, "Items/$itemId/Images")
        return (0 until array.length()).mapNotNull { index ->
            val image = array.optJSONObject(index) ?: throw ApiException(null, tr("服务器返回的图片列表格式不正确"))
            val type = MediaImageType.entries.firstOrNull { it.apiValue == image.opt("ImageType") } ?: return@mapNotNull null
            val imageIndex = image.opt("ImageIndex")?.takeUnless { it == JSONObject.NULL }?.let { integer(it) }
                ?: if (!image.has("ImageIndex") || image.isNull("ImageIndex")) 0 else throw ApiException(null, tr("服务器返回的图片序号无效"))
            if (imageIndex < 0) throw ApiException(null, tr("服务器返回的图片序号无效"))
            MediaImage(type, imageIndex, (image.opt("ImageTag") as? String).orEmpty(),
                integer(image.opt("Width"))?.takeIf { it > 0 }, integer(image.opt("Height"))?.takeIf { it > 0 },
                (image.opt("Size") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0)
        }
    }

    fun imageUrl(session: Session, itemId: String, image: MediaImage, width: Int = 420): String? {
        requireIdentifier(itemId)
        if (image.index < 0 || image.tag.isBlank()) return null
        return ServerUrls.endpoint(session.server, "Items/$itemId/Images/${image.type.apiValue}/${image.index}").toHttpUrl().newBuilder()
            .addQueryParameter("maxWidth", width.coerceIn(48, 2048).toString()).addQueryParameter("quality", "80")
            .addQueryParameter("format", "Webp").addQueryParameter("tag", image.tag).build().toString()
    }

    suspend fun uploadImage(session: Session, itemId: String, type: MediaImageType, bytes: ByteArray, mimeType: String) {
        requireAdministrator(session)
        requireIdentifier(itemId)
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { tr("请选择不超过 10 MB 的图片") }
        val mime = mimeType.lowercase(Locale.ROOT).substringBefore(';').trim().let { if (it == "image/jpg") "image/jpeg" else it }
        require(validImage(bytes, mime)) { tr("请选择有效的 JPEG、PNG、WebP 或 GIF 图片") }
        // Jellyfin 的 SetItemImage 从 image/* 请求体中读取 Base64，而不是原始二进制或 multipart。
        val encoded = Base64.getEncoder().encode(bytes).toRequestBody(mime.toMediaType())
        api.managementObject(session, ManagementOperation.IMAGE_UPLOAD, "Items/$itemId/Images/${type.apiValue}", method = "POST", rawBody = encoded)
    }

    suspend fun deleteImage(session: Session, itemId: String, image: MediaImage) {
        requireAdministrator(session)
        requireIdentifier(itemId)
        require(image.index >= 0 && image.tag.isNotBlank()) { tr("图片标识无效，请刷新图片列表") }
        val latest = images(session, itemId)
        if (latest.none { it.type == image.type && it.index == image.index && it.tag == image.tag })
            throw MediaImageSelectionChanged(latest)
        // 端点没有原子 If-Match；重读仅拦截确认前已发生的替换或索引移动。
        api.managementObject(session, ManagementOperation.IMAGE_DELETE, "Items/$itemId/Images/${image.type.apiValue}/${image.index}", method = "DELETE")
    }

    suspend fun imageProviders(session: Session, itemId: String): List<MediaImageProvider> {
        requireAdministrator(session)
        requireIdentifier(itemId)
        val array = api.managementArray(session, ManagementOperation.IMAGE_PROVIDERS, "Items/$itemId/RemoteImages/Providers")
        if (array.length() > 256) throw ApiException(null, tr("服务器返回的图片提供商过多"))
        return (0 until array.length()).map { index ->
            val raw = array.optJSONObject(index) ?: throw ApiException(null, tr("服务器返回的图片提供商格式不正确"))
            val name = providerName(raw.opt("Name")) ?: throw ApiException(null, tr("服务器返回的图片提供商名称无效"))
            val types = raw.optJSONArray("SupportedImages") ?: throw ApiException(null, tr("服务器未返回提供商支持的图片类型"))
            MediaImageProvider(name, (0 until types.length()).mapNotNull { typeIndex ->
                MediaImageType.entries.firstOrNull { it.apiValue == types.opt(typeIndex) }
            }.toSet())
        }.distinctBy { it.name }
    }

    /** 只查询当前条目的真实提供商结果，不向外站发送账号或搜索请求。 */
    suspend fun searchImages(session: Session, itemId: String, type: MediaImageType, provider: String? = null,
        includeAllLanguages: Boolean = false, startIndex: Int = 0, limit: Int = 24): RemoteMediaImagePage {
        requireAdministrator(session)
        requireIdentifier(itemId)
        require(startIndex >= 0 && limit in 1..48) { tr("图片分页范围无效") }
        require(provider == null || providerName(provider) == provider) { tr("图片提供商无效") }
        val query = linkedMapOf("type" to type.apiValue, "startIndex" to startIndex.toString(), "limit" to limit.toString(),
            "includeAllLanguages" to includeAllLanguages.toString())
        provider?.let { query["providerName"] = it }
        val json = api.managementObject(session, ManagementOperation.IMAGE_SEARCH, "Items/$itemId/RemoteImages", query)
        val array = json.optJSONArray("Images") ?: throw ApiException(null, tr("服务器返回的远程图片列表格式不正确"))
        val total = integer(json.opt("TotalRecordCount")) ?: throw ApiException(null, tr("服务器返回的远程图片总数无效"))
        if (array.length() > limit || array.length() > 0 && startIndex.toLong() + array.length() > total) {
            throw ApiException(null, tr("服务器返回的远程图片数量超过请求范围"))
        }
        val images = (0 until array.length()).map { index ->
            val raw = array.optJSONObject(index) ?: throw ApiException(null, tr("服务器返回的远程图片格式不正确"))
            val imageType = MediaImageType.entries.firstOrNull { it.apiValue == raw.opt("Type") }
            if (imageType != type) throw ApiException(null, tr("服务器返回的远程图片类型与请求不一致"))
            val url = remoteImageUrl(session, raw.opt("Url")) ?: throw ApiException(null, tr("服务器返回的远程图片地址无效"))
            val providerName = providerName(raw.opt("ProviderName")) ?: throw ApiException(null, tr("服务器返回的图片提供商名称无效"))
            RemoteMediaImage(url, remoteImageUrl(session, raw.opt("ThumbnailUrl")), type, providerName,
                integer(raw.opt("Width"))?.takeIf { it > 0 }, integer(raw.opt("Height"))?.takeIf { it > 0 },
                (raw.opt("Language") as? String)?.takeIf { it.length <= 64 && it.none(Char::isISOControl) && it.isNotBlank() },
                itemId, session.server, session.userId)
        }
        return RemoteMediaImagePage(images, total, startIndex, array.length())
    }

    suspend fun applyRemoteImage(session: Session, itemId: String, image: RemoteMediaImage) {
        requireAdministrator(session)
        requireIdentifier(itemId)
        require(image.itemId == itemId && image.server == session.server && image.userId == session.userId) {
            tr("图片结果与当前媒体或服务器账号不一致，请重新搜索")
        }
        require(remoteImageUrl(session, image.url) == image.url) { tr("图片地址无效，请重新搜索") }
        api.managementObject(session, ManagementOperation.IMAGE_APPLY, "Items/$itemId/RemoteImages/Download",
            query = mapOf("type" to image.type.apiValue, "imageUrl" to image.url), method = "POST")
    }

    private fun remoteImageUrl(session: Session, value: Any?): String? {
        val text = (value as? String)?.takeIf { it.length <= 8192 } ?: return null
        if (ServerUrls.httpUri(text) == null) return null
        val url = text.toHttpUrl()
        if (session.accessToken.isNotEmpty() && (text.contains(session.accessToken) || url.pathSegments.any { it.contains(session.accessToken) } ||
                url.queryParameterNames.any { name -> url.queryParameterValues(name).any { it?.contains(session.accessToken) == true } })) return null
        return text
    }

    private fun providerName(value: Any?): String? = (value as? String)?.takeIf {
        it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) && it == it.trim()
    }

    suspend fun refreshItem(session: Session, itemId: String, options: MetadataRefreshOptions = MetadataRefreshOptions()) {
        requireAdministrator(session)
        requireIdentifier(itemId)
        require(!options.replaceAllMetadata || options.metadataMode == MetadataRefreshMode.FULL_REFRESH) { tr("替换全部元数据需要选择完整刷新") }
        require(!options.replaceAllImages || options.imageMode == MetadataRefreshMode.FULL_REFRESH) { tr("替换全部图片需要选择完整刷新") }
        api.managementObject(session, ManagementOperation.ITEM_REFRESH, "Items/$itemId/Refresh", query = mapOf(
            "metadataRefreshMode" to options.metadataMode.apiValue, "imageRefreshMode" to options.imageMode.apiValue,
            "replaceAllMetadata" to options.replaceAllMetadata.toString(), "replaceAllImages" to options.replaceAllImages.toString(),
            "regenerateTrickplay" to options.regenerateTrickplay.toString(),
        ), method = "POST")
    }

    /** 对指定媒体库文件夹排队刷新，不会转成全服务器 Library/Refresh。 */
    suspend fun scanItem(session: Session, parentId: String) = refreshItem(session, parentId)

    suspend fun deleteItem(session: Session, itemId: String) {
        if (!session.canDelete) permissionDenied(tr("当前账号没有删除媒体的权限"))
        if (!readCapabilities(session, itemId).canDelete) permissionDenied(tr("当前账号不能删除该媒体"))
        api.managementObject(session, ManagementOperation.ITEM_DELETE, "Items/$itemId", method = "DELETE")
    }

    internal suspend fun prepareDownload(session: Session, itemId: String, sourceId: String?): DownloadSource {
        if (!session.canDownload) permissionDenied(tr("当前账号没有下载媒体的权限"))
        if (sourceId != null) requireIdentifier(sourceId)
        var json = readItem(session, itemId, ManagementOperation.DOWNLOAD_PREPARE)
        if (!capabilities(session, JsonMapping.mediaItem(json)).canDownload) permissionDenied(tr("当前账号不能下载该媒体"))
        var selected = downloadVersion(json, sourceId ?: itemId)
        if (sourceId != null && sourceId != itemId && selected == null)
            throw IllegalArgumentException(tr("所选媒体版本不存在，请重新选择"))
        // HTTP 源由 PlaybackInfo 解析；其他版本是独立条目，端点与元信息必须从该条目一起读取。
        val selectedRemote = (selected?.opt("Protocol") as? String).equals("Http", true) ||
            ServerUrls.httpUri((selected?.opt("Path") as? String).orEmpty()) != null
        val downloadItemId = if (sourceId != null && sourceId != itemId && !selectedRemote) {
            json = readItem(session, sourceId, ManagementOperation.DOWNLOAD_PREPARE)
            if (!capabilities(session, JsonMapping.mediaItem(json)).canDownload) permissionDenied(tr("当前账号不能下载所选媒体版本"))
            selected = downloadVersion(json, sourceId)
            sourceId
        } else itemId
        val playbackSourceId = sourceId?.takeIf { downloadItemId == itemId }
        val rawPath = (json.opt("Path") as? String).orEmpty()
        val container = (selected?.opt("Container") as? String) ?: (json.opt("Container") as? String)
        val shortcut = isShortcut(rawPath) || (json.opt("Container") as? String).equals("strm", true)
        val remote = ServerUrls.httpUri(rawPath) != null || (selected?.opt("Protocol") as? String).equals("Http", true) ||
            ServerUrls.httpUri((selected?.opt("Path") as? String).orEmpty()) != null
        if (shortcut || remote) {
            val info = api.playbackInfo(session, downloadItemId, playbackSourceId)
            if (!info.errorCode.isNullOrBlank()) throw ApiException(null, tr("服务器无法提供该媒体的下载来源"))
            val source = if (playbackSourceId == null) info.sources.firstOrNull { it.id == downloadItemId } ?: info.sources.firstOrNull()
                else info.sources.firstOrNull { it.id == playbackSourceId }
            val sourcePath = source?.path?.takeIf { ServerUrls.httpUri(it) != null }
                ?: throw ApiException(null, tr("该 STRM 媒体没有可下载的 HTTP 原始地址"))
            if (isShortcut(sourcePath) || isManifest(sourcePath, source.container)) throw ApiException(null, tr("该来源是 STRM 或流媒体清单，不能作为单个影片下载"))
            return DownloadSource(sourcePath, PlaybackHeaders.sourceHeaders(session, source.requiredHttpHeaders),
                downloadFileName((json.opt("Name") as? String).orEmpty(), sourcePath, source.container),
                DownloadMimeType.fromFile(sourcePath, source.container, json.opt("Type") == "Audio"), trustedServer = false)
        }
        if (isManifest(rawPath, container)) throw ApiException(null, tr("流媒体清单不能作为单个影片下载"))
        return DownloadSource(ServerUrls.endpoint(session.server, "Items/$downloadItemId/Download"), PlaybackHeaders.serverHeaders(session),
            downloadFileName((json.opt("Name") as? String).orEmpty(), rawPath, container), DownloadMimeType.fromFile(rawPath, container, json.opt("Type") == "Audio"),
            (selected?.opt("Size") as? Number)?.toLong()?.takeIf { it >= 0 }, trustedServer = true)
    }

    private fun downloadVersion(json: JSONObject, sourceId: String): JSONObject? {
        val sources = json.optJSONArray("MediaSources") ?: return null
        return (0 until sources.length()).firstNotNullOfOrNull { sources.optJSONObject(it)?.takeIf { source -> source.opt("Id") == sourceId } }
    }

    private suspend fun readItem(session: Session, itemId: String, operation: ManagementOperation): JSONObject {
        requireIdentifier(itemId)
        val json = api.managementObject(session, operation, "Items/$itemId", mapOf("userId" to session.userId))
        if (json.opt("Id") != itemId) throw ApiException(null, tr("服务器返回的媒体标识不一致"))
        return json
    }

    private fun metadata(json: JSONObject) = MediaMetadata(
        title = (json.opt("Name") as? String).orEmpty(), originalTitle = (json.opt("OriginalTitle") as? String).orEmpty(),
        sortTitle = (json.opt("ForcedSortName") as? String).orEmpty(), overview = (json.opt("Overview") as? String).orEmpty(),
        productionYear = integer(json.opt("ProductionYear")), premiereDate = (json.opt("PremiereDate") as? String),
        genres = strings(json.optJSONArray("Genres")), tags = strings(json.optJSONArray("Tags")),
        officialRating = json.opt("OfficialRating") as? String, customRating = json.opt("CustomRating") as? String,
        communityRating = (json.opt("CommunityRating") as? Number)?.toDouble(), criticRating = (json.opt("CriticRating") as? Number)?.toDouble(),
        lockData = json.opt("LockData") == true,
        endDate = json.opt("EndDate") as? String, productionLocations = strings(json.optJSONArray("ProductionLocations")),
        providerIds = json.optJSONObject("ProviderIds")?.let { ids -> ids.keys().asSequence().mapNotNull { key -> (ids.opt(key) as? String)?.let { key to it } }.toMap() }.orEmpty(),
    )

    private fun validateMetadata(metadata: MediaMetadata, before: MediaMetadata) {
        require(metadata.title.isNotBlank() && metadata.title.length <= 512 && metadata.title.none(Char::isISOControl)) { tr("标题不能为空或超过 512 个字符") }
        require(metadata.originalTitle.length <= 512 && metadata.sortTitle.length <= 512 && metadata.overview.length <= 1_048_576) { tr("元数据文本过长") }
        require(metadata.productionYear == null || metadata.productionYear in 1..9999) { tr("年份应为 1 到 9999") }
        require(metadata.communityRating == null || metadata.communityRating.isFinite() && metadata.communityRating in 0.0..10.0) { tr("社区评分应为 0 到 10") }
        require(metadata.criticRating == null || metadata.criticRating.isFinite() && metadata.criticRating in 0.0..100.0) { tr("影评人评分应为 0 到 100") }
        require((metadata.genres + metadata.tags).all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) } && metadata.genres.size <= 100 && metadata.tags.size <= 100) { tr("类型或标签无效") }
        require(listOfNotNull(metadata.officialRating, metadata.customRating).all { it.length <= 256 && it.none(Char::isISOControl) }) { tr("分级无效") }
        require(metadata.productionLocations.size <= 16 && metadata.productionLocations.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }) { tr("出生地无效") }
        apiDate(metadata.premiereDate)
        apiDate(metadata.endDate, tr("去世日期"))
        require(metadata.providerIds.size <= 64 && metadata.providerIds.all { (key, value) ->
            value == before.providerIds[key] ||
                key.matches(PROVIDER_KEY) && value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
        }) { tr("外部编号无效") }
    }

    private fun apiDate(value: String?, label: String = tr("首映日期")): String? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val instant = runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: throw IllegalArgumentException(tr("%1\$s格式应为 YYYY-MM-DD", label))
        require(instant.atOffset(ZoneOffset.UTC).year in 1..9999) { tr("%1\$s年份无效", label) }
        return instant.toString()
    }

    private fun validImage(bytes: ByteArray, mime: String): Boolean = when (mime) {
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        "image/gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        else -> false
    }

    private fun isShortcut(path: String): Boolean = path.substringBefore('?').substringBefore('#').endsWith(".strm", true)
    private fun isManifest(path: String, container: String?): Boolean = path.substringBefore('?').endsWith(".m3u8", true) ||
        path.substringBefore('?').endsWith(".mpd", true) || container?.lowercase(Locale.ROOT) in setOf("m3u8", "mpd", "dash", "hls")

    private fun downloadFileName(title: String, path: String, container: String?): String {
        val parsedPath = runCatching { URI(path).path }.getOrNull() ?: path.substringBefore('?')
        var name = parsedPath.replace('\\', '/').substringAfterLast('/').trim()
        val extension = container?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) && it != "strm" }
        if (name.isBlank() || '.' !in name) name = title.trim().ifBlank { "media" } + (extension?.let { ".$it" } ?: "")
        return name.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("").take(200).ifBlank { "media" }
    }

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }
    private fun integer(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
    private fun requireIdentifier(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { tr("媒体标识无效") } }
    private fun requireAdministrator(session: Session) { if (!session.isAdministrator) permissionDenied(tr("当前账号需要管理员权限才能执行此操作")) }
    private fun permissionDenied(message: String): Nothing = throw ApiException(403, message)

    companion object {
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        internal val PROVIDER_KEY = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    }
}

package dev.pathfin.android.data

import dev.pathfin.android.domain.JellyfinAuthorization
import dev.pathfin.android.domain.DownloadSource
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.resultItemTypes
import dev.pathfin.android.domain.LibraryFilterOptions
import dev.pathfin.android.domain.MediaUserData
import dev.pathfin.android.domain.PublicServerInfo
import dev.pathfin.android.domain.PublicUser
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaPage
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackCapabilities
import dev.pathfin.android.domain.PlaybackDelivery
import dev.pathfin.android.domain.PlaybackHeaders
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** 仅负责 Jellyfin API。禁止给播放器复用带服务器鉴权的客户端。 */
/** 库内搜索建议的条数；完整结果按 40 条分页，两者互不混用。 */
internal const val LibrarySuggestionLimit = 8

/** 只控制自动起播前的本机视频能力预检，手动画质与显式恢复模式始终优先。 */
enum class VideoPreflightPolicy {
    NEGOTIATE_UNSUPPORTED,
    TRY_ORIGINAL_SOURCE,
}

class JellyfinApi(client: OkHttpClient = OkHttpClient(),
    /** 首次协商播放时才读取，检测本机解码器可能耗时，在后台线程调用。 */
    private val capabilities: () -> PlaybackCapabilities = { PlaybackCapabilities.Baseline },
    private val videoSupport: (MediaStream) -> Boolean? = { null },
) {
    // 凭据请求不自动跟随 302；重定向可能把认证 body 或 Header 带到另一站点。
    private val client = PerformanceTrace.monitor(client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build())

    suspend fun authenticate(config: ServerConfig, username: String, password: String, deviceId: String): Session {
        val server = ServerUrls.normalize(config)
        require(username.isNotBlank()) { tr("请输入用户名") }
        require(deviceId.isNotBlank()) { tr("设备标识无效") }
        val headers = JellyfinAuthorization.headers(deviceId)
        val body = JSONObject().put("Username", username).put("Pw", password)
        val response = execute(server, "Users/AuthenticateByName", headers = headers, body = body)
        return JsonMapping.session(response, server, deviceId)
    }

    suspend fun validateSession(session: Session): Session {
        val user = execute(session.server, "Users/Me", headers = PlaybackHeaders.serverHeaders(session))
        if (user.optString("Id") != session.userId) throw ApiException(401, tr("登录已失效，请重新登录"))
        return JsonMapping.sessionUser(session, user)
    }

    /** 共享受基址和鉴权限制的 GET，供独立功能 API 使用。 */
    internal suspend fun authenticatedObject(session: Session, path: String, query: Map<String, String> = emptyMap()): JSONObject {
        require(path.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))) { tr("接口路径无效") }
        return execute(session.server, path, query = query, headers = PlaybackHeaders.serverHeaders(session))
    }

    internal suspend fun authenticatedArray(session: Session, path: String, query: Map<String, String> = emptyMap()): JSONArray {
        require(path.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))) { tr("接口路径无效") }
        return executeArray(session.server, path, query, PlaybackHeaders.serverHeaders(session))
    }

    suspend fun libraries(session: Session): List<MediaItem> =
        mapMediaPage(execute(
            session.server,
            "UserViews",
            query = mapOf("userId" to session.userId),
            headers = PlaybackHeaders.serverHeaders(session),
        )).items

    /** 首页最新添加使用专用接口；Series 分组由服务器处理，不用于可分页的库列表。 */
    suspend fun latestItems(session: Session, parentId: String? = null, limit: Int = 12): MediaPage {
        if (parentId != null) requireIdentifier(parentId)
        val boundedLimit = limit.coerceIn(1, 100)
        val parameters = homeParameters(session, boundedLimit).apply {
            put("groupItems", "true")
            parentId?.let { put("parentId", it) }
        }
        val items = executeArray(session.server, "Items/Latest", parameters, PlaybackHeaders.serverHeaders(session))
        validateHomeItems(items, boundedLimit)
        val page = mapMediaPage(JSONObject().put("Items", items).put("TotalRecordCount", items.length()))
        // Latest 将 Series.ChildCount 覆盖为本次新增条目数，不能当作普通 DTO 的季数。
        return page.copy(items = page.items.map { if (it.type == "Series") it.copy(childCount = null) else it })
    }

    /** 首页省略计数；媒体库继续播放开启计数并传游标，使用同一专用接口。 */
    suspend fun resumeItems(session: Session, parentId: String? = null, limit: Int = 12,
        startIndex: Int? = null, itemTypes: Set<String> = emptySet(), count: Boolean = false,
    ): MediaPage {
        if (parentId != null) requireIdentifier(parentId)
        require(itemTypes.all { it in ITEM_TYPES }) { tr("媒体类型无效") }
        val boundedLimit = limit.coerceIn(1, 100)
        val parameters = homeParameters(session, boundedLimit).apply {
            if (itemTypes.isEmpty()) put("mediaTypes", "Video")
            else put("includeItemTypes", itemTypes.sorted().joinToString(","))
            put("enableTotalRecordCount", count.toString())
            parentId?.let { put("parentId", it) }
            startIndex?.let { put("startIndex", it.coerceAtLeast(0).toString()) }
        }
        return mapHomePage(execute(session.server, "UserItems/Resume", parameters, PlaybackHeaders.serverHeaders(session)), boundedLimit)
    }

    private fun homeParameters(session: Session, limit: Int) = mutableMapOf(
        "userId" to session.userId, "limit" to limit.toString(), "fields" to HOME_FIELDS,
        "enableUserData" to "true", "enableImages" to "true", "imageTypeLimit" to "1", "enableImageTypes" to "Primary,Backdrop,Thumb",
    )

    private suspend fun mapHomePage(json: JSONObject, limit: Int): MediaPage {
        val items = json.optJSONArray("Items") ?: throw ApiException(null, tr("服务器返回的首页媒体列表格式不正确"))
        validateHomeItems(items, limit)
        return mapMediaPage(json)
    }

    private fun validateHomeItems(items: JSONArray, limit: Int) {
        if (items.length() > limit) throw ApiException(null, tr("服务器返回的首页媒体数量超过请求范围"))
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
            if (item == null || (item.opt("Id") as? String).isNullOrBlank()) {
                throw ApiException(null, tr("服务器返回的首页媒体列表格式不正确"))
            }
        }
    }

    suspend fun items(
        session: Session,
        parentId: String? = null,
        query: String? = null,
        startIndex: Int = 0,
        limit: Int = 40,
        resume: Boolean = false,
        options: LibraryQuery? = null,
    ): MediaPage {
        when (options?.tab) {
            LibraryTab.GENRES -> return genres(session, parentId, startIndex, limit, options)
            LibraryTab.RESUME -> return resumeItems(session, parentId, limit, startIndex,
                itemTypes = options.resultItemTypes, count = true)
            LibraryTab.UPCOMING -> return upcomingItems(session, parentId, startIndex, limit)
            else -> Unit
        }
        val searchTerm = options?.searchTerm?.trim()?.takeIf(String::isNotEmpty)
            ?: query?.trim()?.takeIf(String::isNotEmpty)
        val posterOnly = options != null && options.itemTypes.isNotEmpty() &&
            options.itemTypes.all { it in setOf("Movie", "Series", "Folder", "BoxSet") }
        val parameters = mutableMapOf(
            "userId" to session.userId,
            "startIndex" to startIndex.coerceAtLeast(0).toString(),
            "limit" to limit.coerceIn(1, 100).toString(),
            "fields" to if (posterOnly) {
                // 季数仅用于节目卡片；电影和合集无需额外让服务器统计 ChildCount。
                POSTER_FIELDS + if ("Series" in options!!.resultItemTypes) ",ChildCount" else ""
            } else LIST_FIELDS,
            "enableUserData" to "true",
            "imageTypeLimit" to "1",
            "enableImageTypes" to if (posterOnly) "Primary,Backdrop" else IMAGE_TYPES,
        )
        if (!parentId.isNullOrBlank()) parameters["parentId"] = parentId
        if (searchTerm != null) parameters["searchTerm"] = searchTerm
        when {
            resume -> {
                parameters["filters"] = "IsResumable"
                parameters["recursive"] = "true"
                parameters["includeItemTypes"] = "Movie,Episode"
                parameters["sortBy"] = "DatePlayed"
                parameters["sortOrder"] = "Descending"
            }
            searchTerm != null || parentId.isNullOrBlank() -> {
                parameters["recursive"] = "true"
                parameters["includeItemTypes"] = "Movie,Series,Episode"
                parameters["sortBy"] = if (searchTerm == null) "DateCreated" else "SortName"
                parameters["sortOrder"] = if (searchTerm == null) "Descending" else "Ascending"
            }
            else -> {
                parameters["recursive"] = "false"
                parameters["sortBy"] = "SortName"
                parameters["sortOrder"] = "Ascending"
            }
        }
        if (options != null) applyOptions(parameters, options)
        if (options?.itemTypes == setOf("Person")) {
            // /Persons 有独立参数契约，不把 Items 的排序/类型参数发送过去。
            val personParameters = parameters.filterKeys { it in PERSON_PARAMETERS }
            return mapMediaPage(execute(session.server, "Persons", personParameters, PlaybackHeaders.serverHeaders(session)))
        }
        return mapMediaPage(execute(session.server, "Items", parameters, PlaybackHeaders.serverHeaders(session)))
    }

    /**
     * 与官方 Web 搜索页一致：从当前用户可见的收藏/随机媒体生成推荐词，不读取图片；
     * 指定媒体库时限定父库与类型，供库内搜索使用。
     */
    suspend fun searchSuggestions(session: Session, parentId: String? = null, itemTypes: Set<String> = emptySet()): MediaPage {
        val parameters = mutableMapOf("userId" to session.userId, "sortBy" to "IsFavoriteOrLiked,Random",
            "recursive" to "true", "imageTypeLimit" to "0", "enableImages" to "false", "enableTotalRecordCount" to "false")
        if (itemTypes.isNotEmpty()) parameters["includeItemTypes"] = itemTypes.joinToString(",")
        else if (parentId.isNullOrBlank()) parameters["includeItemTypes"] = "Movie,Series,MusicArtist"
        if (parentId.isNullOrBlank()) parameters["limit"] = "20"
        else parameters += mapOf("parentId" to parentId, "limit" to LibrarySuggestionLimit.toString())
        return mapMediaPage(execute(session.server, "Items", parameters, PlaybackHeaders.serverHeaders(session)))
    }

    /** 每类独立查询与游标，避免混合 limit 截断其他类别。Persons 返回完整匹配集合，不制造分页。 */
    suspend fun discoveryPage(session: Session, category: dev.pathfin.android.domain.DiscoveryCategory,
        query: String = "", favorite: Boolean = false, startIndex: Int = 0, limit: Int = 24): MediaPage {
        val type = category.itemType
        if (type !in setOf("Person", "MusicArtist")) return items(session, query = query,
            startIndex = startIndex, limit = limit,
            options = LibraryQuery(itemTypes = setOf(type), favorite = true.takeIf { favorite },
                sort = dev.pathfin.android.domain.LibrarySort.TITLE, descending = false))
        val parameters = mutableMapOf("userId" to session.userId, "fields" to LIST_FIELDS,
            "enableUserData" to "true", "imageTypeLimit" to "1", "enableImageTypes" to "Primary")
        query.trim().takeIf(String::isNotBlank)?.let { parameters["searchTerm"] = it }
        if (favorite) parameters["isFavorite"] = "true"
        if (type == "Person") {
            require(startIndex == 0) { tr("演员查询不使用分页游标") }
            parameters["excludePersonTypes"] = "Artist,AlbumArtist"
        } else {
            parameters["startIndex"] = startIndex.coerceAtLeast(0).toString()
            parameters["limit"] = limit.coerceIn(1, 100).toString()
            parameters["enableTotalRecordCount"] = "true"
            parameters["sortBy"] = "SortName"
            parameters["sortOrder"] = "Ascending"
        }
        return mapMediaPage(execute(session.server, if (type == "Person") "Persons" else "Artists",
            parameters, PlaybackHeaders.serverHeaders(session)))
    }

    suspend fun adjacentEpisodes(session: Session, seriesId: String, episodeId: String): MediaPage {
        requireIdentifier(seriesId)
        requireIdentifier(episodeId)
        return mapMediaPage(execute(session.server, "Shows/$seriesId/Episodes",
            query = mapOf("userId" to session.userId, "adjacentTo" to episodeId, "isMissing" to "false",
                "fields" to LIST_FIELDS, "enableUserData" to "true", "imageTypeLimit" to "1"),
            headers = PlaybackHeaders.serverHeaders(session)))
    }

    suspend fun item(session: Session, itemId: String): MediaItem {
        require(itemId.isNotBlank()) { tr("媒体标识无效") }
        val page = mapMediaPage(execute(
            session.server,
            "Items",
            query = mapOf(
                "userId" to session.userId,
                "ids" to itemId,
                "fields" to DETAIL_FIELDS,
                "enableUserData" to "true",
                "enableImageTypes" to IMAGE_TYPES,
                "imageTypeLimit" to "20",
            ),
            headers = PlaybackHeaders.serverHeaders(session),
        ))
        return page.items.firstOrNull { it.id == itemId } ?: throw ApiException(404, tr("该媒体不存在或没有访问权限"))
    }

    suspend fun setFavorite(session: Session, itemId: String, favorite: Boolean): MediaUserData {
        requireIdentifier(itemId)
        return JsonMapping.userData(execute(
            session.server, "UserFavoriteItems/$itemId", mapOf("userId" to session.userId),
            PlaybackHeaders.serverHeaders(session), method = if (favorite) "POST" else "DELETE",
        ))
    }

    suspend fun setPlayed(session: Session, itemId: String, played: Boolean): MediaUserData {
        requireIdentifier(itemId)
        return JsonMapping.userData(execute(
            session.server, "UserPlayedItems/$itemId", mapOf("userId" to session.userId),
            PlaybackHeaders.serverHeaders(session), method = if (played) "POST" else "DELETE",
        ))
    }

    suspend fun nextUp(session: Session, seriesId: String? = null, limit: Int = 20): MediaPage {
        if (seriesId != null) requireIdentifier(seriesId)
        val boundedLimit = limit.coerceIn(1, 100)
        val parameters = homeParameters(session, boundedLimit).apply {
            put("enableResumable", "false")
            put("enableTotalRecordCount", "false")
            put("enableRewatching", "false")
            // 首页只取一年内观看过的节目；详情页指定剧集的起播选择不受此窗口限制。
            if (seriesId == null) put("nextUpDateCutoff", LocalDate.now().minusDays(365).toString())
        }
        seriesId?.let { parameters["seriesId"] = it }
        return mapHomePage(execute(session.server, "Shows/NextUp", parameters, PlaybackHeaders.serverHeaders(session)), boundedLimit)
    }

    suspend fun similar(session: Session, itemId: String, limit: Int = 12): MediaPage {
        requireIdentifier(itemId)
        return mapMediaPage(execute(
            session.server, "Items/$itemId/Similar",
            mapOf("userId" to session.userId, "limit" to limit.coerceIn(1, 100).toString(), "fields" to LIST_FIELDS),
            PlaybackHeaders.serverHeaders(session),
        ))
    }

    suspend fun filterOptions(session: Session, parentId: String? = null, itemTypes: Set<String> = emptySet()): LibraryFilterOptions {
        if (parentId != null) requireIdentifier(parentId)
        require(itemTypes.all { it in ITEM_TYPES }) { tr("媒体类型无效") }
        val parameters = mutableMapOf("userId" to session.userId)
        parentId?.let { parameters["parentId"] = it }
        if (itemTypes.isNotEmpty()) parameters["includeItemTypes"] = itemTypes.sorted().joinToString(",")
        // Filters2 不提供 Years 与 OfficialRatings，使用官方仍提供的完整筛选接口。
        return JsonMapping.filterOptions(execute(session.server, "Items/Filters", parameters, PlaybackHeaders.serverHeaders(session)))
    }

    private suspend fun upcomingItems(session: Session, parentId: String?, startIndex: Int, limit: Int): MediaPage {
        if (parentId != null) requireIdentifier(parentId)
        val parameters = homeParameters(session, limit.coerceIn(1, 100)).apply {
            put("startIndex", startIndex.coerceAtLeast(0).toString())
            put("fields", "AirTime,PrimaryImageAspectRatio")
            parentId?.let { put("parentId", it) }
        }
        return mapMediaPage(execute(session.server, "Shows/Upcoming", parameters, PlaybackHeaders.serverHeaders(session)))
    }

    private suspend fun genres(session: Session, parentId: String?, startIndex: Int, limit: Int, options: LibraryQuery): MediaPage {
        if (parentId != null) requireIdentifier(parentId)
        require(options.itemTypes.all { it in ITEM_TYPES }) { tr("媒体类型无效") }
        val parameters = mutableMapOf(
            "userId" to session.userId, "startIndex" to startIndex.coerceAtLeast(0).toString(),
            "limit" to limit.coerceIn(1, 100).toString(), "fields" to "Overview,ChildCount",
            "enableImageTypes" to "Primary,Backdrop", "imageTypeLimit" to "1",
            "sortBy" to "SortName", "sortOrder" to "Ascending",
        )
        parentId?.let { parameters["parentId"] = it }
        if (options.resultItemTypes.isNotEmpty()) parameters["includeItemTypes"] = options.resultItemTypes.sorted().joinToString(",")
        applyNameInitial(parameters, options.nameInitial)
        return mapMediaPage(execute(session.server, "Genres", parameters, PlaybackHeaders.serverHeaders(session)))
    }

    suspend fun publicServerInfo(config: ServerConfig): PublicServerInfo = JsonMapping.publicServerInfo(execute(
        ServerUrls.normalize(config), "System/Info/Public", headers = emptyMap(),
    ))

    suspend fun publicUsers(config: ServerConfig): List<PublicUser> = JsonMapping.publicUsers(executeArray(
        ServerUrls.normalize(config), "Users/Public", emptyMap(), headers = emptyMap(),
    ))

    /** 服务器在“品牌”设置中启用的启动图，登录前即可匿名读取；未启用时服务器返回 404。 */
    fun splashscreenUrl(config: ServerConfig, width: Int = 1080): String =
        ServerUrls.endpoint(config, "Branding/Splashscreen").toHttpUrl().newBuilder()
            .addQueryParameter("maxWidth", width.coerceIn(48, 2048).toString())
            .addQueryParameter("quality", "80")
            .addQueryParameter("format", "Webp")
            .build().toString()

    suspend fun changePassword(session: Session, currentPassword: String, newPassword: String) {
        require(newPassword.isNotEmpty()) { tr("请输入新密码") }
        execute(
            session.server, "Users/Password", mapOf("userId" to session.userId), PlaybackHeaders.serverHeaders(session),
            JSONObject().put("CurrentPw", currentPassword).put("NewPw", newPassword).put("ResetPassword", false),
        )
    }

    private fun applyOptions(parameters: MutableMap<String, String>, options: LibraryQuery) {
        require(options.itemTypes.all { it in ITEM_TYPES }) { tr("媒体类型无效") }
        require(options.seriesStatus.all { it in setOf("Continuing", "Ended", "Unreleased") }) { tr("剧集状态无效") }
        require(options.years.all { it in 1..9999 }) { tr("年份无效") }
        applyNameInitial(parameters, options.nameInitial)
        parameters["sortBy"] = if (options.episodeOrder) "ParentIndexNumber,IndexNumber" else options.sort.apiValue
        parameters["sortOrder"] = if (options.descending) "Descending" else "Ascending"
        val resultTypes = options.resultItemTypes
        if (resultTypes.isNotEmpty()) parameters["includeItemTypes"] = resultTypes.sorted().joinToString(",")
        else if (parameters.containsKey("parentId") && parameters.containsKey("searchTerm")) {
            // 显式的目录搜索不限类型，不能沿用全局搜索的默认视频类型而漏掉目录中的音频。
            parameters.remove("includeItemTypes")
        }
        options.played?.let { parameters["isPlayed"] = it.toString() }
        options.favorite?.let { parameters["isFavorite"] = it.toString() }
        if (options.seriesStatus.isNotEmpty()) parameters["seriesStatus"] = options.seriesStatus.sorted().joinToString(",")
        if (options.years.isNotEmpty()) parameters["years"] = options.years.sorted().joinToString(",")
        addDelimitedFilter(parameters, "genres", options.genres)
        addDelimitedFilter(parameters, "tags", options.tags)
        addDelimitedFilter(parameters, "officialRatings", options.officialRatings)
        options.personId?.let { requireIdentifier(it); parameters["personIds"] = it }
        options.artistId?.let { requireIdentifier(it); parameters["artistIds"] = it }
        options.studioId?.let { requireIdentifier(it); parameters["studioIds"] = it }
        options.genreId?.let { requireIdentifier(it); parameters["genreIds"] = it }
        // 默认目录只取直接子项；继续播放、收藏等分类仍跨目录聚合。
        parameters["recursive"] = (options.recursive || options.tab != LibraryTab.ALL ||
            parameters.containsKey("searchTerm")).toString()
        when (options.tab) {
            LibraryTab.FAVORITES -> parameters["isFavorite"] = "true"
            else -> Unit
        }
    }

    private fun applyNameInitial(parameters: MutableMap<String, String>, initial: String?) {
        require(initial == null || initial == "0" || initial == "#" || initial.length == 1 && initial[0] in 'A'..'Z') { tr("首字母筛选无效") }
        when (initial) {
            null -> Unit
            "0" -> {
                // 官方范围比较保留 ':'；不能只查以 0 开头而漏掉 1–9。
                parameters["nameStartsWithOrGreater"] = "0"
                parameters["nameLessThan"] = ":"
            }
            "#" -> parameters["nameLessThan"] = "A"
            else -> parameters["nameStartsWith"] = initial
        }
    }

    private fun addDelimitedFilter(parameters: MutableMap<String, String>, key: String, values: Set<String>) {
        require(values.all { value -> value.isNotBlank() && '|' !in value && value.none { it.isISOControl() } }) { tr("筛选条件无效") }
        if (values.isNotEmpty()) parameters[key] = values.sorted().joinToString("|")
    }

    suspend fun playbackInfo(
        session: Session,
        itemId: String,
        sourceId: String? = null,
        startTimeTicks: Long = 0,
        quality: PlaybackQuality = PlaybackQuality.ORIGINAL,
        delivery: PlaybackDelivery = PlaybackDelivery.AUTOMATIC,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        videoPreflight: VideoPreflightPolicy = VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED,
    ): PlaybackInfo {
        requireIdentifier(itemId)
        require(audioStreamIndex == null || audioStreamIndex >= 0) { tr("音轨索引无效") }
        require(subtitleStreamIndex == null || subtitleStreamIndex >= -1) { tr("字幕轨索引无效") }
        val effectiveDelivery = if (quality.maxBitrate != null) PlaybackDelivery.TRANSCODE else delivery
        val allowDirectPlay = effectiveDelivery == PlaybackDelivery.AUTOMATIC
        val profile = withContext(Dispatchers.Default) { deviceProfile() }
        suspend fun request(mode: PlaybackDelivery, id: String? = sourceId, requestedProfile: JSONObject = profile) =
            requestPlaybackInfo(session, itemId, id, startTimeTicks, quality, mode,
                audioStreamIndex, subtitleStreamIndex, requestedProfile)
        val info = request(effectiveDelivery)
        // 多版本未选定时不能因某一个版本不支持而把所有版本强制转码。
        val selected = if (!sourceId.isNullOrBlank()) info.sources.firstOrNull { it.id == sourceId } else info.sources.singleOrNull()
        val video = selected?.mediaStreams?.firstOrNull { it.type.equals("Video", true) }
        if (videoPreflight == VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED && allowDirectPlay && video != null) {
            when (withContext(Dispatchers.Default) { videoSupport(video) }) {
                false -> return request(PlaybackDelivery.TRANSCODE, selected.id)
                true -> if (!selected.supportsDirectPlay && selected.id.isNotBlank()) {
                    sourceSpecificVideoProfile(profile, video)?.let { exact ->
                        // 两条预检分支均直接返回，整次起播最多一次额外协商；服务器仍决定能否直放。
                        return request(effectiveDelivery, selected.id, exact)
                    }
                }
                null -> Unit
            }
        }
        return info
    }

    /** 直连探测或多版本选择后，复用已选来源，仅补做一次限定视频协商。 */
    internal suspend fun negotiateSelectedSourceVideo(
        session: Session,
        itemId: String,
        info: PlaybackInfo,
        sourceId: String,
        startTimeTicks: Long,
        quality: PlaybackQuality,
    ): PlaybackInfo {
        if (quality != PlaybackQuality.ORIGINAL || info.delivery != PlaybackDelivery.AUTOMATIC ||
            !info.errorCode.isNullOrBlank()) return info
        val source = info.sources.singleOrNull { it.id == sourceId } ?: return info
        if (source.id.isBlank() || source.supportsDirectPlay) return info
        val video = source.mediaStreams.firstOrNull { it.type.equals("Video", true) } ?: return info
        val exact = withContext(Dispatchers.Default) {
            sourceSpecificVideoProfile(deviceProfile(), video)?.takeIf { videoSupport(video) == true }
        } ?: return info
        // 已选定来源，直接发送精确声明，不再重复静态声明请求；音频、容器和字幕限制保持不变。
        return requestPlaybackInfo(session, itemId, source.id, startTimeTicks, quality, info.delivery,
            info.audioStreamIndex, info.subtitleStreamIndex, exact)
    }

    private suspend fun requestPlaybackInfo(
        session: Session,
        itemId: String,
        sourceId: String?,
        startTimeTicks: Long,
        quality: PlaybackQuality,
        delivery: PlaybackDelivery,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        profile: JSONObject,
    ): PlaybackInfo {
        requireIdentifier(itemId)
        require(audioStreamIndex == null || audioStreamIndex >= 0) { tr("音轨索引无效") }
        require(subtitleStreamIndex == null || subtitleStreamIndex >= -1) { tr("字幕轨索引无效") }
        val direct = delivery == PlaybackDelivery.AUTOMATIC
        val copy = delivery != PlaybackDelivery.TRANSCODE
        val body = JSONObject().put("UserId", session.userId).put("StartTimeTicks", startTimeTicks.coerceAtLeast(0))
            .put("EnableDirectPlay", direct).put("EnableDirectStream", copy).put("EnableTranscoding", true)
            .put("AllowVideoStreamCopy", copy).put("AllowAudioStreamCopy", copy)
            .put("DeviceProfile", profile.apply { quality.maxBitrate?.let { put("MaxStreamingBitrate", it) } })
        quality.maxBitrate?.let { body.put("MaxStreamingBitrate", it) }
        audioStreamIndex?.let { body.put("AudioStreamIndex", it) }
        subtitleStreamIndex?.let { body.put("SubtitleStreamIndex", it) }
        if (!sourceId.isNullOrBlank()) body.put("MediaSourceId", sourceId)
        val response = JsonMapping.playbackInfo(execute(session.server, "Items/$itemId/PlaybackInfo",
            headers = PlaybackHeaders.serverHeaders(session), body = body))
        return response.copy(delivery = delivery, audioStreamIndex = audioStreamIndex, subtitleStreamIndex = subtitleStreamIndex,
            sources = response.sources.map { source -> source.copy(
                supportsDirectPlay = source.supportsDirectPlay && direct,
                supportsDirectStream = source.supportsDirectStream && copy,
            ) })
    }

    /** 静态声明确实排除、且本机已确认可播时，仅为当前来源声明精确视频条件。 */
    private fun sourceSpecificVideoProfile(profile: JSONObject, stream: MediaStream): JSONObject? {
        val codec = stream.codec?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val width = stream.width?.takeIf { it > 0 } ?: return null
        val height = stream.height?.takeIf { it > 0 } ?: return null
        val depth = stream.bitDepth?.takeIf { it > 0 } ?: return null
        val name = stream.profile?.takeIf { it.isNotBlank() } ?: return null
        val known = linkedMapOf("Width" to width.toString(), "Height" to height.toString(),
            "VideoBitDepth" to depth.toString(), "VideoProfile" to name)
        stream.level?.takeIf { it.isFinite() && it >= 0 }?.let { known["VideoLevel"] = it.toString() }
        listOfNotNull(stream.averageFrameRate, stream.realFrameRate).firstOrNull { it.isFinite() && it > 0 }
            ?.let { known["VideoFramerate"] = it.toString() }
        stream.videoRangeType?.takeIf { it.isNotBlank() }?.let { known["VideoRangeType"] = it }
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        val direct = profile.getJSONArray("DirectPlayProfiles").objects()
        val rows = profile.getJSONArray("CodecProfiles").objects()
        val matching = rows.filter { it.optString("Type") == "Video" && it.optString("Codec").equals(codec, true) }
        val conditions = matching.flatMap { it.getJSONArray("Conditions").objects() }
        val declared = direct.any { it.optString("Type") == "Video" &&
            it.optString("VideoCodec").split(',').any { candidate -> candidate.equals(codec, true) } }
        val excluded = !declared || conditions.any { condition ->
            val value = known[condition.getString("Property")] ?: return@any false
            val limit = condition.getString("Value")
            when (condition.getString("Condition")) {
                "LessThanEqual" -> value.toDoubleOrNull()?.let { actual -> limit.toDoubleOrNull()?.let { actual > it } } == true
                "EqualsAny" -> limit.split('|').none { it.equals(value, true) }
                else -> false
            }
        }
        if (!excluded) return null
        val result = JSONObject(profile.toString())
        result.getJSONArray("DirectPlayProfiles").objects().filter { it.optString("Type") == "Video" }.forEach { row ->
            row.put("VideoCodec", (row.getString("VideoCodec").split(',') + codec).distinct().sorted().joinToString(","))
        }
        val exact = JSONArray()
        // 未知字段继续受原约束；不能因服务器漏报 level/range 而放宽所有级别或动态范围。
        conditions.filter { it.getString("Property") !in known }.forEach(exact::put)
        known.forEach { (property, value) ->
            // Jellyfin 用 float 保存帧率、升为 double 比较；23.976 等值跨 JSON 后不能精确 Equals。
            // 仅为当前来源向上取整不足 1 fps 的余量，其他已知条件仍精确匹配。
            val frameRate = property == "VideoFramerate"
            exact.put(JSONObject().put("Property", property)
                .put("Condition", if (frameRate) "LessThanEqual" else "Equals")
                .put("Value", if (frameRate) ceil(value.toDouble()).toString() else value).put("IsRequired", true))
        }
        result.put("CodecProfiles", JSONArray().apply {
            rows.filterNot { it in matching }.forEach(::put)
            put(JSONObject().put("Type", "Video").put("Codec", codec).put("Conditions", exact))
        })
        return result
    }

    /** 只回收本次由可信 Jellyfin 地址提供的转码会话，不影响其它设备或直放任务。 */
    suspend fun stopTranscoding(request: PlaybackRequest) {
        if (!request.stream.trustedServer || request.stream.method != PlayMethod.TRANSCODE || request.info.playSessionId.isBlank()) return
        execute(request.session.server, "Videos/ActiveEncodings",
            query = mapOf("deviceId" to request.session.deviceId, "playSessionId" to request.info.playSessionId),
            headers = PlaybackHeaders.serverHeaders(request.session), method = "DELETE")
    }

    suspend fun reportPlayback(
        session: Session,
        event: String,
        request: PlaybackRequest,
        positionMs: Long,
        isPaused: Boolean,
    ) {
        require(session.userId == request.session.userId && session.deviceId == request.session.deviceId &&
            session.server == request.session.server && session.accessToken == request.session.accessToken) {
            tr("播放会话与当前账号不一致")
        }
        val endpoint = when (event) {
            "start" -> "Sessions/Playing"
            "progress" -> "Sessions/Playing/Progress"
            "stop" -> "Sessions/Playing/Stopped"
            else -> throw IllegalArgumentException(tr("无效的播放上报事件"))
        }
        val body = JSONObject()
            .put("ItemId", request.item.id)
            .put("MediaSourceId", request.source.id)
            .put("PlaySessionId", request.info.playSessionId)
            .put("PositionTicks", TimeUnits.msToTicks(positionMs))
        if (event == "stop") body.put("Failed", false) else body
            .put("CanSeek", true)
            .put("IsPaused", isPaused)
            .put("IsMuted", false)
            .put("PlayMethod", request.stream.method.apiValue)
        execute(session.server, endpoint, headers = PlaybackHeaders.serverHeaders(session), body = body)
    }

    fun imageUrl(session: Session, item: MediaItem, width: Int = 420, type: String = "Primary", imageIndex: Int = 0): String? {
        if (type !in setOf("Primary", "Backdrop", "Banner", "Logo") || imageIndex < 0) return null
        val backdropTag = if (imageIndex == 0) item.backdropTag else item.backdropTags.getOrNull(imageIndex)?.takeIf(String::isNotBlank)
        val (imageId, imagePath, imageTag) = when {
            type == "Backdrop" && !backdropTag.isNullOrBlank() ->
                Triple(item.backdropItemId ?: item.id, "Backdrop/$imageIndex", backdropTag)
            type == "Logo" -> Triple(item.logoItemId ?: item.id, "Logo", item.logoTag)
            type == "Banner" && !item.bannerTag.isNullOrBlank() -> Triple(item.id, "Banner", item.bannerTag)
            else -> Triple(item.primaryImageItemId ?: item.id, "Primary", item.imageTag)
        }
        if (imageTag.isNullOrBlank() || !validIdentifier(imageId)) return null
        return ServerUrls.endpoint(session.server, "Items/$imageId/Images/$imagePath").toHttpUrl().newBuilder()
            .addQueryParameter("maxWidth", width.coerceIn(48, 2048).toString())
            .addQueryParameter("quality", "80")
            .addQueryParameter("format", "Webp")
            .addQueryParameter("tag", imageTag)
            .build().toString()
    }

    fun imageHeaders(session: Session): Map<String, String> = PlaybackHeaders.serverHeaders(session)

    fun chapterImageUrl(session: Session, item: MediaItem, index: Int, tag: String?, width: Int = 320): String? {
        if (!validIdentifier(item.id) || index !in 0..100_000 || tag.isNullOrBlank() || tag.length > 512 || tag.any(Char::isISOControl)) return null
        return ServerUrls.endpoint(session.server, "Items/${item.id}/Images/Chapter/$index").toHttpUrl().newBuilder()
            .addQueryParameter("maxWidth", width.coerceIn(48, 2048).toString())
            .addQueryParameter("quality", "80").addQueryParameter("format", "Webp")
            .addQueryParameter("tag", tag).build().toString()
    }

    fun userImageUrl(session: Session, width: Int = 160): String? =
        userImageUrl(session.server, session.userId, session.userImageTag, width)

    fun publicUserImageUrl(config: ServerConfig, user: PublicUser, width: Int = 160): String? =
        userImageUrl(config, user.id, user.imageTag, width)

    private fun userImageUrl(config: ServerConfig, userId: String, imageTag: String?, width: Int): String? {
        val tag = imageTag?.takeIf(String::isNotBlank) ?: return null
        if (!validIdentifier(userId)) return null
        return ServerUrls.endpoint(config, "Users/$userId/Images/Primary").toHttpUrl().newBuilder()
            .addQueryParameter("maxWidth", width.coerceIn(48, 1024).toString())
            .addQueryParameter("quality", "80")
            .addQueryParameter("format", "Webp")
            .addQueryParameter("tag", tag)
            .build().toString()
    }

    suspend fun downloadSource(session: Session, itemId: String, sourceId: String? = null): DownloadSource =
        MediaManagementApi(this).prepareDownload(session, itemId, sourceId)

    internal suspend fun managementObject(
        session: Session,
        operation: ManagementOperation,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        method: String = if (body == null) "GET" else "POST",
        rawBody: RequestBody? = null,
    ): JSONObject = executeJson(session.server, path, query, PlaybackHeaders.serverHeaders(session), body, method,
        rawBody = rawBody, operation = operation) { text -> if (text.isBlank()) JSONObject() else JSONObject(text) }

    internal suspend fun managementArray(session: Session, operation: ManagementOperation, path: String,
        query: Map<String, String> = emptyMap(), body: JSONObject? = null,
        method: String = if (body == null) "GET" else "POST"): JSONArray =
        executeJson(session.server, path, query = query, body = body, method = method,
            headers = PlaybackHeaders.serverHeaders(session), operation = operation) { text ->
            val tokens = JSONTokener(text)
            val result = tokens.nextValue()
            if (result !is JSONArray || tokens.nextClean() != '\u0000') throw JSONException("Expected a single array")
            result
        }

    internal suspend fun managementText(session: Session, operation: ManagementOperation, path: String,
        query: Map<String, String> = emptyMap(), maxBytes: Int = 512 * 1024): String {
        require(maxBytes in 1..(512 * 1024))
        return executeJson(session.server, path, query, PlaybackHeaders.serverHeaders(session),
            operation = operation, responseByteLimit = maxBytes.toLong(), truncateResponse = true,
            accept = "text/plain") { it }
    }

    // Persons 可能一次返回完整集合，所有列表 DTO 的批量映射统一离开界面线程。
    private suspend fun mapMediaPage(json: JSONObject): MediaPage = withContext(Dispatchers.Default) {
        PerformanceTrace.measure("map_media_page") { JsonMapping.mediaPage(json) }
    }

    private suspend fun execute(
        server: ServerConfig,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String>,
        body: JSONObject? = null,
        method: String = if (body == null) "GET" else "POST",
    ): JSONObject = executeJson(server, path, query, headers, body, method) { text ->
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private suspend fun executeArray(
        server: ServerConfig,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): JSONArray = executeJson(server, path, query, headers) { text ->
        val tokens = JSONTokener(text)
        val result = tokens.nextValue()
        if (result !is JSONArray || tokens.nextClean() != '\u0000') throw JSONException("Expected a single media array")
        result
    }

    private suspend fun <T> executeJson(
        server: ServerConfig,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String>,
        body: JSONObject? = null,
        method: String = if (body == null) "GET" else "POST",
        rawBody: RequestBody? = null,
        operation: ManagementOperation? = null,
        responseByteLimit: Long = MAX_RESPONSE_BYTES,
        truncateResponse: Boolean = false,
        accept: String = "application/json",
        parse: (String) -> T,
    ): T {
        val url = ServerUrls.endpoint(server, path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val trace = PerformanceTrace.begin(operation?.traceLabel ?: PerformanceTrace.apiOperation(path, query))
        val request = Request.Builder().url(url).tag(PerformanceTrace.Span::class.java, trace).header("Accept", accept).apply {
            headers.forEach { (key, value) -> header(key, value) }
            val requestBody = rawBody ?: body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
            when (method) {
                "GET" -> get()
                "POST" -> post(requestBody ?: ByteArray(0).toRequestBody(null))
                "DELETE" -> delete(requestBody)
                else -> error(tr("不支持的请求方法"))
            }
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiException(null, NetworkErrorMessages.connection(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw httpError(response.code, path == "Users/AuthenticateByName")
                            val source = response.body.source()
                            val exceedsLimit = source.request(responseByteLimit + 1)
                            if (exceedsLimit && !truncateResponse) throw ApiException(null, tr("服务器返回的数据过大，请缩小查询范围"))
                            val text = if (truncateResponse) source.readByteArray(minOf(source.buffer.size, responseByteLimit)).toString(Charsets.UTF_8)
                                else source.readUtf8()
                            trace.point("body_read")
                            val result = parse(text)
                            trace.point("json_parsed")
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            val safe = when (error) {
                                is ApiException -> error
                                is JSONException -> ApiException(null, tr("服务器返回了无法识别的数据"))
                                else -> ApiException(null, tr("读取服务器响应失败，请稍后重试"))
                            }
                            if (continuation.isActive) continuation.resumeWithException(safe)
                        }
                    }
                }
            })
        }
    }

    private fun httpError(code: Int, authenticating: Boolean): ApiException = ApiException(code, when (code) {
        400 -> tr("服务器无法处理此请求（HTTP 400），请检查服务器地址或重试")
        401 -> if (authenticating) tr("账号或密码错误，请重试") else tr("此账号登录已失效，请重新登录")
        403 -> tr("当前账号没有访问该内容的权限")
        404 -> tr("服务器地址或媒体不存在，请检查地址与服务版本")
        in 300..399 -> tr("服务器要求重定向，请填写重定向后的正确服务器地址")
        in 500..599 -> tr("服务器暂时无法处理请求，请稍后重试")
        else -> tr("服务器请求失败，请稍后重试")
    })

    private fun requireIdentifier(value: String) {
        require(validIdentifier(value)) { tr("媒体标识无效") }
    }

    private fun validIdentifier(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private fun subtitleProfiles(): JSONArray = JSONArray().apply {
        listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt").forEach { format ->
            put(JSONObject().put("Format", format).put("Method", "External"))
            put(JSONObject().put("Format", format).put("Method", "Embed"))
        }
        listOf("pgssub", "pgs").forEach { format -> put(JSONObject().put("Format", format).put("Method", "Embed")) }
    }

    /** 按本机实际解码能力声明直放条件；只有超出能力的文件才交给服务器转码。 */
    internal fun deviceProfile(): JSONObject {
        val capabilities = capabilities()
        return JSONObject()
            .put("Name", "Pathfin Android")
            // 原画不设人为码率上限；画质选项另行在请求中限制。
            .put("MaxStreamingBitrate", Int.MAX_VALUE)
            .put("MaxStaticBitrate", Int.MAX_VALUE)
            .put("DirectPlayProfiles", directPlayProfiles(capabilities))
            .put("TranscodingProfiles", JSONArray()
                .put(JSONObject().put("Container", "ts").put("Type", "Video")
                    .put("Protocol", "hls").put("Context", "Streaming")
                    .put("VideoCodec", "h264").put("AudioCodec", "aac").put("MaxAudioChannels", "2")
                    .put("MinSegments", 1).put("BreakOnNonKeyFrames", false))
                .put(JSONObject().put("Container", "mp3").put("Type", "Audio")
                    .put("Protocol", "http").put("Context", "Streaming")
                    .put("AudioCodec", "mp3").put("MaxAudioChannels", "2")))
            .put("CodecProfiles", JSONArray().apply {
                capabilities.video.toSortedMap().forEach { (codec, limit) ->
                    val conditions = JSONArray()
                        .put(maximum("Width", limit.maxWidth.toString()))
                        .put(maximum("Height", limit.maxHeight.toString()))
                        .put(maximum("VideoBitDepth", limit.maxBitDepth.toString()))
                    if (limit.profiles.isNotEmpty()) conditions.put(JSONObject()
                        .put("Condition", "EqualsAny").put("Property", "VideoProfile")
                        .put("Value", limit.profiles.sorted().joinToString("|")).put("IsRequired", false))
                    // MPEG2 的 ffprobe 数值反序（HL=4、ML=8、LL=10），不能当作递增上限。
                    if (codec != "mpeg2video") limit.maxLevel?.let { conditions.put(maximum("VideoLevel", it.toString())) }
                    limit.maxFrameRate?.let { conditions.put(maximum("VideoFramerate", it.toString())) }
                    // 没有 DV 解码器时纯 Dolby Vision（Profile 5）无兼容基础层，交给服务器转码映射色彩。
                    if (codec in setOf("hevc", "av1") &&
                        !capabilities.supportsDolbyVisionProfile(if (codec == "av1") 10 else 5)) conditions.put(JSONObject()
                        .put("Condition", "EqualsAny").put("Property", "VideoRangeType")
                        .put("Value", "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithSDR|DOVIWithHLG")
                        .put("IsRequired", false))
                    put(JSONObject().put("Type", "Video").put("Codec", codec).put("Conditions", conditions))
                }
            })
            // 声明字幕能力：文本字幕可由服务器作为外挂文件下发、也可直接用片源内嵌轨；图形字幕仅内嵌。
            // 不声明时服务器会把默认字幕烧进画面并强制转码（例如下载字幕后被设为默认）。
            .put("SubtitleProfiles", subtitleProfiles())
    }

    private fun directPlayProfiles(capabilities: PlaybackCapabilities): JSONArray = JSONArray().apply {
        val amr = capabilities.audioCodecs.intersect(setOf("amr_nb", "amr_wb"))
        val g711 = capabilities.audioCodecs.intersect(setOf("pcm_alaw", "pcm_mulaw"))
        val generalAudio = capabilities.audioCodecs - amr - g711
        val video = capabilities.video.keys.sorted().joinToString(",")
        if (generalAudio.isNotEmpty()) put(JSONObject().put("Container", "mp4,m4v,mov,mkv,webm,ts,m2ts,mpegts")
            .put("Type", "Video").put("VideoCodec", video).put("AudioCodec", generalAudio.sorted().joinToString(",")))
        put(JSONObject().put("Container", "mp3").put("Type", "Audio").put("AudioCodec", "mp3"))
        put(JSONObject().put("Container", "aac,m4a,mp4,flac,ogg,oga,opus,webm,mka,wav").put("Type", "Audio")
            .put("AudioCodec", (generalAudio + "pcm_s16le").sorted().joinToString(",")))
        // FFmpeg 扩展只解码音频包。容器由 Media3 的 AMR、MP4、WAV 提取器处理，不能按 codec 扩大容器范围。
        if (amr.isNotEmpty()) put(JSONObject().put("Container", "amr,awb,3gp,3g2,mp4,m4a,m4v,mov").put("Type", "Audio")
            .put("AudioCodec", amr.sorted().joinToString(",")))
        if (g711.isNotEmpty()) put(JSONObject().put("Container", "wav,3gp,3g2,mp4,m4a,m4v,mov").put("Type", "Audio")
            .put("AudioCodec", g711.sorted().joinToString(",")))
        if (amr.isNotEmpty() || g711.isNotEmpty()) put(JSONObject().put("Container", "3gp,3g2,mp4,m4v,mov")
            .put("Type", "Video").put("VideoCodec", video).put("AudioCodec", (amr + g711).sorted().joinToString(",")))
    }

    private fun maximum(property: String, value: String): JSONObject = JSONObject()
        .put("Condition", "LessThanEqual")
        .put("Property", property)
        .put("Value", value)
        .put("IsRequired", false)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
        const val HOME_FIELDS = "PrimaryImageAspectRatio"
        const val POSTER_FIELDS = "MediaSourceCount,PrimaryImageAspectRatio"
        const val LIST_FIELDS = "Overview,MediaSourceCount,PrimaryImageAspectRatio,Genres,ChildCount,Tags,DateCreated,DateLastMediaAdded,DateLastSaved"
        const val DETAIL_FIELDS = "Overview,MediaSources,Genres,ChildCount,People,Studios,ExternalUrls,Tags,DateCreated,DateLastMediaAdded,CanDelete,CanDownload,Chapters,ProviderIds,ProductionLocations,OriginalTitle"
        const val IMAGE_TYPES = "Primary,Backdrop,Banner,Logo"
        val PERSON_PARAMETERS = setOf("userId", "startIndex", "limit", "searchTerm", "fields", "enableUserData", "imageTypeLimit", "enableImageTypes", "parentId", "isFavorite", "filters", "nameStartsWith", "nameStartsWithOrGreater", "nameLessThan")
        val ITEM_TYPES = setOf("AggregateFolder", "Audio", "AudioBook", "BasePluginFolder", "Book", "BoxSet", "Channel", "ChannelFolderItem", "CollectionFolder", "Episode", "Folder", "Genre", "ManualPlaylistsFolder", "Movie", "LiveTvChannel", "LiveTvProgram", "MusicAlbum", "MusicArtist", "MusicGenre", "MusicVideo", "Person", "Photo", "PhotoAlbum", "Playlist", "PlaylistsFolder", "Program", "Recording", "Season", "Series", "Studio", "Trailer", "TvChannel", "TvProgram", "UserRootFolder", "UserView", "Video", "Year")
    }
}

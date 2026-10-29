package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaChapter
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaPage
import dev.pathfin.android.domain.MediaPerson
import dev.pathfin.android.domain.MediaStudio
import dev.pathfin.android.domain.MediaUserData
import dev.pathfin.android.domain.ExternalLink
import dev.pathfin.android.domain.LibraryFilterOptions
import dev.pathfin.android.domain.PublicServerInfo
import dev.pathfin.android.domain.PublicUser
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** 集中映射 Jellyfin 的 PascalCase 字段，允许可选字段缺失或为 null。 */
object JsonMapping {
    fun session(json: JSONObject, config: ServerConfig, deviceId: String): Session {
        val user = json.optJSONObject("User") ?: invalidResponse()
        return sessionUser(Session(
            server = config,
            accessToken = json.requiredString("AccessToken"),
            userId = user.requiredString("Id"),
            userName = user.text("Name").orEmpty(),
            deviceId = deviceId,
            serverId = json.text("ServerId").orEmpty(),
        ), user)
    }

    fun sessionUser(session: Session, user: JSONObject): Session {
        val policy = user.optJSONObject("Policy")
        val enabled = policy?.opt("IsDisabled") != true
        return session.copy(
            userName = user.text("Name") ?: session.userName,
            userImageTag = user.text("PrimaryImageTag")?.takeIf(String::isNotBlank),
            isAdministrator = enabled && policy?.opt("IsAdministrator") == true,
            canDownload = enabled && policy?.opt("EnableContentDownloading") == true,
            canDelete = enabled && (policy?.opt("EnableContentDeletion") == true ||
                strings(policy?.optJSONArray("EnableContentDeletionFromFolders")).isNotEmpty()),
        )
    }

    fun mediaPage(json: JSONObject): MediaPage {
        val items = objects(json.optJSONArray("Items")).mapNotNull { item ->
            if (item.text("Id").isNullOrBlank()) null else mediaItem(item)
        }
        return MediaPage(items, maxOf(json.number("TotalRecordCount")?.toInt()?.coerceAtLeast(0) ?: items.size, items.size))
    }

    fun mediaItem(json: JSONObject): MediaItem {
        val userDataJson = json.optJSONObject("UserData")
        val id = json.requiredString("Id")
        val ownBackdropTag = firstImageTag(json.optJSONArray("BackdropImageTags"))
        val parentBackdropId = json.text("ParentBackdropItemId")?.takeIf { it.isNotBlank() }
        val parentBackdropTag = firstImageTag(json.optJSONArray("ParentBackdropImageTags"))
            ?.takeIf { parentBackdropId != null }
        val images = json.optJSONObject("ImageTags")
        val ownPrimaryTag = images?.text("Primary")?.takeIf(String::isNotBlank)
        val parentPrimaryId = json.text("ParentPrimaryImageItemId")?.takeIf(String::isNotBlank)
        val parentPrimaryTag = json.text("ParentPrimaryImageTag")?.takeIf { it.isNotBlank() && parentPrimaryId != null }
        val seriesId = json.text("SeriesId")
        val seriesPrimaryTag = json.text("SeriesPrimaryImageTag")?.takeIf { it.isNotBlank() && !seriesId.isNullOrBlank() }
        val ownLogoTag = images?.text("Logo")?.takeIf(String::isNotBlank)
        val parentLogoId = json.text("ParentLogoItemId")?.takeIf(String::isNotBlank)
        val parentLogoTag = json.text("ParentLogoImageTag")?.takeIf { it.isNotBlank() && parentLogoId != null }
        val data = userDataJson?.let(::userData)
        val year = json.positiveInt("ProductionYear")?.takeIf { it <= 9999 }
        return MediaItem(
            id = id,
            name = json.text("Name")?.takeIf { it.isNotBlank() } ?: tr("未命名媒体"),
            type = json.text("Type").orEmpty(),
            overview = json.text("Overview").orEmpty(),
            year = year,
            runTimeTicks = json.number("RunTimeTicks")?.coerceAtLeast(0L) ?: 0L,
            positionTicks = data?.positionTicks ?: 0L,
            imageTag = ownPrimaryTag ?: parentPrimaryTag ?: seriesPrimaryTag,
            seriesName = json.nonBlankText("SeriesName"),
            seriesId = seriesId,
            indexNumber = json.nonNegativeInt("IndexNumber"),
            parentIndexNumber = json.nonNegativeInt("ParentIndexNumber"),
            isFolder = json.optBoolean("IsFolder", false),
            backdropTag = ownBackdropTag ?: parentBackdropTag,
            backdropItemId = when {
                ownBackdropTag != null -> id
                parentBackdropTag != null -> parentBackdropId
                else -> null
            },
            communityRating = json.decimal("CommunityRating")?.takeIf { it >= 0.0 },
            officialRating = json.text("OfficialRating")?.takeIf { it.isNotBlank() },
            genres = strings(json.optJSONArray("Genres")),
            childCount = json.decimal("ChildCount")
                ?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt(),
            isFavorite = data?.isFavorite ?: false,
            played = data?.played ?: false,
            playedPercentage = data?.playedPercentage,
            unplayedItemCount = data?.unplayedItemCount,
            playCount = data?.playCount ?: 0,
            bannerTag = images?.text("Banner")?.takeIf(String::isNotBlank),
            logoTag = ownLogoTag ?: parentLogoTag,
            logoItemId = if (ownLogoTag != null) id else parentLogoId.takeIf { parentLogoTag != null },
            backdropTags = imageTags(json.optJSONArray(if (ownBackdropTag != null) "BackdropImageTags" else "ParentBackdropImageTags"))
                .takeIf { ownBackdropTag != null || parentBackdropTag != null }.orEmpty(),
            people = objects(json.optJSONArray("People")).mapNotNull { person ->
                val personId = person.text("Id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = person.text("Name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MediaPerson(personId, name, person.text("Role"), person.text("Type").orEmpty(), person.text("PrimaryImageTag"))
            },
            studios = objects(json.optJSONArray("Studios")).mapNotNull { studio ->
                val studioId = studio.text("Id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = studio.text("Name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MediaStudio(studioId, name)
            },
            externalUrls = objects(json.optJSONArray("ExternalUrls")).mapNotNull { link ->
                val url = link.text("Url")?.takeIf(::safeExternalUrl) ?: return@mapNotNull null
                val name = link.text("Name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                ExternalLink(name, url)
            },
            criticRating = json.decimal("CriticRating")?.takeIf { it in 0.0..100.0 },
            premiereDate = json.text("PremiereDate")?.takeIf(String::isNotBlank),
            tags = strings(json.optJSONArray("Tags")),
            status = json.text("Status")?.takeIf(String::isNotBlank),
            seasonId = json.text("SeasonId")?.takeIf(String::isNotBlank),
            collectionType = json.text("CollectionType")?.takeIf(String::isNotBlank),
            primaryImageItemId = when {
                ownPrimaryTag != null -> id
                parentPrimaryTag != null -> parentPrimaryId
                seriesPrimaryTag != null -> seriesId
                else -> null
            },
            canDownload = json.opt("CanDownload") as? Boolean,
            canDelete = json.opt("CanDelete") as? Boolean,
            chapters = json.optJSONArray("Chapters")?.let { chapters ->
                (0 until chapters.length()).mapNotNull { index ->
                    val chapter = chapters.optJSONObject(index) ?: return@mapNotNull null
                    val ticks = chapter.number("StartPositionTicks")?.takeIf { it >= 0 } ?: return@mapNotNull null
                    // 图片URL需要服务端原始下标，过滤空条目/非法时间和排序都不能重新编号。
                    MediaChapter(chapter.text("Name")?.takeIf(String::isNotBlank) ?: tr("章节 %1\$s", index + 1), ticks,
                        chapter.text("ImageTag")?.takeIf(String::isNotBlank), index)
                }.distinctBy { it.startPositionTicks }.sortedBy { it.startPositionTicks }
            }.orEmpty(),
            mediaSources = mediaSources(json.optJSONArray("MediaSources")),
            providerIds = json.optJSONObject("ProviderIds")?.let { values ->
                values.keys().asSequence().take(32).mapNotNull { key ->
                    val value = values.optString(key).trim()
                    if (key.length <= 64 && value.length in 1..256 && value.none(Char::isISOControl)) key to value else null
                }.toMap()
            }.orEmpty(),
            endYear = json.nonBlankText("EndDate")?.let(::dateYear)?.takeIf { year == null || it >= year },
            productionLocations = strings(json.optJSONArray("ProductionLocations")).map(String::trim).filter(String::isNotEmpty).distinct().take(8),
            endDate = json.nonBlankText("EndDate"),
            originalTitle = json.nonBlankText("OriginalTitle"),
        )
    }

    fun userData(json: JSONObject): MediaUserData = MediaUserData(
        isFavorite = json.opt("IsFavorite") as? Boolean,
        played = json.opt("Played") as? Boolean,
        positionTicks = json.number("PlaybackPositionTicks")?.coerceAtLeast(0L),
        playedPercentage = json.decimal("PlayedPercentage")?.takeIf { it in 0.0..100.0 },
        unplayedItemCount = json.nonNegativeInt("UnplayedItemCount"),
        playCount = json.nonNegativeInt("PlayCount"),
    )

    fun filterOptions(json: JSONObject): LibraryFilterOptions = LibraryFilterOptions(
        genres = strings(json.optJSONArray("Genres")).distinct().sorted(),
        tags = strings(json.optJSONArray("Tags")).distinct().sorted(),
        years = json.optJSONArray("Years")?.let { years -> (0 until years.length()).mapNotNull { index ->
            (years.opt(index) as? Number)?.toDouble()?.takeIf { it in 1.0..9999.0 && it % 1.0 == 0.0 }?.toInt()
        } }.orEmpty().distinct().sortedDescending(),
        officialRatings = strings(json.optJSONArray("OfficialRatings")).distinct().sorted(),
    )

    fun publicServerInfo(json: JSONObject): PublicServerInfo = PublicServerInfo(
        id = json.requiredString("Id"),
        name = json.text("ServerName").orEmpty(),
        version = json.text("Version").orEmpty(),
    )

    /** 公开账号列表只用于登录选择，缺少 Id 或名称的条目直接忽略。 */
    fun publicUsers(json: JSONArray): List<PublicUser> = (0 until json.length()).mapNotNull { index ->
        val user = json.optJSONObject(index) ?: return@mapNotNull null
        val id = user.text("Id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val name = user.text("Name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        PublicUser(id, name, user.text("PrimaryImageTag")?.takeIf(String::isNotBlank))
    }

    fun playbackInfo(json: JSONObject): PlaybackInfo = PlaybackInfo(
        sources = mediaSources(json.optJSONArray("MediaSources")),
        playSessionId = json.text("PlaySessionId").orEmpty(),
        errorCode = json.text("ErrorCode")?.takeIf { it.isNotBlank() },
    )

    /** 详情与 PlaybackInfo 共用映射，避免轨道信息在进入播放器前丢失。 */
    fun mediaSources(array: JSONArray?): List<MediaSource> = objects(array).mapNotNull { source ->
            val id = source.text("Id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val headers = source.optJSONObject("RequiredHttpHeaders")
            MediaSource(
                id = id,
                path = source.text("Path"),
                protocol = source.text("Protocol"),
                container = source.text("Container"),
                name = source.text("Name"),
                directStreamUrl = source.text("DirectStreamUrl"),
                transcodingUrl = source.text("TranscodingUrl"),
                supportsDirectPlay = source.optBoolean("SupportsDirectPlay", false),
                supportsDirectStream = source.optBoolean("SupportsDirectStream", false),
                requiredHttpHeaders = buildMap {
                    if (headers != null) for (key in headers.keys()) {
                        headers.text(key)?.let { put(key, it) }
                    }
                },
                mediaStreams = mediaStreams(source.optJSONArray("MediaStreams")),
                bitRate = source.positiveInt("Bitrate"),
                // STRM 片源的 Size 是 .strm 文本本身，服务器并不知道远端视频大小。
                size = source.number("Size")?.takeIf { it >= 0 && !source.optBoolean("IsRemote", false) },
                runTimeTicks = source.number("RunTimeTicks")?.takeIf { it >= 0 },
                // -1 是服务器明确关闭字幕；缺省 null 才交给播放器默认规则。
                defaultSubtitleStreamIndex = source.decimal("DefaultSubtitleStreamIndex")
                    ?.takeIf { it in -1.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt(),
                defaultAudioStreamIndex = source.nonNegativeInt("DefaultAudioStreamIndex"),
            )
        }

    private fun mediaStreams(array: JSONArray?): List<MediaStream> = objects(array).take(256).map { stream ->
        MediaStream(
            index = stream.nonNegativeInt("Index"),
            type = stream.nonBlankText("Type"),
            codec = stream.nonBlankText("Codec"),
            language = stream.nonBlankText("Language"),
            title = stream.nonBlankText("Title"),
            displayTitle = stream.nonBlankText("DisplayTitle"),
            isExternal = stream.opt("IsExternal") == true,
            deliveryUrl = stream.nonBlankText("DeliveryUrl"),
            deliveryMethod = stream.nonBlankText("DeliveryMethod"),
            isDefault = stream.opt("IsDefault") == true,
            isForced = stream.opt("IsForced") == true,
            isHearingImpaired = stream.opt("IsHearingImpaired") == true,
            profile = stream.nonBlankText("Profile"),
            level = stream.decimal("Level")?.takeIf { it >= 0 },
            width = stream.positiveInt("Width"),
            height = stream.positiveInt("Height"),
            averageFrameRate = stream.decimal("AverageFrameRate")?.takeIf { it > 0 },
            realFrameRate = stream.decimal("RealFrameRate")?.takeIf { it > 0 },
            bitDepth = stream.positiveInt("BitDepth"),
            bitRate = stream.positiveInt("BitRate"),
            channels = stream.positiveInt("Channels"),
            channelLayout = stream.nonBlankText("ChannelLayout"),
            sampleRate = stream.positiveInt("SampleRate"),
            videoRange = stream.nonBlankText("VideoRange"),
            videoRangeType = stream.nonBlankText("VideoRangeType"),
            videoDoViTitle = stream.nonBlankText("VideoDoViTitle"),
            dvProfile = stream.nonNegativeInt("DvProfile"),
            colorRange = stream.nonBlankText("ColorRange"),
            colorSpace = stream.nonBlankText("ColorSpace"),
            colorTransfer = stream.nonBlankText("ColorTransfer"),
            colorPrimaries = stream.nonBlankText("ColorPrimaries"),
            pixelFormat = stream.nonBlankText("PixelFormat"),
        )
    }

    internal fun JSONObject.text(name: String): String? = opt(name) as? String

    private fun JSONObject.nonBlankText(name: String): String? = text(name)?.takeIf(String::isNotBlank)

    private fun JSONObject.positiveInt(name: String): Int? = nonNegativeInt(name)?.takeIf { it > 0 }

    private fun dateYear(value: String): Int? = try {
        OffsetDateTime.parse(value).year.takeIf { it in 1..9999 }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JSONObject.number(name: String): Long? = when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        null -> null
        else -> null
    }

    private fun JSONObject.decimal(name: String): Double? = when (val value = opt(name)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        null -> null
        else -> null
    }?.takeIf { it.isFinite() }

    private fun JSONObject.nonNegativeInt(name: String): Int? = decimal(name)
        ?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()

    private fun safeExternalUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    // 索引必须与服务端数组保持一致，不能过滤空项后把第1张误当作第0张。
    private fun imageTags(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.opt(it) as? String ?: "" }

    // URL 请求第 0 张图片，不能把后续图片的 tag 配给该索引。
    private fun firstImageTag(array: JSONArray?): String? =
        (array?.opt(0) as? String)?.takeIf { it.isNotBlank() }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull {
            (array.opt(it) as? String)?.takeIf { value -> value.isNotBlank() }
        }

    private fun JSONObject.requiredString(name: String): String =
        text(name)?.takeIf { it.isNotBlank() } ?: invalidResponse()

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun invalidResponse(): Nothing = throw ApiException(null, tr("服务器返回的数据不完整，请稍后重试"))
}

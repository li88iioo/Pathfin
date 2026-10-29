package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/** 首页与媒体库共用的展示字段；请求头、播放地址和媒体源不进入磁盘快照。 */
internal object MediaSnapshotJson {
    fun items(values: List<MediaItem>) = JSONArray().apply { values.forEach { put(item(it)) } }
    fun items(values: JSONArray) = (0 until values.length()).map { item(values.getJSONObject(it)) }

    private fun item(media: MediaItem) = JSONObject()
        .put("id", media.id).put("name", media.name).put("type", media.type).put("overview", media.overview)
        .put("year", media.year).put("runTimeTicks", media.runTimeTicks).put("positionTicks", media.positionTicks)
        .put("imageTag", media.imageTag).put("primaryImageItemId", media.primaryImageItemId)
        .put("seriesName", media.seriesName).put("seriesId", media.seriesId)
        .put("indexNumber", media.indexNumber).put("parentIndexNumber", media.parentIndexNumber)
        .put("isFolder", media.isFolder).put("backdropTag", media.backdropTag).put("backdropItemId", media.backdropItemId)
        .put("communityRating", media.communityRating).put("officialRating", media.officialRating)
        .put("genres", JSONArray(media.genres)).put("childCount", media.childCount)
        .put("isFavorite", media.isFavorite).put("played", media.played).put("playedPercentage", media.playedPercentage)
        .put("unplayedItemCount", media.unplayedItemCount).put("playCount", media.playCount)
        .put("bannerTag", media.bannerTag).put("logoTag", media.logoTag).put("logoItemId", media.logoItemId)
        .put("backdropTags", JSONArray(media.backdropTags)).put("criticRating", media.criticRating)
        .put("premiereDate", media.premiereDate).put("tags", JSONArray(media.tags)).put("status", media.status)
        .put("seasonId", media.seasonId).put("collectionType", media.collectionType)
        .put("canDownload", media.canDownload).put("canDelete", media.canDelete).put("endYear", media.endYear)

    private fun item(json: JSONObject) = MediaItem(
        id = json.getString("id").also { require(it.isNotBlank()) },
        name = json.getString("name"), type = json.getString("type"), overview = json.getString("overview"),
        year = json.number("year")?.toInt(), runTimeTicks = json.getLong("runTimeTicks"),
        positionTicks = json.getLong("positionTicks"), imageTag = json.text("imageTag"),
        primaryImageItemId = json.text("primaryImageItemId"), seriesName = json.text("seriesName"), seriesId = json.text("seriesId"),
        indexNumber = json.number("indexNumber")?.toInt(), parentIndexNumber = json.number("parentIndexNumber")?.toInt(),
        isFolder = json.getBoolean("isFolder"), backdropTag = json.text("backdropTag"), backdropItemId = json.text("backdropItemId"),
        communityRating = json.number("communityRating")?.toDouble(), officialRating = json.text("officialRating"),
        genres = json.strings("genres"), childCount = json.number("childCount")?.toInt(),
        isFavorite = json.getBoolean("isFavorite"), played = json.getBoolean("played"),
        playedPercentage = json.number("playedPercentage")?.toDouble(), unplayedItemCount = json.number("unplayedItemCount")?.toInt(),
        playCount = json.getInt("playCount"), bannerTag = json.text("bannerTag"), logoTag = json.text("logoTag"), logoItemId = json.text("logoItemId"),
        backdropTags = json.strings("backdropTags"), criticRating = json.number("criticRating")?.toDouble(),
        premiereDate = json.text("premiereDate"), tags = json.strings("tags"), status = json.text("status"),
        seasonId = json.text("seasonId"), collectionType = json.text("collectionType"),
        canDownload = json.opt("canDownload") as? Boolean, canDelete = json.opt("canDelete") as? Boolean,
        endYear = json.number("endYear")?.toInt(),
    )

    private fun JSONObject.text(key: String) = opt(key) as? String
    private fun JSONObject.number(key: String) = opt(key) as? Number
    private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { values ->
        (0 until values.length()).map { values.getString(it) }
    }
}

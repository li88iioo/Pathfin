package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** 同进程快照的文件事务；写入、删除及容量清扫共用一把锁，不会碰到另一份正在写的临时文件。 */
internal object SnapshotFiles {
    val lock = Any()

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun sessionFingerprint(session: Session): String = digest(listOf(session.serverIdentity, session.userId,
        session.accessToken, session.deviceId, session.isAdministrator, session.canDownload, session.canDelete).joinToString("\u0000"))

    fun write(target: File, json: JSONObject, maxBytes: Long, context: CoroutineContext? = null): Boolean = synchronized(lock) {
        var temporary: File? = null
        try {
            context?.ensureActive()
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > maxBytes) return@synchronized false
            val directory = target.parentFile ?: return@synchronized false
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized false
            temporary = File.createTempFile("snapshot-", ".tmp", directory)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            // 取消与清理共用提交边界；排在删账号之后的旧写入不能重新创建快照。
            context?.ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        finally { temporary?.delete() }
    }

    fun retain(directory: File, names: Set<String>) = synchronized(lock) {
        directory.listFiles()?.filter { it.isFile && it.extension == "json" && it.name !in names }?.forEach { it.delete() }
        Unit
    }
}

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

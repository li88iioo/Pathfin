package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaSegment
import dev.pathfin.android.domain.MediaSegmentType
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import org.json.JSONArray
import org.json.JSONObject

/** 普通已登录用户只读接口；不触发片段扫描，旧服务器 404 视为未提供片段。 */
internal suspend fun JellyfinApi.mediaSegments(session: Session, itemId: String): List<MediaSegment> {
    require(segmentUuid(itemId) != null) { tr("媒体片段标识无效") }
    val trace = PerformanceTrace.begin("media_segments")
    return try {
        val response = try {
            authenticatedObject(session, "MediaSegments/$itemId", mapOf("includeSegmentTypes" to "Intro"))
        } catch (error: ApiException) {
            if (error.statusCode == 404) return emptyList()
            throw error
        }
        parseMediaSegments(response, itemId)
    } finally { trace.point("complete") }
}

internal fun parseMediaSegments(response: JSONObject, itemId: String): List<MediaSegment> {
    val expected = segmentUuid(itemId) ?: throw IllegalArgumentException(tr("媒体片段标识无效"))
    fun malformed(): Nothing = throw ApiException(null, tr("服务器返回的片头片段格式不正确"))
    val items = response.opt("Items") as? JSONArray ?: malformed()
    if (items.length() > 256) malformed()
    val seen = mutableSetOf<String>()
    return (0 until items.length()).map { index ->
        val item = items.optJSONObject(index) ?: malformed()
        val id = item.opt("Id") as? String ?: malformed()
        val key = segmentUuid(id) ?: malformed()
        val associatedId = item.opt("ItemId") as? String ?: malformed()
        if (segmentUuid(associatedId) != expected || !seen.add(key) || item.opt("Type") != "Intro") malformed()
        fun ticks(name: String): Long = when (val value = item.opt(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> malformed()
        }
        val start = ticks("StartTicks")
        val end = ticks("EndTicks")
        if (start < 0 || end <= start || end / 10_000 <= start / 10_000) malformed()
        MediaSegment(key, associatedId, MediaSegmentType.INTRO, start, end)
    }.sortedWith(compareBy(MediaSegment::startTicks, MediaSegment::endTicks))
}

private fun segmentUuid(value: String): String? {
    if (!value.matches(Regex("(?:[A-Fa-f0-9]{32}|[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12})"))) return null
    return value.replace("-", "").lowercase().takeUnless { it.all { char -> char == '0' } }
}

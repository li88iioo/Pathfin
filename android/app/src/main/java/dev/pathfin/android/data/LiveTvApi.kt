package dev.pathfin.android.data

import dev.pathfin.android.domain.LiveTvCapabilities
import dev.pathfin.android.domain.LiveTvChannel
import dev.pathfin.android.domain.LiveTvPage
import dev.pathfin.android.domain.LiveTvProgram
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import java.time.Duration
import java.time.Instant
import org.json.JSONObject

/** Jellyfin 12.3 的只读直播接口。所有网络行为复用同源鉴权、限流和取消能力。 */
class LiveTvApi(private val api: JellyfinApi) {
    private data class Access(val session: Session, val capabilities: LiveTvCapabilities)
    @Volatile private var access: Access? = null

    /** 重读频道快照时更新权限；缓存仅用于同一会话内的后续分页和节目单。 */
    suspend fun capabilities(session: Session): LiveTvCapabilities {
        access = null
        val user = api.authenticatedObject(session, "Users/Me")
        if (user.opt("Id") != session.userId) throw ApiException(401, tr("登录已失效，请重新登录"))
        val policy = user.optJSONObject("Policy")
        val canView = policy?.opt("IsDisabled") != true && policy?.opt("EnableLiveTvAccess") == true
        if (!canView) return LiveTvCapabilities(false, false, false).also { access = Access(session, it) }
        val info = api.authenticatedObject(session, "LiveTv/Info")
        val result = LiveTvCapabilities(true, policy?.opt("EnableMediaPlayback") == true, info.opt("IsEnabled") == true)
        access = Access(session, result)
        return result
    }

    suspend fun channels(session: Session, startIndex: Int = 0, limit: Int = 40): LiveTvPage<LiveTvChannel> {
        requirePage(startIndex, limit)
        val permitted = requireAccess(session)
        if (!permitted.isConfigured) return LiveTvPage(emptyList(), 0, startIndex)
        val json = read(session, "LiveTv/Channels", mapOf(
            "userId" to session.userId, "startIndex" to startIndex.toString(), "limit" to limit.toString(),
            "enableImages" to "true", "enableImageTypes" to "Primary", "imageTypeLimit" to "1",
            "enableUserData" to "true", "addCurrentProgram" to "true", "sortBy" to "SortName", "sortOrder" to "Ascending",
        ))
        return page(json, startIndex, limit) { item ->
            val media = JsonMapping.mediaItem(item)
            if (!IDENTIFIER.matches(media.id)) invalid(tr("频道标识"))
            val channel = media.copy(name = media.name.take(512), overview = media.overview.take(20_000),
                type = when (media.type) { "TvChannel", "LiveTvChannel" -> media.type; else -> "TvChannel" }, positionTicks = 0, runTimeTicks = 0)
            LiveTvChannel(channel, text(item, "ChannelNumber"), item.optJSONObject("CurrentProgram")?.let { program(it, media.id) })
        }.also { page -> if (page.items.map { it.id }.distinct().size != page.items.size) invalid(tr("重复频道")) }
    }

    /** 查询与本地日期窗口相交的节目，节目单不会被当成点播或时移回放。 */
    suspend fun programs(
        session: Session,
        channelId: String,
        from: Instant,
        until: Instant,
        startIndex: Int = 0,
        limit: Int = 80,
    ): LiveTvPage<LiveTvProgram> {
        require(IDENTIFIER.matches(channelId)) { tr("频道标识无效") }
        require(until > from && Duration.between(from, until) <= Duration.ofDays(2)) { tr("节目单时间范围应在两天以内") }
        requirePage(startIndex, limit)
        if (!requireAccess(session).isConfigured) return LiveTvPage(emptyList(), 0, startIndex)
        val json = read(session, "LiveTv/Programs", mapOf(
            "userId" to session.userId, "channelIds" to channelId, "minEndDate" to from.toString(), "maxStartDate" to until.toString(),
            "startIndex" to startIndex.toString(), "limit" to limit.toString(), "sortBy" to "StartDate", "sortOrder" to "Ascending",
            "fields" to "Overview", "enableImages" to "false", "enableUserData" to "false", "enableTotalRecordCount" to "true",
        ))
        return page(json, startIndex, limit) { program(it, channelId) }
            .also { page -> if (page.items.map { it.id }.distinct().size != page.items.size) invalid(tr("重复节目")) }
    }

    private suspend fun requireAccess(session: Session): LiveTvCapabilities {
        val result = access?.takeIf { it.session == session }?.capabilities ?: capabilities(session)
        if (!result.canView) throw ApiException(403, tr("当前账号没有直播电视访问权限"))
        return result
    }

    private suspend fun read(session: Session, path: String, query: Map<String, String>): JSONObject = try {
        api.authenticatedObject(session, path, query)
    } catch (error: ApiException) {
        if (error.statusCode in setOf(401, 403)) access = null
        throw error
    }

    private fun program(json: JSONObject, expectedChannelId: String): LiveTvProgram {
        val id = text(json, "Id")?.takeIf(IDENTIFIER::matches) ?: invalid(tr("节目标识"))
        val channelId = text(json, "ChannelId") ?: expectedChannelId
        if (channelId != expectedChannelId) invalid(tr("节目所属频道"))
        val start = date(json, "StartDate")
        val end = date(json, "EndDate")
        if (start != null && end != null && end <= start) invalid(tr("节目起止时间"))
        return LiveTvProgram(id, channelId, (text(json, "Name") ?: tr("未命名节目")).take(512), text(json, "EpisodeTitle")?.take(512),
            text(json, "Overview").orEmpty().take(20_000), start, end, json.opt("IsRepeat") == true)
    }

    private fun <T> page(json: JSONObject, startIndex: Int, limit: Int, map: (JSONObject) -> T): LiveTvPage<T> {
        val array = json.optJSONArray("Items") ?: invalid(tr("列表"))
        val total = integer(json.opt("TotalRecordCount")) ?: invalid(tr("列表总数"))
        if (array.length() > limit || (array.length() > 0 && total < startIndex.toLong() + array.length())) invalid(tr("分页范围"))
        if (json.has("StartIndex") && integer(json.opt("StartIndex")) != startIndex) invalid(tr("分页位置"))
        if (array.length() == 0 && startIndex < total) invalid(tr("空分页"))
        val items = (0 until array.length()).map { map(array.optJSONObject(it) ?: invalid(tr("列表条目"))) }
        return LiveTvPage(items, total, startIndex)
    }

    private fun requirePage(startIndex: Int, limit: Int) {
        require(startIndex in 0..(Int.MAX_VALUE - 100) && limit in 1..100) { tr("直播列表分页范围无效") }
    }

    private fun integer(value: Any?): Int? = (value as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()

    private fun text(json: JSONObject, field: String): String? = (json.opt(field) as? String)?.trim()?.takeIf(String::isNotEmpty)
    private fun date(json: JSONObject, field: String): Instant? = text(json, field)?.let {
        try { Instant.parse(it) } catch (_: Exception) { invalid(tr("节目时间格式")) }
    }

    private fun invalid(field: String): Nothing = throw ApiException(null, tr("服务器返回的直播%1\$s 格式不正确，请刷新重试", field))

    private companion object { val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,200}") }
}

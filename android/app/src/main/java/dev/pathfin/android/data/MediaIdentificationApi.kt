package dev.pathfin.android.data

import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import org.json.JSONObject

/** 搜索不会写入条目；Apply 只接受本账号本条目搜索取得的完整候选。 */
class MediaIdentificationApi(private val api: JellyfinApi = JellyfinApi()) {
    suspend fun search(session: Session, item: MediaItem, query: MediaIdentificationQuery): List<MediaIdentificationResult> {
        val type = requireSupported(session, item)
        validateQuery(query)
        val info = JSONObject().put("Name", query.name.trim()).put("ProviderIds", JSONObject(query.providerIds))
        query.year?.let { info.put("Year", it) }
        val body = JSONObject().put("ItemId", item.id).put("SearchInfo", info).put("IncludeDisabledProviders", false)
        val results = api.managementArray(session, ManagementOperation.IDENTIFY_SEARCH,
            "Items/RemoteSearch/${type.apiValue}", body = body)
        if (results.length() > MAX_RESULTS) throw ApiException(null, tr("识别结果过多，请填写年份或外部编号缩小搜索范围"))
        return (0 until results.length()).map { index ->
            val raw = results.optJSONObject(index) ?: throw ApiException(null, tr("服务器返回的识别结果格式不正确"))
            val name = (raw.opt("Name") as? String)?.takeIf(String::isNotBlank)
                ?: throw ApiException(null, tr("服务器返回的识别结果缺少名称"))
            val idsJson = raw.optJSONObject("ProviderIds") ?: JSONObject()
            val ids = idsJson.keys().asSequence().associateWith { key ->
                idsJson.opt(key) as? String ?: throw ApiException(null, tr("服务器返回的外部编号格式不正确"))
            }
            val year = (raw.opt("ProductionYear") as? Number)?.toInt()?.takeIf { it in 1..9999 }
            val provider = (raw.opt("SearchProviderName") as? String)?.takeIf(String::isNotBlank)
            val key = listOf(provider.orEmpty(), name, year?.toString().orEmpty(), ids.toSortedMap().toString()).joinToString("|")
            val image = (raw.opt("ImageUrl") as? String)?.trim()?.takeIf { it.length <= 2048 && ServerUrls.httpUri(it) != null }
            MediaIdentificationResult(key, name, year, ids, provider, raw.opt("Overview") as? String, image,
                raw.toString(), session.server, session.userId, item.id, type)
        }.distinctBy { it.key }
    }

    suspend fun apply(session: Session, item: MediaItem, result: MediaIdentificationResult,
        options: MediaIdentificationOptions = MediaIdentificationOptions()) {
        val type = requireSupported(session, item)
        require(result.server == session.server && result.userId == session.userId && result.itemId == item.id && result.itemType == type) {
            tr("账号或媒体已经变化，请重新搜索识别结果")
        }
        require(result.providerIds.isNotEmpty()) { tr("此结果没有外部编号，无法可靠识别；请选择其他结果") }
        api.managementObject(session, ManagementOperation.IDENTIFY_APPLY, "Items/RemoteSearch/Apply/${item.id}",
            query = mapOf("replaceAllImages" to options.replaceAllImages.toString()), body = JSONObject(result.originalJson))
    }

    private fun requireSupported(session: Session, item: MediaItem): MediaIdentificationType {
        if (!session.isAdministrator) throw ApiException(403, tr("只有服务器管理员可以识别媒体"))
        require(item.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { tr("媒体标识无效") }
        return MediaIdentificationType.forItem(item) ?: throw IllegalArgumentException(tr("该媒体类型不支持远程识别"))
    }

    private fun validateQuery(query: MediaIdentificationQuery) {
        require(query.name.length <= 512 && query.name.none(Char::isISOControl)) { tr("名称不能超过 512 个字符或包含控制字符") }
        require(query.year == null || query.year in 1..9999) { tr("年份应为 1 到 9999") }
        require(query.providerIds.size <= 20 && query.providerIds.all { (key, value) ->
            key.matches(MediaManagementApi.PROVIDER_KEY) && value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
        }) { tr("外部编号无效") }
        require(query.name.isNotBlank() || query.providerIds.isNotEmpty()) { tr("请输入名称或至少一个外部编号") }
    }

    companion object {
        const val MAX_RESULTS = 200
        fun supports(session: Session, item: MediaItem): Boolean = session.isAdministrator && MediaIdentificationType.forItem(item) != null
    }
}

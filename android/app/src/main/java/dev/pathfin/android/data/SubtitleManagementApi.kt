package dev.pathfin.android.data

import dev.pathfin.android.domain.ManagedSubtitle
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.RemoteSubtitle
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.SubtitleDocument
import dev.pathfin.android.domain.SubtitleLanguages
import dev.pathfin.android.domain.SubtitlePermissions
import dev.pathfin.android.domain.SubtitleUploadFile
import dev.pathfin.android.i18n.tr
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.json.JSONObject

/** 官方 SubtitleController 协议；共用 JellyfinApi 的鉴权、取消、限流和安全错误处理。 */
class SubtitleManagementApi(private val api: JellyfinApi = JellyfinApi()) {
    suspend fun document(session: Session, itemId: String, sourceId: String? = null): SubtitleDocument = coroutineScope {
        requireIdentifier(itemId)
        val user = async { api.managementObject(session, ManagementOperation.SUBTITLE_POLICY, "Users/Me") }
        val media = async { api.item(session, checkNotNull(normalizedIdentifier(itemId))) }
        val item = media.await()
        requireVideo(item)
        val account = user.await()
        if (account.optString("Id") != session.userId) throw ApiException(401, tr("登录已失效，请重新登录"))
        val policy = account.optJSONObject("Policy")
        val administrator = policy?.opt("IsAdministrator") == true
        val permissions = SubtitlePermissions(policy?.opt("EnableSubtitleManagement") == true, administrator)
        // Jellyfin 按分辨率等属性排序 MediaSources；第一项不一定对应写入端点的 itemId。
        val source = if (sourceId == null) item.mediaSources.firstOrNull { sameIdentifier(it.id, item.id) } else
            item.mediaSources.firstOrNull { sameIdentifier(it.id, sourceId) } ?: throw ApiException(404, tr("所选媒体版本已不存在，请重新打开字幕管理"))
        val tracks = source?.mediaStreams.orEmpty().filter {
            it.type.equals("Subtitle", true) && it.index != null && it.index >= 0
        }.map { ManagedSubtitle(requireNotNull(source).id, it) }
        SubtitleDocument(item, source?.id, permissions, tracks, session, source != null && sameIdentifier(source.id, item.id))
    }

    suspend fun search(session: Session, document: SubtitleDocument, language: String, perfectMatch: Boolean): List<RemoteSubtitle> {
        requireManagement(session, document)
        val code = SubtitleLanguages.normalize(language)
        require(code != "und") { tr("请选择搜索字幕的语言") }
        val array = api.managementArray(session, ManagementOperation.SUBTITLE_SEARCH,
            "Items/${document.item.id}/RemoteSearch/Subtitles/$code", mapOf("isPerfectMatch" to perfectMatch.toString()))
        if (array.length() > 1000) throw ApiException(null, tr("字幕搜索结果过多，请使用精准匹配"))
        return withContext(Dispatchers.Default) {
            (0 until array.length()).map { index ->
                val value = array.optJSONObject(index) ?: throw ApiException(null, tr("服务器返回的字幕列表格式不正确"))
                val id = text(value, "Id", 4096)?.takeIf(::validRemoteIdentifier)
                    ?: throw ApiException(null, tr("服务器返回的字幕标识无效"))
                RemoteSubtitle(id, text(value, "Name", 2048) ?: tr("未命名字幕"), text(value, "ProviderName", 256) ?: tr("字幕提供商"),
                    text(value, "ThreeLetterISOLanguageName", 32), text(value, "Format", 32), text(value, "Author", 256),
                    text(value, "Comment", 2048), (value.opt("DownloadCount") as? Number)?.toInt()?.takeIf { it >= 0 },
                    (value.opt("CommunityRating") as? Number)?.toDouble()?.takeIf { it.isFinite() },
                    value.opt("IsHashMatch") == true, value.opt("Forced") == true, value.opt("HearingImpaired") == true,
                    document.item.id, session)
            }.distinctBy { it.id }
        }
    }

    /** Jellyfin 下载端点即使提供商内部失败也可能返回 204；调用者必须重读目录确认结果。 */
    suspend fun download(session: Session, document: SubtitleDocument, subtitle: RemoteSubtitle) {
        requireManagement(session, document)
        require(subtitle.authentication.hasSameAuthentication(session) && subtitle.itemId == document.item.id) {
            tr("字幕结果与当前媒体或服务器账号不一致，请重新搜索")
        }
        require(validRemoteIdentifier(subtitle.id)) { tr("字幕标识无效，请重新搜索") }
        api.managementObject(session, ManagementOperation.SUBTITLE_DOWNLOAD,
            "Items/${document.item.id}/RemoteSearch/Subtitles/${segment(subtitle.id)}", method = "POST")
    }

    suspend fun upload(session: Session, document: SubtitleDocument, file: SubtitleUploadFile, language: String,
        forced: Boolean, hearingImpaired: Boolean) {
        requireManagement(session, document)
        val code = SubtitleLanguages.normalize(language)
        val body = withContext(Dispatchers.Default) {
            JSONObject().put("Language", code).put("Format", file.format).put("IsForced", forced)
                .put("IsHearingImpaired", hearingImpaired).put("Data", Base64.getEncoder().encodeToString(file.content))
        }
        api.managementObject(session, ManagementOperation.SUBTITLE_UPLOAD, "Videos/${document.item.id}/Subtitles", body = body)
    }

    suspend fun delete(session: Session, document: SubtitleDocument, subtitle: ManagedSubtitle) {
        requireBound(session, document)
        if (!document.permissions.canDelete) throw ApiException(403, tr("只有管理员可以删除服务器字幕"))
        require(document.isDefaultSource && subtitle.isExternal && document.tracks.any { it === subtitle }) {
            tr("只能删除当前媒体的外部字幕")
        }
        // 删除端点按轨道序号操作；确认期间若已更新，不能误删后来复用该序号的轨道。
        val latest = this.document(session, document.item.id, document.sourceId)
        if (!latest.permissions.canDelete) throw ApiException(403, tr("只有管理员可以删除服务器字幕"))
        val current = latest.tracks.firstOrNull { it.index == subtitle.index }
        require(latest.isDefaultSource && current?.isExternal == true && current.sourceId == subtitle.sourceId && current.stream == subtitle.stream) {
            tr("字幕列表已变化，请重新打开后操作")
        }
        api.managementObject(session, ManagementOperation.SUBTITLE_DELETE,
            "Videos/${document.item.id}/Subtitles/${subtitle.index}", method = "DELETE")
    }

    private fun requireManagement(session: Session, document: SubtitleDocument) {
        requireBound(session, document)
        if (!document.permissions.canManage) throw ApiException(403, tr("当前账号没有管理服务器字幕的权限"))
        require(document.isDefaultSource) { tr("该字幕操作只适用于当前条目的默认媒体版本") }
    }

    private fun requireBound(session: Session, document: SubtitleDocument) {
        requireIdentifier(document.item.id)
        require(document.authentication.hasSameAuthentication(session)) { tr("服务器账号已切换，请重新打开字幕管理") }
    }

    private fun requireIdentifier(value: String) = require(normalizedIdentifier(value) != null) { tr("媒体标识无效") }
    private fun sameIdentifier(left: String, right: String): Boolean =
        normalizedIdentifier(left)?.let { it == normalizedIdentifier(right) } == true

    private fun normalizedIdentifier(value: String): String? = value.takeIf {
        COMPACT_IDENTIFIER.matches(it) || HYPHENATED_IDENTIFIER.matches(it)
    }?.replace("-", "")?.lowercase(Locale.ROOT)
    private fun requireVideo(item: MediaItem) = require(listOf("Movie", "Episode", "Video", "MusicVideo", "Trailer").any { it.equals(item.type, true) }) {
        tr("此媒体不支持字幕管理")
    }
    private fun text(value: JSONObject, key: String, maximum: Int): String? =
        (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= maximum && it.none(Char::isISOControl) }

    private fun validRemoteIdentifier(value: String): Boolean = value.isNotBlank() && value.length <= 4096 &&
        value.none { it.isISOControl() || it == '\\' } && value.split('/').none { it == "." || it == ".." }

    private fun segment(value: String): String = HttpUrl.Builder().scheme("https").host("pathfin.invalid")
        .addPathSegment(value).build().encodedPath.removePrefix("/")

    private companion object {
        val COMPACT_IDENTIFIER = Regex("[0-9a-fA-F]{32}")
        val HYPHENATED_IDENTIFIER = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

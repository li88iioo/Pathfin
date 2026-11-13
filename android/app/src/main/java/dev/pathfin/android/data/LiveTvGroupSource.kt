package dev.pathfin.android.data

import android.content.Context
import dev.pathfin.android.domain.LiveTvGroups
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 频道分组取自服务器 M3U 调谐器的原始播放列表：管理员账号从直播设置读到地址后直接下载（不带 Jellyfin 凭据）。
 * 只缓存频道名与分组名，不保存播放列表地址或片源；调谐器在内网时，离家沿用上次在家读到的分组。
 */
class LiveTvGroupSource(private val directory: File, private val client: OkHttpClient = DefaultClient,
    private val ensureNetworkAccess: suspend (String) -> Unit) {
    constructor(context: Context, ensureNetworkAccess: suspend (String) -> Unit) :
        this(File(context.filesDir, "live-tv-groups"), ensureNetworkAccess = ensureNetworkAccess)

    suspend fun cached(session: Session): LiveTvGroups? = withContext(Dispatchers.IO) {
        runCatching {
            val file = file(session).takeIf { it.isFile && it.length() <= MaxCacheBytes } ?: return@runCatching null
            val channels = JSONObject(file.readText()).getJSONArray("channels")
            LiveTvGroups((0 until channels.length()).map { channels.getJSONArray(it).let { entry -> entry.getString(0) to entry.getString(1) } })
        }.getOrNull()
    }

    /** 非管理员读不到调谐器地址；任一播放列表读取失败时返回 null，不用残缺结果覆盖缓存。 */
    suspend fun refresh(session: Session, api: JellyfinApi): LiveTvGroups? {
        if (!session.isAdministrator) return null
        val hosts = api.authenticatedObject(session, "System/Configuration/livetv").optJSONArray("TunerHosts") ?: return null
        val playlists = (0 until hosts.length()).mapNotNull(hosts::optJSONObject)
            .filter { it.optString("Type").equals("m3u", ignoreCase = true) }
            .map { host -> (host.optString("Url").takeIf { ServerUrls.httpUri(it) != null } ?: return null) to host.optString("UserAgent") }
        val parsed = playlists.map { (url, userAgent) ->
            // 等待现有授权流程完成后才连接调谐器；拒绝或取消沿调用链返回，不改写缓存。
            ensureNetworkAccess(url)
            withContext(Dispatchers.IO) { runCatching { LiveTvGroups.parse(download(url, userAgent)) }.getOrNull() } ?: return null
        }
        val merged = LiveTvGroups(parsed.flatMap { it.entries }.distinctBy { it.first })
        withContext(Dispatchers.IO) { runCatching { save(session, merged) } }
        return merged
    }

    private fun download(url: String, userAgent: String): String {
        val request = Request.Builder().url(url).apply { if (userAgent.isNotBlank()) header("User-Agent", userAgent) }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body.source()
            if (source.request(MaxPlaylistBytes + 1)) throw IOException("playlist too large")
            val playlist = source.buffer.readUtf8()
            // 200 登录页等无效响应不能被当成空分组；兼容扩展 M3U、无头 EXTINF 与纯 HTTP 地址列表。
            if (playlist.lineSequence().none { raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                line.startsWith("#EXTM3U", true) || line.startsWith("#EXTINF:", true) || ServerUrls.httpUri(line) != null
            }) throw IOException("invalid M3U playlist")
            return playlist
        }
    }

    private fun save(session: Session, groups: LiveTvGroups) {
        directory.mkdirs()
        val json = JSONObject().put("channels", JSONArray(groups.entries.map { (name, group) -> JSONArray(listOf(name, group)) }))
        val target = file(session)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) { temp.delete(); throw IOException("rename failed") }
    }

    private fun file(session: Session): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(session.serverIdentity.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object {
        const val MaxPlaylistBytes = 8L * 1024 * 1024
        const val MaxCacheBytes = 2L * 1024 * 1024
        val DefaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        }
    }
}

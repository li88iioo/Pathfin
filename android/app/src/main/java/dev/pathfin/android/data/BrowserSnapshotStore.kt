package dev.pathfin.android.data

import android.content.Context
import dev.pathfin.android.domain.BrowserPageKey
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaPage
import dev.pathfin.android.domain.ProfileHomeKey
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 媒体库首屏的有限展示快照。所有调用均放在既有存储 IO 队列；加载成功仍须向服务器重验。
 * 搜索与后续分页不保存，会话及权限只进入不可逆文件指纹。
 */
internal class BrowserSnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.cacheDir, "browser-snapshots"))

    fun load(session: Session, key: BrowserPageKey): MediaPage? {
        if (!eligible(session, key)) return null
        val file = file(session, key)
        val entry = read(file) ?: return null
        // 访问时间仅用于容量淘汰，不能作为网络内容仍然新鲜的依据。
        file.setLastModified(System.currentTimeMillis())
        return entry.page
    }

    fun save(session: Session, key: BrowserPageKey, page: MediaPage): Boolean {
        if (!eligible(session, key)) return false
        val entry = Entry(key.browserId, key.parentId, key.query, page.copy(items = page.items.take(MAX_ITEMS)))
        val file = file(session, key)
        if (!write(file, entry)) return false
        prune(file)
        return true
    }

    fun removeProfile(profileId: String) {
        val prefix = "${digest(profileId)}-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /** 主动刷新同时失效同父库下的字母、筛选及入口别名，保留其他会话与父库。 */
    fun invalidate(session: Session, scope: ProfileHomeKey, parentId: String?) {
        if (scope != ProfileHomeKey.of(scope.profileId, session)) return
        val prefix = profileSessionPrefix(scope.profileId, session)
        directory.listFiles()?.filter { it.extension == "json" && it.name.startsWith(prefix) }?.forEach { file ->
            val entry = read(file) ?: return@forEach
            if (entry.parentId == parentId) file.delete()
        }
    }

    /** 用户状态变化只修改已有条目；影响成员或排序的查询直接丢弃，交由下次网络请求重建。 */
    fun update(
        session: Session,
        profileId: String,
        change: (MediaItem) -> MediaItem,
        invalidates: (LibraryQuery) -> Boolean,
    ) {
        val prefix = profileSessionPrefix(profileId, session)
        directory.listFiles()?.filter { it.extension == "json" && it.name.startsWith(prefix) }?.forEach { file ->
            val entry = read(file) ?: return@forEach
            if (invalidates(entry.query)) {
                file.delete()
            } else {
                val updated = entry.page.copy(items = entry.page.items.map(change))
                if (updated != entry.page) write(file, entry.copy(page = updated))
            }
        }
        prune()
    }

    private data class Entry(
        val browserId: String,
        val parentId: String?,
        val query: LibraryQuery,
        val page: MediaPage,
    ) {
        fun keyJson() = JSONObject().put("browserId", browserId)
            .put("parentId", parentId ?: JSONObject.NULL).put("query", queryJson(query))
    }

    private fun eligible(session: Session, key: BrowserPageKey): Boolean =
        key.scope == ProfileHomeKey.of(key.scope.profileId, session) &&
            key.query.searchTerm.isEmpty() && !key.browserId.startsWith("library-search/")

    private fun file(session: Session, key: BrowserPageKey): File {
        val entry = Entry(key.browserId, key.parentId, key.query, MediaPage(emptyList(), 0))
        return File(directory, "${profileSessionPrefix(key.scope.profileId, session)}${digest(entry.keyJson().toString())}.json")
    }

    private fun profileSessionPrefix(profileId: String, session: Session): String {
        val identity = listOf(ServerUrls.normalize(session.server).baseUrl, session.server.allowInsecureHttp,
            session.serverId, session.userId, session.accessToken, session.deviceId,
            session.isAdministrator, session.canDownload, session.canDelete).joinToString("\u0000")
        return "${digest(profileId)}-${digest(identity)}-"
    }

    private fun read(file: File): Entry? {
        if (!file.isFile) return null
        return try {
            require(file.length() <= MAX_FILE_BYTES)
            val json = JSONObject(file.readText())
            require(json.getInt("version") == VERSION)
            val values = json.getJSONArray("items")
            require(values.length() <= MAX_ITEMS)
            val entry = Entry(json.getString("browserId"), json.nullableString("parentId"),
                query(json.getJSONObject("query")), MediaPage(MediaSnapshotJson.items(values), json.getInt("totalCount")))
            require(entry.query.searchTerm.isEmpty() && !entry.browserId.startsWith("library-search/"))
            require(entry.page.totalCount >= entry.page.items.size)
            require(file.name.endsWith("-${digest(entry.keyJson().toString())}.json"))
            entry
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun write(file: File, entry: Entry): Boolean {
        var temporary: File? = null
        return try {
            if (entry.page.totalCount < entry.page.items.size) return false
            val bytes = entry.keyJson().put("version", VERSION).put("totalCount", entry.page.totalCount)
                .put("items", MediaSnapshotJson.items(entry.page.items)).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) return false
            if (!directory.isDirectory && !directory.mkdirs()) return false
            temporary = File.createTempFile("browser-", ".tmp", directory)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary?.delete()
        }
    }

    private fun prune(newest: File? = null) {
        val files = directory.listFiles() ?: return
        files.filter { it.extension == "tmp" }.forEach { it.delete() }
        val ordered = files.filter { it.isFile && it.extension == "json" }.sortedWith(
            compareByDescending<File> { it == newest }.thenByDescending { it.lastModified() }.thenBy { it.name },
        )
        val profileCounts = mutableMapOf<String, Int>()
        var retainedCount = 0
        var retainedBytes = 0L
        ordered.forEach { file ->
            val profile = file.name.substringBefore('-')
            val count = profileCounts[profile] ?: 0
            val size = file.length()
            if (count >= MAX_PROFILE_ENTRIES || retainedCount >= MAX_TOTAL_ENTRIES ||
                size > MAX_FILE_BYTES || retainedBytes + size > MAX_TOTAL_BYTES) {
                file.delete()
            } else {
                profileCounts[profile] = count + 1
                retainedCount++
                retainedBytes += size
            }
        }
    }

    private companion object {
        const val VERSION = 3
        const val MAX_ITEMS = 40
        const val MAX_PROFILE_ENTRIES = 16
        const val MAX_TOTAL_ENTRIES = 64
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32 * 1024 * 1024

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun queryJson(query: LibraryQuery) = JSONObject()
            .put("tab", query.tab.name).put("sort", query.sort.name).put("descending", query.descending)
            .put("played", query.played ?: JSONObject.NULL).put("favorite", query.favorite ?: JSONObject.NULL)
            .put("seriesStatus", JSONArray(query.seriesStatus.sorted())).put("genres", JSONArray(query.genres.sorted()))
            .put("tags", JSONArray(query.tags.sorted())).put("years", JSONArray(query.years.sorted()))
            .put("officialRatings", JSONArray(query.officialRatings.sorted())).put("itemTypes", JSONArray(query.itemTypes.sorted()))
            .put("personId", query.personId ?: JSONObject.NULL).put("artistId", query.artistId ?: JSONObject.NULL)
            .put("studioId", query.studioId ?: JSONObject.NULL).put("genreId", query.genreId ?: JSONObject.NULL)
            .put("episodeOrder", query.episodeOrder).put("searchTerm", query.searchTerm)
            .put("nameInitial", query.nameInitial ?: JSONObject.NULL).put("recursive", query.recursive)

        fun query(json: JSONObject) = LibraryQuery(
            tab = LibraryTab.valueOf(json.getString("tab")), sort = LibrarySort.valueOf(json.getString("sort")),
            descending = json.getBoolean("descending"), played = json.nullableBoolean("played"),
            favorite = json.nullableBoolean("favorite"), seriesStatus = json.strings("seriesStatus").toSet(),
            genres = json.strings("genres").toSet(), tags = json.strings("tags").toSet(),
            years = json.getJSONArray("years").let { values -> (0 until values.length()).map { values.getInt(it) }.toSet() },
            officialRatings = json.strings("officialRatings").toSet(), itemTypes = json.strings("itemTypes").toSet(),
            personId = json.nullableString("personId"), artistId = json.nullableString("artistId"),
            studioId = json.nullableString("studioId"), genreId = json.nullableString("genreId"),
            episodeOrder = json.getBoolean("episodeOrder"), searchTerm = json.getString("searchTerm"),
            nameInitial = json.nullableString("nameInitial"), recursive = json.getBoolean("recursive"),
        )

        fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else getString(key)
        fun JSONObject.nullableBoolean(key: String): Boolean? = if (get(key) == JSONObject.NULL) null else getBoolean(key)
        fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { values ->
            (0 until values.length()).map { values.getString(it) }
        }
    }
}

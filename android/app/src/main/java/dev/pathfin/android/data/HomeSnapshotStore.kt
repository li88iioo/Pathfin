package dev.pathfin.android.data

import android.content.Context
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import org.json.JSONObject
import java.io.File

/** 仅保存首页展示数据；播放器地址、请求头和会话凭据不进入快照。 */
internal data class HomeSnapshot(
    val libraries: List<MediaItem>,
    val recent: List<MediaItem>,
    val resume: List<MediaItem>,
    val nextUp: List<MediaItem>,
    val libraryItems: Map<String, List<MediaItem>>,
    val loaded: Boolean,
    val complete: Boolean,
    val librariesLoaded: Boolean,
    val resumeLoaded: Boolean,
    val nextUpLoaded: Boolean,
    /** 已结束过一轮读取（含空结果和失败）；暖刷新不清除。 */
    val initialized: Boolean = complete,
)

/** 在既有存储 IO 队列使用；整份原子替换，已加载的空分组也必须保存。 */
internal class HomeSnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.cacheDir, "home-snapshots"))

    fun load(profileId: String, session: Session): HomeSnapshot? = synchronized(SnapshotFiles.lock) {
        val file = file(profileId, session)
        if (!file.isFile) return null
        return try {
            require(file.length() <= MaxSnapshotBytes)
            val json = JSONObject(file.readText())
            require(json.getInt("version") == 1)
            val shelves = json.getJSONObject("libraryItems")
            HomeSnapshot(
                libraries = MediaSnapshotJson.items(json.getJSONArray("libraries")),
                recent = MediaSnapshotJson.items(json.getJSONArray("recent")),
                resume = MediaSnapshotJson.items(json.getJSONArray("resume")),
                nextUp = MediaSnapshotJson.items(json.getJSONArray("nextUp")),
                libraryItems = shelves.keys().asSequence().associateWith { MediaSnapshotJson.items(shelves.getJSONArray(it)) },
                loaded = json.getBoolean("loaded"),
                complete = json.getBoolean("complete"),
                librariesLoaded = json.getBoolean("librariesLoaded"),
                resumeLoaded = json.getBoolean("resumeLoaded"),
                nextUpLoaded = json.getBoolean("nextUpLoaded"),
                initialized = json.optBoolean("initialized", json.getBoolean("complete")),
            )
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun save(profileId: String, session: Session, snapshot: HomeSnapshot): Boolean {
        return try {
            val json = JSONObject().put("version", 1)
                .put("libraries", MediaSnapshotJson.items(snapshot.libraries)).put("recent", MediaSnapshotJson.items(snapshot.recent))
                .put("resume", MediaSnapshotJson.items(snapshot.resume)).put("nextUp", MediaSnapshotJson.items(snapshot.nextUp))
                .put("libraryItems", JSONObject().apply { snapshot.libraryItems.forEach { (id, media) -> put(id, MediaSnapshotJson.items(media)) } })
                .put("loaded", snapshot.loaded).put("complete", snapshot.complete)
                .put("librariesLoaded", snapshot.librariesLoaded).put("resumeLoaded", snapshot.resumeLoaded)
                .put("nextUpLoaded", snapshot.nextUpLoaded).put("initialized", snapshot.initialized)
            SnapshotFiles.write(file(profileId, session), json, MaxSnapshotBytes.toLong())
        } catch (_: Exception) {
            false // 快照可以重新获取，存储失败不影响真实内容与登录。
        }
    }

    fun removeProfile(profileId: String) = synchronized(SnapshotFiles.lock) {
        val prefix = "${SnapshotFiles.digest(profileId)}-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        Unit
    }

    private fun file(profileId: String, session: Session): File {
        // 会话与权限变化只改变不可逆指纹，磁盘内容和文件名都不保存 token/deviceId。
        return File(directory, "${SnapshotFiles.digest(profileId)}-${SnapshotFiles.sessionFingerprint(session)}.json")
    }

    private companion object {
        const val MaxSnapshotBytes = 8 * 1024 * 1024
    }
}

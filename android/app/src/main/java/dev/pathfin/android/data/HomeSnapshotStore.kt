package dev.pathfin.android.data

import android.content.Context
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
)

/** 在既有存储 IO 队列使用；整份原子替换，已加载的空分组也必须保存。 */
internal class HomeSnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.cacheDir, "home-snapshots"))

    fun load(profileId: String, session: Session): HomeSnapshot? {
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
            )
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun save(profileId: String, session: Session, snapshot: HomeSnapshot): Boolean {
        var temporary: File? = null
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            val json = JSONObject().put("version", 1)
                .put("libraries", MediaSnapshotJson.items(snapshot.libraries)).put("recent", MediaSnapshotJson.items(snapshot.recent))
                .put("resume", MediaSnapshotJson.items(snapshot.resume)).put("nextUp", MediaSnapshotJson.items(snapshot.nextUp))
                .put("libraryItems", JSONObject().apply { snapshot.libraryItems.forEach { (id, media) -> put(id, MediaSnapshotJson.items(media)) } })
                .put("loaded", snapshot.loaded).put("complete", snapshot.complete)
                .put("librariesLoaded", snapshot.librariesLoaded).put("resumeLoaded", snapshot.resumeLoaded)
                .put("nextUpLoaded", snapshot.nextUpLoaded)
            val encoded = json.toString().toByteArray(Charsets.UTF_8)
            if (encoded.size > MaxSnapshotBytes) return false
            temporary = File.createTempFile("home-", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file(profileId, session).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false // 快照可以重新获取，存储失败不影响真实内容与登录。
        } finally {
            temporary?.delete()
        }
    }

    fun removeProfile(profileId: String) {
        val prefix = "${digest(profileId)}-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun file(profileId: String, session: Session): File {
        // 会话与权限变化只改变不可逆指纹，磁盘内容和文件名都不保存 token/deviceId。
        val identity = listOf(ServerUrls.normalize(session.server).baseUrl, session.server.allowInsecureHttp,
            session.serverId, session.userId, session.accessToken, session.deviceId,
            session.isAdministrator, session.canDownload, session.canDelete).joinToString("\u0000")
        return File(directory, "${digest(profileId)}-${digest(identity)}.json")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MaxSnapshotBytes = 8 * 1024 * 1024
    }
}

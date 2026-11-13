package dev.pathfin.android.data

import android.content.Context
import dev.pathfin.android.domain.LiveTvChannel
import dev.pathfin.android.domain.LiveTvProgram
import dev.pathfin.android.domain.Session
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上次读到的完整频道列表，按服务器与账号保存；进入直播页先画它再后台刷新，冷启动也不空等。
 * 只存展示字段（频道、台号、当前节目名与起止），不含播放地址与凭据。
 */
class LiveTvSnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "live-tv-snapshots"))

    suspend fun load(session: Session): List<LiveTvChannel>? = withContext(Dispatchers.IO) {
        runCatching {
            val file = file(session).takeIf { it.isFile && it.length() <= MaxBytes } ?: return@runCatching null
            val json = JSONObject(file.readText())
            require(json.getInt("version") == 1)
            val items = MediaSnapshotJson.items(json.getJSONArray("items"))
            val extras = json.getJSONArray("extras")
            require(extras.length() == items.size)
            items.mapIndexed { index, item ->
                val extra = extras.getJSONObject(index)
                LiveTvChannel(item, extra.text("number"), extra.optJSONObject("program")?.let { program(it, item.id) },
                    extra.text("lastPlayedAt")?.let(Instant::parse))
            }
        }.getOrNull()
    }

    suspend fun save(session: Session, channels: List<LiveTvChannel>) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val extras = JSONArray(channels.map { channel ->
                JSONObject().put("number", channel.number).put("lastPlayedAt", channel.lastPlayedAt?.toString())
                    .put("program", channel.currentProgram?.let { program ->
                        JSONObject().put("id", program.id).put("name", program.name)
                            .put("start", program.start?.toString()).put("end", program.end?.toString())
                    })
            })
            val json = JSONObject().put("version", 1).put("items", MediaSnapshotJson.items(channels.map { it.item })).put("extras", extras)
            val target = file(session)
            val temp = File(directory, "${target.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(target)) { temp.delete(); throw IOException("rename failed") }
        }
        Unit
    }

    private fun program(json: JSONObject, channelId: String) = LiveTvProgram(json.getString("id"), channelId, json.getString("name"),
        start = json.text("start")?.let(Instant::parse), end = json.text("end")?.let(Instant::parse))

    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotEmpty)

    private fun file(session: Session): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("${session.serverIdentity}/${session.userId}".toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object { const val MaxBytes = 8L * 1024 * 1024 }
}

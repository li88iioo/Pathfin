package dev.pathfin.android.playback

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.data.SafDocumentTransfer
import dev.pathfin.android.domain.SubtitleUploadFile
import dev.pathfin.android.i18n.tr
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

@UnstableApi
internal data class LocalSubtitle(val file: File, val label: String, val mimeType: String, val id: String) {
    fun configuration(): MediaItem.SubtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
        .setId(id).setMimeType(mimeType).setLabel(label).build()
}

/** SAF 内容只复制到本次播放的私有目录，不持久化来源 URI 或外部凭据。 */
@UnstableApi
internal object LocalSubtitleImporter {
    const val MAX_BYTES = SubtitleUploadFile.MAX_BYTES
    const val MAX_FILES = 8
    private val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { operation ->
        Thread(operation, "Pathfin-SubtitleCleanup").apply { isDaemon = true }
    }
    fun delete(files: List<File>) { if (files.isNotEmpty()) cleanup.execute { files.forEach { it.delete() } } }
    suspend fun read(context: Context, uri: Uri): LocalSubtitle {
        require(uri.scheme == "content") { tr("请选择文档中的字幕文件") }
        val created = AtomicReference<File?>()
        try {
            return SafDocumentTransfer.transfer { operation ->
                val bytes = operation.readBytes(context, uri, MAX_BYTES, tr("字幕文件不能超过 8 MB"))
                val name = try { operation.query(context, uri, arrayOf(OpenableColumns.DISPLAY_NAME)) { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val subtitle = SubtitleUploadFile.prepare(bytes)
                operation.ensureActive()
                val id = "pathfin-local-${UUID.randomUUID()}"
                val folder = File(context.cacheDir, "player-subtitles").apply {
                    if (!isDirectory && !mkdirs()) throw IOException(tr("无法保存本地字幕"))
                }
                val file = File(folder, "$id.${subtitle.format}").also(created::set)
                var complete = false
                try {
                    operation.ensureActive()
                    file.writeBytes(subtitle.content)
                    operation.ensureActive()
                    LocalSubtitle(file, name?.filter { !it.isISOControl() && it != '/' && it != '\\' }
                        ?.take(120)?.takeIf(String::isNotBlank) ?: tr("本地字幕"), mime(subtitle.format), id).also { complete = true }
                } finally { if (!complete) file.delete() }
            }
        } catch (failure: Exception) {
            created.get()?.let { delete(listOf(it)) }
            throw failure
        }
    }

    internal fun identify(bytes: ByteArray): Pair<String, String> {
        val file = SubtitleUploadFile.prepare(bytes)
        return file.format to mime(file.format)
    }

    private fun mime(format: String): String = when (format) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass" -> MimeTypes.TEXT_SSA
        else -> error(tr("不支持的字幕格式"))
    }
}

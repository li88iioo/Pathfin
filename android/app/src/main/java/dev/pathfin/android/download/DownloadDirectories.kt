package dev.pathfin.android.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.pathfin.android.domain.DownloadMimeType
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 默认目录失效时，调用方可转到系统“另存为”，不丢失本次下载选择。 */
class DownloadDirectoryUnavailable(cause: Throwable? = null) : IllegalStateException(
    tr("下载目录不可用，请重新选择目录或使用系统另存为"), cause,
)

/** 仅使用用户通过系统文件选择器授予的文档与目录；不解析为文件系统路径。 */
object DownloadDirectories {
    private val creationLock = Mutex()

    suspend fun persist(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val existing = resolver.persistedUriPermissions.any { it.uri == uri }
        try {
            requireTree(uri)
            resolver.takePersistableUriPermission(uri, READ_WRITE)
            directoryName(context, uri)
        } catch (error: Exception) {
            if (!existing) runCatching { resolver.releasePersistableUriPermission(uri, READ_WRITE) }
            throw DownloadDirectoryUnavailable(error)
        }
    }

    suspend fun name(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        try { directoryName(context, uri) }
        catch (error: Exception) { throw DownloadDirectoryUnavailable(error) }
    }

    internal suspend fun create(context: Context, tree: Uri, requestedName: String): Uri =
        withContext(Dispatchers.IO) {
            creationLock.withLock {
                try {
                    directoryName(context, tree)
                    val resolver = context.contentResolver
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val names = mutableSetOf<String>()
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) names += cursor.getString(0).orEmpty()
                    } ?: throw DownloadDirectoryUnavailable()
                    val fileName = uniqueDownloadName(downloadDisplayName(requestedName), names)
                    DocumentsContract.createDocument(resolver, parent,
                        DownloadMimeType.fromFile(fileName) ?: "application/octet-stream", fileName)
                        ?: throw DownloadDirectoryUnavailable()
                } catch (error: DownloadDirectoryUnavailable) { throw error }
                catch (error: Exception) { throw DownloadDirectoryUnavailable(error) }
            }
        }

    internal fun requireDestination(context: Context, uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.userInfo.isNullOrBlank()) {
            tr("请选择系统文件管理器提供的本地保存位置")
        }
        val resolver = context.contentResolver
        fun granted(): Boolean = resolver.persistedUriPermissions.any { grant ->
            grant.isWritePermission && (grant.uri == uri || DocumentsContract.isTreeUri(uri) &&
                grant.uri == DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)))
        }
        if (!granted()) runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        check(granted()) { tr("该位置未授予长期写入权限，请重新选择本地保存位置") }
        // 查询由 DocumentsProvider 校验真正的树从属关系，不能仅依赖 URI 中的 treeId。
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) {
                tr("目标文件不可用，请重新选择本地保存位置")
            }
        } ?: error(tr("目标文件不可用，请重新选择本地保存位置"))
    }

    private fun directoryName(context: Context, uri: Uri): String {
        requireTree(uri)
        val resolver = context.contentResolver
        check(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR &&
                cursor.getInt(2) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
            return cursor.getString(0).orEmpty().ifBlank { tr("已选择的目录") }
        }
        throw DownloadDirectoryUnavailable()
    }

    private fun requireTree(uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.userInfo.isNullOrBlank() && DocumentsContract.isTreeUri(uri))
    }

    private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}

/** 保留扩展名，重名时另建文件；已有的用户文件始终保留。 */
internal fun uniqueDownloadName(requested: String, existing: Set<String>): String {
    val occupied = existing.mapTo(mutableSetOf()) { it.lowercase(java.util.Locale.ROOT) }
    if (requested.lowercase(java.util.Locale.ROOT) !in occupied) return requested
    val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
    val stem = requested.substring(0, dot)
    val suffix = requested.substring(dot)
    var index = 1
    while ("$stem ($index)$suffix".lowercase(java.util.Locale.ROOT) in occupied) index++
    return "$stem ($index)$suffix"
}

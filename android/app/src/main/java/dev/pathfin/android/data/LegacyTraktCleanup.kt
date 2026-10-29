package dev.pathfin.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 已移除同步功能的一次性本机迁移；不解密凭据、不联网，不触及 Jellyfin 数据。 */
internal object LegacyTraktCleanup {
    private val lock = Any()
    private const val PREFERENCES = "pathfin_removed_features"
    private const val COMPLETED = "trakt_v1_removed"
    private const val KEY_ALIAS = "dev.pathfin.android.trakt.v1"

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；迁移标记必须保留 commit 的成功结果。
    suspend fun run(context: Context): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                val application = context.applicationContext
                val marker = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                if (marker.getBoolean(COMPLETED, false)) return@synchronized true

                // 两项独立尝试；文件删除失败也应撤除这个功能唯一的本机解密密钥。
                val filesRemoved = runCatching {
                    removeDirectory(application.noBackupFilesDir.toPath().resolve("trakt"))
                }.isSuccess
                val keyRemoved = runCatching {
                    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (keys.containsAlias(KEY_ALIAS)) keys.deleteEntry(KEY_ALIAS)
                    check(!keys.containsAlias(KEY_ALIAS))
                }.isSuccess
                val completed = filesRemoved && keyRemoved && marker.edit().putBoolean(COMPLETED, true).commit()
                if (!completed) Log.w("PathfinCleanup", "Legacy sync data cleanup incomplete; will retry")
                completed
            } catch (_: Exception) {
                Log.w("PathfinCleanup", "Legacy sync data cleanup incomplete; will retry")
                false
            }
        }
    }

    private fun removeDirectory(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        // walkFileTree 默认不 FOLLOW_LINKS：根路径或子项若为符号链接，只删链接，不删目标。
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        check(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
    }
}

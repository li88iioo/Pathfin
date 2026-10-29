package dev.pathfin.android.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 直接读取实际视频 Surface，不把控制按钮或空的 Compose 背景当作视频截图。 */
@UnstableApi
internal object PlayerFrameCapture {
    suspend fun capture(view: PlayerView): Bitmap = withContext(Dispatchers.Main.immediate) {
        val surface = view.videoSurfaceView ?: throw IOException(tr("视频画面尚未就绪"))
        require(surface.width > 0 && surface.height > 0 && surface.width.toLong() * surface.height <= 16_777_216) { tr("截图尺寸不可用") }
        when (surface) {
            is SurfaceView -> {
                if (!surface.holder.surface.isValid) throw IOException(tr("视频画面已关闭"))
                val bitmap = createBitmap(surface.width, surface.height)
                withTimeout(5_000) {
                    suspendCancellableCoroutine { continuation ->
                        try {
                            PixelCopy.request(surface, bitmap, { result ->
                                if (result == PixelCopy.SUCCESS && continuation.isActive) {
                                    continuation.resume(bitmap) { _, value, _ -> value.recycle() }
                                } else {
                                    bitmap.recycle()
                                    if (continuation.isActive) continuation.resumeWithException(IOException(tr("视频截图失败，请稍后再试")))
                                }
                            }, Handler(Looper.getMainLooper()))
                        } catch (_: Exception) {
                            bitmap.recycle()
                            if (continuation.isActive) continuation.resumeWithException(IOException(tr("视频截图失败，请稍后再试")))
                        }
                    }
                }
            }
            is TextureView -> surface.bitmap ?: throw IOException(tr("视频画面尚未就绪"))
            else -> throw IOException(tr("此视频输出不支持截图"))
        }
    }

    fun fileName(): String = "Pathfin-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}.png"

    suspend fun save(context: Context, bitmap: Bitmap, selected: Uri? = null): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val inserted = selected == null
        val uri = selected ?: run {
            check(Build.VERSION.SDK_INT >= 29) { tr("请选择截图保存位置") }
            requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName())
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pathfin")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })) { tr("无法创建截图文件") }
        }
        try {
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException(tr("无法编码截图"))
            }
            if (inserted && Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            }
            uri
        } catch (error: Exception) {
            if (inserted) runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

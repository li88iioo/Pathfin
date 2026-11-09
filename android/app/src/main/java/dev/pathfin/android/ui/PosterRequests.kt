package dev.pathfin.android.ui

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.useExistingImageAsPlaceholder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session

/** 卡片共用 URL、鉴权、缓存键和真实解码尺寸，避免相同封面重复请求或解码。 */
internal object PosterRequests {
    /** 首次读到的图从占位淡入；内存缓存命中时 Coil 不做过渡，回滚列表不会闪。 */
    const val FADE_MS = 200

    fun url(api: JellyfinApi, session: Session, item: MediaItem, width: Int,
            type: String, imageIndex: Int = 0): String? = width.takeIf { it > 0 }?.let {
        api.imageUrl(session, item, width = PosterImageCache.requestWidth(it), type = type, imageIndex = imageIndex)
    }

    /** 标题 logo：没有 logo 时返回 null，调用方回退显示文字。详情页与播放器共用。 */
    fun logo(context: Context, session: Session, api: JellyfinApi, item: MediaItem, width: Int = 720): ImageRequest? =
        api.imageUrl(session, item, width = width, type = "Logo")?.let { url ->
            val headers = NetworkHeaders.Builder().apply { api.imageHeaders(session).forEach { (key, value) -> set(key, value) } }.build()
            val key = PosterImageCache.requestKey(session, url)
            ImageRequest.Builder(context).data(url).httpHeaders(headers).memoryCacheKey(key).diskCacheKey(key).build()
        }

    @OptIn(ExperimentalCoilApi::class)
    fun build(context: Context, session: Session, api: JellyfinApi, url: String?, size: IntSize): ImageRequest {
        val headers = NetworkHeaders.Builder().apply {
            api.imageHeaders(session).forEach { (name, value) -> set(name, value) }
        }.build()
        return ImageRequest.Builder(context).data(url).httpHeaders(headers).crossfade(FADE_MS)
            // 换图期间保留当前画面，过渡统一交给 Coil；内存命中直接显示，失败/缺图正常清空。
            .useExistingImageAsPlaceholder(true)
            .scale(Scale.FILL).precision(Precision.INEXACT)
            .apply {
                if (size.width > 0 && size.height > 0) size(size.width, size.height)
                url?.let {
                    val key = PosterImageCache.requestKey(session, it)
                    memoryCacheKey(key)
                    diskCacheKey(key)
                }
            }.build()
    }
}

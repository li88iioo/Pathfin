package dev.pathfin.android.ui

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session

/** 卡片共用 URL、鉴权、缓存键和真实解码尺寸，避免相同封面重复请求或解码。 */
internal object PosterRequests {
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

    fun build(context: Context, session: Session, api: JellyfinApi, url: String?, size: IntSize): ImageRequest {
        val headers = NetworkHeaders.Builder().apply {
            api.imageHeaders(session).forEach { (name, value) -> set(name, value) }
        }.build()
        return ImageRequest.Builder(context).data(url).httpHeaders(headers)
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

package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.Session

@Composable
internal fun AccountAvatar(session: Session?, api: JellyfinApi, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    val request = rememberServerImage(session, session?.let { api.userImageUrl(it, width = 128) }, api)
    Box(
        modifier
            .clip(shape)
            .background(PathfinAppearance.accentSoft)
            .border(0.8.dp, PathfinAppearance.accent.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PathfinIcons.User, null, Modifier.size(18.dp), tint = PathfinAppearance.accent)
        if (request != null) key(session?.accessToken) {
            AsyncImage(request, null, imageLoader = LocalPosterLoader.current,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 带会话鉴权头、按账号隔离缓存键的服务器图片请求。 */
@Composable
internal fun rememberServerImage(session: Session?, url: String?, api: JellyfinApi): ImageRequest? {
    val context = LocalContext.current
    return remember(session, url, context) {
        if (session == null || url == null) null else ImageRequest.Builder(context).data(url)
            .httpHeaders(NetworkHeaders.Builder().apply { api.imageHeaders(session).forEach { (key, value) -> set(key, value) } }.build())
            .memoryCacheKey(PosterImageCache.requestKey(session, url))
            .diskCacheKey(PosterImageCache.requestKey(session, url)).build()
    }
}

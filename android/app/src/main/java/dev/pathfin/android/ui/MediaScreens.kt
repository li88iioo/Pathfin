@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.data.PerformanceTrace
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.supportsLatestItems
import dev.pathfin.android.domain.AdultContentRatings
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.domain.MediaPerson
import dev.pathfin.android.domain.MediaChapter
import dev.pathfin.android.i18n.tr
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(home: HomeState, session: Session, api: JellyfinApi, onOpen: (MediaItem) -> Unit, retry: () -> Unit) {
    val bottomInset = LocalNavigationBottomInset.current
    val resumeHeight = compactResumeHeight()
    // 续播确认后再显示去重结果；暖刷新继续保留已确认的观看分组。
    val resumeSeries = home.resume.mapNotNullTo(mutableSetOf()) { it.seriesId }
    val nextUp = home.nextUp.filter { item -> home.resume.none { it.id == item.id } && item.seriesId !in resumeSeries }
    RefreshablePage(home.loading, retry, Modifier.fillMaxSize(), refreshOnEntry = home.loaded || home.error != null) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(top = LocalPageTopInset.current, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "home-libraries") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(tr("媒体库"), compact = true)
                    Box(Modifier.fillMaxWidth().height(168.dp * 9f / 16f).testTag("home-libraries-content"), contentAlignment = Alignment.Center) {
                    if (home.libraries.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(home.libraries, key = { it.id }) { library ->
                            val cover = libraryCover(library, home.libraryItems[library.id].orEmpty())
                            LibraryCoverCard(library, cover, session, api, Modifier.width(168.dp), imageWidth = 168.dp) { onOpen(library) }
                        }
                    }
                    } else if (home.librariesLoaded) Text(tr("暂无可见媒体库"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            item(key = "home-resume") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(tr("继续观看"), compact = true)
                    Box(Modifier.fillMaxWidth().height(resumeHeight).testTag("home-resume-content"), contentAlignment = Alignment.Center) {
                    if (home.resume.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(home.resume, key = { it.id }) { media ->
                            ResumeCard(media, session, api, Modifier.width(196.dp), compact = true) { onOpen(media) }
                        }
                    }
                    } else if (home.resumeLoaded) Text(tr("暂无观看记录"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            if (home.resumeLoaded && nextUp.isNotEmpty()) item(key = "home-next-up") {
                Column(Modifier.testTag("home-next-up"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(tr("接下来"), compact = true)
                    LazyRow(Modifier.height(resumeHeight), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(nextUp, key = { it.id }) { media ->
                            ResumeCard(media, session, api, Modifier.width(196.dp), nextUp = true, compact = true) { onOpen(media) }
                        }
                    }
                }
            }
            home.libraries.filter { it.supportsLatestItems }.forEach { library ->
                item(key = "library-shelf/${library.id}") {
                    PosterShelf(
                        tr("最近入库 · %1\$s", library.name), home.libraryItems[library.id].orEmpty(), session, api, onOpen,
                        onMore = { onOpen(library) }, compactHeading = true,
                        loading = !home.libraryItems.containsKey(library.id),
                        keepEmptySpace = true,
                    )
                }
            }
            if (home.recent.isNotEmpty() && home.libraries.isEmpty()) item(key = "home-recent") {
                PosterShelf(tr("最近入库"), home.recent, session, api, onOpen, compactHeading = true)
            }
            home.error?.let { error ->
                item(key = "home-error") { ErrorPanel(error, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, onMore: (() -> Unit)? = null, compact: Boolean = false, count: Int? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .then(if (onMore != null) Modifier.heightIn(min = 48.dp).clickable(onClick = onMore,
                role = androidx.compose.ui.semantics.Role.Button, onClickLabel = tr("浏览媒体库")) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f, fill = false),
            fontSize = if (compact) 17.sp else 19.sp,
            lineHeight = if (compact) 23.sp else 25.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = PathfinAppearance.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
        )
        if (count != null) Text(count.toString(), Modifier.padding(start = 8.dp), fontSize = 15.sp, lineHeight = 21.sp,
            color = PathfinAppearance.textMuted, maxLines = 1)
        if (onMore != null) {
            Icon(
                PathfinIcons.Chevron,
                null,
                Modifier.padding(start = 4.dp).size(if (compact) 16.dp else 18.dp),
                tint = PathfinAppearance.textMuted,
            )
        }
    }
}

@Composable
internal fun PosterShelf(
    title: String,
    items: List<MediaItem>,
    session: Session,
    api: JellyfinApi,
    onOpen: (MediaItem) -> Unit,
    onMore: (() -> Unit)? = null,
    loading: Boolean = false,
    keepEmptySpace: Boolean = false,
    compactHeading: Boolean = false,
) {
    val rowHeight = compactPosterHeight()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(title, onMore, compact = compactHeading)
        if (items.isEmpty() && keepEmptySpace) {
            Box(Modifier.fillMaxWidth().height(rowHeight), contentAlignment = Alignment.Center) {
                // 首次读取保留真实海报栏的空间，不制造短时闪现的骨架。
                if (!loading) EmptyPanel(tr("暂无可见媒体"), tr("点击库标题浏览，或检查账号的访问权限。"))
            }
        } else {
            LazyRow(
                Modifier.heightIn(min = if (keepEmptySpace) rowHeight else 0.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    PosterCard(item, session, api, Modifier.width(112.dp), compact = true) { onOpen(item) }
                }
            }
        }
    }
}

@Composable
internal fun LibrariesScreen(home: HomeState, session: Session, api: JellyfinApi, onOpen: (MediaItem) -> Unit, retry: () -> Unit) {
    val bottomInset = LocalNavigationBottomInset.current
    RefreshablePage(home.loading, retry, Modifier.fillMaxSize(), refreshOnEntry = home.loaded || home.error != null) {
    LazyVerticalGrid(
        GridCells.Adaptive(152.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = LocalPageTopInset.current, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        home.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }, key = "libraries-error") { ErrorPanel(error) } }
        gridItems(home.libraries, key = { it.id }) { library ->
            LibraryCoverCard(library, libraryCover(library, home.libraryItems[library.id].orEmpty()), session, api, Modifier.fillMaxWidth()) { onOpen(library) }
        }
        if (home.libraries.isEmpty() && !home.loading && home.error == null) item(span = { GridItemSpan(maxLineSpan) }, key = "libraries-empty") {
            Column {
                EmptyPanel(tr("暂无媒体库"), tr("添加媒体库或调整账号权限后，可以重新读取。"))
            }
        }
    }
    }
}

private fun libraryCover(library: MediaItem, media: List<MediaItem>): MediaItem =
    if (!library.backdropTag.isNullOrBlank() || !library.imageTag.isNullOrBlank()) library
    else media.firstOrNull { !it.backdropTag.isNullOrBlank() || !it.imageTag.isNullOrBlank() } ?: library

@Composable
private fun LibraryCoverCard(library: MediaItem, cover: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier, imageWidth: Dp? = null, onClick: () -> Unit) {
    val hasOwnCover = cover.id == library.id && (!cover.imageTag.isNullOrBlank() || !cover.backdropTag.isNullOrBlank())
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.aspectRatio(16f / 9f)
            .clip(shape)
            .border(PathfinAppearance.cardBorder, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = library.name },
        contentAlignment = Alignment.Center,
    ) {
        PosterImage(cover, session, api, Modifier.matchParentSize(), wide = true, shape = shape,
            displayWidth = imageWidth, displayAspectRatio = 16f / 9f)
        // Jellyfin库图通常已包含标题。只有没有自己的库图时才保留可辨识的名称。
        if (!hasOwnCover) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))),
                ),
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(libraryTypeIcon(library), null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.9f))
                Text(
                    library.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResumeCard(item: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier, nextUp: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val title = item.seriesName?.takeIf(String::isNotBlank) ?: item.name
    val subtitle = resumeSubtitle(item, title)
    val watchedMs = TimeUnits.ticksToMs(item.positionTicks)
    val timing = when {
        nextUp && item.positionTicks <= 0 && item.runTimeTicks > 0 -> compactDuration(TimeUnits.ticksToMs(item.runTimeTicks))
        item.runTimeTicks > 0 -> tr("剩 %1\$s", compactDuration(TimeUnits.ticksToMs((item.runTimeTicks - item.positionTicks).coerceAtLeast(0))))
        watchedMs >= 1_000 -> tr("已看 %1\$s", compactDuration(watchedMs))
        nextUp -> tr("待观看")
        else -> null
    }
    val shape = RoundedCornerShape(14.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .border(PathfinAppearance.cardBorder, shape)
                .indication(interactionSource, LocalIndication.current),
        ) {
            val ownPrimary = item.primaryImageItemId == null || item.primaryImageItemId == item.id
            val preferEpisodeImage = item.type == "Episode" && ownPrimary && LocalAppPreferences.current.preferEpisodeImages
            PosterImage(item, session, api, Modifier.matchParentSize(), wide = true, shape = shape,
                imageType = if (preferEpisodeImage) "Primary" else "Backdrop", displayWidth = 196.dp, displayAspectRatio = 16f / 9f)
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))),
            )
            // 剩余时间写在剧照底部渐隐上，紧贴进度条，与画面连成一体。
            if (timing != null) Text(timing, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 9.dp),
                style = ArtworkTextStyle, maxLines = 1)
            WatchProgress(item, Modifier.align(Alignment.BottomCenter))
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp)) {
            Text(
                title,
                Modifier.fillMaxWidth().then(if (compact) Modifier.height(with(LocalDensity.current) { (scaledLineHeightPx(14.sp, 20.sp) + 2).toDp() }) else Modifier),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = PathfinAppearance.textMain,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle.ifBlank { " " },
                Modifier.fillMaxWidth().then(if (compact) Modifier.height(with(LocalDensity.current) { (scaledLineHeightPx(11.sp, 14.sp) + 2).toDp() }) else Modifier),
                fontSize = if (compact) 11.sp else 12.sp,
                lineHeight = if (compact) 14.sp else 16.sp,
                textAlign = TextAlign.Start,
                color = PathfinAppearance.textSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun compactResumeHeight() = with(LocalDensity.current) {
    val imageHeight = kotlin.math.round(196.dp.roundToPx() * 9f / 16f)
    (imageHeight + 5.dp.roundToPx() + 2.dp.roundToPx() + scaledLineHeightPx(14.sp, 20.sp) + scaledLineHeightPx(11.sp, 14.sp) + 4).toDp()
}

private fun resumeSubtitle(item: MediaItem, title: String): String {
    if (item.type != "Episode") return item.year?.toString().orEmpty()
    val episodeNumber = item.indexNumber?.takeIf { it >= 0 }
    val episodeCode = buildString {
        item.parentIndexNumber?.takeIf { it >= 0 }?.let { append("S").append(it) }
        episodeNumber?.let { append("E").append(it) }
    }
    val episodeName = item.name.trim().takeUnless { name ->
        val compactName = name.filterNot(Char::isWhitespace)
        name == title || (episodeNumber != null && (
            // 比对服务器给出的单集名，必须保持原文，不能翻译。
            compactName == "第${episodeNumber}集" || compactName == "第${episodeNumber}话" || compactName == "第${episodeNumber}話" ||
                compactName.equals("Episode$episodeNumber", ignoreCase = true) || compactName.equals("E$episodeNumber", ignoreCase = true)
            ))
    }.orEmpty()
    return listOf(episodeCode, episodeName).filter(String::isNotBlank).joinToString(tr("："))
}

@Composable
internal fun MediaGrid(content: ContentState, session: Session, api: JellyfinApi, onOpen: (MediaItem) -> Unit, onRetry: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    val bottomInset = LocalNavigationBottomInset.current
    val grid = rememberLazyGridState()
    AutomaticGridPagination(grid, content.pagination, content.queryKey, onMore)
    RefreshablePage(content.loading || content.loadingMore, onRetry, modifier.fillMaxSize()) {
    LazyVerticalGrid(
        GridCells.Adaptive(if (fontScale >= 1.5f) 144.dp else 104.dp),
        Modifier.fillMaxSize().testTag("media-grid"),
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = LocalPageTopInset.current, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content.error?.takeUnless { content.retryAppend }?.let { error -> item(span = { GridItemSpan(maxLineSpan) }, key = "media-error") { ErrorPanel(error) } }
        gridItems(content.items, key = { it.id }) { item -> PosterCard(item, session, api, Modifier.fillMaxWidth()) { onOpen(item) } }
        if (content.items.isEmpty() && content.error == null) item(span = { GridItemSpan(maxLineSpan) }, key = "media-empty") {
            Column {
                EmptyPanel(if (content.loading) tr("正在读取媒体") else tr("没有找到内容"), if (content.loading) tr("请稍候…") else tr("尝试刷新，或调整搜索条件。"))
            }
        }
        content.error?.takeIf { content.retryAppend }?.let { error ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "media-append-error") { ErrorPanel(error) }
        }
    }
    }
}

@Composable
private fun PosterCard(item: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier, compact: Boolean = false, onClick: () -> Unit) {
    BrowserMediaCard(item, session, api, PosterStyle.POSTER, modifier, compact = compact,
        imageWidth = if (compact) 112.dp else null, onOpen = onClick)
}

@Composable
internal fun WatchProgress(item: MediaItem, modifier: Modifier = Modifier) {
    if (item.positionTicks <= 0 || item.runTimeTicks <= 0) return
    val fraction = (item.positionTicks.toDouble() / item.runTimeTicks).toFloat().coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.28f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(PathfinAppearance.accent))
    }
}

@Composable
internal fun PosterImage(item: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier, wide: Boolean = false, shape: Shape = RoundedCornerShape(12.dp), imageType: String? = null, imageIndex: Int = 0, displayWidth: Dp? = null, displayAspectRatio: Float? = null) {
    val context = LocalContext.current
    val preferences = LocalAppPreferences.current
    val restricted = preferences.blurAdultCovers && AdultContentRatings.matches(item.officialRating)
    val type = imageType ?: if (wide) "Backdrop" else "Primary"
    val initialWidth = with(LocalDensity.current) { displayWidth?.roundToPx() } ?: 0
    val initialHeight = displayAspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?.let { (initialWidth / it).roundToInt() } ?: 0
    // 固定宽卡片首组合即使用真实尺寸；其余场景按测量结果裁切，避免横图复用小海报解码。
    var displaySize by remember(initialWidth, initialHeight) { mutableStateOf(IntSize(initialWidth, initialHeight)) }
    val imageUrl = PosterRequests.url(api, session, item, displaySize.width, type, imageIndex)
    val request = remember(context, session, imageUrl, displaySize) {
        PosterRequests.build(context, session, api, imageUrl, displaySize)
    }
    // 同一位置换内容（切季、换演员）时，新图到达前先垫着上一张成功加载的图，到达后淡入替换，不闪占位块。
    var shownRequest by remember { mutableStateOf<ImageRequest?>(null) }
    var currentLoaded by remember(request) { mutableStateOf(false) }
    val previous = shownRequest?.takeIf { it !== request && !currentLoaded }
    Box(modifier.onSizeChanged { size ->
        if (size.width > 0 && size.height > 0) displaySize = size
    }.clip(shape).background(MediaImagePlaceholder), contentAlignment = Alignment.Center) {
        if (previous == null && !currentLoaded) Icon(when (item.type) {
            "Person" -> PathfinIcons.User
            "Folder", "CollectionFolder" -> PathfinIcons.Folder
            else -> MediaNavigationIcons.Movies
        }, null,
            Modifier.size(26.dp), tint = Color.White.copy(alpha = .6f))
        if (restricted && Build.VERSION.SDK_INT < 31) {
            // 旧系统不支持 RenderEffect，直接不绘制图像，不能把清晰图误称为已模糊。
            Column(Modifier.matchParentSize().background(MediaImagePlaceholder)
                .testTag("poster-image-covered/${item.id}").semantics { contentDescription = tr("此分级封面已遮盖，当前系统不支持模糊") },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(PathfinIcons.EyeOff, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .85f))
                Text(tr("已遮盖"), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, maxLines = 1)
            }
        } else {
            if (previous != null) AsyncImage(previous, null, imageLoader = LocalPosterLoader.current,
                modifier = Modifier.matchParentSize().then(if (restricted) Modifier.blur(24.dp) else Modifier), contentScale = ContentScale.Crop)
            val fadeIn by animateFloatAsState(if (currentLoaded || shownRequest == null) 1f else 0f,
                if (shownRequest == null) snap() else tween(180), label = "poster-swap")
            AsyncImage(request, null, imageLoader = LocalPosterLoader.current,
                modifier = Modifier.matchParentSize().then(if (restricted) Modifier.blur(24.dp).testTag("poster-image-blurred/${item.id}")
                    .semantics { contentDescription = tr("此分级封面已模糊") } else Modifier.testTag("poster-image-clear/${item.id}")),
                contentScale = ContentScale.Crop, alpha = fadeIn, onSuccess = {
                    PerformanceTrace.imageReady(it.result.dataSource.name)
                    currentLoaded = true
                    shownRequest = request
                })
        }
    }
}

/** 详情背景与继续观看集图分别配置；继承的剧集海报不能误作本集截图。 */
@Composable
internal fun DetailBackgroundImage(item: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier = Modifier) {
    val preferences = LocalAppPreferences.current
    val ownEpisodeImage = item.type == "Episode" && !item.imageTag.isNullOrBlank() &&
        (item.primaryImageItemId == null || item.primaryImageItemId == item.id)
    val type = if (preferences.preferEpisodeBackgrounds && ownEpisodeImage) "Primary" else "Backdrop"
    val radius = if (Build.VERSION.SDK_INT >= 31) preferences.backgroundBlurRadiusDp.coerceIn(0, 30) else 0
    Box(modifier.testTag("detail-background/${item.id}")) {
        PosterImage(item, session, api, Modifier.matchParentSize().then(if (radius > 0) Modifier.blur(radius.dp) else Modifier),
            wide = true, shape = RectangleShape, imageType = type)
    }
}

internal fun MediaChapter.hasThumbnail(): Boolean = imageIndex in 0..100_000 &&
    !imageTag.isNullOrBlank() && imageTag.length <= 512 && imageTag.none(Char::isISOControl)

/** imageIndex 来自服务端原数组；筛选列表或非法条目之后不能重新编号图片。 */
@Composable
internal fun ChapterThumbnail(chapter: MediaChapter, item: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var requestWidth by remember { mutableIntStateOf(0) }
    val imageUrl = requestWidth.takeIf { it > 0 }?.let { api.chapterImageUrl(session, item, chapter.imageIndex, chapter.imageTag, it) }
    val request = remember(context, session, imageUrl) {
        val headers = NetworkHeaders.Builder().apply { api.imageHeaders(session).forEach { (key, value) -> set(key, value) } }.build()
        ImageRequest.Builder(context).data(imageUrl).httpHeaders(headers).apply {
            imageUrl?.let { url ->
                val key = PosterImageCache.requestKey(session, url)
                memoryCacheKey(key)
                diskCacheKey(key)
            }
        }.build()
    }
    Box(modifier.onSizeChanged { size -> if (size.width > 0) requestWidth = PosterImageCache.requestWidth(size.width) }
        .clip(RoundedCornerShape(8.dp)).background(MediaImagePlaceholder)
        .testTag("chapter-thumbnail/${item.id}/${chapter.imageIndex}"), contentAlignment = Alignment.Center) {
        Icon(MediaNavigationIcons.Movies, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .6f))
        if (chapter.hasThumbnail()) AsyncImage(request, null, imageLoader = LocalPosterLoader.current,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            onSuccess = { PerformanceTrace.imageReady(it.result.dataSource.name) })
    }
}

internal fun chapterTime(chapter: MediaChapter): String {
    val seconds = chapter.startPositionTicks.coerceAtLeast(0) / 10_000_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

@Composable
internal fun DetailChaptersShelf(item: MediaItem, session: Session, api: JellyfinApi, busy: Boolean, onSelect: (MediaChapter) -> Unit) {
    val hideWithoutImage = LocalAppPreferences.current.hideChaptersWithoutImage
    val visible = item.chapters.withIndex().filter { !hideWithoutImage || it.value.hasThumbnail() }
    if (visible.isEmpty()) return
    Column(Modifier.testTag("detail-chapters"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(tr("章节"))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visible, key = { it.index }) { indexed ->
                val chapter = indexed.value
                Column(Modifier.width(160.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { onSelect(chapter) }.testTag("detail-chapter/${indexed.index}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChapterThumbnail(chapter, item, session, api, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(chapter.name, Modifier.fillMaxWidth(), fontSize = 14.sp, lineHeight = 20.sp,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(chapterTime(chapter), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun DetailScreen(state: AppState, item: MediaItem?, session: Session, vm: AppViewModel, onScrolled: (Boolean) -> Unit = {}, onCast: ((MediaItem) -> Unit)? = null) {
    val id = item?.id ?: (state.route as? Route.Detail)?.id
    val children = state.folders[id]
    RefreshablePage(state.detailLoading || children?.loading == true || children?.loadingMore == true || state.detailExtras[id]?.loading == true,
        {
            id?.let(vm::refreshDetail)
            if (id != null && item?.type in setOf("Series", "Season")) vm.loadFolder(id)
        }, Modifier.fillMaxSize(), refreshOnEntry = item != null || state.detailError != null, enabled = id != null) {
        DetailContent(state, item, session, vm, onScrolled, onCast)
    }
}

@Composable
private fun DetailContent(state: AppState, item: MediaItem?, session: Session, vm: AppViewModel, onScrolled: (Boolean) -> Unit, onCast: ((MediaItem) -> Unit)?) {
    val horizontalSafeArea = AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)
    if (item == null) {
        LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(horizontalSafeArea)) {
            item { EmptyPanel(if (state.detailError == null) tr("正在读取详情") else tr("无法读取详情"), state.detailError ?: tr("请稍候…")) }
        }
        return
    }
    if (item.type == "Person") { PersonDetailContent(state, item, session, vm, onScrolled); return }
    val children = state.folders[item.id] ?: ContentState()
    val extras = state.detailExtras[item.id]
    val hasChildren = item.type in setOf("Series", "Season")
    val playable = item.type in setOf("Movie", "Episode", "Series", "Season", "Video", "Audio")
    val resumeItems = extras?.resume.orEmpty()
    val listState = rememberLazyListState()
    val collapseOffset = with(LocalDensity.current) { 180.dp.roundToPx() }
    val collapsed by remember(listState, collapseOffset) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseOffset } }
    LaunchedEffect(collapsed) { onScrolled(collapsed) }
    val bottomInset = LocalNavigationBottomInset.current
    val onPlay = { if (hasChildren) vm.playSeries(item) else vm.preparePlayback(item) }
    val onStart = { if (hasChildren) vm.playSeries(item, true) else vm.preparePlayback(item, true) }
    val seasonRows = if (item.type == "Series") children.items.chunked(3) else emptyList()
    if (item.type == "Series") {
        val seasonStart = 2 + (if (state.playbackError != null) 1 else 0) +
            (if (state.detailError != null) 1 else 0) +
            (if (children.error != null && !children.retryAppend) 1 else 0)
        AutomaticListPagination(listState, children.pagination, item.id, { vm.loadFolder(item.id, true) },
            endIndex = seasonStart + seasonRows.lastIndex)
    }
    val seriesId = item.seriesId?.takeIf { item.type == "Season" && it.isNotBlank() }
    LaunchedEffect(seriesId) {
        if (seriesId != null && seriesId !in state.folders) vm.loadFolder(seriesId)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("detail-content"), state = listState, contentPadding = PaddingValues(bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "detail-hero") {
            DetailHero(item, session, vm.api, state.preparingPlay, playable, resumeItems.isNotEmpty(),
                resumeItems.firstOrNull() ?: extras?.nextUp?.firstOrNull(), onPlay, onStart, onCast?.let { { it(item) } },
                onFavorite = { vm.toggleFavorite(item) },
                onDownload = if (session.canDownload && item.canDownload != false && item.type in setOf("Movie", "Episode", "Video", "Audio")) ({ vm.prepareDownload(item) }) else null,
                onWatched = { vm.togglePlayed(item) }, actionBusy = state.actionBusy)
        }
        // 播放失败由全局的重试对话框提示，详情页不再重复一张同样的错误卡片。
        state.detailError?.let { error -> item(key = "detail-error") {
            ErrorPanel(error, modifier = Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
        } }
        if (hasChildren) {
            if (item.type == "Series") {
                item(key = "children-title") { Box(Modifier.windowInsetsPadding(horizontalSafeArea)) {
                    SectionTitle(tr("季"), count = children.total.takeIf { it > 0 } ?: item.childCount?.takeIf { it > 0 })
                } }
                children.error?.takeUnless { children.retryAppend }?.let { error -> item(key = "children-error") {
                    ErrorPanel(error, modifier = Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
                } }
                items(seasonRows, key = { "season-row/${it.first().id}" }) { row ->
                    DetailSeasonRow(row, session, vm.api, vm::open,
                        Modifier.windowInsetsPadding(horizontalSafeArea))
                }
                if (children.items.isEmpty() && !children.loading && children.error == null) item(key = "children-empty") {
                    EmptyPanel(tr("暂无可见季"), tr("请检查 Jellyfin 中的媒体与访问权限。"))
                }
                children.error?.takeIf { children.retryAppend }?.let { error -> item(key = "children-append-error") {
                    ErrorPanel(error, modifier = Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
                } }
            } else item(key = "children-episodes") {
                DetailEpisodePicker(item, children.items, children.total, children.nextIndex, children.loading, children.loadingMore,
                    children.error, state.folders[seriesId]?.items.orEmpty().ifEmpty { listOf(item) }, session, vm.api,
                    vm::switchDetailSeason, vm::open, { vm.loadFolder(item.id, true) },
                    Modifier.windowInsetsPadding(horizontalSafeArea), seasonsPage = state.folders[seriesId],
                    onLoadSeasonsMore = { seriesId?.let { vm.loadFolder(it, true) } },
                    seasonSwitchEnabled = !state.preparingPlay && state.sourceSelection == null && state.activePlayback == null)
            }
        }
        if (item.type == "Episode" && extras?.siblings.orEmpty().isNotEmpty()) item(key = "detail-siblings") {
            DetailResumeShelf(item.parentIndexNumber?.let { tr("来自第 %1\$s 季", it) } ?: tr("同季剧集"), extras?.siblings.orEmpty(), session, vm.api, vm::open, nextUp = true)
        }
        if (state.preferences.showDetailChapters && item.type in setOf("Movie", "Episode") && item.chapters.any { !state.preferences.hideChaptersWithoutImage || it.hasThumbnail() }) item(key = "detail-chapters") {
            Box(Modifier.windowInsetsPadding(horizontalSafeArea)) {
                DetailChaptersShelf(item, session, vm.api, state.preparingPlay) { chapter ->
                    vm.preparePlayback(item.copy(positionTicks = chapter.startPositionTicks))
                }
            }
        }
        if (item.people.isNotEmpty()) item(key = "detail-people") {
            Column(Modifier.windowInsetsPadding(horizontalSafeArea), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(tr("演职人员"))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 按位置复用头像槽：换季后第 N 位沿用同一槽，新头像到达前保留旧图，不闪成一排灰圈。
                    itemsIndexed(item.people.distinctBy { it.id }, key = { index, _ -> index }) { _, person ->
                        PersonCard(person, session, vm.api) { vm.openPerson(person) }
                    }
                }
            }
        }
        if (state.preferences.showArtwork && item.backdropTags.isNotEmpty()) item(key = "detail-artwork") {
            Column(Modifier.windowInsetsPadding(horizontalSafeArea), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(tr("艺术图"))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(item.backdropTags.indices.toList(), key = { it }) { index ->
                        PosterImage(item, session, vm.api, Modifier.width(240.dp).aspectRatio(16f / 9f), wide = true, imageType = "Backdrop", imageIndex = index)
                    }
                }
            }
        }
        if (item.mediaSources.isNotEmpty()) item(key = "detail-media-info") {
            MediaStreamInfo(item, Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
        }
        if (extras?.similar.orEmpty().isNotEmpty()) item(key = "detail-similar") {
            Box(Modifier.windowInsetsPadding(horizontalSafeArea)) {
                PosterShelf(tr("推荐"), extras?.similar.orEmpty(), session, vm.api, vm::open)
            }
        }
        extras?.error?.let { error -> item(key = "detail-extras-error") {
            ErrorPanel(error, modifier = Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
        } }
    }
}

@Composable
internal fun DetailHero(item: MediaItem, session: Session, api: JellyfinApi, busy: Boolean, playable: Boolean, hasResume: Boolean,
    playTarget: MediaItem?, onPlay: () -> Unit, onStart: () -> Unit, onCast: (() -> Unit)? = null,
    onFavorite: () -> Unit, onDownload: (() -> Unit)?, onWatched: () -> Unit, actionBusy: Set<String>) {
    val surface = MaterialTheme.colorScheme.surface
    val safeArea = AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)
    val season = item.type == "Season"
    // 背景大图是首屏的卡内采样源，播放键与图标键是叠在上面的毛玻璃。
    CompositionLocalProvider(LocalTopBarBlurState provides rememberTopBarBlurState(LocalAppPreferences.current.blurTopBar)) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val seasonPosterWidth = (maxWidth * .34f).coerceIn(108.dp, 154.dp)
        val imageHeight = if (season) (maxWidth * .85f).coerceIn(300.dp, 460.dp) else (maxWidth * 1.2f).coerceIn(400.dp, 600.dp)
        val titleTop = if (season) 88.dp + AppSafeDrawingInsets.asPaddingValues().calculateTopPadding()
            else (maxWidth * .58f).coerceIn(210.dp, 340.dp)
        val scrim = MaterialTheme.colorScheme.scrim
        val fadeEnd = (titleTop.value + if (season) 64f else 84f) / imageHeight.value
        val gradient = remember(surface, scrim, fadeEnd) { detailHeroGradient(surface, scrim, fadeEnd) }
        Box(Modifier.fillMaxWidth().height(imageHeight).topBarBlurSource(zIndex = -1f).testTag("detail-hero-background")) {
            DetailBackgroundImage(item, session, api, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(gradient))
        }
        Column(Modifier.fillMaxWidth().windowInsetsPadding(safeArea).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(titleTop))
            if (season) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                PosterImage(item, session, api, Modifier.width(seasonPosterWidth).aspectRatio(2f / 3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTitle(item.copy(name = item.seriesName?.takeIf(String::isNotBlank) ?: item.name), session, api, compact = true)
                    if (!item.seriesName.isNullOrBlank()) Text(item.name, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    DetailInfo(item)
                }
            } else {
                DetailTitle(item, session, api)
                DetailInfo(item)
            }
            if (playable) DetailActions(item, busy, hasResume, onPlay, onStart, onCast,
                onFavorite, onDownload, onWatched, "favorite/${item.id}" in actionBusy,
                "download/${item.id}" in actionBusy, "played/${item.id}" in actionBusy, playTarget)
            item.seriesName?.takeIf { item.type == "Episode" && it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DetailSynopsis(item)
        }
    }
    }
}

@Composable
private fun DetailTitle(item: MediaItem, session: Session, api: JellyfinApi, compact: Boolean = false) {
    val context = LocalContext.current
    val request = remember(context, session, item.id, item.logoTag, item.logoItemId) { PosterRequests.logo(context, session, api, item) }
    var imageReady by remember(request) { mutableStateOf(false) }
    val shadowColor = rememberUpdatedState(MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
    val shadowLayer = rememberGraphicsLayer()
    val shadowRadius = with(LocalDensity.current) { 2.dp.toPx() }
    val shadowBlur = remember(shadowRadius) { BlurEffect(shadowRadius, shadowRadius, TileMode.Decal) }
    val transform = remember(shadowLayer, shadowBlur) {
        { state: AsyncImagePainter.State ->
            if (state is AsyncImagePainter.State.Success) state.copy(painter = DetailLogoPainter(state.painter, shadowLayer, shadowColor, shadowBlur)) else state
        }
    }
    val titleSlot = if (compact) 32.dp else 84.dp
    Box(Modifier.fillMaxWidth().heightIn(min = titleSlot).testTag("detail-title/${item.id}"),
        contentAlignment = if (compact) Alignment.TopStart else Alignment.BottomStart) {
        Text(item.name, Modifier.fillMaxWidth().padding(bottom = 4.dp).then(if (imageReady) Modifier.alpha(0f) else Modifier),
            fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (request != null) AsyncImage(request, null, imageLoader = LocalPosterLoader.current,
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(titleSlot).testTag("detail-logo/${item.id}")
                .semantics { stateDescription = if (imageReady) tr("已加载") else tr("正在加载") },
            contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, clipToBounds = false,
            transform = transform, onState = { imageReady = it is AsyncImagePainter.State.Success })
    }
}

/** 浅底上可读的评分金色，深浅主题共用。 */
private val DetailRatingGold = Color(0xFFD99A1E)

@Composable
private fun DetailInfo(item: MediaItem) {
    // 第一行：金色评分 + 分级/年份/季集/时长；第二行：类型。电影、电视剧这类与标题重复的标签不再单列。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val rating = item.communityRating?.takeIf { it.isFinite() && it in 0.0..10.0 }
        val episode = if (item.type == "Episode") listOfNotNull(item.parentIndexNumber?.let { tr("第%1\$s季", it) },
            item.indexNumber?.let { tr("第%1\$s集", it) }).joinToString(" ").takeIf(String::isNotBlank) else null
        val facts = listOfNotNull(episode, item.officialRating?.takeIf(String::isNotBlank), item.year?.toString(),
            item.runTimeTicks.takeIf { it > 0 && item.type != "Series" }?.let {
                if (item.type == "Audio") duration(TimeUnits.ticksToMs(it)) else compactDuration(TimeUnits.ticksToMs(it))
            })
        Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rating != null) {
                Icon(PathfinIcons.Star, null, Modifier.size(14.dp), tint = DetailRatingGold)
                Text(ratingText(rating), Modifier.padding(start = 3.dp), fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold, color = DetailRatingGold)
                if (facts.isNotEmpty()) Text("  ·  ", fontSize = 13.sp, color = PathfinAppearance.textMuted)
            }
            Text(facts.joinToString("  ·  "), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = PathfinAppearance.textSub)
        }
        Text(item.genres.joinToString(" · "), fontSize = 13.sp, lineHeight = 19.sp, minLines = 1, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = PathfinAppearance.textSub)
    }
}

@Composable
private fun DetailResumeShelf(title: String, items: List<MediaItem>, session: Session, api: JellyfinApi, onOpen: (MediaItem) -> Unit, nextUp: Boolean = false) {
    Column(Modifier.windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item -> ResumeCard(item, session, api, Modifier.width(196.dp), nextUp) { onOpen(item) } }
        }
    }
}

@Composable
private fun PersonCard(person: MediaPerson, session: Session, api: JellyfinApi, onOpen: () -> Unit) {
    val cardWidth = with(LocalDensity.current) { maxOf(84.dp, (13.sp.toPx() * 6).toDp()) }.coerceAtMost(132.dp)
    Column(Modifier.width(cardWidth).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PosterImage(MediaItem(person.id, person.name, "Person", imageTag = person.imageTag), session, api, Modifier.size(76.dp).clip(CircleShape))
        Text(person.name, Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(person.role?.takeIf(String::isNotBlank) ?: when (person.type.lowercase(Locale.ROOT)) {
            "actor" -> tr("演员"); "director" -> tr("导演"); "writer" -> tr("编剧"); "producer" -> tr("制片人"); "gueststar" -> tr("客串"); else -> " "
        }, Modifier.fillMaxWidth(), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

private fun metadataParts(item: MediaItem): List<String> = listOfNotNull(
    item.year?.toString(),
    item.indexNumber?.let { number ->
        if (item.type == "Season") tr("第 %1\$s 季", number)
        else if (item.type == "Audio") tr("曲目 %1\$s", number)
        else tr("%1\$s第 %2\$s 集", item.parentIndexNumber?.let { tr("第 %1\$s 季 · ", it) }.orEmpty(), number)
    },
    item.runTimeTicks.takeIf { it > 0 }?.let { tr("%1\$s 分钟", TimeUnits.ticksToMs(it) / 60_000) },
)

internal fun metadata(item: MediaItem): String = metadataParts(item).joinToString(" · ")
private fun duration(ms: Long): String = tr("%1\$s分%2\$s秒", ms / 60_000, ms / 1000 % 60)

/** 卡片上的时长只精确到分钟：1 小时 12 分、46 分、不到 1 分。 */
internal fun compactDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes >= 60 -> if (minutes % 60 == 0L) tr("%1\$s 小时", minutes / 60) else tr("%1\$s 小时 %2\$s 分", minutes / 60, minutes % 60)
        minutes >= 1 -> tr("%1\$s 分", minutes)
        else -> tr("不到 1 分")
    }
}
internal fun ratingText(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** 媒体缺图沿用参考的深中性底，不随浅色主题变成不可辨识的亮灰块。 */
private val MediaImagePlaceholder = Color(0xff3d3d3d)

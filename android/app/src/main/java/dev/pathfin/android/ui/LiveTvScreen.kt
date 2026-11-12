package dev.pathfin.android.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import android.os.Build
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import dev.pathfin.android.domain.LiveTvCategory
import java.time.Duration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.LiveTvApi
import dev.pathfin.android.domain.LiveTvCapabilities
import dev.pathfin.android.domain.LiveTvChannel
import dev.pathfin.android.domain.LiveTvProgram
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 当前会话的唯一频道快照与请求所有者；页面退出不销毁列表、搜索和滚动位置。 */
class LiveTvState(val session: Session, val api: JellyfinApi, private val scope: CoroutineScope,
    val onAuthExpired: () -> Unit = {}, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    val service = LiveTvApi(api)
    var access by mutableStateOf<LiveTvCapabilities?>(null)
        private set
    var channels by mutableStateOf<List<LiveTvChannel>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var nextIndex by mutableIntStateOf(0)
        private set
    var loadKind by mutableStateOf<LiveTvLoad?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
    var filter by mutableStateOf(LiveTvFilter.ALL)
    /** 本次进入后点播的频道；服务器的上次观看时间要等播放上报后才更新。 */
    var watchedId by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    val hasMore: Boolean get() = nextIndex < total
    private var loadedAt by mutableStateOf<Long?>(null)
    val loaded: Boolean get() = loadedAt != null
    private var job: Job? = null

    // 当前节目与权限短期复用；过期重进时保留旧频道，刷新整段成功后再替换。
    suspend fun enter() {
        job?.takeIf { it.isActive }?.let { pending -> pending.join(); if (pending.isCancelled) return }
        if (loadedAt?.let { clock() - it < 60_000L } != true) load()
    }
    fun cancel() { job?.cancel() }
    fun denyAccess() { cancel(); access = LiveTvCapabilities(false, false, false); error = null; clearChannels() }
    private fun clearChannels() {
        channels = emptyList(); total = 0; nextIndex = 0; loadedAt = clock()
    }

    fun load(kind: LiveTvLoad = LiveTvLoad.Refresh) {
        val append = kind != LiveTvLoad.Refresh
        val all = kind == LiveTvLoad.All
        if (loadKind != null || (append && !hasMore)) return
        val readTarget = nextIndex.coerceAtLeast(CHANNEL_PAGE_SIZE)
        loadKind = kind; error = null
        job = scope.launch {
            try {
                if (!append) {
                    val permission = service.capabilities(session)
                    access = permission
                    if (!permission.canView || !permission.isConfigured) {
                        clearChannels()
                        return@launch
                    }
                }
                var offset = if (append) nextIndex else 0
                var incoming = if (append) channels else emptyList()
                var readTotal: Int? = null
                var more: Boolean
                do {
                    val page = service.channels(session, offset, CHANNEL_PAGE_SIZE)
                    if (!append && ((readTotal != null && readTotal != page.totalCount) ||
                        (page.items.isEmpty() && offset < minOf(readTarget, page.totalCount)))) {
                        throw ApiException(null, tr("频道列表已变化，请下拉刷新重试"))
                    }
                    incoming = (incoming + page.items).distinctBy { it.id }
                    readTotal = page.totalCount
                    offset = page.nextIndex
                    more = page.hasMore
                    // 搜索全部是明确的长操作，逐页显示进度；普通刷新整段成功后再发布。
                    if (all) {
                        channels = incoming; total = page.totalCount; nextIndex = offset
                    }
                } while (more && (all || (!append && offset < readTarget)))
                channels = incoming; total = checkNotNull(readTotal); nextIndex = offset
                if (!append) loadedAt = clock()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                when ((exception as? ApiException)?.statusCode) {
                    401 -> onAuthExpired()
                    403 -> denyAccess()
                }
                error = liveTvError(exception)
            } finally { loadKind = null }
        }
    }
}

enum class LiveTvLoad { Refresh, Append, All }

enum class LiveTvFilter(val category: LiveTvCategory? = null) {
    ALL, FAVORITES, MOVIE(LiveTvCategory.MOVIE), SPORTS(LiveTvCategory.SPORTS), NEWS(LiveTvCategory.NEWS), KIDS(LiveTvCategory.KIDS);

    val label: String get() = when (this) {
        ALL -> tr("全部"); FAVORITES -> tr("收藏"); MOVIE -> tr("电影"); SPORTS -> tr("体育"); NEWS -> tr("新闻"); KIDS -> tr("少儿")
    }

    fun matches(channel: LiveTvChannel): Boolean = when (this) {
        ALL -> true
        FAVORITES -> channel.item.isFavorite
        else -> category in channel.currentProgram?.categories.orEmpty()
    }
}

@Composable
fun LiveTvScreen(state: LiveTvState, onBack: () -> Unit, onPlay: (MediaItem) -> Unit, preparingId: String? = null,
    onAmbientCovered: (Boolean) -> Unit = {}) = with(state) {
    val snackbar = remember(state) { SnackbarHostState() }
    var selected by remember(state) { mutableStateOf<LiveTvChannel?>(null) }
    val loading = loadKind != null
    val appending = loading && loadKind != LiveTvLoad.Refresh
    val loadingAll = loadKind == LiveTvLoad.All
    val now = liveTvClock()
    val canPlay = access?.canPlay == true
    val play: (LiveTvChannel) -> Unit = { channel -> watchedId = channel.id; onPlay(channel.item) }

    LaunchedEffect(state) { enter() }
    DisposableEffect(state) { onDispose { if (state.loadKind == LiveTvLoad.All) state.cancel() } }
    LaunchedEffect(error) {
        if (channels.isNotEmpty()) error?.let { message ->
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }
    // 搜索默认收起成按钮；有搜索词时（包括重新进入）保持展开。
    var searchOpen by remember(state) { mutableStateOf(false) }
    val searchExpanded = searchOpen || query.isNotEmpty()
    val focus = LocalFocusManager.current
    fun closeSearch() { query = ""; searchOpen = false; focus.clearFocus() }
    BackHandler { when { selected != null -> selected = null; searchExpanded -> closeSearch(); else -> onBack() } }
    val search = query.trim()
    // 只列出当前有频道可归入的筛选项；只剩“全部”时整行隐藏。
    val filters = remember(channels, filter) {
        LiveTvFilter.entries.filter { it == LiveTvFilter.ALL || it == filter || channels.any(it::matches) }
    }
    val filtered = remember(channels, search, filter) { channels.filter { channel ->
        filter.matches(channel) && (search.isEmpty() || channel.name.contains(search, true) ||
            channel.number.orEmpty().contains(search, true) || channel.currentProgram?.name?.contains(search, true) == true)
    } }
    // 收藏置顶、上次观看单独成卡，只在未搜索、未筛选的总览里出现。
    val overview = search.isEmpty() && filter == LiveTvFilter.ALL
    val favorites = if (overview) filtered.filter { it.item.isFavorite } else emptyList()
    val others = if (favorites.isEmpty()) filtered else filtered.filterNot { it.item.isFavorite }
    val resume = if (overview) channels.firstOrNull { it.id == watchedId }
        ?: channels.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! } else null
    // 触底按可见频道计数，页脚始终占一项；筛选为空也能继续读下一页，服务器游标仍用 nextIndex。
    AutomaticGridPagination(grid, PaginationStatus(filtered.size + 1, nextIndex, total,
        refreshing = loadKind == LiveTvLoad.Refresh, appending = appending, failed = error != null, hasMore = hasMore),
        session, { load(LiveTvLoad.Append) }, enabled = search.isEmpty() && selected == null)

    val topInset = LocalPageTopInset.current
    val density = LocalDensity.current
    // 搜索栏与筛选条一起悬浮成毛玻璃页头；频道列表按实测高度让位，滚动时从页头下方透出。
    var headerHeight by remember { mutableStateOf(60.dp) }
    val contentTop = topInset + headerHeight
    // 上次观看的深色氛围区还在页头下方时，页头与状态栏换成深色玻璃白字，和首页一致。
    val ambientShown by rememberUpdatedState(resume != null)
    val coverLimitPx = with(density) { (contentTop + AmbientFade).roundToPx() }
    val covered by remember(coverLimitPx) { derivedStateOf {
        val first = grid.layoutInfo.visibleItemsInfo.firstOrNull()
        ambientShown && first?.key == "resume" && first.offset.y + first.size.height > coverLimitPx
    } }
    LaunchedEffect(covered) { onAmbientCovered(covered) }
    Box(Modifier.fillMaxSize().testTag("live-tv-screen")) {
        RefreshablePage(loading, { load() }, Modifier.fillMaxSize().topBarBlurSource().background(PathfinAppearance.canvas),
            refreshOnEntry = false, indicatorTopInset = contentTop) {
            // 台标宫格：手机三列、宽屏自动加列，频道多时一屏也能看到十几个台。
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize().testTag("live-tv-channel-list"), grid,
                // 氛围区自己铺到页头下方，其余状态从页头下沿起排。
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (resume != null) 0.dp else contentTop + 8.dp,
                    bottom = LocalNavigationBottomInset.current + 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                when {
                    error != null && channels.isEmpty() -> item("error", span = full) { ErrorPanel(error!!, modifier = Modifier.testTag("live-tv-error")) }
                    access?.canView == false -> item("permission", span = full) { EmptyPanel(tr("没有直播电视权限"), tr("请联系服务器管理员为当前账号启用直播电视访问权限，再刷新此页。"), PathfinIcons.Lock) }
                    access?.isConfigured == false -> item("unconfigured", span = full) { EmptyPanel(tr("服务器尚未启用直播电视"), tr("请在 Jellyfin 服务端配置直播源及节目指南，然后刷新频道。"), PathfinIcons.Tv) }
                    !loaded -> item("initial", span = full) { EmptyPanel(tr("正在读取直播频道"), tr("连接当前服务器并读取频道与节目单。"), loading = true) }
                    channels.isEmpty() -> item("empty", span = full) { EmptyPanel(tr("暂无直播频道"), tr("服务器没有返回可访问频道，请检查直播源及账号的频道访问范围。"), PathfinIcons.Tv) }
                    filtered.isEmpty() -> item("search-empty", span = full) { EmptyPanel(tr("没有匹配的频道"), if (hasMore) tr("可继续载入全部频道，或换个频道名称、频道号或节目名称。") else tr("试试频道名称、频道号或当前节目名称。")) }
                }
                if (search.isNotEmpty() && hasMore) item("search-scope", span = full) {
                    Text(tr("搜索已加载的 %1\$s 个频道，可继续搜索全部频道", channels.size), Modifier.padding(start = 4.dp, bottom = 4.dp),
                        fontSize = 12.sp, lineHeight = 17.sp, color = PathfinAppearance.textMuted)
                }
                resume?.let { channel -> item("resume", span = full) {
                    LiveTvAmbient(channel, session, api, now, canPlay, preparingId, contentTop, onPlay = { play(channel) }, onGuide = { selected = channel })
                } }
                if (favorites.isNotEmpty()) {
                    item("favorites-title", span = full) { LiveTvSectionTitle(tr("收藏"), tr("%1\$s 个", favorites.size)) }
                    gridItems(favorites, key = { "channel/${it.id}" }) { channel ->
                        LiveTvChannelTile(channel, session, api, now, canPlay, preparingId, onPlay = { play(channel) }, onGuide = { selected = channel })
                    }
                }
                if (others.isNotEmpty() && (favorites.isNotEmpty() || resume != null)) item("channels-title", span = full) {
                    LiveTvSectionTitle(if (favorites.isEmpty()) tr("全部频道") else tr("其他频道"),
                        if (channels.size < total) tr("已载入 %1\$s / %2\$s", channels.size, total) else tr("%1\$s 个", total))
                }
                gridItems(others, key = { "channel/${it.id}" }) { channel ->
                    LiveTvChannelTile(channel, session, api, now, canPlay, preparingId, onPlay = { play(channel) }, onGuide = { selected = channel })
                }
                item("footer", span = full) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        when {
                            loadingAll -> QuietButton(tr("停止加载 · 已读取 %1\$s 个频道", channels.size), { cancel() },
                                Modifier.testTag("live-tv-cancel-search"), color = PathfinAppearance.accent)
                            hasMore && search.isNotEmpty() -> QuietButton(tr("搜索全部频道"), { load(LiveTvLoad.All) },
                                Modifier.testTag("live-tv-more"), enabled = !loading, color = PathfinAppearance.accent)
                            !hasMore && loaded && channels.isNotEmpty() -> Text(tr("已显示全部频道"), fontSize = 12.sp, color = PathfinAppearance.textMuted)
                        }
                    }
                }
            }
            PathfinSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavigationBottomInset.current))
        }
        // 页头接住点击不穿透；双击回到顶部，与应用标题栏一致。
        val scope = rememberCoroutineScope()
        PageTitleBar(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onDoubleTap = { scope.launch { grid.animateScrollToItem(0) } }) },
            height = contentTop, dark = covered) {
            Column(Modifier.fillMaxWidth().padding(top = topInset).onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PathfinIconButton(PathfinIcons.Back, tr("返回"), onBack, Modifier.testTag("live-tv-back"), tint = LocalContentColor.current, size = 44.dp)
                    // 平时只留返回与放大镜；点放大镜原位展开输入并弹出键盘，取消即清空收起。
                    AnimatedContent(searchExpanded, Modifier.weight(1f), transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                    }, label = "live-tv-search") { expanded ->
                        if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
                            val requester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { if (query.isEmpty()) requester.requestFocus() }
                            PathfinSearchField(query, { query = it.take(200) }, { focus.clearFocus() }, { query = "" }, tr("搜索频道、节目或台号"),
                                Modifier.weight(1f).padding(start = 4.dp).focusRequester(requester).testTag("live-tv-search"), frosted = true)
                            QuietButton(tr("取消"), ::closeSearch, Modifier.testTag("live-tv-search-close"), color = LocalContentColor.current)
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            PathfinIconButton(PathfinIcons.Search, tr("搜索频道"), { searchOpen = true },
                                Modifier.testTag("live-tv-search-open"), tint = LocalContentColor.current, size = 44.dp)
                        }
                    }
                }
                if (filters.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { option ->
                        PathfinPill(option.label, filter == option, { filter = option }, Modifier.testTag("live-tv-filter-${option.name.lowercase()}"))
                    }
                }
            }
        }
    }
    selected?.let { channel ->
        AppModalBottomSheet(onDismissRequest = { selected = null },
            containerColor = PathfinAppearance.canvas, dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            LiveTvGuide(session, service, api, channel, now, canPlay, preparingId, onPlay = { play(channel) }, onAuthExpired = onAuthExpired,
                onAccessDenied = { selected = null; denyAccess() })
        }
    }
}

/** 圆形播放键：强调色浅底配细线播放图标，准备中换成加载圈。 */
@Composable
private fun LiveTvPlayButton(label: String, enabled: Boolean, preparing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(modifier.size(size).clip(CircleShape).background(if (enabled || preparing) PathfinAppearance.accentSoft else PathfinAppearance.softFill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        if (preparing) PathfinSpinner(PathfinAppearance.accent, Modifier.size(18.dp))
        else Icon(PathfinIcons.PlayGlyph, null, Modifier.size(16.dp), tint = if (enabled) PathfinAppearance.accent else PathfinAppearance.textMuted)
    }
}

/** 正在播出且有起止时间的节目；时间缺失的当前节目照常显示，但不计进度。 */
private fun LiveTvChannel.nowShowing(now: Instant): LiveTvProgram? =
    currentProgram?.takeIf { it.isAiring(now) || it.start == null || it.end == null }

private fun LiveTvProgram.remainingMinutes(now: Instant): Long? = end?.takeIf { isAiring(now) }
    ?.let { ((Duration.between(now, it).seconds + 59) / 60).coerceAtLeast(1) }

/**
 * 宫格里的一台：深色“小屏幕”里完整显示台标，底边细线是当前节目进度；下面频道名与正在播的节目。
 * 点按播放（无播放权限时打开节目单），长按打开节目单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTvChannelTile(channel: LiveTvChannel, session: Session, api: JellyfinApi, now: Instant, canPlay: Boolean, preparingId: String?,
    onPlay: () -> Unit, onGuide: () -> Unit) {
    val current = channel.nowShowing(now)
    val shape = RoundedCornerShape(CardRadius)
    val guideLabel = tr("%1\$s 节目单", channel.name)
    Column(Modifier.fillMaxWidth().clip(shape).background(PathfinAppearance.card)
        .combinedClickable(role = Role.Button,
            onClickLabel = if (canPlay) tr("播放 %1\$s", channel.name) else guideLabel,
            onLongClickLabel = guideLabel, onLongClick = onGuide,
            onClick = { if (preparingId == null) { if (canPlay) onPlay() else onGuide() } })
        .semantics { customActions = listOf(CustomAccessibilityAction(guideLabel) { onGuide(); true }) }
        .padding(8.dp).testTag("live-tv-channel-${channel.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            LiveTvChannelMark(channel, session, api, preparing = preparingId == channel.item.id, modifier = Modifier.matchParentSize())
            current?.progress(now)?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().height(2.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(PathfinAppearance.accent))
                }
            }
        }
        Text(listOfNotNull(channel.number, channel.name).joinToString(" "), Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when { !canPlay -> tr("未获播放权限"); current != null -> current.name; else -> tr("暂无节目信息") },
            Modifier.padding(horizontal = 2.dp), fontSize = 11.sp, lineHeight = 15.sp,
            color = if (current != null && canPlay) PathfinAppearance.textSub else PathfinAppearance.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 台标块：深色圆角“小屏幕”里完整显示台标（不裁切）。台标多为白色或彩色透明底，
 * 浅底上会看不清，深底在明暗主题下都清楚；没有台标时显示台号或电视图标，准备播放时叠加载圈。
 */
@Composable
private fun LiveTvChannelMark(channel: LiveTvChannel, session: Session, api: JellyfinApi, preparing: Boolean,
    size: Dp = 44.dp, modifier: Modifier = Modifier.size(size)) {
    val logo = rememberServerImage(session, api.imageUrl(session, channel.item, width = 240), api)
    val screen = if (PathfinAppearance.isDark()) Color(0xFF2A2B31) else Color(0xFF24252B)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).background(screen), contentAlignment = Alignment.Center) {
        val inner = minOf(maxWidth, maxHeight)
        when {
            logo != null -> AsyncImage(logo, null, imageLoader = LocalPosterLoader.current,
                modifier = Modifier.fillMaxSize().padding(inner / 6), contentScale = ContentScale.Fit)
            channel.number != null -> Text(channel.number, fontSize = (inner.value * 0.3f).sp, fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.86f), style = TabularNumbers, maxLines = 1)
            else -> Icon(PathfinIcons.Tv, null, Modifier.size(inner / 2), tint = Color.White.copy(alpha = 0.6f))
        }
        if (preparing) Box(Modifier.matchParentSize().background(screen.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
            PathfinSpinner(Color.White, Modifier.size(inner * 0.4f))
        }
    }
}

/**
 * 上次观看：像首页一样铺满顶部并延伸到页头下方，不做卡片。直播没有剧照，用放大并强模糊的台标透出主色光晕，
 * 再叠一抹强调色，最后用首页同一套遮罩淡进页面底色。整块点按回到这个台。
 */
@Composable
private fun LiveTvAmbient(channel: LiveTvChannel, session: Session, api: JellyfinApi, now: Instant, canPlay: Boolean, preparingId: String?,
    contentTop: Dp, onPlay: () -> Unit, onGuide: () -> Unit) {
    val current = channel.nowShowing(now)
    val canvas = MaterialTheme.colorScheme.surface
    val accent = PathfinAppearance.accent
    val logo = rememberServerImage(session, api.imageUrl(session, channel.item, width = 160), api)
    val preparing = preparingId == channel.item.id
    // 网格两侧留了页边距，氛围区向两侧各伸出同样宽度，铺满整屏。
    Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val bleed = 16.dp.roundToPx()
        val width = constraints.maxWidth + bleed * 2
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
    }.testTag("live-tv-resume")) {
        Box(Modifier.matchParentSize().background(Color(0xFF15161A))) {
            if (logo != null && Build.VERSION.SDK_INT >= 31) AsyncImage(logo, null, imageLoader = LocalPosterLoader.current,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.6f }
                    .blur(56.dp, BlurredEdgeTreatment.Rectangle), contentScale = ContentScale.Fit)
            Box(Modifier.fillMaxSize().drawWithCache {
                val glow = Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), Color.Transparent),
                    Offset(size.width * 0.85f, size.height * 0.3f), size.width * 0.9f)
                val scrim = ambientScrim(canvas, ((size.height - AmbientFade.toPx()) / size.height).coerceIn(.5f, .95f))
                onDrawBehind { drawRect(glow); drawRect(scrim) }
            })
        }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxWidth()
                .clickable(enabled = canPlay && preparingId == null, role = Role.Button, onClickLabel = tr("继续观看 %1\$s", channel.name), onClick = onPlay)
                .padding(start = 20.dp, end = 20.dp, top = contentTop + 28.dp, bottom = AmbientFade).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 深色氛围区本身托得住白色台标，这里不再垫底块，台标放大直接压在背景上；没有台标时退回台号块。
                    if (logo != null) AsyncImage(logo, null, imageLoader = LocalPosterLoader.current,
                        modifier = Modifier.height(56.dp).widthIn(max = 132.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                    else LiveTvChannelMark(channel, session, api, preparing = false, size = 48.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(tr("上次观看"), fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = .7f), maxLines = 1)
                        Text(listOfNotNull(channel.number, channel.name).joinToString("  "), fontSize = 14.sp, lineHeight = 19.sp,
                            fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .95f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(current?.name ?: tr("暂无节目信息"), Modifier.padding(top = 6.dp), fontSize = 26.sp, lineHeight = 34.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(current?.takeIf { it.start != null && it.end != null }?.let(::liveTvProgramTime),
                    current?.remainingMinutes(now)?.let { tr("剩 %1\$s 分", it) })
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = .85f))
                current?.progress(now)?.let { progress ->
                    Box(Modifier.fillMaxWidth(.6f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canPlay) AmbientPlayButton(tr("继续看"), preparing, Modifier, tag = "live-tv-resume-play", onClick = onPlay)
                    QuietButton(tr("节目单"), onGuide, color = Color.White.copy(alpha = .9f))
                }
            }
        }
    }
}

@Composable
private fun LiveTvSectionTitle(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(title, Modifier.weight(1f))
        Text(detail, fontSize = 12.sp, color = PathfinAppearance.textMuted)
    }
}

private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun LiveTvGuide(session: Session, service: LiveTvApi, api: JellyfinApi, channel: LiveTvChannel, now: Instant, canPlay: Boolean, preparingId: String?, onPlay: () -> Unit, onAuthExpired: () -> Unit, onAccessDenied: () -> Unit) {
    val today = LocalDate.now()
    var date by remember(channel.id) { mutableStateOf(today) }
    // 高度放在内容上：抽屉面板本身透明，外层设高度会让面板贴顶、底部露出页面。
    // 节目单是大段文字，叠一层画布色加浓玻璃，后面频道列表的深色台标不会透成灰斑。
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).background(PathfinAppearance.canvas.copy(alpha = 0.7f)).windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveTvChannelMark(channel, session, api, preparing = false, size = 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
                Text(channel.name, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.number?.let { tr("频道 %1\$s · 节目单", it) } ?: tr("节目单"), fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub)
            }
            // 与频道列表同一枚圆形播放键，不再用大号墨色按钮与正在播出节目的入口重复抢眼。
            LiveTvPlayButton(tr("观看直播"), canPlay && preparingId == null, preparingId == channel.item.id, onPlay,
                Modifier.testTag("live-tv-guide-play"))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(7) { offset ->
                val day = today.plusDays(offset.toLong())
                PathfinPill(when (offset) { 0 -> tr("今天"); 1 -> tr("明天"); else -> day.format(DateTimeFormatter.ofPattern("M/d E")) }, date == day, { date = day })
            }
        }
        key(channel.id, date) { LiveTvPrograms(session, service, channel, date, now, canPlay, onPlay, onAuthExpired, onAccessDenied) }
    }
}

@Composable
private fun ColumnScope.LiveTvPrograms(session: Session, service: LiveTvApi, channel: LiveTvChannel, date: LocalDate, now: Instant, canPlay: Boolean, onPlay: () -> Unit, onAuthExpired: () -> Unit, onAccessDenied: () -> Unit) {
    val scope = rememberCoroutineScope()
    var programs by remember { mutableStateOf<List<LiveTvProgram>>(emptyList()) }
    var nextIndex by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var appending by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    fun load(append: Boolean = false) {
        if (loading || (append && !hasMore)) return
        val readTarget = nextIndex.coerceAtLeast(PROGRAM_PAGE_SIZE)
        loading = true; appending = append; error = null
        scope.launch {
            try {
                val zone = ZoneId.systemDefault()
                var offset = if (append) nextIndex else 0
                var incoming = if (append) programs else emptyList()
                var readTotal: Int? = null
                var more: Boolean
                do {
                    val page = service.programs(session, channel.id, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant(),
                        startIndex = offset, limit = PROGRAM_PAGE_SIZE)
                    if (!append && ((readTotal != null && readTotal != page.totalCount) ||
                        (page.items.isEmpty() && offset < minOf(readTarget, page.totalCount)))) {
                        throw ApiException(null, tr("节目单已变化，请下拉刷新重试"))
                    }
                    incoming = (incoming + page.items).distinctBy { it.id }
                    readTotal = page.totalCount; offset = page.nextIndex; more = page.hasMore
                } while (more && !append && offset < readTarget)
                programs = incoming; total = checkNotNull(readTotal); nextIndex = offset; hasMore = more; loaded = true
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (exception is ApiException && exception.statusCode == 401) onAuthExpired()
                if (exception is ApiException && exception.statusCode == 403) onAccessDenied()
                error = liveTvError(exception)
            } finally { loading = false; appending = false }
        }
    }
    LaunchedEffect(date) { load() }
    // 今天的节目单首次读到后滚到正在播（或下一档）节目，前面留一档作为上下文。
    var scrolledToNow by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) {
        if (!loaded || scrolledToNow || date != LocalDate.now()) return@LaunchedEffect
        scrolledToNow = true
        val target = programs.indexOfFirst { it.end?.let { end -> end > now } ?: false }
        if (target > 0) list.scrollToItem(target - 1 + if (error != null) 1 else 0)
    }
    AutomaticListPagination(list, PaginationStatus(programs.size, nextIndex, total,
        refreshing = loading && !appending, appending = appending, failed = error != null, hasMore = hasMore),
        channel.id to date, { load(append = true) })
    Eyebrow(date.format(DateTimeFormatter.ofPattern(tr("yyyy年M月d日 EEEE"))), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
    RefreshablePage(loading, { load() }, Modifier.weight(1f).fillMaxWidth(), refreshOnEntry = false) {
        LazyColumn(Modifier.fillMaxSize().testTag("live-tv-program-list"), state = list,
            // 时间轴的轨道线跨行连续，条目之间不留间距，间隔由每档自身的留白提供。
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            // 节目单在独立窗口的抽屉里，错误留在抽屉内，避免应用级通知被遮挡。
            error?.let { message -> item("error") {
                val errorModifier = Modifier.testTag("live-tv-program-error")
                if (programs.isEmpty()) ErrorPanel(message, errorModifier, retry = { load() }) else InlineError(message, errorModifier)
            } }
            if (programs.isEmpty() && error == null) item("empty") { EmptyPanel(if (!loaded) tr("正在读取节目单") else tr("暂无节目信息"),
                if (!loaded && error == null) tr("读取所选频道的当日节目。") else tr("服务器没有提供这一天的节目，仍可直接观看直播。"), loading = !loaded && error == null) }
            itemsIndexed(programs, key = { _, program -> "program/${program.id}" }) { index, program ->
                LiveTvTimelineRow(program, now, canPlay, first = index == 0, last = index == programs.lastIndex && !hasMore, onPlay = onPlay)
            }
        }
    }
}

/**
 * 节目单的一档：左侧开播时间，中间一条贯穿的轨道和节点，右侧节目内容。
 * 正在播出用实心强调色节点并带进度与观看入口；已结束整体淡化，节点空心。
 */
@Composable
private fun LiveTvTimelineRow(program: LiveTvProgram, now: Instant, canPlay: Boolean, first: Boolean, last: Boolean, onPlay: () -> Unit) {
    val airing = program.isAiring(now)
    val ended = program.end?.let { it <= now } == true
    val accent = PathfinAppearance.accent
    val track = PathfinAppearance.segmentTrack
    val node = when { airing -> accent; ended -> PathfinAppearance.textMuted; else -> PathfinAppearance.textSub }
    val dotTop = 18.dp
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).alpha(if (ended) 0.55f else 1f).testTag("live-tv-program-${program.id}")) {
        Text(program.start?.let(::liveTvTime) ?: "--:--", Modifier.width(48.dp).padding(top = 12.dp), fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal, color = if (airing) accent else PathfinAppearance.textSub,
            style = TabularNumbers)
        Box(Modifier.width(20.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            val dotY = dotTop.toPx()
            val line = 1.dp.toPx()
            if (!first) drawLine(track, Offset(x, 0f), Offset(x, dotY), line)
            if (!last) drawLine(track, Offset(x, dotY), Offset(x, size.height), line)
            if (airing) {
                drawCircle(accent.copy(alpha = 0.18f), 9.dp.toPx(), Offset(x, dotY))
                drawCircle(accent, 4.5.dp.toPx(), Offset(x, dotY))
            } else drawCircle(node, 4.dp.toPx(), Offset(x, dotY), style = Stroke(1.5.dp.toPx()))
        })
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 16.dp)
            .then(if (airing) Modifier.clip(RoundedCornerShape(CardRadius)).background(accent.copy(alpha = 0.07f)).padding(12.dp)
                else Modifier.padding(top = 4.dp)),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(listOfNotNull(liveTvProgramTime(program), tr("重播").takeIf { program.isRepeat },
                when { program.start == null || program.end == null -> null; airing -> tr("正在播出"); ended -> tr("已结束"); else -> null })
                .joinToString(" · "), fontSize = 11.5.sp, lineHeight = 16.sp, color = if (airing) accent else PathfinAppearance.textMuted)
            Text(program.name, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            program.episodeTitle?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = PathfinAppearance.textSub,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (program.overview.isNotBlank()) Text(program.overview, fontSize = 12.5.sp, lineHeight = 18.sp,
                color = PathfinAppearance.textMuted, maxLines = if (airing) 3 else 2, overflow = TextOverflow.Ellipsis)
            if (airing) {
                program.progress(now)?.let { PathfinProgressBar(it, Modifier.padding(top = 6.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(program.remainingMinutes(now)?.let { tr("剩 %1\$s 分", it) }.orEmpty(), Modifier.weight(1f),
                        fontSize = 12.sp, color = PathfinAppearance.textSub)
                    QuietButton(tr("观看直播"), onPlay, Modifier.testTag("live-tv-watch-program-${program.id}"),
                        enabled = canPlay, color = accent)
                }
            }
        }
    }
}

private const val CHANNEL_PAGE_SIZE = 40
private const val PROGRAM_PAGE_SIZE = 80

@Composable
private fun liveTvClock(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { now = Instant.now(); delay(30_000) } } }
    return now
}

private fun liveTvTime(instant: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)

private fun liveTvProgramTime(program: LiveTvProgram): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return if (program.start != null && program.end != null) runCatching {
        formatter.format(program.start) + " – " + formatter.format(program.end)
    }.getOrDefault(tr("播出时间无法显示")) else tr("播出时间未提供")
}

private fun liveTvError(exception: Exception): String =
    (exception as? ApiException)?.message ?: tr("读取直播电视失败，请刷新重试")

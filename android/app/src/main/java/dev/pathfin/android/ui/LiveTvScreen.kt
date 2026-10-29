package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 参考素材只证实直播入口；此页沿现有设计系统呈现真实 Jellyfin 直播能力。 */
@Composable
fun LiveTvScreen(session: Session, api: JellyfinApi, onBack: () -> Unit, onPlay: (MediaItem) -> Unit, onAuthExpired: () -> Unit = {},
    preparingId: String? = null) {
    key(session) { LiveTvContent(session, api, onBack, onPlay, onAuthExpired, preparingId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTvContent(session: Session, api: JellyfinApi, onBack: () -> Unit, onPlay: (MediaItem) -> Unit, onAuthExpired: () -> Unit,
    preparingId: String?) {
    val service = remember(api) { LiveTvApi(api) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var access by remember { mutableStateOf<LiveTvCapabilities?>(null) }
    var channels by remember { mutableStateOf<List<LiveTvChannel>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var nextIndex by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var appending by remember { mutableStateOf(false) }
    var loadingAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<LiveTvChannel?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val grid = rememberLazyGridState()
    val now = liveTvClock()

    fun load(append: Boolean = false, all: Boolean = false) {
        if (loading || (append && !hasMore)) return
        val readTarget = nextIndex.coerceAtLeast(CHANNEL_PAGE_SIZE)
        loading = true; appending = append; loadingAll = all; error = null
        job = scope.launch {
            try {
                if (!append) {
                    val permission = service.capabilities(session)
                    access = permission
                    if (!permission.canView || !permission.isConfigured) {
                        channels = emptyList(); total = 0; nextIndex = 0; hasMore = false; loaded = true
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
                        channels = incoming; total = page.totalCount; nextIndex = offset; hasMore = more; loaded = true
                    }
                } while (more && (all || (!append && offset < readTarget)))
                channels = incoming; total = checkNotNull(readTotal); nextIndex = offset; hasMore = more; loaded = true
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (exception is ApiException && exception.statusCode == 401) onAuthExpired()
                if (exception is ApiException && exception.statusCode == 403) {
                    access = LiveTvCapabilities(false, false, false); channels = emptyList(); hasMore = false
                }
                error = liveTvError(exception)
            } finally { loading = false; appending = false; loadingAll = false }
        }
    }

    LaunchedEffect(session) { load() }
    LaunchedEffect(error) {
        if (channels.isNotEmpty()) error?.let { message ->
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }
    BackHandler { if (selected != null) selected = null else onBack() }
    val search = query.trim()
    val filtered = remember(channels, search) { channels.filter { channel ->
        search.isEmpty() || channel.name.contains(search, true) || channel.number.orEmpty().contains(search, true) ||
            channel.currentProgram?.name?.contains(search, true) == true
    } }
    AutomaticGridPagination(grid, PaginationStatus(channels.size, nextIndex, total,
        refreshing = loading && !appending, appending = appending, failed = error != null, hasMore = hasMore),
        session, { load(append = true) }, enabled = search.isEmpty() && selected == null)

    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .testTag("live-tv-screen")) {
        // 搜索框就是标题栏：返回键右侧，省出原来单独一行的高度；频道数量放在下面的状态行。
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PathfinIconButton(PathfinIcons.Back, tr("返回"), onBack, Modifier.testTag("live-tv-back"), tint = PathfinAppearance.textMain, size = 44.dp)
            PathfinSearchField(query, { query = it.take(200) }, { focus.clearFocus() }, { query = "" }, tr("搜索直播频道或当前节目"),
                Modifier.weight(1f).padding(start = 4.dp).testTag("live-tv-search"))
        }
        val ready = loaded && access?.canView == true && access?.isConfigured == true
        Text(when {
            search.isNotEmpty() && hasMore -> tr("搜索已加载的 %1\$s 个频道，可继续搜索全部频道", channels.size)
            ready && channels.size < total -> tr("直播电视 · 已载入 %1\$s / %2\$s 个频道", channels.size, total)
            ready -> tr("直播电视 · %1\$s 个频道", total)
            else -> tr("频道与节目单来自当前 Jellyfin 服务器")
        },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp, lineHeight = 17.sp,
            color = PathfinAppearance.textMuted)
        RefreshablePage(loading, { load() }, Modifier.weight(1f).fillMaxWidth(), refreshOnEntry = false) {
            // 两列（宽屏自动多列）频道卡片，台标在上、正在播出的节目在下，一屏看到更多频道。
            LazyVerticalGrid(GridCells.Adaptive(158.dp), Modifier.fillMaxSize().testTag("live-tv-channel-list"), grid,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalNavigationBottomInset.current + 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                when {
                    error != null && channels.isEmpty() -> item("error", span = full) { ErrorPanel(error!!, modifier = Modifier.testTag("live-tv-error")) }
                    access?.canView == false -> item("permission", span = full) { EmptyPanel(tr("没有直播电视权限"), tr("请联系服务器管理员为当前账号启用直播电视访问权限，再刷新此页。"), PathfinIcons.Lock) }
                    access?.isConfigured == false -> item("unconfigured", span = full) { EmptyPanel(tr("服务器尚未启用直播电视"), tr("请在 Jellyfin 服务端配置直播源及节目指南，然后刷新频道。"), PathfinIcons.Tv) }
                    !loaded -> item("initial", span = full) { EmptyPanel(tr("正在读取直播频道"), tr("连接当前服务器并读取频道与节目单。"), loading = true) }
                    channels.isEmpty() -> item("empty", span = full) { EmptyPanel(tr("暂无直播频道"), tr("服务器没有返回可访问频道，请检查直播源及账号的频道访问范围。"), PathfinIcons.Tv) }
                    filtered.isEmpty() -> item("search-empty", span = full) { EmptyPanel(tr("没有匹配的频道"), if (hasMore) tr("可继续载入全部频道，或换个频道名称、频道号或节目名称。") else tr("试试频道名称、频道号或当前节目名称。")) }
                }
                gridItems(filtered, key = { "channel/${it.id}" }) { channel ->
                    LiveTvChannelTile(channel, session, api, now, access?.canPlay == true, preparingId,
                        onPlay = { onPlay(channel.item) }, onGuide = { selected = channel })
                }
                item("footer", span = full) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        when {
                            loadingAll -> QuietButton(tr("停止加载 · 已读取 %1\$s 个频道", channels.size), { job?.cancel() },
                                Modifier.testTag("live-tv-cancel-search"), color = PathfinAppearance.accent)
                            hasMore && search.isNotEmpty() -> QuietButton(tr("搜索全部频道"), { load(append = true, all = true) },
                                Modifier.testTag("live-tv-more"), enabled = !loading, color = PathfinAppearance.accent)
                            !hasMore && loaded && channels.isNotEmpty() -> Text(tr("已显示全部频道"), fontSize = 12.sp, color = PathfinAppearance.textMuted)
                        }
                    }
                }
            }
            PathfinSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavigationBottomInset.current))
        }
    }
    selected?.let { channel ->
        AppModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PathfinAppearance.canvas, dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            LiveTvGuide(session, service, channel, now, access?.canPlay == true, preparingId, onPlay = { onPlay(channel.item) }, onAuthExpired = onAuthExpired,
                onAccessDenied = { selected = null; access = LiveTvCapabilities(false, false, false); channels = emptyList(); hasMore = false })
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

@Composable
private fun LiveTvChannelTile(channel: LiveTvChannel, session: Session, api: JellyfinApi, now: Instant, canPlay: Boolean, preparingId: String?,
    onPlay: () -> Unit, onGuide: () -> Unit) {
    val current = channel.currentProgram?.takeIf { it.isAiring(now) || it.start == null || it.end == null }
    val airing = current?.isAiring(now) == true
    val shape = RoundedCornerShape(CardRadius)
    // 整块卡片点按即播放；台标右上角打开节目单，频道名右侧保留明确的播放键（准备中显示加载圈）。
    Column(Modifier.fillMaxWidth().clip(shape).background(PathfinAppearance.card).border(PathfinAppearance.cardBorder, shape)
        .clickable(enabled = canPlay && preparingId == null, role = Role.Button, onClickLabel = tr("播放 %1\$s", channel.name), onClick = onPlay)
        .testTag("live-tv-channel-${channel.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(PathfinAppearance.softFill), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth(.5f).fillMaxHeight(.46f), contentAlignment = Alignment.Center) {
                if (channel.item.imageTag != null) PosterImage(channel.item, session, api, Modifier.matchParentSize(), shape = RoundedCornerShape(8.dp))
                else Icon(PathfinIcons.Tv, null, Modifier.size(28.dp), tint = PathfinAppearance.textMuted)
            }
            if (airing) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { LiveTvBadge(tr("直播中"), live = true) }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                PathfinIconButton(PathfinIcons.Calendar, tr("%1\$s 节目单", channel.name), onGuide, Modifier.testTag("live-tv-guide-${channel.id}"),
                    tint = PathfinAppearance.textSub, size = 40.dp)
            }
            current?.progress(now)?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(PathfinAppearance.segmentTrack)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(PathfinAppearance.accent))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(channel.name, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current?.name ?: tr("暂无节目信息"), fontSize = 13.sp, lineHeight = 18.sp,
                color = if (current != null) PathfinAppearance.textSub else PathfinAppearance.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(current?.let(::liveTvProgramTime), channel.number?.let { tr("频道 %1\$s", it) },
                if (canPlay) null else tr("未获播放权限")).joinToString(" · ").ifEmpty { tr("直播频道") },
                fontSize = 11.5.sp, lineHeight = 16.sp, color = PathfinAppearance.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LiveTvPlayButton(tr("播放 %1\$s", channel.name), canPlay && preparingId == null, preparingId == channel.item.id, onPlay,
            Modifier.padding(start = 6.dp).testTag("live-tv-play-${channel.id}"), size = 36.dp)
        }
    }
}

/** 节目状态小标签：正在播出用强调色浅底，其他用浅灰底。 */
@Composable
private fun LiveTvBadge(text: String, live: Boolean) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(if (live) PathfinAppearance.accentSoft else PathfinAppearance.softFill)
        .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
        color = if (live) PathfinAppearance.accent else PathfinAppearance.textSub, maxLines = 1)
}

@Composable
private fun LiveTvGuide(session: Session, service: LiveTvApi, channel: LiveTvChannel, now: Instant, canPlay: Boolean, preparingId: String?, onPlay: () -> Unit, onAuthExpired: () -> Unit, onAccessDenied: () -> Unit) {
    val today = LocalDate.now()
    var date by remember(channel.id) { mutableStateOf(today) }
    // 高度放在内容上：抽屉面板本身透明，外层设高度会让面板贴顶、底部露出页面。
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
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
    AutomaticListPagination(list, PaginationStatus(programs.size, nextIndex, total,
        refreshing = loading && !appending, appending = appending, failed = error != null, hasMore = hasMore),
        channel.id to date, { load(append = true) })
    Eyebrow(date.format(DateTimeFormatter.ofPattern(tr("yyyy年M月d日 EEEE"))), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
    RefreshablePage(loading, { load() }, Modifier.weight(1f).fillMaxWidth(), refreshOnEntry = false) {
        LazyColumn(Modifier.fillMaxSize().testTag("live-tv-program-list"), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { message -> item("error") { ErrorPanel(message) } }
            if (programs.isEmpty()) item("empty") { EmptyPanel(if (!loaded && error == null) tr("正在读取节目单") else tr("暂无节目信息"),
                if (!loaded && error == null) tr("读取所选频道的当日节目。") else tr("服务器没有提供这一天的节目，仍可直接观看直播。"), loading = !loaded && error == null) }
            items(programs, key = { "program/${it.id}" }) { program ->
                val airing = program.isAiring(now)
                GroupCard(Modifier.testTag("live-tv-program-${program.id}")) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (airing) 4.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(liveTvProgramTime(program), Modifier.weight(1f), fontSize = 12.5.sp, lineHeight = 17.sp,
                                fontWeight = FontWeight.Medium, color = PathfinAppearance.textSub)
                            LiveTvBadge(when { program.start == null || program.end == null -> tr("时间未知"); airing -> tr("正在播出")
                                program.end <= now -> tr("已结束"); else -> tr("即将播出") }, live = airing)
                        }
                        Text(program.name, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        program.episodeTitle?.let { Text(it, fontSize = 13.5.sp, lineHeight = 19.sp, color = PathfinAppearance.textMain,
                            maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (program.overview.isNotBlank()) Text(program.overview, fontSize = 12.5.sp, lineHeight = 18.sp,
                            color = PathfinAppearance.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (airing) {
                            program.progress(now)?.let { PathfinProgressBar(it, Modifier.padding(top = 4.dp)) }
                            QuietButton(tr("观看直播"), onPlay, Modifier.align(Alignment.End).testTag("live-tv-watch-program-${program.id}"),
                                enabled = canPlay, color = PathfinAppearance.accent)
                        }
                    }
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

private fun liveTvProgramTime(program: LiveTvProgram): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return if (program.start != null && program.end != null) runCatching {
        formatter.format(program.start) + " – " + formatter.format(program.end)
    }.getOrDefault(tr("播出时间无法显示")) else tr("播出时间未提供")
}

private fun liveTvError(exception: Exception): String = when (exception) {
    is ApiException -> exception.message ?: tr("读取直播电视失败，请刷新重试")
    else -> tr("读取直播电视失败，请刷新重试")
}

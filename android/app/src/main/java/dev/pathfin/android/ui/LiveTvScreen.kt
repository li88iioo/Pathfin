package dev.pathfin.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.pathfin.android.data.LiveTvSnapshotStore
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import android.os.Build
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.pathfin.android.data.LiveTvGroupSource
import dev.pathfin.android.domain.LiveTvGroups
import java.time.Duration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.selected
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

/**
 * 当前会话的唯一频道快照与请求所有者；随 AppState 保留，切走再回来直接画上次的列表与滚动位置。
 * 频道整份读完后一次发布（不分页），先显示磁盘快照，后台刷新只替换内容、键不变，列表不会跳。
 */
class LiveTvState(val session: Session, val api: JellyfinApi, private val scope: CoroutineScope,
    val onAuthExpired: () -> Unit = {}, private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val groupSource: LiveTvGroupSource? = null, private val snapshots: LiveTvSnapshotStore? = null) {
    val service = LiveTvApi(api)
    var access by mutableStateOf<LiveTvCapabilities?>(null)
        private set
    var channels by mutableStateOf<List<LiveTvChannel>>(emptyList())
        private set
    /** M3U 分组（见 LiveTvGroupSource）。 */
    var groups by mutableStateOf(LiveTvGroups())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 磁盘快照已读完（有无都算）；读完前不画列表，避免先空后满的闪烁。 */
    var restored by mutableStateOf(snapshots == null && groupSource == null)
        private set
    var query by mutableStateOf("")
    /** 选中的分组标签；null 为“全部”，未分组的频道用 OTHER_GROUP。 */
    var group by mutableStateOf<String?>(null)
    /** 本次进入后点播的频道；服务器的上次观看时间要等播放上报后才更新。 */
    var watchedId by mutableStateOf<String?>(null)
    val list = LazyGridState()
    /** 进入搜索前总览的滚动位置；取消搜索时原位回去。 */
    internal var overviewPosition = 0 to 0
    val tabScroll = ScrollState(0)
    private var loadedAt by mutableStateOf<Long?>(null)
    val loaded: Boolean get() = loadedAt != null
    private var job: Job? = null
    private var groupsJob: Job? = null

    // 当前节目与权限短期复用；过期重进时保留旧频道，整份刷新成功后再替换。
    suspend fun enter() {
        if (!restored) {
            if (channels.isEmpty()) snapshots?.load(session)?.let { channels = it }
            groupSource?.cached(session)?.let { groups = it }
            restored = true
        }
        job?.takeIf { it.isActive }?.let { pending -> pending.join(); if (pending.isCancelled) return }
        if (loadedAt?.let { clock() - it < 60_000L } != true) load()
    }
    fun cancel() { job?.cancel(); groupsJob?.cancel() }
    fun denyAccess() {
        cancel(); access = LiveTvCapabilities(false, false, false); error = null
        channels = emptyList(); loadedAt = clock()
        scope.launch { snapshots?.save(session, emptyList()) }
    }

    fun load() {
        if (refreshing) return
        refreshGroups()
        refreshing = true; error = null
        job = scope.launch {
            try {
                val permission = service.capabilities(session)
                access = permission
                if (!permission.canView || !permission.isConfigured) { publish(emptyList()); return@launch }
                val all = ArrayList<LiveTvChannel>()
                var offset = 0
                var total: Int? = null
                do {
                    val page = service.channels(session, offset, CHANNEL_PAGE_SIZE)
                    if (total != null && total != page.totalCount) throw ApiException(null, tr("频道列表已变化，请下拉刷新重试"))
                    all += page.items
                    total = page.totalCount
                    offset = page.nextIndex
                } while (page.hasMore)
                publish(all.distinctBy { it.id })
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                when ((exception as? ApiException)?.statusCode) {
                    401 -> onAuthExpired()
                    403 -> denyAccess()
                }
                error = liveTvError(exception)
            } finally { refreshing = false }
        }
    }

    private suspend fun publish(value: List<LiveTvChannel>) {
        channels = value; loadedAt = clock()
        snapshots?.save(session, value)
    }

    /** 先显示缓存，再后台重读；读取失败（如离家时调谐器在内网）保留缓存，不打扰频道列表。 */
    private fun refreshGroups() {
        val source = groupSource ?: return
        if (groupsJob?.isActive == true) return
        groupsJob = scope.launch {
            try {
                source.refresh(session, api)?.let { groups = it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {}
        }
    }
}

/** 列表的每一行；键稳定，刷新只替换内容。 */
private sealed interface LiveTvRow { val key: String }
private data object LiveTvResumeRow : LiveTvRow { override val key = "resume" }
private data object LiveTvTabsRow : LiveTvRow { override val key = "tabs" }
private data class LiveTvHeaderRow(val title: String, val count: Int, override val key: String) : LiveTvRow
private data class LiveTvChannelRow(val channel: LiveTvChannel) : LiveTvRow { override val key = "channel/${channel.id}" }
private data class LiveTvSkeletonRow(val index: Int) : LiveTvRow { override val key = "skeleton/$index" }
private data class LiveTvStatusRow(val title: String, val detail: String, val error: Boolean = false) : LiveTvRow { override val key = "status" }
private data object LiveTvFillerRow : LiveTvRow { override val key = "filler" }
private const val OTHER_GROUP = "\u0000other"

/**
 * 直播页：顶部是上次观看，下面一行分组标签（默认“全部”），点哪个组网格就只剩那组频道，不用长距离滑动。
 * 频道是自适应列数的台标卡片；换组只替换标签下方的卡片，标签原地不动；组里频道少时底部补足留白，
 * 网格不会因内容变短而回弹。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveTvScreen(state: LiveTvState, onBack: () -> Unit, onPlay: (MediaItem) -> Unit, preparingId: String? = null,
    onAmbientCovered: (Boolean) -> Unit = {}) = with(state) {
    var selected by remember(state) { mutableStateOf<LiveTvChannel?>(null) }
    val pageActive = LocalPageActive.current
    val now = liveTvClock()
    val canPlay = access?.canPlay == true
    val play: (LiveTvChannel) -> Unit = { channel -> watchedId = channel.id; onPlay(channel.item) }
    val guide: (LiveTvChannel) -> Unit = { selected = it }

    RefreshFailureNotice(error?.takeIf { channels.isNotEmpty() }, settled = !refreshing, retry = { load() })
    // 搜索默认收起成按钮；有搜索词时（包括重新进入）保持展开。
    var searchOpen by remember(state) { mutableStateOf(false) }
    val searchExpanded = searchOpen || query.isNotEmpty()
    val focus = LocalFocusManager.current
    var searchFocused by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state, pageActive) {
        if (pageActive) enter() else {
            selected = null
            if (searchFocused) focus.clearFocus()
        }
    }
    fun closeSearch() { query = ""; searchOpen = false; focus.clearFocus() }
    BackHandler(enabled = pageActive) { when { selected != null -> selected = null; searchExpanded -> closeSearch(); else -> onBack() } }

    val search = query.trim()
    // 搜索与总览互换时整页直接替换：结果从头显示，取消后回到搜索前的位置。位置在换内容的同一次重组里请求，
    // 与新内容同帧生效，不会先按旧首项的键跟随、下一帧再跳；首次组合（含重新进入）不动，保留上次位置。
    val searchMark = remember(state) { arrayOf(search) }
    if (search != searchMark[0]) {
        if (searchMark[0].isEmpty()) overviewPosition = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        val (index, offset) = if (search.isEmpty()) overviewPosition else 0 to 0
        list.requestScrollToItem(index, offset)
        searchMark[0] = search
    }
    // 换内容的这一帧不做逐项淡入与位移，否则整页卡片闪一下、再从结果位置滑回去；换组的动画照常。
    var settledSearch by remember(state) { mutableStateOf(search) }
    val animateRows = search == settledSearch
    LaunchedEffect(search) { settledSearch = search }
    val sections = remember(channels, groups) { groups.sections(channels) }
    val results = remember(channels, search) {
        if (search.isEmpty()) emptyList() else channels.filter { channel -> channel.name.contains(search, true) ||
            channel.number.orEmpty().contains(search, true) || channel.currentProgram?.name?.contains(search, true) == true }
    }
    val resume = if (search.isNotEmpty()) null else channels.firstOrNull { it.id == watchedId }
        ?: channels.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! }
    val status = when {
        !restored -> null
        error != null && channels.isEmpty() -> LiveTvStatusRow(error!!, "", error = true)
        access?.canView == false -> LiveTvStatusRow(tr("没有直播电视权限"), tr("请联系服务器管理员为当前账号启用直播电视访问权限，再刷新此页。"))
        access?.isConfigured == false -> LiveTvStatusRow(tr("服务器尚未启用直播电视"), tr("请在 Jellyfin 服务端配置直播源及节目指南，然后刷新频道。"))
        channels.isEmpty() && loaded -> LiveTvStatusRow(tr("暂无直播频道"), tr("服务器没有返回可访问频道，请检查直播源及账号的频道访问范围。"))
        search.isNotEmpty() && results.isEmpty() && channels.isNotEmpty() -> LiveTvStatusRow(tr("没有匹配的频道"), tr("试试频道名称、频道号或当前节目名称。"))
        else -> null
    }
    val groupKeys = remember(sections) { if (sections.size > 1) listOf<String?>(null) + sections.map { it.group ?: OTHER_GROUP } else emptyList() }
    val chosen = sections.takeIf { group != null }?.firstOrNull { (it.group ?: OTHER_GROUP) == group }
    val rows = remember(sections, chosen, resume?.id, results, search, status, restored, loaded) {
        val rows = ArrayList<LiveTvRow>()
        when {
            !restored -> Unit
            status != null -> rows += status
            channels.isEmpty() -> repeat(9) { rows += LiveTvSkeletonRow(it) }
            search.isNotEmpty() -> results.forEach { rows += LiveTvChannelRow(it) }
            chosen != null -> {
                if (resume != null) rows += LiveTvResumeRow
                rows += LiveTvTabsRow
                chosen.channels.forEach { rows += LiveTvChannelRow(it) }
                rows += LiveTvFillerRow
            }
            else -> {
                if (resume != null) rows += LiveTvResumeRow
                if (sections.size > 1) rows += LiveTvTabsRow
                sections.forEach { section ->
                    val title = section.group ?: if (sections.size > 1) tr("其他频道") else tr("全部频道")
                    rows += LiveTvHeaderRow(title, section.channels.size, "section/${section.group.orEmpty()}")
                    section.channels.forEach { rows += LiveTvChannelRow(it) }
                }
            }
        }
        rows
    }

    val topInset = LocalPageTopInset.current
    val density = LocalDensity.current
    // 页头悬浮成毛玻璃；列表按实测高度让位，滚动时从页头下方透出。
    var headerHeight by remember { mutableStateOf(60.dp) }
    val headerTop = topInset + headerHeight
    // 没有上次观看时不留只有放大镜的空页头：放大镜并入首行（分组标签或“全部频道”），内容从状态栏下开始；
    // 首行滚走一半后页头带着放大镜淡入，深处也能直接搜索。页头出现不推动内容。
    val searchInRows = resume == null && rows.firstOrNull().let { it == LiveTvTabsRow || it is LiveTvHeaderRow }
    val firstRowGonePx = with(density) { 34.dp.roundToPx() }
    val firstRowGone by remember(state, firstRowGonePx) { derivedStateOf {
        list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > firstRowGonePx
    } }
    val headerRow = !searchInRows || searchExpanded || firstRowGone
    val contentTop = if (searchInRows) topInset else headerTop
    val topPadding = if (rows.firstOrNull() == LiveTvResumeRow) 0.dp else contentTop + 8.dp
    // 上次观看的深色氛围区还在页头下方时，页头与状态栏换成深色玻璃白字，和首页一致。
    val coverLimitPx = with(density) { (contentTop + LiveAmbientFade).roundToPx() }
    val covered by remember(coverLimitPx) { derivedStateOf {
        val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
        first?.key == LiveTvResumeRow.key && first.offset.y + first.size.height > coverLimitPx
    } }
    LaunchedEffect(covered) { onAmbientCovered(covered) }

    // 原本停在顶部时，上次观看等行后插到最前面也保持在顶部，不让封面被顶出屏幕。
    // 网格按键保持首个可见项：首项恰好被挤到第 2 项且没有偏移，说明是新行插在了最上面。
    LaunchedEffect(rows.firstOrNull()?.key) {
        withFrameNanos { }
        if (list.firstVisibleItemIndex == 1 && list.firstVisibleItemScrollOffset == 0 && !list.isScrollInProgress) list.scrollToItem(0)
    }

    val haptic = LocalHapticFeedback.current
    val tabLabels = groupKeys.map { key -> when (key) { null -> tr("全部"); OTHER_GROUP -> tr("其他"); else -> key } }
    val activeTab = groupKeys.indexOf(chosen?.let { it.group ?: OTHER_GROUP }).coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    fun selectTab(index: Int) {
        if (index == activeTab || index !in groupKeys.indices) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        group = groupKeys[index]
        // 从长列表深处换组时，把标签行带回页头下沿，新组从头显示，而不是停在被截断的位置。
        val tabs = rows.indexOf(LiveTvTabsRow)
        if (tabs >= 0 && list.firstVisibleItemIndex > tabs) scope.launch {
            list.scrollToItem(tabs, -with(density) { (contentTop - topPadding).coerceAtLeast(0.dp).roundToPx() })
        }
    }
    // 在频道区左右滑动换组：卡片跟手平移，超过阈值换到相邻分组，新组从反方向滑入；首尾分组带阻尼。
    val swipe = remember(state) { Animatable(0f) }
    val currentTab by rememberUpdatedState(activeTab)
    val currentSelectTab by rememberUpdatedState { index: Int -> selectTab(index) }
    val swipeGesture = if (search.isNotEmpty() || groupKeys.size < 2) Modifier else Modifier.pointerInput(groupKeys) {
        val threshold = size.width * .2f
        detectHorizontalDragGestures(
            onDragEnd = { scope.launch {
                val offset = swipe.value
                val next = currentTab + if (offset < 0) 1 else -1
                if (abs(offset) > threshold && next in groupKeys.indices) {
                    currentSelectTab(next)
                    swipe.snapTo(-sign(offset) * size.width * .25f)
                }
                swipe.animateTo(0f, spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow))
            } },
            onDragCancel = { scope.launch { swipe.animateTo(0f) } },
        ) { change, delta ->
            change.consume()
            val outward = (delta < 0 && currentTab == groupKeys.lastIndex) || (delta > 0 && currentTab == 0)
            scope.launch { swipe.snapTo(swipe.value + if (outward) delta * .3f else delta) }
        }
    }
    val shifted = Modifier.graphicsLayer {
        translationX = swipe.value
        alpha = 1f - (abs(swipe.value) / size.width.coerceAtLeast(1f)).coerceAtMost(.5f)
    }
    val bottomInset = LocalNavigationBottomInset.current
    val canvas = PathfinAppearance.canvas
    var pulled by remember(state) { mutableStateOf(false) }
    // 首行里的放大镜；页头出现时由页头的放大镜接替，同一时刻只有一个。
    val rowSearch: @Composable () -> Unit = {
        if (headerRow) Spacer(Modifier.size(44.dp))
        else PathfinIconButton(PathfinIcons.Search, tr("搜索频道"), { searchOpen = true },
            Modifier.offset(x = 8.dp).testTag("live-tv-search-open"), tint = PathfinAppearance.textMain, size = 44.dp)
    }
    // 卡片高度随字号变化，按实测值补足分组末尾留白。
    var tileHeight by remember { mutableStateOf(0.dp) }
    // 只观察视口尺寸；滚动产生的新 layoutInfo 不应让填充行每帧重组。
    val viewport by remember(list) { derivedStateOf { list.layoutInfo.viewportSize } }
    LaunchedEffect(refreshing) { if (!refreshing) pulled = false }
    Box(Modifier.fillMaxSize().testTag("live-tv-screen")) {
        // 只有手动下拉才显示刷新圈；进入时的后台刷新静默替换内容。
        RefreshablePage(pulled && refreshing, { pulled = true; load() }, Modifier.fillMaxSize().topBarBlurSource().background(canvas),
            refreshOnEntry = false, indicatorTopInset = contentTop) {
            // 台标宫格：手机三列、宽屏自动加列；上次观看、标签、分组标题与状态整行跨列。
            LazyVerticalGrid(GridCells.Adaptive(TileMinWidth), Modifier.fillMaxSize().then(swipeGesture).testTag("live-tv-channel-list"), list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding,
                    bottom = bottomInset + 8.dp),
                verticalArrangement = Arrangement.spacedBy(GridSpacing), horizontalArrangement = Arrangement.spacedBy(GridSpacing)) {
                gridItems(rows, key = { it.key }, contentType = { it::class },
                    span = { if (it is LiveTvChannelRow || it is LiveTvSkeletonRow) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { row ->
                    // 换组时旧卡片直接移除、新卡片淡入，不让两组叠在一起闪。
                    val item = if (!animateRows) Modifier else Modifier.animateItem(fadeInSpec = tween(220),
                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow), fadeOutSpec = null)
                    val moving = if (row is LiveTvResumeRow || row is LiveTvTabsRow) item else item.then(shifted)
                    when (row) {
                        LiveTvResumeRow -> resume?.let { channel ->
                            LiveTvAmbient(channel, session, api, now, canPlay, preparingId, contentTop, onPlay = { play(channel) }, onGuide = { guide(channel) })
                        }
                        LiveTvTabsRow -> LiveTvTabs(tabLabels, activeTab, tabScroll, ::selectTab, item,
                            trailing = rowSearch.takeIf { searchInRows })
                        LiveTvFillerRow -> {
                            // 让标签能停在页头下沿：补足“页头 + 标签 + 本组各排卡片 + 底部留白”之外的高度。
                            val (width, height) = with(density) { viewport.width.toDp() to viewport.height.toDp() }
                            val columns = maxOf(1, ((width - 32.dp + GridSpacing) / (TileMinWidth + GridSpacing)).toInt())
                            val lines = ((chosen?.channels?.size ?: 0) + columns - 1) / columns
                            val used = contentTop + TabsHeight + GridSpacing + (tileHeight + GridSpacing) * lines + bottomInset + 16.dp
                            Spacer(moving.height((height - used).coerceAtLeast(0.dp)))
                        }
                        is LiveTvHeaderRow -> LiveTvSectionHeader(row.title, row.count, moving,
                            trailing = rowSearch.takeIf { searchInRows && row == rows.first() })
                        is LiveTvChannelRow -> LiveTvChannelTile(row.channel, session, api, now, canPlay, preparingId,
                            onPlay = { play(row.channel) }, onGuide = { guide(row.channel) },
                            modifier = moving.onSizeChanged { tileHeight = with(density) { it.height.toDp() } })
                        is LiveTvSkeletonRow -> LiveTvSkeletonTile(row.index, item)
                        is LiveTvStatusRow -> if (row.error) ErrorPanel(row.title, retry = { load() }, modifier = item.testTag("live-tv-error"))
                            else Box(item) { EmptyPanel(row.title, row.detail, PathfinIcons.Tv) }
                    }
                }
            }
        }
        // 页头接住点击不穿透；双击回到顶部，与应用标题栏一致。
        val headerBottom by animateDpAsState(if (headerRow) headerTop else topInset, tween(200), label = "live-tv-header")
        PageTitleBar(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onDoubleTap = { scope.launch { list.animateScrollToItem(0) } }) },
            height = headerBottom, dark = covered) {
            Column(Modifier.fillMaxWidth().padding(top = topInset)) {
                AnimatedVisibility(headerRow, enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(200))) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 8.dp)
                        .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }.testTag("live-tv-header-row"),
                        verticalAlignment = Alignment.CenterVertically) {
                        // 平时只留放大镜；点放大镜原位展开输入并弹出键盘，取消即清空收起。
                        AnimatedContent(searchExpanded, Modifier.weight(1f), transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                        }, label = "live-tv-search") { expanded ->
                            if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
                                val requester = remember { FocusRequester() }
                                LaunchedEffect(pageActive) { if (pageActive && query.isEmpty()) requester.requestFocus() }
                                PathfinSearchField(query, { query = it.take(200) }, { focus.clearFocus() }, { query = "" }, tr("搜索频道、节目或台号"),
                                    Modifier.weight(1f).padding(start = 4.dp).focusRequester(requester)
                                        .onFocusChanged { searchFocused = it.isFocused }.testTag("live-tv-search"), frosted = true)
                                QuietButton(tr("取消"), ::closeSearch, Modifier.testTag("live-tv-search-close"), color = LocalContentColor.current)
                            } else Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                PathfinIconButton(PathfinIcons.Search, tr("搜索频道"), { searchOpen = true },
                                    Modifier.testTag("live-tv-search-open"), tint = LocalContentColor.current, size = 44.dp)
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.takeIf { pageActive }?.let { channel ->
        AppModalBottomSheet(onDismissRequest = { selected = null },
            containerColor = PathfinAppearance.canvas, dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            LiveTvGuide(session, service, api, channel, now, canPlay, preparingId, onPlay = { play(channel) }, onAuthExpired = onAuthExpired,
                onAccessDenied = { selected = null; denyAccess() })
        }
    }
}

/**
 * 分组标签：一行可横滑的文字标签。选中底色是同一块胶囊从旧标签滑到新标签，不在两处各自淡入淡出；
 * 只在选中项被遮住时才滚动标签行——滚动中的点按会被当作“停止滚动”吞掉，平时不滚就不会点不动。
 */
@Composable
private fun LiveTvTabs(labels: List<String>, active: Int, scroll: ScrollState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null) {
    val density = LocalDensity.current
    val bounds = remember(labels) { mutableStateMapOf<Int, IntRange>() }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    val target = bounds[active]
    LaunchedEffect(target) {
        val range = target ?: return@LaunchedEffect
        if (width.value == 0f) { left.snapTo(range.first.toFloat()); width.snapTo(range.count().toFloat()); return@LaunchedEffect }
        val motion = spring<Float>(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow)
        launch { left.animateTo(range.first.toFloat(), motion) }
        launch { width.animateTo(range.count().toFloat(), motion) }
        val margin = with(density) { 24.dp.roundToPx() }
        val viewport = scroll.viewportSize
        val destination = when {
            range.first - margin < scroll.value -> range.first - margin
            range.last + margin > scroll.value + viewport -> range.last + margin - viewport
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(destination.coerceIn(0, scroll.maxValue))
    }
    val fill = PathfinAppearance.accentSoft
    Row(modifier.fillMaxWidth().height(TabsHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().layout { measurable, constraints ->
            // 标签行贯穿到屏幕两边，首尾仍与列表对齐；带尾部按钮时右侧止于按钮前。
            val bleed = 16.dp.roundToPx()
            val full = constraints.maxWidth + bleed + if (trailing == null) bleed else 0
            val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
        }.horizontalScroll(scroll).testTag("live-tv-tabs"), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset { IntOffset(left.value.roundToInt(), 0) }.width(with(density) { width.value.toDp() }).height(36.dp)
                .clip(CircleShape).background(fill))
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                labels.forEachIndexed { index, label ->
                    val chosen = index == active
                    val ink by animateColorAsState(if (chosen) PathfinAppearance.accent else PathfinAppearance.textSub, tween(200), label = "tab-ink")
                    Text(label, Modifier.onPlaced { bounds[index] = it.positionInParent().x.roundToInt().let { x -> x until x + it.size.width } }
                        .height(36.dp).clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab,
                            onClickLabel = tr("跳到 %1\$s", label)) { onSelect(index) }
                        .semantics { selected = chosen }
                        .padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically).testTag("live-tv-tab-$label"),
                        color = ink, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun LiveTvSectionHeader(title: String, count: Int, modifier: Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 2.dp),
        verticalAlignment = if (trailing == null) Alignment.Bottom else Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
            color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tr("%1\$s 个", count), fontSize = 12.sp, color = PathfinAppearance.textMuted)
        trailing?.invoke()
    }
}

/**
 * 台标卡片：上面是深色“小屏幕”完整显示台标，底边细条是当前节目进度；下面是频道名与正在播的节目。
 * 点按播放（无播放权限时打开节目单），长按打开节目单。
 */
@Composable
private fun LiveTvChannelTile(channel: LiveTvChannel, session: Session, api: JellyfinApi, now: Instant, canPlay: Boolean, preparingId: String?,
    onPlay: () -> Unit, onGuide: () -> Unit, modifier: Modifier) {
    val current = channel.nowShowing(now)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card)
        .liveTvActions(channel, canPlay, preparingId, onPlay, onGuide)
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
        Text(listOfNotNull(channel.displayNumber, channel.name).joinToString(" "), Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when { !canPlay -> tr("未获播放权限"); current != null -> current.name; else -> tr("暂无节目信息") },
            Modifier.padding(horizontal = 2.dp), fontSize = 11.sp, lineHeight = 15.sp,
            color = if (current != null && canPlay) PathfinAppearance.textSub else PathfinAppearance.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 首次读取前的占位卡片，与真实卡片同形，内容到达时原位替换不跳。 */
@Composable
private fun LiveTvSkeletonTile(index: Int, modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "live-tv-skeleton").animateFloat(.45f, .9f,
        infiniteRepeatable(tween(900, delayMillis = index * 60), RepeatMode.Reverse), label = "pulse")
    val block = PathfinAppearance.softFill
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card)
        .graphicsLayer { alpha = pulse }.padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(block))
        Box(Modifier.padding(top = 10.dp, start = 2.dp).fillMaxWidth(.7f).height(11.dp).clip(CircleShape).background(block))
        Box(Modifier.padding(top = 6.dp, start = 2.dp, bottom = 2.dp).fillMaxWidth(.5f).height(9.dp).clip(CircleShape).background(block))
    }
}

/** 点按播放（无权限时看节目单），长按节目单；读屏另给节目单动作。 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.liveTvActions(channel: LiveTvChannel, canPlay: Boolean, preparingId: String?, onPlay: () -> Unit, onGuide: () -> Unit) =
    composed {
        val guideLabel = tr("%1\$s 节目单", channel.name)
        combinedClickable(role = Role.Button,
            onClickLabel = if (canPlay) tr("播放 %1\$s", channel.name) else guideLabel,
            onLongClickLabel = guideLabel, onLongClick = onGuide,
            onClick = { if (preparingId == null) { if (canPlay) onPlay() else onGuide() } })
            .semantics { customActions = listOf(CustomAccessibilityAction(guideLabel) { onGuide(); true }) }
    }

private val TileMinWidth = 96.dp
private val GridSpacing = 10.dp
private val TabsHeight = 52.dp
/** 上次观看底部淡出段：比首页短，分组标签紧跟在封面下方。 */
private val LiveAmbientFade = 56.dp

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
            channel.displayNumber != null -> Text(channel.displayNumber!!, fontSize = (inner.value * 0.3f).sp, fontWeight = FontWeight.SemiBold,
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
        // 放大的模糊台标会溢出图层边界，必须裁在氛围区内，否则会盖到下面的频道列表。
        Box(Modifier.matchParentSize().clipToBounds().background(Color(0xFF15161A))) {
            if (logo != null && Build.VERSION.SDK_INT >= 31) AsyncImage(logo, null, imageLoader = LocalPosterLoader.current,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.6f }
                    .blur(56.dp, BlurredEdgeTreatment.Rectangle), contentScale = ContentScale.Fit)
            Box(Modifier.fillMaxSize().drawWithCache {
                val glow = Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), Color.Transparent),
                    Offset(size.width * 0.85f, size.height * 0.3f), size.width * 0.9f)
                val scrim = ambientScrim(canvas, ((size.height - LiveAmbientFade.toPx()) / size.height).coerceIn(.5f, .95f))
                onDrawBehind { drawRect(glow); drawRect(scrim) }
            })
        }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxWidth()
                .clickable(enabled = canPlay && preparingId == null, role = Role.Button, onClickLabel = tr("继续观看 %1\$s", channel.name), onClick = onPlay)
                .padding(start = 20.dp, end = 20.dp, top = contentTop + 28.dp, bottom = LiveAmbientFade).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 深色氛围区本身托得住白色台标，这里不再垫底块，台标放大直接压在背景上；没有台标时退回台号块。
                    if (logo != null) AsyncImage(logo, null, imageLoader = LocalPosterLoader.current,
                        modifier = Modifier.height(56.dp).widthIn(max = 132.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                    else LiveTvChannelMark(channel, session, api, preparing = false, size = 48.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(tr("上次观看"), fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = .7f), maxLines = 1)
                        Text(listOfNotNull(channel.displayNumber, channel.name).joinToString("  "), fontSize = 14.sp, lineHeight = 19.sp,
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
                Text(channel.displayNumber?.let { tr("频道 %1\$s · 节目单", it) } ?: tr("节目单"), fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub)
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

private const val CHANNEL_PAGE_SIZE = 100
private const val PROGRAM_PAGE_SIZE = 80

@Composable
private fun liveTvClock(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    val owner = LocalLifecycleOwner.current
    val active = LocalPageActive.current
    LaunchedEffect(owner, active) {
        if (active) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = Instant.now(); delay(30_000) }
        }
    }
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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.delay

private enum class BrowserSheet { SORT, CATEGORY, FILTER }

/** 查询更换即同步退出多选；分页追加和封面样式变化不改变选择上下文。 */
@Composable
internal fun rememberLibrarySelection(pageKey: String, query: LibraryQuery?): MutableState<Set<String>> =
    remember(pageKey, query) { mutableStateOf(emptySet()) }

@Composable
internal fun BrowserScreen(
    state: BrowserUiState,
    session: Session,
    api: JellyfinApi,
    onQueryChange: (LibraryQuery) -> Unit,
    onStyleChange: (PosterStyle) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onRetryFilters: () -> Unit = onRetry,
    libraryType: String? = null,
    libraryCategories: Boolean = true,
    showIndex: Boolean = false,
    selection: Set<String> = emptySet(),
    onSelectionChange: (Set<String>) -> Unit = {},
) {
    var sheet by remember { mutableStateOf<BrowserSheet?>(null) }
    val displayedQuery = state.displayedQuery
    val displayedState = if (state.query == displayedQuery) state else state.copy(query = displayedQuery)
    val selecting = selection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    BackHandler(enabled = selecting) { onSelectionChange(emptySet()) }
    val grid = rememberLazyGridState()
    val scrollToTop = LocalScrollToTopRequests.current
    val requestsAtEntry = remember { scrollToTop.value }
    LaunchedEffect(scrollToTop.value) { if (scrollToTop.value != requestsAtEntry) grid.animateScrollToItem(0) }
    var lastQuery by rememberSaveable { mutableIntStateOf(displayedQuery.hashCode()) }
    if (lastQuery != displayedQuery.hashCode()) {
        // 新结果的第一次测量就回顶；不能先画旧位置，或在请求刚发出时跳到旧结果顶部。
        grid.requestScrollToItem(0)
        lastQuery = displayedQuery.hashCode()
    }
    val canBrowse by rememberUpdatedState(LocalPageRefreshEnabled.current)
    val latestQuery by rememberUpdatedState(displayedQuery)
    val changeQuery by rememberUpdatedState(onQueryChange)
    // 至少提前一批首读的 40 项，宽屏留两屏；追加 100 项后自然停止，避免尾部断层。
    AutomaticGridPagination(grid, state.content.pagination, displayedQuery, onMore,
        enabled = sheet == null && state.query == displayedQuery, minimumAheadItems = 100, screensAhead = 2)
    val tabs = availableLibraryTabs(libraryType, libraryCategories)
    val category = displayedQuery.tab.label
    val genresPage = displayedQuery.tab == LibraryTab.GENRES
    val peoplePage = displayedQuery.itemTypes == setOf("Person")
    val fixedSort = when {
        displayedQuery.tab == LibraryTab.RESUME -> LibrarySort.DATE_PLAYED
        displayedQuery.tab == LibraryTab.UPCOMING -> LibrarySort.PREMIERE_DATE
        genresPage || peoplePage -> LibrarySort.TITLE
        else -> null
    }
    val supportsQueryControls = fixedSort == null
    val supportsQueryFilters = supportsQueryControls && !genresPage
    val supportsAlphabetIndex = !peoplePage && displayedQuery.tab != LibraryTab.RESUME && displayedQuery.tab != LibraryTab.UPCOMING
    val emptyContent = state.content.items.isEmpty() && state.content.error == null
    val pendingContent = state.content.loading || state.restoringCache
    // 已确认的空结果也属于可保留的内容，下拉/后台校验时不把它替换成整页加载态。
    var confirmedEmpty by remember(session, displayedQuery) { mutableStateOf(emptyContent && !pendingContent) }
    LaunchedEffect(session, displayedQuery, pendingContent, emptyContent) {
        if (!pendingContent) confirmedEmpty = emptyContent
    }
    val awaitingFirstContent = emptyContent && pendingContent && !confirmedEmpty
    var loadingVisible by remember(session, state.query, awaitingFirstContent) { mutableStateOf(false) }
    LaunchedEffect(session, state.query, awaitingFirstContent) {
        if (awaitingFirstContent) {
            // 只推迟反馈；磁盘/网络结果随到随画，快请求不挂载一闪而过的加载面板。
            delay(300)
            loadingVisible = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(PathfinAppearance.canvas)) {
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val columns = BrowserColumns.resolve(state.style, maxWidth.value, fontScale)
        val expandedToolbar = fontScale >= 1.5f && maxWidth < 500.dp || maxWidth < 320.dp
        val toolbarRowHeight = with(density) {
            (maxOf(scaledLineHeightPx(14.sp, 20.sp), scaledLineHeightPx(13.sp, 19.sp)) + 2).toDp()
        }.coerceAtLeast(48.dp)
        val toolbarHeight = toolbarRowHeight + if (expandedToolbar) 48.dp else 0.dp
        val topInset = LocalPageTopInset.current
        val gridCells = GridCells.Fixed(columns)
        val startPadding = BrowserColumns.horizontalPaddingDp.dp
        val endPadding = startPadding
        // 复用网格本身的像素分配，保留余数列的1px差异，首组合即可请求准确宽度的图片。
        val imageWidths = with(gridCells) {
            with(density) {
                calculateCrossAxisCellSizes(
                    availableSize = (constraints.maxWidth - startPadding.roundToPx() - endPadding.roundToPx()).coerceAtLeast(0),
                    spacing = BrowserColumns.spacingDp.dp.roundToPx(),
                ).map { it.toDp() }
            }
        }
        Box(Modifier.fillMaxSize().background(PathfinAppearance.canvas)) {
            Box(Modifier.fillMaxSize()) {
                RefreshablePage(state.content.loading || state.content.loadingMore, onRetry,
                    Modifier.fillMaxSize().topBarBlurSource().background(PathfinAppearance.canvas), enabled = sheet == null,
                    indicatorTopInset = topInset + toolbarHeight) {
                    LazyVerticalGrid(
                        columns = gridCells, state = grid, modifier = Modifier.fillMaxSize().testTag("browser-grid"),
                        contentPadding = PaddingValues(start = startPadding, end = endPadding,
                            top = topInset + toolbarHeight + 4.dp, bottom = LocalNavigationBottomInset.current),
                        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(BrowserColumns.spacingDp.dp),
                    ) {
                        state.content.error?.takeUnless { state.content.retryAppend }?.let { error -> item(key = "browser-error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(error) } }
                        gridItemsIndexed(state.content.items, key = { _, item -> item.id }) { index, item ->
                            // 目录和分类是导航入口，避免影片批量操作递归修改整个目录。
                            val selectable = !genresPage && !peoplePage && item.type !in setOf("Genre", "Person", "Folder", "CollectionFolder")
                            BrowserMediaCard(item, session, api, state.style, Modifier.fillMaxWidth(),
                                imageWidth = imageWidths[index % columns].takeIf { it > 0.dp }, libraryStyle = true,
                                selected = if (selecting && selectable) item.id in selection else null,
                                onLongClick = if (selectable) { {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelectionChange(selection.toggle(item.id))
                                } } else null,
                            ) { if (selecting && selectable) onSelectionChange(selection.toggle(item.id)) else onOpen(item) }
                        }
                        if (emptyContent && (!awaitingFirstContent || loadingVisible)) item(key = "browser-empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyPanel(
                                title = if (awaitingFirstContent) tr("正在读取媒体") else tr("没有找到内容"),
                                subtitle = if (awaitingFirstContent) tr("正在从服务器同步内容，请稍候…")
                                else tr("尝试调整分类或筛选条件。"),
                                loading = awaitingFirstContent,
                            )
                        }
                        state.content.error?.takeIf { state.content.retryAppend }?.let { error ->
                            item(key = "browser-append-error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(error) }
                        }
                    }
                }
                // 索引条在采样源（刷新页）之外，才能以毛玻璃透出背后的网格。
                if (showIndex && supportsAlphabetIndex) key(session) {
                    AlphabetIndexRail(displayedQuery.nameInitial, { letter ->
                        if (canBrowse && sheet == null && letter != latestQuery.nameInitial) {
                            // 正常 UP 或完整无障碍激活后立即提交；浮层不改变网格的左右边距。
                            changeQuery(latestQuery.copy(nameInitial = letter))
                        }
                    }, Modifier.align(Alignment.CenterEnd).padding(
                        top = topInset + toolbarHeight, end = 2.dp, bottom = LocalNavigationBottomInset.current,
                    ), enabled = canBrowse && sheet == null)
                }
                TopBarBlurSurface(
                    Modifier.align(Alignment.TopStart).padding(top = topInset).fillMaxWidth().height(toolbarHeight)
                        .pointerInput(Unit) { detectTapGestures { } },
                    topOffset = topInset, height = toolbarHeight, secondaryRow = true,
                ) {
                    BrowserToolbar(displayedState, category, fixedSort, supportsQueryFilters, supportsAlphabetIndex,
                        expanded = expandedToolbar, rowHeight = toolbarRowHeight,
                        onSort = { sheet = BrowserSheet.SORT }, onCategory = { sheet = BrowserSheet.CATEGORY },
                        onStyle = { onStyleChange(if (state.style == PosterStyle.POSTER) PosterStyle.BACKDROP else PosterStyle.POSTER) }, onFilter = { sheet = BrowserSheet.FILTER },
                        onReset = { changeQuery((if (supportsQueryFilters) latestQuery.clearUiFilters() else latestQuery).copy(nameInitial = null)) },
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        when (sheet) {
            BrowserSheet.SORT -> SortSheet(displayedQuery, { sheet = null }) { onQueryChange(it); sheet = null }
            BrowserSheet.CATEGORY -> CategorySheet(state.query, tabs, { sheet = null }) { onQueryChange(it); sheet = null }
            BrowserSheet.FILTER -> FilterSheet(displayedState, { sheet = null }, onRetryFilters) { onQueryChange(it); sheet = null }
            null -> Unit
        }
    }
}

@Composable
private fun BrowserToolbar(state: BrowserUiState, category: String, fixedSort: LibrarySort?, canFilter: Boolean, canIndex: Boolean, expanded: Boolean,
    rowHeight: Dp, onSort: () -> Unit, onCategory: () -> Unit, onStyle: () -> Unit, onFilter: () -> Unit,
    onReset: () -> Unit, modifier: Modifier = Modifier) {
    val controlColor = PathfinAppearance.textMain
    val supportsQueryControls = fixedSort == null
    val sortButton: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.height(rowHeight).clip(CircleShape)
            .then(if (supportsQueryControls) Modifier.clickable(role = Role.Button, onClick = onSort) else Modifier)
            .padding(horizontal = 4.dp).testTag("browser-sort"), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fixedSort?.label ?: sortLabel(state.query.sort), Modifier.weight(1f, fill = false), fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold, color = controlColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supportsQueryControls) Icon(sortDirectionIcon(state.query.descending), if (state.query.descending) tr("当前降序") else tr("当前升序"),
                    Modifier.padding(start = 3.dp).size(18.dp).testTag("browser-sort-direction-icon"),
                    tint = controlColor)
            }
        }
    }
    val count: @Composable () -> Unit = {
        Box(Modifier.widthIn(min = 30.dp, max = if (expanded) 72.dp else 56.dp).heightIn(min = 20.dp)
            .testTag("browser-count"), contentAlignment = Alignment.CenterStart) {
            Text(state.content.total.toString(), fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = PathfinAppearance.textSub,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val categoryButton: @Composable () -> Unit = {
        Row(Modifier.widthIn(max = if (expanded) 144.dp else 92.dp).heightIn(min = 48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onCategory).padding(start = 6.dp, end = 2.dp).testTag("browser-category"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(category, Modifier.weight(1f, fill = false), color = controlColor,
                fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(PathfinIcons.ChevronDown, null, Modifier.size(16.dp), tint = controlColor)
        }
    }
    // 筛选或字母索引任一生效时出现，一次清掉两者，与 Jellyfin 网页端的清除筛选一致。
    val resetButton: @Composable () -> Unit = {
        // 与「全部 ⌄」同为无底文字按钮：回转箭头加文字，只用强调色区分。
        if ((canFilter && state.query.hasUiFilters()) || (canIndex && state.query.nameInitial != null)) Row(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onReset)
                .padding(horizontal = 6.dp).testTag("browser-reset"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(PathfinIcons.Reset, null, Modifier.size(15.dp), tint = PathfinAppearance.accent)
            Text(tr("重置"), fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.accent, maxLines = 1)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        // 点击直接在竖幅与横幅间切换，图标即当前布局。
        val poster = state.style == PosterStyle.POSTER
        PathfinIconButton(if (poster) PathfinIcons.LayoutPoster else PathfinIcons.LayoutBackdrop,
            if (poster) tr("封面样式：竖幅") else tr("封面样式：横幅"), onStyle, Modifier.testTag("browser-style"),
            tint = controlColor, size = 44.dp)
        // 有生效的筛选时在图标右上角放一个点缀色小点。
        if (canFilter) Box {
            PathfinIconButton(PathfinIcons.Filter, tr("筛选"), onFilter, Modifier.testTag("browser-filter"),
                tint = controlColor, size = 44.dp)
            if (state.query.hasUiFilters()) Box(Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 10.dp).size(6.dp)
                .background(PathfinAppearance.accent, CircleShape).border(1.dp, PathfinAppearance.canvas, CircleShape))
        } else Spacer(Modifier.size(44.dp))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("browser-toolbar")) {
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                sortButton(Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                count()
            }
            resetButton()
            categoryButton()
            if (!expanded) actions()
        }
        if (expanded) Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

private fun sortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.DATE_CREATED, LibrarySort.DATE_ADDED -> tr("按添加日期")
    LibrarySort.PREMIERE_DATE -> tr("按发行日期")
    LibrarySort.TITLE -> tr("按标题")
    LibrarySort.COMMUNITY_RATING -> tr("按评分")
    else -> tr("按%1\$s", sort.label)
}

private fun sortDirectionIcon(descending: Boolean): ImageVector =
    if (descending) PathfinIcons.SortDescending else PathfinIcons.SortAscending

private fun sortIcon(sort: LibrarySort): ImageVector = when (sort) {
    LibrarySort.DATE_CREATED, LibrarySort.DATE_ADDED -> PathfinIcons.Clock
    LibrarySort.PREMIERE_DATE -> PathfinIcons.Calendar
    LibrarySort.TITLE -> PathfinIcons.SortAlpha
    LibrarySort.COMMUNITY_RATING -> PathfinIcons.Star
    else -> PathfinIcons.Swap
}

private fun categoryIcon(tab: LibraryTab): ImageVector = when (tab) {
    LibraryTab.ALL -> PathfinIcons.Grid
    LibraryTab.MEDIA -> PathfinIcons.Video
    LibraryTab.RESUME -> PathfinIcons.Play
    LibraryTab.UPCOMING -> PathfinIcons.Calendar
    LibraryTab.COLLECTIONS -> PathfinIcons.Folder
    LibraryTab.GENRES -> PathfinIcons.Shapes
    LibraryTab.FAVORITES -> PathfinIcons.Star
}

@Composable
private fun CategorySheet(query: LibraryQuery, tabs: List<LibraryTab>, onDismiss: () -> Unit, onChoose: (LibraryQuery) -> Unit) {
    AppModalBottomSheet(
        onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = PathfinAppearance.card,
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        SheetDragHandle(Modifier.testTag("browser-category-sheet"))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = windowHeight * .7f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tabs, key = { it.name }) { tab ->
                val selected = tab == query.tab
                CategoryOption(
                    title = tab.label,
                    icon = categoryIcon(tab),
                    selected = selected,
                    modifier = Modifier.testTag("browser-tab/${tab.name}"),
                ) { onChoose(query.copy(tab = tab)) }
            }
        }
    }
}

@Composable
private fun CategoryOption(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onChoose: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onChoose)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) PathfinAppearance.accent else PathfinAppearance.textSub,
        )
        Spacer(Modifier.width(14.dp))
        // 与设置弹窗同一规则：选中只用墨色加粗与点缀色图标、对勾，不铺底色。
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = PathfinAppearance.textMain,
        )
        if (selected) {
            Icon(PathfinIcons.Check, null, Modifier.size(20.dp), tint = PathfinAppearance.accent)
        }
    }
}

@Composable
private fun SortSheet(query: LibraryQuery, onDismiss: () -> Unit, onChoose: (LibraryQuery) -> Unit) {
    val current = if (query.sort == LibrarySort.DATE_ADDED) LibrarySort.DATE_CREATED else query.sort
    val choices = (listOf(current, LibrarySort.DATE_CREATED, LibrarySort.PREMIERE_DATE, LibrarySort.TITLE, LibrarySort.COMMUNITY_RATING) +
        LibrarySort.entries.filterNot { it == LibrarySort.DATE_ADDED }).distinct()
    AppModalBottomSheet(
        onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = PathfinAppearance.card,
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        SheetDragHandle(Modifier.testTag("browser-sort-sheet"))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = windowHeight * .68f).testTag("browser-sort-options"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(choices, key = { it.name }) { sort ->
                val selected = sort == current
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .heightIn(min = 52.dp)
                        .selectable(selected, role = Role.RadioButton) {
                            onChoose(if (selected) query else query.copy(sort = sort, descending = sort != LibrarySort.TITLE))
                        }
                        .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("browser-sort-option/${sort.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        sortIcon(sort),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) PathfinAppearance.accent else PathfinAppearance.textSub,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        sortLabel(sort),
                        Modifier.weight(1f),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = PathfinAppearance.textMain,
                    )
                    if (selected) {
                        Row(Modifier.heightIn(min = 44.dp).clip(CircleShape).clickable(role = Role.Button) {
                            onChoose(query.copy(descending = !query.descending))
                        }.padding(horizontal = 10.dp).testTag("browser-sort-direction"), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (query.descending) tr("降序排序") else tr("升序排序"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.accent)
                            Icon(sortDirectionIcon(query.descending), null, Modifier.padding(start = 4.dp).size(16.dp), tint = PathfinAppearance.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(state: BrowserUiState, onDismiss: () -> Unit, onRetry: () -> Unit, onApply: (LibraryQuery) -> Unit) {
    var draft by remember { mutableStateOf(state.query) }
    val options = state.filterOptions
    AppModalBottomSheet(
        onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = PathfinAppearance.card,
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        Column(Modifier.fillMaxWidth().height(windowHeight * .84f)) {
            SheetDragHandle(Modifier.testTag("browser-filter-sheet"))
            RefreshablePage(state.filtersLoading, onRetry, Modifier.fillMaxWidth().weight(1f), indicatorTopInset = 0.dp) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("browser-filter-options"),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    state.filtersError?.let { error -> item(key = "filters-error") { ErrorPanel(error, modifier = Modifier.padding(horizontal = 16.dp)) } }
                    item("played") { FilterChoices(tr("已观看"), listOf(null to tr("全部"), true to tr("已观看"), false to tr("未观看")), draft.played) { draft = draft.copy(played = it) } }
                    if (state.query.tab != LibraryTab.FAVORITES) item("favorite") {
                        FilterChoices(tr("收藏"), listOf(null to tr("全部"), true to tr("已收藏"), false to tr("未收藏")), draft.favorite) { draft = draft.copy(favorite = it) }
                    }
                    if (state.query.itemTypes.isEmpty() || state.query.itemTypes.any { it in setOf("Series", "Season", "Episode") }) item("status") {
                        FilterChoices(tr("剧集状态"), listOf("" to tr("全部"), "Continuing" to tr("播出中"), "Ended" to tr("已完结")), draft.seriesStatus.singleOrNull().orEmpty()) {
                            draft = draft.copy(seriesStatus = if (it.isBlank()) emptySet() else setOf(it))
                        }
                    }
                    item("genres") { FilterValues(tr("类型"), (options?.genres.orEmpty() + draft.genres).distinct().sorted(), draft.genres,
                        { draft = draft.copy(genres = emptySet()) }) { draft = draft.copy(genres = draft.genres.toggle(it)) } }
                    item("tags") { FilterValues(tr("标签"), (options?.tags.orEmpty() + draft.tags).distinct().sorted(), draft.tags,
                        { draft = draft.copy(tags = emptySet()) }) { draft = draft.copy(tags = draft.tags.toggle(it)) } }
                    item("years") { FilterValues(tr("发行年份"), (options?.years.orEmpty() + draft.years).distinct().sortedDescending().map(Int::toString), draft.years.map(Int::toString).toSet(),
                        { draft = draft.copy(years = emptySet()) }) { value -> value.toIntOrNull()?.let { draft = draft.copy(years = draft.years.toggle(it)) } } }
                    item("ratings") { FilterValues(tr("官方分级"), (options?.officialRatings.orEmpty() + draft.officialRatings).distinct().sorted(), draft.officialRatings,
                        { draft = draft.copy(officialRatings = emptySet()) }) { draft = draft.copy(officialRatings = draft.officialRatings.toggle(it)) } }
                }
            }
            SettingsDivider(Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton({ draft = draft.clearUiFilters() }, Modifier.weight(1f).testTag("browser-filter-reset"), muted = true) {
                    ButtonLabel(tr("重置"))
                }
                InkButton({ onApply(draft) }, Modifier.weight(1f).testTag("browser-filter-apply")) { ButtonLabel(tr("确定")) }
            }
        }
    }
}

@Composable
private fun <T> FilterChoices(label: String, choices: List<Pair<T, String>>, selected: T, onChoose: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textSub)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(choices, key = { it.second }) { (value, title) ->
                val isSelected = selected == value
                PathfinPill(title, isSelected, { onChoose(value) }, Modifier.testTag("browser-filter-choice/$label/$title"))
            }
        }
    }
}

@Composable
private fun FilterValues(label: String, values: List<String>, selected: Set<String>, onClear: () -> Unit, onToggle: (String) -> Unit) {
    Column(Modifier.testTag("browser-filter-group/$label"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textSub)
        LazyRow(Modifier.testTag("browser-filter-values/$label"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                val allSelected = selected.isEmpty()
                PathfinPill(tr("全部"), allSelected, onClear, Modifier.testTag("browser-filter-all/$label"))
            }
            items(values, key = { "value/$it" }) { value ->
                val isSelected = value in selected
                PathfinPill(value, isSelected, { onToggle(value) }, Modifier.testTag("browser-filter-value/$label/$value"))
            }
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
private fun LibraryQuery.hasUiFilters(): Boolean = played != null || favorite != null || seriesStatus.isNotEmpty() || genres.isNotEmpty() || tags.isNotEmpty() || years.isNotEmpty() || officialRatings.isNotEmpty()
private fun LibraryQuery.clearUiFilters(): LibraryQuery = copy(played = null, favorite = null, seriesStatus = emptySet(), genres = emptySet(), tags = emptySet(), years = emptySet(), officialRatings = emptySet())

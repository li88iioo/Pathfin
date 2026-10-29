@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.domain.favoriteDiscoveryCategories
import dev.pathfin.android.domain.searchDiscoveryCategories
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.delay

internal const val DISCOVERY_PREVIEW_LIMIT = 6

@Composable
internal fun SearchScreen(state: AppState, session: Session, vm: AppViewModel) = SearchContent(
    state.search, session, vm.api, { if (vm.state.value.route == Route.Search) vm.search(it) }, vm::toggleSearchCategory,
    vm::refreshSearch, { vm.openDiscovery(it, false) }, vm::open,
)

@Composable
internal fun FavoritesScreen(state: AppState, session: Session, vm: AppViewModel) = FavoritesContent(
    state.favorites, session, vm.api, vm::refreshFavorites, { vm.openDiscovery(it, true) }, vm::open,
)

/** 输入与类型栏固定占位；请求只更新下方内容，不改变输入框与筛选项的位置。 */
@Composable
internal fun SearchContent(
    state: SearchState,
    session: Session,
    api: JellyfinApi,
    onSearch: (String) -> Unit,
    onToggleCategory: (DiscoveryCategory?) -> Unit,
    onRefresh: () -> Unit,
    onShowAll: (DiscoveryCategory) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    var query by rememberSaveable(session.server.baseUrl, session.userId) { mutableStateOf(state.query) }
    val committedQuery by rememberUpdatedState(state.query)
    val search by rememberUpdatedState(onSearch)
    val focus = LocalFocusManager.current
    val pageActive = LocalPageActive.current
    LaunchedEffect(query, pageActive) {
        if (pageActive && query.trim() != committedQuery) {
            delay(350)
            if (pageActive && query.trim() != committedQuery) search(query)
        }
    }
    fun submit(value: String = query) {
        query = value
        focus.clearFocus()
        onSearch(value)
    }
    val topInset = LocalPageTopInset.current
    // 同次测量先确定输入框与类型栏的真实高度，大字号也不需要首帧猜测或回调修正占位。
    SubcomposeLayout(Modifier.fillMaxSize().background(PathfinAppearance.canvas).imePadding()) { constraints ->
        val header = subcompose("search-header") {
            Column(Modifier.fillMaxWidth().padding(top = topInset)) {
                PathfinSearchField(query, { query = it }, { submit() }, { submit("") }, tr("搜索媒体或人物"),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("search-query"))
                LazyRow(
                    Modifier.fillMaxWidth().testTag("search-filters"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    item(key = "all") {
                        PathfinPill(tr("全部"), state.categories.isEmpty(), modifier = Modifier.testTag("search-filter/all"), toggle = true, onClick = {
                            if (query.trim() != state.query) onSearch(query)
                            onToggleCategory(null)
                        })
                    }
                    items(searchDiscoveryCategories, key = { it.name }) { category ->
                        PathfinPill(category.label, category in state.categories, modifier = Modifier.testTag("search-filter/${category.name}"), toggle = true, onClick = {
                            if (query.trim() != state.query) onSearch(query)
                            onToggleCategory(category)
                        })
                    }
                }
            }
        }.single().measure(constraints.copy(minHeight = 0))
        val headerHeight = header.height.toDp()
        val body = subcompose("search-body") {
            Box(Modifier.fillMaxSize().topBarBlurSource().background(PathfinAppearance.canvas)) {
                if (state.query.isBlank()) {
                    SearchSuggestions(state, onRefresh, headerHeight) { focus.clearFocus(); onOpen(it) }
                } else {
                    key(state.query) {
                        DiscoveryResults(
                            state.results, searchDiscoveryCategories.filter { state.categories.isEmpty() || it in state.categories },
                            session, api, "search-results", onRefresh, onShowAll, onOpen,
                            topInset = headerHeight, emptyTitle = tr("没有找到相关内容"),
                        )
                    }
                }
            }
        }.single().measure(constraints)
        val material = subcompose("search-header-material") {
            TopBarBlurSurface(
                Modifier.fillMaxWidth().height(headerHeight).pointerInput(Unit) { detectTapGestures { } },
                height = headerHeight, secondaryRow = true,
            ) { }
        }.single().measure(constraints.copy(minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.placeRelative(0, 0)
            material.placeRelative(0, 0)
            header.placeRelative(0, 0)
        }
    }
}

@Composable
private fun SearchSuggestions(state: SearchState, onRefresh: () -> Unit, topInset: Dp, onSelect: (MediaItem) -> Unit) {
    val suggestions = state.suggestions
    val items = suggestions.items.filter { item -> state.categories.isEmpty() || state.categories.any { it.itemType == item.type } }
    RefreshablePage(suggestions.loading, onRefresh, Modifier.fillMaxSize(),
        refreshOnEntry = state.suggestionsLoaded || suggestions.error != null, indicatorTopInset = topInset) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("search-suggestions"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 20.dp, bottom = LocalNavigationBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (items.isNotEmpty()) item(key = "suggestions") { SearchSuggestionChips(tr("推荐搜索"), items, onSelect) }
            suggestions.error?.let { error -> item(key = "suggestions-error") { ErrorPanel(error, retry = onRefresh) } }
        }
    }
}

@Composable
internal fun FavoritesContent(
    state: DiscoveryState,
    session: Session,
    api: JellyfinApi,
    onRefresh: () -> Unit,
    onShowAll: (DiscoveryCategory) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    DiscoveryResults(state, favoriteDiscoveryCategories, session, api, "favorites-results", onRefresh, onShowAll, onOpen,
        topInset = LocalPageTopInset.current, emptyTitle = tr("还没有收藏"))
}

@Composable
private fun DiscoveryResults(
    state: DiscoveryState,
    categories: List<DiscoveryCategory>,
    session: Session,
    api: JellyfinApi,
    tag: String,
    onRefresh: () -> Unit,
    onShowAll: (DiscoveryCategory) -> Unit,
    onOpen: (MediaItem) -> Unit,
    topInset: Dp,
    emptyTitle: String,
) {
    val visible = (state.order + categories).distinct().filter { it in categories }
        .mapNotNull { category -> state.sections[category]?.takeIf { it.items.isNotEmpty() }?.let { category to it } }
    val errors = categories.mapNotNull { category -> state.sections[category]?.error?.let { tr("%1\$s：%2\$s", category.label, it) } }
    val loading = categories.any { state.sections[it]?.loading == true }
    RefreshablePage(loading, onRefresh, Modifier.fillMaxSize().background(PathfinAppearance.canvas),
        refreshOnEntry = state.requested, indicatorTopInset = topInset) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(tag),
            contentPadding = PaddingValues(top = topInset + 12.dp, bottom = LocalNavigationBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(visible, key = { it.first.name }, contentType = { "discovery-section" }) { (category, content) ->
                DiscoverySection(category, content, session, api, { onShowAll(category) }, onOpen)
            }
            if (errors.isNotEmpty()) item(key = "discovery-error") {
                ErrorPanel(errors.joinToString("\n"), modifier = Modifier.padding(horizontal = 16.dp), retry = onRefresh)
            }
            if (state.requested && !loading && visible.isEmpty() && errors.isEmpty()) item(key = "discovery-empty") {
                EmptyPanel(emptyTitle, "", if (tag == "favorites-results") PathfinIcons.Star else PathfinIcons.Search)
            }
        }
    }
}

@Composable
private fun DiscoverySection(
    category: DiscoveryCategory,
    content: ContentState,
    session: Session,
    api: JellyfinApi,
    onMore: () -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val row = rememberLazyListState()
    val preview = content.items.take(DISCOVERY_PREVIEW_LIMIT)
    val cardWidth = when {
        category.isLandscape -> 196.dp
        category == DiscoveryCategory.PEOPLE || category == DiscoveryCategory.ARTISTS -> 104.dp
        category == DiscoveryCategory.ALBUMS || category == DiscoveryCategory.MUSIC -> 132.dp
        else -> 112.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp).testTag("discovery-section/${category.name}")
            .semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
            Text(category.label, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
            if (content.total > 0) Text(content.total.toString(), Modifier.padding(start = 8.dp),
                fontSize = 11.sp, lineHeight = 16.sp, color = PathfinAppearance.textMuted)
            Spacer(Modifier.weight(1f))
            if (content.total > DISCOVERY_PREVIEW_LIMIT || content.items.size > DISCOVERY_PREVIEW_LIMIT) {
                Row(Modifier.heightIn(min = 44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore)
                    .padding(start = 12.dp, end = 2.dp).testTag("discovery-more/${category.name}"),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("更多"), fontSize = 12.sp, lineHeight = 18.sp, color = PathfinAppearance.textSub)
                    Icon(PathfinIcons.Chevron, null, Modifier.size(17.dp), tint = PathfinAppearance.textMuted)
                }
            }
        }
        LazyRow(
            state = row, modifier = Modifier.fillMaxWidth().testTag("discovery-row/${category.name}"),
            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(preview, key = MediaItem::id) { item ->
                DiscoveryMediaCard(category, item, session, api, Modifier.width(cardWidth), cardWidth, onOpen)
            }
        }
    }
}

/** 完整分类只保留纵向内容；首读、刷新与分页都沿用同一组卡片。 */
@Composable
internal fun DiscoveryGridContent(
    category: DiscoveryCategory,
    content: ContentState,
    session: Session,
    api: JellyfinApi,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val grid = rememberLazyGridState()
    AutomaticGridPagination(grid, content.pagination, content.queryKey ?: category.name, onMore)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val style = if (category.isLandscape) PosterStyle.BACKDROP else PosterStyle.POSTER
        val columns = BrowserColumns.resolve(style, maxWidth.value, LocalDensity.current.fontScale)
        RefreshablePage(content.loading || content.loadingMore, onRefresh,
            Modifier.fillMaxSize().background(PathfinAppearance.canvas), refreshOnEntry = content.queryKey != null) {
            LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                modifier = Modifier.fillMaxSize().testTag("discovery-grid/${category.name}"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = LocalPageTopInset.current + 12.dp,
                    bottom = LocalNavigationBottomInset.current),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                gridItems(content.items, key = MediaItem::id) { item ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        DiscoveryMediaCard(category, item, session, api, Modifier.fillMaxWidth(), maxWidth, onOpen)
                    }
                }
                content.error?.let { error -> item(key = "discovery-grid-error", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorPanel(error, retry = onRefresh)
                } }
                if (content.items.isEmpty() && !content.loading && content.error == null && content.queryKey != null) {
                    item(key = "discovery-grid-empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyPanel(tr("暂无%1\$s", category.label), "", PathfinIcons.Search)
                    }
                }
            }
        }
    }
}

private val DiscoveryCategory.isLandscape: Boolean
    get() = this == DiscoveryCategory.EPISODES || this == DiscoveryCategory.PROGRAMS

@Composable
private fun DiscoveryMediaCard(
    category: DiscoveryCategory,
    item: MediaItem,
    session: Session,
    api: JellyfinApi,
    modifier: Modifier,
    imageWidth: Dp,
    onOpen: (MediaItem) -> Unit,
) {
    val card = modifier.testTag("discovery-item/${category.name}/${item.id}")
    when (category) {
        DiscoveryCategory.PEOPLE, DiscoveryCategory.ARTISTS -> DiscoveryArtworkCard(item, session, api,
            card, imageWidth, portrait = true, onOpen = { onOpen(item) })
        DiscoveryCategory.ALBUMS, DiscoveryCategory.MUSIC -> DiscoveryArtworkCard(item, session, api,
            card, imageWidth, onOpen = { onOpen(item) })
        else -> BrowserMediaCard(item, session, api, if (category.isLandscape) PosterStyle.BACKDROP else PosterStyle.POSTER,
            card, compact = true, imageWidth = imageWidth, onOpen = { onOpen(item) })
    }
}

@Composable
private fun DiscoveryArtworkCard(
    item: MediaItem,
    session: Session,
    api: JellyfinApi,
    modifier: Modifier,
    imageWidth: Dp,
    portrait: Boolean = false,
    onOpen: () -> Unit,
) {
    val shape = if (portrait) CircleShape else RoundedCornerShape(12.dp)
    val portraitWidth = (imageWidth - 8.dp).coerceAtMost(144.dp)
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen),
        horizontalAlignment = if (portrait) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PosterImage(item, session, api,
            if (portrait) Modifier.size(portraitWidth).border(PathfinAppearance.cardBorder, shape)
            else Modifier.fillMaxWidth().aspectRatio(1f).border(PathfinAppearance.cardBorder, shape),
            shape = shape, displayWidth = if (portrait) portraitWidth else imageWidth, displayAspectRatio = 1f)
        Text(item.name, Modifier.fillMaxWidth().height(with(LocalDensity.current) { (scaledLineHeightPx(14.sp, 20.sp) + 2).toDp() }),
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            color = PathfinAppearance.textMain, textAlign = if (portrait) TextAlign.Center else TextAlign.Start,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!portrait) Text(discoveryArtworkSubtitle(item).ifBlank { " " },
            Modifier.fillMaxWidth().height(with(LocalDensity.current) { (scaledLineHeightPx(11.sp, 14.sp) + 2).toDp() }),
            fontSize = 11.sp, lineHeight = 14.sp, color = PathfinAppearance.textSub,
            style = MaterialTheme.typography.bodySmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun discoveryArtworkSubtitle(item: MediaItem): String {
    if (item.type != "Audio") return item.year?.toString().orEmpty()
    if (item.runTimeTicks <= 0) return ""
    val seconds = TimeUnits.ticksToMs(item.runTimeTicks) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** 搜索建议词条：小标题加一组圆角胶囊，全局搜索与库内搜索共用；点按直接进入该条目。 */
@Composable
internal fun SearchSuggestionChips(title: String, items: List<MediaItem>, onSelect: (MediaItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Eyebrow(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.distinctBy { it.name }.forEach { item ->
                key(item.id) {
                    Box(Modifier.widthIn(max = 280.dp).heightIn(min = 44.dp).clip(CircleShape)
                        .background(PathfinAppearance.softFill).clickable(role = Role.Button) { onSelect(item) }
                        .padding(horizontal = 14.dp, vertical = 11.dp).testTag("search-suggestion/${item.id}"),
                        contentAlignment = Alignment.Center) {
                        Text(item.name, fontSize = 13.5.sp, lineHeight = 20.sp,
                            color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

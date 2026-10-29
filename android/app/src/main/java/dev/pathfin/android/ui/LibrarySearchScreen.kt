package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.delay

/** 输入停顿多久后再取片名建议，避免每个字都请求服务器。 */
private const val SuggestionDebounceMs = 250L

/**
 * 库内搜索：顶栏即搜索框。输入或聚焦时显示词条建议，点建议直接进详情；按键盘搜索键才进入完整结果网格。
 */
@Composable
internal fun LibrarySearchScreen(
    library: MediaItem,
    state: BrowserUiState,
    session: Session,
    api: JellyfinApi,
    onSubmit: (String) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onBack: () -> Unit,
    suggest: suspend (String) -> List<MediaItem>,
) {
    var query by rememberSaveable(library.id) { mutableStateOf(state.query.searchTerm) }
    var lastSearch by rememberSaveable(library.id) { mutableStateOf(state.query.searchTerm) }
    // 还没提交过搜索时处于输入态并显示建议；提交后显示结果，重新聚焦输入框再回到建议。
    var editing by rememberSaveable(library.id) { mutableStateOf(state.query.searchTerm.isBlank()) }
    // 提交首个搜索词会启用刷新手势，但不是重新进入页面；该次请求已由提交入口发起。
    val refreshOnEntry = remember(library.id) { state.query.searchTerm.isNotBlank() }
    val grid = rememberLazyGridState()
    AutomaticGridPagination(grid, state.content.pagination, state.query, onMore,
        enabled = !editing && state.query.searchTerm.isNotBlank() && lastSearch == state.query.searchTerm,
        minimumAheadItems = 100, screensAhead = 2)
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    fun submit() { focus.clearFocus(); editing = false; onSubmit(query) }
    LaunchedEffect(state.query.searchTerm) {
        if (lastSearch != state.query.searchTerm) {
            grid.scrollToItem(0)
            lastSearch = state.query.searchTerm
        }
    }
    LaunchedEffect(library.id) { if (editing) focusRequester.requestFocus() }
    val suggestions by produceState(emptyList<MediaItem>(), library.id, query.trim(), editing) {
        if (!editing) return@produceState
        if (query.isNotBlank()) delay(SuggestionDebounceMs)
        value = suggest(query)
    }
    val topBarHeight = 64.dp
    val topInset = LocalPageTopInset.current
    val headerHeight = topInset + topBarHeight
    Box(Modifier.fillMaxSize().imePadding()) {
        // 保留网格与刷新生命周期，建议遮盖结果时移除旧结果采样。
        val resultsSource = if (editing) Modifier else Modifier.topBarBlurSource()
        BoxWithConstraints(Modifier.fillMaxSize().then(resultsSource).background(PathfinAppearance.canvas)) {
            val columns = BrowserColumns.resolve(state.style, maxWidth.value, LocalDensity.current.fontScale)
            RefreshablePage(state.content.loading || state.content.loadingMore, onRefresh, Modifier.fillMaxSize(),
                enabled = state.query.searchTerm.isNotBlank(), refreshOnEntry = refreshOnEntry,
                indicatorTopInset = headerHeight) {
                LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize().testTag("library-search-results"),
                    contentPadding = PaddingValues(start = BrowserColumns.horizontalPaddingDp.dp, end = BrowserColumns.horizontalPaddingDp.dp,
                        top = headerHeight + 8.dp, bottom = LocalNavigationBottomInset.current),
                    verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(BrowserColumns.spacingDp.dp)) {
                    state.content.error?.takeUnless { state.content.retryAppend }?.let { error -> item(key = "search-error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(error) } }
                    items(state.content.items, key = { it.id }) { item ->
                        BrowserMediaCard(item, session, api, state.style, Modifier.fillMaxWidth(), libraryStyle = true) { onOpen(item) }
                    }
                    if (state.query.searchTerm.isNotBlank() && !state.content.loading && state.content.items.isEmpty() && state.content.error == null)
                        item(key = "search-empty", span = { GridItemSpan(maxLineSpan) }) { EmptyPanel(tr("没有找到内容"), tr("试试其他片名。")) }
                    state.content.error?.takeIf { state.content.retryAppend }?.let { error ->
                        item(key = "search-append-error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(error) }
                    }
                }
            }
        }
        // 与全局搜索同一套词条胶囊：空词为库内推荐，有词为片名匹配。
        if (editing) Column(
            Modifier.fillMaxSize().topBarBlurSource().background(PathfinAppearance.canvas)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = headerHeight + 20.dp, bottom = 24.dp)
                .testTag("library-search-suggestions"),
        ) {
            if (suggestions.isNotEmpty()) SearchSuggestionChips(if (query.isBlank()) tr("推荐搜索") else tr("相关条目"), suggestions) {
                focus.clearFocus(); onOpen(it)
            }
        }
        TopBarBlurSurface(Modifier.fillMaxWidth().height(headerHeight).pointerInput(Unit) { detectTapGestures { } }, height = headerHeight) {
            Row(
                Modifier.fillMaxWidth().padding(top = topInset).height(topBarHeight).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PathfinSearchField(query, {
                    query = it
                    editing = true
                    if (it.isBlank()) onSubmit("")
                }, { if (query.isNotBlank()) submit() }, { query = ""; editing = true; onSubmit("") }, tr("在%1\$s中搜索", library.name),
                    Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { if (it.isFocused) editing = true }
                        .testTag("library-search-input"))
                QuietButton(tr("取消"), onBack, Modifier.testTag("library-search-cancel"), color = PathfinAppearance.textMain)
            }
        }
    }
}

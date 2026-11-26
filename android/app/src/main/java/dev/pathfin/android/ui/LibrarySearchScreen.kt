package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
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
    val committedQuery = state.query.searchTerm
    val input = key(library.id) { rememberTextFieldState(committedQuery) }
    val query = input.text.toString()
    val term = query.trim()
    var lastSearch by rememberSaveable(library.id) { mutableStateOf(committedQuery) }
    // 还没提交过搜索时处于输入态并显示建议；提交后显示结果，重新聚焦输入框再回到建议。
    var showingSuggestions by rememberSaveable(library.id) { mutableStateOf(committedQuery.isBlank()) }
    val editing = showingSuggestions || term != committedQuery
    val pageActive = LocalPageActive.current
    // 提交首个搜索词会启用刷新手势，但不是重新进入页面；该次请求已由提交入口发起。
    val refreshOnEntry = remember(library.id) { committedQuery.isNotBlank() }
    val grid = rememberLazyGridState()
    AutomaticGridPagination(grid, state.content.pagination, state.query, onMore,
        enabled = !editing && committedQuery.isNotBlank() && lastSearch == committedQuery,
        minimumAheadItems = 100, screensAhead = 2)
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    fun submit() {
        val value = input.text.toString()
        if (value.isBlank()) return
        focus.clearFocus()
        showingSuggestions = false
        onSubmit(value)
    }
    LaunchedEffect(committedQuery) {
        if (lastSearch != committedQuery) {
            grid.scrollToItem(0)
            lastSearch = committedQuery
        }
    }
    // 键盘删除和清空按钮都走这里。只有当前输入仍为空才取消正式查询。
    LaunchedEffect(input, term.isEmpty(), committedQuery, pageActive) {
        if (pageActive && input.text.isBlank()) {
            showingSuggestions = true
            if (committedQuery.isNotEmpty()) onSubmit("")
        }
    }
    LaunchedEffect(library.id) { if (editing) focusRequester.requestFocus() }
    val suggestions by produceState(emptyList<MediaItem>(), input, term, editing, pageActive) {
        if (!editing || !pageActive) return@produceState
        if (term.isNotEmpty()) delay(SuggestionDebounceMs)
        value = suggest(term)
    }
    val topBarHeight = 64.dp
    val topInset = LocalPageTopInset.current
    val headerHeight = topInset + topBarHeight
    Box(Modifier.fillMaxSize().imePadding()) {
        // 保留网格与刷新生命周期，建议遮盖结果时移除旧结果采样。
        val resultsSource = if (editing) Modifier else Modifier.topBarBlurSource()
        RefreshFailureNotice(state.content.error?.takeIf { !state.content.retryAppend && state.content.items.isNotEmpty() },
            settled = !state.content.loading, retry = onRefresh)
        RefreshablePage(state.content.loading || state.content.loadingMore, onRefresh,
            Modifier.fillMaxSize().then(resultsSource).background(PathfinAppearance.canvas),
            enabled = state.query.searchTerm.isNotBlank(), refreshOnEntry = refreshOnEntry, indicatorTopInset = headerHeight) {
            BoxWithConstraints(Modifier.fillMaxSize().pageHorizontalInsets()) {
                val density = LocalDensity.current
                val columns = BrowserColumns.resolve(state.style, maxWidth.value, density.fontScale)
                val gridCells = GridCells.Fixed(columns)
                val imageWidths = BrowserColumns.imageWidths(gridCells, constraints.maxWidth, density)
                LazyVerticalGrid(gridCells, state = grid, modifier = Modifier.fillMaxSize().testTag("library-search-results"),
                    contentPadding = PaddingValues(start = BrowserColumns.horizontalPaddingDp.dp, end = BrowserColumns.horizontalPaddingDp.dp,
                        top = headerHeight + 8.dp, bottom = LocalNavigationBottomInset.current),
                    verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(BrowserColumns.spacingDp.dp)) {
                    state.content.error?.takeIf { !state.content.retryAppend && state.content.items.isEmpty() }?.let { error ->
                        item(key = "search-error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(error, retry = onRefresh) }
                    }
                    itemsIndexed(state.content.items, key = { _, item -> item.id }) { index, item ->
                        BrowserMediaCard(item, session, api, state.style, Modifier.fillMaxWidth(),
                            imageWidth = imageWidths[index % columns].takeIf { it > 0.dp }, libraryStyle = true) { onOpen(item) }
                    }
                    if (state.query.searchTerm.isNotBlank() && !state.content.loading && state.content.items.isEmpty() && state.content.error == null)
                        item(key = "search-empty", span = { GridItemSpan(maxLineSpan) }) { EmptyPanel(tr("没有找到内容"), tr("试试其他片名。")) }
                    state.content.error?.takeIf { state.content.retryAppend }?.let { error ->
                        item(key = "search-append-error", span = { GridItemSpan(maxLineSpan) }) { InlineError(error) }
                    }
                }
            }
        }
        // 与全局搜索同一套词条胶囊：空词为库内推荐，有词为片名匹配。
        if (editing) Column(
            Modifier.fillMaxSize().topBarBlurSource().background(PathfinAppearance.canvas)
                .pageHorizontalInsets().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = headerHeight + 20.dp, bottom = 24.dp)
                .testTag("library-search-suggestions"),
        ) {
            if (suggestions.isNotEmpty()) SearchSuggestionChips(if (term.isEmpty()) tr("推荐搜索") else tr("相关条目"), suggestions) {
                focus.clearFocus(); onOpen(it)
            }
        }
        TopBarBlurSurface(Modifier.fillMaxWidth().height(headerHeight).testTag("library-search-header")
            .pointerInput(Unit) { detectTapGestures { } }, height = headerHeight) {
            Row(
                Modifier.fillMaxWidth().pageHorizontalInsets().padding(top = topInset).height(topBarHeight)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PathfinSearchField(input, ::submit, input::clearText, tr("在%1\$s中搜索", library.name),
                    Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { if (it.isFocused) showingSuggestions = true }
                        .testTag("library-search-input"))
                QuietButton(tr("取消"), onBack, Modifier.testTag("library-search-cancel"), color = PathfinAppearance.textMain)
            }
        }
    }
}

package dev.pathfin.android.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect

/** 分页观察只依赖标量，媒体、节目和日志共用同一个触底判定。 */
internal data class PaginationStatus(
    val itemCount: Int,
    val nextIndex: Int,
    val total: Int,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val failed: Boolean = false,
    val hasMore: Boolean = nextIndex < total,
)

internal val ContentState.pagination: PaginationStatus
    get() = PaginationStatus(items.size, nextIndex, total, loading, loadingMore, error != null)

/** 默认提前一屏；媒体库可设置最低余量和屏数，独立扩大滚动缓冲。 */
@Composable
internal fun AutomaticGridPagination(
    grid: LazyGridState,
    status: PaginationStatus,
    queryKey: Any?,
    onMore: () -> Unit,
    enabled: Boolean = true,
    minimumAheadItems: Int = 0,
    screensAhead: Int = 1,
) = AutomaticPagination(grid, status, queryKey, onMore, enabled) {
    val layout = grid.layoutInfo
    // 数据追加已发布但网格尚未重排时，不能拿旧总数再次触发；额外的标签/错误行仍计入布局。
    gridNeedsNextPage(maxOf(status.itemCount, layout.totalItemsCount),
        layout.visibleItemsInfo.firstOrNull()?.index ?: -1,
        layout.visibleItemsInfo.lastOrNull()?.index ?: -1, minimumAheadItems, screensAhead)
}

internal fun gridNeedsNextPage(totalItems: Int, firstVisible: Int, lastVisible: Int,
    minimumAheadItems: Int = 0, screensAhead: Int = 1): Boolean {
    if (totalItems <= 0 || firstVisible < 0 || lastVisible < firstVisible || lastVisible >= totalItems) return false
    val visibleItems = lastVisible - firstVisible + 1
    // 提前量不超过已有数据；大网格乘法使用 Long，避免计数溢出变为负值。
    val aheadItems = maxOf(minimumAheadItems.toLong(), visibleItems.toLong() * screensAhead)
        .coerceIn(0L, totalItems.toLong())
    return totalItems - lastVisible - 1 <= aheadItems
}

/** endIndex 可指向详情页中季集区的末项，后面的演员等区块不阻挡分页。 */
@Composable
internal fun AutomaticListPagination(
    list: LazyListState,
    status: PaginationStatus,
    queryKey: Any?,
    onMore: () -> Unit,
    endIndex: Int = status.itemCount - 1,
    enabled: Boolean = true,
) = AutomaticPagination(list, status, queryKey, onMore, enabled) {
    val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    lastVisible >= 0 && lastVisible >= endIndex - 2
}

private data class PageWindow(
    val status: PaginationStatus,
    val enabled: Boolean,
    val nearEnd: Boolean,
)

@Composable
private fun AutomaticPagination(
    scrollState: Any,
    status: PaginationStatus,
    queryKey: Any?,
    onMore: () -> Unit,
    enabled: Boolean,
    nearEnd: () -> Boolean,
) {
    val page by rememberUpdatedState(status)
    val loadMore by rememberUpdatedState(onMore)
    val canLoad by rememberUpdatedState(enabled && LocalPageRefreshEnabled.current)
    val atEnd by rememberUpdatedState(nearEnd)
    LaunchedEffect(scrollState, queryKey) {
        var requestedCursor: Int? = null
        snapshotFlow {
            PageWindow(page, canLoad, atEnd())
        }.collect { current ->
            val status = current.status
            // 首读/刷新成功后可以重新走相同游标；追加中的状态变化不会重发同一页。
            if (status.refreshing || status.failed) requestedCursor = null
            if (!current.enabled || status.refreshing || status.appending || status.failed ||
                status.itemCount == 0 || !status.hasMore || status.nextIndex >= status.total || !current.nearEnd) return@collect
            if (requestedCursor != status.nextIndex) {
                requestedCursor = status.nextIndex
                loadMore()
            }
        }
    }
}

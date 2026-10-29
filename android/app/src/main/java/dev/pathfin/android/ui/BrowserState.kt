package dev.pathfin.android.ui

import dev.pathfin.android.domain.LibraryFilterOptions
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.i18n.tr

enum class PosterStyle(private val labelKey: String, val aspectRatio: Float) {
    POSTER("海报", 2f / 3f),
    BACKDROP("背景图", 16f / 9f);

    val label: String get() = tr(labelKey)
}

data class BrowserUiState(
    val content: ContentState = ContentState(),
    /** 当前请求；改选可以替换在途请求，不要求先清掉已经呈现的结果。 */
    val query: LibraryQuery = LibraryQuery(),
    /** 结果所属的查询。首读尚无结果时为 null；标题、数量与网格必须使用同一份已发布结果。 */
    val contentQuery: LibraryQuery? = null,
    val filterOptions: LibraryFilterOptions? = null,
    val filtersLoading: Boolean = false,
    val style: PosterStyle = PosterStyle.POSTER,
    val filtersError: String? = null,
    val restoringCache: Boolean = false,
) {
    val displayedQuery: LibraryQuery get() = contentQuery ?: query

    /** 内存、磁盘和网络结果都以同一边界发布；筛选选项也只属于已经展示的媒体类型。 */
    internal fun withContent(query: LibraryQuery, content: ContentState): BrowserUiState {
        val typesChanged = displayedQuery.itemTypes != query.itemTypes
        return copy(content = content, contentQuery = query,
            filterOptions = if (typesChanged) null else filterOptions,
            filtersError = if (typesChanged) null else filtersError,
            filtersLoading = if (typesChanged) false else filtersLoading)
    }
}

/** 和网格使用同一组边距，在测量阶段确定列数，避免首帧后再改变卡片尺寸。 */
internal object BrowserColumns {
    const val horizontalPaddingDp = 16f
    const val spacingDp = 12f

    fun resolve(style: PosterStyle, availableWidthDp: Float, fontScale: Float): Int {
        val width = if (availableWidthDp.isFinite()) availableWidthDp.coerceAtLeast(0f) else 360f
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale.coerceAtLeast(1f) else 1f
        val contentWidth = (width - horizontalPaddingDp * 2).coerceAtLeast(0f)
        fun fitting(minimumCardWidth: Float) =
            ((contentWidth + spacingDp) / (minimumCardWidth + spacingDp)).toInt().coerceAtLeast(1)

        val phoneColumns: Int
        val preferredWidth: Float
        val readableWidth: Float
        when (style) {
            PosterStyle.POSTER -> { phoneColumns = 3; preferredWidth = 144f; readableWidth = 88f }
            PosterStyle.BACKDROP -> { phoneColumns = 2; preferredWidth = 208f; readableWidth = 132f }
        }
        val desired = maxOf(phoneColumns, fitting(preferredWidth * scale)).coerceAtMost(8)
        return minOf(desired, fitting(readableWidth * scale))
    }
}

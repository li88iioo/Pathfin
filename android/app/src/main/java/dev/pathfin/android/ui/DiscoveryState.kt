package dev.pathfin.android.ui

import dev.pathfin.android.domain.DiscoveryCategory

/** 每组拥有独立游标与错误；某类慢请求不阻塞已到达的其他类。 */
data class DiscoveryState(
    val sections: Map<DiscoveryCategory, ContentState> = emptyMap(),
    val requested: Boolean = false,
    // 已出现的分组保留位置；迟到的分类追加在末尾，避免异步结果插入首屏引起跳动。
    val order: List<DiscoveryCategory> = emptyList(),
) {
    val loading: Boolean get() = sections.values.any { it.loading }
    val hasItems: Boolean get() = sections.values.any { it.items.isNotEmpty() }
    val error: String? get() = sections.values.firstNotNullOfOrNull { it.error }
}

data class SearchState(
    val query: String = "",
    /** 空集表示全部；非空时取所选媒体类型的并集。 */
    val categories: Set<DiscoveryCategory> = emptySet(),
    val results: DiscoveryState = DiscoveryState(),
    val suggestions: ContentState = ContentState(),
    val suggestionsLoaded: Boolean = false,
)

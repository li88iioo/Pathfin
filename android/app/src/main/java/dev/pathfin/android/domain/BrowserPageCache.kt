package dev.pathfin.android.domain

/** 首屏按实际会话、浏览入口、父目录和完整查询隔离。 */
data class BrowserPageKey(
    val scope: ProfileHomeKey,
    val browserId: String,
    val parentId: String?,
    val query: LibraryQuery,
)

/** 由 ViewModel 主线程拥有；仅接收成功首屏，失效及迟到响应由调用方管理。 */
class BrowserPageCache(
    private val capacity: Int = 16,
    private val ttlMs: Long = 120_000,
    maxItems: Int = 40,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry(val page: MediaPage, val writtenAtMs: Long)

    private val itemLimit = maxItems.coerceAtMost(40)
    private val entries = LinkedHashMap<BrowserPageKey, Entry>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
        require(ttlMs >= 0)
        require(maxItems >= 0)
    }

    operator fun get(key: BrowserPageKey): MediaPage? {
        val entry = entries[key] ?: return null
        if (nowMs() - entry.writtenAtMs >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.page.copy(items = entry.page.items.toList())
    }

    fun put(key: BrowserPageKey, page: MediaPage) {
        if (page.items.size > itemLimit) return
        val now = nowMs()
        entries.entries.removeAll { now - it.value.writtenAtMs >= ttlMs }
        entries[key] = Entry(page.copy(items = page.items.toList()), now)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun remove(key: BrowserPageKey) {
        entries.remove(key)
    }

    /** 不延长缓存寿命；只让依赖变化字段的查询失效，其他首屏按条目更新。 */
    fun update(scope: ProfileHomeKey, change: BrowserUserDataChange) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val (key, value) = entry
            if (key.scope != scope) continue
            if (change.invalidates(key.query)) iterator.remove()
            else entry.setValue(value.copy(page = value.page.copy(items = value.page.items.map(change::apply))))
        }
    }

    /** 写操作需同时清除相同资料和父目录的分类、字母筛选及入口别名。 */
    fun invalidate(scope: ProfileHomeKey, parentId: String?) {
        entries.keys.removeAll { it.scope == scope && it.parentId == parentId }
    }

    fun clear() {
        entries.clear()
    }
}

package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class BrowserPageCacheTest {
    private val scope = ProfileHomeKey("profile-A", "https://example.test/jellyfin", "server", "user")
    private val key = BrowserPageKey(scope, "library/movies", "movies", LibraryQuery(itemTypes = setOf("Movie")))

    private fun page(name: String = "Movie", count: Int = 1, totalCount: Int = count) = MediaPage(
        List(count) { MediaItem("$name-$it", "$name $it", "Movie") }, totalCount,
    )

    @Test fun eachProfileAddressServerUserParentAndBrowserHasItsOwnPage() {
        val keys = listOf(
            key,
            key.copy(scope = scope.copy(profileId = "profile-B")),
            key.copy(scope = scope.copy(baseUrl = "https://other.test/jellyfin")),
            key.copy(scope = scope.copy(serverId = "other-server")),
            key.copy(scope = scope.copy(userId = "other-user")),
            key.copy(parentId = "other-parent"),
            key.copy(parentId = null),
            key.copy(browserId = "other-browser"),
        )
        val cache = BrowserPageCache(nowMs = { 0L })
        keys.forEachIndexed { index, value -> cache.put(value, page("page-$index")) }
        keys.forEachIndexed { index, value -> assertEquals(page("page-$index"), cache[value]) }
    }

    @Test fun everyQueryFieldSeparatesPagesIncludingAlphabetAndSearch() {
        val query = key.query
        val alternatives = listOf(
            query.copy(tab = LibraryTab.RESUME),
            query.copy(sort = LibrarySort.TITLE),
            query.copy(descending = false),
            query.copy(played = true),
            query.copy(played = false),
            query.copy(favorite = true),
            query.copy(favorite = false),
            query.copy(seriesStatus = setOf("Continuing")),
            query.copy(genres = setOf("Drama")),
            query.copy(tags = setOf("Family")),
            query.copy(years = setOf(2026)),
            query.copy(officialRatings = setOf("PG")),
            query.copy(itemTypes = setOf("Series")),
            query.copy(personId = "person"),
            query.copy(studioId = "studio"),
            query.copy(genreId = "genre"),
            query.copy(episodeOrder = true),
            query.copy(searchTerm = "query"),
            query.copy(nameInitial = "A"),
            query.copy(nameInitial = "0"),
            query.copy(nameInitial = "#"),
        )
        val cache = BrowserPageCache(capacity = alternatives.size + 1, nowMs = { 0L })
        cache.put(key, page("base"))
        alternatives.forEachIndexed { index, value -> cache.put(key.copy(query = value), page("query-$index")) }
        assertEquals(page("base"), cache[key])
        alternatives.forEachIndexed { index, value ->
            assertEquals("query field $index", page("query-$index"), cache[key.copy(query = value)])
        }
    }

    @Test fun successfulEmptyPageIsPresentButAnUnloadedQueryIsAbsent() {
        val cache = BrowserPageCache(nowMs = { 0L })
        cache.put(key, MediaPage(emptyList(), 0))
        assertEquals(MediaPage(emptyList(), 0), cache[key])
        assertNull(cache[key.copy(query = key.query.copy(nameInitial = "Z"))])
    }

    @Test fun ttlStartsAtTheWriteAndHitsDoNotRenewIt() {
        var now = 10L
        val cache = BrowserPageCache(ttlMs = 100, nowMs = { now })
        cache.put(key, page(totalCount = 2_000))
        now = 60
        assertEquals(page(totalCount = 2_000), cache[key])
        now = 109
        assertEquals(page(totalCount = 2_000), cache[key])
        now = 110
        assertNull("TTL 到边界即失效，读取不能续期", cache[key])
        now = 111
        assertNull(cache[key])
    }

    @Test fun replacingAPageStartsANewWriteLifetime() {
        var now = 0L
        val cache = BrowserPageCache(ttlMs = 100, nowMs = { now })
        cache.put(key, page("old"))
        now = 90
        cache.put(key, page("new"))
        now = 100
        assertEquals(page("new"), cache[key])
        now = 189
        assertEquals(page("new"), cache[key])
        now = 190
        assertNull(cache[key])
    }

    @Test fun defaultCapacityEvictsTheLeastRecentlyReadOfSixteenPages() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val keys = List(17) { key.copy(query = key.query.copy(searchTerm = "query-$it")) }
        repeat(16) { cache.put(keys[it], page("page-$it")) }
        assertEquals(page("page-0"), cache[keys[0]])
        cache.put(keys[16], page("page-16"))
        assertNull(cache[keys[1]])
        assertEquals(page("page-0"), cache[keys[0]])
        for (index in 2..16) assertEquals(page("page-$index"), cache[keys[index]])
    }

    @Test fun expiredRecentlyReadPagesAreRemovedBeforeEvictingAStillLivePage() {
        var now = 0L
        val cache = BrowserPageCache(capacity = 2, ttlMs = 100, nowMs = { now })
        val second = key.copy(browserId = "second")
        val third = key.copy(browserId = "third")
        cache.put(key, page("old"))
        now = 50
        cache.put(second, page("live"))
        now = 99
        assertEquals(page("old"), cache[key])
        now = 100
        cache.put(third, page("new"))
        assertNull(cache[key])
        assertEquals(page("live"), cache[second])
        assertEquals(page("new"), cache[third])
    }

    @Test fun invalidationRemovesEveryAliasAndQueryOnlyForTheExactScopeAndParent() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val removed = listOf(
            key,
            key.copy(browserId = "library-alias"),
            key.copy(query = key.query.copy(nameInitial = "A", favorite = true)),
        )
        val retained = listOf(
            key.copy(parentId = "other-parent"),
            key.copy(parentId = null),
            key.copy(scope = scope.copy(profileId = "profile-B")),
            key.copy(scope = scope.copy(baseUrl = "https://other.test/jellyfin")),
            key.copy(scope = scope.copy(serverId = "other-server")),
            key.copy(scope = scope.copy(userId = "other-user")),
        )
        (removed + retained).forEach { cache.put(it, page()) }
        cache.invalidate(scope, key.parentId)
        removed.forEach { assertNull(cache[it]) }
        retained.forEach { assertEquals(page(), cache[it]) }
        cache.invalidate(scope, null)
        assertNull(cache[key.copy(parentId = null)])
        assertEquals(page(), cache[key.copy(parentId = "other-parent")])
    }

    @Test fun removingOneQueryKeepsOtherLettersAndAliasesUntilTheParentIsInvalidated() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val a = key.copy(query = key.query.copy(nameInitial = "A"))
        val b = key.copy(query = key.query.copy(nameInitial = "B"))
        val alias = a.copy(browserId = "library-alias")
        listOf(key, a, b, alias).forEach { cache.put(it, page()) }
        cache.remove(a)
        assertNull(cache[a])
        listOf(key, b, alias).forEach { assertEquals(page(), cache[it]) }
        cache.invalidate(scope, key.parentId)
        listOf(key, a, b, alias).forEach { assertNull(cache[it]) }
    }

    @Test fun clearRemovesPagesFromEveryScopeAndAllowsFreshResults() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val other = key.copy(scope = scope.copy(profileId = "profile-B"))
        cache.put(key, page())
        cache.put(other, page())
        cache.clear()
        assertNull(cache[key])
        assertNull(cache[other])
        cache.put(key, page("fresh"))
        assertEquals(page("fresh"), cache[key])
    }

    @Test fun userDataUpdatesOrdinaryPagesWithoutExtendingTtlOrTouchingOtherProfiles() {
        var now = 0L
        val cache = BrowserPageCache(ttlMs = 100, nowMs = { now })
        val movie = MediaItem("movie", "Movie", "Movie")
        val normal = key.copy(query = LibraryQuery(itemTypes = setOf("Movie")))
        val filtered = normal.copy(query = normal.query.copy(favorite = true))
        val other = normal.copy(scope = scope.copy(profileId = "other"))
        listOf(normal, filtered, other).forEach { cache.put(it, MediaPage(listOf(movie), 100)) }
        now = 90
        cache.update(scope, BrowserUserDataChange(movie, MediaUserData(isFavorite = true), BrowserUserDataChange.Kind.FAVORITE))
        assertEquals(true, cache[normal]?.items?.single()?.isFavorite)
        assertEquals(100, cache[normal]?.totalCount)
        assertNull(cache[filtered])
        assertEquals(false, cache[other]?.items?.single()?.isFavorite)
        now = 100
        assertNull("局部写回不能为整份服务器结果续期", cache[normal])
    }

    @Test fun resettingAPartlyWatchedSeriesUnplayedEvictsResumeAndDescendantPagesOnlyForItsProfile() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val resume = key.copy(query = LibraryQuery(tab = LibraryTab.RESUME, itemTypes = setOf("Series")))
        val episodes = key.copy(query = LibraryQuery(itemTypes = setOf("Episode")))
        val other = resume.copy(scope = scope.copy(profileId = "other"))
        val episode = MediaItem("episode", "Episode", "Episode", positionTicks = 900_000_000L)
        val continuing = MediaPage(listOf(episode), 1)
        listOf(resume, episodes, other).forEach { cache.put(it, continuing) }
        cache.put(key, page())
        val series = MediaItem("series", "Series", "Series", played = false, unplayedItemCount = 9)

        cache.update(scope, BrowserUserDataChange(series,
            MediaUserData(played = false, positionTicks = 0, playCount = 0, unplayedItemCount = 10),
            BrowserUserDataChange.Kind.PLAYED))

        assertNull("整剧重置未看后不能继续命中旧续播集", cache[resume])
        assertNull("父项 played 未变也必须重取子集", cache[episodes])
        assertEquals(continuing, cache[other])
        assertEquals(page(), cache[key])
    }

    @Test fun fortyItemsRemainBoundedWithoutLosingTheServerTotalAndLargerPagesAreRejected() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val valid = page(count = 40, totalCount = 2_000)
        cache.put(key, valid)
        assertEquals(valid, cache[key])
        val oversized = key.copy(browserId = "oversized")
        cache.put(oversized, page(count = 41, totalCount = 2_000))
        assertNull(cache[oversized])
        cache.put(key, page("oversized-replacement", count = 41))
        assertEquals("拒绝超限写入不应破坏已有合法首屏", valid, cache[key])
    }

    @Test fun aSmallerItemLimitIsRespectedAndCannotRaiseTheFortyItemCeiling() {
        val smaller = BrowserPageCache(maxItems = 2, nowMs = { 0L })
        smaller.put(key, page(count = 2))
        assertEquals(page(count = 2), smaller[key])
        val oversized = key.copy(browserId = "oversized")
        smaller.put(oversized, page(count = 3))
        assertNull(smaller[oversized])
        val larger = BrowserPageCache(maxItems = 100, nowMs = { 0L })
        larger.put(key, page(count = 41))
        assertNull(larger[key])
    }

    @Test fun inputAndReturnedMutableListsCannotChangeTheCachedPage() {
        val cache = BrowserPageCache(nowMs = { 0L })
        val original = page(count = 3, totalCount = 200)
        val input = original.items.toMutableList()
        cache.put(key, original.copy(items = input))
        input.clear()
        assertEquals(original, cache[key])
        val returned = requireNotNull(cache[key])
        @Suppress("UNCHECKED_CAST")
        (returned.items as MutableList<MediaItem>).clear()
        assertEquals(original, cache[key])
    }
}

package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.BrowserSnapshotStore
import dev.pathfin.android.data.HomeSnapshotStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.BrowserPageKey
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaPage
import dev.pathfin.android.domain.ProfileHomeKey
import dev.pathfin.android.domain.favoriteDiscoveryCategories
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.DiscoveryState
import dev.pathfin.android.ui.Route
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 AppViewModel 缓存回归；只允许 verification，网络和账号均由进程内 fixture 提供。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BrowserCacheTest {
    @Test
    fun structuralMediaChangeRevalidatesVisibleFiltersWithoutDroppingExistingOptions() = withApp { app, fixture ->
        val library = app.state.value.home.libraries.single { it.id == LIBRARY_A }
        onMain { app.open(library) }
        val original = awaitBrowser(app, fixture, LIBRARY_A)
        val previousOptions = checkNotNull(original.filterOptions)
        val before = fixture.counts().filters
        fixture.libraryAGenres.set(listOf("NewMetadataGenre"))
        val filters = fixture.blockNextFilters()
        onMain {
            app.mediaChanged(checkNotNull(app.state.value.session), MOVIE_A)
            val refreshing = app.state.value.browsers.getValue(LIBRARY_A)
            assertEquals("重验可选项时应保持现有抽屉尺寸和旧选项", previousOptions, refreshing.filterOptions)
            assertTrue("媒体结构变更必须重验筛选，不能只刷新 Items", refreshing.filtersLoading)
        }
        filters.awaitRequest()
        filters.release()
        val updated = awaitBrowser(app, fixture, LIBRARY_A) { it.filterOptions?.genres == listOf("NewMetadataGenre") }
        assertEquals(original.content.items, updated.content.items)
        assertEquals("结构变更只需一轮新的筛选请求", before + 1, fixture.counts().filters)
    }

    @Test
    fun structuralMediaChangeCancelsOlderFiltersAndTheirLateReplyCannotRestoreRemovedOptions() = withApp { app, fixture ->
        onMain { app.open(app.state.value.home.libraries.single { it.id == LIBRARY_A }) }
        awaitBrowser(app, fixture, LIBRARY_A)
        val oldFilters = fixture.blockNextFilters(captureResponse = true, allowClientDisconnect = true)
        onMain { app.retryBrowserFilters(LIBRARY_A) }
        oldFilters.awaitRequest()
        fixture.libraryAGenres.set(listOf("NewMetadataGenre"))
        onMain { app.mediaChanged(checkNotNull(app.state.value.session), MOVIE_A) }
        awaitBrowser(app, fixture, LIBRARY_A) { it.filterOptions?.genres == listOf("NewMetadataGenre") }
        oldFilters.release()
        oldFilters.awaitResponse()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        fixture.assertHealthy()
        assertEquals("旧筛选响应即使实际返回，也不能复活已删除的类型", listOf("NewMetadataGenre"),
            app.state.value.browsers.getValue(LIBRARY_A).filterOptions?.genres)
    }

    @Test
    fun reentryReusesPopulatedEmptyAndGroupedFavoriteResultsWithSelectedQuery() = withApp { app, fixture ->
        val library = app.state.value.home.libraries.single { it.id == LIBRARY_A }
        val emptyLibrary = app.state.value.home.libraries.single { it.id == EMPTY_LIBRARY }
        onMain { app.open(library) }
        val initial = awaitBrowser(app, fixture, LIBRARY_A)
        val selected = initial.query.copy(sort = LibrarySort.TITLE, descending = false, genres = setOf("CacheGenre"))
        onMain { app.changeBrowserQuery(LIBRARY_A, selected) }
        val populated = awaitBrowser(app, fixture, LIBRARY_A) { it.query == selected }
        assertEquals(listOf(MOVIE_A), populated.content.items.map { it.id })
        val populatedCounts = fixture.counts()
        onMain {
            app.back()
            app.open(library)
            assertCached(app, LIBRARY_A, populated)
        }
        fixture.assertCounts(populatedCounts)

        onMain { app.selectTab(Route.Home); app.open(emptyLibrary) }
        val empty = awaitBrowser(app, fixture, EMPTY_LIBRARY)
        assertTrue(empty.content.items.isEmpty())
        assertEquals(0, empty.content.total)
        assertTrue(checkNotNull(empty.filterOptions).genres.isEmpty())
        val emptyCounts = fixture.counts()
        onMain {
            app.back()
            app.open(emptyLibrary)
            assertCached(app, EMPTY_LIBRARY, empty)
        }
        fixture.assertCounts(emptyCounts)

        onMain { app.selectTab(Route.Favorites) }
        val favorites = awaitFavorites(app, fixture)
        assertTrue(favorites.sections.values.all { it.items.isEmpty() })
        val favoriteCounts = fixture.counts()
        onMain {
            app.selectTab(Route.Home)
            app.selectTab(Route.Favorites)
            assertCachedFavorites(app, favorites)
        }
        fixture.assertCounts(favoriteCounts)
        fixture.assertCounts(Counts(items = 3, filters = 2, favoriteGroups = 8))
        fixture.assertFavoriteRequests(mapOf("Movie" to 1, "Series" to 1, "BoxSet" to 1, "Person" to 1,
            "Episode" to 1, "Season" to 1, "MusicAlbum" to 1, "Audio" to 1))
    }

    @Test
    fun explicitQueryAndParentChangesReloadOnlyRequiredRequests() = withApp { app, fixture ->
        val key = "shared-browser"
        val initialQuery = LibraryQuery(itemTypes = setOf("Movie"))
        onMain { app.configureBrowser(key, LIBRARY_A, initialQuery) }
        awaitBrowser(app, fixture, key)
        fixture.assertCounts(Counts(1, 1))

        val sorted = initialQuery.copy(sort = LibrarySort.TITLE, descending = false)
        onMain { app.changeBrowserQuery(key, sorted) }
        val sortedState = awaitBrowser(app, fixture, key) { it.query == sorted }
        fixture.assertCounts(Counts(2, 1))
        assertEquals("SortName", fixture.lastItems().query["sortBy"])
        assertEquals("Ascending", fixture.lastItems().query["sortOrder"])
        onMain { app.changeBrowserQuery(key, sorted); assertCached(app, key, sortedState) }
        fixture.assertCounts(Counts(2, 1))

        val expanded = sorted.copy(itemTypes = setOf("Movie", "Series"))
        val expandedRead = fixture.blockNextItems()
        onMain { app.changeBrowserQuery(key, expanded) }
        expandedRead.awaitRequest()
        val pending = app.state.value.browsers.getValue(key)
        assertEquals(expanded, pending.query)
        assertEquals("新类型尚未返回时，筛选仍属于当前结果", sorted, pending.displayedQuery)
        assertEquals(sortedState.filterOptions, pending.filterOptions)
        fixture.assertCounts(Counts(3, 1))
        expandedRead.release()
        val expandedState = awaitBrowser(app, fixture, key) { it.query == expanded }
        assertEquals(expanded, expandedState.displayedQuery)
        fixture.assertCounts(Counts(3, 2))
        assertEquals(setOf("Movie", "Series"), fixture.lastFilters().query["includeItemTypes"]?.split(',')?.toSet())

        val replacement = LibraryQuery(sort = LibrarySort.YEAR, itemTypes = setOf("Movie"))
        onMain { app.configureBrowser(key, LIBRARY_B, replacement) }
        val changedParent = awaitBrowser(app, fixture, key) {
            it.query == replacement && it.content.items.map { item -> item.id } == listOf(MOVIE_B)
        }
        fixture.assertCounts(Counts(4, 3))
        assertEquals(LIBRARY_B, fixture.lastItems().query["parentId"])
        assertEquals(LIBRARY_B, fixture.lastFilters().query["parentId"])
        assertEquals(listOf("ParentBGenre"), changedParent.filterOptions?.genres)
    }

    @Test
    fun successfulUserDataChangesPreserveUnrelatedQueriesAndReloadDependentLists() = withApp { app, fixture ->
        val library = app.state.value.home.libraries.single { it.id == LIBRARY_A }
        onMain { app.open(library) }
        val initial = awaitBrowser(app, fixture, LIBRARY_A)
        val unplayed = initial.query.copy(played = false)
        onMain { app.changeBrowserQuery(LIBRARY_A, unplayed) }
        val unplayedState = awaitBrowser(app, fixture, LIBRARY_A) { it.query == unplayed }
        val movie = unplayedState.content.items.single()
        onMain { app.selectTab(Route.Favorites) }
        val cachedFavorites = awaitFavorites(app, fixture)
        assertTrue(cachedFavorites.sections.values.all { it.items.isEmpty() })
        onMain { app.selectTab(Route.Home); app.toggleFavorite(movie) }
        awaitState(app, fixture, "收藏写入必须结束") {
            "favorite/$MOVIE_A" !in it.actionBusy && it.browsers[LIBRARY_A]?.content?.items?.singleOrNull()?.isFavorite == true
        }
        val beforeFavoriteReturn = fixture.counts()
        onMain { app.selectTab(Route.Favorites) }
        val favorites = awaitFavorites(app, fixture) {
            it.sections[DiscoveryCategory.MOVIES]?.items?.map { item -> item.id } == listOf(MOVIE_A)
        }
        assertTrue("影片不能出现在其他收藏分类", favorites.sections.filterKeys { it != DiscoveryCategory.MOVIES }.values.all { it.items.isEmpty() })
        assertEquals("收藏电影后其他七组必须完整保留缓存",
            cachedFavorites.sections.filterKeys { it != DiscoveryCategory.MOVIES },
            favorites.sections.filterKeys { it != DiscoveryCategory.MOVIES })
        fixture.assertCounts(beforeFavoriteReturn.plus(favoriteGroups = 1))
        fixture.assertFavoriteRequests(mapOf("Movie" to 2, "Series" to 1, "BoxSet" to 1, "Person" to 1,
            "Episode" to 1, "Season" to 1, "MusicAlbum" to 1, "Audio" to 1))
        val favoriteCounts = fixture.counts()
        onMain {
            app.selectTab(Route.Home)
            app.selectTab(Route.Favorites)
            assertCachedFavorites(app, favorites)
        }
        fixture.assertCounts(favoriteCounts)

        val beforeFavoriteLibraryReturn = fixture.counts()
        onMain { app.selectTab(Route.Home); app.open(library) }
        val refreshed = awaitBrowser(app, fixture, LIBRARY_A) { it.query == unplayed }
        fixture.assertCounts(beforeFavoriteLibraryReturn)
        val beforePlayed = fixture.counts()
        onMain { app.selectTab(Route.Home); app.togglePlayed(refreshed.content.items.single()) }
        awaitState(app, fixture, "已看写入必须结束") {
            "played/$MOVIE_A" !in it.actionBusy && it.browsers[LIBRARY_A]?.content?.items?.singleOrNull()?.played == true
        }
        onMain { app.open(library) }
        val empty = awaitBrowser(app, fixture, LIBRARY_A) { it.query == unplayed && it.content.items.isEmpty() }
        fixture.assertCounts(beforePlayed.plus(items = 1))
        val afterPlayed = fixture.counts()
        onMain { app.back(); app.open(library); assertCached(app, LIBRARY_A, empty) }
        fixture.assertCounts(afterPlayed)

        // 播放返回也需使隐藏浏览列表失效，不能因已缓存空结果而永久停止更新。
        val session = checkNotNull(app.state.value.session)
        onMain { app.selectTab(Route.Home); app.refreshAfterPlayback(session, MOVIE_A) }
        awaitState(app, fixture, "播放返回的首页和详情刷新必须结束") {
            it.home.loaded && !it.home.loading && !it.detailLoading && it.details[MOVIE_A] != null &&
                it.detailExtras[MOVIE_A]?.loading == false
        }
        val beforePlaybackReturn = fixture.counts()
        onMain { app.open(library) }
        awaitBrowser(app, fixture, LIBRARY_A) { it.query == unplayed && it.content.items.isEmpty() }
        fixture.assertCounts(beforePlaybackReturn.plus(items = 1))
    }

    @Test
    fun favoriteAndPlayedReloadOnlyQueriesDependingOnTheirChangedFields() = withApp { app, fixture ->
        val base = LibraryQuery(itemTypes = setOf("Movie"))
        val queries = linkedMapOf(
            "ordinary-created" to base,
            "ordinary-title" to base.copy(sort = LibrarySort.TITLE, descending = false),
            "favorite-only" to base.copy(favorite = true),
            "not-favorite" to base.copy(favorite = false),
            "favorite-tab" to base.copy(tab = LibraryTab.FAVORITES),
            "unplayed" to base.copy(played = false),
            "resume" to base.copy(tab = LibraryTab.RESUME),
            "date-played" to base.copy(sort = LibrarySort.DATE_PLAYED),
        )
        queries.forEach { (key, query) ->
            onMain { app.configureBrowser(key, LIBRARY_A, query) }
            awaitBrowser(app, fixture, key)
        }
        val original = app.state.value.browsers.getValue("ordinary-created").content.items.single()
        val beforeFavorite = fixture.counts()
        onMain { app.toggleFavorite(original) }
        awaitState(app, fixture, "收藏应立即写回普通列表") { state ->
            "favorite/$MOVIE_A" !in state.actionBusy &&
                state.browsers["ordinary-created"]?.content?.items?.singleOrNull()?.isFavorite == true
        }
        queries.forEach { (key, query) ->
            onMain { app.configureBrowser(key, LIBRARY_A, query) }
            awaitBrowser(app, fixture, key)
        }
        fixture.assertCounts(beforeFavorite.plus(items = 3))
        assertTrue(app.state.value.browsers.getValue("not-favorite").content.items.isEmpty())
        for (key in listOf("ordinary-created", "ordinary-title", "favorite-only", "favorite-tab")) {
            assertTrue("$key 应保留成功的收藏状态", app.state.value.browsers.getValue(key).content.items.single().isFavorite)
        }

        val favorite = app.state.value.browsers.getValue("ordinary-created").content.items.single()
        val beforePlayed = fixture.counts()
        onMain { app.togglePlayed(favorite) }
        awaitState(app, fixture, "已看应立即写回普通列表") { state ->
            "played/$MOVIE_A" !in state.actionBusy &&
                state.browsers["ordinary-created"]?.content?.items?.singleOrNull()?.played == true
        }
        queries.forEach { (key, query) ->
            onMain { app.configureBrowser(key, LIBRARY_A, query) }
            awaitBrowser(app, fixture, key)
        }
        fixture.assertCounts(beforePlayed.plus(items = 3))
        assertTrue(app.state.value.browsers.getValue("unplayed").content.items.isEmpty())
        assertTrue(app.state.value.browsers.getValue("resume").content.items.isEmpty())
        for (key in listOf("ordinary-created", "ordinary-title", "favorite-only", "favorite-tab", "date-played")) {
            val item = app.state.value.browsers.getValue(key).content.items.single()
            assertTrue("$key 的已看和收藏应原地更新", item.played && item.isFavorite)
        }
    }

    @Test
    fun successfulFavoriteOnAnAppendedPagePreservesEveryReadItemWithoutAnotherItemsRequest() = withApp { app, fixture ->
        val library = MediaItem(PAGED_LIBRARY, "分页缓存测试", "CollectionFolder", isFolder = true)
        onMain { app.open(library) }
        awaitBrowser(app, fixture, PAGED_LIBRARY)
        onMain { app.loadBrowser(PAGED_LIBRARY, append = true) }
        val complete = awaitBrowser(app, fixture, PAGED_LIBRARY) { it.content.nextIndex == PAGED_ITEM_COUNT }
        val last = complete.content.items.last()
        assertEquals("cache-paged-${PAGED_ITEM_COUNT - 1}", last.id)
        val beforeFavorite = fixture.counts()
        onMain { app.selectTab(Route.Home); app.toggleFavorite(last) }
        val updated = awaitState(app, fixture, "深页条目的收藏必须原地写回") { state ->
            "favorite/${last.id}" !in state.actionBusy &&
                state.browsers[PAGED_LIBRARY]?.content?.items?.lastOrNull()?.isFavorite == true
        }.browsers.getValue(PAGED_LIBRARY)
        assertEquals(complete.content.copy(items = complete.content.items.map {
            if (it.id == last.id) it.copy(isFavorite = true) else it
        }), updated.content)
        onMain { app.open(library); assertCached(app, PAGED_LIBRARY, updated) }
        fixture.assertCounts(beforeFavorite)
    }

    @Test
    fun staleItemsReconcileSuccessfulUserDataWhileAnUnrelatedQueryStillCompletes() = withApp { app, fixture ->
        val query = LibraryQuery(itemTypes = setOf("Movie"))
        val stale = fixture.blockNextItems(captureResponse = true)
        onMain { app.configureBrowser("stale", LIBRARY_A, query) }
        stale.awaitRequest()
        val unrelated = fixture.blockNextItems()
        onMain { app.configureBrowser("unrelated", LIBRARY_B, query) }
        unrelated.awaitRequest()
        val obsolete = fixture.blockNextItems(captureResponse = true, allowClientDisconnect = true)
        val favoriteQuery = query.copy(favorite = true)
        onMain { app.configureBrowser("affected", LIBRARY_A, favoriteQuery) }
        obsolete.awaitRequest()
        val movie = app.state.value.home.libraryItems.getValue(LIBRARY_A).single()
        onMain { app.toggleFavorite(movie) }
        awaitState(app, fixture, "在途读取期间收藏写入必须成功") { state ->
            "favorite/$MOVIE_A" !in state.actionBusy &&
                state.home.libraryItems[LIBRARY_A]?.singleOrNull()?.isFavorite == true
        }
        onMain { app.togglePlayed(movie.copy(isFavorite = true)) }
        awaitState(app, fixture, "连续已看写入不能擦掉先前收藏") { state ->
            "played/$MOVIE_A" !in state.actionBusy &&
                state.home.libraryItems[LIBRARY_A]?.singleOrNull()?.let { it.played && it.isFavorite } == true
        }
        assertFalse("收藏筛选的旧在途请求应被取消", app.state.value.browsers.getValue("affected").content.loading)
        onMain { app.configureBrowser("affected", LIBRARY_A, favoriteQuery) }
        assertTrue(awaitBrowser(app, fixture, "affected").content.items.single().isFavorite)
        obsolete.release()
        obsolete.awaitResponse()
        assertTrue("晚到的旧空收藏结果不能覆盖新查询", app.state.value.browsers.getValue("affected").content.items.single().isFavorite)
        unrelated.release()
        val other = awaitBrowser(app, fixture, "unrelated")
        assertEquals(listOf(MOVIE_B), other.content.items.map { it.id })
        assertTrue("无关查询不能因为其他条目写入而被丢弃", app.state.value.browsers.getValue("stale").content.loading)
        stale.release()
        val reconciled = awaitBrowser(app, fixture, "stale")
        val item = reconciled.content.items.single()
        assertEquals(MOVIE_A, item.id)
        assertTrue("旧 Items 回包不能覆盖后来写成功的收藏和已看", item.isFavorite && item.played)
        fixture.assertCounts(Counts(items = 4, filters = 3))
    }

    @Test
    fun returningToAnEmptyMemoryPageCancelsDiskRestoreAndImmediatelyShowsTheLoadedEmptyState() = withApp { app, fixture ->
        val library = app.state.value.home.libraries.single { it.id == EMPTY_LIBRARY }
        onMain { app.open(library) }
        val empty = awaitBrowser(app, fixture, EMPTY_LIBRARY)
        assertTrue(empty.content.items.isEmpty())
        val cancelled = fixture.blockNextItems(allowClientDisconnect = true)
        onMain {
            // 同一 Main 回调内切回，磁盘即使已经读完也还没有机会恢复到主线程。
            app.changeBrowserQuery(EMPTY_LIBRARY, empty.query.copy(nameInitial = "A"))
            assertTrue("未命中的新查询应先进入磁盘查找阶段", app.state.value.browsers.getValue(EMPTY_LIBRARY).restoringCache)
            app.changeBrowserQuery(EMPTY_LIBRARY, empty.query)
            assertCached(app, EMPTY_LIBRARY, empty)
            assertFalse("合法空结果命中内存后不能仍然隐藏空态", app.state.value.browsers.getValue(EMPTY_LIBRARY).restoringCache)
        }
        cancelled.release()
    }

    @Test
    fun explicitParentPlayedAndFavoriteWritesCancelOlderPlaybackAggregationReads() = withApp { app, fixture ->
        val query = LibraryQuery(itemTypes = setOf("Series"))
        onMain { app.configureBrowser(PARENT_LIBRARY, PARENT_LIBRARY, query) }
        val initial = awaitBrowser(app, fixture, PARENT_LIBRARY)
        assertEquals(listOf(SERIES_A), initial.content.items.map { it.id })
        val before = fixture.counts()
        for (operation in listOf("played", "favorite")) {
            val parent = app.state.value.browsers.getValue(PARENT_LIBRARY).content.items.single()
            val oldAggregate = fixture.blockItem(SERIES_A, captureResponse = true, allowClientDisconnect = true)
            onMain { app.refreshAfterPlayback(checkNotNull(app.state.value.session), EPISODE_A) }
            oldAggregate.awaitRequest()
            onMain {
                if (operation == "played") app.togglePlayed(parent) else app.toggleFavorite(parent)
            }
            awaitState(app, fixture, "显式 $operation 写入应先更新已展示的父项") { state ->
                "$operation/$SERIES_A" !in state.actionBusy &&
                    state.browsers[PARENT_LIBRARY]?.content?.items?.singleOrNull()?.let {
                        if (operation == "played") it.played && it.unplayedItemCount == 0 else it.played && it.isFavorite
                    } == true
            }
            oldAggregate.awaitClientDisconnect()
            oldAggregate.release()
            oldAggregate.awaitResponse()
            val latest = app.state.value.browsers.getValue(PARENT_LIBRARY).content.items.single()
            assertTrue("旧父项聚合不能覆写已看", latest.played)
            assertEquals("旧未看集数不能回流", 0, latest.unplayedItemCount)
            if (operation == "favorite") assertTrue("旧父项聚合不能覆写收藏", latest.isFavorite)
            awaitState(app, fixture, "播放返回的其他读取应独立完成") {
                it.home.loaded && !it.home.loading && !it.detailLoading && it.detailExtras[EPISODE_A]?.loading == false
            }
        }
        fixture.assertCounts(before)
    }

    @Test
    fun recreatedViewModelDisplaysDiskFirstPageWhileItsNewItemsResponseIsBlocked() = withRecreatedApp { app, fixture, recreate ->
        val target = RangeTarget.LIBRARY
        val previous = loadReadRange(app, fixture, target)
        val query = app.state.value.browsers.getValue(target.id).query
        awaitDiskSnapshot(app, fixture, target.id, target.id)
        val originalSession = checkNotNull(app.state.value.session)
        val originalProfile = app.state.value.currentProfileId
        fixture.replaceRange(revision = 1)
        val before = fixture.rangeRequests(target.id).size
        val gate = fixture.blockRangeFragment(target.id, startIndex = 0)
        val restored = recreate()
        awaitRestoredSession(restored, fixture)
        assertEquals(originalSession, restored.state.value.session)
        assertEquals(originalProfile, restored.state.value.currentProfileId)
        onMain { restored.configureBrowser(target.id, target.id, query) }
        gate.awaitRequest()
        val cached = awaitState(restored, fixture, "网络仍在途时应先显示磁盘首屏") { state ->
            state.browsers[target.id]?.let { browser ->
                !browser.restoringCache && browser.content.let {
                    it.loading && it.items == previous.items.take(PAGE_SIZE) && it.nextIndex == PAGE_SIZE
                }
            } == true
        }.browsers.getValue(target.id).content
        assertEquals(previous.total, cached.total)
        assertEquals("重建只应重验首屏，不能重放旧深页", listOf(0), fixture.rangeStarts(target.id, before))
        gate.release()
        assertRange(awaitRange(restored, fixture, target), target, revision = 1, count = PAGE_SIZE, total = RANGE_ITEM_COUNT)
    }

    @Test
    fun recreatedViewModelNeverDisplaysAnOldTokenOrPermissionSnapshot() = withRecreatedApp { app, fixture, recreate ->
        val target = RangeTarget.LIBRARY
        onMain { app.configureBrowser(target.id, target.id, LibraryQuery(itemTypes = setOf("Movie"))) }
        awaitRange(app, fixture, target)
        awaitDiskSnapshot(app, fixture, target.id, target.id)
        val original = checkNotNull(app.state.value.session)
        val profileId = app.state.value.currentProfileId
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fixture.replaceRange(revision = 1)
        val variants = listOf(original.copy(accessToken = "renewed-cache-token"), original.copy(canDownload = true))
        variants.forEach { changed ->
            fixture.canDownload.set(changed.canDownload)
            SessionStore(context).save(changed)
            val gate = fixture.blockRangeFragment(target.id, startIndex = 0)
            val restored = recreate()
            awaitRestoredSession(restored, fixture)
            assertEquals(changed, restored.state.value.session)
            assertEquals("仅 token 或权限变化不能变成本机新资料", profileId, restored.state.value.currentProfileId)
            runBlocking {
                val leaked = AtomicBoolean()
                val observation = launch(Dispatchers.Unconfined) {
                    restored.state.collect { state ->
                        if (state.browsers[target.id]?.content?.items?.any { it.name.startsWith("revision-0/") } == true)
                            leaked.set(true)
                    }
                }
                try {
                    onMain { restored.configureBrowser(target.id, target.id, LibraryQuery(itemTypes = setOf("Movie"))) }
                    gate.awaitRequest()
                    awaitState(restored, fixture, "磁盘查找结束后新身份仍应为空，等待自己的网络结果") { state ->
                        state.browsers[target.id]?.let { !it.restoringCache && it.content.loading && it.content.items.isEmpty() } == true
                    }
                    gate.release()
                    assertRange(awaitRange(restored, fixture, target), target, revision = 1, count = PAGE_SIZE, total = RANGE_ITEM_COUNT)
                    assertFalse("整个恢复期间都不能泄露旧权限或旧 token 的快照", leaked.get())
                } finally { observation.cancel() }
            }
        }
    }

    @Test
    fun explicitRefreshRetriesFailedFiltersAndKeepsExistingContent() = withApp { app, fixture ->
        fixture.failNextFilters.set(true)
        onMain { app.configureBrowser(LIBRARY_A, LIBRARY_A, LibraryQuery(itemTypes = setOf("Movie"))) }
        val failed = awaitState(app, fixture, "列表成功后必须独立显示筛选错误") { state ->
            state.browsers[LIBRARY_A]?.let { browser ->
                !browser.content.loading && browser.content.items.map { it.id } == listOf(MOVIE_A) &&
                    !browser.filtersLoading && browser.filtersError != null
            } == true
        }.browsers.getValue(LIBRARY_A)
        assertNull(failed.filterOptions)
        val previousItems = failed.content.items
        val beforeRefresh = fixture.counts()
        val itemsGate = fixture.blockNextItems()
        val filtersGate = fixture.blockNextFilters()
        onMain {
            app.refreshBrowser(LIBRARY_A)
            val refreshing = app.state.value.browsers.getValue(LIBRARY_A)
            assertTrue(refreshing.content.loading)
            assertTrue(refreshing.filtersLoading)
            assertEquals("刷新期间必须保留原列表", previousItems, refreshing.content.items)
        }
        itemsGate.awaitRequest()
        filtersGate.awaitRequest()
        fixture.assertCounts(beforeRefresh.plus(items = 1, filters = 1))
        itemsGate.release()
        val pendingFilters = awaitState(app, fixture, "列表响应不能继续等待筛选") {
            it.browsers[LIBRARY_A]?.let { browser -> !browser.content.loading && browser.filtersLoading } == true
        }.browsers.getValue(LIBRARY_A)
        assertEquals(previousItems, pendingFilters.content.items)
        filtersGate.release()
        val recovered = awaitBrowser(app, fixture, LIBRARY_A)
        assertNull(recovered.filtersError)
        assertEquals(listOf("CacheGenre"), recovered.filterOptions?.genres)
        assertEquals(previousItems, recovered.content.items)
        val recoveredCounts = fixture.counts()
        onMain {
            app.configureBrowser(LIBRARY_A, LIBRARY_A, LibraryQuery(itemTypes = setOf("Movie")))
            assertCached(app, LIBRARY_A, recovered)
        }
        fixture.assertCounts(recoveredCounts)

        val optionsOnly = fixture.blockNextFilters()
        onMain {
            app.retryBrowserFilters(LIBRARY_A)
            val retrying = app.state.value.browsers.getValue(LIBRARY_A)
            assertFalse("仅重试筛选不能重新加载列表", retrying.content.loading)
            assertTrue(retrying.filtersLoading)
            assertEquals(previousItems, retrying.content.items)
        }
        optionsOnly.awaitRequest()
        fixture.assertCounts(recoveredCounts.plus(filters = 1))
        optionsOnly.release()
        assertNull(awaitBrowser(app, fixture, LIBRARY_A).filtersError)
    }

    @Test
    fun reentryPreservesAllPagesAndFailedAppendUntilExplicitRetry() = withApp { app, fixture ->
        val library = MediaItem(PAGED_LIBRARY, "分页缓存测试", "CollectionFolder", isFolder = true)
        val expectedIds = (0 until PAGED_ITEM_COUNT).map { "cache-paged-$it" }
        onMain { app.open(library) }
        val firstPage = awaitBrowser(app, fixture, PAGED_LIBRARY)
        assertEquals(expectedIds.take(PAGE_SIZE), firstPage.content.items.map { it.id })
        assertEquals(PAGED_ITEM_COUNT, firstPage.content.total)
        assertEquals(PAGE_SIZE, firstPage.content.nextIndex)
        fixture.assertCounts(Counts(1, 1))

        onMain { app.loadBrowser(PAGED_LIBRARY, append = true) }
        val complete = awaitBrowser(app, fixture, PAGED_LIBRARY) { it.content.nextIndex == PAGED_ITEM_COUNT }
        assertEquals(expectedIds, complete.content.items.map { it.id })
        assertEquals(PAGED_ITEM_COUNT, complete.content.total)
        assertFalse(complete.content.retryAppend)
        val completeCounts = fixture.counts()
        fixture.assertCounts(Counts(2, 1))
        assertEquals(PAGE_SIZE.toString(), fixture.lastItems().query["startIndex"])
        onMain {
            app.back()
            app.open(library)
            assertCached(app, PAGED_LIBRARY, complete)
        }
        fixture.assertCounts(completeCounts)

        // 新查询从首屏开始；其追加错误不能在返回时偷偷变成首屏重查。
        val changedQuery = complete.query.copy(sort = LibrarySort.COMMUNITY_RATING, descending = false)
        onMain { app.changeBrowserQuery(PAGED_LIBRARY, changedQuery) }
        val refreshed = awaitBrowser(app, fixture, PAGED_LIBRARY) { it.content.nextIndex == PAGE_SIZE }
        assertEquals(expectedIds.take(PAGE_SIZE), refreshed.content.items.map { it.id })
        assertEquals(PAGED_ITEM_COUNT, refreshed.content.total)
        fixture.assertCounts(completeCounts.plus(items = 1))
        assertEquals("0", fixture.lastItems().query["startIndex"])

        fixture.failNextAppend.set(true)
        onMain { app.loadBrowser(PAGED_LIBRARY, append = true) }
        val failed = awaitState(app, fixture, "追加失败必须保留已加载页和重试状态") { state ->
            state.browsers[PAGED_LIBRARY]?.let { browser ->
                !browser.content.loading && !browser.content.loadingMore &&
                    browser.content.error != null && browser.content.retryAppend
            } == true
        }.browsers.getValue(PAGED_LIBRARY)
        assertEquals(refreshed.content.items, failed.content.items)
        assertEquals(PAGED_ITEM_COUNT, failed.content.total)
        assertEquals(PAGE_SIZE, failed.content.nextIndex)
        val failedCounts = fixture.counts()
        fixture.assertCounts(completeCounts.plus(items = 2))
        assertEquals(PAGE_SIZE.toString(), fixture.lastItems().query["startIndex"])
        onMain {
            app.back()
            app.open(library)
            assertCached(app, PAGED_LIBRARY, failed)
        }
        fixture.assertCounts(failedCounts)

        onMain { app.loadBrowser(PAGED_LIBRARY, append = true) }
        val recovered = awaitBrowser(app, fixture, PAGED_LIBRARY) { it.content.nextIndex == PAGED_ITEM_COUNT }
        assertEquals(expectedIds, recovered.content.items.map { it.id })
        assertEquals(PAGED_ITEM_COUNT, recovered.content.total)
        assertFalse(recovered.content.retryAppend)
        assertNull(recovered.content.error)
        fixture.assertCounts(failedCounts.plus(items = 1))
        assertEquals(PAGE_SIZE.toString(), fixture.lastItems().query["startIndex"])
        val recoveredCounts = fixture.counts()
        onMain {
            app.back()
            app.open(library)
            assertCached(app, PAGED_LIBRARY, recovered)
        }
        fixture.assertCounts(recoveredCounts)
    }

    @Test
    fun thousandItemLibraryAppendsOneHundredAtATimeWithoutDuplicateOrAutomaticWholeLibraryReads() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        val total = 1_200
        fixture.replaceRange(revision = 0, total = total, pageSize = APPEND_PAGE_SIZE)
        val library = MediaItem(target.id, "千条分页测试", "CollectionFolder", isFolder = true)
        onMain { app.open(library) }
        val first = awaitBrowser(app, fixture, target.id)
        assertRange(first.content, target, revision = 0, count = PAGE_SIZE, total = total)
        assertEquals("首屏只请求40条，不能预读全库", listOf("0" to "40"),
            fixture.rangeRequests(target.id).map { it.query["startIndex"] to it.query["limit"] })

        val firstAppend = fixture.blockRangeFragment(target.id, startIndex = PAGE_SIZE)
        onMain { repeat(5) { app.loadBrowser(target.id, append = true) } }
        firstAppend.awaitRequest()
        assertEquals(first.content.items, app.state.value.browsers.getValue(target.id).content.items)
        assertTrue(app.state.value.browsers.getValue(target.id).content.loadingMore)
        assertEquals("重复触底只允许发出同一条追加请求", listOf("0" to "40", "40" to "100"),
            fixture.rangeRequests(target.id).map { it.query["startIndex"] to it.query["limit"] })
        firstAppend.release()
        val firstExpanded = awaitBrowser(app, fixture, target.id)
        val firstExpandedCount = PAGE_SIZE + APPEND_PAGE_SIZE
        assertRange(firstExpanded.content, target, revision = 0, count = firstExpandedCount, total = total)
        fixture.assertCounts(Counts(items = 2, filters = 1))

        val secondAppend = fixture.blockRangeFragment(target.id, startIndex = firstExpandedCount)
        onMain { repeat(3) { app.loadBrowser(target.id, append = true) } }
        secondAppend.awaitRequest()
        assertEquals(firstExpanded.content.items, app.state.value.browsers.getValue(target.id).content.items)
        assertEquals("后续追加应沿实际游标140取100条", listOf("0" to "40", "40" to "100", "140" to "100"),
            fixture.rangeRequests(target.id).map { it.query["startIndex"] to it.query["limit"] })
        secondAppend.release()
        val expanded = awaitBrowser(app, fixture, target.id)
        assertRange(expanded.content, target, revision = 0, count = PAGE_SIZE + APPEND_PAGE_SIZE * 2, total = total)
        assertFalse("追加结束不能继续自动拉取全库", expanded.content.loadingMore)
        val expandedCounts = fixture.counts()
        fixture.assertCounts(Counts(items = 3, filters = 1))
        onMain {
            app.back()
            app.open(library)
            assertCached(app, target.id, expanded)
        }
        fixture.assertCounts(expandedCounts)
    }

    @Test
    fun libraryRefreshReplacesAllEightyReadItemsAtomically() = withApp { app, fixture ->
        assertAtomicRangeRefresh(app, fixture, RangeTarget.LIBRARY)
    }

    @Test
    fun seasonRefreshReplacesAllOneHundredTwentyReadEpisodesAtomically() = withApp { app, fixture ->
        assertAtomicRangeRefresh(app, fixture, RangeTarget.SEASON)
    }

    @Test
    fun laterLibraryRefreshFailureKeepsPreviousRangeAndRetryRecovers() = withApp { app, fixture ->
        assertRangeRefreshFailure(app, fixture, RangeTarget.LIBRARY)
    }

    @Test
    fun laterSeasonRefreshFailureKeepsPreviousRangeAndRetryRecovers() = withApp { app, fixture ->
        assertRangeRefreshFailure(app, fixture, RangeTarget.SEASON)
    }

    @Test
    fun libraryRefreshStopsAtReducedTotalAndDoesNotAppendPastEnd() = withApp { app, fixture ->
        assertReducedRangeRefresh(app, fixture, RangeTarget.LIBRARY)
    }

    @Test
    fun seasonRefreshStopsAtReducedTotalAndDoesNotAppendPastEnd() = withApp { app, fixture ->
        assertReducedRangeRefresh(app, fixture, RangeTarget.SEASON)
    }

    @Test
    fun changedLibraryQueryReadsOnlyFirstFortyItemsBeforeNormalAppend() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        val previous = loadReadRange(app, fixture, target)
        val original = app.state.value.browsers.getValue(target.id).query
        val selected = original.copy(sort = LibrarySort.TITLE, descending = false)
        fixture.replaceRange(revision = 1)
        val before = fixture.rangeRequests(target.id).size
        val first = fixture.blockRangeFragment(target.id, startIndex = 0)
        onMain { app.changeBrowserQuery(target.id, selected) }
        first.awaitRequest()
        val pending = app.state.value.browsers.getValue(target.id)
        assertEquals(selected, pending.query)
        assertEquals("请求中的排序不能先套到旧结果上", original, pending.displayedQuery)
        assertEquals(previous.items, pending.content.items)
        assertEquals(previous.total, pending.content.total)
        assertEquals(previous.nextIndex, pending.content.nextIndex)
        onMain { app.loadBrowser(target.id, append = true) }
        assertEquals("等待新查询时，不能用旧游标追加新查询", listOf(0), fixture.rangeStarts(target.id, before))
        first.release()
        val changed = awaitRange(app, fixture, target)
        assertRange(changed, target, revision = 1, count = PAGE_SIZE, total = RANGE_ITEM_COUNT)
        assertEquals("查询与结果必须一起发布", selected, app.state.value.browsers.getValue(target.id).displayedQuery)
        assertEquals("换查询不能沿用旧查询的深页范围", listOf(0), fixture.rangeStarts(target.id, before))
        onMain { app.loadBrowser(target.id, append = true) }
        val appended = awaitRange(app, fixture, target)
        assertRange(appended, target, revision = 1, count = PAGE_SIZE * 2, total = RANGE_ITEM_COUNT)
        assertEquals(listOf(0, PAGE_SIZE), fixture.rangeStarts(target.id, before))
        assertEquals("SortName", fixture.lastItems().query["sortBy"])
        assertEquals("Ascending", fixture.lastItems().query["sortOrder"])
    }

    @Test
    fun returningToVisibleCategoryCancelsPendingReadAndRetainsTheWholeReadRange() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        loadReadRange(app, fixture, target)
        val previous = app.state.value.browsers.getValue(target.id)
        val before = fixture.rangeRequests(target.id).size
        fixture.replaceRange(revision = 1)
        val cancelled = fixture.blockNextItems(captureResponse = true, allowClientDisconnect = true)
        onMain { app.changeBrowserQuery(target.id, previous.query.copy(tab = LibraryTab.COLLECTIONS)) }
        cancelled.awaitRequest()
        assertEquals(previous.query, app.state.value.browsers.getValue(target.id).displayedQuery)
        onMain {
            app.changeBrowserQuery(target.id, previous.query)
            assertCached(app, target.id, previous)
        }
        cancelled.awaitClientDisconnect()
        cancelled.release()
        cancelled.awaitResponse()
        onMain { assertCached(app, target.id, previous) }
        assertEquals("改选回当前分类不应再读网络或降为40项缓存", before + 1, fixture.rangeRequests(target.id).size)
        assertRange(app.state.value.browsers.getValue(target.id).content, target,
            revision = 0, count = target.readCount, total = RANGE_ITEM_COUNT)
    }

    @Test
    fun failedCategoryReadKeepsTheVisibleCategoryAndRetryPublishesTheNewEmptyResult() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        val previous = loadReadRange(app, fixture, target)
        val original = app.state.value.browsers.getValue(target.id).query
        val selected = original.copy(tab = LibraryTab.RESUME)
        val failure = fixture.blockRangeFragment(target.id, startIndex = 0, fail = true)
        onMain { app.changeBrowserQuery(target.id, selected) }
        failure.awaitRequest()
        failure.release()
        val failed = awaitRange(app, fixture, target, allowError = true)
        assertTrue("失败需要保留可重试的反馈", failed.error != null)
        assertEquals(previous.items, failed.items)
        assertEquals(previous.total, failed.total)
        assertEquals(previous.nextIndex, failed.nextIndex)
        assertEquals(selected, app.state.value.browsers.getValue(target.id).query)
        assertEquals(original, app.state.value.browsers.getValue(target.id).displayedQuery)

        val retry = fixture.blockNextItems()
        onMain { app.loadBrowser(target.id) }
        retry.awaitRequest()
        assertEquals("重试期间仍完整保留可见分类", original, app.state.value.browsers.getValue(target.id).displayedQuery)
        assertEquals(previous.items, app.state.value.browsers.getValue(target.id).content.items)
        retry.release()
        val recovered = awaitRange(app, fixture, target)
        assertTrue(recovered.items.isEmpty())
        assertEquals(0, recovered.total)
        assertEquals(0, recovered.nextIndex)
        assertEquals(selected, app.state.value.browsers.getValue(target.id).displayedQuery)
        assertEquals("/UserItems/Resume", fixture.lastItems().path)
    }

    @Test
    fun refreshedLibraryRangeCachesOnlyItsFirstFortyItemsWhenQueryIsRevisited() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        loadReadRange(app, fixture, target)
        val original = app.state.value.browsers.getValue(target.id).query
        fixture.replaceRange(revision = 1)
        onMain { app.refreshBrowser(target.id) }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = target.readCount, total = RANGE_ITEM_COUNT)

        onMain { app.changeBrowserQuery(target.id, original.copy(sort = LibrarySort.TITLE, descending = false)) }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = PAGE_SIZE, total = RANGE_ITEM_COUNT)
        val beforeReturn = fixture.rangeRequests(target.id).size
        onMain {
            app.changeBrowserQuery(target.id, original)
            assertFalse("首屏缓存命中不能再读网络", rangeContent(app.state.value, target).loading)
            assertEquals("缓存命中也要同步发布对应查询", original, app.state.value.browsers.getValue(target.id).displayedQuery)
        }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = PAGE_SIZE, total = RANGE_ITEM_COUNT)
        assertEquals("已阅80条的刷新不能将80条写入首屏缓存", beforeReturn, fixture.rangeRequests(target.id).size)
        onMain { app.loadBrowser(target.id, append = true) }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = target.readCount, total = RANGE_ITEM_COUNT)
        assertEquals(listOf(PAGE_SIZE), fixture.rangeStarts(target.id, beforeReturn))
    }

    @Test
    fun dirtyLibraryReentryRefreshesItsPreviousRangeWithoutPublishingOnlyFirstPage() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        val previous = loadReadRange(app, fixture, target)
        val query = app.state.value.browsers.getValue(target.id).query
        fixture.replaceRange(revision = 1)
        val before = fixture.rangeRequests(target.id).size
        val second = fixture.blockRangeFragment(target.id, startIndex = PAGE_SIZE)
        onMain {
            app.mediaChanged(checkNotNull(app.state.value.session), previous.items.last().id)
            app.configureBrowser(target.id, target.id, query)
        }
        second.awaitRequestBeforeRefreshEnds(app, fixture, target)
        assertEquals(previous.items, rangeContent(app.state.value, target).items)
        second.release()
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = target.readCount, total = RANGE_ITEM_COUNT)
        assertEquals(listOf(0, PAGE_SIZE), fixture.rangeStarts(target.id, before))
    }

    @Test
    fun shortFirstPageAndAppendKeepServerCursorsWithoutFillingUnrequestedRange() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        fixture.replaceRange(revision = 0, total = 26, pageSize = 23)
        onMain { app.configureBrowser(target.id, target.id, LibraryQuery(itemTypes = setOf("Movie"))) }
        val first = awaitRange(app, fixture, target)
        assertRange(first, target, revision = 0, count = 23, total = 26)
        assertEquals("短首屏不能偷偷加载下一页", listOf(0), fixture.rangeStarts(target.id))

        fixture.replaceRange(revision = 1, total = 26, pageSize = 23)
        val refresh = fixture.blockRangeFragment(target.id, startIndex = 0)
        onMain {
            app.refreshBrowser(target.id)
            assertEquals(first.items, rangeContent(app.state.value, target).items)
        }
        refresh.awaitRequest()
        refresh.release()
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = 23, total = 26)
        assertEquals("只读过23条的刷新不应自动扩到40条", listOf(0, 0), fixture.rangeStarts(target.id))

        fixture.replaceRange(revision = 1, total = 26, pageSize = 2)
        onMain { app.loadBrowser(target.id, append = true) }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = 25, total = 26)
        assertEquals("短追加只前进服务器实际返回的2条", listOf(0, 0, 23), fixture.rangeStarts(target.id))
        onMain { app.loadBrowser(target.id, append = true) }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = 26, total = 26)
        assertEquals(listOf(0, 0, 23, 25), fixture.rangeStarts(target.id))
        assertEquals("首读和范围刷新请求40条；追加请求100条，游标仍按实际短页数量前进",
            listOf("40", "40", "100", "100"), fixture.rangeRequests(target.id).map { it.query["limit"] })
    }

    @Test
    fun totalChangingBetweenRefreshFragmentsKeepsOldRangeAndCannotPopulateFirstPageCache() = withApp { app, fixture ->
        val target = RangeTarget.LIBRARY
        val previous = loadReadRange(app, fixture, target)
        val original = app.state.value.browsers.getValue(target.id).query
        fixture.replaceRange(revision = 1, total = 200)
        val before = fixture.rangeRequests(target.id).size
        val second = fixture.blockRangeFragment(target.id, startIndex = PAGE_SIZE)
        onMain { app.refreshBrowser(target.id) }
        second.awaitRequestBeforeRefreshEnds(app, fixture, target)
        assertEquals(previous.items, rangeContent(app.state.value, target).items)
        // 首片40条来自total200；后片在途时服务器删库，只剩20条，start40将返回空。
        fixture.replaceRange(revision = 1, total = 20)
        second.release()
        val failed = awaitRange(app, fixture, target, allowError = true)
        assertEquals("媒体列表已变化，请重新刷新", failed.error)
        assertEquals("不能发布旧首片40条配新总数20的混合快照", previous.items, failed.items)
        assertEquals(previous.total, failed.total)
        assertEquals(previous.nextIndex, failed.nextIndex)
        assertEquals(listOf(0, PAGE_SIZE), fixture.rangeStarts(target.id, before))

        onMain { app.changeBrowserQuery(target.id, original.copy(sort = LibrarySort.TITLE, descending = false)) }
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = 20, total = 20)
        val retry = fixture.blockRangeFragment(target.id, startIndex = 0)
        onMain {
            app.changeBrowserQuery(target.id, original)
            assertTrue("不一致快照不能进入首屏缓存，返回原查询必须重新请求",
                rangeContent(app.state.value, target).loading)
        }
        retry.awaitRequest()
        retry.release()
        assertRange(awaitRange(app, fixture, target), target, revision = 1, count = 20, total = 20)
        assertEquals("稳定的新总数允许重试成功", listOf(0, PAGE_SIZE, 0, 0), fixture.rangeStarts(target.id, before))
    }

    private fun assertAtomicRangeRefresh(app: AppViewModel, fixture: BrowserFixture, target: RangeTarget) {
        val previous = loadReadRange(app, fixture, target)
        fixture.replaceRange(revision = 1)
        val before = fixture.rangeRequests(target.id).size
        val second = fixture.blockRangeFragment(target.id, startIndex = PAGE_SIZE)
        onMain {
            refreshRange(app, target)
            assertTrue(rangeContent(app.state.value, target).loading)
            assertEquals(previous.items, rangeContent(app.state.value, target).items)
        }
        second.awaitRequestBeforeRefreshEnds(app, fixture, target)
        val pending = rangeContent(app.state.value, target)
        assertTrue("后续分片在途时整轮刷新仍为 busy", pending.loading)
        assertEquals("第一分片不能提前替换已浏览范围", previous.items, pending.items)
        assertEquals(previous.total, pending.total)
        assertEquals(previous.nextIndex, pending.nextIndex)
        second.release()
        val refreshed = awaitRange(app, fixture, target)
        assertRange(refreshed, target, revision = 1, count = target.readCount, total = RANGE_ITEM_COUNT)
        assertEquals((0 until target.readCount step PAGE_SIZE).toList(), fixture.rangeStarts(target.id, before))
        assertEquals("刷新仍按旧游标范围请求，不能随追加批量一起改变",
            (0 until target.readCount step PAGE_SIZE).map { (target.readCount - it).coerceIn(PAGE_SIZE, APPEND_PAGE_SIZE).toString() },
            fixture.rangeRequests(target.id).drop(before).map { it.query["limit"] })

        onMain { appendRange(app, target) }
        val appended = awaitRange(app, fixture, target)
        assertRange(appended, target, revision = 1, count = target.readCount + PAGE_SIZE, total = RANGE_ITEM_COUNT)
        assertEquals("刷新后追加必须从保留范围的末端继续", target.readCount,
            fixture.rangeStarts(target.id, before).last())
    }

    private fun assertRangeRefreshFailure(app: AppViewModel, fixture: BrowserFixture, target: RangeTarget) {
        val previous = loadReadRange(app, fixture, target)
        fixture.replaceRange(revision = 1)
        val before = fixture.rangeRequests(target.id).size
        val second = fixture.blockRangeFragment(target.id, startIndex = PAGE_SIZE, fail = true)
        onMain { refreshRange(app, target) }
        second.awaitRequestBeforeRefreshEnds(app, fixture, target)
        assertEquals(previous.items, rangeContent(app.state.value, target).items)
        second.release()
        val failed = awaitRange(app, fixture, target, allowError = true)
        assertTrue("后续分片失败必须可见", failed.error != null)
        assertEquals("不能留下新旧分片混合的范围", previous.items, failed.items)
        assertEquals(previous.total, failed.total)
        assertEquals(previous.nextIndex, failed.nextIndex)
        assertFalse(failed.loading)
        assertFalse(failed.loadingMore)
        assertFalse("范围刷新失败不是追加失败", failed.retryAppend)
        assertEquals(listOf(0, PAGE_SIZE), fixture.rangeStarts(target.id, before))

        val retryStart = fixture.rangeRequests(target.id).size
        onMain { refreshRange(app, target) }
        val recovered = awaitRange(app, fixture, target)
        assertRange(recovered, target, revision = 1, count = target.readCount, total = RANGE_ITEM_COUNT)
        assertEquals("失败后重试必须恢复整个旧范围", (0 until target.readCount step PAGE_SIZE).toList(),
            fixture.rangeStarts(target.id, retryStart))
    }

    private fun assertReducedRangeRefresh(app: AppViewModel, fixture: BrowserFixture, target: RangeTarget) {
        val previous = loadReadRange(app, fixture, target)
        val reducedTotal = PAGE_SIZE + 15
        fixture.replaceRange(revision = 1, total = reducedTotal)
        val before = fixture.rangeRequests(target.id).size
        val second = fixture.blockRangeFragment(target.id, startIndex = PAGE_SIZE)
        onMain { refreshRange(app, target) }
        second.awaitRequestBeforeRefreshEnds(app, fixture, target)
        assertEquals(previous.items, rangeContent(app.state.value, target).items)
        second.release()
        val reduced = awaitRange(app, fixture, target)
        assertRange(reduced, target, revision = 1, count = reducedTotal, total = reducedTotal)
        assertEquals("新总数小于旧范围时不能继续请求旧末页", listOf(0, PAGE_SIZE),
            fixture.rangeStarts(target.id, before))
        val afterRefresh = fixture.rangeRequests(target.id).size
        onMain {
            appendRange(app, target)
            assertFalse(rangeContent(app.state.value, target).loadingMore)
        }
        assertEquals("已到新总数不能追加空页", afterRefresh, fixture.rangeRequests(target.id).size)
    }

    private fun loadReadRange(app: AppViewModel, fixture: BrowserFixture, target: RangeTarget): ContentState {
        onMain {
            if (target == RangeTarget.SEASON) app.loadFolder(target.id)
            else app.configureBrowser(target.id, target.id, LibraryQuery(itemTypes = setOf("Movie")))
        }
        var page = awaitRange(app, fixture, target)
        assertRange(page, target, revision = 0, count = PAGE_SIZE, total = RANGE_ITEM_COUNT)
        for (count in PAGE_SIZE * 2..target.readCount step PAGE_SIZE) {
            onMain { appendRange(app, target) }
            page = awaitRange(app, fixture, target)
            assertRange(page, target, revision = 0, count = count, total = RANGE_ITEM_COUNT)
        }
        assertEquals((0 until target.readCount step PAGE_SIZE).toList(), fixture.rangeStarts(target.id))
        val appendLimit = if (target == RangeTarget.SEASON) PAGE_SIZE else APPEND_PAGE_SIZE
        assertEquals("媒体库追加100条，季集追加仍为40条；两者首读都为40条",
            listOf(PAGE_SIZE.toString()) + List(target.readCount / PAGE_SIZE - 1) { appendLimit.toString() },
            fixture.rangeRequests(target.id).map { it.query["limit"] })
        return page
    }

    private fun refreshRange(app: AppViewModel, target: RangeTarget) {
        if (target == RangeTarget.SEASON) app.loadFolder(target.id) else app.refreshBrowser(target.id)
    }

    private fun appendRange(app: AppViewModel, target: RangeTarget) {
        if (target == RangeTarget.SEASON) app.loadFolder(target.id, append = true)
        else app.loadBrowser(target.id, append = true)
    }

    private fun awaitRange(app: AppViewModel, fixture: BrowserFixture, target: RangeTarget, allowError: Boolean = false): ContentState =
        rangeContent(awaitState(app, fixture, "${target.id} 范围读取必须结束") { state ->
            val page = if (target == RangeTarget.SEASON) state.folders[target.id] else state.browsers[target.id]?.content
            page != null && !page.loading && !page.loadingMore && run {
                if (!allowError) page.error?.let { throw AssertionError("范围读取失败：$it") }
                target == RangeTarget.SEASON || state.browsers[target.id]?.filtersLoading == false
            }
        }, target)

    private fun assertRange(page: ContentState, target: RangeTarget, revision: Int, count: Int, total: Int) {
        assertEquals((0 until count).map { "${target.id}/$it" }, page.items.map { it.id })
        assertEquals((0 until count).map { "revision-$revision/item-$it" }, page.items.map { it.name })
        assertTrue(page.items.all { it.type == if (target == RangeTarget.SEASON) "Episode" else "Movie" })
        assertEquals(total, page.total)
        assertEquals(count, page.nextIndex)
        assertFalse(page.loading)
        assertFalse(page.loadingMore)
        assertNull(page.error)
    }

    private enum class RangeTarget(val id: String, val readCount: Int) {
        LIBRARY("cache-range-library", 80),
        SEASON("cache-range-season", 120),
    }

    private fun rangeContent(state: AppState, target: RangeTarget): ContentState =
        if (target == RangeTarget.SEASON) state.folders.getValue(target.id) else state.browsers.getValue(target.id).content

    private fun withApp(block: (AppViewModel, BrowserFixture) -> Unit) =
        withRecreatedApp { app, fixture, _ -> block(app, fixture) }

    private fun withRecreatedApp(block: (AppViewModel, BrowserFixture, () -> AppViewModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "浏览缓存回归只允许独立 verification 包" }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var app: AppViewModel? = null
        clearSyntheticStorage(context)
        val fixture = BrowserFixture()
        try {
            lateinit var model: AppViewModel
            onMain {
                model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java]
                app = model
                model.login(fixture.baseUrl, FIXTURE_USER, FIXTURE_PASSWORD, allowHttp = true)
            }
            awaitState(model, fixture, "合成账号登录与首页加载必须完成") {
                it.session?.serverId == FIXTURE_SERVER_ID && it.home.loaded && !it.home.loading
            }
            block(model, fixture) {
                lateinit var restored: AppViewModel
                onMain {
                    // 模拟进程重建，只清掉 ViewModel；持久会话、资料和磁盘快照均继续保留。
                    owner.viewModelStore.clear()
                    restored = ViewModelProvider(owner,
                        ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java]
                    app = restored
                }
                restored
            }
            fixture.assertHealthy()
        } finally {
            fixture.close()
            onMain { app?.logout(); owner.viewModelStore.clear() }
            clearSyntheticStorage(context)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

    private fun awaitRestoredSession(app: AppViewModel, fixture: BrowserFixture) =
        awaitState(app, fixture, "保留的会话应完成服务器验证并恢复首页") {
            !it.restoringSession && it.session?.serverId == FIXTURE_SERVER_ID && it.home.loaded && !it.home.loading &&
                fixture.userValidationRequests() > 0
        }

    private fun awaitDiskSnapshot(app: AppViewModel, fixture: BrowserFixture, key: String, parentId: String?) {
        val state = app.state.value
        val session = checkNotNull(state.session)
        val browser = state.browsers.getValue(key)
        val pageKey = BrowserPageKey(ProfileHomeKey.of(checkNotNull(state.currentProfileId), session), key, parentId, browser.query)
        val expected = MediaPage(browser.content.items.take(PAGE_SIZE), browser.content.total)
        val snapshots = BrowserSnapshotStore(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            runBlocking { withTimeout(WAIT_MS) {
                while (snapshots.load(session, pageKey) != expected) {
                    fixture.assertHealthy()
                    delay(10)
                }
            } }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("首屏必须实际落盘后才能销毁 ViewModel", failure)
        }
    }

    private fun awaitBrowser(
        app: AppViewModel,
        fixture: BrowserFixture,
        key: String,
        predicate: (BrowserUiState) -> Boolean = { true },
    ): BrowserUiState = awaitState(app, fixture, "浏览器 $key 的列表与筛选必须完成") { state ->
        state.browsers[key]?.let { browser ->
            if (!browser.content.loading && !browser.content.loadingMore) {
                browser.content.error?.let { throw AssertionError("列表请求失败：$it") }
            }
            if (!browser.filtersLoading) browser.filtersError?.let { throw AssertionError("筛选请求失败：$it") }
            !browser.content.loading && !browser.content.loadingMore && !browser.filtersLoading &&
                browser.filterOptions != null && predicate(browser)
        } == true
    }.browsers.getValue(key)

    private fun awaitFavorites(
        app: AppViewModel,
        fixture: BrowserFixture,
        predicate: (DiscoveryState) -> Boolean = { true },
    ): DiscoveryState = awaitState(app, fixture, "收藏的独立分类必须全部加载完成") { state ->
        val favorites = state.favorites
        favorites.error?.let { throw AssertionError("收藏请求失败：$it") }
        favorites.requested && favorites.sections.keys == favoriteDiscoveryCategories.toSet() &&
            favorites.sections.values.all { !it.loading && !it.loadingMore && it.queryKey == "favorites" } && predicate(favorites)
    }.favorites

    private fun awaitState(app: AppViewModel, fixture: BrowserFixture, message: String, predicate: (AppState) -> Boolean): AppState = try {
        runBlocking {
            withTimeout(WAIT_MS) {
                app.state.first { state ->
                    fixture.assertHealthy()
                    state.loginError?.let { throw AssertionError("$message；登录失败：$it") }
                    state.home.error?.let { throw AssertionError("$message；首页失败：$it") }
                    predicate(state)
                }
            }
        }
    } catch (failure: TimeoutCancellationException) {
        throw AssertionError("$message；browsers=${app.state.value.browsers.keys}", failure)
    }

    private fun assertCached(app: AppViewModel, key: String, expected: BrowserUiState) {
        val actual = app.state.value.browsers.getValue(key)
        assertEquals("返回时必须完整保留列表、用户查询和筛选", expected, actual)
        assertFalse("命中缓存不能重新加载列表", actual.content.loading)
        assertFalse("命中缓存不能重新追加分页", actual.content.loadingMore)
        assertFalse("命中缓存不能重新加载筛选", actual.filtersLoading)
    }

    private fun assertCachedFavorites(app: AppViewModel, expected: DiscoveryState) {
        val actual = app.state.value.favorites
        assertEquals("返回收藏时必须完整保留每个分类的内容与游标", expected, actual)
        assertFalse("命中收藏缓存不能重新加载分类", actual.loading)
        assertTrue("命中收藏缓存不能重新追加分页", actual.sections.values.none { it.loadingMore })
    }

    private fun clearSyntheticStorage(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        val store = SessionStore(context)
        store.loadProfiles().forEach {
            BrowserSnapshotStore(context).removeProfile(it.id)
            HomeSnapshotStore(context).removeProfile(it.id)
            store.removeProfile(it.id)
        }
        store.clear()
        check(context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private data class Counts(val items: Int, val filters: Int, val favoriteGroups: Int = 0) {
        fun plus(items: Int = 0, filters: Int = 0, favoriteGroups: Int = 0) =
            Counts(this.items + items, this.filters + filters, this.favoriteGroups + favoriteGroups)
    }

    private data class RequestRecord(val method: String, val path: String, val query: Map<String, String>) {
        // 按查询用途计数，不假定刷新整个已读范围时仍请求固定的 limit。
        val isBrowserItems: Boolean get() = path == "/Items" && query["ids"] == null && !isFavoriteGroup ||
            path == "/UserItems/Resume" && query["parentId"] != null && query["enableTotalRecordCount"] == "true"
        val isFavoriteGroup: Boolean get() = query["isFavorite"] == "true" &&
            (path == "/Persons" || path == "/Items" && query["limit"] == "24" && query["ids"] == null)
    }

    private class ResponseGate(val captureResponse: Boolean = false, val allowClientDisconnect: Boolean = false) {
        private val requested = CompletableDeferred<Unit>()
        private val responded = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        private val client = AtomicReference<Socket?>()
        fun attachClient(socket: Socket) { client.set(socket) }
        fun blockResponse() {
            requested.complete(Unit)
            check(released.await(WAIT_MS * 3, TimeUnit.MILLISECONDS)) { "浏览 fixture 响应未释放" }
        }
        fun awaitRequest() = runBlocking { withTimeout(WAIT_MS) { requested.await() } }
        fun awaitResponse() = runBlocking { withTimeout(WAIT_MS) { responded.await() } }
        fun responseFinished() { responded.complete(Unit) }
        fun awaitClientDisconnect() {
            val socket = checkNotNull(client.get())
            val disconnected = try { socket.getInputStream().read() == -1 } catch (_: SocketException) { true }
            assertTrue("成功的显式写入必须取消旧聚合请求，不能继续等待旧响应", disconnected)
        }
        fun awaitRequestBeforeRefreshEnds(app: AppViewModel, fixture: BrowserFixture, target: RangeTarget) = runBlocking {
            withTimeout(WAIT_MS) {
                val finished = async {
                    app.state.first { state ->
                        fixture.assertHealthy()
                        val page = if (target == RangeTarget.SEASON) state.folders[target.id]
                            else state.browsers[target.id]?.content
                        page != null && !page.loading
                    }
                }
                try {
                    select<Unit> {
                        requested.onAwait { }
                        finished.onAwait { state ->
                            val page = if (target == RangeTarget.SEASON) state.folders.getValue(target.id)
                                else state.browsers.getValue(target.id).content
                            throw AssertionError("${target.id} 尚未读取第二分片就结束刷新；" +
                                "items=${page.items.size}, nextIndex=${page.nextIndex}, error=${page.error}")
                        }
                    }
                } finally { finished.cancel() }
            }
        }
        fun release() = released.countDown()
    }

    private data class RangeData(val revision: Int, val total: Int, val pageSize: Int = PAGE_SIZE)
    private data class ControlledFragment(val parentId: String, val startIndex: Int, val fail: Boolean, val gate: ResponseGate)

    private class BrowserFixture : Closeable {
        val failNextFilters = AtomicBoolean()
        val failNextAppend = AtomicBoolean()
        val canDownload = AtomicBoolean()
        private val favoriteItems = ConcurrentHashMap.newKeySet<String>()
        private val playedItems = ConcurrentHashMap.newKeySet<String>()
        private val nextItemsGate = AtomicReference<ResponseGate?>()
        private val nextFiltersGate = AtomicReference<ResponseGate?>()
        private val itemGates = ConcurrentHashMap<String, ResponseGate>()
        private val rangeData = AtomicReference(RangeData(revision = 0, total = RANGE_ITEM_COUNT))
        private val controlledFragments = ConcurrentLinkedQueue<ControlledFragment>()
        private val allGates = ConcurrentLinkedQueue<ResponseGate>()
        private val records = ConcurrentLinkedQueue<RequestRecord>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-cache-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val acceptThread = Thread({
            while (!closed.get()) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    executor.execute { serve(socket) }
                } catch (failure: SocketException) {
                    if (!closed.get()) failures.add(failure)
                }
            }
        }, "pathfin-cache-accept").apply { isDaemon = true; start() }

        fun counts() = Counts(records.count { it.isBrowserItems }, records.count { it.path == "/Items/Filters" }, records.count { it.isFavoriteGroup })
        fun assertCounts(expected: Counts) = assertEquals("浏览/筛选/收藏分类请求数不符合缓存契约", expected, counts())
        fun assertFavoriteRequests(expected: Map<String, Int>) {
            val favorites = records.filter { it.isFavoriteGroup }
            assertEquals("演员收藏必须按预期次数请求完整集合", expected.getValue("Person"), favorites.count { it.path == "/Persons" })
            assertEquals("其余七类收藏必须分别按预期次数查询", expected - "Person",
                favorites.filter { it.path == "/Items" }.groupingBy { it.query["includeItemTypes"] }.eachCount())
        }
        fun lastItems() = records.last { it.isBrowserItems }
        fun lastFilters() = records.last { it.path == "/Items/Filters" }
        fun userValidationRequests() = records.count { it.path == "/Users/Me" }
        fun rangeRequests(parentId: String) = records.filter { it.isBrowserItems && it.query["parentId"] == parentId }
        fun rangeStarts(parentId: String, skip: Int = 0) = rangeRequests(parentId).drop(skip).map { it.query.getValue("startIndex").toInt() }
        fun replaceRange(revision: Int, total: Int = RANGE_ITEM_COUNT, pageSize: Int = PAGE_SIZE) {
            rangeData.set(RangeData(revision, total, pageSize))
        }
        fun blockRangeFragment(parentId: String, startIndex: Int, fail: Boolean = false) = ResponseGate().also { gate ->
            allGates.add(gate)
            controlledFragments.add(ControlledFragment(parentId, startIndex, fail, gate))
        }
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("浏览 fixture 执行失败", it) } }
        fun blockNextItems(captureResponse: Boolean = false, allowClientDisconnect: Boolean = false) =
            ResponseGate(captureResponse, allowClientDisconnect).also {
                check(nextItemsGate.compareAndSet(null, it)); allGates.add(it)
            }
        val libraryAGenres = AtomicReference(listOf("CacheGenre"))
        fun blockNextFilters(captureResponse: Boolean = false, allowClientDisconnect: Boolean = false) =
            ResponseGate(captureResponse, allowClientDisconnect).also { check(nextFiltersGate.compareAndSet(null, it)); allGates.add(it) }
        fun blockItem(id: String, captureResponse: Boolean, allowClientDisconnect: Boolean) =
            ResponseGate(captureResponse, allowClientDisconnect).also {
                check(itemGates.putIfAbsent(id, it) == null); allGates.add(it)
            }

        private fun serve(socket: Socket) {
            var responseGate: ResponseGate? = null
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine() ?: return
                    val parts = requestLine.split(' ')
                    check(parts.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: throw EOFException("fixture 请求头未完成")
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        check(separator > 0)
                        headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..32_768)
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        if (read < 0) throw EOFException("fixture 请求体未完成")
                        offset += read
                    }
                    val uri = URI(parts[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { parameter ->
                        val pair = parameter.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    val request = RequestRecord(parts[0], uri.path, query)
                    records.add(request)
                    val itemsGate = when {
                        request.isBrowserItems -> nextItemsGate.getAndSet(null)
                        request.path == "/Items" -> request.query["ids"]?.let(itemGates::remove)
                        request.path == "/Items/Filters" -> nextFiltersGate.getAndSet(null)
                        else -> null
                    }
                    responseGate = itemsGate
                    itemsGate?.attachClient(connection)
                    // 可在写入前拍下旧 UserData，避免释放门控后才现查新状态而产生假阳性。
                    val captured = if (itemsGate?.captureResponse == true) response(request, String(body)) else null
                    itemsGate?.blockResponse()
                    val fragment = controlledFragments.firstOrNull {
                        request.isBrowserItems && request.query["parentId"] == it.parentId &&
                            request.query["startIndex"]?.toIntOrNull() == it.startIndex
                    }?.takeIf { controlledFragments.remove(it) }
                    fragment?.gate?.blockResponse()
                    val failFilters = request.path == "/Items/Filters" && failNextFilters.compareAndSet(true, false)
                    val failAppend = request.isBrowserItems && request.query["parentId"] == PAGED_LIBRARY &&
                        (request.query["startIndex"]?.toIntOrNull() ?: 0) > 0 && failNextAppend.compareAndSet(true, false)
                    val response = when {
                        failFilters -> JSONObject().put("Error", "synthetic filter failure")
                        failAppend -> JSONObject().put("Error", "synthetic append failure")
                        fragment?.fail == true -> JSONObject().put("Error", "synthetic later refresh fragment failure")
                        else -> captured ?: response(request, String(body))
                    }
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    val status = if (failFilters || failAppend || fragment?.fail == true) "500 Internal Server Error" else "200 OK"
                    val output = connection.getOutputStream()
                    output.write(("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            } catch (failure: Throwable) {
                if (!closed.get() && !(responseGate?.allowClientDisconnect == true && failure is IOException)) failures.add(failure)
            } finally {
                responseGate?.responseFinished()
                sockets.remove(socket)
            }
        }

        private fun response(request: RequestRecord, body: String): Any = when (request.path) {
            "/Users/AuthenticateByName" -> {
                check(request.method == "POST")
                val credentials = JSONObject(body)
                check(credentials.getString("Username") == FIXTURE_USER && credentials.getString("Pw") == FIXTURE_PASSWORD)
                JSONObject().put("AccessToken", "cache-token").put("ServerId", FIXTURE_SERVER_ID)
                    .put("User", user())
            }
            "/Users/Me" -> user()
            "/System/Info/Public" -> JSONObject().put("Id", FIXTURE_SERVER_ID).put("ServerName", "浏览缓存测试").put("Version", "12.3.0")
            "/UserViews" -> page(library(LIBRARY_A), library(LIBRARY_B), library(EMPTY_LIBRARY))
            "/Items/Latest" -> {
                check(request.query["groupItems"] == "true")
                items(request.query).getJSONArray("Items")
            }
            "/UserItems/Resume" -> {
                if (request.query["parentId"] != null) {
                    check(request.query["enableTotalRecordCount"] == "true")
                    check(request.query["startIndex"] != null)
                    check(request.query["includeItemTypes"] == "Movie")
                    check(request.query["filters"] == null && request.query["sortBy"] == null)
                } else {
                    check(request.query["enableTotalRecordCount"] == "false")
                    check(request.query["mediaTypes"] == "Video")
                }
                page()
            }
            "/Items" -> items(request.query)
            "/Persons" -> {
                check(request.query["isFavorite"] == "true")
                check(request.query["limit"] == null && request.query["startIndex"] == null)
                check(request.query["excludePersonTypes"] == "Artist,AlbumArtist")
                page()
            }
            "/Items/Filters" -> {
                val genres = when (request.query["parentId"]) { LIBRARY_A -> libraryAGenres.get(); LIBRARY_B -> listOf("ParentBGenre"); else -> emptyList() }
                JSONObject().put("Genres", JSONArray(genres)).put("Tags", JSONArray())
                    .put("Years", JSONArray()).put("OfficialRatings", JSONArray())
            }
            "/Shows/NextUp", "/Items/$MOVIE_A/Similar", "/Items/$EPISODE_A/Similar" -> page()
            else -> when {
                request.path.startsWith("/UserFavoriteItems/") -> {
                    check(request.method in setOf("POST", "DELETE"))
                    val id = request.path.substringAfter("/UserFavoriteItems/")
                    if (request.method == "POST") favoriteItems.add(id) else favoriteItems.remove(id)
                    userData(id)
                }
                request.path.startsWith("/UserPlayedItems/") -> {
                    check(request.method in setOf("POST", "DELETE"))
                    val id = request.path.substringAfter("/UserPlayedItems/")
                    if (request.method == "POST") playedItems.add(id) else playedItems.remove(id)
                    userData(id)
                }
                else -> error("浏览 fixture 未定义路由：${request.method} ${request.path}")
            }
        }

        private fun items(query: Map<String, String>): JSONObject {
            if (query["ids"] == MOVIE_A) return page(movie(MOVIE_A))
            if (query["ids"] == SERIES_A) return page(series())
            if (query["ids"] == EPISODE_A) return page(episode())
            if ("IsResumable" in query["filters"].orEmpty().split(',')) return page()
            if (query["parentId"] == PARENT_LIBRARY) return page(series())
            val range = RangeTarget.values().firstOrNull { it.id == query["parentId"] }
            if (range != null) {
                val data = rangeData.get()
                val start = query.getValue("startIndex").toInt()
                // 真实 HTTP 分片：服务端可少于请求 limit，原始游标仍按实际返回数量前进。
                val limit = minOf(query.getValue("limit").toInt(), data.pageSize)
                check(start >= 0 && limit > 0)
                val items = (start until minOf(start + limit, data.total)).map { index ->
                    movie("${range.id}/$index").put("Name", "revision-${data.revision}/item-$index")
                        .put("Type", if (range == RangeTarget.SEASON) "Episode" else "Movie").also { item ->
                            if (range == RangeTarget.SEASON) item.put("SeasonId", range.id).put("SeriesId", "range-series")
                                .put("IndexNumber", index + 1).put("ParentIndexNumber", 1)
                        }
                }
                return JSONObject().put("Items", JSONArray(items)).put("TotalRecordCount", data.total)
            }
            if (query["parentId"] == PAGED_LIBRARY) {
                val start = query["startIndex"]?.toInt() ?: 0
                val limit = query["limit"]?.toInt() ?: PAGE_SIZE
                val items = (0 until PAGED_ITEM_COUNT).drop(start).take(limit).map { movie("cache-paged-$it") }
                return JSONObject().put("Items", JSONArray(items)).put("TotalRecordCount", PAGED_ITEM_COUNT)
            }
            var items = when (query["parentId"]) {
                LIBRARY_A -> listOf(movie(MOVIE_A))
                LIBRARY_B -> listOf(movie(MOVIE_B))
                EMPTY_LIBRARY -> emptyList()
                else -> listOf(movie(MOVIE_A), movie(MOVIE_B))
            }
            query["isFavorite"]?.let { value -> items = items.filter { it.getJSONObject("UserData").getBoolean("IsFavorite") == value.toBoolean() } }
            query["isPlayed"]?.let { value -> items = items.filter { it.getJSONObject("UserData").getBoolean("Played") == value.toBoolean() } }
            query["includeItemTypes"]?.let { types -> if ("Movie" !in types.split(',')) items = emptyList() }
            return page(*items.toTypedArray())
        }

        private fun user() = JSONObject().put("Id", FIXTURE_USER).put("Name", FIXTURE_USER)
            .put("Policy", JSONObject().put("EnableContentDownloading", canDownload.get()))
        private fun userData(id: String) = JSONObject().put("IsFavorite", id in favoriteItems).put("Played", id in playedItems)
            .put("PlaybackPositionTicks", 0).apply {
                if (id == SERIES_A) put("UnplayedItemCount", if (id in playedItems) 0 else 5)
            }
        private fun series() = movie(SERIES_A).put("Type", "Series").put("IsFolder", true)
        private fun episode() = movie(EPISODE_A).put("Type", "Episode").put("SeriesId", SERIES_A)
            .put("IndexNumber", 1).put("ParentIndexNumber", 1)
        private fun movie(id: String) = JSONObject().put("Id", id).put("Name", id).put("Type", "Movie")
            .put("Genres", JSONArray(listOf(if (id == MOVIE_A) "CacheGenre" else "ParentBGenre"))).put("ImageTags", JSONObject())
            .put("UserData", userData(id))
        private fun library(id: String) = JSONObject().put("Id", id).put("Name", id).put("Type", "CollectionFolder").put("IsFolder", true)
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size)

        override fun close() {
            closed.set(true)
            allGates.forEach { it.release() }
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            acceptThread.join(2_000)
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val FIXTURE_USER = "cache-user"
        const val FIXTURE_PASSWORD = "cache-password"
        const val FIXTURE_SERVER_ID = "cache-server"
        const val LIBRARY_A = "cache-library-a"
        const val LIBRARY_B = "cache-library-b"
        const val EMPTY_LIBRARY = "cache-library-empty"
        const val PAGED_LIBRARY = "cache-library-paged"
        const val PARENT_LIBRARY = "cache-library-playback-parent"
        const val PAGED_ITEM_COUNT = 45
        const val RANGE_ITEM_COUNT = 160
        const val PAGE_SIZE = 40
        const val APPEND_PAGE_SIZE = 100
        const val MOVIE_A = "cache-movie-a"
        const val MOVIE_B = "cache-movie-b"
        const val SERIES_A = "cache-series-a"
        const val EPISODE_A = "cache-episode-a"
        const val WAIT_MS = 20_000L
    }
}

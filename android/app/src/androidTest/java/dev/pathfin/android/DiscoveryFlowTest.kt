package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.favoriteDiscoveryCategories
import dev.pathfin.android.domain.searchDiscoveryCategories
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.DiscoveryState
import dev.pathfin.android.ui.Route
import java.io.Closeable
import java.io.EOFException
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 只在独立 verification 包运行；搜索、收藏、账号和竞态均经过真实 VM 与自有回环 HTTP。 */
@RunWith(AndroidJUnit4::class)
class DiscoveryFlowTest {
    @Test fun independentGroupsKeepArrivalOrderReuseFiltersAndPaginateTheirOwnCursors() = withApp { app, fixture ->
        main { app.selectTab(Route.Search) }
        val suggestions = await(app, fixture, "推荐搜索完成") { it.search.suggestionsLoaded }.search.suggestions
        assertEquals(listOf("推荐甲", "推荐乙"), suggestions.items.map { it.name })
        val recommendation = fixture.suggestionRequests().single()
        assertEquals("false", recommendation.query["enableImages"])
        assertEquals("IsFavoriteOrLiked,Random", recommendation.query["sortBy"])

        fixture.block(PageKey(ALPHA_ID, "paged", DiscoveryCategory.MOVIES)).use { movies ->
            main { app.search("  paged  ") }
            movies.awaitRequest()
            val early = await(app, fixture, "慢电影不能阻塞其余分类") { state ->
                state.search.results.sections.size == searchDiscoveryCategories.size &&
                    state.search.results.sections.all { (category, page) -> category == DiscoveryCategory.MOVIES || !page.loading }
            }.search.results
            assertTrue(early.sections.getValue(DiscoveryCategory.MOVIES).loading)
            assertEquals(2, early.sections.getValue(DiscoveryCategory.SERIES).items.size)
            assertEquals(setOf(DiscoveryCategory.SERIES, DiscoveryCategory.PEOPLE, DiscoveryCategory.ARTISTS), early.order.toSet())
            assertFalse(DiscoveryCategory.MOVIES in early.order)
            movies.release()
            val completed = awaitSearch(app, fixture, "paged")
            assertEquals("迟到电影只能追加到已显示分组末尾", early.order + DiscoveryCategory.MOVIES, completed.order)
        }
        val first = app.state.value.search.results
        assertEquals(24, first.sections.getValue(DiscoveryCategory.MOVIES).items.size)
        assertEquals(27, first.sections.getValue(DiscoveryCategory.MOVIES).total)
        assertEquals(24, first.sections.getValue(DiscoveryCategory.ARTISTS).nextIndex)
        val firstRequests = fixture.discoveryRequests()
        assertEquals(searchDiscoveryCategories.size, firstRequests.size)
        assertEquals(searchDiscoveryCategories.toSet(), firstRequests.map { it.category }.toSet())
        firstRequests.forEach { request ->
            assertEquals(ALPHA_ID, request.userId)
            assertEquals("paged", request.query["searchTerm"])
            assertFalse("parentId" in request.query)
            assertFalse("isFavorite" in request.query)
            if (request.category == DiscoveryCategory.PEOPLE) {
                assertEquals("/Persons", request.path)
                assertEquals("Artist,AlbumArtist", request.query["excludePersonTypes"])
                assertFalse("limit" in request.query)
                assertFalse("startIndex" in request.query)
            } else {
                assertEquals("24", request.query["limit"])
                assertEquals("0", request.query["startIndex"])
                if (request.category == DiscoveryCategory.ARTISTS) assertEquals("/Artists", request.path)
                else {
                    assertEquals("/Items", request.path)
                    assertEquals(request.category?.itemType, request.query["includeItemTypes"])
                }
            }
        }
        main {
            app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.SERIES)
            app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.MOVIES)
        }
        assertEquals("切换已加载分类复用整组结果", first, app.state.value.search.results)
        assertEquals(firstRequests.size, fixture.discoveryRequests().size)
        val source = app.state.value.routes
        val selected = app.state.value.search.categories
        main { app.openDiscovery(category = DiscoveryCategory.MOVIES, favorite = false) }
        val more = app.state.value.route as Route.Discovery
        assertEquals(DiscoveryCategory.MOVIES, more.category)
        assertFalse(more.favorite)
        assertEquals("paged", more.query)
        assertEquals(source + more, app.state.value.routes)
        assertEquals("更多复用已读取首屏，不能再次请求 0 游标", firstRequests.size, fixture.discoveryRequests().size)
        assertNotEquals("不同词的完整列表应有各自滚动状态", more.key, more.copy(query = "another").key)
        assertNotEquals("收藏完整列表不能共享搜索滚动状态", more.key, more.copy(favorite = true, query = "").key)

        fixture.block(PageKey(ALPHA_ID, "paged", DiscoveryCategory.MOVIES, 24)).use { append ->
            main { repeat(3) { app.loadMoreDiscovery(DiscoveryCategory.MOVIES, favorite = false) } }
            append.awaitRequest()
            val loading = app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES)
            assertTrue(loading.loadingMore)
            assertEquals(first.sections.getValue(DiscoveryCategory.MOVIES).items, loading.items)
            assertEquals(listOf("0", "24"), fixture.requestsFor("paged", DiscoveryCategory.MOVIES).map { it.query["startIndex"] })
            append.release()
            await(app, fixture, "电影追加完成") { it.search.results.sections[DiscoveryCategory.MOVIES]?.items?.size == 27 }
        }
        val movie = app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES)
        assertEquals(27, movie.nextIndex)
        assertEquals(27, movie.items.map { it.id }.distinct().size)
        val requestsBeforeTerminal = fixture.discoveryRequests().size
        main { app.loadMoreDiscovery(DiscoveryCategory.MOVIES, favorite = false); app.loadMoreDiscovery(DiscoveryCategory.PEOPLE, favorite = false) }
        assertEquals("终页与演员完整集合不能继续发送分页", requestsBeforeTerminal, fixture.discoveryRequests().size)
        main { app.back() }
        assertEquals(source, app.state.value.routes)
        assertEquals(Route.Search, app.state.value.route)
        assertEquals("paged", app.state.value.search.query)
        assertEquals(selected, app.state.value.search.categories)
        assertEquals(movie, app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES))
        assertEquals(requestsBeforeTerminal, fixture.discoveryRequests().size)

        main { app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.ARTISTS) }
        fixture.fail(PageKey(ALPHA_ID, "paged", DiscoveryCategory.ARTISTS, 24), 500)
        main { app.loadMoreDiscovery(DiscoveryCategory.ARTISTS, favorite = false) }
        val failed = await(app, fixture, "歌手追加失败保留当前页") {
            it.search.results.sections[DiscoveryCategory.ARTISTS]?.let { page -> !page.loadingMore && page.error != null } == true
        }.search.results.sections.getValue(DiscoveryCategory.ARTISTS)
        assertEquals(24, failed.items.size)
        assertEquals(24, failed.nextIndex)
        val failedRequests = fixture.discoveryRequests().size
        main { repeat(3) { app.loadMoreDiscovery(DiscoveryCategory.ARTISTS, favorite = false) } }
        assertEquals("追加失败不得在底部反复请求", failedRequests, fixture.discoveryRequests().size)
        val order = app.state.value.search.results.order
        fixture.block(PageKey(ALPHA_ID, "paged", DiscoveryCategory.ARTISTS)).use { refresh ->
            main { app.refreshSearch() }
            refresh.awaitRequest()
            assertEquals(failed.items, app.state.value.search.results.sections.getValue(DiscoveryCategory.ARTISTS).items)
            assertEquals(order, app.state.value.search.results.order)
            refresh.release()
            awaitSearch(app, fixture, "paged")
        }
        main { app.loadMoreDiscovery(DiscoveryCategory.ARTISTS, favorite = false) }
        val recovered = await(app, fixture, "刷新后歌手续页恢复") {
            it.search.results.sections[DiscoveryCategory.ARTISTS]?.items?.size == 26
        }.search.results
        assertEquals(26, recovered.sections.getValue(DiscoveryCategory.ARTISTS).nextIndex)
        assertNull(recovered.sections.getValue(DiscoveryCategory.ARTISTS).error)
        assertEquals(order, recovered.order)
        assertEquals(movie, recovered.sections.getValue(DiscoveryCategory.MOVIES))
        main {
            app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.MOVIES)
            app.refreshDiscovery(category = DiscoveryCategory.MOVIES, favorite = false)
        }
        val refreshedMovies = awaitSearch(app, fixture, "paged")
        assertEquals("刷新必须保留已浏览的 27 项范围", movie.items, refreshedMovies.sections.getValue(DiscoveryCategory.MOVIES).items)
        assertEquals(27, refreshedMovies.sections.getValue(DiscoveryCategory.MOVIES).nextIndex)
        assertEquals(recovered.sections - DiscoveryCategory.MOVIES, refreshedMovies.sections - DiscoveryCategory.MOVIES)
        assertEquals(order, refreshedMovies.order)
    }

    @Test fun multipleMovieSeasonAndEpisodeFiltersToggleIndependentlyAndEmptySelectionReturnsToAll() = withApp { app, fixture ->
        val selected = listOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS, DiscoveryCategory.EPISODES)
        main {
            app.selectTab(Route.Search)
            selected.forEach(app::toggleSearchCategory)
            app.search("multi")
        }
        val first = awaitSearch(app, fixture, "multi", selected)
        assertEquals(selected.toSet(), app.state.value.search.categories)
        assertEquals(selected.toSet(), first.sections.keys)
        assertEquals(selected.toSet(), fixture.requestsFor("multi").map { it.category }.toSet())
        selected.forEach { category ->
            val item = first.sections.getValue(category).items.single()
            assertEquals(category.itemType, item.type)
            assertEquals(category.itemType, fixture.requestsFor("multi", category).single().query["includeItemTypes"])
        }
        val requests = fixture.requestsFor("multi").size
        main { app.toggleSearchCategory(DiscoveryCategory.EPISODES) }
        assertEquals(setOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS), app.state.value.search.categories)
        assertEquals("取消选择不丢已完成缓存", first, app.state.value.search.results)
        assertEquals(requests, fixture.requestsFor("multi").size)
        main { app.toggleSearchCategory(DiscoveryCategory.MOVIES) }
        assertEquals(setOf(DiscoveryCategory.SEASONS), app.state.value.search.categories)
        main { app.toggleSearchCategory(DiscoveryCategory.SEASONS) }
        val all = awaitSearch(app, fixture, "multi")
        assertTrue("移除最后一个筛选回到全部", app.state.value.search.categories.isEmpty())
        assertEquals(searchDiscoveryCategories.toSet(), all.sections.keys)
        selected.forEach { category -> assertEquals(first.sections.getValue(category), all.sections.getValue(category)) }
        assertEquals("回到全部只补齐尚未读取的组", searchDiscoveryCategories.size, fixture.requestsFor("multi").size)
        main {
            app.toggleSearchCategory(DiscoveryCategory.MOVIES)
            app.toggleSearchCategory(DiscoveryCategory.SERIES)
        }
        assertEquals(setOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SERIES), app.state.value.search.categories)
        main { app.toggleSearchCategory(null) }
        assertTrue(app.state.value.search.categories.isEmpty())
        assertEquals(all, app.state.value.search.results)
        assertEquals(searchDiscoveryCategories.size, fixture.requestsFor("multi").size)
    }

    @Test fun changingMultipleFiltersRetainsSelectedRequestsAndFreesUnselectedQueueSlots() = withApp { app, fixture ->
        val blockedCategories = listOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SERIES, DiscoveryCategory.COLLECTIONS)
        val blocked = blockedCategories.map { fixture.block(PageKey(ALPHA_ID, "priority", it)) }
        try {
            main { app.search("priority") }
            blocked.forEach { it.awaitRequest() }
            assertTrue(app.state.value.search.results.sections.getValue(DiscoveryCategory.ARTISTS).loading)
            main {
                app.toggleSearchCategory(DiscoveryCategory.MOVIES)
                app.toggleSearchCategory(DiscoveryCategory.ARTISTS)
            }
            val focused = awaitSearch(app, fixture, "priority", listOf(DiscoveryCategory.ARTISTS))
            assertEquals(setOf(DiscoveryCategory.MOVIES, DiscoveryCategory.ARTISTS), app.state.value.search.categories)
            assertEquals(listOf(DiscoveryCategory.ARTISTS), focused.order)
            assertEquals(1, focused.sections.getValue(DiscoveryCategory.ARTISTS).items.size)
            assertTrue("保留在多选集合的电影请求不得取消重读", focused.sections.getValue(DiscoveryCategory.MOVIES).loading)
            assertEquals(1, fixture.requestsFor("priority", DiscoveryCategory.MOVIES).size)
            blockedCategories.drop(1).forEach { category ->
                val cancelled = focused.sections.getValue(category)
                assertFalse(cancelled.loading)
                assertFalse(cancelled.loadingMore)
                assertNull("被取消的首读必须失效，不能把空内容当命中", cancelled.queryKey)
            }
            main { app.toggleSearchCategory(DiscoveryCategory.MOVIES) }
            assertEquals(setOf(DiscoveryCategory.ARTISTS), app.state.value.search.categories)
            assertFalse(app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES).loading)
            assertNull(app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES).queryKey)
            val artistRequests = fixture.requestsFor("priority", DiscoveryCategory.ARTISTS).size
            main { app.toggleSearchCategory(null) }
            val all = awaitSearch(app, fixture, "priority")
            assertTrue(app.state.value.search.categories.isEmpty())
            assertEquals(searchDiscoveryCategories.size, all.sections.values.count { it.items.size == 1 })
            assertEquals(focused.sections.getValue(DiscoveryCategory.ARTISTS), all.sections.getValue(DiscoveryCategory.ARTISTS))
            assertEquals("切回全部不得重新读取已完成歌手组", artistRequests, fixture.requestsFor("priority", DiscoveryCategory.ARTISTS).size)
            assertEquals(DiscoveryCategory.ARTISTS, all.order.first())
            assertEquals(searchDiscoveryCategories.toSet(), all.order.toSet())
            blocked.forEach { it.release(); it.awaitFinished() }
            main { assertEquals("已取消首读的迟到响应不得覆盖重读结果", all, app.state.value.search.results) }
        } finally { blocked.forEach { it.close() } }
    }

    @Test fun cancelledConnectionProbeLetsTheCachedAccountReloadSearchFavoritesAndSuggestions() = withApp { app, fixture ->
        main { app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.MOVIES); app.search("retained") }
        val first = awaitSearch(app, fixture, "retained", listOf(DiscoveryCategory.MOVIES))
        val profile = app.state.value.profiles.single { it.id == app.state.value.currentProfileId }
        fixture.block(PageKey(ALPHA_ID, "retained", DiscoveryCategory.MOVIES)).use { search ->
            fixture.block(PageKey(ALPHA_ID, "", DiscoveryCategory.MOVIES, favorite = true)).use { favorites ->
                main { app.refreshSearch(); app.selectTab(Route.Favorites) }
                search.awaitRequest(); favorites.awaitRequest()
                main { app.showServers(); app.beginAddServer(); app.probeServer(fixture.baseUrl, allowHttp = true) }
                await(app, fixture, "添加服务器探测完成") { !it.loginBusy && it.serverProbe != null }
                assertFalse(app.state.value.search.results.loading)
                assertFalse(app.state.value.favorites.loading)
                assertFalse(app.state.value.search.results.sections.values.any { it.loadingMore })
                assertFalse(app.state.value.favorites.sections.values.any { it.loadingMore })
                assertEquals(first.sections.getValue(DiscoveryCategory.MOVIES).items,
                    app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES).items)
                main { app.backFromConnect(); app.backFromConnect(); app.openServer(profile); app.selectTab(Route.Search) }
                val resumedSearch = awaitSearch(app, fixture, "retained", listOf(DiscoveryCategory.MOVIES))
                assertEquals(first.sections.getValue(DiscoveryCategory.MOVIES).items,
                    resumedSearch.sections.getValue(DiscoveryCategory.MOVIES).items)
                val searchVisit = app.state.value.searchVisitId
                main { app.selectTab(Route.Favorites) }
                val resumedFavorites = awaitFavorites(app, fixture)
                val releasedSearch = app.state.value.search
                assertTrue("离开全局搜索后释放查询和结果", releasedSearch.query.isEmpty() &&
                    releasedSearch.categories.isEmpty() && releasedSearch.results.sections.isEmpty() && releasedSearch.results.order.isEmpty())
                assertNotEquals("重新搜索应使用新的页面状态", searchVisit, app.state.value.searchVisitId)
                assertEquals(profile.id, app.state.value.currentProfileId)
                assertEquals(3, fixture.requestsFor("retained", DiscoveryCategory.MOVIES).size)
                assertEquals(2, fixture.discoveryRequests().count { it.favorite && it.category == DiscoveryCategory.MOVIES })
                search.release(); favorites.release()
                search.awaitFinished(); favorites.awaitFinished()
                main {
                    assertEquals("旧响应不能复活已释放的搜索", releasedSearch, app.state.value.search)
                    assertEquals(resumedFavorites, app.state.value.favorites)
                }
            }
        }
        fixture.blockSuggestions(ALPHA_ID).use { suggestions ->
            main { app.selectTab(Route.Search); app.search("") }
            suggestions.awaitRequest()
            assertTrue(app.state.value.search.suggestions.loading)
            main { app.showServers(); app.beginAddServer(); app.probeServer(fixture.baseUrl, allowHttp = true) }
            await(app, fixture, "再次探测完成后推荐不应留在忙态") { !it.loginBusy && it.serverProbe != null }
            assertFalse(app.state.value.search.suggestions.loading)
            assertFalse(app.state.value.search.suggestionsLoaded)
            main { app.backFromConnect(); app.backFromConnect(); app.openServer(profile); app.selectTab(Route.Search) }
            val resumed = await(app, fixture, "取消探测后空搜索能重新获取推荐") { it.search.suggestionsLoaded }.search.suggestions
            assertEquals(2, fixture.suggestionRequests().size)
            suggestions.release(); suggestions.awaitFinished()
            main { assertEquals(resumed, app.state.value.search.suggestions) }
        }
    }

    @Test fun newerSearchAndClearCancelSlowQueriesAndRefreshWithoutLateOverwrite() = withApp { app, fixture ->
        main { app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.MOVIES) }
        fixture.block(PageKey(ALPHA_ID, "slow-old", DiscoveryCategory.MOVIES)).use { old ->
            main { app.search("slow-old") }
            old.awaitRequest()
            main { app.search("fast-new") }
            val latest = awaitSearch(app, fixture, "fast-new", listOf(DiscoveryCategory.MOVIES))
            old.release(); old.awaitFinished()
            main { assertEquals("旧词迟到不得覆盖新词", latest, app.state.value.search.results) }
            assertEquals("fast-new", app.state.value.search.query)
        }
        val beforeRefresh = app.state.value.search.results
        fixture.block(PageKey(ALPHA_ID, "fast-new", DiscoveryCategory.MOVIES)).use { refresh ->
            main { app.refreshSearch() }
            refresh.awaitRequest()
            val pending = app.state.value.search.results
            assertEquals(beforeRefresh.sections.getValue(DiscoveryCategory.MOVIES).items, pending.sections.getValue(DiscoveryCategory.MOVIES).items)
            assertEquals(beforeRefresh.order, pending.order)
            main { app.search("replacement") }
            val replacement = awaitSearch(app, fixture, "replacement", listOf(DiscoveryCategory.MOVIES))
            refresh.release(); refresh.awaitFinished()
            main { assertEquals("被替代的刷新不得恢复旧词", replacement, app.state.value.search.results) }
            assertEquals("replacement", app.state.value.search.query)
        }
        fixture.block(PageKey(ALPHA_ID, "slow-clear", DiscoveryCategory.MOVIES)).use { pending ->
            main { app.search("slow-clear") }
            pending.awaitRequest()
            main { app.search(" \n ") }
            await(app, fixture, "清空恢复推荐搜索") { it.search.query.isEmpty() && it.search.suggestionsLoaded }
            val requests = fixture.discoveryRequests().size
            main { app.loadMoreDiscovery(DiscoveryCategory.MOVIES, favorite = false); app.search("") }
            pending.release(); pending.awaitFinished()
            main {
                assertTrue(app.state.value.search.results.sections.isEmpty())
                assertTrue(app.state.value.search.results.order.isEmpty())
                assertFalse(app.state.value.search.results.loading)
            }
            assertEquals("空词不能请求全媒体库或追加旧页", requests, fixture.discoveryRequests().size)
        }
        assertTrue("选择电影后每个新词只请求电影分类", fixture.discoveryRequests().all { it.category == DiscoveryCategory.MOVIES })
    }

    @Test fun failedServerSwitchReleasesOldActionsAndLateCompletionCannotUnlockTheirRetry() = withApp { app, fixture ->
        val alpha = app.state.value.profiles.single()
        main { app.login(fixture.baseUrl, BETA, PASSWORD, allowHttp = true, displayName = "Discovery beta") }
        await(app, fixture, "第二账号必须真实保存") { it.session?.userId == BETA_ID && !it.loginBusy && !it.home.loading }
        val beta = app.state.value.profiles.single { it.session.userId == BETA_ID }
        main { app.openServer(alpha) }
        await(app, fixture, "恢复原账号") { it.session?.userId == ALPHA_ID && !it.loginBusy && !it.home.loading }
        main { app.selectTab(Route.Favorites) }
        val item = awaitFavorites(app, fixture).sections.getValue(DiscoveryCategory.MOVIES).items.first()
        val key = "favorite/${item.id}"

        fixture.blockMutation(ALPHA_ID, item.id).use { old ->
            val oldJob = main {
                val running = app.viewModelScope.coroutineContext[Job]!!.children.toSet()
                app.toggleFavorite(item)
                app.viewModelScope.coroutineContext[Job]!!.children.single { it !in running }
            }
            old.awaitRequest()
            assertTrue(key in app.state.value.actionBusy)
            fixture.failNextValidation.set(true)
            main { app.openServer(beta) }
            val failed = await(app, fixture, "切号失败后原账号可继续交互") { !it.loginBusy && it.loginError != null }
            assertEquals(alpha.id, failed.currentProfileId)
            assertEquals(ALPHA_ID, failed.session?.userId)
            assertFalse("失效代次的操作不能永久占用原账号按钮", key in failed.actionBusy)

            fixture.blockMutation(ALPHA_ID, item.id).use { retry ->
                main { app.toggleFavorite(item) }
                retry.awaitRequest()
                assertTrue(key in app.state.value.actionBusy)
                old.release(); old.awaitFinished()
                runBlocking { withTimeout(WAIT_MS) { oldJob.join() } }
                main {
                    assertTrue("旧 finally 不能解锁新代次相同条目的操作", key in app.state.value.actionBusy)
                    assertTrue("旧写入成功不能回写失效代次的页面",
                        app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES).items.single { it.id == item.id }.isFavorite)
                }
                retry.release()
                val complete = await(app, fixture, "新代次操作可以正常结束") { key !in it.actionBusy }
                assertFalse(complete.favorites.sections.getValue(DiscoveryCategory.MOVIES).items.any { it.id == item.id })
                assertEquals(2, fixture.mutations().size)
            }
        }
    }

    @Test fun cancelledProbeReleasesOldActionsAndLateWriteCannotAffectTheNewAccount() = withApp { app, fixture ->
        main { app.selectTab(Route.Favorites) }
        val item = awaitFavorites(app, fixture).sections.getValue(DiscoveryCategory.MOVIES).items.first()
        val key = "favorite/${item.id}"
        fixture.blockMutation(ALPHA_ID, item.id).use { old ->
            val oldJob = main {
                val running = app.viewModelScope.coroutineContext[Job]!!.children.toSet()
                app.toggleFavorite(item)
                app.viewModelScope.coroutineContext[Job]!!.children.single { it !in running }
            }
            old.awaitRequest()
            main { app.showServers(); app.beginAddServer(); app.probeServer(fixture.baseUrl, allowHttp = true) }
            await(app, fixture, "探测完成后允许取消连接") { !it.loginBusy && it.serverProbe != null }
            main { app.backFromConnect(); app.backFromConnect() }
            assertEquals(ALPHA_ID, app.state.value.session?.userId)
            assertFalse("取消连接必须释放旧账号的动作忙态", key in app.state.value.actionBusy)

            main { app.login(fixture.baseUrl, BETA, PASSWORD, allowHttp = true, displayName = "Discovery beta") }
            await(app, fixture, "切换到新账号") { it.session?.userId == BETA_ID && !it.loginBusy && !it.home.loading }
            main { app.selectTab(Route.Favorites) }
            val current = awaitFavorites(app, fixture)
            old.release(); old.awaitFinished()
            runBlocking { withTimeout(WAIT_MS) { oldJob.join() } }
            main {
                assertEquals(BETA_ID, app.state.value.session?.userId)
                assertEquals("原账号迟到写入不能改变新账号的收藏数据", current, app.state.value.favorites)
                assertTrue(app.state.value.actionBusy.isEmpty())
                assertNull(app.state.value.notice)
            }
        }
    }

    @Test fun favoriteWritesRemoveGroupsImmediatelyReturnReloadsAndFailuresPreserveVisibleItems() = withApp { app, fixture ->
        main { app.search("favorite") }
        awaitSearch(app, fixture, "favorite")
        main { app.selectTab(Route.Favorites) }
        awaitFavorites(app, fixture)
        val favoriteRequests = fixture.discoveryRequests().filter { it.favorite }
        assertEquals(favoriteDiscoveryCategories.toSet(), favoriteRequests.map { it.category }.toSet())
        assertTrue(favoriteRequests.all { it.query["isFavorite"] == "true" && "searchTerm" !in it.query })
        val source = app.state.value.routes
        main { app.openDiscovery(category = DiscoveryCategory.MOVIES, favorite = true) }
        val more = app.state.value.route as Route.Discovery
        assertTrue(more.favorite)
        assertEquals(DiscoveryCategory.MOVIES, more.category)
        assertEquals("", more.query)
        assertEquals(source + more, app.state.value.routes)
        assertEquals("收藏更多复用已经读取的首屏", favoriteRequests, fixture.discoveryRequests().filter { it.favorite })
        fixture.block(PageKey(ALPHA_ID, "", DiscoveryCategory.MOVIES, 24, favorite = true)).use { append ->
            main { repeat(2) { app.loadMoreDiscovery(DiscoveryCategory.MOVIES, favorite = true) } }
            append.awaitRequest()
            assertEquals(24, app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES).items.size)
            append.release()
            await(app, fixture, "收藏电影独立追加完成") { it.favorites.sections[DiscoveryCategory.MOVIES]?.items?.size == 26 }
        }
        val original = app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES)
        val otherGroups = app.state.value.favorites.sections - DiscoveryCategory.MOVIES
        val removed = original.items.first()
        assertTrue(removed.isFavorite)
        fixture.failNextMutation.set(true)
        main { app.toggleFavorite(removed) }
        await(app, fixture, "失败的取消收藏必须报告错误并恢复操作") { it.actionBusy.isEmpty() && it.notice != null }
        assertEquals("写入失败不得提前移除收藏", original, app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES))
        val order = app.state.value.favorites.order
        val beforeMutation = fixture.discoveryRequests().filter { it.favorite }
        fixture.block(PageKey(ALPHA_ID, "", DiscoveryCategory.MOVIES, favorite = true)).use { refresh ->
            main { app.dismissNotice(); app.toggleFavorite(removed) }
            await(app, fixture, "成功写回立即从保留分组移除") {
                it.actionBusy.isEmpty() && it.favorites.sections[DiscoveryCategory.MOVIES]?.items?.none { item -> item.id == removed.id } == true
            }
            val updated = app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES)
            assertEquals(25, updated.items.size)
            assertEquals(25, updated.total)
            assertEquals(25, updated.nextIndex)
            assertFalse(app.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES).items.single { it.id == removed.id }.isFavorite)
            assertEquals(listOf("DELETE", "DELETE"), fixture.mutations().map { it.method })
            assertTrue(fixture.mutations().all { it.path == "/UserFavoriteItems/${removed.id}" && it.userId == ALPHA_ID })
            main { app.back() }
            refresh.awaitRequest()
            assertEquals(Route.Favorites, app.state.value.route)
            assertEquals(source, app.state.value.routes)
            assertEquals(updated.items, app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES).items)
            assertEquals(order, app.state.value.favorites.order)
            assertEquals(otherGroups, app.state.value.favorites.sections - DiscoveryCategory.MOVIES)
            refresh.release()
            val fresh = awaitFavorites(app, fixture)
            assertEquals(25, fresh.sections.getValue(DiscoveryCategory.MOVIES).total)
            assertTrue(fresh.sections.getValue(DiscoveryCategory.MOVIES).items.none { it.id == removed.id })
            assertEquals("取消收藏返回只重新读取失效电影组", 1,
                fixture.discoveryRequests().filter { it.favorite }.size - beforeMutation.size)
            assertEquals(otherGroups, fresh.sections - DiscoveryCategory.MOVIES)
            val cached = fixture.discoveryRequests().size
            main { app.openDiscovery(category = DiscoveryCategory.MOVIES, favorite = true); app.back() }
            assertEquals("返回后重进完整列表继续复用已更新收藏", cached, fixture.discoveryRequests().size)
        }
        val visible = app.state.value.favorites.sections.getValue(DiscoveryCategory.MOVIES).items
        fixture.fail(PageKey(ALPHA_ID, "", DiscoveryCategory.MOVIES, favorite = true), 500)
        main { app.refreshFavorites() }
        val failure = await(app, fixture, "收藏刷新错误保留全部已有卡片") {
            !it.favorites.loading && it.favorites.sections[DiscoveryCategory.MOVIES]?.error != null
        }.favorites
        assertEquals(visible, failure.sections.getValue(DiscoveryCategory.MOVIES).items)
        assertEquals(order, failure.order)
        main { app.refreshFavorites() }
        val recovered = awaitFavorites(app, fixture)
        assertEquals(visible, recovered.sections.getValue(DiscoveryCategory.MOVIES).items)
        assertNull(recovered.error)
        assertEquals(order, recovered.order)
    }

    @Test fun switchingAccountsDiscardsLateSearchAndUnauthorizedRefreshKeepsOnlyTheCurrentAccount() = withApp { app, fixture ->
        main { app.search("account-a"); app.selectTab(Route.Favorites) }
        awaitSearch(app, fixture, "account-a")
        awaitFavorites(app, fixture)
        main { app.toggleSearchCategory(null); app.toggleSearchCategory(DiscoveryCategory.MOVIES) }
        fixture.block(PageKey(ALPHA_ID, "pending-a", DiscoveryCategory.MOVIES)).use { old ->
            main { app.search("pending-a") }
            old.awaitRequest()
            main { app.login(fixture.baseUrl, BETA, PASSWORD, allowHttp = true, displayName = "Discovery beta") }
            await(app, fixture, "第二账号登录首页完成") { it.session?.userId == BETA_ID && it.home.loaded && !it.home.loading && !it.loginBusy }
            old.release(); old.awaitFinished()
            main {
                assertTrue(app.state.value.search.query.isEmpty())
                assertTrue(app.state.value.search.results.sections.isEmpty())
                assertTrue(app.state.value.search.results.order.isEmpty())
                assertTrue(app.state.value.favorites.sections.isEmpty())
                assertTrue(app.state.value.favorites.order.isEmpty())
                assertFalse(app.state.value.favorites.requested)
            }
        }
        main { app.selectTab(Route.Search) }
        val suggestions = await(app, fixture, "第二账号推荐独立读取") { it.search.suggestionsLoaded }.search.suggestions
        assertTrue(suggestions.items.all { it.id.startsWith(BETA_ID) })
        main { app.search("account-b") }
        val current = awaitSearch(app, fixture, "account-b")
        assertTrue(current.sections.values.flatMap { it.items }.all { it.id.startsWith(BETA_ID) })
        fixture.fail(PageKey(BETA_ID, "account-b", DiscoveryCategory.MOVIES), 401)
        main { app.refreshSearch() }
        val expired = await(app, fixture, "401 只恢复当前账号并停止全部忙态") { it.reauthenticationProfile?.session?.userId == BETA_ID }
        assertEquals(BETA_ID, expired.session?.userId)
        assertEquals(current.sections.mapValues { it.value.items }, expired.search.results.sections.mapValues { it.value.items })
        assertFalse(expired.search.results.sections.values.any { it.loading || it.loadingMore })
        main {
            app.search("must-not-run")
            app.refreshSearch()
            app.loadMoreDiscovery(DiscoveryCategory.MOVIES, favorite = false)
            assertEquals("认证恢复期间的搜索操作不得改变现有数据", expired.search, app.state.value.search)
            app.selectTab(Route.Favorites)
            app.refreshFavorites()
        }
        assertTrue("用户主动离开搜索仍应释放结果", app.state.value.search.let {
            it.query.isEmpty() && it.categories.isEmpty() && it.results.sections.isEmpty() && it.results.order.isEmpty()
        })
        assertNotEquals(expired.searchVisitId, app.state.value.searchVisitId)
        assertEquals(BETA_ID, app.state.value.session?.userId)
        assertEquals(BETA_ID, app.state.value.reauthenticationProfile?.session?.userId)
        assertTrue(app.state.value.favorites.sections.isEmpty())
        assertTrue(fixture.discoveryRequests().none { it.query["searchTerm"] == "must-not-run" })
        assertTrue(fixture.discoveryRequests().filter { it.userId == BETA_ID }.none { it.favorite })
    }

    @Test fun librarySuggestionsKeepOrdinaryFailuresSilentAndRecoverTheirCurrentAccount() {
        for (term in listOf("", "library-match")) withApp { app, fixture ->
            val library = MediaItem("suggestion-library", "合成电影库", "CollectionFolder", collectionType = "movies")
            val profileId = app.state.value.currentProfileId
            val key = PageKey(ALPHA_ID, term, DiscoveryCategory.MOVIES)
            for (status in listOf(500, 401)) {
                fixture.fail(key, status)
                val response = main { app.viewModelScope.async { app.librarySearchSuggestions(library, term) } }
                assertTrue("建议失败应返回空列表，term=$term status=$status",
                    runBlocking { withTimeout(WAIT_MS) { response.await() } }.isEmpty())
                val current = app.state.value
                assertEquals(ALPHA_ID, current.session?.userId)
                assertEquals(profileId, current.currentProfileId)
                assertFalse(current.loginBusy)
                if (status == 500) {
                    assertNull("普通建议读取失败不能要求重新登录", current.reauthenticationProfile)
                    assertNull(current.loginError)
                    assertNull(current.notice)
                } else {
                    assertEquals("当前建议的 401 必须进入该账号的既有恢复流程", ALPHA_ID,
                        current.reauthenticationProfile?.session?.userId)
                    assertNotNull(current.loginError)
                    assertFalse(current.home.loading)
                }
            }
            val requests = fixture.discoveryRequests().filter { it.pageKey == key }
            assertEquals("推荐和片名匹配分支都必须真实完成两次失败请求", 2, requests.size)
            assertTrue(requests.all { it.query["parentId"] == library.id && it.query["limit"] == "8" })
            assertTrue("每个请求均须保留实际推荐/片名匹配语义",
                requests.all { it.query["searchTerm"] == term.takeIf(String::isNotEmpty) })
        }
    }

    @Test fun oldLibrarySuggestionUnauthorizedResponseCannotReauthenticateTheNewAccount() = withApp { app, fixture ->
        val library = MediaItem("suggestion-library", "合成电影库", "CollectionFolder", collectionType = "movies")
        val key = PageKey(ALPHA_ID, "old-library-match", DiscoveryCategory.MOVIES)
        fixture.fail(key, 401)
        fixture.block(key).use { old ->
            val response = main { app.viewModelScope.async { app.librarySearchSuggestions(library, key.term) } }
            old.awaitRequest()
            main { app.login(fixture.baseUrl, BETA, PASSWORD, allowHttp = true, displayName = "Discovery beta") }
            val switched = await(app, fixture, "旧建议响应保持在途时第二账号须完成登录") {
                it.session?.userId == BETA_ID && it.home.loaded && !it.home.loading && !it.loginBusy
            }
            assertFalse("旧建议必须实际跨越切号继续等待，不能用取消代替鉴权归属检查", response.isCompleted)
            old.release(); old.awaitFinished()
            assertTrue(runBlocking { withTimeout(WAIT_MS) { response.await() } }.isEmpty())
            val current = app.state.value
            assertEquals(switched.session, current.session)
            assertEquals(switched.currentProfileId, current.currentProfileId)
            assertNull("旧账号的 401 不能使新账号重新登录", current.reauthenticationProfile)
            assertNull(current.loginError)
            assertFalse(current.loginBusy)
            assertTrue(current.home.loaded)
            assertFalse(current.home.loading)
            assertEquals(ALPHA_ID, fixture.requestsFor(key.term, DiscoveryCategory.MOVIES).single().userId)
        }
    }

    private fun withApp(block: (AppViewModel, Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "分组链路测试只允许独立 verification 包" }
        val preferences = AppPreferencesStore(context)
        val previous = preferences.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            clearSessions(context)
            preferences.save(AppPreferences())
            val app = main { ViewModelProvider(owner,
                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java] }
            await(app, fixture, "合成会话恢复完成") { !it.restoringSession }
            assertNull(app.state.value.session)
            main { app.login(fixture.baseUrl, ALPHA, PASSWORD, allowHttp = true, displayName = "Discovery alpha") }
            await(app, fixture, "第一账号登录首页完成") { it.session?.userId == ALPHA_ID && it.home.loaded && !it.home.loading && !it.loginBusy }
            assertNull(app.state.value.home.error)
            block(app, fixture)
            fixture.assertHealthy()
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                main { owner.viewModelStore.clear() }
                fixture.close()
                clearSessions(context)
                preferences.save(previous)
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun awaitSearch(app: AppViewModel, fixture: Fixture, query: String,
        categories: List<DiscoveryCategory> = searchDiscoveryCategories): DiscoveryState =
        await(app, fixture, "搜索 $query 的分类完成") {
            it.search.query == query && categories.all { category -> it.search.results.sections[category]?.settled == true }
        }.search.results

    private fun awaitFavorites(app: AppViewModel, fixture: Fixture): DiscoveryState =
        await(app, fixture, "收藏分类完成") { favoriteDiscoveryCategories.all { category -> it.favorites.sections[category]?.settled == true } }.favorites

    private val ContentState.settled get() = !loading && !loadingMore && error == null && queryKey != null

    private fun await(app: AppViewModel, fixture: Fixture, message: String, predicate: (AppState) -> Boolean): AppState = try {
        runBlocking { withTimeout(WAIT_MS) { app.state.first { fixture.assertHealthy(); predicate(it) } } }
    } catch (error: TimeoutCancellationException) {
        fixture.assertHealthy()
        throw AssertionError("$message；state=${app.state.value}；requests=${fixture.discoveryRequests()}", error)
    }

    private fun clearSessions(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private data class PageKey(val userId: String, val term: String, val category: DiscoveryCategory,
        val start: Int = 0, val favorite: Boolean = false)

    private data class Request(val method: String, val path: String, val query: Map<String, String>, val userId: String?) {
        val favorite get() = query["isFavorite"] == "true"
        val category: DiscoveryCategory? get() = when (path) {
            "/Persons" -> DiscoveryCategory.PEOPLE
            "/Artists" -> DiscoveryCategory.ARTISTS
            "/Items" -> DiscoveryCategory.entries.singleOrNull { it.itemType == query["includeItemTypes"] }
            else -> null
        }
        val pageKey: PageKey? get() = category?.let { PageKey(checkNotNull(userId), query["searchTerm"].orEmpty(), it,
            query["startIndex"]?.toInt() ?: 0, favorite) }
    }

    private class Gate : Closeable {
        private val arrived = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        fun hold() { arrived.countDown(); check(released.await(WAIT_MS * 2, TimeUnit.MILLISECONDS)) { "合成响应未释放" } }
        fun awaitRequest() = assertTrue("请求必须实际到达服务端", arrived.await(WAIT_MS, TimeUnit.MILLISECONDS))
        fun release() = released.countDown()
        fun complete() = finished.countDown()
        fun awaitFinished() = assertTrue("已取消的真实响应必须结束", finished.await(WAIT_MS, TimeUnit.MILLISECONDS))
        override fun close() = release()
    }

    private class Fixture : Closeable {
        val failNextMutation = AtomicBoolean()
        val failNextValidation = AtomicBoolean()
        private val requests = ConcurrentLinkedQueue<Request>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val gates = ConcurrentHashMap<PageKey, Gate>()
        private val suggestionGates = ConcurrentHashMap<String, Gate>()
        private val mutationGates = ConcurrentHashMap<Pair<String, String>, Gate>()
        private val allGates = ConcurrentLinkedQueue<Gate>()
        private val failures = ConcurrentHashMap<PageKey, Int>()
        private val removedFavorites = ConcurrentHashMap.newKeySet<String>()
        private val closed = AtomicBoolean()
        private val workers = Executors.newCachedThreadPool { Thread(it, "pathfin-discovery-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept()
                sockets.add(socket)
                workers.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) errors.add(error) }
        }, "pathfin-discovery-accept").apply { isDaemon = true; start() }

        fun discoveryRequests() = requests.filter { it.pageKey != null }
        fun suggestionRequests() = requests.filter { it.path == "/Items" && it.query["sortBy"] == "IsFavoriteOrLiked,Random" }
        fun requestsFor(term: String, category: DiscoveryCategory? = null) = discoveryRequests().filter {
            it.query["searchTerm"] == term && (category == null || it.category == category)
        }
        fun mutations() = requests.filter { it.path.startsWith("/UserFavoriteItems/") }
        fun block(key: PageKey) = Gate().also { check(gates.putIfAbsent(key, it) == null); allGates.add(it) }
        fun blockSuggestions(userId: String) = Gate().also { check(suggestionGates.putIfAbsent(userId, it) == null); allGates.add(it) }
        fun blockMutation(userId: String, itemId: String) = Gate().also {
            check(mutationGates.putIfAbsent(userId to itemId, it) == null); allGates.add(it)
        }
        fun fail(key: PageKey, status: Int) { check(failures.putIfAbsent(key, status) == null) }
        fun assertHealthy() { errors.peek()?.let { throw AssertionError("分组合成 HTTP 执行失败", it) } }

        private fun serve(socket: Socket) {
            var gate: Gate? = null
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val first = input.readLine() ?: return
                    val parts = first.split(' ')
                    check(parts.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = input.readLine() ?: throw EOFException("合成请求头不完整")
                        if (header.isEmpty()) break
                        val separator = header.indexOf(':')
                        check(separator > 0)
                        headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..16_384)
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        if (read < 0) throw EOFException("合成请求体不完整")
                        offset += read
                    }
                    val uri = URI(parts[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { parameter ->
                        val pair = parameter.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    val user = listOf(ALPHA_ID, BETA_ID).singleOrNull { "Token=\"token-$it\"" in headers["authorization"].orEmpty() }
                    if (uri.path !in setOf("/Users/AuthenticateByName", "/System/Info/Public", "/Users/Public")) {
                        check(user != null) { "合成请求缺少本夹具令牌" }
                        query["userId"]?.let { check(it == user) { "请求账号与令牌不符" } }
                    }
                    val request = Request(parts[0], uri.path, query, user)
                    requests.add(request)
                    val key = request.pageKey
                    gate = when {
                        key != null -> gates.remove(key)
                        request.path == "/Items" && request.query["sortBy"] == "IsFavoriteOrLiked,Random" -> suggestionGates.remove(user)
                        request.path.startsWith("/UserFavoriteItems/") -> mutationGates.remove(checkNotNull(user) to request.path.substringAfterLast('/'))
                        else -> null
                    }
                    gate?.hold()
                    val status = key?.let(failures::remove)
                        ?: when {
                            request.path == "/Users/Me" && failNextValidation.compareAndSet(true, false) -> 500
                            request.path.startsWith("/UserFavoriteItems/") && failNextMutation.compareAndSet(true, false) -> 500
                            else -> 200
                        }
                    val response = if (status == 200) response(request, String(body))
                        else JSONObject().put("Message", "Synthetic discovery failure $status")
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    val reason = when (status) { 200 -> "OK"; 401 -> "Unauthorized"; else -> "Internal Server Error" }
                    val output = connection.getOutputStream()
                    output.write(("HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            } catch (error: Throwable) {
                // OkHttp 会真正关闭被替换查询/失效账号的连接；不把合法取消当成夹具协议失败。
                if (!closed.get() && error !is SocketException) errors.add(error)
            } finally { gate?.complete(); sockets.remove(socket) }
        }

        private fun response(request: Request, body: String): Any = when (request.path) {
            "/Users/AuthenticateByName" -> {
                check(request.method == "POST")
                val credentials = JSONObject(body)
                val username = credentials.getString("Username")
                check(username in setOf(ALPHA, BETA) && credentials.getString("Pw") == PASSWORD)
                val userId = if (username == ALPHA) ALPHA_ID else BETA_ID
                JSONObject().put("AccessToken", "token-$userId").put("ServerId", SERVER_ID)
                    .put("User", JSONObject().put("Id", userId).put("Name", username))
            }
            "/Users/Me" -> JSONObject().put("Id", request.userId).put("Name", if (request.userId == ALPHA_ID) ALPHA else BETA)
            "/System/Info/Public" -> JSONObject().put("Id", SERVER_ID).put("ServerName", "Discovery fixture").put("Version", "12.3.0")
            "/UserViews", "/UserItems/Resume", "/Shows/NextUp" -> page(emptyList())
            "/Items/Latest", "/Users/Public" -> JSONArray()
            "/Items", "/Persons", "/Artists" -> {
                if (request.path == "/Items" && request.query["sortBy"] == "IsFavoriteOrLiked,Random") {
                    val user = checkNotNull(request.userId)
                    page(listOf(media("$user-suggestion-1", "Movie", "推荐甲", false),
                        media("$user-suggestion-2", "Series", "推荐甲", false), media("$user-suggestion-3", "Movie", "推荐乙", false)))
                } else discovery(request)
            }
            else -> {
                check(request.path.startsWith("/UserFavoriteItems/")) { "未定义夹具路径：${request.method} ${request.path}" }
                check(request.method in setOf("POST", "DELETE"))
                val id = request.path.substringAfterLast('/')
                if (request.method == "DELETE") removedFavorites.add(id) else removedFavorites.remove(id)
                JSONObject().put("IsFavorite", request.method == "POST").put("Played", false).put("PlaybackPositionTicks", 0)
            }
        }

        private fun discovery(request: Request): JSONObject {
            val key = checkNotNull(request.pageKey)
            val favoriteContent = key.favorite || key.term == "favorite"
            val total = when {
                favoriteContent -> when (key.category) { DiscoveryCategory.MOVIES -> 26; DiscoveryCategory.SERIES, DiscoveryCategory.PEOPLE -> 1; else -> 0 }
                key.term == "paged" -> when (key.category) { DiscoveryCategory.MOVIES -> 27; DiscoveryCategory.ARTISTS -> 26; DiscoveryCategory.SERIES -> 2; DiscoveryCategory.PEOPLE -> 1; else -> 0 }
                else -> 1
            }
            val prefix = if (favoriteContent) "favorite" else key.term
            val items = (0 until total).map { index ->
                val id = "${key.userId}-$prefix-${key.category.name.lowercase()}-$index"
                media(id, key.category.itemType, "$prefix ${key.category.name} $index", favoriteContent && id !in removedFavorites)
            }.filter { !key.favorite || it.getJSONObject("UserData").getBoolean("IsFavorite") }
            val limit = request.query["limit"]?.toInt() ?: items.size
            return page(items.drop(key.start).take(limit), items.size)
        }

        private fun media(id: String, type: String, name: String, favorite: Boolean) = JSONObject()
            .put("Id", id).put("Name", name).put("Type", type).put("ImageTags", JSONObject())
            .put("UserData", JSONObject().put("IsFavorite", favorite).put("Played", false).put("PlaybackPositionTicks", 0))

        private fun page(items: List<JSONObject>, total: Int = items.size) = JSONObject().put("Items", JSONArray(items)).put("TotalRecordCount", total)

        override fun close() {
            closed.set(true)
            allGates.forEach { it.release() }
            server.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            accept.join(2_000)
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val ALPHA = "discovery-alpha"
        const val BETA = "discovery-beta"
        const val ALPHA_ID = "discovery-user-alpha"
        const val BETA_ID = "discovery-user-beta"
        const val PASSWORD = "synthetic-discovery-password"
        const val SERVER_ID = "synthetic-discovery-server"
        const val WAIT_MS = 20_000L
    }
}

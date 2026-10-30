package dev.pathfin.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.EOFException
import java.io.File
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 库内搜索的真实 VM/HTTP/Compose 链路；只使用独立 verification 包与进程内合成服务。 */
@RunWith(AndroidJUnit4::class)
class LibrarySearchFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun musicLibraryAndItsGenreOpenAlbumsThenTracksAndPrepareAudioPlayback() = withApp(supportedLibraries = true) { env ->
        openLibrary(env, env.vm.state.value.home.libraries.single { it.id == MUSIC })
        val albums = awaitBrowser(env, MUSIC) { it.content.items.singleOrNull()?.id == ALBUM }
        assertEquals(setOf("MusicAlbum"), albums.query.itemTypes)

        main { env.vm.changeBrowserQuery(MUSIC, albums.query.copy(tab = LibraryTab.GENRES,
            genres = setOf("PreviousGenreFilter"), genreId = "previous-genre")) }
        awaitBrowser(env, MUSIC) { it.content.items.singleOrNull()?.id == MUSIC_GENRE }
        openBrowserItem(MUSIC_GENRE)
        val genreRoute = await(env, "音乐类型必须打开同一音乐库的专辑") { it.route is Route.Browse }.route as Route.Browse
        val genre = awaitBrowser(env, genreRoute.id) { it.content.items.singleOrNull()?.id == ALBUM }
        assertEquals(setOf("MusicAlbum"), genre.query.itemTypes)
        assertEquals(MUSIC_GENRE, genre.query.genreId)
        assertTrue("进入具体类型不能继续叠加旧的名称筛选", genre.query.genres.isEmpty())
        val genreRequest = env.fixture.folderRequests(MUSIC).last().query
        assertEquals(MUSIC_GENRE, genreRequest["genreIds"])
        assertFalse("类型导航对齐 Web 的标识查询，不能继续发送名称筛选", "genres" in genreRequest)
        assertEquals("MusicAlbum", genreRequest["includeItemTypes"])
        assertEquals("true", genreRequest["recursive"])

        openBrowserItem(ALBUM)
        val tracks = awaitBrowser(env, ALBUM) { it.content.items.singleOrNull()?.id == TRACK }
        assertEquals(setOf("Audio"), tracks.query.itemTypes)
        assertEquals("Audio", env.fixture.folderRequests(ALBUM).single().query["includeItemTypes"])
        openBrowserItem(TRACK)
        assertPlayableRequest(env, TRACK, "Audio", "/Audio/$TRACK/stream")
    }

    @Test fun musicSearchFindsArtistsAndArtistAlbumsKeepTheirTrackPlaybackEntry() = withApp(supportedLibraries = true) { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MUSIC }
        openLibrary(env, library)
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        submit("music")
        val results = awaitBrowser(env, Route.LibrarySearch(library).key) { it.content.items.size == 3 }
        assertEquals(setOf("MusicAlbum", "MusicArtist", "Audio"), results.content.items.map { it.type }.toSet())
        val request = env.fixture.searchRequests().last()
        assertEquals(MUSIC, request.query["parentId"])
        assertEquals(setOf("MusicAlbum", "MusicArtist", "Audio"), request.query.getValue("includeItemTypes").split(',').toSet())

        openBrowserItem(ARTIST)
        val artist = awaitBrowser(env, "artist/$ARTIST") { it.content.items.singleOrNull()?.id == ALBUM }
        assertEquals(ARTIST, artist.query.artistId)
        assertEquals(setOf("MusicAlbum", "Audio"), artist.query.itemTypes)
        openBrowserItem(ALBUM)
        awaitBrowser(env, ALBUM) { it.content.items.singleOrNull()?.id == TRACK }
        openBrowserItem(TRACK)
        assertPlayableRequest(env, TRACK, "Audio", "/Audio/$TRACK/stream")
    }

    @Test fun collectionsLibraryOpensABoxSetAndPreparesItsMoviePlayback() = withApp(supportedLibraries = true) { env ->
        openLibrary(env, env.vm.state.value.home.libraries.single { it.id == COLLECTIONS })
        val collections = awaitBrowser(env, COLLECTIONS) { it.content.items.singleOrNull()?.id == BOX_SET }
        assertEquals(setOf("BoxSet"), collections.query.itemTypes)
        openBrowserItem(BOX_SET)
        val contents = awaitBrowser(env, BOX_SET) { it.content.items.singleOrNull()?.id == BOX_SET_MOVIE }
        assertTrue("合集内容沿用原有不限制媒体类型的查询", contents.query.itemTypes.isEmpty())
        openBrowserItem(BOX_SET_MOVIE)
        assertPlayableRequest(env, BOX_SET_MOVIE, "Movie", "/Videos/$BOX_SET_MOVIE/stream")
    }

    @Test fun homeVideoLibraryRetainsItsVideoTypeAndHasAWorkingDetailPlaybackAction() = withApp(supportedLibraries = true) { env ->
        openLibrary(env, env.vm.state.value.home.libraries.single { it.id == HOME_VIDEOS })
        val videos = awaitBrowser(env, HOME_VIDEOS) { it.content.items.singleOrNull()?.id == HOME_VIDEO }
        assertEquals(setOf("Video"), videos.query.itemTypes)
        openBrowserItem(HOME_VIDEO)
        assertPlayableRequest(env, HOME_VIDEO, "Video", "/Videos/$HOME_VIDEO/stream")
    }

    @Test fun mixedLibraryBrowsesDirectoriesAndSearchesAcrossThemWithoutLosingReturnDestination() = withApp(supportedLibraries = true) { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MIXED }
        assertNull(library.collectionType)
        openLibrary(env, library)
        val root = awaitBrowser(env, MIXED) { it.content.items.singleOrNull()?.id == MIXED_FOLDER }
        val folder = root.content.items.single()
        assertEquals("Folder", folder.type)
        assertTrue(folder.isFolder)
        compose.onNode(hasClickAction() and hasAnyDescendant(hasTestTag("poster-title/$MIXED_FOLDER")), useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        val rootRequest = env.fixture.folderRequests(MIXED).single()
        assertEquals(MIXED, rootRequest.query["parentId"])
        assertEquals("false", rootRequest.query["recursive"])
        assertEquals(setOf("Folder", "Movie", "Series"), rootRequest.query.getValue("includeItemTypes").split(',').toSet())

        openBrowserItem(MIXED_FOLDER)
        val contents = awaitBrowser(env, MIXED_FOLDER) { it.content.items.size == 4 }
        assertEquals(Route.Folder(folder), env.vm.state.value.route)
        assertEquals(setOf("Movie", "Episode", "Audio", "Folder"), contents.content.items.map { it.type }.toSet())
        val folderRequest = env.fixture.folderRequests(MIXED_FOLDER).single()
        assertEquals(MIXED_FOLDER, folderRequest.query["parentId"])
        assertEquals("false", folderRequest.query["recursive"])
        assertFalse("进入目录不能丢掉单集、音乐或更深的目录", "includeItemTypes" in folderRequest.query)

        openBrowserItem(MIXED_MOVIE)
        assertPlayableRequest(env, MIXED_MOVIE, "Movie", "/Videos/$MIXED_MOVIE/stream")
        val preserved = env.vm.state.value.browsers.getValue(MIXED_FOLDER)
        touchDiscovery("mixed-detail-return-to-folder", compose.onNodeWithContentDescription("返回"))
        await(env, "影片详情必须返回所在子目录") { it.route == Route.Folder(folder) }
        assertEquals(preserved, env.vm.state.value.browsers[MIXED_FOLDER])
        assertEquals("详情返回复用目录内容", 1, env.fixture.folderRequests(MIXED_FOLDER).size)

        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        submit("nested-audio")
        val audio = awaitBrowser(env, Route.LibrarySearch(folder).key) { it.content.items.singleOrNull()?.id == TRACK }
        assertEquals("Audio", audio.content.items.single().type)
        val audioRequest = env.fixture.searchRequests().single()
        assertEquals(MIXED_FOLDER, audioRequest.query["parentId"])
        assertEquals("true", audioRequest.query["recursive"])
        assertFalse("普通目录内搜索必须保留音频和单集等所有类型", "includeItemTypes" in audioRequest.query)
        openBrowserItem(TRACK)
        await(env, "目录内搜索的音频仍可打开详情") { it.route == Route.Detail(TRACK) && !it.detailLoading && it.details[TRACK]?.type == "Audio" }
        touchDiscovery("mixed-audio-return-to-search", compose.onNodeWithContentDescription("返回"))
        await(env, "音频详情返回目录内搜索") { it.route == Route.LibrarySearch(folder) }
        compose.onNodeWithTag("library-search-cancel").performVerificationClick(compose)
        await(env, "关闭目录内搜索恢复所在子目录") { it.route == Route.Folder(folder) }
        assertEquals(preserved, env.vm.state.value.browsers[MIXED_FOLDER])

        touchDiscovery("mixed-folder-return-to-library", compose.onNodeWithContentDescription("返回"))
        await(env, "子目录必须返回混合库而非首页") { it.route == Route.Folder(library) }
        assertEquals(root, env.vm.state.value.browsers[MIXED])
        assertEquals(1, env.fixture.folderRequests(MIXED).size)

        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        submit("nested-movie")
        val result = awaitBrowser(env, Route.LibrarySearch(library).key) { it.content.items.singleOrNull()?.id == MIXED_MOVIE }
        assertEquals("Movie", result.content.items.single().type)
        val searchRequest = env.fixture.searchRequests().single { it.query["parentId"] == MIXED }
        assertEquals(MIXED, searchRequest.query["parentId"])
        assertEquals("true", searchRequest.query["recursive"])
        assertEquals(setOf("Movie", "Series"), searchRequest.query.getValue("includeItemTypes").split(',').toSet())
        openBrowserItem(MIXED_MOVIE)
        await(env, "跨目录搜索结果仍可打开详情") { it.route == Route.Detail(MIXED_MOVIE) && !it.detailLoading }
        touchDiscovery("mixed-detail-return-to-search", compose.onNodeWithContentDescription("返回"))
        await(env, "搜索详情返回仍保留跨目录结果") { it.route == Route.LibrarySearch(library) }
        assertEquals(result, env.vm.state.value.browsers[Route.LibrarySearch(library).key])
        compose.onNodeWithTag("library-search-cancel").performVerificationClick(compose)
        await(env, "关闭搜索恢复原混合库目录") { it.route == Route.Folder(library) }
        assertEquals(root, env.vm.state.value.browsers[MIXED])
    }

    @Test fun scopedMovieSearchPaginatesAndBackRestoresOriginalFiltersAndScroll() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        val originalQuery = env.vm.state.value.browsers.getValue(MOVIES).query.copy(tab = LibraryTab.FAVORITES,
            sort = LibrarySort.TITLE, descending = false, favorite = true, played = false,
            genres = setOf("SyntheticGenre"), tags = setOf("SyntheticTag"), years = setOf(2021))
        main { env.vm.changeBrowserQuery(MOVIES, originalQuery) }
        val original = awaitBrowser(env, MOVIES) { it.query == originalQuery && it.content.items.size == 45 }
        val grid = compose.onNodeWithTag("browser-grid")
        grid.performScrollToIndex(12)
        compose.waitForIdle()
        val scroll = grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("原库必须实际滚动后再打开搜索", scroll > 0f)
        val originalRequests = env.fixture.browseRequests().size
        val filterRequests = env.fixture.filterRequests()
        val open = compose.onNodeWithTag("library-search-open")
        VerificationDisplay.awaitWindowReady(compose, "library-search-open", open)
        open.assertVerificationDisplayed(compose).performVerificationClick(compose)
        val key = Route.LibrarySearch(library).key
        await(env, "只打开搜索输入页") { it.route == Route.LibrarySearch(library) }
        compose.onNodeWithTag("library-search-input").assertVerificationDisplayed(compose)
        assertEquals(0, env.fixture.searchRequests().size)
        assertEquals(filterRequests, env.fixture.filterRequests())
        compose.onNodeWithContentDescription("首页").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多操作").assertDoesNotExist()

        val completed = env.fixture.blockItems("paged", 40).use { append ->
            submit("  paged  ")
            val first = await(env, "首屏显示后自动预取，追加响应等待期间保留40条") {
                append.requestArrived && it.browsers[key]?.let { browser -> browser.query.searchTerm == "paged" &&
                    !browser.content.loading && browser.content.loadingMore && browser.content.items.size == 40 } == true
            }.browsers.getValue(key)
            assertEquals(45, first.content.total)
            assertEquals(40, first.content.nextIndex)
            val initialRequest = env.fixture.searchRequests().first()
            assertScoped(initialRequest, MOVIES, "Movie", "paged", 0)
            listOf("isFavorite", "isPlayed", "genres", "tags", "years", "filters").forEach {
                assertFalse("搜索不得继承原库收藏/类型/标签限制：$it", it in initialRequest.query)
            }
            assertEquals("SortName", initialRequest.query["sortBy"])
            assertEquals("Ascending", initialRequest.query["sortOrder"])
            assertEquals(filterRequests, env.fixture.filterRequests())
            repeat(3) { compose.onNodeWithTag("library-search-results").performVerificationTouchInput(compose) { swipeUp() } }
            compose.runOnIdle {
                assertEquals(40, env.vm.state.value.browsers.getValue(key).content.items.size)
                assertEquals("重复触底不能重发同一游标", 2, env.fixture.searchRequests().size)
            }
            append.release()
            awaitBrowser(env, key) { it.content.items.size == 45 }
        }
        assertEquals(45, completed.content.total)
        assertEquals(45, completed.content.nextIndex)
        assertEquals(45, completed.content.items.map { it.id }.distinct().size)
        assertEquals(2, env.fixture.searchRequests().size)
        assertScoped(env.fixture.searchRequests().last(), MOVIES, "Movie", "paged", 40)
        repeat(2) { compose.onNodeWithTag("library-search-results").performVerificationTouchInput(compose) { swipeUp() } }
        compose.onNodeWithText("加载更多", substring = true).assertDoesNotExist()
        main { env.vm.loadBrowser(key, append = true); env.vm.submitLibrarySearch(library, " paged ") }
        compose.waitForIdle()
        assertEquals("终页与重复同词提交都不得发新请求", 2, env.fixture.searchRequests().size)
        assertEquals(originalRequests, env.fixture.browseRequests().size)

        compose.onNodeWithTag("library-search-cancel").performVerificationClick(compose)
        await(env, "搜索返回原库") { it.route == Route.Folder(library) }
        compose.runOnIdle { assertEquals("搜索不能覆盖原库查询、分页或结果", original, env.vm.state.value.browsers[MOVIES]) }
        assertEquals(scroll, compose.onNodeWithTag("browser-grid").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        assertEquals(originalRequests, env.fixture.browseRequests().size)
        assertEquals(filterRequests, env.fixture.filterRequests())
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        assertInput("  paged  ")
        assertEquals(completed, env.vm.state.value.browsers[key])
        assertEquals(2, env.fixture.searchRequests().size)
    }

    @Test fun newerQueryWinsAndClearingCancelsLateResultsWithoutEmptyRequests() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        val key = Route.LibrarySearch(library).key
        val filters = env.fixture.filterRequests()
        env.fixture.blockSearch("slow-old").use { old ->
            submit("slow-old")
            await(env, "旧查询必须真实到达服务端门闩") { old.requestArrived && it.browsers[key]?.content?.loading == true }
            submit("fast-new")
            val latest = awaitBrowser(env, key) { it.query.searchTerm == "fast-new" && it.content.items.singleOrNull()?.id == "fast-new-0" }
            assertEquals(2, env.fixture.searchRequests().size)
            old.release()
            await(env, "取消的旧响应已实际结束") { old.finished }
            compose.runOnIdle { assertEquals("迟到旧词不能覆盖新词的真实结果", latest, env.vm.state.value.browsers[key]) }
            compose.onNodeWithText("Search fast-new 0").assertVerificationDisplayed(compose)
            compose.onNodeWithText("Search slow-old 0").assertDoesNotExist()
        }
        env.fixture.blockSearch("slow-clear").use { old ->
            submit("slow-clear")
            await(env, "待清空查询已发到服务端") { old.requestArrived }
            compose.onNodeWithTag("search-clear", true).assertVerificationDisplayed(compose).performVerificationClick(compose)
            val empty = await(env, "清空应立即取消并恢复输入态") {
                it.browsers[key]?.let { browser -> browser.query.searchTerm.isEmpty() &&
                    browser.content.items.isEmpty() && !browser.content.loading } == true
            }.browsers.getValue(key)
            val requests = env.fixture.searchRequests().size
            main { env.vm.refreshBrowser(key); env.vm.loadBrowser(key, append = true); env.vm.submitLibrarySearch(library, " \n ") }
            pullSearchResultsToRefresh("empty-library-search-no-refresh")
            old.release()
            await(env, "清空前迟到响应已结束") { old.finished }
            compose.runOnIdle { assertEquals(empty, env.vm.state.value.browsers[key]) }
            assertEquals("空词提交/刷新/分页/物理下拉均不得请求全库", requests, env.fixture.searchRequests().size)
            assertTrue(env.fixture.itemRequests().all { it.query["parentId"] == MOVIES })
            assertTrue(env.fixture.searchRequests().all { !it.query["searchTerm"].isNullOrBlank() })
            assertEquals(filters, env.fixture.filterRequests())
            assertInput("")
            // 清空后回到输入态，显示库内推荐而不是结果网格。
            compose.onNodeWithTag("library-search-suggestions").assertVerificationDisplayed(compose)
        }
    }

    @Test fun televisionSearchUsesItsOwnParentAndSingleSeriesTypeWithoutGlobalSearchPollution() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == SHOWS }
        openLibrary(env, library)
        val original = env.vm.state.value.browsers.getValue(SHOWS)
        val filters = env.fixture.filterRequests()
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        submit("tv")
        val key = Route.LibrarySearch(library).key
        val result = awaitBrowser(env, key) { it.content.items.singleOrNull()?.id == "tv-0" }
        assertEquals("Series", result.content.items.single().type)
        assertScoped(env.fixture.searchRequests().single(), SHOWS, "Series", "tv", 0)
        assertEquals("SortName", env.fixture.searchRequests().single().query["sortBy"])
        assertEquals("Ascending", env.fixture.searchRequests().single().query["sortOrder"])
        assertEquals(filters, env.fixture.filterRequests())
        assertEquals("", env.vm.state.value.search.query)
        assertFalse(env.vm.state.value.search.results.hasItems)
        compose.onNodeWithTag("library-search-results").performScrollToIndex(0)
        pullSearchResultsToRefresh("television-library-search-refresh")
        await(env, "实际下拉重读同一词与媒体库") { env.fixture.searchRequests().size == 2 && !it.browsers.getValue(key).content.loading }
        env.fixture.searchRequests().forEach { assertScoped(it, SHOWS, "Series", "tv", 0) }
        compose.onNodeWithTag("library-search-cancel").performVerificationClick(compose)
        assertEquals(original, env.vm.state.value.browsers[SHOWS])
        compose.onNodeWithContentDescription("返回").performVerificationClick(compose)
        await(env, "返回首页恢复导航") { it.route == Route.Home }
        compose.onNodeWithContentDescription("首页").assertVerificationDisplayed(compose)
    }

    @Test fun searchBarShowsLibraryRecommendationsThenTitleMatchesAndOpensDetailDirectly() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == SHOWS }
        openLibrary(env, library)
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        await(env, "进入库内搜索") { it.route == Route.LibrarySearch(library) }
        // 顶栏即搜索框：不再显示“XX内搜索”标题。
        compose.onNodeWithText("${library.name}内搜索").assertDoesNotExist()
        val recommended = compose.onNodeWithTag("search-suggestion/$SERIES")
        compose.waitUntil(5_000) { runCatching { recommended.assertVerificationDisplayed(compose) }.isSuccess }
        compose.onNodeWithText("推荐搜索").assertVerificationDisplayed(compose)
        val recommendation = env.fixture.suggestionRequests().single()
        assertEquals(SHOWS, recommendation.query["parentId"])
        assertEquals("Series", recommendation.query["includeItemTypes"])
        assertEquals("打开即推荐，不得发起正式搜索", 0, env.fixture.searchRequests().size)

        compose.onNodeWithTag("library-search-input").performTextReplacement("match")
        val match = compose.onNodeWithTag("search-suggestion/match-0")
        compose.waitUntil(5_000) { runCatching { match.assertVerificationDisplayed(compose) }.isSuccess }
        compose.onNodeWithText("相关条目").assertVerificationDisplayed(compose)
        val typed = env.fixture.suggestionRequests().last()
        assertEquals("match", typed.query["searchTerm"])
        assertEquals(SHOWS, typed.query["parentId"])
        assertEquals("输入建议不计入完整结果搜索", 0, env.fixture.searchRequests().size)

        compose.onNodeWithTag("search-clear", true).performVerificationClick(compose)
        compose.waitUntil(5_000) { runCatching { recommended.assertVerificationDisplayed(compose) }.isSuccess }
        recommended.performVerificationClick(compose)
        await(env, "点建议直接进入详情") { it.route == Route.Detail(SERIES) }
        assertEquals(0, env.fixture.searchRequests().size)
    }

    @Test fun disabledPullModeReentersAndAutomaticallyRefreshesASuccessfulEmptySearch() = withApp { env ->
        main { env.vm.setPreferences(env.vm.state.value.preferences.copy(pullToRefreshEnabled = false)) }
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        submit("nothing")
        val key = Route.LibrarySearch(library).key
        awaitBrowser(env, key) { it.query.searchTerm == "nothing" && it.content.total == 0 && it.content.items.isEmpty() }
        assertEquals(1, env.fixture.searchRequests().size)
        compose.onNodeWithText("没有找到内容").assertVerificationDisplayed(compose)
        env.fixture.blockSearch("nothing").use { gate ->
            compose.onNodeWithTag("library-search-cancel").performVerificationClick(compose)
            await(env, "空结果返回原库") { it.route == Route.Folder(library) }
            VerificationDisplay.awaitWindowReady(compose, "empty-search-reentry", compose.onNodeWithTag("library-search-open"))
            compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
            await(env, "关闭下拉后，合法空结果再进入仍需自动重读") { gate.requestArrived && it.browsers[key]?.content?.loading == true }
            assertInput("nothing")
            assertEquals(2, env.fixture.searchRequests().size)
            assertTrue(env.vm.state.value.browsers.getValue(key).content.items.isEmpty())
            compose.waitForIdle()
            assertEquals("在途自动读取不能重复发送", 2, env.fixture.searchRequests().size)
            gate.release()
            awaitBrowser(env, key) { it.content.total == 0 && it.content.items.isEmpty() }
            env.fixture.searchRequests().forEach { assertScoped(it, MOVIES, "Movie", "nothing", 0) }
        }
    }

    @Test fun automaticAppendFailureRetainsRowsStopsRequestsAndRecoversThroughRealPull() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        val key = Route.LibrarySearch(library).key
        env.fixture.failNextItems("append-failure", 40)
        val first = env.fixture.blockItems("append-failure", 40).use { append ->
            submit("append-failure")
            val pending = await(env, "自动追加失败前仍显示真实首屏") {
                append.requestArrived && it.browsers[key]?.content?.let { page ->
                    !page.loading && page.loadingMore && page.items.size == 40
                } == true
            }.browsers.getValue(key).content.items
            append.release()
            pending
        }
        scrollUntil(env, "library-search-results", "滚动到失败的真实追加请求") {
            env.vm.state.value.browsers[key]?.content?.let { !it.loadingMore && it.retryAppend && it.error != null } == true
        }
        val failed = env.vm.state.value.browsers.getValue(key).content
        assertEquals(first, failed.items)
        assertEquals(40, failed.nextIndex)
        assertEquals(2, env.fixture.searchRequests().size)
        repeat(3) { compose.onNodeWithTag("library-search-results").performVerificationTouchInput(compose) { swipeUp() } }
        compose.waitForIdle()
        assertEquals("分页失败停留底部不得自动自旋", 2, env.fixture.searchRequests().size)
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithText("加载更多", substring = true).assertDoesNotExist()
        env.fixture.blockItems("append-failure", 0).use { refresh ->
            compose.onNodeWithTag("library-search-results").performScrollToIndex(0)
            pullSearchResultsToRefresh("failed-append-real-pull")
            await(env, "下拉真实重新读取首屏且保留旧内容") {
                refresh.requestArrived && it.browsers[key]?.content?.let { content -> content.loading && content.items == first } == true
            }
            assertEquals(3, env.fixture.searchRequests().size)
            refresh.release()
            awaitBrowser(env, key) { it.content.items.size == 45 && it.content.items.take(40) == first && it.content.error == null }
        }
        scrollUntil(env, "library-search-results", "恢复后重新临近底部自动追加") {
            env.vm.state.value.browsers[key]?.content?.items?.size == 45
        }
        val completed = awaitBrowser(env, key) { it.content.items.size == 45 }
        assertEquals(45, completed.content.nextIndex)
        assertEquals(listOf("0", "40", "0", "40"), env.fixture.searchRequests().map { it.query["startIndex"] })
        assertTrue(env.fixture.searchRequests().all { it.query["searchTerm"] == "append-failure" })
    }

    @Test fun aRealEmptyAppendEndsPaginationEvenWhenTheServerTotalIsLarger() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        compose.onNodeWithTag("library-search-open").performVerificationClick(compose)
        submit("empty-tail")
        val key = Route.LibrarySearch(library).key
        val first = await(env, "真实首屏及空追加完成前后均保留40条") {
            it.browsers[key]?.content?.items?.size == 40
        }.browsers.getValue(key).content.items
        scrollUntil(env, "library-search-results", "真实追加空页结束游标") {
            env.vm.state.value.browsers[key]?.content?.nextIndex == 45
        }
        val complete = awaitBrowser(env, key) { it.content.nextIndex == 45 }
        assertEquals(first, complete.content.items)
        assertEquals(45, complete.content.total)
        assertEquals(2, env.fixture.searchRequests().size)
        repeat(4) { compose.onNodeWithTag("library-search-results").performVerificationTouchInput(compose) { swipeUp() } }
        compose.waitForIdle()
        assertEquals("空页必须结束，不可重复请求相同游标", 2, env.fixture.searchRequests().size)
        assertEquals(listOf("0", "40"), env.fixture.searchRequests().map { it.query["startIndex"] })
    }

    @Test fun letterQueriesKeepTheLibraryScopeIgnoreLateResponsesAndToggleBackToAll() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        val original = env.vm.state.value.browsers.getValue(MOVIES).query.copy(sort = LibrarySort.TITLE, descending = false,
            played = false, favorite = true, genres = setOf("SyntheticGenre"), tags = setOf("SyntheticTag"), years = setOf(2021))
        main { env.vm.changeBrowserQuery(MOVIES, original) }
        val baseline = awaitBrowser(env, MOVIES) { it.query == original && it.content.items.size == 45 }.content.items
        val requestStart = env.fixture.browseRequests().size
        env.fixture.blockItems("browse", 0, "A").use { slow ->
            selectLetter("A")
            await(env, "A 请求实际到达后保持旧卡片") {
                slow.requestArrived && it.browsers[MOVIES]?.let { browser -> browser.query.nameInitial == "A" &&
                    browser.content.loading && browser.content.items == baseline } == true
            }
            selectLetter("B")
            val newer = awaitBrowser(env, MOVIES) { it.query == original.copy(nameInitial = "B") &&
                it.content.items.singleOrNull()?.id == "initial-B-0" }
            slow.release()
            await(env, "被取消的 A 响应已实际完成") { slow.finished }
            compose.runOnIdle { assertEquals("迟到 A 不得覆盖已显示 B", newer, env.vm.state.value.browsers[MOVIES]) }
            val beforeClear = env.fixture.itemRequests().size
            selectLetter("B")
            awaitBrowser(env, MOVIES) { it.query == original && it.content.items == baseline }
            assertEquals("清除 B 复用缓存首屏，只能按原游标续页，不能重读首屏",
                listOf("40" to "100"), env.fixture.itemRequests().drop(beforeClear).map { it.query["startIndex"] to it.query["limit"] })
        }
        selectLetter("0", env)
        try {
            awaitBrowser(env, MOVIES) { it.query == original.copy(nameInitial = "0") && it.content.items.singleOrNull()?.id == "initial-0-0" }
        } catch (failure: Throwable) {
            recordLetterDiagnostic(env, "numeric-timeout", failure)
            throw failure
        }
        val numeric = env.fixture.browseRequests().last()
        assertEquals("0", numeric.query["nameStartsWithOrGreater"])
        assertEquals(":", numeric.query["nameLessThan"])
        assertFalse("nameStartsWith" in numeric.query)
        selectLetter("#")
        awaitBrowser(env, MOVIES) { it.query == original.copy(nameInitial = "#") && it.content.items.singleOrNull()?.id == "initial-#-0" }
        val symbols = env.fixture.browseRequests().last()
        // Jellyfin 的 # 是小于 A，包含数字；不可用客户端假分组改写其语义。
        assertEquals("A", symbols.query["nameLessThan"])
        assertFalse("nameStartsWith" in symbols.query)
        assertFalse("nameStartsWithOrGreater" in symbols.query)
        val beforeClear = env.fixture.itemRequests().size
        selectLetter("#")
        awaitBrowser(env, MOVIES) { it.query == original && it.content.items == baseline }
        assertEquals("清除 # 复用缓存首屏，只能续页补齐原库",
            listOf("40" to "100"), env.fixture.itemRequests().drop(beforeClear).map { it.query["startIndex"] to it.query["limit"] })
        val requests = env.fixture.browseRequests().drop(requestStart).filter {
            "nameStartsWith" in it.query || "nameStartsWithOrGreater" in it.query || "nameLessThan" in it.query
        }
        assertEquals("只有 A、B、0、# 各一次真实 Items 请求", 4, requests.size)
        assertEquals("A", requests[0].query["nameStartsWith"])
        assertEquals("B", requests[1].query["nameStartsWith"])
        assertEquals(numeric, requests[2])
        assertEquals(symbols, requests[3])
        for (request in requests) {
            assertEquals(MOVIES, request.query["parentId"])
            assertEquals("Movie", request.query["includeItemTypes"])
            assertEquals("SortName", request.query["sortBy"])
            assertEquals("Ascending", request.query["sortOrder"])
            assertEquals("false", request.query["isPlayed"])
            assertEquals("true", request.query["isFavorite"])
            assertEquals("SyntheticGenre", request.query["genres"])
            assertEquals("SyntheticTag", request.query["tags"])
            assertEquals("2021", request.query["years"])
        }
        showLetterIndex()
        compose.onNodeWithTag("library-index/#").assertIsNotSelected()
    }

    @Test fun aHeldLetterReleasedAfterLoginDisablesThePageNeverQueriesOrSendsItems() = withApp { env ->
        val library = env.vm.state.value.home.libraries.single { it.id == MOVIES }
        openLibrary(env, library)
        val original = env.vm.state.value.browsers.getValue(MOVIES)
        val requests = env.fixture.itemRequests().size
        showLetterIndex()
        // 浮层会随认证门禁隐藏；保留真实 DOWN，再经始终存在的 activation 节点送出 UP/CANCEL。
        val activation = compose.onNodeWithTag("library-index-activation")
        VerificationDisplay.awaitWindowReady(compose, "held-index-before-login", activation)
        env.fixture.blockAuthentication().use { authentication ->
            var held = false
            try {
                compose.onNodeWithTag("library-index/A").performVerificationTouchInput(compose) { down(center) }
                held = true
                compose.runOnIdle {
                    assertEquals("真实 DOWN 本身不能提交字母", original.query, env.vm.state.value.browsers.getValue(MOVIES).query)
                    assertEquals(requests, env.fixture.itemRequests().size)
                }
                compose.onNodeWithTag("library-index/A").assertIsNotSelected()
                main { env.vm.login(env.fixture.baseUrl, USERNAME, PASSWORD, allowHttp = true, displayName = "Library search fixture") }
                await(env, "认证网络保持阻塞且旧页请求门禁已生效") { authentication.requestArrived && it.loginBusy }
                activation.performVerificationTouchInput(compose) { up() }
                held = false
                compose.runOnIdle {
                    assertEquals("认证期间到达的真实 UP 不能改变旧查询", original.query,
                        env.vm.state.value.browsers.getValue(MOVIES).query)
                    assertEquals("认证期间不能以旧页面发出新的 Items 请求", requests, env.fixture.itemRequests().size)
                }
                compose.onNodeWithTag("library-index").assertDoesNotExist()
                compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
            } finally { if (held) activation.performVerificationTouchInput(compose) { cancel() } }
            authentication.release()
            await(env, "正常认证可完成且只读首页专用接口") { !it.loginBusy && it.home.loaded && !it.home.loading }
            assertEquals(requests, env.fixture.itemRequests().size)
        }
    }

    @Test fun cachedLetterRestoresInTheSameMainTurnWithoutHttpAndRecordsControlledVisibilityTiming() = withApp { env ->
        openLibrary(env, env.vm.state.value.home.libraries.single { it.id == MOVIES })
        val base = env.vm.state.value.browsers.getValue(MOVIES).query
        val queryA = base.copy(nameInitial = "A")
        val queryB = base.copy(nameInitial = "B")
        val before = env.fixture.browseRequests().size
        val coldStart = SystemClock.elapsedRealtime()
        val firstA = env.fixture.blockItems("browse", 0, "A").use { gate ->
            main { env.vm.changeBrowserQuery(MOVIES, queryA) }
            await(env, "冷查询已进入真实 HTTP 门闩") { gate.requestArrived && it.browsers[MOVIES]?.content?.loading == true }
            SystemClock.sleep(CONTROLLED_HOLD_MS)
            assertTrue(env.vm.state.value.browsers.getValue(MOVIES).content.loading)
            gate.release()
            awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-0" }
        }
        compose.onNodeWithText("Search initial-A 0").assertVerificationDisplayed(compose)
        val coldVisibleMs = SystemClock.elapsedRealtime() - coldStart
        main { env.vm.changeBrowserQuery(MOVIES, queryB) }
        awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        compose.onNodeWithText("Search initial-B 0").assertVerificationDisplayed(compose)

        val warmStart = SystemClock.elapsedRealtime()
        restoreCached(env, queryA, firstA.content)
        compose.onNodeWithText("Search initial-A 0").assertVerificationDisplayed(compose)
        compose.onNodeWithText("Search initial-B 0").assertDoesNotExist()
        val warmVisibleMs = SystemClock.elapsedRealtime() - warmStart
        assertEquals(listOf("A", "B"), env.fixture.browseRequests().drop(before).map { it.query["nameStartsWith"] })
        assertTrue("冷查询包含有意设置的受控延迟", coldVisibleMs >= CONTROLLED_HOLD_MS)
        // 只报告本进程合成夹具的可见延迟，不设置倍率断言或外推个人服务器性能。
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("pathfin.libraryCacheTiming", JSONObject().put("fixture", "synthetic-loopback")
                .put("controlledHoldMs", CONTROLLED_HOLD_MS).put("coldVisibleMs", coldVisibleMs)
                .put("warmVisibleMs", warmVisibleMs).put("warmItemsRequests", 0).toString())
        })
    }

    @Test fun successfulEmptyLetterIsCachedAndRemainsLoadedOnReentryWithoutHttp() = withApp { env ->
        val base = configureMovieBrowser(env)
        env.fixture.setBrowsePage("E", 0, emptyList(), 0)
        val emptyQuery = base.copy(nameInitial = "E")
        main { env.vm.changeBrowserQuery(MOVIES, emptyQuery) }
        val empty = awaitBrowser(env, MOVIES) { it.query == emptyQuery && it.content.items.isEmpty() && it.content.total == 0 }
        main { env.vm.changeBrowserQuery(MOVIES, base.copy(nameInitial = "A")) }
        awaitBrowser(env, MOVIES) { it.content.items.singleOrNull()?.id == "initial-A-0" }
        restoreCached(env, emptyQuery, empty.content)
        val requests = env.fixture.itemRequests().size
        main {
            env.vm.configureBrowser(MOVIES, MOVIES, base)
            env.vm.loadBrowser(MOVIES, append = true)
            assertEquals(emptyQuery, env.vm.state.value.browsers.getValue(MOVIES).query)
            assertEquals(empty.content, env.vm.state.value.browsers.getValue(MOVIES).content)
        }
        compose.waitForIdle()
        assertEquals("合法空页必须计为已加载，重入与终点追加都不能再发请求", requests, env.fixture.itemRequests().size)
        assertEquals(1, env.fixture.browseRequests().count { it.query["nameStartsWith"] == "E" })
    }

    @Test fun cachedShortRawPageKeepsItsServerCursorAndAppendDoesNotReplaceTheCachedFirstPage() = withApp { env ->
        val base = configureMovieBrowser(env)
        val queryA = base.copy(nameInitial = "A")
        val queryB = base.copy(nameInitial = "B")
        // 23 个原始 DTO 中重复一个 ID；列表去重后 22 项，下一页必须仍从 23 开始。
        env.fixture.setBrowsePage("A", 0, listOf(0) + (0 until 22).toList(), 26)
        env.fixture.setBrowsePage("A", 23, (23 until 26).toList(), 26)
        main { env.vm.changeBrowserQuery(MOVIES, queryA) }
        val first = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.size == 22 }
        assertEquals(23, first.content.nextIndex)
        assertEquals(26, first.content.total)
        main { env.vm.changeBrowserQuery(MOVIES, queryB) }
        val firstB = awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        restoreCached(env, queryA, first.content)
        env.fixture.blockItems("browse", 23, "A").use { gate ->
            main { env.vm.loadBrowser(MOVIES, append = true); env.vm.loadBrowser(MOVIES, append = true) }
            await(env, "恢复缓存后按原始游标进行一次真实追加") { gate.requestArrived && it.browsers[MOVIES]?.content?.loadingMore == true }
            assertEquals(listOf("0", "23"), env.fixture.browseRequests().filter { it.query["nameStartsWith"] == "A" }.map { it.query["startIndex"] })
            gate.release()
            val appended = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.nextIndex == 26 }
            assertEquals(25, appended.content.items.size)
            assertEquals(25, appended.content.items.map { it.id }.distinct().size)
        }
        restoreCached(env, queryB, firstB.content)
        restoreCached(env, queryA, first.content)
        main { env.vm.loadBrowser(MOVIES, append = true) }
        awaitBrowser(env, MOVIES) { it.content.nextIndex == 26 && it.content.items.size == 25 }
        val requests = env.fixture.browseRequests().filter { it.query["nameStartsWith"] == "A" }
        assertEquals("追加结果不能冒充首屏缓存；恢复后应再次从原始 23 续页", listOf("0", "23", "23"), requests.map { it.query["startIndex"] })
        assertEquals(listOf("40", "100", "100"), requests.map { it.query["limit"] })
        assertTrue(requests.all { it.query["parentId"] == MOVIES && it.query["includeItemTypes"] == "Movie" })
    }

    @Test fun explicitRefreshInvalidatesEveryCachedLetterForTheParentAndKeepsOnlyFreshPages() = withApp { env ->
        val base = configureMovieBrowser(env)
        val queryA = base.copy(nameInitial = "A")
        val queryB = base.copy(nameInitial = "B")
        main { env.vm.changeBrowserQuery(MOVIES, queryA) }
        val oldA = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-0" }
        main { env.vm.changeBrowserQuery(MOVIES, queryB) }
        awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        restoreCached(env, queryA, oldA.content)
        env.fixture.setBrowsePage("A", 0, listOf(10), 1)
        env.fixture.blockItems("browse", 0, "A").use { gate ->
            main { env.vm.refreshBrowser(MOVIES) }
            await(env, "显式刷新必须走网络且保留原卡片") { gate.requestArrived && it.browsers[MOVIES]?.content?.loading == true }
            assertEquals(oldA.content.items, env.vm.state.value.browsers.getValue(MOVIES).content.items)
            gate.release()
        }
        val freshA = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-10" }
        env.fixture.blockItems("browse", 0, "B").use { gate ->
            main { env.vm.changeBrowserQuery(MOVIES, queryB) }
            await(env, "刷新 A 也必须失效同库 B 的首屏") { gate.requestArrived && it.browsers[MOVIES]?.content?.loading == true }
            assertEquals(freshA.content.items, env.vm.state.value.browsers.getValue(MOVIES).content.items)
            gate.release()
        }
        awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        restoreCached(env, queryA, freshA.content)
        assertEquals(listOf("A", "B", "A", "B"), env.fixture.browseRequests().mapNotNull { it.query["nameStartsWith"] })
    }

    @Test fun failedRefreshKeepsVisibleRowsButOldLetterPagesCannotResurrectFromCache() = withApp { env ->
        val base = configureMovieBrowser(env)
        val queryA = base.copy(nameInitial = "A")
        val queryB = base.copy(nameInitial = "B")
        main { env.vm.changeBrowserQuery(MOVIES, queryA) }
        val oldA = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-0" }
        main { env.vm.changeBrowserQuery(MOVIES, queryB) }
        awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        restoreCached(env, queryA, oldA.content)
        env.fixture.failNextItems("browse", 0, "A")
        main { env.vm.refreshBrowser(MOVIES) }
        await(env, "真实 500 刷新失败后保留旧页") { it.browsers[MOVIES]?.content?.let { page -> !page.loading && page.error != null } == true }
        assertEquals(oldA.content.items, env.vm.state.value.browsers.getValue(MOVIES).content.items)
        main { env.vm.changeBrowserQuery(MOVIES, queryB) }
        val freshB = awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        env.fixture.setBrowsePage("A", 0, listOf(11), 1)
        env.fixture.blockItems("browse", 0, "A").use { gate ->
            main { env.vm.changeBrowserQuery(MOVIES, queryA) }
            await(env, "切回失败的字母必须重读，不能恢复失效旧缓存") { gate.requestArrived && it.browsers[MOVIES]?.content?.loading == true }
            assertEquals("新响应到达前应保留刚才的 B，而非复活旧 A", freshB.content.items,
                env.vm.state.value.browsers.getValue(MOVIES).content.items)
            gate.release()
        }
        awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-11" }
        assertEquals(listOf("A", "B", "A", "B", "A"), env.fixture.browseRequests().mapNotNull { it.query["nameStartsWith"] })
    }

    @Test fun cachedLetterCancelsAnInflightOtherLetterAndItsLateReplyCannotPolluteEitherCache() = withApp { env ->
        val base = configureMovieBrowser(env)
        val queryA = base.copy(nameInitial = "A")
        val queryB = base.copy(nameInitial = "B")
        main { env.vm.changeBrowserQuery(MOVIES, queryA) }
        val firstA = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-0" }
        env.fixture.blockItems("browse", 0, "B").use { slow ->
            main { env.vm.changeBrowserQuery(MOVIES, queryB) }
            await(env, "B 请求确实在途") { slow.requestArrived && it.browsers[MOVIES]?.content?.loading == true }
            restoreCached(env, queryA, firstA.content)
            slow.release()
            await(env, "被取消的 B 已实际结束响应") { slow.finished }
            compose.runOnIdle {
                assertEquals(queryA, env.vm.state.value.browsers.getValue(MOVIES).query)
                assertEquals(firstA.content, env.vm.state.value.browsers.getValue(MOVIES).content)
            }
        }
        env.fixture.setBrowsePage("B", 0, listOf(9), 1)
        env.fixture.blockItems("browse", 0, "B").use { fresh ->
            main { env.vm.changeBrowserQuery(MOVIES, queryB) }
            await(env, "迟到 B 不能留下可命中的成功缓存") { fresh.requestArrived && it.browsers[MOVIES]?.content?.loading == true }
            fresh.release()
        }
        awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-9" }
        restoreCached(env, queryA, firstA.content)
        assertEquals(listOf("A", "B", "B"), env.fixture.browseRequests().mapNotNull { it.query["nameStartsWith"] })
    }

    @Test fun successfulFavoriteWriteUpdatesCachedLettersWithoutRefetchingUnrelatedQueries() = withApp { env ->
        val base = configureMovieBrowser(env)
        val queryA = base.copy(nameInitial = "A")
        val queryB = base.copy(nameInitial = "B")
        main { env.vm.changeBrowserQuery(MOVIES, queryA) }
        val firstA = awaitBrowser(env, MOVIES) { it.query == queryA && it.content.items.singleOrNull()?.id == "initial-A-0" }
        main { env.vm.changeBrowserQuery(MOVIES, queryB) }
        val firstB = awaitBrowser(env, MOVIES) { it.query == queryB && it.content.items.singleOrNull()?.id == "initial-B-0" }
        restoreCached(env, queryA, firstA.content)
        val item = firstA.content.items.single()
        assertFalse(item.isFavorite)
        main { env.vm.toggleFavorite(item) }
        await(env, "真实收藏 POST 完成并更新当前媒体") {
            env.fixture.favoriteRequests().size == 1 && it.actionBusy.isEmpty() &&
                it.browsers[MOVIES]?.content?.items?.singleOrNull()?.isFavorite == true
        }
        val mutation = env.fixture.favoriteRequests().single()
        assertEquals("POST", mutation.method)
        assertEquals("/UserFavoriteItems/${item.id}", mutation.path)
        restoreCached(env, queryB, firstB.content)
        restoreCached(env, queryA, firstA.content.copy(items = firstA.content.items.map { it.copy(isFavorite = true) }))
        assertTrue(env.vm.state.value.browsers.getValue(MOVIES).content.items.single().isFavorite)
        assertEquals(listOf("A", "B"), env.fixture.browseRequests().mapNotNull { it.query["nameStartsWith"] })
    }

    @Test fun globalMovieSearchLimitsItsPreviewAndPaginatesOnlyInsideTheFullGrid() = withApp { env ->
        main { env.vm.selectTab(Route.Search) }
        val input = compose.onNodeWithTag("search-query")
        VerificationDisplay.awaitWindowReady(compose, "global-search-input", input)
        val filter = compose.onNodeWithTag("search-filter/MOVIES")
        touchDiscovery("global-search-movie-filter", filter)
        filter.assertIsOn()
        await(env, "切换全站电影搜索") { it.search.categories == setOf(DiscoveryCategory.MOVIES) }
        input.performTextReplacement("  global-paged  ")
        input.performImeAction()
        await(env, "全站电影搜索24条首屏") { state ->
            state.search.query == "global-paged" && state.search.results.sections[DiscoveryCategory.MOVIES]?.let {
                !it.loading && it.items.size == 24 && it.nextIndex == 24 && it.total == 45
            } == true
        }
        val source = env.vm.state.value.routes
        val preview = compose.onNodeWithTag("discovery-row/MOVIES")
        preview.performScrollToIndex(5)
        VerificationDisplay.awaitWindowReady(compose, "global-search-preview-end",
            compose.onNodeWithTag("discovery-item/MOVIES/global-paged-5"))
        compose.onNodeWithTag("discovery-item/MOVIES/global-paged-6").assertDoesNotExist()
        repeat(2) { preview.performVerificationTouchInput(compose) { swipeLeft() } }
        compose.waitForIdle()
        assertEquals("6 项预览横向触底不能追加隐藏内容", 1, env.fixture.searchRequests().size)
        env.fixture.blockItems("global-paged", 24).use { append ->
            val more = compose.onNodeWithTag("discovery-more/MOVIES")
            touchDiscovery("global-search-more", more)
            await(env, "更多复用当前搜索进入完整列表") {
                it.route == Route.Discovery(category = DiscoveryCategory.MOVIES, favorite = false, query = "global-paged")
            }
            VerificationDisplay.awaitWindowReady(compose, "global-search-complete-grid",
                compose.onNodeWithTag("discovery-grid/MOVIES"))
            assertEquals("打开更多不能重发首屏", 1, env.fixture.searchRequests().count { it.query["startIndex"] == "0" })
            scrollUntil(env, "discovery-grid/MOVIES", "完整电影列表真实纵向触底分页") { append.requestArrived }
            await(env, "全站电影追加在途") { it.search.results.sections[DiscoveryCategory.MOVIES]?.loadingMore == true }
            repeat(2) { compose.onNodeWithTag("discovery-grid/MOVIES").performVerificationTouchInput(compose) {
                swipeUp()
            } }
            assertEquals(2, env.fixture.searchRequests().size)
            append.release()
            await(env, "完整电影列表自动补齐") { state ->
                state.search.results.sections[DiscoveryCategory.MOVIES]?.let {
                    !it.loadingMore && it.items.size == 45 && it.nextIndex == 45
                } == true
            }
        }
        repeat(2) { compose.onNodeWithTag("discovery-grid/MOVIES").performVerificationTouchInput(compose) {
            swipeUp()
        } }
        main { env.vm.search(" global-paged "); env.vm.loadMoreDiscovery(DiscoveryCategory.MOVIES, favorite = false) }
        compose.waitForIdle()
        assertEquals(2, env.fixture.searchRequests().size)
        assertEquals(listOf("0", "24"), env.fixture.searchRequests().map { it.query["startIndex"] })
        assertTrue(env.fixture.searchRequests().all { it.query["parentId"] == null &&
            it.query["searchTerm"] == "global-paged" && it.query["includeItemTypes"] == "Movie" && it.query["limit"] == "24" })
        assertEquals(45, env.vm.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES).items.map { it.id }.distinct().size)
        compose.onNodeWithText("加载更多", substring = true).assertDoesNotExist()
        val completeRoute = env.vm.state.value.route
        val grid = compose.onNodeWithTag("discovery-grid/MOVIES")
        grid.performScrollToIndex(30)
        val movie = compose.onNodeWithTag("discovery-item/MOVIES/global-paged-30")
        VerificationDisplay.awaitWindowReady(compose, "global-search-detail-return", grid, movie)
        val gridNode = grid.fetchSemanticsNode()
        val scroll = compose.runOnIdle { gridNode.config[SemanticsProperties.VerticalScrollAxisRange].value() }
        assertTrue("完整列表先真实滚动再打开详情", scroll > 0f)
        touchDiscovery("global-search-open-visible-movie", movie)
        await(env, "完整列表打开可见电影详情") {
            (it.route as? Route.Detail)?.id == "global-paged-30" && !it.detailLoading &&
                it.detailExtras["global-paged-30"]?.loading == false
        }
        touchDiscovery("global-search-return-to-grid", compose.onNodeWithContentDescription("返回"))
        await(env, "详情返回完整列表") { it.route == completeRoute }
        val restoredGrid = grid.fetchSemanticsNode()
        compose.runOnIdle {
            assertEquals("详情返回应恢复完整列表的实际滚动位置", scroll,
                restoredGrid.config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        }
        assertEquals(2, env.fixture.searchRequests().size)
        touchDiscovery("global-search-return-to-preview", compose.onNodeWithContentDescription("返回"))
        await(env, "返回6项搜索预览并保留词与多选集合") {
            it.routes == source && it.search.query == "global-paged" && it.search.categories == setOf(DiscoveryCategory.MOVIES)
        }
        compose.onNodeWithTag("discovery-grid/MOVIES").assertDoesNotExist()
        compose.onNodeWithTag("discovery-row/MOVIES").performScrollToIndex(5)
        VerificationDisplay.awaitWindowReady(compose, "global-search-restored-preview-end",
            compose.onNodeWithTag("discovery-item/MOVIES/global-paged-5"))
        compose.onNodeWithTag("discovery-item/MOVIES/global-paged-6").assertDoesNotExist()
        assertEquals("返回预览继续复用完整列表缓存", 45,
            env.vm.state.value.search.results.sections.getValue(DiscoveryCategory.MOVIES).items.size)
        assertEquals(2, env.fixture.searchRequests().size)
    }

    @Test fun detailSeasonsAndEpisodePickerAppendFromTheirActualScrolling() = withApp { env ->
        for ((id, type, tag) in listOf(Triple(SERIES, "Series", "detail-content"), Triple(SEASON, "Season", "detail-episode-numbers"))) {
            main { env.vm.open(MediaItem(id, "Synthetic $id", type, isFolder = true)) }
            await(env, "详情首屏与补充读取完成") {
                it.folders[id]?.let { page -> !page.loading && page.items.size == 40 } == true &&
                    !it.detailLoading && it.detailExtras[id]?.loading == false
            }
            if (type == "Season") compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag(tag))
            env.fixture.blockItems(id, 40).use { append ->
                scrollUntil(env, tag, "详情实际${if (type == "Season") "横向" else "纵向"}触底", horizontal = type == "Season") { append.requestArrived }
                await(env, "详情追加在途") { it.folders[id]?.loadingMore == true }
                repeat(2) { compose.onNodeWithTag(tag).performVerificationTouchInput(compose) { if (type == "Season") swipeLeft() else swipeUp() } }
                assertEquals(2, env.fixture.folderRequests(id).size)
                append.release()
                await(env, "详情实际自动补齐") { it.folders[id]?.let { page -> !page.loadingMore && page.items.size == 45 && page.nextIndex == 45 } == true }
            }
            repeat(2) { compose.onNodeWithTag(tag).performVerificationTouchInput(compose) { if (type == "Season") swipeLeft() else swipeUp() } }
            assertEquals(listOf("0", "40"), env.fixture.folderRequests(id).map { it.query["startIndex"] })
            assertEquals(45, env.vm.state.value.folders.getValue(id).items.map { it.id }.distinct().size)
            compose.onNodeWithText("加载更多", substring = true).assertDoesNotExist()
            main { env.vm.back() }
            await(env, "详情返回原页面") { it.route == Route.Home }
        }
    }

    @Test fun seasonTabsKeepEpisodeSheetAndReturnDestination() = withApp { env ->
        main { env.vm.open(MediaItem(SEASON, "合成第一季", "Season", isFolder = true, seriesId = SERIES)) }
        await(env, "季详情及父剧季列表") { !it.detailLoading && it.folders[SEASON]?.items?.size == 40 && it.folders[SERIES]?.items?.size == 40 }
        val originalRoutes = env.vm.state.value.routes
        val originalKey = env.vm.state.value.route.key
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("detail-episode-count"))
        compose.onNodeWithTag("detail-episode-count").performVerificationClick(compose)
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-sheet-tabs/$SECOND_SEASON").performVerificationClick(compose)
        await(env, "切季刷新真实详情和单集") { (it.route as? Route.Detail)?.id == SECOND_SEASON && !it.detailLoading && it.folders[SECOND_SEASON]?.items?.size == 40 }
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
        assertEquals(originalRoutes.size, env.vm.state.value.routes.size)
        assertEquals(originalKey, env.vm.state.value.route.key)
        assertTrue(env.vm.state.value.folders.getValue(SECOND_SEASON).items.all { it.id.startsWith(SECOND_SEASON) })
        // 不能通过季标签跳入另一个剧，或把 Episode 当作 Season。
        main { env.vm.switchDetailSeason(MediaItem("foreign", "其他剧季", "Season", seriesId = "other-series")) }
        assertEquals(SECOND_SEASON, (env.vm.state.value.route as Route.Detail).id)
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("detail-episode-sheet").assertDoesNotExist()
        assertEquals(SECOND_SEASON, (env.vm.state.value.route as Route.Detail).id)
        compose.onNodeWithContentDescription("返回").performVerificationClick(compose)
        await(env, "一次返回原页面") { it.route == Route.Home }
        assertEquals(originalRoutes.dropLast(1), env.vm.state.value.routes)
    }

    @Test fun seasonTitleOpensItsSeriesAndReusesTheSeriesAlreadyInTheBackStack() = withApp { env ->
        val base = env.vm.state.value.routes
        // 直接进入季：栈里没有这部剧，点标题新开剧集详情。
        main { env.vm.open(MediaItem(SEASON, "合成第一季", "Season", isFolder = true, seriesId = SERIES)) }
        await(env, "季详情") { it.route == Route.Detail(SEASON) && !it.detailLoading }
        compose.onNodeWithTag("detail-title/$SEASON").performVerificationClick(compose)
        await(env, "打开所属剧集") { it.route == Route.Detail(SERIES) && !it.detailLoading }
        assertEquals(base + Route.Detail(SEASON) + Route.Detail(SERIES), env.vm.state.value.routes)
        // 剧 → 季 → 剧：退回已有的剧集详情，不再叠一层。
        main { env.vm.open(MediaItem(SEASON, "合成第一季", "Season", isFolder = true, seriesId = SERIES)) }
        await(env, "再次进入季详情") { it.route == Route.Detail(SEASON) && !it.detailLoading }
        compose.onNodeWithTag("detail-title/$SEASON").performVerificationClick(compose)
        await(env, "退回已有剧集详情") { it.routes == base + Route.Detail(SEASON) + Route.Detail(SERIES) }
        // 不属于剧集的条目不响应。
        main { env.vm.openSeries(MediaItem("movie", "电影", "Movie", seriesId = SERIES)) }
        main { env.vm.openSeries(MediaItem("orphan", "无剧集的单集", "Episode")) }
        assertEquals(base + Route.Detail(SEASON) + Route.Detail(SERIES), env.vm.state.value.routes)
        main { env.vm.back() }
        await(env, "返回季详情") { it.route == Route.Detail(SEASON) }
        main { env.vm.back() }
        await(env, "返回原页面") { it.routes == base }
    }

    private fun selectLetter(label: String, diagnostic: Environment? = null) {
        showLetterIndex()
        diagnostic?.let { recordLetterDiagnostic(it, "$label-before-touch") }
        compose.onNodeWithTag("library-index/$label").assertVerificationDisplayed(compose).performVerificationClick(compose)
        diagnostic?.let { recordLetterDiagnostic(it, "$label-after-touch") }
    }

    /** 只记录合成账号和夹具请求；保留真实触摸失败，不用无障碍动作绕过遮挡。 */
    private fun recordLetterDiagnostic(env: Environment, stage: String, failure: Throwable? = null) {
        runCatching {
            val state = env.vm.state.value
            val browser = state.browsers[MOVIES]
            val bounds = listOf("library-index-activation", "library-index", "library-index/0", "library-index/#",
                "library-index/A", "browser-toolbar", "browser-filter", "browser-grid").joinToString("\n") { tag ->
                val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                if (node == null) "$tag absent" else compose.runOnUiThread {
                    "$tag root=${node.boundsInRoot} window=${node.boundsInWindow} " +
                        "disabled=${node.config.contains(SemanticsProperties.Disabled)} " +
                        "actions=${node.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label }}"
                }
            }
            val requests = env.fixture.itemRequests().joinToString("\n") { "${it.method} ${it.path} ${it.query}" }
            val message = "$stage route=${state.route.key} loginBusy=${state.loginBusy} reauthentication=${state.reauthenticationProfile != null} " +
                "query=${browser?.query} loading=${browser?.content?.loading} error=${browser?.content?.error} " +
                "items=${browser?.content?.items?.map { it.id }}\n$bounds\nItems requests:\n$requests"
            Log.i("PathfinLetterDiagnostic", message)
            if (failure != null) {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                val trees = roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
                val file = File(context.cacheDir, "library-letter-failure.png")
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
                Log.e("PathfinLetterDiagnostic", "screenshot=${file.absolutePath}\n$trees", failure)
            }
        }.onFailure { diagnosticFailure ->
            if (failure != null) failure.addSuppressed(diagnosticFailure)
            else Log.e("PathfinLetterDiagnostic", "$stage diagnostic unavailable", diagnosticFailure)
        }
    }

    private fun pullSearchResultsToRefresh(stage: String) {
        val results = compose.onNodeWithTag("library-search-results")
        val input = compose.onNodeWithTag("library-search-input")
        VerificationDisplay.awaitWindowReady(compose, stage, results, input)
        val nodes = listOf(results, input).map { it.fetchSemanticsNode() }
        val (listBounds, inputBounds) = compose.runOnUiThread { nodes.map { it.boundsInRoot } }
        val clearance = 12f * compose.activity.resources.displayMetrics.density
        // 结果网格绘制在标题栏/输入卡片后面，不能再从全页固定 15%（可能是输入框）发 DOWN。
        val startY = maxOf(listBounds.height * .15f, inputBounds.bottom - listBounds.top + clearance)
        val endY = listBounds.height * .95f
        assertTrue("真实下拉必须落在输入卡片下面且有可见拖动距离：$stage list=$listBounds input=$inputBounds start=$startY end=$endY",
            listBounds.top + startY > inputBounds.bottom && startY >= 0f && startY < endY && endY < listBounds.height)
        Log.i("PathfinVerificationWindow", "search-pull $stage list=$listBounds input=$inputBounds start=$startY end=$endY")
        results.assertVerificationDisplayed(compose).performVerificationTouchInput(compose) {
            swipe(Offset(width * .5f, startY), Offset(width * .5f, endY), durationMillis = 750)
        }
        compose.waitForIdle()
    }

    private fun showLetterIndex() {
        val activation = compose.onNodeWithTag("library-index-activation")
        VerificationDisplay.awaitWindowReady(compose, "library-index-activation", activation)
        val actions = activation.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val show = actions.singleOrNull { it.label == "显示字母索引" }
        if (show != null) compose.runOnIdle { assertTrue("无障碍入口必须真正唤出索引", show.action()) }
        else assertTrue("已展开的索引必须提供隐藏入口", actions.any { it.label == "隐藏字母索引" })
        // 不推进 4 秒自动隐藏时钟；后续触摸仍走实际 activation 手势链路。
        VerificationDisplay.awaitWindowReady(compose, "library-index-visible", compose.onNodeWithTag("library-index"))
    }

    /** 保持首页路由，避免短页夹具被 UI 近尾部预取；浏览查询仍使用生产 VM 与真实 HTTP。 */
    private fun configureMovieBrowser(env: Environment): LibraryQuery {
        main { env.vm.configureBrowser(MOVIES, MOVIES, LibraryQuery(itemTypes = setOf("Movie"))) }
        return awaitBrowser(env, MOVIES) { it.filterOptions != null && !it.filtersLoading }.query
    }

    private fun restoreCached(env: Environment, query: LibraryQuery, expected: ContentState) {
        val before = env.fixture.itemRequests().size
        main {
            env.vm.changeBrowserQuery(MOVIES, query)
            val restored = env.vm.state.value.browsers.getValue(MOVIES)
            assertEquals("changeBrowserQuery 返回前就必须恢复目标查询", query, restored.query)
            assertEquals("同一 Main 调用中必须恢复干净的目标首屏", expected, restored.content)
            assertFalse(restored.content.loading)
            assertFalse(restored.content.loadingMore)
            assertFalse(restored.content.retryAppend)
            assertNull(restored.content.error)
            assertEquals("缓存命中不能发出 Items 请求", before, env.fixture.itemRequests().size)
        }
        compose.waitForIdle()
        assertEquals("重组后缓存命中仍不能补发 Items", before, env.fixture.itemRequests().size)
    }

    private fun scrollUntil(env: Environment, tag: String, message: String, horizontal: Boolean = false, predicate: () -> Boolean) {
        val list = compose.onNodeWithTag(tag)
        VerificationDisplay.awaitWindowReady(compose, message, list)
        repeat(24) {
            env.fixture.assertHealthy()
            if (predicate()) return
            list.performVerificationTouchInput(compose) {
                if (horizontal) swipeLeft() else swipeUp()
            }
            compose.waitForIdle()
        }
        await(env, message) { predicate() }
    }

    private fun touchDiscovery(stage: String, target: SemanticsNodeInteraction) {
        VerificationDisplay.awaitWindowReady(compose, stage, target)
        target.performVerificationClick(compose)
    }

    private fun openLibrary(env: Environment, library: MediaItem) {
        main { env.vm.open(library) }
        awaitBrowser(env, library.id) { it.filterOptions != null && !it.filtersLoading && it.content.nextIndex == it.content.total }
        VerificationDisplay.awaitWindowReady(compose, "loaded-library", compose.onNodeWithTag("library-search-open"))
        compose.onNodeWithContentDescription("首页").assertDoesNotExist()
    }

    private fun openBrowserItem(id: String) {
        val tag = "poster-title/$id"
        // 推进动画短暂保留出发页；同一专辑在两页出现时先等旧页退出，避免点到动画中的副本。
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        val title = compose.onNodeWithTag(tag, useUnmergedTree = true)
        VerificationDisplay.awaitWindowReady(compose, "open-library-item-$id", title)
        title.assertVerificationDisplayed(compose).performVerificationClick(compose)
    }

    /** PathfinApp 组件宿主不创建播放器；这里验证真实 UI 点击、HTTP 协商及可消费 PlaybackRequest。 */
    private fun assertPlayableRequest(env: Environment, id: String, type: String, streamPath: String) {
        await(env, "完整媒体详情必须可供播放") {
            it.route == Route.Detail(id) && !it.detailLoading && it.details[id]?.type == type
        }
        val play = compose.onNodeWithTag("detail-play")
        VerificationDisplay.awaitWindowReady(compose, "play-library-item-$id", play)
        play.assertVerificationDisplayed(compose).performVerificationClick(compose)
        val playing = await(env, "播放操作必须完成真实来源解析，不能停在无入口或不可播放错误") {
            it.playbackError?.let { error -> throw AssertionError(error) }
            it.activePlayback?.item?.id == id && !it.preparingPlay && it.pendingPlayback == null
        }
        val request = checkNotNull(playing.activePlayback)
        assertEquals(type, request.item.type)
        assertEquals("supported-source-$id", request.source.id)
        assertEquals(streamPath, URI(request.stream.uri).path)
        assertTrue(request.stream.trustedServer)
        main { env.vm.playerClosed(0) }
    }

    /** 搜索框没有单独的提交按钮，统一经键盘搜索键提交。 */
    private fun submit(term: String) {
        val input = compose.onNodeWithTag("library-search-input")
        VerificationDisplay.awaitWindowReady(compose, "library-search-input", input)
        input.performTextReplacement(term)
        input.performImeAction()
    }

    private fun assertInput(expected: String) = assertEquals(expected,
        compose.onNodeWithTag("library-search-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

    private fun assertScoped(request: RequestRecord, parent: String, type: String, term: String, start: Int) {
        assertEquals("GET", request.method)
        assertEquals(parent, request.query["parentId"])
        assertEquals(type, request.query["includeItemTypes"])
        assertEquals(term, request.query["searchTerm"])
        assertEquals(start.toString(), request.query["startIndex"])
        assertEquals(if (start == 0) "40" else "100", request.query["limit"])
        assertEquals("true", request.query["recursive"])
        assertNotEquals("false", request.query["enableTotalRecordCount"])
    }

    private data class Environment(val vm: AppViewModel, val fixture: SearchFixture)

    private fun withApp(supportedLibraries: Boolean = false, block: (Environment) -> Unit) {
        check(context.packageName == VERIFICATION_PACKAGE)
        val prefs = AppPreferencesStore(context)
        val previous = prefs.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val loader = ImageLoader.Builder(context).diskCache(null).build()
        val fixture = SearchFixture(supportedLibraries)
        var failure: Throwable? = null
        try {
            clearSessions()
            prefs.save(AppPreferences())
            val vm = main { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java] }
            val env = Environment(vm, fixture)
            await(env, "隔离会话恢复结束") { !it.restoringSession }
            assertNull(vm.state.value.session)
            compose.setContent {
                val state by vm.state.collectAsState()
                val saved = rememberSaveableStateHolder()
                PathfinTheme(ThemePreference.LIGHT) {
                    Surface(Modifier.fillMaxSize()) { PathfinApp(vm, state, saved, posterLoader = loader) }
                }
            }
            main { vm.login(fixture.baseUrl, USERNAME, PASSWORD, allowHttp = true, displayName = "Library search fixture") }
            await(env, "合成登录与首页完成") { it.session?.serverId == SERVER_ID && it.home.loaded && !it.home.loading && !it.loginBusy }
            block(env)
            fixture.assertHealthy()
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                main { owner.viewModelStore.clear() }
                fixture.close()
                loader.shutdown()
                clearSessions()
                prefs.save(previous)
            }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun awaitBrowser(env: Environment, key: String, predicate: (BrowserUiState) -> Boolean = { true }): BrowserUiState =
        await(env, "浏览/搜索请求完成") { state -> state.browsers[key]?.let {
            if (!it.content.loading && !it.content.loadingMore) it.content.error?.let { error -> throw AssertionError(error) }
            !it.content.loading && !it.content.loadingMore && predicate(it)
        } == true }.browsers.getValue(key)

    private fun await(env: Environment, message: String, predicate: (AppState) -> Boolean): AppState {
        try {
            compose.waitUntil(WAIT_MS) {
                env.fixture.assertHealthy()
                val state = env.vm.state.value
                if (!state.loginBusy) state.loginError?.let { throw AssertionError(it) }
                state.home.error?.let { throw AssertionError(it) }
                predicate(state)
            }
        } catch (error: Throwable) {
            env.fixture.assertHealthy()
            throw AssertionError("$message；route=${env.vm.state.value.route.key}，items请求=${env.fixture.itemRequests().size}，搜索请求=${env.fixture.searchRequests().size}", error)
        }
        compose.waitForIdle()
        return env.vm.state.value
    }

    private fun clearSessions() {
        check(context.packageName == VERIFICATION_PACKAGE)
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private data class RequestRecord(val method: String, val path: String, val query: Map<String, String>) {
        fun isSuggestion() = path == "/Items" && query["limit"] == "8"
    }

    private class SearchGate : Closeable {
        private val arrival = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val complete = CountDownLatch(1)
        val requestArrived get() = arrival.count == 0L
        val finished get() = complete.count == 0L
        fun awaitRelease() { arrival.countDown(); check(release.await(WAIT_MS * 2, TimeUnit.MILLISECONDS)) }
        fun responseFinished() = complete.countDown()
        fun release() = release.countDown()
        override fun close() = release()
    }

    /** 小型专用服务：认证、首页、筛选与真实分页 Items，所有数据只存在本进程内存。 */
    private class SearchFixture(private val supportedLibraries: Boolean = false) : Closeable {
        private val records = ConcurrentLinkedQueue<RequestRecord>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val gates = ConcurrentHashMap<String, SearchGate>()
        private val itemGates = ConcurrentHashMap<String, SearchGate>()
        private val failedItems = ConcurrentHashMap<String, Boolean>()
        private val browsePages = ConcurrentHashMap<String, Pair<List<Int>, Int>>()
        private val favoriteIds = ConcurrentHashMap.newKeySet<String>()
        private val closed = AtomicBoolean()
        private val workers = Executors.newCachedThreadPool { Thread(it, "pathfin-library-search-http").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept()
                sockets.add(socket)
                workers.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) errors.add(error) }
        }, "pathfin-library-search-accept").apply { isDaemon = true; start() }

        fun itemRequests() = records.filter { it.path == "/Items" }
        /** 库内建议固定取 8 条，与 40 条一页的正式浏览/搜索分开计数，互不影响原有请求契约。 */
        fun suggestionRequests() = itemRequests().filter { it.isSuggestion() }
        fun searchRequests() = itemRequests().filter { "searchTerm" in it.query && !it.isSuggestion() }
        fun browseRequests() = itemRequests().filter { "searchTerm" !in it.query && it.query["parentId"] in setOf(MOVIES, SHOWS) && !it.isSuggestion() }
        fun folderRequests(parent: String) = itemRequests().filter { it.query["parentId"] == parent && it.query["filters"] != "IsResumable" }
        fun filterRequests() = records.count { it.path == "/Items/Filters" }
        fun favoriteRequests() = records.filter { it.path.startsWith("/UserFavoriteItems/") }
        fun assertHealthy() { errors.peek()?.let { throw AssertionError("库内搜索 fixture 失败", it) } }
        fun blockSearch(term: String) = SearchGate().also { check(gates.putIfAbsent(term, it) == null) }
        fun blockAuthentication() = SearchGate().also { check(itemGates.putIfAbsent("authentication", it) == null) }
        fun blockItems(term: String, start: Int, initial: String? = null) = SearchGate().also {
            check(itemGates.putIfAbsent(itemKey(term, start, initial), it) == null)
        }
        fun failNextItems(term: String, start: Int, initial: String? = null) { check(failedItems.putIfAbsent(itemKey(term, start, initial), true) == null) }
        fun setBrowsePage(initial: String, start: Int, indices: List<Int>, total: Int) {
            check(start >= 0 && indices.size <= 40 && total >= 0)
            browsePages[itemKey("browse", start, initial)] = indices.toList() to total
        }
        private fun itemKey(term: String, start: Int, initial: String?) = "$term/$start/${initial.orEmpty()}"
        private fun initial(query: Map<String, String>) = when {
            query["nameStartsWith"] != null -> query["nameStartsWith"]
            query["nameStartsWithOrGreater"] == "0" && query["nameLessThan"] == ":" -> "0"
            query["nameLessThan"] == "A" -> "#"
            else -> null
        }
        private fun requestKey(request: RequestRecord): String {
            val term = request.query["searchTerm"] ?: request.query["parentId"]?.takeIf { it in setOf(SERIES, SEASON, SECOND_SEASON) } ?: "browse"
            return itemKey(term, request.query["startIndex"]?.toInt() ?: 0, initial(request.query))
        }

        private fun serve(socket: Socket) {
            var gate: SearchGate? = null
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val line = input.readLine() ?: return
                    val parts = line.split(' ')
                    check(parts.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = input.readLine() ?: throw EOFException("请求头不完整")
                        if (header.isEmpty()) break
                        val colon = header.indexOf(':')
                        check(colon > 0)
                        headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..16_384)
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val n = input.read(body, offset, length - offset)
                        if (n < 0) throw EOFException("请求体不完整")
                        offset += n
                    }
                    val uri = URI(parts[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        val pair = it.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    val record = RequestRecord(parts[0], uri.path, query)
                    records.add(record)
                    if (uri.path !in setOf("/System/Info/Public", "/Users/AuthenticateByName")) {
                        check(listOf("authorization", "x-emby-authorization", "x-emby-token").any { TOKEN in headers[it].orEmpty() }) { "合成认证未传递" }
                    }
                    if (uri.path == "/Items" && !record.isSuggestion()) {
                        gate = query["searchTerm"]?.let(gates::remove) ?: itemGates.remove(requestKey(record))
                    } else if (uri.path == "/Users/AuthenticateByName") gate = itemGates.remove("authentication")
                    gate?.awaitRelease()
                    val failed = uri.path == "/Items" && !record.isSuggestion() && failedItems.remove(requestKey(record)) == true
                    val payload = (if (failed) JSONObject().put("Message", "Synthetic append failure") else response(record, String(body)))
                        .toString().toByteArray(Charsets.UTF_8)
                    val status = if (failed) "500 Internal Server Error" else "200 OK"
                    val output = connection.getOutputStream()
                    output.write(("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(payload)
                    output.flush()
                }
            } catch (error: Throwable) {
                // 已收到并记录的旧搜索可以被真正取消；不把客户端关闭连接伪装成协议错误。
                if (!closed.get() && !(gate != null && error is SocketException)) errors.add(error)
            } finally { gate?.responseFinished(); sockets.remove(socket) }
        }

        private fun response(request: RequestRecord, body: String): Any = when (request.path) {
            "/System/Info/Public" -> JSONObject().put("Id", SERVER_ID).put("ServerName", "Library search fixture").put("Version", "12.3.0")
            "/Users/AuthenticateByName" -> {
                check(request.method == "POST")
                val credentials = JSONObject(body)
                check(credentials.getString("Username") == USERNAME && credentials.getString("Pw") == PASSWORD)
                JSONObject().put("AccessToken", TOKEN).put("ServerId", SERVER_ID).put("User", user())
            }
            "/Users/Me" -> user()
            "/UserViews" -> page(buildList {
                add(library(MOVIES, "合成电影库", "movies")); add(library(SHOWS, "合成剧集库", "tvshows"))
                if (supportedLibraries) {
                    add(library(MUSIC, "合成音乐库", "music")); add(library(COLLECTIONS, "合成合集库", "boxsets"))
                    add(library(HOME_VIDEOS, "合成家庭视频库", "homevideos"))
                    add(library(MIXED, "合成混合库", null))
                }
            })
            "/UserItems/Resume" -> page(emptyList())
            "/Items/Latest" -> JSONArray(listOf(when (request.query["parentId"]) {
                MUSIC -> supportedItem(ALBUM)
                COLLECTIONS -> supportedItem(BOX_SET)
                HOME_VIDEOS -> supportedItem(HOME_VIDEO)
                MIXED -> supportedItem(MIXED_MOVIE)
                else -> media(request.query.getValue("parentId"), "home", 0)
            }))
            "/Items/Filters" -> JSONObject().put("Genres", JSONArray(listOf("SyntheticGenre")))
                .put("Tags", JSONArray(listOf("SyntheticTag"))).put("Years", JSONArray(listOf(2021))).put("OfficialRatings", JSONArray())
            "/Shows/NextUp" -> page(emptyList())
            "/Genres" -> {
                check(supportedLibraries && request.query["parentId"] == MUSIC)
                page(listOf(supportedItem(MUSIC_GENRE)))
            }
            "/Items/$SERIES/Similar", "/Items/$SEASON/Similar", "/Items/$SECOND_SEASON/Similar" -> page(emptyList())
            "/Items" -> {
                if (request.query["ids"] != null) {
                    val id = request.query.getValue("ids")
                    if (supportedLibraries && id in SUPPORTED_ITEM_TYPES) page(listOf(supportedItem(id)))
                    else if (id.startsWith("global-paged-")) {
                        val index = id.removePrefix("global-paged-").toInt()
                        check(index in 0 until 45)
                        page(listOf(media(MOVIES, "global-paged", index)))
                    } else {
                        check(id in setOf(SERIES, SEASON, SECOND_SEASON))
                        page(listOf(JSONObject().put("Id", id).put("Name", "Synthetic $id")
                            .put("Type", if (id == SERIES) "Series" else "Season").put("IsFolder", true)
                            .put("SeriesId", if (id != SERIES) SERIES else JSONObject.NULL)))
                    }
                } else if (request.query["filters"] == "IsResumable") page(emptyList())
                else if (supportedLibraries && (request.query["parentId"] in setOf(MUSIC, ALBUM, COLLECTIONS, BOX_SET, HOME_VIDEOS, MIXED, MIXED_FOLDER) ||
                        request.query["artistIds"] == ARTIST)) supportedPage(request)
                else if (request.query["sortBy"] == "IsFavoriteOrLiked,Random") {
                    check(request.query["searchTerm"] == null)
                    when (val parent = request.query["parentId"]) {
                        null -> {
                            check(request.query["includeItemTypes"] == "Movie,Series,MusicArtist" && request.query["limit"] == "20")
                            check(request.query["enableImages"] == "false")
                            page(emptyList())
                        }
                        else -> {
                            // 库内推荐：限定父库与单一类型，带封面；剧集库给出可打开详情的合成剧集。
                            check(parent in setOf(MOVIES, SHOWS) && request.query["limit"] == "8")
                            check(request.query["includeItemTypes"] == if (parent == MOVIES) "Movie" else "Series")
                            page(if (parent == SHOWS) listOf(JSONObject().put("Id", SERIES).put("Name", "Synthetic $SERIES")
                                .put("Type", "Series").put("IsFolder", true).put("ImageTags", JSONObject()).put("UserData", JSONObject()))
                                else emptyList())
                        }
                    }
                } else {
                    val parent = request.query["parentId"]
                    val term = request.query["searchTerm"] ?: parent?.takeIf { it in setOf(SERIES, SEASON, SECOND_SEASON) } ?: "browse"
                    check(term.isNotBlank())
                    if (term == "global-paged") {
                        check(parent == null && request.query["includeItemTypes"] == "Movie" && request.query["limit"] == "24")
                    } else if (parent in setOf(MOVIES, SHOWS)) {
                        check(request.query["includeItemTypes"] == if (parent == MOVIES) "Movie" else "Series")
                    } else check(parent in setOf(SERIES, SEASON, SECOND_SEASON)) { "分页缺少正确父库" }
                    val letter = initial(request.query)
                    val count = when {
                        letter != null -> 1
                        term in setOf("paged", "browse", "append-failure", "empty-tail", "global-paged", SERIES, SEASON, SECOND_SEASON) -> 45
                        term == "nothing" -> 0
                        else -> 1
                    }
                    val start = request.query.getValue("startIndex").toInt()
                    val limit = request.query.getValue("limit").toInt()
                    val configured = browsePages[requestKey(request)]
                    val items = if (term == "empty-tail" && start > 0) emptyList() else
                        (configured?.first ?: (start until minOf(start + limit, count)).toList()).map { index ->
                            if (parent in setOf(SERIES, SEASON, SECOND_SEASON)) JSONObject().put("Id", if (parent == SERIES && index == 0) SEASON else if (parent == SERIES && index == 1) SECOND_SEASON else "$parent-child-$index")
                                .put("Name", "Synthetic child $index").put("Type", if (parent == SERIES) "Season" else "Episode")
                                .put("IndexNumber", index + 1).put("ParentIndexNumber", 1).put("SeriesId", SERIES)
                                .put("IsFolder", parent == SERIES).put("ImageTags", JSONObject()).put("UserData", JSONObject())
                            else media(parent ?: MOVIES, letter?.let { "initial-$it" } ?: term, index)
                        }
                    page(items, configured?.second ?: count)
                }
            }
            else -> if (request.path.matches(Regex("/Items/global-paged-[0-9]+/Similar")) ||
                supportedLibraries && SUPPORTED_ITEM_TYPES.keys.any { request.path == "/Items/$it/Similar" }) page(emptyList())
            // 打开详情会预取播放信息；本测试不播放，返回无媒体源即可，预取结果在点播放时会被丢弃重取。
            else if (request.method == "POST" && request.path.matches(Regex("/Items/[^/]+/PlaybackInfo"))) {
                val id = request.path.split('/')[2]
                val sources = if (supportedLibraries && id in setOf(TRACK, BOX_SET_MOVIE, HOME_VIDEO, MIXED_MOVIE))
                    listOf(JSONObject().put("Id", "supported-source-$id").put("Container", "mp4")
                        .put("SupportsDirectPlay", true).put("SupportsDirectStream", true).put("MediaStreams", JSONArray()))
                    else emptyList()
                JSONObject().put("MediaSources", JSONArray(sources)).put("PlaySessionId", "library-search-prefetch")
            }
            else if (request.path.startsWith("/UserFavoriteItems/")) {
                check(request.method == "POST")
                val id = request.path.removePrefix("/UserFavoriteItems/")
                check(id.matches(Regex("initial-[A-Z]-[0-9]+")))
                favoriteIds.add(id)
                JSONObject().put("IsFavorite", true)
            } else error("未定义合成接口：${request.method} ${request.path}")
        }

        private fun user() = JSONObject().put("Id", "library-search-user").put("Name", USERNAME)
            .put("Policy", JSONObject().put("IsAdministrator", false))
        private fun library(id: String, name: String, type: String?) = JSONObject().put("Id", id).put("Name", name)
            .put("Type", "CollectionFolder").put("CollectionType", type ?: JSONObject.NULL).put("IsFolder", true)
        private fun media(parent: String, term: String, index: Int) = JSONObject().put("Id", "$term-$index")
            .put("Name", "Search $term $index").put("Type", if (parent == SHOWS) "Series" else "Movie")
            .put("ProductionYear", 2021).put("ImageTags", JSONObject()).put("UserData", JSONObject().put("IsFavorite", "$term-$index" in favoriteIds))

        private fun supportedItem(id: String): JSONObject {
            check(supportedLibraries)
            val type = SUPPORTED_ITEM_TYPES.getValue(id)
            return JSONObject().put("Id", id).put("Name", if (id == MUSIC_GENRE) "SyntheticGenre" else "Synthetic $id")
                .put("Type", type).put("IsFolder", type in setOf("MusicAlbum", "MusicArtist", "BoxSet", "Genre", "Folder"))
                .put("RunTimeTicks", 600_000_000L).put("ImageTags", JSONObject()).put("UserData", JSONObject())
        }

        private fun supportedPage(request: RequestRecord): JSONObject {
            val parent = request.query["parentId"]
            val search = request.query["searchTerm"]
            val searching = search != null || request.isSuggestion()
            val types = request.query["includeItemTypes"]?.split(',')?.toSet().orEmpty()
            val ids = when {
                request.query["artistIds"] == ARTIST -> {
                    check(parent == null && types == setOf("MusicAlbum", "Audio"))
                    listOf(ALBUM)
                }
                parent == MUSIC -> {
                    check(types == if (searching) setOf("MusicAlbum", "MusicArtist", "Audio") else setOf("MusicAlbum"))
                    if (searching) listOf(ALBUM, ARTIST, TRACK) else listOf(ALBUM)
                }
                parent == ALBUM -> { check(types == setOf("Audio")); listOf(TRACK) }
                parent == COLLECTIONS -> { check(types == setOf("BoxSet")); listOf(BOX_SET) }
                parent == BOX_SET -> { check(types.isEmpty()); listOf(BOX_SET_MOVIE) }
                parent == HOME_VIDEOS -> { check(types == setOf("Video")); listOf(HOME_VIDEO) }
                parent == MIXED -> {
                    check(request.query["recursive"] == if (searching) "true" else "false")
                    check(types == if (searching) setOf("Movie", "Series") else setOf("Folder", "Movie", "Series"))
                    if (searching) listOf(MIXED_MOVIE) else listOf(MIXED_FOLDER)
                }
                parent == MIXED_FOLDER -> {
                    check(request.query["recursive"] == if (searching) "true" else "false")
                    check(types.isEmpty())
                    if (searching) listOf(TRACK) else listOf(MIXED_MOVIE, MIXED_EPISODE, TRACK, MIXED_SUBFOLDER)
                }
                else -> error("不允许跨库读取合成媒体")
            }
            return page(ids.map(::supportedItem))
        }
        private fun page(items: List<JSONObject>, total: Int = items.size) = JSONObject().put("Items", JSONArray(items)).put("TotalRecordCount", total)

        override fun close() {
            closed.set(true)
            gates.values.forEach { it.release() }
            itemGates.values.forEach { it.release() }
            server.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            accept.join(2_000)
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val USERNAME = "synthetic-library-search-user"
        const val PASSWORD = "synthetic-library-search-password"
        const val TOKEN = "synthetic-library-search-token"
        const val SERVER_ID = "synthetic-library-search-server"
        const val MOVIES = "synthetic-movie-library"
        const val SHOWS = "synthetic-series-library"
        const val MUSIC = "synthetic-music-library"
        const val ALBUM = "synthetic-music-album"
        const val TRACK = "synthetic-music-track"
        const val ARTIST = "synthetic-music-artist"
        const val MUSIC_GENRE = "synthetic-music-genre"
        const val COLLECTIONS = "synthetic-collections-library"
        const val BOX_SET = "synthetic-box-set"
        const val BOX_SET_MOVIE = "synthetic-box-set-movie"
        const val HOME_VIDEOS = "synthetic-home-video-library"
        const val HOME_VIDEO = "synthetic-home-video"
        const val MIXED = "synthetic-mixed-library"
        const val MIXED_FOLDER = "synthetic-mixed-folder"
        const val MIXED_SUBFOLDER = "synthetic-mixed-subfolder"
        const val MIXED_MOVIE = "synthetic-mixed-movie"
        const val MIXED_EPISODE = "synthetic-mixed-episode"
        val SUPPORTED_ITEM_TYPES = mapOf(ALBUM to "MusicAlbum", TRACK to "Audio", ARTIST to "MusicArtist", MUSIC_GENRE to "Genre",
            BOX_SET to "BoxSet", BOX_SET_MOVIE to "Movie", HOME_VIDEO to "Video", MIXED_FOLDER to "Folder", MIXED_SUBFOLDER to "Folder",
            MIXED_MOVIE to "Movie", MIXED_EPISODE to "Episode")
        const val SERIES = "synthetic-paged-series"
        const val SEASON = "synthetic-paged-season"
        const val SECOND_SEASON = "synthetic-second-season"
        const val CONTROLLED_HOLD_MS = 250L
        const val WAIT_MS = 20_000L
    }
}

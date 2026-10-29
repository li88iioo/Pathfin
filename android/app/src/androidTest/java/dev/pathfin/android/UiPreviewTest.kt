package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.*
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.*
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 人工视觉验收工具，正常回归默认跳过；所有界面均调用生产 Composable。
 *
 * 先启动 fixture_server.py --with-images 并转发 API 端口，再向 .verification 测试包传入
 * 通过 android/tools/export_ui_previews.py --suite ui 启动；工具显式传入 pathfin.screenshots=true。
 * 可选 pathfin.fixtureUrl 只接受回环地址（支持 fixture 的 --base-path）。
 * pathfin.previewFrames 可用逗号指定帧名，例如 05-grid-light,13-sort-sheet,14-filter-sheet,20-category-sheet；未指定仍导出全部。
 * 每帧只发就绪通知并等待外部截图确认，PNG 由 host 从真实屏幕导出；不使用个人服务器或账号。
 */
@RunWith(AndroidJUnit4::class)
class UiPreviewTest {
    @get:Rule
    val compose = createVerificationComposeRule<ComponentActivity>()

    @Before
    fun requireExplicitPreviewRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "截图工具仅在 pathfin.screenshots=true 时执行",
            InstrumentationRegistry.getArguments().getString("pathfin.screenshots") == "true",
        )
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification") {
            "UI 截图只允许独立 verification 包，避免读取日常应用会话。"
        }
        // 只清除隔离测试包的会话，保证 AppViewModel 初始化不会连接此前保存的服务器。
        SessionStore(instrumentation.targetContext).clear()
    }

    @Test
    fun exportRepresentativeScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtureUrl = fixtureUrl()
        val blurTopBar = InstrumentationRegistry.getArguments().getString("pathfin.previewBlurTopBar") == "true"
        val session = Session(
            server = ServerConfig(fixtureUrl, allowInsecureHttp = true),
            accessToken = "fixture-token",
            userId = "fixture-user",
            userName = "测试片库",
            deviceId = "preview-device",
            serverId = "pathfin-fixture-server",
        )
        val movies = previewMovies()
        val libraries = listOf(
            previewLibrary("movies", "测试电影"),
            previewLibrary("shows", "测试剧集"),
            previewLibrary("documentaries", "测试纪录片"),
        )
        lateinit var vm: AppViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        assertEquals("截图不应读取已有登录会话", null, vm.state.value.session)

        // 提前验证图片服务，缺失 --with-images 时明确报错，避免把全占位图误当验收截图。
        (movies + libraries).forEach { item ->
            val imageUrl = requireNotNull(vm.api.imageUrl(session, item))
            verifyFixtureImage(imageUrl, vm.api.imageHeaders(session))
        }

        val home = HomeState(
            libraries = libraries,
            recent = movies,
            resume = movies.take(2),
            libraryItems = mapOf(libraries[0].id to movies.take(5), libraries[1].id to movies.drop(3), libraries[2].id to movies.reversed().take(4)),
            loaded = true,
        )
        val profiles = listOf(ServerProfile("preview-local", session, "本地测试片库", icon = "🎬"), ServerProfile("preview-other", session.copy(userName = "备用测试账号"), "备用测试片库", icon = "📺", lastPlayedAt = System.currentTimeMillis() - 3600000))
        val scrollingProfiles = profiles + List(10) { index -> ServerProfile("preview-scroll-$index",
            session.copy(userId = "preview-scroll-user-$index", userName = "合成账号 ${index + 1}"),
            "家庭片库 ${index + 1}", icon = if (index % 2 == 0) "🎞️" else "🎧") }
        val browser = BrowserUiState(content = ContentState(items = movies, total = movies.size, nextIndex = movies.size),
            query = LibraryQuery(itemTypes = setOf("Movie", "Series")), filterOptions = LibraryFilterOptions(genres = listOf("剧情", "冒险"), years = listOf(2024, 2025, 2026), officialRatings = listOf("PG-13")))
        val base = AppState(session = session, home = home, theme = ThemePreference.LIGHT, preferences = AppPreferences(blurTopBar = blurTopBar), profiles = profiles, currentProfileId = profiles.first().id,
            browsers = mapOf(libraries.first().id to browser),
            favorites = DiscoveryState(requested = true, sections = mapOf(
                DiscoveryCategory.MOVIES to ContentState(items = movies.take(5), total = 5, nextIndex = 5, queryKey = "favorites"),
                DiscoveryCategory.SERIES to ContentState(items = movies.drop(5).map { it.copy(type = "Series", isFolder = true) }, total = movies.size - 5, nextIndex = movies.size - 5, queryKey = "favorites"),
                DiscoveryCategory.PEOPLE to ContentState(items = movies.take(3).mapIndexed { i, it -> it.copy(type = "Person", name = "合成演员${i + 1}") }, total = 3, nextIndex = 3, queryKey = "favorites"))))
        val detailMovie = movies.first().copy(
            overview = movies.first().overview.repeat(4),
            people = movies.take(5).mapIndexed { index, movie -> MediaPerson(movie.id, "合成演职员${index + 1}", "角色${index + 1}", "Actor", movie.imageTag) },
            studios = listOf(MediaStudio("not-shown", "不应展示的工作室")),
            externalUrls = listOf(ExternalLink("不应展示的外链", "https://example.invalid")),
            mediaSources = listOf(MediaSource("synthetic-source", path = "/合成媒体/电影/山海之间.mkv", container = "mkv", size = 5_234_567_890,
                bitRate = 24_000_000, mediaStreams = listOf(
                    MediaStream(index = 0, type = "Video", codec = "hevc", width = 3840, height = 2160, averageFrameRate = 23.976, bitDepth = 10, videoRangeType = "HDR10", profile = "Main 10", isDefault = true),
                    MediaStream(index = 1, type = "Audio", codec = "aac", language = "zho", channels = 6, channelLayout = "5.1", sampleRate = 48000, isDefault = true),
                    MediaStream(index = 2, type = "Subtitle", codec = "ass", language = "zho", title = "简体中文", isExternal = true))))
        )
        val detailBase = base.copy(session = session.copy(isAdministrator = true, canDownload = true, canDelete = true),
            routes = listOf(Route.Home, Route.Detail(detailMovie.id)), details = mapOf(detailMovie.id to detailMovie),
            detailExtras = mapOf(detailMovie.id to DetailExtras(similar = movies.drop(1))))
        val person = movies.first().copy(type = "Person", name = "林朔 · 合成人物", originalTitle = "Lin Shuo",
            premiereDate = "1992-04-18T00:00:00Z", productionLocations = listOf("杭州, 浙江, 中国"),
            overview = "用于验证人物详情的合成资料。这位演员参与了多部电影与剧集，作品按首映时间展示。",
            externalUrls = listOf(ExternalLink("IMDb", "https://example.invalid/person")), runTimeTicks = 0)
        val personBase = detailBase.copy(routes = listOf(Route.Home, Route.Detail(person.id)),
            details = mapOf(person.id to person), detailExtras = mapOf(person.id to DetailExtras(
                movies = MediaPage(movies.take(5), 24), series = MediaPage(movies.drop(5), 6))))
        val series = movies[2].copy(type = "Series", isFolder = true, name = "远山纪事 · 测试剧集", runTimeTicks = 0, childCount = 2)
        val seasons = listOf(movies[3], movies[4]).mapIndexed { index, item ->
            item.copy(type = "Season", isFolder = true, name = "第 ${index + 1} 季", indexNumber = index + 1, runTimeTicks = 0, seriesId = series.id, seriesName = series.name, childCount = 3)
        }
        val episodes = movies.drop(5).mapIndexed { index, item ->
            item.copy(type = "Episode", name = "第 ${index + 1} 集：山路与来信", indexNumber = index + 1, parentIndexNumber = 1)
        }
        val discoveryMovies = List(30) { index ->
            val artwork = movies[index % movies.size]
            artwork.copy(
                id = "preview-more-movie-" + (index + 1).toString().padStart(2, '0'),
                name = "山海之间 · 合成影片 " + (index + 1),
                primaryImageItemId = artwork.id,
                positionTicks = 0,
            )
        }
        val searchMoviePage = ContentState(items = discoveryMovies, total = discoveryMovies.size,
            nextIndex = discoveryMovies.size, queryKey = "山海")
        val multiselectResults = DiscoveryState(requested = true, sections = mapOf(
            DiscoveryCategory.MOVIES to ContentState(items = movies.take(3), total = 3, nextIndex = 3, queryKey = "山海"),
            DiscoveryCategory.SEASONS to ContentState(items = seasons, total = seasons.size, nextIndex = seasons.size, queryKey = "山海"),
            DiscoveryCategory.EPISODES to ContentState(items = episodes, total = episodes.size, nextIndex = episodes.size, queryKey = "山海"),
        ), order = listOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS, DiscoveryCategory.EPISODES))
        val frames = listOf(
            PreviewFrame("01-servers-light", base.copy(showServers = true)),
            PreviewFrame("02-add-server-light", base.copy(showServers = true, addingServer = true)),
            PreviewFrame("03-home-light", base),
            PreviewFrame("04-libraries-light", base.copy(routes = listOf(Route.Libraries))),
            PreviewFrame(
                "05-grid-light",
                base.copy(
                    routes = listOf(Route.Libraries, Route.Folder(libraries.first())),
                    folders = mapOf(libraries.first().id to ContentState(
                        items = movies, total = movies.size, nextIndex = movies.size,
                    )),
                ),
            ),
            PreviewFrame(
                "06-detail-light",
                detailBase,
            ),
            PreviewFrame("07-settings-light", base.copy(routes = listOf(Route.Home, Route.Settings))),
            PreviewFrame("08-home-dark", base.copy(theme = ThemePreference.DARK)),
            PreviewFrame("09-detail-dark", detailBase.copy(theme = ThemePreference.DARK)),
            PreviewFrame("10-series-light", base.copy(
                routes = listOf(Route.Home, Route.Detail(series.id)), details = mapOf(series.id to series),
                folders = mapOf(series.id to ContentState(items = seasons, total = seasons.size, nextIndex = seasons.size)),
            )),
            PreviewFrame("11-season-light", base.copy(
                routes = listOf(Route.Home, Route.Detail(seasons.first().id)), details = mapOf(seasons.first().id to seasons.first()),
                folders = mapOf(series.id to ContentState(items = seasons, total = seasons.size, nextIndex = seasons.size), seasons.first().id to ContentState(items = episodes, total = episodes.size, nextIndex = episodes.size)),
            )),
            PreviewFrame("12-favorites-light", base.copy(routes = listOf(Route.Favorites))),
            PreviewFrame("13-sort-sheet", base.copy(routes = listOf(Route.Home, Route.Folder(libraries.first()))), "sort"),
            PreviewFrame("14-filter-sheet", base.copy(routes = listOf(Route.Home, Route.Folder(libraries.first()))), "filter"),
            PreviewFrame("15-player-settings", base.copy(routes = listOf(Route.Home, Route.SettingsPage("player")))),
            PreviewFrame("16-interaction-settings", base.copy(routes = listOf(Route.Home, Route.SettingsPage("interaction")))),
            PreviewFrame("17-backdrop-grid", base.copy(routes = listOf(Route.Home, Route.Folder(libraries.first())), browsers = mapOf(libraries.first().id to browser.copy(style = PosterStyle.BACKDROP)))),
            PreviewFrame("18-servers-dark", base.copy(showServers = true, theme = ThemePreference.DARK)),
            PreviewFrame("19-cache-settings", base.copy(routes = listOf(Route.Home, Route.SettingsPage("cache")))),
            PreviewFrame("20-category-sheet", base.copy(routes = listOf(Route.Home, Route.Folder(libraries.first()))), "category"),
            PreviewFrame("22-detail-more", detailBase, "detail-more"),
            PreviewFrame("23-detail-synopsis", detailBase, "synopsis"),
            PreviewFrame("24-detail-media", detailBase, "media"),
            PreviewFrame("25-detail-lower", detailBase, "detail-lower"),
            PreviewFrame("26-detail-large-type", detailBase, fontScale = 1.5f),
            PreviewFrame("27-episode-sheet", base.copy(routes = listOf(Route.Home, Route.Detail(seasons.first().id)),
                details = mapOf(seasons.first().id to seasons.first()), folders = mapOf(
                    series.id to ContentState(items = seasons, total = seasons.size, nextIndex = seasons.size),
                    seasons.first().id to ContentState(items = episodes, total = episodes.size, nextIndex = episodes.size))), "episodes"),
            PreviewFrame("28-appearance-settings", base.copy(routes = listOf(Route.Home, Route.SettingsPage("appearance")))),
            PreviewFrame("29-library-settings", base.copy(routes = listOf(Route.Home, Route.SettingsPage("library")))),
            PreviewFrame("30-library-index", base.copy(routes = listOf(Route.Home, Route.Folder(libraries.first()))), "index"),
            PreviewFrame("31-home-scrolled", base, "home-scroll"),
            PreviewFrame("32-detail-scrolled", detailBase, "detail-scrolled"),
            PreviewFrame("33-image-editor", detailBase, "image-editor"),
            PreviewFrame("34-image-search", detailBase, "image-search"),
            PreviewFrame("35-image-provider", detailBase, "image-provider"),
            PreviewFrame("36-detail-white-logo", detailBase.copy(details = mapOf(detailMovie.id to detailMovie.copy(logoTag = "white-v1")))),
            PreviewFrame("37-detail-white-logo-dark", detailBase.copy(theme = ThemePreference.DARK,
                details = mapOf(detailMovie.id to detailMovie.copy(logoTag = "white-v1")))),
            PreviewFrame("38-hidden-filter", base.copy(preferences = base.preferences.copy(hideSystemStatusBar = true),
                routes = listOf(Route.Home, Route.Folder(libraries.first()))), "filter"),
            PreviewFrame("39-hidden-home-scrolled", base.copy(preferences = base.preferences.copy(hideSystemStatusBar = true)), "home-scroll"),
            PreviewFrame("40-home-scrolled-dark", base.copy(theme = ThemePreference.DARK), "home-scroll"),
            PreviewFrame("41-image-search-end", detailBase, "image-search-end"),
            PreviewFrame("42-metadata-scrolled", detailBase.copy(routes = listOf(Route.Home, Route.MediaTools(detailMovie))), "metadata-scroll"),
            PreviewFrame("43-welcome-light", AppState(theme = ThemePreference.LIGHT)),
            PreviewFrame("44-welcome-dark", AppState(theme = ThemePreference.DARK)),
            PreviewFrame("45-welcome-account-light", AppState(theme = ThemePreference.LIGHT, serverProbe = ServerProbe(
                ServerConfig("http://192.168.1.20:8096", allowInsecureHttp = true),
                dev.pathfin.android.domain.PublicServerInfo("preview-server", "家庭影院", "10.10.3"),
                listOf(dev.pathfin.android.domain.PublicUser("u1", "阿林"), dev.pathfin.android.domain.PublicUser("u2", "yuki"))))),
            PreviewFrame("46-settings-dark", base.copy(routes = listOf(Route.Home, Route.Settings), theme = ThemePreference.DARK)),
            PreviewFrame("47-search-empty", base.copy(routes = listOf(Route.Search), search = SearchState(
                suggestionsLoaded = true, suggestions = ContentState(items = movies, total = movies.size, nextIndex = movies.size)))),
            PreviewFrame("48-search-results", base.copy(routes = listOf(Route.Search), search = SearchState(query = "山海",
                results = DiscoveryState(requested = true, sections = mapOf(
                    DiscoveryCategory.MOVIES to ContentState(items = movies, total = movies.size, nextIndex = movies.size, queryKey = "山海"),
                    DiscoveryCategory.SERIES to ContentState(items = listOf(series), total = 1, nextIndex = 1, queryKey = "山海"),
                    DiscoveryCategory.EPISODES to ContentState(items = episodes, total = episodes.size, nextIndex = episodes.size, queryKey = "山海"),
                    DiscoveryCategory.PEOPLE to base.favorites.sections.getValue(DiscoveryCategory.PEOPLE).copy(queryKey = "山海")))))),
            PreviewFrame("49-server-switcher", base, "server-switcher"),
            PreviewFrame("50-home-menu", base, "home-menu"),
            PreviewFrame("51-search-dark", base.copy(theme = ThemePreference.DARK, routes = listOf(Route.Search),
                search = SearchState(suggestionsLoaded = true, suggestions = ContentState(items = movies)))),
            PreviewFrame("52-favorites", base.copy(routes = listOf(Route.Favorites))),
            PreviewFrame("53-search-hidden-status", base.copy(routes = listOf(Route.Search),
                preferences = base.preferences.copy(hideSystemStatusBar = true),
                search = SearchState(query = "山海", categories = setOf(DiscoveryCategory.MOVIES),
                    results = base.favorites.copy(sections = base.favorites.sections.mapValues { (_, content) -> content.copy(queryKey = "山海") })))),
            PreviewFrame("54-search-multiselect", base.copy(routes = listOf(Route.Search),
                search = SearchState(query = "山海", categories = setOf(DiscoveryCategory.MOVIES, DiscoveryCategory.SEASONS, DiscoveryCategory.EPISODES),
                    results = multiselectResults))),
            PreviewFrame("55-search-more", base.copy(
                routes = listOf(Route.Search, Route.Discovery(DiscoveryCategory.MOVIES, favorite = false, query = "山海")),
                search = SearchState(query = "山海", categories = setOf(DiscoveryCategory.MOVIES),
                    results = DiscoveryState(requested = true, sections = mapOf(DiscoveryCategory.MOVIES to searchMoviePage))))),
            PreviewFrame("56-favorites-more", base.copy(
                routes = listOf(Route.Favorites, Route.Discovery(DiscoveryCategory.MOVIES, favorite = true)),
                favorites = base.favorites.copy(sections = base.favorites.sections +
                    (DiscoveryCategory.MOVIES to searchMoviePage.copy(queryKey = "favorites"))))),
            PreviewFrame("57-grid-scrolled", base.copy(
                routes = listOf(Route.Home, Route.Folder(libraries.first())),
                browsers = mapOf(libraries.first().id to browser.copy(content = searchMoviePage))), "grid-scroll"),
            PreviewFrame("58-grid-scrolled-dark", base.copy(theme = ThemePreference.DARK,
                routes = listOf(Route.Home, Route.Folder(libraries.first())),
                browsers = mapOf(libraries.first().id to browser.copy(content = searchMoviePage))), "grid-scroll"),
            PreviewFrame("59-grid-hidden-large-type", base.copy(
                preferences = base.preferences.copy(hideSystemStatusBar = true),
                routes = listOf(Route.Home, Route.Folder(libraries.first())),
                browsers = mapOf(libraries.first().id to browser.copy(content = searchMoviePage))), "grid-scroll", fontScale = 1.5f),
            PreviewFrame("60-search-scrolled", base.copy(routes = listOf(Route.Search), search = SearchState(query = "山海",
                results = base.favorites.copy(sections = base.favorites.sections.mapValues { (_, content) -> content.copy(queryKey = "山海") }))), "search-scroll"),
            PreviewFrame("61-servers-scrolled", base.copy(showServers = true, profiles = scrollingProfiles), "servers-scroll"),
            PreviewFrame("62-servers-scrolled-dark", base.copy(showServers = true, profiles = scrollingProfiles,
                theme = ThemePreference.DARK), "servers-scroll"),
            PreviewFrame("63-person-light", personBase),
            PreviewFrame("64-person-dark", personBase.copy(theme = ThemePreference.DARK)),
            PreviewFrame("65-person-large-type", personBase, fontScale = 1.5f),
        )
        val requested = InstrumentationRegistry.getArguments().getString("pathfin.previewFrames").orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        check(requested.all { name -> frames.any { it.name == name } }) { "pathfin.previewFrames 包含未知帧名" }
        val selectedFrames = if (requested.isEmpty()) frames else frames.filter { it.name in requested }
        var frame by mutableStateOf(selectedFrames.first())
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            // 独立页面状态，避免上一个截图的滚动位置或表单状态渗入下一张。
            key(frame.name) {
                PathfinTheme(frame.state.theme, frame.state.preferences.accentColor) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, frame.fontScale),
                        LocalAppPreferences provides frame.state.preferences) {
                    AppSystemBars()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        PathfinApp(vm, frame.state, rememberSaveableStateHolder())
                    }
                    }
                }
            }
        }

        selectedFrames.forEach { next ->
            compose.runOnIdle { frame = next }
            when (next.action) {
                "server-switcher" -> compose.onNodeWithContentDescription("切换服务器").performClick()
                "home-menu" -> compose.onNodeWithTag("home-more").performClick()
                "sort" -> compose.onNodeWithTag("browser-sort").performClick()
                "filter" -> compose.onNodeWithTag("browser-filter").performClick()
                "category" -> compose.onNodeWithTag("browser-category").performClick()
                "detail-more" -> compose.onNodeWithContentDescription("更多操作").performClick()
                "image-editor", "image-search", "image-provider", "image-search-end" -> {
                    compose.onNodeWithTag("detail-more").performClick()
                    VerificationDisplay.awaitWindowReady(compose, "preview-more", compose.onNodeWithTag("detail-edit-images"))
                    compose.onNodeWithTag("detail-edit-images").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-image-search/Primary").fetchSemanticsNodes().isNotEmpty() }
                    VerificationDisplay.awaitWindowReady(compose, "preview-image-editor", compose.onNodeWithTag("media-image-search/Primary"))
                    if (next.action != "image-editor") {
                        compose.onNodeWithTag("media-image-search/Primary").performClick()
                        compose.waitUntil(10_000) { compose.onAllNodesWithTag("remote-image-result").fetchSemanticsNodes().isNotEmpty() }
                        VerificationDisplay.awaitWindowReady(compose, "preview-image-search", compose.onNodeWithTag("remote-image-search"))
                        if (next.action == "image-provider") compose.onNodeWithTag("remote-image-provider").performClick()
                        if (next.action == "image-search-end") {
                            compose.onNodeWithTag("remote-image-results").performScrollToIndex(7)
                            compose.onAllNodesWithText("420 × 630", substring = false).onLast().assertIsDisplayed()
                        }
                    }
                }
                "synopsis" -> { compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("detail-synopsis")); compose.onNodeWithTag("detail-synopsis").performClick() }
                "media" -> { compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("detail-media-info-open")); compose.onNodeWithTag("detail-media-info-open").performClick() }
                "detail-lower" -> compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("detail-file-info"))
                "index" -> {
                    val action = compose.onNodeWithTag("library-index-activation").fetchSemanticsNode()
                        .config[SemanticsActions.CustomActions].single { it.label == "显示字母索引" }
                    compose.runOnIdle { assertTrue(action.action()) }
                }
                "metadata-scroll" -> {
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("metadata-form").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("metadata-form").performScrollToIndex(5)
                    compose.onNodeWithTag("metadata-save").assertIsDisplayed()
                }
                "detail-scrolled" -> compose.onNodeWithTag("detail-content").performScrollToIndex(1)
                "home-scroll" -> compose.onNodeWithTag("home-list").performScrollToIndex(2)
                "servers-scroll" -> compose.onNodeWithTag("server-list").performScrollToIndex(5)
                "grid-scroll" -> compose.onNodeWithTag("browser-grid").performScrollToIndex(6)
                "search-scroll" -> compose.onNodeWithTag("search-results").performScrollToIndex(1)
                "episodes" -> { compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("detail-episode-count")); compose.onNodeWithTag("detail-episode-count").performClick() }
            }
            compose.waitForIdle()
            // 网络图片由 Coil 在 Compose 时钟外解码；仅截图工具等待其短暂完成。
            SystemClock.sleep(900)
            compose.waitForIdle()
            val visibleWindow = when (next.action) {
                "image-editor" -> compose.onNodeWithTag("media-image-editor-sheet")
                "image-search", "image-search-end" -> compose.onNodeWithTag("remote-image-search")
                "image-provider" -> compose.onNodeWithTag("remote-image-provider/Fixture Studio")
                null, "detail-lower", "index", "home-scroll", "servers-scroll", "detail-scrolled", "metadata-scroll", "grid-scroll", "search-scroll" -> compose.onRoot()
                "server-switcher", "home-menu" -> compose.onNode(isPopup())
                else -> compose.onNode(isDialog())
            }
            VerificationDisplay.awaitWindowReady(compose, "preview/${next.name}", visibleWindow)
            if (next.action == "detail-scrolled") compose.runOnIdle {
                assertTrue("浅色详情滚过头图后，真实系统状态栏必须同步变为深色字",
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
            }
            ExternalScreenshotCheckpoint.capture(next.name)
        }
    }

    private fun setLoginField(tag: String, value: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) {
            assertTrue("测试表单无法填入 $tag", it(AnnotatedString(value)))
        }
    }

    private fun fixtureUrl(): String {
        val value = InstrumentationRegistry.getArguments().getString("pathfin.fixtureUrl")
            ?.trim()?.trimEnd('/') ?: "http://127.0.0.1:8097"
        val uri = URI(value)
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1")) {
            "截图 fixtureUrl 只接受本机 HTTP 回环地址。"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "截图 fixtureUrl 不能包含凭据、查询参数或片段。"
        }
        return value
    }

    private fun verifyFixtureImage(url: String, headers: Map<String, String>) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            assertEquals("测试海报不可用，请启动 fixture_server.py --with-images 并转发 API 端口", 200, connection.responseCode)
            assertTrue("测试端点必须返回图片", connection.contentType?.startsWith("image/") == true)
            connection.inputStream.use { input -> assertTrue("测试图片为空", input.read() != -1) }
        } finally {
            connection.disconnect()
        }
    }

    private fun previewLibrary(suffix: String, title: String) = MediaItem(
        id = "preview-library-$suffix", name = title, type = "CollectionFolder",
        isFolder = true, imageTag = "ui-preview-v1", collectionType = when (suffix) {
            "movies" -> "movies"; "shows" -> "tvshows"; else -> null
        },
    )

    private fun previewMovies(): List<MediaItem> = listOf(
        "山海之间", "零点列车", "漫长的夏日", "远方来信", "晴空档案", "城市漫游", "静谧海岸", "星野旅人",
    ).mapIndexed { index, title ->
        MediaItem(
            id = "preview-movie-${(index + 1).toString().padStart(2, '0')}",
            name = "$title · 测试影片",
            type = "Movie",
            overview = "测试片库中的虚构影片，仅用于 Pathfin 界面验收。海报由本地程序生成，无个人媒体或服务器数据。" +
                "这段简介用于检查详情页的文字层级、换行与操作区布局。",
            year = 2026 - index % 3,
            runTimeTicks = (96L + index * 7L) * 60L * 10_000_000L,
            positionTicks = if (index < 2) (24L + index * 11L) * 60L * 10_000_000L else 0L,
            imageTag = "ui-preview-v1",
            communityRating = 8.6 - index * 0.2,
            officialRating = "PG-13",
            genres = listOf("剧情", "冒险"),
        )
    }

    private data class PreviewFrame(val name: String, val state: AppState, val action: String? = null, val fontScale: Float = 1f)
}

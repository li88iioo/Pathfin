package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.DetailEpisodePicker
import dev.pathfin.android.ui.DetailSeasonRow
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实详情组件与滚动；只用无图的合成媒体，不读取账号、不请求个人服务。 */
@RunWith(AndroidJUnit4::class)
class DetailEpisodesUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val session = Session(ServerConfig("https://detail-episodes.invalid/jellyfin"),
        "synthetic-token", "synthetic-user", "合成用户", "synthetic-device")
    private val api = JellyfinApi()
    private var loader: ImageLoader? = null

    @After fun releaseLoader() { loader?.shutdown() }

    @Test fun seasonPostersKeepThreeEqualPortraitSlotsAndOnlyShowActualCountAndYear() {
        val seasons = mutableStateOf(listOf(season(1).copy(childCount = 24, year = 2022),
            season(2).copy(childCount = 12, year = 2023), season(3).copy(childCount = 0)))
        val unknown = season(4)
        var opened: MediaItem? = null
        show {
            Column(Modifier.width(330.dp)) {
                DetailSeasonRow(seasons.value, session, api, { opened = it })
                DetailSeasonRow(listOf(unknown), session, api, { opened = it })
            }
        }
        val first = bounds("detail-season-poster/season-1")
        (2..4).forEach { index ->
            val other = bounds("detail-season-poster/season-$index")
            assertEquals("最后一行也必须保留三列宽度", first.width, other.width, 1f)
            assertEquals("季海报必须保持2:3", other.width * 1.5f, other.height, 1f)
        }
        compose.onNodeWithTag("detail-season-count/season-1", true).assertTextEquals("共24集 · 2022")
        compose.onNodeWithTag("detail-season-count/season-3", true).assertTextEquals("共0集")
        compose.onNodeWithTag("detail-season-count/season-4", true).assertTextEquals(" ")
        val originalMetadataBounds = bounds("detail-season-count/season-2")
        compose.runOnIdle { seasons.value = seasons.value.map {
            if (it.id == "season-2") it.copy(name = "这是会省略的很长的合成季标题", childCount = 100, year = 2025) else it
        } }
        assertEquals(first, bounds("detail-season-poster/season-1"))
        assertEquals(originalMetadataBounds, bounds("detail-season-count/season-2"))
        compose.onNodeWithTag("detail-season/season-2").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(seasons.value[1], opened) }
    }

    @Test fun sheetStaysOpenAcrossRealSeasonChangeWithoutOldEpisodesAndSwitchesBothInlineModes() {
        val firstSeason = season(1)
        val secondSeason = season(2)
        val selected = mutableStateOf(firstSeason)
        val firstEpisode = episode(1, firstSeason).copy(played = true)
        val secondEpisode = episode(2, secondSeason).copy(positionTicks = 900_000_000L)
        val page = mutableStateOf(ContentState(listOf(firstEpisode), total = 1, nextIndex = 1))
        var selectedCallback: MediaItem? = null
        var opened: MediaItem? = null
        showPicker(selected, page, seasons = { listOf(firstSeason, secondSeason) }, onSeason = {
            selectedCallback = it
            selected.value = it
            page.value = ContentState(loading = true)
        }, onOpen = { opened = it })
        openSheet()
        // 抽屉默认整页数字网格。
        compose.onNodeWithTag("detail-episode-sheet-grid").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-sheet-number/${firstEpisode.id}").assertVerificationDisplayed(compose)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已观看"))
        compose.onNodeWithTag("detail-episode-sheet-tabs/${secondSeason.id}").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(secondSeason, selectedCallback) }
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-sheet-tabs/${secondSeason.id}").assertIsSelected()
        compose.onNodeWithTag("detail-episode-sheet-number/${firstEpisode.id}").assertDoesNotExist()
        compose.onNodeWithTag("detail-episode-number/${firstEpisode.id}").assertDoesNotExist()
        compose.runOnIdle { page.value = ContentState(listOf(secondEpisode), total = 1, nextIndex = 1) }
        compose.onNodeWithTag("detail-episode-sheet-number/${secondEpisode.id}").assertVerificationDisplayed(compose)
        // 原地切到缩略图列表，抽屉不关闭、季不变。
        compose.onNodeWithTag("detail-episode-sheet-layout").performVerificationClick(compose)
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-sheet-grid").assertDoesNotExist()
        compose.onNodeWithTag("detail-episode-sheet-tabs/${secondSeason.id}").assertIsSelected()
        compose.onNodeWithTag("detail-episode-sheet-item/${secondEpisode.id}").assertVerificationDisplayed(compose)
        compose.onNode(hasText("观看至 1:30") and hasAnyAncestor(hasTestTag("detail-episode-sheet-list"))).assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-sheet-item/${secondEpisode.id}").performVerificationClick(compose)
        compose.onNodeWithTag("detail-episode-sheet").assertDoesNotExist()
        compose.runOnIdle { assertSame(secondEpisode, opened); opened = null }
        // 正文与抽屉共用形态：抽屉选了列表，正文即为卡片。
        compose.onNodeWithTag("detail-episode-cards").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-card/${secondEpisode.id}").performVerificationClick(compose)
        compose.runOnIdle { assertSame(secondEpisode, opened); opened = null }
        // 正文没有第二个切换入口，切回数字只能在抽屉里。
        compose.onNodeWithContentDescription("切换为数字选集").assertDoesNotExist()
        openSheet()
        compose.onNodeWithTag("detail-episode-sheet-layout").performVerificationClick(compose)
        compose.onNodeWithTag("detail-episode-sheet-grid").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-numbers").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-episode-cards").assertDoesNotExist()
        compose.onNodeWithTag("detail-episode-sheet-number/${secondEpisode.id}").performVerificationClick(compose)
        compose.onNodeWithTag("detail-episode-sheet").assertDoesNotExist()
        compose.runOnIdle { assertSame(secondEpisode, opened) }
    }

    @Test fun sheetNumberGridPagesOncePerCursorAndShowsFailureInPlace() {
        val selected = mutableStateOf(season(1))
        val episodes = (1..300).map { episode(it, selected.value) }
        val page = mutableStateOf(ContentState(episodes.take(200), total = 300, nextIndex = 200))
        val requests = AtomicInteger()
        showPicker(selected, page, onMore = {
            requests.incrementAndGet()
            page.value = page.value.copy(loadingMore = true)
        })
        openSheet()
        compose.runOnIdle { assertEquals("首屏未近尾部不预取", 0, requests.get()) }
        compose.onNodeWithTag("detail-episode-sheet-grid").performScrollToIndex(199)
        compose.waitUntil(5_000) { requests.get() == 1 }
        compose.runOnIdle { page.value = page.value.copy(loadingMore = false, error = "合成续页失败") }
        compose.onNodeWithTag("detail-episode-sheet-grid").performScrollToIndex(0).performScrollToIndex(200)
        compose.onNode(hasTestTag("detail-episode-error") and hasAnyAncestor(hasTestTag("detail-episode-sheet-grid")))
            .assertVerificationDisplayed(compose).assertTextEquals("合成续页失败")
        compose.runOnIdle { assertEquals("失败时滚动不能循环重试", 1, requests.get()) }
        compose.runOnIdle { page.value = ContentState(episodes, total = 300, nextIndex = 300) }
        compose.onNodeWithTag("detail-episode-sheet-grid").performScrollToIndex(299)
        compose.onNodeWithTag("detail-episode-sheet-number/${episodes.last().id}").assertVerificationDisplayed(compose)
        compose.runOnIdle { assertEquals("已读到总数不再请求", 1, requests.get()) }
    }

    @Test fun refreshAndFailureKeepNumberCellsInPlaceWithoutInventingACurrentEpisode() {
        val selected = mutableStateOf(season(1))
        val episodes = (1..4).map { episode(it, selected.value).copy(played = it == 2) }
        val page = mutableStateOf(ContentState(episodes, total = 4, nextIndex = 4))
        showPicker(selected, page)
        val tag = "detail-episode-number/${episodes[1].id}"
        val before = bounds(tag)
        assertFalse("观看状态不能冒充当前播放选择", compose.onNodeWithTag(tag)
            .fetchSemanticsNode().config.contains(SemanticsProperties.Selected))
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已观看"))
        compose.runOnIdle { page.value = page.value.copy(loading = true) }
        assertEquals(before, bounds(tag))
        compose.runOnIdle { page.value = page.value.copy(loading = false, error = "合成分页暂时不可用") }
        compose.onNodeWithTag("detail-episode-error").assertTextEquals("合成分页暂时不可用")
        assertEquals(before, bounds(tag))
        compose.onNodeWithText("加载更多").assertDoesNotExist()
    }

    @Test fun numberRowAutomaticallyPagesOncePerCursorAndStopsOnErrorUntilRefresh() {
        val selected = mutableStateOf(season(1))
        val episodes = (1..45).map { episode(it, selected.value) }
        val page = mutableStateOf(ContentState(episodes.take(40), total = 45, nextIndex = 40))
        val requests = AtomicInteger()
        showPicker(selected, page, onMore = {
            assertEquals(40, page.value.nextIndex)
            requests.incrementAndGet()
            page.value = page.value.copy(loadingMore = true)
        })
        compose.onNodeWithTag("detail-episode-numbers").performScrollToIndex(39)
        compose.waitUntil(5_000) { requests.get() == 1 }
        compose.runOnIdle { page.value = page.value.copy(loadingMore = false, error = "合成续页失败") }
        compose.onNodeWithTag("detail-episode-numbers").performScrollToIndex(0).performScrollToIndex(39)
        compose.runOnIdle { assertEquals("失败时滚动不能循环重试", 1, requests.get()) }
        compose.runOnIdle { page.value = page.value.copy(loading = true, error = null) }
        compose.waitForIdle()
        compose.runOnIdle { page.value = page.value.copy(loading = false) }
        compose.waitUntil(5_000) { requests.get() == 2 }
        compose.runOnIdle { page.value = ContentState(episodes, total = 45, nextIndex = 45) }
        compose.onNodeWithTag("detail-episode-numbers").performScrollToIndex(44)
        compose.runOnIdle { assertEquals("已读到服务器总数不再请求", 2, requests.get()) }
        compose.onNodeWithTag("detail-episode-number/${episodes.last().id}").assertVerificationDisplayed(compose)
    }

    @Test fun sheetPagesSeasonsPastFortyAndDoesNotPageTheHiddenBackgroundTabs() {
        val allSeasons = (1..45).map(::season)
        val selected = mutableStateOf(allSeasons.first())
        val seasonsPage = mutableStateOf(ContentState(allSeasons.take(40), total = 45, nextIndex = 40))
        val page = mutableStateOf(ContentState(listOf(episode(1, selected.value)), total = 1, nextIndex = 1))
        val requests = AtomicInteger()
        var picked: MediaItem? = null
        showPicker(selected, page, seasons = { seasonsPage.value.items }, seasonsPage = seasonsPage,
            onSeason = { picked = it; selected.value = it; page.value = ContentState(loading = true) }, onSeasonsMore = {
                requests.incrementAndGet()
                seasonsPage.value = seasonsPage.value.copy(loadingMore = true)
            })
        compose.onNodeWithTag("detail-episode-tabs").performScrollToIndex(39)
        compose.waitUntil(5_000) { requests.get() == 1 }
        openSheet()
        compose.runOnIdle { seasonsPage.value = seasonsPage.value.copy(loading = true, loadingMore = false) }
        compose.waitForIdle()
        compose.runOnIdle { seasonsPage.value = seasonsPage.value.copy(loading = false) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("sheet开启后正文季tab不能偷偷续页", 1, requests.get()) }
        compose.onNodeWithTag("detail-episode-sheet-tabs").performScrollToIndex(39)
        compose.waitUntil(5_000) { requests.get() == 2 }
        compose.runOnIdle { seasonsPage.value = ContentState(allSeasons, total = 45, nextIndex = 45) }
        compose.onNodeWithTag("detail-episode-sheet-tabs").performScrollToIndex(44)
        compose.onNodeWithTag("detail-episode-sheet-tabs/season-45").performVerificationClick(compose).assertIsSelected()
        compose.runOnIdle { assertSame(allSeasons.last(), picked); assertEquals(2, requests.get()) }
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
    }

    @Test fun busyDisablesBothSeasonTabsAndEachResumesTheRealCallbackWhenReady() {
        val firstSeason = season(1)
        val secondSeason = season(2)
        val selected = mutableStateOf(firstSeason)
        val busy = mutableStateOf(true)
        val page = mutableStateOf(ContentState(listOf(episode(1, firstSeason)), total = 1, nextIndex = 1))
        val callbacks = mutableListOf<MediaItem>()
        showPicker(selected, page, seasons = { listOf(firstSeason, secondSeason) },
            seasonSwitchEnabled = { !busy.value }, onSeason = {
                callbacks += it
                selected.value = it
                page.value = ContentState(listOf(episode(1, it)), total = 1, nextIndex = 1)
            })
        val inline = compose.onNodeWithTag("detail-episode-tabs/${secondSeason.id}")
        VerificationDisplay.awaitWindowReady(compose, "detail-season-busy-inline", inline)
        val inlineBounds = bounds("detail-episode-tabs/${secondSeason.id}")
        inline.assertIsNotEnabled().performVerificationTouchInput(compose) { click() }
        compose.runOnIdle { assertTrue(callbacks.isEmpty()); assertSame(firstSeason, selected.value) }
        compose.runOnIdle { busy.value = false }
        assertEquals("恢复可操作时标签不得跳位", inlineBounds, bounds("detail-episode-tabs/${secondSeason.id}"))
        inline.assertIsEnabled().performVerificationClick(compose).assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(secondSeason), callbacks); busy.value = true }

        openSheet()
        val sheetTab = compose.onNodeWithTag("detail-episode-sheet-tabs/${firstSeason.id}")
        sheetTab.assertIsNotEnabled().performVerificationTouchInput(compose) { click() }
        compose.runOnIdle { assertEquals(listOf(secondSeason), callbacks); assertSame(secondSeason, selected.value) }
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
        compose.runOnIdle { busy.value = false }
        sheetTab.assertIsEnabled().performVerificationClick(compose).assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(secondSeason, firstSeason), callbacks); assertSame(firstSeason, selected.value) }
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
    }

    private fun showPicker(
        selected: MutableState<MediaItem>,
        page: MutableState<ContentState>,
        seasons: () -> List<MediaItem> = { listOf(selected.value) },
        onSeason: (MediaItem) -> Unit = {},
        onOpen: (MediaItem) -> Unit = {},
        onMore: () -> Unit = {},
        seasonsPage: MutableState<ContentState>? = null,
        onSeasonsMore: () -> Unit = {},
        seasonSwitchEnabled: () -> Boolean = { true },
    ) = show {
        val content = page.value
        DetailEpisodePicker(selected.value, content.items, content.total, content.nextIndex,
            content.loading, content.loadingMore, content.error, seasons(), session, api,
            onSeason, onOpen, onMore, seasonsPage = seasonsPage?.value, onLoadSeasonsMore = onSeasonsMore,
            seasonSwitchEnabled = seasonSwitchEnabled())
    }

    private fun show(content: @Composable () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification") { "选集组件回归只允许独立verification包" }
        val images = ImageLoader.Builder(context).diskCache(null).build()
        loader = images
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides images, LocalAppPreferences provides AppPreferences()) {
                    Surface(Modifier.fillMaxSize(), content = content)
                }
            }
        }
    }

    private fun openSheet() {
        val button = compose.onNodeWithTag("detail-episode-count")
        VerificationDisplay.awaitWindowReady(compose, "detail-episode-picker-open", button)
        button.performVerificationClick(compose)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("detail-episode-sheet").assertVerificationDisplayed(compose)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).readVerificationNode(compose) { it.boundsInRoot }
    private fun season(number: Int) = MediaItem("season-$number", "第${number}季", "Season", indexNumber = number,
        seriesId = "synthetic-series", isFolder = true)
    private fun episode(number: Int, season: MediaItem) = MediaItem("${season.id}-episode-$number", "合成第${number}集", "Episode",
        indexNumber = number, parentIndexNumber = season.indexNumber, seasonId = season.id, seriesId = season.seriesId)
}

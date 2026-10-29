package dev.pathfin.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.HomeScreen
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实首页组件；仅 verification、合成条目、无封面 URL，不读账号、不联网。 */
@RunWith(AndroidJUnit4::class)
class HomePresentationTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val api = JellyfinApi()
    private val session = Session(ServerConfig("https://home-presentation.invalid/jellyfin"),
        "synthetic-token", "synthetic-user", "合成账号", "synthetic-device")
    private lateinit var loader: ImageLoader

    @Before fun prepare() {
        check(context.packageName == "dev.pathfin.android.verification")
        loader = ImageLoader.Builder(context).diskCache(null).build()
    }

    @After fun close() { loader.shutdown() }

    @Test fun nextUpWaitsForResumeDeduplicationAndWarmRefreshKeepsItsVisibleRow() {
        val library = MediaItem("library", "合成电影库", "CollectionFolder", isFolder = true)
        val resume = MediaItem("episode-1", "第一集", "Episode", seriesId = "series", seriesName = "同一部剧")
        val next = resume.copy(id = "episode-2", name = "第二集")
        val home = mutableStateOf(HomeState(libraries = listOf(library), loaded = true, loading = true,
            libraryItems = mapOf(library.id to listOf(movie)), nextUp = listOf(next), nextUpLoaded = true))
        showHome(home)
        val following = compose.onNodeWithText("最近入库 · ${library.name}")
        VerificationDisplay.awaitWindowReady(compose, "next-up-before-resume", following)
        val before = layoutBounds(following).top
        compose.onNodeWithTag("home-next-up").assertDoesNotExist()
        compose.runOnIdle { home.value = home.value.copy(resume = listOf(resume), resumeLoaded = true) }
        compose.onNodeWithTag("home-next-up").assertDoesNotExist()
        assertSamePosition(before, layoutBounds(following).top, "NextUp先返回不能先显示再被续播去重移除")

        compose.runOnIdle { home.value = home.value.copy(nextUp = emptyList(), nextUpLoaded = false) }
        compose.onNodeWithTag("home-next-up").assertDoesNotExist()
        compose.runOnIdle { home.value = home.value.copy(nextUp = listOf(next), nextUpLoaded = true) }
        compose.onNodeWithTag("home-next-up").assertDoesNotExist()
        assertSamePosition(before, layoutBounds(following).top, "Resume先返回也应得到相同布局")

        compose.runOnIdle {
            home.value = home.value.copy(loading = false,
                nextUp = listOf(next.copy(id = "other-next", seriesId = "other-series", seriesName = "另一部剧")))
        }
        val visible = compose.onNodeWithTag("home-next-up").performScrollTo()
        VerificationDisplay.awaitWindowReady(compose, "next-up-warm-row", visible)
        val position = layoutBounds(visible).top
        compose.runOnIdle { home.value = home.value.copy(loading = true) }
        visible.assertExists()
        assertSamePosition(position, layoutBounds(visible).top, "暖刷新不能收起已展示的接下来")
        compose.runOnIdle { home.value = home.value.copy(loading = false) }
        assertSamePosition(position, layoutBounds(visible).top, "刷新完成不能移动未变化的观看分组")
    }

    @Test fun libraryHeadingOpensTheOriginalLibraryAndOldHomeActionsAreAbsent() {
        val library = MediaItem("library", "合成电影库", "CollectionFolder", collectionType = "movies", isFolder = true)
        val collections = MediaItem("collection-library", "合成合集库", "CollectionFolder", collectionType = "boxsets", isFolder = true)
        val home = mutableStateOf(HomeState(libraries = listOf(collections, library), loaded = true,
            libraryItems = mapOf(library.id to listOf(movie))))
        var opened: MediaItem? = null
        var opens = 0
        showHome(home, onOpen = { opened = it; opens++ })
        compose.onNodeWithContentDescription(collections.name).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("最近入库 · ${collections.name}").assertDoesNotExist()

        val heading = compose.onNodeWithText("最近入库 · ${library.name}").performScrollTo()
        heading.assertIsDisplayed()
        val bounds = layoutBounds(heading)
        assertTrue("整行库标题必须保留至少48dp点击高度", bounds.height >= 48f * context.resources.displayMetrics.density - 1f)
        heading.performClick()
        compose.runOnIdle {
            assertSame("库标题应打开原库，而不是某张封面条目", library, opened)
            assertEquals("一个标题入口只能触发一次打开", 1, opens)
        }
        compose.onNodeWithText("更多").assertDoesNotExist()
        compose.onNodeWithText("接下来").assertDoesNotExist()
    }

    @Test fun homeShowsSeriesAndEpisodeWhileLongMovieTitlesKeepOneStableLine() {
        val episode = MediaItem("episode", "第17集", "Episode", seriesName = "合成剧名",
            parentIndexNumber = 1, indexNumber = 17)
        val home = mutableStateOf(HomeState(recent = listOf(episode, movie), loaded = true))
        showHome(home)
        // 首页分区有固定空间；先把被测海报栏真正滚入窗口，再比较同一位置的文字。
        compose.onNodeWithText("最近入库").performScrollTo()
        VerificationDisplay.awaitWindowReady(compose, "home-recent-title-layout",
            compose.onNodeWithTag("poster-title/${movie.id}", true), compose.onNodeWithTag("poster-subtitle/${movie.id}", true))
        compose.onNodeWithTag("poster-title/episode", true).assertTextEquals("合成剧名")
        compose.onNodeWithTag("poster-subtitle/episode", true).assertTextEquals("S1E17：第17集")
        val subtitleBefore = layoutBounds(compose.onNodeWithTag("poster-subtitle/${movie.id}", true))

        compose.runOnIdle {
            home.value = home.value.copy(recent = listOf(episode,
                movie.copy(name = "这是只在首页占一行并且必须用省略号截断的合成电影长标题")))
        }
        val title = textLayout("poster-title/${movie.id}")
        assertEquals("首页长标题实际只能绘制一行", 1, title.lineCount)
        assertTrue("长标题应真实显示省略号，而不是挤压相邻卡片", title.isLineEllipsized(0))
        // didOverflowHeight 也包含 maxLines 省略；实际行上下边界才反映垂直裁切。
        assertTrue("允许水平省略，不能垂直截掉文字",
            title.getLineTop(0) >= -.5f && title.getLineBottom(0) <= title.size.height + .5f)
        val subtitleAfter = layoutBounds(compose.onNodeWithTag("poster-subtitle/${movie.id}", true).assertTextEquals("2026"))
        assertEquals("长短标题不能移动年份", subtitleBefore.top, subtitleAfter.top, .5f)
        assertEquals("长短标题不能改变副文案高度", subtitleBefore.height, subtitleAfter.height, .5f)
        compose.onNodeWithText("更多").assertDoesNotExist()
        compose.onNodeWithText("接下来").assertDoesNotExist()
    }

    @Test fun loadingEmptyAndLoadedLibraryShelvesKeepTheFollowingHeadingInPlace() {
        val first = MediaItem("first-library", "合成首库", "CollectionFolder", isFolder = true)
        val second = MediaItem("second-library", "合成次库", "CollectionFolder", isFolder = true)
        val home = mutableStateOf(HomeState(libraries = listOf(first, second), loading = true,
            libraryItems = mapOf(second.id to listOf(movie.copy(id = "second-movie")))))
        showHome(home, fontScale = mutableStateOf(1.6f))
        val anchor = compose.onNodeWithText("最近入库 · ${first.name}")
        val following = compose.onNodeWithText("最近入库 · ${second.name}")
        // 以首库为滚动锚点，让次库标题进入短屏；后续不重滚，避免对齐次库掩盖真实位移。
        compose.onNodeWithTag("home-list").performScrollToKey("library-shelf/${first.id}")
        VerificationDisplay.awaitWindowReady(compose, "home-shelf-stable-anchor", anchor, following)
        anchor.assertIsDisplayed()
        following.assertIsDisplayed()
        val anchorBefore = layoutBounds(anchor).top
        val before = layoutBounds(following)
        fun assertStable(stage: String) {
            anchor.assertIsDisplayed()
            following.assertIsDisplayed()
            assertSamePosition(anchorBefore, layoutBounds(anchor).top, "$stage 的首库滚动锚点")
            assertSamePosition(before.top, layoutBounds(following).top, stage)
        }
        compose.runOnIdle {
            home.value = home.value.copy(loading = false, loaded = true,
                libraryItems = home.value.libraryItems + (first.id to listOf(movie)))
        }
        compose.onNodeWithTag("poster-title/${movie.id}", true).assertExists()
        assertStable("首次真实卡片到达")

        compose.runOnIdle { home.value = home.value.copy(loading = true) }
        compose.onNodeWithTag("poster-title/${movie.id}", true).assertExists()
        assertStable("带旧内容刷新")

        compose.runOnIdle {
            home.value = home.value.copy(loading = false,
                libraryItems = home.value.libraryItems + (first.id to emptyList()))
        }
        compose.onNodeWithText("暂无可见媒体").assertExists()
        assertStable("已加载空库")
    }

    @Test fun renderedRatingIsGoldAndRequiredTextFitsInLightDarkAndLargeFonts() {
        val rated = movie.copy(communityRating = 10.0)
        val library = MediaItem("rating-library", "合成电影库", "CollectionFolder", isFolder = true)
        val home = mutableStateOf(HomeState(libraries = listOf(library), loaded = true,
            libraryItems = mapOf(library.id to listOf(rated))))
        val theme = mutableStateOf(ThemePreference.LIGHT)
        val fontScale = mutableStateOf(1f)
        showHome(home, theme, fontScale)
        for (mode in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
            for (scale in listOf(1f, 1.6f)) {
                compose.runOnIdle { theme.value = mode; fontScale.value = scale }
                val heading = compose.onNodeWithText("最近入库 · ${library.name}").performScrollTo()
                heading.assertIsDisplayed()
                compose.onNodeWithTag("poster-rating", true).assertIsDisplayed()
                val pixels = compose.onNodeWithTag("poster-rating", true).captureToImage().toPixelMap()
                var golden = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.red > .85f && color.green >= .65f && color.green <= .92f &&
                        color.blue < .5f && color.red - color.blue > .4f) golden++
                }
                assertTrue("${mode.name}/$scale 必须绘制实际金色评分像素，不能只改文案或语义", golden > 8)
                saveScreenshot("${mode.name.lowercase()}-${if (scale > 1f) "large" else "normal"}")
                assertFullyVisibleText(textLayout("poster-title/${rated.id}"), "${mode.name}/$scale 标题")
                assertFullyVisibleText(textLayout("poster-subtitle/${rated.id}"), "${mode.name}/$scale 年份")
                val ratingLayouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("10.0", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(ratingLayouts) }
                assertFullyVisibleText(ratingLayouts.single(), "${mode.name}/$scale 评分")
                val titleBounds = layoutBounds(compose.onNodeWithTag("poster-title/${rated.id}", true))
                val subtitleBounds = layoutBounds(compose.onNodeWithTag("poster-subtitle/${rated.id}", true))
                assertTrue("标题和年份不能重叠", subtitleBounds.top >= titleBounds.bottom)
            }
        }
    }

    @Test fun resumeArrivingAfterLibrariesOrBecomingEmptyDoesNotMoveTheLatestShelf() {
        val library = MediaItem("delayed-resume-library", "即时电影库", "CollectionFolder", isFolder = true)
        val home = mutableStateOf(HomeState(libraries = listOf(library), loading = true,
            librariesLoaded = true, resumeLoaded = false, libraryItems = mapOf(library.id to listOf(movie))))
        showHome(home)
        val heading = compose.onNodeWithText("最近入库 · ${library.name}")
        heading.assertIsDisplayed()
        val before = layoutBounds(heading)
        val slot = layoutBounds(compose.onNodeWithTag("home-resume-content"))
        compose.runOnIdle { home.value = home.value.copy(resume = listOf(movie.copy(positionTicks = 50_000_000)), resumeLoaded = true) }
        assertEquals("慢续播不能推移已显示的媒体库", before, layoutBounds(heading))
        assertEquals(slot, layoutBounds(compose.onNodeWithTag("home-resume-content")))
        compose.runOnIdle { home.value = home.value.copy(resume = emptyList(), loading = false, loaded = true) }
        compose.onNodeWithText("暂无观看记录").assertIsDisplayed()
        assertEquals("空续播也不能收起已有空间", before, layoutBounds(heading))
    }

    private fun layoutBounds(target: SemanticsNodeInteraction): Rect {
        val node = target.fetchSemanticsNode()
        // Compose 坐标换算使用可变 rectCache，不能在测试线程与绘制线程并发读取。
        return compose.runOnIdle { node.boundsInRoot }
    }

    private fun showHome(
        home: MutableState<HomeState>,
        theme: MutableState<ThemePreference> = mutableStateOf(ThemePreference.LIGHT),
        fontScale: MutableState<Float> = mutableStateOf(1f),
        onOpen: (MediaItem) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value),
                LocalPosterLoader provides loader,
                LocalAppPreferences provides AppPreferences(showPosterBadges = true)) {
                PathfinTheme(theme.value) {
                    Surface(Modifier.fillMaxSize()) { HomeScreen(home.value, session, api, onOpen, retry = {}) }
                }
            }
        }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertFullyVisibleText(layout: TextLayoutResult, label: String) {
        assertEquals("$label 应只有一行", 1, layout.lineCount)
        assertFalse("$label 不得垂直裁字：size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, line=${layout.getLineTop(0)}..${layout.getLineBottom(0)}, right=${layout.getLineRight(0)}, ellipsis=${layout.isLineEllipsized(0)}, exceeded=${layout.multiParagraph.didExceedMaxLines}", layout.didOverflowHeight)
        assertFalse("$label 的完整短文案不应省略", layout.isLineEllipsized(0))
        assertFalse("$label 不得超出可绘制范围", layout.hasVisualOverflow)
    }

    private fun assertSamePosition(before: Float, after: Float, stage: String) =
        assertEquals("$stage 不能把下一栏推走", before, after, .5f)

    private fun saveScreenshot(name: String) {
        val directory = File(context.filesDir, "home-polish")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue("首页截图应可保存", compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private companion object {
        // 不提供任何 imageTag，真实生产组件不会构造图片 URL。
        val movie = MediaItem("movie", "合成电影", "Movie", year = 2026)
    }
}

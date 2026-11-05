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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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

/** 真实首页组件；仅 verification、合成条目，会话主机为 .invalid，不读账号、不联网。 */
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

    @Test fun nextUpGroupWaitsForResumeDeduplicationAndNeverMovesTheShelves() {
        val library = MediaItem("library", "合成电影库", "CollectionFolder", isFolder = true)
        val resume = MediaItem("episode-1", "第一集", "Episode", seriesId = "series", seriesName = "同一部剧")
        val next = resume.copy(id = "episode-2", name = "第二集")
        val home = mutableStateOf(HomeState(libraries = listOf(library), loaded = true, librariesLoaded = true, loading = true,
            libraryItems = mapOf(library.id to listOf(movie)), nextUp = listOf(next), nextUpLoaded = true))
        showHome(home)
        val following = heading(library)
        VerificationDisplay.awaitWindowReady(compose, "next-up-before-resume", following)
        val before = layoutBounds(following).top
        compose.onNodeWithTag("home-ambient").assertExists()
        compose.onNodeWithTag("home-ambient-thumb/${next.id}").assertDoesNotExist()

        compose.runOnIdle { home.value = home.value.copy(resume = listOf(resume), resumeLoaded = true) }
        compose.onNodeWithTag("home-ambient-thumb/${resume.id}").assertExists()
        compose.onNodeWithTag("home-ambient-group/NEXT_UP").assertDoesNotExist()
        compose.onNodeWithTag("home-ambient-thumb/${next.id}").assertDoesNotExist()
        assertSamePosition(before, layoutBounds(following).top, "续播到达并去掉同剧下一集")

        val other = next.copy(id = "other-next", seriesId = "other-series", seriesName = "另一部剧")
        compose.runOnIdle { home.value = home.value.copy(loading = false, nextUp = listOf(next, other)) }
        compose.onNodeWithTag("home-ambient-group/NEXT_UP").assertTextEquals("下一集 1").performVerificationClick(compose)
        compose.onNodeWithTag("home-ambient-thumb/${other.id}").assertExists()
        compose.onNodeWithTag("home-ambient-hero/${other.id}").assertExists()
        compose.onNodeWithTag("home-ambient-thumb/${resume.id}").assertDoesNotExist()
        assertSamePosition(before, layoutBounds(following).top, "切换到下一集分组")

        compose.runOnIdle { home.value = home.value.copy(loading = true) }
        compose.onNodeWithTag("home-ambient-thumb/${other.id}").assertExists()
        assertSamePosition(before, layoutBounds(following).top, "暖刷新不能收起已展示的分组")
    }

    @Test fun libraryPillsAndShelfHeadingsOpenTheOriginalLibraryAndOldHomeActionsAreAbsent() {
        val library = MediaItem("library", "合成电影库", "CollectionFolder", collectionType = "movies", isFolder = true)
        val collections = MediaItem("collection-library", "合成合集库", "CollectionFolder", collectionType = "boxsets", isFolder = true)
        val home = mutableStateOf(HomeState(libraries = listOf(collections, library), loaded = true, librariesLoaded = true,
            libraryItems = mapOf(library.id to listOf(movie))))
        var opened: MediaItem? = null
        var opens = 0
        showHome(home, onOpen = { opened = it; opens++ })
        compose.onNodeWithTag("home-library/${collections.id}").assertVerificationDisplayed(compose).assertHasClickAction()
            .assertTextEquals(collections.name)
        compose.onNodeWithTag("library-shelf/${collections.id}").assertDoesNotExist()

        val heading = heading(library).performScrollTo()
        heading.assertVerificationDisplayed(compose)
        val bounds = layoutBounds(heading)
        assertTrue("整行库标题必须保留至少48dp点击高度", bounds.height >= 48f * context.resources.displayMetrics.density - 1f)
        heading.performVerificationClick(compose)
        compose.runOnIdle {
            assertSame("库标题应打开原库，而不是某张封面条目", library, opened)
            assertEquals("一个标题入口只能触发一次打开", 1, opens)
        }
        compose.onNodeWithTag("home-library/${collections.id}").performScrollTo().performVerificationClick(compose)
        compose.runOnIdle { assertSame("没有最近入库栏的库也能从标签进入", collections, opened); assertEquals(2, opens) }
        compose.onNodeWithText("更多").assertDoesNotExist()
        compose.onNodeWithText("接下来").assertDoesNotExist()
        compose.onNodeWithText("最近入库 · ${library.name}").assertDoesNotExist()
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
        // 观看区已有固定内容；这里单独验证各海报栏，避免混入首轮空氛围区收起的高度变化。
        val home = mutableStateOf(HomeState(libraries = listOf(first, second), loading = true, resumeLoaded = true, nextUpLoaded = true,
            resume = listOf(movie.copy(id = "watching")),
            libraryItems = mapOf(second.id to listOf(movie.copy(id = "second-movie")))))
        showHome(home, fontScale = mutableStateOf(1.6f))
        val anchor = heading(first)
        val following = heading(second)
        // 以首库为滚动锚点，让次库标题进入短屏；后续不重滚，避免对齐次库掩盖真实位移。
        compose.onNodeWithTag("home-list").performScrollToKey("library-shelf/${first.id}")
        VerificationDisplay.awaitWindowReady(compose, "home-shelf-stable-anchor", anchor, following)
        anchor.assertVerificationDisplayed(compose)
        following.assertVerificationDisplayed(compose)
        val anchorBefore = layoutBounds(anchor).top
        val before = layoutBounds(following)
        fun assertStable(stage: String) {
            anchor.assertVerificationDisplayed(compose)
            following.assertVerificationDisplayed(compose)
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
                val heading = heading(library).performScrollTo()
                heading.assertVerificationDisplayed(compose)
                compose.onNodeWithTag("poster-rating", true).assertVerificationDisplayed(compose)
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

    @Test fun ambientHoldsItsSpaceUntilWatchingArrivesThenFallsBackToLatestBackdrops() {
        val library = MediaItem("delayed-resume-library", "即时电影库", "CollectionFolder", isFolder = true)
        // 条目带剧照标签才能作为氛围区的最近入库；会话主机是 .invalid，图片请求只会解析失败，不会联网。
        val latest = movie.copy(id = "latest-movie", backdropTag = "synthetic-backdrop")
        val home = mutableStateOf(HomeState(libraries = listOf(library), loading = true,
            librariesLoaded = true, resumeLoaded = false, libraryItems = mapOf(library.id to listOf(latest))))
        showHome(home)
        val heading = heading(library)
        heading.assertVerificationDisplayed(compose)
        val before = layoutBounds(heading)
        compose.onNodeWithTag("home-ambient").assertExists()
        val slot = layoutBounds(compose.onNodeWithTag("home-ambient"))

        val resume = movie.copy(positionTicks = 50_000_000, runTimeTicks = 600_000_000)
        compose.runOnIdle { home.value = home.value.copy(resume = listOf(resume), resumeLoaded = true) }
        compose.onNodeWithTag("home-ambient-thumb/${resume.id}").assertExists()
        assertEquals("慢续播不能推移已显示的媒体库", before, layoutBounds(heading))
        assertEquals("氛围区占位与填充后同高", slot, layoutBounds(compose.onNodeWithTag("home-ambient")))

        compose.runOnIdle { home.value = home.value.copy(resume = emptyList(), nextUpLoaded = true, loading = false, loaded = true) }
        compose.onNodeWithTag("home-ambient-group/LATEST").assertTextEquals("最近入库")
        compose.onNodeWithTag("home-ambient-thumb/${latest.id}").assertExists()
        assertEquals("没有观看记录时改放最近入库，不能收起已有空间", before, layoutBounds(heading))
    }

    @Test fun emptyWatchingWaitsForTheLatestShelfWithoutCollapsingTheAmbientSlot() {
        val library = MediaItem("delayed-latest-library", "慢速电影库", "CollectionFolder", isFolder = true)
        val home = mutableStateOf(HomeState(libraries = listOf(library), librariesLoaded = true, loaded = true, loading = true))
        showHome(home)
        val heading = heading(library)
        VerificationDisplay.awaitWindowReady(compose, "home-delayed-latest", heading)
        val before = layoutBounds(heading).top
        compose.runOnIdle { home.value = home.value.copy(resumeLoaded = true, nextUpLoaded = true) }
        compose.onNodeWithTag("home-ambient").assertExists()
        assertSamePosition(before, layoutBounds(heading).top, "观看记录先返回空结果")
        val latest = movie.copy(backdropTag = "synthetic-backdrop")
        compose.runOnIdle { home.value = home.value.copy(libraryItems = mapOf(library.id to listOf(latest)), loading = false) }
        compose.onNodeWithTag("home-ambient-hero/${latest.id}").assertExists()
        assertSamePosition(before, layoutBounds(heading).top, "最近入库稍后返回")
        compose.runOnIdle { home.value = home.value.copy(initialized = true, libraryItems = mapOf(library.id to emptyList())) }
        compose.onNodeWithTag("home-ambient").assertDoesNotExist()
        val emptyTop = layoutBounds(heading).top
        compose.runOnIdle { home.value = home.value.copy(loading = true) }
        compose.onNodeWithTag("home-ambient").assertDoesNotExist()
        assertSamePosition(emptyTop, layoutBounds(heading).top, "已确认空首页的暖刷新")
    }

    @Test fun ambientTextFitsAtLargeFontScalesAndPlaybackRemainsReachable() {
        val resume = movie.copy(positionTicks = 50_000_000, runTimeTicks = 600_000_000)
        val home = mutableStateOf(HomeState(resumeLoaded = true, nextUpLoaded = true, loaded = true,
            resume = listOf(resume), nextUp = listOf(movie.copy(id = "next", type = "Episode"))))
        val scale = mutableStateOf(1f)
        var played: MediaItem? = null
        showHome(home, fontScale = scale, onPlay = { played = it })
        for (value in listOf(1f, 1.6f, 2f)) {
            compose.runOnIdle { scale.value = value }
            val group = compose.onNodeWithText("继续观看 1", useUnmergedTree = true)
            val thumb = compose.onNode(hasText(movie.name) and hasAnyAncestor(hasTestTag("home-ambient-thumbs")), useUnmergedTree = true)
            for ((label, node) in listOf("分组" to group, "缩略图标题" to thumb)) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("$value 氛围区$label 应完整保留一行", 1, layout.lineCount)
                assertFalse("$value 氛围区$label 不得垂直裁字：size=${layout.size}, paragraphHeight=${layout.multiParagraph.height}", layout.didOverflowHeight)
                assertFalse("$value 氛围区$label 短文案不应省略", layout.isLineEllipsized(0))
                // wrap-content Text 的段落测量宽度可以大于最终节点，检查实际文字行而非段落画布。
                assertTrue("$value 氛围区$label 文字必须在节点内", layout.getLineRight(0) <= layout.size.width + 1f)
            }
            compose.onNodeWithTag("home-ambient-play").assertVerificationDisplayed(compose).performVerificationClick(compose)
            compose.runOnIdle { assertSame(resume, played); played = null }
        }
    }

    @Test fun ambientThumbsSelectThenOpenWhilePlayAndTitleBarCoverFollowTheSelection() {
        fun watching(id: String, series: String) = MediaItem(id, "第 3 集", "Episode", seriesId = series, seriesName = series,
            parentIndexNumber = 1, indexNumber = 3, runTimeTicks = 27_600_000_000L, positionTicks = 6_000_000_000L)
        val first = watching("watch-1", "第一部剧")
        val second = watching("watch-2", "第二部剧")
        // 后面接几栏海报，页面足够长，才能把氛围区完整滚出标题栏。
        val libraries = List(4) { MediaItem("ambient-library-$it", "合成库 $it", "CollectionFolder", isFolder = true) }
        val home = mutableStateOf(HomeState(libraries = libraries, loaded = true, librariesLoaded = true, resumeLoaded = true,
            nextUpLoaded = true, resume = listOf(first, second),
            libraryItems = libraries.associate { it.id to listOf(movie.copy(id = "movie-${it.id}")) }))
        var opened: MediaItem? = null
        var played: MediaItem? = null
        val covered = mutableListOf<Boolean>()
        showHome(home, onOpen = { opened = it }, onPlay = { played = it }, onAmbientCovered = { covered += it })
        compose.onNodeWithTag("home-ambient-hero/${first.id}").assertExists()
        compose.runOnIdle { assertEquals("氛围区在标题栏下方时通知外壳透明标题栏", true, covered.lastOrNull()) }

        compose.onNodeWithTag("home-ambient-thumb/${second.id}").performVerificationClick(compose)
        compose.onNodeWithTag("home-ambient-hero/${second.id}").assertExists()
        compose.runOnIdle { assertEquals("第一次点击只切换选中，不进入详情", null, opened) }
        compose.onNodeWithTag("home-ambient-thumb/${second.id}").performVerificationClick(compose)
        compose.runOnIdle { assertSame("再点已选中的小图进入详情", second, opened) }
        compose.onNodeWithTag("home-ambient-play").assertTextEquals("继续播放").performVerificationClick(compose)
        compose.runOnIdle { assertSame("播放键播放当前选中的一集", second, played) }

        compose.onNodeWithTag("home-list").performScrollToKey("library-shelf/${libraries.last().id}")
        compose.runOnIdle { assertEquals("剧照滚出标题栏后恢复底色", false, covered.last()) }
        compose.onNodeWithTag("home-list").performScrollToIndex(0)
        compose.runOnIdle { assertEquals(true, covered.last()) }
    }

    @Test fun eachShelfLeadsWithItsNewestBackdropAtPosterHeight() {
        val library = MediaItem("lead-library", "合成剧集库", "CollectionFolder", collectionType = "tvshows", isFolder = true)
        val lead = movie.copy(id = "lead-movie", name = "最新一部", backdropTag = "synthetic-backdrop")
        val plain = movie.copy(id = "plain-movie", name = "没有剧照")
        val home = mutableStateOf(HomeState(libraries = listOf(library), loaded = true, librariesLoaded = true,
            resumeLoaded = true, nextUpLoaded = true, libraryItems = mapOf(library.id to listOf(lead, plain))))
        showHome(home)
        heading(library).performScrollTo()
        compose.onNodeWithTag("latest-lead/${lead.id}").assertExists()
        compose.onNodeWithTag("latest-lead/${plain.id}").assertDoesNotExist()
        val leadTitle = layoutBounds(compose.onNodeWithTag("poster-title/${lead.id}", true))
        val plainTitle = layoutBounds(compose.onNodeWithTag("poster-title/${plain.id}", true))
        assertEquals("横版首卡与海报同高，标题在同一行", plainTitle.top, leadTitle.top, 1f)
        assertTrue("横版首卡明显比海报宽", leadTitle.width > plainTitle.width * 2)

        compose.runOnIdle { home.value = home.value.copy(libraryItems = mapOf(library.id to listOf(plain, lead))) }
        compose.onNodeWithTag("latest-lead/${plain.id}").assertDoesNotExist()
        compose.onNodeWithTag("latest-lead/${lead.id}").assertDoesNotExist()
    }

    /** 库名也出现在顶部库标签上，栏标题按所在栏查找。 */
    private fun heading(library: MediaItem) =
        compose.onNode(hasText(library.name) and hasAnyAncestor(hasTestTag("library-shelf/${library.id}")))

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
        onPlay: ((MediaItem) -> Unit)? = null,
        onAmbientCovered: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value),
                LocalPosterLoader provides loader,
                LocalAppPreferences provides AppPreferences(showPosterBadges = true)) {
                PathfinTheme(theme.value) {
                    Surface(Modifier.fillMaxSize()) { HomeScreen(home.value, session, api, onOpen, retry = {}, onPlay = onPlay, onAmbientCovered = onAmbientCovered) }
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
        assertFalse("$label 不得超出可绘制范围：size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
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

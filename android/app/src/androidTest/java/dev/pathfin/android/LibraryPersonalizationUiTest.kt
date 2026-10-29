package dev.pathfin.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BrowserMediaCard
import dev.pathfin.android.ui.HomeScreen
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PosterStyle
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 自有棋盘图直接放入 Coil 内存，不联网；PixelCopy 验证真实系统图像效果。 */
@RunWith(AndroidJUnit4::class)
class LibraryPersonalizationUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val session = Session(ServerConfig("https://personalization.invalid/jellyfin"), "synthetic-token", "user", "User", "device")

    @Test fun matchingRatingActuallyBlursPixelsOrCoversOnOldAndroidWithoutMovingText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val api = JellyfinApi()
        val item = mutableStateOf(MediaItem("pattern", "不会被模糊的标题", "Movie", year = 2026, imageTag = "own-pattern", officialRating = "TV-MA"))
        val preferences = mutableStateOf(AppPreferences())
        val bitmap = Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1024 * 1536) { index -> if (((index % 1024) / 16 + (index / 1024) / 16) % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK }
        bitmap.setPixels(pixels, 0, 1024, 0, 0, 1024, 1536)
        val loader = ImageLoader.Builder(context).memoryCache(MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build()).diskCache(null).build()
        val width = PosterImageCache.requestWidth((112 * context.resources.displayMetrics.density).roundToInt())
        val url = requireNotNull(api.imageUrl(session, item.value, width = width))
        requireNotNull(loader.memoryCache)[MemoryCache.Key(PosterImageCache.requestKey(session, url))] = MemoryCache.Value(bitmap.asImage())
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides preferences.value) {
                        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                            BrowserMediaCard(item.value, session, api, PosterStyle.POSTER, Modifier.width(112.dp), onOpen = {})
                        } }
                    }
                }
            }
            val clearTag = "poster-image-clear/pattern"
            val originalTextBounds = compose.onNodeWithTag("poster-title/pattern", true).readVerificationNode(compose) { it.boundsInRoot }
            var clear = pixels(clearTag)
            repeat(25) {
                if (variance(clear) <= 3000) { Thread.sleep(80); clear = pixels(clearTag) }
            }
            assertTrue("必须先绘制真实高对比棋盘图，不能用空占位通过模糊回归", variance(clear) > 3000)
            compose.runOnIdle { preferences.value = preferences.value.copy(blurAdultCovers = true) }
            val restrictedTag = if (Build.VERSION.SDK_INT >= 31) "poster-image-blurred/pattern" else "poster-image-covered/pattern"
            compose.onNodeWithTag(restrictedTag, true).assertExists()
            Thread.sleep(200)
            val restricted = pixels(restrictedTag)
            if (Build.VERSION.SDK_INT >= 31) {
                assertTrue("真实 RenderEffect 应降低棋盘空间对比，而非只改变语义标签", variance(restricted) < variance(clear) * .35)
                assertTrue("模糊后仍需是真实图像的平均颜色，不能用暗色遮盖假扮模糊", restricted.average() in 75.0..185.0)
            } else compose.onNodeWithText("已遮盖", true).assertExists()
            assertEquals(originalTextBounds, compose.onNodeWithTag("poster-title/pattern", true).readVerificationNode(compose) { it.boundsInRoot })
            compose.onNodeWithText("不会被模糊的标题", true).assertExists()
            compose.onNodeWithText("2026", true).assertExists()
            compose.runOnIdle { item.value = item.value.copy(officialRating = "PG-13") }
            compose.onNodeWithTag(clearTag, true).assertExists()
            Thread.sleep(150)
            assertTrue("启用成人模糊时 PG-13 仍须保持真实清晰图片", variance(pixels(clearTag)) > 3000)
            assertEquals(originalTextBounds, compose.onNodeWithTag("poster-title/pattern", true).readVerificationNode(compose) { it.boundsInRoot })
            compose.runOnIdle {
                item.value = item.value.copy(officialRating = "TV-MA")
                preferences.value = preferences.value.copy(blurAdultCovers = false)
            }
            compose.onNodeWithTag(clearTag, true).assertExists()
        } finally { loader.shutdown() }
    }

    @Test fun nextUpShowsUnwatchedEpisodesWithoutRepeatingResumeSeriesAndRefreshesThroughHomePull() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        fun episode(id: String, series: String) = MediaItem(id, "第 2 集", "Episode", seriesId = series, seriesName = "剧集$series",
            parentIndexNumber = 1, indexNumber = 2, runTimeTicks = 27_600_000_000L)
        val resume = episode("resume-a", "A").copy(positionTicks = 6_000_000_000L)
        val home = mutableStateOf(HomeState(loaded = true, resumeLoaded = true, resume = listOf(resume),
            nextUp = listOf(episode("next-a", "A"), episode("next-b", "B")), nextUpLoaded = true))
        var homeRefreshes = 0
        var opened: String? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).diskCache(null).build()
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides AppPreferences()) {
                        HomeScreen(home.value, session, JellyfinApi(), { opened = it.id }, { homeRefreshes++ })
                    }
                }
            }
            compose.onNodeWithTag("home-next-up").performScrollTo().assertVerificationDisplayed(compose)
            // 剧集 A 已在继续观看中，接下来只保留剧集 B；未开始的下一集显示整集时长。
            compose.onAllNodesWithText("剧集A").assertCountEquals(1)
            val next = compose.onNode(hasText("剧集B") and hasAnyAncestor(hasTestTag("home-next-up")))
            next.assertVerificationDisplayed(compose)
            compose.onNode(hasText("46 分") and hasAnyAncestor(hasTestTag("home-next-up"))).assertVerificationDisplayed(compose)
            next.performVerificationClick(compose)
            compose.runOnIdle { assertEquals("next-b", opened) }
            pullPageToRefresh(compose, stage = "next-up-home-refresh")
            compose.runOnIdle { assertEquals(1, homeRefreshes) }
            compose.runOnIdle { home.value = home.value.copy(nextUp = listOf(episode("next-a2", "A"))) }
            compose.onNodeWithTag("home-next-up").assertDoesNotExist()
        } finally { loader.shutdown() }
    }

    private fun pixels(tag: String): IntArray {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag, true).readVerificationNode(compose) { it.boundsInRoot }
        lateinit var windowBounds: Rect
        compose.runOnIdle {
            fun root(view: View): View? {
                if (view.javaClass.name.endsWith("AndroidComposeView")) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) root(view.getChildAt(index))?.let { return it }
                return null
            }
            val origin = IntArray(2)
            requireNotNull(root(compose.activity.window.decorView)).getLocationInWindow(origin)
            windowBounds = Rect(origin[0] + bounds.left.roundToInt(), origin[1] + bounds.top.roundToInt(),
                origin[0] + bounds.right.roundToInt(), origin[1] + bounds.bottom.roundToInt())
        }
        val bitmap = Bitmap.createBitmap(windowBounds.width(), windowBounds.height(), Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(compose.activity.window, windowBounds, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("窗口像素复制必须完成", latch.await(3, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return try {
            // 避开圆角，仅采样图片中心区域；标题和角标不进入图像证据。
            IntArray(40 * 60) { index -> bitmap.getPixel(bitmap.width / 8 + (index % 40) * bitmap.width * 3 / 4 / 40,
                bitmap.height / 8 + (index / 40) * bitmap.height * 3 / 4 / 60) and 255 }
        } finally { bitmap.recycle() }
    }

    private fun variance(values: IntArray): Double {
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}

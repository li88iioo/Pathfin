package dev.pathfin.android

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaChapter
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.DetailActions
import dev.pathfin.android.ui.DetailBackgroundImage
import dev.pathfin.android.ui.DetailChaptersShelf
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PlaybackChaptersSheet
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只在 verification 使用自制内存图像；不连接个人服务器，不读写日常账号。 */
@RunWith(AndroidJUnit4::class)
class DetailMediaPreferencesUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = Session(ServerConfig("https://detail-preferences.invalid/jellyfin"), "synthetic-token", "user", "User", "device")
    private val api = JellyfinApi()

    @Test fun backgroundUsesOwnEpisodeScreenshotIndependentlyFromContinueWatching() {
        checkVerification()
        val episode = MediaItem("episode", "合成单集", "Episode", imageTag = "episode-primary", primaryImageItemId = "episode",
            backdropTag = "series-backdrop", backdropItemId = "series")
        val item = mutableStateOf(episode)
        val preferences = mutableStateOf(AppPreferences(preferEpisodeImages = false, preferEpisodeBackgrounds = true))
        val loader = loader()
        val red = solid(android.graphics.Color.RED)
        val blue = solid(android.graphics.Color.BLUE)
        val width = requestWidth(240)
        cache(loader, requireNotNull(api.imageUrl(session, episode, width, "Primary")), red)
        cache(loader, requireNotNull(api.imageUrl(session, episode, width, "Backdrop")), blue)
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides preferences.value) {
                        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                            DetailBackgroundImage(item.value, session, api, Modifier.size(240.dp, 135.dp))
                        } }
                    }
                }
            }
            awaitColor("detail-background/episode", Color.Red)
            compose.runOnIdle { preferences.value = preferences.value.copy(preferEpisodeImages = true, preferEpisodeBackgrounds = false) }
            awaitColor("detail-background/episode", Color.Blue)
            compose.runOnIdle {
                // 模型可能继承剧集 Primary；即使“优先本集截图”开启，也不能用继承海报冒充本集截图。
                preferences.value = preferences.value.copy(preferEpisodeBackgrounds = true)
                item.value = episode.copy(primaryImageItemId = "series")
            }
            awaitColor("detail-background/episode", Color.Blue)
            compose.runOnIdle { item.value = episode.copy(imageTag = null) }
            awaitColor("detail-background/episode", Color.Blue)
        } finally { loader.shutdown() }
    }

    @Test fun backgroundBlurChangesRealPixelsAndKeepsForegroundAtTheSamePosition() {
        checkVerification()
        val item = MediaItem("checker", "清晰的前景文字", "Movie", backdropTag = "checker-backdrop")
        val preferences = mutableStateOf(AppPreferences(backgroundBlurRadiusDp = 0))
        val loader = loader()
        val bitmap = Bitmap.createBitmap(1024, 576, Bitmap.Config.ARGB_8888)
        val source = IntArray(1024 * 576) { index ->
            if ((index % 1024 / 12 + index / 1024 / 12) % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        }
        bitmap.setPixels(source, 0, 1024, 0, 0, 1024, 576)
        cache(loader, requireNotNull(api.imageUrl(session, item, requestWidth(240), "Backdrop")), bitmap)
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides preferences.value) {
                        Surface(Modifier.fillMaxSize()) { Column {
                            DetailBackgroundImage(item, session, api, Modifier.size(240.dp, 135.dp))
                            Text(item.name, Modifier.testTag("foreground-title"))
                        } }
                    }
                }
            }
            compose.waitUntil(5_000) { variance(windowPixels("detail-background/checker")) > 3000 }
            val clear = windowPixels("detail-background/checker")
            val originalBounds = compose.onNodeWithTag("foreground-title").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { preferences.value = preferences.value.copy(backgroundBlurRadiusDp = 18) }
            if (Build.VERSION.SDK_INT >= 31) {
                compose.waitUntil(5_000) { variance(windowPixels("detail-background/checker")) < variance(clear) * .35 }
                val blurred = windowPixels("detail-background/checker")
                assertTrue("实际模糊必须保留棋盘的平均图像色彩，不能只遮住图片", blurred.average() in 75.0..185.0)
            } else {
                // 旧系统设置入口由生产界面明确禁用；组件不伪装为已经应用 RenderEffect。
                assertTrue("旧系统应保留原图", variance(windowPixels("detail-background/checker")) > 3000)
            }
            assertEquals(originalBounds, compose.onNodeWithTag("foreground-title").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { preferences.value = preferences.value.copy(backgroundBlurRadiusDp = 0) }
            compose.waitUntil(5_000) { variance(windowPixels("detail-background/checker")) > 3000 }
            assertEquals(originalBounds, compose.onNodeWithTag("foreground-title").fetchSemanticsNode().boundsInRoot)
        } finally { loader.shutdown() }
    }

    @Test fun chapterFilteringKeepsTheServerImageIndexAndTheSelectedOriginalChapter() {
        checkVerification()
        val first = MediaChapter("无图章", 0, imageIndex = 0)
        val imageChapter = MediaChapter("原始第五张章图", 60L * 10_000_000, "chapter-five", imageIndex = 5)
        val invalid = MediaChapter("非法缩略图", 90L * 10_000_000, "not-an-index", imageIndex = -1)
        val item = MediaItem("chapters", "合成章节", "Movie", chapters = listOf(first, imageChapter, invalid))
        val hide = mutableStateOf(true)
        val sheet = mutableStateOf(true)
        val busy = mutableStateOf(false)
        var selected: MediaChapter? = null
        val loader = loader()
        cache(loader, requireNotNull(api.chapterImageUrl(session, item, 5, imageChapter.imageTag, requestWidth(104))), solid(android.graphics.Color.GREEN))
        cache(loader, requireNotNull(api.chapterImageUrl(session, item, 5, imageChapter.imageTag, requestWidth(160))), solid(android.graphics.Color.GREEN))
        // 如果错误地用过滤后的0当服务器图片索引，将命中红色而非绿色。
        cache(loader, requireNotNull(api.chapterImageUrl(session, item, 0, imageChapter.imageTag, requestWidth(104))), solid(android.graphics.Color.RED))
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader,
                        LocalAppPreferences provides AppPreferences(hideChaptersWithoutImage = hide.value)) {
                        if (sheet.value) PlaybackChaptersSheet(item.chapters, { selected = it }, {}, session, item, api, hide.value)
                        else DetailChaptersShelf(item, session, api, busy.value) { selected = it }
                    }
                }
            }
            compose.onNodeWithText(first.name).assertDoesNotExist()
            compose.onNodeWithText(invalid.name).assertDoesNotExist()
            awaitColor("chapter-thumbnail/chapters/5", Color.Green)
            compose.onNodeWithText(imageChapter.name).performClick()
            compose.runOnIdle { assertSame(imageChapter, selected); assertEquals(5, selected!!.imageIndex) }
            compose.runOnIdle { hide.value = false }
            compose.onNodeWithTag("playback-chapters").performScrollToNode(hasText(first.name))
            compose.onNodeWithText(first.name).performClick()
            compose.runOnIdle { assertSame(first, selected); sheet.value = false; hide.value = true; selected = null }
            compose.onNodeWithTag("detail-chapter/0").assertDoesNotExist()
            compose.onNodeWithTag("detail-chapter/1").performClick()
            compose.runOnIdle { assertSame(imageChapter, selected); busy.value = true }
            compose.onNodeWithTag("detail-chapter/1").assertIsNotEnabled()
        } finally { loader.shutdown() }
    }

    @Test fun chapterSheetEmptyStateAndPrimaryPlaybackActionAreReal() {
        checkVerification()
        val hide = mutableStateOf(true)
        val showChapters = mutableStateOf(true)
        var plays = 0
        val loader = loader()
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides AppPreferences()) {
                        if (showChapters.value) PlaybackChaptersSheet(listOf(MediaChapter("只有文字的章", 0, imageIndex = 0)), {}, {}, hideWithoutImage = hide.value)
                        else DetailActions(MediaItem("play", "合成电影", "Movie"), false, false, { plays++ }, {})
                    }
                }
            }
            compose.onNodeWithText("没有带缩略图的章节").assertExists()
            compose.runOnIdle { hide.value = false }
            compose.onNodeWithText("只有文字的章").assertExists()
            compose.runOnIdle { showChapters.value = false }
            compose.onNodeWithTag("detail-play").performClick()
            compose.runOnIdle { assertEquals(1, plays) }
        } finally { loader.shutdown() }
    }

    private fun checkVerification() { check(context.packageName == "dev.pathfin.android.verification") }
    private fun loader() = ImageLoader.Builder(context).memoryCache(MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build()).diskCache(null).build()
    private fun solid(color: Int) = Bitmap.createBitmap(1024, 576, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun requestWidth(dp: Int) = PosterImageCache.requestWidth((dp * context.resources.displayMetrics.density).roundToInt())
    private fun cache(loader: ImageLoader, url: String, image: Bitmap) {
        requireNotNull(loader.memoryCache)[MemoryCache.Key(PosterImageCache.requestKey(session, url))] = MemoryCache.Value(image.asImage())
    }
    private fun awaitColor(tag: String, expected: Color) {
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithTag(tag, true).captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            kotlin.math.abs(center.red - expected.red) < .1 && kotlin.math.abs(center.green - expected.green) < .1 && kotlin.math.abs(center.blue - expected.blue) < .1
        }
    }
    private fun windowPixels(tag: String): IntArray {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
        lateinit var area: Rect
        compose.runOnIdle {
            fun findCompose(view: View): View? {
                if (view.javaClass.name.endsWith("AndroidComposeView")) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
                return null
            }
            val origin = IntArray(2)
            requireNotNull(findCompose(compose.activity.window.decorView)).getLocationInWindow(origin)
            area = Rect(origin[0] + bounds.left.roundToInt(), origin[1] + bounds.top.roundToInt(), origin[0] + bounds.right.roundToInt(), origin[1] + bounds.bottom.roundToInt())
        }
        val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(compose.activity.window, area, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("真实窗口像素应复制成功", latch.await(3, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return try {
            IntArray(40 * 30) { index -> bitmap.getPixel(bitmap.width / 8 + index % 40 * bitmap.width * 3 / 4 / 40,
                bitmap.height / 8 + index / 40 * bitmap.height * 3 / 4 / 30) and 255 }
        } finally { bitmap.recycle() }
    }
    private fun variance(values: IntArray): Double { val mean = values.average(); return values.sumOf { (it - mean) * (it - mean) } / values.size }
}

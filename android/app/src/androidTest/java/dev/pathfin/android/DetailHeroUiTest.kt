package dev.pathfin.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
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
import dev.pathfin.android.ui.DetailHero
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真正透明的自制 Logo 经生产 Coil/Hero 绘制；无网络、无个人影片图片。 */
@RunWith(AndroidJUnit4::class)
class DetailHeroUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = Session(ServerConfig("https://hero-fixture.invalid/jellyfin"), "fixture-token", "fixture-user", "合成用户", "fixture-device")
    private val api = JellyfinApi()
    private val item = MediaItem("hero", "合成详情标题保留原有字体布局", "Movie", backdropTag = "backdrop",
        communityRating = 7.8, year = 2026, genres = listOf("合成类型"), overview = "用于验证详情 Hero 的合成简介。")

    @Test fun transparentLightAndDarkLogosKeepTheirColorsContrastAndLayoutAtLargeType() {
        checkVerification()
        val loader = loader()
        val mode = mutableStateOf(ThemePreference.LIGHT)
        val scale = mutableStateOf(1f)
        val current = mutableStateOf(item)
        var density = 1f
        cache(loader, item, "Backdrop", solid(android.graphics.Color.rgb(54, 72, 102)), backdropWidth())
        cache(loader, item.copy(logoTag = "white"), "Logo", logo(android.graphics.Color.WHITE), 720)
        cache(loader, item.copy(logoTag = "dark"), "Logo", logo(android.graphics.Color.rgb(5, 5, 8)), 720)
        try {
            compose.setContent {
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                    PathfinTheme(mode.value) {
                        CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides AppPreferences()) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Box(Modifier.width(360.dp)) { Hero(current.value) }
                                }
                            }
                        }
                    }
                }
            }
            for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (fontScale in listOf(1f, 1.7f)) {
                compose.runOnIdle { mode.value = theme; scale.value = fontScale; current.value = item }
                val titleBefore = bounds("detail-title/hero")
                val actionBefore = bounds("detail-play-pill")
                val darkLogo = theme == ThemePreference.DARK
                compose.runOnIdle { current.value = item.copy(logoTag = if (darkLogo) "dark" else "white") }
                awaitLogo()
                assertEquals("Logo 到达不能改变标题测量", titleBefore, bounds("detail-title/hero"))
                assertEquals("Logo 到达不能移动操作栏", actionBefore, bounds("detail-play-pill"))
                assertTrue("操作栏不能越过正文右边界", actionBefore.right <= titleBefore.right + .5f)
                val pixels = capture("detail-logo/hero")
                val width = pixels.width
                val height = pixels.height
                val color = IntArray(width * height).also { pixels.getPixels(it, 0, width, 0, 0, width, height) }
                fun isFill(value: Int): Boolean = if (darkLogo) red(value) < 13 && green(value) < 13 && blue(value) < 13
                    else red(value) > 252 && green(value) > 252 && blue(value) > 252
                assertTrue("必须呈现原始明/暗 Logo，不能统一染色", color.count(::isFill) > color.size / 40)
                assertTrue("Logo 的原始青色装饰必须保留", color.count { red(it) < 25 && green(it) > 180 && blue(it) > 180 } > 40)
                val reach = max(1, (density * 1.25f).roundToInt())
                var edges = 0
                for (y in reach until height - reach) for (x in reach until width - reach) {
                    val pixel = color[y * width + x]
                    val contrasting = if (darkLogo) brightness(pixel) > .3f else brightness(pixel) < .75f
                    if (contrasting && !isFill(pixel) &&
                        (isFill(color[y * width + x - reach]) || isFill(color[y * width + x + reach]) ||
                            isFill(color[(y - reach) * width + x]) || isFill(color[(y + reach) * width + x]))) edges++
                }
                assertTrue("轮廓需有实际可辨的主题对比，而非只声明 shadow", edges > width / 10)
                val background = capture("detail-hero-background")
                val logoBounds = bounds("detail-logo/hero")
                val backgroundBounds = bounds("detail-hero-background")
                val y = (logoBounds.top - backgroundBounds.top + height * .15f).roundToInt().coerceIn(0, background.height - 1)
                assertColorNear("透明区域不能变成实色矩形", background.getPixel(background.width - 2, y), pixels.getPixel(width - 2, (height * .15f).roundToInt()), 4)
                save(background, "hero-build28-${theme.name.lowercase()}-${fontScale}.png")
            }
        } finally { loader.shutdown() }
    }

    @Test fun actualBackdropFadesContinuouslyIntoBothThemeSurfacesWithoutHorizontalBands() {
        checkVerification()
        val loader = loader()
        val mode = mutableStateOf(ThemePreference.LIGHT)
        var surface = Color.Unspecified
        var density = 1f
        cache(loader, item, "Backdrop", solid(android.graphics.Color.rgb(36, 78, 126)), backdropWidth())
        try {
            compose.setContent {
                density = LocalDensity.current.density
                PathfinTheme(mode.value) {
                    surface = MaterialTheme.colorScheme.surface
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides AppPreferences()) {
                        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) { Box(Modifier.width(360.dp)) { Hero(item) } } }
                    }
                }
            }
            compose.waitUntil(5_000) {
                val pixels = capture("detail-hero-background")
                val color = pixels.getPixel(pixels.width - 2, pixels.height / 10)
                blue(color) - red(color) > 40
            }
            for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
                compose.runOnIdle { mode.value = theme }
                val pixels = capture("detail-hero-background")
                val x = pixels.width - 2 // 正文16dp之外，仅采样真实背景和渐变。
                val start = pixels.height / 6
                var adjacent = 0f
                for (y in start + 1 until pixels.height) {
                    adjacent = max(adjacent, abs(brightness(pixels.getPixel(x, y)) - brightness(pixels.getPixel(x, y - 1))))
                }
                assertTrue("渐变不能有突变横线，实际相邻行差=$adjacent", adjacent < .02f)
                val step = max(1, (8f * density).roundToInt())
                var slopeChange = 0f
                for (y in start + step until pixels.height - step) {
                    val previous = brightness(pixels.getPixel(x, y - step))
                    val current = brightness(pixels.getPixel(x, y))
                    val next = brightness(pixels.getPixel(x, y + step))
                    slopeChange = max(slopeChange, abs(next - 2f * current + previous))
                }
                assertTrue("8dp范围的渐变斜率不能突然折断，实际=$slopeChange", slopeChange < .025f)
                assertColorNear("图片末端必须自然落到页面主题色", surface.toArgb(), pixels.getPixel(x, pixels.height - 2), 2)
                save(pixels, "hero-build28-gradient-${theme.name.lowercase()}.png")
            }
        } finally { loader.shutdown() }
    }

    @androidx.compose.runtime.Composable
    private fun Hero(media: MediaItem) = DetailHero(media, session, api, busy = false, playable = true, hasResume = false,
        playTarget = null, onPlay = {}, onStart = {}, onFavorite = {}, onDownload = {}, onWatched = {}, actionBusy = emptySet())

    private fun awaitLogo() {
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("detail-logo/hero", true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "已加载"
        }
        compose.waitForIdle()
    }
    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
        val node = compose.onNodeWithTag(tag, true).fetchSemanticsNode()
        return compose.runOnIdle { node.boundsInRoot }
    }
    private fun capture(tag: String) = compose.onNodeWithTag(tag, true).captureToImage().asAndroidBitmap()
    private fun backdropWidth() = PosterImageCache.requestWidth((360f * context.resources.displayMetrics.density).roundToInt())
    private fun cache(loader: ImageLoader, media: MediaItem, type: String, image: Bitmap, width: Int) {
        val url = requireNotNull(api.imageUrl(session, media, width, type))
        requireNotNull(loader.memoryCache)[MemoryCache.Key(PosterImageCache.requestKey(session, url))] = MemoryCache.Value(image.asImage())
    }
    private fun loader() = ImageLoader.Builder(context).memoryCache(MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build()).diskCache(null).build()
    private fun solid(color: Int) = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun logo(color: Int): Bitmap = Bitmap.createBitmap(720, 210, Bitmap.Config.ARGB_8888).apply {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 102f; typeface = Typeface.DEFAULT_BOLD }
        val canvas = Canvas(this)
        canvas.drawText("DEEP", 12f, 95f, paint)
        canvas.drawText("REVENGE", 12f, 195f, paint)
        paint.color = android.graphics.Color.CYAN
        canvas.drawRect(600f, 18f, 700f, 36f, paint)
    }
    private fun save(bitmap: Bitmap, name: String) { File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun assertColorNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}",
            abs(red(expected) - red(actual)) <= tolerance && abs(green(expected) - green(actual)) <= tolerance && abs(blue(expected) - blue(actual)) <= tolerance)
    }
    private fun checkVerification() { check(context.packageName == "dev.pathfin.android.verification") }
    private fun red(value: Int) = value shr 16 and 255
    private fun green(value: Int) = value shr 8 and 255
    private fun blue(value: Int) = value and 255
    private fun brightness(value: Int) = (red(value) + green(value) + blue(value)) / (3f * 255f)
}

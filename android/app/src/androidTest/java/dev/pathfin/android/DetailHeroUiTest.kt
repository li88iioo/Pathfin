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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
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
import dev.pathfin.android.ui.DetailTitle
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun cachedLogoSwitchesNeverDrawFallbackTextIncludingEpisodesWithTheSameSeriesLogo() {
        checkVerification()
        val loader = loader()
        val blue = item.copy(id = "blue-title", name = "缓存标题不应闪现文字", logoTag = "blue")
        val green = blue.copy(id = "green-title", logoTag = "green")
        val episode = blue.copy(id = "episode-one", type = "Episode", logoTag = "shared", logoItemId = "series")
        val nextEpisode = episode.copy(id = "episode-two")
        cache(loader, blue, "Logo", logo(android.graphics.Color.BLUE), 720)
        cache(loader, green, "Logo", logo(android.graphics.Color.GREEN), 720)
        cache(loader, episode, "Logo", logo(android.graphics.Color.BLUE), 720)
        assertEquals(api.imageUrl(session, episode, 720, "Logo"), api.imageUrl(session, nextEpisode, 720, "Logo"))
        val current = mutableStateOf(blue)
        val frames = TitleFrames()
        try {
            showTitle(loader, current, frames)
            awaitLogo(blue.id)
            frames.awaitSamples()
            compose.mainClock.autoAdvance = false
            for (target in listOf(green, blue, green, episode, nextEpisode, episode, blue)) {
                compose.runOnUiThread { current.value = target }
                repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); frames.awaitSamples() }
            }
            val drawn = frames.finish()
            assertTrue("必须记录切换过程的实际绘制帧", drawn.size >= 8)
            assertTrue("已缓存蓝色 Logo 必须真实绘制", drawn.any { it.blue > 50 })
            assertTrue("已缓存绿色 Logo 必须真实绘制", drawn.any { it.green > 50 })
            for (frame in drawn) {
                assertEquals("${frame.id} 缓存切换的任一帧都不能闪出或叠出红色文字", 0, frame.red)
                assertTrue("${frame.id} 已缓存 Logo 不能先清空标题", frame.logo > 50)
                if (frame.id == green.id) assertTrue("绿色目标不能残留旧蓝色 Logo：$frame", frame.green > 50 && frame.blue == 0)
                else assertTrue("蓝色目标不能残留旧绿色 Logo：$frame", frame.blue > 50 && frame.green == 0)
            }
        } finally { compose.mainClock.autoAdvance = true; loader.shutdown() }
    }

    @Test fun pendingLogosKeepTheSlotClearAndLateResponsesCannotReplaceTheLatestSelection() {
        checkVerification()
        val first = item.copy(id = "cold-title", name = "冷加载不应闪出文字", logoTag = "cold")
        val stale = first.copy(id = "stale-title", logoTag = "stale")
        val latest = first.copy(id = "latest-title", logoTag = "latest")
        val cold = LogoReply(logo(android.graphics.Color.BLUE))
        val delayed = LogoReply(logo(android.graphics.Color.BLUE))
        val replies = mapOf("cold" to cold, "stale" to delayed)
        val client = logoTransport(replies)
        val loader = PosterImageCache.createMemoryLoader(context, client)
        cache(loader, latest, "Logo", logo(android.graphics.Color.GREEN), 720)
        val current = mutableStateOf(first)
        val frames = TitleFrames()
        try {
            showTitle(loader, current, frames)
            compose.waitUntil(5_000) { cold.requested.count == 0L }
            val slot = bounds("detail-title/${first.id}")
            assertEquals("已知有 Logo 的等待态不能先绘制普通标题", 0, colors(capture("detail-title/${first.id}")).red)
            cold.release.countDown()
            awaitLogo(first.id)
            val loaded = capture("detail-title/${first.id}")
            save(loaded, "title-cold-loaded.png")
            assertTrue("解码完成后必须呈现蓝色 Logo：${colors(loaded)}", colors(loaded).blue > 50)
            assertEquals("冷加载前后标题占位保持稳定", slot, bounds("detail-title/${first.id}"))

            compose.runOnIdle { current.value = stale }
            compose.waitUntil(5_000) { delayed.requested.count == 0L }
            compose.runOnIdle { current.value = latest }
            awaitLogo(latest.id)
            delayed.release.countDown()
            assertTrue("已切走请求的合成响应必须已经释放", delayed.returned.await(5, TimeUnit.SECONDS))
            compose.mainClock.autoAdvance = false
            repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val pixels = colors(capture("detail-title/${latest.id}"))
            assertTrue("迟到蓝色响应不能覆盖当前绿色 Logo", pixels.green > 50 && pixels.blue == 0)
            for (frame in frames.finish()) {
                assertEquals("${frame.id} 等待与成功切换之间都不能短暂叠出文字", 0, frame.red)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            replies.values.forEach { it.release.countDown() }
            loader.shutdown()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun missingAndFailedLogosShowReadableTextWithoutMovingTheTitleSlot() {
        checkVerification()
        val missing = item.copy(id = "missing-title", name = "没有标志时保留标题")
        val failed = missing.copy(id = "failed-title", logoTag = "missing-image")
        val error = LogoReply(null)
        val client = logoTransport(mapOf("missing-image" to error))
        val loader = PosterImageCache.createMemoryLoader(context, client)
        val current = mutableStateOf(missing)
        try {
            showTitle(loader, current)
            val before = bounds("detail-title/${missing.id}")
            assertTrue("没有 Logo 的媒体应立即绘制可读标题", colors(capture("detail-title/${missing.id}")).red > 50)
            compose.runOnIdle { current.value = failed }
            compose.waitUntil(5_000) { error.requested.count == 0L }
            assertEquals("失败尚未确定前不能闪出临时文字", 0, colors(capture("detail-title/${failed.id}")).red)
            error.release.countDown()
            compose.waitUntil(5_000) { colors(capture("detail-title/${failed.id}")).red > 50 }
            assertEquals("明确失败回退文字不能改变标题占位", before, bounds("detail-title/${failed.id}"))
        } finally {
            error.release.countDown()
            loader.shutdown()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun showTitle(loader: ImageLoader, current: MutableState<MediaItem>, frames: TitleFrames? = null) {
        compose.setContent {
            PathfinTheme(ThemePreference.DARK) {
                CompositionLocalProvider(LocalPosterLoader provides loader, LocalAppPreferences provides AppPreferences()) {
                    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.Red) {
                        Column(Modifier.fillMaxSize()) {
                            val media = current.value
                            if (frames == null) Box(Modifier.width(320.dp)) { DetailTitle(media, session, api) }
                            else frames.Record(media.id) { DetailTitle(media, session, api) }
                        }
                    }
                }
            }
        }
    }

    private data class TitleColors(val id: String = "", val red: Int, val green: Int, val blue: Int, val logo: Int)

    private fun colors(bitmap: Bitmap, id: String = ""): TitleColors {
        var red = 0; var green = 0; var blue = 0; var logo = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            // 采样层透明边缘先合成到夹具黑底；不能把 alpha=1 的反预乘噪声当作可见颜色。
            val alpha = pixel ushr 24
            val r = (pixel shr 16 and 255) * alpha / 255
            val g = (pixel shr 8 and 255) * alpha / 255
            val b = (pixel and 255) * alpha / 255
            if (r > 180 && g < 80 && b < 80) red++
            if (g > 180 && r < 80 && b < 80) green++
            if (b > 180 && r < 80 && g < 80) blue++
            if (max(g, b) > 20 && max(g, b) > r * 2) logo++
        }
        return TitleColors(id, red, green, blue, logo)
    }

    /** 每次绘制单独记录图层，读取完成后释放；异步读回不复用下一帧的记录内容。 */
    private inner class TitleFrames {
        private val recording = AtomicBoolean(true)
        private val submitted = AtomicInteger()
        private val finished = AtomicInteger()
        private val samples = CopyOnWriteArrayList<TitleColors>()
        private val failures = CopyOnWriteArrayList<Throwable>()

        @Composable fun Record(id: String, content: @Composable () -> Unit) {
            val graphics = LocalGraphicsContext.current
            val scope = rememberCoroutineScope()
            Box(Modifier.width(320.dp).drawWithContent {
                if (!recording.get()) { drawContent(); return@drawWithContent }
                val layer = graphics.createGraphicsLayer()
                layer.record { this@drawWithContent.drawContent() }
                // 采样层只供读回，屏幕直接绘制原内容，释放采样层不会影响已提交帧。
                drawContent()
                submitted.incrementAndGet()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val hardware = layer.toImageBitmap().asAndroidBitmap()
                        try {
                            val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                            try { samples += colors(bitmap, id) } finally { bitmap.recycle() }
                        } finally { hardware.recycle() }
                    } catch (failure: Throwable) { failures += failure }
                    finally { graphics.releaseGraphicsLayer(layer); finished.incrementAndGet() }
                }
            }) { content() }
        }

        fun awaitSamples() {
            // GPU 异步读回完成后再切下一条，避免采样中引用的子图层被下一帧改写。
            compose.waitUntil(5_000) { finished.get() == submitted.get() }
            assertTrue("每帧像素读回必须成功：$failures", failures.isEmpty())
        }

        fun finish(): List<TitleColors> {
            compose.runOnUiThread { recording.set(false) }
            awaitSamples()
            return samples.toList()
        }
    }

    private class LogoReply(val image: Bitmap?) {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
    }

    private fun logoTransport(replies: Map<String, LogoReply>) = OkHttpClient.Builder().addInterceptor { chain ->
        check(chain.request().url.host == "hero-fixture.invalid")
        val reply = requireNotNull(replies[chain.request().url.queryParameter("tag")])
        reply.requested.countDown()
        check(reply.release.await(10, TimeUnit.SECONDS)) { "合成 Logo 响应未释放" }
        val bytes = ByteArrayOutputStream().use { output ->
            reply.image?.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (reply.image == null) 404 else 200).message("Synthetic logo")
            .body(bytes.toResponseBody("image/png".toMediaType())).build().also { reply.returned.countDown() }
    }.build()

    @androidx.compose.runtime.Composable
    private fun Hero(media: MediaItem) = DetailHero(media, session, api, busy = false, playable = true, hasResume = false,
        playTarget = null, onPlay = {}, onStart = {}, onFavorite = {}, onDownload = {}, onWatched = {}, actionBusy = emptySet())

    private fun awaitLogo(id: String = "hero") {
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("detail-logo/$id", true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "已加载"
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

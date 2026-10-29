package dev.pathfin.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.pathfin.android.ui.LocalTopBarBlurState
import dev.pathfin.android.ui.NavigationBarSurface
import dev.pathfin.android.ui.PageTitleBar
import dev.pathfin.android.ui.TopBarBlurSurface
import dev.pathfin.android.ui.rememberTopBarBlurState
import dev.pathfin.android.ui.topBarBlurSource
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实窗口像素验证采样、模糊和前景分层，不以半透明实底冒充模糊。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class TopBarBlurUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test
    fun togglingBlurKeepsLayoutAndForegroundSharpWhileMaterialTracksTheBody() {
        val enabled = mutableStateOf(false)
        val source = mutableStateOf(Color.Red)
        compose.setContent { SolidFixture(enabled.value, source.value) }
        ready("blur-disabled")

        val originalGeometry = geometry()
        val originalRow = pixels("blur-first-row")
        val originalText = pixels("blur-foreground-text")
        assertTrue("前景对照图形应有清晰黑白边缘", contrast(pixels("blur-foreground-mark")) > 0.8f)

        for ((index, blur) in listOf(true, false, true).withIndex()) {
            compose.runOnIdle { enabled.value = blur }
            ready("blur-toggle-$index-$blur")
            assertEquals("模糊开关不能移动标题、首行或前景控件", originalGeometry, geometry())
            assertTrue("标题模糊不能向首行扩出遮色尾巴", meanDifference(originalRow, pixels("blur-first-row")) < 0.005f)
            assertTrue("前景图形不能参与模糊", contrast(pixels("blur-foreground-mark")) > 0.8f)
            assertInkPreserved(originalText, pixels("blur-foreground-text"))
        }

        val red = awaitMaterial { it.red - it.blue > 0.12f }
        compose.runOnIdle { source.value = Color.Blue }
        ready("blur-body-blue")
        val blue = awaitMaterial { it.blue - it.red > 0.12f }
        assertTrue("材质必须实时采样正文，不能是固定实底：red=$red blue=$blue",
            red.red - red.blue > 0.12f && blue.blue - blue.red > 0.12f)
        assertEquals("正文变化不能改变固定栏或首行位置", originalGeometry, geometry())
        assertInkPreserved(originalText, pixels("blur-foreground-text"))
    }

    @Test
    fun pageTitleBarRevealsTheBodyGraduallyBeforeItsBottomEdge() {
        compose.setContent { SolidFixture(true, Color.Black) }
        ready("title-bar-bottom-transition")
        awaitMaterial { gray(it) < 0.95f }
        val image = pixels("blur-fixture")
        val middle = gray(materialSample(image, y = 48f / 224f))
        val lower = gray(materialSample(image, y = 80f / 224f))
        val outside = gray(materialSample(image, y = 98f / 224f))

        // 真实单行标题栏应从中部就逐渐露出正文，不能把白色遮罩集中到最后一小段撤掉。
        assertTrue("标题中部不应形成浓白雾块：$middle", middle in 0.35f..0.65f)
        assertTrue("接近底边时应已接近正文颜色：$lower", lower < 0.25f)
        assertTrue("正文起点不能被标题材质继续遮色：$outside", outside < 0.03f)
        val profile = (32..96 step 4).map { y -> gray(materialSample(image, y = y / 224f)) }
        assertTrue("底部不能出现集中消退的明暗横带：$profile",
            profile.zipWithNext().all { (above, below) -> above - below < 0.10f })
    }

    @Test
    fun titleAndToolbarShareOneContinuousFadeAcrossTheirJoinAndBottom() {
        val body = mutableStateOf<Color?>(Color.Black)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surface = Color.White)) {
                val state = rememberTopBarBlurState(true)
                CompositionLocalProvider(LocalTopBarBlurState provides state) {
                    Box(Modifier.size(300.dp, 224.dp).background(Color.White).testTag("blur-fixture")) {
                        Canvas(Modifier.fillMaxSize().topBarBlurSource()) {
                            body.value?.let { drawRect(it) } ?: drawStripes()
                        }
                        PageTitleBar(Modifier.fillMaxWidth().height(96.dp), height = 96.dp) {}
                        TopBarBlurSurface(
                            Modifier.offset(y = 96.dp).fillMaxWidth().height(48.dp),
                            topOffset = 96.dp, height = 48.dp, secondaryRow = true,
                        ) {}
                        // 同页底栏不能加入顶部材质范围，否则上面的渐隐会被拉伸到整窗。
                        NavigationBarSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)) {}
                    }
                }
            }
        }
        ready("title-and-toolbar-transition")
        awaitMaterial { gray(it) < 0.95f }
        val image = pixels("blur-fixture")
        fun shade(y: Int) = gray(materialSample(image, y = y / 224f))
        assertTrue("双行中部应已逐渐露出正文，不能保留浓底色", shade(72) in 0.35f..0.65f)
        assertTrue("主标题和工具栏拼接处不能重启渐变", abs(shade(94) - shade(98)) < 0.08f)
        assertTrue("工具栏下半段应继续渐隐", shade(120) < 0.25f)
        assertTrue("渐隐不能扩到正文首行", shade(146) < 0.03f)
        val profile = (48..144 step 4).map(::shade)
        assertTrue("双行底部不能再次出现狭窄白雾带：$profile",
            profile.zipWithNext().all { (above, below) -> above - below in -0.02f..0.10f })

        compose.runOnIdle { body.value = null }
        ready("toolbar-text-detail-suppression")
        var toolbarContrast = 1f
        compose.waitUntil(10_000) {
            val stripes = pixels("blur-fixture")
            toolbarContrast = horizontalContrast(stripes, 0.15f, 0.85f, 120f / 224f)
            horizontalContrast(stripes, 0.15f, 0.85f, 176f / 224f) > 0.7f && toolbarContrast < 0.12f
        }
        assertTrue("工具栏中心必须实际消除文字细节，不能透出清晰正文与控件重叠：$toolbarContrast",
            toolbarContrast < 0.12f)
    }

    @Test
    fun highFrequencyStripesAreBlurredComparedWithBothRawAndTintOnlyControls() {
        val uniformBody = mutableStateOf<Color?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surface = Color.White)) {
                val state = rememberTopBarBlurState(true)
                CompositionLocalProvider(LocalTopBarBlurState provides state) {
                    Box(Modifier.size(300.dp, 224.dp).background(Color.White).testTag("blur-fixture")) {
                        Canvas(Modifier.fillMaxSize().topBarBlurSource()) {
                            val color = uniformBody.value
                            if (color == null) drawStripes() else drawRect(color)
                        }
                        TopBarBlurSurface(
                            modifier = Modifier.width(150.dp).height(96.dp),
                            height = 96.dp,
                        ) {}
                        // 和真实材质处于同一正文、同一高度的普通半透明底；保留足够图案信号。
                        Box(Modifier.offset(x = 150.dp).width(150.dp).height(96.dp)
                            .background(Color.White.copy(alpha = 0.45f)))
                    }
                }
            }
        }
        ready("blur-frequency-controls")

        var actual = FrequencySample(0f, 0f, 0f)
        compose.waitUntil(10_000) {
            val image = pixels("blur-fixture")
            actual = FrequencySample(
                blurred = horizontalContrast(image, 0.11f, 0.39f, 48f / 224f),
                tintOnly = horizontalContrast(image, 0.61f, 0.89f, 48f / 224f),
                raw = horizontalContrast(image, 0.11f, 0.39f, 144f / 224f),
            )
            actual.raw > 0.7f && actual.tintOnly > actual.raw * 0.3f &&
                actual.blurred < actual.tintOnly * 0.45f
        }
        assertTrue("原图必须有高频信号，防止空画面误通过：$actual", actual.raw > 0.7f)
        assertTrue("半透明底对照必须仍保留条纹：$actual", actual.tintOnly > actual.raw * 0.3f)
        assertTrue("真实模糊应消除高频，不能只降低整图不透明度：$actual",
            actual.blurred < actual.tintOnly * 0.45f)

        // 单纯加深 tint 也能降低条纹对比度；再测同一点的低频增益，排除这种假模糊。
        val stripeMean = gray(materialSample(pixels("blur-fixture"), x = 0.25f, y = 48f / 224f))
        compose.runOnIdle { uniformBody.value = Color.Black }
        ready("blur-low-frequency-black")
        var black = 1f
        compose.waitUntil(10_000) {
            black = gray(materialSample(pixels("blur-fixture"), x = 0.25f, y = 48f / 224f))
            black < stripeMean - 0.08f
        }
        compose.runOnIdle { uniformBody.value = Color.White }
        ready("blur-low-frequency-white")
        var white = 0f
        compose.waitUntil(10_000) {
            white = gray(materialSample(pixels("blur-fixture"), x = 0.25f, y = 48f / 224f))
            white - black > 0.15f
        }
        val lowFrequencyGain = white - black
        assertTrue("低频正文必须仍然可见，不能靠实底抹平：gain=$lowFrequencyGain", lowFrequencyGain > 0.15f)
        assertTrue("高频须被选择性衰减；纯 tint 的高低频增益相同：$actual lowFrequencyGain=$lowFrequencyGain",
            actual.blurred < lowFrequencyGain * 0.3f)
    }

    @Test
    fun detachingTheSourceClearsOldPixelsBeforeAnotherSourceIsAttached() {
        val sourcePresent = mutableStateOf(true)
        val source = mutableStateOf(Color.Red)
        val page = mutableStateOf(0)
        compose.setContent { key(page.value) { SolidFixture(true, source.value, sourcePresent.value) } }
        ready("blur-source-red")
        awaitMaterial { it.red - it.blue > 0.12f }
        awaitNavigationMaterial { it.red - it.blue > 0.06f }
        val originalGeometry = geometry()

        compose.runOnIdle { sourcePresent.value = false }
        ready("blur-source-detached")
        val cleared = awaitMaterial { abs(it.red - it.blue) < 0.04f && it.green > 0.7f }
        assertTrue("正文离场后不能保留上一页的红色像素：$cleared", abs(cleared.red - cleared.blue) < 0.04f)
        awaitNavigationMaterial { abs(it.red - it.blue) < 0.04f && it.green > 0.7f }

        compose.runOnIdle { page.value++; source.value = Color.Blue; sourcePresent.value = true }
        ready("blur-new-source-blue")
        awaitMaterial { it.blue - it.red > 0.12f }
        awaitNavigationMaterial { it.blue - it.red > 0.06f }
        assertEquals("采样源卸载和重新接入不能移动页面", originalGeometry, geometry())
    }

    @Test
    fun bothBottomNavigationMaterialsTrackTheBodyWithoutMovingOrRecoloringTheirForeground() {
        val enabled = mutableStateOf(false)
        val floating = mutableStateOf(true)
        val body = mutableStateOf<Color?>(Color.Red)
        compose.setContent { NavigationFixture(enabled.value, floating.value, body.value) }
        ready("navigation-disabled")
        val originalGeometry = navigationGeometry()
        val originalInk = pixels("navigation-foreground-text")
        assertTrue(contrast(pixels("navigation-foreground-mark")) > 0.8f)

        for (isFloating in listOf(true, false)) {
            compose.runOnIdle { floating.value = isFloating; body.value = Color.Red; enabled.value = true }
            ready("navigation-red-$isFloating")
            awaitNavigationMaterial { it.red - it.blue > 0.06f }
            compose.runOnIdle { body.value = Color.Blue }
            ready("navigation-blue-$isFloating")
            awaitNavigationMaterial { it.blue - it.red > 0.06f }
            assertEquals("底栏样式、模糊开关和正文变化不能重排页面", originalGeometry, navigationGeometry())
            assertTrue("底栏前景图形不能被采入模糊", contrast(pixels("navigation-foreground-mark")) > 0.8f)
            assertInkPreserved(originalInk, pixels("navigation-foreground-text"))

            compose.runOnIdle { enabled.value = false }
            ready("navigation-opaque-$isFloating")
            awaitNavigationMaterial { minOf(it.red, it.green, it.blue) > 0.97f }
            assertEquals(originalGeometry, navigationGeometry())
            assertInkPreserved(originalInk, pixels("navigation-foreground-text"))
        }
    }

    @Test
    fun bottomNavigationSuppressesFineTextureWhileStillTransmittingChangingBodyColors() {
        val floating = mutableStateOf(true)
        val body = mutableStateOf<Color?>(null)
        compose.setContent { NavigationFixture(true, floating.value, body.value, frequencyControls = true) }

        for (isFloating in listOf(true, false)) {
            compose.runOnIdle { floating.value = isFloating; body.value = null }
            ready("navigation-frequency-$isFloating")
            var actual = FrequencySample(0f, 0f, 0f)
            compose.waitUntil(10_000) {
                val image = pixels("blur-fixture")
                actual = FrequencySample(
                    blurred = horizontalContrast(image, 0.10f, 0.40f, 0.9f),
                    tintOnly = horizontalContrast(image, 0.60f, 0.90f, 0.9f),
                    raw = horizontalContrast(image, 0.10f, 0.40f, 0.5f),
                )
                actual.raw > 0.7f && actual.tintOnly > 0.1f && actual.blurred < actual.tintOnly * 0.45f
            }
            assertTrue("正文与半透明对照必须真实保留条纹：$actual", actual.raw > 0.7f && actual.tintOnly > 0.1f)

            val stripeMean = gray(materialSample(pixels("blur-fixture"), x = 0.25f, y = 0.9f))
            compose.runOnIdle { body.value = Color.Black }
            ready("navigation-low-frequency-black-$isFloating")
            val black = gray(awaitNavigationMaterial(x = 0.25f) { gray(it) < stripeMean - 0.035f })
            compose.runOnIdle { body.value = Color.White }
            ready("navigation-low-frequency-white-$isFloating")
            val white = gray(awaitNavigationMaterial(x = 0.25f) { gray(it) - black > 0.08f })
            assertTrue("低频正文仍应穿过底栏，不能用不透明底伪装模糊", white - black > 0.08f)
            assertTrue("底栏应选择性消除高频，而非只给整幅画面加遮色：$actual",
                actual.blurred < (white - black) * 0.3f)
        }
    }

    @Composable
    private fun NavigationFixture(enabled: Boolean, floating: Boolean, body: Color?, frequencyControls: Boolean = false) {
        MaterialTheme(colorScheme = lightColorScheme(surface = Color.White, onSurface = Color.Black,
            surfaceContainerLowest = Color.White)) {
            val state = rememberTopBarBlurState(enabled)
            val shape = if (floating) CircleShape else RectangleShape
            CompositionLocalProvider(LocalTopBarBlurState provides state) {
                Box(Modifier.size(300.dp, 280.dp).background(Color.White).testTag("blur-fixture")) {
                    Canvas(Modifier.fillMaxSize().topBarBlurSource()) { body?.let { drawRect(it) } ?: drawStripes() }
                    if (frequencyControls) {
                        NavigationBarSurface(Modifier.align(Alignment.BottomStart).width(150.dp).height(72.dp).clip(shape),
                            tintAlpha = if (floating) 0.80f else 0.90f) {}
                        Box(Modifier.align(Alignment.BottomEnd).width(150.dp).height(72.dp)
                            .background(Color.White.copy(alpha = 0.8f)))
                    } else {
                        Box(Modifier.offset(y = 96.dp).fillMaxWidth().height(48.dp)
                            .background(Color(0xFF59C58A)).testTag("navigation-body-marker"))
                        NavigationBarSurface(Modifier.align(Alignment.BottomCenter).width(280.dp).height(72.dp)
                            .clip(shape).testTag("navigation-material"), tintAlpha = if (floating) 0.80f else 0.90f) {
                            Column(Modifier.padding(start = 16.dp, top = 12.dp)) {
                                Canvas(Modifier.size(64.dp, 12.dp).testTag("navigation-foreground-mark")) { drawStripes() }
                                Text("Pathfin", Modifier.padding(top = 8.dp).testTag("navigation-foreground-text"),
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SolidFixture(enabled: Boolean, source: Color, sourcePresent: Boolean = true) {
        MaterialTheme(colorScheme = lightColorScheme(surface = Color.White)) {
            val state = rememberTopBarBlurState(enabled)
            CompositionLocalProvider(LocalTopBarBlurState provides state) {
                Box(Modifier.size(300.dp, 224.dp).background(Color.White).testTag("blur-fixture")) {
                    if (sourcePresent) Box(Modifier.fillMaxSize().topBarBlurSource().background(source))
                    Box(Modifier.offset(y = 112.dp).fillMaxWidth().height(48.dp)
                        .background(Color(0xFF59C58A)).testTag("blur-first-row")) {
                        Text("第一行内容", Modifier.padding(12.dp), color = Color.Black)
                    }
                    PageTitleBar(
                        modifier = Modifier.fillMaxWidth().height(96.dp).testTag("blur-header"),
                        height = 96.dp,
                    ) {
                        Column(Modifier.padding(start = 16.dp, top = 12.dp)) {
                            Canvas(Modifier.size(64.dp, 12.dp).testTag("blur-foreground-mark")) { drawStripes() }
                            Text("Pathfin", Modifier.padding(top = 8.dp).testTag("blur-foreground-text"),
                                color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    NavigationBarSurface(Modifier.align(Alignment.BottomEnd).width(96.dp).height(48.dp)
                        .testTag("blur-footer")) {}
                }
            }
        }
    }

    private fun ready(stage: String) = VerificationDisplay.awaitWindowReady(compose, stage, compose.onNodeWithTag("blur-fixture"))

    private fun pixels(tag: String): PixelMap {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        // 坐标只在 UI 线程读取，避免截图辅助在测试线程取得瞬态的其他节点范围。
        val region = compose.runOnIdle {
            val bounds = node.boundsInRoot
            val view = checkNotNull(node.root as? ViewRootForTest).view
            val origin = IntArray(2).also(view::getLocationInWindow)
            android.graphics.Rect(
                bounds.left.roundToInt() + origin[0], bounds.top.roundToInt() + origin[1],
                bounds.right.roundToInt() + origin[0], bounds.bottom.roundToInt() + origin[1],
            )
        }
        check(region.width() > 0 && region.height() > 0 && region.left >= 0 && region.top >= 0) {
            "节点截图范围必须有效：$tag $region"
        }
        val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(compose.activity.window, region, bitmap, { status ->
                result = status
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("等待窗口像素超时：$tag $region", copied.await(3, TimeUnit.SECONDS))
            assertEquals("窗口像素复制失败：$tag $region", PixelCopy.SUCCESS, result)
            return bitmap.asImageBitmap().toPixelMap()
        } finally {
            bitmap.recycle()
        }
    }

    private fun geometry() = listOf("blur-header", "blur-first-row", "blur-foreground-mark", "blur-foreground-text", "blur-footer")
        .map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        .let { nodes -> compose.runOnIdle { nodes.map { it.boundsInRoot } } }

    private fun navigationGeometry() = listOf("navigation-material", "navigation-body-marker", "navigation-foreground-mark", "navigation-foreground-text")
        .map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        .let { nodes -> compose.runOnIdle { nodes.map { it.boundsInRoot } } }

    private fun awaitNavigationMaterial(x: Float = 0.85f, matches: (Color) -> Boolean): Color {
        var sampled = Color.Transparent
        compose.waitUntil(10_000) {
            sampled = materialSample(pixels("blur-fixture"), x = x, y = 0.9f)
            matches(sampled)
        }
        return sampled
    }

    private fun awaitMaterial(matches: (Color) -> Boolean): Color {
        var sampled = Color.Transparent
        compose.waitUntil(10_000) {
            sampled = materialSample(pixels("blur-fixture"))
            matches(sampled)
        }
        return sampled
    }

    /** 位于标题中部且远离文字、边界；整块正文变色用于证明低频内容确实穿过材质。 */
    private fun materialSample(image: PixelMap, x: Float = 0.9f, y: Float = 52f / 224f): Color {
        val column = (image.width * x).toInt()
        val row = (image.height * y).toInt()
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            val color = image[(column + dx).coerceIn(0, image.width - 1), (row + dy).coerceIn(0, image.height - 1)]
            red += color.red
            green += color.green
            blue += color.blue
            count++
        }
        return Color(red / count, green / count, blue / count)
    }

    private fun gray(color: Color): Float = (color.red + color.green + color.blue) / 3f

    private fun contrast(image: PixelMap): Float = horizontalContrast(image, 0.05f, 0.95f, 0.5f)

    /** 取分位差，容忍设备像素密度和抗锯齿变化，不锁定某个边缘的精确像素。 */
    private fun horizontalContrast(image: PixelMap, left: Float, right: Float, y: Float): Float {
        val row = (image.height * y).toInt().coerceIn(0, image.height - 1)
        val start = (image.width * left).toInt().coerceIn(0, image.width - 1)
        val end = (image.width * right).toInt().coerceIn(start + 1, image.width)
        val values = (start until end).map { x ->
            val color = image[x, row]
            (color.red + color.green + color.blue) / 3f
        }.sorted()
        return values[(values.lastIndex * 0.9f).toInt()] - values[(values.lastIndex * 0.1f).toInt()]
    }

    private fun meanDifference(before: PixelMap, after: PixelMap): Float {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var difference = 0f
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val first = before[x, y]
            val second = after[x, y]
            difference += (abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)) / 3f
        }
        return difference / (before.width * before.height)
    }

    private fun assertInkPreserved(before: PixelMap, after: PixelMap) {
        assertEquals("标题文字宽度不能变化", before.width, after.width)
        assertEquals("标题文字高度不能变化", before.height, after.height)
        var ink = 0
        var preserved = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val original = before[x, y]
            if (maxOf(original.red, original.green, original.blue) < 0.12f) {
                ink++
                val current = after[x, y]
                if (maxOf(current.red, current.green, current.blue) < 0.18f) preserved++
            }
        }
        assertTrue("文字对照必须存在实心笔画", ink > 20)
        assertTrue("前景文字必须在模糊层上方保持清晰：$preserved/$ink", preserved > ink * 0.9f)
    }

    private fun DrawScope.drawStripes() {
        val stripeWidth = 2.dp.toPx()
        var x = 0f
        var index = 0
        while (x < size.width) {
            drawRect(if (index++ % 2 == 0) Color.Black else Color.White,
                topLeft = Offset(x, 0f), size = Size(minOf(stripeWidth, size.width - x), size.height))
            x += stripeWidth
        }
    }

    private data class FrequencySample(val blurred: Float, val tintOnly: Float, val raw: Float)
}

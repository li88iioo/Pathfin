package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PathfinAppearance
import dev.pathfin.android.ui.ServerManagementScreen
import dev.pathfin.android.ui.ServerShowcase
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.SoftPressIndication
import dev.pathfin.android.ui.theme.ThemePreference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 真实窗口像素回归；独立 ComponentActivity 与合成数据，不读取或修改个人会话。 */
@RunWith(AndroidJUnit4::class)
class SoftPressIndicationTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val componentTargetsOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            check(target == "dev.pathfin.android.debug" || target == "dev.pathfin.android.verification") {
                "纯绘图回归只允许开发包或 verification 包，实际为 $target"
            }
            base.evaluate()
        }
    } }
    // 在 Activity 启动前拒绝生产目标；不改变其它测试的 verification 保护。
    @get:Rule val rules: TestRule = RuleChain.outerRule(componentTargetsOnly).around(compose)

    @Test fun heldPressKeepsRoundedCardFilledAndReleaseOrCancelRestoresPixels() {
        val interactions = MutableInteractionSource()
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ParentColor), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.size(240.dp, 200.dp)
                        // 与卡片调用方一致：裁剪和白底在 indication 外层，内容必须始终铺满。
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .clickable(interactionSource = interactions, indication = SoftPressIndication, onClick = {})
                        .testTag(CardTag),
                ) {
                    val coverHeight = size.height * .75f
                    drawRect(Color.Red, size = Size(size.width, coverHeight))
                    drawRect(Color.Blue, topLeft = Offset(0f, coverHeight),
                        size = Size(size.width, size.height - coverHeight))
                }
            }
        }
        compose.waitForIdle()
        assertFootprint(capture(), Color.Red, Color.Blue, "初始")
        compose.mainClock.autoAdvance = false
        try {
            for (cancel in listOf(false, true)) {
                val ending = if (cancel) "Cancel" else "Release"
                val press = PressInteraction.Press(Offset.Zero)
                compose.runOnIdle { check(interactions.tryEmit(press)) }
                compose.mainClock.advanceTimeBy(200)
                compose.waitForIdle()

                val held = capture()
                val cover = held[held.width / 2, held.height / 2]
                val resume = held[held.width / 2, held.height * 7 / 8]
                assertTrue("$ending 前封面中心仍需保留淡化反馈，实际为 $cover",
                    cover.red > .95f && cover.green in .1f.. .85f && cover.blue in .1f.. .85f)
                assertTrue("$ending 前续播中心仍需保留淡化反馈，实际为 $resume",
                    resume.blue > .95f && resume.red in .1f.. .85f && resume.green in .1f.. .85f)
                // 比较中心与边缘，不复制 indication 的动画或透明度计算。
                assertFootprint(held, cover, resume, "$ending 前按住")

                compose.runOnIdle {
                    check(interactions.tryEmit(if (cancel) PressInteraction.Cancel(press) else PressInteraction.Release(press)))
                }
                compose.mainClock.advanceTimeBy(400)
                compose.waitForIdle()
                assertFootprint(capture(), Color.Red, Color.Blue, "$ending 后恢复")
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun actualServerCardKeepsCoverEdgesWithEitherBlurSettingAndMoreStaysIndependent() {
        val profile = ServerProfile("synthetic-press-profile",
            Session(ServerConfig("https://fixture.invalid"), "synthetic-token", "synthetic-user", "Synthetic", "synthetic-device"),
            "合成按压服务器", icon = "S", showUserName = false, showLastPlayed = false)
        val resume = MediaItem("synthetic-resume", "合成续播", "Movie", runTimeTicks = 54_000_000_000L, positionTicks = 6_000_000_000L)
        val api = JellyfinApi()
        // 空海报、无 image tag 的续播与自定义账号图标不会产生图片 URL 或网络请求。
        assertTrue(api.imageUrl(profile.session, resume) == null && api.imageUrl(profile.session, resume, type = "Backdrop") == null)
        val loader = ImageLoader.Builder(compose.activity).diskCache(null).build()
        val blur = mutableStateOf(false)
        var cardSurface = Color.Unspecified
        var opens = 0
        var expectedOpens = 0
        val card = compose.onNodeWithTag("server-row/${profile.id}")
        fun captureCard() = card.captureToImage().toPixelMap()
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    val surface = PathfinAppearance.card
                    SideEffect { cardSurface = surface }
                    CompositionLocalProvider(LocalAppPreferences provides AppPreferences(blurTopBar = blur.value),
                        LocalPosterLoader provides loader) {
                        Box(Modifier.fillMaxSize().background(PathfinAppearance.canvas)) {
                            ServerManagementScreen(listOf(profile), profile.id, busy = false, error = null, probe = null,
                                adding = false, showcase = ServerShowcase(emptyList(), resume), onProbe = {}, onAddServer = {},
                                onConnectBack = {}, onOpen = { opens++ }, onAdd = { _, _, _, _ -> }, onUpdate = {},
                                onDelete = {}, onAddAddress = { _, _ -> }, onPassword = { _, _, _ -> },
                                onDismissError = {}, api = api)
                        }
                    }
                }
            }
            for (enabled in listOf(false, true)) {
                compose.runOnIdle { blur.value = enabled }
                compose.waitForIdle()
                val baseline = captureCard()
                compose.mainClock.autoAdvance = false
                try {
                    for (cancelled in listOf(true, false)) {
                        val label = "blur=$enabled ${if (cancelled) "Cancel" else "Up"}"
                        card.performTouchInput { down(Offset(baseline.width / 2f, baseline.width * 11f / 32f)) }
                        // LazyColumn 会延迟发出 Press；同时等待它与按下动画，仍早于长按阈值。
                        compose.mainClock.advanceTimeBy(300)
                        compose.waitForIdle()
                        assertServerCoverHeld(baseline, captureCard(), cardSurface, label)
                        card.performTouchInput { if (cancelled) cancel() else up() }
                        compose.mainClock.advanceTimeBy(400)
                        compose.waitForIdle()
                        val restored = captureCard()
                        (serverCoverPoints(baseline) + cornerPoints(baseline)).forEach { (x, y) ->
                            assertColorNear("$label 后恢复 ($x,$y)", baseline[x, y], restored[x, y])
                        }
                        if (!cancelled) expectedOpens++
                        compose.runOnIdle { assertEquals("$label 的主卡点击次数", expectedOpens, opens) }
                    }
                } finally {
                    compose.mainClock.autoAdvance = true
                }
            }
            compose.onNodeWithContentDescription(tr("服务器操作 %1\$s", profile.displayName)).performTouchInput { click() }
            compose.onNodeWithText(tr("显示名称"), substring = false).assertIsDisplayed()
            compose.runOnIdle { assertEquals("更多按钮不能触发主卡点击", expectedOpens, opens) }
        } finally {
            loader.shutdown()
        }
    }

    private fun assertServerCoverHeld(before: PixelMap, held: PixelMap, surface: Color, label: String) {
        val points = serverCoverPoints(before)
        val (centerX, centerY) = points.first()
        fun distance(color: Color) = abs(surface.red - color.red) + abs(surface.green - color.green) + abs(surface.blue - color.blue)
        val baselineSignal = distance(before[centerX, centerY])
        assertTrue("$label 的夹具中心必须有实际封面颜色", baselineSignal > .5f)
        val retention = distance(held[centerX, centerY]) / baselineSignal
        assertTrue("$label 按住时中心必须仍有淡化反馈，实际保留比例为 $retention", retention in .2f.. .9f)
        points.forEach { (x, y) ->
            // 使用真实渐变基线和中部实测淡化比例；露白边的保留比例会降为零。
            val original = before[x, y]
            val expected = Color(surface.red + (original.red - surface.red) * retention,
                surface.green + (original.green - surface.green) * retention,
                surface.blue + (original.blue - surface.blue) * retention)
            assertColorNear("$label 封面 ($x,$y) 必须保留图案而非白边", expected, held[x, y])
        }
        cornerPoints(before).forEach { (x, y) ->
            assertColorNear("$label 圆角外父背景与阴影必须保持 ($x,$y)", before[x, y], held[x, y])
        }
    }

    private fun serverCoverPoints(pixels: PixelMap): List<Pair<Int, Int>> {
        val edge = with(compose.density) { 1.dp.roundToPx() }.coerceAtLeast(1) - 1
        val y = (pixels.width * 11f / 32f).toInt()
        return listOf(pixels.width / 2 to y, pixels.width / 2 to edge, edge to y, pixels.width - 1 - edge to y)
    }

    private fun cornerPoints(pixels: PixelMap): List<Pair<Int, Int>> {
        val edge = with(compose.density) { 1.dp.roundToPx() }.coerceAtLeast(1) - 1
        val right = pixels.width - 1 - edge
        val bottom = pixels.height - 1 - edge
        return listOf(edge to edge, right to edge, edge to bottom, right to bottom)
    }

    private fun capture() = compose.onNodeWithTag(CardTag).captureToImage().toPixelMap()

    private fun assertFootprint(pixels: PixelMap, cover: Color, resume: Color, state: String) {
        // 采样像素中心在边缘内 1dp；240×200dp 的旧 0.98 缩放会在这里确定露出白底。
        val edge = with(compose.density) { 1.dp.roundToPx() }.coerceAtLeast(1) - 1
        val right = pixels.width - 1 - edge
        val bottom = pixels.height - 1 - edge
        val centerX = pixels.width / 2
        val coverY = pixels.height / 2
        val resumeY = pixels.height * 7 / 8
        assertColorNear("$state：封面中心", cover, pixels[centerX, coverY])
        assertColorNear("$state：续播中心", resume, pixels[centerX, resumeY])
        assertColorNear("$state：上边缘不能露白", cover, pixels[centerX, edge])
        assertColorNear("$state：左封面边缘不能露白", cover, pixels[edge, coverY])
        assertColorNear("$state：右封面边缘不能露白", cover, pixels[right, coverY])
        assertColorNear("$state：左续播边缘不能露白", resume, pixels[edge, resumeY])
        assertColorNear("$state：右续播边缘不能露白", resume, pixels[right, resumeY])
        assertColorNear("$state：底边缘不能露白线", resume, pixels[centerX, bottom])
        listOf(edge to edge, right to edge, edge to bottom, right to bottom).forEach { (x, y) ->
            assertColorNear("$state：圆角外 ($x,$y) 必须保留父背景", ParentColor, pixels[x, y])
        }
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color) {
        val difference = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green),
            abs(expected.blue - actual.blue), abs(expected.alpha - actual.alpha))
        assertTrue("$message：期望 $expected，实际 $actual", difference <= .03f)
    }

    private companion object {
        const val CardTag = "soft-press-card"
        val ParentColor = Color(0xFF244434)
    }
}

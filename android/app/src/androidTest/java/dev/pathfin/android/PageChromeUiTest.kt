package dev.pathfin.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.AppSystemBars
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.PageTitleBar
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 仅 verification 合成窗口；真实 PixelCopy 和窗口 Insets，不注册系统辅助服务。 */
@RunWith(AndroidJUnit4::class)
class PageChromeUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test
    fun scrollingContentKeepsTitleBarOpaqueAndControlsFixed() {
        checkVerification()
        val preferences = AppPreferences()
        var clicks = 0
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides preferences) {
                    val list = rememberLazyListState()
                    val surface = MaterialTheme.colorScheme.surface
                    Box(Modifier.fillMaxSize().background(surface)) {
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("chrome-body")) {
                                item { Spacer(Modifier.height(96.dp)) }
                                item { Canvas(Modifier.fillMaxWidth().height(2_400.dp).testTag("chrome-checker")) { checker() } }
                            }
                        }
                        PageTitleBar(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(96.dp).testTag("chrome-header"), height = 96.dp) {
                            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ clicks++ }, Modifier.size(48.dp).testTag("chrome-action")) {
                                    Icon(Icons.Outlined.Search, "合成搜索", Modifier.size(24.dp), tint = Color.Black)
                                }
                                Text("固定清晰标题", Modifier.testTag("chrome-title"), color = Color.Black, fontSize = 18.sp, lineHeight = 24.sp)
                            }
                            Box(Modifier.align(Alignment.TopEnd).offset(y = 32.dp).padding(end = 16.dp)
                                .width(96.dp).height(16.dp).testTag("chrome-upper-probe"))
                            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 2.dp)
                                .width(96.dp).height(4.dp).testTag("chrome-edge-probe"))
                        }

                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "chrome-initial", compose.onNodeWithTag("chrome-header"),
            compose.onNodeWithTag("chrome-title"), compose.onNodeWithTag("chrome-action"))
        val titleBounds = bounds("chrome-title")
        val buttonBounds = bounds("chrome-action")
        val titleClear = pixels("chrome-title")
        val actionClear = pixels("chrome-action")
        val plain = pixels("chrome-upper-probe")
        val plainEdge = pixels("chrome-edge-probe")

        compose.onNodeWithTag("chrome-body").performScrollToIndex(1)
        assertTrue("自有棋盘必须真实滚入标题栏背后", bounds("chrome-checker").top <= bounds("chrome-header").top + 1f)
        assertTrue("滚动时标题栏应保持不透明主题底色", plain.meanDistance(pixels("chrome-upper-probe")) < 2.0)
        assertTrue(plainEdge.meanDistance(pixels("chrome-edge-probe")) < 2.0)
        assertEquals(titleBounds, bounds("chrome-title"))
        assertEquals(buttonBounds, bounds("chrome-action"))
        assertInkUnchanged("标题", titleClear, pixels("chrome-title"))
        assertInkUnchanged("按钮图标", actionClear, pixels("chrome-action"))
        click("chrome-action")
        compose.runOnIdle { assertEquals("标题栏按钮应收到真实触摸", 1, clicks) }
    }

    @Test
    fun darkTitleBarUsesThemeLightInkForDefaultTextAndIcon() {
        checkVerification()
        val expectedInk = AtomicReference(0)
        compose.setContent {
            PathfinTheme(ThemePreference.DARK) {
                val ink = MaterialTheme.colorScheme.onSurface
                SideEffect { expectedInk.set(ink.toArgb()) }
                CompositionLocalProvider(LocalAppPreferences provides AppPreferences(),
                    LocalContentColor provides Color.Black) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        Canvas(Modifier.fillMaxSize()) { checker() }
                        PageTitleBar(Modifier.fillMaxWidth().height(96.dp).testTag("chrome-dark-header"), height = 96.dp) {
                            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Search, "默认浅色图标", Modifier.size(24.dp).testTag("chrome-dark-icon"))
                                Spacer(Modifier.width(12.dp))
                                Text("默认浅色标题", Modifier.testTag("chrome-dark-title"), fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "chrome-dark-theme", compose.onNodeWithTag("chrome-dark-header"),
            compose.onNodeWithTag("chrome-dark-title"), compose.onNodeWithTag("chrome-dark-icon"))
        compose.onNodeWithText("默认浅色标题").assertIsDisplayed()
        compose.onNodeWithContentDescription("默认浅色图标").assertIsDisplayed()
        val expected = expectedInk.get()
        assertTrue("深色主题应提供可见浅色前景", (expected shr 16 and 255) > 180 &&
            (expected shr 8 and 255) > 180 && (expected and 255) > 180)
        for (tag in listOf("chrome-dark-title", "chrome-dark-icon")) {
            val actual = pixels(tag)
            val lightInkPixels = actual.data.count { pixel ->
                abs((pixel shr 16 and 255) - (expected shr 16 and 255)) <= 20 &&
                    abs((pixel shr 8 and 255) - (expected shr 8 and 255)) <= 20 &&
                    abs((pixel and 255) - (expected and 255)) <= 20
            }
            assertTrue("$tag 必须真实绘制主题浅色笔画，不能继承宿主黑色前景：pixels=$lightInkPixels", lightInkPixels > 20)
        }
    }

    @Test fun mainWindowActuallyHidesAndRestoresStatusBarsAndFullscreen() = restoringWindow {
        val hide = mutableStateOf(false)
        val fullscreen = mutableStateOf(false)
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                AppSystemBars(hideStatusBar = hide.value, fullscreen = fullscreen.value)
                Column(Modifier.fillMaxSize().testTag("chrome-bars-host")) {
                    Switch(hide.value, { hide.value = it }, Modifier.testTag("chrome-status-toggle"))
                    Switch(fullscreen.value, { fullscreen.value = it }, Modifier.testTag("chrome-fullscreen-toggle"))
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "chrome-main-bars", compose.onNodeWithTag("chrome-bars-host"))
        awaitBars(compose.activity.window, status = true, navigation = true)
        click("chrome-status-toggle")
        awaitBars(compose.activity.window, status = false, navigation = true)
        click("chrome-status-toggle")
        awaitBars(compose.activity.window, status = true, navigation = true)
        click("chrome-fullscreen-toggle")
        awaitBars(compose.activity.window, status = false, navigation = false)
        click("chrome-fullscreen-toggle")
        awaitBars(compose.activity.window, status = true, navigation = true)
        compose.runOnIdle {
            assertEquals("退出全屏必须恢复 DEFAULT 返回/系统栏行为", WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
                WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).systemBarsBehavior)
        }
    }

    @Test fun dialogUsesItsOwnWindowAndClosingItRestoresTheHostStatusBar() = restoringWindow {
        val show = mutableStateOf(true)
        val hide = mutableStateOf(false)
        val fullscreen = mutableStateOf(false)
        val dialogWindow = AtomicReference<Window?>()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                AppSystemBars(hideStatusBar = false)
                Box(Modifier.fillMaxSize().testTag("chrome-dialog-host"))
                if (show.value) Dialog(onDismissRequest = { show.value = false },
                    properties = DialogProperties(decorFitsSystemWindows = false)) {
                    val view = LocalView.current
                    val window = remember(view) {
                        generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().first().window
                    }
                    DisposableEffect(window) {
                        dialogWindow.set(window)
                        onDispose { dialogWindow.compareAndSet(window, null) }
                    }
                    AppSystemBars(hideStatusBar = hide.value, fullscreen = fullscreen.value)
                    Surface(Modifier.fillMaxWidth().testTag("chrome-dialog")) {
                        Column(Modifier.padding(16.dp)) {
                            Text("合成窗口")
                            Switch(hide.value, { hide.value = it }, Modifier.testTag("chrome-dialog-status-toggle"))
                            Switch(fullscreen.value, { fullscreen.value = it }, Modifier.testTag("chrome-dialog-fullscreen-toggle"))
                            Button({ show.value = false }, Modifier.testTag("chrome-dialog-close")) { Text("关闭") }
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "chrome-dialog-bars", compose.onNodeWithTag("chrome-dialog"))
        val window = requireNotNull(dialogWindow.get())
        assertNotSame("Dialog 效果必须作用于自己的 Window", compose.activity.window, window)
        awaitBars(window, status = true, navigation = true)
        click("chrome-dialog-status-toggle")
        awaitBars(window, status = false, navigation = true)
        click("chrome-dialog-status-toggle")
        awaitBars(window, status = true, navigation = true)
        click("chrome-dialog-fullscreen-toggle")
        awaitBars(window, status = false, navigation = false)
        click("chrome-dialog-fullscreen-toggle")
        awaitBars(window, status = true, navigation = true)
        click("chrome-dialog-status-toggle")
        awaitBars(window, status = false, navigation = true)
        click("chrome-dialog-close")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chrome-dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("chrome-dialog").assertDoesNotExist()
        VerificationDisplay.awaitWindowReady(compose, "chrome-host-focus-restored", compose.onNodeWithTag("chrome-dialog-host"))
        awaitBars(compose.activity.window, status = true, navigation = true)
        assertNull("关闭后不保留旧 Dialog Window", dialogWindow.get())
    }

    private fun click(tag: String) {
        val target = compose.onNodeWithTag(tag)
        VerificationDisplay.awaitWindowReady(compose, "chrome-touch-$tag", target)
        target.performTouchInput {
            // 系统栏切换会重新布局 Dialog；只从 UI 线程取得当前触摸范围，再注入真实触摸。
            compose.runOnUiThread { visibleSize }
            click()
        }
    }

    private fun awaitBars(window: Window, status: Boolean, navigation: Boolean) {
        var actual: Pair<Boolean, Boolean>? = null
        try {
            compose.waitUntil(5_000) {
                compose.runOnIdle {
                    actual = ViewCompat.getRootWindowInsets(window.decorView)?.let {
                        it.isVisible(WindowInsetsCompat.Type.statusBars()) to it.isVisible(WindowInsetsCompat.Type.navigationBars())
                    }
                    actual == (status to navigation)
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("真实 WindowInsets 未到达：expected=($status,$navigation) actual=$actual " +
                "focus=${compose.runOnUiThread { window.decorView.hasWindowFocus() }}", failure)
        }
        compose.runOnIdle {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(window.decorView))
            assertEquals("真实 WindowInsets 状态栏", status, insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            assertEquals("真实 WindowInsets 导航栏", navigation, insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
        }
    }

    private fun restoringWindow(block: () -> Unit) {
        checkVerification()
        val window = compose.activity.window
        val original = compose.runOnUiThread {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            Triple(ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()),
                ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.navigationBars()), controller.systemBarsBehavior)
        }
        try { block() }
        finally {
            compose.runOnUiThread {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = original.third
                if (original.first != false) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
                if (original.second != false) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    private fun checkVerification() { check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification") }
    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
        val node = compose.onNodeWithTag(tag, true).fetchSemanticsNode()
        return compose.runOnIdle { node.boundsInRoot }
    }

    private fun pixels(tag: String): Pixels {
        compose.waitForIdle()
        val node = compose.onNodeWithTag(tag, true).fetchSemanticsNode()
        val area = compose.runOnIdle { node.boundsInWindow }.let {
            Rect(floor(it.left).toInt(), floor(it.top).toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt())
        }
        assertTrue("像素采样区域必须已实际测量：$tag $area", area.width() > 0 && area.height() > 0)
        val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(compose.activity.window, area, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("真实窗口 PixelCopy 应完成：$tag", done.await(3, TimeUnit.SECONDS))
        assertEquals("真实窗口 PixelCopy：$tag", PixelCopy.SUCCESS, result)
        return try {
            val data = IntArray(area.width() * area.height())
            bitmap.getPixels(data, 0, area.width(), 0, 0, area.width(), area.height())
            Pixels(area.width(), area.height(), data)
        } finally { bitmap.recycle() }
    }

    private fun DrawScope.checker() {
        val cell = 12.dp.toPx()
        for (y in 0..ceil(size.height / cell).toInt()) for (x in 0..ceil(size.width / cell).toInt()) {
            drawRect(if ((x + y) % 2 == 0) Color(0xFFF01830) else Color(0xFF1830F0),
                topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
        }
    }

    private fun assertInkUnchanged(label: String, before: Pixels, after: Pixels) {
        assertEquals("$label 宽度保持", before.width, after.width)
        assertEquals("$label 高度保持", before.height, after.height)
        // 只比较近黑的笔画核心，避免把因背景变化而改变的抗锯齿边缘误当成布局变化。
        fun dark(pixel: Int) = (pixel shr 16 and 255) < 16 && (pixel shr 8 and 255) < 16 && (pixel and 255) < 16
        val original = before.data.indices.filter { dark(before.data[it]) }.toSet()
        val current = after.data.indices.filter { dark(after.data[it]) }.toSet()
        assertTrue("$label 必须存在真实清晰深色笔画", original.size > 20 && current.size > 20)
        assertTrue("$label 的笔画位置应保持一致", original.intersect(current).size.toDouble() / original.union(current).size > .96)
    }

    private data class Pixels(val width: Int, val height: Int, val data: IntArray) {
        private fun means() = (0..2).map { channel -> data.map { it shr ((2 - channel) * 8) and 255 }.average() }
        fun meanDistance(other: Pixels): Double = means().zip(other.means()).sumOf { (a, b) -> abs(a - b) } / 3

    }
}

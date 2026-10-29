package dev.pathfin.android

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.ui.AppBackHandler
import dev.pathfin.android.ui.SystemBackGestureProgress
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** AndroidX dispatcher 真正分发手势，像素和几何同时验证页面始终不变形。 */
@RunWith(AndroidJUnit4::class)
class AppBackHandlerTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun cancellingEitherEdgeKeepsPixelsAndCompletionNavigatesExactlyOnce() {
        checkVerification()
        val active = mutableStateOf(true)
        var backs = 0
        compose.setContent {
            Box(Modifier.size(300.dp, 200.dp).background(Color.Black).testTag("back-canvas")) {
                AppBackHandler(active.value, "fixture-page", { backs++; active.value = false }, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
        val canvas = compose.onNodeWithTag("back-canvas")
        val initialBounds = canvas.fetchSemanticsNode().boundsInRoot
        val initialPixels = samplePixels()
        listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT).forEach { edge ->
            startAndProgress(.75f, edge)
            compose.waitUntil { progress() > .7f }
            assertEquals("手势中页面尺寸和位置不能变化", initialBounds, canvas.fetchSemanticsNode().boundsInRoot)
            assertEquals("手势中实际图层不能缩放、平移或露出黑底", initialPixels, samplePixels())
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.waitUntil { progress() == 0f }
            assertEquals("取消不能导航", 0, backs)
            assertTrue(active.value)
            assertEquals(initialPixels, samplePixels())
        }
        startAndProgress(.6f)
        compose.waitUntil { progress() > .5f }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { !active.value }
        assertEquals("一次完成只能导航一次", 1, backs)
        assertEquals(0f, progress(), 0f)
    }

    @Test fun changingTheContentDuringAGestureCannotReturnTheNewPage() {
        checkVerification()
        val key = mutableStateOf("original-page")
        var backs = 0
        compose.setContent {
            Box(Modifier.size(300.dp, 200.dp).background(Color.Black).testTag("back-canvas")) {
                AppBackHandler(true, key.value, { backs++ }, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
        startAndProgress(.9f)
        compose.waitUntil { progress() > .8f }
        compose.runOnIdle { key.value = "new-account-or-page" }
        compose.waitUntil { progress() == 0f }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("旧页面开始的手势不能关闭替换后的页面", 0, backs)
        startAndProgress(.5f)
        compose.waitUntil { progress() > .4f }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { backs == 1 }
        assertEquals(0f, progress(), 0f)
    }

    private fun startAndProgress(fraction: Float, edge: Int = BackEventCompat.EDGE_LEFT) = compose.runOnIdle {
        compose.activity.onBackPressedDispatcher.apply {
            dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, edge))
            dispatchOnBackProgressed(BackEventCompat(150f, 200f, fraction, edge))
        }
    }
    private fun progress() = compose.onNodeWithTag("app-back-handler").fetchSemanticsNode().config[SystemBackGestureProgress]
    private fun samplePixels(): List<Color> {
        val pixels = compose.onNodeWithTag("back-canvas").captureToImage().toPixelMap()
        return listOf(pixels[1, 1], pixels[pixels.width - 2, 1], pixels[1, pixels.height - 2],
            pixels[pixels.width - 2, pixels.height - 2], pixels[pixels.width / 2, pixels.height / 2])
    }
    private fun checkVerification() = check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
}

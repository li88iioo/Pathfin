package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AccentColor
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.accentPalette
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 读取真实主题与默认实心按钮前景，不复制调色板选择逻辑。 */
@RunWith(AndroidJUnit4::class)
class ThemeContrastUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    // 同一测试内只能 setContent 一次，切换主题色走状态。
    private val accentState = mutableStateOf(AccentColor.PATHFIN)
    private var contentSet = false
    private val buttonColors = AtomicReference<Pair<Color, Color>>()

    @Test fun everyPresetLightPrimaryButtonKeepsReadableText() =
        AccentColor.entries.filter { it.argb != null }.forEach { assertFilledButtonContrast(ThemePreference.LIGHT, it) }

    @Test fun everyPresetDarkPrimaryButtonKeepsReadableText() =
        AccentColor.entries.filter { it.argb != null }.forEach { assertFilledButtonContrast(ThemePreference.DARK, it) }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun wallpaperLightPrimaryButtonKeepsReadableText() = assertFilledButtonContrast(ThemePreference.LIGHT, AccentColor.WALLPAPER)

    @Test @SdkSuppress(minSdkVersion = 31)
    fun wallpaperDarkPrimaryButtonKeepsReadableText() = assertFilledButtonContrast(ThemePreference.DARK, AccentColor.WALLPAPER)

    private fun assertFilledButtonContrast(mode: ThemePreference, accent: AccentColor) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        buttonColors.set(null)
        compose.runOnIdle { accentState.value = accent }
        if (!contentSet) compose.setContent {
            PathfinTheme(mode, accentState.value) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = {}, modifier = Modifier.testTag("primary-filled-button")) {
                            val background = MaterialTheme.colorScheme.primary
                            val foreground = LocalContentColor.current
                            SideEffect { buttonColors.set(background to foreground) }
                            Text("播放")
                        }
                    }
                }
            }
        }
        contentSet = true
        compose.waitForIdle()
        val button = compose.onNodeWithTag("primary-filled-button")
        VerificationDisplay.awaitWindowReady(compose, "primary-contrast-$mode-$accent", button)
        button.assertVerificationDisplayed(compose)
        compose.runOnIdle {
            val (background, foreground) = checkNotNull(buttonColors.get())
            val backgroundLuminance = background.luminance()
            val foregroundLuminance = foreground.luminance()
            val contrast = (maxOf(backgroundLuminance, foregroundLuminance) + 0.05f) /
                (minOf(backgroundLuminance, foregroundLuminance) + 0.05f)
            accent.argb?.let { argb -> assertEquals("$mode $accent 须真正作用到主题主色",
                accentPalette(Color(argb), mode == ThemePreference.DARK).accent, background) }
            assertTrue("$mode $accent 的实心按钮文字对比度须达到 4.5:1，实际为 $contrast:1；" +
                "primary=$background foreground=$foreground", contrast >= 4.5f)
        }
    }
}

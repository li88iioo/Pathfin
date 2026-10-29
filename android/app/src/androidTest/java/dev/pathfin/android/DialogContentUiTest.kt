package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.ui.PlaybackSourceDialog
import dev.pathfin.android.ui.SourceSelection
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 对话框独占滚动；长版本列表不能触发嵌套 LazyColumn 的无限高度测量。 */
@RunWith(AndroidJUnit4::class)
class DialogContentUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun longVersionListCanReachItsLastChoiceAtLargeTypeAndCancelRemainsVisible() {
        val choices = mutableListOf<String>()
        var dismisses = 0
        val sources = List(20) { i -> MediaSource("source-$i", name = "合成播放版本 ${i + 1} · 长标题检查", container = "mkv") }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                PathfinTheme(ThemePreference.LIGHT) {
                    PlaybackSourceDialog(SourceSelection(MediaItem("item", "合成影片", "Movie"), PlaybackInfo(sources, "session"), 0),
                        onChoose = choices::add, onDismiss = { dismisses++ })
                }
            }
        }
        compose.onNodeWithTag("playback-source/source-19").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("source-19"), choices)
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertEquals(1, dismisses)
    }
}

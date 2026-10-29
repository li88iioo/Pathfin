package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.ui.DetailActions
import dev.pathfin.android.ui.DetailSynopsis
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailComponentsTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun actionRowKeepsCallbacksBusyBoundsAndPlaybackOptions() {
        val busy = mutableStateOf(false)
        var plays = 0; var starts = 0; var favorites = 0; var downloads = 0; var watched = 0; var casts = 0
        compose.setContent { PathfinTheme(ThemePreference.LIGHT) { Surface(Modifier.statusBarsPadding()) {
            DetailActions(MediaItem("synthetic", "合成电影", "Movie"), busy.value, false, { plays++ }, { starts++ },
                onCast = { casts++ }, onFavorite = { favorites++ }, onDownload = { downloads++ }, onWatched = { watched++ },
                favoriteBusy = busy.value, downloadBusy = busy.value, watchedBusy = busy.value)
        } } }
        VerificationDisplay.awaitWindowReady(compose, "detail-actions", compose.onNodeWithTag("detail-play"),
            compose.onNodeWithContentDescription("下载媒体"))
        val nodes = listOf("detail-play-pill", "detail-play-label").map { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode() }
        val (pill, label) = compose.runOnIdle { nodes.map { it.boundsInRoot } }
        assertEquals("播放文字在整个按钮正中心", pill.center.x, label.center.x, 1f)
        assertEquals("播放文字垂直居中", pill.center.y, label.center.y, 1f)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("detail-play-label", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        repeat(layout.lineCount) { line ->
            assertEquals("每行实际字形排版居中", layout.size.width / 2f,
                (layout.getLineLeft(line) + layout.getLineRight(line)) / 2f, 1f)
        }
        val original = compose.onNodeWithTag("detail-play").readVerificationNode(compose) { it.boundsInRoot }
        compose.onNodeWithTag("detail-play").performVerificationClick(compose)
        compose.onNodeWithContentDescription("收藏媒体").performVerificationClick(compose)
        compose.onNodeWithContentDescription("下载媒体").performVerificationClick(compose)
        compose.onNodeWithContentDescription("标记已观看").performVerificationClick(compose)
        compose.onNodeWithContentDescription("播放选项").performVerificationClick(compose)
        compose.onNodeWithText("从头播放").performVerificationClick(compose)
        compose.onNodeWithContentDescription("播放选项").performVerificationClick(compose)
        compose.onNodeWithText("投屏").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1, 1, 1), listOf(plays, starts, favorites, downloads, watched, casts)); busy.value = true }
        compose.onNodeWithTag("detail-play").assertIsNotEnabled()
        compose.onNodeWithContentDescription("收藏媒体").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下载媒体").assertIsNotEnabled()
        compose.onNodeWithContentDescription("标记已观看").assertIsNotEnabled()
        assertEquals(original, compose.onNodeWithTag("detail-play").readVerificationNode(compose) { it.boundsInRoot })
    }

    @Test fun synopsisExpandsWithoutMovingPageAndSwipeOrBackOnlyClosesSheet() {
        val item = MediaItem("synopsis", "合成详情", "Movie", overview = "这是一段用于验证完整简介抽屉和返回行为的合成文字。".repeat(30))
        compose.setContent { PathfinTheme(ThemePreference.DARK) { Surface { Column(Modifier.fillMaxWidth()) { DetailSynopsis(item) } } } }
        val before = compose.onNodeWithTag("detail-synopsis").readVerificationNode(compose) { it.boundsInRoot }
        compose.onNodeWithTag("detail-synopsis").performVerificationClick(compose)
        compose.onNodeWithTag("detail-synopsis-sheet").assertVerificationDisplayed(compose)
        compose.onNode(hasText(item.overview) and hasAnyAncestor(hasTestTag("detail-synopsis-sheet")), useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("关闭简介").assertDoesNotExist()
        compose.onNodeWithTag("detail-synopsis-sheet").performVerificationTouchInput(compose) {
            swipeDown(startY = 10f, endY = height.toFloat() - 1f, durationMillis = 250)
        }
        compose.onNodeWithTag("detail-synopsis-sheet").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("detail-synopsis").readVerificationNode(compose) { it.boundsInRoot })
        compose.onNodeWithTag("detail-synopsis").performVerificationClick(compose)
        Espresso.pressBack()
        compose.onNodeWithTag("detail-synopsis-sheet").assertDoesNotExist()
        compose.onNodeWithTag("detail-synopsis").assertVerificationDisplayed(compose)
    }

    @Test fun arrivingSynopsisKeepsTheSameSpaceAsTheInitialMetadata() {
        val item = mutableStateOf(MediaItem("delayed-description", "合成详情", "Movie"))
        compose.setContent { PathfinTheme(ThemePreference.LIGHT) { Surface {
            Column(Modifier.fillMaxWidth()) { DetailSynopsis(item.value) }
        } } }
        val before = compose.onNodeWithTag("detail-synopsis").readVerificationNode(compose) { it.boundsInRoot }
        compose.runOnIdle { item.value = item.value.copy(overview = "延迟到达的完整简介应填充已有空间。".repeat(20)) }
        assertEquals("完整简介到达不能把后面的播放按钮推走", before,
            compose.onNodeWithTag("detail-synopsis").readVerificationNode(compose) { it.boundsInRoot })
        compose.runOnIdle { item.value = item.value.copy(overview = "短简介") }
        assertEquals("短简介也保持相同排版槽", before,
            compose.onNodeWithTag("detail-synopsis").readVerificationNode(compose) { it.boundsInRoot })
    }
}

package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.PageTransitionHost
import dev.pathfin.android.ui.ReleaseDepartedPageStates
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.savedPageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Compose 保存状态与出入场动画，验证滚动状态随导航栈的生存期。 */
@RunWith(AndroidJUnit4::class)
class PageStateLifecycleTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val library = MediaItem("synthetic-library", "合成媒体库", "CollectionFolder", isFolder = true)

    @Test fun detailReturnKeepsPositionButReopeningPoppedLibraryStartsAtTop() {
        val folder = Route.Folder(library)
        val original = AppState(currentProfileId = "synthetic-account-a", routes = listOf(Route.Home, folder))
        val frame = show(original)
        scrollDown()
        val position = position()
        compose.runOnIdle { frame.value = original.copy(routes = original.routes + Route.Detail("synthetic-movie")) }
        compose.waitForIdle()
        compose.runOnIdle { frame.value = original }
        compose.waitForIdle()
        assertEquals("详情返回保留原来的滚动位置", position, position(), .01f)
        compose.runOnIdle { frame.value = original.copy(routes = listOf(Route.Home)) }
        compose.waitForIdle()
        compose.runOnIdle { frame.value = original.copy(routes = listOf(Route.Home, Route.Folder(library))) }
        compose.waitForIdle()
        assertEquals("退出媒体库后重新进入从顶部开始", 0f, position(), .01f)
    }

    @Test fun changingAccountDoesNotDiscardAnotherAccountsSavedPosition() {
        val folder = Route.Folder(library)
        val first = AppState(currentProfileId = "synthetic-account-a", routes = listOf(Route.Home, folder))
        val frame = show(first)
        scrollDown()
        val position = position()
        compose.runOnIdle { frame.value = AppState(currentProfileId = "synthetic-account-b") }
        compose.waitForIdle()
        compose.runOnIdle { frame.value = first }
        compose.waitForIdle()
        assertEquals("另一账号的路由不能释放原账号的页面状态", position, position(), .01f)
    }

    @Test fun newSearchVisitCannotRestorePreviousSearchPageState() {
        val first = AppState(currentProfileId = "synthetic-account-a", routes = listOf(Route.Search))
        val frame = show(first)
        scrollDown()
        compose.runOnIdle { frame.value = first.copy(routes = listOf(Route.Home), searchVisitId = "synthetic-next-search") }
        compose.waitForIdle()
        compose.runOnIdle { frame.value = frame.value.copy(routes = listOf(Route.Search)) }
        compose.waitForIdle()
        assertEquals("新搜索会话不能恢复旧输入页的保存状态", 0f, position(), .01f)
    }

    private fun show(initial: AppState): MutableState<AppState> {
        val frame = mutableStateOf(initial)
        compose.setContent {
            val state = frame.value
            val saved = rememberSaveableStateHolder()
            ReleaseDepartedPageStates(state, saved)
            PageTransitionHost(state) { page ->
                saved.SaveableStateProvider(page.savedPageKey()) {
                    if (page.route is Route.Folder || page.route == Route.Search) {
                        val list = rememberLazyListState()
                        LazyColumn(Modifier.fillMaxSize().testTag("lifecycle-list"), state = list) {
                            items((0 until 120).toList(), key = { it }) { Text("合成条目 $it") }
                        }
                    } else Text(page.route.key)
                }
            }
        }
        compose.waitForIdle()
        return frame
    }

    private fun scrollDown() {
        compose.onNodeWithTag("lifecycle-list").performScrollToIndex(60)
        compose.waitForIdle()
        assertTrue("必须先发生实际滚动", position() > 0f)
    }

    private fun position(): Float = compose.onNodeWithTag("lifecycle-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
}

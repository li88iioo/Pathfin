package dev.pathfin.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BrowserScreen
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.rememberPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实指针/无障碍动作；触感观察器委托设备框架，不把回调次数冒充物理震动。 */
@RunWith(AndroidJUnit4::class)
class AlphabetIndexUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun holdingDownAndCancellingNeverSelectsOrVibrates() {
        val env = show()
        rail().performTouchInput { down(point("M")) }
        assertStableWindow(env, emptyList(), 0, preview = null)
        assertUnconsumedDowns(env, 1)
        rail().performTouchInput { cancel() }
        assertStableWindow(env, emptyList(), 0, preview = null)
    }

    /** 此项在旧21包应失败：旧版在DOWN已消费事件并产生字母触感/查询。 */
    @Test fun horizontalAndDiagonalBackIntentNeverSelectsOrVibrates() {
        val env = show()
        val distance = maxOf(env.touchSlop * 4f, railWidth() * 2f)
        val diagonal = env.touchSlop * 1.5f
        val paths = listOf(
            Offset(-distance, 0f), Offset(distance, 0f),
            Offset(-distance, -diagonal), Offset(distance, -diagonal),
            Offset(-distance, diagonal), Offset(distance, diagonal),
        )
        paths.forEachIndexed { index, delta ->
            rail().performTouchInput {
                val start = point("M")
                down(start)
                moveTo(start + delta, delayMillis = 16)
                // 一旦确认水平意图，即使回到原字母再UP，也不能退化为点击。
                moveTo(start, delayMillis = 16)
                up()
            }
            assertStableWindow(env, emptyList(), 0, preview = null)
            assertUnconsumedDowns(env, index + 1)
        }
    }

    @Test fun verticalDragShowsPerLetterFeedbackButOnlyNormalUpSubmitsTheFinalLetter() {
        val env = show()
        rail().performTouchInput { down(point("A")) }
        assertStableWindow(env, emptyList(), 0, preview = null)
        rail().performTouchInput {
            moveTo(point("M"), delayMillis = 16)
            moveTo(point("N"), delayMillis = 16)
            moveTo(point("O"), delayMillis = 16)
            moveTo(point("M"), delayMillis = 16)
        }
        // 指针仍按住，持续观察也必须零查询；只有预览和四次逐格触感。
        assertStableWindow(env, emptyList(), 4, preview = "M")
        assertUnconsumedDowns(env, 1)
        rail().performTouchInput { up() }
        assertCommitted(env, listOf(env.original.copy(nameInitial = "M")), 4)
        compose.onNodeWithTag("library-index/M").assertIsSelected()
        assertStableWindow(env, listOf(env.original.copy(nameInitial = "M")), 4, preview = null)
    }

    @Test fun cancellingAConfirmedVerticalDragDiscardsItsPendingLetter() {
        val env = show()
        rail().performTouchInput {
            down(point("A"))
            moveTo(point("M"), delayMillis = 16)
        }
        assertStableWindow(env, emptyList(), 1, preview = "M")
        rail().performTouchInput { cancel() }
        assertStableWindow(env, emptyList(), 1, preview = null)
        compose.onNodeWithTag("library-index/M").assertIsNotSelected()
        assertUnconsumedDowns(env, 1)
    }

    @Test fun leavingSidewaysAfterVerticalConfirmationCancelsInsteadOfSubmitting() {
        val env = show()
        val side = maxOf(env.touchSlop * 8f, railHeight() * .75f)
        rail().performTouchInput {
            down(point("A"))
            moveTo(point("M"), delayMillis = 16)
        }
        assertStableWindow(env, emptyList(), 1, preview = "M")
        rail().performTouchInput {
            moveTo(point("M") + Offset(-side, 0f), delayMillis = 16)
            moveTo(point("M"), delayMillis = 16)
            up()
        }
        assertStableWindow(env, emptyList(), 1, preview = null)
        compose.onNodeWithTag("library-index/M").assertIsNotSelected()
        assertUnconsumedDowns(env, 1)
    }

    @Test fun draggingBackToTheOriginallySelectedLetterKeepsItsFilter() {
        val env = show(defaultQuery().copy(nameInitial = "A"))
        rail().performTouchInput {
            down(point("A"))
            moveTo(point("M"), delayMillis = 16)
            moveTo(point("N"), delayMillis = 16)
            moveTo(point("A"), delayMillis = 16)
        }
        assertStableWindow(env, emptyList(), 3, preview = "A")
        rail().performTouchInput { up() }
        assertCommitted(env, emptyList(), 3)
        compose.onNodeWithTag("library-index/A").assertIsSelected()
        assertStableWindow(env, emptyList(), 3, preview = null)
        assertUnconsumedDowns(env, 1)
    }

    @Test fun tapsAndRepeatedTapsSelectThenClearOnlyOnUp() {
        val env = show()
        rail().performTouchInput { down(point("M")) }
        assertStableWindow(env, emptyList(), 0, preview = null)
        rail().performTouchInput { up() }
        val selected = env.original.copy(nameInitial = "M")
        assertCommitted(env, listOf(selected), 1)
        compose.onNodeWithTag("library-index/M").assertIsSelected()
        rail().performTouchInput { down(point("M")) }
        assertStableWindow(env, listOf(selected), 1, preview = null)
        rail().performTouchInput { up() }
        assertCommitted(env, listOf(selected, env.original), 2)
        compose.onNodeWithTag("library-index/M").assertIsNotSelected()
        assertStableWindow(env, listOf(selected, env.original), 2, preview = null)
        assertUnconsumedDowns(env, 2)
    }

    @Test fun accessibilityActionsSelectAndToggleThroughTheProductionSemantics() {
        val env = show()
        val letter = compose.onNodeWithTag("library-index/M")
        letter.assertContentDescriptionEquals("按字母 M 筛选")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertIsNotSelected()
        val selected = env.original.copy(nameInitial = "M")
        // 动作与断言处于同一Main调用：返回前须已派发，不能依赖异步等待后才成立。
        letter.performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue(action())
            assertSnapshot(env, listOf(selected), 1)
        }
        assertCommitted(env, listOf(selected), 1)
        letter.assertIsSelected()
        compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
        letter.performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue(action())
            assertSnapshot(env, listOf(selected, env.original), 2)
        }
        assertCommitted(env, listOf(selected, env.original), 2)
        letter.assertIsNotSelected()
        compose.onNodeWithTag("library-index/0").assertContentDescriptionEquals("数字开头")
        compose.onNodeWithTag("library-index/#").assertContentDescriptionEquals("非字母开头")
        assertStableWindow(env, listOf(selected, env.original), 2, preview = null)
        compose.runOnIdle { assertTrue("无障碍动作不能伪造触摸DOWN", env.downConsumed.isEmpty()) }
    }

    private class Fixture(val original: LibraryQuery) {
        val state = mutableStateOf(BrowserUiState(query = original, content = ContentState(
            items = listOf(MediaItem("index-ui-fixture", "Synthetic index card", "Movie")), total = 1, nextIndex = 1)))
        val changes = mutableListOf<LibraryQuery>()
        val haptics = mutableListOf<HapticFeedbackType>()
        val downConsumed = mutableListOf<Boolean>()
        var touchSlop = 0f
    }

    private fun show(initial: LibraryQuery = defaultQuery()): Fixture {
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification")
        val env = Fixture(initial)
        val session = Session(ServerConfig("https://index-ui.invalid"), "synthetic-index-token", "synthetic-index-user", "fixture", "synthetic-index-device")
        val api = JellyfinApi()
        compose.setContent {
            val platform = LocalHapticFeedback.current
            val slop = LocalViewConfiguration.current.touchSlop
            SideEffect { env.touchSlop = slop }
            val observer = remember(platform) { object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    env.haptics += hapticFeedbackType
                    platform.performHapticFeedback(hapticFeedbackType)
                }
            } }
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalHapticFeedback provides observer,
                    LocalPosterLoader provides rememberPosterLoader(session)) {
                    Surface(Modifier.fillMaxSize().pointerInput(env) {
                        awaitPointerEventScope {
                            while (true) {
                                // 只读父层Final pass，记录子控件处理后的DOWN消费状态。
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                event.changes.filter { it.pressed && !it.previousPressed }.forEach {
                                    env.downConsumed += it.isConsumed
                                }
                            }
                        }
                    }) {
                        BrowserScreen(env.state.value, session, api,
                            onQueryChange = { env.changes += it; env.state.value = env.state.value.copy(query = it) },
                            onStyleChange = {}, onOpen = {}, onRetry = {}, onMore = {},
                            libraryType = "movies", showIndex = true)
                    }
                }
            }
        }
        val activation = compose.onNodeWithTag("library-index-activation")
        VerificationDisplay.awaitWindowReady(compose, "alphabet-index-gesture", activation)
        rail().assertDoesNotExist()
        val action = activation.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "显示字母索引" }
        compose.runOnIdle { assertTrue("真实无障碍动作唤出索引", action.action()) }
        VerificationDisplay.awaitWindowReady(compose, "alphabet-index-open", rail())
        compose.runOnIdle { assertTrue("读取真实系统触摸阈值", env.touchSlop > 0f) }
        return env
    }

    private fun rail() = compose.onNodeWithTag("library-index")
    private fun railWidth() = rail().fetchSemanticsNode().boundsInRoot.width
    private fun railHeight() = rail().fetchSemanticsNode().boundsInRoot.height

    private fun TouchInjectionScope.point(label: String): Offset {
        val index = when (label) { "0" -> 0; "#" -> 1; else -> { require(label.length == 1 && label[0] in 'A'..'Z'); label[0] - 'A' + 2 } }
        return Offset(center.x, height * (index + .5f) / 28f)
    }

    private fun assertCommitted(env: Fixture, expected: List<LibraryQuery>, feedbackCount: Int) {
        compose.waitUntil(5_000) { env.changes.size >= expected.size }
        compose.runOnIdle { assertSnapshot(env, expected, feedbackCount) }
        compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
    }

    /** 有界观察真实wallclock，捕捉误发或迟到查询，不依赖特定防抖间隔。 */
    private fun assertStableWindow(env: Fixture, expected: List<LibraryQuery>, feedbackCount: Int, preview: String?) {
        val deadline = SystemClock.elapsedRealtime() + 180L
        val tick = CountDownLatch(1)
        do {
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
            compose.runOnIdle { assertSnapshot(env, expected, feedbackCount) }
            if (preview == null) compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
            else compose.onNodeWithTag("library-index-preview").assertIsDisplayed().assert(hasAnyDescendant(hasText(preview)))
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0L) tick.await(minOf(16L, remaining), TimeUnit.MILLISECONDS)
        } while (SystemClock.elapsedRealtime() < deadline)
        instrumentation.waitForIdleSync()
        compose.runOnIdle { assertSnapshot(env, expected, feedbackCount) }
    }

    private fun assertSnapshot(env: Fixture, expected: List<LibraryQuery>, feedbackCount: Int) {
        assertEquals("手势必须保留原媒体类型、排序与全部筛选，且不能提前/重复提交", expected, env.changes)
        assertEquals(expected.lastOrNull() ?: env.original, env.state.value.query)
        assertEquals("触感仅发生于确认的逐格移动或正常激活", List(feedbackCount) { HapticFeedbackType.TextHandleMove }, env.haptics)
    }

    private fun assertUnconsumedDowns(env: Fixture, count: Int) = compose.runOnIdle {
        assertEquals("DOWN属于系统手势竞争阶段，字母索引不得提前消费", List(count) { false }, env.downConsumed)
    }

    private fun defaultQuery() = LibraryQuery(itemTypes = setOf("Movie"), sort = LibrarySort.TITLE, descending = false,
        genres = setOf("SyntheticGenre"), tags = setOf("SyntheticTag"), years = setOf(2021))
}

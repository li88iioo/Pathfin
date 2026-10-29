package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.BrowserScreen
import dev.pathfin.android.ui.BrowserUiState
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LocalPageRefreshEnabled
import dev.pathfin.android.ui.LocalPageTopInset
import dev.pathfin.android.ui.LocalNavigationBottomInset
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实长按、UP/CANCEL、无障碍入口及网格测量；仅 verification 合成资料，不访问服务器。 */
@RunWith(AndroidJUnit4::class)
class LibraryIndexOverlayTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun longPressRevealsWithoutQueryHoldingPausesTimeoutAndReleaseStartsFourSecondIdle() {
        val fixture = show()
        val initialCards = cardBounds()
        rail().assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            activation().performVerificationTouchInput(compose) {
                val railHeight = minOf(height.toFloat(), 448.dp.toPx())
                val railTop = (height - railHeight) / 2f
                down(Offset(center.x, railTop + railHeight * 14.5f / 28f)) // M 的格子中心。
            }
            compose.mainClock.advanceTimeBy((fixture.longPressTimeout - 100).coerceAtLeast(1))
            rail().assertDoesNotExist()
            assertUnchanged(fixture, haptics = emptyList())
            compose.mainClock.advanceTimeBy(200)
            compose.mainClock.advanceTimeByFrame()
            rail().assertVerificationDisplayed(compose)
            compose.onNodeWithTag("library-index-preview").assertVerificationDisplayed(compose).assert(hasAnyDescendant(hasText("M")))
            assertUnchanged(fixture, haptics = listOf(HapticFeedbackType.LongPress)) // 唤出索引是更明显的长按振动
            assertEquals("浮层出现不能重排封面", initialCards, cardBounds())

            compose.mainClock.advanceTimeBy(5_000)
            rail().assertVerificationDisplayed(compose)
            assertUnchanged(fixture, haptics = listOf(HapticFeedbackType.LongPress))
            activation().performVerificationTouchInput(compose) { up() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(listOf(fixture.initial.copy(nameInitial = "M")), fixture.queries)
                assertEquals("长按 UP 只提交一次", 1, fixture.haptics.size)
            }
            compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
            compose.onNodeWithTag("library-index/M").assertIsSelected()
            compose.mainClock.advanceTimeBy(3_800)
            rail().assertVerificationDisplayed(compose)
            compose.mainClock.advanceTimeBy(300)
            compose.mainClock.advanceTimeByFrame()
            rail().assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("自动隐藏不清除已提交筛选", "M", fixture.state.value.query.nameInitial)
                assertEquals(1, fixture.queries.size)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun hiddenHorizontalCancelAndMultiplePointersNeverOpenAndDisablingAnOpenDragCannotCommit() {
        val fixture = show()
        activation().performVerificationTouchInput(compose) {
            down(center)
            moveTo(center + Offset(-fixture.touchSlop * 4, 0f), delayMillis = 16)
            up()
        }
        rail().assertDoesNotExist()
        assertUnchanged(fixture, emptyList())
        activation().performVerificationTouchInput(compose) { down(center); cancel() }
        rail().assertDoesNotExist()
        assertUnchanged(fixture, emptyList())
        activation().performVerificationTouchInput(compose) {
            down(0, center)
            down(1, center + Offset(0f, 10f))
            advanceEventTime(fixture.longPressTimeout + 100)
            cancel()
        }
        rail().assertDoesNotExist()
        assertUnchanged(fixture, emptyList())

        toggleIndex("显示字母索引")
        rail().performVerificationTouchInput(compose) {
            down(Offset(center.x, height * 2.5f / 28f)) // A
            moveTo(Offset(center.x, height * 14.5f / 28f), delayMillis = 16) // M
        }
        compose.onNodeWithTag("library-index-preview").assertVerificationDisplayed(compose)
        assertUnchanged(fixture, listOf(HapticFeedbackType.TextHandleMove))
        compose.runOnIdle { fixture.enabled.value = false }
        rail().assertDoesNotExist()
        compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
        activation().performVerificationTouchInput(compose) { cancel() }
        assertUnchanged(fixture, listOf(HapticFeedbackType.TextHandleMove))
        val blockedAction = activation().fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle { assertFalse("认证门闩禁止重新打开索引", blockedAction.action()) }
    }

    @Test fun bothGridMarginsStayEqualAndOpeningClosingOrRemovingTheIndexNeverMovesCards() {
        val fixture = show()
        val layout = bounds(compose.onNodeWithTag("browser-grid"), *cardTargets())
        val grid = layout.first()
        val initialCards = layout.drop(1)
        // 列宽余数可能相差1px，2:3图片高度因此相差至多2px，不能误认为换了下一行。
        val firstRow = initialCards.filter { kotlin.math.abs(it.top - initialCards.first().top) <= 3f }
        assertTrue("合成库必须真实排出多列", firstRow.size >= 2)
        assertEquals("没有右侧 AZ 常驻占位，左右封面外边距必须一致", firstRow.first().left - grid.left,
            grid.right - firstRow.last().right, 1f)
        toggleIndex("显示字母索引")
        rail().assertVerificationDisplayed(compose)
        assertEquals(initialCards, cardBounds())
        toggleIndex("隐藏字母索引")
        rail().assertDoesNotExist()
        assertEquals(initialCards, cardBounds())
        compose.runOnIdle { fixture.showIndex.value = false }
        activation().assertDoesNotExist()
        assertEquals("启用索引能力也不能预留空间", initialCards, cardBounds())
        compose.runOnIdle { fixture.showIndex.value = true }
        activation().assertVerificationDisplayed(compose)
        rail().assertDoesNotExist()
        assertEquals(initialCards, cardBounds())
        assertUnchanged(fixture, emptyList())
    }

    @Test fun compactLargeFontViewportKeepsNumericSymbolAndLastLetterCentersReachable() {
        val fixture = show(viewport = DpSize(360.dp, 540.dp), fontScale = 1.6f, topInset = 84.dp, bottomInset = 40.dp)
        val cards = cardBounds()
        toggleIndex("显示字母索引")
        val expected = mutableListOf<LibraryQuery>()
        for (label in listOf("0", "#", "Z")) {
            compose.onNodeWithTag("library-index/$label").assertVerificationDisplayed(compose).performVerificationTouchInput(compose) { click() }
            expected += fixture.initial.copy(nameInitial = label)
            compose.runOnIdle { assertEquals("短屏必须通过实际字母中心触摸提交 $label", expected, fixture.queries) }
            compose.onNodeWithTag("library-index/$label").assertIsSelected()
        }
        val (activation, toolbar, grid) = bounds(activation(), compose.onNodeWithTag("browser-toolbar"), compose.onNodeWithTag("browser-grid"))
        val bottomInset = with(compose.density) { 40.dp.toPx() }
        assertTrue("索引触摸区不得延伸到双行工具栏后面", activation.top >= toolbar.bottom)
        assertTrue("索引触摸区必须让出系统导航安全区", activation.bottom <= grid.bottom - bottomInset + 1f)
        assertEquals("索引出现和字母切换都不得改动封面网格尺寸", cards, cardBounds())
    }

    @Test fun blankSpaceAboveOrBelowTheIndexAndCancelledEdgeDragsNeverSubmit() {
        val fixture = show(viewport = DpSize(360.dp, 620.dp), topInset = 24.dp, bottomInset = 16.dp)
        toggleIndex("显示字母索引")
        val (activation, visible) = bounds(activation(), rail())
        activation().performVerificationTouchInput(compose) {
            val clearance = 18.dp.toPx()
            val above = visible.top - activation.top - clearance
            val below = visible.bottom - activation.top + clearance
            assertTrue("夹具必须在可见索引上下留出真实空白", above > 0f && below < height)
            click(Offset(center.x, above))
            click(Offset(center.x, below))
        }
        assertUnchanged(fixture, emptyList())
        val (first, last) = bounds(compose.onNodeWithTag("library-index/0"), compose.onNodeWithTag("library-index/Z"))
            .map { it.center - activation.topLeft }
        for ((start, end) in listOf(first to last, last to first)) {
            activation().performVerificationTouchInput(compose) { down(start); moveTo(end, delayMillis = 16); cancel() }
            compose.runOnIdle { assertEquals(emptyList<LibraryQuery>(), fixture.queries) }
            compose.onNodeWithTag("library-index-preview").assertDoesNotExist()
        }
    }

    private class Fixture {
        val initial = LibraryQuery(itemTypes = setOf("Movie"), genres = setOf("合成类型"), years = setOf(2026))
        val state = mutableStateOf(BrowserUiState(query = initial, content = ContentState(
            items = (1..3).map { MediaItem("overlay-$it", "Synthetic $it", "Movie", year = 2026) }, total = 3, nextIndex = 3)))
        val queries = mutableListOf<LibraryQuery>()
        val haptics = mutableListOf<HapticFeedbackType>()
        val enabled = mutableStateOf(true)
        val showIndex = mutableStateOf(true)
        var touchSlop = 0f
        var longPressTimeout = 0L
    }

    private fun show(viewport: DpSize = DpSize(360.dp, 760.dp), fontScale: Float = 1f,
        topInset: Dp = 0.dp, bottomInset: Dp = 0.dp): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val fixture = Fixture()
        val loader = ImageLoader.Builder(context).diskCache(null).build()
        val api = JellyfinApi()
        val session = Session(ServerConfig("https://index-overlay.invalid"), "synthetic-overlay-token",
            "synthetic-overlay-user", "fixture", "synthetic-overlay-device")
        compose.setContent {
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            val platformHaptics = LocalHapticFeedback.current
            val configuration = LocalViewConfiguration.current
            SideEffect { fixture.touchSlop = configuration.touchSlop; fixture.longPressTimeout = configuration.longPressTimeoutMillis }
            val observer = remember(platformHaptics) { object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    fixture.haptics += hapticFeedbackType
                    platformHaptics.performHapticFeedback(hapticFeedbackType)
                }
            } }
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(viewport) then DeviceConfigurationOverride.FontScale(fontScale)) {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader, LocalHapticFeedback provides observer,
                        LocalPageRefreshEnabled provides fixture.enabled.value, LocalPageTopInset provides topInset,
                        LocalNavigationBottomInset provides bottomInset) {
                        Surface(Modifier.fillMaxSize()) {
                            BrowserScreen(fixture.state.value, session, api,
                                onQueryChange = { fixture.queries += it; fixture.state.value = fixture.state.value.copy(query = it) },
                                onStyleChange = {}, onOpen = {}, onRetry = {}, onMore = {}, libraryType = "movies",
                                showIndex = fixture.showIndex.value)
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "index-overlay", activation())
        compose.runOnIdle { assertTrue(fixture.touchSlop > 0); assertTrue(fixture.longPressTimeout > 100) }
        return fixture
    }

    private fun activation() = compose.onNodeWithTag("library-index-activation")
    private fun rail() = compose.onNodeWithTag("library-index")
    private fun cardTargets() = (1..3).map { compose.onNodeWithTag("poster-title/overlay-$it", true) }.toTypedArray()
    private fun cardBounds(): List<Rect> = bounds(*cardTargets())
    private fun bounds(vararg targets: SemanticsNodeInteraction): List<Rect> {
        val nodes = targets.map { it.fetchSemanticsNode() }
        return compose.runOnIdle { nodes.map { it.boundsInRoot } }
    }
    private fun toggleIndex(label: String) {
        val action = activation().fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
    }
    private fun assertUnchanged(fixture: Fixture, haptics: List<HapticFeedbackType>) = compose.runOnIdle {
        assertEquals(emptyList<LibraryQuery>(), fixture.queries)
        assertEquals(fixture.initial, fixture.state.value.query)
        assertEquals(haptics, fixture.haptics)
    }
}

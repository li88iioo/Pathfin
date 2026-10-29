@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android

import android.os.IBinder
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.AppAlertDialog
import dev.pathfin.android.ui.AppDropdownMenu
import dev.pathfin.android.ui.AppModalBottomSheet
import dev.pathfin.android.ui.AppPopup
import dev.pathfin.android.ui.AppSafeDrawingInsets
import dev.pathfin.android.ui.AppSystemBars
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPlaybackFullscreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 实际窗口每次 OnDraw 记录安全区/底页几何；不注册 UiAutomation，也不改变设备设置。 */
@RunWith(AndroidJUnit4::class)
class AppWindowPolicyUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun hiddenStatusBarDoesNotFlashOrMoveTheHostAcrossEveryModalKind() = verifyModalWindows(hide = true)

    @Test fun visibleStatusBarAndHostBoundsStayStableAcrossEveryModalKind() = verifyModalWindows(hide = false)

    private fun verifyModalWindows(hide: Boolean) {
        checkVerification()
        val preferences = mutableStateOf(AppPreferences(hideSystemStatusBar = hide))
        val open = mutableStateOf<Kind?>(null)
        val host = Frames()
        val popup = AtomicReference(Frames())
        val sheet = AtomicReference<SheetState?>()
        val sheetGesturesEnabled = mutableStateOf(true)
        val sheetList = AtomicReference<LazyListState?>()
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides preferences.value) {
                    AppSystemBars()
                    ObserveFrames(host)
                    Column(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets)) {
                        Text("固定标题", Modifier.fillMaxWidth().height(60.dp).testTag("policy-header")
                            .onGloballyPositioned { host.header.set(it) })
                        Column(Modifier.fillMaxWidth().weight(1f).testTag("policy-body")
                            .onGloballyPositioned { host.body.set(it) }) {
                            Kind.entries.forEach { kind ->
                                TextButton({ popup.set(Frames()); open.value = kind }, Modifier.testTag("policy-open-${kind.name}")) {
                                    Text(kind.name)
                                }
                            }
                        }
                    }
                    val dismiss = { open.value = null }
                    when (open.value) {
                        Kind.SHEET -> {
                            val state = rememberModalBottomSheetState()
                            val listState = rememberLazyListState()
                            val scope = rememberCoroutineScope()
                            SideEffect { sheet.set(state); sheetList.set(listState) }
                            AppModalBottomSheet(onDismissRequest = dismiss, sheetState = state,
                                sheetGesturesEnabled = sheetGesturesEnabled.value,
                                dragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag("policy-sheet-handle")) }) {
                                ObserveFrames(popup.get())
                                Column(Modifier.fillMaxWidth().height(640.dp).testTag("policy-sheet-body")) {
                                    Text("实际抽屉", Modifier.padding(16.dp))
                                    Row {
                                        TextButton({ scope.launch { state.expand() } }, Modifier.testTag("policy-expand")) { Text("展开") }
                                        TextButton({ scope.launch { state.partialExpand() } }, Modifier.testTag("policy-collapse")) { Text("收起") }
                                        TextButton({ scope.launch { state.hide(); dismiss() } }, Modifier.testTag("policy-close")) { Text("关闭") }
                                    }
                                    Box(Modifier.fillMaxWidth().height(120.dp).testTag("policy-sheet-content"))
                                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("policy-sheet-list"), state = listState) {
                                        items(40) { index -> Text("列表条目 $index", Modifier.fillMaxWidth().height(48.dp)) }
                                    }
                                }
                            }
                        }
                        Kind.ALERT -> AppAlertDialog(onDismissRequest = dismiss, title = { Text("实际确认窗口") },
                            text = { ObserveFrames(popup.get()); Text("窗口策略必须在首次绘制前生效。") },
                            confirmButton = { TextButton(dismiss, Modifier.testTag("policy-close")) { Text("关闭") } })
                        Kind.DROPDOWN -> AppDropdownMenu(true, dismiss) {
                            ObserveFrames(popup.get())
                            DropdownMenuItem({ Text("关闭菜单") }, dismiss, Modifier.testTag("policy-close"))
                        }
                        Kind.POPUP -> AppPopup(CenterPopup, dismiss, PopupProperties(focusable = true)) {
                            ObserveFrames(popup.get())
                            Surface { TextButton(dismiss, Modifier.testTag("policy-close")) { Text("关闭播放器菜单") } }
                        }
                        null -> Unit
                    }
                }
            }
        }
        try {
            VerificationDisplay.awaitWindowReady(compose, "policy-host-$hide", compose.onNodeWithTag("policy-header"))
            awaitBars(host, status = !hide, navigation = true)
            val baseline = latestGeometry(host)
            for (kind in Kind.entries) {
                val firstHostFrame = host.frames.size
                click("policy-open-${kind.name}")
                VerificationDisplay.awaitWindowReady(compose, "policy-${kind.name}-$hide", compose.onNodeWithTag("policy-close"))
                assertOwnWindowAndEveryDraw(popup.get(), host, status = !hide, navigation = true)
                if (kind == Kind.SHEET) {
                    click("policy-expand")
                    compose.waitUntil(5_000) { sheet.get()?.currentValue == SheetValue.Expanded }
                    val state = checkNotNull(sheet.get())
                    for (tag in listOf("policy-sheet-content", "policy-sheet-handle")) {
                        assertSheetSwipeStaysAtOffset(tag, up = true, state)
                    }
                    compose.runOnIdle { sheetGesturesEnabled.value = false }
                    for (tag in listOf("policy-sheet-content", "policy-sheet-handle")) {
                        assertSheetSwipeStaysAtOffset(tag, up = false, state)
                    }
                    val expandedOffset = compose.runOnIdle { state.requireOffset() }
                    compose.onNodeWithTag("policy-sheet-list").performTouchInput {
                        compose.runOnUiThread { visibleSize }
                        swipeUp(durationMillis = 180)
                    }
                    compose.runOnIdle {
                        val list = checkNotNull(sheetList.get())
                        assertTrue("禁用面板拖动时内部列表仍可滚动", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
                        assertEquals("内部列表滚动不移动面板", expandedOffset, state.requireOffset(), 1f)
                        sheetGesturesEnabled.value = true
                    }
                    compose.onNodeWithTag("policy-sheet-list").performScrollToIndex(39)
                    assertSheetSwipeStaysAtOffset("policy-sheet-list", up = true, state)
                    assertStableHost(host, baseline, firstHostFrame, "$kind-expanded")
                    click("policy-collapse")
                    compose.waitUntil(5_000) { sheet.get()?.currentValue == SheetValue.PartiallyExpanded }
                    assertStableHost(host, baseline, firstHostFrame, "$kind-collapsed")
                    click("policy-expand")
                    compose.waitUntil(5_000) { state.currentValue == SheetValue.Expanded }
                    val contentHeight = compose.onNodeWithTag("policy-sheet-body").fetchSemanticsNode().boundsInRoot.height
                    compose.onNodeWithTag("policy-sheet-handle").performTouchInput {
                        compose.runOnUiThread { visibleSize }
                        swipeDown(startY = height * 0.8f, endY = height + contentHeight * 0.9f, durationMillis = 250)
                    }
                } else {
                    click("policy-close")
                }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("policy-close").fetchSemanticsNodes().isEmpty() }
                VerificationDisplay.awaitWindowReady(compose, "policy-host-restored-${kind.name}-$hide", compose.onNodeWithTag("policy-header"))
                awaitBars(host, status = !hide, navigation = true)
                assertOwnWindowAndEveryDraw(popup.get(), host, status = !hide, navigation = true)
                assertStableHost(host, baseline, firstHostFrame, "$kind-dismissed")
            }
        } finally {
            compose.runOnIdle { open.value = null; preferences.value = preferences.value.copy(hideSystemStatusBar = false) }
            restoreBars()
        }
    }

    @Test fun fullscreenPopupUsesItsOwnRootAndClosingRestoresVisibleBarsAndHostGeometry() {
        checkVerification()
        val fullscreen = mutableStateOf(false)
        val host = Frames()
        val popup = Frames()
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalAppPreferences provides AppPreferences(hideSystemStatusBar = false),
                    LocalPlaybackFullscreen provides fullscreen.value) {
                    AppSystemBars()
                    ObserveFrames(host)
                    Column(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets)) {
                        Text("全屏前后固定标题", Modifier.fillMaxWidth().height(60.dp).testTag("policy-header")
                            .onGloballyPositioned { host.header.set(it) })
                        Box(Modifier.fillMaxSize().onGloballyPositioned { host.body.set(it) }) {
                            TextButton({ fullscreen.value = true }, Modifier.testTag("policy-fullscreen")) { Text("进入播放器全屏") }
                        }
                    }
                    if (fullscreen.value) AppPopup(CenterPopup, { fullscreen.value = false }, PopupProperties(focusable = true)) {
                        ObserveFrames(popup)
                        Surface { TextButton({ fullscreen.value = false }, Modifier.testTag("policy-close")) { Text("退出全屏") } }
                    }
                }
            }
        }
        try {
            VerificationDisplay.awaitWindowReady(compose, "policy-before-fullscreen", compose.onNodeWithTag("policy-header"))
            awaitBars(host, status = true, navigation = true)
            val baseline = latestGeometry(host)
            click("policy-fullscreen")
            VerificationDisplay.awaitWindowReady(compose, "policy-fullscreen-popup", compose.onNodeWithTag("policy-close"))
            assertOwnWindowAndEveryDraw(popup, host, status = false, navigation = false)
            click("policy-close")
            VerificationDisplay.awaitWindowReady(compose, "policy-after-fullscreen", compose.onNodeWithTag("policy-header"))
            awaitBars(host, status = true, navigation = true)
            assertEquals("退出播放器必须恢复原页面安全区", baseline, latestGeometry(host))
        } finally {
            compose.runOnIdle { fullscreen.value = false }
            restoreBars()
        }
    }

    private fun click(tag: String) {
        val target = compose.onNodeWithTag(tag)
        VerificationDisplay.awaitWindowReady(compose, "window-policy-touch-$tag", target)
        target.performTouchInput {
            // 每帧观察者也读取布局；触摸范围必须由同一个 UI 线程取得，避免坐标缓存竞争。
            compose.runOnUiThread { visibleSize }
            click()
        }
    }

    private fun assertSheetSwipeStaysAtOffset(tag: String, up: Boolean, sheet: SheetState) {
        val target = compose.onNodeWithTag(tag)
        VerificationDisplay.awaitWindowReady(compose, "policy-sheet-before-$tag-$up", target)
        val offset = compose.runOnIdle { sheet.requireOffset() }
        compose.mainClock.autoAdvance = false
        try {
            target.performTouchInput {
                compose.runOnUiThread { visibleSize }
                if (up) swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 120)
                else swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 120)
            }
            // 逐帧检查松手后的动画，不能只看弹簧归位后的最终位置。
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle { assertEquals("$tag 上推到边界或禁用手势后不能移动面板", offset, sheet.requireOffset(), 1f) }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        VerificationDisplay.awaitWindowReady(compose, "policy-sheet-after-$tag-$up", target)
        compose.runOnIdle { assertEquals(offset, sheet.requireOffset(), 1f) }
    }

    private fun assertOwnWindowAndEveryDraw(frames: Frames, host: Frames, status: Boolean, navigation: Boolean) {
        val actual = frames.frames.toList()
        assertTrue("弹层必须产生真实 OnDraw", actual.isNotEmpty())
        assertNotNull("弹层首帧必须拥有已 attach 的真实窗口 token", actual.first().token)
        assertNotEquals("Popup/Dialog 必须检查自己的窗口，不能误读 Activity", host.frames.last().token, actual.first().token)
        actual.forEachIndexed { index, frame ->
            assertEquals("弹层第 $index 帧状态栏", status, frame.status)
            assertEquals("弹层第 $index 帧导航栏", navigation, frame.navigation)
        }
    }

    private fun assertStableHost(host: Frames, baseline: Geometry, from: Int, phase: String) {
        // awaitWindowReady 主动要求一个真实 host draw，避免仅检查最终 Compose state。
        val frames = host.frames.drop(from)
        assertTrue("$phase 必须记录底页实际绘制", frames.isNotEmpty())
        frames.forEachIndexed { index, frame ->
            assertEquals("$phase 底页第 $index 帧标题/正文不能抖动", baseline, frame.geometry)
        }
    }

    private fun latestGeometry(frames: Frames): Geometry = checkNotNull(frames.frames.last().geometry)

    private fun awaitBars(frames: Frames, status: Boolean, navigation: Boolean) {
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                val insets = frames.view.get()?.let(ViewCompat::getRootWindowInsets)
                insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars()) == status &&
                    insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == navigation
            }
        }
    }

    @Composable private fun ObserveFrames(frames: Frames) {
        val view = LocalView.current
        DisposableEffect(view, frames) {
            frames.view.set(view)
            val listener = ViewTreeObserver.OnDrawListener {
                val insets = ViewCompat.getRootWindowInsets(view)
                fun coordinates(ref: AtomicReference<LayoutCoordinates?>): Rect? = ref.get()?.takeIf { it.isAttached }?.boundsInWindow()
                val header = coordinates(frames.header)
                val body = coordinates(frames.body)
                frames.frames.add(Frame(view.windowToken, insets?.isVisible(WindowInsetsCompat.Type.statusBars()),
                    insets?.isVisible(WindowInsetsCompat.Type.navigationBars()),
                    if (header != null && body != null) Geometry(header, body) else null))
            }
            view.viewTreeObserver.addOnDrawListener(listener)
            onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
        }
    }

    private fun restoreBars() = compose.runOnUiThread {
        WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun checkVerification() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
    }

    private enum class Kind { SHEET, ALERT, DROPDOWN, POPUP }
    private data class Geometry(val header: Rect, val body: Rect)
    private data class Frame(val token: IBinder?, val status: Boolean?, val navigation: Boolean?, val geometry: Geometry?)
    private class Frames {
        val view = AtomicReference<View?>()
        val header = AtomicReference<LayoutCoordinates?>()
        val body = AtomicReference<LayoutCoordinates?>()
        val frames = CopyOnWriteArrayList<Frame>()
    }

    private object CenterPopup : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
            popupContentSize: IntSize) = IntOffset((windowSize.width - popupContentSize.width) / 2,
                (windowSize.height - popupContentSize.height) / 2)
    }
}

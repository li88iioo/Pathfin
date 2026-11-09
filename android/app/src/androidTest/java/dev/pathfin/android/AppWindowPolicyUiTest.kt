@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android

import android.os.IBinder
import android.view.KeyEvent
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
import androidx.test.espresso.Espresso
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

    @Test fun nestedSheetsIsolateKeyboardFocusAndReleaseItWhenClosed() {
        checkVerification()
        val background = mutableStateOf("")
        val outer = mutableStateOf("")
        val inner = mutableStateOf("")
        val outerOpen = mutableStateOf(false)
        val innerOpen = mutableStateOf(false)
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    TextField(background.value, { background.value = it }, Modifier.testTag("focus-background"))
                    TextButton({ outerOpen.value = true }, Modifier.testTag("focus-open-outer")) { Text("打开抽屉") }
                }
                if (outerOpen.value) AppModalBottomSheet({ outerOpen.value = false }, dragHandle = null,
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
                    Column(Modifier.fillMaxWidth().height(360.dp)) {
                        TextField(outer.value, { outer.value = it }, Modifier.testTag("focus-outer-input"))
                        TextButton({ innerOpen.value = true }, Modifier.testTag("focus-open-inner")) { Text("打开子抽屉") }
                        TextButton({ outerOpen.value = false }, Modifier.testTag("focus-close-outer")) { Text("关闭抽屉") }
                        if (innerOpen.value) AppModalBottomSheet({ innerOpen.value = false }, dragHandle = null,
                            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
                            Column(Modifier.fillMaxWidth().height(200.dp)) {
                                TextField(inner.value, { inner.value = it }, Modifier.testTag("focus-inner-input"))
                                TextButton({ innerOpen.value = false }, Modifier.testTag("focus-close-inner")) { Text("关闭子抽屉") }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("focus-background").performTextInput("a")
        typeKey(KeyEvent.KEYCODE_Z)
        compose.runOnIdle { assertEquals("键盘分发基线必须真的修改已聚焦输入框", "az", background.value) }
        click("focus-open-outer")
        VerificationDisplay.awaitWindowReady(compose, "focus-outer", compose.onNodeWithTag("focus-outer-input"))
        typeKey(KeyEvent.KEYCODE_Y)
        compose.runOnIdle { assertEquals("打开抽屉后输入不能穿透到底页", "az", background.value) }
        compose.onNodeWithTag("focus-background", true).assertIsNotFocused()
        compose.onNodeWithTag("focus-outer-input").performTextReplacement("b")
        typeKey(KeyEvent.KEYCODE_X)
        compose.runOnIdle { assertEquals("顶层抽屉的输入框仍应可编辑", "bx", outer.value) }
        click("focus-open-inner")
        VerificationDisplay.awaitWindowReady(compose, "focus-inner", compose.onNodeWithTag("focus-inner-input"))
        typeKey(KeyEvent.KEYCODE_Y)
        compose.runOnIdle { assertEquals("子抽屉不能继续修改父抽屉输入", "bx", outer.value) }
        compose.onNodeWithTag("focus-inner-input").performTextReplacement("c")
        typeKey(KeyEvent.KEYCODE_Z)
        compose.runOnIdle { assertEquals("子抽屉的输入框仍应可编辑", "cz", inner.value) }
        repeat(4) { typeKey(KeyEvent.KEYCODE_TAB); typeKey(KeyEvent.KEYCODE_Y) }
        compose.runOnIdle {
            assertEquals("Tab 导航不能越过栈顶抽屉回到底页", "az", background.value)
            assertEquals("Tab 导航不能回到被遮挡的父抽屉", "bx", outer.value)
        }
        click("focus-close-inner")
        compose.onNodeWithTag("focus-inner-input").assertDoesNotExist()
        compose.onNodeWithTag("focus-outer-input").performTextInput("d")
        typeKey(KeyEvent.KEYCODE_Y)
        compose.runOnIdle { assertEquals("关闭子抽屉后父抽屉必须恢复输入", "bxdy", outer.value) }
        click("focus-close-outer")
        compose.onNodeWithTag("focus-outer-input").assertDoesNotExist()
        compose.onNodeWithTag("focus-background").performTextInput("q")
        typeKey(KeyEvent.KEYCODE_Y)
        compose.runOnIdle { assertEquals("关闭所有抽屉后底页必须恢复输入", "azqy", background.value) }
    }

    @Test fun becomingBusyDuringCloseRestoresTheSheetAndAllowsOneLaterDismissal() {
        checkVerification()
        val open = mutableStateOf(true)
        val dismissEnabled = mutableStateOf(true)
        var dismissals = 0
        var backgroundClicks = 0
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    TextButton({ backgroundClicks++ }, Modifier.testTag("busy-background-action")) { Text("底页操作") }
                }
                if (open.value) AppModalBottomSheet({ dismissals++; if (dismissEnabled.value) open.value = false },
                    dismissEnabled = dismissEnabled.value, dragHandle = null,
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
                    Box(Modifier.fillMaxWidth().height(300.dp).testTag("busy-sheet"))
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "busy-sheet-open", compose.onNodeWithTag("busy-sheet"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnUiThread { dismissEnabled.value = false }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("写入开始必须中断关闭", open.value)
                assertEquals("忙态不能提前消费关闭回调", 0, dismissals)
            }
            compose.onNodeWithTag("busy-sheet").assertVerificationDisplayed(compose)
            compose.runOnUiThread { dismissEnabled.value = true }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("busy-sheet").assertVerificationDisplayed(compose)
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("busy-sheet").assertDoesNotExist()
            compose.runOnIdle { assertEquals("真正关闭时只能回调一次", 1, dismissals) }
        } finally { compose.mainClock.autoAdvance = true }
        click("busy-background-action")
        compose.runOnIdle { assertEquals("关闭后不能留下拦截底页点击的透明遮罩", 1, backgroundClicks) }
    }

    private fun verifyModalWindows(hide: Boolean) {
        checkVerification()
        val preferences = mutableStateOf(AppPreferences(hideSystemStatusBar = hide))
        val open = mutableStateOf<Kind?>(null)
        val host = Frames()
        val popup = AtomicReference(Frames())
        val sheetDismissEnabled = mutableStateOf(true)
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
                            val listState = rememberLazyListState()
                            SideEffect { sheetList.set(listState) }
                            AppModalBottomSheet(onDismissRequest = dismiss, dismissEnabled = sheetDismissEnabled.value,
                                dragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag("policy-sheet-handle")) }) {
                                ObserveFrames(popup.get())
                                Column(Modifier.fillMaxWidth().height(640.dp).testTag("policy-sheet-body")) {
                                    Text("实际抽屉", Modifier.padding(16.dp))
                                    TextButton(dismiss, Modifier.testTag("policy-close")) { Text("关闭") }
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
                // 抽屉画在主窗口里，不再拥有独立窗口；其余弹层仍各自持有窗口策略。
                assertWindowAndEveryDraw(popup.get(), host, ownWindow = kind != Kind.SHEET, status = !hide, navigation = true)
                if (kind == Kind.SHEET) {
                    compose.waitForIdle() // 滑入动画结束后再比较位置
                    for (tag in listOf("policy-sheet-content", "policy-sheet-handle")) {
                        assertSheetSwipeStaysAtOffset(tag, up = true)
                    }
                    compose.runOnIdle { sheetDismissEnabled.value = false }
                    for (tag in listOf("policy-sheet-content", "policy-sheet-handle")) {
                        assertSheetSwipeStaysAtOffset(tag, up = false)
                    }
                    val expandedTop = sheetTop()
                    compose.onNodeWithTag("policy-sheet-list").performVerificationTouchInput(compose) {
                        swipeUp(durationMillis = 180)
                    }
                    compose.runOnIdle {
                        val list = checkNotNull(sheetList.get())
                        assertTrue("禁用关闭时内部列表仍可滚动", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
                        sheetDismissEnabled.value = true
                    }
                    assertEquals("内部列表滚动不移动面板", expandedTop, sheetTop(), 1f)
                    compose.onNodeWithTag("policy-sheet-list").performScrollToIndex(39)
                    assertSheetSwipeStaysAtOffset("policy-sheet-list", up = true)
                    assertStableHost(host, baseline, firstHostFrame, "$kind-expanded")
                    val contentHeight = compose.onNodeWithTag("policy-sheet-body").readVerificationNode(compose) { it.boundsInRoot }.height
                    compose.onNodeWithTag("policy-sheet-handle").performVerificationTouchInput(compose) {
                        swipeDown(startY = height * 0.8f, endY = height + contentHeight * 0.9f, durationMillis = 250)
                    }
                } else {
                    click("policy-close")
                }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("policy-close").fetchSemanticsNodes().isEmpty() }
                VerificationDisplay.awaitWindowReady(compose, "policy-host-restored-${kind.name}-$hide", compose.onNodeWithTag("policy-header"))
                awaitBars(host, status = !hide, navigation = true)
                assertWindowAndEveryDraw(popup.get(), host, ownWindow = kind != Kind.SHEET, status = !hide, navigation = true)
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
            assertWindowAndEveryDraw(popup, host, ownWindow = true, status = false, navigation = false)
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
        target.performVerificationClick(compose)
    }

    /** 抽屉主体在根坐标中的上沿；展开静止时等于根高减去主体高度。 */
    private fun sheetTop(): Float = compose.onNodeWithTag("policy-sheet-body").readVerificationNode(compose) { it.boundsInRoot.top }

    private fun assertSheetSwipeStaysAtOffset(tag: String, up: Boolean) {
        val target = compose.onNodeWithTag(tag)
        VerificationDisplay.awaitWindowReady(compose, "policy-sheet-before-$tag-$up", target)
        val sheet = compose.onNodeWithTag("policy-sheet-body").fetchSemanticsNode()
        val offset = compose.runOnUiThread { sheet.boundsInRoot.top }
        compose.mainClock.autoAdvance = false
        try {
            target.performVerificationTouchInput(compose) {
                if (up) swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 120)
                else swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 120)
            }
            // 逐帧检查松手后的动画，不能只看弹簧归位后的最终位置。
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread {
                    assertEquals("$tag 上推到边界或禁用关闭后不能移动面板", offset, sheet.boundsInRoot.top, 1f)
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        VerificationDisplay.awaitWindowReady(compose, "policy-sheet-after-$tag-$up", target)
        assertEquals(offset, sheetTop(), 1f)
    }

    private fun typeKey(code: Int) = compose.runOnUiThread {
        compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun assertWindowAndEveryDraw(frames: Frames, host: Frames, ownWindow: Boolean, status: Boolean, navigation: Boolean) {
        val actual = frames.frames.toList()
        assertTrue("弹层必须产生真实 OnDraw", actual.isNotEmpty())
        assertNotNull("弹层首帧必须拥有已 attach 的真实窗口 token", actual.first().token)
        if (ownWindow) assertNotEquals("Popup/Dialog 必须检查自己的窗口，不能误读 Activity", host.frames.last().token, actual.first().token)
        else assertEquals("抽屉必须画在宿主窗口内，不能再创建独立窗口", host.frames.last().token, actual.first().token)
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

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.pathfin.android.ui

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal val LocalPlaybackFullscreen = staticCompositionLocalOf { false }

/** 首帧 Insets 尚未分发时使用系统尺寸；同一方向内不因浮层获焦短暂归零。 */
@android.annotation.SuppressLint("InternalInsetResource", "DiscouragedApi")
@Composable
private fun stableStatusBarInsets(): WindowInsets {
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    val density = LocalDensity.current
    val resourceTop = remember(view, orientation, density.density) {
        val resources = view.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id == 0) 0 else resources.getDimensionPixelSize(id)
    }
    val retainedTop = remember(view, orientation, density.density) { intArrayOf(resourceTop) }
    val platformTop = ViewCompat.getRootWindowInsets(view)
        ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    val top = maxOf(retainedTop[0], WindowInsets.statusBarsIgnoringVisibility.getTop(density), platformTop, resourceTop)
    retainedTop[0] = top
    return WindowInsets(top = top)
}

/** 导航只避开稳定的系统导航区与屏幕缺口；键盘由页面内容处理。 */
internal val AppNavigationBarInsets: WindowInsets
    @Composable get() {
        val navigation = if (LocalPlaybackFullscreen.current) WindowInsets(0, 0, 0, 0)
            else WindowInsets.navigationBarsIgnoringVisibility
        return navigation.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    }

/** 安全区由用户选择决定，不跟随 Dialog 获焦时短暂变化的系统栏可见性。 */
internal val AppSafeDrawingInsets: WindowInsets
    @Composable get() {
        val fullscreen = LocalPlaybackFullscreen.current
        val status = if (fullscreen || LocalAppPreferences.current.hideSystemStatusBar)
            WindowInsets(0, 0, 0, 0) else stableStatusBarInsets()
        return status.union(AppNavigationBarInsets).union(WindowInsets.displayCutout).union(WindowInsets.ime)
    }

/** 每个真实窗口只拥有自己的策略；Popup 不得把请求误发给宿主 Activity。 */
@Composable
internal fun AppSystemBars(
    hideStatusBar: Boolean = LocalAppPreferences.current.hideSystemStatusBar,
    fullscreen: Boolean = LocalPlaybackFullscreen.current,
) {
    val view = LocalView.current
    DisposableEffect(view, hideStatusBar, fullscreen) {
        // Material3 在 Dialog.show() 前创建 content composition，此时即可配置其真实 Window。
        val dialogWindow = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        @Suppress("DEPRECATION")
        fun applyPolicy() {
            val bars = if (dialogWindow != null) WindowCompat.getInsetsController(dialogWindow, view) else {
                if (!view.isAttachedToWindow) return
                if (Build.VERSION.SDK_INT < 30) {
                    // Popup 没有 Window 对象。旧平台只操作它自己的 ViewRoot，禁止沿 Context 找 Activity。
                    val root = view.rootView
                    val mask = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    val requested = when {
                        fullscreen -> View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        hideStatusBar -> View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        else -> 0
                    }
                    val flags = (root.systemUiVisibility and mask.inv()) or requested
                    if (root.systemUiVisibility != flags) root.systemUiVisibility = flags
                    return
                }
                val controller = view.windowInsetsController ?: return
                WindowInsetsControllerCompat.toWindowInsetsControllerCompat(controller)
            }
            bars.systemBarsBehavior = if (hideStatusBar && !fullscreen)
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else {
                bars.show(WindowInsetsCompat.Type.navigationBars())
                if (hideStatusBar) bars.hide(WindowInsetsCompat.Type.statusBars())
                else bars.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) applyPolicy() }
        val firstDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // 已 attach 的 Popup 在第一次显示之前获得自己的 controller；不等待首次获焦。
                applyPolicy()
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        }
        fun observeRoot() {
            val observer = view.viewTreeObserver
            observer.removeOnWindowFocusChangeListener(focus)
            observer.addOnWindowFocusChangeListener(focus)
            observer.removeOnPreDrawListener(firstDraw)
            observer.addOnPreDrawListener(firstDraw)
            applyPolicy()
        }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = observeRoot()
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        view.addOnAttachStateChangeListener(attach)
        observeRoot()
        onDispose {
            view.removeOnAttachStateChangeListener(attach)
            // attach 会把 floating observer 合并到真实 ViewRoot，必须从当前 observer 解绑。
            val observer = view.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnWindowFocusChangeListener(focus)
                observer.removeOnPreDrawListener(firstDraw)
            }
        }
    }
}

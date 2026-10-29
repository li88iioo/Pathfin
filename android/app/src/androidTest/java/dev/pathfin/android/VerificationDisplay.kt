package dev.pathfin.android

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** 仅合成 verification 测试窗口可在系统锁屏上显示，不解除设备锁、不改变日常应用权限。 */
internal object VerificationDisplay {
    private var installed = false
    @Synchronized fun prepare() {
        if (installed) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // 合成验证窗口不向设备密码管理器提交测试账号，不更改系统自动填充设置。
                activity.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                if (Build.VERSION.SDK_INT >= 27) { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true) }
                @Suppress("DEPRECATION")
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        installed = true
    }

    fun awaitWindowReady(compose: AndroidComposeTestRule<*, *>, stage: String, vararg targets: SemanticsNodeInteraction) {
        require(targets.isNotEmpty())
        val owner = checkNotNull(targets.first().fetchSemanticsNode().root as? ViewRootForTest) {
            "目标节点没有 Android Compose 宿主"
        }
        val drawn = AtomicBoolean(false)
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.runOnUiThread {
            owner.view.viewTreeObserver.addOnDrawListener(listener)
            owner.view.postInvalidateOnAnimation()
        }
        try {
            compose.waitUntil(10_000) {
                val nodes = targets.map { it.fetchSemanticsNode() }
                compose.runOnIdle {
                    val activity = compose.activity
                    val view = owner.view
                    val visible = Rect()
                    // Dialog 使用自己的窗口焦点；宿主 Activity 此时可以没有焦点。
                    drawn.get() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        !activity.isFinishing && !activity.isDestroyed && owner.isLifecycleInResumedState &&
                        view.isAttachedToWindow && view.isLaidOut && view.isShown && view.hasWindowFocus() &&
                        view.windowVisibility == View.VISIBLE && view.width > 0 && view.height > 0 &&
                        !view.isLayoutRequested && !owner.hasPendingMeasureOrLayout &&
                        view.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0 &&
                        nodes.all { it.root === owner && it.layoutInfo.isPlaced &&
                            it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f &&
                            it.boundsInWindow.width > 0f && it.boundsInWindow.height > 0f }
                }
            }
            Log.i("PathfinVerificationWindow", "window-ready $stage ${windowDiagnostic(compose, owner)}")
        } catch (failure: Throwable) {
            targets.forEach { logWindowFailure(compose, "$stage firstDraw=${drawn.get()}", it, owner, failure) }
            throw failure
        } finally {
            compose.runOnUiThread {
                if (owner.view.viewTreeObserver.isAlive) owner.view.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
    }

}

/** 在 UI 线程计算坐标并构建手势；返回测试线程后由原测试框架统一交付事件。 */
internal fun SemanticsNodeInteraction.performVerificationTouchInput(
    compose: ComposeTestRule,
    block: TouchInjectionScope.() -> Unit,
): SemanticsNodeInteraction = performTouchInput {
    val scope = this
    compose.runOnUiThread { scope.block() }
}

internal fun SemanticsNodeInteraction.performVerificationClick(compose: ComposeTestRule): SemanticsNodeInteraction =
    performVerificationTouchInput(compose) { click() }

/** 节点查询留在测试线程；可变布局缓存只能由 UI 线程读取。 */
internal fun <T> SemanticsNodeInteraction.readVerificationNode(compose: ComposeTestRule, read: (SemanticsNode) -> T): T {
    val node = fetchSemanticsNode()
    return compose.runOnUiThread { read(node) }
}

internal fun isVerificationNodeDisplayed(compose: ComposeTestRule, node: SemanticsNode): Boolean = compose.runOnUiThread {
    val view = (node.root as? ViewRootForTest)?.view ?: return@runOnUiThread false
    val visible = Rect()
    if (!view.isShown || !view.getLocalVisibleRect(visible) ||
        !generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isPlaced }) return@runOnUiThread false
    val origin = IntArray(2).also(view::getLocationInWindow)
    val viewport = androidx.compose.ui.geometry.Rect(
        visible.left + origin[0].toFloat(), visible.top + origin[1].toFloat(),
        visible.right + origin[0].toFloat(), visible.bottom + origin[1].toFloat(),
    )
    val area = node.boundsInWindow.intersect(viewport)
    area.width > 0f && area.height > 0f
}

internal fun SemanticsNodeInteraction.assertVerificationDisplayed(
    compose: AndroidComposeTestRule<*, *>,
    stage: String = "displayed",
): SemanticsNodeInteraction {
    try {
        if (!isVerificationNodeDisplayed(compose, fetchSemanticsNode())) {
            throw AssertionError("$stage 节点必须实际显示在窗口内")
        }
        return this
    } catch (failure: AssertionError) {
        val owner = runCatching { fetchSemanticsNode().root as? ViewRootForTest }.getOrNull()
        logWindowFailure(compose, stage, this, owner, failure)
        throw failure
    }
}

private fun windowDiagnostic(compose: AndroidComposeTestRule<*, *>, owner: ViewRootForTest?): String = compose.runOnUiThread {
    val activity = compose.activity
    val view = owner?.view ?: activity.window.decorView
    val visible = Rect()
    val hasVisibleRect = view.getGlobalVisibleRect(visible)
    val screen = IntArray(2).also(view::getLocationOnScreen)
    val window = IntArray(2).also(view::getLocationInWindow)
    "lifecycle=${activity.lifecycle.currentState} activityFocus=${activity.hasWindowFocus()} " +
        "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} " +
        "view=${view.javaClass.simpleName} attached=${view.isAttachedToWindow} laidOut=${view.isLaidOut} " +
        "shown=${view.isShown} viewFocus=${view.hasWindowFocus()} windowVisibility=${view.windowVisibility} " +
        "size=${view.width}x${view.height} layoutRequested=${view.isLayoutRequested} " +
        "ownerResumed=${owner?.isLifecycleInResumedState} pendingMeasure=${owner?.hasPendingMeasureOrLayout} " +
        "screen=${screen.contentToString()} window=${window.contentToString()} globalVisible=$hasVisibleRect:$visible"
}

private fun logWindowFailure(compose: AndroidComposeTestRule<*, *>, stage: String, target: SemanticsNodeInteraction,
    owner: ViewRootForTest?, failure: Throwable) {
    val node = runCatching { target.fetchSemanticsNode() }.getOrNull()
    val bounds = node?.let {
        compose.runOnUiThread { "placed=${it.layoutInfo.isPlaced} root=${it.boundsInRoot} window=${it.boundsInWindow}" }
    } ?: "node unavailable"
    Log.e("PathfinVerificationWindow", "$stage $bounds ${windowDiagnostic(compose, owner)}", failure)
}

internal inline fun <reified A : ComponentActivity> createVerificationComposeRule() =
    run { VerificationDisplay.prepare(); createAndroidComposeRule<A>() }

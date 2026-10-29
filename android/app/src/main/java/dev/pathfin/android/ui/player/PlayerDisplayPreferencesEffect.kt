package dev.pathfin.android.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * 只控制当前播放 Activity 的窗口，不写系统显示设置。
 * Media3 1.11.1 ExoPlayer 的公开帧率策略 API 仅在其 applicationLooper 上调用；
 * Pathfin 将公开 Player 控制绑定 Main。
 */
@UnstableApi
@Composable
internal fun PlayerDisplayPreferencesEffect(
    player: Player?,
    active: Boolean,
    lockHighRefreshRate: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.displayActivity() }
    val enabled = active && lockHighRefreshRate

    // 换集/重试替换 Player 时保持同一个窗口请求，避免每次重建播放器时撤销再申请。
    DisposableEffect(activity, enabled) {
        val lease = if (enabled && activity != null) WindowDisplayModeLease(activity) else null
        onDispose { lease?.close() }
    }
    DisposableEffect(player, enabled) {
        val exo = (player as? ExoPlayer)?.takeIf { enabled && !it.isReleased }
        val original = exo?.videoChangeFrameRateStrategy
        // 官方 OFF 明确禁止 Exo 对 Surface 发帧率请求，与窗口最高档位保持互斥。
        if (exo != null) exo.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        onDispose {
            if (exo != null && original != null && !exo.isReleased) exo.setVideoChangeFrameRateStrategy(original)
        }
    }
}

private class WindowDisplayModeLease(private val activity: Activity) {
    private val window = activity.window
    private val originalModeId = window.attributes.preferredDisplayModeId
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var closed = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (!closed && display()?.displayId == displayId) applyMode()
        }
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = applyMode()
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    init {
        applyMode()
        displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        window.decorView.addOnAttachStateChangeListener(attachListener)
    }

    private fun applyMode() {
        if (closed) return
        val display = display() ?: return
        val target = highestRefreshRateMode(display.mode.spec(), display.supportedModes.map { it.spec() }) ?: return
        if (window.attributes.preferredDisplayModeId != target.id) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = target.id }
        }
    }

    @Suppress("DEPRECATION") // decorView 尚未附着时，API 26+ 的当前 Activity 显示器回退。
    private fun display(): Display? = window.decorView.display ?: activity.windowManager.defaultDisplay

    fun close() {
        if (closed) return
        closed = true
        displays?.unregisterDisplayListener(listener)
        window.decorView.removeOnAttachStateChangeListener(attachListener)
        // 只还原自己拥有的字段，不覆盖方向、系统栏或其他窗口属性的新值。
        if (window.attributes.preferredDisplayModeId != originalModeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
        }
    }
}

private fun Display.Mode.spec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

private tailrec fun Context.displayActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.displayActivity()
    else -> null
}

package dev.pathfin.android.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/** 仅用于观测真实系统手势，不参与页面的布局或绘制。 */
internal val SystemBackGestureProgress = SemanticsPropertyKey<Float>("PathfinSystemBackGestureProgress")

@Stable
private class BackGestureState {
    var progress by mutableFloatStateOf(0f)
    var generation = 0L

    fun reset() {
        generation++
        progress = 0f
    }
}

/** 取消保留当前页面，正常完成才返回；手势期间页面不做缩放或位移。 */
@Composable
internal fun AppBackHandler(
    enabled: Boolean,
    contentKey: Any?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "app-back-handler",
    content: @Composable () -> Unit,
) {
    val gesture = remember { BackGestureState() }
    val latestKey by rememberUpdatedState(contentKey)
    val latestBack by rememberUpdatedState(onBack)
    val latestEnabled by rememberUpdatedState(enabled)
    DisposableEffect(contentKey, enabled) { onDispose { gesture.reset() } }

    // 固定注册顺序；页面内部的编辑草稿和对话框返回处理仍优先。
    PredictiveBackHandler(enabled = enabled) { events ->
        gesture.reset()
        val generation = gesture.generation
        val key = latestKey
        try {
            events.collect { event ->
                if (generation == gesture.generation) gesture.progress = event.progress.coerceIn(0f, 1f)
            }
            // 账号、路由或播放器切换后，迟到的旧手势不能关闭新内容。
            if (generation == gesture.generation && latestEnabled && latestKey == key) latestBack()
        } finally {
            if (generation == gesture.generation) gesture.reset()
        }
    }
    Box(modifier.testTag(testTag).semantics { this[SystemBackGestureProgress] = gesture.progress }) {
        content()
    }
}

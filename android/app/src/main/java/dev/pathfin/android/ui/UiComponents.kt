package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.i18n.tr

/** 应用级浮出通知，由 PathfinApp 提供；页面内的刷新失败经它提示。 */
internal val LocalNoticeHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * 刷新失败而页面已有内容时，不把错误卡片插进内容流：等这次刷新结束后浮出一条通知，有重试就带重试。
 * 页面没有任何内容时由调用方原地显示 ErrorPanel，避免整页空白。
 */
@Composable
internal fun RefreshFailureNotice(error: String?, settled: Boolean, retry: (() -> Unit)? = null) {
    val host = LocalNoticeHost.current ?: return
    val active = LocalPageActive.current
    val currentRetry by rememberUpdatedState(retry)
    // 根页切走后仍保留组合；失活时取消通知，避免旧页错误与重试入口串到新页。
    LaunchedEffect(host, error, settled, active) {
        if (!active || error == null || !settled) return@LaunchedEffect
        val action = currentRetry
        val result = host.showSnackbar(error, actionLabel = if (action != null) tr("重试") else null,
            withDismissAction = action == null, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) action?.invoke()
    }
}

/** 整页没有内容时的读取失败：与空状态同一语言，细线警示图标、原因与胶囊重试。 */
@Composable
internal fun ErrorPanel(message: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    StatePanel(tr("加载失败"), message, modifier, action = retry?.let { { RetryPill(it) } }) {
        Icon(PathfinIcons.Alert, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
    }
}

/** 表单、分区与追加加载里的错误：一条浅色提示条，不打断周围内容。 */
@Composable
internal fun InlineError(message: String, modifier: Modifier = Modifier) {
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(error.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PathfinIcons.Alert, null, Modifier.size(16.dp), tint = error.copy(alpha = 0.85f))
        Text(message, Modifier.padding(start = 10.dp), fontSize = 13.sp, lineHeight = 19.sp, color = PathfinAppearance.textMain)
    }
}

@Composable
internal fun EmptyPanel(title: String, subtitle: String, icon: ImageVector = MediaNavigationIcons.Movies,
    loading: Boolean = false) {
    StatePanel(title, subtitle) {
        if (loading) PathfinSpinner(PathfinAppearance.accent, Modifier.size(22.dp))
        else Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = PathfinAppearance.textSub)
    }
}

@Composable
private fun StatePanel(title: String, subtitle: String, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null, icon: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 与分组图标同一语言：浅底方圆块里放单色细线图标；读取中换成细线进度弧。
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(PathfinAppearance.softFill),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = PathfinAppearance.textMain,
            textAlign = TextAlign.Center,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = PathfinAppearance.textSub,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
private fun RetryPill(onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(PathfinAppearance.softFill)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PathfinIcons.Refresh, null, Modifier.size(16.dp), tint = PathfinAppearance.textMain)
        Text(tr("重试"), Modifier.padding(start = 6.dp), fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
    }
}

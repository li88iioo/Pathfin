package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.i18n.tr

@Composable
internal fun ErrorPanel(message: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(CardRadius)
    Box(modifier.fillMaxWidth().clip(shape).background(PathfinAppearance.card).border(PathfinAppearance.cardBorder, shape)) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(PathfinAppearance.tintRed),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PathfinIcons.Alert, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
            Text(
                message,
                Modifier.weight(1f).padding(horizontal = 12.dp),
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = PathfinAppearance.textMain,
            )
            if (retry != null) QuietButton(tr("重试"), retry, color = PathfinAppearance.textMain)
        }
    }
}

@Composable
internal fun EmptyPanel(title: String, subtitle: String, icon: ImageVector = MediaNavigationIcons.Movies,
    loading: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 与分组图标同一语言：浅底方圆块里放单色细线图标；读取中换成细线进度弧。
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(PathfinAppearance.softFill),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) PathfinSpinner(PathfinAppearance.accent, Modifier.size(22.dp))
            else Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = PathfinAppearance.textSub)
        }
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
    }
}

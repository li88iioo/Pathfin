@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import dev.pathfin.android.i18n.tr
import kotlin.math.ceil
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.domain.MediaItem

/** 透明度在两端平缓收束，避免背景图在标题前出现可见的水平折线。 */
internal fun detailHeroGradient(surface: Color, scrim: Color, fadeEnd: Float): Brush {
    fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val end = fadeEnd.coerceIn(.3f, .95f)
    val stops = Array(65) { index ->
        val position = index / 64f
        val surfaceAlpha = smooth((position - .12f) / (end - .12f))
        // 顶部压一层较深的暗角，返回/更多的白色图标在浅色海报上也清楚，无需额外圆底。
        val topShade = scrim.copy(alpha = .42f * (1f - smooth(position / .3f)))
        position to surface.copy(alpha = surfaceAlpha).compositeOver(topShade)
    }
    return Brush.verticalGradient(*stops)
}

/** 原图保持原色；其透明轮廓单独经过高斯模糊，避免硬描边改变 Logo 质感。 */
internal class DetailLogoPainter(
    private val original: Painter,
    private val shadowLayer: GraphicsLayer,
    private val shadowColor: State<Color>,
    private val blur: BlurEffect,
) : Painter() {
    override val intrinsicSize get() = original.intrinsicSize

    override fun DrawScope.onDraw() {
        val imageSize = size
        val tint = ColorFilter.tint(shadowColor.value)
        shadowLayer.renderEffect = blur
        shadowLayer.record(this, layoutDirection, IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) {
            with(original) { draw(imageSize, colorFilter = tint) }
        }
        translate(top = .5.dp.toPx()) { drawLayer(shadowLayer) }
        with(original) { draw(imageSize) }
    }
}

@Composable
internal fun DetailActions(
    item: MediaItem, busy: Boolean, hasResume: Boolean, onPlay: () -> Unit, onStart: () -> Unit,
    onCast: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null, onDownload: (() -> Unit)? = null, onWatched: (() -> Unit)? = null,
    favoriteBusy: Boolean = false, downloadBusy: Boolean = false, watchedBusy: Boolean = false,
    playTarget: MediaItem? = null,
) {
    var options by rememberSaveable { mutableStateOf(false) }
    // 墨色椭圆胶囊（毛玻璃开启时为高浓度墨色玻璃），右侧并排圆形描边图标键；深色模式自动反转为浅底深字。
    val container = PathfinAppearance.textMain
    val content = PathfinAppearance.canvas
    val label = when {
        item.positionTicks > 0 || hasResume -> tr("继续播放")
        playTarget?.type == "Episode" && playTarget.indexNumber != null -> tr("第%1\$s集", playTarget.indexNumber)
        else -> tr("播放")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).clip(CircleShape).frostedCard(0.94f, container, 32.dp).testTag("detail-play-pill")) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail-play")
                .clickable(enabled = !busy, role = Role.Button, onClick = onPlay)
                .semantics { if (busy) stateDescription = tr("正在准备播放") else playTarget?.let { stateDescription = it.name } },
                contentAlignment = Alignment.Center) {
                // 准备播放期间留在详情页，图标位换成加载圈，不再切到中间过渡页。
                if (busy) PathfinSpinner(content, Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(20.dp).testTag("detail-play-spinner"))
                else Icon(PathfinIcons.Play, null, Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(20.dp), tint = content)
                Text(label, Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 10.dp).testTag("detail-play-label"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                    fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = content,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                PathfinIconButton(PathfinIcons.ChevronDown, tr("播放选项"), { options = true }, enabled = !busy, tint = content, size = 48.dp)
                AppDropdownMenu(options, { options = false }, shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
                    MenuRow(tr("从头播放"), { options = false; onStart() }, icon = PathfinIcons.Reset)
                    if (item.positionTicks > 0 || hasResume) MenuRow(tr("继续播放"), { options = false; onPlay() }, icon = PathfinIcons.Play)
                    if (onCast != null) MenuRow(tr("投屏"), { options = false; onCast() }, Modifier.testTag("detail-cast"), icon = PathfinIcons.Cast)
                }
            }
        }
        if (onFavorite != null) DetailIconAction(if (item.isFavorite) tr("取消收藏") else tr("收藏媒体"), !favoriteBusy, onFavorite,
            if (item.isFavorite) PathfinIcons.HeartFilled else PathfinIcons.Heart, selected = item.isFavorite)
        if (onDownload != null) DetailIconAction(tr("下载媒体"), !downloadBusy, onDownload, PathfinIcons.Download)
        if (onWatched != null) DetailIconAction(if (item.played) tr("标记未观看") else tr("标记已观看"), !watchedBusy, onWatched,
            if (item.played) PathfinIcons.CheckCircleFilled else PathfinIcons.Unwatched, selected = item.played)
    }
}

/** 播放按钮右侧的圆形图标键：卡片底细描边（毛玻璃开启时半透明），选中为浅强调色底；与播放键同高。 */
@Composable
private fun DetailIconAction(label: String, enabled: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false) {
    val shape = CircleShape
    Box(Modifier.size(48.dp).clip(shape).frostedCard(if (selected) 0.72f else 0.50f,
        if (selected) PathfinAppearance.accentSoft else PathfinAppearance.card, 32.dp)
        .border(if (selected) BorderStroke(1.dp, Color.Transparent) else PathfinAppearance.cardBorder, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
        .alpha(if (enabled) 1f else .45f), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) PathfinAppearance.accent else PathfinAppearance.textMain)
    }
}

@Composable
internal fun DetailSynopsis(item: MediaItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var overflow by remember(item.id, item.overview) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().testTag("detail-synopsis")
        .clickable(enabled = item.overview.isNotBlank(), role = Role.Button) { expanded = true }
        .semantics { contentDescription = tr("查看完整简介") }) {
        Text(item.overview.ifBlank { tr("暂无简介") }, fontSize = 14.sp, lineHeight = 22.sp, minLines = 3, maxLines = 3,
            overflow = TextOverflow.Ellipsis, onTextLayout = { overflow = it.hasVisualOverflow })
        if (overflow) Text(tr("… 更多"), Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface).padding(start = 6.dp),
            color = PathfinAppearance.accent, fontSize = 14.sp, lineHeight = 22.sp)
    }
    if (expanded) AppModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PathfinAppearance.card, dragHandle = null, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.7f).testTag("detail-synopsis-sheet")) {
            SheetDragHandle()
            Text(item.overview, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 15.sp, lineHeight = 25.sp, color = PathfinAppearance.textMain)
        }
    }
}

/** “更多操作”里的一行：与设置分组同一语言，裸细线图标 + 文字，行间分隔线从文字列开始；危险操作整行变红。 */
@Composable
internal fun DetailOperation(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false, enabled: Boolean = true, divider: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else PathfinAppearance.textMain
    Row(modifier.fillMaxWidth().groupSeparator(PathfinAppearance.divider, divider)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupIcon(icon, if (destructive) color else PathfinAppearance.textSub)
        Text(label, fontSize = 15.sp, lineHeight = 21.sp, color = color)
    }
}

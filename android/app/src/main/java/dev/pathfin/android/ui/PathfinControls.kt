package dev.pathfin.android.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.i18n.tr

/** 地址、端口与时长使用等宽字体，便于核对；其余文字保持系统无衬线。 */

/** 墨色胶囊主按钮。muted 只改变外观，表示“输入未完成”；请求中仍保留墨色。danger 用于不可撤销操作。 */
@Composable
internal fun InkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    muted: Boolean = false,
    danger: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(when {
        // 未就绪：极淡的墨色底配弱化文字，一眼看出“还不能点”，又不像一块发灰的残影。
        muted -> PathfinAppearance.textMain.copy(alpha = 0.08f)
        danger -> scheme.error
        else -> PathfinAppearance.textMain
    }, tween(180), label = "ink-button")
    val foreground = when {
        muted -> PathfinAppearance.textMuted
        danger -> scheme.onError
        else -> PathfinAppearance.canvas
    }
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

@Composable
internal fun ButtonLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp, maxLines = 1)
}

/** 次要操作只用文字，不加底色与边框。 */
@Composable
internal fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = PathfinAppearance.textSub,
) {
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.5.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp, color = color, maxLines = 1)
    }
}

/** 圆形图标按钮；container 为透明时只保留按压反馈。 */
@Composable
internal fun PathfinIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = PathfinAppearance.textSub,
    container: Color = Color.Transparent,
    size: Dp = 40.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

/** 细线旋转弧，替代 Material 圆形进度。 */
@Composable
internal fun PathfinSpinner(color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 1.6.dp) {
    val rotation = rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spinner-rotation",
    )
    // 旋转只在图层阶段读取，逐帧重绘而不重组；独立语义节点不被所在按钮合并。
    Canvas(modifier.semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
        .graphicsLayer { rotationZ = rotation.value }) {
        val stroke = strokeWidth.toPx()
        drawCircle(color.copy(alpha = 0.2f), radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
        drawArc(color, -90f, 100f, false, style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f), size = Size(size.width - stroke, size.height - stroke))
    }
}

/** 纯外观开关；语义与点击由所在行的 toggleable 承担。 */
@Composable
internal fun PathfinSwitch(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) PathfinAppearance.accent else PathfinAppearance.segmentTrack, tween(180), label = "switch-track")
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 2.dp, tween(200), label = "switch-thumb")
    Box(modifier.alpha(if (enabled) 1f else 0.45f).size(width = 40.dp, height = 24.dp).clip(CircleShape).background(track)) {
        Box(Modifier.offset { IntOffset(thumbOffset.roundToPx(), 2.dp.roundToPx()) }.size(20.dp)
            .shadow(1.5.dp, CircleShape).background(Color.White, CircleShape))
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(PathfinAppearance.divider))
}

/** 小号标签：半粗、略放字距，用于分组名与输入标签。 */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = PathfinAppearance.textSub) {
    Text(text, modifier, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.SemiBold,
        color = color, maxLines = 1)
}

/** 全站卡片圆角：分组卡、输入框、菜单与提示面板共用，保持利落的骨架感。 */
internal val CardRadius = 16.dp

/** 分组行的文字起点：16 内边距 + 30 图标块 + 14 间距；分隔线从这里开始，避开图标列。 */
internal val GroupTextInset = 60.dp

/** 一组相关的行放在同一张纸面卡片里；在弹窗内以画布色作底以拉开层次。frosted 时为采样背景的毛玻璃。 */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, container: Color = PathfinAppearance.card, frosted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius))
        .then(if (frosted) Modifier.frostedControl(container) else Modifier.background(container)), content = content)
}


/** 行与行之间的细线，起点对齐文字；每组首行不画，避免贴着卡片顶边出现双线。 */
internal fun Modifier.groupSeparator(color: Color, visible: Boolean) = if (!visible) this else drawBehind {
    val start = GroupTextInset.toPx()
    drawRect(color, Offset(start, 0f), Size(size.width - start, 1.dp.toPx()))
}

/** 裸细线图标，不加底块：靠留白与文字分层；只有危险操作或聚焦时换色。30dp 占位保持文字列对齐。 */
@Composable
internal fun GroupIcon(icon: ImageVector, tint: Color = PathfinAppearance.textSub) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

/**
 * 分组卡片里的一行输入：图标块、小标签与输入值。整行位于输入框的装饰区内，点击任意处聚焦，
 * 标签与输入合并为同一无障碍节点；聚焦时图标与标签切换为点缀色。
 */
@Composable
internal fun GroupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val tone = if (focused) PathfinAppearance.accent else PathfinAppearance.textMuted
    val line = PathfinAppearance.divider
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.5.sp, lineHeight = 22.sp, color = PathfinAppearance.textMain,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused },
        readOnly = readOnly,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(PathfinAppearance.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().groupSeparator(line, divider).heightIn(min = 60.dp)
                    .padding(start = 16.dp, end = if (trailing == null) 16.dp else 8.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupIcon(icon, if (focused) PathfinAppearance.accent else PathfinAppearance.textSub)
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(label, fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp, color = tone, maxLines = 1)
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = textStyle, color = PathfinAppearance.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
                trailing?.invoke()
            }
        },
    )
}

/** 分组卡片里的一行开关。 */
@Composable
internal fun GroupToggle(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().groupSeparator(PathfinAppearance.divider, divider)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(icon)
        Text(title, Modifier.weight(1f).padding(start = 14.dp, end = 12.dp), fontSize = 14.5.sp, lineHeight = 21.sp,
            color = if (enabled || checked) PathfinAppearance.textMain else PathfinAppearance.textMuted)
        PathfinSwitch(checked, enabled)
    }
}

internal data class DockItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/** 悬浮胶囊导航：只放图标，选中项是一枚浮起的小片（毛玻璃下为点缀色淡玻璃，否则为画布色）；无障碍名称即页签名。 */
@Composable
internal fun PathfinDock(items: List<DockItem>, modifier: Modifier = Modifier) {
    NavigationBarSurface(
        modifier.shadow(24.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(CircleShape).border(PathfinAppearance.cardBorder, CircleShape).testTag("app-dock-surface"),
    ) {
        // 毛玻璃胶囊上，实色画布片会像贴了一块白纸；改为点缀色的淡玻璃片，透出底下的模糊。
        val frosted = LocalTopBarBlurState.current?.enabled == true
        val accent = PathfinAppearance.accent
        Row(Modifier.padding(horizontal = 5.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                Box(
                    Modifier.size(width = 60.dp, height = 48.dp).clip(CircleShape)
                        .then(when {
                            !item.selected -> Modifier
                            frosted -> Modifier.background(accent.copy(alpha = 0.13f)).border(0.5.dp, accent.copy(alpha = 0.22f), CircleShape)
                            else -> Modifier.background(PathfinAppearance.canvas)
                        })
                        .selectable(item.selected, role = Role.Tab, onClick = item.onClick).semantics { contentDescription = item.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, null, Modifier.size(22.dp), tint = if (item.selected) PathfinAppearance.accent else PathfinAppearance.textMuted)
                }
            }
        }
    }
}

/**
 * 下拉菜单里的一行：细线图标、文字与可选尾部。checked 非空时为开关项，尾部以点缀色对勾表示开启；
 * selected 用于单选列表（如服务器切换），仅提供选中语义。
 */
@Composable
internal fun MenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(12.dp))
            .then(if (checked == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                else Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox) { onClick() })
            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else if (icon != null) Icon(icon, null, Modifier.size(19.dp), tint = PathfinAppearance.textSub)
        Text(label, Modifier.weight(1f).padding(start = if (icon != null || leading != null) 12.dp else 0.dp),
            fontSize = 14.5.sp, lineHeight = 20.sp, color = if (enabled) PathfinAppearance.textMain else PathfinAppearance.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (checked != null) Icon(PathfinIcons.Check, null, Modifier.padding(start = 12.dp).size(17.dp),
            tint = if (checked) PathfinAppearance.accent else Color.Transparent)
        trailing?.invoke()
    }
}

/** 菜单分组之间的细线。 */
@Composable
internal fun MenuDivider() {
    Hairline(Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
}

/** 全站统一的搜索框：白底圆角卡片配常驻细描边，聚焦只让放大镜变色、不加彩色外框；清空位常驻，避免输入首字时文字区跳动。 */
@Composable
internal fun PathfinSearchField(
    query: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(CardRadius)
    BasicTextField(
        query, onChange,
        modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 22.sp, color = PathfinAppearance.textMain),
        cursorBrush = SolidColor(PathfinAppearance.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        interactionSource = interactions,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(shape).background(PathfinAppearance.card)
                    .border(PathfinAppearance.cardBorder, shape)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PathfinIcons.Search, null, Modifier.size(19.dp), tint = if (focused) PathfinAppearance.accent else PathfinAppearance.textMuted)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
                    if (query.isEmpty()) Text(placeholder, fontSize = 15.5.sp, lineHeight = 22.sp,
                        color = PathfinAppearance.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                // 清空位始终保留，首字输入不会缩短文本区或挤动占位文字。
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (query.isNotEmpty()) PathfinIconButton(PathfinIcons.Close, tr("清空搜索"), onClear,
                        Modifier.testTag("search-clear"), tint = PathfinAppearance.textMuted)
                }
            }
        },
    )
}

/** 细线小方框复选：选中填强调色并带白勾，未选只有一圈弱色描边；整行可点。 */
@Composable
internal fun PathfinCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    Row(modifier.heightIn(min = 44.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
        .alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
            .background(if (checked) PathfinAppearance.accent else Color.Transparent)
            .border(1.4.dp, if (checked) PathfinAppearance.accent else PathfinAppearance.textMuted, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(PathfinIcons.Check, null, Modifier.size(14.dp), tint = PathfinAppearance.onAccent)
        }
        Text(label, fontSize = 13.5.sp, lineHeight = 19.sp, color = PathfinAppearance.textMain)
    }
}

/** 全站统一的轻提示：居中的细描边胶囊，宽度随文字；左侧强调色点，操作用强调色文字，需要手动关闭时带关闭键。 */
@Composable
internal fun PathfinSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier, tag: String = "app-notice") {
    SnackbarHost(state, modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { data ->
        val shape = RoundedCornerShape(24.dp)
        val trailing = data.visuals.actionLabel != null || data.visuals.withDismissAction
        // 居中的胶囊：宽度随文字（最宽 560dp），过长时换行；毛玻璃底半透明，投影只在实色时保留。
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 560.dp)
                    .then(if (frostedEnabled) Modifier else Modifier.shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = .10f),
                        spotColor = Color.Black.copy(alpha = .14f))).clip(shape).frostedPanel()
                    .border(PathfinAppearance.cardBorder, shape).heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = if (trailing) 6.dp else 18.dp, top = 4.dp, bottom = 4.dp).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(PathfinAppearance.accent))
                Text(data.visuals.message, Modifier.weight(1f, fill = false).padding(start = 10.dp, end = if (trailing) 8.dp else 0.dp,
                    top = 10.dp, bottom = 10.dp), fontSize = 14.sp, lineHeight = 20.sp, color = PathfinAppearance.textMain,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                data.visuals.actionLabel?.let { QuietButton(it, data::performAction, color = PathfinAppearance.accent) }
                if (data.visuals.withDismissAction) PathfinIconButton(PathfinIcons.Close, tr("关闭提示"), data::dismiss,
                    tint = PathfinAppearance.textMuted, size = 36.dp)
            }
        }
    }
}


/**
 * 胶囊选择项：选中为强调色浅底与强调色字，未选为浅灰底；用于筛选与日期切换。
 * toggle 为开关语义（独立勾选的筛选），否则为选中语义（同组择一或筛选值）。
 */
@Composable
internal fun PathfinPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, toggle: Boolean = false) {
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape)
            .background(if (selected) PathfinAppearance.accentSoft else PathfinAppearance.softFill)
            .then(if (toggle) Modifier.toggleable(selected, onValueChange = { onClick() }, role = Role.Checkbox)
                else Modifier.selectable(selected, role = Role.Tab, onClick = onClick))
            .padding(horizontal = 15.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            color = if (selected) PathfinAppearance.accent else PathfinAppearance.textSub, maxLines = 1)
    }
}

/** 2dp 细线进度条：浅灰轨道上一段强调色，替代 Material 线性进度。 */
@Composable
internal fun PathfinProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = PathfinAppearance.accent) {
    val value = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(PathfinAppearance.softFill)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
        Box(Modifier.fillMaxWidth(value).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** 细线进度环，中间可放百分比；与 PathfinSpinner 同一笔触。 */
@Composable
internal fun PathfinProgressRing(progress: Float, modifier: Modifier = Modifier, strokeWidth: Dp = 2.dp,
    content: @Composable BoxScope.() -> Unit = {}) {
    val value = progress.coerceIn(0f, 1f)
    val accent = PathfinAppearance.accent
    val track = PathfinAppearance.softFill
    Box(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val arc = Size(size.width - stroke, size.height - stroke)
            drawCircle(track, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            drawArc(accent, -90f, 360f * value, false, style = Stroke(stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2f, stroke / 2f), size = arc)
        }
        content()
    }
}

/** 细轨道白色圆点滑块；Material Slider 只提供拖动与无障碍行为，外观全部自绘。深色面板传入自己的轨道色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PathfinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    activeColor: Color = if (enabled) PathfinAppearance.accent else PathfinAppearance.textMuted,
    inactiveColor: Color = PathfinAppearance.segmentTrack,
) {
    Slider(value, onValueChange, modifier, enabled = enabled, valueRange = range, steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(Modifier.size(20.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape)
                .border(0.5.dp, PathfinAppearance.divider, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
                val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                val y = size.height / 2f
                drawLine(inactiveColor, Offset(0f, y), Offset(size.width, y), 2.5.dp.toPx(), StrokeCap.Round)
                drawLine(activeColor, Offset(0f, y), Offset(size.width * fraction, y), 2.5.dp.toPx(), StrokeCap.Round)
            }
        })
}

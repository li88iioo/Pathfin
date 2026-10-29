package dev.pathfin.android.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.delay
import kotlin.math.abs

internal val LibraryIndexLabels: List<String> = listOf("0", "#") + ('A'..'Z').map(Char::toString)

/** 右边缘长按唤出，不占网格宽度；只有正常抬手才提交，系统返回取消时丢弃预览。 */
@Composable
internal fun AlphabetIndexRail(
    selected: String?,
    onSelectionChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val frosted = frostedEnabled
    var visible by remember { mutableStateOf(false) }
    var interacting by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf<String?>(null) }
    val latestSelection by rememberUpdatedState(selected)
    val commit by rememberUpdatedState(onSelectionChange)
    // 换字母是轻触感；唤出索引用更明显的长按振动，手指不看屏幕也能确认已进入索引模式。
    val feedback by rememberUpdatedState<() -> Unit> {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val summonFeedback by rememberUpdatedState<() -> Unit> {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(enabled) {
        if (!enabled) { visible = false; pressed = null }
    }
    LaunchedEffect(visible, interacting, interaction) {
        if (visible && !interacting) {
            val idleInteraction = interaction
            delay(4_000)
            // 同帧 DOWN/无障碍动作可能早于 effect 重启；旧计时不能隐藏新一轮交互。
            if (!interacting && interaction == idleInteraction) visible = false
        }
    }

    BoxWithConstraints(modifier.width(76.dp).fillMaxHeight()) {
        val railHeight = minOf(448.dp, maxHeight)
        val railTop = (maxHeight - railHeight) / 2
        val railPadding = minOf(6.dp, railHeight / 2)
        val contentHeight = railHeight - railPadding * 2
        val cellHeight = contentHeight / LibraryIndexLabels.size
        val density = LocalDensity.current
        val railHeightPx = with(density) { railHeight.toPx() }
        val railTopPx = with(density) { railTop.toPx() }
        val contentTopPx = with(density) { (railTop + railPadding).toPx() }
        val contentHeightPx = with(density) { contentHeight.toPx() }.coerceAtLeast(1f)
        val fontSize = with(density) { (cellHeight * .78f).toSp() }.let { if (it < 12.sp) it else 12.sp }
        val markSize = minOf(cellHeight, 18.dp)
        Box(
            Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight()
                .testTag("library-index-activation")
                .semantics {
                    contentDescription = tr("字母索引，长按显示")
                    customActions = listOf(CustomAccessibilityAction(if (visible) tr("隐藏字母索引") else tr("显示字母索引")) {
                        if (!enabled) false else { visible = !visible; interaction++; true }
                    })
                }
                .pointerInput(enabled, railHeightPx, railTopPx, contentHeightPx, contentTopPx) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // 不消费 DOWN，也不排除系统返回区。父层/系统已接管时不启动长按。
                        val finalDown = awaitPointerEvent(PointerEventPass.Final)
                        if (finalDown.changes.size != 1 || finalDown.changes.any { it.isConsumed }) return@awaitEachGesture
                        val slop = viewConfiguration.touchSlop
                        fun labelAt(y: Float): String {
                            val slot = ((y - contentTopPx) / contentHeightPx * LibraryIndexLabels.size)
                                .toInt().coerceIn(LibraryIndexLabels.indices)
                            return LibraryIndexLabels[slot]
                        }
                        interacting = true
                        try {
                            var dragging = false
                            if (!visible) {
                                // 只接受停留长按。提前滑动、抬手、多指或 CANCEL 均放行，不产生查询和触感。
                                val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    var done = false
                                    while (!done) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        done = event.changes.size != 1 || change == null || !change.pressed || change.isConsumed ||
                                            abs(change.position.x - down.position.x) > slop ||
                                            abs(change.position.y - down.position.y) > slop
                                        if (!done) done = awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }
                                    }
                                    true
                                }
                                if (cancelled == true) return@awaitEachGesture
                                val held = currentEvent.changes.singleOrNull { it.id == down.id }
                                if (held == null || !held.pressed || held.isConsumed) return@awaitEachGesture
                                visible = true
                                dragging = true
                                pressed = labelAt(held.position.y)
                                summonFeedback()
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Android CANCEL 是已消费的 UP，必须先判消费再判抬手。
                                if (event.changes.size != 1 || change.isConsumed) break
                                val delta = change.position - down.position
                                val horizontal = abs(delta.x)
                                val vertical = abs(delta.y)
                                if (horizontal > slop && horizontal >= vertical) break
                                if (!dragging && vertical > slop) dragging = true
                                if (change.position.x < -slop || change.position.x > size.width + slop) break
                                if (change.changedToUp()) {
                                    if (!dragging && (change.position.x !in 0f..size.width.toFloat() ||
                                            change.position.y !in railTopPx..(railTopPx + railHeightPx))) break
                                    if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
                                    val label = labelAt(change.position.y)
                                    if (!dragging || pressed != label) feedback()
                                    commit(if (!dragging && latestSelection == label) null else label)
                                    change.consume()
                                    break
                                }
                                if (!change.pressed) break
                                if (dragging) {
                                    val label = labelAt(change.position.y)
                                    if (label != pressed) { pressed = label; feedback() }
                                    change.consume()
                                } else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
                            }
                        } finally {
                            pressed = null
                            interacting = false
                            interaction++
                        }
                    }
                },
        ) {
            if (enabled && visible) Column(
                Modifier.align(Alignment.Center).fillMaxWidth().height(railHeight)
                    // 毛玻璃底半透明，投影会透成灰雾，只在实色时保留。
                    .then(if (frosted) Modifier else Modifier.shadow(8.dp, CircleShape,
                        ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .10f)))
                    .clip(CircleShape).frostedControl().border(PathfinAppearance.cardBorder, CircleShape)
                    .padding(vertical = railPadding)
                    .testTag("library-index"),
            ) {
                LibraryIndexLabels.forEach { label ->
                    val highlighted = label == (pressed ?: selected)
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("library-index/$label").semantics(mergeDescendants = true) {
                        contentDescription = when (label) {
                            "0" -> tr("数字开头")
                            "#" -> tr("非字母开头")
                            else -> tr("按字母 %1\$s 筛选", label)
                        }
                        role = Role.Button
                        this.selected = highlighted
                        onClick {
                            feedback()
                            commit(if (latestSelection == label) null else label)
                            interaction++
                            true
                        }
                    }, contentAlignment = Alignment.Center) {
                        // 当前字母用强调色小圆托底，其余为副文字色，整列读起来轻而不散。
                        if (highlighted) Box(Modifier.size(markSize).background(PathfinAppearance.accent, CircleShape))
                        Text(label, fontSize = fontSize, lineHeight = fontSize,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (highlighted) PathfinAppearance.onAccent else PathfinAppearance.textSub,
                            maxLines = 1)
                    }
                }
            }
        }
        if (enabled && visible) pressed?.let { label ->
            val center = railPadding + cellHeight * (LibraryIndexLabels.indexOf(label) + .5f)
            val bubbleSize = minOf(48.dp, railHeight)
            Box(Modifier.offset(y = railTop + (center - bubbleSize / 2).coerceIn(0.dp, railHeight - bubbleSize))
                .size(bubbleSize).shadow(10.dp, CircleShape, spotColor = PathfinAppearance.accentShadow)
                .background(PathfinAppearance.accent, CircleShape)
                .testTag("library-index-preview"), contentAlignment = Alignment.Center) {
                Text(label, color = PathfinAppearance.onAccent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

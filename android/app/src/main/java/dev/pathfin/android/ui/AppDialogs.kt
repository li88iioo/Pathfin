package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 抽屉顶部只留拖动条：标题与关闭键去掉，下滑、点遮罩或返回即可关闭。 */
@Composable
internal fun SheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PathfinAppearance.divider),
        )
    }
}

/** App 内所有底部弹层通过此入口拥有其系统栏策略。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { AppSafeDrawingInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // 底色画在内容列上（毛玻璃或实色），面板本身透明；把手和安全区留白都放进这一列，手势条下也是同一材质。
    val insets = contentWindowInsets()
    ModalBottomSheet(onDismissRequest, modifier, sheetState, sheetMaxWidth, sheetGesturesEnabled,
        shape, Color.Transparent, contentColor, tonalElevation, scrimColor, dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }, properties = properties) {
        AppSystemBars()
        // 普通内容与把手也通过嵌套滚动派发手势；只移动 sheet，不改变内容尺寸或位置。
        Column(Modifier.fillMaxWidth().frostedPanel(containerColor)
            .nestedScroll(KeepExpandedSheetInPlace)
            .scrollable(rememberScrollableState { 0f }, Orientation.Vertical, enabled = sheetGesturesEnabled)
            .windowInsetsPadding(insets)) {
            dragHandle?.let { handle -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { handle() } }
            Column(content = content)
        }
    }
}

/**
 * 内容滚到底后剩下的上推位移与惯性不交给 sheet，避免 M3 松手时带速度的弹簧动画越过展开锚点。
 * 普通内容、把手与真正的滚动列表共用此边界；向下的剩余量照常交给 sheet，下拉关闭不受影响。
 */
private val KeepExpandedSheetInPlace = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y.coerceAtMost(0f))

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y.coerceAtMost(0f))
}

/**
 * 确认窗口：毛玻璃卡片，标题与正文左对齐，底部并排等宽的 [DialogAction]（取消在左、确认在右）。
 * 只有一个操作时独占整行。点击卡片外的遮罩关闭。
 */
@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    val frosted = frostedEnabled
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AppSystemBars()
        Box(
            Modifier.fillMaxSize().pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .windowInsetsPadding(AppSafeDrawingInsets).padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier.widthIn(max = 380.dp).fillMaxWidth()
                    .then(if (frosted) Modifier else Modifier.shadow(32.dp, shape,
                        ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f)))
                    .clip(shape).frostedPanel().border(PathfinAppearance.cardBorder, shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                icon?.let { Box(Modifier.padding(bottom = 2.dp)) { CompositionLocalProvider(LocalContentColor provides PathfinAppearance.accent) { it() } } }
                title?.let { content ->
                    Box(Modifier.semantics { heading() }) {
                        ProvideTextStyle(TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
                            color = PathfinAppearance.textMain), content)
                    }
                }
                text?.let { content ->
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        ProvideTextStyle(TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = PathfinAppearance.textSub), content)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    dismissButton?.let { Box(Modifier.weight(1f)) { it() } }
                    Box(Modifier.weight(1f)) { confirmButton() }
                }
            }
        }
    }
}

internal enum class DialogActionRole { Neutral, Primary, Danger }

/** 对话框底部的等宽按钮：中性为浅灰底，主操作与全站主按钮同为墨色实底，危险操作为浅红底红字。 */
@Composable
internal fun DialogAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: DialogActionRole = DialogActionRole.Neutral,
) {
    val error = MaterialTheme.colorScheme.error
    val (container, content) = when (role) {
        DialogActionRole.Neutral -> PathfinAppearance.softFill to PathfinAppearance.textMain
        DialogActionRole.Primary -> PathfinAppearance.textMain to PathfinAppearance.canvas
        DialogActionRole.Danger -> error.copy(alpha = 0.12f) to error
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f).padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (role == DialogActionRole.Neutral) FontWeight.Medium else FontWeight.SemiBold,
            color = content, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** 自绘卡片对话框：标题、可滚动正文与底部操作行；点击卡片外的遮罩区域关闭。 */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val frosted = frostedEnabled
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AppSystemBars()
        Box(
            Modifier.fillMaxSize().pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .windowInsetsPadding(AppSafeDrawingInsets).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // 毛玻璃底半透明，投影会透成灰雾，只在实色时保留。
                modifier.widthIn(max = 440.dp).fillMaxWidth()
                    .then(if (frosted) Modifier else Modifier.shadow(32.dp, shape,
                        ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f)))
                    .clip(shape).frostedPanel().border(PathfinAppearance.cardBorder, shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
            ) {
                Text(title, Modifier.padding(end = 8.dp).semantics { heading() }, fontSize = 19.sp, lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
                if (subtitle != null) Text(subtitle, Modifier.padding(end = 8.dp, top = 6.dp),
                    fontSize = 13.5.sp, lineHeight = 21.sp, color = PathfinAppearance.textSub)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).padding(end = 8.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
                Spacer(Modifier.height(20.dp))
                // 与确认窗口同一操作行：调用方传入的 DialogAction 以 weight(1f) 均分整行。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** Popup 使用自己的 ViewRoot 策略，不能委托给 Activity 的 InsetsController。 */
@Composable
internal fun AppPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    Popup(popupPositionProvider, onDismissRequest, properties) {
        AppSystemBars()
        content()
    }
}

/** M3 下拉菜单同样创建独立 Popup 窗口，必须与其他弹层使用同一窗口策略。 */
@Composable
internal fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 菜单面板本身透明，底色画在内容列上（含上下留白），由面板形状裁切；毛玻璃时阴影会透出灰边，改用细描边。
    val frosted = frostedEnabled
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
        modifier = modifier.frostedCard(0.50f, PathfinAppearance.card, 32.dp, fallback = containerColor), shape = shape,
        containerColor = Color.Transparent,
        shadowElevation = if (frosted) 0.dp else MenuDefaults.ShadowElevation,
        border = if (frosted) PathfinAppearance.cardBorder else null) {
        AppSystemBars()
        content()
    }
}

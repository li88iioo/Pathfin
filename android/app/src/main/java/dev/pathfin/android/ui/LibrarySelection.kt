package dev.pathfin.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.domain.MetadataRefreshOptions
import dev.pathfin.android.domain.MetadataRefreshScope
import dev.pathfin.android.i18n.tr

/** 媒体库多选时替换标题栏：左侧取消，右侧更多菜单承载批量操作。 */
@Composable
internal fun RowScope.LibrarySelectionBar(
    count: Int,
    busy: Boolean,
    canDelete: Boolean,
    canRefresh: Boolean,
    onCancel: () -> Unit,
    onAction: (BatchMediaAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var refreshDialog by remember { mutableStateOf(false) }
    PathfinIconButton(PathfinIcons.Close, tr("取消选择"), onCancel, Modifier.testTag("library-selection-cancel"),
        tint = PathfinAppearance.textMain, size = 44.dp)
    Text(tr("已选择 %1\$s 项", count), Modifier.weight(1f).padding(start = 4.dp).testTag("library-selection-count"),
        color = PathfinAppearance.textMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box {
        PathfinIconButton(MediaNavigationIcons.More, tr("更多操作"), { menu = true }, Modifier.testTag("library-selection-more"),
            enabled = !busy, tint = PathfinAppearance.textMain, size = 48.dp)
        AppDropdownMenu(menu, { menu = false }, Modifier.widthIn(min = 176.dp, max = 260.dp),
            shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
            MenuRow(tr("标记为已播放"), { menu = false; onAction(BatchMediaAction.Played(true)) },
                Modifier.testTag("library-selection-played"), icon = PathfinIcons.CheckCircleFilled)
            MenuRow(tr("标记为未播放"), { menu = false; onAction(BatchMediaAction.Played(false)) },
                Modifier.testTag("library-selection-unplayed"), icon = PathfinIcons.Unwatched)
            if (canRefresh) MenuRow(tr("刷新元数据"), { menu = false; refreshDialog = true },
                Modifier.testTag("library-selection-refresh"), icon = PathfinIcons.Refresh)
            if (canDelete) {
                MenuDivider()
                MenuRow(tr("删除"), { menu = false; confirmDelete = true }, Modifier.testTag("library-selection-delete"),
                    icon = PathfinIcons.Trash)
            }
        }
    }
    if (confirmDelete) AppAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("删除媒体")) },
        text = { Text(tr("确认删除已选的 %1\$s 项？此操作会删除服务器中的媒体及对应文件，无法撤销。", count)) },
        confirmButton = { DialogAction(tr("确认删除"), { confirmDelete = false; onAction(BatchMediaAction.Delete) },
            Modifier.testTag("library-selection-delete-confirm"), role = DialogActionRole.Danger) },
        dismissButton = { DialogAction(tr("取消"), { confirmDelete = false }) },
    )
    if (refreshDialog) RefreshMetadataDialog(count, { refreshDialog = false }) {
        refreshDialog = false
        onAction(BatchMediaAction.Refresh(it))
    }
}

@Composable
internal fun RefreshMetadataDialog(count: Int, onDismiss: () -> Unit, onConfirm: (MetadataRefreshOptions) -> Unit) {
    var scope by remember { mutableStateOf(MetadataRefreshScope.SCAN) }
    var replaceImages by remember { mutableStateOf(false) }
    var replaceTrickplay by remember { mutableStateOf(false) }
    // 扫描只核对文件变化，替换选项对它无意义。
    val replaceable = scope != MetadataRefreshScope.SCAN
    AppDialog(onDismiss, tr("刷新元数据"), subtitle = tr("已选择 %1\$s 项", count), actions = {
        DialogAction(tr("取消"), onDismiss, Modifier.weight(1f))
        DialogAction(tr("刷新"), { onConfirm(scope.options(
            replaceImages && replaceable, replaceTrickplay && replaceable)) },
            Modifier.weight(1f).testTag("library-refresh-confirm"), role = DialogActionRole.Primary)
    }) {
        GroupCard(container = PathfinAppearance.canvas) {
            MetadataRefreshScope.entries.forEach { option ->
                SettingsOptionRow(option.label, option == scope, tag = "library-refresh-${option.name.lowercase()}") { scope = option }
            }
        }
        Column(Modifier.padding(start = 6.dp, top = 8.dp)) {
            PathfinCheckbox(tr("替换现有图片"), replaceImages, { replaceImages = it },
                Modifier.testTag("library-refresh-replace-images"), enabled = replaceable)
            PathfinCheckbox(tr("替换现有的进度条预览图"), replaceTrickplay, { replaceTrickplay = it },
                Modifier.testTag("library-refresh-replace-trickplay"), enabled = replaceable)
        }
    }
}

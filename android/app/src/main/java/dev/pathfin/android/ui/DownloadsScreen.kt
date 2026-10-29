package dev.pathfin.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.download.DownloadStatus
import dev.pathfin.android.download.DownloadTask
import dev.pathfin.android.download.MediaDownloads
import dev.pathfin.android.download.formatDownloadSize
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 任务变化只更新对应行；进度区和操作区保持固定尺寸，避免下载状态切换时跳位。 */
@Composable
fun DownloadsScreen(profileId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val taskFlow = remember(context) { MediaDownloads.observe(context.applicationContext) }
    var tasks by remember { mutableStateOf<List<DownloadTask>?>(null) }
    var loadingVisible by remember { mutableStateOf(false) }
    var relocatingId by rememberSaveable { mutableStateOf<String?>(null) }
    var busyIds by remember { mutableStateOf(emptySet<String>()) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(true) }

    fun action(taskId: String, block: suspend () -> Unit) {
        if (taskId in busyIds) return
        busyIds = busyIds + taskId
        scope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                snackbar.showSnackbar(tr("操作未完成，请检查账号与保存位置的访问权限后重试"))
            } finally { busyIds = busyIds - taskId }
        }
    }

    val chooseDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val taskId = relocatingId
        relocatingId = null
        if (uri != null && taskId != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            action(taskId) { MediaDownloads.retry(context, taskId, uri) }
        }
    }
    LaunchedEffect(taskFlow, reload) {
        refreshing = true
        loadFailed = false
        try { taskFlow.collect { tasks = it; refreshing = false } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            loadFailed = true
            refreshing = false
        }
    }
    LaunchedEffect(tasks == null) {
        loadingVisible = false
        if (tasks == null) { delay(300); loadingVisible = true }
    }
    val visible = tasks?.filter { profileId == null || it.profileId == profileId }
    val active = visible?.count { it.isActive } ?: 0
    RefreshablePage(refreshing, { if (!refreshing) { refreshing = true; reload++ } },
        Modifier.fillMaxSize().testTag("downloads-screen"), refreshOnEntry = false) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("downloads-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalNavigationBottomInset.current + 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow(when {
                        visible.isNullOrEmpty() -> if (profileId == null) tr("本机下载") else tr("此账号的下载")
                        active > 0 -> tr("%1\$s 个任务 · %2\$s 个进行中", visible.size, active)
                        else -> tr("%1\$s 个任务", visible.size)
                    })
                    Text(tr("文件保存到你选择的位置，可在后台继续；中断后重试会从头下载。"),
                        fontSize = 12.5.sp, lineHeight = 18.sp, color = PathfinAppearance.textMuted)
                }
            }
            if (loadFailed && visible != null) item(key = "load-error") {
                ErrorPanel(tr("无法读取下载任务"))
            }
            when {
                visible == null -> item(key = "initial-loading") {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        if (loadFailed) ErrorPanel(tr("无法读取下载任务"))
                        else if (loadingVisible) PathfinSpinner(PathfinAppearance.accent, Modifier.size(24.dp))
                    }
                }
                visible.isEmpty() -> item(key = "empty") {
                    EmptyPanel(tr("还没有下载"), tr("在电影或单集详情中选择下载，再选择文件保存位置。"), icon = PathfinIcons.Download)
                }
                else -> items(visible, key = { it.id }, contentType = { "download" }) { task ->
                    DownloadRow(task, busy = task.id in busyIds,
                        onPrimary = {
                            when (task.status) {
                                DownloadStatus.COMPLETED -> if (!MediaDownloads.open(context, task)) scope.launch {
                                    snackbar.showSnackbar(tr("无法打开文件，文件可能已移除或尚未安装对应播放器"))
                                }
                                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> action(task.id) { MediaDownloads.cancel(context, task.id) }
                                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> action(task.id) { MediaDownloads.retry(context, task.id) }
                            }
                        },
                        onRelocate = { relocatingId = task.id; chooseDestination.launch(task.fileName) },
                        onRemove = { action(task.id) { MediaDownloads.remove(context, task.id) } })
                }
            }
        }
        PathfinSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavigationBottomInset.current))
    }
}

/** 一张卡：左侧状态图标块，中间标题与状态，右侧主操作（进行中为细线进度环）与更多菜单。 */
@Composable
private fun DownloadRow(task: DownloadTask, busy: Boolean, onPrimary: () -> Unit, onRelocate: () -> Unit, onRemove: () -> Unit) {
    var menu by remember(task.id) { mutableStateOf(false) }
    val failed = task.status == DownloadStatus.FAILED
    val primary = when (task.status) {
        DownloadStatus.COMPLETED -> PathfinIcons.Play to tr("打开文件")
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> PathfinIcons.Stop to tr("取消下载")
        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> PathfinIcons.Refresh to tr("重新下载")
    }
    val statusIcon = when (task.status) {
        DownloadStatus.COMPLETED -> PathfinIcons.CheckCircle
        DownloadStatus.FAILED -> PathfinIcons.Alert
        DownloadStatus.CANCELLED -> PathfinIcons.Close
        else -> PathfinIcons.Download
    }
    GroupCard(Modifier.testTag("download-${task.id}")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (failed) PathfinAppearance.tintRed else PathfinAppearance.softFill), contentAlignment = Alignment.Center) {
                Icon(statusIcon, null, Modifier.size(20.dp), tint = when {
                    failed -> MaterialTheme.colorScheme.error
                    task.status == DownloadStatus.COMPLETED -> PathfinAppearance.accent
                    else -> PathfinAppearance.textSub
                })
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.displayName, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    color = PathfinAppearance.textMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(downloadStatus(task), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (failed) MaterialTheme.colorScheme.error else PathfinAppearance.textMuted)
            }
            // 进行中显示细线进度环，整格仍是“取消下载”按钮，与其他状态的图标同一位置。
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !busy, role = Role.Button, onClick = onPrimary)
                .semantics { contentDescription = primary.second }, contentAlignment = Alignment.Center) {
                val progress = task.progress
                when {
                    busy -> PathfinSpinner(PathfinAppearance.accent, Modifier.size(22.dp))
                    task.isActive && progress != null -> PathfinProgressRing(progress, Modifier.size(38.dp)) {
                        Text("${(progress * 100).toInt()}", fontSize = 10.5.sp, lineHeight = 12.sp, color = PathfinAppearance.textSub)
                    }
                    task.isActive -> PathfinSpinner(PathfinAppearance.accent, Modifier.size(30.dp))
                    else -> Icon(primary.first, null, Modifier.size(22.dp), tint = PathfinAppearance.accent)
                }
            }
            Box {
                PathfinIconButton(MediaNavigationIcons.More, tr("下载操作"), { menu = true }, enabled = !busy, size = 40.dp)
                AppDropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(CardRadius), containerColor = dialogSurfaceColor()) {
                    if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                        MenuRow(tr("重新选择保存位置"), { menu = false; onRelocate() }, icon = PathfinIcons.Folder)
                    }
                    MenuRow(tr("移除记录，保留文件"), { menu = false; onRemove() }, icon = PathfinIcons.Trash, enabled = !task.isActive)
                }
            }
        }
    }
}

private fun downloadStatus(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> task.queueDescription
    DownloadStatus.DOWNLOADING -> if (task.downloadedBytes == 0L) tr("正在准备下载") else
        formatDownloadSize(task.downloadedBytes) + (task.totalBytes?.let { " / ${formatDownloadSize(it)}" } ?: "")
    DownloadStatus.COMPLETED -> formatDownloadSize(task.downloadedBytes) + tr(" · 可用本机播放器打开")
    DownloadStatus.FAILED -> task.error ?: tr("下载失败，可以重试")
    DownloadStatus.CANCELLED -> task.error ?: tr("已取消；重试会从头重新下载")
}

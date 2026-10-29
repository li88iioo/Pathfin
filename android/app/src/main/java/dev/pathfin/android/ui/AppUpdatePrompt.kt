package dev.pathfin.android.ui

import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pathfin.android.BuildConfig
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.update.AppUpdateViewModel
import dev.pathfin.android.update.UpdateState
import kotlinx.coroutines.launch

/** 新版本对话框：下载可在后台继续，完成后重新弹出；安装前按需引导“安装未知应用”授权。 */
@Composable
internal fun AppUpdatePrompt(vm: AppUpdateViewModel) {
    val visible by vm.prompt.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val install = {
        scope.launch {
            val intent = vm.installIntent() ?: return@launch
            runCatching { context.startActivity(intent) }
                .onSuccess { vm.dismissPrompt() }
                .onFailure { note = tr("无法打开系统安装器，请重试") }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install() else note = tr("尚未允许安装未知应用，授权后可再次安装")
    }
    UpdateDialog(state, note, onDismiss = vm::dismissPrompt, onDownload = vm::download, onCancel = vm::cancelDownload, onInstall = {
        note = null
        if (context.packageManager.canRequestPackageInstalls()) install()
        else runCatching {
            permission.launch(android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        }.onFailure { note = tr("无法打开安装权限设置，请在系统设置中允许安装未知应用") }
    })
}

@Composable
internal fun UpdateDialog(state: UpdateState, note: String?, onDismiss: () -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onInstall: () -> Unit) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        else -> return
    }
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, release.apkSize)
    AppDialog(onDismiss, tr("发现新版本 %1\$s", release.tag), Modifier.testTag("update-dialog"),
        subtitle = tr("当前版本 %1\$s · 安装包 %2\$s", BuildConfig.VERSION_NAME, size), actions = {
            when (state) {
                is UpdateState.Downloading -> {
                    DialogAction(tr("后台下载"), onDismiss, Modifier.weight(1f))
                    DialogAction(tr("取消下载"), onCancel, Modifier.weight(1f).testTag("update-cancel"))
                }
                is UpdateState.Ready -> {
                    DialogAction(tr("稍后"), onDismiss, Modifier.weight(1f))
                    DialogAction(tr("安装"), onInstall, Modifier.weight(1f).testTag("update-install"), role = DialogActionRole.Primary)
                }
                else -> {
                    DialogAction(tr("稍后"), onDismiss, Modifier.weight(1f))
                    DialogAction(tr("下载更新"), onDownload, Modifier.weight(1f).testTag("update-download"), role = DialogActionRole.Primary)
                }
            }
        }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(release.notes.ifBlank { tr("此版本没有更新说明") },
                Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("update-notes"),
                fontSize = 13.sp, lineHeight = 20.sp, color = PathfinAppearance.textSub)
            (state as? UpdateState.Downloading)?.let { downloading ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PathfinProgressBar(downloading.bytes.toFloat() / release.apkSize, Modifier.testTag("update-progress"))
                    Text(tr("已下载 %1\$s / %2\$s", Formatter.formatShortFileSize(context, downloading.bytes), size),
                        fontSize = 12.sp, color = PathfinAppearance.textMuted)
                }
            }
            ((state as? UpdateState.Available)?.error ?: note)?.let { message ->
                Text(message, Modifier.padding(top = 2.dp).testTag("update-error"), fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

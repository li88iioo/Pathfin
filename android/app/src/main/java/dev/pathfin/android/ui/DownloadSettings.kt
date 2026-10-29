package dev.pathfin.android.ui

import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import dev.pathfin.android.data.SafDocumentTransfer
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.download.DownloadDirectories
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 与现有设置共用分组卡片和单选对话框；目录访问与授权使用有界 SAF 操作。 */
@Composable
internal fun DownloadSettings(preferences: AppPreferences, onPreferences: (AppPreferences) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(preferences)
    var networkDialog by remember { mutableStateOf(false) }
    var directoryDialog by remember { mutableStateOf(false) }
    var directoryBusy by remember { mutableStateOf(false) }
    var directoryName by remember {
        mutableStateOf(if (preferences.downloadDirectoryUri == null) tr("每次询问") else tr("已选择的目录"))
    }
    var feedback by remember { mutableStateOf<String?>(null) }
    val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            directoryBusy = true
            scope.launch {
                try {
                    val name = SafDocumentTransfer.transfer { DownloadDirectories.persist(context, uri, it) }
                    onPreferences(current.copy(downloadDirectoryUri = uri.toString()))
                    directoryName = name
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback = tr("无法使用此目录，请选择支持写入的文件夹") }
                finally { directoryBusy = false }
            }
        }
    }
    LaunchedEffect(preferences.downloadDirectoryUri) {
        preferences.downloadDirectoryUri?.let { uri ->
            directoryName = try { SafDocumentTransfer.transfer { DownloadDirectories.name(context, uri.toUri(), it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { tr("目录不可用，请重新选择") }
        }
    }
    Column {
        SettingsRow(tr("允许使用的网络"), preferences.downloadNetwork.label, tag = "download-network",
            position = GroupPosition.TOP) { networkDialog = true }
        SettingsRow(tr("下载位置"), if (preferences.downloadDirectoryUri == null) tr("每次询问") else directoryName,
            tag = "download-directory", enabled = !directoryBusy,
            position = GroupPosition.BOTTOM) { directoryDialog = true }
    }
    if (networkDialog) SettingsChoiceDialog(tr("允许使用的网络"), { networkDialog = false },
        note = tr("不计费属性由系统判断。更改规则会重新排队，未完成的下载从头开始。")) {
        DownloadNetwork.entries.forEach { network ->
            SettingsOptionRow(network.label, network == preferences.downloadNetwork,
                tag = "download-network-${network.name.lowercase()}") {
                onPreferences(current.copy(downloadNetwork = network))
                networkDialog = false
            }
        }
    }
    if (directoryDialog) SettingsChoiceDialog(tr("下载位置"), { directoryDialog = false },
        note = tr("当前：%1\$s。更改仅影响新下载，已有文件和任务保持原位置。", directoryName)) {
        SettingsOptionRow(tr("每次询问"), preferences.downloadDirectoryUri == null, tag = "download-directory-ask") {
            onPreferences(current.copy(downloadDirectoryUri = null))
            directoryDialog = false
        }
        SettingsOptionRow(tr("选择文件夹…"), preferences.downloadDirectoryUri != null, tag = "download-directory-pick") {
            directoryDialog = false
            chooseDirectory.launch(current.downloadDirectoryUri?.toUri())
        }
    }
    feedback?.let { message ->
        AppDialog({ feedback = null }, message,
            actions = { DialogAction(tr("确定"), { feedback = null }, Modifier.weight(1f), role = DialogActionRole.Primary) }) {}
    }
}

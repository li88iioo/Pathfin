package dev.pathfin.android.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pathfin.android.casting.CastDevice
import dev.pathfin.android.casting.CastState
import dev.pathfin.android.casting.CastStatus
import dev.pathfin.android.casting.DlnaCasting
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选择设备即开始向所选设备投屏；关闭底部页后仍可从前台通知控制。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(item: MediaItem, profileId: String, session: Session, api: JellyfinApi, onDismiss: () -> Unit,
    onLocalNetworkPermissionRequired: () -> Unit, onAuthExpired: () -> Unit = {}, onStarted: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by DlnaCasting.state.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(LocalNetworkPermission.isGranted(context)) }
    val grantedNow = LocalNetworkPermission.isGranted(context)
    var sourceJob by remember { mutableStateOf<Job?>(null) }
    var loadingSources by remember(profileId, item.id) { mutableStateOf(false) }
    var sources by remember(profileId, item.id) { mutableStateOf(emptyList<MediaSource>()) }
    var selectedDevice by remember(profileId, item.id) { mutableStateOf<CastDevice?>(null) }
    var sourceError by remember(profileId, item.id) { mutableStateOf<String?>(null) }
    var connectingObserved by remember(profileId, item.id) { mutableStateOf(false) }
    var startedDelivered by remember(profileId, item.id) { mutableStateOf(false) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permission = LocalNetworkPermission.isGranted(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); DlnaCasting.cancelDiscovery() }
    }
    DisposableEffect(profileId, session.userId, session.server, session.accessToken, item.id) {
        onDispose { sourceJob?.cancel() }
    }
    LaunchedEffect(grantedNow) { permission = grantedNow }
    LaunchedEffect(permission, profileId) { if (permission) DlnaCasting.discover(context) }
    LaunchedEffect(state.status, state.profileId, state.itemId, state.authExpired) {
        if (state.profileId == profileId && state.itemId == item.id) {
            if (state.status == CastStatus.CONNECTING) { connectingObserved = true; startedDelivered = false }
            if (state.status == CastStatus.PLAYING && connectingObserved && !startedDelivered) { startedDelivered = true; onStarted() }
            if (state.authExpired) onAuthExpired()
        }
    }
    fun start(device: CastDevice, source: MediaSource) {
        selectedDevice = null; sources = emptyList(); sourceError = null
        DlnaCasting.start(context, profileId, item.id, device.id, source.id, item.positionTicks)
    }
    fun choose(device: CastDevice) {
        if (loadingSources || state.busy) return
        if (!LocalNetworkPermission.isGranted(context)) { permission = false; onLocalNetworkPermissionRequired(); return }
        loadingSources = true; sourceError = null
        sourceJob = scope.launch {
            try {
                val available = withContext(Dispatchers.IO) { api.playbackInfo(session, item.id).sources }
                if (available.isEmpty()) sourceError = tr("服务器未提供可播放的媒体版本")
                else if (available.size == 1) start(device, available.single())
                else { sources = available; selectedDevice = device }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                sourceError = when {
                    failure is ApiException && failure.statusCode == 401 -> { onAuthExpired(); tr("登录已失效，请重新登录") }
                    failure is ApiException && failure.statusCode == 403 -> tr("当前账号无权播放此媒体")
                    else -> tr("无法读取媒体版本，请稍后重试")
                }
            } finally { loadingSources = false }
        }
    }
    AppModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("cast-sheet")) {
        CastSheetContent(state, profileId, permission, loadingSources, sourceError,
            onDiscover = { sourceError = null; scope.launch { DlnaCasting.discover(context) } },
            onPermission = onLocalNetworkPermissionRequired, onDevice = ::choose,
            onPlay = { DlnaCasting.play(context) }, onPause = { DlnaCasting.pause(context) },
            onSeek = { DlnaCasting.seek(context, it) }, onStop = { DlnaCasting.stop(context) })
    }
    selectedDevice?.let { device ->
        AppAlertDialog(onDismissRequest = { selectedDevice = null; sources = emptyList() }, title = { Text(tr("选择媒体版本")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { source ->
                    Text(source.name?.takeIf(String::isNotBlank) ?: source.container?.uppercase() ?: tr("媒体版本"),
                        Modifier.fillMaxWidth().clickable { start(device, source) }.padding(vertical = 14.dp).testTag("cast-source"))
                }
            } }, confirmButton = { DialogAction(tr("取消"), { selectedDevice = null; sources = emptyList() }) })
    }
}

/** 纯展示层便于无网络验证空态、忙态和真实控制回调；发现与取流均由服务执行。 */
@Composable
internal fun CastSheetContent(state: CastState, profileId: String, permission: Boolean, loadingSources: Boolean,
    sourceError: String?, onDiscover: () -> Unit, onPermission: () -> Unit, onDevice: (CastDevice) -> Unit,
    onPlay: () -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit) {
    val own = state.profileId == profileId
    val active = own && state.active
    RefreshablePage(state.discovering, onDiscover, Modifier.fillMaxWidth().heightIn(max = 660.dp).height(500.dp),
        refreshOnEntry = false, enabled = permission && !state.busy && !loadingSources) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("cast-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("DLNA 设备"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterEnd) {
                        if (state.discovering || loadingSources) PathfinSpinner(PathfinAppearance.accent, Modifier.size(22.dp).testTag("cast-discovering"))
                    }
                }
                Text(tr("选择与手机在同一局域网的电视或播放器"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!permission) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(PathfinIcons.Tv, null, Modifier.size(40.dp), tint = PathfinAppearance.textSub)
                    Text(tr("允许局域网访问后才能发现和连接设备"), color = PathfinAppearance.textMain)
                    InkButton(onPermission, Modifier.testTag("cast-permission")) { ButtonLabel(tr("允许局域网访问")) }
                }
            }
            if (active) item {
                Column(Modifier.fillMaxWidth().testTag("cast-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hairline()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(PathfinIcons.CastConnected, null, Modifier.size(28.dp), tint = PathfinAppearance.accent)
                        Column(Modifier.weight(1f)) {
                            Text(state.device?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(state.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(when (state.status) { CastStatus.CONNECTING -> tr("正在连接并等待设备读取媒体…"); CastStatus.PAUSED -> tr("已暂停"); else -> tr("正在播放") },
                        Modifier.testTag("cast-status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.status != CastStatus.CONNECTING) {
                        CastProgress(state, onSeek)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InkButton(if (state.status == CastStatus.PAUSED) onPlay else onPause,
                                Modifier.weight(1f).testTag("cast-play-pause"), enabled = !state.busy, muted = state.busy) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (state.status == CastStatus.PAUSED) PathfinIcons.Play else PathfinIcons.Pause, null, Modifier.size(18.dp))
                                    ButtonLabel(if (state.status == CastStatus.PAUSED) tr("继续播放") else tr("暂停"))
                                }
                            }
                            CastStopButton(tr("停止投屏"), onStop, Modifier.weight(1f))
                        }
                    } else CastStopButton(tr("取消连接"), onStop, Modifier.fillMaxWidth())
                    Text(tr("设备通过本机的临时地址读取媒体。关闭此页面后，可从通知暂停或停止。"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Hairline()
                }
            }
            (sourceError ?: state.error?.takeIf { own || !state.active })?.let { error -> item {
                Text(error, Modifier.testTag("cast-error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            } }
            if (permission && state.devices.isEmpty()) item {
                Column(Modifier.fillMaxWidth().heightIn(min = if (active) 130.dp else 210.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(PathfinIcons.Cast, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(if (state.discovering) tr("正在发现局域网设备…") else tr("未发现 DLNA 设备"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(tr("请打开电视的 DLNA 功能，并关闭路由器的设备隔离。"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (permission) items(state.devices, key = { it.id }) { device ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy && !loadingSources) { onDevice(device) }
                    .heightIn(min = 68.dp).padding(vertical = 12.dp).testTag("cast-device"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (active && state.device?.id == device.id) PathfinIcons.CastConnected else PathfinIcons.Tv, null, Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active && state.device?.id == device.id) Text(tr("已选择"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/** 次要但需要明确边界的操作：白卡片细描边、与主按钮同高同圆角。 */
@Composable
private fun CastStopButton(label: String, onStop: () -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(PathfinAppearance.card)
        .border(PathfinAppearance.cardBorder, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onStop)
        .testTag("cast-stop"), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PathfinIcons.Stop, null, Modifier.size(16.dp), tint = PathfinAppearance.textMain)
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
        }
    }
}

@Composable
private fun CastProgress(state: CastState, onSeek: (Long) -> Unit) {
    val duration = state.durationTicks?.takeIf { it > 0 }
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.positionTicks, duration) { if (!dragging) fraction = if (duration == null) 0f else (state.positionTicks.toDouble() / duration).toFloat().coerceIn(0f, 1f) }
    Column {
        PathfinSlider(fraction, { dragging = true; fraction = it }, Modifier.fillMaxWidth().semantics { contentDescription = tr("投屏进度") }.testTag("cast-seek"),
            onValueChangeFinished = { dragging = false; if (duration != null) onSeek((duration * fraction.toDouble()).toLong()) },
            enabled = !state.busy && duration != null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(castTime(if (dragging && duration != null) (duration * fraction.toDouble()).toLong() else state.positionTicks), style = MaterialTheme.typography.labelMedium)
            Text(duration?.let(::castTime) ?: tr("时长未知"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun castTime(ticks: Long): String {
    val seconds = ticks.coerceAtLeast(0) / 10_000_000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

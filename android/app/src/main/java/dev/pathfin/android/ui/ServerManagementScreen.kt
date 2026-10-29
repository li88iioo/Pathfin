@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import dev.pathfin.android.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.PublicUser
import dev.pathfin.android.domain.ServerAddressFieldSource
import dev.pathfin.android.domain.ServerAddressInputState
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.delay

private data class ServerDialog(val kind: String, val profileId: String? = null)

private val ServerAddressInputSaver = listSaver<ServerAddressInputState, Any>(
    save = { listOf(it.host, it.scheme, it.port, it.path, it.protocolSource.name, it.portSource.name) },
    restore = { ServerAddressInputState(it[0] as String, it[1] as String, it[2] as String, it[3] as String, ServerAddressFieldSource.valueOf(it[4] as String), ServerAddressFieldSource.valueOf(it[5] as String)) },
)

private const val TicksPerMinute = 600_000_000L

/**
 * 服务器页：没有服务器或正在添加时展示两步连接页，否则展示媒体空间。
 * 网络操作由 ViewModel 完成；本页只发出经过本地校验的事件。
 */
@Composable
internal fun ServerManagementScreen(
    profiles: List<ServerProfile>,
    currentId: String?,
    busy: Boolean,
    error: String?,
    probe: ServerProbe?,
    adding: Boolean,
    showcase: ServerShowcase?,
    onProbe: (ServerConfig) -> Unit,
    onAddServer: () -> Unit,
    onConnectBack: () -> Unit,
    onOpen: (ServerProfile) -> Unit,
    onAdd: (ServerConfig, String, String, String) -> Unit,
    onUpdate: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onAddAddress: (ServerProfile, ServerConfig) -> Unit,
    onPassword: (ServerProfile, String, String) -> Unit,
    onDismissError: () -> Unit,
    api: JellyfinApi = remember { JellyfinApi() },
    switching: Boolean = false,
) {
    var dialog by remember { mutableStateOf<ServerDialog?>(null) }
    var sheetId by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var observedBusy by remember { mutableStateOf(false) }
    val activeDialog = dialog
    val target = profiles.firstOrNull { it.id == activeDialog?.profileId }
    val requestBusy = busy || submitted
    val connecting = profiles.isEmpty() || adding
    LaunchedEffect(busy, submitted) {
        if (busy) observedBusy = true
        else if (observedBusy) {
            observedBusy = false
            submitted = false
            if (error == null) dialog = null
        } else if (submitted) {
            withFrameNanos { }
            if (!busy) submitted = false
        }
    }
    fun show(kind: String, profile: ServerProfile? = null) {
        onDismissError()
        sheetId = null
        submitted = false
        observedBusy = false
        dialog = ServerDialog(kind, profile?.id)
    }
    fun dispatch(block: () -> Unit) {
        if (busy || submitted) return
        submitted = true
        block()
    }
    fun leaveConnectStep() {
        if (!requestBusy || probe == null) {
            submitted = false
            observedBusy = false
            onConnectBack()
        }
    }
    BackHandler(enabled = connecting && (probe != null || adding || requestBusy)) { leaveConnectStep() }

    Box(Modifier.fillMaxSize()) {
        if (connecting) updateTransition(probe, label = "connect-step").Crossfade(
            animationSpec = tween(240), contentKey = { it?.config }) { step ->
            if (step == null) AddressStep(requestBusy, error.takeIf { activeDialog == null }, firstRun = profiles.isEmpty(),
                onProbe = { config -> dispatch { onProbe(config) } }, onCancel = ::leaveConnectStep)
            else AccountStep(step, requestBusy, error.takeIf { activeDialog == null }, api,
                onLogin = { username, password, displayName -> dispatch { onAdd(step.config, username, password, displayName) } },
                onBack = ::leaveConnectStep)
        } else MediaSpace(profiles, currentId, showcase, error.takeIf { activeDialog == null }, api,
            onAdd = { if (!requestBusy) onAddServer() },
            onOpen = { if (!requestBusy || switching) onOpen(it) },
            onMore = { if (!requestBusy) sheetId = it.id },
            onDismissError = onDismissError)
    }

    profiles.firstOrNull { it.id == sheetId }?.let { profile ->
        ServerActionSheet(profile, api, onDismiss = { sheetId = null }, onAction = { show(it, profile) }, onUpdate = onUpdate)
    }

    if (activeDialog != null) key(activeDialog) {
        val dismiss = { if (!requestBusy) { dialog = null; onDismissError() } }
        val cancel: @Composable RowScope.() -> Unit = { DialogAction(tr("取消"), dismiss, Modifier.weight(1f), enabled = !requestBusy) }
        when (activeDialog.kind) {
            "edit", "address" -> target?.let { profile ->
                ServerAddressDialog(activeDialog.kind, profile, requestBusy, error, dismiss) { config, displayName ->
                    dispatch {
                        if (activeDialog.kind == "address") onAddAddress(profile, config)
                        else onUpdate(profile.copy(session = profile.session.copy(server = config), displayName = displayName.ifBlank { profile.displayName }))
                    }
                }
            }
            "name" -> target?.let { profile ->
                DisplayNameDialog(profile, api, dismiss) { name -> onUpdate(profile.copy(displayName = name)); dialog = null }
            }
            "icon" -> target?.let { profile ->
                AppDialog(dismiss, tr("自定义图标"), subtitle = tr("在服务器列表中替代账号头像"), actions = cancel) {
                    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("", "🎬", "📺", "🍿", "🎞️", "🌟", "🌊", "🏠", "🌙", "🚀", "🐱", "🎧").forEach { icon ->
                            val selected = profile.icon == icon
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier.size(48.dp).clip(shape).background(PathfinAppearance.softFill)
                                    .border(1.5.dp, if (selected) PathfinAppearance.accent else Color.Transparent, shape)
                                    .clickable(role = Role.Button) { onUpdate(profile.copy(icon = icon)); dialog = null },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (icon.isBlank()) Icon(PathfinIcons.User, tr("默认图标"), Modifier.size(20.dp),
                                    tint = if (selected) PathfinAppearance.accent else PathfinAppearance.textSub)
                                else Text(icon, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
            "password" -> target?.let { profile ->
                var current by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var confirmation by remember { mutableStateOf("") }
                val valid = password.isNotEmpty() && password == confirmation
                ServerDialogFrame(tr("修改密码"), busy = requestBusy, error = error, onDismiss = dismiss,
                    onSubmit = { dispatch { onPassword(profile, current, password) } }, submitEnabled = valid, submitLabel = tr("修改"),
                    subtitle = tr("为 %1\$s 设置新密码", profile.session.userName)) {
                    GroupCard(container = PathfinAppearance.canvas) {
                        PasswordField(current, { current = it }, tr("当前密码"), tr("输入当前密码"), requestBusy, divider = false)
                        PasswordField(password, { password = it }, tr("新密码"), tr("输入新密码"), requestBusy)
                        PasswordField(confirmation, { confirmation = it }, tr("确认新密码"), tr("再次输入新密码"), requestBusy, imeAction = ImeAction.Done)
                    }
                }
            }
            "addresses" -> target?.let { profile ->
                AppDialog(dismiss, tr("连接地址"), subtitle = tr("选择本次连接使用的地址"), actions = cancel) {
                    (listOf(profile.session.server) + profile.addresses).distinctBy { it.baseUrl }.forEachIndexed { index, address ->
                        val active = address == profile.session.server
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().clickable { if (!requestBusy) { dialog = null; onOpen(profile.copy(session = profile.session.copy(server = address))) } }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(address.baseUrl, Modifier.weight(1f), fontSize = 13.5.sp, lineHeight = 20.sp,
                                color = if (active) PathfinAppearance.textMain else PathfinAppearance.textSub)
                            if (active) Icon(PathfinIcons.Check, tr("当前地址"), Modifier.size(18.dp), tint = PathfinAppearance.accent)
                        }
                    }
                }
            }
            "delete" -> target?.let { profile ->
                AppDialog(dismiss, tr("删除服务器？"), actions = {
                    cancel()
                    DialogAction(tr("删除"), { onDelete(profile); dialog = null }, Modifier.weight(1f), role = DialogActionRole.Danger)
                }) {
                    Text(tr("从本机移除“%1\$s”的账号与地址，服务器上的媒体和观看记录会保留。", profile.displayName),
                        fontSize = 14.sp, lineHeight = 22.sp, color = PathfinAppearance.textSub)
                }
            }
        }
    }
}

// ───────────────────────── 两步连接 ─────────────────────────

/** 连接页骨架：内容可滚动，操作固定在底部并让出手势条与键盘，错误出现时不推动按钮。 */
@Composable
private fun StepScaffold(
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Bottom)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = actions,
        )
    }
}

/** 首次连接保留紧凑品牌标识，不随键盘开合改变尺寸。 */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.height(28.dp).aspectRatio(42f / 50f))
        Text("Pathfin", fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp, color = PathfinAppearance.textMain)
    }
}

/** 品牌色固定，不随主题点缀色变化。 */
private val BrandBlue = Color(0xFF5B89FA)

/**
 * 连接第一步：清晰标题、紧凑地址表单和底部主操作；首启保留品牌标识。
 * 纯色画布延展到系统栏，控件让出安全区。键盘、连接状态和错误变化不重排品牌或操作区。
 */
@Composable
private fun AddressStep(
    busy: Boolean,
    error: String?,
    firstRun: Boolean,
    onProbe: (ServerConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var address by rememberSaveable(stateSaver = ServerAddressInputSaver) { mutableStateOf(ServerAddressInputState()) }
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy) return
        try {
            val config = address.toServerConfig(allowHttp)
            localError = null
            onProbe(config)
        } catch (error: IllegalArgumentException) { localError = error.message ?: tr("请检查服务器地址") }
    }
    StepScaffold(
        modifier = Modifier.background(PathfinAppearance.canvas).testTag("server-address-page"),
        actions = {
            SubmitButton(tr("连接"), address.host.isNotBlank(), busy, ::submit, Modifier.fillMaxWidth())
        },
    ) {
        // 标题和表单共用滚动区域，横屏或键盘占用较多高度时仍能访问每个字段。
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth()
            .windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Top))
            .padding(start = if (firstRun) 24.dp else 10.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)
            .heightIn(min = 52.dp).testTag("server-address-header"), verticalAlignment = Alignment.CenterVertically) {
            if (firstRun) {
                Wordmark()
                Spacer(Modifier.weight(1f))
                if (busy) PathfinIconButton(PathfinIcons.Close, tr("取消连接"), onCancel,
                    tint = PathfinAppearance.textMain, size = 48.dp)
            } else {
                PathfinIconButton(PathfinIcons.Back, if (busy) tr("取消连接") else tr("返回"), onCancel,
                    Modifier.testTag("server-address-back"), tint = PathfinAppearance.textMain, size = 48.dp)
                Text(tr("添加服务器"), Modifier.padding(start = 4.dp).weight(1f).semantics { heading() },
                    fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
                    color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            if (firstRun) Text(tr("添加服务器"),
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 18.dp).semantics { heading() },
                fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
            ServerAddressInput(address, { address = it; localError = null }, allowHttp, { allowHttp = it }, busy, onDone = ::submit)
            FormFeedback(localError ?: error, lines = 3, Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
        }
    }
}

/**
 * 地址输入：一个地址栏承载主机（可粘贴完整地址），左侧协议块切换 HTTPS / HTTP；下方实时预览将连接的地址。
 * HTTP 时直接给出明文连接开关；路径与端口收在“高级选项”里，已填过非默认值时自动展开。连接页与编辑弹窗共用。
 */
@Composable
private fun ServerAddressInput(
    address: ServerAddressInputState,
    onChange: (ServerAddressInputState) -> Unit,
    allowHttp: Boolean,
    onAllowHttp: (Boolean) -> Unit,
    busy: Boolean,
    onDone: () -> Unit,
    container: Color = PathfinAppearance.card,
) {
    val http = address.scheme == "http"
    var advanced by rememberSaveable { mutableStateOf(address.path.isNotBlank() || address.portSource == ServerAddressFieldSource.Manual) }
    val endpoint = remember(address) { address.host.takeIf(String::isNotBlank)?.let { runCatching { address.toServerConfig(true).baseUrl }.getOrNull() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AddressBar(address, onChange, busy, onDone, container)
        Text(endpoint?.let { tr("将连接 %1\$s", it) } ?: tr("支持域名、IP 或完整地址，端口自动识别"), Modifier.padding(horizontal = 8.dp).testTag("server-endpoint"),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = if (endpoint != null) PathfinAppearance.textSub else PathfinAppearance.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (http) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(allowHttp, enabled = !busy, role = Role.Switch, onValueChange = onAllowHttp)
            .heightIn(min = 48.dp).padding(horizontal = 8.dp).testTag("login-http"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("允许 HTTP 明文连接与播放"), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp,
                color = PathfinAppearance.textSub)
            PathfinSwitch(allowHttp, enabled = !busy)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { advanced = !advanced }
            .heightIn(min = 48.dp).padding(horizontal = 8.dp).testTag("server-advanced")
            .semantics { stateDescription = if (advanced) tr("已展开") else tr("已收起") },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("高级选项"), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = PathfinAppearance.textSub)
            if (address.path.isNotBlank() || address.portSource == ServerAddressFieldSource.Manual)
                Text(tr("路径 · 端口 %1\$s", address.port), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                    color = PathfinAppearance.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(PathfinIcons.ChevronDown, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (advanced) 180f else 0f }, tint = PathfinAppearance.textMuted)
        }
        if (advanced) GroupCard(container = container) {
            GroupField(address.path, { if (!busy) onChange(address.copy(path = it)) }, tr("路径"), tr("可选，如 /jellyfin"), PathfinIcons.Link,
                divider = false, readOnly = busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next))
            GroupField(address.port, { if (!busy && it.all(Char::isDigit)) onChange(address.withPort(it)) }, tr("端口"), "443", PathfinIcons.Hash,
                Modifier.testTag("server-port"), readOnly = busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }))
        }
    }
}

/** 类似浏览器地址栏的主机输入；协议块切换 HTTPS / HTTP，聚焦时描边变为点缀色。 */
@Composable
private fun AddressBar(
    address: ServerAddressInputState,
    onChange: (ServerAddressInputState) -> Unit,
    busy: Boolean,
    onDone: () -> Unit,
    container: Color,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp,
        color = PathfinAppearance.textMain)
    val outline = if (focused) PathfinAppearance.accent.copy(alpha = 0.6f) else PathfinAppearance.divider
    BasicTextField(
        value = address.host,
        onValueChange = { if (!busy) onChange(address.withAddress(it)) },
        modifier = Modifier.fillMaxWidth().testTag("login-server").semantics { contentDescription = tr("服务器地址") }
            .onFocusChanged { focused = it.isFocused },
        readOnly = busy,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(PathfinAppearance.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onDone() }, onDone = { onDone() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp)
                    .clip(shape).background(container)
                    .border(1.dp, outline, shape).padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy, role = Role.Button, onClickLabel = tr("切换协议")) {
                            onChange(address.withScheme(if (address.scheme == "https") "http" else "https"))
                        }
                        .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(address.scheme.lowercase(), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                        color = if (address.scheme == "https") PathfinAppearance.textSub else PathfinAppearance.accent)
                    Icon(PathfinIcons.ChevronDown, null, Modifier.size(12.dp), tint = PathfinAppearance.textMuted)
                }
                Box(Modifier.padding(start = 8.dp).size(width = 1.dp, height = 20.dp).background(PathfinAppearance.divider))
                Box(Modifier.weight(1f).padding(start = 12.dp)) {
                    if (address.host.isEmpty()) Text(tr("服务器地址"), style = textStyle, color = PathfinAppearance.textMuted, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/**
 * 连接页底图：品牌标识后方一团主题色柔光，右下一缕更淡的余光，几道细线尾迹呼应图标；克制、不抢地址栏。
 * fadeTo 为底边保留的浓度。
 */
@Composable
private fun ConnectBackdrop(modifier: Modifier = Modifier, fadeTo: Float = 0f) {
    val accent = PathfinAppearance.accent
    // 深色画布上光晕减弱，避免发灰发脏。
    val strength = if (PathfinAppearance.canvas.luminance() < 0.5f) 0.6f else 1f
    Canvas(modifier.clipToBounds().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        fun glow(color: Color, alpha: Float, x: Float, y: Float, radius: Float) {
            val center = Offset(size.width * x, size.height * y)
            val r = size.width * radius
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha * strength), color.copy(alpha = 0f)), center, r), r, center)
        }
        glow(accent, 0.18f, 0.5f, 0.26f, 0.72f)
        glow(BrandBlue, 0.14f, 0.5f, 0.24f, 0.38f)
        glow(accent, 0.08f, 1.0f, 0.95f, 0.7f)
        // 尾迹：三道平行缓弧，细线、低浓度，只作纹理。
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
        repeat(3) { line ->
            val y = size.height * (0.44f + line * 0.035f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(-size.width * 0.1f, y + size.width * 0.10f)
                cubicTo(size.width * 0.30f, y - size.width * 0.06f, size.width * 0.66f, y + size.width * 0.14f, size.width * 1.1f, y - size.width * 0.02f)
            }
            drawPath(path, accent.copy(alpha = (0.16f - line * 0.04f) * strength), style = stroke)
        }
        drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.85f),
            1f to Color.Black.copy(alpha = fadeTo)), blendMode = BlendMode.DstIn)
    }
}

@Composable
private fun AccountStep(
    probe: ServerProbe,
    busy: Boolean,
    error: String?,
    api: JellyfinApi,
    onLogin: (String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(probe.users.firstOrNull()?.name) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf(probe.info.name) }
    val usersAboveFields = remember { probe.users.isNotEmpty() }
    val manual = probe.users.isEmpty() || selected == null
    val chosen = if (manual) username.trim() else selected.orEmpty()
    fun submit() { if (!busy && chosen.isNotBlank()) onLogin(chosen, password, displayName.trim()) }
    StepScaffold(actions = {
        SubmitButton(tr("进入媒体库"), chosen.isNotBlank(), busy, ::submit, Modifier.fillMaxWidth())
        QuietButton(tr("更换服务器"), onBack, enabled = !busy)
    }) {
        ServerHero(probe, api)
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (usersAboveFields) PublicAccountChoices(probe, selected, manual, busy, api) { selected = it }
            GroupCard(Modifier.padding(top = 16.dp)) {
                if (manual) GroupField(username, { if (!busy) username = it }, tr("用户名"), tr("Jellyfin 用户名"), PathfinIcons.User,
                    Modifier.testTag("login-username"), divider = false, readOnly = busy,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
                PasswordField(password, { password = it }, if (manual) tr("密码") else tr("%1\$s 的密码", selected), tr("可留空"), busy,
                    Modifier.testTag("login-password"), divider = manual, imeAction = ImeAction.Done, onDone = ::submit)
                GroupField(displayName, { if (!busy) displayName = it }, tr("在本机显示为"), tr("默认使用服务器名称"), PathfinIcons.Type,
                    Modifier.testTag("server-display-name"), readOnly = busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            }
            // 迟到的可选账号追加在表单之后，不能推移正在输入的用户名与密码。
            if (!usersAboveFields) PublicAccountChoices(probe, selected, manual, busy, api) { selected = it }
            FormFeedback(error, lines = 3, Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp))
        }
    }
}

@Composable
private fun PublicAccountChoices(probe: ServerProbe, selected: String?, manual: Boolean, busy: Boolean,
    api: JellyfinApi, onSelect: (String?) -> Unit) {
    if (probe.users.isEmpty()) return
    Eyebrow(tr("选择账号"), Modifier.padding(start = 6.dp, top = 16.dp))
    Row(Modifier.padding(start = 6.dp, top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        probe.users.forEach { user ->
            UserChoice(user.name, selected == user.name, { if (!busy) onSelect(user.name) }) {
                PublicUserAvatar(probe.config, user, api)
            }
        }
        UserChoice(tr("其他"), manual, { if (!busy) onSelect(null) }) {
            Icon(PathfinIcons.Plus, null, Modifier.size(20.dp), tint = PathfinAppearance.textMuted)
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** 服务器的公开启动图承担首屏颜色；未启用启动图时以柔光底图作底，标题保持同一位置。 */
@Composable
private fun ServerHero(probe: ServerProbe, api: JellyfinApi) {
    val canvas = PathfinAppearance.canvas
    val topInset = AppSafeDrawingInsets.asPaddingValues().calculateTopPadding()
    var splashShown by remember(probe.config.baseUrl) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(240.dp + topInset).testTag("server-account-hero")) {
        ConnectBackdrop(Modifier.matchParentSize().testTag("server-connect-artwork"))
        AsyncImage(api.splashscreenUrl(probe.config), null, imageLoader = LocalPosterLoader.current,
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(), onSuccess = { splashShown = true })
        if (splashShown) Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Transparent, 0.96f to canvas)))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(tr("已连接"), color = PathfinAppearance.textSub)
            Text(probe.info.name.ifBlank { "Jellyfin" }, Modifier.semantics { heading() }, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
                color = PathfinAppearance.textMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf("Jellyfin ${probe.info.version}".trim(), java.net.URI(probe.config.baseUrl).host.orEmpty()).joinToString(" · "),
                fontSize = 11.5.sp, color = PathfinAppearance.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun UserChoice(name: String, selected: Boolean, onClick: () -> Unit, avatar: @Composable BoxScope.() -> Unit) {
    Column(
        Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(58.dp).border(1.5.dp, if (selected) PathfinAppearance.accent else Color.Transparent, CircleShape).padding(3.dp)
                .clip(CircleShape).background(PathfinAppearance.softFill),
            contentAlignment = Alignment.Center,
            content = avatar,
        )
        Text(name, fontSize = 12.sp, color = if (selected) PathfinAppearance.textMain else PathfinAppearance.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 64.dp))
    }
}

@Composable
private fun PublicUserAvatar(config: ServerConfig, user: PublicUser, api: JellyfinApi) {
    Text(user.name.take(1).uppercase(), fontSize = 18.sp, color = PathfinAppearance.textSub)
    api.publicUserImageUrl(config, user, width = 128)?.let { url ->
        AsyncImage(url, null, imageLoader = LocalPosterLoader.current, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

// ───────────────────────── 媒体空间 ─────────────────────────

/** 当前服务器主卡的内容：直接取自已加载的首页数据，服务器页不另发请求。 */
internal data class ServerShowcase(val posters: List<MediaItem>, val resume: MediaItem?)

@Composable
private fun MediaSpace(
    profiles: List<ServerProfile>,
    currentId: String?,
    showcase: ServerShowcase?,
    error: String?,
    api: JellyfinApi,
    onAdd: () -> Unit,
    onOpen: (ServerProfile) -> Unit,
    onMore: (ServerProfile) -> Unit,
    onDismissError: () -> Unit,
) {
    val featured = profiles.firstOrNull { it.id == currentId } ?: profiles.maxBy { it.lastPlayedAt }
    val others = profiles.filter { it.id != featured.id }
    LazyColumn(Modifier.fillMaxSize().testTag("server-list"),
        contentPadding = PaddingValues(bottom = LocalNavigationBottomInset.current + 8.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 14.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("服务器"), Modifier.weight(1f).semantics { heading() }, fontSize = 24.sp, lineHeight = 32.sp,
                    letterSpacing = 0.3.sp, color = PathfinAppearance.textMain)
                PathfinIconButton(PathfinIcons.Plus, tr("添加服务器"), onAdd, Modifier.testTag("server-add"), tint = PathfinAppearance.textMain)
            }
        }
        if (error != null) item(key = "server-error") { ErrorLine(error, onDismissError, Modifier.padding(start = 26.dp, end = 12.dp, bottom = 12.dp)) }
        item(key = featured.id) {
            FeaturedServer(featured, featured.id == currentId, showcase.takeIf { featured.id == currentId }, api,
                { onOpen(featured) }, { onMore(featured) }, Modifier.padding(horizontal = 16.dp))
        }
        if (others.isNotEmpty()) item(key = "others") { Eyebrow(tr("其他服务器"), Modifier.padding(start = 26.dp, top = 30.dp, bottom = 4.dp)) }
        itemsIndexed(others, key = { _, it -> it.id }) { index, profile ->
            if (index > 0) Hairline(Modifier.padding(start = 84.dp, end = 26.dp))
            ServerLine(profile, api, { onOpen(profile) }, { onMore(profile) })
        }
    }
}

private fun subtitle(profile: ServerProfile): String = buildList {
    if (profile.showUserName) add(profile.session.userName)
    if (profile.showLastPlayed && profile.lastPlayedAt > 0) add(tr("上次播放 %1\$s", relativeTime(profile.lastPlayedAt)))
}.joinToString(" · ")

/** 主封面卡：最近入库的海报拼贴承担颜色，下方接续播。尚无首页数据时保留暗色幕布。 */
@Composable
private fun FeaturedServer(
    profile: ServerProfile,
    current: Boolean,
    showcase: ServerShowcase?,
    api: JellyfinApi,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    // 拼贴铺满整张卡并作为卡内采样源；续播条与“正在使用”标签是叠在拼贴上的毛玻璃。
    CompositionLocalProvider(LocalTopBarBlurState provides rememberTopBarBlurState(LocalAppPreferences.current.blurTopBar)) {
    Box(
        modifier.fillMaxWidth().testTag("server-row/${profile.id}")
            .shadow(20.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.14f))
            .clip(shape).background(PathfinAppearance.card).clickable(onClick = onOpen),
    ) {
        Box(Modifier.matchParentSize().clipToBounds().topBarBlurSource(zIndex = -1f)) {
            CoverMosaic(profile.session, showcase?.posters.orEmpty(), api)
        }
        Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f)) {
            // 压暗只盖封面区，续播条下保留拼贴原色供毛玻璃透出。
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.45f), 0.45f to Color.Black.copy(alpha = 0.38f), 1f to Color.Black.copy(alpha = 0.85f))))
            if (current) LivePill(Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profile.displayName, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = subtitle(profile)
                    if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, lineHeight = 17.sp, color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PathfinIconButton(MediaNavigationIcons.More, tr("服务器操作 %1\$s", profile.displayName), onMore, tint = Color.White)
            }
        }
        showcase?.resume?.let { ResumeLine(profile.session, it, api) }
        }
    }
    }
}

@Composable
private fun CoverMosaic(session: Session, posters: List<MediaItem>, api: JellyfinApi) {
    if (posters.isEmpty()) {
        // 没有封面时用带一点强调色的中性深底，与有封面的卡片同一明度。
        val accent = PathfinAppearance.accent
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(Color(0xFF2E2E33), accent, 0.28f), Color(0xFF161618)))))
        return
    }
    Row(
        Modifier.fillMaxSize().blur(5.dp).graphicsLayer { rotationZ = -9f; scaleX = 1.45f; scaleY = 1.45f }
            .background(Color(0xFF161618)),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(5) { column ->
            Column(
                Modifier.weight(1f).wrapContentHeight(Alignment.Top, unbounded = true).offset(y = if (column % 2 == 1) (-28).dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // 续播条也铺在拼贴上，卡片变高，每列多放一张以免旋转后露底。
                repeat(4) { row ->
                    val item = posters[(column * 4 + row) % posters.size]
                    val request = rememberServerImage(session, api.imageUrl(session, item, width = 220), api)
                    // 拼贴降饱和，只作氛围底，不与卡片上的白字抢视线。
                    AsyncImage(request, null, imageLoader = LocalPosterLoader.current, contentScale = ContentScale.Crop,
                        colorFilter = MosaicColorFilter,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2A2926)))
                }
            }
        }
    }
}

private val MosaicColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) })

@Composable
private fun LivePill(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).frostedCard(0.30f, Color.Black, fallback = Color.Black.copy(alpha = 0.30f))
            .border(0.5.dp, Color.White.copy(alpha = 0.22f), CircleShape).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(PathfinAppearance.accentWash.copy(alpha = 1f)))
        Text(tr("正在使用"), fontSize = 11.sp, letterSpacing = 0.5.sp, color = Color.White)
    }
}

@Composable
private fun ResumeLine(session: Session, item: MediaItem, api: JellyfinApi, modifier: Modifier = Modifier) {
    val still = rememberServerImage(session, api.imageUrl(session, item, width = 320, type = "Backdrop") ?: api.imageUrl(session, item, width = 200), api)
    val fraction = if (item.runTimeTicks > 0) (item.positionTicks.toFloat() / item.runTimeTicks).coerceIn(0f, 1f) else 0f
    val remaining = ((item.runTimeTicks - item.positionTicks).coerceAtLeast(0) / TicksPerMinute).coerceAtLeast(1)
    Row(modifier.fillMaxWidth().frostedCard(0.66f, radius = 32.dp)
        .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(still, null, imageLoader = LocalPosterLoader.current, contentScale = ContentScale.Crop,
            modifier = Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(PathfinAppearance.softFill))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Eyebrow(tr("继续观看"))
            Text(resumeTitle(item), fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(PathfinAppearance.segmentTrack)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(PathfinAppearance.accent))
            }
        }
        Text(tr("剩 %1\$s 分", remaining), fontSize = 11.sp, color = PathfinAppearance.textMuted)
    }
}

private fun resumeTitle(item: MediaItem): String {
    val series = item.seriesName?.takeIf(String::isNotBlank) ?: return item.name
    val season = item.parentIndexNumber?.let { tr("第 %1\$s 季", it) }
    val episode = item.indexNumber?.let { tr("第 %1\$s 集", it) }
    return listOfNotNull(series, listOfNotNull(season, episode).joinToString(" ").ifBlank { null }).joinToString(" · ")
}

@Composable
private fun ServerLine(profile: ServerProfile, api: JellyfinApi, onOpen: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("server-row/${profile.id}").clickable(onClick = onOpen)
            .padding(start = 26.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerAvatar(profile, api, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile.displayName, fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = subtitle(profile)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, lineHeight = 17.sp, color = PathfinAppearance.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PathfinIconButton(MediaNavigationIcons.More, tr("服务器操作 %1\$s", profile.displayName), onMore, tint = PathfinAppearance.textMuted)
    }
}

@Composable
private fun ServerAvatar(profile: ServerProfile, api: JellyfinApi, size: Dp) {
    val shape = RoundedCornerShape(size / 4)
    if (profile.icon.isBlank()) {
        AccountAvatar(profile.session, api, Modifier.size(size), shape)
        return
    }
    Box(Modifier.size(size).clip(shape).background(PathfinAppearance.softFill), contentAlignment = Alignment.Center) {
        Text(profile.icon, fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
private fun ErrorLine(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val error = MaterialTheme.colorScheme.error
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(PathfinIcons.Alert, null, Modifier.size(16.dp), tint = error)
        Text(message, Modifier.weight(1f).padding(horizontal = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 13.sp, lineHeight = 19.sp, color = error)
        PathfinIconButton(PathfinIcons.Close, tr("关闭错误提示"), onDismiss, tint = error, size = 36.dp)
    }
}

// ───────────────────────── 操作面板 ─────────────────────────

@Composable
private fun ServerActionSheet(
    profile: ServerProfile,
    api: JellyfinApi,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onUpdate: (ServerProfile) -> Unit,
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = PathfinAppearance.canvas,
        scrimColor = Color.Black.copy(alpha = 0.26f),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(PathfinAppearance.segmentTrack)) },
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                ServerAvatar(profile, api, 44.dp)
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(profile.session.server.baseUrl, fontSize = 11.sp, color = PathfinAppearance.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SheetGroup {
                SheetRow(PathfinIcons.Type, tr("显示名称"), divider = false) { onAction("name") }
                SheetRow(PathfinIcons.Smile, tr("自定义图标")) { onAction("icon") }
                SheetRow(PathfinIcons.Edit, tr("编辑连接")) { onAction("edit") }
                SheetRow(PathfinIcons.Route, tr("添加备用地址")) { onAction("address") }
                if (profile.addresses.isNotEmpty()) SheetRow(PathfinIcons.Link, tr("连接地址"),
                    tr("%1\$s 个", (listOf(profile.session.server) + profile.addresses).distinctBy { it.baseUrl }.size)) { onAction("addresses") }
                SheetRow(PathfinIcons.Key, tr("修改密码")) { onAction("password") }
            }
            SheetGroup {
                SheetToggle(PathfinIcons.User, tr("显示用户名"), profile.showUserName, divider = false) { onUpdate(profile.copy(showUserName = it)) }
                SheetToggle(PathfinIcons.Clock, tr("显示上次播放时间"), profile.showLastPlayed) { onUpdate(profile.copy(showLastPlayed = it)) }
            }
            SheetGroup {
                SheetRow(PathfinIcons.Trash, tr("从本机移除"), divider = false, danger = true) { onAction("delete") }
            }
        }
    }
}

@Composable
private fun SheetGroup(content: @Composable ColumnScope.() -> Unit) {
    GroupCard(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), content = content)
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, detail: String? = null, divider: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val tone = if (danger) MaterialTheme.colorScheme.error else PathfinAppearance.textMain
    Row(
        Modifier.fillMaxWidth().groupSeparator(PathfinAppearance.divider, divider).clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(icon, if (danger) tone else PathfinAppearance.textSub)
        Text(label, Modifier.weight(1f).padding(start = 14.dp), fontSize = 14.5.sp, color = tone)
        if (detail != null) Text(detail, Modifier.padding(end = 4.dp), fontSize = 13.sp, color = PathfinAppearance.textMuted)
        if (!danger) Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
    }
}

@Composable
private fun SheetToggle(icon: ImageVector, label: String, checked: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().groupSeparator(PathfinAppearance.divider, divider).toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(icon)
        Text(label, Modifier.weight(1f).padding(start = 14.dp), fontSize = 14.5.sp, color = PathfinAppearance.textMain)
        Icon(PathfinIcons.Check, null, Modifier.size(18.dp), tint = if (checked) PathfinAppearance.accent else Color.Transparent)
    }
}

// ───────────────────────── 弹窗 ─────────────────────────

@Composable
internal fun ReauthenticateServerDialog(
    profile: ServerProfile,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // 密码仅留在当前弹窗内存中，不进入 SavedState、服务器资料或磁盘。
    var password by remember(profile.id) { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val requestBusy = busy || submitted
    LaunchedEffect(busy, submitted) {
        if (submitted && !busy) { withFrameNanos { }; submitted = false }
    }
    fun submit() { if (!busy && !submitted) { submitted = true; onSubmit(password) } }
    ServerDialogFrame(tr("重新登录"), requestBusy, error, onDismiss, onSubmit = ::submit, submitLabel = tr("登录"),
        subtitle = tr("登录状态已失效，请输入密码继续")) {
        Column(Modifier.padding(top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile.displayName, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(profile.session.userName, fontSize = 13.sp, lineHeight = 19.sp, color = PathfinAppearance.textSub)
            Text(profile.session.server.baseUrl, fontSize = 11.5.sp, lineHeight = 17.sp,
                color = PathfinAppearance.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        GroupCard(container = PathfinAppearance.canvas) {
            PasswordField(password, { password = it }, tr("密码"), tr("可留空"), requestBusy, Modifier.testTag("reauth-password"),
                divider = false, imeAction = ImeAction.Done, onDone = ::submit)
        }
    }
}

/** 编辑连接与添加备用地址：与连接页共用同一个地址输入。 */
@Composable
private fun ServerAddressDialog(
    kind: String,
    profile: ServerProfile,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (ServerConfig, String) -> Unit,
) {
    var address by rememberSaveable(stateSaver = ServerAddressInputSaver) {
        mutableStateOf(if (kind == "edit") runCatching { ServerAddressInputState.fromConfig(profile.session.server) }.getOrNull() ?: ServerAddressInputState()
            else ServerAddressInputState())
    }
    var displayName by rememberSaveable { mutableStateOf(if (kind == "edit") profile.displayName else "") }
    var allowHttp by rememberSaveable { mutableStateOf(kind == "edit" && profile.session.server.allowInsecureHttp) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy) return
        try {
            val config = address.toServerConfig(allowHttp)
            localError = null
            onSubmit(config, displayName.trim())
        } catch (error: IllegalArgumentException) { localError = error.message ?: tr("请检查服务器地址") }
    }
    ServerDialogFrame(if (kind == "edit") tr("编辑连接") else tr("添加备用地址"), busy, localError ?: error, onDismiss, ::submit,
        submitEnabled = address.host.isNotBlank(), submitLabel = if (kind == "edit") tr("保存") else tr("添加"),
        subtitle = if (kind == "address") tr("同一台服务器的其他访问地址，例如外网域名") else null) {
        ServerAddressInput(address, { address = it }, allowHttp, { allowHttp = it }, busy, onDone = ::submit,
            container = PathfinAppearance.canvas)
        if (kind == "edit") GroupCard(Modifier.padding(top = 14.dp), container = PathfinAppearance.canvas) {
            GroupField(displayName, { if (!busy) displayName = it }, tr("在本机显示为"), tr("默认使用服务器名称"), PathfinIcons.Type,
                divider = false, readOnly = busy)
        }
    }
}

/**
 * 显示名称：上方是服务器列表里的实时预览（头像、名称、地址与账号），下方一个独立输入框；
 * 打开即聚焦，回车保存，有内容时可一键清空。只改本机显示，不会出错，因此不预留错误区。
 */
@Composable
private fun DisplayNameDialog(profile: ServerProfile, api: JellyfinApi, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(profile.displayName) }
    val valid = name.isNotBlank()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() { if (valid) onSave(name.trim()) }
    val host = remember(profile.session.server.baseUrl) { runCatching { java.net.URI(profile.session.server.baseUrl).host }.getOrNull().orEmpty() }
    AppDialog(onDismiss, tr("显示名称"), subtitle = tr("只改本机服务器列表里的名字，不影响服务器"), actions = {
        DialogAction(tr("取消"), onDismiss, Modifier.weight(1f))
        DialogAction(tr("保存"), ::submit, Modifier.weight(1f).testTag("server-name-save"), enabled = valid, role = DialogActionRole.Primary)
    }) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.softFill)
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerAvatar(profile, api, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name.trim().ifEmpty { tr("未命名") }, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = if (valid) PathfinAppearance.textMain else PathfinAppearance.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(host, profile.session.userName).filter(String::isNotBlank).joinToString(" · "), fontSize = 12.sp, lineHeight = 17.sp,
                    color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val shape = RoundedCornerShape(14.dp)
        val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, color = PathfinAppearance.textMain)
        BasicTextField(name, { if (it.length <= 64) name = it },
            Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = tr("显示名称") }.testTag("server-name-input"),
            singleLine = true, textStyle = textStyle, cursorBrush = SolidColor(PathfinAppearance.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(PathfinAppearance.card)
                    .border(1.dp, if (focused) PathfinAppearance.accent.copy(alpha = 0.6f) else PathfinAppearance.divider, shape)
                    .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (name.isEmpty()) Text(host.ifEmpty { tr("为服务器起个名字") }, style = textStyle, color = PathfinAppearance.textMuted, maxLines = 1)
                        inner()
                    }
                    if (name.isNotEmpty()) PathfinIconButton(PathfinIcons.Close, tr("清空名称"), { name = "" }, tint = PathfinAppearance.textMuted, size = 40.dp)
                }
            })
    }
}

@Composable
private fun ServerDialogFrame(
    title: String,
    busy: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean = true,
    submitLabel: String = tr("保存"),
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppDialog({ if (!busy) onDismiss() }, title, subtitle = subtitle, actions = {
        DialogAction(tr("取消"), onDismiss, Modifier.weight(1f), enabled = !busy)
        SubmitButton(submitLabel, submitEnabled, busy, onSubmit, Modifier.weight(1f))
    }) {
        content()
        FormFeedback(error, lines = 2, Modifier.padding(top = 10.dp))
    }
}

/** 只延迟进度图标：快速响应不闪现，已经出现的图标至少显示 300 ms；请求中保持墨色外观。 */
@Composable
private fun SubmitButton(label: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var progressVisible by remember { mutableStateOf(false) }
    var progressCanHide by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy && !progressVisible) {
            delay(200)
            progressCanHide = false
            progressVisible = true
        }
    }
    LaunchedEffect(progressVisible) {
        if (progressVisible) { delay(300); progressCanHide = true }
    }
    LaunchedEffect(busy, progressCanHide) {
        if (!busy && progressCanHide) progressVisible = false
    }
    InkButton(onClick, modifier.testTag("login-submit").semantics { if (busy) stateDescription = tr("正在处理") },
        enabled = enabled && !busy, muted = !enabled) {
        ButtonLabel(label, Modifier.padding(horizontal = 22.dp).testTag("login-submit-label"))
        if (progressVisible) PathfinSpinner(LocalContentColor.current,
            Modifier.align(Alignment.CenterEnd).size(16.dp).testTag("login-progress"))
    }
}

/** 固定高度的错误区，随系统字号缩放；长文本在区域内滚动，不推动操作按钮。出现错误时滚入视野。 */
@Composable
private fun FormFeedback(error: String?, lines: Int, modifier: Modifier = Modifier) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) { withFrameNanos { }; requester.bringIntoView() }
    }
    val height = with(LocalDensity.current) { 20.sp.toDp() * lines }
    Box(modifier.fillMaxWidth().height(height).testTag("server-form-error")
        .bringIntoViewRequester(requester).verticalScroll(rememberScrollState())) {
        if (error != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PathfinIcons.Alert, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
            Text(error, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    GroupField(value, { if (!busy) onValueChange(it) }, label, placeholder, PathfinIcons.Key, modifier, divider = divider, readOnly = busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            PathfinIconButton(if (visible) PathfinIcons.EyeOff else PathfinIcons.Eye,
                if (visible) tr("隐藏密码") else tr("显示密码"), { visible = !visible }, size = 32.dp, tint = PathfinAppearance.textMuted)
        })
}

/** 与实际表单共用纯解析规则；分离的协议、端口、路径控件允许覆盖粘贴值。 */
internal fun serverConfigFromFields(host: String, scheme: String, port: String, path: String, allowHttp: Boolean): ServerConfig {
    return ServerAddressInputState(host, scheme, port, path).toServerConfig(allowHttp)
}

private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> tr("刚刚")
        minutes < 60 -> tr("%1\$s 分钟前", minutes)
        minutes < 24 * 60 -> tr("%1\$s 小时前", minutes / 60)
        else -> tr("%1\$s 天前", minutes / (24 * 60))
    }
}

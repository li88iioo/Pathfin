package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.SafDocumentTransfer
import dev.pathfin.android.data.SubtitleManagementApi
import dev.pathfin.android.domain.ManagedSubtitle
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.RemoteSubtitle
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.SubtitleDocument
import dev.pathfin.android.domain.SubtitleLanguages
import dev.pathfin.android.domain.SubtitleUploadFile
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情页管理服务器字幕；播放器只打开在线搜索，本机导入由播放器字幕面板直接提供。 */
@Composable
internal fun SubtitleManagementSheet(
    session: Session,
    item: MediaItem,
    api: JellyfinApi,
    onDismiss: () -> Unit,
    onChanged: (MediaItem) -> Unit,
    verificationScope: CoroutineScope,
    onAuthExpired: () -> Unit = {},
    sourceId: String? = null,
    playerMode: Boolean = false,
) {
    key(session.server, session.serverId, session.userId, session.accessToken, session.deviceId, item.id, sourceId) {
        val content: @Composable () -> Unit = {
            SubtitleManagementContent(session, item, api, onDismiss, onChanged, verificationScope, onAuthExpired, sourceId, playerMode)
        }
        if (playerMode) CompositionLocalProvider(LocalPlaybackFullscreen provides true) {
            PathfinTheme(ThemePreference.DARK, LocalAppPreferences.current.accentColor, content = content)
        } else content()
    }
}

private const val SubtitleVerificationAttempts = 15
private const val SubtitleVerificationIntervalMs = 2_000L

private enum class SubtitlePage { TRACKS, SEARCH, UPLOAD }

private class SubtitleVerification(
    val success: String,
    val expected: (SubtitleDocument) -> Boolean,
    val remoteId: String?,
) {
    var checking by mutableStateOf(false)
    var unconfirmed by mutableStateOf(false)
    var readError by mutableStateOf<String?>(null)
    var job: Job? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleManagementContent(session: Session, item: MediaItem, api: JellyfinApi, onDismiss: () -> Unit,
    onChanged: (MediaItem) -> Unit, verificationScope: CoroutineScope, onAuthExpired: () -> Unit, sourceId: String?, playerMode: Boolean) {
    val management = remember(api) { SubtitleManagementApi(api) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val feedback = remember { SnackbarHostState() }
    var document by remember { mutableStateOf<SubtitleDocument?>(null) }
    var page by remember { mutableStateOf(if (playerMode) SubtitlePage.SEARCH else SubtitlePage.TRACKS) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var pendingVerification by remember { mutableStateOf<SubtitleVerification?>(null) }
    var authenticationExpired by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf("zho") }
    var forced by remember { mutableStateOf(false) }
    var hearingImpaired by remember { mutableStateOf(false) }
    var uploadFile by remember { mutableStateOf<SubtitleUploadFile?>(null) }
    var searchLanguage by remember { mutableStateOf("zho") }
    var perfectMatch by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchedLanguage by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<RemoteSubtitle>>(emptyList()) }
    var submittedRemote by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRemote by remember { mutableStateOf<RemoteSubtitle?>(null) }
    var selectedDelete by remember { mutableStateOf<ManagedSubtitle?>(null) }
    val tracksList = rememberLazyListState()
    val searchList = rememberLazyListState()
    val uploadList = rememberLazyListState()
    val busy = writing || preparing
    val canManage = !authenticationExpired && document?.let { it.permissions.canManage && it.isDefaultSource } == true

    fun message(text: String, failure: Boolean = false) {
        scope.launch {
            feedback.currentSnackbarData?.dismiss()
            feedback.showSnackbar(text, withDismissAction = failure,
                duration = if (failure) SnackbarDuration.Indefinite else SnackbarDuration.Short)
        }
    }
    fun error(failure: Exception): String {
        if (failure is ApiException && failure.statusCode == 401 && !authenticationExpired) {
            authenticationExpired = true
            pendingVerification?.let { pending ->
                pending.unconfirmed = true
                pending.job?.cancel()
            }
            onAuthExpired()
        }
        return when (failure) {
            is ApiException -> failure.message ?: tr("服务器请求失败，请重试")
            is IllegalArgumentException -> failure.message ?: tr("字幕信息无效，请重新选择")
            else -> tr("字幕操作失败，请重试")
        }
    }
    fun publish(latest: SubtitleDocument) {
        val previous = document
        document = latest
        // 服务器先返回 204，再异步扫描字幕。检查旧列表不能触发播放器重建并丢失待确认状态。
        if (previous != null && previous.tracks.associate { it.index to it.stream } != latest.tracks.associate { it.index to it.stream })
            onChanged(latest.item)
    }
    suspend fun read() {
        val latest = management.document(session, item.id, sourceId)
        currentCoroutineContext().ensureActive()
        publish(latest)
    }
    fun ownsVerification(pending: SubtitleVerification, job: Job): Boolean =
        pendingVerification === pending && pending.job === job && job.isActive
    suspend fun checkVerification(pending: SubtitleVerification, job: Job): Boolean {
        if (!ownsVerification(pending, job)) return true
        pending.checking = true
        try {
            val latest = management.document(session, item.id, sourceId)
            currentCoroutineContext().ensureActive()
            // 结束等待或重新检查后，旧请求即使迟到也不能发布数据、确认或解锁新操作。
            if (!ownsVerification(pending, job)) return true
            publish(latest)
            pending.readError = null
            if (pending.expected(latest)) {
                pendingVerification = null
                pending.remoteId?.let { submittedRemote += it }
                message(pending.success)
                return true
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (!ownsVerification(pending, job)) return true
            pending.unconfirmed = true
            pending.readError = error(failure)
            if (authenticationExpired) return true
        } finally {
            if (pendingVerification === pending && pending.job === job) pending.checking = false
        }
        return false
    }
    // 确认任务属于调用方作用域；关闭面板仍能确认迟到的轨道，但不会重复写入。
    fun verify(pending: SubtitleVerification, poll: Boolean) {
        pending.job?.cancel()
        pending.checking = true
        val job = verificationScope.launch(start = CoroutineStart.LAZY) {
            val owner = checkNotNull(currentCoroutineContext()[Job])
            if (checkVerification(pending, owner)) return@launch
            if (poll) repeat(SubtitleVerificationAttempts) {
                delay(SubtitleVerificationIntervalMs)
                if (checkVerification(pending, owner)) return@launch
            }
            if (ownsVerification(pending, owner)) pending.unconfirmed = true
        }
        pending.job = job
        job.start()
    }
    fun endVerification(pending: SubtitleVerification) {
        if (pendingVerification !== pending) return
        pendingVerification = null
        pending.job?.cancel()
        message(tr("已结束等待，服务器可能仍在处理该请求"))
    }
    fun reload() {
        if (loading || writing || preparing || authenticationExpired) return
        val pending = pendingVerification
        if (pending != null) {
            if (!pending.checking) verify(pending, poll = pending.job?.isActive == true)
            return
        }
        loading = true
        readError = null
        scope.launch {
            try { read() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                readError = error(failure)
            } finally { loading = false }
        }
    }
    fun write(success: String, expectedChange: (SubtitleDocument) -> Boolean, remoteId: String? = null,
        operation: suspend () -> Unit) {
        if (writing || preparing || loading || pendingVerification != null || authenticationExpired) return
        writing = true
        scope.launch {
            try {
                operation()
                val pending = SubtitleVerification(success, expectedChange, remoteId)
                pendingVerification = pending
                readError = null
                message(tr("服务器已接受请求，正在等待字幕更新"))
                verify(pending, poll = true)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message(error(failure), true)
            } finally { writing = false }
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy && canManage) {
            preparing = true
            scope.launch {
                try {
                    require(uri.scheme == "content") { tr("请通过系统文件选择器选择字幕") }
                    val bytes = SafDocumentTransfer.transfer { transfer ->
                        transfer.readBytes(context, uri, SubtitleUploadFile.MAX_BYTES, tr("字幕文件不能超过 8 MB"))
                    }
                    uploadFile = withContext(Dispatchers.Default) { SubtitleUploadFile.prepare(bytes) }
                    forced = false
                    hearingImpaired = false
                    page = SubtitlePage.UPLOAD
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    message(error(failure), true)
                } finally { preparing = false }
            }
        }
    }
    fun search() {
        val current = document ?: return
        if (searching || busy || !canManage) return
        val requestedLanguage = searchLanguage
        val requestedPerfect = perfectMatch
        searching = true
        searchError = null
        scope.launch {
            try {
                remote = management.search(session, current, requestedLanguage, requestedPerfect)
                searchedLanguage = requestedLanguage
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                searchError = error(failure)
            } finally { searching = false }
        }
    }
    LaunchedEffect(Unit) {
        try { read() }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            readError = error(failure)
        } finally { loading = false }
    }
    // 进入搜索页即按默认语言搜索，不再多点一次“搜索字幕”；换语言后仍由按钮重新搜索。
    LaunchedEffect(page, canManage) { if (page == SubtitlePage.SEARCH && canManage && searchedLanguage == null) search() }
    BackHandler(!playerMode && (writing || page != SubtitlePage.TRACKS)) { if (!writing) page = SubtitlePage.TRACKS }
    val restriction = when {
        loading && document == null -> tr("正在读取服务器字幕…")
        document?.isDefaultSource == false -> tr("当前为其他媒体版本；服务器字幕操作用于条目的默认版本。")
        document?.permissions?.canManage == false -> tr("此账号没有服务器字幕管理权限。")
        else -> null
    }
    fun LazyListScope.statusItems() {
        if (readError != null) item(key = "read-error") {
            SubtitleNotice(readError!!, tr("重试读取"), !loading && !busy, ::reload)
        }
        pendingVerification?.let { pending ->
            item(key = "verification") {
                val status = if (pending.unconfirmed) tr("尚未确认服务器字幕更新") else tr("等待服务器更新字幕列表")
                SubtitleNotice(listOfNotNull(status, pending.readError).joinToString(tr("。")), tr("检查更新"),
                    !loading && !busy && !pending.checking && !authenticationExpired, ::reload,
                    onEndWaiting = { endVerification(pending) }, endWaitingEnabled = !busy)
            }
        }
    }
    val title = when (page) { SubtitlePage.TRACKS -> tr("字幕"); SubtitlePage.SEARCH -> tr("在线搜索"); SubtitlePage.UPLOAD -> tr("上传字幕") }
    val caption = if (writing) tr("正在更新服务器字幕…") else item.name
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().testTag("subtitle-management")) {
            Column(Modifier.fillMaxSize()) {
                if (!playerMode) Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page != SubtitlePage.TRACKS) PathfinIconButton(PathfinIcons.Back, tr("返回字幕"), { page = SubtitlePage.TRACKS }, enabled = !writing)
                    Column(Modifier.weight(1f).padding(start = if (page == SubtitlePage.TRACKS) 12.dp else 4.dp)) {
                        Text(title,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                        Text(caption, fontSize = 12.sp, color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PathfinIconButton(PathfinIcons.Close, tr("关闭字幕管理"), onDismiss, enabled = !writing)
                } else Text(caption, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp,
                    color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("subtitle-management-content"),
                    state = when (page) { SubtitlePage.TRACKS -> tracksList; SubtitlePage.SEARCH -> searchList; SubtitlePage.UPLOAD -> uploadList },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (page) {
                        SubtitlePage.TRACKS -> {
                            item(key = "actions") {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SubtitleAction(if (preparing) tr("正在读取…") else tr("上传字幕"), PathfinIcons.Upload, canManage && !busy && !loading && pendingVerification == null,
                                        Modifier.weight(1f).testTag("subtitle-upload-file")) { uploadPicker.launch(arrayOf("*/*")) }
                                    SubtitleAction(tr("在线搜索"), PathfinIcons.Search, canManage && !busy,
                                        Modifier.weight(1f).testTag("subtitle-open-search")) { page = SubtitlePage.SEARCH }
                                }
                            }
                            item(key = "permissions") {
                                Text(restriction ?: tr("上传和在线搜索会将字幕保存到 Jellyfin。"), Modifier.heightIn(min = 34.dp),
                                    fontSize = 12.sp, lineHeight = 18.sp, color = PathfinAppearance.textMuted)
                            }
                            statusItems()
                            item(key = "track-heading") { Eyebrow(tr("服务器字幕")) }
                            val tracks = document?.tracks.orEmpty()
                            if (tracks.isEmpty()) item(key = "empty") {
                                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                    Text(if (loading) tr("正在读取字幕轨道…") else tr("没有字幕轨道"), fontSize = 14.sp, color = PathfinAppearance.textMuted)
                                }
                            }
                            items(tracks, key = { "${it.sourceId}/${it.index}" }) { track ->
                                GroupCard {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        GroupIcon(PathfinIcons.Subtitles)
                                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                                            Text(track.title, fontSize = 14.sp, lineHeight = 21.sp, color = PathfinAppearance.textMain,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(trackDescription(track), fontSize = 11.5.sp, lineHeight = 18.sp, color = PathfinAppearance.textSub)
                                        }
                                        if (track.isExternal && document?.permissions?.canDelete == true && document?.isDefaultSource == true)
                                            PathfinIconButton(PathfinIcons.Trash, tr("删除%1\$s", track.title), { selectedDelete = track }, enabled = !authenticationExpired && !busy && !loading && pendingVerification == null,
                                                modifier = Modifier.testTag("subtitle-delete/${track.index}"))
                                    }
                                }
                            }
                        }
                        SubtitlePage.SEARCH -> {
                            restriction?.let { text -> item(key = "search-restriction") {
                                Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = PathfinAppearance.textMuted)
                            } }
                            statusItems()
                            item(key = "search-controls") {
                                GroupCard {
                                    SubtitleLanguageRow(searchLanguage, { searchLanguage = it }, canManage && !searching && !busy, allowUndefined = false)
                                    GroupToggle(PathfinIcons.CheckCircle, tr("仅精准匹配"), perfectMatch, canManage && !searching && !busy, { perfectMatch = it })
                                }
                            }
                            item(key = "search-button") {
                                InkButton(::search, Modifier.fillMaxWidth().testTag("subtitle-search-submit"),
                                    enabled = canManage && !searching && !busy && searchLanguage.matches(Regex("[a-z]{3}")) && searchLanguage != "und") {
                                    ButtonLabel(if (searching) tr("正在搜索…") else tr("搜索字幕"))
                                }
                            }
                            if (searchError != null) item(key = "search-error") { SubtitleNotice(searchError!!, tr("重试"), !searching && !busy, ::search) }
                            if (searchedLanguage != null) item(key = "search-heading") {
                                Eyebrow(tr("%1\$s · %2\$s 个结果", languageLabel(searchedLanguage!!), remote.size))
                            }
                            if (remote.isEmpty()) item(key = "search-empty") {
                                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                    Text(if (searchedLanguage == null) tr("通过 Jellyfin 已安装的字幕提供商搜索") else tr("未找到字幕，可更换语言或关闭精准匹配"),
                                        fontSize = 13.sp, lineHeight = 20.sp, color = PathfinAppearance.textMuted)
                                }
                            }
                            items(remote, key = { it.id }) { result ->
                                val submitted = result.id in submittedRemote
                                GroupCard {
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius))
                                        .clickable(enabled = canManage && !busy && !loading && !submitted && pendingVerification == null, role = Role.Button) { selectedRemote = result }
                                        .padding(16.dp).testTag("subtitle-search-result"), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                            Text(result.name, fontSize = 14.sp, lineHeight = 21.sp, color = PathfinAppearance.textMain, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            Text(remoteDescription(result), Modifier.padding(top = 4.dp), fontSize = 11.5.sp, lineHeight = 18.sp, color = PathfinAppearance.textSub)
                                        }
                                        Icon(if (submitted) PathfinIcons.Check else PathfinIcons.Download, null, Modifier.size(20.dp), tint = PathfinAppearance.accent)
                                    }
                                }
                            }
                        }
                        SubtitlePage.UPLOAD -> {
                            item(key = "upload-file") {
                                GroupCard {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        GroupIcon(PathfinIcons.Subtitles)
                                        Column(Modifier.weight(1f)) {
                                            Text(tr("%1\$s 字幕", uploadFile?.format?.uppercase().orEmpty()), fontSize = 15.sp, color = PathfinAppearance.textMain)
                                            Text(tr("已读取本地文件 · 上传到 Jellyfin"), fontSize = 12.sp, color = PathfinAppearance.textSub)
                                        }
                                    }
                                }
                            }
                            item(key = "upload-options") {
                                GroupCard {
                                    SubtitleLanguageRow(language, { language = it }, !busy, allowUndefined = true)
                                    GroupToggle(PathfinIcons.Subtitles, tr("强制字幕"), forced, !busy, { forced = it })
                                    GroupToggle(PathfinIcons.Audio, tr("听障辅助字幕"), hearingImpaired, !busy, { hearingImpaired = it })
                                }
                            }
                            item(key = "upload-button") {
                                InkButton({
                                    val current = document ?: return@InkButton
                                    val file = uploadFile ?: return@InkButton
                                    val before = current.tracks.map { it.stream }
                                    write(tr("字幕已上传"), { latest -> latest.tracks.any { it.isExternal && it.stream !in before } }) {
                                        management.upload(session, current, file, language, forced, hearingImpaired)
                                        uploadFile = null
                                        page = SubtitlePage.TRACKS
                                    }
                                }, Modifier.fillMaxWidth().testTag("subtitle-upload-submit"), enabled = !busy && !loading && pendingVerification == null && canManage && uploadFile != null && language.matches(Regex("[a-z]{3}"))) {
                                    ButtonLabel(if (writing) tr("正在上传…") else tr("上传字幕"))
                                }
                            }
                        }
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(48.dp)) }
                }
            }
            PathfinSnackbarHost(feedback, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (playerMode) PlayerSidePanel(title, { if (!writing) onDismiss() }, closeLabel = tr("关闭在线搜索"), closeTag = "subtitle-management-close") {
        Box(Modifier.weight(1f)) { body() }
    } else AppModalBottomSheet(onDismissRequest = { if (!writing) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetGesturesEnabled = !writing,
        dragHandle = null, containerColor = PathfinAppearance.canvas, contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        // 高度不依赖状态栏和数据量；权限读取、操作反馈均不改变弹层锚点。
        Box(Modifier.fillMaxWidth().fillMaxHeight(.92f).windowInsetsPadding(AppSafeDrawingInsets)) { body() }
    }
    selectedRemote?.let { selected ->
        AppAlertDialog(onDismissRequest = { selectedRemote = null }, title = { Text(tr("添加字幕")) },
            text = { Text(tr("%1\$s\n将由 Jellyfin 下载并保存到服务器。", selected.name)) },
            dismissButton = { DialogAction(tr("取消"), { selectedRemote = null }) }, confirmButton = {
                DialogAction(tr("添加到服务器"), {
                    selectedRemote = null
                    val current = document ?: return@DialogAction
                    val before = current.tracks.map { it.stream }
                    write(tr("字幕已添加"), { latest -> latest.tracks.any { it.isExternal && it.stream !in before } }, remoteId = selected.id) {
                        management.download(session, current, selected)
                    }
                }, Modifier.testTag("subtitle-download-confirm"), role = DialogActionRole.Primary)
            })
    }
    selectedDelete?.let { selected ->
        AppAlertDialog(onDismissRequest = { selectedDelete = null }, title = { Text(tr("删除字幕")) },
            text = { Text(tr("将从服务器删除“%1\$s”的外部字幕文件，无法撤销。", selected.title)) },
            dismissButton = { DialogAction(tr("取消"), { selectedDelete = null }) }, confirmButton = {
                DialogAction(tr("删除"), {
                    selectedDelete = null
                    val current = document ?: return@DialogAction
                    write(tr("字幕已删除"), { latest -> latest.tracks.none { it.index == selected.index && it.stream == selected.stream } }) {
                        management.delete(session, current, selected)
                    }
                }, Modifier.testTag("subtitle-delete-confirm"), role = DialogActionRole.Danger)
            })
    }
}

@Composable
private fun SubtitleAction(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 86.dp).clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) PathfinAppearance.accent else PathfinAppearance.textMuted)
        Text(label, fontSize = 12.5.sp, lineHeight = 18.sp, color = if (enabled) PathfinAppearance.textMain else PathfinAppearance.textMuted, maxLines = 1)
    }
}

@Composable
private fun SubtitleLanguageRow(code: String, onChange: (String) -> Unit, enabled: Boolean, allowUndefined: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).clickable(enabled = enabled, role = Role.Button) { menu = true }
            .heightIn(min = 60.dp).padding(horizontal = 16.dp).testTag("subtitle-language"), verticalAlignment = Alignment.CenterVertically) {
            GroupIcon(PathfinIcons.Globe)
            Text(languageLabel(code), Modifier.weight(1f).padding(start = 14.dp), fontSize = 14.5.sp, color = PathfinAppearance.textMain)
            Icon(PathfinIcons.ChevronDown, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
        }
        AppDropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
            SubtitleLanguages.common.filter { allowUndefined || it.code != "und" }.forEach { language ->
                MenuRow(language.label, { menu = false; custom = false; onChange(language.code) }, checked = code == language.code,
                    modifier = Modifier.testTag("subtitle-language/${language.code}"))
            }
            MenuRow(tr("其他语言"), { menu = false; custom = true; onChange("") })
        }
    }
    if (custom) GroupField(code, { value -> if (enabled) onChange(value.filter(Char::isLetter).take(3).lowercase()) },
        tr("语言代码"), tr("ISO 639-2，如 ita"), PathfinIcons.Globe, readOnly = !enabled)
}

@Composable
private fun SubtitleNotice(message: String, action: String, enabled: Boolean, onAction: () -> Unit,
    onEndWaiting: (() -> Unit)? = null, endWaitingEnabled: Boolean = false) {
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message, fontSize = 12.5.sp, lineHeight = 19.sp, color = PathfinAppearance.textSub)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onEndWaiting != null) QuietButton(tr("结束等待"), onEndWaiting, enabled = endWaitingEnabled, color = PathfinAppearance.textSub)
                QuietButton(action, onAction, enabled = enabled, color = PathfinAppearance.accent)
            }
        }
    }
}

private fun trackDescription(track: ManagedSubtitle): String = listOfNotNull(
    if (track.isExternal) tr("外挂") else tr("内置"), track.format?.uppercase(), track.language,
    tr("强制").takeIf { track.isForced }, tr("听障辅助").takeIf { track.isHearingImpaired },
).joinToString(" · ")

private fun remoteDescription(subtitle: RemoteSubtitle): String = listOfNotNull(
    subtitle.provider, subtitle.format?.uppercase(), subtitle.language,
    tr("精准匹配").takeIf { subtitle.isHashMatch }, tr("强制").takeIf { subtitle.isForced }, tr("听障辅助").takeIf { subtitle.isHearingImpaired },
    subtitle.downloadCount?.let { tr("%1\$s 次下载", it) },
).joinToString(" · ")

private fun languageLabel(code: String): String = SubtitleLanguages.common.firstOrNull { it.code == code }?.label
    ?: code.takeIf(String::isNotBlank)?.uppercase() ?: tr("其他语言")

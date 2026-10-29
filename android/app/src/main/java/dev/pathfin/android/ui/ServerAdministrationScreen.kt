package dev.pathfin.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.ServerAdministrationApi
import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 服务端管理与本机账号管理分开；切账号或离开页面自动取消旧会话请求。 */
@Composable
fun ServerAdministrationScreen(session: Session, api: JellyfinApi, onBack: () -> Unit, onAuthExpired: () -> Unit = {}) {
    key(session) { ServerAdministrationContent(session, api, onBack, onAuthExpired) }
}

@Composable
private fun ServerAdministrationContent(session: Session, api: JellyfinApi, onBack: () -> Unit, onAuthExpired: () -> Unit) {
    val administration = remember(api) { ServerAdministrationApi(api) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var page by remember { mutableStateOf("overview") }
    var info by remember { mutableStateOf<ServerAdministrationInfo?>(null) }
    var tasks by remember { mutableStateOf<List<ServerScheduledTask>>(emptyList()) }
    var taskReadVersion by remember { mutableLongStateOf(0L) }
    var activities by remember { mutableStateOf<List<ServerActivity>>(emptyList()) }
    var activityTotal by remember { mutableIntStateOf(0) }
    var activityNextIndex by remember { mutableIntStateOf(0) }
    var activityHasMore by remember { mutableStateOf(false) }
    var activityAppending by remember { mutableStateOf(false) }
    var activityFailed by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<ServerLogFile>>(emptyList()) }
    var logsLoaded by remember { mutableStateOf(false) }
    var logFile by remember { mutableStateOf<ServerLogFile?>(null) }
    var logContent by remember { mutableStateOf<ServerLogContent?>(null) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingTask by remember { mutableStateOf<ServerScheduledTask?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var restartPending by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    fun failure(exception: Exception, committed: Boolean = false) {
        if (exception is CancellationException) throw exception
        if (exception is ApiException && exception.statusCode == 401) onAuthExpired()
        error = if (committed) tr("操作已接受，但重新读取失败。请刷新状态，不要重复提交。") else administrationError(exception)
    }
    suspend fun readTasks(committed: Boolean = false) {
        val version = ++taskReadVersion
        try {
            val incoming = administration.tasks(session)
            if (version == taskReadVersion) tasks = incoming
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            if (version == taskReadVersion) failure(exception, committed)
        }
    }
    suspend fun readActivities(append: Boolean) {
        val readTarget = activityNextIndex.coerceAtLeast(ACTIVITY_PAGE_SIZE)
        var offset = if (append) activityNextIndex else 0
        var incoming = if (append) activities else emptyList()
        var readTotal: Int? = null
        var more: Boolean
        do {
            val result = administration.activity(session, offset, ACTIVITY_PAGE_SIZE)
            if (!append && ((readTotal != null && readTotal != result.total) ||
                (result.items.isEmpty() && offset < minOf(readTarget, result.total)))) {
                throw ApiException(null, tr("活动日志已变化，请下拉刷新重试"))
            }
            incoming = (incoming + result.items).distinctBy { it.id }
            readTotal = result.total
            // 保留服务端原始游标，跨页重复的日志不会使下次读取倒退。
            offset = result.startIndex + result.items.size
            more = result.items.isNotEmpty() && offset < result.total
        } while (more && !append && offset < readTarget)
        activities = incoming; activityTotal = checkNotNull(readTotal); activityNextIndex = offset; activityHasMore = more
    }
    suspend fun readOverview() = coroutineScope {
        launch { try { info = administration.info(session); restartPending = false } catch (exception: Exception) { failure(exception) } }
        launch { readTasks() }
        launch { try { readActivities(append = false) }
            catch (exception: Exception) { activityFailed = true; failure(exception) } }
    }
    fun load(append: Boolean = false) {
        if (loading || writing || !session.isAdministrator) return
        if (append && (page != "activity" || !activityHasMore)) return
        val requestedPage = page
        loading = true; activityAppending = append; error = null
        if (requestedPage in listOf("overview", "activity")) activityFailed = false
        scope.launch {
            try {
                when (requestedPage) {
                    "logs" -> { logs = administration.logs(session); logsLoaded = true }
                    "log" -> logFile?.let { file ->
                        val content = administration.readLog(session, file)
                        val lines = withContext(Dispatchers.Default) {
                            content.text.lineSequence().flatMap { it.ifEmpty { " " }.chunked(1000).asSequence() }.take(10_000).toList()
                        }
                        logContent = content; logLines = lines
                    }
                    "activity" -> readActivities(append)
                    "tasks" -> readTasks()
                    else -> readOverview()
                }
            } catch (exception: Exception) {
                if (requestedPage == "activity") activityFailed = true
                failure(exception)
            }
            finally { loading = false; activityAppending = false }
        }
    }
    fun back() {
        if (writing) return
        when (page) { "log" -> { page = "logs"; logFile = null }; "overview" -> onBack(); else -> page = "overview" }
    }
    fun openLogs() { if (loading || writing) return; page = "logs"; if (!logsLoaded) load() }
    fun taskAction(task: ServerScheduledTask) {
        if (writing || loading) return
        // 写操作一开始就撤销旧读取的发布权，不能让在途轮询覆盖已接受的运行/取消状态。
        taskReadVersion++
        pendingTask = null; writing = true; error = null; notice = null
        scope.launch {
            var committed = false
            try {
                val run = task.state == ServerTaskState.IDLE
                if (run) administration.runTask(session, task.id) else administration.cancelTask(session, task.id)
                committed = true
                tasks = tasks.map { if (it.id == task.id) it.copy(state = if (run) ServerTaskState.RUNNING else ServerTaskState.CANCELLING) else it }
                notice = if (run) tr("任务已安排执行") else tr("已请求取消任务")
                readTasks(committed = true)
            } catch (exception: Exception) { failure(exception, committed) }
            finally { writing = false }
        }
    }
    BackHandler(enabled = writing || page != "overview") { back() }
    LaunchedEffect(session) { load() }
    LaunchedEffect(session, lifecycleOwner) {
        if (!session.isAdministrator) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(4_000)
                if (!loading && !writing && page in listOf("overview", "tasks") && tasks.any { it.state != ServerTaskState.IDLE && it.state != ServerTaskState.UNKNOWN }) {
                    readTasks()
                }
            }
        }
    }
    AutomaticListPagination(list, PaginationStatus(activities.size, activityNextIndex, activityTotal,
        refreshing = loading && !activityAppending, appending = activityAppending, failed = activityFailed, hasMore = activityHasMore),
        page, { load(append = true) },
        enabled = page == "activity" && !writing && session.isAdministrator)

    Column(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .testTag("server-administration-screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PathfinIconButton(PathfinIcons.Back, tr("返回"), ::back, Modifier.testTag("server-administration-back"), enabled = !writing,
                tint = PathfinAppearance.textMain, size = 44.dp)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(when (page) { "logs" -> tr("系统日志"); "log" -> logFile?.name ?: tr("系统日志"); "activity" -> tr("活动日志"); "tasks" -> tr("计划任务"); else -> tr("媒体库控制台") },
                    fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (page) {
                    "tasks" -> tr("%1\$s 个任务", tasks.size)
                    "activity" -> if (activityTotal > 0) tr("共 %1\$s 条记录", activityTotal) else tr("服务器活动记录")
                    "logs" -> tr("%1\$s 个日志文件", logs.size)
                    "log" -> tr("最多显示前 512 KiB / 10000 行；账号凭据已隐藏")
                    else -> info?.name ?: tr("Jellyfin 服务器")
                }, fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // 错误与操作结果固定在页头下方：无论列表滚到哪里都看得到，列表本身不被程序滚动。
        if (session.isAdministrator && (error != null || notice != null)) Column(Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { message -> ErrorPanel(message, modifier = Modifier.testTag("server-administration-error")) }
            notice?.let { message -> AdministrationNotice(message) }
        }
        RefreshablePage(loading, { load() }, Modifier.weight(1f).fillMaxWidth(), refreshOnEntry = false,
            enabled = !writing && session.isAdministrator) {
            if (!session.isAdministrator) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyPanel(tr("需要管理员权限"), tr("请使用服务器管理员账号管理服务器。"), PathfinIcons.Lock)
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("server-administration-list"), state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalNavigationBottomInset.current + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (page == "overview") {
                        item("server") { AdministrationServerCard(info) }
                        item("actions") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AdministrationAction(tr("重启服务器"), PathfinIcons.Power, danger = true,
                                    enabled = !writing && !loading && !restartPending && info?.canSelfRestart == true,
                                    modifier = Modifier.weight(1f).testTag("server-restart")) { confirmRestart = true }
                                AdministrationAction(tr("系统日志"), PathfinIcons.List, enabled = !writing && !loading,
                                    modifier = Modifier.weight(1f).testTag("server-logs-open"), onClick = ::openLogs)
                            }
                        }
                        item("tasks-title") { AdministrationSectionTitle(tr("计划任务"), tr("全部"), !loading && !writing) { page = "tasks" } }
                    }
                    if (page in listOf("overview", "tasks")) {
                        val summary = tasks.filter { it.key in listOf("RefreshLibrary", "ExtractChapterImages") }.ifEmpty { tasks.take(2) }
                        items(if (page == "overview") summary else tasks, key = { "task/${it.id}" }) { task ->
                            AdministrationTaskRow(task, !writing && !loading) { pendingTask = task }
                        }
                        if (tasks.isEmpty() && !loading) item { EmptyPanel(tr("暂无计划任务"), tr("刷新以重新读取服务器任务。"), PathfinIcons.Clock) }
                    }
                    if (page == "overview") item("activity-title") { AdministrationSectionTitle(tr("活动日志"), tr("更多"), !loading && !writing) { page = "activity" } }
                    if (page in listOf("overview", "activity")) {
                        items(if (page == "overview") activities.take(6) else activities, key = { "activity/${it.id}" }) { entry ->
                            AdministrationActivityRow(entry)
                        }
                        if (activities.isEmpty() && !loading) item { EmptyPanel(tr("暂无活动"), tr("服务器还没有可显示的活动记录。"), PathfinIcons.List) }
                    }
                    if (page == "logs") {
                        items(logs, key = { "log/${it.name}" }) { file ->
                            GroupCard(Modifier.testTag("server-log-file")) {
                                Row(Modifier.fillMaxWidth().clickable(enabled = !loading && !writing, role = Role.Button) {
                                    logFile = file; logContent = null; logLines = emptyList(); page = "log"; load()
                                }.heightIn(min = 64.dp).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    GroupIcon(PathfinIcons.List)
                                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(file.name, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                            color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${administrationDate(file.modifiedAt)} · ${file.sizeBytes / 1024} KiB", fontSize = 12.sp,
                                            lineHeight = 17.sp, color = PathfinAppearance.textMuted)
                                    }
                                    Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
                                }
                            }
                        }
                        if (logs.isEmpty() && !loading) item { EmptyPanel(tr("暂无系统日志"), tr("刷新以重新读取日志列表。"), PathfinIcons.List) }
                    }
                    if (page == "log") {
                        if (logContent?.truncated == true || logLines.size == 10_000) item("log-truncated") {
                            AdministrationNotice(tr("日志已截断，可在服务器查看完整内容。"))
                        }
                        items(logLines.size, key = { "line/$it" }) { index ->
                            SelectionContainer {
                                Text(logLines[index], Modifier.fillMaxWidth().padding(horizontal = 4.dp), fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp, lineHeight = 16.sp, color = PathfinAppearance.textMain)
                            }
                        }
                        if (logContent != null && logLines.isEmpty()) item { EmptyPanel(tr("日志为空"), tr("此日志文件还没有内容。"), PathfinIcons.List) }
                    }
                }
            }
        }
    }

    pendingTask?.let { task ->
        val run = task.state == ServerTaskState.IDLE
        AppAlertDialog(onDismissRequest = { if (!writing) pendingTask = null }, title = { Text(if (run) tr("运行计划任务？") else tr("取消正在运行的任务？")) },
            text = { Text(task.name + if (run) tr("\n任务将在此服务器执行，可能影响正在进行的播放。") else tr("\n服务器可能需要一些时间结束当前任务。")) },
            confirmButton = { DialogAction(if (run) tr("运行") else tr("确认取消"), { taskAction(task) }, Modifier.testTag("server-task-confirm"),
                enabled = !writing && !loading, role = DialogActionRole.Primary) },
            dismissButton = { DialogAction(tr("返回"), { pendingTask = null }, enabled = !writing) })
    }
    if (confirmRestart) AppAlertDialog(onDismissRequest = { if (!writing) confirmRestart = false }, title = { Text(tr("重启此 Jellyfin 服务器？")) },
        text = { Text(tr("将中断此服务器上所有用户的播放和连接。服务器重启后需要一些时间恢复。\n\n%1\$s", info?.name.orEmpty())) },
        confirmButton = { DialogAction(tr("确认重启"), {
            if (!writing) {
                confirmRestart = false; writing = true; restartPending = true; error = null
                scope.launch {
                    try { administration.restart(session); notice = tr("重启请求已接受，请稍后刷新服务器状态。") }
                    catch (exception: Exception) {
                        failure(exception)
                        notice = tr("无法确认重启结果，请先刷新服务器状态；不要重复发送重启请求。")
                    } finally { writing = false }
                }
            }
        }, Modifier.testTag("server-restart-confirm"), enabled = !writing, role = DialogActionRole.Danger) },
        dismissButton = { DialogAction(tr("取消"), { confirmRestart = false }, enabled = !writing) })
}

private const val ACTIVITY_PAGE_SIZE = 30

/** 服务器概况：图标块、名称、版本与状态标签。 */
@Composable
private fun AdministrationServerCard(info: ServerAdministrationInfo?) {
    GroupCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(PathfinAppearance.accentSoft), contentAlignment = Alignment.Center) {
                Icon(PathfinIcons.Server, null, Modifier.size(22.dp), tint = PathfinAppearance.accent)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(info?.name ?: "Jellyfin", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                    color = PathfinAppearance.textMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(info?.version?.let { tr("版本 %1\$s", it) } ?: tr("正在读取服务器信息"), fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub)
            }
            val status = when { info?.isShuttingDown == true -> tr("正在关闭"); info?.hasPendingRestart == true -> tr("等待重启"); info != null -> tr("运行中"); else -> null }
            status?.let { AdministrationBadge(it, attention = info?.isShuttingDown == true || info?.hasPendingRestart == true) }
        }
    }
}

@Composable
private fun AdministrationBadge(text: String, attention: Boolean) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp))
        .background(if (attention) PathfinAppearance.tintRed else PathfinAppearance.accentSoft).padding(horizontal = 7.dp, vertical = 3.dp),
        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
        color = if (attention) MaterialTheme.colorScheme.error else PathfinAppearance.accent, maxLines = 1)
}

/** 并排的大号操作卡：图标在上、文字在下；危险操作图标用错误色。 */
@Composable
private fun AdministrationAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
    modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val tint = when { !enabled -> PathfinAppearance.textMuted; danger -> MaterialTheme.colorScheme.error; else -> PathfinAppearance.accent }
    Column(modifier.heightIn(min = 84.dp).clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = if (enabled) PathfinAppearance.textMain else PathfinAppearance.textMuted, maxLines = 1)
    }
}

@Composable
private fun AdministrationNotice(message: String) {
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(PathfinAppearance.accent))
            Text(message, Modifier.padding(start = 12.dp), fontSize = 13.5.sp, lineHeight = 19.sp, color = PathfinAppearance.textMain)
        }
    }
}

@Composable
private fun AdministrationSectionTitle(title: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(title, Modifier.weight(1f))
        QuietButton(action, onClick, enabled = enabled, color = PathfinAppearance.accent)
    }
}

@Composable
private fun AdministrationActivityRow(entry: ServerActivity) {
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            GroupIcon(PathfinIcons.User)
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.name, fontSize = 14.sp, lineHeight = 20.sp, color = PathfinAppearance.textMain)
                entry.overview?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp,
                    color = PathfinAppearance.textSub, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                Text(administrationDate(entry.date), fontSize = 11.5.sp, lineHeight = 16.sp, color = PathfinAppearance.textMuted)
            }
        }
    }
}

@Composable
private fun AdministrationTaskRow(task: ServerScheduledTask, enabled: Boolean, action: () -> Unit) {
    val running = task.state != ServerTaskState.IDLE && task.state != ServerTaskState.UNKNOWN
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.name, Modifier.weight(1f, fill = false), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                        color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (running) AdministrationBadge(task.state.label + (task.progressPercent?.let { " · ${it.toInt()}%" } ?: ""), attention = false)
                }
                if (task.description.isNotBlank()) Text(task.description, fontSize = 12.5.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = PathfinAppearance.textSub)
                if (running) task.progressPercent?.let { PathfinProgressBar(it.toFloat() / 100f, Modifier.padding(vertical = 4.dp)) }
                task.lastEndedAt?.let {
                    Text(tr("最后执行 %1\$s · %2\$s", administrationDate(it), taskResultLabel(task.lastResult)) +
                        (if (task.lastStartedAt != null) taskDuration(task).takeIf(String::isNotEmpty)?.let { duration -> tr(" · 耗时 %1\$s", duration) }.orEmpty() else ""),
                        fontSize = 11.5.sp, lineHeight = 16.sp, color = PathfinAppearance.textMuted)
                }
            }
            val idle = task.state == ServerTaskState.IDLE
            PathfinIconButton(if (idle) PathfinIcons.PlayGlyph else PathfinIcons.Stop, if (idle) tr("运行%1\$s", task.name) else tr("取消%1\$s", task.name), action,
                Modifier.testTag("server-task-${task.id}"), enabled = enabled && task.state in listOf(ServerTaskState.IDLE, ServerTaskState.RUNNING),
                tint = if (idle) PathfinAppearance.accent else MaterialTheme.colorScheme.error, container = PathfinAppearance.softFill, size = 44.dp)
        }
    }
}

private fun administrationDate(value: String?): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrDefault(it.take(40))
} ?: tr("未记录时间")

private fun taskDuration(task: ServerScheduledTask): String = runCatching {
    val seconds = Duration.between(Instant.parse(task.lastStartedAt), Instant.parse(task.lastEndedAt)).seconds.coerceAtLeast(0)
    tr("%1\$s分%2\$s秒", seconds / 60, seconds % 60)
}.getOrDefault("")

private fun taskResultLabel(value: String?): String = when (value) { "Completed" -> tr("完成"); "Failed" -> tr("失败"); "Cancelled" -> tr("取消"); "Aborted" -> tr("中止"); else -> tr("未知") }
private fun administrationError(error: Exception): String = when (error) {
    is ApiException -> error.message ?: tr("无法读取服务器，请重试")
    is IllegalArgumentException -> error.message ?: tr("请求参数无效")
    else -> tr("服务器请求未完成，请检查连接后重试")
}

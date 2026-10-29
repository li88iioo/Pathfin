package dev.pathfin.android.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.MediaIdentificationApi
import dev.pathfin.android.data.MediaManagementApi
import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 搜索和确认分开；Apply 后只提示刷新结果，不会因重读失败重复提交。 */
@Composable
fun MediaIdentificationScreen(item: MediaItem, session: Session, api: JellyfinApi, onBack: () -> Unit,
    onChanged: () -> Unit, onAuthExpired: () -> Unit = {}) {
    key(session, item.id) { IdentificationContent(item, session, api, onBack, onChanged, onAuthExpired) }
}

@Composable
private fun IdentificationContent(item: MediaItem, session: Session, api: JellyfinApi, onBack: () -> Unit,
    onChanged: () -> Unit, onAuthExpired: () -> Unit) {
    val identification = remember(api) { MediaIdentificationApi(api) }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(item.name) }
    var year by rememberSaveable { mutableStateOf(item.year?.toString().orEmpty()) }
    val type = MediaIdentificationType.forItem(item)
    val byYear = type?.searchesByYear != false
    var providerIds by rememberSaveable(stateSaver = ProviderIdDraftSaver) { mutableStateOf(item.providerIds.toMap()) }
    var idFields by remember { mutableStateOf<List<ExternalIdField>?>(null) }
    var idFieldsError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MediaIdentificationResult>>(emptyList()) }
    var resultForm by remember { mutableStateOf<MediaIdentificationQuery?>(null) }
    var selected by remember { mutableStateOf<MediaIdentificationResult?>(null) }
    var replaceImages by remember { mutableStateOf(false) }
    var resultsPage by remember { mutableStateOf(false) }
    val validYear = !byYear || year.isBlank() || year.toIntOrNull()?.let { it in 1..9999 } == true
    val enabled = MediaIdentificationApi.supports(session, item)
    val fields = idFields.orEmpty()
    fun providerValue(key: String) = providerIds[key] ?: providerIds.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()
    // 请求只带服务器提供的编号；完整表单快照单独用于判断用户是否修改了条件。
    // 清单晚到只补充输入框，不会让已经取得的名称搜索结果无故失效。
    val form = MediaIdentificationQuery(name.trim(), if (byYear) year.toIntOrNull() else null, providerIds.toMap())
    val filledIds = fields.mapNotNull { field -> providerValue(field.key).trim().takeIf(String::isNotEmpty)?.let { field.key to it } }.toMap()
    val query = form.copy(providerIds = filledIds)
    val canApply = !searching && !applying && !applied && validYear && resultForm == form

    fun failure(exception: Exception) {
        if (exception is CancellationException) throw exception
        if (exception is ApiException && exception.statusCode == 401) onAuthExpired()
        error = identificationError(exception)
    }
    fun search() {
        if (searching || applying || !enabled || (byYear && !validYear)) return
        searching = true; searched = true; error = null; selected = null; applied = false; resultForm = null
        scope.launch {
            try {
                results = identification.search(session, item, query)
                resultForm = form
                resultsPage = true
            }
            catch (exception: Exception) { failure(exception) }
            finally { searching = false }
        }
    }
    BackHandler(enabled = LocalPageActive.current && applying) { }
    // 读不到编号清单时仍可按名称搜索，不扩大服务器提供程序的范围。
    LaunchedEffect(item.id, session) {
        if (enabled) idFields = try { MediaManagementApi(api).externalIds(session, item) } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            if (exception is ApiException && exception.statusCode == 401) onAuthExpired()
            idFieldsError = tr("外部编号清单读取失败，仍可使用名称搜索。")
            emptyList()
        }
    }

    // 搜索成功后进入独立的结果页（带海报/头像，便于区分同名条目）；返回键回到表单修改条件。
    BackHandler(enabled = LocalPageActive.current && resultsPage && !applying) { resultsPage = false }
    val person = item.type == "Person"
    Column(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets).testTag("identification-screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PathfinIconButton(PathfinIcons.Back, if (resultsPage) tr("返回搜索条件") else tr("返回"), { if (resultsPage) resultsPage = false else onBack() },
                Modifier.testTag("identification-back"), enabled = !applying, tint = PathfinAppearance.textMain, size = 44.dp)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(if (resultsPage) tr("选择匹配结果") else if (person) tr("识别人物") else tr("识别媒体"), fontSize = 19.sp, lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                Text(if (resultsPage) tr("「%1\$s」· %2\$s 个结果", resultForm?.name?.ifBlank { null } ?: item.name, results.size) else item.name,
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RefreshablePage(searching, ::search, Modifier.weight(1f).fillMaxWidth(), refreshOnEntry = false,
            enabled = enabled && searched && !applying && validYear && (name.isNotBlank() || filledIds.isNotEmpty())) {
            if (!enabled) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyPanel(tr("无法识别此媒体"), if (!session.isAdministrator) tr("需要服务器管理员权限。") else tr("此类型没有可用的远程识别接口。"))
                }
            } else if (resultsPage) LazyColumn(Modifier.fillMaxSize().testTag("identification-results"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalNavigationBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { message -> item { ErrorPanel(message, modifier = Modifier.testTag("identification-error")) } }
                if (applied) item { AppliedBanner() }
                items(results, key = { it.key }) { result ->
                    IdentificationResultCard(result, person, canApply) { selected = result; replaceImages = false }
                }
                if (results.isEmpty() && !searching && error == null) item {
                    EmptyPanel(tr("没有找到匹配结果"), tr("返回修改%1\$s或外部编号，并检查服务器是否配置了可用的元数据提供程序。", if (person) tr("姓名") else tr("名称、年份")))
                }
            } else LazyColumn(Modifier.fillMaxSize().testTag("identification-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = LocalNavigationBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 与服务器表单同一语言：名称、年份（人物与合集没有）、各提供程序的外部编号在一张分组卡片里。
                item {
                    GroupCard {
                        GroupField(name, { name = it }, if (person) tr("姓名") else tr("名称"),
                            if (person) tr("人物姓名") else tr("片名或剧名"), PathfinIcons.Type, Modifier.testTag("identification-name"),
                            divider = false, readOnly = searching || applying)
                        if (byYear) GroupField(year, { year = it }, tr("年份"), tr("可选"), PathfinIcons.Calendar, Modifier.testTag("identification-year"),
                            readOnly = searching || applying, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        // 每个已启用的元数据提供程序一行，用条目已有的编号预填，与 Jellyfin 官方识别表单一致。
                        fields.forEach { field ->
                            GroupField(providerValue(field.key), { providerIds = providerIds + (field.key to it) }, field.name, tr("可选"), PathfinIcons.Hash,
                                Modifier.testTag("identification-id/${field.key}"), readOnly = searching || applying)
                        }
                    }
                    Text(if (validYear) tr("可填写外部编号以缩小匹配范围") else tr("年份应为 1 到 9999"), Modifier.padding(start = 6.dp, top = 8.dp),
                        fontSize = 12.sp, lineHeight = 17.sp,
                        color = if (validYear) PathfinAppearance.textMuted else MaterialTheme.colorScheme.error)
                }
                idFieldsError?.let { message -> item { ErrorPanel(message) } }
                item {
                    val canSearch = !searching && !applying && validYear && (name.isNotBlank() || filledIds.isNotEmpty())
                    InkButton(::search, Modifier.fillMaxWidth().testTag("identification-search"), enabled = canSearch, muted = !canSearch) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (searching) PathfinSpinner(LocalContentColor.current, Modifier.size(18.dp)) else Icon(PathfinIcons.Search, null, Modifier.size(18.dp))
                            ButtonLabel(tr("搜索"))
                        }
                    }
                }
                error?.let { message -> item { ErrorPanel(message, modifier = Modifier.testTag("identification-error")) } }
                if (applied) item { AppliedBanner() }
                // 已有结果时可直接回到结果页，不必重新搜索。
                if (results.isNotEmpty() && resultForm == form) item {
                    QuietButton(tr("查看 %1\$s 个匹配结果", results.size), { resultsPage = true }, Modifier.fillMaxWidth().testTag("identification-show-results"),
                        color = PathfinAppearance.accent)
                }
            }
        }
    }
    selected?.let { candidate ->
        AppAlertDialog(onDismissRequest = { if (!applying) selected = null }, title = { Text(tr("应用识别结果？")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(candidate.name + (candidate.productionYear?.let { tr("（%1\$s）", it) } ?: ""))
                Text(tr("服务器将重新获取并替换此条目的元数据，包括已有的手动编辑内容。"))
                PathfinCheckbox(tr("同时替换全部图片"), replaceImages, { replaceImages = it }, Modifier.testTag("identification-replace-images"),
                    enabled = !applying)
            } }, confirmButton = {
                DialogAction(tr("确认应用"), {
                    if (canApply && !applying) {
                        applying = true; error = null
                        scope.launch {
                            try {
                                identification.apply(session, item, candidate, MediaIdentificationOptions(replaceImages))
                                applied = true; selected = null; onChanged()
                            } catch (exception: Exception) { selected = null; failure(exception) }
                            finally { applying = false }
                        }
                    }
                }, Modifier.testTag("identification-confirm"), enabled = canApply && candidate.providerIds.isNotEmpty(), role = DialogActionRole.Primary)
            }, dismissButton = { DialogAction(tr("取消"), { selected = null }, enabled = !applying) })
    }
}

@Composable
private fun AppliedBanner() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.accentSoft).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(PathfinIcons.CheckCircle, null, Modifier.size(20.dp), tint = PathfinAppearance.accent)
        Text(tr("识别结果已应用，信息已更新。"), Modifier.testTag("identification-success"), fontSize = 14.sp, color = PathfinAppearance.textMain)
    }
}

/** 一条匹配结果：左侧提供程序的海报或头像（2:3），右侧名称、年份与来源、外部编号和简介。 */
@Composable
private fun IdentificationResultCard(result: MediaIdentificationResult, person: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag("identification-result").padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(76.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(PathfinAppearance.softFill)
            .testTag("identification-result-image"), contentAlignment = Alignment.Center) {
            Icon(if (person) PathfinIcons.User else MediaNavigationIcons.Movies, null, Modifier.size(24.dp), tint = PathfinAppearance.textMuted)
            result.imageUrl?.let { url ->
                coil3.compose.AsyncImage(url, result.name, imageLoader = LocalPosterLoader.current,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.name, fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(result.productionYear?.toString(), result.searchProviderName).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub)
            if (result.providerIds.isNotEmpty()) Text(result.providerIds.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                fontSize = 12.sp, lineHeight = 17.sp, color = PathfinAppearance.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            result.overview?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp, lineHeight = 19.sp, color = PathfinAppearance.textMain) }
            if (result.providerIds.isEmpty()) Text(tr("缺少外部编号，无法应用此结果"), color = MaterialTheme.colorScheme.error,
                fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}

private fun identificationError(error: Exception): String = when (error) {
    is ApiException -> error.message ?: tr("服务器请求失败，请重试")
    is IllegalArgumentException -> error.message ?: tr("请检查搜索内容")
    else -> tr("识别请求未完成，请检查连接后重试")
}

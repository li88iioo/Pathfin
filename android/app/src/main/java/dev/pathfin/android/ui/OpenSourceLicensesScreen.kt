package dev.pathfin.android.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pathfin.android.i18n.tr
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 全部许可来自随包资产；只有用户点击上游链接时才打开浏览器。 */
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit, showTopBar: Boolean = true) {
    val context = LocalContext.current.applicationContext
    var retry by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var selectedId by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val catalog by produceState<NoticeCatalog?>(null, context, retry) {
        value = readOffline(NoticeCatalog(emptyList(), failed = true)) { readNoticeCatalog(context) }
    }
    val selected = catalog?.components?.firstOrNull { it.id == selectedId }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    val goBack = { if (selectedId != null) selectedId = null else onBack() }
    val filtered = remember(catalog, query) {
        val needle = query.trim()
        catalog?.components.orEmpty().filter {
            needle.isEmpty() || it.searchText.contains(needle, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize().background(PathfinAppearance.canvas).testTag("open-source-licenses")
        .padding(top = if (showTopBar) 0.dp else LocalPageTopInset.current)) {
        if (showTopBar) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = 60.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PathfinIconButton(PathfinIcons.Back, tr("返回"), goBack, tint = PathfinAppearance.textMain, size = 44.dp)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(if (selected == null) tr("开源许可") else selected.artifact, fontSize = 19.sp, lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (selected == null) tr("许可全文随应用保存，可离线查阅") else selected.licenseIds, fontSize = 12.5.sp, lineHeight = 17.sp,
                        color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (selectedId != null) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 44.dp).clip(CircleShape)
                .clickable(role = Role.Button) { selectedId = null }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PathfinIcons.Back, null, Modifier.size(18.dp), tint = PathfinAppearance.textMain)
                Text(tr("返回组件列表"), Modifier.padding(start = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
            }
        }
        when {
            catalog == null -> OfflineNoticeState(tr("正在读取离线许可…"))
            catalog?.failed == true -> OfflineNoticeState(tr("无法读取离线许可"), onRetry = { retry++ })
            selected != null -> NoticeDetail(selected)
            else -> {
                PathfinSearchField(query, { query = it }, {}, { query = "" }, tr("搜索组件、版本或许可"),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("license-search"))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag("license-components"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = LocalNavigationBottomInset.current + 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "intro") {
                        Eyebrow(tr("%1\$s 个组件", catalog?.components?.size ?: 0), Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                    }
                    if (filtered.isEmpty()) item(key = "no-results") {
                        EmptyPanel(tr("没有匹配的组件"), tr("换个组件名称、版本或许可再试。"), PathfinIcons.Search)
                    }
                    items(filtered, key = { it.id }) { component ->
                        GroupCard {
                            Row(
                                Modifier.fillMaxWidth().clickable { selectedId = component.id }
                                    .heightIn(min = 72.dp).padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(component.artifact, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                                        color = PathfinAppearance.textMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${component.version} · ${component.licenseIds}", fontSize = 12.5.sp, lineHeight = 17.sp,
                                        color = PathfinAppearance.accent)
                                    Text(component.group, fontSize = 11.5.sp, lineHeight = 16.sp, color = PathfinAppearance.textMuted,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineNoticeState(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (onRetry == null) EmptyPanel(message, tr("许可全文随应用保存，无需联网。"))
        else ErrorPanel(message, retry = onRetry)
    }
}

@Composable
private fun NoticeDetail(component: NoticeComponent) {
    val context = LocalContext.current
    var retry by remember(component.id) { mutableIntStateOf(0) }
    val document by produceState<NoticeDocument?>(null, component.id, retry) {
        value = readOffline(NoticeDocument(emptyList(), failed = true)) { readNoticeDocument(context.applicationContext, component) }
    }
    val detailState = androidx.compose.runtime.key(component.id) { rememberLazyListState() }
    LazyColumn(state = detailState, modifier = Modifier.fillMaxSize().testTag("license-detail"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalNavigationBottomInset.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "identity") {
            GroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(component.name, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                    SelectionContainer { Text(component.id, fontSize = 12.sp, lineHeight = 17.sp, color = PathfinAppearance.textMuted) }
                    Text(component.licenseIds, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.accent)
                    if (component.modifications.isNotBlank()) Text(component.modifications, fontSize = 13.sp, lineHeight = 19.sp,
                        color = PathfinAppearance.textMain)
                }
            }
        }
        if (component.links.isNotEmpty()) item(key = "links") {
            GroupCard {
                component.links.forEachIndexed { index, link ->
                    var unableToOpen by remember(link.url) { androidx.compose.runtime.mutableStateOf(false) }
                    Column(Modifier.groupSeparator(PathfinAppearance.divider, index > 0)) {
                        Row(Modifier.fillMaxWidth().clickable {
                            unableToOpen = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                            }.isFailure
                        }.heightIn(min = 52.dp).padding(horizontal = 16.dp).semantics { contentDescription = tr("在浏览器打开%1\$s", link.label) },
                            verticalAlignment = Alignment.CenterVertically) {
                            GroupIcon(PathfinIcons.Link)
                            Text(link.label, Modifier.weight(1f).padding(start = 14.dp), fontSize = 14.5.sp, color = PathfinAppearance.textMain)
                            Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
                        }
                        if (unableToOpen) Text(tr("没有可用的浏览器；下方许可全文仍可离线查阅。"), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        when {
            document == null -> item(key = "document-loading") { OfflineNoticeState(tr("正在读取许可全文…")) }
            document?.failed == true -> item(key = "document-error") { OfflineNoticeState(tr("许可全文读取失败"), onRetry = { retry++ }) }
            else -> items(document?.blocks.orEmpty(), key = { it.key }, contentType = { if (it.heading) "heading" else "paragraph" }) { block ->
                if (block.heading) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Hairline()
                        Text(block.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                    }
                } else SelectionContainer {
                    Text(block.text, Modifier.padding(horizontal = 4.dp), fontSize = 12.5.sp, lineHeight = 19.sp, color = PathfinAppearance.textSub)
                }
            }
        }
        item(key = "end-space") { Spacer(Modifier.height(8.dp)) }
    }
}

private data class NoticeCatalog(val components: List<NoticeComponent>, val failed: Boolean = false)
private data class NoticeLink(val label: String, val url: String)
private data class NoticeText(val path: String, val sha256: String, val title: String, val assetRoot: String)
private data class NoticeComponent(val id: String, val name: String, val artifact: String, val group: String,
    val version: String, val licenseIds: String, val links: List<NoticeLink>, val texts: List<NoticeText>, val modifications: String) {
    val searchText = "$id $name $licenseIds"
}
private data class NoticeBlock(val key: String, val text: String, val heading: Boolean = false)
private data class NoticeDocument(val blocks: List<NoticeBlock>, val failed: Boolean = false)

private suspend fun <T> readOffline(failed: T, read: () -> T): T = withContext(Dispatchers.IO) {
    try { read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failed }
}

private fun safeNoticeUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

private fun readNoticeCatalog(context: Context): NoticeCatalog {
    val components = listOf("open-source", "open-source-ass", "open-source-ffmpeg", "open-source-artwork").flatMap { assetRoot ->
    val json = JSONObject(context.assets.open("$assetRoot/manifest.json").bufferedReader().use { it.readText() })
    require(json.getInt("schemaVersion") == 1 && json.getJSONArray("unresolved").length() == 0)
    val items = json.getJSONArray("components")
    List(items.length()) { index ->
        val item = items.getJSONObject(index)
        val links = item.getJSONArray("links")
        val texts = item.getJSONArray("texts")
        val ids = item.getJSONArray("licenseIds")
        NoticeComponent(id = item.getString("id"), name = item.getString("name"), artifact = item.getString("artifact"),
            group = item.getString("group"), version = item.getString("version"), licenseIds = List(ids.length()) { ids.getString(it) }.joinToString(" / "),
            links = List(links.length()) { linkIndex -> links.getJSONObject(linkIndex).let { NoticeLink(it.getString("label"), it.getString("url")) } }
                .filter { safeNoticeUrl(it.url) }.distinctBy { it.url },
            texts = List(texts.length()) { textIndex -> texts.getJSONObject(textIndex).let { NoticeText(it.getString("path"), it.getString("sha256"), it.getString("title"), assetRoot) } },
            modifications = item.optJSONArray("modifications")?.let { values ->
                List(values.length()) { values.getString(it) }.joinToString("\n")
            } ?: item.optString("modifications"))
    }
    }
    require(components.isNotEmpty() && components.map { it.id }.toSet().size == components.size)
    return NoticeCatalog(components)
}

private fun readNoticeDocument(context: Context, component: NoticeComponent): NoticeDocument {
    val blocks = component.texts.flatMapIndexed { fileIndex, text ->
        require(text.path.matches(Regex("texts/[a-f0-9]{64}\\.txt")))
        val bytes = context.assets.open("${text.assetRoot}/${text.path}").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(sha == text.sha256)
        val paragraphs = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").split(Regex("\n[ \\t]*\n"))
            .flatMap { it.chunked(1800) }.filter { it.isNotBlank() }
        listOf(NoticeBlock("$fileIndex:heading", text.title, heading = true)) + paragraphs.mapIndexed { index, paragraph ->
            NoticeBlock("$fileIndex:$index", paragraph)
        }
    }
    require(blocks.isNotEmpty())
    return NoticeDocument(blocks)
}

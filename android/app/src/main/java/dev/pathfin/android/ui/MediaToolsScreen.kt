package dev.pathfin.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.MediaManagementApi
import dev.pathfin.android.data.MediaImageSelectionChanged
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.data.SafDocumentTransfer
import dev.pathfin.android.domain.*
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate

internal val ProviderIdDraftSaver = mapSaver<Map<String, String>>(
    save = { it },
    restore = { saved -> saved.mapValues { (_, value) -> value as String } },
)

/** 所有写入均由明确按钮发起；离开页面取消尚未完成的请求。 */
@Composable
internal fun MediaToolsScreen(item: MediaItem, session: Session, api: JellyfinApi, initialPage: String,
    onChanged: () -> Unit, onAuthExpired: () -> Unit = {}, onBusyChanged: (Boolean) -> Unit = {}) {
    require(initialPage == "metadata" || initialPage == "images")
    val management = remember(api) { MediaManagementApi(api) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editorState = rememberSaveableStateHolder()
    val page = initialPage
    var document by remember(session, item.id) { mutableStateOf<EditableMediaMetadata?>(null) }
    // 外部编号来自服务器的提供程序清单；读取失败仍可编辑条目已有的编号。
    var externalIdFields by remember(session, item.id) { mutableStateOf<List<ExternalIdField>>(emptyList()) }
    var externalIdsError by remember(session, item.id) { mutableStateOf<String?>(null) }
    var capabilities by remember(session, item.id) { mutableStateOf(management.capabilities(session, item)) }
    var images by remember(session, item.id) { mutableStateOf<List<MediaImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadVersion by remember(session, item.id) { mutableIntStateOf(0) }
    var readSequence by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var preparingImage by remember { mutableStateOf(false) }
    val controlsBusy = busy || preparingImage
    var savingMetadata by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val feedback = remember { SnackbarHostState() }
    var deleteImage by remember { mutableStateOf<MediaImage?>(null) }
    var imageType by rememberSaveable { mutableStateOf(MediaImageType.PRIMARY.name) }
    var remoteSearchType by remember(session, item.id) { mutableStateOf<MediaImageType?>(null) }
    var draftDirty by remember { mutableStateOf(false) }
    var contentRevision by remember(session, item.id) { mutableIntStateOf(0) }
    var metadataNeedsReload by remember { mutableStateOf(false) }
    var allowBack by remember { mutableStateOf(false) }
    var leaveDialog by remember { mutableStateOf(false) }
    val topInset = LocalPageTopInset.current
    val titleBarHeight = with(LocalDensity.current) {
        maxOf(60.dp, maxOf(MaterialTheme.typography.bodyMedium.lineHeight.toDp(),
            MaterialTheme.typography.labelLarge.lineHeight.toDp()) + 16.dp)
    }
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val reportBusy by rememberUpdatedState(onBusyChanged)
    SideEffect { reportBusy(busy) }
    DisposableEffect(Unit) { onDispose { reportBusy(false) } }
    if (initialPage == "metadata") LaunchedEffect(session, item.id, reloadVersion) {
        externalIdsError = null
        try { externalIdFields = management.externalIds(session, item) } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is ApiException && failure.statusCode == 401) onAuthExpired()
            externalIdsError = tr("外部编号清单读取失败，下拉刷新可重试。已有编号仍可编辑。")
        }
    }
    LaunchedEffect(error, result) {
        val failure = error
        val message = failure ?: result ?: return@LaunchedEffect
        feedback.showSnackbar(message, withDismissAction = failure != null,
            duration = if (failure != null) SnackbarDuration.Indefinite else SnackbarDuration.Short)
    }

    suspend fun read(targetPage: String) {
        val revision = contentRevision
        // 元数据对象已包含条目能力；文本编辑不等待无关的图片读取。
        val latestDocument = if (targetPage == "metadata" && capabilities.canEditMetadata && !draftDirty)
            management.editableMetadata(session, item.id) else null
        val latestCapabilities = latestDocument?.capabilities ?: management.readCapabilities(session, item.id)
        val latestImages = if (targetPage == "images" && latestCapabilities.canEditImages)
            management.images(session, item.id) else null
        // 保存/上传或新草稿产生后，整次旧读取不能覆盖更新后的元数据和图片。
        if (revision != contentRevision || page != targetPage) return
        capabilities = latestCapabilities
        // 后台重读期间仍允许编辑，返回的数据不能覆盖等待期间产生的草稿。
        if (latestDocument != null && !draftDirty) {
            document = latestDocument
            metadataNeedsReload = false
        }
        if (latestImages != null) images = latestImages
    }
    fun action(success: String, metadataSave: Boolean = false,
        refresh: (suspend () -> Unit)? = null, operation: suspend () -> Unit) {
        if (busy || preparingImage) return
        contentRevision++
        busy = true; savingMetadata = metadataSave; error = null; result = null
        scope.launch {
            var committed = false
            try {
                operation()
                committed = true
                onChanged()
                result = success
                refresh?.invoke()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is ApiException && failure.statusCode == 401) onAuthExpired()
                if (failure is MediaImageSelectionChanged) images = failure.images
                error = if (committed) tr("%1\$s，但重新读取失败；可重试加载，请勿重复提交。", success) else toolsError(failure)
            } finally { busy = false; savingMetadata = false }
        }
    }
    fun reload() {
        if (busy || preparingImage || loading) return
        loading = true; error = null; result = null
        reloadVersion++
    }
    // 当前编辑页和下拉共用唯一读取入口；卸载页面会取消读取。
    LaunchedEffect(session, item.id, page, reloadVersion) {
        val sequence = ++readSequence
        loading = true; error = null
        try { read(page) } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (sequence == readSequence) {
                if (failure is ApiException && failure.statusCode == 401) onAuthExpired()
                error = toolsError(failure)
            }
        } finally { if (sequence == readSequence) loading = false }
    }
    BackHandler(enabled = LocalPageActive.current && !allowBack && (busy || draftDirty)) { if (!busy) leaveDialog = true }
    LaunchedEffect(allowBack) { if (allowBack) backDispatcher?.onBackPressed() }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy && !preparingImage) {
            val type = MediaImageType.valueOf(imageType)
            preparingImage = true; error = null; result = null
            scope.launch {
                try {
                    val (bytes, mime) = SafDocumentTransfer.transfer { operation ->
                        require(uri.scheme == "content") { tr("请通过系统文件选择器选择图片") }
                        val content = operation.readBytes(context, uri, MediaManagementApi.MAX_IMAGE_BYTES, tr("图片不能超过10 MB"))
                        operation.ensureActive()
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        operation.ensureActive()
                        content to mime
                    }
                    currentCoroutineContext().ensureActive()
                    // 准备文件允许关闭抽屉；只有开始服务器写入后才阻止退出。
                    preparingImage = false
                    action(tr("图片已更新"), refresh = { images = management.images(session, item.id) }) {
                        management.uploadImage(session, item.id, type, bytes, mime)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = toolsError(failure)
                } finally {
                    preparingImage = false
                }
            }
        }
    }

    CompositionLocalProvider(LocalPersonImages provides (item.type == "Person")) {
    Box(Modifier.fillMaxSize().background(PathfinAppearance.canvas)) {
        RefreshablePage(loading, ::reload, Modifier.fillMaxSize(), refreshOnEntry = false, enabled = !controlsBusy,
            indicatorTopInset = topInset + if (page == "metadata") titleBarHeight else 0.dp) {
            Column(Modifier.fillMaxSize()) {
                when {
                    page == "metadata" && capabilities.canEditMetadata -> {
                        val current = document
                        if (current != null) editorState.SaveableStateProvider("metadata") { MetadataEditor(current, item.type == "Person", externalIdFields, externalIdsError, busy || metadataNeedsReload,
                            saving = savingMetadata, titleBarHeight = titleBarHeight, onDirty = {
                            if (it) contentRevision++
                            draftDirty = it && !metadataNeedsReload
                        }, onSave = { changed ->
                            action(tr("元数据已保存"), metadataSave = true, refresh = {
                                document = management.editableMetadata(session, item.id)
                                metadataNeedsReload = false
                            }) {
                                management.saveMetadata(session, current, changed)
                                metadataNeedsReload = true
                                draftDirty = false
                            }
                        }) } else Column(Modifier.fillMaxSize().padding(top = topInset + titleBarHeight).verticalScroll(rememberScrollState())) {
                            if (!loading) EmptyPanel(tr("无法读取元数据"), tr("请检查连接或当前账号权限。"))
                        }
                    }
                    page == "images" && capabilities.canEditImages -> LazyColumn(
                        Modifier.fillMaxSize().testTag("media-images"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = LocalNavigationBottomInset.current), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val types = ((if (item.type == "Person") listOf(MediaImageType.PRIMARY) else editableImageTypes) + images.map { it.type }.filterNot {
                            it == MediaImageType.BACKDROP || it == MediaImageType.PROFILE || it == MediaImageType.CHAPTER
                        }).distinct()
                        items(types.chunked(2), key = { row -> row.joinToString { it.apiValue } }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { type ->
                                    MediaImageCard(type, images.filter { it.type == type }.minByOrNull { it.index }, session, api, management,
                                        item.id, controlsBusy, Modifier.weight(1f), onSearch = { remoteSearchType = type }, onUpload = {
                                            imageType = type.name
                                            upload.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                        }, onDelete = { deleteImage = it })
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        item("backdrop-heading") {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("背景图"), Modifier.weight(1f).padding(start = 2.dp), fontSize = 16.sp, lineHeight = 22.sp,
                                    fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                                PathfinIconButton(PathfinIcons.ImageSearch, tr("搜索背景图"), { remoteSearchType = MediaImageType.BACKDROP },
                                    Modifier.testTag("media-image-search/Backdrop"), enabled = !controlsBusy, tint = PathfinAppearance.textSub, size = 44.dp)
                                PathfinIconButton(PathfinIcons.Plus, tr("添加背景图"), { imageType = MediaImageType.BACKDROP.name; upload.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                                    Modifier.testTag("media-image-upload/Backdrop"), enabled = !controlsBusy, tint = PathfinAppearance.textSub, size = 44.dp)
                            }
                        }
                        val backdrops = images.filter { it.type == MediaImageType.BACKDROP }.sortedBy { it.index }
                        items(backdrops.chunked(2), key = { row -> "backdrops/${row.joinToString { "${it.index}/${it.tag}" }}" }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { image -> MediaImageCard(MediaImageType.BACKDROP, image, session, api, management, item.id, controlsBusy,
                                    Modifier.weight(1f), onDelete = { deleteImage = it }) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (backdrops.isEmpty()) item("empty-backdrops") {
                            Text(tr("暂无背景图"), fontSize = 13.sp, lineHeight = 19.sp, color = PathfinAppearance.textSub,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                        }
                        item("upload-limit") { Text(tr("JPG、PNG、WebP · 单张不超过 10 MB"), fontSize = 12.sp, lineHeight = 17.sp,
                            color = PathfinAppearance.textMuted) }
                    }
                    !loading -> Box(Modifier.padding(top = if (page == "metadata") topInset + titleBarHeight else 0.dp)) {
                        EmptyPanel(tr("当前账号没有编辑权限"), tr("请在 Jellyfin 中为账号授予所需权限。"))
                    }
                }
            }
        }
        if (page == "metadata" && (document == null || !capabilities.canEditMetadata)) {
            MetadataTitleBar(topInset, titleBarHeight)
        }
        PathfinSnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavigationBottomInset.current), tag = "media-tools-feedback")
    }
    remoteSearchType?.let { type -> key(session, item.id) {
        RemoteImageSearchSheet(management, session, item.id, type, onDismiss = { remoteSearchType = null },
            onAuthExpired = onAuthExpired, onSelect = { image ->
                remoteSearchType = null
                action(tr("图片已更新"), refresh = { images = management.images(session, item.id) }) {
                    management.applyRemoteImage(session, item.id, image)
                }
            })
    } }
    deleteImage?.let { image -> AppAlertDialog(onDismissRequest = { deleteImage = null }, title = { Text(tr("删除%1\$s？", imageEditorLabel(image.type))) }, text = {
        Text(tr("将删除服务器上的这张图片，媒体文件不会受影响。"))
    }, confirmButton = { DialogAction(tr("删除"), { deleteImage = null; action(tr("图片已删除"), refresh = { images = management.images(session, item.id) }) {
        management.deleteImage(session, item.id, image)
    } }, role = DialogActionRole.Danger) }, dismissButton = { DialogAction(tr("取消"), { deleteImage = null }) }) }
    if (leaveDialog) AppAlertDialog(onDismissRequest = { leaveDialog = false }, title = { Text(tr("放弃未保存的修改？")) },
        confirmButton = { DialogAction(tr("放弃修改"), { leaveDialog = false; allowBack = true }, role = DialogActionRole.Danger) },
        dismissButton = { DialogAction(tr("继续编辑"), { leaveDialog = false }) })
    }
}

private val editableImageTypes = listOf(MediaImageType.PRIMARY, MediaImageType.LOGO, MediaImageType.THUMB,
    MediaImageType.BANNER, MediaImageType.DISC, MediaImageType.ART)

/** 正在编辑人物的图片：人物在 Jellyfin 里只有头像（Primary），不提供影视的 Logo、横幅、光盘等格子。 */
private val LocalPersonImages = staticCompositionLocalOf { false }

@Composable
private fun imageEditorLabel(type: MediaImageType): String = when (type) {
    MediaImageType.PRIMARY -> if (LocalPersonImages.current) tr("头像") else tr("封面")
    MediaImageType.LOGO -> "Logo"
    MediaImageType.DISC -> tr("光盘")
    else -> type.label
}

@Composable
private fun MediaImageCard(type: MediaImageType, image: MediaImage?, session: Session, api: JellyfinApi,
    management: MediaManagementApi, itemId: String, busy: Boolean, modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null, onUpload: (() -> Unit)? = null, onDelete: (MediaImage) -> Unit) {
    val context = LocalContext.current
    val label = imageEditorLabel(type)
    val url = image?.let { management.imageUrl(session, itemId, it, 480) }
    val request = remember(url, session) {
        val headers = NetworkHeaders.Builder().apply { api.imageHeaders(session).forEach { (name, value) -> set(name, value) } }.build()
        ImageRequest.Builder(context).data(url).httpHeaders(headers).apply { if (url != null) {
            val key = PosterImageCache.requestKey(session, url); diskCacheKey(key); memoryCacheKey(key)
        } }.build()
    }
    // 白卡片 + 内嵌预览框：有图显示原图，没有图只放一个淡色细线图标；操作键用裸细线图标。
    Column(modifier.clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card).padding(8.dp)
        .testTag(if (type == MediaImageType.BACKDROP) "media-image/Backdrop/${image?.index}" else "media-image/${type.apiValue}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(10.dp))
                .background(if (type == MediaImageType.LOGO) Color(0xFF5E6368) else PathfinAppearance.softFill), contentAlignment = Alignment.Center) {
                if (url != null) AsyncImage(request, label, imageLoader = LocalPosterLoader.current,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Icon(PathfinIcons.ImageAdd, null, Modifier.size(26.dp), tint = PathfinAppearance.textMuted)
            }
            Text(label, Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(image?.let { current -> buildList {
                if (current.width != null && current.height != null) add("${current.width} × ${current.height}")
                if (current.size > 0) add(if (current.size < 1024) "${current.size} B" else "${current.size / 1024} KB")
            }.joinToString(" · ").ifEmpty { "—" } } ?: "—", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = PathfinAppearance.textMuted, modifier = Modifier.padding(top = 2.dp).testTag("media-image-dimensions/${type.apiValue}/${image?.index ?: 0}"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                onSearch?.let { search -> PathfinIconButton(PathfinIcons.ImageSearch, tr("搜索%1\$s", label), search,
                    Modifier.testTag("media-image-search/${type.apiValue}"), enabled = !busy, tint = PathfinAppearance.textSub) }
                onUpload?.let { upload -> PathfinIconButton(PathfinIcons.Upload, tr("上传%1\$s", label), upload,
                    Modifier.testTag("media-image-upload/${type.apiValue}"), enabled = !busy, tint = PathfinAppearance.textSub) }
                if (image != null) PathfinIconButton(PathfinIcons.Trash, tr("删除%1\$s", label), { onDelete(image) },
                    Modifier.testTag("media-image-delete/${type.apiValue}/${image.index}"), enabled = !busy,
                    tint = MaterialTheme.colorScheme.error)
            }
    }
}

private data class RemoteImageQuery(val type: MediaImageType, val provider: String? = null, val allLanguages: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteImageSearchSheet(management: MediaManagementApi, session: Session, itemId: String, initialType: MediaImageType,
    onDismiss: () -> Unit, onAuthExpired: () -> Unit, onSelect: (RemoteMediaImage) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val grid = rememberLazyGridState()
    var query by remember { mutableStateOf(RemoteImageQuery(initialType)) }
    var displayedQuery by remember { mutableStateOf<RemoteImageQuery?>(null) }
    var providers by remember { mutableStateOf<List<MediaImageProvider>>(emptyList()) }
    var providersError by remember { mutableStateOf<String?>(null) }
    var images by remember { mutableStateOf<List<RemoteMediaImage>>(emptyList()) }
    var page by remember { mutableStateOf<RemoteMediaImagePage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var appending by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<RemoteMediaImage?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var originalPreview by rememberSaveable { mutableStateOf(false) }

    fun changeQuery(value: RemoteImageQuery) {
        if (query == value) return
        generation++
        request?.cancel()
        selected = null
        error = null
        loading = true
        appending = false
        query = value
    }
    fun load(append: Boolean) {
        val previousPage = page
        if (append && (loading || appending || displayedQuery != query || previousPage?.hasMore != true || error != null)) return
        val currentQuery = query
        val currentGeneration = ++generation
        request?.cancel()
        loading = !append
        appending = append
        error = null
        val cursor = if (append) requireNotNull(previousPage).startIndex + previousPage.returnedCount else 0
        request = scope.launch {
            try {
                val result = management.searchImages(session, itemId, currentQuery.type, currentQuery.provider,
                    currentQuery.allLanguages, startIndex = cursor)
                if (generation != currentGeneration || query != currentQuery) return@launch
                images = (if (append) images + result.images else result.images).distinctBy { it.url }
                displayedQuery = currentQuery
                page = result
                if (!append) grid.scrollToItem(0)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == currentGeneration) {
                    if (failure is ApiException && failure.statusCode == 401) onAuthExpired()
                    error = toolsError(failure)
                }
            } finally {
                if (generation == currentGeneration) { loading = false; appending = false }
            }
        }
    }
    LaunchedEffect(query, refreshVersion) { load(false) }
    LaunchedEffect(refreshVersion) {
        providersError = null
        try { providers = management.imageProviders(session, itemId) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is ApiException && failure.statusCode == 401) onAuthExpired()
            providersError = toolsError(failure)
        }
    }
    DisposableEffect(Unit) { onDispose { request?.cancel() } }
    LaunchedEffect(grid) {
        snapshotFlow {
            val lastVisible = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (!loading && !appending && error == null && displayedQuery == query && page?.hasMore == true &&
                lastVisible >= 0 && lastVisible >= images.size - 6) page?.let { it.startIndex + it.returnedCount } else null
        }.distinctUntilChanged().collect { cursor -> if (cursor != null) load(true) }
    }

    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PathfinAppearance.card, dragHandle = null, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        // 先固定外高，再在内部避让安全区；动态顶部 inset 不再改变展开锚点。
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).windowInsetsPadding(AppSafeDrawingInsets).testTag("remote-image-search")) {
            // 标题即类型切换；右侧来源切换与之同为无底文字下拉，菜单统一用 MenuRow。
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { typeMenu = true }
                        .padding(horizontal = 8.dp).testTag("remote-image-type"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("选择%1\$s", imageEditorLabel(query.type)), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                        Icon(PathfinIcons.ChevronDown, null, Modifier.size(16.dp), tint = PathfinAppearance.textSub)
                    }
                    AppDropdownMenu(typeMenu, { typeMenu = false }, shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
                        // 人物只列元数据提供程序真正能给的类型（通常只有头像）。
                        val person = LocalPersonImages.current
                        MediaImageType.entries.filterNot { it == MediaImageType.PROFILE || it == MediaImageType.CHAPTER }
                            .filter { !person || it == MediaImageType.PRIMARY || providers.any { provider -> it in provider.supportedTypes } }.forEach { type ->
                            MenuRow(imageEditorLabel(type), {
                                typeMenu = false
                                val provider = query.provider?.takeIf { name -> providers.any { it.name == name && type in it.supportedTypes } }
                                changeQuery(query.copy(type = type, provider = provider))
                            }, Modifier.testTag("remote-image-type/${type.apiValue}"), checked = type == query.type)
                        }
                    }
                }
                Box {
                    Row(Modifier.widthIn(max = 180.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { providerMenu = true }.padding(horizontal = 8.dp).testTag("remote-image-provider"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(query.provider ?: tr("全部来源"), Modifier.weight(1f, fill = false), fontSize = 13.sp, color = PathfinAppearance.textSub,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(PathfinIcons.ChevronDown, null, Modifier.size(14.dp), tint = PathfinAppearance.textMuted)
                    }
                    AppDropdownMenu(providerMenu, { providerMenu = false }, shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
                        MenuRow(tr("全部来源"), { providerMenu = false; changeQuery(query.copy(provider = null)) },
                            Modifier.testTag("remote-image-provider/all"), checked = query.provider == null)
                        providers.filter { query.type in it.supportedTypes }.forEach { provider ->
                            MenuRow(provider.name, { providerMenu = false; changeQuery(query.copy(provider = provider.name)) },
                                Modifier.testTag("remote-image-provider/${provider.name}"), checked = query.provider == provider.name)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PathfinCheckbox(tr("原图预览"), originalPreview, { originalPreview = it }, Modifier.testTag("remote-image-original"))
                PathfinCheckbox(tr("全部语言"), query.allLanguages, { changeQuery(query.copy(allLanguages = it)) }, Modifier.testTag("remote-image-all-languages"))
                Spacer(Modifier.weight(1f))
                Text(tr("%1\$s 项", if (displayedQuery == query) page?.totalCount ?: 0 else 0), fontSize = 12.sp,
                    color = PathfinAppearance.textMuted, modifier = Modifier.testTag("remote-image-count"))
            }
            providersError?.let { ErrorPanel(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            error?.let { ErrorPanel(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            RefreshablePage(loading, { refreshVersion++ }, Modifier.weight(1f).fillMaxWidth(), refreshOnEntry = false) {
                LazyVerticalGrid(GridCells.Fixed(3), state = grid,
                    modifier = Modifier.fillMaxSize().testTag("remote-image-results"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = LocalNavigationBottomInset.current),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(images, key = { it.url }) { image ->
                        // 此请求刻意没有服务器 Header；提供商预览不复用媒体图片的认证请求。
                        val preview = remember(image, originalPreview) {
                            ImageRequest.Builder(context).data(if (originalPreview) image.url else image.thumbnailUrl ?: image.url)
                                .httpHeaders(NetworkHeaders.Builder().build()).diskCachePolicy(coil3.request.CachePolicy.DISABLED).build()
                        }
                        Column(Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !loading && displayedQuery == query, role = Role.Button) { selected = image }
                            .testTag("remote-image-result")) {
                            AsyncImage(preview, "${image.providerName} ${imageEditorLabel(image.type)}", imageLoader = LocalPosterLoader.current,
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()
                                    .aspectRatio(if (image.type == MediaImageType.PRIMARY) 2f / 3f else 16f / 9f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PathfinAppearance.softFill))
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text(image.providerName, fontSize = 12.sp, lineHeight = 16.sp, color = PathfinAppearance.textMain,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (image.width != null && image.height != null) "${image.width} × ${image.height}" else "—", fontSize = 11.sp,
                                    lineHeight = 15.sp, maxLines = 1, color = PathfinAppearance.textMuted)
                            }
                        }
                    }
                    if (!loading && images.isEmpty() && error == null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        EmptyPanel(tr("没有找到图片"), tr("可更换类型、提供商或语言后查询；结果来自此媒体的 Jellyfin 图片提供商。"))
                    }
                }
            }
        }
    }
    selected?.let { image -> AppAlertDialog(onDismissRequest = { selected = null }, title = { Text(tr("选用这张%1\$s？", imageEditorLabel(image.type))) },
        text = {
            Text(tr("由 Jellyfin 从 %1\$s 下载。", image.providerName) + if (image.type == MediaImageType.BACKDROP) tr("将添加为一张背景图。") else tr("将替换已有的同类型图片。"))
        }, confirmButton = { DialogAction(tr("选用图片"), { selected = null; onSelect(image) }, Modifier.testTag("remote-image-apply"), role = DialogActionRole.Primary) },
        dismissButton = { DialogAction(tr("取消"), { selected = null }) }) }
}

@Composable
private fun MetadataEditor(document: EditableMediaMetadata, person: Boolean, externalIdFields: List<ExternalIdField>, externalIdsError: String?, busy: Boolean, saving: Boolean, titleBarHeight: Dp,
    onDirty: (Boolean) -> Unit, onSave: (MediaMetadata) -> Unit) {
    val original = document.metadata
    var title by rememberSaveable(document.itemId, original) { mutableStateOf(original.title) }
    var originalTitle by rememberSaveable(document.itemId, original) { mutableStateOf(original.originalTitle) }
    var sortTitle by rememberSaveable(document.itemId, original) { mutableStateOf(original.sortTitle) }
    var overview by rememberSaveable(document.itemId, original) { mutableStateOf(original.overview) }
    var year by rememberSaveable(document.itemId, original) { mutableStateOf(original.productionYear?.toString().orEmpty()) }
    // 日期与详情页一样保留服务端日历日期；未改动时保存沿用完整服务器原值。
    fun editorDate(value: String?) = value?.let { serverCalendarDate(it)?.toString() ?: it.take(10) }.orEmpty()
    var premiere by rememberSaveable(document.itemId, original) { mutableStateOf(editorDate(original.premiereDate)) }
    var genres by rememberSaveable(document.itemId, original) { mutableStateOf(original.genres.joinToString(tr("，"))) }
    var tags by rememberSaveable(document.itemId, original) { mutableStateOf(original.tags.joinToString(tr("，"))) }
    var rating by rememberSaveable(document.itemId, original) { mutableStateOf(original.officialRating.orEmpty()) }
    var customRating by rememberSaveable(document.itemId, original) { mutableStateOf(original.customRating.orEmpty()) }
    var community by rememberSaveable(document.itemId, original) { mutableStateOf(original.communityRating?.toString().orEmpty()) }
    var critic by rememberSaveable(document.itemId, original) { mutableStateOf(original.criticRating?.toString().orEmpty()) }
    var locked by rememberSaveable(document.itemId, original) { mutableStateOf(original.lockData) }
    var death by rememberSaveable(document.itemId, original) { mutableStateOf(editorDate(original.endDate)) }
    var birthplace by rememberSaveable(document.itemId, original) { mutableStateOf(original.productionLocations.firstOrNull().orEmpty()) }
    // 与其余输入框一样保留旋转时的编号草稿；每次写入替换 Map，避免原地修改丢失重组通知。
    var providerIds by rememberSaveable(document.itemId, original, stateSaver = ProviderIdDraftSaver) {
        mutableStateOf(original.providerIds.toMap())
    }
    val idFields = (externalIdFields.map { field ->
        field.copy(key = original.providerIds.keys.firstOrNull { it.equals(field.key, ignoreCase = true) } ?: field.key)
    } + original.providerIds.keys.map { ExternalIdField(it, it) }).distinctBy { it.key.lowercase() }
    val editedProviderIds = original.providerIds.toMutableMap().apply {
        providerIds.forEach { (key, value) -> if (value != original.providerIds[key]) {
            if (value.isBlank()) remove(key) else put(key, value.trim())
        } }
    }
    val value = original.copy(title = title, originalTitle = originalTitle, sortTitle = sortTitle, overview = overview,
        productionYear = if (person) original.productionYear else year.toIntOrNull(),
        premiereDate = if (premiere == editorDate(original.premiereDate)) original.premiereDate else premiere.takeIf(String::isNotBlank),
        genres = if (person) original.genres else splitMetadataList(genres), tags = splitMetadataList(tags),
        officialRating = if (person) original.officialRating else rating.takeIf(String::isNotBlank),
        customRating = if (person) original.customRating else customRating.takeIf(String::isNotBlank),
        communityRating = if (person) original.communityRating else community.toDoubleOrNull(),
        criticRating = if (person) original.criticRating else critic.toDoubleOrNull(), lockData = locked,
        endDate = if (!person || death == editorDate(original.endDate)) original.endDate else death.takeIf(String::isNotBlank),
        // 只编辑第一条出生地，其余（服务器常存同一地点的多种写法）原样保留。
        productionLocations = if (!person || birthplace == original.productionLocations.firstOrNull().orEmpty()) original.productionLocations
            else listOfNotNull(birthplace.trim().takeIf(String::isNotEmpty)) + original.productionLocations.drop(1),
        // 只提交实际修改的编号；未修改的服务器值（含空值）原样保留。
        providerIds = editedProviderIds)
    val validYear = year.isBlank() || year.toIntOrNull()?.let { it in 1..9999 } == true
    val validDate = premiere.isBlank() || runCatching { LocalDate.parse(premiere) }.isSuccess
    val validDeath = death.isBlank() || runCatching { LocalDate.parse(death) }.isSuccess
    val validCommunity = community.isBlank() || community.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10.0 } == true
    val validCritic = critic.isBlank() || critic.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true
    val changed = value != original || year != original.productionYear?.toString().orEmpty() ||
        community != original.communityRating?.toString().orEmpty() || critic != original.criticRating?.toString().orEmpty()
    LaunchedEffect(changed) { onDirty(changed) }
    val topInset = LocalPageTopInset.current
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().topBarBlurSource().background(PathfinAppearance.canvas).testTag("metadata-form"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + titleBarHeight + 12.dp, bottom = LocalNavigationBottomInset.current + 8.dp)) {
        // 与设置页同一语言：每个字段一个条目，按首/中/末拼成一张白卡片，行间细线；条目粒度不变，滚动恢复与原先一致。
        item(key = "metadata-path") {
            SettingsGroupItem(GroupPosition.SINGLE) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("媒体路径"), fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub)
                    SelectionContainer {
                        Text(mediaInformationPath(document.path)?.let(::mediaInformationDisplayPath) ?: tr("服务器未提供路径"), fontSize = 13.5.sp, lineHeight = 19.sp,
                            color = PathfinAppearance.textMain, modifier = Modifier.fillMaxWidth().testTag("metadata-path"))
                    }
                }
            }
        }
        // 人物只编辑生平相关字段；评分、分级与类型对人物没有意义。
        if (person) {
            item(key = "metadata-basic-label") { Eyebrow(tr("基本信息"), Modifier.padding(start = 6.dp, bottom = 8.dp)) }
            item { SettingsGroupItem(GroupPosition.TOP) { MetadataField(tr("姓名"), title, { title = it; onDirty(true) }, busy, tag = "metadata-title", required = true) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("简介"), overview, { overview = it; onDirty(true) }, busy, multiline = true) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("出生日期（YYYY-MM-DD）"), premiere, { premiere = it; onDirty(true) }, busy, error = !validDate, tag = "metadata-birth") } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("去世日期（YYYY-MM-DD）"), death, { death = it; onDirty(true) }, busy, error = !validDeath, tag = "metadata-death") } }
            item { SettingsGroupItem(GroupPosition.BOTTOM) { MetadataField(tr("出生地"), birthplace, { birthplace = it; onDirty(true) }, busy, tag = "metadata-birthplace") } }
            item(key = "metadata-other-label") { Eyebrow(tr("其他信息"), Modifier.padding(start = 6.dp, bottom = 8.dp)) }
            item { SettingsGroupItem(GroupPosition.TOP) { MetadataField(tr("原始名称"), originalTitle, { originalTitle = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("排序名称"), sortTitle, { sortTitle = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.BOTTOM) { MetadataField(tr("标签（逗号分隔）"), tags, { tags = it; onDirty(true) }, busy) } }
        } else {
            item(key = "metadata-basic-label") { Eyebrow(tr("基本信息"), Modifier.padding(start = 6.dp, bottom = 8.dp)) }
            item { SettingsGroupItem(GroupPosition.TOP) { MetadataField(tr("标题"), title, { title = it; onDirty(true) }, busy, tag = "metadata-title", required = true) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("简介"), overview, { overview = it; onDirty(true) }, busy, multiline = true) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("社区评分（0–10）"), community, { community = it; onDirty(true) }, busy, numeric = true, error = !validCommunity, tag = "metadata-community-rating") } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("首映日期（YYYY-MM-DD）"), premiere, { premiere = it; onDirty(true) }, busy, error = !validDate) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("类型（逗号分隔）"), genres, { genres = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.BOTTOM) { MetadataField(tr("官方分级"), rating, { rating = it; onDirty(true) }, busy) } }
            item(key = "metadata-other-label") { Eyebrow(tr("其他信息"), Modifier.padding(start = 6.dp, bottom = 8.dp)) }
            item { SettingsGroupItem(GroupPosition.TOP) { MetadataField(tr("原始标题"), originalTitle, { originalTitle = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("排序标题"), sortTitle, { sortTitle = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("年份"), year, { year = it; onDirty(true) }, busy, numeric = true, error = !validYear, tag = "metadata-year") } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("标签（逗号分隔）"), tags, { tags = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.MIDDLE) { MetadataField(tr("自定义分级"), customRating, { customRating = it; onDirty(true) }, busy) } }
            item { SettingsGroupItem(GroupPosition.BOTTOM) { MetadataField(tr("影评人评分（0–100）"), critic, { critic = it; onDirty(true) }, busy, numeric = true, error = !validCritic) } }
        }
        if (idFields.isNotEmpty() || externalIdsError != null) {
            item(key = "metadata-ids-label") { Eyebrow(tr("外部编号"), Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp)) }
            externalIdsError?.let { message -> item(key = "metadata-ids-error") { ErrorPanel(message) } }
            itemsIndexed(idFields, key = { _, field -> "metadata-id/${field.key}" }) { index, field ->
                val position = when {
                    idFields.size == 1 -> GroupPosition.SINGLE
                    index == 0 -> GroupPosition.TOP
                    index == idFields.lastIndex -> GroupPosition.BOTTOM
                    else -> GroupPosition.MIDDLE
                }
                SettingsGroupItem(position) {
                    MetadataField(field.name, providerIds[field.key].orEmpty(), { providerIds = providerIds + (field.key to it); onDirty(true) }, busy,
                        tag = "metadata-id/${field.key}")
                }
            }
        }
        item(key = "metadata-lock") {
            SettingsGroupItem(GroupPosition.SINGLE) {
                GroupToggle(PathfinIcons.Lock, tr("锁定全部元数据，防止自动更新覆盖"), locked, !busy, { locked = it; onDirty(true) },
                    Modifier.testTag("metadata-lock-all"), divider = false)
            }
        }
    }
    val validVisibleFields = validDate && if (person) validDeath else validYear && validCommunity && validCritic
    MetadataTitleBar(topInset, titleBarHeight, saving,
        canSave = !busy && changed && title.isNotBlank() && validVisibleFields, onSave = { onSave(value) })
    }
}

/** 编辑页只有一行标题栏；加载前后共用，返回仍交给草稿确认处理。 */
@Composable
private fun MetadataTitleBar(topInset: Dp, height: Dp, saving: Boolean = false, canSave: Boolean = false, onSave: () -> Unit = {}) {
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    PageTitleBar(Modifier.fillMaxWidth().height(topInset + height).pointerInput(Unit) { detectTapGestures { } },
        height = topInset + height) {
        Row(Modifier.fillMaxWidth().padding(top = topInset).height(height).padding(horizontal = 8.dp)
            .testTag("metadata-editor-header"), verticalAlignment = Alignment.CenterVertically) {
            PathfinIconButton(PathfinIcons.Back, tr("返回"), { backDispatcher?.onBackPressed() },
                Modifier.testTag("metadata-back"), tint = PathfinAppearance.textMain, size = 44.dp)
            Text(tr("编辑元数据"), Modifier.weight(1f).testTag("metadata-page-title"), fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
            QuietButton(if (saving) tr("保存中…") else tr("保存"), onSave,
                Modifier.width(88.dp).height(height).testTag("metadata-save"),
                enabled = canSave, color = if (canSave) PathfinAppearance.accent else PathfinAppearance.textMuted)
        }
    }
}

/** 分组卡片里的一行字段（分隔线由 SettingsGroupItem 画）：小标签在上、无框输入在下；出错时标签变红，必填加星号；只读时不暴露写入动作。 */
@Composable
private fun MetadataField(label: String, value: String, onChange: (String) -> Unit, busy: Boolean,
    multiline: Boolean = false, numeric: Boolean = false, error: Boolean = false, tag: String = "", required: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.5.sp, lineHeight = 17.sp,
                    color = if (error) MaterialTheme.colorScheme.error else PathfinAppearance.textSub)
                if (required) Text("*", color = PathfinAppearance.accent, fontSize = 12.5.sp)
            }
            BasicTextField(value, { if (it != value) onChange(it) }, Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
                readOnly = busy, singleLine = !multiline, minLines = if (multiline) 3 else 1,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 22.sp,
                    color = if (busy) PathfinAppearance.textMuted else PathfinAppearance.textMain),
                cursorBrush = SolidColor(PathfinAppearance.accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(tr("未填写"), fontSize = 15.5.sp, lineHeight = 22.sp, color = PathfinAppearance.textMuted)
                        inner()
                    }
                })
        }
    }
}

private fun splitMetadataList(value: String) = value.split(',', '，', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
private fun toolsError(error: Exception): String = when (error) {
    is ApiException, is IllegalArgumentException -> error.message ?: tr("操作失败，请重试")
    else -> tr("操作失败，请检查连接或文件权限后重试")
}

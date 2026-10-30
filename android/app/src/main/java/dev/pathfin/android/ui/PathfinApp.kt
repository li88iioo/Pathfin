@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.pathfin.android.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import android.app.Activity
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.pathfin.android.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import dev.pathfin.android.R
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.Session
import dev.pathfin.android.data.PerformanceTrace
import dev.pathfin.android.data.PosterImageCache
import okhttp3.OkHttpClient
import java.net.URI

internal val LocalPosterLoader = staticCompositionLocalOf<ImageLoader> { error("Poster loader is not configured") }
internal val LocalNavigationBottomInset = staticCompositionLocalOf<Dp> { 16.dp }
internal val LocalAppPreferences = staticCompositionLocalOf { AppPreferences() }
private class RememberedPosterLoader(val loader: ImageLoader) : androidx.compose.runtime.RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = PosterImageCache.release(loader)
    override fun onAbandoned() = PosterImageCache.release(loader)
}
private data class Tab(val route: Route, val title: String, val icon: ImageVector)
// 每次读取都按当前界面语言取标题。
private val tabs: List<Tab> get() = listOf(Tab(Route.Home, tr("首页"), PathfinIcons.Home), Tab(Route.Search, tr("搜索"), PathfinIcons.Search), Tab(Route.Favorites, tr("收藏"), PathfinIcons.Star))

internal fun AppState.savedPageKey(route: Route = this.route): String {
    val visit = when (route) {
        is Route.Folder -> route.visitId
        is Route.Browse -> route.visitId
        Route.Search -> searchVisitId
        else -> null
    }
    return "$currentProfileId/${route.key}${visit?.let { "/$it" }.orEmpty()}"
}

@Composable
internal fun ReleaseDepartedPageStates(state: AppState, savedState: SaveableStateHolder) {
    val previousPageStates = remember(savedState) { arrayOf<Pair<String?, Set<String>>?>(null) }
    val livePageStates = state.routes.filter { it is Route.Folder || it is Route.Browse || it == Route.Search }
        .map { state.savedPageKey(it) }.toSet()
    SideEffect {
        val previous = previousPageStates[0]
        // 只用当前导航栈清理；离场动画的旧快照不能恢复已经退出的库位置，也不能清理另一账号。
        if (previous != null && previous.first == state.currentProfileId)
            (previous.second - livePageStates).forEach(savedState::removeState)
        previousPageStates[0] = state.currentProfileId to livePageStates
    }
}

@Composable
internal fun rememberPosterLoader(session: Session?, cacheSizeMb: Int = 1024): ImageLoader {
    val context = LocalContext.current.applicationContext
    val account = session?.let(PosterImageCache::accountKey)
    return key(account, cacheSizeMb) {
        val warm = remember { session?.let {
            PosterImageCache.acquireWarmLoader(context, it, cacheSizeMb.toLong() * 1024 * 1024)?.let(::RememberedPosterLoader)
        } }
        if (warm != null) {
            warm.loader
        } else {
            val client = remember { PerformanceTrace.monitor(OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).build(), "image") }
            // 准备磁盘期间使用独立内存loader，不让新账号使用旧账号的磁盘实例。
            val fallback = remember { PosterImageCache.createMemoryLoader(context, client) }
            val loader by produceState(fallback) {
                var prepared: ImageLoader? = null
                try {
                    // prepareLoader负责IO返回期间取消的资源回收，旧容量准备可以立即取消。
                    prepared = PosterImageCache.prepareLoader(context, session, client, cacheSizeMb.toLong() * 1024 * 1024)
                    currentCoroutineContext().ensureActive()
                    value = requireNotNull(prepared)
                    awaitCancellation()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { value = fallback; awaitCancellation() }
                finally {
                    withContext(NonCancellable + Dispatchers.IO) { prepared?.let(PosterImageCache::release) }
                }
            }
            DisposableEffect(fallback) { onDispose {
                fallback.shutdown()
                dev.pathfin.android.playback.PlaybackClientCleanup.release(client)
            } }
            loader
        }
    }
}

@Composable
fun PathfinApp(
    vm: AppViewModel,
    state: AppState,
    savedState: SaveableStateHolder,
    posterLoader: ImageLoader = rememberPosterLoader(state.session, state.preferences.imageCacheSizeMb),
) {
    val session = state.session
    val insideServer = session != null && !state.showServers
    val route = state.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val activityResults = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    var downloadDestination by remember(activityResults, vm) { mutableStateOf<ActivityResultLauncher<String>?>(null) }
    DisposableEffect(activityResults, vm) {
        // 同一个 Activity 的根内容重建也沿用稳定 key；系统结果仍交给原 VM claim。
        val launcher = activityResults.register("pathfin.download.destination",
            ActivityResultContracts.CreateDocument("application/octet-stream"), vm::selectDownloadDestination)
        downloadDestination = launcher
        onDispose { launcher.unregister() }
    }
    LaunchedEffect(state.pendingDownload?.requestId, state.pendingDownload?.needsDestination, downloadDestination) {
        val selection = state.pendingDownload?.takeIf { it.needsDestination } ?: return@LaunchedEffect
        val launcher = downloadDestination ?: return@LaunchedEffect
        if (vm.claimDownloadDestination(selection.requestId)) {
            try { launcher.launch(selection.fileName) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.downloadDestinationLaunchFailed(selection.requestId) }
        }
    }
    LaunchedEffect(state.downloadNotificationRequestId) {
        val requestId = state.downloadNotificationRequestId ?: return@LaunchedEffect
        if (vm.consumeDownloadNotification(requestId) && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    ReleaseDepartedPageStates(state, savedState)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); vm.dismissNotice() } }
    CompositionLocalProvider(LocalPosterLoader provides posterLoader, LocalAppPreferences provides state.preferences) {
        AppBackHandler(enabled = insideServer || state.routes.size > 1 || route != Route.Home,
            contentKey = state.currentProfileId to route, onBack = vm::back,
            modifier = Modifier.fillMaxSize(), testTag = "browser-back-motion") {
            PageTransitionHost(state) { page -> PathfinPage(vm, page, savedState, snackbar) }
        }
        if (session != null && state.currentProfileId != null) state.pendingCast?.let { item ->
            CastSheet(item, state.currentProfileId, session, vm.api, onDismiss = vm::dismissCast,
                onLocalNetworkPermissionRequired = vm::requestLocalNetworkAccess,
                onAuthExpired = { vm.authenticationExpired(session) }, onStarted = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
        }
        if (session != null) state.pendingSubtitleManagement?.let { item ->
            SubtitleManagementSheet(session, item, vm.api, onDismiss = vm::dismissSubtitles,
                onChanged = { vm.mediaChanged(session, it.id) }, verificationScope = scope, onAuthExpired = { vm.authenticationExpired(session) })
        }
        state.reauthenticationProfile?.let { target ->
            key(target.id, target.session.server.baseUrl) {
                ReauthenticateServerDialog(target, state.loginBusy, state.loginError,
                    onDismiss = vm::dismissReauthentication, onSubmit = vm::reauthenticate)
            }
        }
    }
}

@Composable
private fun PathfinPage(vm: AppViewModel, state: AppState, savedState: SaveableStateHolder, snackbar: SnackbarHostState) {
    val session = state.session
    val insideServer = session != null && !state.showServers
    val route = state.route
    val detail = if (route is Route.Detail) state.details[route.id] else null
    val context = LocalContext.current
    var libraryMenu by remember { mutableStateOf(false) }
    var detailMenu by remember { mutableStateOf(false) }
    var refreshConfirmation by remember(route, state.currentProfileId) { mutableStateOf(false) }
    var deleteConfirmation by remember(route) { mutableStateOf(false) }
    val browserKey = when (route) { is Route.Folder -> route.item.id; is Route.Browse -> route.id; else -> null }
    var librarySelection by rememberLibrarySelection(route.key, browserKey?.let { state.browsers[it]?.query })
    var imageEditor by remember(session, route) { mutableStateOf(false) }
    var imageEditorBusy by remember(session, route) { mutableStateOf(false) }
    var detailScrolled by remember(route) { mutableStateOf(false) }
    var navigationVisible by remember { mutableStateOf(true) }
    val pageActive = LocalPageActive.current
    LaunchedEffect(route, pageActive) {
        if (pageActive) navigationVisible = true
        else libraryMenu = false
    }
    val autoHide = state.preferences.autoHideNavigation
    val scroll = remember(autoHide) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (autoHide && source == NestedScrollSource.UserInput) {
                if (available.y < -5f) navigationVisible = false
                else if (available.y > 5f) navigationVisible = true
            }
            return Offset.Zero
        }
    } }
    val profile = state.profiles.firstOrNull { it.id == state.currentProfileId }
    val serverName = profile?.displayName?.takeIf { it.isNotBlank() } ?: session?.let { runCatching { URI(it.server.baseUrl).host }.getOrNull() }.orEmpty()
    val managerSettings = !insideServer && (route == Route.Settings || route is Route.SettingsPage || route == Route.Downloads)
    val showManager = !insideServer && !managerSettings
    val connecting = showManager && (state.profiles.isEmpty() || state.addingServer)
    val showManagerNavigation = !insideServer && !connecting
    val isDetail = insideServer && route is Route.Detail
    val standaloneTool = route is Route.Identification || route == Route.ServerAdministration || route == Route.LiveTv
    val libraryPage = route is Route.Folder || route is Route.Browse || route is Route.LibrarySearch
    val showInnerNavigation = insideServer && route in listOf(Route.Home, Route.Search, Route.Favorites)
    val hasBottomNavigation = showInnerNavigation || showManagerNavigation
    // 没有悬浮导航的页面同样铺到手势条下，列表末尾自己让出手势条高度。
    val navigationBarHeight = AppNavigationBarInsets.asPaddingValues().calculateBottomPadding()
    val navigationBottomInset = (if (hasBottomNavigation) 88.dp else 16.dp) + navigationBarHeight
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    // 在组合中读取滚动状态：只在SideEffect里读会漏掉详情子树独立重组后的前景切换。
    val lightStatusBars = lightSurface && (!isDetail || detailScrolled)
    SideEffect {
        if (pageActive) (context as? Activity)?.let { activity -> WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = lightStatusBars }
    }

    val discoveryPage = insideServer && (route == Route.Search || route == Route.Favorites)
    // 搜索与元数据编辑各自绘制完整顶栏，这里只留出状态栏。
    val ownTopBar = discoveryPage || route is Route.LibrarySearch || route is Route.MediaTools
    val hasHeader = !isDetail && !showManager && !standaloneTool && !ownTopBar
    val headerInset = when {
        hasHeader -> 60.dp + AppSafeDrawingInsets.asPaddingValues().calculateTopPadding()
        ownTopBar -> AppSafeDrawingInsets.asPaddingValues().calculateTopPadding()
        else -> 0.dp
    }
    val insetInContent = route == Route.Home || route == Route.Libraries || route == Route.Search ||
        route == Route.Favorites || route is Route.Discovery || libraryPage || route == Route.Settings || route is Route.SettingsPage || route is Route.MediaTools
    val scrollToTopRequests = remember { mutableIntStateOf(0) }
    val topBarBlur = rememberTopBarBlurState(state.preferences.blurTopBar)
    // 带固定工具栏的页面自己提供采样源，工具栏作为标题栏第二行一起模糊。
    val pageOwnsBlurSource = route == Route.Search || libraryPage || route is Route.MediaTools
    CompositionLocalProvider(LocalNavigationBottomInset provides navigationBottomInset,
        LocalScrollToTopRequests provides scrollToTopRequests,
        LocalTopBarBlurState provides topBarBlur) {
        // 服务器页的壁纸环境光铺满整窗（含状态栏区），Scaffold 在该页透明以免遮挡。
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .nestedScroll(scroll)) {
            Scaffold(
                containerColor = if (showManager) Color.Transparent else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                snackbarHost = { if (pageActive) PathfinSnackbarHost(snackbar, Modifier.padding(
                    bottom = if (hasBottomNavigation) navigationBottomInset else (if (insideServer) 80.dp else 0.dp) + navigationBarHeight)) },
                contentWindowInsets = when {
                    isDetail -> WindowInsets(0, 0, 0, 0)
                    showManager && !connecting -> AppSafeDrawingInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    // 连接页底图铺到手势条下，底部安全区由连接页的表单与按钮自己让出。
                    connecting -> AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)
                    hasBottomNavigation -> AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)
                    // 底部只为键盘让位；手势条高度并入 LocalNavigationBottomInset，由页面列表留白。
                    else -> AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal).union(WindowInsets.ime)
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .then(if (pageOwnsBlurSource) Modifier else Modifier.topBarBlurSource())) {
                    CompositionLocalProvider(LocalPageTopInset provides if (insetInContent) headerInset else 0.dp) {
                    Box(Modifier.fillMaxSize().then(if (hasHeader && !insetInContent) Modifier.padding(top = headerInset) else Modifier)) {
                    if (showManager) {
                        ServerManagementScreen(state.profiles, state.currentProfileId, state.loginBusy, state.loginError,
                            switching = state.switchingProfileId != null,
                            probe = state.serverProbe,
                            adding = state.addingServer,
                            showcase = state.home.takeIf { it.loaded }?.let { home ->
                                ServerShowcase((home.recent + home.libraryItems.values.flatten()).distinctBy { it.id }.take(15), home.resume.firstOrNull())
                            },
                            onProbe = { vm.probeServer(it.baseUrl, it.allowInsecureHttp) },
                            onAddServer = vm::beginAddServer,
                            onConnectBack = vm::backFromConnect,
                            onOpen = vm::openServer,
                            onAdd = { config, user, password, name -> vm.login(config.baseUrl, user, password, config.allowInsecureHttp, name) },
                            onUpdate = vm::updateServer, onDelete = vm::deleteServer, onAddAddress = vm::addServerAddress,
                            onPassword = vm::changePassword, onDismissError = vm::clearLoginError, api = vm.api)
                    } else if (route is Route.MediaTools && session != null) {
                        // 编辑页只保留当前访问/旋转的草稿，明确放弃后重新进入从服务器读取。
                        MediaToolsScreen(route.item, session, vm.api, "metadata",
                            onChanged = { vm.mediaChanged(session, route.item.id) }, onAuthExpired = { vm.authenticationExpired(session) })
                    } else if (route is Route.Identification && session != null) {
                        MediaIdentificationScreen(route.item, session, vm.api, onBack = { vm.back() },
                            onChanged = { vm.mediaChanged(session, route.item.id) }, onAuthExpired = { vm.authenticationExpired(session) })
                    } else if (route == Route.LiveTv && session != null) {
                        state.liveTv?.let { liveTv -> LiveTvScreen(liveTv, onBack = { vm.back() }, onPlay = { vm.preparePlayback(it, true) },
                            preparingId = state.pendingPlayback?.item?.id?.takeIf { state.preparingPlay }) }
                    } else if (route == Route.ServerAdministration && session != null) {
                        ServerAdministrationScreen(session, vm.api, onBack = { vm.back() }, onAuthExpired = { vm.authenticationExpired(session) })
                    } else savedState.SaveableStateProvider(state.savedPageKey()) {
                        when (route) {
                            Route.Downloads -> DownloadsScreen(state.currentProfileId)
                            Route.Settings, is Route.SettingsPage -> SettingsScreen(state, vm::setTheme, vm::openSettings, vm::setPreferences, vm::openDownloads, vm::importSettings)
                            else -> if (session != null) when (route) {
                                Route.Home -> {
                                    // 直播媒体库不是普通条目集合（套用浏览页只会是空的），不出现在首页卡片与海报栏，直播只从“更多”进入。
                                    val visibleHome = remember(state.home, state.hiddenLibraries) {
                                        state.home.copy(
                                            libraries = state.home.libraries.filterNot { it.id in state.hiddenLibraries || it.collectionType == "livetv" },
                                            libraryItems = state.home.libraryItems.filterKeys { it !in state.hiddenLibraries },
                                            recent = if (state.home.libraries.isNotEmpty()) emptyList() else state.home.recent)
                                    }
                                    HomeScreen(visibleHome, session, vm.api, vm::open, vm::refreshHomeFromPage)
                                }
                                Route.Libraries -> LibrariesScreen(state.home.copy(libraries = state.home.libraries.filterNot { it.collectionType == "livetv" }),
                                    session, vm.api, vm::open, vm::refreshHomeFromPage)
                                Route.Search -> SearchScreen(state, session, vm)
                                Route.Favorites -> FavoritesScreen(state, session, vm)
                                is Route.Discovery -> DiscoveryGridContent(route.category,
                                    (if (route.favorite) state.favorites else state.search.results).sections[route.category] ?: ContentState(),
                                    session, vm.api, onRefresh = { vm.refreshDiscovery(route.category, route.favorite) },
                                    onMore = { vm.loadMoreDiscovery(route.category, route.favorite) }, onOpen = vm::open)
                                is Route.LibrarySearch -> LibrarySearchScreen(
                                    route.library, state.browsers[route.key] ?: BrowserUiState(), session, vm.api,
                                    onSubmit = { vm.submitLibrarySearch(route.library, it) }, onOpen = vm::open,
                                    onRefresh = { vm.refreshBrowser(route.key) }, onMore = { vm.loadBrowser(route.key, true) },
                                    onBack = vm::back, suggest = { vm.librarySearchSuggestions(route.library, it) },
                                )
                                is Route.Folder, is Route.Browse -> {
                                    val key = if (route is Route.Folder) route.item.id else (route as Route.Browse).id
                                    val browser = state.browsers[key] ?: BrowserUiState(content = ContentState(loading = true))
                                    BrowserScreen(browser, session, vm.api,
                                        onQueryChange = { vm.changeBrowserQuery(key, it) }, onStyleChange = { vm.setBrowserStyle(key, style = it) },
                                        onOpen = vm::open, showIndex = route is Route.Folder,
                                        onRetry = { vm.refreshBrowser(key) }, onMore = { vm.loadBrowser(key, true) },
                                        onRetryFilters = { vm.retryBrowserFilters(key) }, libraryType = (route as? Route.Folder)?.item?.collectionType,
                                        libraryCategories = route is Route.Folder && route.item.type in setOf("CollectionFolder", "Folder"),
                                        selection = librarySelection, onSelectionChange = { librarySelection = it })
                                }
                                is Route.Detail -> DetailScreen(state, detail, session, vm, onScrolled = { detailScrolled = it }, onCast = vm::prepareCast)
                                else -> Unit
                            }
                        }
                    }
                    }
                    }
                }
            }
            // 连接页底图本身就是状态栏背景，不再盖一层画布色雾带。
            if (state.preferences.blurTopBar && !hasHeader && !isDetail && !connecting && route != Route.Search && route !is Route.LibrarySearch && route !is Route.MediaTools) {
                val statusHeight = AppSafeDrawingInsets.asPaddingValues().calculateTopPadding()
                if (statusHeight > 0.dp) TopBarBlurSurface(
                    Modifier.fillMaxWidth().height(statusHeight).align(Alignment.TopCenter), height = statusHeight,
                ) {}
            }
            // 标题栏自己接住点击，不再穿透到下面的海报；双击发出“回到顶部”。
            if (hasHeader) PageTitleBar(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scrollToTopRequests.intValue++ }) }, height = headerInset) {
                        Row(Modifier.fillMaxWidth()
                            .windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (route == Route.Home) {
                                ServerSwitcher(state.profiles, state.currentProfileId, session, !state.canSwitchServer, vm.api, vm::openServer)
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text(serverName, color = LocalContentColor.current,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Box {
                                    Box(Modifier.size(44.dp).clip(CircleShape)
                                        .clickable(role = Role.Button) { libraryMenu = true }
                                        .semantics { contentDescription = tr("更多") }.testTag("home-more"),
                                        contentAlignment = Alignment.Center) {
                                        Icon(painterResource(R.drawable.ic_jellyfin), null, Modifier.size(24.dp), tint = Color.Unspecified)
                                    }
                                    AppDropdownMenu(libraryMenu && pageActive, { libraryMenu = false }, Modifier.widthIn(min = 176.dp, max = 260.dp),
                                        shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
                                        MenuRow(tr("设置"), { libraryMenu = false; vm.openSettings() }, icon = PathfinIcons.Sliders)
                                        MenuRow(tr("下载任务"), { libraryMenu = false; vm.openDownloads() }, icon = PathfinIcons.Download)
                                        if (state.preferences.liveTvEnabled) MenuRow(tr("直播电视"), { libraryMenu = false; vm.openLiveTv() }, icon = PathfinIcons.Tv)
                                        if (session?.isAdministrator == true) MenuRow(tr("媒体库控制台"), { libraryMenu = false; vm.openServerAdministration() },
                                            icon = MediaNavigationIcons.Console)
                                        val homeLibraries = state.home.libraries.filterNot { it.collectionType == "livetv" }
                                        if (homeLibraries.isNotEmpty()) {
                                            MenuDivider()
                                            Eyebrow(tr("首页显示的媒体库"), Modifier.padding(start = 18.dp, top = 4.dp, bottom = 2.dp))
                                            homeLibraries.forEach { library ->
                                                MenuRow(library.name, { vm.toggleHomeLibrary(library.id) }, icon = libraryTypeIcon(library),
                                                    checked = library.id !in state.hiddenLibraries)
                                            }
                                        }
                                    }
                                }
                            } else if (browserKey != null && librarySelection.isNotEmpty()) {
                                val selected = state.browsers[browserKey]?.content?.items.orEmpty().filter { it.id in librarySelection }
                                LibrarySelectionBar(selected.size, busy = BATCH_KEY in state.actionBusy,
                                    canDelete = session?.canDelete == true && selected.any { it.canDelete != false },
                                    canRefresh = session?.isAdministrator == true,
                                    onCancel = { librarySelection = emptySet() }) { action ->
                                    librarySelection = emptySet()
                                    vm.runBatch(selected, action)
                                }
                            } else {
                                PathfinIconButton(PathfinIcons.Back, tr("返回"), vm::back,
                                    tint = PathfinAppearance.textMain, size = 44.dp)
                                Box(Modifier.weight(1f), contentAlignment = if (libraryPage) Alignment.Center else Alignment.CenterStart) {
                                    if (route is Route.Discovery) Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(pageTitle(state), Modifier.weight(1f, fill = false), fontSize = 19.sp, color = LocalContentColor.current,
                                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val total = (if (route.favorite) state.favorites else state.search.results).sections[route.category]?.total ?: 0
                                        if (total > 0) {
                                            Text(total.toString(), fontSize = 12.sp, color = PathfinAppearance.textMuted)
                                        }
                                    } else Text(pageTitle(state), color = LocalContentColor.current, fontSize = if (libraryPage) 18.sp else 19.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (route is Route.Folder) {
                                    PathfinIconButton(PathfinIcons.Search, tr("搜索当前媒体库"), { vm.openLibrarySearch(route.item) },
                                        Modifier.testTag("library-search-open"), tint = PathfinAppearance.textMain, size = 48.dp)
                                } else if (libraryPage) Spacer(Modifier.width(48.dp))
                            }
                        }
            }
            if (isDetail) {
                CompositionLocalProvider(LocalContentColor provides if (detailScrolled) MaterialTheme.colorScheme.onSurface else Color.White) {
                PageTitleBar(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .pointerInput(Unit) { detectTapGestures { } },
                    height = 56.dp + AppSafeDrawingInsets.asPaddingValues().calculateTopPadding(), visible = detailScrolled) {
                Row(Modifier.fillMaxWidth()
                    .windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    PathfinIconButton(PathfinIcons.Back, tr("返回"), { vm.back() }, tint = LocalContentColor.current, size = 48.dp)
                    Text(if (detailScrolled) detail?.name.orEmpty() else "", Modifier.weight(1f), color = LocalContentColor.current,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                    // 人物没有播放区，收藏放在顶栏“更多”左侧。
                    if (detail?.type == "Person") {
                        val busy = "favorite/${detail.id}" in state.actionBusy
                        PathfinIconButton(if (detail.isFavorite) PathfinIcons.HeartFilled else PathfinIcons.Heart,
                            if (detail.isFavorite) tr("取消收藏") else tr("收藏人物"), { vm.toggleFavorite(detail) }, Modifier.testTag("person-favorite"),
                            enabled = !busy, tint = if (detail.isFavorite) PathfinAppearance.accent else LocalContentColor.current, size = 48.dp)
                    }
                    // 人物条目不提供删除：删除只针对媒体文件。
                    if (detail != null && (session.isAdministrator || (session.canDelete && detail.canDelete != false && detail.type != "Person") ||
                            detail.type in setOf("Movie", "Episode", "Video"))) {
                        PathfinIconButton(MediaNavigationIcons.More, tr("更多操作"), { detailMenu = true }, Modifier.testTag("detail-more"),
                            tint = LocalContentColor.current, size = 48.dp)
                        // 管理操作收进一张分组卡，删除单独成组并整行标红，与设置页同一语言。
                        if (detailMenu) AppModalBottomSheet(onDismissRequest = { detailMenu = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            containerColor = PathfinAppearance.card, dragHandle = null,
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SheetDragHandle()
                                if (session.isAdministrator) GroupCard(container = PathfinAppearance.canvas) {
                                    val identify = dev.pathfin.android.data.MediaIdentificationApi.supports(session, detail)
                                    if (identify) DetailOperation(if (detail.type == "Person") tr("识别人物") else tr("识别媒体"), PathfinIcons.Search,
                                        { detailMenu = false; vm.openIdentification(detail) })
                                    DetailOperation(tr("刷新元数据"), PathfinIcons.Refresh, { detailMenu = false; refreshConfirmation = true },
                                        modifier = Modifier.testTag("detail-refresh"), enabled = "refresh/${detail.id}" !in state.actionBusy,
                                        divider = identify)
                                    DetailOperation(tr("编辑元数据"), PathfinIcons.Edit, { detailMenu = false; vm.openMediaTools(detail) },
                                        modifier = Modifier.testTag("detail-edit-metadata"), divider = true)
                                    DetailOperation(tr("编辑图片"), PathfinIcons.Image, { detailMenu = false; imageEditor = true },
                                        modifier = Modifier.testTag("detail-edit-images"), divider = true)
                                }
                                if (detail.type in setOf("Movie", "Episode", "Video")) GroupCard(container = PathfinAppearance.canvas) {
                                    DetailOperation(tr("编辑字幕"), PathfinIcons.Subtitles, { detailMenu = false; vm.openSubtitles(detail) },
                                        modifier = Modifier.testTag("detail-edit-subtitles"))
                                }
                                if (session.canDelete && detail.canDelete != false && detail.type != "Person") GroupCard(container = PathfinAppearance.canvas) {
                                    DetailOperation(tr("删除媒体"), PathfinIcons.Trash,
                                        { detailMenu = false; deleteConfirmation = true }, destructive = true,
                                        modifier = Modifier.testTag("detail-delete"), enabled = "delete/${detail.id}" !in state.actionBusy)
                                }
                            }
                        }
                    }
                }
                }
            }
            }
            if (showManagerNavigation) {
                ManagerDock(managerSettings, onServers = vm::showServers, onSettings = { vm.selectTab(Route.Settings) },
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (showInnerNavigation) {
                AnimatedVisibility(visible = !autoHide || navigationVisible, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                    val items = tabs.map { tab -> DockItem(tab.title, tab.icon, state.routes.first() == tab.route) { vm.selectTab(tab.route) } }
                    Box(Modifier.testTag("app-floating-navigation")
                        .windowInsetsPadding(AppNavigationBarInsets).padding(bottom = 12.dp)) { PathfinDock(items) }
                }
            }
        }
    }
    if (refreshConfirmation && detail != null && session?.isAdministrator == true) {
        RefreshMetadataDialog(1, { refreshConfirmation = false }) { options ->
            refreshConfirmation = false
            vm.refreshMetadata(detail, options)
        }
    }
    if (imageEditor && detail != null && session != null) {
        AppModalBottomSheet(onDismissRequest = { if (!imageEditorBusy) imageEditor = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !imageEditorBusy }),
            containerColor = PathfinAppearance.card, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }, dragHandle = null) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)
                .windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .testTag("media-image-editor-sheet")) {
                Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).background(PathfinAppearance.segmentTrack, CircleShape))
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("编辑图片"), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
                    PathfinIconButton(PathfinIcons.Close, tr("关闭图片编辑"), { imageEditor = false }, enabled = !imageEditorBusy,
                        tint = PathfinAppearance.textSub)
                }
                // 抽屉已让出手势条，列表只留普通底边距。
                CompositionLocalProvider(LocalNavigationBottomInset provides 16.dp) {
                    MediaToolsScreen(detail, session, vm.api, "images",
                        onChanged = { vm.mediaChanged(session, detail.id) },
                        onAuthExpired = { vm.authenticationExpired(session) },
                        onBusyChanged = { imageEditorBusy = it })
                }
            }
        }
    }
    if (deleteConfirmation && detail != null) AppAlertDialog(
        onDismissRequest = { deleteConfirmation = false },
        title = { Text(tr("删除媒体")) },
        text = { Text(tr("确认删除“%1\$s”？此操作会删除服务器中的媒体及对应文件，无法撤销。", detail.name)) },
        confirmButton = { DialogAction(tr("确认删除"), { deleteConfirmation = false; vm.deleteMedia(detail) },
            Modifier.testTag("detail-delete-confirm"), role = DialogActionRole.Danger) },
        dismissButton = { DialogAction(tr("取消"), { deleteConfirmation = false }, Modifier.testTag("detail-delete-cancel")) },
    )
}

@Composable
internal fun ServerSwitcher(
    profiles: List<dev.pathfin.android.domain.ServerProfile>, currentId: String?, session: Session?, busy: Boolean,
    api: dev.pathfin.android.data.JellyfinApi, onSwitch: (dev.pathfin.android.domain.ServerProfile) -> Unit,
) {
    var expanded by remember(currentId) { mutableStateOf(false) }
    val pageActive = LocalPageActive.current
    LaunchedEffect(pageActive) { if (!pageActive) expanded = false }
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !busy && profiles.isNotEmpty(), role = Role.Button) { expanded = true }
                .semantics { contentDescription = tr("切换服务器") },
            contentAlignment = Alignment.Center,
        ) {
            AccountAvatar(session, api, Modifier.size(32.dp))
        }
        AppDropdownMenu(expanded && pageActive, { expanded = false }, shape = RoundedCornerShape(CardRadius), containerColor = PathfinAppearance.card) {
            Column(Modifier.widthIn(min = 176.dp, max = 260.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Eyebrow(tr("切换服务器"), Modifier.padding(start = 18.dp, top = 4.dp, bottom = 4.dp))
                profiles.forEach { profile ->
                    val current = profile.id == currentId
                    MenuRow(
                        profile.displayName.ifBlank { profile.session.userName },
                        { expanded = false; onSwitch(profile) },
                        Modifier.semantics { selected = current },
                        leading = { AccountAvatar(profile.session, api, Modifier.size(30.dp), RoundedCornerShape(9.dp)) },
                        enabled = !busy,
                        trailing = {
                            if (current) Icon(PathfinIcons.Check, tr("当前服务器"), Modifier.padding(start = 12.dp).size(17.dp), tint = PathfinAppearance.accent)
                        },
                    )
                }
            }
        }
    }
}

private fun pageTitle(state: AppState): String = when (val route = state.route) {
    Route.LiveTv -> tr("直播电视")
    Route.Downloads -> tr("下载任务")
    Route.ServerAdministration -> tr("媒体库控制台")
    is Route.Identification -> if (route.item.type == "Person") tr("识别人物") else tr("识别媒体")
    is Route.MediaTools -> tr("编辑元数据")
    Route.Home -> tr("首页"); Route.Libraries -> tr("媒体库"); Route.Search -> tr("搜索"); Route.Favorites -> tr("收藏"); Route.Settings -> tr("设置")
    is Route.Folder -> route.item.name; is Route.Browse -> route.title; is Route.Detail -> state.details[route.id]?.name ?: tr("详情")
    is Route.LibrarySearch -> tr("%1\$s内搜索", route.library.name)
    is Route.Discovery -> if (route.favorite) tr("收藏 · %1\$s", route.category.label) else route.category.label
    is Route.SettingsPage -> settingsPageTitle(route.page)
}

/** 服务器管理外壳的悬浮导航，与服务器内导航共用同一个胶囊组件。 */
@Composable
private fun ManagerDock(settingsSelected: Boolean, onServers: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().testTag("app-manager-navigation").windowInsetsPadding(AppNavigationBarInsets)
            .padding(top = 6.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        PathfinDock(listOf(
            DockItem(tr("服务器"), PathfinIcons.Server, !settingsSelected, onServers),
            DockItem(tr("设置"), PathfinIcons.Sliders, settingsSelected, onSettings),
        ))
    }
}

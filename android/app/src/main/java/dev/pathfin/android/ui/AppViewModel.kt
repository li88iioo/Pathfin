package dev.pathfin.android.ui

import dev.pathfin.android.i18n.tr
import dev.pathfin.android.playback.DevicePlaybackCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.VideoPreflightPolicy
import dev.pathfin.android.data.LibrarySuggestionLimit
import dev.pathfin.android.data.PerformanceTrace
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.HomeSnapshot
import dev.pathfin.android.data.HomeSnapshotStore
import dev.pathfin.android.data.BrowserSnapshotStore
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.LocalNetworkPermissionDenied
import dev.pathfin.android.data.MediaManagementApi
import dev.pathfin.android.data.LegacyTraktCleanup
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import dev.pathfin.android.domain.*
import dev.pathfin.android.ui.theme.ThemePreference
import dev.pathfin.android.ui.theme.ThemeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.pathfin.android.download.MediaDownloads
import dev.pathfin.android.download.DownloadDirectoryUnavailable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalDate

sealed interface Route {
    val key: String
    data object Home : Route { override val key = "home" }
    data object Libraries : Route { override val key = "libraries" }
    data object Search : Route { override val key = "search" }
    data object Settings : Route { override val key = "settings" }
    data object LiveTv : Route { override val key = "live-tv" }
    data object Downloads : Route { override val key = "downloads" }
    data object ServerAdministration : Route { override val key = "server-administration" }
    data class Identification(val item: MediaItem) : Route { override val key = "identify/${item.id}" }
    data class MediaTools(val item: MediaItem) : Route { override val key = "tools/${item.id}" }
    data object Favorites : Route { override val key = "favorites" }
    data class Discovery(val category: DiscoveryCategory, val favorite: Boolean, val query: String = "") : Route {
        override val key = "discovery/${if (favorite) "favorites" else "search/$query"}/${category.name}"
    }
    data class SettingsPage(val page: String) : Route { override val key = "settings/$page" }
    data class Browse(val id: String, val title: String) : Route {
        override val key = "browse/$id"
        internal val visitId: String = UUID.randomUUID().toString()
    }
    data class Folder(val item: MediaItem) : Route {
        override val key = "folder/${item.id}"
        internal val visitId: String = UUID.randomUUID().toString()
    }
    data class LibrarySearch(val library: MediaItem) : Route { override val key = "library-search/${library.id}" }
    data class Detail(val id: String, override val key: String = "detail/$id") : Route
}

data class ContentState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val nextIndex: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val retryAppend: Boolean = false,
    val queryKey: String? = null,
)

data class HomeState(
    val libraries: List<MediaItem> = emptyList(),
    val recent: List<MediaItem> = emptyList(),
    val resume: List<MediaItem> = emptyList(),
    val libraryItems: Map<String, List<MediaItem>> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** 本轮必需首页请求全部成功；部分内容可显示不代表可以跳过再次读取。 */
    val complete: Boolean = false,
    val error: String? = null,
    val nextUp: List<MediaItem> = emptyList(),
    val nextUpLoaded: Boolean = false,
    val librariesLoaded: Boolean = false,
    val resumeLoaded: Boolean = false,
)

private fun HomeSnapshot.homeState() = HomeState(
    libraries = libraries, recent = recent, resume = resume, nextUp = nextUp, libraryItems = libraryItems,
    loaded = loaded, complete = complete, librariesLoaded = librariesLoaded,
    resumeLoaded = resumeLoaded, nextUpLoaded = nextUpLoaded,
)

private fun HomeState.snapshot() = HomeSnapshot(
    libraries = libraries, recent = recent, resume = resume, nextUp = nextUp, libraryItems = libraryItems,
    loaded = loaded, complete = complete, librariesLoaded = librariesLoaded,
    resumeLoaded = resumeLoaded, nextUpLoaded = nextUpLoaded,
)

data class DetailExtras(
    val resume: List<MediaItem> = emptyList(), val nextUp: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(), val siblings: List<MediaItem> = emptyList(), val loading: Boolean = false, val error: String? = null,
    /** 人物详情的参演作品：按首映日期从新到旧的前一页，totalCount 为服务器总数。 */
    val movies: MediaPage = MediaPage(emptyList(), 0), val series: MediaPage = MediaPage(emptyList(), 0),
)

data class DownloadSelection(
    val requestId: String, val profileId: String, val itemId: String, val title: String, val fileName: String,
    val needsDestination: Boolean = false,
)

data class SourceSelection(val item: MediaItem, val info: PlaybackInfo, val startMs: Long,
    val needsSelectedVideoPreflight: Boolean = false)

/** 点击即发布；只保存可重试的目标，PlaybackInfo 仍在每次准备时重新取得。 */
data class PendingPlayback(
    val id: Long,
    val item: MediaItem,
    val fromStart: Boolean,
    val sourceId: String?,
    val loadDetails: Boolean = false,
    val quality: PlaybackQuality = PlaybackQuality.ORIGINAL,
)

/** 连接页第一步的探测结果：确认地址属于 Jellyfin，并带出可选的公开账号。 */
data class ServerProbe(val config: ServerConfig, val info: PublicServerInfo, val users: List<PublicUser>)

internal const val BATCH_KEY = "batch"

sealed interface BatchMediaAction {
    data object Delete : BatchMediaAction
    data class Played(val played: Boolean) : BatchMediaAction
    data class Refresh(val options: MetadataRefreshOptions) : BatchMediaAction
}

data class AppState(
    val session: Session? = null,
    val profiles: List<ServerProfile> = emptyList(),
    val currentProfileId: String? = null,
    val showServers: Boolean = false,
    val preferences: AppPreferences = AppPreferences(),
    val hiddenLibraries: Set<String> = emptySet(),
    val browsers: Map<String, BrowserUiState> = emptyMap(),
    val detailExtras: Map<String, DetailExtras> = emptyMap(),
    val actionBusy: Set<String> = emptySet(),
    val notice: String? = null,
    val localNetworkPermissionRequest: Long? = null,
    val localNetworkPermissionDenied: Boolean = false,
    val loginBusy: Boolean = false,
    val switchingProfileId: String? = null,
    val loginError: String? = null,
    val serverProbe: ServerProbe? = null,
    val addingServer: Boolean = false,
    val reauthenticationProfile: ServerProfile? = null,
    val home: HomeState = HomeState(),
    val routes: List<Route> = listOf(Route.Home),
    val folders: Map<String, ContentState> = emptyMap(),
    val details: Map<String, MediaItem> = emptyMap(),
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val search: SearchState = SearchState(),
    val searchVisitId: String = UUID.randomUUID().toString(),
    val favorites: DiscoveryState = DiscoveryState(),
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val preparingPlay: Boolean = false,
    val playbackError: String? = null,
    val sourceSelection: SourceSelection? = null,
    val pendingPlayback: PendingPlayback? = null,
    val activePlayback: PlaybackRequest? = null,
    val pendingCast: MediaItem? = null,
    val pendingDownload: DownloadSelection? = null,
    val downloadNotificationRequestId: String? = null,
    val pendingSubtitleManagement: MediaItem? = null,
    val restoringSession: Boolean = false,
    val playbackNeighbors: List<MediaItem> = emptyList(),
    val playbackEpisodes: ContentState = ContentState(),
) {
    val route: Route get() = routes.last()
    val canSwitchServer: Boolean get() = !loginBusy || switchingProfileId != null
}

private const val PlaybackPrefetchDelayMs = 600L
private const val PlaybackPrefetchMaxAgeMs = 180_000L

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val api = JellyfinApi(capabilities = { DevicePlaybackCapabilities.current },
        videoSupport = DevicePlaybackCapabilities::supportsVideo)
    private val cloudApi = dev.pathfin.android.data.CloudPlaybackApi(videoSupport = DevicePlaybackCapabilities::supportsVideo)
    private val sessionStore by lazy { SessionStore(application) }
    private val themeStore by lazy { ThemeStore(application) }
    private val preferencesStore by lazy { AppPreferencesStore(application) }
    private val homeSnapshotStore by lazy { HomeSnapshotStore(application) }
    private val browserSnapshotStore by lazy { BrowserSnapshotStore(application) }
    private val mutableState = MutableStateFlow(AppState(restoringSession = true))
    private val storageMutex = Mutex()
    private val storageReady = CompletableDeferred<Unit>()
    private var preferenceRevision = 0L
    private var themeRevision = 0L
    private var lastCommittedPreferences = AppPreferences()
    private var lastCommittedTheme = ThemePreference.SYSTEM

    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private var localNetworkRequestSequence = 0L
    private class PendingLocalNetworkAccess(val id: Long) {
        val result = CompletableDeferred<Boolean>()
        var users = 0
    }
    private var pendingLocalNetworkAccess: PendingLocalNetworkAccess? = null
    private var serverNameJob: Job? = null
    private var generation = 0
    private var homeJob: Job? = null
    private var homeRefreshGeneration = 0L
    private val homeSnapshots = ProfileHomeCache<HomeState>()
    private val homeSnapshotWrites = mutableMapOf<String, Job>()
    private var loginJob: Job? = null
    private val discoveryJobs = mutableMapOf<String, Job>()
    private val discoveryLoads = mutableMapOf<String, Any>()
    private val discoveryConcurrency = Semaphore(3)
    private var suggestionsJob: Job? = null
    private var suggestionsLoad: Any? = null
    private var playJob: Job? = null
    private var playbackPreparationSequence = 0L
    private var lastPlayedJob: Job? = null
    private var playbackNeighborsJob: Job? = null
    private var playbackEpisodesJob: Job? = null
    private var detailJob: Job? = null
    private class DetailLoad(val itemId: String)
    private var detailLoad: DetailLoad? = null
    private val dirtyDetails = mutableSetOf<String>()
    private val folderJobs = mutableMapOf<String, Job>()
    private val browserJobs = mutableMapOf<String, Job>()
    private val browserParents = mutableMapOf<String, String?>()
    private val browserPages = BrowserPageCache(nowMs = SystemClock::elapsedRealtime)
    private var browserPageRevision = 0L
    private data class BrowserQueryKey(val parentId: String?, val query: LibraryQuery)
    private data class BrowserLoad(val key: BrowserQueryKey, val startIndex: Int, val revision: Int)
    /** 只保留同一轮逻辑读取期间成功的写回，最终发布后随请求释放。 */
    private class MediaReadChanges {
        private val userData = mutableMapOf<String, MediaUserData>()
        private val deleted = mutableSetOf<String>()
        fun record(change: BrowserUserDataChange) {
            userData[change.item.id] = change.data.after(userData[change.item.id])
        }
        fun remove(itemId: String) { deleted += itemId }
        fun reconcile(item: MediaItem): MediaItem = userData[item.id]?.applyTo(item) ?: item
        fun reconcile(items: List<MediaItem>): List<MediaItem> = if (userData.isEmpty() && deleted.isEmpty()) items
            else items.filterNot { it.id in deleted }.map(::reconcile)
        fun reconcile(page: MediaPage): MediaPage = page.copy(items = reconcile(page.items),
            totalCount = (page.totalCount - page.items.count { it.id in deleted }).coerceAtLeast(0))
    }
    private val activeMediaReads = mutableSetOf<MediaReadChanges>()
    private data class FilterQueryKey(val parentId: String?, val itemTypes: Set<String>)
    private val loadedBrowsers = mutableMapOf<String, BrowserQueryKey>()
    private val browserLoads = mutableMapOf<String, BrowserLoad>()
    private val loadedFilters = mutableMapOf<String, FilterQueryKey>()
    private val filterLoads = mutableMapOf<String, FilterQueryKey>()
    private val dirtyBrowsers = mutableSetOf<String>()
    private var deletingBatch = false
    private var browserMutationRevision = 0
    private val committedBrowserStyles = mutableMapOf<String, PosterStyle>()
    private val browserStyleRevisions = mutableMapOf<String, Long>()
    private val committedHiddenLibraries = mutableMapOf<String, Set<String>>()
    private val hiddenLibraryRevisions = mutableMapOf<String, Long>()
    private val extraJobs = mutableMapOf<String, Job>()
    private data class DownloadPicker(val selection: DownloadSelection, val generation: Int)
    // 系统选择器的结果归原请求；页面重建不重新 launch，切号也不能把旧结果交给新媒体。
    private var downloadPicker: DownloadPicker? = null

    private fun launchMediaRead(start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.(MediaReadChanges) -> Unit): Job = viewModelScope.launch(start = start) {
        val changes = MediaReadChanges()
        activeMediaReads += changes
        try { block(changes) } finally { activeMediaReads -= changes }
    }

    init {
        // 只在 IO 清理已移除功能的独立本机数据；不等待、不影响 Jellyfin 会话恢复。
        viewModelScope.launch { LegacyTraktCleanup.run(application) }
        viewModelScope.launch {
            val startupTrace = PerformanceTrace.navigation("restore_session")
            try {
                val restored = storeIo {
                    val preferences = preferencesStore.load()
                    val saved = sessionStore.load()
                    val profileId = sessionStore.activeProfileId()
                    AppState(session = saved, profiles = sessionStore.loadProfiles(),
                        currentProfileId = profileId, theme = themeStore.load(),
                        preferences = preferences, showServers = saved == null || !preferences.openLastServer,
                        home = if (saved != null && profileId != null) homeSnapshotStore.load(profileId, saved)?.homeState() ?: HomeState() else HomeState(),
                        hiddenLibraries = saved?.let { preferencesStore.hiddenLibraries(sessionKey(it)) }.orEmpty())
                }
                startupTrace.point("store_loaded")
                lastCommittedPreferences = restored.preferences
                lastCommittedTheme = restored.theme
                if (generation == 0) {
                    if (restored.session != null && restored.currentProfileId != null) {
                        homeSnapshots.put(ProfileHomeKey.of(restored.currentProfileId, restored.session), restored.home)
                    }
                    mutableState.value = restored
                    restored.session?.takeIf { restored.preferences.openLastServer }?.let(::validateAndLoad)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(showServers = true, loginError = tr("本机账号读取失败，请重试或重新添加服务器")) }
            } finally {
                mutableState.update { it.copy(restoringSession = false) }
                storageReady.complete(Unit)
            }
        }
    }

    private suspend fun <T> storeIo(operation: suspend () -> T): T = storageMutex.withLock {
        // 在调用方/Main先排队，再调度IO；不能让后发操作抢先进入另一个IO线程而反向落盘。
        withContext(Dispatchers.IO) { operation() }
    }

    private fun validateAndLoad(saved: Session) {
        val epoch = generation
        loginJob = viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(saved.server.baseUrl)
                val validated = api.validateSession(saved)
                if (epoch == generation) {
                    val freshHome = requiresFreshHome(saved, validated)
                    if (freshHome) state.value.currentProfileId?.let(::invalidateHomeSnapshot)
                    if (validated != saved) storeIo { sessionStore.save(validated) }
                    if (epoch != generation) return@launch
                    mutableState.update { current -> current.copy(session = validated,
                        home = if (freshHome) HomeState() else current.home,
                        profiles = current.profiles.map { if (it.id == current.currentProfileId) it.copy(session = validated) else it }) }
                    state.value.currentProfileId?.let(homeSnapshots::allowProfile)
                    refreshHome()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation && !handleAuthFailure(error)) mutableState.update { it.copy(home = it.home.copy(error = message(error))) }
            }
        }
    }

    fun login(url: String, username: String, password: String, allowHttp: Boolean, displayName: String = "") {
        login(url, username, password, allowHttp, displayName, target = null)
    }

    /** 必需探测完成即可手动登录，公开账号随后补齐，不占用登录忙态。 */
    fun probeServer(url: String, allowHttp: Boolean) {
        if (mutableState.value.loginBusy) return
        cancelRequests()
        val epoch = generation
        mutableState.update { it.copy(loginBusy = true, serverProbe = null, notice = null) }
        loginJob = viewModelScope.launch {
            try {
                val config = ServerUrls.normalize(url, allowHttp)
                ensureLocalNetworkAccess(config.baseUrl)
                val info = api.publicServerInfo(config)
                if (epoch != generation) return@launch
                mutableState.update { it.copy(loginBusy = false, loginError = null, serverProbe = ServerProbe(config, info, emptyList())) }
                val users = try { api.publicUsers(config) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    emptyList()
                }
                if (epoch != generation || state.value.serverProbe?.config != config) return@launch
                mutableState.update { it.copy(serverProbe = it.serverProbe?.copy(users = users)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) mutableState.update { it.copy(loginBusy = false, loginError = message(error)) }
            }
        }
    }

    /** 从媒体空间进入连接页；首次连接（没有服务器）时页面本身就是连接页。 */
    fun beginAddServer() {
        if (mutableState.value.loginBusy) return
        mutableState.update { it.copy(addingServer = true, serverProbe = null, loginError = null) }
    }

    /** 连接页后退一步：账号步骤回到地址，地址步骤退出添加。 */
    fun backFromConnect() {
        if (state.value.loginBusy && state.value.serverProbe != null) return
        cancelRequests()
        mutableState.update {
            if (it.serverProbe != null) it.copy(serverProbe = null, loginError = null, loginBusy = false)
            else it.copy(addingServer = false, loginError = null, loginBusy = false)
        }
    }

    fun dismissReauthentication() {
        if (state.value.loginBusy) return
        mutableState.update { it.copy(reauthenticationProfile = null, loginError = null) }
    }

    fun reauthenticate(password: String) {
        val target = state.value.reauthenticationProfile ?: return
        login(target.session.server.baseUrl, target.session.userName, password,
            target.session.server.allowInsecureHttp, displayName = "", target = target)
    }

    private fun login(url: String, username: String, password: String, allowHttp: Boolean, displayName: String,
        target: ServerProfile?) {
        if (mutableState.value.loginBusy) return
        cancelRequests()
        val epoch = generation
        // 重试期间保留上一次错误，结果返回后再替换，避免快速失败时提示反复闪烁。
        mutableState.update { it.copy(loginBusy = true, reauthenticationProfile = target, notice = null) }
        loginJob = viewModelScope.launch {
            try {
                storageReady.await()
                val config = ServerUrls.normalize(url, allowHttp)
                require(username.isNotBlank()) { tr("请输入用户名") }
                val (profiles, activeId) = storeIo { sessionStore.loadProfiles() to sessionStore.activeProfileId() }
                val previous = if (target != null) {
                    profiles.firstOrNull { it.id == target.id }
                        ?: throw IllegalArgumentException(tr("该服务器已从本机移除，请重新添加。"))
                } else ServerProfileIdentity.forLogin(profiles, activeId, config, username)
                val deviceId = ServerProfileIdentity.deviceIdForLogin(previous, profiles)
                ensureLocalNetworkAccess(config.baseUrl)
                val session = api.authenticate(config, username.trim(), password, deviceId)
                if (epoch != generation) return@launch
                val existing = if (target != null) {
                    require(previous != null && session.userId == previous.session.userId &&
                        (previous.session.serverId.isBlank() || session.serverId == previous.session.serverId)) {
                        tr("服务器返回的账号与原资料不一致，请取消后单独添加服务器。")
                    }
                    previous
                } else {
                    profiles.firstOrNull { it.id == activeId && ServerProfileIdentity.matches(it.session, session) }
                        ?: profiles.firstOrNull { ServerProfileIdentity.matches(it.session, session) }
                }
                val profile = (existing ?: ServerProfile(UUID.randomUUID().toString(), session, displayName.ifBlank { java.net.URI(config.baseUrl).host.orEmpty() }))
                    .copy(session = session, displayName = displayName.ifBlank { existing?.displayName ?: java.net.URI(config.baseUrl).host.orEmpty() })
                invalidateHomeSnapshot(profile.id)
                storeIo { sessionStore.upsertProfile(profile); sessionStore.selectProfile(profile.id) }
                if (epoch != generation) return@launch
                activate(profile)
                mutableState.update { it.copy(serverProbe = null, addingServer = false) }
                refreshHome()
                if (displayName.isBlank() && existing == null) refreshServerName(profile, epoch)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) mutableState.update {
                    it.copy(loginBusy = false, loginError = message(error))
                }
            }
        }
    }

    private fun cancelRequests() {
        generation++
        invalidatePlaybackPrefetch()
        deletingBatch = false
        homeRefreshGeneration++
        activeMediaReads.clear()
        pendingLocalNetworkAccess?.result?.cancel()
        pendingLocalNetworkAccess = null
        mutableState.update { it.copy(localNetworkPermissionRequest = null, localNetworkPermissionDenied = false,
            switchingProfileId = null,
            actionBusy = emptySet(),
            pendingPlayback = null, preparingPlay = false, sourceSelection = null, playbackError = null,
            pendingSubtitleManagement = null, pendingDownload = null, downloadNotificationRequestId = null,
            home = it.home.copy(loading = false), detailExtras = it.detailExtras.mapValues { (_, extras) -> extras.copy(loading = false) }) }
        serverNameJob?.cancel()
        loginJob?.cancel(); homeJob?.cancel(); playJob?.cancel(); detailJob?.cancel()
        invalidateDiscovery()
        suggestionsJob?.cancel(); suggestionsLoad = null
        mutableState.update { it.copy(search = it.search.copy(suggestions = it.search.suggestions.copy(loading = false))) }
        detailLoad = null; dirtyDetails.clear()
        lastPlayedJob?.cancel(); lastPlayedJob = null
        playbackNeighborsJob?.cancel(); playbackEpisodesJob?.cancel()
        folderJobs.values.forEach { it.cancel() }; folderJobs.clear()
        cancelBrowserRequests()
        extraJobs.values.forEach { it.cancel() }; extraJobs.clear()
    }

    private fun cancelBrowserRequests() {
        browserJobs.values.forEach { it.cancel() }; browserJobs.clear(); browserParents.clear()
        extraJobs.keys.filter { it.startsWith("filters/") }.forEach { extraJobs.remove(it)?.cancel() }
        loadedBrowsers.clear(); browserLoads.clear(); loadedFilters.clear(); filterLoads.clear()
        dirtyBrowsers.clear(); browserMutationRevision++
        invalidateBrowserPages()
    }

    private fun invalidateBrowserPages() {
        browserPageRevision++
        browserPages.clear()
    }

    fun logout(reason: String? = null) {
        if ("logout" in state.value.actionBusy) return
        val current = state.value.currentProfileId
        val removed = state.value.profiles.firstOrNull { it.id == current }
        val removedSession = state.value.session
        cancelRequests()
        val epoch = generation
        mutableState.update { it.copy(loginBusy = true, actionBusy = it.actionBusy + "logout") }
        viewModelScope.launch {
            try {
                storageReady.await()
                val remaining = storeIo {
                    if (current != null) sessionStore.removeProfile(current) else sessionStore.clear()
                    sessionStore.loadProfiles()
                }
                current?.let(::invalidateHomeSnapshot)
                if (current != null) dev.pathfin.android.casting.DlnaCasting.cancelProfile(getApplication(), current)
                val queueFailed = current != null && runCatching { MediaDownloads.recover(getApplication()) }.isFailure
                if (epoch != generation) return@launch
                removed?.let(::clearProfileImages)
                mutableState.update { AppState(theme = it.theme, preferences = it.preferences, profiles = remaining,
                    showServers = true, loginError = reason,
                    notice = accountRemovalNotice(queueFailed)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == generation) mutableState.update { it.copy(loginBusy = false,
                    actionBusy = it.actionBusy - "logout", notice = tr("无法移除本机账号，请重试")) }
            }
        }
    }

    private suspend fun activate(profile: ServerProfile) {
        val epoch = generation
        val key = ProfileHomeKey.of(profile.id, profile.session)
        val cached = homeSnapshots[key]
        val mayRestore = cached == null && !homeSnapshots.isInvalidated(profile.id)
        val (profiles, hidden, storedHome) = storeIo {
            Triple(sessionStore.loadProfiles(), preferencesStore.hiddenLibraries(sessionKey(profile.session)),
                if (mayRestore) homeSnapshotStore.load(profile.id, profile.session)?.homeState() else null)
        }
        if (epoch != generation) return
        val old = state.value
        // 磁盘内容只用于立即呈现；跨进程重新进入该账号后仍要后台更新。
        val home = if (homeSnapshots.isInvalidated(profile.id)) null else cached ?: storedHome?.copy(complete = false)
        // 先读取目标以更新 LRU，再保存离开的账号，避免恰好把即将显示的目标淘汰。
        captureHomeSnapshot(old)
        mutableState.value = AppState(session = profile.session, profiles = profiles, currentProfileId = profile.id,
            preferences = old.preferences, theme = old.theme, hiddenLibraries = hidden,
            home = home ?: HomeState())
        // 已发布对应新会话的首页；不能在上面的旧页捕获之前解除失效保护。
        homeSnapshots.allowProfile(profile.id)
    }

    private fun captureHomeSnapshot(current: AppState = state.value) {
        val profileId = current.currentProfileId ?: return
        val session = current.session ?: return
        if (homeSnapshots.isInvalidated(profileId)) return
        val home = current.home
        if (!home.loaded && !home.librariesLoaded && !home.resumeLoaded && !home.nextUpLoaded) return
        val snapshot = home.copy(loading = false, error = null)
        homeSnapshots.put(ProfileHomeKey.of(profileId, session), snapshot)
        // 与会话写入/删除共用顺序队列；失效之后旧回包不能再次排入写入。
        // 同账号的快响应只保留最新待写版本，避免多个媒体库同时返回时重复刷盘。
        homeSnapshotWrites.remove(profileId)?.cancel()
        homeSnapshotWrites[profileId] = viewModelScope.launch {
            storeIo { homeSnapshotStore.save(profileId, session, snapshot.snapshot()) }
        }
    }

    private fun invalidateHomeSnapshot(profileId: String) {
        homeSnapshots.invalidateProfile(profileId)
        homeSnapshotWrites.remove(profileId)?.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { storeIo {
            homeSnapshotStore.removeProfile(profileId)
            browserSnapshotStore.removeProfile(profileId)
        } }
    }

    private fun requiresFreshHome(old: Session, updated: Session): Boolean =
        old.server != updated.server || old.serverId != updated.serverId || old.userId != updated.userId ||
            old.accessToken != updated.accessToken || old.deviceId != updated.deviceId ||
            old.isAdministrator != updated.isAdministrator || old.canDownload != updated.canDownload || old.canDelete != updated.canDelete

    /** 新导航覆盖尚未提交的切服意图；密码/删除等写操作不走此取消入口。 */
    private fun cancelServerSwitch() {
        if (state.value.switchingProfileId == null) return
        cancelRequests()
        mutableState.update { it.copy(loginBusy = false, loginError = null, notice = null) }
    }

    fun showServers() {
        cancelServerSwitch()
        invalidatePlaybackPrefetch()
        releaseSearchResults()
        mutableState.update { it.copy(showServers = true, routes = listOf(Route.Home), loginError = null) }
    }
    fun clearLoginError() { mutableState.update { it.copy(loginError = null) } }
    fun dismissNotice() { mutableState.update { it.copy(notice = null) } }

    fun openServer(profile: ServerProfile) {
        if (!state.value.canSwitchServer) return
        val trace = PerformanceTrace.navigation("open_server")
        val current = state.value
        val homeInFlight = homeJob?.isActive == true
        if (current.currentProfileId == profile.id && current.session == profile.session && (current.home.loaded || homeInFlight) &&
            !homeSnapshots.isInvalidated(profile.id)) {
            // 重选仍在显示的账号即取消切服；不等待离线目标，也不重建当前首页。
            val interruptedSwitch = current.switchingProfileId != null
            if (interruptedSwitch) cancelServerSwitch() else invalidatePlaybackPrefetch()
            mutableState.update { it.copy(showServers = false, routes = listOf(Route.Home), loginBusy = false,
                switchingProfileId = null, loginError = null, notice = null, reauthenticationProfile = null) }
            if (!current.home.complete && (!homeInFlight || interruptedSwitch)) refreshHome()
            trace.point("cached_home")
            return
        }
        cancelRequests()
        mutableState.update { it.copy(loginBusy = true, switchingProfileId = profile.id,
            loginError = null, notice = null, reauthenticationProfile = null) }
        val epoch = generation
        loginJob = viewModelScope.launch {
            var attempted = profile
            try {
                storageReady.await()
                val persisted = storeIo { sessionStore.loadProfiles().firstOrNull { it.id == profile.id } } ?: error(tr("服务器已移除"))
                attempted = persisted.copy(session = persisted.session.copy(server = profile.session.server))
                trace.point("profile_read")
                val validated = validateAddress(persisted.session, profile.session.server)
                trace.point("session_validated")
                if (epoch != generation) return@launch
                // 只读校验可取消替换；进入本地提交后沿用账号写操作互斥，避免旧选择迟到落盘。
                mutableState.update { it.copy(switchingProfileId = null) }
                val updated = attempted.copy(session = validated)
                if (requiresFreshHome(persisted.session, validated)) invalidateHomeSnapshot(updated.id)
                storeIo { sessionStore.upsertProfile(updated); sessionStore.selectProfile(updated.id) }
                if (epoch != generation) return@launch
                activate(updated)
                trace.point("activated")
                if (!state.value.home.complete) refreshHome()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (epoch != generation) return@launch
                if (e is ApiException && e.statusCode == 401) requestReauthentication(attempted)
                else mutableState.update {
                    val error = message(e)
                    it.copy(loginBusy = false, switchingProfileId = null, loginError = error,
                        notice = if (it.showServers) null else tr("切换服务器失败：%1\$s", error))
                }
            }
        }
    }

    fun updateServer(profile: ServerProfile) = serverOperation(profile) {
        val old = storeIo { sessionStore.loadProfiles().firstOrNull { it.id == profile.id } } ?: error(tr("服务器已移除"))
        val session = if (old.session.server == profile.session.server) old.session else validateAddress(old.session, profile.session.server)
        val updated = profile.copy(session = session)
        storeIo { sessionStore.upsertProfile(updated) }
        if (requiresFreshHome(old.session, session)) {
            invalidateHomeSnapshot(updated.id)
            invalidateBrowserPages()
        }
        if (state.value.currentProfileId == updated.id && state.value.session != updated.session) {
            val hidden = storeIo { preferencesStore.hiddenLibraries(sessionKey(updated.session)) }
            invalidateHomeSnapshot(updated.id)
            // 编辑地址属于当前管理操作，不取消它自身；旧首页回包仍须立即失效。
            homeRefreshGeneration++
            homeJob?.cancel()
            cancelBrowserRequests()
            cancelDiscoveryPages(false)
            cancelDiscoveryPages(true)
            suggestionsJob?.cancel()
            suggestionsLoad = null
            mutableState.update { it.copy(session = updated.session, hiddenLibraries = hidden, home = HomeState(), browsers = emptyMap(),
                search = SearchState(), favorites = DiscoveryState()) }
            homeSnapshots.allowProfile(updated.id)
            refreshHome()
        }
    }

    fun addServerAddress(profile: ServerProfile, config: ServerConfig) = serverOperation(profile) {
        validateAddress(profile.session, config)
        storeIo { sessionStore.upsertProfile(profile.copy(addresses = (profile.addresses + config).distinctBy { it.baseUrl })) }
    }

    fun changePassword(profile: ServerProfile, current: String, new: String) = serverOperation(profile) {
        require(new.isNotBlank()) { tr("请输入新密码") }
        ensureLocalNetworkAccess(profile.session.server.baseUrl)
        api.changePassword(profile.session, current, new)
        mutableState.update { it.copy(notice = tr("密码已修改")) }
    }

    fun deleteServer(profile: ServerProfile) {
        if (state.value.loginBusy) return
        if (state.value.currentProfileId == profile.id) { logout(); return }
        val epoch = generation
        mutableState.update { it.copy(loginBusy = true) }
        viewModelScope.launch {
            try {
                val profiles = storeIo { sessionStore.removeProfile(profile.id); sessionStore.loadProfiles() }
                invalidateHomeSnapshot(profile.id)
                dev.pathfin.android.casting.DlnaCasting.cancelProfile(getApplication(), profile.id)
                val queueFailed = runCatching { MediaDownloads.recover(getApplication()) }.isFailure
                if (epoch == generation) {
                    clearProfileImages(profile)
                    mutableState.update { it.copy(profiles = profiles, loginBusy = false,
                        notice = accountRemovalNotice(queueFailed) ?: it.notice) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == generation) mutableState.update { it.copy(loginBusy = false, loginError = tr("无法移除本机账号，请重试")) } }
        }
    }

    private fun accountRemovalNotice(queueFailed: Boolean): String? =
        tr("账号已移除；下载队列更新未完成，请重新打开下载任务确认").takeIf { queueFailed }

    private fun clearProfileImages(profile: ServerProfile) {
        // 先捕获当前缓存代次；迟到的 IO 清理不能关闭重新登录后创建的 loader。
        val removals = (listOf(profile.session.server) + profile.addresses).distinctBy { it.baseUrl }.map { server ->
            PosterImageCache.prepareAccountRemoval(getApplication(), profile.session.copy(server = server))
        }
        viewModelScope.launch(Dispatchers.IO) { removals.forEach { remove -> runCatching { remove() } } }
    }

    private fun serverOperation(profile: ServerProfile, block: suspend () -> Unit) {
        if (state.value.loginBusy) return
        val epoch = generation
        mutableState.update { it.copy(loginBusy = true, loginError = null) }
        loginJob = viewModelScope.launch {
            try {
                storageReady.await()
                block()
                val profiles = storeIo { sessionStore.loadProfiles() }
                if (epoch == generation) {
                    mutableState.update { it.copy(loginBusy = false, profiles = profiles, loginError = null) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (epoch != generation) return@launch
                if (e is ApiException && e.statusCode == 401) requestReauthentication(profile)
                else mutableState.update { it.copy(loginBusy = false, loginError = message(e)) }
            }
        }
    }

    private suspend fun validateAddress(session: Session, config: ServerConfig): Session {
        ensureLocalNetworkAccess(config.baseUrl)
        if (session.server == config) return api.validateSession(session)
        val expected = if (session.serverId.isNotBlank()) session.serverId else {
            ensureLocalNetworkAccess(session.server.baseUrl)
            api.publicServerInfo(session.server).id
        }
        val actual = api.publicServerInfo(config)
        require(expected.isNotBlank() && actual.id == expected) { tr("此地址不属于同一台 Jellyfin 服务器，请单独添加服务器。") }
        return api.validateSession(session.copy(server = config))
    }

    private fun sessionKey(session: Session) = "${session.serverId.ifBlank { session.server.baseUrl }}/${session.userId}"

    fun selectTab(route: Route) {
        cancelServerSwitch()
        cancelPendingPlayback()
        if (route != Route.Search) releaseSearchResults()
        if ((route == Route.Home || route == Route.Libraries) && !state.value.home.complete && !state.value.home.loading) refreshHome()
        if (state.value.routes == listOf(route)) {
            if (route == Route.Favorites) ensureFavorites()
            if (route == Route.Search) {
                if (state.value.search.query.isBlank()) loadSearchSuggestions() else ensureSearchSections()
            }
            return
        }
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = listOf(route), playbackError = null, preparingPlay = false, sourceSelection = null, detailLoading = false) }
        if (route == Route.Favorites) ensureFavorites()
        if (route == Route.Search) {
            if (state.value.search.query.isBlank()) loadSearchSuggestions() else ensureSearchSections()
        }
    }

    fun openSettings(page: String? = null) {
        cancelServerSwitch()
        val route = page?.let(Route::SettingsPage) ?: Route.Settings
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + route) }
    }

    fun openMediaTools(item: MediaItem) {
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + Route.MediaTools(item)) }
    }
    fun openServerAdministration() {
        if (state.value.session?.isAdministrator != true) return
        cancelServerSwitch()
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + Route.ServerAdministration) }
    }
    fun openIdentification(item: MediaItem) {
        val session = state.value.session ?: return
        if (!dev.pathfin.android.data.MediaIdentificationApi.supports(session, item)) return
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + Route.Identification(item)) }
    }
    fun openLiveTv() {
        cancelServerSwitch()
        val session = state.value.session ?: return
        val epoch = generation
        viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                if (epoch == generation) {
                    invalidatePlaybackPrefetch()
                    mutableState.update { it.copy(routes = it.routes + Route.LiveTv) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == generation) notify(message(error)) }
        }
    }
    fun openDownloads() {
        cancelServerSwitch()
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + Route.Downloads) }
    }
    fun authenticationExpired(expected: Session) {
        val current = state.value
        // 多个旧页面可能同时回报同一失效会话；不能取消已经打开的密码恢复请求。
        if (expected.hasSameAuthentication(current.session) && !current.loginBusy && current.reauthenticationProfile == null) {
            handleAuthFailure(ApiException(401, tr("此账号登录已失效，请重新登录")))
        }
    }
    fun notify(message: String) { mutableState.update { it.copy(notice = message) } }
    private fun consumeDownloadSelection(requestId: String) {
        mutableState.update { if (it.pendingDownload?.requestId == requestId) it.copy(pendingDownload = null) else it }
    }
    fun claimDownloadDestination(requestId: String): Boolean {
        val current = state.value
        val selection = current.pendingDownload?.takeIf { it.requestId == requestId && it.needsDestination } ?: return false
        if (downloadPicker != null || current.currentProfileId != selection.profileId || current.session == null ||
            current.loginBusy || current.reauthenticationProfile != null) return false
        downloadPicker = DownloadPicker(selection, generation)
        return true
    }
    fun downloadDestinationLaunchFailed(requestId: String) {
        val picker = downloadPicker?.takeIf { it.selection.requestId == requestId } ?: return
        downloadPicker = null
        consumeDownloadSelection(requestId)
        if (picker.generation == generation && state.value.currentProfileId == picker.selection.profileId)
            notify(tr("无法创建下载，请重新选择保存位置"))
    }
    fun selectDownloadDestination(uri: Uri?) {
        val picker = downloadPicker ?: return
        downloadPicker = null
        val selection = picker.selection
        val pending = state.value.pendingDownload?.requestId == selection.requestId
        consumeDownloadSelection(selection.requestId)
        if (uri == null || !pending || picker.generation != generation ||
            state.value.currentProfileId != selection.profileId || state.value.reauthenticationProfile != null) return
        val key = "download/${selection.itemId}"
        mutableState.update { it.copy(actionBusy = it.actionBusy + key) }
        extraJobs[key] = viewModelScope.launch {
            try {
                // 结果已由用户确认；提交由 VM 接管，旋转不能在写任务一半时丢掉目标。
                withContext(NonCancellable + Dispatchers.IO) {
                    val application = getApplication<Application>()
                    application.contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    MediaDownloads.enqueue(application, selection.profileId, selection.itemId, selection.title, uri)
                }
                completeDownload(selection, picker.generation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (picker.generation == generation && state.value.currentProfileId == selection.profileId)
                    notify(if (error is IllegalArgumentException) error.message ?: tr("无法创建下载")
                        else tr("无法创建下载，请重新选择保存位置"))
            } finally {
                if (picker.generation == generation) mutableState.update { it.copy(actionBusy = it.actionBusy - key) }
            }
        }
    }
    fun consumeDownloadNotification(requestId: String): Boolean {
        if (state.value.downloadNotificationRequestId != requestId) return false
        mutableState.update { if (it.downloadNotificationRequestId == requestId) it.copy(downloadNotificationRequestId = null) else it }
        return true
    }
    private fun completeDownload(selection: DownloadSelection, epoch: Int) {
        consumeDownloadSelection(selection.requestId)
        val current = state.value
        if (epoch != generation || current.currentProfileId != selection.profileId || current.session == null ||
            current.loginBusy || current.reauthenticationProfile != null) return
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(notice = tr("已加入下载任务"),
            routes = if (it.route == Route.Downloads) it.routes else it.routes + Route.Downloads,
            downloadNotificationRequestId = selection.requestId) }
    }
    fun openSubtitles(item: MediaItem) {
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null ||
            item.type !in setOf("Movie", "Episode", "Video")) return
        mutableState.update { it.copy(pendingSubtitleManagement = item) }
    }
    fun dismissSubtitles() { mutableState.update { it.copy(pendingSubtitleManagement = null) } }
    fun dismissCast() { mutableState.update { it.copy(pendingCast = null) } }
    fun prepareCast(item: MediaItem) {
        val session = state.value.session ?: return
        val key = "cast/${item.id}"
        if (key in state.value.actionBusy) return
        val epoch = generation
        mutableState.update { it.copy(actionBusy = it.actionBusy + key) }
        extraJobs[key] = viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val target = if (item.type in setOf("Series", "Season")) resolveSeriesEpisode(session, item, false) else item
                val full = api.item(session, target.id)
                if (epoch == generation) mutableState.update { it.copy(pendingCast = full) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == generation && !handleAuthFailure(error)) notify(message(error)) }
            finally { if (epoch == generation) mutableState.update { it.copy(actionBusy = it.actionBusy - key) } }
        }
    }
    fun prepareDownload(item: MediaItem) {
        val session = state.value.session ?: return
        val profileId = state.value.currentProfileId ?: return
        val key = "download/${item.id}"
        // 一次只准备一个目的地；不能在系统选择器等待期间覆盖原媒体身份。
        if (state.value.pendingDownload != null || downloadPicker != null ||
            state.value.actionBusy.any { it.startsWith("download/") }) return
        val epoch = generation
        mutableState.update { it.copy(actionBusy = it.actionBusy + key) }
        extraJobs[key] = viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val source = api.downloadSource(session, item.id)
                if (epoch != generation) return@launch
                val selection = DownloadSelection(UUID.randomUUID().toString(), profileId, item.id, item.name, source.fileName)
                mutableState.update { it.copy(pendingDownload = selection) }
                // 默认目录只由本次 VM job 提交一次，Composition / Activity 重建只继续观察状态。
                // 新文档与任务提交保持同一收尾：已落任务留给显式重试，未落任务由入口删除新文档。
                val queued = try {
                    withContext(NonCancellable) {
                        MediaDownloads.enqueueToDirectory(getApplication<Application>(), profileId, item.id, item.name, source.fileName)
                    }
                } catch (_: DownloadDirectoryUnavailable) {
                    currentCoroutineContext().ensureActive()
                    if (epoch == generation && state.value.currentProfileId == profileId)
                        notify(tr("下载目录不可用，请重新选择保存位置"))
                    null
                }
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                if (queued != null) completeDownload(selection, epoch)
                else mutableState.update { current ->
                    if (current.pendingDownload?.requestId == selection.requestId)
                        current.copy(pendingDownload = selection.copy(needsDestination = true)) else current
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    state.value.pendingDownload?.takeIf { it.profileId == profileId && it.itemId == item.id }
                        ?.let { consumeDownloadSelection(it.requestId) }
                    if (!handleAuthFailure(error)) mutableState.update { it.copy(notice = message(error)) }
                }
            } finally { if (epoch == generation) mutableState.update { it.copy(actionBusy = it.actionBusy - key) } }
        }
    }
    fun mediaChanged(expected: Session, itemId: String) {
        if (!expected.hasSameAuthentication(state.value.session)) return
        refreshChangedMedia(setOf(itemId))
    }

    /** 单项编辑与批量刷新共用失效范围，保留已有内容直到新的读取完成。 */
    private fun refreshChangedMedia(itemIds: Set<String>) {
        if (itemIds.isEmpty()) return
        itemIds.forEach { itemId ->
            invalidatePlaybackPrefetch(itemId)
            cancelDetailLoad(itemId)
            cancelDetailExtras(itemId)
        }
        dirtyDetails += itemIds
        markBrowsersDirty()
        (state.value.route as? Route.Detail)?.id?.takeIf { it in itemIds }?.let { refreshDetail(it) }
        refreshHome()
    }

    fun refreshMetadata(item: MediaItem, options: MetadataRefreshOptions = MetadataRefreshOptions()) = runMediaAction(item, "refresh", {
        MediaManagementApi(api).refreshItem(it, item.id, options)
    }) { session ->
        notify(tr("已提交元数据刷新"))
        mediaChanged(session, item.id)
    }

    fun deleteMedia(item: MediaItem) = runMediaAction(item, "delete", {
        MediaManagementApi(api).deleteItem(it, item.id)
    }) {
        removeDeletedMedia(item.id)
        finishMediaDeletion()
        notify(tr("媒体已删除"))
    }

    /** 媒体库多选的批量操作：逐条执行，部分失败时如实报告完成数与首个失败原因。 */
    fun runBatch(items: List<MediaItem>, action: BatchMediaAction) {
        val session = state.value.session ?: return
        if (items.isEmpty() || BATCH_KEY in state.value.actionBusy) return
        val epoch = generation
        fun current() = epoch == generation && session.hasSameAuthentication(state.value.session)
        deletingBatch = action == BatchMediaAction.Delete
        mutableState.update { it.copy(actionBusy = it.actionBusy + BATCH_KEY) }
        extraJobs[BATCH_KEY] = viewModelScope.launch {
            var deleted = false
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val management = MediaManagementApi(api)
                var done = 0
                var firstError: String? = null
                val refreshed = mutableSetOf<String>()
                for (item in items) {
                    try {
                        when (action) {
                            BatchMediaAction.Delete -> {
                                management.deleteItem(session, item.id)
                                if (current()) {
                                    if (!deleted) invalidateBrowserSnapshots()
                                    removeDeletedMedia(item.id)
                                    deleted = true
                                }
                            }
                            is BatchMediaAction.Played -> {
                                val data = api.setPlayed(session, item.id, action.played)
                                if (current()) applyUserDataChange(BrowserUserDataChange(item, data, BrowserUserDataChange.Kind.PLAYED))
                            }
                            is BatchMediaAction.Refresh -> {
                                management.refreshItem(session, item.id, action.options)
                                if (current()) refreshed += item.id
                            }
                        }
                        done++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (!current() || handleAuthFailure(error)) return@launch
                        if (firstError == null) firstError = message(error)
                    }
                }
                if (!current()) return@launch
                if (refreshed.isNotEmpty()) refreshChangedMedia(refreshed)
                val failed = items.size - done
                notify(if (failed > 0) tr("%1\$s 项完成，%2\$s 项失败：%3\$s", done, failed, firstError) else when (action) {
                    BatchMediaAction.Delete -> tr("已删除 %1\$s 项", done)
                    is BatchMediaAction.Played -> if (action.played) tr("已将 %1\$s 项标记为已播放", done) else tr("已将 %1\$s 项标记为未播放", done)
                    is BatchMediaAction.Refresh -> tr("已提交 %1\$s 项元数据刷新", done)
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current() && !handleAuthFailure(error)) notify(message(error)) }
            finally {
                if (epoch == generation) {
                    deletingBatch = false
                    // 成功项立即从界面移除；整批（含部分失败）结束后只重刷一次。
                    if (deleted && current()) finishMediaDeletion()
                    mutableState.update { it.copy(actionBusy = it.actionBusy - BATCH_KEY) }
                }
            }
        }
    }

    private fun runMediaAction(item: MediaItem, operation: String, action: suspend (Session) -> Unit,
        onSuccess: (Session) -> Unit) {
        val session = state.value.session ?: return
        val key = "$operation/${item.id}"
        if (key in state.value.actionBusy) return
        val epoch = generation
        mutableState.update { it.copy(actionBusy = it.actionBusy + key) }
        extraJobs[key] = viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                action(session)
                currentCoroutineContext().ensureActive()
                if (epoch == generation && session.hasSameAuthentication(state.value.session)) onSuccess(session)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (epoch == generation && session.hasSameAuthentication(state.value.session) && !handleAuthFailure(error)) notify(message(error))
            } finally {
                if (epoch == generation) mutableState.update { it.copy(actionBusy = it.actionBusy - key) }
            }
        }
    }
    private fun removeDeletedMedia(itemId: String) {
        invalidatePlaybackPrefetch(itemId)
        val pending = state.value
        if (listOfNotNull(pending.pendingPlayback?.item, pending.sourceSelection?.item).any {
                it.id == itemId || it.seriesId == itemId || it.seasonId == itemId
            }) cancelPendingPlayback()
        // 已发出的读取不能在下一项删除等待期间重新插回已删除条目。
        activeMediaReads.forEach { it.remove(itemId) }
        cancelDetailLoad(itemId)
        dirtyDetails.remove(itemId)
        cancelDetailExtras(itemId)
        folderJobs.remove(itemId)?.cancel()
        mutableState.update { current ->
            fun ContentState.without() = copy(items = items.filterNot { it.id == itemId },
                total = (total - items.count { it.id == itemId }).coerceAtLeast(0),
                nextIndex = (nextIndex - items.count { it.id == itemId }).coerceAtLeast(0))
            fun MediaPage.without() = copy(items = items.filterNot { it.id == itemId },
                totalCount = (totalCount - items.count { it.id == itemId }).coerceAtLeast(0))
            current.copy(routes = current.routes.filterNot { route ->
                (route is Route.MediaTools && route.item.id == itemId) || (route is Route.Identification && route.item.id == itemId) || (route is Route.Detail && route.id == itemId) ||
                    (route is Route.Folder && route.item.id == itemId) || (route is Route.LibrarySearch && route.library.id == itemId)
            }.ifEmpty { listOf(Route.Home) }, details = current.details - itemId,
                detailExtras = (current.detailExtras - itemId).mapValues { (_, extras) -> extras.copy(
                    resume = extras.resume.filterNot { it.id == itemId }, nextUp = extras.nextUp.filterNot { it.id == itemId },
                    similar = extras.similar.filterNot { it.id == itemId }, siblings = extras.siblings.filterNot { it.id == itemId },
                    movies = extras.movies.without(), series = extras.series.without()) },
                folders = (current.folders - itemId).mapValues { it.value.without() },
                browsers = (current.browsers - itemId - "library-search/$itemId").mapValues { (_, browser) -> browser.copy(content = browser.content.without()) },
                search = current.search.copy(results = current.search.results.copy(
                    sections = current.search.results.sections.mapValues { it.value.without() }),
                    suggestions = current.search.suggestions.without()),
                favorites = current.favorites.copy(sections = current.favorites.sections.mapValues { it.value.without() }),
                home = current.home.copy(libraries = current.home.libraries.filterNot { it.id == itemId },
                    libraryItems = (current.home.libraryItems - itemId).mapValues { it.value.filterNot { media -> media.id == itemId } },
                    resume = current.home.resume.filterNot { it.id == itemId },
                    nextUp = current.home.nextUp.filterNot { it.id == itemId },
                    recent = current.home.recent.filterNot { it.id == itemId }))
        }
        captureHomeSnapshot()
    }

    private fun finishMediaDeletion() {
        markBrowsersDirty()
        restoreCurrentDetail()
        refreshHome()
    }

    fun back() {
        if (state.value.pendingPlayback != null) { cancelPendingPlayback(); return }
        if (state.value.routes == listOf(Route.Search) && !state.value.showServers) { selectTab(Route.Home); return }
        if (state.value.routes.size <= 1) { if (!state.value.showServers || state.value.route != Route.Home) showServers(); return }
        invalidatePlaybackPrefetch()
        playJob?.cancel()
        (state.value.route as? Route.Detail)?.id?.let(::cancelDetailLoad)
        mutableState.update {
            val routes = it.routes.dropLast(1)
            val destination = routes.last() as? Route.Detail
            it.copy(routes = routes, preparingPlay = false, sourceSelection = null, playbackError = null,
                detailLoading = detailLoad?.let { load -> load.itemId == destination?.id } == true, detailError = null)
        }
        restoreCurrentDetail()
        refreshVisibleBrowserIfDirty()
    }

    private fun restoreCurrentDetail() {
        val itemId = (state.value.route as? Route.Detail)?.id ?: return
        if (itemId in dirtyDetails && detailLoad?.itemId != itemId) refreshDetail(itemId)
        if (playbackPrefetch?.itemId != itemId) prefetchPlaybackInfo(itemId)
    }

    fun open(item: MediaItem) {
        cancelServerSwitch()
        PerformanceTrace.navigation(if (item.isFolder) "open_library" else "open_detail")
        if (item.type == "MusicArtist") {
            openBrowse("artist/${item.id}", item.name, LibraryQuery(artistId = item.id,
                itemTypes = setOf("MusicAlbum", "Audio"), sort = LibrarySort.TITLE, descending = false)); return
        }
        if (item.type in setOf("Genre", "MusicGenre")) {
            val sourceKey = when (val route = state.value.route) {
                is Route.Folder -> route.item.id
                is Route.Browse -> route.id
                else -> null
            }
            val source = sourceKey?.let { state.value.browsers[it]?.displayedQuery }
                ?: LibraryQuery(itemTypes = if (item.type == "MusicGenre") setOf("MusicAlbum") else setOf("Movie", "Series"))
            val parent = sourceKey?.let { browserParents[it] }
            val key = listOfNotNull(sourceKey, "genre", item.id).joinToString("/")
            openBrowse(key, item.name, source.copy(tab = LibraryTab.ALL, genres = emptySet(), genreId = item.id,
                nameInitial = null, searchTerm = "", recursive = true, itemTypes = source.itemTypes - "Folder"), parent); return
        }
        val series = item.type == "Series" || item.type == "Season"
        val folder = !series && item.type != "Person" && (item.isFolder || item.type in setOf("BoxSet", "MusicAlbum", "CollectionFolder", "Folder"))
        val route = if (folder) Route.Folder(item) else Route.Detail(item.id)
        mutableState.update {
            it.copy(routes = it.routes + route, details = it.details + (item.id to (it.details[item.id] ?: item)), detailError = null, playbackError = null)
        }
        if (folder) {
            invalidatePlaybackPrefetch()
            configureBrowser(item.id, item.id, item.defaultLibraryQuery())
        } else {
            refreshDetail(item.id)
            if (series) loadFolder(item.id)
            prefetchPlaybackInfo(item.id)
        }
    }

    /**
     * 停留在单片/单集详情时提前向服务器取 PlaybackInfo（STRM 等需要服务器探测的源常要数秒），
     * 点播放时直接使用，准备阶段几乎不再等待。只保留最近一个详情的结果，3 分钟内且起播位置一致才复用，用过即丢弃。
     */
    private fun prefetchPlaybackInfo(itemId: String) {
        invalidatePlaybackPrefetch()
        val session = state.value.session ?: return
        val epoch = generation
        val job = viewModelScope.async {
            // 快速划过详情不发请求；等完整条目到达，以它的续播位置作为起播点。
            delay(PlaybackPrefetchDelayMs)
            val current = state.value
            if (epoch != generation || !session.hasSameAuthentication(current.session) || current.showServers ||
                (current.route as? Route.Detail)?.id != itemId) return@async null
            val item = current.details[itemId]?.takeIf { it.type in setOf("Movie", "Episode", "Video") } ?: return@async null
            val context = getApplication<Application>()
            if (!LocalNetworkPermission.isGranted(context) && LocalNetworkPermission.isRequired(session.server.baseUrl)) return@async null
            val ticks = item.positionTicks
            val videoPreflight = if (current.preferences.prefersOriginalSource(item))
                VideoPreflightPolicy.TRY_ORIGINAL_SOURCE else VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED
            PlaybackPrefetchResult(ticks, api.playbackInfo(session, item.id, startTimeTicks = ticks,
                videoPreflight = videoPreflight), videoPreflight)
        }
        playbackPrefetch = PlaybackPrefetch(itemId, session, epoch, android.os.SystemClock.elapsedRealtime(), job)
    }

    private fun invalidatePlaybackPrefetch(itemId: String? = null) {
        val prefetch = playbackPrefetch ?: return
        if (itemId != null && prefetch.itemId != itemId) return
        playbackPrefetch = null
        prefetch.job.cancel()
    }

    /** 取出与本次起播完全对应的预取结果；不匹配、过期或失败都返回 null，由调用方照常请求。 */
    private suspend fun takePrefetchedPlaybackInfo(itemId: String, startTicks: Long, session: Session, epoch: Int): PlaybackPrefetchResult? {
        val prefetch = playbackPrefetch?.takeIf { it.itemId == itemId } ?: return null
        if (prefetch.epoch != epoch || !prefetch.session.hasSameAuthentication(session) ||
            android.os.SystemClock.elapsedRealtime() - prefetch.createdAt > PlaybackPrefetchMaxAgeMs) {
            invalidatePlaybackPrefetch(itemId); return null
        }
        return try {
            val result = prefetch.job.await()
            result?.takeIf { playbackPrefetch === prefetch && it.ticks == startTicks &&
                it.info.errorCode.isNullOrBlank() && it.info.sources.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) { null }
        finally {
            if (playbackPrefetch === prefetch) playbackPrefetch = null
            if (!prefetch.job.isCompleted) prefetch.job.cancel()
        }
    }

    /** 同一详情的季标签只替换当前季，保留选集抽屉和返回目标。 */
    fun switchDetailSeason(season: MediaItem) {
        val current = state.value
        val route = current.route as? Route.Detail ?: return
        val previous = current.details[route.id] ?: return
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null ||
            current.pendingPlayback != null || current.preparingPlay || current.sourceSelection != null || current.activePlayback != null ||
            previous.type != "Season" || season.type != "Season" || season.id == previous.id ||
            previous.seriesId.isNullOrBlank() || previous.seriesId != season.seriesId) return
        invalidatePlaybackPrefetch()
        // 未打开过的季先只有季列表里的简略条目，没有演职人员与艺术图；沿用上一季的这两项占住版面，
        // 完整详情到达后整体替换，避免“演职人员”整块消失又回来、下方区块上下跳动。
        val borrowArtwork = season.backdropTags.isEmpty()
        val placeholder = season.copy(
            people = season.people.ifEmpty { previous.people },
            backdropTags = if (borrowArtwork) previous.backdropTags else season.backdropTags,
            backdropTag = if (borrowArtwork) previous.backdropTag else season.backdropTag,
            backdropItemId = if (borrowArtwork) previous.backdropItemId ?: previous.id else season.backdropItemId,
        )
        mutableState.update { it.copy(routes = it.routes.dropLast(1) + route.copy(id = season.id),
            details = it.details + (season.id to (it.details[season.id] ?: placeholder)), detailError = null, playbackError = null) }
        refreshDetail(season.id)
        if (season.id !in current.folders) loadFolder(season.id)
    }

    /** 打开输入页不读取媒体或筛选；原库 browser 和搜索 browser 始终分开保存。 */
    fun openLibrarySearch(library: MediaItem) {
        cancelServerSwitch()
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null || library.id.isBlank()) return
        val route = Route.LibrarySearch(library)
        val original = current.browsers[library.id]
        val browser = current.browsers[route.key] ?: BrowserUiState(
            query = library.librarySearchQuery(""),
            style = original?.style ?: PosterStyle.POSTER,
        )
        browserParents[route.key] = library.id
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(
            routes = if (it.route == route) it.routes else it.routes + route,
            browsers = it.browsers + (route.key to browser),
        ) }
    }

    fun submitLibrarySearch(library: MediaItem, term: String) {
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null) return
        val key = Route.LibrarySearch(library).key
        val old = current.browsers[key] ?: return
        if (browserParents[key] != library.id) return
        val query = library.librarySearchQuery(term)
        if (old.query == query) return
        browserJobs.remove(key)?.cancel()
        browserLoads.remove(key); loadedBrowsers.remove(key); dirtyBrowsers.remove(key)
        updateBrowser(key) { it.copy(query = query, contentQuery = null, content = ContentState()) }
        if (query.searchTerm.isNotEmpty()) loadBrowser(key)
    }

    /** 库内搜索建议：空词为库内收藏/随机条目，有词为片名匹配；失败只返回空列表，不打断输入。 */
    suspend fun librarySearchSuggestions(library: MediaItem, term: String): List<MediaItem> {
        val session = state.value.session ?: return emptyList()
        val query = library.librarySearchQuery(term)
        return try {
            ensureLocalNetworkAccess(session.server.baseUrl)
            val page = if (query.searchTerm.isEmpty()) api.searchSuggestions(session, library.id, query.itemTypes)
                else api.items(session, parentId = library.id, limit = LibrarySuggestionLimit, options = query)
            page.items.filter { it.name.isNotBlank() }.distinctBy { it.id }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }
    }

    /** 首页下拉与自动刷新入口：登录、重新认证进行中不重复发起。 */
    fun refreshHomeFromPage() {
        val current = state.value
        if (current.loginBusy || current.reauthenticationProfile != null) return
        refreshHome()
    }

    fun refreshHome() {
        val initial = state.value
        val session = initial.session ?: return
        val profileId = initial.currentProfileId ?: return
        val homeKey = ProfileHomeKey.of(profileId, session)
        val trace = PerformanceTrace.begin("home_load")
        homeJob?.cancel()
        val epoch = generation
        val refresh = ++homeRefreshGeneration
        fun isCurrentRequest(): Boolean {
            val current = state.value
            val currentSession = current.session ?: return false
            val currentId = current.currentProfileId ?: return false
            return epoch == generation && refresh == homeRefreshGeneration && session.hasSameAuthentication(currentSession) &&
                ProfileHomeKey.of(currentId, currentSession) == homeKey
        }
        // 首次续播不等待下一集；暖刷新保留已确认的观看分组，避免临时去重结果插入/移除整行。
        val keepWatchingTogether = initial.home.resumeLoaded && initial.home.nextUpLoaded
        mutableState.update { it.copy(home = it.home.copy(loading = true, complete = false)) }
        homeJob = launchMediaRead { changes ->
            val errors = mutableListOf<Throwable>()
            fun accept(result: Result<*>): Boolean {
                if (!isCurrentRequest()) return false
                result.exceptionOrNull()?.let { error ->
                    if (handleAuthFailure(error)) return false
                    errors += error
                    mutableState.update { it.copy(home = it.home.copy(error = message(errors.first()))) }
                }
                return true
            }
            coroutineScope {
                launch watchingRequest@ {
                    val resumeRequest = async { serverSafely(session) { api.resumeItems(session, limit = 12) } }
                    val nextUpRequest = async { serverSafely(session) { api.nextUp(session, limit = 24) } }
                    val resume = resumeRequest.await()
                    if (!accept(resume)) return@watchingRequest
                    trace.point("resume_ready", resume.getOrNull()?.items?.size?.toLong() ?: -1)
                    if (!keepWatchingTogether) {
                        mutableState.update { current -> current.copy(home = current.home.copy(
                            resume = resume.getOrNull()?.items?.let(changes::reconcile) ?: current.home.resume,
                            resumeLoaded = true,
                        )) }
                        captureHomeSnapshot()
                    }
                    val nextUp = nextUpRequest.await()
                    if (!accept(nextUp)) return@watchingRequest
                    mutableState.update { current -> current.copy(home = current.home.copy(
                        resume = if (keepWatchingTogether) resume.getOrNull()?.items?.let(changes::reconcile) ?: current.home.resume
                            else current.home.resume,
                        resumeLoaded = true,
                        nextUp = nextUp.getOrNull()?.items?.let(changes::reconcile) ?: current.home.nextUp, nextUpLoaded = true,
                    )) }
                    captureHomeSnapshot()
                }
                launch librariesRequest@ {
                    val result = serverSafely(session) { api.libraries(session) }
                    if (!accept(result)) return@librariesRequest
                    val libraries = result.getOrNull()?.let(changes::reconcile)
                    trace.point("libraries_ready", libraries?.size?.toLong() ?: -1)
                    mutableState.update { current ->
                        val home = current.home
                        current.copy(home = if (libraries == null) home.copy(librariesLoaded = true) else {
                            val ids = libraries.mapTo(mutableSetOf()) { it.id }
                            home.copy(libraries = libraries, libraryItems = home.libraryItems.filterKeys { it in ids },
                                recent = if (libraries.isNotEmpty()) emptyList() else home.recent,
                                loaded = true, librariesLoaded = true)
                        })
                    }
                    captureHomeSnapshot()
                    if (libraries == null) return@librariesRequest
                    if (libraries.isEmpty()) {
                        val recent = serverSafely(session) { api.latestItems(session, limit = 16) }
                        if (!accept(recent)) return@librariesRequest
                        recent.getOrNull()?.let { page ->
                            mutableState.update { it.copy(home = it.home.copy(recent = changes.reconcile(page.items), loaded = true)) }
                            captureHomeSnapshot()
                        }
                        return@librariesRequest
                    }
                    val semaphore = Semaphore(3)
                    // 与 Web 一致，只为有“最近入库”的媒体库请求海报栏。
                    libraries.filter { it.id !in state.value.hiddenLibraries && it.supportsLatestItems }.forEach { library -> launch shelfRequest@ {
                        val items = semaphore.withPermit {
                            serverSafely(session) { api.latestItems(session, parentId = library.id,
                                limit = if (library.collectionType == "music") 30 else 16) }
                        }
                        if (!accept(items)) return@shelfRequest
                        trace.point("shelf_ready", items.getOrNull()?.items?.size?.toLong() ?: -1)
                        mutableState.update { current -> current.copy(home = current.home.copy(
                            libraryItems = current.home.libraryItems +
                                (library.id to (items.getOrNull()?.items?.let(changes::reconcile) ?: current.home.libraryItems[library.id].orEmpty())),
                        )) }
                        captureHomeSnapshot()
                    } }
                }
            }
            if (isCurrentRequest()) {
                mutableState.update { it.copy(home = it.home.copy(loading = false, complete = errors.isEmpty(), error = errors.firstOrNull()?.let(::message))) }
                captureHomeSnapshot()
            }
        }
    }

    /** 服务器展示名称是补充信息，认证成功后即可浏览，不等待此可选接口。 */
    private fun refreshServerName(profile: ServerProfile, epoch: Int) {
        serverNameJob?.cancel()
        serverNameJob = viewModelScope.launch {
            val name = serverSafely(profile.session) { api.publicServerInfo(profile.session.server) }
                .getOrNull()?.name?.takeIf { it.isNotBlank() } ?: return@launch
            if (epoch != generation) return@launch
            val profiles = storeIo {
                val latest = sessionStore.loadProfiles().firstOrNull { it.id == profile.id } ?: return@storeIo null
                if (latest.displayName != profile.displayName || latest.session != profile.session) return@storeIo null
                runCatching { sessionStore.upsertProfile(latest.copy(displayName = name)); sessionStore.loadProfiles() }.getOrNull()
            }
            if (epoch == generation && profiles != null) mutableState.update { it.copy(profiles = profiles) }
        }
    }

    fun loadFolder(id: String, append: Boolean = false) {
        val session = state.value.session ?: return
        val old = state.value.folders[id] ?: ContentState()
        if (append && (old.loading || old.loadingMore || old.nextIndex >= old.total)) return
        folderJobs[id]?.cancel()
        val epoch = generation
        val start = if (append) old.nextIndex else 0
        val target = if (append) start else old.nextIndex
        updateFolder(id) { it.copy(loading = !append, loadingMore = append, error = null, retryAppend = append) }
        folderJobs[id] = launchMediaRead { changes ->
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val (page, nextIndex) = readItemsRange(session, id, start = start, target = target)
                if (epoch != generation) return@launchMediaRead
                updateFolder(id) {
                    ContentState(
                        items = changes.reconcile((if (append) it.items + page.items else page.items).distinctBy(MediaItem::id)),
                        total = page.totalCount,
                        nextIndex = nextIndex,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation && !handleAuthFailure(error)) updateFolder(id) {
                    it.copy(loading = false, loadingMore = false, error = message(error))
                }
            }
        }
    }

    /** 首读/追加取一页；刷新读齐旧游标 target 后一次返回，失败不发布部分列表。 */
    private suspend fun readItemsRange(
        session: Session,
        parentId: String?,
        options: LibraryQuery? = null,
        start: Int = 0,
        target: Int = start,
        pageSize: Int = 40,
    ): Pair<MediaPage, Int> {
        val items = mutableListOf<MediaItem>()
        var next = start
        var total = 0
        do {
            currentCoroutineContext().ensureActive()
            val page = api.items(session, parentId = parentId, options = options,
                startIndex = next, limit = (target - next).coerceIn(pageSize, 100))
            currentCoroutineContext().ensureActive()
            if (items.isNotEmpty() && total != page.totalCount)
                throw ApiException(null, tr("媒体列表已变化，请重新刷新"))
            total = page.totalCount
            items += page.items
            // 去重仅在发布时做，不能用唯一条目数代替服务器下一页游标。
            next = if (page.items.isEmpty()) total else next + page.items.size
        } while (next < target && next < total)
        return MediaPage(items, total) to next
    }

    fun search(query: String) {
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null) return
        val term = query.trim()
        if (term != current.search.query) {
            cancelDiscoveryPages(favorite = false)
            mutableState.update { it.copy(search = it.search.copy(query = term, results = DiscoveryState())) }
        }
        if (term.isBlank()) loadSearchSuggestions() else ensureSearchSections()
    }

    fun toggleSearchCategory(category: DiscoveryCategory?) {
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null) return
        val old = current.search.categories
        val selected = when {
            category == null -> emptySet()
            category in old -> old - category
            else -> old + category
        }
        if (old == selected) return
        if (selected.isNotEmpty()) {
            searchDiscoveryCategories.filter { it !in selected }.forEach { other ->
                val key = "search/${other.name}"
                discoveryLoads.remove(key)
                discoveryJobs.remove(key)?.cancel()
            }
        }
        mutableState.update { state -> state.copy(search = state.search.copy(categories = selected,
            results = state.search.results.copy(sections = state.search.results.sections.mapValues { (type, content) ->
                if (selected.isNotEmpty() && type !in selected && (content.loading || content.loadingMore))
                    content.copy(loading = false, loadingMore = false, queryKey = if (content.loading) null else content.queryKey)
                else content
            }))) }
        if (state.value.search.query.isNotBlank()) ensureSearchSections()
    }

    private fun ensureSearchSections(force: Boolean = false) {
        val search = state.value.search
        if (search.query.isBlank()) return
        searchDiscoveryCategories.filter { search.categories.isEmpty() || it in search.categories }.forEach {
            loadDiscoveryPage(favorite = false, category = it, force = force)
        }
    }

    fun refreshSearch() {
        if (state.value.search.query.isBlank()) loadSearchSuggestions(force = true)
        else ensureSearchSections(force = true)
    }

    fun openDiscovery(category: DiscoveryCategory, favorite: Boolean) {
        cancelServerSwitch()
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null ||
            (favorite && category !in favoriteDiscoveryCategories) || (!favorite && current.search.query.isBlank())) return
        val route = Route.Discovery(category, favorite, if (favorite) "" else current.search.query)
        if (current.route == route) return
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + route) }
        loadDiscoveryPage(favorite, category)
    }

    fun refreshDiscovery(category: DiscoveryCategory, favorite: Boolean) = loadDiscoveryPage(favorite, category, force = true)
    fun loadMoreDiscovery(category: DiscoveryCategory, favorite: Boolean) = loadDiscoveryPage(favorite, category, append = true)

    private fun ensureFavorites() {
        favoriteDiscoveryCategories.forEach { loadDiscoveryPage(true, it) }
    }

    fun refreshFavorites() {
        val current = state.value
        if (current.session == null || current.loginBusy || current.reauthenticationProfile != null) return
        favoriteDiscoveryCategories.forEach { loadDiscoveryPage(true, it, force = true) }
    }

    private fun cancelDiscoveryPages(favorite: Boolean, category: DiscoveryCategory? = null) {
        val prefix = if (favorite) "favorites/" else "search/"
        discoveryLoads.keys.filter { it.startsWith(prefix) && (category == null || it == "$prefix${category.name}") }.forEach { key ->
            discoveryLoads.remove(key)
            discoveryJobs.remove(key)?.cancel()
        }
    }

    private fun releaseSearchResults() {
        if (Route.Search !in state.value.routes) return
        cancelDiscoveryPages(favorite = false)
        suggestionsLoad = null
        suggestionsJob?.cancel()
        suggestionsJob = null
        mutableState.update { current -> current.copy(
            search = SearchState(suggestions = current.search.suggestions.copy(loading = false, loadingMore = false, error = null),
                suggestionsLoaded = current.search.suggestionsLoaded),
            searchVisitId = UUID.randomUUID().toString(),
        ) }
    }

    private fun updateDiscovery(favorite: Boolean, change: (DiscoveryState) -> DiscoveryState) {
        mutableState.update { current -> if (favorite) current.copy(favorites = change(current.favorites))
            else current.copy(search = current.search.copy(results = change(current.search.results))) }
    }

    private fun loadDiscoveryPage(favorite: Boolean, category: DiscoveryCategory, append: Boolean = false, force: Boolean = false) {
        val current = state.value
        val session = current.session ?: return
        if (current.loginBusy || current.reauthenticationProfile != null || (favorite && category !in favoriteDiscoveryCategories)) return
        val term = if (favorite) "" else current.search.query
        if (!favorite && term.isBlank()) return
        val queryKey = if (favorite) "favorites" else term
        val content = (if (favorite) current.favorites else current.search.results).sections[category] ?: ContentState()
        if (append && (category == DiscoveryCategory.PEOPLE || content.queryKey != queryKey || content.loading || content.loadingMore ||
                content.error != null || content.nextIndex >= content.total)) return
        if (!append && !force && (content.loading || (content.queryKey == queryKey && content.error == null))) return
        val key = "${if (favorite) "favorites" else "search"}/${category.name}"
        discoveryJobs.remove(key)?.cancel()
        val load = Any()
        discoveryLoads[key] = load
        val epoch = generation
        val start = if (append) content.nextIndex else 0
        updateDiscovery(favorite) { it.copy(requested = true, sections = it.sections + (category to content.copy(
            loading = !append, loadingMore = append, error = null, retryAppend = append, queryKey = queryKey))) }
        discoveryJobs[key] = viewModelScope.launch {
            try {
                val (page, cursor) = discoveryConcurrency.withPermit {
                    ensureLocalNetworkAccess(session.server.baseUrl)
                    // 刷新覆盖已浏览范围后再整体替换，避免深页横架骤缩成首 24 项。
                    val target = if (append) start + 24 else maxOf(24, content.nextIndex)
                    val items = mutableListOf<MediaItem>()
                    var next = start
                    var total: Int
                    do {
                        val part = api.discoveryPage(session, category, term, favorite, next, (target - next).coerceIn(1, 100))
                        items += part.items
                        total = part.totalCount
                        next = if (part.items.isEmpty() || category == DiscoveryCategory.PEOPLE) total else next + part.items.size
                    } while (!append && category != DiscoveryCategory.PEOPLE && next < target && next < total)
                    MediaPage(items, total) to next
                }
                currentCoroutineContext().ensureActive()
                if (epoch != generation || !session.hasSameAuthentication(state.value.session) || discoveryLoads[key] !== load) return@launch
                updateDiscovery(favorite) { group ->
                    val previous = group.sections[category] ?: ContentState()
                    group.copy(order = if (page.items.isNotEmpty() && category !in group.order) group.order + category else group.order,
                        sections = group.sections + (category to ContentState(
                        items = (if (append) previous.items + page.items else page.items).distinctBy(MediaItem::id),
                        total = page.totalCount,
                        nextIndex = cursor,
                        queryKey = queryKey,
                    )))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation && discoveryLoads[key] === load && !handleAuthFailure(error)) updateDiscovery(favorite) { group ->
                    val previous = group.sections[category] ?: ContentState()
                    group.copy(sections = group.sections + (category to previous.copy(loading = false, loadingMore = false, error = message(error))))
                }
            } finally {
                if (discoveryLoads[key] === load) { discoveryLoads.remove(key); discoveryJobs.remove(key) }
            }
        }
    }

    private fun loadSearchSuggestions(force: Boolean = false) {
        val current = state.value
        val session = current.session ?: return
        if (current.loginBusy || current.reauthenticationProfile != null ||
            (!force && (current.search.suggestionsLoaded || current.search.suggestions.loading))) return
        suggestionsJob?.cancel()
        val load = Any()
        suggestionsLoad = load
        val epoch = generation
        mutableState.update { it.copy(search = it.search.copy(suggestions = it.search.suggestions.copy(loading = true, error = null))) }
        suggestionsJob = viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val page = api.searchSuggestions(session)
                currentCoroutineContext().ensureActive()
                if (epoch != generation || !session.hasSameAuthentication(state.value.session) || suggestionsLoad !== load) return@launch
                val suggestions = page.items.filter { it.name.isNotBlank() }.distinctBy { it.name.trim() }
                mutableState.update { it.copy(search = it.search.copy(suggestionsLoaded = true,
                    suggestions = ContentState(items = suggestions, total = suggestions.size, nextIndex = suggestions.size))) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation && suggestionsLoad === load && !handleAuthFailure(error)) mutableState.update {
                    it.copy(search = it.search.copy(suggestions = it.search.suggestions.copy(loading = false, error = message(error))))
                }
            } finally { if (suggestionsLoad === load) { suggestionsLoad = null; suggestionsJob = null } }
        }
    }

    private fun invalidateDiscovery(category: DiscoveryCategory? = null) {
        cancelDiscoveryPages(false, category)
        cancelDiscoveryPages(true, category)
        fun DiscoveryState.invalidate() = copy(sections = sections.mapValues { (type, content) ->
            if (category == null || type == category) content.copy(loading = false, loadingMore = false, queryKey = null)
            else content })
        mutableState.update { it.copy(search = it.search.copy(results = it.search.results.invalidate()), favorites = it.favorites.invalidate()) }
    }

    fun refreshDetail(id: String, reconcilePlayback: Boolean = false) {
        val session = state.value.session ?: return
        // 简略卡片先行显示不代表完整详情已完成；取消后返回仍需补齐。
        dirtyDetails += id
        detailJob?.cancel()
        val load = DetailLoad(id)
        detailLoad = load
        val epoch = generation
        mutableState.update { it.copy(detailLoading = true, detailError = null) }
        detailJob = launchMediaRead { changes ->
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val response = api.item(session, id)
                currentCoroutineContext().ensureActive()
                if (epoch != generation || detailLoad !== load || !session.hasSameAuthentication(state.value.session)) return@launchMediaRead
                val item = changes.reconcile(response)
                if (reconcilePlayback) {
                    applyUserDataChange(BrowserUserDataChange(state.value.details[id] ?: item, item.userData(), BrowserUserDataChange.Kind.PLAYBACK))
                    refreshPlaybackParents(session, item)
                }
                dirtyDetails.remove(id)
                mutableState.update { it.copy(details = it.details + (id to item), detailLoading = false) }
                loadDetailExtras(item)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation && detailLoad === load && session.hasSameAuthentication(state.value.session) && !handleAuthFailure(error)) mutableState.update {
                    it.copy(detailLoading = false, detailError = message(error))
                }
            } finally {
                if (detailLoad === load) detailLoad = null
            }
        }
    }

    private fun cancelDetailLoad(itemId: String) {
        if (detailLoad?.itemId != itemId) return
        detailJob?.cancel()
        detailLoad = null
        mutableState.update { it.copy(detailLoading = false, detailError = null) }
    }

    fun preparePlayback(item: MediaItem, fromStart: Boolean = false, sourceId: String? = null) {
        beginPlaybackPreparation(item, fromStart, sourceId)
    }

    fun prepareEpisodePlayback(item: MediaItem) {
        if (item.type != "Episode") return
        val full = state.value.details[item.id]
        beginPlaybackPreparation(full ?: item, fromStart = true,
            loadDetails = full == null)
    }

    fun retryPendingPlayback() {
        val pending = state.value.pendingPlayback ?: return
        beginPlaybackPreparation(pending.item, pending.fromStart, pending.sourceId, pending.loadDetails, pending.quality)
    }

    private fun beginPlaybackPreparation(item: MediaItem, fromStart: Boolean,
        sourceId: String? = null, loadDetails: Boolean = false, quality: PlaybackQuality = PlaybackQuality.ORIGINAL) {
        val current = state.value
        val session = current.session ?: return
        if (current.preparingPlay || current.loginBusy || current.reauthenticationProfile != null) return
        playJob?.cancel()
        val epoch = generation
        val profileId = current.currentProfileId
        val pending = PendingPlayback(++playbackPreparationSequence, item, fromStart, sourceId, loadDetails, quality)
        // 不能把界面入口放在任何网络、权限或加密存储挂起之后。
        mutableState.update { it.copy(pendingPlayback = pending, preparingPlay = true, sourceSelection = null, playbackError = null) }
        playJob = viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val target = when {
                    item.type == "Series" || item.type == "Season" -> resolveSeriesEpisode(session, item, fromStart)
                    loadDetails -> api.item(session, item.id)
                    else -> item
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrentPreparation(pending.id, session, profileId, epoch)) return@launch
                val resolvedPending = pending.copy(item = target, loadDetails = false)
                mutableState.update { it.copy(pendingPlayback = resolvedPending,
                    details = if (loadDetails) it.details + (target.id to target) else it.details) }
                val start = if (fromStart) 0L else TimeUnits.ticksToMs(target.positionTicks)
                val startTicks = TimeUnits.msToTicks(start)
                val prefetched = if (sourceId == null && quality == PlaybackQuality.ORIGINAL)
                    takePrefetchedPlaybackInfo(target.id, startTicks, session, epoch) else null
                val videoPreflight = prefetched?.videoPreflight ?: if (state.value.preferences.prefersOriginalSource(target))
                    VideoPreflightPolicy.TRY_ORIGINAL_SOURCE else VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED
                val info = prefetched?.info ?: api.playbackInfo(session, target.id, sourceId, startTicks, quality,
                    videoPreflight = videoPreflight)
                currentCoroutineContext().ensureActive()
                if (!isCurrentPreparation(pending.id, session, profileId, epoch)) return@launch
                require(info.errorCode.isNullOrBlank()) { tr("服务器暂时无法准备播放，请检查权限或媒体源") }
                require(info.sources.isNotEmpty()) { tr("该媒体没有可播放的媒体源") }
                if (sourceId != null) require(info.sources.any { it.id == sourceId }) { tr("当前版本已不可用，请重新选择媒体源") }
                val selection = SourceSelection(target, info, start,
                    needsSelectedVideoPreflight = videoPreflight == VideoPreflightPolicy.TRY_ORIGINAL_SOURCE ||
                        (sourceId == null && info.sources.size > 1))
                mutableState.update { it.copy(preparingPlay = false, sourceSelection = selection) }
                val selectedId = sourceId ?: info.sources.singleOrNull()?.id
                if (selectedId != null) commitPlaybackSource(resolvedPending, selection, selectedId, session, profileId, epoch)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrentPreparation(pending.id, session, profileId, epoch) && !handleAuthFailure(error)) mutableState.update {
                    it.copy(preparingPlay = false, sourceSelection = null, playbackError = message(error))
                }
            }
        }
    }

    private class PlaybackPrefetch(val itemId: String, val session: Session, val epoch: Int, val createdAt: Long,
        val job: Deferred<PlaybackPrefetchResult?>)
    private data class PlaybackPrefetchResult(val ticks: Long, val info: PlaybackInfo, val videoPreflight: VideoPreflightPolicy)
    private var playbackPrefetch: PlaybackPrefetch? = null

    private fun isCurrentPreparation(id: Long, session: Session, profileId: String?, epoch: Int): Boolean {
        val current = state.value
        return epoch == generation && session.hasSameAuthentication(current.session) && current.currentProfileId == profileId && current.pendingPlayback?.id == id
    }

    fun chooseSource(sourceId: String) {
        val current = state.value
        val pending = current.pendingPlayback ?: return
        val selection = current.sourceSelection ?: return
        val session = current.session ?: return
        if (current.preparingPlay) return
        val epoch = generation
        playJob?.cancel()
        mutableState.update { it.copy(preparingPlay = true, playbackError = null) }
        playJob = viewModelScope.launch {
            try {
                commitPlaybackSource(pending, selection, sourceId, session, current.currentProfileId, epoch)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrentPreparation(pending.id, session, current.currentProfileId, epoch) && !handleAuthFailure(error)) mutableState.update {
                    it.copy(sourceSelection = null, preparingPlay = false, playbackError = message(error))
                }
            }
        }
    }

    private suspend fun commitPlaybackSource(pending: PendingPlayback, selection: SourceSelection, sourceId: String,
        session: Session, profileId: String?, epoch: Int) {
        if (!isCurrentPreparation(pending.id, session, profileId, epoch) || state.value.sourceSelection != selection) return
        mutableState.update { it.copy(preparingPlay = true, pendingPlayback = pending.copy(sourceId = sourceId)) }
        val source = selection.info.sources.single { it.id == sourceId }
        val preferences = state.value.preferences
        val cloudEligible = preferences.originalPathEnabled && selection.item.type in setOf("Movie", "Episode", "Video") &&
            source.path?.let(dev.pathfin.android.data.CloudPlaybackApi::isCandidate) == true
        val cloud = if (cloudEligible) {
            ensureLocalNetworkAccess(checkNotNull(source.path))
            cloudApi.discover(session, source)
        } else null
        val request = if (cloud != null) {
            val cloudQuality = if (preferences.strmDefaultQuality == PlaybackQuality.ORIGINAL &&
                cloud.variant(PlaybackQuality.ORIGINAL)?.playable == false) {
                require(cloud.variant(PlaybackQuality.HIGH) != null) { tr("本机不支持此原画，网盘也没有兼容画质。请更换片源。") }
                PlaybackQuality.HIGH
            } else preferences.strmDefaultQuality
            val original = ResolvedPlayback(cloud.sourceUrl, emptyMap(), PlayMethod.DIRECT_PLAY, sourceId, false)
            cloudApi.select(PlaybackRequest(selection.item, selection.info, source, original, selection.startMs, session, profileId,
                cloudPlayback = cloud), cloudQuality)
        } else {
            // 原始HTTP直连不依赖云盘画质发现协议，不能因旧服务返回302而改走服务器。
            val originalHttp = PlaybackResolver.prefersOriginalHttp(selection.item, source,
                preferences.prefersOriginalSource(selection.item), pending.quality, selection.info.delivery)
            val video = source.mediaStreams.firstOrNull { it.type.equals("Video", true) }
            val localSupport = if (selection.info.delivery != PlaybackDelivery.TRANSCODE &&
                !(selection.item.type == "TvChannel" && preferences.liveTvDirectPlay) && video != null)
                withContext(Dispatchers.Default) { DevicePlaybackCapabilities.supportsVideo(video) } else null
            require(!originalHttp || localSupport != false) { tr("本机不支持此原画，请更换片源或关闭原始路径后选择服务器画质。") }
            val info = when {
                localSupport == false -> api.playbackInfo(session, selection.item.id, sourceId,
                    TimeUnits.msToTicks(selection.startMs), pending.quality, PlaybackDelivery.TRANSCODE,
                    selection.info.audioStreamIndex, selection.info.subtitleStreamIndex)
                localSupport == true && !originalHttp && selection.needsSelectedVideoPreflight ->
                    api.negotiateSelectedSourceVideo(session, selection.item.id, selection.info, sourceId,
                        TimeUnits.msToTicks(selection.startMs), pending.quality)
                else -> selection.info
            }
            val selected = info.sources.firstOrNull { it.id == sourceId }
                ?: throw IllegalArgumentException(tr("所选媒体版本已不存在，请重新选择"))
            val resolved = PlaybackResolver.resolve(session, selection.item, info, sourceId,
                preferences.prefersOriginalSource(selection.item), pending.quality)
            PlaybackRequest(selection.item, info, selected, resolved, selection.startMs, session, profileId, pending.quality)
        }
        ensureLocalNetworkAccess(request.stream.uri)
        currentCoroutineContext().ensureActive()
        if (!isCurrentPreparation(pending.id, session, profileId, epoch) || state.value.sourceSelection != selection) return
        mutableState.update { it.copy(pendingPlayback = null, sourceSelection = null, preparingPlay = false,
            activePlayback = request, playbackError = null, playbackNeighbors = emptyList(),
            playbackEpisodes = if (it.activePlayback?.item?.seriesId == request.item.seriesId) it.playbackEpisodes else ContentState()) }
        recordLastPlayed(request, epoch)
        loadPlaybackNeighbors(request)
    }

    private fun recordLastPlayed(request: PlaybackRequest, epoch: Int) {
        lastPlayedJob?.cancel()
        val profileId = request.profileId ?: return
        fun isCurrent(): Boolean {
            val current = state.value
            return epoch == generation && current.activePlayback === request && request.session.hasSameAuthentication(current.session) && current.currentProfileId == profileId
        }
        lastPlayedJob = viewModelScope.launch {
            try {
                val saved = storeIo {
                    if (!isCurrent()) return@storeIo null
                    val profile = sessionStore.loadProfiles().firstOrNull { it.id == profileId && request.session.hasSameAuthentication(it.session) }
                        ?: return@storeIo null
                    if (!isCurrent()) return@storeIo null
                    profile.copy(lastPlayedAt = System.currentTimeMillis()).also(sessionStore::upsertProfile)
                }
                if (saved != null && isCurrent()) mutableState.update { current -> current.copy(
                    profiles = current.profiles.map { if (it.id == profileId && request.session.hasSameAuthentication(it.session)) it.copy(lastPlayedAt = saved.lastPlayedAt) else it }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 最近播放时间保存失败不阻止已经提交的播放，也不覆盖播放器错误。 */ }
        }
    }

    private fun cancelPendingPlayback() {
        playJob?.cancel()
        playJob = null
        mutableState.update { it.copy(pendingPlayback = null, sourceSelection = null, preparingPlay = false, playbackError = null) }
    }

    private fun loadPlaybackNeighbors(request: PlaybackRequest) {
        playbackNeighborsJob?.cancel()
        val series = request.item.seriesId?.takeIf { request.item.type == "Episode" } ?: return
        val epoch = generation
        playbackNeighborsJob = launchMediaRead { changes ->
            try {
                val items = api.adjacentEpisodes(request.session, series, request.item.id).items
                if (epoch == generation && state.value.activePlayback === request) mutableState.update { it.copy(playbackNeighbors = changes.reconcile(items)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 选集面板保留独立重试，失败不打断正在播放的媒体。 */ }
        }
    }

    fun loadPlaybackEpisodes(append: Boolean = false, refresh: Boolean = false) {
        val request = state.value.activePlayback ?: return
        val series = request.item.seriesId?.takeIf { request.item.type == "Episode" } ?: return
        // 选集页可以命中缓存，相邻集失败仍应独立重试；不能用首屏猜测当前集的前后集。
        if (!append && (refresh || state.value.playbackNeighbors.none { it.id == request.item.id }) &&
            playbackNeighborsJob?.isActive != true) loadPlaybackNeighbors(request)
        val epoch = generation
        val queryKey = "$epoch/${sessionKey(request.session)}/$series"
        val old = state.value.playbackEpisodes.takeIf { it.queryKey == queryKey } ?: ContentState()
        if (old.loading || old.loadingMore || (!append && !refresh && old.queryKey == queryKey && old.error == null)) return
        val more = append || (refresh && old.retryAppend)
        if (more && (old.queryKey != queryKey || old.nextIndex >= old.total)) return
        playbackEpisodesJob?.cancel()
        val start = if (more) old.nextIndex else 0
        val target = if (more) start else old.nextIndex
        fun isCurrent() = epoch == generation && request.session.hasSameAuthentication(state.value.session) &&
            state.value.activePlayback?.item?.seriesId == series && state.value.playbackEpisodes.queryKey == queryKey
        mutableState.update { it.copy(playbackEpisodes = old.copy(loading = !more, loadingMore = more,
            error = null, retryAppend = more, queryKey = queryKey)) }
        playbackEpisodesJob = launchMediaRead { changes ->
            try {
                ensureLocalNetworkAccess(request.session.server.baseUrl)
                val (page, nextIndex) = readItemsRange(request.session, series,
                    LibraryQuery(itemTypes = setOf("Episode"), episodeOrder = true, descending = false),
                    start = start, target = target, pageSize = 100)
                if (isCurrent()) mutableState.update {
                    it.copy(playbackEpisodes = ContentState(
                        items = changes.reconcile((if (more) it.playbackEpisodes.items + page.items else page.items).distinctBy(MediaItem::id)),
                        total = page.totalCount, nextIndex = nextIndex, queryKey = queryKey))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrent() && !handleAuthFailure(error)) mutableState.update {
                    it.copy(playbackEpisodes = it.playbackEpisodes.copy(loading = false, loadingMore = false, error = message(error)))
                }
            }
        }
    }

    /** 等相邻集读取结束再判断，避免短片播完时把还没读到的下一集当成最后一集。 */
    suspend fun nextPlaybackEpisode(): MediaItem? {
        while (true) {
            val job = playbackNeighborsJob?.takeIf { it.isActive } ?: break
            job.join()
        }
        return playbackNeighbor(1)
    }

    fun playbackNeighbor(direction: Int): MediaItem? {
        val current = state.value.activePlayback?.item ?: return null
        val neighbors = state.value.playbackNeighbors
        val index = neighbors.indexOfFirst { it.id == current.id }
        return if (index >= 0) neighbors.getOrNull(index + direction.coerceIn(-1, 1)) else null
    }

    fun dismissPlaybackError() { mutableState.update { it.copy(playbackError = null) } }

    fun dismissSourceSelection() = cancelPendingPlayback()

    fun playerClosed(positionMs: Long) {
        // 下一集/版本选择可在旧播放器关闭后才返回；即使尚未建立 activePlayback 也必须取消。
        playJob?.cancel()
        playJob = null
        lastPlayedJob?.cancel(); lastPlayedJob = null
        state.value.activePlayback?.let { request ->
            val ticks = TimeUnits.msToTicks(positionMs)
            applyUserDataChange(BrowserUserDataChange(request.item, MediaUserData(positionTicks = ticks), BrowserUserDataChange.Kind.PLAYBACK))
        }
        playbackNeighborsJob?.cancel(); playbackEpisodesJob?.cancel()
        mutableState.update { it.copy(activePlayback = null, pendingPlayback = null, preparingPlay = false, sourceSelection = null,
            playbackError = null, playbackNeighbors = emptyList(), playbackEpisodes = ContentState()) }
    }

    fun refreshAfterPlayback(session: Session, itemId: String) {
        if (!session.hasSameAuthentication(state.value.session)) return
        refreshDetail(itemId, reconcilePlayback = true)
        refreshHome()
    }

    /** 单集观看会改变季/剧的未看数量；只校验已展示的父项，不重读整个剧集库。 */
    private fun refreshPlaybackParents(session: Session, item: MediaItem) {
        if (item.type != "Episode") return
        val current = state.value
        val epoch = generation
        listOfNotNull(item.seasonId, item.seriesId).distinct().forEach { id ->
            val cached = current.details[id] ?: current.browsers.values.asSequence().flatMap { it.content.items.asSequence() }.firstOrNull { it.id == id }
                ?: current.folders.values.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id }
                ?: current.home.libraryItems.values.asSequence().flatten().firstOrNull { it.id == id } ?: return@forEach
            val key = "playback-parent/$id"
            extraJobs.remove(key)?.cancel()
            extraJobs[key] = viewModelScope.launch {
                val result = serverSafely(session) { api.item(session, id) }
                if (epoch != generation || !session.hasSameAuthentication(state.value.session)) return@launch
                result.onSuccess { parent -> applyUserDataChange(BrowserUserDataChange(cached, parent.userData().copy(isFavorite = null), BrowserUserDataChange.Kind.PLAYBACK)) }
                    .onFailure { handleAuthFailure(it) }
            }
        }
    }

    private fun MediaItem.userData() = MediaUserData(isFavorite, played, positionTicks, playedPercentage, unplayedItemCount, playCount)

    private fun browserKey(key: String) = "${state.value.session?.let(::sessionKey)}/$key"

    private fun browserPageKey(key: String, query: BrowserQueryKey): BrowserPageKey? {
        val current = state.value
        val profileId = current.currentProfileId ?: return null
        val session = current.session ?: return null
        if (deletingBatch || isLibrarySearch(key) || query.query.searchTerm.isNotEmpty()) return null
        return BrowserPageKey(ProfileHomeKey.of(profileId, session), key, query.parentId, query.query)
    }

    /** 同一父库重入保留查询、分页和合法空结果；不同父库不得沿用旧内容。 */
    fun configureBrowser(key: String, parentId: String?, query: LibraryQuery) {
        val existing = state.value.browsers[key]
        val parentChanged = existing != null && browserParents[key] != parentId
        if (parentChanged) {
            browserJobs.remove(key)?.cancel()
            extraJobs.remove("filters/$key")?.cancel()
            browserLoads.remove(key); loadedBrowsers.remove(key)
            filterLoads.remove(key); loadedFilters.remove(key)
            dirtyBrowsers.remove(key)
        }
        browserParents[key] = parentId
        if (existing == null || parentChanged) {
            val savedStyle = preferencesStore.browserStyle(browserKey(key))
            val savedSort = if (parentChanged || isLibrarySearch(key)) null else preferencesStore.browserSort(browserKey(key))
            val initial = BrowserUiState(query = savedSort?.let { (sort, descending) -> query.copy(sort = sort, descending = descending) } ?: query,
                style = existing?.style ?: PosterStyle.entries.firstOrNull { it.name == savedStyle } ?: PosterStyle.POSTER,
                content = ContentState(loading = !isLibrarySearch(key) || query.searchTerm.isNotBlank()))
            mutableState.update { it.copy(browsers = it.browsers + (key to initial)) }
        }
        val current = state.value.browsers.getValue(key)
        if (loadedBrowsers[key] != BrowserQueryKey(parentId, current.query) || key in dirtyBrowsers ||
            (current.content.error != null && !current.content.retryAppend)) {
            loadBrowser(key)
        }
        loadFilters(key)
    }

    fun changeBrowserQuery(key: String, query: LibraryQuery) {
        val current = state.value
        if (current.loginBusy || current.reauthenticationProfile != null || !browserParents.containsKey(key)) return
        val old = current.browsers[key] ?: return
        val safeQuery = query
        if (old.query == safeQuery) return
        if ((old.query.sort != safeQuery.sort || old.query.descending != safeQuery.descending) && !isLibrarySearch(key)) {
            val preferenceKey = browserKey(key)
            viewModelScope.launch {
                try { storeIo { preferencesStore.saveBrowserSort(preferenceKey, safeQuery.sort, safeQuery.descending) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 排序记忆写入失败不影响本次浏览。 */ }
            }
        }
        val queryKey = BrowserQueryKey(browserParents[key], safeQuery)
        // 改选后立即选回当前结果只需取消等待，不能把已读深页换成40项首屏或丢掉原位置。
        val retained = key !in dirtyBrowsers && old.displayedQuery == safeQuery && loadedBrowsers[key] == queryKey
        val cached = if (key !in dirtyBrowsers) browserPageKey(key, queryKey)?.let { browserPages[it] } else null
        val trace = if (old.query.nameInitial != safeQuery.nameInitial) PerformanceTrace.navigation("alphabet_filter") else null
        if (retained || cached != null) {
            browserJobs.remove(key)?.cancel()
            browserLoads.remove(key)
            loadedBrowsers[key] = queryKey
        }
        updateBrowser(key) { browser ->
            val next = when {
                retained -> browser.copy(content = browser.content.copy(loading = false, loadingMore = false, error = null, retryAppend = false))
                cached != null -> browser.withContent(safeQuery, ContentState(
                    items = cached.items.distinctBy { item -> item.id }, total = cached.totalCount,
                    nextIndex = if (cached.items.isEmpty()) cached.totalCount else cached.items.size))
                else -> browser.copy(contentQuery = browser.displayedQuery)
            }
            next.copy(query = safeQuery, restoringCache = if (retained || cached != null) false else next.restoringCache)
        }
        if (!retained && cached == null) loadBrowser(key) else trace?.point("content_cache_ready", state.value.browsers.getValue(key).content.items.size.toLong())
        if (old.displayedQuery.itemTypes != state.value.browsers.getValue(key).displayedQuery.itemTypes) loadFilters(key)
    }

    fun setBrowserStyle(key: String, style: PosterStyle) {
        val current = state.value.browsers[key] ?: return
        if (current.style == style) return
        val preferenceKey = browserKey(key)
        committedBrowserStyles.putIfAbsent(preferenceKey, current.style)
        val revision = (browserStyleRevisions[preferenceKey] ?: 0L) + 1
        browserStyleRevisions[preferenceKey] = revision
        val epoch = generation
        updateBrowser(key) { it.copy(style = style) }
        viewModelScope.launch {
            try {
                storeIo { preferencesStore.saveBrowserStyle(preferenceKey, style.name) }
                committedBrowserStyles[preferenceKey] = style
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == generation) {
                    val committed = committedBrowserStyles.getValue(preferenceKey)
                    updateBrowser(key) { if (browserStyleRevisions[preferenceKey] == revision)
                        it.copy(style = committed) else it }
                    mutableState.update { it.copy(notice = tr("布局保存失败，请重试")) }
                }
            }
        }
    }

    /** 用户主动刷新同时更新列表和可选筛选；两者互不阻塞。 */
    fun refreshBrowser(key: String) {
        val current = state.value
        current.browsers[key]?.let { browser ->
            browserPageKey(key, BrowserQueryKey(browserParents[key], browser.query))?.let { pageKey ->
                browserPageRevision++
                browserPages.invalidate(pageKey.scope, pageKey.parentId)
                val session = requireNotNull(current.session)
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { storeIo {
                    browserSnapshotStore.invalidate(session, pageKey.scope, pageKey.parentId)
                } }
            }
        }
        loadBrowser(key)
        loadFilters(key, force = true)
    }

    fun retryBrowserFilters(key: String) = loadFilters(key, force = true)

    fun loadBrowser(key: String, append: Boolean = false) {
        val current = state.value
        if (current.loginBusy || current.reauthenticationProfile != null || !browserParents.containsKey(key)) return
        val session = current.session ?: return
        val old = current.browsers[key] ?: return
        if (isLibrarySearch(key) && (old.query.searchTerm.isBlank() || browserParents[key].isNullOrBlank())) return
        // 旧结果仍可浏览，但它的游标绝不能用于正在等待的新查询。
        if (append && old.query != old.displayedQuery) return
        val queryKey = BrowserQueryKey(browserParents[key], old.query)
        if (append && (key in dirtyBrowsers || loadedBrowsers[key] != queryKey)) {
            if (!old.content.loading && !old.content.loadingMore) loadBrowser(key)
            return
        }
        if (append && (old.content.loading || old.content.loadingMore || old.content.nextIndex >= old.content.total)) return
        val epoch = generation
        val start = if (append) old.content.nextIndex else 0
        val target = if (append) start else if (loadedBrowsers[key] == queryKey) old.content.nextIndex else 0
        val load = BrowserLoad(queryKey, start, browserMutationRevision)
        // 重按刷新、重复进入同一页面，不取消并重发完全相同的在途请求。
        if (browserJobs[key]?.isActive == true && browserLoads[key] == load) return
        browserJobs[key]?.cancel()
        val pageKey = browserPageKey(key, queryKey)
        val restoreSnapshot = !append && pageKey != null && key !in dirtyBrowsers && loadedBrowsers[key] != queryKey
        if (!append && pageKey != null) browserPages.remove(pageKey)
        val pageRevision = browserPageRevision
        browserLoads[key] = load
        updateBrowser(key) { it.copy(restoringCache = restoreSnapshot,
            content = it.content.copy(loading = !append, loadingMore = append, retryAppend = append)) }
        browserJobs[key] = launchMediaRead { changes ->
            val trace = PerformanceTrace.begin("browser_page")
            fun currentLoad() = generation == epoch && session.hasSameAuthentication(state.value.session) &&
                browserLoads[key] === load && browserParents[key] == queryKey.parentId &&
                state.value.browsers[key]?.query == queryKey.query && load.revision == browserMutationRevision
            try {
                // 网络与磁盘并行：快照只用于立即呈现，不把它当作已经校验的新数据。
                val network = async { serverSafely(session) {
                    // 首屏保持小请求尽早呈现；连续浏览按服务器允许的百条批次提前补足，降低分页往返频率。
                    readItemsRange(session, queryKey.parentId, queryKey.query, start, target, pageSize = if (append) 100 else 40)
                } }
                if (restoreSnapshot) {
                    val cached = storeIo { browserSnapshotStore.load(session, checkNotNull(pageKey)) }
                    if (!currentLoad()) return@launchMediaRead
                    cached?.takeIf { pageRevision == browserPageRevision }?.let { stored ->
                        val display = changes.reconcile(stored)
                        val typesChanged = state.value.browsers[key]?.displayedQuery?.itemTypes != queryKey.query.itemTypes
                        updateBrowser(key) { it.withContent(queryKey.query, ContentState(items = display.items,
                            total = display.totalCount, nextIndex = display.items.size, loading = true)) }
                        if (typesChanged) loadFilters(key)
                        trace.point("content_disk_ready", display.items.size.toLong())
                    }
                    updateBrowser(key) { it.copy(restoringCache = false) }
                }
                val (received, nextIndex) = network.await().getOrThrow()
                if (!currentLoad()) return@launchMediaRead
                val previous = if (append) state.value.browsers[key]?.content?.items.orEmpty() else emptyList()
                val merged = withContext(Dispatchers.Default) { (previous + received.items).distinctBy { it.id } }
                if (!currentLoad()) return@launchMediaRead
                // 读取期间成功写回的 UserData 覆盖旧回包，不丢弃无关查询的整个请求。
                val page = changes.reconcile(received.copy(items = merged))
                loadedBrowsers[key] = queryKey
                if (!append) dirtyBrowsers.remove(key)
                if (!append && pageKey != null && pageRevision == browserPageRevision)
                    browserPages.put(pageKey, changes.reconcile(received.copy(items = received.items.take(40))))
                val typesChanged = state.value.browsers[key]?.displayedQuery?.itemTypes != queryKey.query.itemTypes
                updateBrowser(key) { it.withContent(queryKey.query, ContentState(items = page.items, total = page.totalCount, nextIndex = nextIndex)) }
                if (typesChanged) loadFilters(key)
                trace.point("content_network_ready", page.items.size.toLong())
                // 网络读取已经结束；后面的快照写盘不能把用户下一次刷新当作重复在途请求。
                if (browserLoads[key] === load) {
                    browserLoads.remove(key)
                    browserJobs.remove(key)
                }
                if (!append && pageKey != null && pageRevision == browserPageRevision)
                    storeIo { browserSnapshotStore.save(session, pageKey, page.copy(items = page.items.take(40))) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch && browserLoads[key] === load && !handleAuthFailure(e)) updateBrowser(key) {
                    it.copy(content = it.content.copy(loading = false, loadingMore = false, error = message(e)))
                }
            } finally {
                if (browserLoads[key] === load) {
                    browserLoads.remove(key)
                    updateBrowser(key) { it.copy(restoringCache = false) }
                }
            }
        }
    }

    private fun loadFilters(key: String, force: Boolean = false) {
        if (isLibrarySearch(key)) return
        val session = state.value.session ?: return
        val browser = state.value.browsers[key] ?: return
        val epoch = generation
        val queryKey = FilterQueryKey(browserParents[key], browser.displayedQuery.itemTypes)
        val jobKey = "filters/$key"
        if (extraJobs[jobKey]?.isActive == true && filterLoads[key] == queryKey) return
        if (!force && loadedFilters[key] == queryKey && browser.filterOptions != null && browser.filtersError == null) return
        extraJobs[jobKey]?.cancel()
        filterLoads[key] = queryKey
        // 改查询类型/父库时调用方会清空选项；结构变更只失效已加载标记，保留同页旧选项。
        val sameContext = loadedFilters[key] == queryKey || browser.filterOptions != null
        updateBrowser(key) { it.copy(filtersLoading = true,
            filterOptions = if (sameContext) it.filterOptions else null) }
        extraJobs[jobKey] = viewModelScope.launch {
            try {
                val result = serverSafely(session) { api.filterOptions(session, queryKey.parentId, queryKey.itemTypes) }
                if (generation != epoch || filterLoads[key] !== queryKey || browserParents[key] != queryKey.parentId ||
                    state.value.browsers[key]?.displayedQuery?.itemTypes != queryKey.itemTypes) return@launch
                val error = result.exceptionOrNull()
                if (error != null && handleAuthFailure(error)) return@launch
                if (result.isSuccess) loadedFilters[key] = queryKey
                updateBrowser(key) { it.copy(filtersLoading = false,
                    filterOptions = result.getOrNull() ?: it.filterOptions,
                    filtersError = error?.let(::message)) }
            } finally {
                if (filterLoads[key] === queryKey) filterLoads.remove(key)
            }
        }
    }

    private fun updateBrowser(key: String, transform: (BrowserUiState) -> BrowserUiState) {
        mutableState.update { current -> current.copy(browsers = current.browsers + (key to transform(current.browsers[key] ?: BrowserUiState()))) }
    }

    private fun isLibrarySearch(key: String): Boolean = key.startsWith("library-search/")

    private fun invalidateBrowserSnapshots() {
        invalidateBrowserPages()
        state.value.currentProfileId?.let { profileId ->
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { storeIo { browserSnapshotStore.removeProfile(profileId) } }
        }
    }

    private fun markBrowsersDirty(favoriteCategory: DiscoveryCategory? = null) {
        invalidateBrowserSnapshots()
        browserMutationRevision++
        dirtyBrowsers += state.value.browsers.keys
        browserJobs.values.forEach { it.cancel() }; browserJobs.clear(); browserLoads.clear()
        state.value.browsers.keys.forEach { key -> extraJobs.remove("filters/$key")?.cancel() }
        filterLoads.clear(); loadedFilters.clear()
        mutableState.update { current -> current.copy(browsers = current.browsers.mapValues { (_, browser) ->
            browser.copy(restoringCache = false, filtersLoading = false, filtersError = null,
                content = browser.content.copy(loading = false, loadingMore = false))
        }) }
        invalidateDiscovery(favoriteCategory)
        refreshVisibleBrowserIfDirty()
    }

    private fun applyUserDataChange(change: BrowserUserDataChange, favoriteCategory: DiscoveryCategory? = null) {
        val session = state.value.session ?: return
        val profileId = state.value.currentProfileId ?: return
        // 单项与批量显式写入共用：迟到的播放父项读取不能覆盖用户刚提交的状态。
        if (change.kind != BrowserUserDataChange.Kind.PLAYBACK) extraJobs.remove("playback-parent/${change.item.id}")?.cancel()
        // 在途请求可能已经拍下服务器旧值。只合并写成功的字段，保留读取到的其他信息。
        activeMediaReads.forEach { it.record(change) }
        updateEverywhere(change::apply)
        browserPages.update(ProfileHomeKey.of(profileId, session), change)
        val affected = state.value.browsers.filterValues { change.invalidates(it.query) }.keys
        affected.forEach { key -> browserJobs.remove(key)?.cancel(); browserLoads.remove(key) }
        dirtyBrowsers += affected
        if (affected.isNotEmpty()) mutableState.update { current -> current.copy(browsers = current.browsers.mapValues { (key, browser) ->
            if (key in affected) browser.copy(restoringCache = false, content = browser.content.copy(loading = false, loadingMore = false)) else browser
        }) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { storeIo {
            browserSnapshotStore.update(session, profileId, change::apply, change::invalidates)
        } }
        invalidateDiscovery(favoriteCategory)
        refreshVisibleBrowserIfDirty()
    }

    private fun refreshVisibleBrowserIfDirty() {
        if (state.value.route == Route.Favorites) { ensureFavorites(); return }
        if (state.value.route == Route.Search) { ensureSearchSections(); return }
        (state.value.route as? Route.Discovery)?.let { loadDiscoveryPage(it.favorite, it.category); return }
        val key = when (val route = state.value.route) {
            is Route.Folder -> route.item.id
            is Route.Browse -> route.id
            is Route.LibrarySearch -> route.key
            else -> return
        }
        if (key in dirtyBrowsers) {
            loadBrowser(key)
            loadFilters(key)
        }
    }

    private fun openBrowse(id: String, title: String, query: LibraryQuery, parentId: String? = null) {
        cancelServerSwitch()
        invalidatePlaybackPrefetch()
        mutableState.update { it.copy(routes = it.routes + Route.Browse(id, title)) }
        configureBrowser(id, parentId, query)
    }
    fun openPerson(person: MediaPerson) = open(MediaItem(person.id, person.name, "Person", imageTag = person.imageTag))

    /** 人物详情里“电影/剧集”分区的完整列表，沿用媒体库浏览页的筛选与分页。 */
    fun openPersonCredits(person: MediaItem, type: String) = openBrowse("person/${person.id}/$type",
        "${person.name} · ${if (type == "Movie") tr("电影") else tr("剧集")}",
        LibraryQuery(personId = person.id, itemTypes = setOf(type), sort = LibrarySort.PREMIERE_DATE, descending = true))

    private fun loadDetailExtras(item: MediaItem) {
        val session = state.value.session ?: return
        val epoch = generation
        cancelDetailExtras(item.id)
        mutableState.update { it.copy(detailExtras = it.detailExtras + (item.id to (it.detailExtras[item.id] ?: DetailExtras()).copy(loading = true, error = null))) }
        // 先登记再启动；每次发布与收尾只允许操作自己仍持有的这轮详情扩展。
        val job = launchMediaRead(start = CoroutineStart.LAZY) { changes ->
            val owner = currentCoroutineContext().job
            fun isCurrent() = epoch == generation && session.hasSameAuthentication(state.value.session) && extraJobs[item.id] === owner
            suspend fun receive(request: suspend () -> MediaPage, after: Job? = null, change: DetailExtras.(MediaPage) -> DetailExtras) {
                val result = serverSafely(session, request)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return
                val error = result.exceptionOrNull()
                if (error != null && handleAuthFailure(error)) return
                after?.join()
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return
                mutableState.update { current ->
                    val previous = current.detailExtras[item.id] ?: return@update current
                    current.copy(detailExtras = current.detailExtras + (item.id to
                        (result.getOrNull()?.let { previous.change(changes.reconcile(it)) }
                            ?: previous.copy(error = previous.error ?: error?.let(::message)))))
                }
            }
            try {
                coroutineScope {
                    if (item.type == "Person") {
                        // 与 Jellyfin 官方人物页一致：电影与剧集各按演员表查询一次，不额外展开季和单集。
                        for (type in listOf("Movie", "Series")) launch {
                            receive({ api.items(session, limit = 20, options = LibraryQuery(personId = item.id, itemTypes = setOf(type),
                                sort = LibrarySort.PREMIERE_DATE, descending = true)) }) { if (type == "Movie") copy(movies = it) else copy(series = it) }
                        }
                    } else launch { receive({ api.similar(session, item.id) }) { copy(similar = it.items) } }
                    val resume = if (item.type in setOf("Series", "Season")) launch {
                        receive({ api.items(session, parentId = item.id, resume = true, limit = 6) }) { copy(resume = it.items) }
                    } else null
                    if (item.type == "Series") launch {
                        // 请求并发；发布下一集前先确认续播，避免先到的下一集抢走播放目标。
                        receive({ api.nextUp(session, seriesId = item.id, limit = 1) }, after = resume) { copy(nextUp = it.items) }
                    }
                    item.seasonId?.takeIf { item.type == "Episode" }?.let { season -> launch {
                        receive({ api.items(session, parentId = season, limit = 40) }) { copy(siblings = it.items) }
                    } }
                }
            } finally {
                if (isCurrent()) {
                    extraJobs.remove(item.id)
                    mutableState.update { current -> current.copy(detailExtras = current.detailExtras +
                        (item.id to current.detailExtras.getValue(item.id).copy(loading = false))) }
                }
            }
        }
        extraJobs[item.id] = job
        job.start()
    }

    private fun cancelDetailExtras(itemId: String) {
        extraJobs.remove(itemId)?.cancel()
        mutableState.update { current ->
            val extras = current.detailExtras[itemId] ?: return@update current
            current.copy(detailExtras = current.detailExtras + (itemId to extras.copy(loading = false)))
        }
    }

    private suspend fun resolveSeriesEpisode(session: Session, item: MediaItem, fromStart: Boolean): MediaItem {
        val extras = state.value.detailExtras[item.id]
        var candidate = if (fromStart) null else extras?.resume?.firstOrNull() ?: extras?.nextUp?.firstOrNull()
        if (!fromStart && candidate == null && (extras == null || extras.loading || extras.error != null)) {
            candidate = api.items(session, parentId = item.id, resume = true, limit = 1).items.firstOrNull()
            if (candidate == null && item.type == "Series") candidate = api.nextUp(session, seriesId = item.id, limit = 1).items.firstOrNull()
        }
        return candidate ?: api.items(session, parentId = item.id, limit = 1,
            options = LibraryQuery(itemTypes = setOf("Episode"), episodeOrder = true, descending = false)).items.firstOrNull()
            ?: throw IllegalArgumentException(tr("没有可播放的剧集"))
    }

    fun playSeries(item: MediaItem, fromStart: Boolean = false) {
        beginPlaybackPreparation(item, fromStart)
    }

    fun toggleFavorite(item: MediaItem) = updateUserData(item, "favorite") { session -> api.setFavorite(session, item.id, !item.isFavorite) }
    fun togglePlayed(item: MediaItem) = updateUserData(item, "played") { session -> api.setPlayed(session, item.id, !item.played) }
    private fun updateUserData(item: MediaItem, operation: String, action: suspend (Session) -> MediaUserData) {
        val session = state.value.session ?: return
        val key = "$operation/${item.id}"
        if (key in state.value.actionBusy) return
        val epoch = generation
        mutableState.update { it.copy(actionBusy = it.actionBusy + key) }
        viewModelScope.launch {
            try {
                ensureLocalNetworkAccess(session.server.baseUrl)
                val data = action(session)
                if (generation == epoch) {
                    applyUserDataChange(BrowserUserDataChange(item, data,
                        if (operation == "favorite") BrowserUserDataChange.Kind.FAVORITE else BrowserUserDataChange.Kind.PLAYED),
                        if (operation == "favorite") DiscoveryCategory.entries.firstOrNull { it.itemType == item.type } else null)
                    // 收藏写回后立即更新已显示的分组，返回时再重取受影响的列表。
                    if (operation == "favorite" && item.isFavorite) mutableState.update { current ->
                        current.copy(favorites = current.favorites.copy(sections = current.favorites.sections.mapValues { (_, section) ->
                            val removed = section.items.count { it.id == item.id }
                            section.copy(items = section.items.filterNot { it.id == item.id },
                                total = (section.total - removed).coerceAtLeast(0),
                                nextIndex = (section.nextIndex - removed).coerceAtLeast(0))
                        }))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch && !handleAuthFailure(e)) mutableState.update { it.copy(notice = message(e)) }
            } finally {
                if (generation == epoch) mutableState.update { it.copy(actionBusy = it.actionBusy - key) }
            }
        }
    }

    private fun updateEverywhere(change: (MediaItem) -> MediaItem) {
        mutableState.update { s -> s.copy(
            details = s.details.mapValues { change(it.value) },
            folders = s.folders.mapValues { (_, c) -> c.copy(items = c.items.map(change)) },
            browsers = s.browsers.mapValues { (_, b) -> b.copy(content = b.content.copy(items = b.content.items.map(change))) },
            search = s.search.copy(results = s.search.results.copy(sections = s.search.results.sections.mapValues { (_, c) ->
                c.copy(items = c.items.map(change)) }), suggestions = s.search.suggestions.copy(items = s.search.suggestions.items.map(change))),
            favorites = s.favorites.copy(sections = s.favorites.sections.mapValues { (_, c) -> c.copy(items = c.items.map(change)) }),
            home = s.home.copy(recent = s.home.recent.map(change), resume = s.home.resume.map(change), nextUp = s.home.nextUp.map(change),
                libraryItems = s.home.libraryItems.mapValues { it.value.map(change) }),
            detailExtras = s.detailExtras.mapValues { (_, e) -> e.copy(resume = e.resume.map(change), nextUp = e.nextUp.map(change),
                similar = e.similar.map(change), siblings = e.siblings.map(change), movies = e.movies.copy(items = e.movies.items.map(change)),
                series = e.series.copy(items = e.series.items.map(change))) },
            playbackNeighbors = s.playbackNeighbors.map(change),
            playbackEpisodes = s.playbackEpisodes.copy(items = s.playbackEpisodes.items.map(change)),
        ) }
        captureHomeSnapshot()
    }

    fun setPreferences(value: AppPreferences) {
        val revision = ++preferenceRevision
        if (state.value.preferences.originalPathEnabled != value.originalPathEnabled) invalidatePlaybackPrefetch()
        // 连续改不同设置时，下一次交互立即读到上一修改；实际持久化仍串行放在 IO。
        mutableState.update { it.copy(preferences = value) }
        viewModelScope.launch {
            storageReady.await()
            try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    val warning = persistPreferences(value)
                    mutableState.update { it.copy(preferences = if (revision == preferenceRevision) value else it.preferences,
                        notice = warning ?: it.notice) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == preferenceRevision && state.value.preferences.originalPathEnabled != lastCommittedPreferences.originalPathEnabled)
                    invalidatePlaybackPrefetch()
                mutableState.update { it.copy(preferences = if (revision == preferenceRevision) lastCommittedPreferences else it.preferences,
                    notice = tr("设置保存失败，请重试")) }
            }
        }
    }

    suspend fun importSettings(preferences: AppPreferences, theme: ThemePreference): Boolean {
        val revision = ++preferenceRevision
        val importedThemeRevision = ++themeRevision
        storageReady.await()
        return try {
            // 已开始提交后让写盘与状态发布一起结束，避免取消回调留下已保存但界面未应用的状态。
            withContext(kotlinx.coroutines.NonCancellable) {
                val warning = persistPreferences(preferences, theme)
                if (revision == preferenceRevision && state.value.preferences.originalPathEnabled != preferences.originalPathEnabled)
                    invalidatePlaybackPrefetch()
                mutableState.update { it.copy(
                    preferences = if (revision == preferenceRevision) preferences else it.preferences,
                    theme = if (importedThemeRevision == themeRevision) theme else it.theme,
                    notice = warning ?: it.notice) }
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** 偏好提交只占用存储锁；下载协调始终读取最新持久规则，不依赖页面内的补偿标志。 */
    private suspend fun persistPreferences(value: AppPreferences, theme: ThemePreference? = null): String? {
        storeIo {
            if (theme == null) preferencesStore.save(value) else preferencesStore.saveWithTheme(value, theme)
            lastCommittedPreferences = value
            if (theme != null) lastCommittedTheme = theme
        }
        return try {
            MediaDownloads.recover(getApplication())
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            tr("设置已保存，下载队列更新失败，请在下载任务中重试")
        }
    }

    fun toggleHomeLibrary(id: String) {
        val session = state.value.session ?: return
        val old = state.value.hiddenLibraries
        val key = sessionKey(session)
        committedHiddenLibraries.putIfAbsent(key, old)
        val revision = (hiddenLibraryRevisions[key] ?: 0L) + 1
        hiddenLibraryRevisions[key] = revision
        val hidden = if (id in old) old - id else old + id
        val epoch = generation
        mutableState.update { it.copy(hiddenLibraries = hidden) }
        if (id !in hidden && !state.value.home.libraryItems.containsKey(id)) refreshHome()
        viewModelScope.launch {
            try {
                storeIo { preferencesStore.saveHiddenLibraries(key, hidden) }
                committedHiddenLibraries[key] = hidden
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == generation) mutableState.update { it.copy(
                    hiddenLibraries = if (hiddenLibraryRevisions[key] == revision) committedHiddenLibraries.getValue(key) else it.hiddenLibraries,
                    notice = tr("首页显示设置保存失败，请重试")) }
            }
        }
    }

    fun setTheme(mode: ThemePreference) {
        val revision = ++themeRevision
        viewModelScope.launch {
            storageReady.await()
            try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    storeIo { themeStore.save(mode); lastCommittedTheme = mode }
                    if (revision == themeRevision) mutableState.update { it.copy(theme = mode) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(theme = if (revision == themeRevision) lastCommittedTheme else it.theme, notice = tr("主题保存失败，请重试")) } }
        }
    }

    private fun updateFolder(id: String, transform: (ContentState) -> ContentState) {
        mutableState.update { it.copy(folders = it.folders + (id to transform(it.folders[id] ?: ContentState()))) }
    }

    private suspend fun ensureLocalNetworkAccess(url: String, force: Boolean = false) {
        val context = getApplication<Application>()
        if (LocalNetworkPermission.isGranted(context) || (!force && !LocalNetworkPermission.isRequired(url))) return
        val request = pendingLocalNetworkAccess ?: PendingLocalNetworkAccess(++localNetworkRequestSequence).also {
            pendingLocalNetworkAccess = it
            mutableState.update { state -> state.copy(localNetworkPermissionRequest = it.id, localNetworkPermissionDenied = false) }
        }
        request.users++
        try {
            val granted = request.result.await()
            if (!granted || !LocalNetworkPermission.isGranted(context)) throw LocalNetworkPermissionDenied()
        } finally {
            request.users--
            if (request.users == 0 && pendingLocalNetworkAccess === request) {
                pendingLocalNetworkAccess = null
                if (!request.result.isCompleted) request.result.cancel()
                mutableState.update { if (it.localNetworkPermissionRequest == request.id) it.copy(localNetworkPermissionRequest = null) else it }
            }
        }
    }

    fun requestLocalNetworkAccess() {
        viewModelScope.launch {
            try { ensureLocalNetworkAccess("", force = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LocalNetworkPermissionDenied) { /* MainActivity 提供系统设置入口。 */ }
        }
    }

    fun onLocalNetworkPermissionResult(granted: Boolean, requestId: Long? = state.value.localNetworkPermissionRequest) {
        val request = pendingLocalNetworkAccess ?: return
        if (requestId != request.id) return
        mutableState.update { it.copy(localNetworkPermissionRequest = null, localNetworkPermissionDenied = !granted) }
        request.result.complete(granted)
    }

    fun dismissLocalNetworkPermissionExplanation() {
        mutableState.update { it.copy(localNetworkPermissionDenied = false) }
    }

    private fun handleAuthFailure(error: Throwable): Boolean {
        if (error is ApiException && error.statusCode == 401) {
            val current = state.value
            val profile = current.profiles.firstOrNull { it.id == current.currentProfileId } ?: return false
            if (current.loginBusy || current.reauthenticationProfile != null) {
                // 旧页面的迟到响应只能使其缓存失效，不能取消切服或替换正在恢复的账号。
                invalidateHomeSnapshot(profile.id)
                return true
            }
            requestReauthentication(profile)
            return true
        }
        return false
    }

    private fun requestReauthentication(profile: ServerProfile) {
        invalidateHomeSnapshot(profile.id)
        // 同一轮多个请求的 401 只能建立一次恢复目标，不能中断正在提交的新认证。
        if (state.value.reauthenticationProfile != null) return
        cancelRequests()
        mutableState.update { current -> current.copy(
            reauthenticationProfile = profile, loginBusy = false,
            loginError = tr("此账号登录已失效，请重新登录"), notice = null,
            preparingPlay = false, sourceSelection = null,
            activePlayback = null, pendingCast = null, pendingDownload = null,
            detailLoading = false,
            home = current.home.copy(loading = false),
            search = current.search.copy(results = current.search.results.copy(sections = current.search.results.sections.mapValues { (_, c) ->
                c.copy(loading = false, loadingMore = false) }), suggestions = current.search.suggestions.copy(loading = false)),
            favorites = current.favorites.copy(sections = current.favorites.sections.mapValues { (_, c) -> c.copy(loading = false, loadingMore = false) }),
            playbackEpisodes = current.playbackEpisodes.copy(loading = false, loadingMore = false),
            folders = current.folders.mapValues { (_, value) -> value.copy(loading = false, loadingMore = false) },
            browsers = current.browsers.mapValues { (_, value) -> value.copy(filtersLoading = false,
                content = value.content.copy(loading = false, loadingMore = false)) },
            detailExtras = current.detailExtras.mapValues { (_, value) -> value.copy(loading = false) },
        ) }
    }

    private suspend fun <T> serverSafely(session: Session, action: suspend () -> T): Result<T> = safely {
        ensureLocalNetworkAccess(session.server.baseUrl)
        action()
    }

    private suspend fun <T> safely(action: suspend () -> T): Result<T> = try {
        Result.success(action())
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    private fun message(error: Throwable): String = when (error) {
        is LocalNetworkPermissionDenied -> LocalNetworkPermission.DENIED_MESSAGE
        is ApiException -> error.message ?: tr("服务器请求失败，请重试")
        is IllegalArgumentException -> error.message ?: tr("请检查输入或媒体源")
        else -> tr("连接或读取失败，请检查网络与服务器后重试")
    }
}

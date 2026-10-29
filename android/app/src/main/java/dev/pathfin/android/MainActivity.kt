package dev.pathfin.android

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Build
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.LocaleList
import android.util.Rational
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import dev.pathfin.android.domain.PlayerOrientation
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.i18n.onLocaleChanged
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.PlaybackChaptersSheet
import dev.pathfin.android.ui.PosterRequests
import dev.pathfin.android.ui.PlaybackEpisodesSheet
import dev.pathfin.android.ui.PlaybackSourceDialog
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import dev.pathfin.android.ui.AppAlertDialog
import dev.pathfin.android.ui.DialogAction
import dev.pathfin.android.ui.DialogActionRole
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.PathfinAppearance
import dev.pathfin.android.ui.PlayerScreen
import dev.pathfin.android.ui.AppBackHandler
import dev.pathfin.android.ui.AppSystemBars
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.LocalPlaybackFullscreen
import androidx.compose.runtime.CompositionLocalProvider
import dev.pathfin.android.ui.rememberPosterLoader
import dev.pathfin.android.ui.hasThumbnail
import dev.pathfin.android.ui.player.PlayerDisplayPreferencesEffect
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.theme.PathfinTheme
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val playbackViewModel: PlaybackViewModel by viewModels()
    private val updateViewModel: dev.pathfin.android.update.AppUpdateViewModel by viewModels()
    private var playbackBackKeyDown = false
    private var pictureInPicture by mutableStateOf(false)
    private var pipPlaybackWasVisible = false
    private var closedPipPendingFinish = false
    private val pipLifecycleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPipCloseCheck: Runnable? = null
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "$packageName.PIP_TOGGLE" -> playbackViewModel.togglePlayPause()
                "$packageName.PIP_CLOSE" -> if (isInPictureInPictureMode || pictureInPicture || pipPlaybackWasVisible || closedPipPendingFinish) {
                    releaseClosedPipPlayback()
                    finishAndRemoveTask()
                }
            }
        }
    }
    private val pipAvailable get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 语言由 configChanges 原地处理，不重建 Activity；只通知 tr() 的读取方重组。
        if (newConfig.locales != shownLocales) {
            shownLocales = newConfig.locales
            onLocaleChanged()
        }
    }
    private var shownLocales: LocaleList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownLocales = resources.configuration.locales
        pictureInPicture = isInPictureInPictureMode
        pipPlaybackWasVisible = pictureInPicture
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter("$packageName.PIP_TOGGLE").apply {
            addAction("$packageName.PIP_CLOSE")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge()
        // 第一次绘制等待后台读取本机偏好/会话，既不主线程解密，也不闪一次默认主题。
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (appViewModel.state.value.restoringSession) return false
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            val state by appViewModel.state.collectAsStateWithLifecycle()
            // 准备播放时留在原页面、由播放按钮转圈；返回键仍先取消准备。
            val showingPlayback = state.activePlayback != null || state.pendingPlayback != null
            val playerVisible = state.activePlayback != null
            val playback by playbackViewModel.state.collectAsStateWithLifecycle()
            var showEpisodes by rememberSaveable { mutableStateOf(false) }
            // 选集面板的数字 / 列表形态在本次使用期间保留，重新打开面板不回到默认。
            var episodeGrid by rememberSaveable { mutableStateOf(true) }
            var showChapters by rememberSaveable { mutableStateOf(false) }
            var autoAdvancedGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
            var orientationBeforePlayback by rememberSaveable { mutableStateOf<Int?>(null) }
            DisposableEffect(state.activePlayback != null) {
                if (state.activePlayback != null && orientationBeforePlayback == null) {
                    orientationBeforePlayback = requestedOrientation
                    requestedOrientation = when (state.preferences.playerOrientation) {
                        PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        PlayerOrientation.LANDSCAPE_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        PlayerOrientation.PORTRAIT_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        PlayerOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    }
                }
                onDispose {
                    // 旋转销毁旧Composition时不能反向请求旧方向，否则新旧Activity会不断互相旋转。
                    if (!isChangingConfigurations) orientationBeforePlayback?.let { previous ->
                        requestedOrientation = previous
                        orientationBeforePlayback = null
                    }
                }
            }
            // 播完后续播下一集；没有下一集就退出播放器，画中画里保留到回到全屏再退出。
            LaunchedEffect(playback.ended, playback.playerGeneration, state.activePlayback, state.pendingPlayback?.id,
                pictureInPicture, state.preferences.autoPlayNextEpisode) {
                val current = state.activePlayback ?: return@LaunchedEffect
                val endedGeneration = playback.playerGeneration
                if (!playback.ended || state.pendingPlayback != null || autoAdvancedGeneration == endedGeneration) return@LaunchedEffect
                val next = if (state.preferences.autoPlayNextEpisode) appViewModel.nextPlaybackEpisode() else null
                // 等相邻集时可能已经返回或手动换片，旧播放结束不能关闭或替换新请求。
                val latest = appViewModel.state.value
                val latestPlayback = playbackViewModel.state.value
                if (latest.activePlayback !== current || latest.pendingPlayback != null || !latestPlayback.ended ||
                    latestPlayback.playerGeneration != endedGeneration) return@LaunchedEffect
                when {
                    next != null -> {
                        autoAdvancedGeneration = endedGeneration
                        appViewModel.prepareEpisodePlayback(next)
                    }
                    !pictureInPicture -> finishPlayback()
                }
            }
            LaunchedEffect(playback.isPlaying, pictureInPicture, playback.hasRenderedFrame, state.activePlayback?.item?.id,
                state.preferences.backgroundPlaybackBehavior) {
                if (pipAvailable) setPictureInPictureParams(pipParameters())
            }
            val browsingState = rememberSaveableStateHolder()
            // 图片缓存属于当前会话，进入播放器时仍保留，返回后直接复用封面。
            val posterLoader = rememberPosterLoader(state.session, state.preferences.imageCacheSizeMb)
            var permissionInFlight by rememberSaveable { mutableStateOf<Long?>(null) }
            val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val id = permissionInFlight
                permissionInFlight = null
                appViewModel.onLocalNetworkPermissionResult(granted, id)
            }
            LaunchedEffect(state.localNetworkPermissionRequest, permissionInFlight) {
                val id = state.localNetworkPermissionRequest ?: return@LaunchedEffect
                if (permissionInFlight != null) return@LaunchedEffect
                if (LocalNetworkPermission.isGranted(this@MainActivity)) appViewModel.onLocalNetworkPermissionResult(true, id)
                else {
                    permissionInFlight = id
                    requestLocalNetwork.launch(LocalNetworkPermission.PERMISSION)
                }
            }
            PathfinTheme(state.theme, state.preferences.accentColor) {
            CompositionLocalProvider(LocalAppPreferences provides state.preferences, LocalPlaybackFullscreen provides playerVisible) {
                AppSystemBars()
                PlayerDisplayPreferencesEffect(playbackViewModel.player, state.activePlayback != null, state.preferences.lockHighRefreshRate)
                // 始终注册，播放器 AndroidView 获取焦点或页面切换时也没有返回回调的空档。
                AppBackHandler(
                    enabled = showingPlayback,
                    contentKey = state.activePlayback?.let { Triple(it.session.userId, it.item.id, it.info.playSessionId) to playback.playerGeneration }
                        ?: state.pendingPlayback?.let { state.currentProfileId to it.id },
                    onBack = ::closePlayback,
                    modifier = Modifier.fillMaxSize().background(if (playerVisible) Color.Black else MaterialTheme.colorScheme.surface),
                    testTag = "playback-back-motion",
                ) {
                val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        // 浏览页按真实头图/滚动状态设置状态栏；播放状态更新不覆盖它。
                        if (playerVisible) isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = lightSurface && !playerVisible
                    }
                }
                LaunchedEffect(state.restoringSession, state.preferences.autoCheckUpdates) {
                    if (!state.restoringSession && state.preferences.autoCheckUpdates) updateViewModel.checkAutomatically()
                }
                LaunchedEffect(state.activePlayback) {
                    state.activePlayback?.let { playbackViewModel.play(it, state.preferences) } ?: playbackViewModel.stop()
                }
                if (state.activePlayback != null) {
                    val playbackItem = state.activePlayback?.item
                    val titleLogo = remember(state.session, playbackItem?.id, playbackItem?.logoTag, playbackItem?.logoItemId) {
                        val session = state.session
                        if (session != null && playbackItem != null) PosterRequests.logo(this@MainActivity, session, appViewModel.api, playbackItem) else null
                    }
                    androidx.compose.runtime.CompositionLocalProvider(dev.pathfin.android.ui.LocalPosterLoader provides posterLoader) {
                    PlayerScreen(
                        playbackViewModel,
                        preferences = state.preferences,
                        onBack = ::closePlayback,
                        handleSystemBack = false,
                        onPreferencesChange = appViewModel::setPreferences,
                        onAuthenticationExpired = appViewModel::authenticationExpired,
                        onEnterPictureInPicture = if (pipAvailable) ({ enterPlaybackPictureInPicture() }) else null,
                        onToggleOrientation = {
                            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        },
                        onPrevious = appViewModel.playbackNeighbor(-1)?.takeUnless { state.preparingPlay }?.let { previous ->
                            { appViewModel.prepareEpisodePlayback(previous) }
                        },
                        onNext = appViewModel.playbackNeighbor(1)?.takeUnless { state.preparingPlay }?.let { next ->
                            { appViewModel.prepareEpisodePlayback(next) }
                        },
                        onShowEpisodes = state.activePlayback?.item?.seriesId?.let { { showEpisodes = true; appViewModel.loadPlaybackEpisodes() } },
                        onShowChapters = if (state.preferences.showPlayerChapters && state.activePlayback?.item?.chapters?.any {
                            !state.preferences.hideChaptersWithoutImage || it.hasThumbnail()
                        } == true) ({ showChapters = true }) else null,
                        isInPictureInPicture = pictureInPicture,
                        navigationPanelVisible = showEpisodes || showChapters,
                        titleLogo = titleLogo,
                    )
                    }
                    if (!pictureInPicture) {
                        if (showEpisodes) androidx.compose.runtime.CompositionLocalProvider(dev.pathfin.android.ui.LocalPosterLoader provides posterLoader) {
                            PlaybackEpisodesSheet(state.playbackEpisodes, state.activePlayback!!.item.id, state.preparingPlay,
                            onSelect = { showEpisodes = false; appViewModel.prepareEpisodePlayback(it) },
                            onRefresh = { appViewModel.loadPlaybackEpisodes(refresh = true) },
                            onMore = { appViewModel.loadPlaybackEpisodes(append = true) }, onDismiss = { showEpisodes = false },
                            session = state.activePlayback!!.session, api = appViewModel.api,
                            grid = episodeGrid, onGridChange = { episodeGrid = it })
                        }
                        if (showChapters) androidx.compose.runtime.CompositionLocalProvider(dev.pathfin.android.ui.LocalPosterLoader provides posterLoader) {
                            PlaybackChaptersSheet(state.activePlayback!!.item.chapters,
                                onSelect = { chapter -> playbackViewModel.player?.seekTo(TimeUnits.ticksToMs(chapter.startPositionTicks)); showChapters = false },
                                onDismiss = { showChapters = false }, session = state.activePlayback!!.session,
                                item = state.activePlayback!!.item, api = appViewModel.api,
                                hideWithoutImage = state.preferences.hideChaptersWithoutImage)
                        }
                        state.playbackError?.let { error -> AppAlertDialog(onDismissRequest = appViewModel::dismissPlaybackError,
                            text = { Text(error) }, confirmButton = { DialogAction(tr("确定"), appViewModel::dismissPlaybackError, role = DialogActionRole.Primary) }) }
                    }
                } else {
                    PathfinApp(appViewModel, state, browsingState, posterLoader = posterLoader)
                    // 播放中不打扰；退出播放器后仍会显示待处理的更新提示。
                    dev.pathfin.android.ui.AppUpdatePrompt(updateViewModel)
                    // 准备失败只给重试或放弃，不留下看不见的待播目标。
                    if (state.pendingPlayback != null && !state.preparingPlay) state.playbackError?.let { error ->
                        AppAlertDialog(onDismissRequest = ::closePlayback, text = { Text(error) },
                            dismissButton = { DialogAction(tr("取消"), ::closePlayback) },
                            confirmButton = { DialogAction(tr("重试"), appViewModel::retryPendingPlayback, Modifier.testTag("playback-preparation-retry"), role = DialogActionRole.Primary) })
                    }
                }
                if (showingPlayback && !pictureInPicture) state.sourceSelection?.takeIf { it.info.sources.size > 1 }?.let {
                    PlaybackSourceDialog(it, appViewModel::chooseSource, appViewModel::dismissSourceSelection)
                }
                if (state.localNetworkPermissionDenied) AppAlertDialog(
                    onDismissRequest = appViewModel::dismissLocalNetworkPermissionExplanation,
                    title = { Text(tr("需要局域网访问权限")) },
                    text = { Text(tr("请在系统的 Pathfin 应用权限中允许访问附近设备/局域网。允许后返回，再次点击连接；填写的地址和账号会保留。")) },
                    confirmButton = {
                        DialogAction(tr("打开应用设置"), {
                            appViewModel.dismissLocalNetworkPermissionExplanation()
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                        }, role = DialogActionRole.Primary)
                    },
                    dismissButton = { DialogAction(tr("稍后"), appViewModel::dismissLocalNetworkPermissionExplanation) },
                )
                }
            }
            }
        }
    }

    // Activity.dispatchKeyEvent 是 Android SDK 的公开回调。core 1.19.1 将兼容父类整体标为
    // LIBRARY_GROUP_PREFIX，Lint 因继承声明而误报此重写及 super 调用；只在此处抑制。
    // 必须在 View 分发前消费完整 BACK，其他按键仍保留 AndroidX 的默认分发。
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (playbackBackKeyDown || appViewModel.state.value.activePlayback != null || appViewModel.state.value.pendingPlayback != null)
        ) {
            // 物理/三键返回完整消费 DOWN/UP，防止原生 PlayerView 的焦点路径把 UP 继续交给 Activity。
            when (event.action) {
                KeyEvent.ACTION_DOWN -> playbackBackKeyDown = true
                KeyEvent.ACTION_UP -> {
                    val complete = playbackBackKeyDown && !event.isCanceled
                    playbackBackKeyDown = false
                    if (complete) closePlayback()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            pipPlaybackWasVisible = true
            closedPipPendingFinish = false
        }
        else if (pipPlaybackWasVisible && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            scheduleClosedPipCheck()
        }
    }

    override fun onStart() {
        super.onStart()
        cancelClosedPipCheck()
        closedPipPendingFinish = false
    }

    override fun onResume() {
        super.onResume()
        cancelClosedPipCheck()
        // 从 PiP 恢复为前台全屏后，后续普通回桌面仍遵循暂停偏好。
        if (!isInPictureInPictureMode) pipPlaybackWasVisible = false
    }

    override fun onStop() {
        super.onStop()
        // 系统可在关闭 PiP 后缓存 Activity；不能等 onDestroy 才释放解码器/网络。
        if (pipPlaybackWasVisible && !isChangingConfigurations && !isInPictureInPictureMode) scheduleClosedPipCheck()
    }

    private fun scheduleClosedPipCheck() {
        cancelClosedPipCheck()
        val entry = appViewModel.state.value.activePlayback ?: return
        val check = Runnable {
            pendingPipCloseCheck = null
            // 等本次配置/生命周期事务提交；恢复全屏的 onStart/onResume 会取消检查。
            if (pipPlaybackWasVisible && !isChangingConfigurations && !isInPictureInPictureMode &&
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
                appViewModel.state.value.activePlayback === entry) releaseClosedPipPlayback()
        }
        pendingPipCloseCheck = check
        pipLifecycleHandler.post(check)
    }

    private fun cancelClosedPipCheck() {
        pendingPipCloseCheck?.let(pipLifecycleHandler::removeCallbacks)
        pendingPipCloseCheck = null
    }

    private fun releaseClosedPipPlayback() {
        cancelClosedPipCheck()
        pipPlaybackWasVisible = false
        closedPipPendingFinish = true
        playbackViewModel.stop()
        if (appViewModel.state.value.activePlayback != null) appViewModel.playerClosed(playbackViewModel.lastPositionMs)
    }

    override fun onDestroy() {
        cancelClosedPipCheck()
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android12+由autoEnter处理系统回桌面手势；旧系统在ON_STOP暂停前主动进入。
        if (Build.VERSION.SDK_INT < 31 && pipAvailable && !isInPictureInPictureMode &&
            appViewModel.state.value.preferences.backgroundPlaybackBehavior == "PIP" &&
            appViewModel.state.value.activePlayback != null && playbackViewModel.player?.isPlaying == true) {
            enterPlaybackPictureInPicture()
        }
    }

    private fun pipParameters(): PictureInPictureParams {
        val player = playbackViewModel.player
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0)
            (size.width.toFloat() * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.38f) else 16f / 9f
        val playing = player?.playWhenReady == true
        val intent = PendingIntent.getBroadcast(this, 0, Intent("$packageName.PIP_TOGGLE").setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = RemoteAction(Icon.createWithResource(this, if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play),
            if (playing) tr("暂停") else tr("播放"), if (playing) tr("暂停播放") else tr("继续播放"), intent)
        val source = android.graphics.Rect()
        window.decorView.getGlobalVisibleRect(source)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).setActions(listOf(action))
            .apply {
                if (!source.isEmpty) setSourceRectHint(source)
                if (Build.VERSION.SDK_INT >= 33) {
                    val close = PendingIntent.getBroadcast(this@MainActivity, 1,
                        Intent("$packageName.PIP_CLOSE").setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    setCloseAction(RemoteAction(Icon.createWithResource(this@MainActivity, R.drawable.ic_stat_close),
                        tr("关闭"), tr("关闭画中画"), close))
                }
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(
                    appViewModel.state.value.preferences.backgroundPlaybackBehavior == "PIP" &&
                        appViewModel.state.value.activePlayback != null && playbackViewModel.state.value.hasRenderedFrame && player?.isPlaying == true)
            }.build()
    }

    private fun enterPlaybackPictureInPicture() {
        if (appViewModel.state.value.activePlayback == null) return
        if (runCatching { enterPictureInPictureMode(pipParameters()) }.getOrDefault(false).not()) {
            android.widget.Toast.makeText(this, tr("当前设备暂时无法进入画中画"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePlayback() {
        if (appViewModel.state.value.activePlayback != null && playbackViewModel.consumeBackToUnlock()) return
        finishPlayback()
    }

    private fun finishPlayback() {
        if (appViewModel.state.value.activePlayback == null) {
            if (appViewModel.state.value.pendingPlayback != null) appViewModel.playerClosed(0)
            return
        }
        // 使用最新状态做幂等保护，按钮与系统返回不能因旧 Compose 闭包连续关闭两层页面。
        val previous = appViewModel.state.value.activePlayback ?: return
        playbackViewModel.stop()
        appViewModel.playerClosed(playbackViewModel.lastPositionMs)
        lifecycleScope.launch {
            playbackViewModel.awaitPendingReports()
            appViewModel.refreshAfterPlayback(previous.session, previous.item.id)
        }
    }
}

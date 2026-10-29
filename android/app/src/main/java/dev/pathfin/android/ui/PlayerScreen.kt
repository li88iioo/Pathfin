package dev.pathfin.android.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.player.playerTime
import dev.pathfin.android.ui.player.PlayerSecondary
import dev.pathfin.android.ui.player.PlayerText
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import dev.pathfin.android.ui.player.playerAccent
import dev.pathfin.android.ui.player.PlayerSurface
import dev.pathfin.android.ui.player.AudioOnlyCover
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.view.GestureDetector
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import dev.pathfin.android.playback.PlayerFrameCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.player.PlayerQualitySheet
import dev.pathfin.android.playback.SubtitlePresentation
import dev.pathfin.android.domain.subtitlePreferenceKey
import androidx.compose.runtime.rememberUpdatedState
import dev.pathfin.android.ui.player.PlayerControlsBackdrop
import dev.pathfin.android.ui.player.PlayerControlsOverlay
import dev.pathfin.android.ui.player.PlaybackPrompts
import dev.pathfin.android.ui.player.PlayerResizeMode
import dev.pathfin.android.ui.player.PlayerRoundButton
import dev.pathfin.ass.ExoAssExtension
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay


@UnstableApi
private val SubtitleTextStyle = androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
    android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null)
@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlaybackViewModel,
    onBack: () -> Unit,
    preferences: AppPreferences = AppPreferences(),
    handleSystemBack: Boolean = true,
    onPreferencesChange: (AppPreferences) -> Unit = {},
    onEnterPictureInPicture: (() -> Unit)? = null,
    onToggleOrientation: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onShowEpisodes: (() -> Unit)? = null,
    onShowChapters: (() -> Unit)? = null,
    isInPictureInPicture: Boolean = false,
    navigationPanelVisible: Boolean = false,
    onAuthenticationExpired: (dev.pathfin.android.domain.Session) -> Unit = {},
    titleLogo: coil3.request.ImageRequest? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val subtitlePresentation by viewModel.subtitlePresentation.collectAsStateWithLifecycle()
    val player = viewModel.player
    val assExtension = viewModel.assExtensionFor(player)
    var showTools by rememberSaveable(state.playerGeneration) { mutableStateOf(false) }
    var showSubtitleManager by rememberSaveable(state.playerGeneration) { mutableStateOf(false) }
    val externalPanelVisible = navigationPanelVisible || showTools || showSubtitleManager
    var controlsVisible by rememberSaveable(state.playerGeneration) { mutableStateOf(true) }
    var interactionSequence by remember(state.playerGeneration) { mutableLongStateOf(0L) }
    var keepControlsVisible by remember(state.playerGeneration) { mutableStateOf(false) }
    var resizeMode by rememberSaveable { mutableStateOf(PlayerResizeMode.entries.firstOrNull { it.name == preferences.videoResizeMode } ?: PlayerResizeMode.FIT) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val leave = {
        if (!viewModel.consumeBackToUnlock()) {
            viewModel.stop()
            onBack()
        }
    }
    BackHandler(enabled = handleSystemBack, onBack = leave)
    var sleepDeadline by rememberSaveable { mutableLongStateOf(0L) }
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    val latestBack by rememberUpdatedState(onBack)
    LaunchedEffect(sleepDeadline) {
        if (sleepDeadline == 0L) { sleepRemaining = 0; return@LaunchedEffect }
        while (sleepDeadline > SystemClock.elapsedRealtime()) {
            sleepRemaining = sleepDeadline - SystemClock.elapsedRealtime()
            delay(minOf(1000L, sleepRemaining.coerceAtLeast(1)))
        }
        sleepDeadline = 0; sleepRemaining = 0
        viewModel.setControlsLocked(false)
        viewModel.stop()
        latestBack()
    }
    LaunchedEffect(preferences.audioGainPercent, state.playerGeneration) { viewModel.configureAudioGain(preferences.audioGainPercent) }

    DisposableEffect(lifecycleOwner, viewModel, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true && activity?.isInPictureInPictureMode != true) {
                viewModel.pauseForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.endTemporarySpeed()
        }
    }
    // 快进退/拖动进度引起的缓冲也会让 isPlaying 暂时为 false，只有真正停下才强制显示控件。
    LaunchedEffect(state.playerGeneration, state.errorMessage, state.ended, state.isPlaying, state.isBuffering, isInPictureInPicture) {
        if (isInPictureInPicture) {
            controlsVisible = false
            showTools = false
        } else if ((!state.isPlaying && !state.isBuffering) || state.errorMessage != null || state.ended) controlsVisible = true
    }
    LaunchedEffect(state.controlsLocked) {
        controlsVisible = true
        interactionSequence += 1
    }
    LaunchedEffect(controlsVisible, interactionSequence, state.controlsLocked, state.isPlaying, state.isPreparing,
        state.isBuffering, externalPanelVisible, keepControlsVisible, isInPictureInPicture) {
        if (!isInPictureInPicture && controlsVisible && !externalPanelVisible && !keepControlsVisible &&
            (state.isPlaying || state.controlsLocked) && !state.isPreparing && !state.isBuffering
        ) {
            delay(if (state.controlsLocked) 2_500 else 4_000)
            controlsVisible = false
        }
    }
    val interact = {
        controlsVisible = true
        interactionSequence += 1
    }

    val loading = (state.isPreparing || state.isBuffering) && state.errorMessage == null && !state.ended
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(300)
            showLoading = true
        } else showLoading = false
    }

    var gestureMessage by remember { mutableStateOf<PlayerFeedback?>(null) }
    // 横向拖进度：起点、时长与目标；目标非空时显示预览，松手才真正跳转。
    var seekOrigin by remember { mutableLongStateOf(0L) }
    var seekDuration by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var gestureSequence by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.qualityError) {
        state.qualityError?.let { gestureMessage = PlayerFeedback(it); gestureSequence++ }
    }
    var renderedView by remember { mutableStateOf<PlayerView?>(null) }
    var screenshotBusy by remember { mutableStateOf(false) }
    var pendingScreenshot by remember { mutableStateOf<Bitmap?>(null) }
    val screenshotScope = rememberCoroutineScope()
    val subtitleVerificationScope = rememberCoroutineScope()
    var subtitleBusy by remember { mutableStateOf(false) }
    var subtitlePickerGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    val addSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestedGeneration = subtitlePickerGeneration
        subtitlePickerGeneration = null
        if (uri != null && requestedGeneration == viewModel.state.value.playerGeneration) screenshotScope.launch {
            subtitleBusy = true
            try { gestureMessage = PlayerFeedback(viewModel.addLocalSubtitle(uri)); gestureSequence++ }
            finally { subtitleBusy = false }
        }
    }
    val saveScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = pendingScreenshot
        pendingScreenshot = null
        if (bitmap != null) {
            if (uri == null) bitmap.recycle()
            else screenshotScope.launch {
                screenshotBusy = true
                try { PlayerFrameCapture.save(context, bitmap, uri); gestureMessage = PlayerFeedback(tr("截图已保存")); gestureSequence++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { gestureMessage = PlayerFeedback(tr("截图保存失败，请重试")); gestureSequence++ }
                finally { bitmap.recycle(); screenshotBusy = false }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { pendingScreenshot?.recycle(); pendingScreenshot = null } }
    val captureScreenshot: () -> Unit = {
        if (!screenshotBusy && state.hasRenderedFrame && renderedView != null) screenshotScope.launch {
            screenshotBusy = true
            var bitmap: Bitmap? = null
            try {
                bitmap = PlayerFrameCapture.capture(requireNotNull(renderedView))
                if (Build.VERSION.SDK_INT >= 29) {
                    PlayerFrameCapture.save(context, bitmap)
                    gestureMessage = PlayerFeedback(tr("截图已保存到 Pictures/Pathfin")); gestureSequence++
                } else {
                    pendingScreenshot = bitmap; bitmap = null
                    saveScreenshot.launch(PlayerFrameCapture.fileName())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { gestureMessage = PlayerFeedback(tr("无法保存视频截图，请稍后重试")); gestureSequence++ }
            finally { bitmap?.recycle(); screenshotBusy = false }
        }
    }
    LaunchedEffect(gestureSequence) {
        if (gestureMessage != null) {
            delay(900)
            gestureMessage = null
        }
    }
    // 亮度 / 音量电平：first 为是否亮度，second 为百分比；停手 0.9 秒后自动收起。
    var level by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var levelSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(levelSequence) { if (level != null) { delay(900); level = null } }
    val latestPreferences by rememberUpdatedState(preferences)
    // 关闭“记忆亮度”时进入播放器用系统亮度，手势调过的亮度也不写回设置。
    val levels = remember(activity) { activity?.let { dev.pathfin.android.playback.PlayerLevelGestures(it,
        if (preferences.rememberBrightness) preferences.playerBrightness else -1f,
        onBrightnessCommitted = { level -> if (latestPreferences.rememberBrightness) onPreferencesChange(latestPreferences.copy(playerBrightness = level)) }) { brightness, percent ->
        level = brightness to percent; levelSequence++
    } } }
    DisposableEffect(levels) { onDispose { levels?.close() } }
    val clock = playbackClock(preferences.showClock)
    val battery = playbackBattery(preferences.showBattery)

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player-screen")) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("player-video"),
            factory = { viewContext ->
                GesturePlayerView(viewContext).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    useController = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            },
            update = { view ->
                if (renderedView !== view) renderedView = view
                if (view.player !== player) {
                    view.bindAss(null)
                    view.bindPlayer(player)
                }
                view.bindAss(assExtension, viewModel::onAssAttachmentFailure)
                view.presentationMode = resizeMode
                view.subtitlePresentation = subtitlePresentation
                view.keepScreenOn = state.isPlaying
                view.gesturesLocked = state.controlsLocked
                view.allowPlaybackGestures = state.errorMessage == null && !state.ended && !isInPictureInPicture
                view.longPressEnabled = preferences.longPressSpeedEnabled
                view.onSingleTap = {
                    if (!isInPictureInPicture && state.errorMessage == null && !state.ended) {
                        controlsVisible = !controlsVisible
                        interactionSequence += 1
                    }
                }
                view.onLongPressStart = viewModel::beginTemporarySpeed
                view.onLongPressEnd = viewModel::endTemporarySpeed
                view.onLongPressDrag = viewModel::updateTemporarySpeedBySlide
                view.onVerticalGestureStart = { brightness -> levels?.begin(brightness) }
                view.onVerticalGesture = { brightness, fraction -> levels?.update(brightness, fraction) }
                view.onVerticalGestureEnd = { cancelled -> levels?.end(cancelled) }
                view.horizontalSeekEnabled = preferences.horizontalSeekEnabled
                view.onHorizontalSeekStart = {
                    val current = viewModel.player
                    seekOrigin = current?.currentPosition ?: 0L
                    seekDuration = current?.duration?.takeIf { it > 0 && current.isCurrentMediaItemSeekable } ?: 0L
                    seekTarget = if (seekDuration > 0) seekOrigin else null
                }
                view.onHorizontalSeek = { fraction ->
                    // 滑满一屏对应 3 分钟，片长更短时对应全片。
                    if (seekDuration > 0) seekTarget = (seekOrigin + fraction * minOf(seekDuration, 180_000L)).toLong().coerceIn(0L, seekDuration)
                }
                view.onHorizontalSeekEnd = { cancelled ->
                    val target = seekTarget
                    if (!cancelled && target != null && target != seekOrigin) viewModel.seekToPosition(target)
                    seekTarget = null
                }
                view.onPinch = { zoomIn ->
                    val target = if (zoomIn) PlayerResizeMode.ZOOM else PlayerResizeMode.FIT
                    if (resizeMode != target) {
                        resizeMode = target
                        onPreferencesChange(latestPreferences.copy(videoResizeMode = target.name))
                        gestureMessage = PlayerFeedback(target.label, PathfinIcons.AspectRatio)
                        gestureSequence += 1
                    }
                }
                view.onDoubleTapPosition = { fraction ->
                    when {
                        fraction < 0.35f -> {
                            viewModel.seekByGesture(false)
                            gestureMessage = PlayerFeedback(tr("快退 %1\$s 秒", preferences.seekSeconds.coerceIn(5, 60)), PathfinIcons.SeekBack)
                        }
                        fraction > 0.65f -> {
                            viewModel.seekByGesture(true)
                            gestureMessage = PlayerFeedback(tr("快进 %1\$s 秒", preferences.seekSeconds.coerceIn(5, 60)), PathfinIcons.SeekForward)
                        }
                        else -> {
                            val wasPlaying = viewModel.player?.playWhenReady == true
                            viewModel.togglePlayPause()
                            gestureMessage = if (wasPlaying) PlayerFeedback(tr("暂停"), PathfinIcons.PauseGlyph) else PlayerFeedback(tr("继续播放"), PathfinIcons.PlayGlyph)
                        }
                    }
                    gestureSequence += 1
                }
            },
            onRelease = { view ->
                if (renderedView === view) renderedView = null
                view.cancelGesture()
                view.keepScreenOn = false
                view.bindAss(null)
                view.releasePlayerOutput()
            },
        )
        AudioOnlyCover(player)

        // 面板仅收起交互按钮，暗角保持连续，避免新面板淡入前视频突然变亮。
        AnimatedVisibility(visible = controlsVisible && !state.controlsLocked && !isInPictureInPicture,
            enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
            Box(Modifier.fillMaxSize()) {
                PlayerControlsBackdrop()
                if (!externalPanelVisible) PlayerControlsOverlay(
                    player = player, state = state, showLoading = showLoading && loading,
                    resizeMode = resizeMode, titleLogo = titleLogo,
                    seekSeconds = preferences.seekSeconds.coerceIn(5, 60),
                    clock = clock.takeIf { preferences.showClock },
                    battery = if (preferences.showBattery) battery?.let { "$it%" } ?: "—" else null,
                    onBack = leave, onInteraction = interact, onKeepVisibleChange = { keepControlsVisible = it },
                    onTogglePlay = viewModel::togglePlayPause, onSeekBack = { viewModel.seekByGesture(false) },
                    onSeekForward = { viewModel.seekByGesture(true) },
                    onSeekTo = { if (viewModel.player === player) viewModel.seekToPosition(it) },
                    onSpeedChange = {
                        if (viewModel.player === player) viewModel.setPlaybackSpeed(it)
                        onPreferencesChange(latestPreferences.copy(playbackSpeed = it.coerceIn(.25f, 4f)))
                    },
                    onResizeModeChange = {
                        resizeMode = it
                        onPreferencesChange(latestPreferences.copy(videoResizeMode = it.name))
                    }, onLock = { viewModel.setControlsLocked(true) },
                    onShowQuality = { showTools = true },
                    onEnterPictureInPicture = onEnterPictureInPicture, onToggleOrientation = onToggleOrientation,
                    onPrevious = onPrevious, onNext = onNext, onShowEpisodes = onShowEpisodes, onShowChapters = onShowChapters,
                    onScreenshot = captureScreenshot.takeIf { state.hasRenderedFrame && !screenshotBusy },
                    onManualTrackSelection = viewModel::onManualTrackSelection,
                    onSetSleepTimer = { minutes -> sleepDeadline = minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L } ?: 0L },
                    sleepTimerLabel = sleepRemaining.takeIf { it > 0 }?.let { tr("%1\$s 分钟后关闭", (it + 59_999) / 60_000) },
                    audioGainPercent = preferences.audioGainPercent,
                    onAudioGainChange = { onPreferencesChange(preferences.copy(audioGainPercent = it)) },
                    onAddSubtitle = if (subtitleBusy || player == null || !state.hasRenderedFrame) null else ({
                        subtitlePickerGeneration = state.playerGeneration
                        addSubtitle.launch(arrayOf("*/*"))
                    }),
                    onManageSubtitles = { showSubtitleManager = true },
                    subtitlePresentation = subtitlePresentation,
                    onSubtitlePresentationChange = { value ->
                        val normalized = value.normalized()
                        viewModel.setSubtitlePresentation(normalized)
                        val key = viewModel.currentRequest?.subtitlePreferenceKey()
                        val delays = latestPreferences.subtitleDelays.toMutableMap()
                        if (key != null) {
                            delays.remove(key)
                            if (normalized.delayMs != 0L) delays[key] = normalized.delayMs
                        }
                        onPreferencesChange(latestPreferences.copy(subtitleScale = normalized.scale,
                            subtitlePositionPercent = normalized.positionPercent,
                            subtitleDelays = delays.entries.toList().takeLast(256).associate { it.key to it.value }))
                    },
                )
            }
        }
        PlaybackPrompts(
            player = player, state = state,
            visible = !state.controlsLocked && !isInPictureInPicture && !externalPanelVisible && !keepControlsVisible && state.errorMessage == null && !state.ended,
            showStatus = controlsVisible,
            onSkipIntro = { viewModel.skipIntro(); interact() }, onDismissIntro = viewModel::dismissIntroPrompt,
            onRetrySegments = viewModel::retryMediaSegments, onDismissSegmentsError = viewModel::dismissMediaSegmentsError,
            onNext = onNext,
        )
        if (state.controlsLocked && controlsVisible && !isInPictureInPicture) Box(
            Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets),
        ) {
            PlayerRoundButton(PathfinIcons.Lock, tr("解除屏幕锁定"), "player-unlock",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
                onClick = { viewModel.setControlsLocked(false) })
        }

        seekTarget?.let { target ->
            val delta = (target - seekOrigin) / 1000
            Column(Modifier.align(Alignment.Center).background(PlayerSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 14.dp).testTag("player-seek-preview"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${playerTime(target)} / ${playerTime(seekDuration)}", color = PlayerText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (delta >= 0) tr("+%1\$s 秒", delta) else tr("%1\$s 秒", delta), color = PlayerSecondary, fontSize = 13.sp)
            }
        }
        level?.let { (brightness, percent) ->
            Row(Modifier.align(Alignment.TopCenter).windowInsetsPadding(AppSafeDrawingInsets)
                .padding(top = if (controlsVisible && !state.controlsLocked) 80.dp else 24.dp)
                .background(PlayerSurface, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("player-level"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(when { brightness -> PathfinIcons.Sun; percent == 0 -> PathfinIcons.VolumeOff; else -> PathfinIcons.Volume },
                    if (brightness) tr("亮度") else tr("音量"), Modifier.size(20.dp), tint = PlayerText)
                Box(Modifier.width(132.dp).height(4.dp).background(Color.White.copy(alpha = .2f), CircleShape)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(percent.coerceIn(0, 100) / 100f).background(Color.White, CircleShape))
                }
                Text("$percent%", Modifier.widthIn(min = 40.dp), color = PlayerText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        // 长按倍速与双击快进快退同一位置提示，靠前置图标区分：仪表盘是倍速，双箭头是跳转。
        val feedback = state.temporarySpeed?.let { PlayerFeedback(tr("%1\$s× 倍速播放", it), PathfinIcons.Speed) } ?: gestureMessage
        if (feedback != null && seekTarget == null && level == null && state.errorMessage == null && !state.ended && !isInPictureInPicture) Row(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(AppSafeDrawingInsets)
                .padding(top = if (controlsVisible && !state.controlsLocked) 80.dp else 24.dp)
                .background(PlayerSurface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("player-feedback"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            feedback.icon?.let { Icon(it, null, Modifier.size(18.dp).testTag("player-feedback-icon"), tint = PlayerText) }
            Text(feedback.text, color = PlayerText, fontSize = 14.sp)
        }
        // 与控制层同一安全区与尺寸，控制栏显隐时加载圈停在播放键的同一位置。
        if (showLoading && loading && (!controlsVisible || state.controlsLocked) && !isInPictureInPicture) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(AppSafeDrawingInsets), contentAlignment = Alignment.Center) {
                PathfinSpinner(Color.White, Modifier.size(30.dp).testTag("player-loading"))
            }
        }
        // 播放结束不在这里停留：MainActivity 会续播下一集或直接退出播放器。
        state.errorMessage?.takeIf { !isInPictureInPicture }?.let { error ->
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp).widthIn(max = 420.dp)
                    .background(PlayerSurface, RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(error, color = PlayerText, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerPillButton(tr("重试"), primary = true, onClick = viewModel::retry)
                    PlayerPillButton(tr("返回详情"), primary = false, onClick = leave)
                }
            }
        }
    }
    if (showTools && !isInPictureInPicture) PlayerQualitySheet(
        selected = state.quality, changing = state.isChangingQuality, error = state.qualityError,
        enabled = state.canChangeQuality, onSelect = viewModel::changeQuality,
        onDismiss = { showTools = false; interact() },
        cloudOptions = state.cloudQualityOptions,
    )
    if (showSubtitleManager && !isInPictureInPicture) viewModel.currentRequest?.let { request ->
        SubtitleManagementSheet(request.session, request.item, viewModel.api,
            onDismiss = { showSubtitleManager = false; interact() },
            onChanged = viewModel::refreshSubtitles,
            verificationScope = subtitleVerificationScope,
            onAuthExpired = { onAuthenticationExpired(request.session) },
            sourceId = request.source.id, playerMode = true)
    }
}

/** 只接收画面空白区域；Compose按钮/进度条位于独立上层，不把触摸传给这里。 */
@UnstableApi
internal class GesturePlayerView(context: Context) : PlayerView(context) {
    private var assOwner: ExoAssExtension? = null
    private val videoContent = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
    private val videoLayoutListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) { updateVideoPresentation() }
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateVideoPresentation() }
    }
    var presentationMode = PlayerResizeMode.FIT
        set(value) {
            if (field == value) return
            field = value
            updateVideoPresentation()
        }

    init {
        // 保持实际视频 Surface 的源比例，仅缩放视频和字幕子树；控制栏不受画面比例影响。
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }

    private fun sourceAspectRatio(): Float {
        val owner = player ?: return 0f
        val size = owner.videoSize
        if (size.width > 0 && size.height > 0)
            return size.width * size.pixelWidthHeightRatio / size.height
        // 解码器尚未上报尺寸时，用已选轨道预留正确比例，避免首帧跳动。
        val format = owner.currentTracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }
            .firstOrNull() ?: return 0f
        if (format.width <= 0 || format.height <= 0) return 0f
        val pixelRatio = format.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val aspect = format.width * pixelRatio / format.height
        return if (format.rotationDegrees % 180 != 0) 1f / aspect else aspect
    }

    override fun onContentAspectRatioChanged(contentFrame: AspectRatioFrameLayout?, aspectRatio: Float) {
        super.onContentAspectRatioChanged(contentFrame, sourceAspectRatio().takeIf { it.isFinite() && it > 0f } ?: aspectRatio)
    }

    private fun updateVideoPresentation() {
        val content = videoContent ?: return
        val aspect = sourceAspectRatio()
        if (aspect.isFinite() && aspect > 0f) content.setAspectRatio(aspect)
        if (width <= 0 || height <= 0 || content.width <= 0 || content.height <= 0) return
        val widthScale = width.toFloat() / content.width
        val heightScale = height.toFloat() / content.height
        val scaleX: Float
        val scaleY: Float
        when (presentationMode) {
            PlayerResizeMode.FIT -> { scaleX = 1f; scaleY = 1f }
            PlayerResizeMode.ZOOM -> { scaleX = maxOf(widthScale, heightScale); scaleY = scaleX }
            PlayerResizeMode.FILL -> { scaleX = widthScale; scaleY = heightScale }
        }
        content.pivotX = content.width / 2f
        content.pivotY = content.height / 2f
        if (content.scaleX != scaleX || content.scaleY != scaleY) {
            content.scaleX = scaleX
            content.scaleY = scaleY
            assOwner?.updateViewport()
            updateSubtitlePresentation()
        }
    }
    fun bindPlayer(owner: Player?) {
        if (player === owner) return
        player?.removeListener(videoLayoutListener)
        player = owner
        owner?.addListener(videoLayoutListener)
        updateVideoPresentation()
    }

    fun releasePlayerOutput() {
        player?.removeListener(videoLayoutListener)
        player = null
    }

    var subtitlePresentation = SubtitlePresentation()
        set(value) { field = value; updateSubtitlePresentation() }
    private fun updateSubtitlePresentation() {
        subtitleView?.let { subtitles ->
            // 不沿用系统字幕样式（多数机型默认黑底）：白字、透明底、黑色描边，文件自带样式仍优先。
            subtitles.setStyle(SubtitleTextStyle)
            // 字幕层是画面容器的子 View，“放大/拉伸”会连带把字幕放大并推出屏幕。按容器缩放做反向缩放，
            // 字幕始终落在“适应”模式下的画面区域内，再叠加用户设置的缩放与垂直位置（以该区域底边为基准）。
            val containerX = videoContent?.scaleX?.takeIf { it > 0f } ?: 1f
            val containerY = videoContent?.scaleY?.takeIf { it > 0f } ?: 1f
            val scale = subtitlePresentation.scale
            val half = subtitles.height / 2f
            val offset = -(100 - subtitlePresentation.positionPercent) / 100f * subtitles.height * .8f
            subtitles.pivotX = subtitles.width / 2f
            subtitles.pivotY = half
            subtitles.scaleX = scale / containerX
            subtitles.scaleY = scale / containerY
            subtitles.translationY = (half * (1f - scale) + offset) / containerY
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateVideoPresentation()
        updateSubtitlePresentation()
    }

    fun bindAss(owner: ExoAssExtension?, onFailure: (ExoAssExtension) -> Unit = {}) {
        if (assOwner === owner) return
        val previous = assOwner
        assOwner = null
        runCatching { previous?.detach(this) }
        if (owner != null) {
            // 记录尝试过的 owner，失败后的重组不重复挂载；VM 将异步换为基础播放器。
            assOwner = owner
            try { owner.attach(this) } catch (_: Exception) { onFailure(owner) }
        }
    }

    var gesturesLocked = false
    var allowPlaybackGestures = true
    var longPressEnabled = true
    var onSingleTap: () -> Unit = {}
    var onLongPressStart: () -> Unit = {}
    var onLongPressEnd: () -> Unit = {}
    var onDoubleTapPosition: (Float) -> Unit = {}
    var onLongPressDrag: (Float) -> Unit = {}
    var onVerticalGestureStart: (Boolean) -> Unit = {}
    var onVerticalGesture: (Boolean, Float) -> Unit = { _, _ -> }
    var onVerticalGestureEnd: (Boolean) -> Unit = {}
    var horizontalSeekEnabled = true
    var onHorizontalSeekStart: () -> Unit = {}
    /** 参数为相对按下点的横向位移占画面宽度的比例，向右为正。 */
    var onHorizontalSeek: (Float) -> Unit = {}
    var onHorizontalSeekEnd: (Boolean) -> Unit = {}
    /** 双指缩放：参数为 true 表示张开。每次手势只触发一次。 */
    var onPinch: (Boolean) -> Unit = {}
    private var pinchScale = 1f
    private var pinchHandled = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchScale = 1f; pinchHandled = false
            return allowPlaybackGestures && !gesturesLocked
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (pinchHandled) return true
            pinchScale *= detector.scaleFactor
            if (pinchScale > 1.15f || pinchScale < .87f) {
                pinchHandled = true
                onPinch(pinchScale > 1f)
            }
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    private var horizontalGesture = false
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var verticalGesture: Boolean? = null
    private val edgeInset = 24f * resources.displayMetrics.density
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var videoGesture = false
    private var multiplePointers = false
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (isAttachedToWindow && player != null) performClick()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            onLongPressEnd()
            if (gesturesLocked || !allowPlaybackGestures) performClick()
            else if (width > 0) onDoubleTapPosition((event.x / width).coerceIn(0f, 1f))
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            if (allowPlaybackGestures && !gesturesLocked && longPressEnabled && videoGesture && !multiplePointers && verticalGesture == null) {
                longPressed = true
                onLongPressStart()
            }
        }

        override fun onScroll(start: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!allowPlaybackGestures || gesturesLocked || multiplePointers || longPressed || height <= 0) return false
            val dx = current.x - downX
            val dy = current.y - downY
            // 横向明显大于纵向时识别为拖进度；避开左右边缘，不与系统返回手势冲突。
            if (!horizontalGesture && verticalGesture == null && horizontalSeekEnabled && width > 0 &&
                kotlin.math.abs(dx) > touchSlop * 2 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                downX in edgeInset..(width - edgeInset) && downY in edgeInset..(height - edgeInset)) {
                horizontalGesture = true
                onLongPressEnd()
                onHorizontalSeekStart()
            }
            if (horizontalGesture) { onHorizontalSeek(dx / width); return true }
            if (verticalGesture == null && kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.3f &&
                downX in edgeInset..(width - edgeInset) && downY in edgeInset..(height - edgeInset)) {
                val fraction = downX / width
                if (fraction in .35f.. .65f) return false
                verticalGesture = fraction < .35f
                onLongPressEnd()
                onVerticalGestureStart(verticalGesture!!)
            }
            verticalGesture?.let { onVerticalGesture(it, -dy / (height * .7f)); return true }
            return false
        }
    })

    override fun performClick(): Boolean {
        super.performClick()
        onSingleTap()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            videoGesture = player != null
            multiplePointers = false
            downX = event.x; downY = event.y; longPressed = false; verticalGesture = null; horizontalGesture = false
            if (!videoGesture) cancelDetector()
        }
        if (!videoGesture) return super.dispatchTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiplePointers = true
            cancelDetector()
            onLongPressEnd(); longPressed = false
            onVerticalGestureEnd(true); verticalGesture = null
            endHorizontalSeek(true)
        }
        if (!multiplePointers) {
            detector.onTouchEvent(event)
            if (longPressed && event.actionMasked == MotionEvent.ACTION_MOVE && width > 0)
                onLongPressDrag((event.x - downX) / width)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            onLongPressEnd(); longPressed = false
            onVerticalGestureEnd(event.actionMasked == MotionEvent.ACTION_CANCEL); verticalGesture = null
            endHorizontalSeek(event.actionMasked == MotionEvent.ACTION_CANCEL)
            videoGesture = false
        }
        return true
    }

    fun cancelGesture() {
        cancelDetector()
        videoGesture = false
        multiplePointers = false
        onLongPressEnd(); longPressed = false
        onVerticalGestureEnd(true); verticalGesture = null
        endHorizontalSeek(true)
    }

    private fun endHorizontalSeek(cancelled: Boolean) {
        if (!horizontalGesture) return
        horizontalGesture = false
        onHorizontalSeekEnd(cancelled)
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    private fun cancelDetector() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        detector.onTouchEvent(cancel)
        cancel.recycle()
    }
}

@Composable
private fun playbackClock(enabled: Boolean): String {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var clock by remember { mutableStateOf(LocalTime.now().format(formatter)) }
    LaunchedEffect(enabled) {
        if (enabled) while (true) {
            clock = LocalTime.now().format(formatter)
            delay(15_000)
        }
    }
    return clock
}

@Composable
private fun playbackBattery(enabled: Boolean): Int? {
    val context = LocalContext.current
    var percentage by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        fun update(intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            percentage = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = update(intent)
        }
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        update(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percentage
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 播放器出错/结束层的胶囊按钮：主操作用播放器强调色，次操作为半透明白底。 */
@Composable
private fun PlayerPillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clip(CircleShape).background(if (primary) playerAccent() else Color.White.copy(alpha = .14f))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Color.Black else PlayerText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 播放画面上方的短暂提示：文字与可选的前置图标作为一个整体，避免切换时残留上一条的图标。 */
private data class PlayerFeedback(val text: String, val icon: ImageVector? = null)

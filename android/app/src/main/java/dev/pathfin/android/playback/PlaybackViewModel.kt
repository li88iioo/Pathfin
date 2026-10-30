package dev.pathfin.android.playback

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.SeekParameters
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.VideoPreflightPolicy
import dev.pathfin.android.data.mediaSegments
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.IntroSkipHistory
import dev.pathfin.android.domain.MediaSegment
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.PlaybackDelivery
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.subtitlePreferenceKey
import dev.pathfin.android.domain.hasServerBurnedSubtitles
import dev.pathfin.android.domain.PlaybackResolver
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.domain.ExternalSubtitleResolver
import dev.pathfin.android.i18n.tr
import dev.pathfin.ass.ExoAssExtension
import dev.pathfin.ass.SubtitleMediaSourceFactory
import dev.pathfin.ffmpeg.video.NativeVideoRenderer
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

@UnstableApi
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()
    private val mutableSubtitlePresentation = MutableStateFlow(SubtitlePresentation())
    val subtitlePresentation: StateFlow<SubtitlePresentation> = mutableSubtitlePresentation.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())

    var player: ExoPlayer? = null
        private set

    /** 界面只能取得当前播放器拥有的扩展，旧 PlayerView 不得解绑新会话。 */
    fun assExtensionFor(owner: Player?): ExoAssExtension? =
        active?.assExtension?.takeIf { owner != null && owner === player }

    /** 已建立的 raw ASS parser 不能靠移除画布恢复；只对当前 owner 重建一次基础 SSA。 */
    fun onAssAttachmentFailure(extension: ExoAssExtension) {
        val current = active ?: return
        fallbackAssExtension(current, extension, tr("ASS 样式扩展挂载失败，已回退基础字幕"))
    }

    private fun fallbackAssExtension(current: ActivePlayback, extension: ExoAssExtension, message: String) {
        if (current.assExtension !== extension || current.assFallbackScheduled || !current.tuning.exoAssEnabled) return
        current.assFallbackScheduled = true
        mainHandler.post {
            if (active !== current || current.assExtension !== extension) return@post
            val owner = player ?: return@post
            val restored = capturePlayerState(owner)
            startPlayback(current.request, current.originalRequest,
                current.tuning.copy(exoAssEnabled = false), current.introHistory, current.manualTracks,
                current.localSubtitles.toList(), recoveryPolicy = current.recoveryPolicy, restored = restored)
            mutableState.update { it.copy(assStatus = message) }
        }
    }

    /** 系统解码运行失败后换同源本地扩展；同一入口音视频各一次，不重新协商地址。 */
    private fun recoverLocalDecoder(current: ActivePlayback, owner: ExoPlayer, failure: PlaybackException): Boolean {
        if (active !== current || player !== owner) return false
        if (current.decoderFallbackScheduled) return true
        if (retryJob?.isActive == true || qualityJob?.isActive == true || qualityRollback != null) return false
        val error = failure as? ExoPlaybackException ?: return false
        if (error.type != ExoPlaybackException.TYPE_RENDERER || error.errorCode !in setOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)) return false
        if (error.rendererIndex !in 0 until owner.rendererCount) return false
        val type = owner.getRendererType(error.rendererIndex)
        val format = error.rendererFormat ?: return false
        if (type in current.decoderFallbackTypes || type in owner.trackSelectionParameters.disabledTrackTypes ||
            MimeTypes.getTrackType(format.sampleMimeType) != type || format.cryptoType != C.CRYPTO_TYPE_NONE ||
            format.drmInitData != null) return false
        val fallbackTuning = when (type) {
            C.TRACK_TYPE_VIDEO -> if (current.tuning.preferHardwareVideoDecoder)
                current.tuning.copy(preferHardwareVideoDecoder = false) else return false
            C.TRACK_TYPE_AUDIO -> if (current.tuning.exoAudioDecoder != "SOFTWARE")
                current.tuning.copy(exoAudioDecoder = "SOFTWARE") else return false
            else -> return false
        }
        val extension = (0 until owner.rendererCount).asSequence().map(owner::getRenderer).firstOrNull {
            when (type) {
                C.TRACK_TYPE_VIDEO -> it is NativeVideoRenderer
                C.TRACK_TYPE_AUDIO -> it is FfmpegAudioRenderer
                else -> false
            }
        } ?: return false
        if (owner.getRenderer(error.rendererIndex) === extension || error.rendererName == extension.name) return false
        current.decoderFallbackScheduled = true
        mutableState.update { it.copy(isPreparing = true, isBuffering = false, errorMessage = null, decodeWarning = null) }
        // 音频扩展查询会读取 AudioSink 的 PCM 能力，必须沿用该播放器的 playbackLooper。
        // 未设置 position 的消息在错误后的 IDLE 状态也立即处理，不依赖继续播放。
        owner.createMessage { _, _ ->
            val support = try {
                Result.success(RendererCapabilities.getFormatSupport(extension.capabilities.supportsFormat(format)))
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            mainHandler.post {
                if (active !== current || player !== owner) return@post
                current.decoderFallbackScheduled = false
                if (retryJob?.isActive == true || qualityJob?.isActive == true || qualityRollback != null) return@post
                val capabilityFailure = support.exceptionOrNull()
                if (capabilityFailure != null) {
                    val cause = capabilityFailure as? RuntimeException
                        ?: IllegalStateException("Local decoder capability query failed", capabilityFailure)
                    handlePlaybackFailure(current, owner, capabilityFailure as? PlaybackException
                        ?: ExoPlaybackException.createForUnexpected(cause, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK))
                } else if (support.getOrThrow() != C.FORMAT_HANDLED) {
                    handlePlaybackFailure(current, owner, failure)
                } else {
                    current.decoderFallbackTypes += type
                    val restored = capturePlayerState(owner)
                    startPlayback(current.request, current.originalRequest,
                        fallbackTuning.copy(audioGainPercent = current.runtimeAudioGainPercent),
                        current.introHistory, current.manualTracks, current.localSubtitles.toList(),
                        recoveryPolicy = current.recoveryPolicy, restored = restored, reuseMediaSegments = true)
                }
            }
        }.send()
        return true
    }

    private fun handlePlaybackFailure(current: ActivePlayback, owner: ExoPlayer, error: PlaybackException) {
        if (active !== current || player !== owner) return
        if (restorePreviousSource(current)) return
        if (recoverPlayback(current, playbackFailure(error, cloudUrl = current.request.cloudPlayback != null ||
            PlaybackResolver.isOriginalHttpPlayback(current.request)))) return
        mutableState.update {
            it.copy(isPreparing = false, isBuffering = false, isPlaying = false, errorMessage = errorMessage(error))
        }
        reportProgress(current, owner)
    }

    /** 释放播放器之前的最终位置；显式 stop 和自然结束后仍可读取。 */
    val lastPositionMs: Long get() = savedPositionMs
    private var savedPositionMs: Long = 0

    internal val api = JellyfinApi(capabilities = { DevicePlaybackCapabilities.current },
        videoSupport = DevicePlaybackCapabilities::supportsVideo)
    private val cloudApi = dev.pathfin.android.data.CloudPlaybackApi(videoSupport = DevicePlaybackCapabilities::supportsVideo)
    private var active: ActivePlayback? = null
    private var completedManualTracks: CompletedManualTracks? = null
    private var progressJob: Job? = null
    private var retryJob: Job? = null
    private var qualityJob: Job? = null
    private var qualityReadyJob: Job? = null
    private var qualityRollback: PlaybackRecovery? = null
    private var pendingSubtitleRefresh: dev.pathfin.android.domain.MediaItem? = null
    val currentRequest: PlaybackRequest? get() = active?.request
    private var playerListener: Player.Listener? = null
    private var generation = 0L
    private val entryGate = PlaybackEntryGate()
    private var speedBeforeLongPress: Float? = null
    private var temporarySpeedPlayer: Player? = null

    // 上报独立于 viewModelScope，onCleared 后仍能排空已入队的 stop，随后自行结束。
    // 顺序消费 Jellyfin 事件，新会话不能抢在旧会话停止上报之前。
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reports = PlaybackReportQueue()
    private val reportWorker: Job

    init {
        reportWorker = reportScope.launch {
            try {
                // Jellyfin 事件按入队顺序发送，旧会话 stop 不会被新会话 start 抢先。
                while (true) {
                    val message = reports.next() ?: break
                    when (message) {
                        is ReportMessage.Barrier -> message.completion.complete(Unit)
                        is ReportMessage.ReleaseTranscode -> {
                            try { withTimeout(8_000) { api.stopTranscoding(message.request) } }
                            catch (exception: Exception) {
                                if (exception is CancellationException && !isActive) throw exception
                            }
                        }
                        is ReportMessage.Playback -> {
                            val (request, report) = message
                            try {
                                withTimeout(8_000) {
                                    api.reportPlayback(request.session, report.event, request, report.positionMs, report.isPaused)
                                }
                            } catch (exception: Exception) {
                                if (exception is CancellationException && !isActive) throw exception
                                // 网络或过期会话不能阻塞返回，也不向日志暴露媒体签名地址。
                            }
                        }
                    }
                }
            } finally {
                reports.close()
                // 即便工作协程异常结束，也不能让已入队的刷新等待器永远挂起。
                reports.completePendingBarriers()
                reportScope.cancel()
            }
        }
    }

    fun play(request: PlaybackRequest, preferences: AppPreferences = AppPreferences()) {
        if (!entryGate.canOpen(request)) return
        val existing = active
        if (existing != null &&
            (PlaybackEntryGate.sameEntry(existing.originalRequest, request) || PlaybackEntryGate.sameEntry(existing.request, request))
        ) return
        entryGate.reset()
        // 主页面传入已恢复的偏好快照，起播不在主线程等待 SharedPreferences 磁盘读取。
        val tuning = PlaybackTuning.from(preferences)
            .let { if (request.item.type == "Audio") it.copy(introSkipMs = 0, outroSkipMs = 0) else it }
        val position = if (request.item.type == "TvChannel") 0 else tuning.initialPositionMs(request.startPositionMs, request.item.runTimeTicks)
        // 第一次有效起播立即消费结束交接；不匹配也丢弃，不能绕过别的媒体再继承。
        val completedTracks = completedManualTracks
        completedManualTracks = null
        val inheritedTracks = when {
            existing != null && player != null && canInheritManualTracks(existing.request, request, tuning.keepTracksForContinuousPlayback) -> existing.manualTracks
            completedTracks != null && canInheritManualTracks(completedTracks.identity, request, tuning.keepTracksForContinuousPlayback) -> completedTracks.selections
            else -> ManualTrackSelections()
        }
        mutableSubtitlePresentation.value = SubtitlePresentation(preferences.subtitleScale,
            preferences.subtitlePositionPercent, preferences.subtitleDelays[request.subtitlePreferenceKey()] ?: 0).normalized()
        startPlayback(request.copy(startPositionMs = position), request, tuning, manualTracks = inheritedTracks)
        player?.setPlaybackSpeed(preferences.playbackSpeed.takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f)
    }

    private fun capturePlayerState(owner: Player) = PlayerState(owner.currentPosition.coerceAtLeast(0),
        owner.playbackParameters, owner.playWhenReady, mutableState.value.controlsLocked,
        owner.trackSelectionParameters.disabledTrackTypes.toSet())

    private fun startPlayback(prepared: PlaybackRequest, originalRequest: PlaybackRequest, tuning: PlaybackTuning,
        introHistory: IntroSkipHistory = IntroSkipHistory(),
        manualTracks: ManualTrackSelections = ManualTrackSelections(),
        localSubtitles: List<LocalSubtitle> = emptyList(), rollback: PlaybackRecovery? = null,
        recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(), restored: PlayerState? = null,
        reuseMediaSegments: Boolean = false) {
        val request = restored?.let { prepared.copy(startPositionMs = it.positionMs) } ?: prepared
        val pendingSubtitles = pendingSubtitleRefresh?.takeIf {
            it.id == request.item.id && active?.request?.session?.hasSameAuthentication(request.session) == true
        }
        val previous = active
        val retainSession = previous?.let { PlaybackEntryGate.sameEntry(it.request, request) } == true
        val retainedEntry = previous?.takeIf { PlaybackEntryGate.sameEntry(it.originalRequest, originalRequest) }
        val retainedSegments = previous?.takeIf { reuseMediaSegments && retainSession }
        val reloadSegments = retainedSegments == null || retainedSegments.mediaSegmentsPending
        val segmentError = mutableState.value.mediaSegmentsError.takeIf { retainedSegments != null }
        val tracker = previous?.tracker?.takeIf { retainSession } ?: PlaybackSessionTracker()
        val pendingLocal = previous?.pendingLocalSubtitle?.takeIf { it in localSubtitles }
        finishActive(localSubtitles.map { it.file }.toSet(), retainPlaybackSession = retainSession)
        qualityRollback = rollback
        pendingSubtitleRefresh = pendingSubtitles
        generation += 1
        val current = ActivePlayback(request, originalRequest, tuning, playerGeneration = generation,
            introHistory = introHistory, manualTracks = manualTracks, recoveryPolicy = recoveryPolicy, tracker = tracker)
        current.decoderFallbackTypes = retainedEntry?.decoderFallbackTypes.orEmpty()
        current.mediaSegments = retainedSegments?.mediaSegments.orEmpty()
        current.outroSkipped = retainedSegments?.outroSkipped ?: false
        active = current
        current.localSubtitles.addAll(localSubtitles)
        current.pendingLocalSubtitle = pendingLocal
        current.subtitleDelayUs = mutableSubtitlePresentation.value.delayMs * 1000
        savedPositionMs = request.startPositionMs.coerceAtLeast(0)
        mutableState.value = PlaybackUiState(
            title = request.item.let { item ->
                val episode = item.indexNumber
                val season = item.parentIndexNumber
                when {
                    item.type == "Episode" && episode != null && season != null -> "S${season}E${episode} · ${item.name}"
                    item.type == "Episode" && episode != null -> tr("第%1\$s集 · %2\$s", episode, item.name)
                    else -> item.name
                }
            },
            isPreparing = true,
            controlsLocked = restored?.locked ?: false,
            isChangingQuality = rollback != null,
            mediaSegmentsError = segmentError,
            playerGeneration = generation,
            quality = request.quality,
            cloudQualityOptions = request.cloudPlayback?.options(),
            subtitlesBurnedIn = request.hasServerBurnedSubtitles(),
            canChangeQuality = request.item.type in setOf("Movie", "Episode", "Video"),
            showBufferSpeed = tuning.showBufferSpeed,
            nextEpisodePromptSeconds = tuning.nextEpisodePromptSeconds,
            rapidSeekWhileDragging = tuning.rapidSeekWhileDragging,
            sourceLabel = if (request.cloudPlayback != null) tr("云盘直连") else playbackSourceLabel(request.item, request.stream.trustedServer, request.stream.method),
        )

        try {
            val subtitles = ExternalSubtitleResolver.resolve(request)
            val subtitleUris = subtitles.map { it.uri }.toSet()
            val mediaClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(ScopedPlaybackInterceptor(request, subtitleUris))
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val metrics = current.transferMetrics
                    if (metrics != null && response.isSuccessful && chain.request().method != "HEAD")
                        response.newBuilder().body(metrics.wrap(response.body)).build()
                    else response
                }
                .build()
            current.mediaClient = mediaClient
            val assSubtitleUris = subtitles.filter { it.mimeType == MimeTypes.TEXT_SSA }.map { it.uri }.toSet()
            // 所有已解析外挂文本在解析器分配前统一限额，不影响视频或内嵌字幕读取。
            val dataSource = ExoAssExtension.boundedSubtitleDataSource(
                ResolvingDataSource.Factory(DefaultDataSource.Factory(getApplication(),
                    OkHttpDataSource.Factory(mediaClient).setUserAgent("Pathfin/0.1"))) { spec ->
                    val owned = current.localSubtitles.map { Uri.fromFile(it.file).toString() }.toSet()
                    if (!isPlaybackDataUriAllowed(spec.uri.toString(), owned))
                        throw java.io.IOException(tr("不允许读取此媒体地址"))
                    spec
                }, subtitleUris,
            ) {
                mainHandler.post {
                    if (active === current) mutableState.update {
                        it.copy(assStatus = tr("字幕文件超过安全大小限制，未加载该字幕"))
                    }
                }
            }
            val exoPlayer = createExoPlayer(current, dataSource, assSubtitleUris).apply {
                setHandleAudioBecomingNoisy(true)
            }
            player = exoPlayer
            exoPlayer.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().apply {
                tuning.audioLanguage?.let(::setPreferredAudioLanguage)
                tuning.subtitleLanguage?.let(::setPreferredTextLanguage)
                val disabled = restored?.disabledTrackTypes ?: setOfNotNull(C.TRACK_TYPE_TEXT.takeIf {
                    manualTracks.subtitle == RememberedTrack.Disabled || !tuning.subtitlesEnabled
                })
                disabled.forEach { setTrackTypeDisabled(it, true) }
            }.build()
            restored?.let { exoPlayer.playbackParameters = it.parameters }
            val listener = listenerFor(current, exoPlayer)
            playerListener = listener
            exoPlayer.addListener(listener)
            applyAudioGain(current, exoPlayer)
            val mediaItem = MediaItem.Builder().setUri(request.stream.uri).apply {
                setMediaMetadata(MediaMetadata.Builder().setTitle(request.item.name)
                    .setDisplayTitle(request.item.seriesName?.takeIf(String::isNotBlank) ?: request.item.name)
                    .setExtras(Bundle().apply {
                        val cloud = request.cloudPlayback?.let { snapshot -> snapshot.variants.firstOrNull { it.id == snapshot.selectedId } }
                        if (cloud != null && !cloud.original) {
                            putString("pathfin.container", cloud.transport)
                            cloud.bitrate?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.let { putInt("pathfin.sourceBitrate", it.toInt()) }
                        } else {
                            request.source.container?.let { putString("pathfin.container", it) }
                            request.source.size?.takeIf { it > 0 }?.let { putLong("pathfin.sourceBytes", it) }
                            request.source.bitRate?.takeIf { it > 0 }?.let { putInt("pathfin.sourceBitrate", it) }
                        }
                    }).build())
                request.stream.mimeType?.let(::setMimeType)
                setSubtitleConfigurations(subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(subtitle.uri.toUri())
                        .setId("external-subtitle-${subtitle.index}")
                        .setMimeType(subtitle.mimeType)
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .setSelectionFlags(
                            (if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
                                (if (subtitle.isForced) C.SELECTION_FLAG_FORCED else 0),
                        )
                        .setRoleFlags(C.ROLE_FLAG_SUBTITLE or if (subtitle.isHearingImpaired) C.ROLE_FLAG_CAPTION else 0)
                        .build()
                } + current.localSubtitles.map(LocalSubtitle::configuration))
            }.build()
            exoPlayer.setMediaItem(mediaItem, request.startPositionMs.coerceAtLeast(0))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = restored?.playing ?: true
            if (reloadSegments) loadMediaSegments(current)
            progressJob = viewModelScope.launch {
                var lastReportNanos = System.nanoTime()
                val interval = if (tuning.showBufferSpeed || tuning.outroSkipMs > 0 || current.introEnabled) 500L else 10_000L
                while (active === current) {
                    delay(interval)
                    if (active !== current) break
                    if (tuning.showBufferSpeed) mutableState.update {
                        it.copy(transferBytesPerSecond = current.transferMetrics?.sample()?.bytesPerSecond)
                    }
                    refreshIntroPrompt(current, exoPlayer)
                    if (active !== current) break
                    if (tuning.outroSkipMs > 0 && request.item.type != "TvChannel" && !exoPlayer.isCurrentMediaItemLive &&
                        exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                        tuning.outroEndPositionMs(exoPlayer.isPlaying, exoPlayer.currentPosition, exoPlayer.duration, current.outroSkipped)?.let { end ->
                            current.outroSkipped = true
                            // 片尾只跳至真实结尾；自然结束事件仍由原自动下一集偏好决定后续动作。
                            exoPlayer.seekTo(end)
                        }
                    }
                    // seekTo(end) 可能同步结束会话并释放播放器，不能继续读取旧实例。
                    if (active !== current) break
                    val now = System.nanoTime()
                    if (now - lastReportNanos >= 10_000_000_000L) {
                        lastReportNanos = now
                        reportProgress(current, exoPlayer)
                    }
                }
            }
        } catch (_: Exception) {
            mutableState.update {
                it.copy(isPreparing = false, isBuffering = false, errorMessage = tr("无法初始化播放器，请返回后重试。"))
            }
        }
    }

    private fun createExoPlayer(current: ActivePlayback, dataSource: DataSource.Factory, assSubtitleUris: Set<String>): ExoPlayer {
        val tuning = current.tuning
        val application = getApplication<Application>()
        fun build(extension: ExoAssExtension?): ExoPlayer {
            val delegate = PathfinRenderersFactory(application, tuning.preferHardwareVideoDecoder)
                .setMediaCodecSelector(PreferredMediaCodecSelector(tuning.exoAudioDecoder, tuning.preferHardwareVideoDecoder))
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(ffmpegAudioExtensionMode(tuning.exoAudioDecoder))
            extension?.setSubtitlePresentation(mutableSubtitlePresentation.value.scale,
                mutableSubtitlePresentation.value.positionPercent, mutableSubtitlePresentation.value.delayMs)
            val builder = ExoPlayer.Builder(application, subtitleTimingRenderers(extension?.renderersFactory(delegate) ?: delegate) {
                current.subtitleDelayUs
            })
            return builder.setTrackSelector(PathfinTrackSelector(application, current.request.source))
                .setMediaSourceFactory(extension?.mediaSourceFactory(dataSource, assSubtitleUris,
                ::SubtitleSeekPrerollExtractorsFactory)
                ?: SubtitleMediaSourceFactory(dataSource, SubtitleSeekPrerollExtractorsFactory()))
                .setLoadControl(DefaultLoadControl.Builder()
                    .setBufferDurationsMs(tuning.minBufferMs, tuning.maxBufferMs, 2_500, 5_000)
                    .setTargetBufferBytes(tuning.targetBufferBytes)
                    .setPrioritizeTimeOverSizeThresholds(false).build())
                .setSeekBackIncrementMs(tuning.seekIncrementMs)
                .setSeekParameters(if (tuning.exactSeek) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
                .setSeekForwardIncrementMs(tuning.seekIncrementMs).build()
        }
        if (!tuning.exoAssEnabled) return build(null)
        if (!ExoAssExtension.supportedOnDevice()) {
            mutableState.update { it.copy(assStatus = tr("当前设备不支持 ASS 样式扩展，使用基础字幕")) }
            return build(null)
        }
        var candidate: ExoPlayer? = null
        val token = ++current.assGeneration
        return try {
            val extension = ExoAssExtension(application) { status ->
                // 模块回调在 Main；身份与代次同时匹配，迟到状态不能覆盖新播放器。
                val owner = current.assExtension
                if (active === current && current.assGeneration == token && owner != null) {
                    mutableState.update { it.copy(assStatus = status) }
                    // 原始 ASS 管线已经接管字幕时，运行期 JNI/字体/渲染失败必须重建 SSA，
                    // 不能只显示错误后继续吞掉基础字幕；失败判定不依赖翻译后的文案。
                    if (owner.failed) fallbackAssExtension(current, owner, tr("ASS 样式扩展运行失败，已回退基础字幕"))
                }
            }
            current.assExtension = extension
            build(extension).also { candidate = it; extension.bind(it) }
        } catch (_: Exception) {
            ++current.assGeneration
            val extension = current.assExtension
            current.assExtension = null
            runCatching { extension?.close() }
            // 此实例尚未 setMediaItem/prepare，没有网络源；释放后重新创建基础 SSA 渲染器。
            runCatching { candidate?.release() }
            mutableState.update { it.copy(assStatus = tr("ASS 样式扩展初始化失败，已回退基础字幕")) }
            build(null)
        }
    }

    /** 片段查询完全独立于 prepare/首帧；退出、切片或重试会取消旧请求。 */
    private fun loadMediaSegments(current: ActivePlayback) {
        if (!current.tuning.serverIntroSkipEnabled || current.request.item.type !in setOf("Movie", "Episode")) return
        if (current.mediaSegmentsJob?.isActive == true) return
        current.mediaSegmentsPending = true
        mutableState.update { it.copy(mediaSegmentsError = null) }
        current.mediaSegmentsJob = viewModelScope.launch {
            try {
                val segments = withTimeout(8_000) { api.mediaSegments(current.request.session, current.request.item.id) }
                if (active === current && isActive) {
                    current.mediaSegments = segments
                    current.mediaSegmentsPending = false
                    player?.let { refreshIntroPrompt(current, it) }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (active === current && isActive) {
                    current.mediaSegmentsPending = false
                    mutableState.update { it.copy(mediaSegmentsError = tr("片头信息获取超时，可重试")) }
                    player?.let { refreshIntroPrompt(current, it) }
                }
            } catch (_: Exception) {
                if (active === current && isActive) {
                    current.mediaSegmentsPending = false
                    mutableState.update { it.copy(mediaSegmentsError = tr("片头信息暂不可用，可重试")) }
                    player?.let { refreshIntroPrompt(current, it) }
                }
            } finally {
                current.mediaSegmentsJob = null
            }
        }
    }

    fun retryMediaSegments() { active?.let(::loadMediaSegments) }

    private fun refreshIntroPrompt(current: ActivePlayback, owner: Player, allowAutomatic: Boolean = true) {
        if (active !== current || player !== owner) return
        val prompt = if (current.introEnabled && !owner.isCurrentMediaItemLive &&
            owner.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && mutableState.value.errorMessage == null)
            current.introHistory.activeIntro(current.mediaSegments, owner.currentPosition, owner.duration,
                current.tuning.introPromptMs, current.mediaSegmentsPending) else null
        if (prompt != null && allowAutomatic && current.tuning.autoSkipIntro && owner.isPlaying && current.introHistory.consume(prompt)) {
            mutableState.update { it.copy(introPrompt = null) }
            owner.seekTo(prompt.endMs)
        } else mutableState.update { it.copy(introPrompt = prompt) }
    }

    /** 重算实际位置后再跳，过期提示和旧片段不能跳动新影片。 */
    fun skipIntro() {
        val current = active ?: return
        val owner = player ?: return
        if (mutableState.value.controlsLocked) return
        refreshIntroPrompt(current, owner, allowAutomatic = false)
        val prompt = mutableState.value.introPrompt ?: return
        if (!current.introHistory.consume(prompt)) return
        mutableState.update { it.copy(introPrompt = null) }
        owner.seekTo(prompt.endMs)
    }

    fun dismissIntroPrompt() {
        val current = active ?: return
        mutableState.value.introPrompt?.let(current.introHistory::consume)
        mutableState.update { it.copy(introPrompt = null) }
    }

    fun dismissMediaSegmentsError() { mutableState.update { it.copy(mediaSegmentsError = null) } }

    /** 保留当前位置和选定源，显式重新协商过期签名；退出会取消任务，旧响应不能复活播放。 */
    fun retry() {
        val current = active ?: return
        retrySource(current, current.request.delivery, automatic = false)
    }

    private fun recoverPlayback(current: ActivePlayback, failure: PlaybackFailure): Boolean {
        if (active !== current || qualityJob?.isActive == true || qualityRollback != null) return false
        if (retryJob?.isActive == true) return true
        val delivery = current.recoveryPolicy.next(current.request, failure) ?: return false
        retrySource(current, delivery, automatic = true)
        return true
    }

    /** 显式重试与自动恢复共享协商、所有权检查和状态恢复，不另建一条播放链。 */
    private fun retrySource(current: ActivePlayback, delivery: PlaybackDelivery, automatic: Boolean) {
        if (active !== current || retryJob?.isActive == true || qualityJob?.isActive == true || qualityRollback != null) return
        val owner = player ?: return
        endTemporarySpeed()
        mutableState.update { it.copy(isPreparing = true, isBuffering = false, errorMessage = null, decodeWarning = null, ended = false) }
        retryJob = viewModelScope.launch {
            try {
                val previous = current.request
                val retainOriginalHttp = PlaybackResolver.isOriginalHttpPlayback(previous)
                var target = delivery
                while (true) {
                    val prepared = if (previous.cloudPlayback != null) {
                        cloudApi.select(previous, previous.quality)
                    } else {
                        val revision = current.manualTrackRevision
                        val tracks = serverTrackSelection(current, owner)
                        val info = api.playbackInfo(previous.session, previous.item.id, previous.source.id,
                            TimeUnits.msToTicks(owner.currentPosition.coerceAtLeast(0)), previous.quality, target, tracks.first, tracks.second,
                            videoPreflight = if (retainOriginalHttp || previous.item.type == "TvChannel" && current.tuning.preferOriginalLive)
                                VideoPreflightPolicy.TRY_ORIGINAL_SOURCE else VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED)
                        if (active !== current || player !== owner) return@launch
                        if (revision != current.manualTrackRevision) continue
                        val source = info.sources.firstOrNull { it.id == previous.source.id }
                            ?: throw IllegalArgumentException(tr("所选媒体版本已不存在"))
                        val stream = try {
                            PlaybackResolver.resolve(previous.session, previous.item, info, source.id,
                                retainOriginalHttp || current.tuning.preferOriginalFor(previous.item), previous.quality, target).also {
                                require(!retainOriginalHttp || !it.trustedServer) { tr("原始HTTP播放地址已不可用，请重新选择片源") }
                            }
                        } catch (unavailable: IllegalArgumentException) {
                            // 服务器没有可重封装的流时直接升级一次；权限/API错误不会进入此分支。
                            if (retainOriginalHttp || !automatic || target != PlaybackDelivery.SERVER_STREAM || !info.errorCode.isNullOrBlank()) throw unavailable
                            target = PlaybackDelivery.TRANSCODE
                            continue
                        }
                        previous.copy(info = info, source = source, stream = stream)
                    }
                    if (active !== current || player !== owner) return@launch
                    val restored = capturePlayerState(owner)
                    retryJob = null
                    startPlayback(prepared,
                        current.originalRequest, current.tuning, current.introHistory, current.manualTracks,
                        current.localSubtitles.toList(), recoveryPolicy = if (automatic) current.recoveryPolicy else PlaybackRecoveryPolicy(),
                        restored = restored)
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (active === current) mutableState.update {
                    it.copy(isPreparing = false, isBuffering = false, errorMessage = if (current.request.cloudPlayback != null ||
                        PlaybackResolver.isOriginalHttpPlayback(current.request))
                        tr("无法重新获取直连播放地址，请检查网络或重新选择片源。") else if (automatic)
                        tr("无法恢复播放，请检查服务器转码权限或网络后重试。") else tr("无法重新获取播放地址，请检查网络后重试。"))
                }
            } finally {
                if (active === current) retryJob = null
            }
        }
    }

    fun pauseForBackground() {
        endTemporarySpeed()
        val current = active ?: return
        qualityJob?.cancel(); qualityJob = null
        qualityRollback = qualityRollback?.let { it.copy(state = it.state.copy(playing = false)) }
        mutableState.update { it.copy(isChangingQuality = false) }
        if (retryJob?.isActive == true) {
            retryJob?.cancel()
            retryJob = null
            mutableState.update {
                it.copy(isPreparing = false, isBuffering = false, errorMessage = tr("重新获取地址已暂停，请重试。"))
            }
        }
        player?.let {
            val wasPlaying = it.isPlaying
            it.pause()
            // 实际播放转为暂停时，Listener 已负责上报；缓冲或已暂停时没有该回调。
            if (!wasPlaying) reportProgress(current, it)
        }
    }

    /** 协商期间保留旧播放器；失败不会把当前可用片源替换成黑屏。 */
    fun changeQuality(quality: PlaybackQuality) = updatePlaybackSource(quality)

    fun refreshSubtitles(item: dev.pathfin.android.domain.MediaItem) {
        val current = active ?: return
        if (item.id != current.request.item.id) return
        if (qualityJob?.isActive == true || retryJob?.isActive == true || qualityRollback != null) pendingSubtitleRefresh = item
        else updatePlaybackSource(current.request.quality, item)
    }

    private fun updatePlaybackSource(quality: PlaybackQuality, updatedItem: dev.pathfin.android.domain.MediaItem? = null,
        recovery: PlaybackRecovery? = null) {
        val current = active ?: return
        val owner = player
        if (owner == null && recovery == null) return
        if (qualityJob?.isActive == true || retryJob?.isActive == true || qualityRollback != null) return
        if (updatedItem == null && (quality == current.request.quality || !mutableState.value.canChangeQuality)) return
        endTemporarySpeed()
        mutableState.update { it.copy(isChangingQuality = true, qualityError = null) }
        qualityJob = viewModelScope.launch {
            try {
                val previous = current.request
                val item = updatedItem ?: previous.item
                val delivery = recovery?.request?.delivery ?: if (updatedItem != null) previous.delivery else PlaybackDelivery.AUTOMATIC
                val prepared = if (previous.cloudPlayback != null) {
                    var basis = (recovery?.request ?: previous).copy(item = item)
                    // 上传/删除外挂字幕仍刷新原文件的轨道信息，但不能让非原画档位触发JF转码。
                    if (updatedItem != null && recovery == null) {
                        val info = api.playbackInfo(previous.session, item.id, previous.source.id,
                            TimeUnits.msToTicks(checkNotNull(owner).currentPosition.coerceAtLeast(0)),
                            videoPreflight = VideoPreflightPolicy.TRY_ORIGINAL_SOURCE)
                        val source = info.sources.firstOrNull { it.id == previous.source.id }
                            ?: throw IllegalStateException(tr("当前媒体版本已不存在"))
                        val cloud = if (dev.pathfin.android.data.CloudPlaybackApi.candidateUrl(source) == basis.cloudPlayback?.sourceUrl) basis.cloudPlayback else
                            cloudApi.discover(previous.session, source)
                                ?: throw IllegalStateException(tr("媒体来源已变化，请重新打开播放"))
                        basis = basis.copy(info = info, source = source, cloudPlayback = cloud)
                    }
                    cloudApi.select(basis, quality)
                } else {
                    var info: dev.pathfin.android.domain.PlaybackInfo
                    do {
                        val revision = current.manualTrackRevision
                        val tracks = if (recovery != null) recovery.request.info.let { it.audioStreamIndex to it.subtitleStreamIndex }
                            else serverTrackSelection(current, checkNotNull(owner))
                        info = api.playbackInfo(previous.session, item.id, previous.source.id,
                            TimeUnits.msToTicks(recovery?.state?.positionMs ?: checkNotNull(owner).currentPosition.coerceAtLeast(0)), quality,
                            delivery, tracks.first, tracks.second,
                            videoPreflight = if (item.type == "TvChannel" && current.tuning.preferOriginalLive)
                                VideoPreflightPolicy.TRY_ORIGINAL_SOURCE else VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED)
                        if (active !== current || player !== owner) return@launch
                    } while (revision != current.manualTrackRevision)
                    val source = info.sources.firstOrNull { it.id == previous.source.id }
                        ?: throw IllegalStateException(tr("当前媒体版本已不存在"))
                    val stream = PlaybackResolver.resolve(previous.session, item, info, source.id, current.tuning.preferOriginalFor(item), quality, delivery)
                    previous.copy(item = item, info = info, source = source, stream = stream, quality = quality)
                }
                if (active !== current || player !== owner) return@launch
                val restored = recovery?.state ?: capturePlayerState(checkNotNull(owner))
                val rollback = if (recovery == null) PlaybackRecovery(previous, restored) else null
                qualityJob = null
                startPlayback(prepared, current.originalRequest, current.tuning,
                    current.introHistory, current.manualTracks, current.localSubtitles.toList(), rollback,
                    recoveryPolicy = if (updatedItem != null) current.recoveryPolicy else PlaybackRecoveryPolicy(), restored = restored)
                mutableState.update { it.copy(isChangingQuality = qualityRollback != null,
                    qualityError = if (recovery != null) tr("切换失败，已重新请求%1\$s", quality.label) else null) }
                val replacement = active
                if (replacement != null && qualityRollback != null) {
                    if (mutableState.value.errorMessage != null) restorePreviousSource(replacement)
                    else qualityReadyJob = viewModelScope.launch {
                        delay(20_000)
                        if (active === replacement && qualityRollback != null) restorePreviousSource(replacement)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (active === current) mutableState.update { it.copy(
                    errorMessage = if (recovery != null) tr("切换失败，原播放源也暂时无法恢复，请重试") else it.errorMessage,
                    qualityError =
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message
                    else if (current.request.cloudPlayback != null) tr("无法切换云盘画质；当前播放保持不变")
                    else tr("无法更新播放源，请检查服务器转码权限或网络；当前播放保持不变")) }
            } finally {
                if (active === current) {
                    qualityJob = null
                    mutableState.update { it.copy(isChangingQuality = false) }
                    flushPendingSubtitleRefresh(current)
                }
            }
        }
    }

    private fun restorePreviousSource(failed: ActivePlayback): Boolean {
        if (active !== failed) return false
        val recovery = qualityRollback ?: return false
        qualityRollback = null
        qualityReadyJob?.cancel(); qualityReadyJob = null
        mutableState.update { it.copy(errorMessage = null, isChangingQuality = true) }
        updatePlaybackSource(recovery.request.quality, recovery.request.item, recovery)
        return true
    }

    private fun acceptPlaybackSource(current: ActivePlayback) {
        if (active !== current) return
        qualityRollback = null
        qualityReadyJob?.cancel(); qualityReadyJob = null
        mutableState.update { it.copy(isChangingQuality = false) }
        flushPendingSubtitleRefresh(current)
    }

    private fun flushPendingSubtitleRefresh(current: ActivePlayback) {
        if (active !== current || qualityJob?.isActive == true || retryJob?.isActive == true || qualityRollback != null) return
        val item = pendingSubtitleRefresh ?: return
        pendingSubtitleRefresh = null
        viewModelScope.launch { if (active === current) refreshSubtitles(item) }
    }

    /** 转码只保留服务端选中的轨道；必须在协商前传入索引，不能只给新播放器恢复 override。 */
    private fun serverTrackSelection(current: ActivePlayback, owner: Player): Pair<Int?, Int?> {
        fun selected(type: Int): Format? {
            // override同步更新，Tracks回调异步到达；协商必须先读用户刚选的轨道。
            val override = owner.trackSelectionParameters.overrides.values.firstOrNull { it.type == type && it.trackIndices.isNotEmpty() }
            if (override != null) return override.mediaTrackGroup.getFormat(override.trackIndices.first())
            return owner.currentTracks.groups.asSequence().filter { it.type == type }
                .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }.firstOrNull()
        }
        val source = current.request.source
        val transcoded = current.request.stream.method == PlayMethod.TRANSCODE
        val audio = serverTrackIndex(source, C.TRACK_TYPE_AUDIO, selected(C.TRACK_TYPE_AUDIO))
            ?: if (transcoded) current.request.info.audioStreamIndex ?: source.defaultAudioStreamIndex else null
        val text = selected(C.TRACK_TYPE_TEXT)
        val subtitle = if (C.TRACK_TYPE_TEXT in owner.trackSelectionParameters.disabledTrackTypes ||
            current.manualTracks.subtitle == RememberedTrack.Disabled || current.pendingLocalSubtitle != null ||
            current.localSubtitles.any { it.id == text?.id }) -1
        else serverTrackIndex(source, C.TRACK_TYPE_TEXT, text)
            ?: if (transcoded) current.request.info.subtitleStreamIndex ?: source.defaultSubtitleStreamIndex else null
        return audio to subtitle
    }

    fun seekByGesture(forward: Boolean) {
        val exoPlayer = player ?: return
        if (forward) {
            if (exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) exoPlayer.seekForward()
        } else if (exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_BACK)) exoPlayer.seekBack()
    }

    fun togglePlayPause() {
        val exoPlayer = player ?: return
        if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
    }

    /** 进度拖动保留原播放/暂停状态；位置变化由统一Listener上报。 */
    fun seekToPosition(positionMs: Long) {
        val exoPlayer = player ?: return
        if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val duration = exoPlayer.duration
        val maximum = duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        exoPlayer.seekTo(positionMs.coerceIn(0, maximum))
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val exoPlayer = player ?: return
        if (!exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        endTemporarySpeed()
        exoPlayer.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
    }

    /** 仅由实际选轨界面调用；自动选轨事件本身绝不变成用户记忆。 */
    fun onManualTrackSelection(owner: Player, type: Int, format: Format?, disabled: Boolean) {
        val current = active ?: return
        if (player !== owner || mutableState.value.playerGeneration != current.playerGeneration ||
            owner.pathfinReleased() || !owner.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val rememberedType = rememberedType(type) ?: return
        if (format != null && (disabled || owner.currentTracks.groups.none { group ->
                group.type == type && (0 until group.length).any { group.isTrackSupported(it) && group.getTrackFormat(it) == format }
            })) return
        val remembered = when {
            disabled -> RememberedTrack.Disabled
            format != null -> stableTrackIdentity(format)?.let { RememberedTrack.Selected(it) }
            else -> null
        }
        current.manualTrackRevision += 1
        current.manualTracks = current.manualTracks.with(rememberedType, remembered)
        // “自动”与“关闭”同样立即使旧 pending 失效；迟到 Tracks 不能覆盖新手选。
        current.pendingManualTrackTypes.remove(rememberedType)
    }

    fun configureAudioGain(percent: Int) {
        val current = active ?: return
        val normalized = percent.coerceIn(0, 200)
        if (current.runtimeAudioGainPercent == normalized) return
        current.runtimeAudioGainPercent = normalized
        player?.let { applyAudioGain(current, it) }
    }

    fun setSubtitlePresentation(value: SubtitlePresentation) {
        val current = active ?: return
        val owner = player ?: return
        val next = value.normalized()
        val previous = mutableSubtitlePresentation.value
        if (next == previous) return
        mutableSubtitlePresentation.value = next
        current.subtitleDelayUs = next.delayMs * 1000
        current.assExtension?.setSubtitlePresentation(next.scale, next.positionPercent, next.delayMs)
        if (next.delayMs != previous.delayMs) reloadExoSubtitles(current, owner)
    }

    /** One owned copy and the same Player; cancellation/old picker results cannot change a new item. */
    suspend fun addLocalSubtitle(uri: Uri): String {
        val current = active ?: return tr("播放器已关闭")
        val owner = player ?: return tr("播放器已关闭")
        if (current.localSubtitles.size >= LocalSubtitleImporter.MAX_FILES) return tr("本次播放最多添加 8 个本地字幕")
        val subtitle = try { LocalSubtitleImporter.read(getApplication(), uri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return tr("无法读取字幕，请选择不超过 8 MB 的 SRT、VTT、ASS 或 SSA 文件") }
        if (active !== current || player !== owner || current.localSubtitles.size >= LocalSubtitleImporter.MAX_FILES) {
            LocalSubtitleImporter.delete(listOf(subtitle.file))
            return tr("播放已切换，未添加字幕")
        }
        current.localSubtitles += subtitle
        current.pendingLocalSubtitle = subtitle
        current.manualTrackRevision += 1
        val media = owner.currentMediaItem ?: return tr("播放器已关闭")
        val configurations = media.localConfiguration?.subtitleConfigurations.orEmpty() + subtitle.configuration()
        reloadExoSubtitles(current, owner, media.buildUpon().setSubtitleConfigurations(configurations).build())
        return tr("正在加载本地字幕")
    }

    private fun reloadExoSubtitles(current: ActivePlayback, owner: ExoPlayer, media: MediaItem? = owner.currentMediaItem) {
        if (active !== current || player !== owner || media == null) return
        val position = owner.currentPosition.coerceAtLeast(0)
        val playing = owner.playWhenReady
        val parameters = owner.playbackParameters
        // A new text stream reconstructs long cues through the same bounded/preroll source.
        current.pendingManualTrackTypes += RememberedTrackType.entries.filter { current.manualTracks[it] != null }
        owner.setMediaItem(media, position)
        owner.prepare()
        owner.playbackParameters = parameters
        owner.playWhenReady = playing
    }

    private fun applyLocalSubtitleSelection(current: ActivePlayback, owner: Player, tracks: Tracks) {
        if (active !== current || player !== owner) return
        val subtitle = current.pendingLocalSubtitle ?: return
        val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group.mediaTrackGroup to it }
        }
        val match = candidates.firstOrNull { (group, index) -> group.getFormat(index).id == subtitle.id }
            ?: candidates.filter { (group, index) -> group.getFormat(index).label == subtitle.label }.singleOrNull()
            ?: return
        current.pendingLocalSubtitle = null
        owner.trackSelectionParameters = owner.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(match.first, match.second)).build()
        onManualTrackSelection(owner, C.TRACK_TYPE_TEXT, match.first.getFormat(match.second), false)
    }

    private fun applyRememberedTracks(current: ActivePlayback, owner: Player, tracks: Tracks) {
        if (active !== current || player !== owner || mutableState.value.playerGeneration != current.playerGeneration ||
            current.pendingManualTrackTypes.isEmpty() || owner.pathfinReleased() ||
            !owner.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val revision = current.manualTrackRevision
        val previous = owner.trackSelectionParameters
        val builder = previous.buildUpon()
        val applied = mutableSetOf<RememberedTrackType>()
        for (rememberedType in current.pendingManualTrackTypes.toList()) {
            val type = if (rememberedType == RememberedTrackType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            when (val remembered = current.manualTracks[rememberedType]) {
                null -> applied += rememberedType
                RememberedTrack.Disabled -> {
                    builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true)
                    applied += rememberedType
                }
                is RememberedTrack.Selected -> {
                    val candidates = tracks.groups.filter { it.type == type }.flatMap { group ->
                        (0 until group.length).filter { group.isTrackSupported(it) }.map { group.mediaTrackGroup to it }
                    }
                    val match = uniqueRememberedTrackIndex(remembered.identity, candidates.map { (group, index) ->
                        stableTrackIdentity(group.getFormat(index))
                    })?.let(candidates::get) ?: continue
                    builder.clearOverridesOfType(type).setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(match.first, match.second))
                    applied += rememberedType
                }
            }
        }
        if (active !== current || player !== owner || mutableState.value.playerGeneration != current.playerGeneration ||
            current.manualTrackRevision != revision) return
        current.pendingManualTrackTypes.removeAll(applied)
        val next = builder.build()
        if (next != previous) owner.trackSelectionParameters = next
    }

    private fun rememberedType(type: Int): RememberedTrackType? = when (type) {
        C.TRACK_TYPE_AUDIO -> RememberedTrackType.AUDIO
        C.TRACK_TYPE_TEXT -> RememberedTrackType.SUBTITLE
        else -> null
    }

    private fun stableTrackIdentity(format: Format): StableTrackIdentity? = StableTrackIdentity.create(
        format.language, format.label, format.roleFlags, format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
    )

    fun setControlsLocked(locked: Boolean) {
        if (locked && active == null) return
        endTemporarySpeed()
        mutableState.update { it.copy(controlsLocked = locked) }
    }

    /** 顶部、预测返回和物理返回共用；第一次只解锁，后续返回才退出播放。 */
    fun consumeBackToUnlock(): Boolean {
        if (!mutableState.value.controlsLocked) return false
        setControlsLocked(false)
        return true
    }

    fun beginTemporarySpeed() {
        val current = active ?: return
        val exoPlayer = player ?: return
        if (!current.tuning.longPressSpeedEnabled || speedBeforeLongPress != null || !exoPlayer.isPlaying) return
        speedBeforeLongPress = exoPlayer.playbackParameters.speed
        temporarySpeedPlayer = exoPlayer
        val speed = temporaryPlaybackSpeed(checkNotNull(speedBeforeLongPress), current.tuning.longPressSpeed) ?: run {
            speedBeforeLongPress = null; temporarySpeedPlayer = null; return
        }
        exoPlayer.setPlaybackSpeed(speed)
        mutableState.update { it.copy(temporarySpeed = speed) }
    }

    fun updateTemporarySpeed(speed: Float) {
        if (active?.tuning?.longPressSlideSpeedEnabled != true) return
        val owner = temporarySpeedPlayer ?: return
        if (speedBeforeLongPress == null || owner !== player || !speed.isFinite()) return
        val bounded = (kotlin.math.round(speed * 4) / 4).coerceIn(.25f, 4f)
        owner.setPlaybackSpeed(bounded)
        mutableState.update { it.copy(temporarySpeed = bounded) }
    }

    fun updateTemporarySpeedBySlide(fraction: Float) {
        val current = active ?: return
        if (!current.tuning.longPressSlideSpeedEnabled || !fraction.isFinite()) return
        val base = speedBeforeLongPress ?: return
        val multiplier = (kotlin.math.round((current.tuning.longPressSpeed + fraction.coerceIn(-1f, 1f) * 2f) * 4) / 4).coerceIn(1.25f, 4f)
        val speed = temporaryPlaybackSpeed(base, multiplier) ?: return
        val owner = temporarySpeedPlayer ?: return
        if (owner !== player) return
        owner.setPlaybackSpeed(speed)
        mutableState.update { it.copy(temporarySpeed = speed) }
    }

    fun endTemporarySpeed() {
        val speed = speedBeforeLongPress
        val owner = temporarySpeedPlayer
        speedBeforeLongPress = null
        temporarySpeedPlayer = null
        if (speed != null && owner != null && owner === player) owner.setPlaybackSpeed(speed)
        if (mutableState.value.temporarySpeed != null) mutableState.update { it.copy(temporarySpeed = null) }
    }

    fun stop() {
        completedManualTracks = null
        finishActive()
        entryGate.reset()
        mutableState.value = PlaybackUiState(playerGeneration = generation)
    }

    /** 仅挂起调用方；立即返回页面后可等待此前的停止上报已尝试完成，再刷新服务端进度。 */
    suspend fun awaitPendingReports() {
        val completion = CompletableDeferred<Unit>()
        if (reports.offer(ReportMessage.Barrier(completion))) completion.await()
        else reportWorker.join()
    }

    private fun listenerFor(current: ActivePlayback, exoPlayer: ExoPlayer) = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            if (active !== current || player !== exoPlayer) return
            applyRememberedTracks(current, exoPlayer, tracks)
            applyLocalSubtitleSelection(current, exoPlayer, tracks)
            val disabled = exoPlayer.trackSelectionParameters.disabledTrackTypes
            val warning = (if (C.TRACK_TYPE_VIDEO !in disabled) unsupportedVideoMessage(tracks) else null)
                ?: if (C.TRACK_TYPE_AUDIO !in disabled) unsupportedAudioMessage(tracks) else null
            if (warning != null && (restorePreviousSource(current) || recoverPlayback(current, PlaybackFailure.FORMAT))) return
            mutableState.update { it.copy(decodeWarning = warning) }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            applyAudioGain(current, exoPlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (active !== current) return
            mutableState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                val started = current.tracker.started(exoPlayer.currentPosition)
                if (started != null) enqueue(current, started)
                else reportProgress(current, exoPlayer)
            } else if (exoPlayer.playbackState != Player.STATE_ENDED) {
                reportProgress(current, exoPlayer)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (active !== current) return
            if (playbackState == Player.STATE_READY && (C.TRACK_TYPE_VIDEO in exoPlayer.trackSelectionParameters.disabledTrackTypes ||
                    exoPlayer.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }))
                acceptPlaybackSource(current)
            if (playbackState == Player.STATE_ENDED) {
                completedManualTracks = if (current.manualTracks != ManualTrackSelections())
                    trackContinuityIdentity(current.request)?.let { CompletedManualTracks(it, current.manualTracks) } else null
                entryGate.completed(current.originalRequest, current.request)
                finishActive()
                mutableState.update {
                    it.copy(isPreparing = false, isBuffering = false, isPlaying = false, ended = true)
                }
            } else {
                mutableState.update {
                    it.copy(
                        isPreparing = retryJob?.isActive == true || (playbackState == Player.STATE_IDLE && it.errorMessage == null),
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                    )
                }
            }
        }

        override fun onRenderedFirstFrame() {
            acceptPlaybackSource(current)
            if (active === current) mutableState.update { it.copy(hasRenderedFrame = true, isPreparing = retryJob?.isActive == true) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (active !== current) return
            endTemporarySpeed()
            if (recoverLocalDecoder(current, exoPlayer, error)) return
            handlePlaybackFailure(current, exoPlayer, error)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (active === current && reason == Player.DISCONTINUITY_REASON_SEEK) {
                reportProgress(current, exoPlayer)
                refreshIntroPrompt(current, exoPlayer, allowAutomatic = false)
            }
        }
    }

    private fun applyAudioGain(current: ActivePlayback, exoPlayer: ExoPlayer) {
        if (active !== current || player !== exoPlayer) return
        val gain = current.audioGain ?: PlaybackAudioGain().also { current.audioGain = it }
        val result = gain.apply(exoPlayer.audioSessionId, current.runtimeAudioGainPercent)
        exoPlayer.volume = result.volume
        mutableState.update { it.copy(audioGainStatus = result.status) }
    }

    private fun reportProgress(current: ActivePlayback, exoPlayer: Player) {
        enqueue(current, current.tracker.progress(exoPlayer.currentPosition, !exoPlayer.isPlaying))
    }

    private fun enqueue(current: ActivePlayback, report: PlaybackReport?) {
        if (report == null) return
        reports.offer(ReportMessage.Playback(current.request, report))
    }

    private fun finishActive(retainedSubtitleFiles: Set<java.io.File> = emptySet(), retainPlaybackSession: Boolean = false) {
        endTemporarySpeed()
        val current = active
        val exoPlayer = player
        if (current != null) {
            val position = (exoPlayer?.currentPosition ?: current.request.startPositionMs).coerceAtLeast(0)
            savedPositionMs = position
            // ASS回退只重建本机渲染器，同一播放会话仍在使用，不能停止它再异步删除其转码任务。
            if (!retainPlaybackSession) {
                enqueue(current, current.tracker.stopped(position, exoPlayer?.isPlaying != true))
                if (current.request.stream.trustedServer && current.request.stream.method == PlayMethod.TRANSCODE)
                    reports.offer(ReportMessage.ReleaseTranscode(current.request))
            }
        }
        active = null
        LocalSubtitleImporter.delete(current?.localSubtitles.orEmpty().map { it.file }.filterNot { it in retainedSubtitleFiles })
        current?.let { owner ->
            owner.assGeneration += 1
            val extension = owner.assExtension
            owner.assExtension = null
            // close 只解绑/入队 native 释放；不等待 worker，不触碰媒体网络客户端。
            runCatching { extension?.close() }
        }
        current?.mediaSegmentsJob?.cancel()
        current?.mediaSegmentsJob = null
        retryJob?.cancel()
        retryJob = null
        qualityJob?.cancel()
        qualityJob = null
        qualityReadyJob?.cancel()
        qualityReadyJob = null
        qualityRollback = null
        pendingSubtitleRefresh = null
        progressJob?.cancel()
        progressJob = null
        playerListener?.let { exoPlayer?.removeListener(it) }
        playerListener = null
        current?.audioGain?.close()
        current?.audioGain = null
        mutableState.update { it.copy(transferBytesPerSecond = null, audioGainStatus = null, introPrompt = null, mediaSegmentsError = null, assStatus = null) }
        player = null
        val mediaClient = current?.mediaClient
        current?.mediaClient = null
        try {
            // Media3 仍在 applicationLooper 释放，不把播放器对象交给网络清理线程。
            exoPlayer?.release()
        } finally {
            // Socket.close 可能写 TLS close_notify；即使 onCleared 已取消 viewModelScope，
            // 独立清理任务仍会执行，且只操作这个旧会话已摘下的独占客户端。
            mediaClient?.let { PlaybackClientCleanup.release(it) }
        }
    }

    override fun onCleared() {
        completedManualTracks = null
        try { finishActive() }
        finally {
            reports.close()
        }
    }

    private class ActivePlayback(
        val request: PlaybackRequest,
        val originalRequest: PlaybackRequest,
        val tuning: PlaybackTuning,
        val playerGeneration: Long,
        val tracker: PlaybackSessionTracker = PlaybackSessionTracker(),
        var mediaClient: OkHttpClient? = null,
        val transferMetrics: PlaybackTransferMetrics? = if (tuning.showBufferSpeed) PlaybackTransferMetrics() else null,
        var audioGain: PlaybackAudioGain? = null,
        var runtimeAudioGainPercent: Int = tuning.audioGainPercent,
        val localSubtitles: CopyOnWriteArrayList<LocalSubtitle> = CopyOnWriteArrayList(),
        var pendingLocalSubtitle: LocalSubtitle? = null,
        @Volatile var subtitleDelayUs: Long = 0,
        var outroSkipped: Boolean = false,
        val introHistory: IntroSkipHistory = IntroSkipHistory(),
        var mediaSegments: List<MediaSegment> = emptyList(),
        var mediaSegmentsPending: Boolean = false,
        var mediaSegmentsJob: Job? = null,
        val introEnabled: Boolean = request.item.type in setOf("Movie", "Episode") && (tuning.serverIntroSkipEnabled || tuning.introPromptMs > 0),
        var assExtension: ExoAssExtension? = null,
        var assGeneration: Long = 0,
        var assFallbackScheduled: Boolean = false,
        var decoderFallbackScheduled: Boolean = false,
        var decoderFallbackTypes: Set<Int> = emptySet(),
        var manualTracks: ManualTrackSelections = ManualTrackSelections(),
        val recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
        var manualTrackRevision: Long = 0,
        val pendingManualTrackTypes: MutableSet<RememberedTrackType> = RememberedTrackType.entries
            .filterTo(mutableSetOf()) { manualTracks[it] != null },
    )
    private data class CompletedManualTracks(val identity: TrackContinuityIdentity, val selections: ManualTrackSelections)
    /** 同一媒体重建共享一份运行态；不保留旧 TrackGroup，也不跨新播放入口继承。 */
    private data class PlayerState(val positionMs: Long, val parameters: androidx.media3.common.PlaybackParameters,
        val playing: Boolean, val locked: Boolean, val disabledTrackTypes: Set<Int>)
    private data class PlaybackRecovery(val request: PlaybackRequest, val state: PlayerState)
}

private fun errorMessage(error: PlaybackException): String {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return when (cause.responseCode) {
                401, 403 -> tr("媒体地址已失效或拒绝访问，点击重试以重新获取地址。")
                404 -> tr("媒体文件不存在或地址已失效。")
                else -> tr("媒体服务器返回错误（HTTP %1\$s），请稍后重试。", cause.responseCode)
            }
        }
        cause = cause.cause
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> tr("无法连接媒体服务器，请检查网络后重试。")
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> tr("设备暂不支持此媒体的编码或封装，可返回后选择其他版本。")
        else -> tr("播放失败，请重试或返回后选择其他媒体版本。")
    }
}

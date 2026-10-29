package dev.pathfin.android.playback

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.IntroSkipPrompt
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackQualityOption
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.i18n.tr

data class PlaybackUiState(
    val title: String = "",
    val isPreparing: Boolean = false,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val hasRenderedFrame: Boolean = false,
    val ended: Boolean = false,
    val errorMessage: String? = null,
    val playerGeneration: Long = 0,
    val temporarySpeed: Float? = null,
    val controlsLocked: Boolean = false,
    val sourceLabel: String = "",
    val audioGainStatus: String? = null,
    val showBufferSpeed: Boolean = false,
    val transferBytesPerSecond: Long? = null,
    val introPrompt: IntroSkipPrompt? = null,
    val mediaSegmentsError: String? = null,
    val nextEpisodePromptSeconds: Int = 30,
    val rapidSeekWhileDragging: Boolean = false,
    val assStatus: String? = null,
    val quality: dev.pathfin.android.domain.PlaybackQuality = dev.pathfin.android.domain.PlaybackQuality.ORIGINAL,
    val isChangingQuality: Boolean = false,
    val qualityError: String? = null,
    val canChangeQuality: Boolean = false,
    val decodeWarning: String? = null,
    val subtitlesBurnedIn: Boolean = false,
    /** null 沿用 Jellyfin 码率选项；非 null 展示云盘实际档位与可用状态。 */
    val cloudQualityOptions: List<PlaybackQualityOption>? = null,
)

/** 播放开始时固定参数；重试沿用同一快照，避免设置变更切换播放路径。 */
internal data class PlaybackTuning(
    val preferOriginal: Boolean,
    val preferOriginalLive: Boolean,
    val seekIncrementMs: Long,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val targetBufferBytes: Int,
    val introSkipMs: Long,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitlesEnabled: Boolean,
    val longPressSpeedEnabled: Boolean,
    val longPressSpeed: Float,
    val audioGainPercent: Int = 100,
    val outroSkipMs: Long = 0,
    val exactSeek: Boolean = true,
    val showBufferSpeed: Boolean = false,
    val serverIntroSkipEnabled: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val introPromptMs: Long = 0,
    val nextEpisodePromptSeconds: Int = 30,
    val longPressSlideSpeedEnabled: Boolean = true,
    val rapidSeekWhileDragging: Boolean = false,
    val exoAssEnabled: Boolean = true,
    val keepTracksForContinuousPlayback: Boolean = false,
    val exoAudioDecoder: String = "AUTO",
    val preferHardwareVideoDecoder: Boolean = true,
) {
    /** 直播频道按“直连直播源”，其余媒体按 STRM 原始地址开关。 */
    fun preferOriginalFor(item: MediaItem): Boolean = if (item.type == "TvChannel") preferOriginalLive else preferOriginal

    fun initialPositionMs(startPositionMs: Long, durationTicks: Long): Long {
        if (startPositionMs > 0) return startPositionMs
        val durationMs = TimeUnits.ticksToMs(durationTicks)
        // 短片不能因片头设置而直接结束；续播位置始终优先。
        return if (durationMs > 0 && introSkipMs >= durationMs) 0 else introSkipMs
    }

    /** 只依据播放器实际时长；暂停、缓冲、未知/短时长或已触发时不跳片尾。 */
    fun outroEndPositionMs(isPlaying: Boolean, positionMs: Long, durationMs: Long, alreadySkipped: Boolean): Long? =
        durationMs.takeIf {
            !alreadySkipped && isPlaying && outroSkipMs > 0 && durationMs > outroSkipMs &&
                positionMs >= 0 && positionMs < durationMs && durationMs - positionMs <= outroSkipMs
        }

    companion object {
        fun from(preferences: AppPreferences): PlaybackTuning {
            val maxBufferMs = preferences.bufferPreset.seconds * 1_000
            return PlaybackTuning(
                preferOriginal = preferences.originalPathEnabled,
                preferOriginalLive = preferences.liveTvDirectPlay,
                seekIncrementMs = preferences.seekSeconds.coerceIn(5, 60) * 1_000L,
                minBufferMs = minOf(15_000, maxBufferMs),
                maxBufferMs = maxBufferMs,
                targetBufferBytes = preferences.bufferPreset.megabytes * 1_024 * 1_024,
                introSkipMs = preferences.introSkipSeconds.coerceIn(0, 180) * 1_000L,
                audioLanguage = language(preferences.preferredAudioLanguage),
                subtitleLanguage = language(preferences.preferredSubtitleLanguage),
                subtitlesEnabled = preferences.subtitlesEnabled,
                longPressSpeedEnabled = preferences.longPressSpeedEnabled,
                longPressSpeed = preferences.longPressSpeed.takeIf { it.isFinite() }?.coerceIn(1.25f, 4f) ?: 2.5f,
                audioGainPercent = preferences.audioGainPercent.coerceIn(0, 200),
                outroSkipMs = preferences.outroSkipSeconds.coerceIn(0, 180) * 1_000L,
                exactSeek = preferences.exactSeek, showBufferSpeed = preferences.showBufferSpeed,
                serverIntroSkipEnabled = preferences.serverIntroSkipEnabled,
                autoSkipIntro = preferences.autoSkipIntro,
                introPromptMs = preferences.introPromptSeconds.coerceIn(0, 180) * 1_000L,
                nextEpisodePromptSeconds = preferences.nextEpisodePromptSeconds.coerceIn(0, 180),
                longPressSlideSpeedEnabled = preferences.longPressSlideSpeedEnabled,
                rapidSeekWhileDragging = preferences.rapidSeekWhileDragging,
                exoAssEnabled = preferences.exoAssEnabled,
                keepTracksForContinuousPlayback = preferences.keepTracksForContinuousPlayback,
                exoAudioDecoder = preferences.exoAudioDecoder.takeIf { it in setOf("AUTO", "HARDWARE", "SOFTWARE") } ?: "AUTO",
                preferHardwareVideoDecoder = preferences.preferHardwareVideoDecoder,
            )
        }

        private fun language(value: String): String? = value.trim().takeIf {
            it.isNotBlank() && it.length <= 64 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
        }?.replace('_', '-')
    }
}

/** 长按倍率乘长按前的实际速度；无效输入不产生非有限播放器参数。 */
internal fun temporaryPlaybackSpeed(baseSpeed: Float, multiplier: Float): Float? {
    if (!baseSpeed.isFinite() || baseSpeed <= 0 || !multiplier.isFinite()) return null
    return (baseSpeed.coerceIn(.25f, 4f) * multiplier.coerceIn(1.25f, 4f)).coerceIn(.25f, 4f)
}

/** 单调时间节流只影响拖动预览；松手的最终 seek 不经过该门。 */
internal class RapidSeekThrottle(private val intervalMs: Long = 150) {
    private var lastTimeMs: Long? = null
    private var lastPositionMs: Long? = null

    fun reset() { lastTimeMs = null; lastPositionMs = null }

    fun shouldSeek(positionMs: Long, nowMs: Long): Boolean {
        if (positionMs < 0 || nowMs < 0 || positionMs == lastPositionMs) return false
        val previous = lastTimeMs
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false
        lastTimeMs = nowMs
        lastPositionMs = positionMs
        return true
    }
}

/** 控制层使用 Media3 Player 接口；已释放的 ExoPlayer 不再接收监听和控制操作。 */
@androidx.media3.common.util.UnstableApi
internal fun androidx.media3.common.Player.pathfinReleased(): Boolean =
    (this as? androidx.media3.exoplayer.ExoPlayer)?.isReleased == true

/**
 * 播放信息里的来源：外部地址分 STRM 直连与直播源直连；经 Jellyfin 的再按实际播放方式区分
 * 直接播放（原文件）、直接串流（只换封装、不重新编码）与转码。每次换流（切画质、自动恢复）都会重新计算。
 */
internal fun playbackSourceLabel(item: MediaItem, trustedServer: Boolean, method: PlayMethod): String {
    val live = item.type == "TvChannel"
    if (!trustedServer) return if (live) tr("直播源直连") else tr("STRM 原始地址")
    val server = if (live) tr("Jellyfin 直播") else "Jellyfin"
    return when (method) {
        PlayMethod.DIRECT_PLAY -> tr("%1\$s · 直接播放", server)
        PlayMethod.DIRECT_STREAM -> tr("%1\$s · 直接串流（不转码）", server)
        PlayMethod.TRANSCODE -> tr("%1\$s · 转码", server)
    }
}

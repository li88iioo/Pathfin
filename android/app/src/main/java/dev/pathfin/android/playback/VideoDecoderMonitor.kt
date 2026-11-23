package dev.pathfin.android.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 只在本机软解 VVC 且持续倍速播放时采样。暂停、跳转或换解码器后重新计数。 */
@UnstableApi
internal class VideoDecoderMonitor(
    private val owner: ExoPlayer,
    private val selector: PreferredMediaCodecSelector,
    private val state: MutableStateFlow<PlaybackUiState>,
    private val scope: CoroutineScope,
    alreadyHinted: Boolean,
) : AnalyticsListener {
    var hinted = alreadyHinted
        private set
    private var sampling: Job? = null

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        state.update { it.copy(videoDecoder = selector.videoDecoderKind(decoderName)) }
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        state.update { it.copy(videoDecoder = null) }
    }

    override fun onEvents(player: Player, events: AnalyticsListener.Events) {
        if (events.containsAny(AnalyticsListener.EVENT_IS_PLAYING_CHANGED, AnalyticsListener.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                AnalyticsListener.EVENT_POSITION_DISCONTINUITY, AnalyticsListener.EVENT_VIDEO_INPUT_FORMAT_CHANGED,
                AnalyticsListener.EVENT_VIDEO_DECODER_INITIALIZED, AnalyticsListener.EVENT_VIDEO_DECODER_RELEASED,
                AnalyticsListener.EVENT_VIDEO_DISABLED)) resetSampling()
    }

    fun resetSampling() {
        sampling?.cancel()
        sampling = null
        val current = state.value
        if (current.showSpeedHint) state.update { it.copy(showSpeedHint = false) }
        if (hinted || current.live || !owner.isPlaying || current.temporarySpeed != null || owner.playbackParameters.speed <= 1f ||
            owner.videoFormat?.sampleMimeType != MimeTypes.VIDEO_H266 ||
            (current.videoDecoder != VideoDecoderKind.FFMPEG && current.videoDecoder != VideoDecoderKind.SYSTEM_SOFTWARE)) return
        val counters = owner.videoDecoderCounters ?: return
        sampling = scope.launch {
            awaitSustainedDrops(counters)
            hinted = true
            state.update { it.copy(showSpeedHint = true) }
            delay(4_000)
            state.update { it.copy(showSpeedHint = false) }
        }
    }

    private suspend fun awaitSustainedDrops(counters: DecoderCounters) {
        counters.ensureUpdated()
        var dropped = counters.droppedBufferCount
        var rendered = counters.renderedOutputBufferCount
        var slowWindows = 0
        while (slowWindows < 2) {
            delay(1_000)
            counters.ensureUpdated()
            val newDrops = counters.droppedBufferCount - dropped
            val newFrames = counters.renderedOutputBufferCount - rendered
            slowWindows = if (newDrops >= 2 && newDrops * 10L >= newFrames + newDrops) slowWindows + 1 else 0
            dropped += newDrops
            rendered += newFrames
        }
    }

    fun close() {
        owner.removeAnalyticsListener(this)
        sampling?.cancel()
        state.update { it.copy(videoDecoder = null, showSpeedHint = false) }
    }
}

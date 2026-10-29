package dev.pathfin.ass

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.NoSampleRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A real libass Canvas overlay independent of the video effects chain.
 *
 * Construction/capability/factories perform no JNI, font, cache, or network IO. Preparation is
 * triggered only after an ASS track is selected, on the worker. The supplied DataSource is used intact.
 * Main-thread bind/attach/detach/close are idempotent; onStatus is always delivered on main.
 * If [failed] becomes true, the owner should rebuild once with its normal SSA pipeline.
 */
@UnstableApi
class ExoAssExtension(context: Context, onStatus: (String?) -> Unit = {}) : AutoCloseable {
    private val context = context.applicationContext
    private val closed = AtomicBoolean()
    private var player: ExoPlayer? = null
    private var attached: PlayerView? = null
    private var overlay: AssOverlayView? = null
    private val subtitleDelayMs = AtomicLong()
    private val playbackTimeMs = AtomicLong()
    private var subtitleScale = 1f
    private var subtitlePosition = 100
    private val controller = AssController(onFrame = { overlay?.setFrame(it) }, onStatus = onStatus)
    val failed: Boolean get() = controller.failed
    val diagnostics: ExoAssDiagnostics get() = controller.diagnostics

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) { selectTrack(tracks) }
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateViewport() }
        // Only the renderer/extractor invalidate the ASS clock. The application-thread seek
        // notification also fires for a same-position seek which Media3 does not perform; clearing
        // here would erase a paused frame without any matching renderer reset to redraw it.
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewport() }

    fun mediaSourceFactory(
        dataSource: DataSource.Factory,
        assSubtitleUris: Set<String>,
        wrapExtractors: (ExtractorsFactory) -> ExtractorsFactory,
    ): MediaSource.Factory {
        check(!closed.get())
        val parsers = AssSubtitleParserFactory(controller, embeddedPackets = false)
        return SubtitleMediaSourceFactory(boundedSubtitleDataSource(dataSource, assSubtitleUris,
            controller::rejectExternalInput), wrapExtractors(AssExtractorsFactory(controller)), parsers)
    }

    fun renderersFactory(delegate: RenderersFactory = DefaultRenderersFactory(context)): RenderersFactory {
        check(!closed.get())
        return object : RenderersFactory by delegate {
            override fun createRenderers(
                eventHandler: android.os.Handler,
                videoRendererEventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                audioRendererEventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                textRendererOutput: androidx.media3.exoplayer.text.TextOutput,
                metadataRendererOutput: androidx.media3.exoplayer.metadata.MetadataOutput,
            ): Array<Renderer> = delegate.createRenderers(eventHandler, videoRendererEventListener,
                audioRendererEventListener, textRendererOutput, metadataRendererOutput) + AssClockRenderer(controller) { time ->
                    playbackTimeMs.set(time)
                    controller.requestFrame(time - subtitleDelayMs.get())
                }
        }
    }

    /** Subtitle-only presentation: no font, native, or disk work on main. */
    fun setSubtitlePresentation(scale: Float, positionPercent: Int, delayMs: Long) {
        requireMain()
        if (closed.get()) return
        subtitleScale = if (scale.isFinite()) scale.coerceIn(.5f, 2f) else 1f
        subtitlePosition = positionPercent.coerceIn(0, 100)
        overlay?.setPresentation(subtitleScale, subtitlePosition)
        val next = delayMs.coerceIn(-60_000, 60_000)
        if (subtitleDelayMs.getAndSet(next) != next) {
            controller.discontinuity()
            controller.requestFrame(playbackTimeMs.get() - next)
        }
    }

    fun bind(player: ExoPlayer) {
        requireMain()
        check(!closed.get())
        if (this.player === player) return
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
        selectTrack(player.currentTracks)
        updateViewport()
    }

    fun attach(view: PlayerView) {
        requireMain()
        check(!closed.get())
        if (attached === view && overlay != null) return
        attached?.let(::detach)
        val parent = checkNotNull(view.overlayFrameLayout) { "ASS overlay container is unavailable" }
        val canvas = AssOverlayView(view.context)
        canvas.setPresentation(subtitleScale, subtitlePosition)
        try {
            parent.addView(canvas, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            attached = view
            overlay = canvas
            view.addOnLayoutChangeListener(layoutListener)
            view.videoSurfaceView?.addOnLayoutChangeListener(layoutListener)
            canvas.addOnLayoutChangeListener(layoutListener)
            controller.setActive(true)
            updateViewport()
        } catch (error: RuntimeException) {
            parent.removeView(canvas)
            attached = null; overlay = null
            throw IllegalStateException("ASS 画布不可用，需要回退基础字幕", error)
        }
    }

    fun detach(view: PlayerView) {
        requireMain()
        if (attached !== view) return
        view.removeOnLayoutChangeListener(layoutListener)
        view.videoSurfaceView?.removeOnLayoutChangeListener(layoutListener)
        overlay?.let { canvas ->
            canvas.removeOnLayoutChangeListener(layoutListener)
            (canvas.parent as? android.view.ViewGroup)?.removeView(canvas)
            canvas.setFrame(null)
        }
        attached = null; overlay = null
        controller.setActive(false)
    }

    private fun selectTrack(tracks: Tracks) {
        val format = tracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).asSequence()
                .filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }.firstOrNull()
        controller.select(format)
    }

    /** Refresh display geometry after a video-only transform; never changes the playback clock. */
    fun updateViewport() {
        requireMain()
        if (closed.get()) return
        val view = attached ?: return
        val canvas = overlay ?: return
        val size = player?.videoSize ?: VideoSize.UNKNOWN
        val rect = canvas.videoRect(view, size)
        // libass renders in the same untransformed plane as the physical video buffer;
        // Canvas applies the display transform once, including intentional FILL stretching.
        val surface = view.videoSurfaceView
        val width = surface?.width?.takeIf { it > 0 } ?: rect.width().toInt()
        val height = surface?.height?.takeIf { it > 0 } ?: rect.height().toInt()
        if (width > 0 && height > 0) controller.viewport(width, height, size.width, size.height)
    }

    override fun close() {
        requireMain()
        if (!closed.compareAndSet(false, true)) return
        attached?.let(::detach)
        player?.removeListener(listener)
        player = null
        controller.close()
    }

    companion object {
        /** A pure ABI check: never attempts to load the subtitle JNI on main. */
        fun supportedOnDevice(): Boolean = Process.is64Bit() && Build.SUPPORTED_ABIS.contains("arm64-v8a")
        /** Apply equally to extended and base SSA so a fallback never reopens unbounded ASS input. */
        fun boundedSubtitleDataSource(delegate: DataSource.Factory, exactAssUris: Set<String>,
                                      onRejected: (() -> Unit)? = null): DataSource.Factory =
            AssSubtitleDataSource.factory(delegate, exactAssUris, onRejected)
        private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "ASS view operations require main" }
    }
}

@UnstableApi
internal class AssClockRenderer(private val controller: AssController,
    private val onClock: (Long) -> Unit = controller::requestFrame) : NoSampleRenderer() {
    private var offsetUs = 0L
    override fun getName() = "PathfinAssClock"
    override fun onRendererOffsetChanged(offsetUs: Long) { this.offsetUs = offsetUs; controller.discontinuity() }
    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        controller.discontinuity()
        onClock((positionUs - offsetUs) / 1000)
    }
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        onClock((positionUs - offsetUs) / 1000)
    }
    override fun onDisabled() { controller.discontinuity() }
}

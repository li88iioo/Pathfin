package dev.pathfin.ass

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.sqrt

/** Safe, URL-free diagnostics. A rendered frame means libass produced actual ASS_Image pixels. */
data class ExoAssDiagnostics(
    val nativeInitialized: Boolean,
    val renderedFrames: Long,
    val lastRenderedTimeMs: Long,
    val loadedAttachmentFonts: Int,
    val liveTracks: Int,
    val pendingMainFrames: Int,
    val pendingMainTasks: Int,
    val replacedPendingFrames: Long,
    /** A non-empty frame of the current clock/selection generation has reached the main callback. */
    val hasPresentedCurrentFrame: Boolean = false,
)

@UnstableApi
internal class AssController(
    private val onFrame: (Bitmap?) -> Unit,
    private val onStatus: (String?) -> Unit,
) : AutoCloseable {
    internal class Track(val key: String, val format: Format) {
        @Volatile var failed = false
        @Volatile var handle = 0L // initialized only after this ASS track is actually selected
        var fullDigest: ByteArray? = null // worker only; a file is never parsed twice on seek
        var fullData: ByteArray? = null
        var fullLoaded = false
        val pending = ArrayList<AssPacket>()
        val readOrders = HashSet<Long>()
    }
    internal data class Viewport(val width: Int, val height: Int, val storageWidth: Int, val storageHeight: Int)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Pathfin-libass").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val failure = AtomicBoolean()
    private val initialized = AtomicBoolean()
    private val rendered = AtomicLong()
    private val lastTime = AtomicLong(-1)
    private val presentedGeneration = AtomicLong(-1)
    private val attachmentFonts = AtomicInteger()
    private data class PendingFrame(val bitmap: Bitmap?, val generation: Long)
    private val pendingFrame = AtomicReference<PendingFrame?>()
    private val framePosted = AtomicBoolean()
    private val replacedFrames = AtomicLong()
    private val reservedFontBytes = AtomicInteger()
    private val epoch = AtomicLong()
    private val queued = AtomicBoolean()
    private val dirty = AtomicBoolean()
    private val tracks = ConcurrentHashMap<String, Track>()
    private val trackLock = Any()
    private var headerBytes = 0L // trackLock
    private var eventBytes = 0L // worker, owner-wide (not per track)
    private var eventCount = 0
    private val inputError = AtomicReference<String?>()
    private val pendingFonts = LinkedHashMap<String, ByteArray>() // worker only
    private var engine = 0L // worker only
    private var lastBitmap: Bitmap? = null // worker only; safe immutable after native composition
    private var lastRenderNanos = 0L
    private var lastRenderEpoch = -1L
    @Volatile private var selectedFormat: Format? = null
    @Volatile private var selectedTrack: Track? = null
    @Volatile private var viewport: Viewport? = null
    @Volatile private var mediaTimeMs = 0L
    @Volatile private var active = true
    private var lastStatus: String? = null // main only
    private val statusPosted = AtomicBoolean()

    val failed: Boolean get() = failure.get()
    val diagnostics: ExoAssDiagnostics get() = ExoAssDiagnostics(initialized.get(), rendered.get(),
        lastTime.get(), attachmentFonts.get(), tracks.size, if (pendingFrame.get() == null) 0 else 1,
        if (framePosted.get()) 1 else 0, replacedFrames.get(), presentedGeneration.get() == epoch.get())

    /** Pure in-memory registration. Unselected ASS tracks must never initialize JNI or fonts. */
    fun openTrack(format: Format): Track? {
        if (closed.get() || failed) return null
        val key = trackKey(format)
        return synchronized(trackLock) {
            if (closed.get() || failed) return@synchronized null
            tracks[key]?.let { return@synchronized it.takeUnless(Track::failed) }
            val size = format.initializationData.sumOf { it.size.toLong() }
            if (headerBytes + size > MAX_TEXT || tracks.size >= 64) { fail(); return@synchronized null }
            headerBytes += size
            Track(key, format).also { track ->
                tracks[key] = track
                if (matches(selectedFormat, format)) selectedTrack = track
                statusForSelection()
                requestFrame(mediaTimeMs)
            }
        }
    }

    fun external(track: Track, bytes: ByteArray): Boolean {
        if (closed.get() || failed || track.failed) return false
        if (bytes.size > MAX_TEXT) { fail(); return false }
        return callWorker {
            if (closed.get() || failed || track.failed) return@callWorker false
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            if (!digest.contentEquals(track.fullDigest)) {
                eventBytes += bytes.size - (track.fullData?.size ?: 0)
                check(eventBytes <= MAX_EVENTS) { "ASS owner data budget exceeded" }
                track.fullData = bytes
                track.fullDigest = digest
                track.fullLoaded = false
            }
            if (selectedTrack === track && active) prepareSelected(track)
            requestFrame(mediaTimeMs)
            true
        } == true
    }

    fun chunk(track: Track, sample: ByteArray, timeUs: Long): Boolean {
        if (closed.get() || failed || track.failed) return false
        val packet = AssPacket.parse(sample, timeUs) ?: return true // Empty/invalid packets have no cue.
        return callWorker {
            if (closed.get() || failed || track.failed) return@callWorker false
            if (track.readOrders.add(packet.readOrder)) {
                eventBytes += packet.data.size
                check(eventBytes <= MAX_EVENTS && ++eventCount <= 50_000) { "ASS owner event budget exceeded" }
                track.pending.add(packet)
            }
            if (selectedTrack === track && active) prepareSelected(track)
            requestFrame(mediaTimeMs)
            true
        } == true
    }

    /** Input has already been size/name checked by the extractor. No filesystem extraction. */
    fun addFont(name: String, bytes: ByteArray) {
        if (closed.get() || !AssPacket.safeFontName(name) || bytes.size !in 1..MAX_FONT) return
        while (true) {
            val before = reservedFontBytes.get()
            if (before + bytes.size > MAX_FONTS) return
            if (reservedFontBytes.compareAndSet(before, before + bytes.size)) break
        }
        submit {
            if (engine == 0L) pendingFonts.putIfAbsent(name, bytes)
            else { AssNative.addFont(engine, name, bytes); attachmentFonts.incrementAndGet() }
        }
    }

    fun select(format: Format?) {
        if (closed.get()) return
        val actual = format?.takeIf(::isAss)
        val generation = synchronized(trackLock) {
            val changed = selectedFormat != actual
            selectedFormat = actual
            selectedTrack = tracks.values.lastOrNull { !it.failed && matches(actual, it.format) }
            if (changed) epoch.incrementAndGet() else null
        }
        if (generation != null) postFrame(null, generation)
        statusForSelection()
        requestFrame(mediaTimeMs)
    }

    fun rejectExternalInput() {
        inputError.set("ASS 字幕文件超过安全大小限制，未加载该字幕")
        statusForSelection()
    }

    fun viewport(width: Int, height: Int, storageWidth: Int, storageHeight: Int) {
        if (width <= 0 || height <= 0) return
        val scale = min(1.0, min(2048.0 / maxOf(width, height), sqrt(2_073_600.0 / (width.toDouble() * height))))
        val next = Viewport((width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1), storageWidth.coerceAtLeast(1), storageHeight.coerceAtLeast(1))
        if (next != viewport) { viewport = next; epoch.incrementAndGet() }
        requestFrame(mediaTimeMs)
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) { epoch.incrementAndGet(); postFrame(null, epoch.get()) }
        else requestFrame(mediaTimeMs)
    }

    fun discontinuity() {
        val generation = epoch.incrementAndGet()
        postFrame(null, generation)
        dirty.set(true)
    }

    fun requestFrame(timeMs: Long) {
        // A positive subtitle delay puts its clock before zero at the beginning of video.
        mediaTimeMs = timeMs
        dirty.set(true)
        if (closed.get() || failed || !active || selectedTrack == null || viewport == null) return
        if (!queued.compareAndSet(false, true)) return
        submit {
            try {
                dirty.set(false)
                val track = selectedTrack ?: return@submit
                val size = viewport ?: return@submit
                val generation = epoch.get()
                val time = mediaTimeMs
                if (!active || track.failed || failed) return@submit
                prepareSelected(track)
                if (selectedTrack !== track || !matches(selectedFormat, track.format) || !active || track.handle == 0L) return@submit
                val now = SystemClock.elapsedRealtimeNanos()
                // Bound both native work/allocation and Main delivery. Seek/viewport/selection
                // generations bypass the 30fps budget so a paused seek clears/redraws promptly.
                if (generation == lastRenderEpoch && now - lastRenderNanos < 33_000_000L) return@submit
                val newGeneration = generation != lastRenderEpoch
                lastRenderNanos = now
                lastRenderEpoch = generation
                val imageCount = AssNative.render(engine, track.handle, time, size.width, size.height,
                    size.storageWidth, size.storageHeight)
                if (imageCount != 0) {
                    val bitmap = if (imageCount > 0) Bitmap.createBitmap(size.width, size.height,
                        Bitmap.Config.ARGB_8888).also { AssNative.copyFrame(engine, it) } else null
                    if (bitmap != null) { rendered.incrementAndGet(); lastTime.set(time) }
                    lastBitmap = bitmap
                    postFrame(bitmap, generation)
                } else if (newGeneration) postFrame(lastBitmap, generation)
            } finally {
                queued.set(false)
                if (dirty.get() && !closed.get()) requestFrame(mediaTimeMs)
            }
        }
    }

    /** Only worker, and only the selected ASS descriptor. Replays bounded unselected packets. */
    private fun prepareSelected(track: Track) {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (selectedTrack !== track || !matches(selectedFormat, track.format) || !active || closed.get() || failed) return
        if (track.handle != 0L && (track.fullData == null || track.fullLoaded) && track.pending.isEmpty()) return
        ensureEngine()
        if (selectedTrack !== track || !matches(selectedFormat, track.format) || !active || closed.get() || failed) return
        if (track.handle == 0L) {
            val header = if (track.format.initializationData.size >= 2) track.format.initializationData[1].let { bytes ->
                var end = bytes.size
                while (end > 0 && bytes[end - 1] == 0.toByte()) end--
                bytes.copyOf(end)
            } else byteArrayOf()
            track.handle = AssNative.newTrack(engine, header)
            check(track.handle != 0L)
        }
        if (!track.fullLoaded) track.fullData?.let {
            AssNative.processData(engine, track.handle, it)
            track.fullLoaded = true
        }
        for (packet in track.pending) AssNative.processChunk(track.handle, packet.data, packet.startMs, packet.durationMs)
        track.pending.clear()
        statusForSelection()
    }

    private fun ensureEngine() {
        if (engine != 0L) return
        AssNative.load()
        // Deliberately executed only for a real ASS track, on this worker. No Context/cacheDir IO.
        // 脚本字体缺失或缺字时 libass 回退到默认字体，必须覆盖中文；族名让 TTC 选中简体字形而非首个日文字形。
        fun usable(file: File) = file.isFile && file.length() in 1..32L * 1024 * 1024
        val cjk = CJK_FALLBACKS.firstNotNullOfOrNull { (name, family) ->
            File("/system/fonts", name).takeIf(::usable)?.let { it.absolutePath to family }
        }
        val extras = listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "NotoSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf")
            .map { File("/system/fonts", it) }.filter(::usable).map(File::getAbsolutePath)
        val fallback = cjk?.first ?: extras.firstOrNull() ?: File("/system/fonts").listFiles().orEmpty()
            .firstOrNull { it.extension in listOf("ttf", "otf", "ttc") && usable(it) }
            ?.absolutePath ?: error("No Android fallback font")
        val fonts = (listOfNotNull(cjk?.first) + extras).take(6).ifEmpty { listOf(fallback) }
        engine = AssNative.create(fallback, cjk?.second.orEmpty(), fonts.toTypedArray())
        check(engine != 0L)
        for ((name, bytes) in pendingFonts) {
            AssNative.addFont(engine, name, bytes)
            attachmentFonts.incrementAndGet()
        }
        pendingFonts.clear()
        initialized.set(true)
    }

    private fun <T> callWorker(action: () -> T): T? {
        if (closed.get() || failed) return null
        val task = FutureTask<T> { action() }
        return try {
            worker.execute(task)
            task.get(15, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            // Media3 interrupts its Loader when a subtitle is deselected, sought or released.
            // Abort extraction: returning null would parse this cancelled input as base SSA.
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("ASS subtitle load interrupted").apply { initCause(interrupted) }
        } catch (_: Exception) { task.cancel(false); fail(); null }
        catch (_: LinkageError) { fail(); null }
    }

    private fun submit(action: () -> Unit) {
        if (closed.get() || failed) return
        try { worker.execute { if (!closed.get() && !failed) try { action() }
            catch (_: Exception) { fail() } catch (_: LinkageError) { fail() } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* release won the race */ }
    }

    private fun fail() {
        if (!failure.compareAndSet(false, true)) return
        selectedTrack = null
        val generation = epoch.incrementAndGet()
        postFrame(null, generation)
        statusForSelection()
    }

    private fun statusForSelection() {
        if (!statusPosted.compareAndSet(false, true)) return
        main.post {
            statusPosted.set(false)
            if (closed.get()) return@post
            val value = when { failed -> "ASS 扩展不可用，已回退基础字幕"
                inputError.get() != null -> inputError.get()
                selectedFormat == null -> null
                selectedTrack?.handle?.let { it != 0L } == true -> "ASS 扩展已启用（libass）"
                else -> "正在准备 ASS 扩展" }
            if (lastStatus != value) { lastStatus = value; onStatus(value) }
        }
    }

    private val deliverFrame = object : Runnable {
        override fun run() {
            val latest = pendingFrame.getAndSet(null)
            if (latest != null && !closed.get() && latest.generation == epoch.get()) {
                onFrame(latest.bitmap)
                presentedGeneration.set(if (latest.bitmap == null) -1 else latest.generation)
            }
            framePosted.set(false)
            if (pendingFrame.get() != null && framePosted.compareAndSet(false, true)) main.post(this)
        }
    }
    private fun postFrame(bitmap: Bitmap?, generation: Long) {
        if (closed.get()) return
        // Only the latest unsubmitted frame is retained while Main is blocked. Already submitted
        // bitmaps are never recycled: Android's display list may still be reading them.
        val next = PendingFrame(bitmap, generation)
        var previous: PendingFrame?
        do {
            previous = pendingFrame.get()
            if (generation != epoch.get() || previous != null && previous.generation > generation) return
        } while (!pendingFrame.compareAndSet(previous, next))
        if (previous != null) replacedFrames.incrementAndGet()
        if (framePosted.compareAndSet(false, true)) main.post(deliverFrame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        selectedTrack = null
        epoch.incrementAndGet()
        pendingFrame.set(null)
        // Previously queued tasks first check closed; only the final task touches native handles.
        worker.execute {
            synchronized(trackLock) { tracks.clear() }
            pendingFonts.clear(); lastBitmap = null
            // Native destroys renderer first, then its owned tracks, then ASS_Library.
            if (engine != 0L) { AssNative.destroy(engine); engine = 0 }
        }
        worker.shutdown()
    }

    companion object {
        /** 系统中文字体文件与其简体中文字体族名，按厂商常见程度排列。 */
        private val CJK_FALLBACKS = listOf(
            "NotoSansCJK-Regular.ttc" to "Noto Sans CJK SC", "NotoSansCJK-VF.ttc" to "Noto Sans CJK SC",
            "NotoSansCJK-VF.ttf" to "Noto Sans CJK SC", "NotoSansSC-Regular.otf" to "Noto Sans SC",
            "DroidSansFallbackFull.ttf" to "Droid Sans Fallback", "DroidSansFallback.ttf" to "Droid Sans Fallback",
        )
        const val MAX_TEXT = 8 * 1024 * 1024
        const val MAX_FONT = 8 * 1024 * 1024
        const val MAX_FONTS = 32 * 1024 * 1024
        const val MAX_EVENTS = 8L * 1024 * 1024
        fun isAss(format: Format) = format.sampleMimeType == MimeTypes.TEXT_SSA ||
            (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES && format.codecs == MimeTypes.TEXT_SSA)
        private fun normalizedId(format: Format) = format.id?.substringAfterLast(':')
        private fun matches(selected: Format?, original: Format): Boolean {
            if (selected == null || !isAss(selected)) return false
            return normalizedId(selected) == normalizedId(original) && selected.language == original.language &&
                selected.label == original.label && (selected.initializationData.isEmpty() ||
                selected.initializationData.size == original.initializationData.size &&
                selected.initializationData.indices.all { selected.initializationData[it].contentEquals(original.initializationData[it]) })
        }
        private fun trackKey(format: Format) = buildString {
            append(normalizedId(format)); append('|'); append(format.language); append('|'); append(format.label)
            for (bytes in format.initializationData) { append('|'); append(bytes.size); append(':'); append(bytes.contentHashCode()) }
        }
    }
}

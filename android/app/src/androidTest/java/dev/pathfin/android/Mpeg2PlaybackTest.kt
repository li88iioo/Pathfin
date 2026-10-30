package dev.pathfin.android

import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.playback.PathfinRenderersFactory
import dev.pathfin.ffmpeg.video.NativeVideoDecoder
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真提取、音视频解码和 Surface 像素；精确模拟系统缺少 MPEG-2 硬解。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class Mpeg2PlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeAvailabilityAcceptsCompiledMpeg2AndRejectsAudioUnknownAndNull() {
        assertTrue("MPEG-2 必须实际编入视频解码库", NativeVideoDecoder.isCodecAvailable("mpeg2video"))
        assertFalse(NativeVideoDecoder.isCodecAvailable("aac"))
        assertFalse(NativeVideoDecoder.isCodecAvailable("unknown-pathfin-codec"))
        assertFalse(NativeVideoDecoder.isCodecAvailable(null))
    }

    @Test fun matroskaWithoutSystemMpeg2DecodesMovingVideoAndAudioAndSeeks() = play("interlaced.mkv")
    @Test fun transportStreamWithoutSystemMpeg2DecodesMovingVideoAndAudioAndSeeks() = play("interlaced.ts")
    @Test fun programStreamWithoutSystemMpeg2DecodesMovingVideoAndAudioAndSeeks() = play("interlaced.mpg")
    @Test fun hlsWithoutSystemMpeg2DecodesMovingVideoAndAudioAndSeeks() {
        play("interlaced.m3u8")
        InstrumentationRegistry.getArguments().getString("mpeg2LiveUrl")?.takeIf { it.isNotBlank() }?.let { play("live-hls", it) }
    }

    private fun play(name: String, liveUrl: String? = null) {
        val context = instrumentation.targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val live = liveUrl != null
        var sourceDirectory: File? = null
        var width = 192
        var height = 128
        val item = if (liveUrl != null) MediaItem.Builder().setUri(liveUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build() else {
            val directory = "native-video/mpeg2/"
            val manifest = JSONObject(instrumentation.context.assets.open(directory + "manifest.json").bufferedReader().use { it.readText() })
            val fixtures = manifest.getJSONArray("fixtures")
            val entries = (0 until fixtures.length()).map(fixtures::getJSONObject)
            val fixture = entries.single { it.getString("file") == name }
            assertEquals(150, fixture.getInt("interlacedFrames"))
            assertTrue(fixture.getInt("bFrames") > 0)
            width = fixture.getInt("width")
            height = fixture.getInt("height")
            val local = File(context.cacheDir, "mpeg2-${UUID.randomUUID()}").apply { check(mkdir()) }
            sourceDirectory = local
            val needed = if (name.endsWith(".m3u8")) setOf(name, "interlaced.ts") else setOf(name)
            for (entry in entries.filter { it.getString("file") in needed }) {
                val bytes = instrumentation.context.assets.open(directory + entry.getString("file")).use { it.readBytes() }
                assertEquals(entry.getInt("bytes"), bytes.size)
                assertEquals(entry.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                File(local, entry.getString("file")).writeBytes(bytes)
            }
            MediaItem.fromUri(Uri.fromFile(File(local, name)))
        }
        val thread = HandlerThread("Pathfin-mpeg2-pixels").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val images = AtomicInteger()
        val coloredImages = AtomicInteger()
        val signatures = ConcurrentHashMap.newKeySet<Int>()
        val imageFailure = AtomicReference<Throwable?>()
        var player: ExoPlayer? = null
        var videoDecoder: String? = null
        var audioDecoder: String? = null
        var audioCounters: DecoderCounters? = null
        var firstFrame = false
        try {
            reader.setOnImageAvailableListener({ current ->
                try {
                    current.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        var minimum = 255
                        var maximum = 0
                        var signature = 1
                        for (y in 8 until image.height step 8) for (x in 8 until image.width step 8) {
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            for (channel in 0..2) {
                                val value = buffer.get(offset + channel).toInt() and 255
                                minimum = minOf(minimum, value)
                                maximum = maxOf(maximum, value)
                                signature = 31 * signature + value
                            }
                        }
                        images.incrementAndGet()
                        if (maximum - minimum > 40) coloredImages.incrementAndGet()
                        signatures.add(signature)
                    }
                } catch (error: Throwable) { imageFailure.compareAndSet(null, error) }
            }, Handler(thread.looper))
            main {
                val selector = if (live) MediaCodecSelector.DEFAULT else MediaCodecSelector { mime, secure, tunneling ->
                    if (mime == MimeTypes.VIDEO_MPEG2) emptyList()
                    else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                }
                val renderers = PathfinRenderersFactory(context, hardwareVideo = true)
                    .setMediaCodecSelector(selector).setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                player = ExoPlayer.Builder(context, renderers).build().also { current ->
                    current.addAnalyticsListener(object : AnalyticsListener {
                        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestamp: Long, duration: Long) { videoDecoder = name }
                        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestamp: Long, duration: Long) { audioDecoder = name }
                        override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, counters: DecoderCounters) { audioCounters = counters }
                    })
                    current.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { firstFrame = true } })
                    current.volume = 0f
                    current.setVideoSurface(reader.surface)
                    current.setMediaItem(item)
                    current.prepare()
                    current.play()
                }
            }
            val current = checkNotNull(player)
            // 直播 currentPosition 相对滑动窗口；换算到同一 Period 后才能验证暂停/推进。
            fun mediaPosition() = current.currentPosition + current.currentTimeline
                .getWindow(current.currentMediaItemIndex, Timeline.Window()).positionInFirstPeriodMs
            fun healthy() {
                imageFailure.get()?.let { throw AssertionError("实际 Surface 读帧失败", it) }
                main { current.playerError }?.let { throw AssertionError("$name 实际播放失败：${it.errorCodeName}", if (live) null else it) }
            }
            await("$name 必须播放实际 MPEG-2 画面及音频") {
                healthy()
                main {
                    firstFrame && current.isPlaying && current.currentPosition > 500 &&
                        videoDecoder?.let { live || it.startsWith("pathfin.ffmpeg.mpeg2video.") } == true && audioDecoder != null &&
                        audioCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount > 0 } == true
                } && coloredImages.get() >= 3 && signatures.size >= 3
            }
            val playingFrom = main { mediaPosition() }
            val framesFrom = images.get()
            await("实际画面与播放时钟必须持续推进") {
                healthy()
                main { current.isPlaying && mediaPosition() > playingFrom + 300 } && images.get() > framesFrom + 3
            }
            main { current.pause() }
            await("暂停后实际播放时钟必须停止") { healthy(); main { !current.isPlaying } }
            val paused = main { mediaPosition() }
            SystemClock.sleep(250)
            val afterPause = main { mediaPosition() }
            assertTrue("暂停不能继续推进：$paused -> $afterPause", abs(afterPause - paused) < 80)
            val beforeResume = images.get()
            main { if (!live) current.seekTo(3_500); current.play() }
            await(if (live) "直播恢复后必须继续输出新画面和播放时钟" else "跳转后必须恢复新画面和播放时钟") {
                healthy()
                main { current.isPlaying && (if (live) mediaPosition() > paused + 300 else current.currentPosition > 3_800) } && images.get() > beforeResume + 3
            }
            if (!live) await("MPEG-2 B 帧尾部必须正常结束") { healthy(); main { current.playbackState == Player.STATE_ENDED } }
            main {
                assertTrue(if (live) videoDecoder!!.isNotBlank() else videoDecoder!!.startsWith("pathfin.ffmpeg.mpeg2video."))
                assertTrue("必须实际选择音频解码器", audioDecoder!!.isNotBlank())
                assertEquals(MimeTypes.VIDEO_MPEG2, current.videoFormat?.sampleMimeType)
                assertEquals("MP2 双声道必须实际解码", 2, current.audioFormat?.channelCount)
                assertEquals(48_000, current.audioFormat?.sampleRate)
                if (live) assertTrue("必须保留真实直播时间轴", current.isCurrentMediaItemLive)
                else assertTrue("结束位置=${current.currentPosition}", current.currentPosition >= 5_500)
            }
        } finally {
            main {
                player?.let { current ->
                    audioCounters?.ensureUpdated()
                    val formats = current.currentTracks.groups.joinToString { group ->
                        (0 until group.length).joinToString { index ->
                            "${group.getTrackFormat(index).sampleMimeType}:selected=${group.isTrackSelected(index)}:supported=${group.isTrackSupported(index)}"
                        }
                    }
                    val diagnostic = "container=$name video=$videoDecoder audio=$audioDecoder videoMime=${current.videoFormat?.sampleMimeType} " +
                        "videoSize=${current.videoFormat?.width}x${current.videoFormat?.height} " +
                        "audioMime=${current.audioFormat?.sampleMimeType} position=${current.currentPosition} " +
                        "audioBuffers=${audioCounters?.renderedOutputBufferCount} frames=${images.get()} pixelSignatures=${signatures.size} tracks=[$formats]"
                    Log.i("PathfinMpeg2", diagnostic)
                    println("PathfinMpeg2: $diagnostic")
                    current.release()
                    assertTrue(current.isReleased)
                }
            }
            reader.setOnImageAvailableListener(null, null)
            thread.quitSafely()
            thread.join(3_000)
            reader.close()
            assertFalse("像素读取线程必须结束", thread.isAlive)
            sourceDirectory?.let { assertTrue(it.deleteRecursively()) }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        fail(message)
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

package dev.pathfin.android

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JsonMapping
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PathfinRenderersFactory
import dev.pathfin.android.playback.PathfinTrackSelector
import dev.pathfin.android.playback.SubtitleSeekPrerollExtractorsFactory
import dev.pathfin.android.playback.allowsDolbyVisionProfile7BaseLayer
import dev.pathfin.android.playback.hasOnlySeparateProfile7EnhancementTracks
import dev.pathfin.ffmpeg.video.NativeVideoDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实 FFmpeg 队列、GLES Surface 输出和基础层首帧。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NativeVideoDecodeTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Test(timeout = 45_000)
    fun legacyContainersReachTheSurfaceThroughFfmpegWithoutSystemDecoders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((asset, codec) in mapOf("legacy-xvid.avi" to "mpeg4", "legacy-mp42.avi" to "msmpeg4v2",
            "legacy-mp43.avi" to "msmpeg4", "legacy-h263.3gp" to "h263")) {
            val file = File.createTempFile("pathfin-legacy-", ".${asset.substringAfterLast('.')}", context.cacheDir)
            try {
                assets.open("native-video/$asset").use { input -> file.outputStream().use(input::copyTo) }
                assertNativeFrames(file, codec)
            } finally { file.delete() }
        }
        // 单独提供的 WVC1/MKV 兼容样本不随测试包分发。
        InstrumentationRegistry.getArguments().getString("pathfinLegacyVc1File")?.let {
            assertNativeFrames(File(it), "vc1")
        }
    }

    @Test(timeout = 35_000)
    fun vvcMp4RendersBeforeAndAfterSeekAtOneAndOnePointSevenFiveTimes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val external = InstrumentationRegistry.getArguments().getString("pathfinVvcFile")
        val file = external?.let(::File) ?: File.createTempFile("pathfin-vvc-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            if (external == null) {
                val fixture = JSONObject(asset("manifest.json").toString(Charsets.UTF_8)).getJSONObject("vvc")
                val bytes = asset(fixture.getString("file"))
                assertEquals(fixture.getInt("bytes"), bytes.size)
                assertEquals(fixture.getString("sha256"), MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
                file.writeBytes(bytes)
            }
            assertNativeFrames(file, "vvc", verifyPlayback = { player, reader ->
                var seekMs = 0L
                instrumentation.runOnMainSync {
                    val format = checkNotNull(player.videoFormat)
                    assertEquals("MP4 中的视频轨须由 Media3 识别为 VVC", MimeTypes.VIDEO_H266, format.sampleMimeType)
                    assertEquals(10, format.colorInfo?.lumaBitdepth)
                    assertTrue("VVC 测试片必须可跳转", player.isCurrentMediaItemSeekable)
                    seekMs = player.duration / 2
                    assertTrue(seekMs >= 2_000)
                    Log.i(TAG, "VVC input ${format.width}x${format.height}, durationMs=${player.duration}")
                }
                assertVvcPlaybackWindow(player, reader, speed = 1f)
                assertVvcPlaybackWindow(player, reader, speed = 1.75f, seekMs = seekMs)
            })
        } finally {
            if (external == null) file.delete()
        }
    }

    private fun assertVvcPlaybackWindow(player: ExoPlayer, reader: ImageReader, speed: Float, seekMs: Long? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var startPosition = 0L
        var startRendered = 0
        var startDropped = 0
        instrumentation.runOnMainSync {
            val counters = checkNotNull(player.videoDecoderCounters)
            counters.ensureUpdated()
            startRendered = counters.renderedOutputBufferCount
            startDropped = counters.droppedBufferCount
            if (seekMs != null) player.seekTo(seekMs)
            startPosition = seekMs ?: player.currentPosition
            player.setPlaybackSpeed(speed)
            assertEquals(speed, player.playbackParameters.speed, 0f)
        }
        val started = SystemClock.elapsedRealtime()
        val targetPosition = startPosition + 1_200
        var position = startPosition
        var rendered = 0
        var dropped = 0
        var images = 0
        while (SystemClock.elapsedRealtime() - started < 8_000) {
            awaitImage(reader).use { image -> assertPattern(image, "vvc") }
            images++
            instrumentation.runOnMainSync {
                player.playerError?.let { throw AssertionError("VVC ${speed}x 播放失败", it) }
                position = player.currentPosition
                val counters = checkNotNull(player.videoDecoderCounters)
                counters.ensureUpdated()
                rendered = counters.renderedOutputBufferCount - startRendered
                dropped = counters.droppedBufferCount - startDropped
            }
            if (position >= targetPosition && rendered >= 3 && images >= 3) break
        }
        Log.i(TAG, "VVC ${speed}x: positionMs=$startPosition->$position, elapsedMs=${SystemClock.elapsedRealtime() - started}, " +
            "rendered=$rendered, dropped=$dropped, sampledImages=$images")
        assertTrue("VVC ${speed}x 必须继续前进并输出真实画面", position >= targetPosition && rendered >= 3 && images >= 3)
    }

    @Test(timeout = 15_000)
    fun profile7FelMatroskaRendersTheHdr10BaseLayer() {
        // 不在仓库分发外部片源。这个 FATE 样本含一帧原始 UHD HDR10 BL、FEL 和 RPU。
        val fixture = InstrumentationRegistry.getArguments().getString("pathfinDolbyVisionP7File")
        assumeTrue("需提供经核实的 P7 FEL Matroska 样本", fixture != null)
        assertNativeFrames(File(checkNotNull(fixture)), "hevc", frames = 1, expectedDolbyProfile = "07")
    }

    @Test(timeout = 15_000)
    fun separateProfile7EnhancementTrackKeepsTheIndependentMp4BaseLayer() {
        val fixture = InstrumentationRegistry.getArguments().getString("pathfinDolbyVisionP7SeparateFile")
        assumeTrue("需提供独立 HEVC 基础轨和 EL-only P7 轨的 MP4 样本", fixture != null)
        val file = File(checkNotNull(fixture))
        val source = JsonMapping.mediaSources(JSONArray("""[{"Id":"p7","Container":"mp4","MediaStreams":[
            {"Type":"Video","Codec":"hevc","Index":0},
            {"Type":"Video","Codec":"hevc","Index":1,"DvProfile":7,"DvBlSignalCompatibilityId":6,"BlPresentFlag":0}
        ]}]""")).single()
        val playback = PlaybackRequest(dev.pathfin.android.domain.MediaItem("p7", "P7 fixture", "Movie"),
            PlaybackInfo(listOf(source), "test"), source, ResolvedPlayback(file.toURI().toString(), emptyMap(),
                PlayMethod.DIRECT_PLAY, source.id, false), 0, Session(ServerConfig("https://fixture.invalid"),
                "test", "user", "Fixture", "device"))
        assertNativeFrames(file, "hevc", frames = 1, expectedDolbyProfile = "07", separateP7 = playback)
    }

    private fun assertNativeFrames(file: File, codec: String, frames: Int = 3, expectedDolbyProfile: String? = null,
        separateP7: PlaybackRequest? = null, verifyPlayback: ((ExoPlayer, ImageReader) -> Unit)? = null) {
        assertTrue("测试样本必须可读：${file.name}", file.isFile && file.canRead())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val decoder = AtomicReference<String>()
        val dolbyProfile = AtomicReference<String>()
        val error = AtomicReference<PlaybackException>()
        val firstFrame = CountDownLatch(1)
        ImageReader.newInstance(176, 144, PixelFormat.RGBA_8888, 3).use { reader ->
            var player: ExoPlayer? = null
            try {
                instrumentation.runOnMainSync {
                    val renderers = PathfinRenderersFactory(context, hardwareVideo = true)
                        .setMediaCodecSelector { _, _, _ -> emptyList() }
                    val extractors = SubtitleSeekPrerollExtractorsFactory(supportsDolbyVision = { format ->
                        dolbyProfile.set(format.codecs?.split('.')?.getOrNull(1))
                        separateP7?.allowsDolbyVisionProfile7BaseLayer() == false
                    })
                    val builder = ExoPlayer.Builder(context, renderers)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
                    separateP7?.let { playback -> builder.setTrackSelector(PathfinTrackSelector(context, playback.source,
                        separateProfile7EnhancementOnly = playback.hasOnlySeparateProfile7EnhancementTracks())) }
                    player = builder.build().apply {
                        addAnalyticsListener(object : AnalyticsListener {
                            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String,
                                initializedTimestampMs: Long, initializationDurationMs: Long) { decoder.set(name) }
                        })
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { firstFrame.countDown() }
                            override fun onPlayerError(failure: PlaybackException) {
                                error.set(failure)
                                firstFrame.countDown()
                            }
                        })
                        setVideoSurface(reader.surface)
                        setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                        prepare()
                        play()
                    }
                }
                assertTrue("$codec 未输出首帧", firstFrame.await(8, TimeUnit.SECONDS))
                error.get()?.let { throw AssertionError("$codec 播放失败", it) }
                assertEquals("必须实际使用编入 APK 的软件视频解码器", "pathfin.ffmpeg.$codec.gpu-sdr", decoder.get())
                if (expectedDolbyProfile != null) assertEquals("提取的轨确实是所声明的杜比档位", expectedDolbyProfile, dolbyProfile.get())
                if (separateP7 != null) instrumentation.runOnMainSync {
                    assertEquals("选择独立的 HEVC 基础轨（track ID 1），不选 EL-only 轨（ID 2）", "1", player?.videoFormat?.id)
                }
                repeat(frames) {
                    awaitImage(reader).use { image ->
                        if (separateP7 != null) return@use // 外部双轨样本是黑帧，收到真实 Surface 缓冲即可。
                        assertPattern(image, codec)
                    }
                }
                error.get()?.let { throw AssertionError("$codec 播放失败", it) }
                Log.i(TAG, "$codec decoded and presented $frames RGBA frames")
                verifyPlayback?.invoke(checkNotNull(player), reader)
            } finally { instrumentation.runOnMainSync { player?.release() } }
        }
    }

    private fun assertPattern(image: Image, codec: String) {
        val plane = image.planes.single()
        val pixels = plane.buffer
        var min = 255
        var max = 0
        for (y in 4 until image.height step 8) for (x in 4 until image.width step 8) {
            val offset = pixels.position() + y * plane.rowStride + x * plane.pixelStride
            for (channel in 0..2) {
                val value = pixels.get(offset + channel).toInt() and 255
                min = minOf(min, value)
                max = maxOf(max, value)
            }
        }
        assertTrue("$codec 输出必须包含真实图案", max - min > 40)
    }

    @Test(timeout = 20_000)
    fun hi10pReordersAllBFramesAndDrainsTheDelayedTailBeforeEos() {
        val fixture = fixture("hi10p-bframes")
        // 固定 Hi10P 真实码流，直接实例化 Native，不能由设备的普通 AVC 硬解冒充通过。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hardware = MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
            .filter { it.hardwareAccelerated }.map { "${it.name}=${it.isFormatSupported(context, fixture.format)}" }
        Log.i(TAG, "Hi10P hardware format support=$hardware; test uses FFmpeg directly")
        val decoder = NativeVideoDecoder(fixture.format)
        try {
            assertTrue(decoder.name.startsWith("pathfin.ffmpeg.h264."))
            assertTrue(decoder.name.endsWith(".gpu-sdr"))
            val evidence = decodeToEnd(decoder, fixture)
            assertTrue("B 帧不能在 EOS 前已全部输出；夹具必须覆盖延迟尾帧", evidence.framesBeforeEos in 1 until fixture.times.size)
            assertEquals("最后呈现帧不能被 EOS 吞掉", fixture.times.last(), evidence.times.last())
        } finally { decoder.release() }
    }

    @Test(timeout = 20_000)
    fun flushDiscardsQueuedOldFramesAndRestartsAtZeroWithACompleteNewEos() {
        val fixture = fixture("hi10p-bframes")
        val decoder = NativeVideoDecoder(fixture.format)
        try {
            val old = primeFirstFrame(decoder, fixture, offsetUs = 10_000_000)
            try {
                assertTrue("预置旧一代帧确实进入真实解码器", old.timeUs >= 10_000_000)
                // 留有异步工作和未消费输出，不能只测试已完全结束后的空 flush。
                decoder.flush()
            } finally { old.release() }
            val evidence = decodeToEnd(decoder, fixture)
            assertEquals(0L, evidence.times.first())
            assertTrue("flush 后不得泄漏旧一代的 10 秒时戳", evidence.times.all { it < 2_000_000 })
        } finally { decoder.release() }
    }

    @Test(timeout = 30_000)
    fun tenBitColorGridMatchesCpuSrgbAcrossSurfaceReplacementAndBack() {
        val fixture = fixture("hi10p-color-grid")
        ImageReader.newInstance(fixture.width, fixture.height, PixelFormat.RGBA_8888, 2).use { first ->
            ImageReader.newInstance(fixture.width, fixture.height, PixelFormat.RGBA_8888, 2).use { second ->
                val decoder = NativeVideoDecoder(fixture.format)
                try {
                    val evidence = decodeToEnd(decoder, fixture) { output, index ->
                        val reader = if (index in 8..15) second else first
                        val previous = if (reader === first) second else first
                        decoder.renderToSurface(output, reader.surface)
                        awaitImage(reader).use { image -> assertReferencePixels(image, fixture, index) }
                        previous.acquireNextImage()?.use {
                            fail("更换 Surface 后不得继续向旧 Surface 输出帧，frame=$index")
                        }
                    }
                    assertEquals("两个 Surface 与返回首个 Surface 都必须有真实帧", 24, evidence.times.size)
                } finally { decoder.release() }
            }
        }
    }

    @Test(timeout = 30_000)
    fun theSameJavaSurfaceRebindsWhenItsNativeWindowChangesAndReturns() {
        val fixture = fixture("hi10p-color-grid")
        ImageReader.newInstance(fixture.width, fixture.height, PixelFormat.RGBA_8888, 2).use { first ->
            ImageReader.newInstance(fixture.width, fixture.height, PixelFormat.RGBA_8888, 2).use { second ->
                // SurfaceView 复用 Java Surface，仅替换内部窗口。公开 Parcel API
                // 复现同一生命周期，不能用换一个 Java Surface 对象掩盖旧窗口缓存。
                val surface = copySurfaceThroughParcel(first.surface)
                try {
                    val decoder = NativeVideoDecoder(fixture.format)
                    try {
                        val evidence = decodeToEnd(decoder, fixture) { output, index ->
                            val reader = if (index in 8..15) second else first
                            val previous = if (reader === first) second else first
                            if (index == 8 || index == 16) {
                                assertSame(surface, copySurfaceThroughParcel(reader.surface, surface))
                            }
                            decoder.renderToSurface(output, surface)
                            awaitImage(reader).use { image -> assertReferencePixels(image, fixture, index) }
                            previous.acquireNextImage()?.use {
                                fail("同一 Surface 换窗后不得向旧窗口输出，frame=$index")
                            }
                        }
                        assertEquals("同一 Java Surface 换窗及返回后都要完整呈现", 24, evidence.times.size)
                    } finally { decoder.release() }
                } finally { surface.release() }
            }
        }
    }

    @Test(timeout = 30_000)
    fun releasingWithHeldAndQueuedFramesIsIdempotentAndTheNextDecoderStillRenders() {
        val fixture = fixture("hi10p-color-grid")
        val reader = ImageReader.newInstance(fixture.width, fixture.height, PixelFormat.RGBA_8888, 2)
        try {
            // 在未 EOS、输出池仍持有 AVFrame 时释放，覆盖工作线程等待缓冲的关闭路径。
            val decoder = NativeVideoDecoder(fixture.format)
            var held: VideoDecoderOutputBuffer? = null
            try {
                held = primeFirstFrame(decoder, fixture)
                assertTrue(held.decoderPrivate != 0L)
                decoder.renderToSurface(held, reader.surface)
                awaitImage(reader).use { assertReferencePixels(it, fixture, 0) }
                decoder.release()
                decoder.release()
                assertEquals("关闭时必须回收仍由调用方持有的 native AVFrame", 0L, held.decoderPrivate)
                assertThrows(IllegalStateException::class.java) { decoder.dequeueInputBuffer() }
            } finally {
                decoder.release()
                held?.release()
            }
            // 不能只断言 release 不抛异常；复用同一真实 Surface 再解码并核对色彩。
            val next = NativeVideoDecoder(fixture.format)
            try {
                decodeToEnd(next, fixture) { output, index ->
                    if (index == 0 || index == fixture.times.lastIndex) {
                        next.renderToSurface(output, reader.surface)
                        awaitImage(reader).use { assertReferencePixels(it, fixture, index) }
                    }
                }
            } finally {
                next.release()
                next.release()
            }
        } finally { reader.close() }
    }

    private data class Packet(val bytes: ByteArray, val timeUs: Long)
    private data class Fixture(
        val id: String,
        val width: Int,
        val height: Int,
        val format: Format,
        val packets: List<Packet>,
        val times: List<Long>,
        val reference: JSONArray,
        val radius: Int,
    )
    private data class Evidence(val times: List<Long>, val framesBeforeEos: Int)

    private fun fixture(id: String): Fixture {
        assertTrue("必须实际加载 FFmpeg JNI", FfmpegLibrary.isAvailable())
        assertTrue("H264 视频解码器必须真实编入共享库", NativeVideoDecoder.isCodecAvailable("h264"))
        val manifest = JSONObject(asset("manifest.json").toString(Charsets.UTF_8))
        val items = manifest.getJSONArray("fixtures")
        val item = (0 until items.length()).map(items::getJSONObject).single { it.getString("id") == id }
        assertEquals("High 10", item.getString("profile"))
        assertEquals("yuv420p10le", item.getString("pixelFormat"))
        assertEquals(10, item.getInt("bitDepth"))
        assertTrue(item.getInt("bFrames") > 0)
        val files = item.getJSONObject("files")
        for (name in files.keys()) {
            val body = asset(name)
            val expected = files.getJSONObject(name)
            assertEquals("$name 大小", expected.getInt("bytes"), body.size)
            assertEquals("$name SHA256", expected.getString("sha256"), MessageDigest.getInstance("SHA-256")
                .digest(body).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        val input = ByteBuffer.wrap(asset("$id.packets")).order(ByteOrder.LITTLE_ENDIAN)
        val packets = buildList {
            while (input.hasRemaining()) {
                val size = input.int
                val timeUs = input.long
                require(size in 1..input.remaining())
                add(Packet(ByteArray(size).also(input::get), timeUs))
            }
        }
        val reference = JSONObject(asset("$id.reference.json").toString(Charsets.UTF_8))
        val times = reference.getJSONArray("frameTimesUs").let { values ->
            List(values.length(), values::getLong)
        }
        assertEquals(24, times.size)
        assertEquals(times, packets.map { it.timeUs }.sorted())
        assertNotEquals("真实夹具必须在解码顺序中重排 PTS", times, packets.map { it.timeUs })
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs(item.getString("codecs"))
            .setWidth(item.getInt("width")).setHeight(item.getInt("height"))
            .setFrameRate(item.getInt("frameRate").toFloat())
            .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709)
                .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_SDR)
                .setLumaBitdepth(10).setChromaBitdepth(10).build())
            .setInitializationData(listOf(asset("$id.init"))).build()
        return Fixture(id, format.width, format.height, format, packets, times,
            reference.getJSONArray("frames"), item.getJSONObject("cpuReference").getInt("sampleRadius"))
    }

    private fun asset(name: String) = assets.open("native-video/$name").use { it.readBytes() }

    private fun copySurfaceThroughParcel(source: Surface, target: Surface? = null): Surface {
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            target?.apply { readFromParcel(parcel) } ?: Surface.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun queuePacket(decoder: NativeVideoDecoder, packet: Packet, offsetUs: Long = 0): Boolean {
        val input = decoder.dequeueInputBuffer() ?: return false
        input.ensureSpaceForWrite(packet.bytes.size)
        requireNotNull(input.data).put(packet.bytes)
        input.timeUs = packet.timeUs + offsetUs
        input.flip()
        decoder.queueInputBuffer(input)
        return true
    }

    private fun primeFirstFrame(decoder: NativeVideoDecoder, fixture: Fixture, offsetUs: Long = 0): VideoDecoderOutputBuffer {
        var next = 0
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            while (next < fixture.packets.size && queuePacket(decoder, fixture.packets[next], offsetUs)) next++
            decoder.dequeueOutputBuffer()?.let { output ->
                assertFalse(output.isEndOfStream)
                assertEquals(fixture.times.first() + offsetUs, output.timeUs)
                return output
            }
            SystemClock.sleep(1)
        }
        throw AssertionError("${fixture.id} 未产生首帧，已投递 $next 个真实包")
    }

    private fun decodeToEnd(
        decoder: NativeVideoDecoder,
        fixture: Fixture,
        onFrame: (VideoDecoderOutputBuffer, Int) -> Unit = { _, _ -> },
    ): Evidence {
        var next = 0
        var queuedEos = false
        var ended = false
        var framesBeforeEos = -1
        var lastActivity = SystemClock.elapsedRealtime()
        val start = lastActivity
        val times = mutableListOf<Long>()
        while (!ended && SystemClock.elapsedRealtime() - start < 10_000) {
            while (next < fixture.packets.size && queuePacket(decoder, fixture.packets[next])) {
                next++
                lastActivity = SystemClock.elapsedRealtime()
            }
            while (true) {
                val output = decoder.dequeueOutputBuffer() ?: break
                try {
                    if (output.isEndOfStream) {
                        assertTrue("EOS 不能在输入 EOS 前自行出现", queuedEos)
                        ended = true
                    } else {
                        assertFalse("EOS 后不能追加视频帧", ended)
                        assertEquals(fixture.width, output.width)
                        assertEquals(fixture.height, output.height)
                        assertTrue("每帧必须拥有真实 native AVFrame", output.decoderPrivate != 0L)
                        assertEquals(C.VIDEO_OUTPUT_MODE_SURFACE_YUV, output.mode)
                        onFrame(output, times.size)
                        times += output.timeUs
                    }
                } finally { output.release() }
                lastActivity = SystemClock.elapsedRealtime()
            }
            if (next == fixture.packets.size && !queuedEos && SystemClock.elapsedRealtime() - lastActivity >= 150) {
                decoder.dequeueInputBuffer()?.let { input ->
                    framesBeforeEos = times.size
                    input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    decoder.queueInputBuffer(input)
                    queuedEos = true
                }
            }
            if (!ended) SystemClock.sleep(1)
        }
        assertTrue("${fixture.id} 必须在有限时间内完整 drain 到 EOS，已输出 ${times.size} 帧", ended)
        assertEquals("全部呈现时戳必须与 CPU 真解码一致，不能丢尾帧/重复帧", fixture.times, times)
        assertNull("完整 EOS 后不能再留额外帧", decoder.dequeueOutputBuffer())
        Log.i(TAG, "${fixture.id}: frames=${times.size}, beforeEOS=$framesBeforeEos, elapsedMs=${SystemClock.elapsedRealtime() - start}")
        return Evidence(times, framesBeforeEos)
    }

    private fun awaitImage(reader: ImageReader): Image {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireNextImage()?.let { return it }
            SystemClock.sleep(1)
        }
        throw AssertionError("GLES 必须向当前 RGBA8888 Surface 提交实际像素")
    }

    private fun assertReferencePixels(image: Image, fixture: Fixture, frame: Int) {
        assertEquals(PixelFormat.RGBA_8888, image.format)
        assertEquals(fixture.width, image.width)
        assertEquals(fixture.height, image.height)
        assertEquals(1, image.planes.size)
        val plane = image.planes.single()
        assertTrue("像素跨度必须容纳 RGBA", plane.pixelStride >= 4)
        assertTrue("行跨度必须覆盖实际宽度", plane.rowStride >= fixture.width * plane.pixelStride)
        val pixels = plane.buffer.duplicate()
        val origin = pixels.position()
        val samples = fixture.reference.getJSONObject(frame).getJSONArray("samples")
        var totalError = 0.0
        var channels = 0
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            val x = sample.getInt("x")
            val y = sample.getInt("y")
            val sums = IntArray(4)
            var count = 0
            for (dy in -fixture.radius..fixture.radius) for (dx in -fixture.radius..fixture.radius) {
                val offset = origin + (y + dy) * plane.rowStride + (x + dx) * plane.pixelStride
                for (channel in 0..3) sums[channel] += pixels.get(offset + channel).toInt() and 255
                count++
            }
            val expected = sample.getJSONArray("rgb")
            for (channel in 0..2) {
                val actual = sums[channel].toDouble() / count
                val error = abs(actual - expected.getDouble(channel))
                assertTrue("${fixture.id} frame=$frame sample=($x,$y) channel=$channel: GPU=$actual CPU=${expected.getDouble(channel)}",
                    error <= 12.0)
                totalError += error
                channels++
            }
            assertEquals("输出应为不透明实际视频像素", 255.0, sums[3].toDouble() / count, 1.0)
        }
        assertTrue("${fixture.id} frame=$frame 平均颜色误差过大：${totalError / channels}", totalError / channels <= 5.0)
    }

    companion object { private const val TAG = "NativeVideoDecodeTest" }
}

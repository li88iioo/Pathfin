package dev.pathfin.android

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.playback.DevicePlaybackCapabilities
import dev.pathfin.android.playback.PathfinRenderersFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 合成片源→真实解码器→SurfaceView；验证颜色元数据与非黑画面，不冒充亮度/色准仪测量。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HdrPlaybackOutputTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun sdrHdr10AndHlgReachTheRealSurfaceWithTheirColorTransferPreserved() {
        assumeTrue("需要HEVC Main10解码设备执行真实HDR输出回归",
            (DevicePlaybackCapabilities.current.video["hevc"]?.maxBitDepth ?: 0) >= 10)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.toList()
        val results = JSONArray()
        val report = JSONObject().put("displayHdrTypes", hdrTypes?.let(::JSONArray))
            .put("device", android.os.Build.MODEL).put("cases", results)
        val reportFile = File(context.cacheDir, "hdr-output-audit.json")
        reportFile.writeText(report.toString())
        lateinit var view: PlayerView
        compose.setContent {
            AndroidView(factory = { PlayerView(it).also { playerView -> view = playerView; playerView.useController = false } },
                modifier = Modifier.fillMaxSize())
        }
        for ((name, transfer) in listOf("sdr" to C.COLOR_TRANSFER_SDR,
            "hdr10" to C.COLOR_TRANSFER_ST2084, "hlg" to C.COLOR_TRANSFER_HLG)) {
            val file = File.createTempFile("pathfin-hdr-$name-", ".mp4", context.cacheDir)
            instrumentation.context.assets.open("hdr-playback/$name.mp4").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val frame = CountDownLatch(1)
            val ended = CountDownLatch(1)
            val error = AtomicReference<PlaybackException>()
            val decoder = AtomicReference<String>()
            val dropped = AtomicInteger()
            val started = SystemClock.elapsedRealtime()
            lateinit var player: ExoPlayer
            compose.runOnIdle {
                player = ExoPlayer.Builder(context, PathfinRenderersFactory(context, hardwareVideo = true).setEnableDecoderFallback(true)).build()
                view.player = player
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                        initializedTimestampMs: Long, initializationDurationMs: Long) { decoder.set(decoderName) }
                    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                        dropped.addAndGet(droppedFrames)
                    }
                })
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.countDown() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) ended.countDown()
                    }
                    override fun onPlayerError(failure: PlaybackException) {
                        error.set(failure); frame.countDown(); ended.countDown()
                    }
                })
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                player.prepare()
                player.play()
            }
            try {
                assertTrue("$name 首帧超时", frame.await(12, TimeUnit.SECONDS))
                assertNull("$name 解码失败", error.get())
                assertFalse("已支持的硬解 HDR/SDR 不能被新增补解抢占", decoder.get().orEmpty().startsWith("pathfin.ffmpeg."))
                val firstFrameMs = SystemClock.elapsedRealtime() - started
                val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                val copyStatus = AtomicInteger(-1)
                compose.runOnIdle {
                    val surface = view.videoSurfaceView as SurfaceView
                    PixelCopy.request(surface, bitmap, { status -> copyStatus.set(status); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue("$name Surface取样超时", copied.await(5, TimeUnit.SECONDS))
                assertEquals("$name 需要实际Surface画面", PixelCopy.SUCCESS, copyStatus.get())
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                val lit = pixels.count { ((it shr 16) and 255) + ((it shr 8) and 255) + (it and 255) > 60 }
                assertTrue("$name 首帧回调后Surface不能仍然是黑屏", lit > pixels.size / 10)
                assertTrue("$name 应完整播放到EOS", ended.await(12, TimeUnit.SECONDS))
                assertNull("$name 播放失败", error.get())
                compose.runOnIdle {
                    val format = checkNotNull(player.videoFormat)
                    assertEquals("$name 传递函数不能被丢失", transfer, format.colorInfo?.colorTransfer)
                    assertEquals(if (name == "sdr") C.COLOR_SPACE_BT709 else C.COLOR_SPACE_BT2020, format.colorInfo?.colorSpace)
                    assertEquals(640, format.width)
                    assertEquals(360, format.height)
                    assertNotNull("$name 必须实际选择解码器", decoder.get())
                    results.put(JSONObject().put("sample", name).put("decoder", decoder.get())
                        .put("transfer", format.colorInfo?.colorTransfer).put("space", format.colorInfo?.colorSpace)
                        .put("firstFrameMs", firstFrameMs).put("nonBlackPixels", lit).put("sampledPixels", pixels.size)
                        .put("droppedFrames", dropped.get()).put("ended", true))
                }
                reportFile.writeText(report.toString())
            } finally {
                compose.runOnIdle { view.player = null; player.release() }
                file.delete()
            }
        }
    }
}

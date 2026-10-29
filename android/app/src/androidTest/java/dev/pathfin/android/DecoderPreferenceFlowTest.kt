package dev.pathfin.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.playback.PreferredMediaCodecSelector
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用系统实际可用codec解码自制MKV，不把排序结果当作实际解码器证据。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DecoderPreferenceFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    @Test fun softwarePreferenceUsesTheActualAvailableSoftwareDecoderAndRenders() = runPreference("SOFTWARE", false)
    @Test fun hardwarePreferenceRetainsUsableSystemFallbackAndRenders() = runPreference("HARDWARE", true)

    private fun runPreference(audioMode: String, videoHardware: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val source = File(context.cacheDir, "codec-fixture-${UUID.randomUUID()}.mkv")
        instrumentation.context.assets.open("continuous-tracks/episode-one.mkv").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        val selector = PreferredMediaCodecSelector(audioMode, videoHardware)
        val videoCandidates = selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        val audioCandidates = selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)
        assertTrue(videoCandidates.isNotEmpty()); assertTrue(audioCandidates.isNotEmpty())
        var videoDecoder: String? = null
        var audioDecoder: String? = null
        var firstFrame = false
        var videoFailure = false
        var audioFailure = false
        var player: ExoPlayer? = null
        try {
            compose.runOnUiThread {
                player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(selector).setEnableDecoderFallback(true)).build().also { p ->
                    p.addAnalyticsListener(object : AnalyticsListener {
                        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestamp: Long, duration: Long) { videoDecoder = name }
                        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestamp: Long, duration: Long) { audioDecoder = name }
                        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, error: Exception) { videoFailure = true }
                        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, error: Exception) { audioFailure = true }
                    })
                    p.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { firstFrame = true } })
                    p.volume = 0f
                }
            }
            val current = checkNotNull(player)
            compose.setContent { AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = current } }, modifier = Modifier.fillMaxSize()) }
            compose.runOnIdle { current.setMediaItem(MediaItem.fromUri(Uri.fromFile(source))); current.prepare(); current.play() }
            compose.waitUntil(20_000) {
                compose.runOnIdle {
                    current.playerError?.let { throw AssertionError("实际解码失败", it) }
                    firstFrame && videoDecoder != null && audioDecoder != null && current.isPlaying && current.currentPosition > 400
                }
            }
            compose.runOnIdle {
                assertTrue(videoCandidates.any { it.name == videoDecoder })
                assertTrue(audioCandidates.any { it.name == audioDecoder })
                if (!videoFailure) assertEquals("无失败时应使用首选候选视频解码器", videoCandidates.first().name, videoDecoder)
                if (!audioFailure) assertEquals("无失败时应使用首选候选音频解码器", audioCandidates.first().name, audioDecoder)
                println("PathfinCodec: audio=$audioMode videoHardware=$videoHardware actualVideo=$videoDecoder actualAudio=$audioDecoder videoFallback=$videoFailure audioFallback=$audioFailure")
                assertNull(current.playerError)
            }
        } finally {
            compose.runOnUiThread { player?.release() }
            assertTrue(!source.exists() || source.delete())
        }
    }
}

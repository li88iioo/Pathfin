package dev.pathfin.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.playback.PlayerFrameCapture
import dev.pathfin.android.playback.SubtitleSeekPrerollExtractorsFactory
import dev.pathfin.ass.ExoAssExtension
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ARM64 libass pixels, animation clock, MKV fonts, external ASS, seek, and bounded UI delivery. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ExoAssExtensionFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private var view: PlayerView? = null

    @Test fun embeddedAssMovesAtActualSeekPositionAndUsesFontAttachment() =
        withPlayer("embedded-font.mkv") { player, extension, _ ->
            awaitNative(player, extension)
            assertTrue("Actual Matroska font bytes must reach the native memory provider", extension.diagnostics.loadedAttachmentFonts > 0)
            seek(player, extension, 2500)
            val first = overlayPixels()
            val firstX = yellowCentroid(first)
            assertTrue("ASS top and middle positions must both be rendered", cyanCount(first) > 20)
            seek(player, extension, 5500)
            val secondX = yellowCentroid(overlayPixels())
            assertTrue("\\move must advance across the video, not remain at its event-start bitmap: $firstX -> $secondX",
                secondX - firstX > .20)
            seek(player, extension, 16000)
            assertTrue("Seek into another ASS event must render its actual green pixels", greenCount(overlayPixels()) > 20)

            assertTrue(extension.diagnostics.renderedFrames > 3)
            assertNotNull(main { checkNotNull(view).findViewWithTag<View>("pathfin.libass.overlay") })
            assertFalse(extension.failed)
            assertNull(main { player.playerError })
        }

    @Test fun externalInlineFontAssUsesActualClippedPeriodOffsetAndRedrawsOnSeek() =
        withPlayer("plain.mp4", external = "animated-inline-font.ass", clipStartMs = 1000) { player, extension, source ->
            awaitNative(player, extension)
            seek(player, extension, 1500, rawPositionMs = 2500)
            val first = yellowCentroid(overlayPixels())
            seek(player, extension, 4500, rawPositionMs = 5500)
            val second = yellowCentroid(overlayPixels())
            assertTrue("Renderer offset must follow the clipped period instead of hard-coded 1e12", second - first > .20)
            seek(player, extension, 15000, rawPositionMs = 16000)
            assertTrue(greenCount(overlayPixels()) > 20)
            seek(player, extension, 1500, rawPositionMs = 2500)
            assertTrue("Seeking back must recover the first event and clear later pixels", abs(yellowCentroid(overlayPixels()) - first) < .05)
            assertEquals(0, greenCount(overlayPixels()))
            assertTrue("External ASS must use the very same bounded caller DataSource", source.subtitleOpens.get() > 0)
            assertFalse(extension.failed)
        }

    @Test fun missingScriptFontFallsBackToSystemChineseGlyphsInsteadOfTofu() =
        withPlayer("plain.mp4", external = "cjk-fallback.ass") { player, extension, _ ->
            awaitNative(player, extension)
            // 静态字幕在同一事件内跳转不会生成新位图，因此第一张在自然播放到“中”之后暂停截取。
            compose.waitUntil(WAIT) {
                main { player.currentPosition in 1500..3500 } &&
                    extension.diagnostics.let { it.hasPresentedCurrentFrame && it.lastRenderedTimeMs in 1000..4000 }
            }
            main { player.pause() }
            compose.waitForIdle()
            val first = yellowMask(overlayPixels())
            seek(player, extension, 6000)
            val second = yellowMask(overlayPixels())
            assertTrue("Both CJK events must render actual glyph pixels", first.count { it } > 200 && second.count { it } > 200)
            // 缺字时两个字都会画成同一个方框；真实字形的像素分布必然不同。
            val differing = first.indices.count { first[it] != second[it] }
            assertTrue("“中” and “国” must not render as identical tofu boxes: $differing differing pixels", differing > 150)
            assertFalse(extension.failed)
        }

    @Test fun pausedSubtitleDelayMovesTheRealAnimationClockAndSurvivesSeekAndReset() =
        withPlayer("embedded-font.mkv") { player, extension, _ ->
            awaitNative(player, extension)
            seek(player, extension, 2500)
            val at2500 = yellowCentroid(overlayPixels())
            seek(player, extension, 5500)
            val at5500 = yellowCentroid(overlayPixels())
            assertTrue(at5500 - at2500 > .20)
            main { extension.setSubtitlePresentation(1f, 100, 3000) }
            compose.waitUntil(WAIT) { extension.diagnostics.let { it.hasPresentedCurrentFrame && abs(it.lastRenderedTimeMs - 2500) < 100 } }
            assertTrue("Positive delay must render the earlier actual glyph position while paused", abs(yellowCentroid(overlayPixels()) - at2500) < .05)
            main { assertFalse(player.playWhenReady); assertTrue(abs(player.currentPosition - 5500) < 200) }
            seek(player, extension, 19_000, rawPositionMs = 16_000)
            assertTrue("Seek must use delayed ASS event time instead of resetting the offset", greenCount(overlayPixels()) > 20)
            val beforePosition = overlayPixels()
            main { extension.setSubtitlePresentation(1.25f, 60, 3000) }
            compose.waitForIdle()
            val afterPosition = overlayPixels()
            assertTrue("Subtitle presentation must change actual pixels, independent of the video", beforePosition.pixels.indices.any {
                beforePosition.pixels[it] != afterPosition.pixels[it]
            })
            main { extension.setSubtitlePresentation(1f, 100, 0) }
            seek(player, extension, 5500)
            assertTrue(abs(yellowCentroid(overlayPixels()) - at5500) < .05)
            assertFalse(extension.failed)
            assertNull(main { player.playerError })
        }

    @Test fun blockedMainRetainsOnlyOnePendingNativeFrameAndOneDeliveryTask() =
        withPlayer("embedded-font.mkv") { player, extension, _ ->
            awaitNative(player, extension)
            main { player.seekTo(2000); player.play() }
            compose.waitUntil(WAIT) { extension.diagnostics.lastRenderedTimeMs in 2000..4000 }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val before = extension.diagnostics
            Handler(Looper.getMainLooper()).post { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                Thread.sleep(650) // Playback/worker must continue while Main cannot consume frames.
                val actual = extension.diagnostics
                assertTrue("Must produce more than one real animated libass frame during the block", actual.renderedFrames > before.renderedFrames + 1)
                assertEquals("Latest-frame storage is bounded independently of worker coalescing", 1, actual.pendingMainFrames)
                assertEquals("There cannot be one Handler Runnable per bitmap", 1, actual.pendingMainTasks)
                assertTrue("Superseded unsubmitted frames must be dropped", actual.replacedPendingFrames > before.replacedPendingFrames)
            } finally { release.countDown() }
            compose.waitForIdle()
            assertFalse(extension.failed)
        }

    @Test fun ordinaryVideoDoesNotInitializeAnyAssEngineOrReadFonts() =
        withPlayer("plain.mp4") { player, extension, _ ->
            compose.waitUntil(WAIT) { main { player.playbackState == Player.STATE_READY && player.currentPosition > 250 } }
            assertFalse(extension.diagnostics.nativeInitialized)
            assertEquals(0, extension.diagnostics.loadedAttachmentFonts)
            assertEquals(0, extension.diagnostics.liveTracks)
            assertEquals(0L, extension.diagnostics.renderedFrames)
            assertFalse(extension.failed)
            assertTrue("Must play actual input rather than merely idle without subtitles", videoPixels().toSet().size > 20)
        }

    @Test fun disabledAssTrackStaysLazyAndCanLaterReplayItsBufferedAnimation() =
        withPlayer("embedded-font.mkv", disabledText = true) { player, extension, _ ->
            compose.waitUntil(WAIT) { main { player.playbackState == Player.STATE_READY && player.currentPosition > 300 } }
            assertFalse("Merely discovering an unselected ASS format must not read system fonts or load JNI", extension.diagnostics.nativeInitialized)
            assertTrue("The test must really have discovered its ASS descriptor", extension.diagnostics.liveTracks > 0)
            main {
                player.pause(); player.seekTo(3000)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            }
            awaitNative(player, extension)
            seek(player, extension, 3000)
            assertTrue(extension.diagnostics.nativeInitialized)
            assertTrue(yellowCentroid(overlayPixels()) in .2.. .6)

            // Media3 reports a public seek notification even when its playback thread skips an
            // exact same-position seek. Block native rendering: the already presented paused ASS
            // must remain visible, rather than being cleared until a later playback tick repaints.
            val controller = ExoAssExtension::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(extension)
            val worker = controller.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(controller) as ExecutorService
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            worker.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                main { assertFalse(player.isPlaying); player.seekTo(player.currentPosition) }
                compose.waitForIdle()
                assertTrue("A no-op paused seek must preserve the actual moving ASS glyph while native work is blocked",
                    yellowCentroid(overlayPixels()) in .2.. .6)
            } finally { release.countDown() }
        }

    @Test fun lateOldRenderCannotReplaceThePendingClearAfterSubtitleIsDisabled() =
        withPlayer("embedded-font.mkv") { player, extension, _ ->
            awaitNative(player, extension)
            seek(player, extension, 3000)
            assertTrue(yellowCentroid(overlayPixels()) > 0)
            val controllerField = ExoAssExtension::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val controller = controllerField.get(extension)
            val epoch = controller.javaClass.getDeclaredField("epoch").apply { isAccessible = true }.get(controller) as AtomicLong
            val post = controller.javaClass.getDeclaredMethod("postFrame", Bitmap::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
            val select = controller.javaClass.getDeclaredMethod("select", androidx.media3.common.Format::class.java).apply { isAccessible = true }
            val oldGeneration = epoch.get()
            val releaseLateRender = CountDownLatch(1)
            val posted = CountDownLatch(1)
            val oldBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
            val lateRender = Thread {
                releaseLateRender.await(2, TimeUnit.SECONDS)
                post.invoke(controller, oldBitmap, oldGeneration)
                posted.countDown()
            }.apply { start() }
            try {
                main {
                    // Exact race: new clear is pending while Main cannot deliver it; the older
                    // worker result arrives next. Waiting until after clear displayed would miss it.
                    select.invoke(controller, null)
                    releaseLateRender.countDown()
                    assertTrue(posted.await(2, TimeUnit.SECONDS))
                }
                compose.waitForIdle()
                assertTrue("Disabling subtitles must actually remove all previous glyph pixels",
                    overlayPixels().pixels.all { Color.alpha(it) == 0 })
            } finally { releaseLateRender.countDown(); lateRender.join(2000) }
        }

    private fun withPlayer(file: String, external: String? = null, clipStartMs: Long = 0, disabledText: Boolean = false,
                           block: (ExoPlayer, ExoAssExtension, FixtureSource) -> Unit) {
        assumeTrue("Native tests require actual ARM64 JNI, never count SSA fallback as success", ExoAssExtension.supportedOnDevice())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val source = FixtureSource()
        val extension = main { ExoAssExtension(context) }
        val assUris = external?.let { setOf("https://pathfin-fixture.invalid/$it") }.orEmpty()
        val player = main {
            ExoPlayer.Builder(context).setRenderersFactory(extension.renderersFactory())
                .setMediaSourceFactory(extension.mediaSourceFactory(source, assUris,
                    ::SubtitleSeekPrerollExtractorsFactory)).build().also {
                    extension.bind(it)
                    if (disabledText) it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
        }
        try {
            compose.setContent {
                AndroidView(factory = { PlayerView(it).also { playerView ->
                    playerView.useController = false; playerView.player = player; view = playerView
                    extension.attach(playerView)
                } }, modifier = Modifier.fillMaxSize())
            }
            compose.waitUntil(WAIT) { main { view?.width?.let { it > 0 } == true } }
            val item = MediaItem.Builder().setUri("https://pathfin-fixture.invalid/$file").apply {
                if (external != null) setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(
                    Uri.parse("https://pathfin-fixture.invalid/$external")).setMimeType(MimeTypes.TEXT_SSA)
                    .setLanguage("en").setLabel("Pathfin external ASS fixture").setId("fixture-ass")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
                if (clipStartMs != 0L) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs).setEndPositionMs(22_000).build())
            }.build()
            main { player.setMediaItem(item); player.prepare(); player.play() }
            block(player, extension, source)
        } finally {
            main { view?.let(extension::detach); extension.close(); extension.close(); player.release(); view = null }
        }
    }

    private fun awaitNative(player: ExoPlayer, extension: ExoAssExtension) {
        compose.waitUntil(WAIT) {
            main { player.playerError }?.let { throw AssertionError("ASS fixture playback failed", it) }
            if (extension.failed) throw AssertionError("Actual libass rendering failed; a status-only fallback cannot pass")
            extension.diagnostics.renderedFrames > 0 && main { player.playbackState == Player.STATE_READY }
        }
    }
    private fun seek(player: ExoPlayer, extension: ExoAssExtension, positionMs: Long, rawPositionMs: Long = positionMs) {
        main { player.pause(); player.seekTo(positionMs) }
        compose.waitUntil(WAIT) {
            if (extension.failed) throw AssertionError("libass failed after seek")
            main { player.playbackState == Player.STATE_READY && abs(player.currentPosition - positionMs) < 200 } &&
                extension.diagnostics.let { it.hasPresentedCurrentFrame &&
                    abs(it.lastRenderedTimeMs - rawPositionMs) < 300 }
        }
        compose.waitForIdle()
    }

    private data class OverlayPixels(val pixels: IntArray, val width: Int, val height: Int,
                                     val left: Float, val top: Float, val videoWidth: Float, val videoHeight: Float)
    private fun overlayPixels(): OverlayPixels = main {
        val playerView = checkNotNull(view)
        val canvasView = checkNotNull(playerView.findViewWithTag<View>("pathfin.libass.overlay"))
        val surface = checkNotNull(playerView.videoSurfaceView)
        val origin = IntArray(2); val surfaceOrigin = IntArray(2)
        canvasView.getLocationInWindow(origin); surface.getLocationInWindow(surfaceOrigin)
        val bitmap = Bitmap.createBitmap(canvasView.width, canvasView.height, Bitmap.Config.ARGB_8888)
        canvasView.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = OverlayPixels(pixels, bitmap.width, bitmap.height, (surfaceOrigin[0] - origin[0]).toFloat(),
            (surfaceOrigin[1] - origin[1]).toFloat(), surface.width.toFloat(), surface.height.toFloat())
        bitmap.recycle(); result
    }
    private fun yellowCentroid(image: OverlayPixels): Double {
        var sum = 0.0; var count = 0
        image.pixels.forEachIndexed { index, color ->
            val y = ((index / image.width) - image.top) / image.videoHeight
            if (y in .45f.. .65f && Color.alpha(color) > 180 && Color.red(color) > 180 && Color.green(color) > 180 && Color.blue(color) < 90) {
                sum += ((index % image.width) - image.left) / image.videoWidth; count++
            }
        }
        assertTrue("Must have actual colored moving glyph pixels", count > 20)
        return sum / count
    }
    private fun yellowMask(image: OverlayPixels) = image.pixels.map { Color.alpha(it) > 180 && Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) < 90 }
    private fun cyanCount(image: OverlayPixels) = image.pixels.count { Color.alpha(it) > 180 && Color.red(it) < 90 && Color.green(it) > 180 && Color.blue(it) > 180 }
    private fun greenCount(image: OverlayPixels) = image.pixels.count { Color.alpha(it) > 180 && Color.red(it) < 90 && Color.green(it) > 180 && Color.blue(it) < 90 }
    private fun videoPixels(): IntArray {
        val target = main { checkNotNull(view) }
        val bitmap = runBlocking { PlayerFrameCapture.capture(target) }
        return try { IntArray(160 * 90) { i -> bitmap.getPixel((i % 160) * bitmap.width / 160, (i / 160) * bitmap.height / 90) } }
        finally { bitmap.recycle() }
    }
    private fun <T> main(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /** The fixture host can only be read through this caller-supplied DataSource, never native HTTP. */
    private class FixtureSource : DataSource.Factory {
        private val assets = InstrumentationRegistry.getInstrumentation().context.assets
        private val files = listOf("embedded-font.mkv", "plain.mp4", "animated-inline-font.ass", "cjk-fallback.ass").associateWith {
            assets.open("ass-extension/$it").use { input -> input.readBytes() }
        }
        val subtitleOpens = AtomicInteger()
        override fun createDataSource(): DataSource = object : DataSource {
            private var uri: Uri? = null
            private var input: ByteArrayInputStream? = null
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long {
                if (dataSpec.uri.host != "pathfin-fixture.invalid") throw IOException("Unexpected fixture destination")
                val name = dataSpec.uri.lastPathSegment ?: throw IOException("Missing fixture")
                val bytes = files[name] ?: throw IOException("Unknown fixture")
                if (name.endsWith(".ass")) subtitleOpens.incrementAndGet()
                if (dataSpec.position < 0 || dataSpec.position > bytes.size) throw IOException("Invalid fixture range")
                val available = bytes.size - dataSpec.position.toInt()
                val size = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available.toLong(), dataSpec.length).toInt()
                uri = dataSpec.uri
                input = ByteArrayInputStream(bytes, dataSpec.position.toInt(), size)
                return size.toLong()
            }
            override fun getUri(): Uri? = uri
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                if (length == 0) 0 else checkNotNull(input).read(buffer, offset, length)
            override fun close() { input = null; uri = null }
        }
    }
    companion object { private const val WAIT = 20_000L }
}

package dev.pathfin.android

import android.view.InputDevice
import android.view.MotionEvent
import java.io.File
import android.content.Context
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.playback.PlayerFrameCapture
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 新控制层的真实 MainActivity/Media3 回归：双 AAC 音轨、内封 mov_text、外挂 SRT/VTT/ASS。
 * 仅 verification 包；先运行 prepare_multitrack_fixture.py 并 reverse 8101/8102。
 * 大字号用例由 root 在系统 font_scale >= 1.5 时以 -e pathfin.largeFont true 单独运行。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerControlsFlowTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private val fixtureClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val isolatedStorage = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(context.packageName == VERIFICATION_PACKAGE) { "播放控制回归只允许 verification 包" }
                val status = fixtureStatus()
                check(status.optBoolean("MultiTrackFixture") && status.optInt("DurationSeconds") == 120) {
                    "必须启动独立的 8101/8102 合成多轨夹具，不能使用用户服务器或默认短视频夹具"
                }
                val store = AppPreferencesStore(context)
                val previous = store.load()
                clearSyntheticSession(context)
                store.save(testPreferences())
                try {
                    base.evaluate()
                } finally {
                    clearSyntheticSession(context)
                    store.save(previous)
                    fixtureClient.connectionPool.evictAll()
                    fixtureClient.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test fun controlsChangeRealPlaybackWithoutReplacingSession() = withPlayback { active ->
        pauseWithControls(active)
        val pausedAt = main { active.player.currentPosition }
        compose.onNodeWithTag("player-seek-slider").performTouchInput {
            swipe(Offset(width * .15f, centerY), Offset(width * .5f, centerY), durationMillis = 400)
        }
        compose.waitUntil(WAIT_MS) { main { active.player.currentPosition in 45_000..75_000 } }
        main {
            assertFalse("拖动进度不能恢复暂停", active.player.playWhenReady)
            assertTrue("必须实际改变 Media3 位置", active.player.currentPosition > pausedAt + 20_000)
        }
        val seekPosition = main { active.player.currentPosition }
        compose.onNodeWithTag("player-seek-forward").performClick()
        compose.waitUntil(WAIT_MS) { main { active.player.currentPosition >= seekPosition + 9_000 } }
        compose.onNodeWithTag("player-seek-back").performClick()
        compose.waitUntil(WAIT_MS) { main { kotlin.math.abs(active.player.currentPosition - seekPosition) < 1_000 } }
        compose.onNodeWithTag("player-speed-increase").performClick()
        compose.waitUntil(WAIT_MS) { main { kotlin.math.abs(active.player.playbackParameters.speed - 1.1f) < .001f } }
        compose.onNodeWithTag("player-speed-decrease").performClick()
        compose.waitUntil(WAIT_MS) { main { active.player.playbackParameters.speed == 1f } }
        compose.onNodeWithTag("player-speed").performClick()
        scrollDialogTo(3)
        compose.onNodeWithTag("player-speed-1.25").performClick()
        compose.waitUntil(WAIT_MS) { main { active.player.playbackParameters.speed == 1.25f } }
        compose.onNodeWithTag("player-aspect").performClick()
        compose.onNodeWithTag("player-resize-zoom").performClick()
        compose.waitUntil(WAIT_MS) { main { findPlayerView(compose.activity.window.decorView)
            ?.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let { it.scaleX > 1f && it.scaleY > 1f } == true } }
        compose.onNodeWithTag("player-aspect").performClick()
        compose.onNodeWithTag("player-resize-fit").performClick()
        compose.waitUntil(WAIT_MS) { main { findPlayerView(compose.activity.window.decorView)
            ?.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let { it.scaleX == 1f && it.scaleY == 1f } == true } }
        visitChaptersWithoutChangingPlayback(active)
        compose.onNodeWithTag("player-play-pause").performClick()
        compose.waitUntil(WAIT_MS) { main { active.player.isPlaying } }
        assertContinuous(active)
    }

    @Test fun doubleTapSeekAndLongPressSpeedFeedbackCarryTheirOwnIcons() = withPlayback { active ->
        pauseWithControls(active)
        compose.onNodeWithTag("player-video").performTouchInput { doubleClick(Offset(width * .85f, centerY)) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("快进", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-feedback-icon", useUnmergedTree = true).assertExists()
        // 临时倍速只在播放中生效；按住期间显示，松手后随倍速一起结束。
        main { active.player.play() }
        compose.waitUntil(WAIT_MS) { main { active.player.isPlaying } }
        // 长按要靠系统按住时长识别，用真实时间注入按下/抬起，落点避开中部的控制按钮。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (x, y) = main {
            val view = checkNotNull(findPlayerView(compose.activity.window.decorView))
            val location = IntArray(2).also(view::getLocationOnScreen)
            (location[0] + view.width * .5f) to (location[1] + view.height * .3f)
        }
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int) = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }.also { instrumentation.sendPointerSync(it); it.recycle() }
        send(MotionEvent.ACTION_DOWN)
        try {
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("倍速播放", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player-feedback-icon", useUnmergedTree = true).assertExists()
        } finally { send(MotionEvent.ACTION_UP) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("倍速播放", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun audioSwitchAndThreeExternalSubtitleFormatsActuallyRender() = withPlayback { active ->
        pauseWithControls(active)
        compose.waitUntil(WAIT_MS) { main { tracks(active.player, C.TRACK_TYPE_AUDIO).size == 2 && tracks(active.player, C.TRACK_TYPE_TEXT).size >= 4 } }
        main {
            val configs = checkNotNull(active.player.currentMediaItem).localConfiguration!!.subtitleConfigurations
            assertEquals(setOf("external-subtitle-4", "external-subtitle-5", "external-subtitle-6"), configs.map { it.id }.toSet())
            assertTrue(configs.none { it.uri.toString().contains("api_key") })
        }
        val targetAudio = main { tracks(active.player, C.TRACK_TYPE_AUDIO).first { !it.selected } }
        chooseTrack(active, C.TRACK_TYPE_AUDIO, targetAudio)
        compose.waitUntil(WAIT_MS) { main { tracks(active.player, C.TRACK_TYPE_AUDIO).any { it.tag == targetAudio.tag && it.selected } } }
        compose.onNodeWithTag("player-play-pause").performClick()
        compose.waitUntil(WAIT_MS) { main { active.player.isPlaying && active.player.audioFormat?.language == targetAudio.language } }

        val cues = ConcurrentLinkedQueue<String>()
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { cueGroup.cues.mapNotNull { it.text?.toString() }.forEach(cues::add) }
        }
        main { active.player.addListener(listener) }
        try {
            for ((index, format) in listOf(4 to "SRT", 5 to "VTT", 6 to "ASS")) {
                val target = main { tracks(active.player, C.TRACK_TYPE_TEXT).single { it.id == "external-subtitle-$index" || it.label.startsWith("$format ") } }
                cues.clear()
                chooseTrack(active, C.TRACK_TYPE_TEXT, target)
                awaitSubtitle(active, "选择 $format 外挂字幕") {
                    assertHealthy(active)
                    main { tracks(active.player, C.TRACK_TYPE_TEXT).any { it.tag == target.tag && it.selected } } &&
                        if (format == "ASS") main {
                            active.playback.assExtensionFor(active.player)?.diagnostics?.let {
                                it.nativeInitialized && it.hasPresentedCurrentFrame && it.renderedFrames > 0
                            } == true && assGlyphPixels() > 20
                        } else cues.any { it.contains("PATHFIN $format") }
                }
            }
            ensureControls()
            compose.onNodeWithTag("player-subtitles").performClick()
            awaitTrackMenu("player-track-off")
            compose.onNodeWithTag("player-track-off").performClick()
            compose.waitUntil(WAIT_MS) { main { C.TRACK_TYPE_TEXT in active.player.trackSelectionParameters.disabledTrackTypes &&
                active.player.currentCues.cues.isEmpty() && assGlyphPixels() == 0 } }
            val requests = fixtureStatus().getJSONArray("SubtitleRequests")
            val own = (0 until requests.length()).map { requests.getJSONObject(it) }
            for (index in 4..6) assertTrue("每种外挂字幕必须实际通过独立服务器认证加载", own.any {
                it.getInt("index") == index && it.getBoolean("authorized") && !it.getBoolean("source_header_leak") && !it.getBoolean("query_token_leak")
            })
            assertContinuous(active)
        } finally {
            main { if (!active.player.isReleased) active.player.removeListener(listener) }
        }
    }

    @Test fun lockedBackUnlocksAndRotationRetainsPlayerBeforeReturningToDetails() = withPlayback { active ->
        pauseWithControls(active)
        val position = main { active.player.currentPosition }
        compose.onNodeWithTag("player-lock").performClick()
        compose.waitUntil(WAIT_MS) { active.playback.state.value.controlsLocked }
        compose.onNodeWithTag("player-unlock").assertIsDisplayed()
        physicalBack()
        compose.waitUntil(WAIT_MS) { !active.playback.state.value.controlsLocked }
        main {
            assertSame("锁定后的第一次返回只解锁", active.player, active.playback.player)
            assertSame(active.request, active.app.state.value.activePlayback)
            assertFalse(active.player.playWhenReady)
            assertTrue(kotlin.math.abs(active.player.currentPosition - position) < 250)
        }
        drainReports(active.playback)
        assertEquals(0, eventCount("stop", active.request.info.playSessionId))
        ensureControls()
        assertControlsFit()
        rotateTo(Configuration.ORIENTATION_LANDSCAPE, active)
        ensureControls()
        assertControlsFit()
        rotateTo(Configuration.ORIENTATION_PORTRAIT, active)
        ensureControls()
        assertControlsFit()
        physicalBack()
        awaitClosed(active)
    }

    /** Whole-display PNGs come from the external ADB runner, including the actual Surface layer. */
    @Test fun pausedAspectModesHaveRealScreenPixelsAndKeepAssAligned() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.aspectPreviews") == "true")
        withPlayback(preferences = testPreferences().copy(serverIntroSkipEnabled = false)) { active ->
            pauseWithControls(active)
            ensureControls()
            compose.onNodeWithTag("player-subtitles").performClick()
            awaitTrackMenu("player-track-off")
            compose.onNodeWithTag("player-track-off").performClick()
            val beforeSeek = active.presentedFrames.get()
            main { active.player.seekTo(16_000) }
            compose.waitUntil(WAIT_MS) {
                assertHealthy(active)
                main { active.player.playbackState == Player.STATE_READY &&
                    kotlin.math.abs(active.player.currentPosition - 16_000) < 100 && active.player.currentCues.cues.isEmpty() } &&
                    active.presentedFrames.get() > beforeSeek
            }
            videoPixels("比例回归原始暂停帧")
            val source = runBlocking { PlayerFrameCapture.capture(main { checkNotNull(findPlayerView(compose.activity.window.decorView)) }) }
            val position = main { active.player.currentPosition }
            try {
                for ((orientation, label) in listOf(Configuration.ORIENTATION_PORTRAIT to "portrait", Configuration.ORIENTATION_LANDSCAPE to "landscape")) {
                    chooseAspect("fit")
                    if (main { compose.activity.resources.configuration.orientation } != orientation) rotateTo(orientation, active)
                    for (mode in listOf("fit", "fill", "zoom")) {
                        chooseAspect(mode)
                        hideControls()
                        val screen = aspectScreenshot("player-aspect-$label-$mode")
                        try { assertAspectPixels(mode, source, screen) } finally { screen.recycle() }
                        assertPausedAspectSession(active, position)
                    }
                }

                chooseAspect("fit")
                rotateTo(Configuration.ORIENTATION_PORTRAIT, active)
                val ass = main { tracks(active.player, C.TRACK_TYPE_TEXT).single { it.id == "external-subtitle-6" || it.label.startsWith("ASS ") } }
                chooseTrack(active, C.TRACK_TYPE_TEXT, ass)
                awaitSubtitle(active, "比例回归的真实 libass 字形") {
                    assertHealthy(active)
                    main { active.playback.assExtensionFor(active.player)?.diagnostics?.hasPresentedCurrentFrame == true && assGlyphPixels() > 20 }
                }
                val glyphMask = main { captureAssMask() }
                try {
                    for (mode in listOf("fit", "fill", "zoom")) {
                        chooseAspect(mode)
                        hideControls()
                        val screen = aspectScreenshot("player-aspect-ass-$mode")
                        try { assertAssAspectPixels(mode, source, glyphMask, screen) } finally { screen.recycle() }
                        assertPausedAspectSession(active, position)
                    }
                } finally { glyphMask.recycle() }
                chooseAspect("fit")
            } finally { source.recycle() }
        }
    }

    private fun chooseAspect(mode: String) {
        ensureControls()
        VerificationDisplay.awaitWindowReady(compose, "aspect-menu-open/$mode", compose.onNodeWithTag("player-aspect"))
        compose.onNodeWithTag("player-aspect").performClick()
        val choice = compose.onNodeWithTag("player-resize-$mode")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag("player-resize-$mode")).fetchSemanticsNodes().isNotEmpty()
        }
        // Popup has its own Android window. Compose idle alone does not establish its
        // first draw/focus, particularly when the previous same-mode menu just closed.
        VerificationDisplay.awaitWindowReady(compose, "aspect-menu-choice/$mode", choice)
        VerificationDisplay.assertDisplayed(compose, "aspect-menu-choice/$mode", choice).performClick()
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasTestTag("player-popup-aspect")).fetchSemanticsNodes().isEmpty()
        }
        VerificationDisplay.awaitWindowReady(compose, "aspect-menu-dismiss/$mode", compose.onNodeWithTag("player-aspect"))
    }

    private fun hideControls() {
        if (compose.onAllNodes(hasTestTag("player-controls")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("player-video").performTouchInput { click(Offset(width * .5f, height * .3f)) }
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("player-controls")).fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun assertPausedAspectSession(active: ActiveFixture, position: Long) {
        assertContinuous(active)
        main {
            val view = checkNotNull(findPlayerView(compose.activity.window.decorView))
            assertEquals("显示比例不能缩放全屏触摸区域", 1f, view.scaleX, .0001f)
            assertEquals("显示比例不能缩放全屏触摸区域", 1f, view.scaleY, .0001f)
            assertFalse("显示比例不能恢复播放", active.player.playWhenReady)
            assertTrue("显示比例不能 seek", kotlin.math.abs(active.player.currentPosition - position) < 100)
        }
    }

    private fun aspectScreenshot(name: String): Bitmap {
        VerificationDisplay.awaitWindowReady(compose, name, compose.onNodeWithTag("player-screen"))
        ExternalScreenshotCheckpoint.capture(name)
        val run = InstrumentationRegistry.getArguments().getString("pathfin.screenshotRun").orEmpty()
        check(Regex("[a-f0-9]{32}").matches(run))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-preview/returned/$run/$name.png")
        check(file.isFile && file.length() in 1..32_000_000L) { "外部截图未回传：$name" }
        return try { checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "外部截图不能解码：$name" } }
        finally { check(file.delete()) }
    }

    private fun screenViewport(): RectF = main {
        val view = checkNotNull(findPlayerView(compose.activity.window.decorView))
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        RectF(position[0].toFloat(), position[1].toFloat(), position[0] + view.width.toFloat(), position[1] + view.height.toFloat())
    }

    /** Expected geometry is fixed by the owned 320x180 square-pixel fixture, never by observed non-black bounds. */
    private fun expectedVideoRect(viewport: RectF, mode: String): RectF {
        val fitWidth = minOf(viewport.width(), viewport.height() * 320f / 180f)
        val fitHeight = fitWidth * 180f / 320f
        val scale = maxOf(viewport.width() / fitWidth, viewport.height() / fitHeight)
        val width = when (mode) { "fill" -> viewport.width(); "zoom" -> fitWidth * scale; else -> fitWidth }
        val height = when (mode) { "fill" -> viewport.height(); "zoom" -> fitHeight * scale; else -> fitHeight }
        return RectF(viewport.centerX() - width / 2, viewport.centerY() - height / 2,
            viewport.centerX() + width / 2, viewport.centerY() + height / 2)
    }

    private fun assertAspectPixels(mode: String, source: Bitmap, screen: Bitmap) {
        val viewport = screenViewport()
        assertTrue("整屏 PNG 必须包含播放器实际坐标", viewport.left >= 0 && viewport.top >= 0 && viewport.right <= screen.width && viewport.bottom <= screen.height)
        val video = expectedVideoRect(viewport, mode)
        var videoSamples = 0
        var blackSamples = 0
        var wrongBlack = 0
        var colorError = 0L
        for (row in 0 until 64) for (column in 0 until 40) {
            val x = viewport.left + viewport.width() * (column + .5f) / 40
            val y = viewport.top + viewport.height() * (row + .5f) / 64
            val actual = screen.getPixel(x.toInt(), y.toInt())
            if (!video.contains(x, y)) {
                blackSamples++
                if (maxOf(Color.red(actual), Color.green(actual), Color.blue(actual)) > 20) wrongBlack++
            } else {
                val u = ((x - video.left) / video.width() * source.width).toInt().coerceIn(0, source.width - 1)
                val v = ((y - video.top) / video.height() * source.height).toInt().coerceIn(0, source.height - 1)
                val expected = source.getPixel(u, v)
                colorError += kotlin.math.abs(Color.red(actual) - Color.red(expected)) +
                    kotlin.math.abs(Color.green(actual) - Color.green(expected)) + kotlin.math.abs(Color.blue(actual) - Color.blue(expected))
                videoSamples++
            }
        }
        val meanError = colorError.toDouble() / (videoSamples * 3).coerceAtLeast(1)
        assertTrue("$mode 真实整屏视频必须符合固定源的矩形/裁切，viewport=$viewport,video=$video,RGB meanError=$meanError", videoSamples > 200 && meanError < 18)
        if (mode == "fit") assertTrue("FIT 必须实际保留黑边，不能通过裁掉黑边读取像素", blackSamples > 40)
        assertTrue("$mode 黑边不能残留视频或错位，black=$blackSamples,wrong=$wrongBlack", wrongBlack <= blackSamples / 100)
    }

    /** Draw only the real native libass overlay, never compose a synthetic subtitle. Main only. */
    private fun captureAssMask(): Bitmap {
        val view = checkNotNull(findPlayerView(compose.activity.window.decorView)?.findViewWithTag<View>("pathfin.libass.overlay"))
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun assertAssAspectPixels(mode: String, source: Bitmap, mask: Bitmap, screen: Bitmap) {
        val viewport = screenViewport()
        val fit = expectedVideoRect(viewport, "fit")
        val target = expectedVideoRect(viewport, mode)
        var samples = 0
        var white = 0
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        fun opaqueWhite(pixel: Int) = Color.alpha(pixel) > 240 &&
            minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 220
        fun solidGlyph(x: Int, y: Int): Boolean {
            val i = y * mask.width + x
            return opaqueWhite(pixels[i]) && opaqueWhite(pixels[i - 2]) &&
                opaqueWhite(pixels[i + 2]) && opaqueWhite(pixels[i - 2 * mask.width]) &&
                opaqueWhite(pixels[i + 2 * mask.width])
        }
        for (y in 2 until mask.height - 2 step 2) for (x in 2 until mask.width - 2 step 2) {
            if (!solidGlyph(x, y)) continue
            val u = (viewport.left + x - fit.left) / fit.width()
            val v = (viewport.top + y - fit.top) / fit.height()
            if (u !in 0f..1f || v !in 0f..1f) continue
            val background = source.getPixel((u * (source.width - 1)).toInt(), (v * (source.height - 1)).toInt())
            if (minOf(Color.red(background), Color.green(background), Color.blue(background)) > 180) continue
            val sx = target.left + target.width() * u
            val sy = target.top + target.height() * v
            if (!viewport.contains(sx, sy) || sx < 3 || sy < 3 || sx >= screen.width - 3 || sy >= screen.height - 3) continue
            samples++
            if ((-2..2).any { dx -> (-2..2).any { dy ->
                    val p = screen.getPixel(sx.toInt() + dx, sy.toInt() + dy)
                    minOf(Color.red(p), Color.green(p), Color.blue(p)) > 200
                } }) white++
        }
        assertTrue("$mode 必须有真实ASS高对比字形可检查，samples=$samples", samples > 40)
        assertTrue("$mode 屏幕字形必须跟随源视频同一矩形，不能留在旧位置：white=$white,samples=$samples", white * 100 >= samples * 85)
    }

    @Test fun largeTextKeepsControlsReachableInPortraitAndLandscape() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.largeFont") == "true")
        assertTrue("此回归必须在系统大字号配置下运行", main { compose.activity.resources.configuration.fontScale >= 1.5f })
        withPlayback { active ->
            pauseWithControls(active)
            assertControlsFit()
            rotateTo(Configuration.ORIENTATION_LANDSCAPE, active)
            ensureControls()
            assertControlsFit()
            compose.onNodeWithTag("player-more").performClick()
            VerificationDisplay.awaitWindowReady(compose, "large-font-settings", compose.onNodeWithTag("player-popup-more"))
            listOf("player-menu-information", "player-audio-gain", "player-tools", "player-sleep-timer").forEach { tag ->
                compose.onNodeWithTag("player-popup-more").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            }
            listOf("媒体信息", "音量增益", "播放质量", "定时关闭").forEach { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("弹窗必须使用真实系统大字号：$label", layouts.isNotEmpty() && layouts.all { it.layoutInput.density.fontScale >= 1.5f })
                assertTrue("大字号文字不能裁切：$label", layouts.all { result ->
                    !result.didOverflowHeight && (0 until result.lineCount).all { line ->
                        !result.isLineEllipsized(line) && result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 1f
                    }
                })
            }
            compose.onNodeWithTag("playback-panel-close").performClick()
            compose.onNodeWithTag("player-audio").performClick()
            scrollDialogTo(2)
            val other = main { tracks(active.player, C.TRACK_TYPE_AUDIO).last() }
            compose.onNodeWithTag(other.tag).assertIsDisplayed().performClick()
            assertContinuous(active)
        }
    }

    /** 只导出真实 MainActivity 播放器。供截图评审使用，必须显式开启。 */
    @Test fun exportReferencePlaybackFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.playerPreviews") == "true")
        withPlayback(preferences = testPreferences().copy(serverIntroSkipEnabled = false)) { active ->
            pauseWithControls(active)
            main { active.player.seekTo(16_000) }
            compose.waitUntil(WAIT_MS) { main { active.player.playbackState == Player.STATE_READY && active.player.currentPosition >= 15_900 } }
            rotateTo(Configuration.ORIENTATION_LANDSCAPE, active)
            ensureControls()
            exportPlayerFrame("player-landscape-controls")
            compose.onNodeWithTag("player-more").performClick()
            exportPlayerFrame("player-landscape-more")
            compose.onNodeWithTag("player-sleep-timer").performClick()
            exportPlayerFrame("player-landscape-sleep")
            compose.onNodeWithTag("player-sleep-off").performClick()
            visitChaptersWithoutChangingPlayback(active) { exportPlayerFrame("player-landscape-chapters") }
            compose.onNodeWithTag("player-more").performClick()
            compose.onNodeWithTag("player-menu-information").performClick()
            compose.onNodeWithTag("player-popup-info").assertIsDisplayed()
            val video = main { checkNotNull(active.player.videoFormat) }
            compose.onNodeWithText("分辨率").assertIsDisplayed()
            compose.onNodeWithText("${video.width} × ${video.height}").assertIsDisplayed()
            exportPlayerFrame("player-landscape-information")
            compose.onNodeWithTag("player-info-close").performClick()
            compose.onNodeWithTag("player-audio").performClick()
            exportPlayerFrame("player-landscape-audio")
            compose.onNodeWithTag("player-track-auto").performClick()
            compose.onNodeWithTag("player-subtitles").performClick()
            exportPlayerFrame("player-landscape-subtitles")
            compose.onNodeWithTag("player-track-off").performClick()
            rotateTo(Configuration.ORIENTATION_PORTRAIT, active)
            ensureControls()
            exportPlayerFrame("player-portrait-controls")
            compose.onNodeWithTag("player-more").performClick()
            exportPlayerFrame("player-portrait-more")
            compose.onNodeWithTag("player-sleep-timer").performClick()
            compose.onNodeWithTag("player-sleep-off").performClick()
            assertContinuous(active)
        }
    }

    @Test fun subtitleDelayRepreparesTheSamePausedPlayerAndKeepsLongCuesAcrossSeek() = withPlayback { active ->
        pauseWithControls(active)
        compose.waitUntil(WAIT_MS) { main { tracks(active.player, C.TRACK_TYPE_TEXT).any { it.label.contains("SRT", true) } } }
        val srt = main { tracks(active.player, C.TRACK_TYPE_TEXT).first { it.label.contains("SRT", true) } }
        chooseTrack(active, C.TRACK_TYPE_TEXT, srt)
        main { active.player.seekTo(15_000); active.player.setPlaybackSpeed(1.5f) }
        awaitCue(active, "SRT")
        main { active.playback.setSubtitlePresentation(dev.pathfin.android.playback.SubtitlePresentation(delayMs = 500)) }
        awaitCue(active, "SRT")
        main {
            assertSame(active.player, active.playback.player)
            assertFalse(active.player.playWhenReady)
            assertTrue(kotlin.math.abs(15_000 - active.player.currentPosition) <= 200)
            assertEquals(1.5f, active.player.playbackParameters.speed, .001f)
            assertEquals(500L, active.playback.subtitlePresentation.value.delayMs)
        }
        main { active.player.seekTo(250) }
        compose.waitUntil(WAIT_MS) { main { active.player.playbackState == Player.STATE_READY && active.player.currentPosition in 200..300 } }
        SystemClock.sleep(250)
        main { assertTrue("延后500ms时，250ms不能提前显示从0开始的字幕", active.player.currentCues.cues.isEmpty()) }
        main { active.player.seekTo(750) }
        awaitCue(active, "SRT")
        main { active.player.seekTo(35_000) }
        awaitCue(active, "SRT")
        main { active.playback.setSubtitlePresentation(dev.pathfin.android.playback.SubtitlePresentation(delayMs = -500)) }
        awaitCue(active, "SRT")
        assertContinuous(active)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun ownedDocumentSubtitleActuallyCreatesASelectedTrackAndItsCopyIsDeletedOnExit() = withPlayback { active ->
        pauseWithControls(active)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "Pathfin-local-${java.util.UUID.randomUUID()}.srt"
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pathfin-Verification")
        }))
        try {
            resolver.openOutputStream(uri)!!.use { it.write("1\n00:00:00,000 --> 00:01:59,000\nPATHFIN LOCAL DOCUMENT\n\n".toByteArray()) }
            main { active.player.seekTo(16_000) }
            runBlocking { withContext(Dispatchers.Main) { active.playback.addLocalSubtitle(uri) } }
            compose.waitUntil(WAIT_MS) {
                assertHealthy(active)
                main { active.player.playbackState == Player.STATE_READY && !active.player.playWhenReady &&
                    active.player.currentCues.cues.any { it.text?.contains("PATHFIN LOCAL DOCUMENT") == true } &&
                    active.player.currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_TEXT &&
                        (0 until group.length).any { group.isTrackSelected(it) && group.getTrackFormat(it).label == name } } }
            }
            val file = main {
                val configuration = active.player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.single { it.label == name }
                assertEquals("file", configuration.uri.scheme)
                assertTrue(configuration.id!!.startsWith("pathfin-local-"))
                File(checkNotNull(configuration.uri.path))
            }
            assertTrue(file.isFile)
            assertContinuous(active)
            main { active.playback.stop(); active.app.playerClosed(active.playback.lastPositionMs) }
            compose.waitUntil(WAIT_MS) { !file.exists() }
            awaitClosed(active)
        } finally { resolver.delete(uri, null, null) }
    }

    private fun awaitCue(active: ActiveFixture, format: String) = awaitSubtitle(active, "当前 $format 字幕呈现") {
        assertHealthy(active)
        main { active.player.playbackState == Player.STATE_READY &&
            active.player.currentCues.cues.any { it.text?.contains("PATHFIN $format") == true } }
    }

    private fun awaitSubtitle(active: ActiveFixture, stage: String, condition: () -> Boolean) {
        try { compose.waitUntil(WAIT_MS, condition) }
        catch (failure: Throwable) {
            val detail = main {
                val player = active.player
                val text = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.joinToString { group ->
                    (0 until group.length).joinToString { index -> group.getTrackFormat(index).let { format ->
                        "group=${group.mediaTrackGroup.id}:${format.id}:${format.sampleMimeType}/${format.codecs}:" +
                            "supported=${group.isTrackSupported(index)}:selected=${group.isTrackSelected(index)}"
                    } }
                }
                val overrides = player.trackSelectionParameters.overrides.values.joinToString { override ->
                    val group = override.mediaTrackGroup
                    "${group.id}:formats=${(0 until group.length).map { group.getFormat(it).id }}:" +
                        "matchesCurrent=${player.currentTracks.groups.any { it.mediaTrackGroup == group }}:indices=${override.trackIndices}"
                }
                val ass = active.playback.assExtensionFor(player)?.diagnostics
                "state=${player.playbackState},paused=${!player.playWhenReady},position=${player.currentPosition}," +
                    "disabled=${player.trackSelectionParameters.disabledTrackTypes},overrides=[$overrides],text=[$text]," +
                    "cues=${player.currentCues.cues.map { it.text?.toString() }},ass=$ass"
            }
            throw AssertionError("$stage 未完成：$detail,pipeline=${textPipelineDiagnostics(active)}", failure)
        }
    }

    /** Read metadata only, on the actual playback thread. Never consume/skip a subtitle sample. */
    private fun textPipelineDiagnostics(active: ActiveFixture): String {
        val renderer = main { (0 until active.player.rendererCount).firstOrNull {
            active.player.getRendererType(it) == C.TRACK_TYPE_TEXT
        }?.let(active.player::getRenderer) } ?: return "no-text-renderer"
        val completed = CountDownLatch(1)
        var result = "playback-thread-timeout"
        main { active.player.createMessage { _, _ ->
            result = try {
                val actual = (readField(renderer, "delegate") as? Renderer) ?: renderer
                val resolver = readField(actual, "cuesResolver")
                val cues = resolver?.let { readField(it, "cuesWithTimingList") as? List<*> }.orEmpty()
                    .take(8).mapNotNull { it as? CuesWithTiming }.map {
                        "${it.startTimeUs}..${it.endTimeUs}:n=${it.cues.size}"
                    }
                val values = listOf("lastRendererPositionUs", "inputStreamEnded", "outputStreamEnded", "finalStreamEndPositionUs")
                    .joinToString { "$it=${readField(actual, it)}" }
                "state=${actual.state},ready=${actual.isReady},ended=${actual.isEnded},readEnd=${actual.hasReadStreamToEnd()}," +
                    "readUs=${actual.readingPositionUs},final=${actual.isCurrentStreamFinal},$values,cueTimes=$cues," +
                    streamDiagnostics(renderer.stream)
            } catch (error: Exception) { "diagnostic-failure=${error.javaClass.simpleName}" }
            finally { completed.countDown() }
        }.send() }
        completed.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun streamDiagnostics(initial: SampleStream?): String {
        var stream = initial ?: return "stream=null"
        val classes = mutableListOf<String>()
        repeat(6) {
            classes += stream.javaClass.simpleName
            val period = readField(stream, "this\$0")
            val track = readField(stream, "track") as? Int
            val queues = period?.let { readField(it, "sampleQueues") as? Array<*> }
            val queue = track?.let { queues?.getOrNull(it) as? SampleQueue }
            if (queue != null) {
                val owner = checkNotNull(period)
                return "streams=$classes,track=$track,first=${queue.firstIndex},read=${queue.readIndex},write=${queue.writeIndex}," +
                    "firstUs=${queue.firstTimestampUs},largestUs=${queue.largestQueuedTimestampUs}," +
                    "loadingFinished=${readField(owner, "loadingFinished")},pendingResetUs=${readField(owner, "pendingResetPositionUs")}," +
                    "readEndUs=${readField(queue, "readEndTimeUs")},queueLength=${readField(queue, "length")},queueRead=${readField(queue, "readPosition")}"
            }
            stream = (readField(stream, "sampleStream") ?: readField(stream, "childStream") ?: readField(stream, "delegate")) as? SampleStream
                ?: return "streams=$classes,no-progressive-queue"
        }
        return "streams=$classes,max-depth"
    }

    private fun readField(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val field = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { type = type.superclass; continue }
            field.isAccessible = true
            return field.get(owner)
        }
        return null
    }

    private fun exportPlayerFrame(name: String) {
        compose.waitForIdle()
        val target = when {
            name.endsWith("-more") -> "player-popup-more"
            name.endsWith("-information") -> "player-popup-info"
            name.endsWith("-audio") -> "player-popup-audio"
            name.endsWith("-subtitles") -> "player-popup-subtitles"
            name.endsWith("-sleep") -> "player-popup-sleep"
            name.endsWith("-chapters") -> "playback-chapters"
            else -> "player-screen"
        }
        VerificationDisplay.awaitWindowReady(compose, name, compose.onNodeWithTag(target))
        ExternalScreenshotCheckpoint.capture(name)
    }

    private fun withPlayback(preferences: AppPreferences = testPreferences(), block: (ActiveFixture) -> Unit) {
        val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        var active: ActiveFixture? = null
        var primaryFailure: Throwable? = null
        try {
            main {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                app.setPreferences(preferences)
                app.login(FIXTURE_BASE, "pathfin", "pathfin", true, "Synthetic multitrack fixture")
            }
            compose.waitUntil(WAIT_MS) {
                app.state.value.loginError?.let { throw AssertionError("合成多轨登录失败：$it") }
                app.state.value.home.error?.let { throw AssertionError("合成多轨媒体库失败：$it") }
                app.state.value.session != null && app.state.value.home.loaded && !app.state.value.home.loading
            }
            main {
                val home = app.state.value.home
                app.open((home.libraryItems.values.flatten() + home.recent + home.resume).first { it.id == ITEM_ID })
            }
            compose.waitUntil(WAIT_MS) { app.state.value.route == Route.Detail(ITEM_ID) && !app.state.value.detailLoading && app.state.value.details[ITEM_ID] != null }
            val routes = app.state.value.routes
            main { app.preparePlayback(app.state.value.details.getValue(ITEM_ID), fromStart = true) }
            compose.waitUntil(WAIT_MS) {
                app.state.value.playbackError?.let { throw AssertionError("合成多轨准备失败：$it") }
                playback.state.value.errorMessage?.let { throw AssertionError("合成多轨播放失败：$it") }
                app.state.value.activePlayback != null && playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
            }
            val request = checkNotNull(app.state.value.activePlayback)
            check(request.stream.uri == "http://127.0.0.1:8102/sample.mp4" && request.session.accessToken == "fixture-token") {
                "测试只允许独立回环合成片源"
            }
            val current = ActiveFixture(app, playback, main { checkNotNull(playback.player) }, request, routes)
            active = current
            main { current.player.setVideoFrameMetadataListener { _, _, _, _ -> current.presentedFrames.incrementAndGet() } }
            compose.waitUntil(WAIT_MS) { main { current.player.duration in 119_000..121_000 } }
            block(current)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                main {
                    playback.stop()
                    if (app.state.value.activePlayback != null) app.playerClosed(playback.lastPositionMs)
                    compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                drainReports(playback)
                active?.let { assertEquals("一次实际播放只能有一个停止报告", 1, eventCount("stop", it.request.info.playSessionId)) }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun pauseWithControls(active: ActiveFixture) {
        ensureControls()
        compose.onNodeWithTag("player-play-pause").performClick()
        compose.waitUntil(WAIT_MS) { main { !active.player.playWhenReady && !active.player.isPlaying } }
    }

    private fun ensureControls() {
        if (compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player-video").performTouchInput { click(Offset(width * .5f, height * .3f)) }
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun visitChaptersWithoutChangingPlayback(active: ActiveFixture, onVisible: () -> Unit = {}) {
        ensureControls()
        val position = main { active.player.currentPosition }
        compose.onNodeWithTag("player-chapters").performClick()
        VerificationDisplay.awaitWindowReady(compose, "chapters-open", compose.onNodeWithTag("playback-chapters"))
        compose.onNodeWithTag("player-controls").assertDoesNotExist()
        onVisible()
        compose.onNodeWithTag("playback-panel-close").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("player-controls")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-play-pause").assertIsDisplayed()
        main {
            assertSame("章节开关不能替换播放器", active.player, active.playback.player)
            assertFalse("浏览章节不能恢复暂停播放", active.player.playWhenReady)
            assertTrue("未选择章节时不能改变播放进度", kotlin.math.abs(active.player.currentPosition - position) < 250)
        }
    }

    private fun chooseTrack(active: ActiveFixture, type: Int, choice: TrackRef) {
        ensureControls()
        compose.onNodeWithTag(if (type == C.TRACK_TYPE_AUDIO) "player-audio" else "player-subtitles").performClick()
        awaitTrackMenu(if (type == C.TRACK_TYPE_AUDIO) "player-track-auto" else "player-track-off")
        check(main { tracks(active.player, type).any { it.tag == choice.tag } })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(choice.tag))
        compose.onNodeWithTag(choice.tag).assertIsDisplayed().performClick()
        main {
            val override = active.player.trackSelectionParameters.overrides.values.singleOrNull { it.type == type }
            assertNotNull("实际点选必须设置对应类型的轨道覆盖：${choice.label}", override)
            val selected = checkNotNull(override)
            assertTrue("覆盖必须属于当前真实轨道组", active.player.currentTracks.groups.any { it.mediaTrackGroup == selected.mediaTrackGroup })
            assertTrue("覆盖必须选择实际点中的轨道", selected.trackIndices.any { selected.mediaTrackGroup.getFormat(it).id == choice.id })
        }
    }

    private fun awaitTrackMenu(anchorTag: String) {
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(anchorTag))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(anchorTag)).fetchSemanticsNodes().isNotEmpty() }
        VerificationDisplay.awaitWindowReady(compose, "track-menu/$anchorTag", compose.onNodeWithTag(anchorTag))
        compose.onNodeWithTag(anchorTag).assertIsDisplayed()
    }

    private fun scrollDialogTo(index: Int) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(index)

    private fun rotateTo(orientation: Int, active: ActiveFixture) {
        val before = videoPixels("旋转前")
        val position = main { active.player.currentPosition }
        val presentedFrames = active.presentedFrames.get()
        main { compose.activity.requestedOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(WAIT_MS) {
            assertHealthy(active)
            val bounds = compose.onNodeWithTag("player-screen").fetchSemanticsNode().boundsInRoot
            main { compose.activity.resources.configuration.orientation == orientation && compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) } &&
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) bounds.width > bounds.height else bounds.height > bounds.width
        }
        var geometry = "尚未采样"
        try {
            compose.waitUntil(WAIT_MS) {
                assertHealthy(active)
                main {
                    val view = checkNotNull(findPlayerView(compose.activity.window.decorView))
                    val surface = checkNotNull(view.videoSurfaceView)
                    val viewOrigin = IntArray(2); val surfaceOrigin = IntArray(2)
                    view.getLocationInWindow(viewOrigin); surface.getLocationInWindow(surfaceOrigin)
                    val buffer = (surface as? SurfaceView)?.holder?.surfaceFrame
                    geometry = "player=${view.width}x${view.height},surface=${surface.width}x${surface.height},buffer=$buffer," +
                        "offset=${surfaceOrigin[0] - viewOrigin[0]},${surfaceOrigin[1] - viewOrigin[1]},frames=$presentedFrames→${active.presentedFrames.get()}"
                    // 暂停旋转可由 Surface 合成器直接缩放已提交的 buffer，不要求再次解码。
                    // 下方仍读取真实视频像素并校验原帧误差，随后全屏截图检查比例和字幕位置。
                    surface.width > 0 && surface.height > 0 && surface.width <= view.width + 3 && surface.height <= view.height + 3 &&
                        kotlin.math.abs(surfaceOrigin[0] - viewOrigin[0] - (view.width - surface.width) / 2f) <= 3 &&
                        kotlin.math.abs(surfaceOrigin[1] - viewOrigin[1] - (view.height - surface.height) / 2f) <= 3
                }
            }
        } catch (error: Throwable) { throw AssertionError("旋转后真实 Surface 几何未完成：$geometry", error) }
        val after = videoPixels("旋转后", before)
        val meanError = pixelError(before, after)
        assertTrue("暂停旋转后必须重绘同一帧，不能黑屏、裁切或错位：RGB meanError=$meanError", meanError < 18)
        assertContinuous(active)
        main {
            assertFalse("旋转不能恢复已暂停的播放", active.player.playWhenReady)
            assertTrue("旋转不能改变暂停位置", kotlin.math.abs(active.player.currentPosition - position) <= 100)
        }
    }

    /** Read the actual video Surface, never the Compose controls or a cached player flag. */
    private fun videoPixels(stage: String, expected: IntArray? = null): IntArray {
        var result: IntArray? = null
        var lastColorCount = 0
        var lastError = Double.NaN
        var source = "尚未采样"
        var saved = false
        var failure: Exception? = null
        try {
            compose.waitUntil(WAIT_MS) {
                val view = main { checkNotNull(findPlayerView(compose.activity.window.decorView)) }
                try {
                    val bitmap = runBlocking { PlayerFrameCapture.capture(view) }
                    val pixels = try {
                        // Fixed fixture contract, independent of observed brightness: 320x180,
                        // square pixels, no rotation, centered FIT. Never search/crop non-black pixels.
                        val contentWidth: Int
                        val contentHeight: Int
                        if (bitmap.width.toLong() * 180 <= bitmap.height.toLong() * 320) {
                            contentWidth = bitmap.width
                            contentHeight = (bitmap.width.toLong() * 180 / 320).toInt().coerceAtLeast(1)
                        } else {
                            contentHeight = bitmap.height
                            contentWidth = (bitmap.height.toLong() * 320 / 180).toInt().coerceAtLeast(1)
                        }
                        val left = (bitmap.width - contentWidth) / 2
                        val top = (bitmap.height - contentHeight) / 2
                        source = "bitmap=${bitmap.width}x${bitmap.height},FIT=[$left,$top,${left + contentWidth},${top + contentHeight}]"
                        if (!saved) {
                            val context = InstrumentationRegistry.getInstrumentation().targetContext
                            val folder = File(context.filesDir, "player-diagnostics").apply { mkdirs() }
                            val orientation = main { compose.activity.resources.configuration.orientation }
                            val name = "${if (expected == null) "before" else "after"}-${if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}"
                            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            val geometry = main {
                                val surface = checkNotNull(view.videoSurfaceView)
                                val viewOrigin = IntArray(2); val surfaceOrigin = IntArray(2)
                                view.getLocationInWindow(viewOrigin); surface.getLocationInWindow(surfaceOrigin)
                                JSONObject().put("playerWidth", view.width).put("playerHeight", view.height)
                                    .put("surfaceWidth", surface.width).put("surfaceHeight", surface.height)
                                    .put("surfaceBuffer", (surface as? SurfaceView)?.holder?.surfaceFrame?.toShortString())
                                    .put("surfaceOffsetX", surfaceOrigin[0] - viewOrigin[0]).put("surfaceOffsetY", surfaceOrigin[1] - viewOrigin[1])
                            }
                            geometry.put("bitmapWidth", bitmap.width).put("bitmapHeight", bitmap.height)
                                .put("sourceRect", org.json.JSONArray(listOf(left, top, left + contentWidth, top + contentHeight)))
                                .put("sourceRectBasis", "fixed-320x180-centered-FIT-no-pixel-search")
                            File(folder, "$name.json").writeText(geometry.toString(2))
                            saved = true
                        }
                        IntArray(64 * 36) { index -> bitmap.getPixel(left + (index % 64) * contentWidth / 64,
                            top + (index / 64) * contentHeight / 36) }
                    } finally { bitmap.recycle() }
                    lastColorCount = pixels.count {
                        maxOf(Color.red(it), Color.green(it), Color.blue(it)) - minOf(Color.red(it), Color.green(it), Color.blue(it)) > 60
                    }
                    lastError = if (expected == null) 0.0 else pixelError(expected, pixels)
                    (lastColorCount > 500 && lastError < 18).also { if (it) result = pixels }
                } catch (error: Exception) { failure = error; false }
            }
        } catch (error: Throwable) {
            throw AssertionError("$stage 真实视频未呈现：$source,colorPixels=$lastColorCount,RGB meanError=$lastError,captureFailure=${failure?.javaClass?.simpleName}", error)
        }
        return checkNotNull(result)
    }

    private fun pixelError(before: IntArray, after: IntArray): Double = before.indices.sumOf { index ->
        val a = before[index]; val b = after[index]
        kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) +
            kotlin.math.abs(Color.blue(a) - Color.blue(b))
    }.toDouble() / (before.size * 3)

    /** The real libass Canvas renders independently of Media3 text cues. Call on Main only. */
    private fun assGlyphPixels(): Int {
        val view = findPlayerView(compose.activity.window.decorView)?.findViewWithTag<View>("pathfin.libass.overlay") ?: return 0
        if (view.width <= 0 || view.height <= 0) return 0
        val scale = minOf(1f, 640f / maxOf(view.width, view.height))
        val bitmap = Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1),
            (view.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap).apply { scale(scale, scale) }
            view.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { Color.alpha(it) > 180 && Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) > 180 }
        } finally { bitmap.recycle() }
    }

    private fun assertControlsFit() {
        val density = main { compose.activity.resources.displayMetrics.density }
        val tags = listOf("player-back", "player-play-pause", "player-seek-back", "player-seek-forward", "player-speed-increase", "player-speed-decrease",
            "player-lock", "player-audio", "player-subtitles", "player-aspect", "player-orientation", "player-chapters", "player-more")
        // Capture every coordinate on one Main turn. Sequential fetches can straddle the
        // orientation/insets commit and compare an old portrait frame with landscape buttons.
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true).fetchSemanticsNodes()
        val snapshot = main { nodes.mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { it to node.boundsInRoot }
        }.toMap() }
        val frame = checkNotNull(snapshot["player-controls"])
        val bounds = tags.associateWith { tag ->
            checkNotNull(snapshot[tag]) { "缺少可见播放器控件 $tag" }.also { rect ->
                assertTrue("$tag 点击宽度至少 48dp", rect.width >= density * 48 - 1)
                assertTrue("$tag 点击高度至少 48dp", rect.height >= density * 48 - 1)
                assertTrue("$tag 不能溢出播放器：control=$rect frame=$frame", rect.left >= frame.left - 1 && rect.right <= frame.right + 1 && rect.top >= frame.top - 1 && rect.bottom <= frame.bottom + 1)
            }
        }
        val controls = bounds.entries.toList()
        for (i in controls.indices) for (j in i + 1 until controls.size) {
            val a = controls[i]; val b = controls[j]
            val xOverlap = minOf(a.value.right, b.value.right) - maxOf(a.value.left, b.value.left)
            val yOverlap = minOf(a.value.bottom, b.value.bottom) - maxOf(a.value.top, b.value.top)
            assertFalse("${a.key} 与 ${b.key} 不能重叠", xOverlap > 1 && yOverlap > 1)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("player-time").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("播放时间不能被大字号截断", layouts.isNotEmpty() && layouts.none { it.didOverflowWidth || it.didOverflowHeight })
    }

    private fun physicalBack() = main {
        val activity = compose.activity
        val view = checkNotNull(findPlayerView(activity.window.decorView))
        view.isFocusableInTouchMode = true
        assertTrue(view.requestFocus())
        val time = SystemClock.uptimeMillis()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)))
    }

    private fun assertContinuous(active: ActiveFixture) {
        assertHealthy(active)
        main {
            assertSame("控制操作不能重建 ExoPlayer", active.player, active.playback.player)
            assertSame("控制操作不能重建播放会话", active.request, active.app.state.value.activePlayback)
            assertSame(active.app, ViewModelProvider(compose.activity)[AppViewModel::class.java])
            assertSame(active.playback, ViewModelProvider(compose.activity)[PlaybackViewModel::class.java])
            assertFalse(compose.activity.isFinishing)
        }
        drainReports(active.playback)
        assertEquals(1, eventCount("start", active.request.info.playSessionId))
        assertEquals(0, eventCount("stop", active.request.info.playSessionId))
    }

    private fun assertHealthy(active: ActiveFixture) {
        val error = main { active.player.playerError }
        val message = active.playback.state.value.errorMessage
        if (error != null || message != null) {
            val classes = generateSequence<Throwable>(error) { it.cause }.take(8).map { it.javaClass.name }.toList()
            throw AssertionError("合成多轨实际播放失败：errorCode=${error?.errorCode},causes=$classes,status=$message", error)
        }
    }

    private fun awaitClosed(active: ActiveFixture) {
        compose.waitUntil(WAIT_MS) { active.app.state.value.activePlayback == null && active.playback.player == null }
        drainReports(active.playback)
        main {
            assertEquals(Route.Detail(ITEM_ID), active.app.state.value.route)
            assertEquals(active.detailRoutes, active.app.state.value.routes)
            assertFalse(compose.activity.isFinishing)
        }
        assertEquals(1, eventCount("stop", active.request.info.playSessionId))
    }

    private fun tracks(player: ExoPlayer, type: Int): List<TrackRef> = buildList {
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == type) repeat(group.length) { index ->
                val format = group.getTrackFormat(index)
                add(TrackRef("player-track-$groupIndex:$index", format.id, format.label.orEmpty(), format.language, group.isTrackSelected(index)))
            }
        }
    }

    private fun fixtureStatus(): JSONObject = fixtureClient.newCall(Request.Builder().url("$FIXTURE_BASE/health").build()).execute().use {
        check(it.isSuccessful) { "合成多轨夹具未就绪" }
        JSONObject(checkNotNull(it.body).string()).also { json -> check(json.optBoolean("MultiTrackFixture")) }
    }

    private fun eventCount(event: String, session: String): Int {
        val events = fixtureStatus().getJSONArray("Events")
        return (0 until events.length()).count { index -> events.getJSONObject(index).let { it.optString("event") == event && it.optString("play_session_id") == session } }
    }

    private fun drainReports(playback: PlaybackViewModel) = runBlocking { withTimeout(WAIT_MS) { playback.awaitPendingReports() } }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    private data class TrackRef(val tag: String, val id: String?, val label: String, val language: String?, val selected: Boolean)
    private data class ActiveFixture(val app: AppViewModel, val playback: PlaybackViewModel, val player: ExoPlayer, val request: PlaybackRequest, val detailRoutes: List<Route>,
                                     val presentedFrames: AtomicLong = AtomicLong())

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val FIXTURE_BASE = "http://127.0.0.1:8101/jellyfin"
        const val ITEM_ID = "fixture-movie"
        const val WAIT_MS = 25_000L

        fun testPreferences() = AppPreferences(showClock = false, showBattery = false, subtitlesEnabled = false)

        fun clearSyntheticSession(context: Context) {
            val store = SessionStore(context)
            val profiles = store.loadProfiles()
            check(profiles.all { it.session.serverId == "pathfin-fixture-server" && it.session.accessToken == "fixture-token" }) {
                "verification 中存在非合成账号，拒绝清理或访问"
            }
            profiles.forEach { store.removeProfile(it.id) }
            store.clear()
        }

        fun findPlayerView(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
            return null
        }
    }
}

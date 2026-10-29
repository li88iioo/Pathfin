package dev.pathfin.android

import android.os.SystemClock
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.geometry.Rect
import android.os.Looper
import android.content.pm.ActivityInfo
import androidx.activity.enableEdgeToEdge
import dev.pathfin.android.ui.LocalPlaybackFullscreen
import androidx.compose.ui.test.isSelected
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.playback.PlaybackUiState
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.PlaybackEpisodesSheet
import dev.pathfin.android.ui.episodeTitle
import dev.pathfin.android.ui.player.PlayerControlsOverlay
import dev.pathfin.android.ui.player.PlayerResizeMode
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 保留长时间/大字号排版回归；已删除的功能不留适配参数。 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerControlsLayoutTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    @Test fun longTimeAtLargeFontFitsAndPositionUpdatesKeepTheSliderInPlace() {
        lateinit var player: ClockPlayer
        compose.runOnUiThread { player = ClockPlayer(40_000_000) }
        try {
            showControls(player)
            assertTimeFits()
            val before = compose.onNodeWithTag("player-seek-slider").readVerificationNode(compose) { it.boundsInRoot }
            compose.runOnUiThread { player.setPosition(35_000_000) }
            compose.waitForIdle()
            assertTimeFits()
            assertEquals(before, compose.onNodeWithTag("player-seek-slider").readVerificationNode(compose) { it.boundsInRoot })
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun portraitMoreKeepsUniqueQualityAndScreenshotAndUsesTheEpisodeTitleShortcut() {
        lateinit var player: ClockPlayer
        var episodes = 0
        var quality = 0
        var captures = 0
        var timer: Int? = null
        var gain = -1
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            player = ClockPlayer(40_000_000)
        }
        try {
            awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT)
            showControls(player, onEpisodes = { episodes++ }, onQuality = { quality++ }, onScreenshot = { captures++ },
                onSetSleepTimer = { timer = it }, onAudioGainChange = { gain = it })
            compose.onNodeWithTag("player-title").clickWhenStationary()
            compose.runOnIdle { assertEquals(1, episodes) }
            openPanel("player-more", "player-popup-more")
            assertNoDuplicateMenuActions()
            assertSettingsLayout(listOf("player-menu-information", "player-audio-gain", "player-tools", "player-screenshot"))
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-tools"))
            compose.onNodeWithTag("player-tools").clickWhenStationary()
            compose.runOnIdle { assertEquals(1, quality) }
            openPanel("player-more", "player-popup-more")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-screenshot"))
            assertNoDuplicateMenuActions()
            compose.onNodeWithTag("player-screenshot").clickWhenStationary()
            compose.runOnIdle { assertEquals(1, captures) }
            openPanel("player-more", "player-popup-more")
            compose.onNodeWithTag("player-audio-gain").clickWhenStationary()
            compose.onNodeWithTag("player-gain-reset").assertVerificationDisplayed(compose).clickWhenStationary()
            compose.runOnIdle { assertEquals(100, gain) }
            compose.onNodeWithTag("playback-panel-close").clickWhenStationary()
            openPanel("player-more", "player-popup-more")
            compose.onNodeWithTag("player-menu-information").clickWhenStationary()
            compose.onNodeWithTag("player-popup-info").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("player-info-close").clickWhenStationary()
            openPanel("player-more", "player-popup-more")
            compose.onNodeWithTag("player-sleep-timer").assertVerificationDisplayed(compose).clickWhenStationary()
            compose.onNodeWithTag("player-sleep-15").assertVerificationDisplayed(compose).clickWhenStationary()
            compose.runOnIdle { assertEquals(15, timer) }
        } finally {
            compose.runOnUiThread { player.release(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun landscapeMoreDoesNotRepeatTheVisibleCaptureShortcut() {
        lateinit var player: ClockPlayer
        var captures = 0
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            player = ClockPlayer(40_000_000)
        }
        try {
            awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            showControls(player, onScreenshot = { captures++ })
            compose.waitUntil(10_000) { compose.onNodeWithTag("player-controls").readVerificationNode(compose) { it.boundsInRoot }.let { it.width > it.height } }
            compose.onNodeWithTag("player-capture").clickWhenStationary()
            compose.runOnIdle { assertEquals(1, captures) }
            openPanel("player-more", "player-popup-more")
            assertSettingsLayout(listOf("player-menu-information", "player-audio-gain", "player-tools"))
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-tools"))
            assertNoDuplicateMenuActions()
            compose.onNodeWithTag("player-screenshot").assertDoesNotExist()
            compose.onNodeWithTag("player-tools").clickWhenStationary()
        } finally {
            compose.runOnUiThread { player.release(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun landscapeTitlePrefersTheLogoAndStatusSitsUnderTheRightToolbar() {
        lateinit var player: ClockPlayer
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            player = ClockPlayer(40_000_000)
        }
        try {
            awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            val bitmap = Bitmap.createBitmap(240, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val loaded = AtomicBoolean()
            val logo = coil3.request.ImageRequest.Builder(compose.activity).data(png)
                .listener(onSuccess = { _, _ -> loaded.set(true) }).build()
            showControls(player, clock = "21:30", battery = "80%", titleLogo = logo)
            compose.waitUntil(10_000) { loaded.get() }
            compose.onNodeWithTag("player-media-logo").assertVerificationDisplayed(compose)
            assertStatusUnderToolbar()
        } finally {
            compose.runOnUiThread { player.release(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun portraitStatusAlsoSitsUnderTheRightToolbarAndTitleFallsBackToText() {
        lateinit var player: ClockPlayer
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            player = ClockPlayer(40_000_000)
        }
        try {
            awaitOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT)
            showControls(player, clock = "21:30", battery = "80%")
            compose.onNodeWithTag("player-media-logo").assertDoesNotExist()
            compose.onNodeWithTag("player-media-title").assertDoesNotExist()
            compose.onNodeWithTag("player-title").assertVerificationDisplayed(compose).assertTextEquals("Synthetic fixture")
            assertStatusUnderToolbar()
        } finally {
            compose.runOnUiThread { player.release(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun failedLandscapeLogoKeepsTheTextTitleVisible() {
        lateinit var player: ClockPlayer
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            player = ClockPlayer(40_000_000)
        }
        try {
            awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            val failed = AtomicBoolean()
            val logo = coil3.request.ImageRequest.Builder(compose.activity).data(byteArrayOf(1, 2, 3))
                .listener(onError = { _, _ -> failed.set(true) }).build()
            showControls(player, titleLogo = logo)
            compose.waitUntil(10_000) { failed.get() }
            compose.onNodeWithTag("player-media-title").assertVerificationDisplayed(compose).assertTextEquals("Synthetic fixture")
        } finally {
            compose.runOnUiThread { player.release(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun pinchOutZoomsPinchInRestoresAndLockedScreenIgnoresIt() {
        val pinches = mutableListOf<Boolean>()
        compose.runOnUiThread {
            val player = ClockPlayer(40_000_000)
            val view = dev.pathfin.android.ui.GesturePlayerView(compose.activity).apply { bindPlayer(player); onPinch = { pinches += it } }
            // 间距取真实手指尺度：系统缩放识别要求双指至少相距约 27mm。
            pinch(view, from = 600f, to = 1100f)
            pinch(view, from = 1100f, to = 600f)
            view.gesturesLocked = true
            pinch(view, from = 600f, to = 1100f)
            view.bindPlayer(null); player.release()
        }
        assertEquals("张开切裁切、捏合回原始；每次手势只触发一次，锁屏不响应", listOf(true, false), pinches)
    }

    /** 两指沿水平方向从间距 [from] 变为 [to]，逐帧注入真实多指事件。 */
    private fun pinch(view: android.view.View, from: Float, to: Float) {
        val start = SystemClock.uptimeMillis()
        val properties = Array(2) { android.view.MotionEvent.PointerProperties().apply { id = it; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
        fun event(time: Long, action: Int, span: Float, count: Int = 2): android.view.MotionEvent {
            val coords = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply {
                x = 640f + (if (index == 0) -span else span) / 2; y = 500f; pressure = 1f; size = 1f } }
            return android.view.MotionEvent.obtain(start, time, action, count, properties.copyOf(count).requireNoNulls(), coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(event: android.view.MotionEvent) { view.dispatchTouchEvent(event); event.recycle() }
        send(event(start, android.view.MotionEvent.ACTION_DOWN, from, count = 1))
        send(event(start + 10, android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), from))
        for (step in 1..10) send(event(start + 10 + step * 16L, android.view.MotionEvent.ACTION_MOVE, from + (to - from) * step / 10))
        send(event(start + 200, android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), to))
        send(event(start + 210, android.view.MotionEvent.ACTION_UP, to, count = 1))
    }

    private fun assertStatusUnderToolbar() {
        val nodes = listOf("player-status-line", "player-more", "player-back").map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        val (status, more, back) = compose.runOnIdle { nodes.map { it.boundsInRoot } }
        val tolerance = 32f * compose.activity.resources.displayMetrics.density
        assertTrue("状态行应在顶栏下方：status=$status more=$more", status.top >= more.bottom - 1f)
        assertTrue("状态行应靠右对齐到工具栏：status=$status more=$more", kotlin.math.abs(status.right - more.right) <= tolerance)
        assertTrue("状态行不应再贴在左侧：status=$status back=$back", status.left > back.right)
    }

    private fun assertNoDuplicateMenuActions() {
        listOf("player-menu-aspect", "player-menu-speed", "player-episodes").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    private fun awaitOrientation(orientation: Int) {
        // ComponentActivity is recreated on rotation: publish test content only after its
        // replacement is resumed, otherwise the requested layout can discard the content.
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == orientation &&
                compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    @Test fun endedPlaybackKeepsSpeedWhenItsShortcutIsHidden() {
        lateinit var player: ClockPlayer
        var speed = 1f
        compose.runOnUiThread { player = ClockPlayer(40_000_000) }
        try {
            showControls(player, ended = true, onSpeed = { speed = it })
            compose.onNodeWithTag("player-speed").assertDoesNotExist()
            openPanel("player-more", "player-popup-more")
            compose.onNodeWithText("更多", substring = false).assertVerificationDisplayed(compose)
            assertLabelsFit("播放速度")
            compose.onNodeWithTag("player-menu-speed").clickWhenStationary()
            compose.onNodeWithTag("player-speed-0.5").clickWhenStationary()
            compose.runOnIdle { assertEquals(.5f, speed, .0001f) }
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun subtitleImportAndManagementStayAvailableWithoutTracksAndHaveNoDuplicateMoreEntry() {
        lateinit var player: ClockPlayer
        var imports = 0
        var management = 0
        compose.runOnUiThread { player = ClockPlayer(40_000_000) }
        try {
            showControls(player, onAddSubtitle = { imports++ }, onManageSubtitles = { management++ })
            openPanel("player-more", "player-popup-more")
            listOf("player-add-subtitle", "player-manage-subtitles", "player-subtitle-layout", "player-subtitle-delay").forEach {
                compose.onNodeWithTag(it).assertDoesNotExist()
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-tools"))
            compose.onNodeWithText("播放质量").assertExists()
            compose.onNodeWithTag("playback-panel-close").clickWhenStationary()
            compose.onNodeWithTag("player-subtitles").assertIsEnabled()
            openPanel("player-subtitles", "player-popup-subtitles")
            compose.onNodeWithTag("player-track-auto").assertDoesNotExist()
            compose.onNodeWithTag("player-track-off").assertDoesNotExist()
            compose.onNodeWithTag("player-add-subtitle").assertIsEnabled().clickWhenStationary()
            compose.runOnIdle { assertEquals(1, imports) }
            openPanel("player-subtitles", "player-popup-subtitles")
            compose.onNodeWithTag("player-manage-subtitles").assertIsEnabled().clickWhenStationary()
            compose.runOnIdle { assertEquals(1, management) }
            openPanel("player-subtitles", "player-popup-subtitles")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("player-subtitle-layout"))
            compose.onNodeWithTag("player-subtitle-layout").clickWhenStationary()
            compose.onNodeWithTag("player-subtitle-scale-slider").assertExists()
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun onlyConfirmedBurnedInPlaybackHidesTheSubtitleButton() {
        lateinit var player: ClockPlayer
        compose.runOnUiThread { player = ClockPlayer(40_000_000) }
        try {
            showControls(player, burnedIn = true, onAddSubtitle = {}, onManageSubtitles = {})
            compose.onNodeWithTag("player-subtitles").assertDoesNotExist()
            compose.onNodeWithTag("player-audio").assertExists()
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun portraitEpisodesKeepFiveColumnsAndSwitchTitlesWithoutLosingTheChosenSeason() =
        assertEpisodeLayouts(android.content.res.Configuration.ORIENTATION_PORTRAIT)

    @Test fun landscapeEpisodesKeepNumbersAndTitlesReachableAcrossSeasons() =
        assertEpisodeLayouts(android.content.res.Configuration.ORIENTATION_LANDSCAPE)

    private fun assertEpisodeLayouts(orientation: Int) {
        val episodes = (1..2).flatMap { season -> (1..12).map { number ->
            MediaItem("layout-s$season-e$number", "合成第${number}集", "Episode", indexNumber = number,
                parentIndexNumber = season, overview = "合成剧情", played = number < 3)
        } }
        val content = ContentState(items = episodes, total = episodes.size, nextIndex = episodes.size)
        val grid = mutableStateOf(true)
        val busy = mutableStateOf(false)
        val largeFont = InstrumentationRegistry.getArguments().getString("pathfin.largeFont") == "true"
        var selected: MediaItem? = null
        compose.runOnUiThread {
            compose.activity.requestedOrientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try {
            awaitOrientation(orientation)
            compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
            compose.setContent {
                CompositionLocalProvider(LocalPlaybackFullscreen provides true) {
                    PathfinTheme(ThemePreference.DARK) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            PlaybackEpisodesSheet(content, "layout-s2-e3", busy.value, onSelect = { selected = it },
                                onRefresh = {}, onMore = {}, onDismiss = {}, grid = grid.value,
                                onGridChange = { grid.value = it })
                        }
                    }
                }
            }
            VerificationDisplay.awaitWindowReady(compose, "episode-layout-$orientation", compose.onNodeWithTag("playback-episodes"))
            compose.onNodeWithTag("playback-episode-season/2").assertIsSelected()
            compose.onNodeWithTag("playback-episodes-layout").assertIsOff()
            compose.onNodeWithText("分集标题").assertVerificationDisplayed(compose)
            assertEpisodeCellsFit(episodes.filter { it.parentIndexNumber == 2 }.take(6), expectedColumns = if (largeFont) null else 5)
            compose.onNodeWithText(episodeTitle(episodes.first { it.id == "layout-s2-e3" })).assertIsNotEnabled()

            exportEpisodePanel(orientation, "grid")
            VerificationDisplay.awaitWindowReady(compose, "episode-layout-toggle", compose.onNodeWithTag("playback-episodes-layout"))
            compose.onNodeWithTag("playback-episodes-layout").clickWhenStationary()
            compose.waitUntil(5_000) { !grid.value }
            compose.onNodeWithTag("playback-episodes-layout").assertIsOn()
            exportEpisodePanel(orientation, "titles")

            assertLabelsFit("分集标题")
            compose.onNodeWithTag("playback-episode-season/2").assertIsSelected()
            compose.onNodeWithTag("playback-episodes").performScrollToIndex(0)
            val chosen = episodes.first { it.id == "layout-s2-e2" }
            compose.onNodeWithText(episodeTitle(chosen)).assertVerificationDisplayed(compose).assertIsEnabled().clickWhenStationary()
            compose.waitUntil(5_000) { compose.runOnIdle { selected == chosen } }
            compose.runOnIdle { assertEquals(chosen, selected) }
            assertLabelsFit(episodeTitle(chosen))
            if (largeFont) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(episodeTitle(chosen), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("选集弹窗必须使用真实系统大字号", layouts.isNotEmpty() && layouts.all { it.layoutInput.density.fontScale >= 1.5f })
            }

            compose.onNodeWithTag("playback-episode-season/1").clickWhenStationary()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("playback-episode-season/1") and isSelected()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("playback-episode-season/1").assertIsSelected()
            compose.onNodeWithTag("playback-episodes").performScrollToIndex(0)
            compose.onNodeWithText(episodeTitle(episodes.first())).assertVerificationDisplayed(compose)
            VerificationDisplay.awaitWindowReady(compose, "episode-layout-toggle", compose.onNodeWithTag("playback-episodes-layout"))
            compose.onNodeWithTag("playback-episodes-layout").clickWhenStationary()
            compose.waitUntil(5_000) { grid.value }
            compose.onNodeWithTag("playback-episodes-layout").assertIsOff()
            compose.onNodeWithTag("playback-episode-season/1").assertIsSelected()
            assertEpisodeCellsFit(episodes.take(6))
            compose.runOnIdle { busy.value = true }
            compose.onNodeWithText(episodeTitle(episodes.first())).assertIsNotEnabled()
            compose.runOnIdle { busy.value = false }
            compose.onNodeWithText(episodeTitle(episodes.first())).assertIsEnabled().clickWhenStationary()
            compose.runOnIdle { assertEquals(episodes.first(), selected) }
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private fun exportEpisodePanel(orientation: Int, mode: String) {
        if (InstrumentationRegistry.getArguments().getString("pathfin.episodeScreenshots") != "true") return
        val direction = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val bitmap = compose.onNodeWithTag("player-side-panel").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "episode-$direction-$mode.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun assertEpisodeCellsFit(items: List<MediaItem>, expectedColumns: Int? = null) {
        compose.waitUntil(5_000) {
            items.all {
                val nodes = compose.onAllNodesWithText(episodeTitle(it)).fetchSemanticsNodes()
                nodes.isNotEmpty() && isVerificationNodeDisplayed(compose, nodes.single())
            }
        }
        val nodes = items.map { compose.onNodeWithText(episodeTitle(it)).assertVerificationDisplayed(compose).fetchSemanticsNode() }
        val bounds = compose.runOnIdle { nodes.map { it.boundsInRoot } }
        val firstRow = bounds.takeWhile { kotlin.math.abs(it.top - bounds.first().top) < 1f }
        if (expectedColumns != null) assertEquals("标准字号的数字模式每行必须有五集", expectedColumns, firstRow.size)
        firstRow.zipWithNext().forEach { (left, right) -> assertTrue("数字格不能重叠", left.right <= right.left + 1f) }
        if (bounds.size > firstRow.size) assertTrue("换行后的数字格不能重叠", bounds[firstRow.size].top >= firstRow.maxOf { it.bottom } - 1f)
        items.forEach { assertLabelsFit(it.indexNumber.toString()) }
    }

    private fun assertSettingsLayout(expectedTags: List<String>) {
        compose.onNodeWithText("更多", substring = false).assertVerificationDisplayed(compose)
        val shortcutTags = expectedTags + "player-audio-only"
        shortcutTags.forEach { tag ->
            assertEquals("快捷入口必须唯一：$tag", 1, compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
            compose.onNodeWithTag(tag).assertVerificationDisplayed(compose).assertIsEnabled()
        }
        compose.onNodeWithTag("player-sleep-timer").assertVerificationDisplayed(compose)
        val nodes = (shortcutTags + "player-sleep-timer").map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        val bounds = compose.runOnIdle { nodes.map { it.boundsInRoot } }
        val shortcuts = bounds.dropLast(1)
        val timer = bounds.last()
        // 快捷入口按每行最多四个排列：同一行顶部对齐、互不重叠，行与行上下不重叠。
        val rows = shortcuts.groupBy { bound -> shortcuts.first { kotlin.math.abs(it.top - bound.top) < 1f }.top }.toSortedMap().values.toList()
        rows.forEach { row ->
            assertTrue("每行最多四个快捷入口", row.size <= 4)
            row.sortedBy { it.left }.zipWithNext().forEach { (left, right) -> assertTrue("快捷触摸区域不能重叠", left.right <= right.left + 1f) }
        }
        rows.zipWithNext().forEach { (upper, lower) -> assertTrue("快捷入口的行不能重叠", lower.minOf { it.top } >= upper.maxOf { it.bottom } - 1f) }
        assertTrue("定时关闭必须位于快捷入口下方", timer.top >= shortcuts.maxOf { it.bottom } - 1f)
        assertTrue("定时关闭应使用整行触摸区域", timer.width > shortcuts.maxOf { it.width } * 2f)
        assertLabelsFit("媒体信息", "音量增益", "播放质量", "仅播放音频", "定时关闭")
        if ("player-screenshot" in shortcutTags) assertLabelsFit("保存截图")
    }

    // simple Text 的语义结果按父约束重建段落；用实际行字形宽度判断，避免把空白容器当成溢出。
    private fun assertLabelsFit(vararg labels: String) {
        labels.forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val diagnostics = results.mapIndexed { index, result ->
                "#$index(size=${result.size}, paragraph=${result.multiParagraph.width}×${result.multiParagraph.height}, " +
                    "lines=${result.lineCount}, overflowW=${result.didOverflowWidth}, overflowH=${result.didOverflowHeight}, " +
                    "maxLines=${result.layoutInput.maxLines}, " +
                    "constraints=${result.layoutInput.constraints}, fontSize=${result.layoutInput.style.fontSize}, " +
                    "lineHeight=${result.layoutInput.style.lineHeight}, fontScale=${result.layoutInput.density.fontScale}, " +
                    "ellipsized=${(0 until result.lineCount).filter(result::isLineEllipsized)})"
            }
            assertTrue("标签必须完整排版：$label results=${results.size} $diagnostics",
                results.isNotEmpty() && results.all { result ->
                    !result.didOverflowHeight && (0 until result.lineCount).all { line ->
                        !result.isLineEllipsized(line) && result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 1f
                    }
                })
        }
    }

    // Dialog 与内容各自有进场动画；真实触摸前等目标坐标稳定，避免用上一帧坐标点击。
    private fun SemanticsNodeInteraction.clickWhenStationary(): SemanticsNodeInteraction {
        VerificationDisplay.awaitWindowReady(compose, "player-touch-target", this)
        var previous: Rect? = null
        var stableSince = 0L
        compose.waitUntil(5_000) {
            val bounds = readVerificationNode(compose) { it.boundsInWindow }
            val now = SystemClock.uptimeMillis()
            if (bounds != previous) {
                previous = bounds
                stableSince = now
            }
            bounds.width > 0f && bounds.height > 0f && now - stableSince >= 80L
        }
        return performVerificationClick(compose)
    }

    private fun openPanel(button: String, panel: String) {
        VerificationDisplay.awaitWindowReady(compose, "before-$panel", compose.onNodeWithTag(button))
        compose.onNodeWithTag(button).clickWhenStationary()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(panel).fetchSemanticsNodes().size == 1 }
        VerificationDisplay.awaitWindowReady(compose, panel, compose.onNodeWithTag(panel))
    }

    private fun showControls(player: Player, onEpisodes: (() -> Unit)? = null,
        onQuality: () -> Unit = {}, onScreenshot: (() -> Unit)? = null,
        ended: Boolean = false, onSpeed: (Float) -> Unit = {}, burnedIn: Boolean = false,
        onAddSubtitle: (() -> Unit)? = null, onManageSubtitles: (() -> Unit)? = null,
        onSetSleepTimer: (Int?) -> Unit = {}, onAudioGainChange: (Int) -> Unit = {},
        clock: String? = null, battery: String? = null, titleLogo: coil3.request.ImageRequest? = null) {
        val loader = coil3.ImageLoader.Builder(compose.activity).diskCache(null).build()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalPlaybackFullscreen provides true,
                dev.pathfin.android.ui.LocalPosterLoader provides loader) {
                PathfinTheme(ThemePreference.DARK) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        PlayerControlsOverlay(player, PlaybackUiState(title = "Synthetic fixture", ended = ended, subtitlesBurnedIn = burnedIn), false, PlayerResizeMode.FIT,
                            seekSeconds = 10, clock = clock, battery = battery, onBack = {}, onInteraction = {},
                            onKeepVisibleChange = {}, onTogglePlay = {}, onSeekBack = {}, onSeekForward = {},
                            onSeekTo = {}, onSpeedChange = onSpeed, onResizeModeChange = {}, onLock = {}, onShowQuality = onQuality,
                            onShowEpisodes = onEpisodes, onScreenshot = onScreenshot, onAddSubtitle = onAddSubtitle, onManageSubtitles = onManageSubtitles,
                            onSetSleepTimer = onSetSleepTimer, onAudioGainChange = onAudioGainChange, titleLogo = titleLogo)
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "controls-first-draw", compose.onNodeWithTag("player-controls"))
    }

    private fun assertTimeFits() {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("player-time").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        assertTrue("完整时间必须能实际排版，不允许以省略号掩盖截断", results.none { it.didOverflowWidth || it.didOverflowHeight })
    }

    private class ClockPlayer(private val durationMs: Long) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var position = 2_000L
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TRACKS).build())
            .setPlaylist(listOf(MediaItemData.Builder("synthetic-fixture").setDurationUs(durationMs * 1_000).build()))
            .setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(position).build()
        fun setPosition(value: Long) { position = value; invalidateState() }
    }
}

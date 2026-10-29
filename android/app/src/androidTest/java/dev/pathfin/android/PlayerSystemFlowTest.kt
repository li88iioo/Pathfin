package dev.pathfin.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ContentUris
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 真系统 PiP/RemoteAction、MediaStore 截图、触摸手势和章节 Seek；无播放器或回调替身。
 * 仅 verification；需要 prepare_multitrack_fixture.py 8101/8102，PiP 必须在支持且解锁设备运行。
 * 不改全局亮度或方向，恢复媒体音量/窗口方向，截图仅删除本次点击产生的独立 URI。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerSystemFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var automation: UiAutomation? = null
    private var originalAccessibilityFlags: Int? = null
    // 仅跨窗口系统动作测试取得 Instrumentation 的默认连接。
    // 缓存同一对象，不能用不同连接flags触发 Instrumentation 先disconnect再立即connect。
    private val ui: UiAutomation get() = automation ?: connectSystemAutomation()
    private val currentActivity = AtomicReference<MainActivity?>()
    private val fixtureClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolatedStorage = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                check(context.packageName == TARGET) { "系统播放回归只允许 verification 包" }
                check(fixtureStatus().optBoolean("MultiTrackFixture")) { "先启动独立 8101/8102 合成媒体，禁止用户片源" }
                val audio = context.getSystemService(AudioManager::class.java)
                val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val callbacks = object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) currentActivity.set(activity) }
                    override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) currentActivity.set(activity) }
                    override fun onActivityDestroyed(activity: Activity) { if (activity is MainActivity) currentActivity.compareAndSet(activity, null) }
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                }
                val application = context.applicationContext as Application
                val store = AppPreferencesStore(context)
                val previous = store.load()
                clearSyntheticSession()
                store.save(preferences())
                application.registerActivityLifecycleCallbacks(callbacks)
                try { base.evaluate() }
                finally {
                    application.unregisterActivityLifecycleCallbacks(callbacks)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                    clearSyntheticSession()
                    store.save(previous)
                    fixtureClient.connectionPool.evictAll()
                    fixtureClient.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    private val systemEnvironment = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                // 必须位于 compose 内层：恢复无障碍属性时 Activity/测试环境尚未 teardown。
                main { assertSame(compose.activity, host()) }
                var failure: Throwable? = null
                try {
                    base.evaluate()
                }
                catch (error: Throwable) { failure = error; throw error }
                finally {
                    val connected = automation
                    val oldFlags = originalAccessibilityFlags
                    try {
                        if (connected != null && oldFlags != null)
                            connected.serviceInfo = connected.serviceInfo.apply { flags = oldFlags }
                    } catch (error: Throwable) {
                        failure?.addSuppressed(error) ?: throw error
                    } finally {
                        // 对象由 Instrumentation 所有；不 destroy 全局连接影响后续测试。
                        automation = null
                        originalAccessibilityFlags = null
                    }
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose).around(systemEnvironment)

    @Test fun realPipRemoteActionsRestoreTheSameTaskAndClosingReleasesThePlayer() = withSystemPlayback { active ->
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            "设备不支持画中画；此项必须在支持 PiP 的设备/模拟器运行，不能静默跳过"
        }
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            "系统锁屏可能拒绝进入 PiP；请解锁设备后重跑，不能用显示在锁屏上代替 PiP 验证"
        }
        val task = main { host().taskId }
        enterPip()
        val initialPosition = main { active.player.currentPosition }
        await("PiP 内视频必须继续实际播放") { healthy(active); main { active.player.isPlaying && active.player.currentPosition > initialPosition + 300 } }
        clickPipAction("暂停播放") { matchesPipLabel(it, setOf("暂停播放", "暂停", "Pause", "Pause playback")) }
        await("SystemUI 必须执行真实暂停 RemoteAction") { main { !active.player.playWhenReady && !active.player.isPlaying } }
        val paused = main { active.player.currentPosition }
        SystemClock.sleep(300)
        assertTrue("PiP 暂停不能只换图标", abs(main { active.player.currentPosition } - paused) < 200)
        clickPipAction("继续播放") { matchesPipLabel(it, setOf("继续播放", "播放", "Resume", "Resume playback", "Play")) }
        await("SystemUI 继续 RemoteAction 必须恢复实际进度") { healthy(active); main { active.player.isPlaying && active.player.currentPosition > paused + 300 } }
        assertContinuous(active)

        // 通过系统任务管理恢复，不能用直接改 Activity lifecycle 的替身。
        val appTask = context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo?.taskId == task }
        appTask.moveToFront()
        await("恢复同一 task 后必须退出 PiP 且重新前台") { main { currentActivity.get()?.let {
            !it.isInPictureInPictureMode && it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && it.taskId == task
        } == true } }
        assertContinuous(active)
        assertEquals("恢复不能新建 Activity task", task, main { host().taskId })
        ensureControls()
        compose.onNodeWithTag("player-back").fetchSemanticsNode()

        enterPip()
        main { active.playback.setControlsLocked(true) }
        clickPipAction("关闭画中画") {
            val id = it.viewIdResourceName.orEmpty()
            val label = it.contentDescription?.toString().orEmpty()
            id.endsWith(":id/dismiss") || id.endsWith(":id/close") ||
                label in setOf("关闭", "关闭画中画", "关闭画中画窗口", "Close", "Close picture-in-picture")
        }
        try {
            await("系统关闭 PiP 后必须清空播放会话、释放原播放器并移除可见窗口") {
                closedPipResourcesAndHostReleased(active) && !hasVisibleVerificationWindow()
            }
        } catch (error: AssertionError) {
            reportClosedPipState(active, "关闭后释放超时")
            throw error
        }
        drainReports(active.playback)
        assertEquals(1, eventCount("start", active.request.info.playSessionId))
        assertEquals("系统关闭只能上报一次停止", 1, eventCount("stop", active.request.info.playSessionId))
        val progressAfterStop = eventCount("progress", active.request.info.playSessionId)
        // Android 可以缓存已停止的 Activity；缓存窗口不能保留或重新建立播放资源。
        SystemClock.sleep(500)
        if (currentActivity.get() != null) compose.mainClock.advanceTimeByFrame()
        assertTrue("PiP 已关闭后不能重新建立播放会话或恢复可见窗口",
            closedPipResourcesAndHostReleased(active) && !hasVisibleVerificationWindow())
        assertEquals("PiP 关闭后不能继续上报播放进度", progressAfterStop, eventCount("progress", active.request.info.playSessionId))
        assertEquals("PiP 关闭后不能重新开始播放", 1, eventCount("start", active.request.info.playSessionId))
        assertEquals("幂等清理不能重复上报停止", 1, eventCount("stop", active.request.info.playSessionId))
        reportClosedPipState(active, "关闭后释放通过")
    }

    @Test fun pipPreferenceDoesNotTurnSystemPlayerBackIntoHomeOrCloseTheTask() = withSystemPlayback(preferences().copy(backgroundPlaybackBehavior = "PIP")) { active ->
        val task = main { host().taskId }
        assertTrue(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        await("返回播放器必须停留在同一任务详情页，不能变成Home或画中画") {
            main { active.app.state.value.activePlayback == null && active.app.state.value.route == Route.Detail(ITEM) &&
                host().taskId == task && !host().isFinishing && !host().isInPictureInPictureMode && active.playback.player == null }
        }
    }

    @Test fun configuredHomePauseStopsPlaybackAndRestoresTheSameTask() = withSystemPlayback { active ->
        val task = main { host().taskId }
        val appTask = context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo?.taskId == task }
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        try {
            assertTrue(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            await("默认回桌面必须暂停实际播放") { main { !active.player.playWhenReady && !active.player.isPlaying && !host().isInPictureInPictureMode } }
            val paused = main { active.player.currentPosition }
            SystemClock.sleep(300)
            assertTrue(abs(main { active.player.currentPosition } - paused) < 200)
        } finally {
            appTask.moveToFront()
            await("返回同一任务") { main { currentActivity.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true } }
        }
        assertContinuous(active)
    }

    @Test fun configuredHomePipEntersActualSystemPipAndContinuesPlayback() = withSystemPlayback(preferences().copy(backgroundPlaybackBehavior = "PIP")) { active ->
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val task = main { host().taskId }
        val appTask = context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo?.taskId == task }
        val before = main { active.player.currentPosition }
        try {
            assertTrue(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            await("真实Home动作必须按设置进入系统画中画") { main { host().isInPictureInPictureMode && active.player.isPlaying && active.player.currentPosition > before + 300 } }
        } finally {
            appTask.moveToFront()
            await("画中画恢复同一窗口") { main { currentActivity.get()?.let { !it.isInPictureInPictureMode && it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) } == true } }
        }
        assertContinuous(active)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun screenshotButtonWritesTheActualVideoPngAndOnlyItsNewUriIsDeleted() = withPlayback { active ->
        ensureControls()
        compose.onNodeWithTag("player-play-pause").performVerificationClick(compose)
        await("截图前先暂停实际视频") { main { !active.player.isPlaying && !active.player.playWhenReady } }
        val before = ownScreenshots().keys
        val created = linkedSetOf<Uri>()
        try {
            compose.onNodeWithTag("player-more").performVerificationClick(compose)
            compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasTestTag("player-screenshot"))
            compose.onNodeWithTag("player-screenshot").performVerificationClick(compose)
            var saved: StoredScreenshot? = null
            await("截图按钮必须实际创建并完成 MediaStore PNG") {
                val images = ownScreenshots().filterKeys { it !in before }
                created += images.keys
                assertTrue("单次截图不能创建多个文件", images.size <= 1)
                saved = images.values.singleOrNull()?.takeIf { !it.pending && it.bytes > 1_024 }
                saved != null
            }
            val image = checkNotNull(saved)
            assertEquals("image/png", image.mime)
            assertEquals(TARGET, image.owner)
            context.contentResolver.openInputStream(image.uri)!!.use { input ->
                val signature = ByteArray(8)
                var count = 0
                while (count < signature.size) {
                    val read = input.read(signature, count, signature.size - count)
                    check(read > 0) { "PNG 文件不足8字节" }; count += read
                }
                assertArrayEquals("必须是实际PNG文件", byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), signature)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(image.uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue("截图必须具有视频尺寸", bounds.outWidth >= 100 && bounds.outHeight >= 100)
            assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216)
            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 768).coerceAtLeast(1) }
            val bitmap = checkNotNull(context.contentResolver.openInputStream(image.uri).use { BitmapFactory.decodeStream(it, null, options) })
            try {
                val colors = linkedSetOf<Int>()
                var chromatic = 0
                for (y in 1..20) for (x in 1..20) {
                    val pixel = bitmap.getPixel(x * (bitmap.width - 1) / 21, y * (bitmap.height - 1) / 21)
                    colors += pixel
                    if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 40) chromatic++
                }
                assertTrue("自制testsrc视频不能保存为黑图或空白控制层", colors.size > 12 && chromatic > 20)
            } finally { bitmap.recycle() }
            assertContinuous(active)
        } finally {
            created += ownScreenshots().keys.filter { it !in before }
            for (uri in created) {
                val own = ownScreenshots()[uri]
                check(own != null && own.owner == TARGET) { "只能删除本次验证包创建的截图" }
                assertEquals(1, context.contentResolver.delete(uri, null, null))
            }
            assertTrue("仅本次新增图片应被清理", ownScreenshots().keys.none { it in created })
        }
    }

    @Test fun realTouchGesturesChangeLevelsAndTemporarySpeedAndCancellationRestoresThem() = withPlayback { active ->
        val audio = context.getSystemService(AudioManager::class.java)
        check(!audio.isVolumeFixed) { "设备固定媒体音量，需可调音量设备执行真实音量手势回归" }
        val systemBrightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        val previousBrightness = main { host().window.attributes.screenBrightness }
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            hideControls()
            val video = videoBounds()
            main { host().window.attributes = host().window.attributes.apply { screenBrightness = .4f } }
            val volumeBeforeLeft = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            TouchSequence(video, .2f, .65f).use { touch ->
                touch.move(.2f, .4f)
                await("左侧上滑必须改变实际窗口亮度") { main { host().window.attributes.screenBrightness > .6f } }
                touch.finish()
            }
            assertTrue(main { host().window.attributes.screenBrightness > .6f })
            assertEquals("亮度手势不能修改媒体音量", volumeBeforeLeft, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("只允许窗口亮度，不能改系统全局设置", systemBrightness, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1))
            main { host().window.attributes = host().window.attributes.apply { screenBrightness = .4f } }
            TouchSequence(video, .2f, .65f).use { touch ->
                touch.move(.2f, .4f)
                await("取消前必须真实改变亮度") { main { host().window.attributes.screenBrightness > .6f } }
                touch.cancel()
            }
            assertEquals("ACTION_CANCEL恢复开始前窗口亮度", .4f, main { host().window.attributes.screenBrightness }, .01f)

            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            check(maximum >= 3) { "媒体音量档位不足以验证真实变化" }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, maximum / 2, 0)
            val baseline = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            check(baseline > 0) { "系统策略禁止测试设置有效音量，请在可调媒体音量设备运行" }
            TouchSequence(video, .8f, .4f).use { touch ->
                touch.move(.8f, .68f)
                await("右侧下滑必须改变实际系统媒体音量") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) < baseline }
                touch.cancel()
            }
            assertEquals("ACTION_CANCEL恢复开始前媒体音量", baseline, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            TouchSequence(video, .8f, .4f).use { touch ->
                touch.move(.8f, .68f)
                await("完成前必须真实改变音量") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) < baseline }
                touch.finish()
            }
            assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) < baseline)
            assertEquals("音量手势不能修改亮度", .4f, main { host().window.attributes.screenBrightness }, .01f)

            main { active.player.setPlaybackSpeed(1.25f) }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            TouchSequence(video, .45f, .3f).use { touch ->
                await("真实长按必须进入临时倍速") { healthy(active); main { active.playback.state.value.temporarySpeed == 3.125f && active.player.playbackParameters.speed == 3.125f } }
                touch.move(.72f, .3f)
                await("长按后横移必须继续改变实际速度") { main { active.playback.state.value.temporarySpeed?.let { it > 3.125f && it == active.player.playbackParameters.speed } == true } }
                touch.finish()
            }
            await("松手恢复原实际速度") { main { active.playback.state.value.temporarySpeed == null && active.player.playbackParameters.speed == 1.25f } }
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            TouchSequence(video, .5f, .3f).use { touch ->
                await("第二次长按必须实际启用") { main { active.player.playbackParameters.speed == 3.125f } }
                touch.move(.25f, .3f)
                await("向左拖动必须减少临时速度") { main { active.playback.state.value.temporarySpeed?.let { it < 3.125f && it == active.player.playbackParameters.speed } == true } }
                touch.cancel()
            }
            await("取消也必须恢复原速度而非固定1倍") { main { active.playback.state.value.temporarySpeed == null && active.player.playbackParameters.speed == 1.25f && active.player.isPlaying } }
            assertContinuous(active)
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            main { currentActivity.get()?.let { it.window.attributes = it.window.attributes.apply { screenBrightness = previousBrightness } } }
        }
    }

    @Test fun chapterSheetSeeksTheActualPlayerToTheSecondChapterWithoutReplacingTheSession() = withPlayback { active ->
        ensureControls()
        compose.onNodeWithTag("player-play-pause").performVerificationClick(compose)
        await("跳章节前实际暂停") { main { !active.player.playWhenReady && !active.player.isPlaying } }
        assertTrue(active.request.item.chapters.any { it.name == "测试章节二" && it.startPositionTicks == 600_000_000L })
        compose.onNodeWithTag("player-chapters").performVerificationClick(compose)
        compose.onNodeWithText("测试章节二").performVerificationClick(compose)
        await("选择章节二必须实际 Seek 到60秒") { healthy(active); main { active.player.playbackState == Player.STATE_READY && active.player.currentPosition in 59_750..60_750 } }
        main { assertFalse("章节选择保留原暂停状态", active.player.playWhenReady) }
        assertContinuous(active)
    }

    private fun withSystemPlayback(config: AppPreferences = preferences(), block: (ActiveFixture) -> Unit) {
        // 只由真实 PiP/Home/系统返回用例申请跨窗口连接；应用内用例不注册无障碍服务。
        // 冷连接可能超过长按阈值，必须在创建任何 MotionEvent/downTime 之前完成。
        connectSystemAutomation()
        withPlayback(config, block)
    }

    private fun withPlayback(config: AppPreferences = preferences(), block: (ActiveFixture) -> Unit) {
        val app = main { ViewModelProvider(host())[AppViewModel::class.java] }
        val playback = main { ViewModelProvider(host())[PlaybackViewModel::class.java] }
        val previousOrientation = main { host().requestedOrientation }
        var active: ActiveFixture? = null
        try {
            main {
                host().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                app.setPreferences(config)
                app.login(BASE, "pathfin", "pathfin", true, "Synthetic system-player fixture")
            }
            awaitForeground("合成媒体登录和首页") {
                app.state.value.loginError?.let { throw AssertionError("合成登录失败：$it") }
                app.state.value.home.error?.let { throw AssertionError("合成首页失败：$it") }
                app.state.value.session != null && app.state.value.home.loaded && !app.state.value.home.loading
            }
            main { app.open((app.state.value.home.libraryItems.values.flatten() + app.state.value.home.resume + app.state.value.home.recent).first { it.id == ITEM }) }
            awaitForeground("合成详情") { app.state.value.route == Route.Detail(ITEM) && !app.state.value.detailLoading && ITEM in app.state.value.details }
            main { app.preparePlayback(app.state.value.details.getValue(ITEM), fromStart = true) }
            awaitForeground("实际ExoPlayer首帧") {
                app.state.value.playbackError?.let { throw AssertionError("合成起播失败：$it") }
                playback.state.value.errorMessage?.let { throw AssertionError("合成播放失败：$it", main { playback.player?.playerError }) }
                app.state.value.activePlayback != null && playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
            }
            val request = checkNotNull(app.state.value.activePlayback)
            check(request.stream.uri == "http://127.0.0.1:8102/sample.mp4" && request.session.accessToken == "fixture-token")
            val current = ActiveFixture(app, playback, main { checkNotNull(playback.player) }, request)
            active = current
            awaitForeground("实际媒体时长") { main { current.player.duration in 119_000..121_000 } }
            if (automation != null) dismissKnownLetterboxEducation()
            // 首帧 StateFlow 就绪之后，提交 Compose 的实际 PiP 参数与窗口更新，再发系统输入。
            compose.waitForIdle()
            block(current)
        } finally {
            main {
                playback.stop()
                if (app.state.value.activePlayback != null) app.playerClosed(playback.lastPositionMs)
                currentActivity.get()?.requestedOrientation = previousOrientation
            }
            drainReports(playback)
            active?.let { assertEquals("系统流程不能重复停止报告", 1, eventCount("stop", it.request.info.playSessionId)) }
        }
    }

    private fun host(): MainActivity = checkNotNull(currentActivity.get()) { "验证Activity已销毁" }
    @Synchronized private fun connectSystemAutomation(): UiAutomation {
        automation?.let { return it }
        check(currentActivity.get() != null) { "系统输入服务只能在测试 Activity 启动后准备" }
        val connected = try {
            // AOSP Instrumentation.getUiAutomation(flags) 在flags变化时先断开再重连；
            // Compose/Espresso 使用默认0。切成DONT_SUPPRESS会与系统尚未注销的服务冲突。
            checkNotNull(instrumentation.uiAutomation)
                .also { originalAccessibilityFlags = it.serviceInfo.flags }
        } catch (error: RuntimeException) {
            throw AssertionError("实际 UiAutomation 无障碍连接失败；SystemUI 动作尚未验证", error)
        }
        connected.serviceInfo = connected.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        automation = connected
        return connected
    }
    private fun ensureControls() {
        if (compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("player-video").performVerificationTouchInput(compose) { click(Offset(width * .5f, height * .3f)) }
        compose.waitUntil(WAIT) { compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun hideControls() {
        if (compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("player-video").performVerificationTouchInput(compose) { click(Offset(width * .5f, height * .3f)) }
        compose.waitUntil(WAIT) { compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isEmpty() }
    }
    private fun enterPip() {
        ensureControls()
        compose.onNodeWithTag("player-pip").performVerificationClick(compose)
        await("系统必须实际进入PiP；锁屏/AppOps拒绝不能当作通过") { main { currentActivity.get()?.isInPictureInPictureMode == true } }
    }

    /** 只关闭 Android 首次窄屏兼容引导；不能把任意系统确认框当作测试障碍点掉。 */
    private fun dismissKnownLetterboxEducation() {
        check(context.packageName == TARGET)
        val button = findSystemNode { node ->
            node.viewIdResourceName in SYSTEM_UI_PACKAGES.map { "$it:id/letterbox_education_dialog_dismiss_button" } &&
                node.isEnabled && node.isClickable
        } ?: return
        val packageName = checkNotNull(button.packageName?.toString())
        check(packageName in SYSTEM_UI_PACKAGES)
        val systemApp = context.packageManager.getApplicationInfo(packageName, 0)
        check(systemApp.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        val windowId = button.windowId
        assertTrue("必须实际点击已识别的 Android letterbox 引导关闭按钮", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Android letterbox 首次引导窗口关闭后才能开始真实播放器交互", 5_000) {
            ui.windows.none { it.id == windowId }
        }
        Log.i("PathfinSystemFlow", "已关闭系统 letterbox 首次引导；验证播放器真实输入")
    }
    private fun assertContinuous(active: ActiveFixture) {
        healthy(active)
        main {
            assertSame(active.player, active.playback.player)
            assertSame(active.request, active.app.state.value.activePlayback)
            assertSame(active.app, ViewModelProvider(host())[AppViewModel::class.java])
            assertSame(active.playback, ViewModelProvider(host())[PlaybackViewModel::class.java])
            assertFalse(host().isFinishing)
        }
        drainReports(active.playback)
        assertEquals(1, eventCount("start", active.request.info.playSessionId))
        assertEquals(0, eventCount("stop", active.request.info.playSessionId))
    }
    private fun healthy(active: ActiveFixture) {
        active.playback.state.value.errorMessage?.let { throw AssertionError("实际播放失败：$it", main { active.player.playerError }) }
    }
    private fun videoBounds(): Rect = main {
        fun find(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        val view = checkNotNull(find(host().window.decorView))
        val origin = IntArray(2); view.getLocationOnScreen(origin)
        Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height).also { check(!it.isEmpty) }
    }

    private inner class TouchSequence(private val bounds: Rect, x: Float, y: Float, crossWindow: Boolean = false) : Closeable {
        // PiP 菜单属于系统窗口，必须先取得跨窗口连接再取时间；应用内触摸直接经 InputManager 注入。
        private val crossWindowInput = if (crossWindow) ui else null
        private val down = SystemClock.uptimeMillis()
        private var px = bounds.left + bounds.width() * x
        private var py = bounds.top + bounds.height() * y
        private var finished = false
        init { send(MotionEvent.ACTION_DOWN) }
        fun move(x: Float, y: Float) {
            val fromX = px; val fromY = py
            val toX = bounds.left + bounds.width() * x; val toY = bounds.top + bounds.height() * y
            for (step in 1..8) {
                px = fromX + (toX - fromX) * step / 8; py = fromY + (toY - fromY) * step / 8
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE)
            }
        }
        fun finish() {
            if (!finished) {
                // PiP 菜单使用真实短触，不发同一毫秒的 DOWN/UP。
                val remaining = 64L - (SystemClock.uptimeMillis() - down)
                if (remaining > 0) SystemClock.sleep(remaining)
                send(MotionEvent.ACTION_UP); finished = true
            }
        }
        fun cancel() { if (!finished) { send(MotionEvent.ACTION_CANCEL); finished = true } }
        private fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                if (crossWindowInput != null)
                    assertTrue("系统必须注入真实触摸事件 action=$action down=$down event=${event.eventTime}", crossWindowInput.injectInputEvent(event, true))
                else
                    instrumentation.sendPointerSync(event)
            }
            finally { event.recycle() }
        }
        override fun close() = cancel()
    }

    private fun clickPipAction(label: String, match: (AccessibilityNodeInfo) -> Boolean) {
        try {
            if (findSystemNode(match) == null) {
                var stable: Rect? = null
                var stableAt = 0L
                await("系统必须暴露稳定的当前应用PiP窗口", 5_000) {
                    val current = pipWindowBounds()
                    val now = SystemClock.uptimeMillis()
                    if (current == null || current != stable) { stable = current; stableAt = now }
                    // PiP 容器边界会先于 SystemUI 菜单转为可触摸稳定；覆盖进入/展开动画后只点一次。
                    current != null && now - stableAt >= 600
                }
                TouchSequence(checkNotNull(stable), .5f, .5f, crossWindow = true).use { it.finish() }
            }
            var found: AccessibilityNodeInfo? = null
            await("SystemUI 必须展示本次实际RemoteAction：$label", 5_000) { found = findSystemNode(match); found != null }
            var node = checkNotNull(found)
            repeat(4) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node = node.parent ?: throw AssertionError("PiP动作没有可点击节点：$label")
            }
            throw AssertionError("无法点击SystemUI PiP动作：$label")
        } catch (error: AssertionError) {
            reportSystemDiagnostics("PiP action=$label")
            throw error
        }
    }
    private fun pipWindowBounds(): Rect? = ui.windows.firstOrNull {
        it.isInPictureInPictureMode && it.root?.packageName?.toString() == TARGET
    }?.let { Rect().apply(it::getBoundsInScreen) }?.takeUnless(Rect::isEmpty)

    private fun matchesPipLabel(node: AccessibilityNodeInfo, labels: Set<String>): Boolean =
        listOf(node.contentDescription, node.text).any { actual ->
            actual != null && labels.any { actual.toString().trim().equals(it, ignoreCase = true) }
        }

    private fun isSystemUiNode(node: AccessibilityNodeInfo): Boolean =
        node.packageName?.toString() in SYSTEM_UI_PACKAGES ||
            SYSTEM_UI_PACKAGES.any { node.viewIdResourceName.orEmpty().startsWith("$it:id/") }

    private fun hasVisibleVerificationWindow(): Boolean =
        ui.windows.any { it.root?.packageName?.toString() == TARGET }

    private fun closedPipResourcesAndHostReleased(active: ActiveFixture): Boolean = main {
        val activity = currentActivity.get()
        active.app.state.value.activePlayback == null && active.playback.player == null && active.player.isReleased &&
            (activity == null || (!activity.isInPictureInPictureMode && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)))
    }

    /** 只记录 verification 合成会话的资源与生命周期布尔值，不输出媒体/鉴权内容。 */
    private fun reportClosedPipState(active: ActiveFixture, reason: String) {
        try {
            val snapshot = main {
                val activity = currentActivity.get()
                fun field(name: String): String = if (activity == null) "host-destroyed" else runCatching {
                    MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.getBoolean(activity).toString()
                }.getOrDefault("unavailable")
                buildString {
                    append("$reason hostPresent=${activity != null} lifecycle=${activity?.lifecycle?.currentState}")
                    append(" hostPip=${activity?.isInPictureInPictureMode} finishing=${activity?.isFinishing} destroyed=${activity?.isDestroyed}")
                    append(" activePlayback=${active.app.state.value.activePlayback != null} playerPresent=${active.playback.player != null}")
                    append(" originalPlayerReleased=${active.player.isReleased}")
                    append(" closedPending=${field("closedPipPendingFinish")} pipWasVisible=${field("pipPlaybackWasVisible")}")
                }
            }
            val text = "$snapshot visibleAppWindow=${hasVisibleVerificationWindow()} starts=${eventCount("start", active.request.info.playSessionId)} stops=${eventCount("stop", active.request.info.playSessionId)}"
            Log.i("PathfinSystemFlow", text)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
        } catch (error: Exception) {
            Log.e("PathfinSystemFlow", "无法取得 PiP 关闭资源诊断：$reason", error)
        }
    }

    private fun reportSystemDiagnostics(reason: String) {
        try {
            val connected = automation ?: return
            val text = buildString {
                append("$reason uptime=${SystemClock.uptimeMillis()}\n")
                append(main { currentActivity.get()?.let {
                    "hostPip=${it.isInPictureInPictureMode} brightness=${it.window.attributes.screenBrightness} lifecycle=${it.lifecycle.currentState}\n"
                } ?: "host=destroyed\n" })
                val pip = runCatching { pipWindowBounds() }.getOrNull()
                for (window in connected.windows) {
                    val bounds = Rect().apply(window::getBoundsInScreen)
                    val root = window.root
                    append("window id=${window.id} type=${window.type} layer=${window.layer} pip=${window.isInPictureInPictureMode} bounds=$bounds root=${root?.packageName}\n")
                    // 只记录与测试PiP区域相交的系统菜单；不转储通知/其他应用的私人文本。
                    if (root == null || pip == null || !Rect.intersects(pip, bounds)) continue
                    val queue = ArrayDeque<AccessibilityNodeInfo>(); queue += root
                    var visited = 0
                    while (queue.isNotEmpty() && visited++ < 200 && length < 16_000) {
                        val node = queue.removeFirst()
                        val nodeBounds = Rect().apply(node::getBoundsInScreen)
                        if (node.isVisibleToUser && isSystemUiNode(node) && Rect.intersects(pip, nodeBounds))
                            append(" menu pkg=${node.packageName} id=${node.viewIdResourceName} class=${node.className} bounds=$nodeBounds clickable=${node.isClickable} text=${node.text?.take(100)} desc=${node.contentDescription?.take(100)}\n")
                        for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                    }
                }
            }
            Log.e("PathfinSystemFlow", text)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
        } catch (error: Exception) {
            // 诊断连接失败不能覆盖原来的真实交互断言。
            Log.e("PathfinSystemFlow", "无法取得系统交互诊断：$reason", error)
        }
    }
    private fun findSystemNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (window in ui.windows) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>(); queue += root
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 2_000) {
                val node = queue.removeFirst()
                // PiP的宿主root可属于应用，动作节点才属于SystemUI/Shell，不能只按root过滤。
                if (node.isVisibleToUser && isSystemUiNode(node) && match(node)) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private data class StoredScreenshot(val uri: Uri, val owner: String, val mime: String, val bytes: Long, val pending: Boolean)
    private fun ownScreenshots(): Map<Uri, StoredScreenshot> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING)
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        return context.contentResolver.query(collection, columns, selection, arrayOf(TARGET, "Pictures/Pathfin/", "Pathfin-%.png"), null)!!.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    put(uri, StoredScreenshot(uri, cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4) != 0))
                }
            }
        }
    }
    private fun fixtureStatus(): JSONObject = fixtureClient.newCall(Request.Builder().url("$BASE/health").build()).execute().use {
        check(it.isSuccessful) { "独立8101合成服务未就绪" }; JSONObject(it.body.string())
    }
    private fun eventCount(event: String, session: String): Int {
        val events = fixtureStatus().getJSONArray("Events")
        return (0 until events.length()).count { events.getJSONObject(it).let { row -> row.optString("event") == event && row.optString("play_session_id") == session } }
    }
    private fun clearSyntheticSession() {
        val store = SessionStore(context)
        val profiles = store.loadProfiles()
        check(profiles.all { it.session.serverId == "pathfin-fixture-server" && it.session.accessToken == "fixture-token" }) { "存在非合成账号，拒绝清理" }
        profiles.forEach { store.removeProfile(it.id) }; store.clear()
    }
    private fun drainReports(playback: PlaybackViewModel) = runBlocking { withTimeout(WAIT) { playback.awaitPendingReports() } }
    private fun awaitForeground(message: String, predicate: () -> Boolean) {
        try { compose.waitUntil(WAIT, predicate) }
        catch (error: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError(message, error) }
    }
    private fun await(message: String, timeout: Long = WAIT, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            // PiP 的 RemoteAction 也需提交 MainActivity 的参数更新；不等待应用窗口焦点。
            // Activity 真正关闭后不再触碰 Compose Activity/root，仍可检查 VM 释放/停止上报。
            if (currentActivity.get() != null) compose.mainClock.advanceTimeByFrame()
            SystemClock.sleep(30)
        }
        val passed = predicate()
        if (!passed) reportSystemDiagnostics(message)
        assertTrue(message, passed)
    }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    private data class ActiveFixture(val app: AppViewModel, val playback: PlaybackViewModel, val player: ExoPlayer, val request: PlaybackRequest)
    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val BASE = "http://127.0.0.1:8101/jellyfin"
        const val ITEM = "fixture-movie"
        const val WAIT = 25_000L
        val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "com.android.wm.shell")
        fun preferences() = AppPreferences(showClock = false, showBattery = false,
            subtitlesEnabled = false, longPressSpeedEnabled = true, longPressSpeed = 2.5f)
    }
}

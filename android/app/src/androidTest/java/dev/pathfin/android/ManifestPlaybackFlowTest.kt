package dev.pathfin.android

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.playback.PlayerFrameCapture
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 真实四秒 HLS / DASH 分片：由 ExoPlayer 解码实际视频，再跨片 seek。
 * 先启动 tools/prepare_manifest_fixture.py 并由测试负责人 reverse 8121 / 8122。
 * 全程只用合成 verification 账号；不接触日常包、用户服务器或用户媒体。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ManifestPlaybackFlowTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val setup = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            val context = context()
            check(context.packageName == "dev.pathfin.android.verification")
            val format = if (description.methodName.startsWith("hls")) "hls" else "dash"
            check(status().optBoolean("ManifestFixture")) { "8121 必须运行独立合成清单夹具" }
            reset(format)
            val preferences = AppPreferencesStore(context)
            val previous = preferences.load()
            clearSynthetic(context)
            preferences.save(AppPreferences(originalPathEnabled = true, showClock = false, showBattery = false,
                subtitlesEnabled = false))
            try {
                base.evaluate()
            } finally {
                clearSynthetic(context)
                preferences.save(previous)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(setup).around(compose)

    @Test fun hlsExoRendersAndSeeksAcrossActualSegments() = verify("hls")
    @Test fun dashExoRendersAndSeeksAcrossActualSegments() = verify("dash")

    private fun verify(format: String) {
        val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        try {
            compose.waitUntil(WAIT) { !app.state.value.restoringSession }
            main { app.login(BASE, "pathfin", "pathfin", true, "Synthetic $format fixture") }
            compose.waitUntil(WAIT) {
                app.state.value.loginError?.let { throw AssertionError("合成清单登录失败：$it") }
                app.state.value.home.loaded && !app.state.value.home.loading
            }
            val item = app.state.value.home.let { it.libraryItems.values.flatten() + it.recent + it.resume }.first { it.id == ITEM }
            main { app.open(item) }
            compose.waitUntil(WAIT) { app.state.value.route == Route.Detail(ITEM) && !app.state.value.detailLoading }
            main {
                app.preparePlayback(app.state.value.details.getValue(ITEM), fromStart = true)
            }
            compose.waitUntil(WAIT) {
                failOnPlaybackError(app, playback)
                playback.state.value.hasRenderedFrame && playback.state.value.isPlaying
            }
            val active = main { requireNotNull(playback.player) }
            main { assertTrue("清单必须提供真实两分钟时长", active.duration in 118_000..122_000) }
            assertActualVideoPixels()
            assertSourceIsolation(format)
            for (target in listOf(28_000L, 84_000L)) {
                main { active.seekTo(target) }
                compose.waitUntil(WAIT) {
                    failOnPlaybackError(app, playback)
                    main { active.playbackState == Player.STATE_READY && active.isPlaying && active.currentPosition in (target + 250)..(target + 5_000) }
                }
                // 不能只看到时间条改变：目标位置的真实视频分片必须经过源站。
                val targetSegment = (target / 4_000).toInt()
                waitForStatus { snapshot ->
                    val requests = snapshot.getJSONArray("MediaRequests")
                    (0 until requests.length()).any { index ->
                        requests.getJSONObject(index).let { request -> request.optString("format") == format &&
                            request.optBoolean("video") && request.optBoolean("successful") && request.optInt("segment", -1) in (targetSegment - 1)..(targetSegment + 1) }
                    }
                }
                assertActualVideoPixels()
            }
            main { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(WAIT) { app.state.value.activePlayback == null && playback.player == null }
            main { assertTrue("返回后必须释放 ExoPlayer", active.isReleased) }
            val final = waitForStatus { snapshot ->
                val events = snapshot.getJSONArray("Events")
                (0 until events.length()).any { events.getJSONObject(it).optString("event") == "stop" }
            }
            val events = final.getJSONArray("Events")
            val values = (0 until events.length()).map(events::getJSONObject)
            assertEquals("只应上报一次播放开始", 1, values.count { it.optString("event") == "start" })
            assertEquals("退出只应上报一次停止", 1, values.count { it.optString("event") == "stop" })
            assertTrue(values.single { it.optString("event") == "stop" }.getLong("position_ticks") >= 84_000L * 10_000)
            assertSourceIsolation(format)
        } finally {
            main {
                if (app.state.value.activePlayback != null) {
                    val position = playback.player?.currentPosition ?: 0
                    playback.stop()
                    app.playerClosed(position)
                }
            }
        }
    }

    private fun assertActualVideoPixels() {
        val view = main { requireNotNull(findPlayerView(compose.activity.window.decorView)) }
        val bitmap = runBlocking { PlayerFrameCapture.capture(view) }
        try {
            val colors = mutableSetOf<Int>()
            for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 8)
                for (x in bitmap.width / 8 until bitmap.width * 7 / 8 step 8) colors += bitmap.getPixel(x, y) and 0x00ffffff
            assertTrue("必须解码实际测试图，不能把黑 Surface 当成功", colors.size > 80)
        } finally { bitmap.recycle() }
    }

    private fun assertSourceIsolation(format: String) {
        val requests = status().getJSONArray("MediaRequests")
        assertTrue("必须实际请求媒体源", requests.length() > 0)
        for (index in 0 until requests.length()) {
            val request = requests.getJSONObject(index)
            assertEquals(format, request.getString("format"))
            assertFalse("Jellyfin 身份不能传给媒体端口", request.getBoolean("credential_leak"))
            assertTrue("源站需要的媒体头必须保留", request.getBoolean("source_header_present"))
            assertTrue("媒体请求必须实际通过源站校验", request.getBoolean("successful"))
        }
    }

    private fun failOnPlaybackError(app: AppViewModel, playback: PlaybackViewModel) {
        app.state.value.playbackError?.let { throw AssertionError("清单准备失败：$it") }
        playback.state.value.errorMessage?.let { throw AssertionError("清单播放失败：$it") }
    }

    private fun reset(format: String) {
        val request = Request.Builder().url("$BASE/_fixture/reset").post(
            JSONObject().put("Format", format).toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            check(JSONObject(requireNotNull(response.body).string()).getString("Format") == format)
        }
    }

    private fun status(): JSONObject = client.newCall(Request.Builder().url("$BASE/_fixture/status").build()).execute().use {
        check(it.isSuccessful)
        JSONObject(requireNotNull(it.body).string())
    }

    private fun waitForStatus(predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val snapshot = status()
            if (predicate(snapshot)) return snapshot
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("未观察到实际目标分片 / 播放停止事件；请检查仅含合成路由的 requests.jsonl")
    }

    private fun clearSynthetic(context: Context) {
        val store = SessionStore(context)
        val profiles = store.loadProfiles()
        check(profiles.all { it.session.serverId == "pathfin-fixture-server" && it.session.accessToken == "fixture-token" })
        check(store.load()?.let { it.serverId == "pathfin-fixture-server" && it.accessToken == "fixture-token" } != false)
        profiles.forEach { store.removeProfile(it.id) }
        store.clear()
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext
    private companion object {
        const val BASE = "http://127.0.0.1:8121/jellyfin"
        const val ITEM = "fixture-movie"
        const val WAIT = 35_000L
    }
}

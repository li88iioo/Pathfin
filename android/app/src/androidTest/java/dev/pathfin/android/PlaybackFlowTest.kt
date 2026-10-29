package dev.pathfin.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.autofill.AutofillManager
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用真实 Android 播放器与本机自制视频；不代表真实 Jellyfin 服务器验收。
 *
 * 执行前启动 tools/fixture_server.py，并分别 adb reverse 8097、8098。
 * 测试仅使用固定测试账号与测试会话，不连接或读取个人服务器。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackFlowTest {
    @get:Rule
    val compose = createVerificationComposeRule<MainActivity>()

    @Test
    fun loginOriginalPlaybackSeekRecreateAndLogout() {
        val app = compose.runOnIdle {
            ViewModelProvider(compose.activity)[AppViewModel::class.java].also { it.logout() }
        }
        compose.waitUntil(WAIT_MS) { app.state.value.session == null && !app.state.value.loginBusy && !app.state.value.restoringSession }
        val playback = compose.runOnIdle {
            ViewModelProvider(compose.activity)[PlaybackViewModel::class.java]
        }
        val displayName = "Playback flow ${System.nanoTime()}"

        compose.connectServer("http://127.0.0.1:8097", "pathfin", "pathfin", allowHttp = true, displayName = displayName)

        compose.waitUntil(timeoutMillis = WAIT_MS) {
            val state = app.state.value
            state.loginError?.let { throw AssertionError("测试服务登录失败：$it") }
            state.session != null && state.home.loaded && !state.home.loading
        }
        val syntheticProfile = compose.runOnIdle {
            assertNotNull("登录会话应加密保存到设备", SessionStore(compose.activity).load())
            app.state.value.profiles.single { it.id == app.state.value.currentProfileId }.also {
                assertEquals("删除目标必须是本次唯一合成账号", displayName, it.displayName)
                assertEquals("http://127.0.0.1:8097", it.session.server.baseUrl)
                assertEquals("pathfin", it.session.userName)
            }
        }
        compose.runOnUiThread {
            check(compose.activity.packageName == "dev.pathfin.android.verification")
            compose.activity.getSystemService(AutofillManager::class.java)?.cancel()
        }
        val retainedProfileIds = app.state.value.profiles.filterNot { it.id == syntheticProfile.id }.map { it.id }.toSet()

        compose.onAllNodesWithText(MOVIE_NAME).onFirst().performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            val state = app.state.value
            state.detailError?.let { throw AssertionError("测试详情读取失败：$it") }
            !state.detailLoading && state.details[ITEM_ID] != null
        }
        val playLabel = if (app.state.value.details.getValue(ITEM_ID).positionTicks > 0) "从头播放" else "播放"
        if (playLabel == "从头播放") compose.onNodeWithContentDescription("播放选项").performScrollTo().performClick()
        compose.onNodeWithText(playLabel).performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = WAIT_MS) {
            app.state.value.playbackError?.let { throw AssertionError("测试媒体准备失败：$it") }
            val state = playback.state.value
            state.errorMessage?.let { throw AssertionError("测试媒体播放失败：$it") }
            state.hasRenderedFrame && state.isPlaying
        }
        val originalPlayer = compose.runOnIdle {
            checkNotNull(playback.player).also {
                it.pause()
                it.seekTo(SEEK_MS)
            }
        }
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            compose.runOnIdle {
                originalPlayer.playbackState == Player.STATE_READY &&
                    !originalPlayer.isPlaying &&
                    originalPlayer.currentPosition in SEEK_MS..(SEEK_MS + 500)
            }
        }

        // 暂停时重建 Activity，避免 15 秒视频自然结束干扰实例保留的断言。
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            val recreatedPlayback = ViewModelProvider(compose.activity)[PlaybackViewModel::class.java]
            assertSame("旋转/配置重建必须保留播放器所有者", playback, recreatedPlayback)
            assertSame("Activity 重建不得创建第二个 ExoPlayer", originalPlayer, recreatedPlayback.player)
            assertFalse("重建后应保留暂停状态", originalPlayer.playWhenReady)
            assertTrue("重建后应保留 Seek 位置", originalPlayer.currentPosition >= SEEK_MS)
        }

        compose.onNodeWithTag("player-back").performClick()
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            app.state.value.activePlayback == null &&
                (app.state.value.details[ITEM_ID]?.positionTicks ?: 0) >= SEEK_MS * 10_000
        }
        compose.runOnIdle {
            assertNull("退出播放必须释放 ExoPlayer", playback.player)
            assertTrue(app.state.value.details.getValue(ITEM_ID).positionTicks >= SEEK_MS * 10_000)
        }

        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(WAIT_MS) { app.state.value.showServers }
        dismissOnlySyntheticAutofillSave(app, syntheticProfile, displayName)
        VerificationDisplay.awaitWindowReady(compose, "playback-cleanup-server-list", compose.onNodeWithTag("server-row/${syntheticProfile.id}"))
        compose.onNodeWithTag("server-row/${syntheticProfile.id}").assertExists()
        compose.onNodeWithContentDescription("服务器操作 $displayName").performClick()
        compose.onNodeWithText("从本机移除", substring = false).performClick()
        compose.onNodeWithText("删除服务器？", substring = false).assertExists()
        compose.onNodeWithText("从本机移除“$displayName”的账号与地址", substring = true).assertExists()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            val state = app.state.value
            state.session == null && !state.loginBusy && state.profiles.none { it.id == syntheticProfile.id }
        }
        compose.runOnIdle {
            val store = SessionStore(compose.activity)
            assertNull("移除当前合成账号必须清除本机活动会话", store.load())
            assertEquals("只允许移除本次合成 profile", retainedProfileIds, store.loadProfiles().map { it.id }.toSet())
        }
    }

    @Test
    fun encryptedSessionRoundTripRejectsTamperingAndClears() {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].also { it.logout() } }
        compose.waitUntil(WAIT_MS) { !app.state.value.loginBusy && !app.state.value.restoringSession }
        compose.runOnIdle {
            val context = compose.activity.applicationContext
            val store = SessionStore(context)
            val deviceId = store.deviceId()
            val session = Session(
                server = ServerConfig("https://fixture.invalid/jellyfin"),
                accessToken = "instrumentation-token-not-a-real-secret",
                userId = "fixture-user",
                userName = "pathfin",
                deviceId = deviceId,
                serverId = "fixture-server",
            )
            val preferences = context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE)
            try {
                store.save(session)
                assertEquals("随机设备身份应跨 Store 实例稳定", deviceId, SessionStore(context).deviceId())
                assertEquals("加密会话应可跨 Store 实例恢复", session, SessionStore(context).load())
                val encoded = checkNotNull(preferences.getString("encrypted_session_v1", null))
                assertFalse("落盘数据不能包含明文 token", encoded.contains(session.accessToken))
                val envelope = JSONObject(encoded)
                val ciphertext = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
                envelope.put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                check(preferences.edit().putString("encrypted_session_v1", envelope.toString()).commit())

                assertNull("GCM 验证必须拒绝被篡改的密文", store.load())
                assertNull("损坏会话应被移除", preferences.getString("encrypted_session_v1", null))
                store.save(session)
                assertEquals("损坏会话之后仍应允许重新保存登录", session, store.load())
                store.clear()
                assertNull("退出后不可恢复旧会话", store.load())
                assertEquals("退出不应更换设备身份", deviceId, store.deviceId())
            } finally {
                store.clear()
            }
        }
    }

    private fun dismissOnlySyntheticAutofillSave(app: AppViewModel, profile: ServerProfile, displayName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification")
        check(profile.session.server.baseUrl == "http://127.0.0.1:8097" && profile.session.userName == "pathfin")
        check(profile.displayName == displayName && displayName.startsWith("Playback flow ") &&
            displayName.removePrefix("Playback flow ").toLongOrNull() != null)
        val own = app.state.value.profiles.single { it.displayName == displayName }
        check(own.id == profile.id && own.session.server == profile.session.server && own.session.userName == "pathfin")
        var automation: UiAutomation? = null
        var originalFlags: Int? = null
        var connectionAttempted = false
        var declineAttempted = false
        try {
            compose.waitUntil(WAIT_MS) {
                if (compose.runOnUiThread { compose.activity.hasWindowFocus() }) true else {
                    if (!connectionAttempted) {
                        connectionAttempted = true
                        // 与 Compose 已有连接保持相同模式，不切换或关闭用户无障碍服务。
                        automation = runCatching { instrumentation.uiAutomation }.getOrNull()
                        automation?.let { ui ->
                            originalFlags = ui.serviceInfo.flags
                            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
                        }
                    }
                    val root = automation?.rootInActiveWindow
                    if (root != null) try {
                        if (!declineAttempted && root.packageName?.toString() == "android") {
                            val matches = root.findAccessibilityNodeInfosByViewId("android:id/autofill_save_no")
                            try {
                                val button = matches.singleOrNull { it.packageName?.toString() == "android" && it.isEnabled && it.isClickable }
                                if (button != null) {
                                    declineAttempted = true
                                    assertTrue("只能取消本次合成凭据的系统保存提示", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                    Log.i("PathfinPlaybackFlow", "已取消本次合成凭据的 Android 自动填充保存提示")
                                }
                            } finally { matches.forEach { it.recycle() } }
                        }
                    } finally { root.recycle() }
                    false
                }
            }
        } catch (_: ComposeTimeoutException) {
            // 不处理未知弹窗。随后原 awaitWindowReady 仍须严格验证真实窗口焦点。
            Log.w("PathfinPlaybackFlow", "未恢复验证窗口焦点，继续原窗口就绪检查")
        } finally {
            val ui = automation
            val flags = originalFlags
            if (ui != null && flags != null) ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags }
        }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val SEEK_MS = 5_000L
        const val ITEM_ID = "fixture-movie"
        const val MOVIE_NAME = "Pathfin · 原始路径播放测试"
    }
}

package dev.pathfin.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 实际应用导航及持久化：设置迁移位置后，修改仍只影响相应偏好。 */
@RunWith(AndroidJUnit4::class)
class SettingsOrganizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolate = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == "dev.pathfin.android.verification")
            val raw = context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE)
            val previous = raw.getString("settings", null)
            // 外层 Rule 在 Activity 启动前隔离验证包的夹具资料，结束后原样恢复密文和设备身份。
            val sessions = context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE)
            val previousSessions = sessions.all.toMap()
            var failure: Throwable? = null
            try {
                check(sessions.edit().clear().commit())
                AppPreferencesStore(context).save(AppPreferences(imageCacheSizeMb = 1536,
                    preferredAudioLanguage = "ja", subtitlesEnabled = true))
                base.evaluate()
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                val cleanupResults = listOf(runCatching {
                    val editor = raw.edit()
                    if (previous == null) editor.remove("settings") else editor.putString("settings", previous)
                    check(editor.commit())
                }, runCatching {
                    val editor = sessions.edit().clear()
                    previousSessions.forEach { (key, value) -> when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> error("验证包会话偏好包含不支持的数据类型")
                    } }
                    check(editor.commit())
                    check(previousSessions == sessions.all)
                })
                cleanupResults.mapNotNull { it.exceptionOrNull() }.forEach { cleanup ->
                    if (failure != null) failure.addSuppressed(cleanup) else failure = cleanup
                }
                if (cleanupResults.any { it.isFailure } && failure != null) throw failure
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolate).around(compose)

    @Test fun categorizedFormsKeepTheirValuesAndPersistIndependentChanges() {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(10_000) { !vm.state.value.restoringSession }
        compose.runOnIdle { check(vm.state.value.session == null); vm.openSettings() }
        page(null, vm)
        shot("01-settings")
        compose.onAllNodesWithText("Trakt", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("弹幕", substring = true).assertCountEquals(0)
        choose("常规")
        page("general", vm)
        compose.onNodeWithTag("pull-to-refresh-enabled").assertIsOn().performVerificationClick(compose)
        persisted { !it.pullToRefreshEnabled }
        compose.onNodeWithTag("pull-to-refresh-enabled").assertIsOff()
        compose.onNodeWithText("启动时打开上次的服务器").performVerificationClick(compose)
        persisted { !it.openLastServer }
        shot("02-general")
        back()
        choose("外观")
        page("appearance", vm)
        compose.onNodeWithText("深色模式").assertVerificationDisplayed(compose)
        compose.onAllNodesWithText("预测性返回动画", substring = false).assertCountEquals(0)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-hide-system-status-bar"))
        compose.onNodeWithTag("settings-hide-system-status-bar").assertIsOff().performVerificationClick(compose)
        persisted { it.hideSystemStatusBar && !it.blurAdultCovers }
        shot("03-appearance")
        back()
        choose("播放")
        page("player", vm)
        compose.onAllNodesWithText("默认播放器").assertCountEquals(0)
        shot("04-playback")
        choose("音轨与字幕")
        page("tracks", vm)
        val beforeSubtitle = compose.onNodeWithText("启用字幕").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        Log.i("SettingsOrganization", "beforeSubtitle ui=$beforeSubtitle vm=${vm.state.value.preferences.subtitlesEnabled} stored=${AppPreferencesStore(context).load().subtitlesEnabled}")
        compose.onNodeWithText("启用字幕").assertIsOn()
        choose("启用字幕")
        try { persisted { !it.subtitlesEnabled } }
        catch (failure: Throwable) {
            val shown = compose.onNodeWithText("启用字幕").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
            Log.e("SettingsOrganization", "subtitleTimeout ui=$shown vm=${vm.state.value.preferences.subtitlesEnabled} stored=${AppPreferencesStore(context).load().subtitlesEnabled}")
            shot("failure-subtitles")
            throw failure
        }
        assertEquals("ja", AppPreferencesStore(context).load().preferredAudioLanguage)
        shot("05-tracks")
        back()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-exo"))
        compose.onNodeWithTag("settings-exo").performVerificationClick(compose)
        page("exo", vm)
        choose("优先硬件解码")
        persisted { !it.preferHardwareVideoDecoder }
        assertEquals("ja", AppPreferencesStore(context).load().preferredAudioLanguage)
        shot("06-exo")
        back()
        back()
        choose("手势与控制")
        page("interaction", vm)
        // 方向设置已移到“播放”页，控制栏页验证同组的读取速度开关。
        choose("读取速度")
        persisted { it.showBufferSpeed }
        assertFalse(AppPreferencesStore(context).load().preferHardwareVideoDecoder)
        shot("08-controls")
        back()
        choose("存储与备份")
        page("cache", vm)
        compose.onNodeWithText("上限 1536 MB · 每个账号独立缓存", substring = true).assertVerificationDisplayed(compose)
        choose("导入设置", click = false)
        shot("09-storage")
        assertEquals(1536, AppPreferencesStore(context).load().imageCacheSizeMb)
        assertEquals("ja", AppPreferencesStore(context).load().preferredAudioLanguage)
        assertFalse(AppPreferencesStore(context).load().pullToRefreshEnabled)
        back()
        choose("媒体库")
        page("library", vm)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-blur-adult-covers"))
        compose.onNodeWithTag("settings-blur-adult-covers").assertIsOff().performVerificationClick(compose)
        persisted { it.blurAdultCovers && it.hideSystemStatusBar }
        compose.onAllNodesWithTag("settings-blurred-ratings-input").assertCountEquals(0)
        shot("10-library")
    }

    private fun choose(text: String, click: Boolean = true) {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(text, substring = false))
        val option = compose.onNode(hasText(text, substring = false) and hasAnyAncestor(hasTestTag("settings-list")))
        option.assertVerificationDisplayed(compose)
        if (click) option.performVerificationClick(compose)
    }
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun page(expected: String?, vm: AppViewModel) {
        compose.waitUntil(5_000) {
            if (expected == null) vm.state.value.route == Route.Settings
            else vm.state.value.route == Route.SettingsPage(expected)
        }
        compose.waitForIdle()
    }
    private fun persisted(check: (AppPreferences) -> Boolean) {
        compose.waitUntil(5_000) { check(AppPreferencesStore(context).load()) }
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val folder = File(context.filesDir, "settings-cleanup").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

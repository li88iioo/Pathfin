package dev.pathfin.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.SettingsScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 使用生产设置页与持久化 Store；不创建会话、不联网、不分配磁盘图片缓存。 */
@RunWith(AndroidJUnit4::class)
class SettingsCacheTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == VERIFICATION_PACKAGE) {
                "封面容量回归只允许 verification 包"
            }
            base.evaluate()
        }
    } }

    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test fun arbitraryCacheCapacityPersistsAndInvalidOrCancelledDraftsDoNotSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rawStore = context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE)
        val hadSettings = rawStore.contains("settings")
        val previousSettings = rawStore.getString("settings", null)
        val loader = ImageLoader.Builder(context).diskCache { null }.build()
        val shown = mutableStateOf(true)
        var contentInstalled = false
        try {
            // 只重置验证包的合成设置项，保留其它布局/隐藏媒体库键，最后恢复原始 JSON。
            check(rawStore.edit().remove("settings").commit())
            val preferences = AppPreferencesStore(context)
            assertEquals("未配置时封面容量默认 1 GB", 1024, preferences.load().imageCacheSizeMb)
            assertNull("配置路径测试不能创建磁盘缓存", loader.diskCache)
            val state = mutableStateOf(AppState(
                routes = listOf(Route.SettingsPage("cache")), preferences = preferences.load(),
            ))
            val saves = AtomicInteger()
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader) {
                        if (shown.value) SettingsScreen(state.value, onTheme = {}, onPreferences = { value ->
                            preferences.save(value)
                            state.value = state.value.copy(preferences = value)
                            saves.incrementAndGet()
                        })
                    }
                }
            }
            contentInstalled = true
            compose.onNodeWithText("上限 1024 MB · 每个账号独立缓存", substring = true).assertIsDisplayed()
            openCapacityDialog()
            assertEquals("1024", inputText())
            compose.onNodeWithTag("cache-size-input").performTextReplacement("1536")
            compose.onNodeWithText("保存", substring = false).assertIsEnabled().performClick()
            compose.onNodeWithTag("cache-size-input").assertDoesNotExist()
            compose.onNodeWithText("上限 1536 MB · 每个账号独立缓存", substring = true).assertIsDisplayed()
            assertEquals(1, saves.get())
            assertEquals("重建 Store 后任意容量仍可读取，不能退回预设容量", 1536,
                AppPreferencesStore(context).load().imageCacheSizeMb)

            openCapacityDialog()
            assertEquals("1536", inputText())
            for (invalid in listOf("", "0", "15", "65537", "99999")) {
                compose.onNodeWithTag("cache-size-input").performTextReplacement(invalid)
                compose.onNodeWithText("保存", substring = false).assertIsNotEnabled()
                assertEquals("无效草稿不能提交偏好", 1, saves.get())
                assertEquals(1536, AppPreferencesStore(context).load().imageCacheSizeMb)
            }
            // 合法边界可提交，避免用始终禁用保存按钮的实现误过负例。
            for (boundary in listOf("16", "65536")) {
                compose.onNodeWithTag("cache-size-input").performTextReplacement(boundary)
                compose.onNodeWithText("保存", substring = false).assertIsEnabled()
            }
            compose.onNodeWithTag("cache-size-input").performTextReplacement("2049")
            compose.onNodeWithText("保存", substring = false).assertIsEnabled()
            compose.onNodeWithText("取消", substring = false).performClick()
            compose.onNodeWithTag("cache-size-input").assertDoesNotExist()
            compose.onNodeWithText("上限 1536 MB · 每个账号独立缓存", substring = true).assertIsDisplayed()
            assertEquals("取消合法新草稿也不能写入", 1, saves.get())
            assertEquals(1536, AppPreferencesStore(context).load().imageCacheSizeMb)
            openCapacityDialog()
            assertEquals("重新打开不能恢复已取消的容量草稿", "1536", inputText())
            compose.onNodeWithText("取消", substring = false).performClick()
        } finally {
            try {
                if (contentInstalled) {
                    compose.runOnIdle { shown.value = false }
                    compose.waitForIdle()
                }
            } finally {
                loader.shutdown()
                val restore = rawStore.edit()
                if (hadSettings) restore.putString("settings", previousSettings) else restore.remove("settings")
                check(restore.commit()) { "无法恢复验证包的原始设置" }
            }
        }
    }

    private fun openCapacityDialog() {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("容量上限"))
        compose.onNodeWithText("容量上限", substring = false).performClick()
        compose.onNodeWithTag("cache-size-input").assertIsDisplayed()
    }

    private fun inputText() = compose.onNodeWithTag("cache-size-input")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    companion object { private const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification" }
}

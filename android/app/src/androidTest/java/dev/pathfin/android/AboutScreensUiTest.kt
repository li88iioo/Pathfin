package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.diagnostics.CrashLog
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.SettingsScreen
import dev.pathfin.android.ui.UpdateDialog
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import dev.pathfin.android.update.AppRelease
import dev.pathfin.android.update.UpdateState
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 关于页入口、文档页、崩溃日志与更新对话框各状态；不访问 GitHub。 */
@RunWith(AndroidJUnit4::class)
class AboutScreensUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val crashFile get() = File(context.filesDir, "crash_log.txt")
    private val state = mutableStateOf(AppState(routes = listOf(Route.SettingsPage("about"))))
    private val pages = mutableListOf<String?>()
    private val changes = mutableListOf<AppPreferences>()
    private val originalLocale = Locale.getDefault()

    @Before fun clearLog() { Locale.setDefault(Locale.SIMPLIFIED_CHINESE); CrashLog.clear(context) }
    @After fun removeLog() { CrashLog.clear(context); Locale.setDefault(originalLocale) }

    private fun settings(page: String) {
        state.value = state.value.copy(routes = listOf(Route.SettingsPage(page)))
        val loader = ImageLoader.Builder(context).diskCache { null }.build()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides loader) {
                    SettingsScreen(state.value, onTheme = {}, onPage = { pages += it }, onPreferences = { value ->
                        changes += value
                        state.value = state.value.copy(preferences = value)
                    })
                }
            }
        }
    }

    @Test fun aboutLinksEveryHelpAndPolicyPage() {
        settings("about")
        compose.onNodeWithTag("about-update").assertTextContains("当前版本 ${BuildConfig.VERSION_NAME}").assertIsEnabled()
        listOf("faq", "crash", "privacy", "disclaimer").forEach { compose.onNodeWithTag("about-$it").performScrollTo().performVerificationClick(compose) }
        compose.onNodeWithText("开源许可").performScrollTo().performVerificationClick(compose)
        compose.runOnIdle { assertEquals(listOf("faq", "crash", "privacy", "disclaimer", "licenses"), pages) }
    }

    @Test fun automaticUpdateCheckCanBeTurnedOffAndOn() {
        settings("about")
        compose.onNodeWithTag("about-auto-update").assertIsOn().performVerificationClick(compose)
        compose.runOnIdle { assertFalse(changes.last().autoCheckUpdates) }
        compose.onNodeWithTag("about-auto-update").assertIsOff().performVerificationClick(compose)
        compose.runOnIdle { assertTrue(changes.last().autoCheckUpdates) }
    }

    @Test fun documentsShowTheirSections() {
        settings("faq")
        compose.onNodeWithTag("about-document-faq").assertExists()
        compose.onNodeWithText("MediaFlux 生成的 STRM 和光鸭云盘", substring = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(routes = listOf(Route.SettingsPage("privacy"))) }
        compose.onNodeWithTag("about-document-privacy").assertExists()
        compose.onNodeWithText("1. 不收集个人信息").assertExists()
        compose.onNodeWithText("7. 协议变更").performScrollTo().assertVerificationDisplayed(compose)
        compose.runOnIdle { state.value = state.value.copy(routes = listOf(Route.SettingsPage("disclaimer"))) }
        compose.onNodeWithTag("about-document-disclaimer").assertExists()
        compose.onNodeWithText("2. 不提供任何内容").assertExists()
    }

    @Test fun emptyCrashLogDisablesCopyAndClearButStillAllowsFeedback() {
        settings("crash")
        compose.onNodeWithTag("about-crash-empty").assertExists()
        compose.onNodeWithTag("about-crash-copy").assertIsNotEnabled()
        compose.onNodeWithTag("about-crash-clear").assertIsNotEnabled()
        compose.onNodeWithTag("about-crash-report").assertIsEnabled()
    }

    @Test fun recordedCrashIsShownAndCanBeCleared() {
        val record = CrashLog.entry("OkHttp https://private.example.test/request?ApiKey=synthetic-key",
            IllegalStateException("test crash"), java.util.Date())
        assertFalse(record.contains("private.example.test"))
        assertFalse(record.contains("synthetic-key"))
        crashFile.writeText(record)
        settings("crash")
        compose.onNodeWithText("test crash", substring = true).assertExists()
        compose.onNodeWithTag("about-crash-copy").assertIsEnabled().performScrollTo().performVerificationClick(compose)
        compose.onNodeWithText("已复制崩溃日志").assertExists()
        compose.onNodeWithText("确定").performVerificationClick(compose)
        compose.onNodeWithTag("about-crash-clear").performVerificationClick(compose)
        compose.onNodeWithText("已清空崩溃日志").assertExists()
        compose.onNodeWithTag("about-crash-empty").assertExists()
        assertFalse(crashFile.exists())
    }

    @Test fun unreadableCrashLogCanBeRetriedOrClearedWithoutCrashingThePage() {
        crashFile.writeText("unreadable test record")
        android.system.Os.chmod(crashFile.absolutePath, 0)
        try {
            settings("crash")
            compose.onNodeWithTag("about-crash-retry").assertExists().performVerificationClick(compose)
            compose.onNodeWithTag("about-crash-copy").assertIsNotEnabled()
            compose.onNodeWithTag("about-crash-clear").assertIsEnabled().performVerificationClick(compose)
            compose.onNodeWithText("已清空崩溃日志").assertExists()
            compose.onNodeWithTag("about-crash-empty").assertExists()
            assertFalse(crashFile.exists())
        } finally {
            if (crashFile.exists()) android.system.Os.chmod(crashFile.absolutePath, 384)
        }
    }

    @Test fun updateDialogFollowsDownloadStates() {
        val release = AppRelease("9.9.9", "v9.9.9", "修复播放问题",
            "https://github.com/li88iioo/Pathfin/releases/download/v9.9.9/Pathfin.apk", 10_000_000, null)
        val update = mutableStateOf<UpdateState>(UpdateState.Available(release))
        val calls = mutableListOf<String>()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                UpdateDialog(update.value, null, onDismiss = { calls += "dismiss" }, onDownload = { calls += "download" },
                    onCancel = { calls += "cancel" }, onInstall = { calls += "install" })
            }
        }
        compose.onNodeWithText("发现新版本 v9.9.9").assertExists()
        compose.onNodeWithTag("update-notes").assertTextEquals("修复播放问题")
        compose.onNodeWithTag("update-download").performVerificationClick(compose)

        compose.runOnIdle { update.value = UpdateState.Downloading(release, 5_000_000) }
        compose.onNodeWithTag("update-progress").assertExists()
        compose.onNodeWithTag("update-download").assertDoesNotExist()
        compose.onNodeWithTag("update-cancel").performVerificationClick(compose)
        compose.onNodeWithText("后台下载").performVerificationClick(compose)

        compose.runOnIdle { update.value = UpdateState.Ready(release, File(context.cacheDir, "unused.apk")) }
        compose.onNodeWithTag("update-progress").assertDoesNotExist()
        compose.onNodeWithTag("update-install").performVerificationClick(compose)

        compose.runOnIdle { update.value = UpdateState.Available(release.copy(notes = ""), "安装包校验失败，请重新下载") }
        compose.onNodeWithTag("update-notes").assertTextEquals("此版本没有更新说明")
        compose.onNodeWithTag("update-error").assertTextEquals("安装包校验失败，请重新下载")
        compose.runOnIdle { assertEquals(listOf("download", "cancel", "dismiss", "install"), calls) }

        compose.runOnIdle { update.value = UpdateState.UpToDate }
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
    }
}

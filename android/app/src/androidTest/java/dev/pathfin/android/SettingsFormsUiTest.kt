package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.AccentColor
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.SettingsScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 生产表单的开关、选中语义与取消行为；无账号、无网络、无磁盘封面缓存。 */
@RunWith(AndroidJUnit4::class)
class SettingsFormsUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun adultCoverSwitchSubmitsOncePerTapWithoutOpeningAForm() = screen("library") { state, changes ->
        val list = compose.onNodeWithTag("settings-list")
        list.performScrollToNode(hasTestTag("settings-blur-adult-covers"))
        val adult = compose.onNodeWithTag("settings-blur-adult-covers")
        adult.assertIsOff().performVerificationClick(compose).assertIsOn()
        compose.runOnIdle {
            assertEquals(1, changes.size)
            assertTrue(state.value.preferences.blurAdultCovers)
            assertFalse(state.value.preferences.hideSystemStatusBar)
        }
        // 成人模糊为直接开关，不再打开自定义分级表单。
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        adult.performVerificationClick(compose).assertIsOff()
        compose.runOnIdle {
            assertEquals(2, changes.size)
            assertFalse(state.value.preferences.blurAdultCovers)
        }
    }

    @Test fun themeDialogShowsTheActualSelectionAndStatusBarChangeKeepsTheme() = screen("appearance") { state, changes ->
        fun option(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(isDialog()))
        compose.onNodeWithText("深色模式").performVerificationClick(compose)
        option(ThemePreference.SYSTEM.label).assertIsSelected()
        option(ThemePreference.DARK.label).assertIsNotSelected().performVerificationClick(compose)
        compose.runOnIdle { assertEquals(ThemePreference.DARK, state.value.theme); assertTrue(changes.isEmpty()) }
        compose.onNodeWithText("深色模式").performVerificationClick(compose)
        // 切换深浅主题后重新打开，对话框窗口可能晚一帧挂上。
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(ThemePreference.DARK.label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        option(ThemePreference.DARK.label).assertIsSelected()
        option(ThemePreference.SYSTEM.label).assertIsNotSelected()
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performVerificationClick(compose)
        compose.runOnIdle { assertEquals(ThemePreference.DARK, state.value.theme); assertTrue(changes.isEmpty()) }
        val list = compose.onNodeWithTag("settings-list")
        list.performScrollToNode(hasTestTag("settings-hide-system-status-bar"))
        compose.onNodeWithTag("settings-hide-system-status-bar").assertIsOff().performVerificationClick(compose).assertIsOn()
        compose.runOnIdle {
            assertEquals(1, changes.size)
            assertTrue(state.value.preferences.hideSystemStatusBar)
            assertEquals(ThemePreference.DARK, state.value.theme)
            assertFalse(state.value.preferences.blurAdultCovers)
        }
    }

    @Test fun accentChoicesKeepUsableTouchTargetsAndSubmitTheSelectedPresetOnce() = screen("appearance") { state, changes ->
        val options = AccentColor.entries.filter { it != AccentColor.WALLPAPER || android.os.Build.VERSION.SDK_INT >= 31 }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-accent-options"))
        val row = compose.onNodeWithTag("settings-accent-options")
        val current = compose.onNodeWithTag("settings-accent-current")
        val nodes = listOf(row, current).map { it.fetchSemanticsNode() }
        val (rowBounds, currentBounds) = compose.runOnUiThread { nodes.map { it.boundsInRoot } }
        for (option in options) {
            val tag = "settings-accent/${option.name}"
            val node = compose.onNodeWithTag(tag).performScrollTo().assertVerificationDisplayed(compose)
            val bounds = node.readVerificationNode(compose) { it.boundsInRoot }
            val minimum = 48 * compose.density.density
            assertTrue("${option.name} 点击宽度不能被压缩", bounds.width >= minimum - 1)
            assertTrue("${option.name} 点击高度不能被压缩", bounds.height >= minimum - 1)
            assertEquals("${option.name} 必须留在同一行", rowBounds.top, bounds.top, 1f)
            val previous = changes.size
            node.performVerificationClick(compose).assertIsSelected()
            compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("settings-accent-options"))).assertCountEquals(1)
            if (option != AccentColor.WALLPAPER) current.assertTextEquals(option.label)
            val updated = listOf(row, current).map { it.fetchSemanticsNode() }
            val (afterRow, afterCurrent) = compose.runOnUiThread { updated.map { it.boundsInRoot } }
            assertEquals("切换颜色后色样行不得跳位", rowBounds, afterRow)
            assertEquals("切换颜色后当前色说明不得跳位", currentBounds, afterCurrent)
            compose.runOnIdle {
                assertEquals(option, state.value.preferences.accentColor)
                assertEquals(previous + 1, changes.size)
            }
        }
    }

    private fun screen(page: String, block: (MutableState<AppState>, MutableList<AppPreferences>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val loader = ImageLoader.Builder(context).diskCache { null }.build()
        val state = mutableStateOf(AppState(routes = listOf(Route.SettingsPage(page))))
        val changes = mutableListOf<AppPreferences>()
        val shown = mutableStateOf(true)
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader) {
                        if (shown.value) SettingsScreen(state.value,
                            onTheme = { state.value = state.value.copy(theme = it) },
                            onPreferences = { value -> changes += value; state.value = state.value.copy(preferences = value) })
                    }
                }
            }
            VerificationDisplay.awaitWindowReady(compose, "settings-form-$page", compose.onNodeWithTag("settings-list"))
            block(state, changes)
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            loader.shutdown()
        }
    }
}

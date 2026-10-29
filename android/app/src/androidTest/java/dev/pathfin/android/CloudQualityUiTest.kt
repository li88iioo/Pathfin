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
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.PlaybackQualityOption
import dev.pathfin.android.playback.PlaybackUiState
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.SettingsScreen
import dev.pathfin.android.ui.player.PlayerQualitySheet
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 生产画质菜单与设置；只注入真实档位结构，不访问云盘、服务器或播放器。 */
@RunWith(AndroidJUnit4::class)
class CloudQualityUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    private val cloudOptions = listOf(
        PlaybackQualityOption(PlaybackQuality.ORIGINAL, "3840×2160 · HEVC · 原始文件"),
        PlaybackQualityOption(PlaybackQuality.HIGH, "1920×1080 · H264 · 云盘画质"),
        PlaybackQualityOption(PlaybackQuality.STANDARD, "1280×720 · H264 · 云盘画质"),
        PlaybackQualityOption(PlaybackQuality.DATA_SAVER, "没有可用的云盘档位", available = false),
    )

    @Test fun cloudMenuUsesMetadataAndUnavailableOptionsCannotChangeTheSelectedQuality() {
        val state = mutableStateOf(PlaybackUiState(canChangeQuality = true, cloudQualityOptions = cloudOptions))
        val selections = mutableListOf<PlaybackQuality>()
        qualitySheet(state, selections)
        compose.onNodeWithText("使用云盘已有画质，不经过 Jellyfin 转码").assertExists()
        cloudOptions.forEach { option ->
            val row = compose.onNodeWithTag("player-quality-${option.quality.name.lowercase()}")
            row.assertTextContains(option.quality.label).assertTextContains(option.description)
            if (option.available) row.assertIsEnabled() else row.assertIsNotEnabled()
        }
        compose.onNodeWithText("码率上限 12 Mbps").assertDoesNotExist()
        compose.onNodeWithTag("player-quality-data_saver").performScrollTo().performVerificationClick(compose)
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        compose.onNodeWithTag("player-quality-high").performScrollTo().performVerificationClick(compose)
        compose.runOnIdle { assertEquals(listOf(PlaybackQuality.HIGH), selections) }
        // 回调只是请求切换，选中态仍由实际播放状态确认。
        compose.onNodeWithTag("player-quality-original").assertIsSelected()
        compose.onNodeWithTag("player-quality-high").assertIsNotSelected()
        compose.runOnIdle { state.value = state.value.copy(quality = PlaybackQuality.HIGH) }
        compose.onNodeWithTag("player-quality-high").assertIsSelected()
        compose.onNodeWithTag("player-quality-original").assertIsNotSelected()
    }

    @Test fun switchingAndErrorKeepTheMenuGeometryAndCurrentSelection() {
        val state = mutableStateOf(PlaybackUiState(quality = PlaybackQuality.HIGH,
            canChangeQuality = true, cloudQualityOptions = cloudOptions))
        val selections = mutableListOf<PlaybackQuality>()
        qualitySheet(state, selections)
        val original = compose.onNodeWithTag("player-quality-original").performScrollTo()
        fun geometry() = listOf(original, compose.onNodeWithTag("player-quality-sheet")).map { it.fetchSemanticsNode() }
            .let { nodes -> compose.runOnUiThread { nodes.map { it.boundsInRoot } } }
        val bounds = geometry()
        compose.runOnIdle { state.value = state.value.copy(isChangingQuality = true) }
        original.assertIsNotEnabled().performVerificationClick(compose)
        compose.onNodeWithTag("player-quality-high").assertIsSelected().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        assertEquals(bounds, geometry())
        val error = "云盘画质暂时无法切换，请重试或继续观看当前画质。当前视频仍使用切换前的档位播放。"
        compose.runOnIdle { state.value = state.value.copy(isChangingQuality = false, qualityError = error) }
        compose.onNodeWithText(error).assertExists()
        original.assertIsEnabled()
        compose.onNodeWithTag("player-quality-high").assertIsSelected()
        assertEquals(bounds, geometry())
        compose.runOnIdle { state.value = state.value.copy(qualityError = null) }
        assertEquals(bounds.first(), original.readVerificationNode(compose) { it.boundsInRoot })
    }

    @Test fun nullOptionsKeepJellyfinBitratesButAnEmptyCloudListCannotOfferTranscoding() {
        val state = mutableStateOf(PlaybackUiState(canChangeQuality = true))
        qualitySheet(state, mutableListOf())
        PlaybackQuality.entries.forEach { quality ->
            compose.onNodeWithTag("player-quality-${quality.name.lowercase()}")
                .assertIsEnabled().assertTextContains(quality.label).assertTextContains(quality.description)
        }
        compose.onNodeWithText("码率上限 12 Mbps").assertExists()
        compose.onNodeWithText("码率上限 4 Mbps").assertExists()
        compose.onNodeWithText("码率上限 1.5 Mbps").assertExists()
        compose.onNodeWithTag("player-quality-original").assertIsSelected()
        compose.runOnIdle { state.value = state.value.copy(cloudQualityOptions = emptyList()) }
        PlaybackQuality.entries.forEach { quality ->
            compose.onNodeWithTag("player-quality-${quality.name.lowercase()}").assertDoesNotExist()
        }
        compose.onNodeWithText("使用云盘已有画质，不经过 Jellyfin 转码").assertExists()
        compose.onNodeWithText("降低质量可节省流量，需要服务器支持视频转码").assertDoesNotExist()
    }

    @Test fun settingsSaveRequiresConfirmationAndCancelRestoresThePersistedSelection() = settingsScreen(
        AppPreferences(originalPathEnabled = true),
    ) { state, changes ->
        val row = compose.onNodeWithTag("strm-default-quality")
        row.assertIsEnabled().assertTextContains("原画").performVerificationClick(compose)
        PlaybackQuality.entries.forEach { quality ->
            compose.onNodeWithTag("strm-default-quality-${quality.name.lowercase()}").assertTextContains(quality.label)
        }
        compose.onNodeWithTag("strm-default-quality-original").assertIsSelected()
        compose.onNodeWithTag("strm-default-quality-high").performVerificationClick(compose).assertIsSelected()
        compose.runOnIdle {
            assertTrue(changes.isEmpty())
            assertEquals(PlaybackQuality.ORIGINAL, state.value.preferences.strmDefaultQuality)
        }
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performVerificationClick(compose)
        row.performVerificationClick(compose)
        compose.onNodeWithTag("strm-default-quality-original").assertIsSelected()
        compose.onNodeWithTag("strm-default-quality-high").assertIsNotSelected().performVerificationClick(compose)
        compose.onNode(hasText("保存") and hasAnyAncestor(isDialog())).performVerificationClick(compose)
        compose.runOnIdle {
            assertEquals(listOf(AppPreferences(originalPathEnabled = true, strmDefaultQuality = PlaybackQuality.HIGH)), changes)
            assertEquals(PlaybackQuality.HIGH, state.value.preferences.strmDefaultQuality)
        }
        row.assertTextContains("高质量").performVerificationClick(compose)
        compose.onNodeWithTag("strm-default-quality-high").assertIsSelected()
        compose.onNodeWithTag("strm-default-quality-standard").performVerificationClick(compose)
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performVerificationClick(compose)
        compose.runOnIdle {
            assertEquals(1, changes.size)
            assertEquals(PlaybackQuality.HIGH, state.value.preferences.strmDefaultQuality)
        }
    }

    @Test fun strmSwitchControlsAvailabilityWithoutLosingTheSavedQualityOrMovingTheRow() = settingsScreen(
        AppPreferences(originalPathEnabled = false, strmDefaultQuality = PlaybackQuality.STANDARD, liveTvDirectPlay = true),
    ) { state, changes ->
        val row = compose.onNodeWithTag("strm-default-quality")
        val bounds = row.readVerificationNode(compose) { it.boundsInRoot }
        row.assertIsNotEnabled().assertTextContains("标准").performVerificationClick(compose)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        compose.onNodeWithText("STRM 原始地址").performVerificationClick(compose)
        row.assertIsEnabled()
        assertEquals(bounds, row.readVerificationNode(compose) { it.boundsInRoot })
        compose.runOnIdle {
            assertEquals(1, changes.size)
            assertEquals(AppPreferences(originalPathEnabled = true, strmDefaultQuality = PlaybackQuality.STANDARD, liveTvDirectPlay = true), state.value.preferences)
        }
        row.performVerificationClick(compose)
        compose.onNodeWithTag("strm-default-quality-standard").assertIsSelected()
    }

    private fun qualitySheet(state: MutableState<PlaybackUiState>, selections: MutableList<PlaybackQuality>) {
        compose.setContent {
            PathfinTheme(ThemePreference.DARK) {
                val value = state.value
                PlayerQualitySheet(value.quality, value.isChangingQuality, value.qualityError, value.canChangeQuality,
                    onSelect = { selections += it }, onDismiss = {}, cloudOptions = value.cloudQualityOptions)
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "cloud-quality-sheet", compose.onNodeWithTag("player-quality-sheet"))
    }

    private fun settingsScreen(
        preferences: AppPreferences = AppPreferences(),
        block: (MutableState<AppState>, MutableList<AppPreferences>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val loader = ImageLoader.Builder(context).diskCache { null }.build()
        val state = mutableStateOf(AppState(routes = listOf(Route.SettingsPage("player")), preferences = preferences))
        val changes = mutableListOf<AppPreferences>()
        val shown = mutableStateOf(true)
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader) {
                        if (shown.value) SettingsScreen(state.value, onTheme = {}, onPreferences = { value ->
                            changes += value
                            state.value = state.value.copy(preferences = value)
                        })
                    }
                }
            }
            VerificationDisplay.awaitWindowReady(compose, "strm-quality-settings", compose.onNodeWithTag("settings-list"))
            block(state, changes)
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            loader.shutdown()
        }
    }
}

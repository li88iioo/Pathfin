package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.MetadataRefreshMode
import dev.pathfin.android.domain.MetadataRefreshOptions
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.RefreshMetadataDialog
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 独立窗口和内存回调；不创建 ViewModel，不读取账号、偏好、服务器或缓存。 */
@RunWith(AndroidJUnit4::class)
class MetadataRefreshDialogUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val componentTargetsOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            check(target == "dev.pathfin.android.debug" || target == "dev.pathfin.android.verification") {
                "刷新元数据组件回归只允许开发包或 verification 包，实际为 $target"
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(componentTargetsOnly).around(compose)

    @Test fun cancellationDoesNotSubmitAndReopeningRestoresScanDefaults() {
        val probe = renderDialog()
        assertScanDefaults()
        // 使用真实触摸验证禁用项不会被点击改变。
        touchOption("library-refresh-replace-images")
        compose.onNodeWithTag("library-refresh-replace-images").assertIsOff()
        touchOption("library-refresh-missing")
        touchOption("library-refresh-replace-images")
        touchOption("library-refresh-replace-trickplay")
        compose.onNodeWithText(tr("取消")).performTouchInput { click() }
        compose.onNodeWithTag("library-refresh-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, probe.dismissals)
            assertEquals(emptyList<MetadataRefreshOptions>(), probe.confirmed)
            probe.visible.value = true
        }
        assertScanDefaults()
        confirm()
        compose.runOnIdle {
            assertEquals(listOf(MetadataRefreshOptions()), probe.confirmed)
            assertEquals(1, probe.dismissals)
        }
    }

    @Test fun selectedModesSubmitOptionsAndReturningToScanDropsReplacementFlags() {
        val probe = renderDialog()
        touchOption("library-refresh-missing")
        compose.onNodeWithTag("library-refresh-replace-images").assertIsEnabled()
        touchOption("library-refresh-replace-images")
        confirm()
        reopen(probe)

        touchOption("library-refresh-replace_all")
        touchOption("library-refresh-replace-images")
        touchOption("library-refresh-replace-trickplay")
        confirm()
        reopen(probe)

        touchOption("library-refresh-replace_all")
        touchOption("library-refresh-replace-images")
        touchOption("library-refresh-replace-trickplay")
        compose.onNodeWithTag("library-refresh-replace-images").assertIsOn()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsOn()
        touchOption("library-refresh-scan")
        compose.onNodeWithTag("library-refresh-replace-images").assertIsNotEnabled()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsNotEnabled()
        confirm()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    MetadataRefreshOptions(
                        metadataMode = MetadataRefreshMode.FULL_REFRESH,
                        imageMode = MetadataRefreshMode.FULL_REFRESH,
                        replaceAllImages = true,
                    ),
                    MetadataRefreshOptions(
                        metadataMode = MetadataRefreshMode.FULL_REFRESH,
                        imageMode = MetadataRefreshMode.FULL_REFRESH,
                        replaceAllMetadata = true,
                        replaceAllImages = true,
                        regenerateTrickplay = true,
                    ),
                    MetadataRefreshOptions(),
                ),
                probe.confirmed,
            )
            assertEquals(0, probe.dismissals)
        }
    }

    private fun renderDialog(): DialogProbe {
        val probe = DialogProbe()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    if (probe.visible.value) RefreshMetadataDialog(
                        count = 1,
                        onDismiss = { probe.dismissals++; probe.visible.value = false },
                        onConfirm = { probe.confirmed += it; probe.visible.value = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("library-refresh-confirm").assertIsDisplayed()
        return probe
    }

    private fun assertScanDefaults() {
        compose.onNodeWithTag("library-refresh-scan").assertIsSelected()
        compose.onNodeWithTag("library-refresh-replace-images").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsNotEnabled().assertIsOff()
    }

    private fun touchOption(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTouchInput { click() }
    }

    private fun confirm() {
        compose.onNodeWithTag("library-refresh-confirm").performTouchInput { click() }
        compose.onNodeWithTag("library-refresh-confirm").assertDoesNotExist()
    }

    private fun reopen(probe: DialogProbe) {
        compose.runOnIdle { probe.visible.value = true }
        compose.onNodeWithTag("library-refresh-confirm").assertIsDisplayed()
    }

    private class DialogProbe {
        val visible = mutableStateOf(true)
        val confirmed = mutableListOf<MetadataRefreshOptions>()
        var dismissals = 0
    }
}

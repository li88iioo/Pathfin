package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.text.AnnotatedString
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.ReauthenticateServerDialog
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.ServerManagementScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 添加服务器与重新登录弹窗的真实组件回归，不连接网络。 */
class ServerDialogStabilityTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    @Test fun repeatedSubmitIsSingleAndErrorsDoNotMoveDialogActions() {
        val existing = syntheticProfile()
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        val updates = mutableListOf<ServerProfile>()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val loader = ImageLoader.Builder(compose.activity).diskCache(null).build()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) { CompositionLocalProvider(LocalPosterLoader provides loader) {
                ServerManagementScreen(listOf(existing), existing.id, busy, error, null, adding = false, showcase = null, onProbe = {}, onAddServer = {}, onConnectBack = {}, onOpen = {},
                    onAdd = { _, _, _, _ -> }, onUpdate = { updates += it; busy = true }, onDelete = {},
                    onAddAddress = { _, _ -> }, onPassword = { _, _, _ -> }, onDismissError = { error = null })
            } }
        }
        compose.onNodeWithContentDescription("服务器操作 ${existing.displayName}").performVerificationClick(compose)
        compose.onNodeWithText("编辑连接", substring = false).performVerificationClick(compose)
        VerificationDisplay.awaitWindowReady(compose, "edit-server-dialog", compose.onNodeWithTag("login-submit"),
            compose.onNodeWithTag("server-form-error"))
        compose.onNodeWithTag("login-server").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("fixture.invalid")))
        compose.onNodeWithTag("login-server").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString("https://media.fixture.invalid/jellyfin")))
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("login-submit", useUnmergedTree = true).fetchSemanticsNode()
        val before = compose.runOnIdle { node.boundsInRoot }
        compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) { action -> repeat(8) { action() } }
        compose.runOnIdle {
            assertEquals(1, updates.size)
            assertEquals("https://media.fixture.invalid/jellyfin", updates.single().session.server.baseUrl)
        }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-server").assertIsEnabled().assert(!hasSetTextAction())
        compose.runOnIdle { error = "此地址不属于同一台 Jellyfin 服务器，请单独添加服务器。"; busy = false }
        compose.onNodeWithText("此地址不属于同一台 Jellyfin 服务器，请单独添加服务器。").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("login-submit").assertVerificationDisplayed(compose).assertIsEnabled()
        val afterNode = compose.onNodeWithTag("login-submit", useUnmergedTree = true).fetchSemanticsNode()
        val after = compose.runOnIdle { afterNode.boundsInRoot }
        assertEquals(before.left, after.left, .5f); assertEquals(before.top, after.top, .5f)
        assertEquals(before.width, after.width, .5f); assertEquals(before.height, after.height, .5f)
    }

    @Test fun reauthenticationShowsExactTargetAndForgetsPasswordWhenDialogIsRecreated() {
        val profile = syntheticProfile()
        var visible by mutableStateOf(true)
        val submitted = mutableListOf<String>()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                if (visible) ReauthenticateServerDialog(profile, busy = false, error = null,
                    onDismiss = { visible = false }, onSubmit = { submitted += it })
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "reauth-password-dialog", compose.onNodeWithTag("reauth-password"))
        compose.onNodeWithText("重新登录", substring = false).assertVerificationDisplayed(compose)
        compose.onNodeWithText(profile.displayName, substring = false).assertVerificationDisplayed(compose)
        compose.onNodeWithText(profile.session.userName, substring = false).assertVerificationDisplayed(compose)
        compose.onNodeWithText(profile.session.server.baseUrl, substring = false).assertVerificationDisplayed(compose)
        compose.onNodeWithTag("reauth-password").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString("synthetic-password-only-in-dialog")))
        }
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(listOf("synthetic-password-only-in-dialog"), submitted) }
        compose.onNodeWithText("取消", substring = false).performVerificationClick(compose)
        compose.onNodeWithTag("reauth-password").assertDoesNotExist()

        // 移除整个弹窗后重新组合，必须创建空密码；账号信息只来自明确的目标资料。
        compose.runOnIdle { visible = true }
        VerificationDisplay.awaitWindowReady(compose, "reauth-recreated-dialog", compose.onNodeWithTag("reauth-password"))
        compose.onNodeWithText(profile.displayName, substring = false).assertVerificationDisplayed(compose)
        compose.onNodeWithTag("reauth-password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(listOf("synthetic-password-only-in-dialog", ""), submitted) }
    }

    @Test fun reauthenticationSubmitsOnceAndKeepsActionsStableThroughBusyErrorAndRetry() {
        val profile = syntheticProfile()
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var dismissals = 0
        val submitted = mutableListOf<String>()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                ReauthenticateServerDialog(profile, busy, error, onDismiss = { dismissals++ },
                    onSubmit = { submitted += it; busy = true })
            }
        }
        compose.onNodeWithTag("reauth-password").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString("synthetic-retry-password")))
        }
        VerificationDisplay.awaitWindowReady(compose, "reauth-layout-baseline",
            compose.onNodeWithTag("login-submit", useUnmergedTree = true), compose.onNodeWithTag("server-form-error"))
        val targets = listOf(compose.onNodeWithTag("login-submit", useUnmergedTree = true), compose.onNodeWithTag("server-form-error"))
            .map { it.fetchSemanticsNode() }
        val (beforeAction, beforeFeedback) = compose.runOnIdle { targets.map { it.boundsInRoot } }
        compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) { action ->
            repeat(8) { action() }
        }
        compose.runOnIdle { assertEquals(listOf("synthetic-retry-password"), submitted) }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("reauth-password").assertIsEnabled().assert(!hasSetTextAction())
        compose.onNodeWithText("取消", substring = false).performVerificationClick(compose)
        compose.runOnIdle { assertEquals("请求期间不能关闭弹窗", 0, dismissals) }
        assertSameBounds(beforeAction, compose.onNodeWithTag("login-submit", useUnmergedTree = true).readVerificationNode(compose) { it.boundsInRoot })

        val failure = "用户名或密码错误，请检查密码后重新登录。"
        compose.runOnIdle { error = failure; busy = false }
        compose.onNodeWithText(failure, substring = false).assertVerificationDisplayed(compose)
        compose.onNodeWithTag("login-submit").assertIsEnabled().assertVerificationDisplayed(compose)
        compose.onNodeWithTag("reauth-password").assert(hasSetTextAction())
        val failedTargets = listOf(compose.onNodeWithTag("login-submit", useUnmergedTree = true), compose.onNodeWithTag("server-form-error"))
            .map { it.fetchSemanticsNode() }
        val (failedAction, failedFeedback) = compose.runOnIdle { failedTargets.map { it.boundsInRoot } }
        assertSameBounds(beforeAction, failedAction)
        assertSameBounds(beforeFeedback, failedFeedback)
        compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) { action ->
            repeat(8) { action() }
        }
        compose.runOnIdle {
            assertEquals("错误重试保留当前弹窗内的密码，每次操作只提交一次",
                listOf("synthetic-retry-password", "synthetic-retry-password"), submitted)
        }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("reauth-password").assert(!hasSetTextAction())
        assertSameBounds(beforeAction, compose.onNodeWithTag("login-submit", useUnmergedTree = true).readVerificationNode(compose) { it.boundsInRoot })
    }

    private fun assertSameBounds(before: androidx.compose.ui.geometry.Rect, after: androidx.compose.ui.geometry.Rect) {
        assertEquals(before.left, after.left, .5f)
        assertEquals(before.top, after.top, .5f)
        assertEquals(before.width, after.width, .5f)
        assertEquals(before.height, after.height, .5f)
    }

    private fun syntheticProfile() = ServerProfile(
        id = "synthetic-reauth-profile",
        displayName = "合成服务器 · 测试",
        session = Session(
            server = ServerConfig("https://fixture.invalid/jellyfin"),
            accessToken = "synthetic-token-not-used-on-network",
            userId = "fixture-user",
            userName = "pathfin-reauth-test",
            deviceId = "synthetic-reauth-device",
            serverId = "synthetic-server",
        ),
    )
}

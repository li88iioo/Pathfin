package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.domain.PublicServerInfo
import dev.pathfin.android.domain.PublicUser
import dev.pathfin.android.domain.ServerConfig
import coil3.ImageLoader
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.ServerManagementScreen
import dev.pathfin.android.ui.ServerProbe
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 首次连接页（无已保存服务器）的真实两步表单，不读取已保存会话、不连接个人服务器。 */
@RunWith(AndroidJUnit4::class)
class LoginStabilityTest {
    @get:Rule
    val compose = createVerificationComposeRule<ComponentActivity>()

    private data class Login(val config: ServerConfig, val username: String, val password: String, val displayName: String)

    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var probe by mutableStateOf<ServerProbe?>(null)
    private val probes = mutableListOf<ServerConfig>()
    private val logins = mutableListOf<Login>()
    private var resets = 0
    @Volatile private var composedInsets: Insets? = null

    private val fixtureServer = ServerConfig("http://192.168.1.20:8096", allowInsecureHttp = true)
    private fun probeOf(users: List<PublicUser> = emptyList()) =
        ServerProbe(fixtureServer, PublicServerInfo("fixture-id", "家庭影院", "10.10.3"), users)

    // ── 第一步：地址 ──

    @Test
    fun addressStepInfersLanHttpAndSubmitsOneProbe() {
        showLogin()
        setText("login-server", "192.168.1.20")
        // 推断结果先体现在连接预览里；端口在“高级选项”中，展开后可核对。
        compose.onNodeWithTag("server-endpoint").assertTextEquals("将连接 http://192.168.1.20:8096")
        compose.onNodeWithTag("server-advanced").performScrollTo().performVerificationClick(compose)
        compose.onNodeWithTag("server-port").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("8096")))
        compose.onNodeWithTag("login-http").performScrollTo().performVerificationClick(compose).assertIsOn()
        compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) { click -> repeat(8) { click() } }
        compose.runOnIdle { assertEquals("同一帧内的重复点击只能探测一次", listOf(fixtureServer), probes) }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-server").assertIsEnabled().assert(!hasSetTextAction())
        compose.onNodeWithTag("login-http").performVerificationClick(compose).assertIsOn()
    }

    @Test
    fun httpAddressWithoutConsentShowsLocalErrorAndDoesNotProbe() {
        showLogin()
        setText("login-server", "http://media.example.com")
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.onNodeWithText("请先确认允许 HTTP 明文连接").assertVerificationDisplayed(compose)
        compose.runOnIdle { assertTrue(probes.isEmpty()) }
        compose.onNodeWithTag("login-submit").assertIsEnabled()
    }

    @Test
    fun emptyAddressKeepsContinueDisabled() {
        showLogin()
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        setText("login-server", "  ")
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.runOnIdle { assertTrue(probes.isEmpty()) }
    }

    @Test
    fun busyAndChangingErrorsKeepAddressFieldAndButtonStable() {
        showLogin()
        setText("login-server", "https://jellyfin.example.com")
        val tags = listOf("login-server", "server-form-error", "login-submit", "login-submit-label")
        val initial = captureBaseline(tags)
        compose.runOnIdle { error = "请求参数不被服务器接受，请检查服务器地址。" }
        assertBounds(initial)
        compose.runOnIdle { busy = true }
        assertBounds(initial)
        compose.runOnIdle { busy = false; error = "无法连接服务器" }
        assertBounds(initial)
        compose.runOnIdle { error = null }
        assertBounds(initial)
    }

    @Test
    fun quickResponsesDoNotFlashProgressAndSlowResponsesKeepProgressBriefly() {
        showLogin()
        setText("login-server", "https://jellyfin.example.com")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { busy = true }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("login-progress").assertDoesNotExist()
            compose.runOnUiThread { busy = false }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("login-progress").assertDoesNotExist()

            compose.runOnUiThread { busy = true }
            compose.mainClock.advanceTimeBy(256)
            compose.onNodeWithTag("login-progress").assertExists()
            compose.runOnUiThread { busy = false }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("login-submit").assertIsEnabled()
            compose.onNodeWithTag("login-progress").assertExists()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("login-progress").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { busy = false }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeFontErrorsRemainReadableWithoutMovingTheButton() {
        showLogin(fontScale = 2f)
        setText("login-server", "https://jellyfin.example.com")
        compose.onNodeWithTag("server-form-error").performScrollTo().assertVerificationDisplayed(compose)
        compose.onNodeWithTag("login-submit").assertVerificationDisplayed(compose)
        val initial = captureBaseline(listOf("login-submit"))
        val message = "服务器返回了错误。请确认服务器地址后重新连接；如错误仍然存在，请检查服务器日志。"
        compose.runOnIdle { error = message }
        compose.onNodeWithText(message).assertExists()
        assertBounds(initial)
        compose.onNodeWithTag("server-form-error").performScrollTo()
        assertTrue("错误区域需跟随系统字号扩大", bounds("server-form-error").height > 100f * compose.density.density)
        compose.onNodeWithTag("login-submit").assertVerificationDisplayed(compose)
    }

    // ── 第二步：账号 ──

    @Test
    fun accountStepSubmitsOnceWithProbedConfigAndServerNameDefault() {
        showLogin()
        compose.runOnIdle { probe = probeOf() }
        compose.onNode(hasText("家庭影院") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertVerificationDisplayed(compose)
        compose.onNodeWithTag("server-display-name").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("家庭影院")))
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        setText("login-username", "pathfin-test")
        setText("login-password", "test-password")
        compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) { click -> repeat(8) { click() } }
        compose.runOnIdle {
            assertEquals(listOf(Login(fixtureServer, "pathfin-test", "test-password", "家庭影院")), logins)
        }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        listOf("login-username", "login-password", "server-display-name").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsEnabled().assert(!hasSetTextAction())
        }
        compose.runOnIdle { busy = false; error = "用户名或密码错误" }
        compose.onNodeWithText("用户名或密码错误").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("login-submit").assertIsEnabled().performVerificationClick(compose)
        compose.runOnIdle { assertEquals(2, logins.size) }
    }

    @Test
    fun publicUsersArePickedByAvatarAndOtherRevealsUsernameField() {
        showLogin()
        compose.runOnIdle { probe = probeOf(listOf(PublicUser("u1", "阿林"), PublicUser("u2", "yuki"))) }
        compose.onAllNodesWithTag("login-username").assertCountEquals(0)
        compose.onNodeWithText("阿林").assertIsSelected()
        compose.onNodeWithText("yuki").performVerificationClick(compose).assertIsSelected()
        setText("login-password", "secret")
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.runOnIdle { assertEquals("yuki", logins.single().username) }
        compose.runOnIdle { busy = false }
        compose.onNodeWithText("其他").performVerificationClick(compose)
        VerificationDisplay.awaitWindowReady(compose, "public-account-to-manual", compose.onNodeWithTag("login-username"))
        compose.onNodeWithTag("login-username").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
    }

    @Test
    fun changeServerReturnsToAddressStep() {
        showLogin()
        compose.runOnIdle { probe = probeOf() }
        compose.onNodeWithText("更换服务器").performVerificationClick(compose)
        compose.runOnIdle { assertEquals(1, resets) }
        compose.onNodeWithTag("login-server").assertVerificationDisplayed(compose)
    }

    @Test
    fun largeFontLoginErrorBringsFeedbackAndButtonIntoView() {
        showLogin(fontScale = 2f)
        compose.runOnIdle { probe = probeOf() }
        setText("login-username", "pathfin-test")
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.runOnIdle { busy = false; error = "用户名或密码错误，请检查后重试。" }
        compose.waitUntil(timeoutMillis = 5_000) {
            bounds("server-form-error").height > 100f * compose.density.density
        }
        compose.onNodeWithTag("login-submit").assertVerificationDisplayed(compose).assertIsEnabled()
        compose.onNodeWithText("用户名或密码错误，请检查后重试。").assertVerificationDisplayed(compose)
    }

    private fun showLogin(fontScale: Float = 1f) {
        // 与应用宿主一致，避免默认测试 Activity 首帧切换系统栏布局模式。
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val loader = ImageLoader.Builder(compose.activity).diskCache(null).build()
        compose.setContent {
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val safeDrawing = WindowInsets.safeDrawing
            val insets = Insets.of(
                safeDrawing.getLeft(density, direction), safeDrawing.getTop(density),
                safeDrawing.getRight(density, direction), safeDrawing.getBottom(density),
            )
            SideEffect { composedInsets = insets }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PathfinTheme(ThemePreference.LIGHT) {
                  CompositionLocalProvider(LocalPosterLoader provides loader) {
                    ServerManagementScreen(emptyList(), null, busy, error, probe, adding = false, showcase = null,
                        onProbe = { probes += it; busy = true },
                        onAddServer = {},
                        onConnectBack = { resets++; probe = null },
                        onOpen = {},
                        onAdd = { config, username, password, displayName ->
                            logins += Login(config, username, password, displayName); busy = true
                        },
                        onUpdate = {}, onDelete = {}, onAddAddress = { _, _ -> }, onPassword = { _, _, _ -> },
                        onDismissError = { error = null })
                  }
                }
            }
        }
    }

    private fun setText(tag: String, value: String) {
        // 使用真实 SetText 动作但不唤起 IME，尺寸断言独立于设备键盘动画。
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) { setText ->
            assertTrue(setText(AnnotatedString(value)))
        }
    }

    private fun captureBaseline(tags: List<String>): Map<String, Rect> {
        // 首帧以实际 Insets 和已放置的节点为准；不把 Rect.Zero 或窗口中间态当基线。
        compose.waitUntil(timeoutMillis = 5_000) {
            val insetsApplied = compose.runOnIdle {
                val windowInsets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                windowInsets != null && composedInsets == windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            }
            insetsApplied && snapshotBounds(tags).values.all { it.width > 0f && it.height > 0f }
        }
        return snapshotBounds(tags)
    }

    private fun bounds(tag: String): Rect = snapshotBounds(listOf(tag)).getValue(tag)

    private fun snapshotBounds(tags: List<String>): Map<String, Rect> {
        val nodes = tags.associateWith { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode() }
        // boundsInRoot 必须在 UI 线程一次读取，不能在逐节点同步期间夹入平台布局帧。
        return compose.runOnIdle { nodes.mapValues { (_, node) -> node.boundsInRoot } }
    }

    private fun assertBounds(expected: Map<String, Rect>) {
        val actual = snapshotBounds(expected.keys.toList())
        val root = compose.onRoot(useUnmergedTree = true).readVerificationNode(compose) { it.boundsInRoot }
        val detail = "root=$root; expected=$expected; actual=$actual"
        expected.forEach { (tag, before) ->
            val after = actual.getValue(tag)
            assertEquals("$tag left; $detail", before.left, after.left, 0.5f)
            assertEquals("$tag top; $detail", before.top, after.top, 0.5f)
            assertEquals("$tag width; $detail", before.width, after.width, 0.5f)
            assertEquals("$tag height; $detail", before.height, after.height, 0.5f)
        }
    }
}

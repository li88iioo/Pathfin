package dev.pathfin.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.theme.PathfinTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Android 17 真实 LAN 权限回归；不使用 adb reverse，不连接日常 Jellyfin 账号。
 *
 * 显式传入 pathfin.lanServer=http://<开发机私网 IPv4>:8097，并通过
 * pathfin.lanPermissionPhase=denied/granted 分别运行。未提供地址时整类跳过。
 * fixture 必须使用 --bind-address 绑定同一地址，测试只接受它的合成 serverId。
 *
 * denied 阶段须由外部 harness 在 instrumentation 启动前撤销 verification 权限；
 * granted 阶段仅对 verification 授权。之后外部撤权，再运行 denied 验证撤权回归。
 * 不在被测进程中执行 pm revoke，因为 Android 会终止该 UID 及 instrumentation。
 * 使用真实 PathfinApp/AppViewModel 配合隔离宿主，避免系统权限窗口自动处理干扰断言；
 * 系统授权窗口由外部设备回归另行覆盖。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LocalNetworkAccessTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private lateinit var server: ServerConfig
    private lateinit var phase: String
    private val isolatedLanTarget = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                val arguments = InstrumentationRegistry.getArguments()
                val explicitServer = arguments.getString("pathfin.lanServer")
                assumeTrue("真实 LAN 回归需要显式 pathfin.lanServer", !explicitServer.isNullOrBlank())
                check(context.packageName == VERIFICATION_PACKAGE) { "LAN 回归只允许独立 verification 包" }
                assumeTrue("ACCESS_LOCAL_NETWORK 回归仅适用于 Android 17+", Build.VERSION.SDK_INT >= 37)
                check(context.applicationInfo.targetSdkVersion >= 37) { "验证包必须 target Android 17+" }
                server = privateFixtureServer(checkNotNull(explicitServer))
                phase = arguments.getString("pathfin.lanPermissionPhase").orEmpty()
                check(phase in setOf("denied", "granted")) {
                    "请显式指定 pathfin.lanPermissionPhase=denied 或 granted"
                }
                clearSyntheticStorage(context)
                if (phase == "granted") grantVerificationLocalNetwork(context)
                try {
                    base.evaluate()
                } finally {
                    clearSyntheticStorage(context)
                }
            }
        }
    }

    // 所有目标/地址检查及合成会话清理均在 Activity 启动之前完成。
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedLanTarget).around(compose)

    @Test
    fun deniedPermissionStopsRawLanAuthenticationBeforeHttpHeaders() {
        assumeTrue("仅 denied 阶段执行权限负例", phase == "denied")
        assertLocalNetworkDenied()
        val requestHeaderWrites = AtomicInteger()
        val api = JellyfinApi(OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun requestHeadersEnd(call: Call, request: Request) {
                requestHeaderWrites.incrementAndGet()
            }
        }).build())

        val failure = runCatching { fixtureLogin(api) }.exceptionOrNull()
        assertNotNull("未授权时真实 LAN 登录不能成功", failure)
        assertTrue("未授权必须表现为网络拒绝，而非服务器 HTTP 错误", failure is ApiException)
        assertNull("不应收到服务器 HTTP 响应码", (failure as ApiException).statusCode)
        assertEquals("权限拒绝时不能向 LAN 写出认证 HTTP 头", 0, requestHeaderWrites.get())
    }

    @Test
    fun deniedPermissionKeepsLoginInputsAndIgnoresCancelledRequestResult() {
        assumeTrue("仅 denied 阶段执行权限界面负例", phase == "denied")
        assertLocalNetworkDenied()
        val app = showApp()
        fillLoginForm()
        val originalInputs = loginInputValues()
        compose.onNodeWithTag("login-submit").performClick()
        compose.waitUntil(WAIT_MS) { app.state.value.localNetworkPermissionRequest != null }
        val requestId = checkNotNull(app.state.value.localNetworkPermissionRequest)
        assertNull("尚未授权时不能建立会话", app.state.value.session)
        assertTrue("权限窗口待处理时只保留一个登录操作", app.state.value.loginBusy)
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()

        compose.runOnIdle { app.onLocalNetworkPermissionResult(false, requestId) }
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            !state.loginBusy && state.localNetworkPermissionRequest == null &&
                state.loginError == LocalNetworkPermission.DENIED_MESSAGE
        }
        assertNull(app.state.value.session)
        assertTrue(app.state.value.localNetworkPermissionDenied)
        compose.onNodeWithText(LocalNetworkPermission.DENIED_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag("login-submit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("login-http").performScrollTo().assertIsOn()
        assertEquals("权限拒绝后必须完整保留表单", originalInputs, loginInputValues())

        // 再次请求后取消整个登录，迟到的“允许”回调不得激活已退出的会话。
        compose.runOnIdle { app.dismissLocalNetworkPermissionExplanation() }
        compose.onNodeWithTag("login-submit").performClick()
        compose.waitUntil(WAIT_MS) { app.state.value.localNetworkPermissionRequest != null }
        val cancelledRequestId = checkNotNull(app.state.value.localNetworkPermissionRequest)
        assertTrue("每次权限请求必须有独立身份", requestId != cancelledRequestId)
        compose.runOnIdle {
            app.logout()
            app.onLocalNetworkPermissionResult(true, cancelledRequestId)
        }
        compose.waitForIdle()
        assertNull("取消后不能保留待处理授权", app.state.value.localNetworkPermissionRequest)
        assertNull("迟到授权不能重新登录", app.state.value.session)
        assertFalse("取消后不能继续显示登录加载", app.state.value.loginBusy)
        assertTrue(app.state.value.showServers)
        assertLocalNetworkDenied()
    }

    @Test
    fun grantedPermissionAuthenticatesRealLanAndLoadsHomeFromUi() {
        assumeTrue("仅 granted 阶段执行真实 LAN 正例", phase == "granted")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(LOCAL_NETWORK_PERMISSION))
        val verified = fixtureLogin(JellyfinApi())
        assertEquals("必须连接合成 fixture，不能使用真实用户服务器", FIXTURE_SERVER_ID, verified.serverId)
        val app = showApp()
        fillLoginForm()
        compose.continueToAccount(WAIT_MS)
        assertEquals("第一步必须探测到合成 fixture", FIXTURE_SERVER_ID, app.state.value.serverProbe?.info?.id)
        compose.enterAccount(FIXTURE_USER, FIXTURE_PASSWORD)
        compose.onNodeWithTag("login-submit").performClick()
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.loginError?.let { throw AssertionError("LAN 界面登录失败：$it") }
            state.home.error?.let { throw AssertionError("LAN 首页读取失败：$it") }
            state.session != null && state.home.loaded && !state.home.loading
        }
        assertEquals(FIXTURE_SERVER_ID, app.state.value.session?.serverId)
        assertEquals("首页必须保留真实 LAN 地址", server.baseUrl, app.state.value.session?.server?.baseUrl)
        assertTrue("首页必须读到 fixture 媒体库", app.state.value.home.libraries.any { it.id == "fixture-library" })
        compose.onAllNodesWithText(FIXTURE_MOVIE).onFirst().performScrollTo().assertIsDisplayed()
    }

    private fun showApp(): AppViewModel {
        lateinit var app: AppViewModel
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            app = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        }
        compose.setContent {
            val state by app.state.collectAsState()
            PathfinTheme(state.theme) {
                PathfinApp(app, state, rememberSaveableStateHolder())
            }
        }
        return app
    }

    /** 局域网授权在连接页第一步（探测服务器）发起，表单只含地址与明文开关。 */
    private fun fillLoginForm() {
        compose.enterServerAddress(server.baseUrl, allowHttp = true)
    }

    private fun loginInputValues(): Map<String, String> =
        listOf("login-server").associateWith { tag ->
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        }

    private fun assertLocalNetworkDenied() {
        assertEquals(
            "先在独立 verification 包撤销 ACCESS_LOCAL_NETWORK，再运行 denied 阶段",
            PackageManager.PERMISSION_DENIED,
            InstrumentationRegistry.getInstrumentation().targetContext.checkSelfPermission(LOCAL_NETWORK_PERMISSION),
        )
    }

    private fun fixtureLogin(api: JellyfinApi): Session = runBlocking {
        api.authenticate(server, FIXTURE_USER, FIXTURE_PASSWORD, "fixture-lan-device")
    }

    private fun setText(tag: String, value: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString(value)))
        }
    }

    private fun privateFixtureServer(value: String): ServerConfig {
        val url = value.toHttpUrl()
        check(url.scheme == "http" && url.username.isEmpty() && url.password.isEmpty()) {
            "LAN fixture 地址仅接受无内嵌凭据的 HTTP URL"
        }
        check(url.encodedQuery == null && url.encodedFragment == null) { "LAN fixture 地址不能包含查询或 fragment" }
        val octets = url.host.split('.').map { it.toIntOrNull() }
        check(octets.size == 4 && octets.all { it != null && it in 0..255 }) { "LAN fixture 必须使用显式私网 IPv4，不能通过 DNS 解析" }
        val address = octets.map { checkNotNull(it) }
        check(url.host == address.joinToString(".")) { "LAN fixture 必须使用标准 IPv4 写法" }
        check(address[0] == 10 || address[0] == 172 && address[1] in 16..31 || address[0] == 192 && address[1] == 168) {
            "LAN fixture 必须位于 RFC1918 私网，不能使用回环/public 地址或 adb reverse"
        }
        return ServerConfig(url.toString().trimEnd('/'), allowInsecureHttp = true)
    }

    private fun grantVerificationLocalNetwork(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        if (context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) return
        val command = "pm grant $VERIFICATION_PACKAGE $LOCAL_NETWORK_PERMISSION"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        check(output.isBlank()) { "verification 局域网权限授予失败：$output" }
        assertEquals("verification 必须获得局域网权限", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(LOCAL_NETWORK_PERMISSION))
    }

    private fun clearSyntheticStorage(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        val store = SessionStore(context)
        store.loadProfiles().forEach { store.removeProfile(it.id) }
        store.clear()
        check(context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val FIXTURE_SERVER_ID = "pathfin-fixture-server"
        const val FIXTURE_USER = "pathfin"
        const val FIXTURE_PASSWORD = "pathfin"
        const val FIXTURE_MOVIE = "Pathfin · 原始路径播放测试"
        const val WAIT_MS = 20_000L
    }
}

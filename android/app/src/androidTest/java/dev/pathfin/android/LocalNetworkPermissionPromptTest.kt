package dev.pathfin.android

import android.app.UiAutomation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.ui.AppViewModel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 原生局域网授权窗口回归：真实 MainActivity → 系统允许按钮 → 真实 LAN fixture 首页。
 *
 * 仅在显式传入 pathfin.lanServer=http://<开发机 RFC1918 IPv4>:8097 时运行。
 * 外部 harness 须先撤销 verification 包的 ACCESS_LOCAL_NETWORK；本测试不执行
 * pm grant/revoke、不改变日常 debug 包，也不以回环地址或 adb reverse 替代 LAN。
 * UiAutomation 不可用时可显式传入 pathfin.externalPermissionPrompt=true，
 * 由外部操作者检查并点击真实系统允许按钮；实际权限和首页断言仍完整执行。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LocalNetworkPermissionPromptTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private lateinit var server: ServerConfig
    private val isolatedLanTarget = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val explicitServer = InstrumentationRegistry.getArguments().getString("pathfin.lanServer")
                assumeTrue("原生 LAN 授权回归需要显式 pathfin.lanServer", !explicitServer.isNullOrBlank())
                check(context.packageName == VERIFICATION_PACKAGE) { "原生授权回归只允许独立 verification 包" }
                assumeTrue("原生局域网权限回归仅适用于 Android 17+", Build.VERSION.SDK_INT >= 37)
                check(context.applicationInfo.targetSdkVersion >= 37) { "验证包必须 target Android 17+" }
                server = privateFixtureServer(checkNotNull(explicitServer))
                assertEquals(
                    "外部 harness 必须在启动前撤销 verification 局域网权限，以便出现原生窗口",
                    PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(LocalNetworkPermission.PERMISSION),
                )
                clearSyntheticStorage(context)
                try {
                    base.evaluate()
                } finally {
                    clearSyntheticStorage(context)
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedLanTarget).around(compose)

    @Test
    fun nativeAllowButtonResumesPendingLanLoginAndLoadsHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val externalPrompt = InstrumentationRegistry.getArguments().getString("pathfin.externalPermissionPrompt") == "true"
        val activity = compose.activity
        val app = compose.runOnIdle { ViewModelProvider(activity)[AppViewModel::class.java] }
        // 局域网授权在连接页第一步（探测服务器）发起；授权后再完成账号步骤。
        compose.enterServerAddress(server.baseUrl, allowHttp = true)

        // 保留设备已有无障碍服务；连接失败时明确失败，不以 shell grant 代替原生窗口。
        val automation = if (externalPrompt) null else try {
            checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        } catch (failure: IllegalStateException) {
            throw AssertionError("设备 UiAutomation 无法连接；原生局域网授权窗口尚未验证", failure)
        }
        compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) {
            assertTrue(it())
        }

        // 测试时钟需提交状态变化，让 MainActivity 的 LaunchedEffect 真正启动系统窗口。
        compose.mainClock.advanceTimeBy(500)

        if (externalPrompt) {
            val requestDeadline = SystemClock.elapsedRealtime() + PROMPT_WAIT_MS
            while (app.state.value.localNetworkPermissionRequest == null &&
                context.checkSelfPermission(LocalNetworkPermission.PERMISSION) == PackageManager.PERMISSION_DENIED &&
                SystemClock.elapsedRealtime() < requestDeadline) {
                check(context.packageName == VERIFICATION_PACKAGE && activity.packageName == VERIFICATION_PACKAGE)
                app.state.value.loginError?.let { throw AssertionError("原生授权请求失败：$it") }
                SystemClock.sleep(50)
            }
            check(app.state.value.localNetworkPermissionRequest != null ||
                context.checkSelfPermission(LocalNetworkPermission.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                "真实 MainActivity 未发起局域网权限请求"
            }
            Log.i("PathfinPermissionPromptTest", "WAITING_FOR_NATIVE_ALLOW target=$VERIFICATION_PACKAGE permission=${LocalNetworkPermission.PERMISSION}; 请检查真实系统窗口并点击允许，最长等待60秒")
        }

        // 系统窗口活跃时不通过 Compose/Espresso 同步，以免等待应用窗口焦点。
        val deadline = SystemClock.elapsedRealtime() + PROMPT_WAIT_MS
        var nativeAllowClicked = false
        while (!externalPrompt && SystemClock.elapsedRealtime() < deadline && !nativeAllowClicked) {
            val root = checkNotNull(automation).rootInActiveWindow
            if (root != null) {
                try {
                    val controllerPackage = root.packageName?.toString()
                    if (controllerPackage != null && isSystemPermissionController(context, controllerPackage) &&
                        app.state.value.localNetworkPermissionRequest != null) {
                        val allow = findLocalNetworkAllowButton(root, context, controllerPackage)
                        if (allow != null) {
                            try {
                                check(context.packageName == VERIFICATION_PACKAGE && activity.packageName == VERIFICATION_PACKAGE)
                                check(context.checkSelfPermission(LocalNetworkPermission.PERMISSION) == PackageManager.PERMISSION_DENIED)
                                check(app.state.value.loginBusy && app.state.value.session == null)
                                check(app.state.value.localNetworkPermissionRequest != null)
                                nativeAllowClicked = allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            } finally {
                                allow.recycle()
                            }
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            if (!nativeAllowClicked) SystemClock.sleep(80)
        }
        if (!externalPrompt) assertTrue("未找到 verification 的原生局域网允许按钮；请确认外部撤权且系统未固定为拒绝", nativeAllowClicked)

        val permissionDeadline = SystemClock.elapsedRealtime() + (if (externalPrompt) EXTERNAL_PROMPT_WAIT_MS else PROMPT_WAIT_MS)
        while (context.checkSelfPermission(LocalNetworkPermission.PERMISSION) != PackageManager.PERMISSION_GRANTED &&
            SystemClock.elapsedRealtime() < permissionDeadline) {
            if (externalPrompt) {
                check(context.packageName == VERIFICATION_PACKAGE && activity.packageName == VERIFICATION_PACKAGE)
                check(app.state.value.localNetworkPermissionRequest != null && app.state.value.loginBusy && app.state.value.session == null) {
                    "等待外部允许时 verification 权限请求已取消或拒绝"
                }
            }
            SystemClock.sleep(50)
        }
        assertEquals("点击原生允许按钮后必须真正获得权限", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(LocalNetworkPermission.PERMISSION))
        compose.waitUntil(HOME_WAIT_MS) {
            app.state.value.loginError?.let { throw AssertionError("原生授权后探测服务器失败：$it") }
            app.state.value.serverProbe != null
        }
        compose.waitUntil(HOME_WAIT_MS) { compose.onAllNodesWithTag("login-username").fetchSemanticsNodes().isNotEmpty() }
        compose.enterAccount("pathfin", "pathfin")
        compose.onNodeWithTag("login-submit").performVerificationClick(compose)
        compose.waitUntil(HOME_WAIT_MS) {
            val state = app.state.value
            state.loginError?.let { throw AssertionError("原生授权后登录失败：$it") }
            state.home.error?.let { throw AssertionError("原生授权后首页读取失败：$it") }
            state.session != null && state.home.loaded && !state.home.loading
        }
        val state = app.state.value
        assertEquals("只允许连接合成 fixture", "pathfin-fixture-server", state.session?.serverId)
        assertEquals("必须通过真实 LAN 地址登录", server.baseUrl, state.session?.server?.baseUrl)
        assertTrue("必须加载 fixture 媒体库", state.home.libraries.any { it.id == "fixture-library" })
        compose.onAllNodesWithText("Pathfin · 原始路径播放测试").onFirst().performScrollTo().assertVerificationDisplayed(compose)
    }

    private fun isSystemPermissionController(context: Context, packageName: String): Boolean {
        if (packageName !in PERMISSION_CONTROLLER_PACKAGES) return false
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        return (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }

    private fun findLocalNetworkAllowButton(root: AccessibilityNodeInfo, context: Context, permissionController: String): AccessibilityNodeInfo? {
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val texts = mutableListOf<String>()
        var allow: AccessibilityNodeInfo? = null
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.packageName?.toString() == permissionController) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
                if (allow == null && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    node.viewIdResourceName in setOf(
                        "$permissionController:id/permission_allow_button",
                        "$permissionController:id/permission_allow_foreground_only_button",
                    )) {
                    allow = AccessibilityNodeInfo.obtain(node)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
            if (node !== root) node.recycle()
        }
        val message = texts.joinToString(" ")
        val networkTerms = listOf("局域网", "本地网络", "网络上的设备", "附近设备", "附近的设备", "local network", "nearby devices")
        if (!message.contains(appLabel, ignoreCase = true) || networkTerms.none { message.contains(it, ignoreCase = true) }) {
            allow?.recycle()
            return null
        }
        return allow
    }

    private fun setText(tag: String, value: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString(value)))
        }
    }

    private fun privateFixtureServer(value: String): ServerConfig {
        val url = value.toHttpUrl()
        check(url.scheme == "http" && url.username.isEmpty() && url.password.isEmpty()) { "LAN fixture 仅接受无内嵌凭据的 HTTP URL" }
        check(url.encodedQuery == null && url.encodedFragment == null) { "LAN fixture 不能带查询或 fragment" }
        val octets = url.host.split('.').map { it.toIntOrNull() }
        check(octets.size == 4 && octets.all { it != null && it in 0..255 }) { "LAN fixture 必须使用显式私网 IPv4" }
        val address = octets.map { checkNotNull(it) }
        check(url.host == address.joinToString(".")) { "LAN fixture 必须使用标准 IPv4 写法" }
        check(address[0] == 10 || address[0] == 172 && address[1] in 16..31 || address[0] == 192 && address[1] == 168) {
            "LAN fixture 只接受 RFC1918 私网，禁止回环/public 地址"
        }
        return ServerConfig(url.toString().trimEnd('/'), allowInsecureHttp = true)
    }

    private fun clearSyntheticStorage(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        val store = SessionStore(context)
        store.loadProfiles().forEach { store.removeProfile(it.id) }
        store.clear()
        check(context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private companion object {
        val PERMISSION_CONTROLLER_PACKAGES = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val PROMPT_WAIT_MS = 15_000L
        const val EXTERNAL_PROMPT_WAIT_MS = 60_000L
        const val HOME_WAIT_MS = 20_000L
    }
}

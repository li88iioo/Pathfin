package dev.pathfin.android

import android.content.Context
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.domain.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith

/** 仅显式传入隔离测试服务参数时运行；不得将个人服务器密码写入源码。 */
@RunWith(AndroidJUnit4::class)
class RealJellyfinLoginTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolatedAccount = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "dev.pathfin.android.verification")
            val preferences = AppPreferencesStore(context)
            val previous = preferences.load()
            fun clearSessions() { check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit()) }
            // 在 MainActivity 启动前清掉前组的合成会话，不能把已关闭 fixture 的错误当成新服务器响应。
            clearSessions()
            preferences.save(AppPreferences())
            try { base.evaluate() }
            finally { clearSessions(); preferences.save(previous) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedAccount).around(compose)

    @Test fun authenticatesAndLoadsHomeAgainstRealServer() {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("pathfin.realServer")
        assumeTrue("需要显式提供隔离的真实 Jellyfin 测试服务", !server.isNullOrBlank())
        val username = checkNotNull(arguments.getString("pathfin.realUsername"))
        val password = checkNotNull(arguments.getString("pathfin.realPassword"))
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(10_000) { !app.state.value.restoringSession }
        try {
            compose.openServerForm()
            VerificationDisplay.awaitWindowReady(compose, "real-add-server", compose.onNodeWithTag("login-server"))
            compose.connectServer(server!!, username, password, allowHttp = server.startsWith("http://"))
            compose.waitUntil(timeoutMillis = 20_000) {
                val state = app.state.value
                state.loginError?.let { throw AssertionError("真实服务登录失败：$it") }
                state.home.error?.let { throw AssertionError("真实服务首页请求失败：$it") }
                state.session != null && state.home.loaded && !state.home.loading
            }
            compose.runOnIdle {
                val session = app.state.value.session
                assertNotNull(session)
                assertEquals(username, session!!.userName)
                assertEquals(session, SessionStore(compose.activity).load())
                assertNull(app.state.value.home.error)
            }
        } finally {
            compose.runOnIdle { app.logout() }
        }
    }

    @Test fun switchesBetweenTwoAddressesOfTheSameRealServerWithoutRevokingEitherLogin() {
        val arguments = InstrumentationRegistry.getArguments()
        val firstUrl = arguments.getString("pathfin.realServer")
        val secondUrl = arguments.getString("pathfin.realServerAlias")
        assumeTrue("需要显式提供同一隔离 Jellyfin 的两个地址", !firstUrl.isNullOrBlank() && !secondUrl.isNullOrBlank())
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        val username = checkNotNull(arguments.getString("pathfin.realUsername"))
        val password = checkNotNull(arguments.getString("pathfin.realPassword"))
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(10_000) { !app.state.value.restoringSession }
        fun awaitHome(profileId: String? = null) {
            compose.waitUntil(25_000) {
                val state = app.state.value
                if (!state.loginBusy) {
                    state.loginError?.let { throw AssertionError("真实同后端切换失败：$it") }
                    state.home.error?.let { throw AssertionError("真实首页读取失败：$it") }
                    state.reauthenticationProfile?.let { throw AssertionError("另一地址的登录不应使已保存账号失效") }
                }
                state.session != null && !state.loginBusy && state.home.loaded && !state.home.loading &&
                    (profileId == null || state.currentProfileId == profileId)
            }
        }
        fun login(url: String, name: String): ServerProfile {
            compose.runOnIdle { app.login(url, username, password, allowHttp = true, displayName = name) }
            awaitHome()
            return app.state.value.profiles.single { it.id == app.state.value.currentProfileId }
        }
        fun switch(profile: ServerProfile) {
            // 网络状态已切换不代表上一次 Popup 窗口已移除；先等待真实窗口交还触摸焦点。
            compose.waitUntil(10_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
            val avatar = compose.onNodeWithContentDescription("切换服务器")
            VerificationDisplay.awaitWindowReady(compose, "real-server-avatar", avatar)
            avatar.performClick()
            val target = hasText(profile.displayName) and hasAnyAncestor(isPopup())
            compose.waitUntil(10_000) { compose.onAllNodes(target).fetchSemanticsNodes().size == 1 }
            val row = compose.onNode(target)
            VerificationDisplay.awaitWindowReady(compose, "real-switch-target", row)
            row.performClick()
            awaitHome(profile.id)
            assertEquals(profile.session, app.state.value.session)
        }
        try {
            val first = login(firstUrl!!, "真实测试 · 地址 A")
            val second = login(secondUrl!!, "真实测试 · 地址 B")
            assertEquals(first.session.serverId, second.session.serverId)
            assertEquals(first.session.userId, second.session.userId)
            assertNotEquals(first.id, second.id)
            assertNotEquals(first.session.deviceId, second.session.deviceId)
            repeat(3) { switch(first); switch(second) }
            val refreshedFirst = login(firstUrl, "")
            assertEquals(first.id, refreshedFirst.id)
            assertEquals(first.session.deviceId, refreshedFirst.session.deviceId)
            assertNotEquals(first.session.accessToken, refreshedFirst.session.accessToken)
            switch(second)
            switch(refreshedFirst)
            val store = SessionStore(compose.activity)
            assertEquals(listOf(refreshedFirst, second), store.loadProfiles())
            assertEquals(refreshedFirst.session, store.load())
        } finally {
            app.state.value.profiles.toList().forEach { profile ->
                compose.runOnIdle { app.deleteServer(profile) }
                compose.waitUntil(10_000) { !app.state.value.loginBusy }
            }
        }
    }
}

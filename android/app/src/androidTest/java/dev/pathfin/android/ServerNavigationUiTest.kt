package dev.pathfin.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.ServerManagementScreen
import dev.pathfin.android.ui.ServerSwitcher
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 真实菜单组件与应用导航；只登录本测试的回环夹具，不使用任何已保存会话。 */
@RunWith(AndroidJUnit4::class)
class ServerNavigationUiTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var loader: ImageLoader
    private val api = JellyfinApi()
    private val first = profile("a", "合成服务器 A")
    private val second = profile("b", "合成服务器 B")
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == "dev.pathfin.android.verification")
            clearSessions()
            loader = ImageLoader.Builder(context).diskCache(null).build()
            var failure: Throwable? = null
            try { base.evaluate() }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                try { loader.shutdown(); clearSessions() }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test
    fun avatarOnlyListsProfilesAndBusyStatePreventsAnotherSwitch() {
        val busy = mutableStateOf(false)
        val switches = mutableListOf<ServerProfile>()
        showSwitcher(listOf(first, second), busy) { switches += it }
        compose.onNodeWithContentDescription("切换服务器").performClick()
        compose.onNodeWithText(first.displayName).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(second.displayName).assertIsDisplayed().assertIsNotSelected()
        compose.onAllNodesWithContentDescription("当前服务器").assertCountEquals(1)
        assertOnlyProfileActions(2)
        screenshot("01-two-server-switcher", popup = true)

        compose.onNodeWithText(second.displayName).performClick()
        compose.runOnIdle { assertEquals("一次选择只能回调一次正确资料", listOf(second), switches) }
        compose.onNode(isPopup()).assertDoesNotExist()

        compose.runOnIdle { busy.value = true }
        compose.onNodeWithContentDescription("切换服务器").assertIsNotEnabled().performTouchInput { click() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(second), switches); busy.value = false }

        // 菜单已打开时转入 busy，现有条目也必须立刻禁止点击。
        compose.onNodeWithContentDescription("切换服务器").performClick()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithContentDescription("切换服务器").assertIsNotEnabled()
        compose.onNodeWithText(first.displayName).assertIsNotEnabled()
        compose.onNodeWithText(second.displayName).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals("忙时真实触摸不能多触发切换", listOf(second), switches) }
        assertOnlyProfileActions(2)
        screenshot("02-busy-server-switcher", popup = true)
    }

    @Test
    fun pendingReadOnlySwitchKeepsTheAvatarAndOriginalAccountReachable() {
        val state = mutableStateOf(AppState(session = first.session, profiles = listOf(first, second),
            currentProfileId = first.id))
        val switches = mutableListOf<ServerProfile>()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides loader) {
                    Surface(Modifier.fillMaxSize()) {
                        ServerSwitcher(state.value.profiles, state.value.currentProfileId, state.value.session,
                            !state.value.canSwitchServer, api) { profile ->
                            switches += profile
                            state.value = state.value.copy(loginBusy = true, switchingProfileId = profile.id)
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "pending-server-switch", compose.onNodeWithContentDescription("切换服务器"))
        compose.onNodeWithContentDescription("切换服务器").performClick()
        compose.onNodeWithText(second.displayName).performClick()
        compose.onNodeWithContentDescription("切换服务器").assertIsEnabled().performClick()
        compose.onNodeWithText(first.displayName).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(second, first), switches) }
        // 校验完成后的写入提交期仍禁止打断；普通busy测试继续覆盖密码等写操作。
        compose.runOnIdle { state.value = state.value.copy(switchingProfileId = null) }
        compose.onNodeWithContentDescription("切换服务器").assertIsNotEnabled()
    }

    @Test
    fun managerCardsCanReplaceReadOnlySwitchButCannotInterruptAccountWrites() {
        val switching = mutableStateOf(true)
        val opened = mutableListOf<ServerProfile>()
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides loader) {
                    ServerManagementScreen(listOf(first, second), first.id, busy = true, error = null,
                        probe = null, adding = false, showcase = null, switching = switching.value,
                        onProbe = {}, onAddServer = {}, onConnectBack = {}, onOpen = { opened += it },
                        onAdd = { _, _, _, _ -> }, onUpdate = {}, onDelete = {}, onAddAddress = { _, _ -> },
                        onPassword = { _, _, _ -> }, onDismissError = {}, api = api)
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "pending-manager-switch", compose.onNodeWithText(first.displayName))
        compose.onNodeWithText(second.displayName).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(second), opened); switching.value = false }
        compose.onNodeWithText(first.displayName).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("账号写入不能被卡片选择打断", listOf(second), opened) }
    }

    @Test
    fun oneProfileStillOpensItsSelectedListWithoutAccountActions() {
        var switches = 0
        showSwitcher(listOf(first), mutableStateOf(false)) { switches++ }
        compose.onNodeWithContentDescription("切换服务器").assertIsEnabled().performClick()
        compose.onNodeWithText(first.displayName).assertIsDisplayed().assertIsSelected()
        compose.onAllNodesWithContentDescription("当前服务器").assertCountEquals(1)
        compose.onAllNodesWithText(second.displayName).assertCountEquals(0)
        assertOnlyProfileActions(1)
        compose.runOnIdle { assertEquals("仅展开列表不能触发切换", 0, switches) }
        screenshot("03-single-server-switcher", popup = true)
    }

    @Test
    fun ordinaryAccountMenuKeepsSettingsAndSystemBackOpensTheOuterServerList() {
        val preferences = AppPreferencesStore(context)
        val previous = preferences.load()
        preferences.save(AppPreferences())
        val backend = ServerProfilesFlowTest.BackendAuthState()
        ServerProfilesFlowTest.ProfilesFixture("Navigation", backend,
            includeUserImage = false, administrator = false).use { fixture ->
            lateinit var vm: AppViewModel
            try {
                compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                compose.waitUntil(10_000) { !vm.state.value.restoringSession }
                compose.runOnIdle {
                    assertNull(vm.state.value.session)
                    assertTrue(vm.state.value.profiles.isEmpty())
                    vm.login(fixture.baseUrl, "synthetic-profiles-user", "synthetic-profiles-password",
                        allowHttp = true, displayName = "合成普通账号")
                }
                compose.waitUntil(20_000) {
                    fixture.assertHealthy()
                    vm.state.value.session != null && vm.state.value.home.loaded && !vm.state.value.home.loading
                }
                compose.setContent {
                    val state by vm.state.collectAsState()
                    PathfinTheme(ThemePreference.LIGHT) {
                        Surface(Modifier.fillMaxSize()) {
                            PathfinApp(vm, state, rememberSaveableStateHolder(), posterLoader = loader)
                        }
                    }
                }
                compose.runOnIdle {
                    assertTrue(vm.state.value.session?.isAdministrator == false)
                    vm.selectTab(Route.Home)
                }
                val serverMenu = compose.onNodeWithContentDescription("更多")
                VerificationDisplay.awaitWindowReady(compose, "initial-server-menu", serverMenu)
                serverMenu.performClick()
                listOf("管理服务器", "刷新", "扫描全部媒体库", "媒体库控制台").forEach {
                    compose.onAllNodesWithText(it, substring = false).assertCountEquals(0)
                }
                val settings = compose.onNodeWithText("设置")
                VerificationDisplay.awaitWindowReady(compose, "ordinary-account-menu", settings)
                screenshot("04-standard-account-menu", popup = true)
                settings.assertIsDisplayed().performClick()
                compose.waitUntil(5_000) { vm.state.value.routes == listOf(Route.Home, Route.Settings) }
                compose.waitUntil(5_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
                VerificationDisplay.awaitWindowReady(compose, "settings-after-menu", compose.onNodeWithTag("settings-list"))
                Espresso.pressBack()
                compose.waitUntil(5_000) { vm.state.value.routes == listOf(Route.Home) }
                VerificationDisplay.awaitWindowReady(compose, "home-before-system-back", serverMenu)
                Espresso.pressBack()
                compose.waitUntil(5_000) { vm.state.value.showServers }
                assertEquals(listOf(Route.Home), vm.state.value.routes)
                val row = compose.onNodeWithTag("server-row/${vm.state.value.currentProfileId}")
                VerificationDisplay.awaitWindowReady(compose, "outer-server-list", row)
                row.assertIsDisplayed()
                compose.onNodeWithTag("server-add").assertIsDisplayed()
                screenshot("05-server-management")
                // 服务器列表的设置是根路由；系统返回应回列表，不能直接退出应用。导航只有图标，以无障碍名称定位。
                compose.onNodeWithContentDescription("设置").performClick()
                compose.waitUntil(5_000) { vm.state.value.showServers && vm.state.value.routes == listOf(Route.Settings) }
                VerificationDisplay.awaitWindowReady(compose, "outer-settings-before-system-back", compose.onNodeWithTag("settings-list"))
                Espresso.pressBack()
                compose.waitUntil(5_000) { vm.state.value.showServers && vm.state.value.routes == listOf(Route.Home) }
                VerificationDisplay.awaitWindowReady(compose, "outer-settings-returned-to-server-list", row)
                row.assertIsDisplayed()
                fixture.assertHealthy()
            } finally {
                compose.runOnUiThread { compose.activity.viewModelStore.clear() }
                preferences.save(previous)
            }
        }
    }

    private fun showSwitcher(profiles: List<ServerProfile>, busy: MutableState<Boolean>, onSwitch: (ServerProfile) -> Unit) {
        compose.setContent {
            PathfinTheme(ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalPosterLoader provides loader) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            ServerSwitcher(profiles, first.id, first.session, busy.value, api, onSwitch)
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "server-switcher", compose.onNodeWithContentDescription("切换服务器"))
    }

    private fun assertOnlyProfileActions(count: Int) {
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(count)
        listOf("设置", "管理服务器", "退出登录", "退出账号", "退出").forEach {
            compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
    }

    private fun screenshot(name: String, popup: Boolean = false) {
        // Popup跨窗口截图依赖UiAutomation，在部分API37系统会占用失败；不让可选截图中断交互断言。
        if (popup) return
        compose.waitForIdle()
        val directory = File(context.filesDir, "server-navigation").apply { check(isDirectory || mkdirs()) }
        val node = compose.onRoot()
        File(directory, "$name.png").outputStream().use {
            assertTrue(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun clearSessions() {
        check(context.packageName == "dev.pathfin.android.verification")
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun profile(id: String, name: String) = ServerProfile("synthetic-navigation-$id",
        Session(ServerConfig("https://navigation-$id.invalid"), "synthetic-navigation-token-$id", "synthetic-user-$id",
            "合成用户 $id", "synthetic-device", "synthetic-server-$id", isAdministrator = false), name)
}

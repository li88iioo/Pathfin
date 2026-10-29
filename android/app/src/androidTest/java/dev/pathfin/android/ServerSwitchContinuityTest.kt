package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.ServerProfilesFlowTest.BackendAuthState
import dev.pathfin.android.ServerProfilesFlowTest.ProfilesFixture
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.HomeState
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 VM/HTTP/PathfinApp；网络门闩保持未完成时即验收内容和两维滚动位置。 */
@RunWith(AndroidJUnit4::class)
class ServerSwitchContinuityTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousPreferences: AppPreferences? = null
    private var owner: ViewModelStoreOwner? = null
    private lateinit var loader: ImageLoader

    @Before fun prepare() {
        check(context.packageName == VERIFICATION_PACKAGE)
        clearSessions()
        val store = AppPreferencesStore(context)
        previousPreferences = store.load()
        // 暖切专项要求自动后台更新；默认开启下拉时暖缓存不再自动重读。
        store.save(AppPreferences(pullToRefreshEnabled = false))
        loader = ImageLoader.Builder(context).diskCache(null).build()
    }

    @After fun cleanup() {
        if (context.packageName != VERIFICATION_PACKAGE) return
        clearViewModel()
        if (::loader.isInitialized) loader.shutdown()
        clearSessions()
        previousPreferences?.let { AppPreferencesStore(context).save(it) }
    }

    @Test fun validationKeepsTheCurrentHomeThenWarmActivationDoesNotWaitForHomeResponses() = withApp { env ->
        val first = login(env, env.first)
        val firstHome = env.vm.state.value.home
        val second = login(env, env.second)
        val secondHome = env.vm.state.value.home
        val authentications = env.backend.successfulAuthentications.get()
        env.first.homeVersion.set(2)
        env.first.blockHomeResponses().use { home ->
            env.first.blockNextValidation().use { validation ->
                switchFromMenu(first)
                validation.awaitRequest()
                validation.assertStillBlocked()
                assertSelected(env, second)
                assertHomeContentEquals(secondHome, env.vm.state.value.home)
                assertEquals(second.id, SessionStore(context).activeProfileId())
                validation.release()
                // 自动读取由目标账号页面完成组合后发起，等待期间必须继续推进 Compose。
                await(env, "A 页面自动读取必须真实到达 Home 门闩") { home.requestArrived }
                await(env, "认证成功后必须先恢复 A 的完整暖内容") { it.currentProfileId == first.id && !it.loginBusy }
                home.assertStillBlocked()
                assertSelected(env, first)
                assertTrue(env.vm.state.value.home.loading)
                assertHomeContentEquals(firstHome, env.vm.state.value.home)
                assertHomeOwner(env.vm.state.value.home, env.first)
                assertEquals("暖账号切换不能重新提交密码", authentications, env.backend.successfulAuthentications.get())
                home.release()
            }
            awaitLoaded(env, first)
            assertEquals(env.first.libraryName(0, 2), env.vm.state.value.home.libraries.first().name)
            assertHomeOwner(env.vm.state.value.home, env.first)
            assertTrue(env.first.homeDeviceIds.all { it == first.session.deviceId })
            assertTrue(env.second.homeDeviceIds.all { it == second.session.deviceId })
        }
    }

    @Test fun defaultPullModeWarmSwitchUsesCachedHomeAndARealPullRefreshesOnlyTheCurrentAccount() = withApp { env ->
        main { env.vm.setPreferences(env.vm.state.value.preferences.copy(pullToRefreshEnabled = true)) }
        val first = login(env, env.first)
        login(env, env.second)
        val firstRequests = env.first.homeRequests.get()
        val secondRequests = env.second.homeRequests.get()
        switchFromMenu(first)
        awaitLoaded(env, first)
        compose.waitForIdle()
        assertEquals("默认下拉模式暖切不能自动重读 Home", firstRequests, env.first.homeRequests.get())
        assertEquals(secondRequests, env.second.homeRequests.get())
        val cached = env.vm.state.value.home
        env.first.blockHomeResponses().use { home ->
            pullPageToRefresh(compose, stage = "cached-home-real-pull")
            await(env, "真实 Home 下拉必须发送当前账号请求") {
                home.requestArrived && it.home.loading && env.first.homeRequests.get() >= firstRequests + 2
            }
            home.assertStillBlocked()
            assertSelected(env, first)
            assertHomeContentEquals(cached, env.vm.state.value.home)
            pullPageToRefresh(compose, stage = "cached-home-busy-pull")
            assertEquals("已有首页请求不能重叠", firstRequests + 2, env.first.homeRequests.get())
            assertEquals("不能刷新非当前账号", secondRequests, env.second.homeRequests.get())
            home.release()
            awaitLoaded(env, first)
            assertHomeOwner(env.vm.state.value.home, env.first)
        }
    }

    @Test fun aRealPullOnTheOldHomeCannotInterruptAnotherProfilesPendingValidation() = withApp { env ->
        main { env.vm.setPreferences(env.vm.state.value.preferences.copy(pullToRefreshEnabled = true)) }
        val first = login(env, env.first)
        val second = login(env, env.second)
        switchFromMenu(first)
        awaitLoaded(env, first)
        val firstHome = env.vm.state.value.home
        val firstHomeRequests = env.first.homeRequests.get()
        val firstAuthenticatedRequests = env.first.authenticatedRequests.get()
        val secondHomeRequests = env.second.homeRequests.get()
        env.backend.revoke(first.session.accessToken)
        env.second.blockNextValidation().use { validation ->
            switchFromMenu(second)
            await(env, "B 校验必须真实等待，此时仍保留 A 首页", allowFailure = true) {
                validation.requestArrived && it.loginBusy
            }
            validation.assertStillBlocked()
            assertSelected(env, first)
            assertHomeContentEquals(firstHome, env.vm.state.value.home)
            pullPageToRefresh(compose, stage = "old-home-pull-during-server-validation")
            compose.runOnIdle {
                assertTrue("切换校验未完成时必须继续保持忙状态", env.vm.state.value.loginBusy)
                assertNull(env.vm.state.value.reauthenticationProfile)
            }
            // 同时验证入口门禁和迟到页面 401；这里不替代上面的真实下拉。
            main {
                env.vm.refreshHomeFromPage()
                env.vm.authenticationExpired(first.session)
                assertTrue("旧页面 401 不能取消 B 的验证", env.vm.state.value.loginBusy)
                assertNull(env.vm.state.value.reauthenticationProfile)
            }
            compose.waitForIdle()
            assertEquals("校验期间不能重读旧账号媒体", firstHomeRequests, env.first.homeRequests.get())
            assertEquals("已失效 A 的真实下拉不能发送任何鉴权请求", firstAuthenticatedRequests, env.first.authenticatedRequests.get())
            assertEquals("B 验证成功前不能预读媒体", secondHomeRequests, env.second.homeRequests.get())
            assertHomeContentEquals(firstHome, env.vm.state.value.home)
            assertEquals(first.id, SessionStore(context).activeProfileId())
            validation.release()
            awaitLoaded(env, second)
            assertSelected(env, second)
            assertHomeOwner(env.vm.state.value.home, env.second)
            assertNull(env.vm.state.value.reauthenticationProfile)
            assertEquals(second.id, SessionStore(context).activeProfileId())
            assertEquals(firstHomeRequests, env.first.homeRequests.get())
            assertEquals(firstAuthenticatedRequests, env.first.authenticatedRequests.get())
        }
    }

    @Test fun realHomeRestoresIndependentVerticalAndHorizontalScrollWhileRefreshIsBlocked() = withApp { env ->
        val first = login(env, env.first)
        val firstPosition = scrollAndCapture(env.first, library = 2, movie = 7)
        val firstHome = env.vm.state.value.home
        val second = login(env, env.second)
        val secondPosition = scrollAndCapture(env.second, library = 1, movie = 3)
        val secondHome = env.vm.state.value.home
        assertTrue("A 纵向位置必须离开列表起点", firstPosition.vertical > 0f)
        assertTrue("A 海报栏必须真的横向滚动", firstPosition.horizontal > 0f)

        env.first.blockHomeResponses().use { home ->
            env.first.blockNextValidation().use { validation ->
                switchFromMenu(first)
                validation.awaitRequest()
                assertSelected(env, second)
                assertPosition(secondPosition, capturePosition(env.second, 1, 3), "Users/Me 等待期间 B")
                validation.release()
                await(env, "恢复 A 页面后必须启动自动读取") { home.requestArrived }
                await(env, "暖 A 应恢复原列表而非先渲染空首页") { it.currentProfileId == first.id && !it.loginBusy }
                home.assertStillBlocked()
                assertHomeContentEquals(firstHome, env.vm.state.value.home)
                assertPosition(firstPosition, capturePosition(env.first, 2, 7), "A 暖切后台刷新阻塞")
                home.release()
            }
            awaitLoaded(env, first)
            assertPosition(firstPosition, capturePosition(env.first, 2, 7), "A 后台刷新完成")
        }
        env.second.blockHomeResponses().use { home ->
            switchFromMenu(second)
            await(env, "恢复 B 页面后必须启动自动读取") { home.requestArrived }
            await(env, "再次切 B 应恢复 B 自己的位置") { it.currentProfileId == second.id && !it.loginBusy }
            home.assertStillBlocked()
            assertHomeContentEquals(secondHome, env.vm.state.value.home)
            assertPosition(secondPosition, capturePosition(env.second, 1, 3), "B 独立滚动恢复")
            home.release()
            awaitLoaded(env, second)
        }
    }

    @Test fun aPreviousAccountsDelayedHomeResponsesCannotOverwriteTheWarmCurrentAccount() = withApp { env ->
        val first = login(env, env.first)
        val second = login(env, env.second)
        val secondHome = env.vm.state.value.home
        switchFromMenu(first)
        awaitLoaded(env, first)
        env.first.homeVersion.set(2)
        val requests = env.first.homeRequests.get()
        val responses = env.first.homeResponses.get()
        env.first.blockHomeResponses().use { oldHome ->
            main { env.vm.refreshHome() }
            oldHome.awaitRequest()
            compose.waitUntil(WAIT_MS) { env.first.homeRequests.get() >= requests + 2 }
            oldHome.assertStillBlocked()
            env.second.blockHomeResponses().use { currentHome ->
                switchFromMenu(second)
                await(env, "B 页面自动读取必须独立启动") { currentHome.requestArrived }
                await(env, "B 暖内容必须独立于 A 的慢请求") { it.currentProfileId == second.id && !it.loginBusy }
                assertHomeContentEquals(secondHome, env.vm.state.value.home)
                oldHome.release()
                // 等旧 HTTP handler 确实走过释放点；被取消的连接允许写失败，但不能污染 B。
                compose.waitUntil(WAIT_MS) { env.first.homeResponses.get() >= responses + 2 }
                compose.waitForIdle()
                currentHome.assertStillBlocked()
                assertSelected(env, second)
                assertHomeContentEquals(secondHome, env.vm.state.value.home)
                assertHomeOwner(env.vm.state.value.home, env.second)
                assertNull(env.vm.state.value.reauthenticationProfile)
                assertNull(env.vm.state.value.home.error)
                currentHome.release()
                awaitLoaded(env, second)
            }
        }
    }

    @Test fun aWarmTargetsUnauthorizedValidationKeepsTheCurrentAccountAndRecoveryTarget() = withApp { env ->
        val first = login(env, env.first)
        val second = login(env, env.second)
        val secondHome = env.vm.state.value.home
        val profiles = env.vm.state.value.profiles
        val homeRequests = env.first.homeRequests.get()
        env.backend.revoke(first.session.accessToken)
        env.first.blockNextValidation().use { validation ->
            switchFromMenu(first)
            await(env, "暖 A 的 Users/Me 请求必须真实到达验证门闩", allowFailure = true) { validation.requestArrived }
            validation.assertStillBlocked()
            assertSelected(env, second)
            assertHomeContentEquals(secondHome, env.vm.state.value.home)
            validation.release()
            val failed = await(env, "暖缓存不能越过目标账号的 401 校验", allowFailure = true) {
                !it.loginBusy && it.reauthenticationProfile?.id == first.id
            }
            assertSelected(env, second)
            assertHomeContentEquals(secondHome, failed.home)
            assertHomeOwner(failed.home, env.second)
            assertEquals(profiles, failed.profiles)
            assertEquals(second.id, SessionStore(context).activeProfileId())
            assertEquals(profiles, SessionStore(context).loadProfiles())
            assertEquals("未授权目标不能启动首页刷新", homeRequests, env.first.homeRequests.get())
            assertFalse(failed.loginError.orEmpty().contains("密码"))
            main { env.vm.dismissReauthentication() }
            assertNull(env.vm.state.value.reauthenticationProfile)
            assertSelected(env, second)
            assertHomeContentEquals(secondHome, env.vm.state.value.home)
        }
    }

    @Test fun editingTheCurrentAddressDuringHomeLoadingFinishesAtTheNewAddressAndKeepsManagementOpen() = withApp { env ->
        val first = login(env, env.first)
        val authentications = env.backend.successfulAuthentications.get()
        val oldRequests = env.first.homeRequests.get()
        val oldResponses = env.first.homeResponses.get()
        env.first.homeVersion.set(2)
        env.first.blockHomeResponses().use { oldHome ->
            main { env.vm.showServers(); env.vm.refreshHome() }
            oldHome.awaitRequest()
            compose.waitUntil(WAIT_MS) { env.first.homeRequests.get() >= oldRequests + 2 }
            val routes = env.vm.state.value.routes
            env.second.blockHomeResponses().use { newHome ->
                env.second.blockNextValidation().use { validation ->
                    val updated = first.copy(session = first.session.copy(server = env.second.config))
                    main { env.vm.updateServer(updated) }
                    validation.awaitRequest()
                    oldHome.assertStillBlocked(); validation.assertStillBlocked()
                    assertTrue(env.vm.state.value.loginBusy)
                    assertEquals(first.session, env.vm.state.value.session)
                    assertTrue(env.vm.state.value.showServers)
                    validation.release()
                    newHome.awaitRequest()
                    val pending = await(env, "编辑地址不能因取消旧 Home 连带取消管理操作") {
                        it.session?.server == env.second.config && !it.loginBusy
                    }
                    newHome.assertStillBlocked()
                    assertEquals(first.id, pending.currentProfileId)
                    assertTrue(pending.showServers)
                    assertEquals(routes, pending.routes)
                    assertTrue(pending.home.loading)
                    oldHome.release()
                    compose.waitUntil(WAIT_MS) { env.first.homeResponses.get() >= oldResponses + 2 }
                    compose.waitForIdle()
                    newHome.assertStillBlocked()
                    assertEquals(env.second.config, env.vm.state.value.session?.server)
                    assertTrue(env.vm.state.value.showServers)
                    assertEquals("旧地址响应不能回填新会话首页", pending.home, env.vm.state.value.home)
                    newHome.release()
                    val complete = awaitLoaded(env, updated)
                    assertHomeOwner(complete.home, env.second)
                    assertTrue(complete.showServers)
                    assertEquals(routes, complete.routes)
                    assertFalse(complete.loginBusy)
                    assertFalse(complete.home.loading)
                    assertNull(complete.loginError)
                    assertEquals(authentications, env.backend.successfulAuthentications.get())
                    val stored = SessionStore(context).loadProfiles().single()
                    assertEquals(first.id, stored.id)
                    assertEquals(env.second.config, stored.session.server)
                    assertEquals(first.id, SessionStore(context).activeProfileId())
                }
            }
        }
    }

    private data class Environment(val vm: AppViewModel, val first: ProfilesFixture, val second: ProfilesFixture, val backend: BackendAuthState)

    private fun withApp(block: (Environment) -> Unit) {
        val backend = BackendAuthState()
        ProfilesFixture("A", backend, LIBRARIES, 12, includeUserImage = false).use { first ->
            ProfilesFixture("B", backend, LIBRARIES, 12, includeUserImage = false).use { second ->
                val preferences = AppPreferencesStore(context)
                val previousHidden = preferences.hiddenLibraries(first.preferencesAccountKey)
                preferences.saveHiddenLibraries(first.preferencesAccountKey, emptySet())
                val nextOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                owner = nextOwner
                val vm = main { ViewModelProvider(nextOwner,
                    ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java] }
                val env = Environment(vm, first, second, backend)
                try {
                    await(env, "验证账号初始恢复必须完成") { !it.restoringSession }
                    assertTrue(vm.state.value.profiles.isEmpty())
                    compose.setContent {
                        val state by vm.state.collectAsState()
                        val saved = rememberSaveableStateHolder()
                        PathfinTheme(ThemePreference.LIGHT) {
                            Surface(Modifier.fillMaxSize()) { PathfinApp(vm, state, saved, posterLoader = loader) }
                        }
                    }
                    block(env)
                    first.assertHealthy(); second.assertHealthy()
                } finally {
                    clearViewModel()
                    preferences.saveHiddenLibraries(first.preferencesAccountKey, previousHidden)
                }
            }
        }
    }

    private fun login(env: Environment, fixture: ProfilesFixture): ServerProfile {
        val before = fixture.authentications.get()
        main { env.vm.login(fixture.baseUrl, USERNAME, PASSWORD, allowHttp = true, displayName = "Fixture ${fixture.label}") }
        val state = await(env, "合成账号首次加载必须完整完成") {
            fixture.authentications.get() == before + 1 && it.session?.server?.baseUrl == fixture.baseUrl &&
                !it.loginBusy && it.home.loaded && !it.home.loading
        }
        assertHomeOwner(state.home, fixture)
        VerificationDisplay.awaitWindowReady(compose, "loaded-${fixture.label}", compose.onNodeWithContentDescription("切换服务器"))
        val profile = state.profiles.single { it.id == state.currentProfileId }
        val settled = awaitLoaded(env, profile)
        return settled.profiles.single { it.id == settled.currentProfileId }
    }

    private fun switchFromMenu(target: ServerProfile) {
        val button = compose.onNodeWithContentDescription("切换服务器")
        VerificationDisplay.awaitWindowReady(compose, "before-switch-${target.displayName}", button)
        button.performVerificationClick(compose)
        val choice = compose.onNode(hasText(target.displayName) and hasAnyAncestor(isPopup()))
        // Popup 有自己的窗口；语义节点出现时，首次绘制和真实触摸坐标仍可能未就绪。
        VerificationDisplay.awaitWindowReady(compose, "switch-popup-${target.displayName}", choice)
        choice.assertVerificationDisplayed(compose).assertIsEnabled().performVerificationClick(compose)
        // 等 DropdownMenu 的独立窗口退出，不能把随后触摸/位置检查送到残留 Popup。
        compose.waitUntil(10_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
        VerificationDisplay.awaitWindowReady(compose, "after-popup-${target.displayName}", button)
    }

    private fun awaitLoaded(env: Environment, profile: ServerProfile) = await(env, "当前账号后台首页应完成") {
        it.currentProfileId == profile.id && !it.loginBusy && it.home.loaded && !it.home.loading
    }

    private fun await(env: Environment, description: String, allowFailure: Boolean = false, predicate: (AppState) -> Boolean): AppState {
        try {
            compose.waitUntil(WAIT_MS) {
                env.first.assertHealthy(); env.second.assertHealthy()
                val state = env.vm.state.value
                if (!allowFailure && !state.loginBusy) {
                    state.loginError?.let { throw AssertionError("$description；$it") }
                    state.home.error?.let { throw AssertionError("$description；$it") }
                }
                predicate(state)
            }
        } catch (failure: Throwable) {
            env.first.assertHealthy(); env.second.assertHealthy()
            val failed = env.vm.state.value
            throw AssertionError("$description；current=${failed.currentProfileId}, busy=${failed.loginBusy}, " +
                "reauth=${failed.reauthenticationProfile?.id}, loaded=${failed.home.loaded}, loading=${failed.home.loading}, " +
                "UsersMe=A:${env.first.validations.get()}/B:${env.second.validations.get()}", failure)
        }
        compose.waitForIdle()
        return env.vm.state.value
    }

    private fun assertSelected(env: Environment, profile: ServerProfile) {
        assertEquals(profile.id, env.vm.state.value.currentProfileId)
        assertEquals(profile.session, env.vm.state.value.session)
        assertFalse("切换器不得把用户送回服务器管理页", env.vm.state.value.showServers)
    }

    private fun assertHomeContentEquals(expected: HomeState, actual: HomeState) {
        assertTrue("暖内容必须仍是已加载状态", actual.loaded)
        assertEquals(expected.libraries, actual.libraries)
        assertEquals(expected.resume, actual.resume)
        assertEquals(expected.recent, actual.recent)
        assertEquals(expected.libraryItems, actual.libraryItems)
        assertEquals(expected.nextUp, actual.nextUp)
    }

    private fun assertHomeOwner(home: HomeState, fixture: ProfilesFixture) {
        assertEquals(LIBRARIES, home.libraries.size)
        assertEquals(LIBRARIES, home.libraryItems.size)
        assertEquals(1, home.resume.size)
        val names = home.libraries.map { it.name } + home.resume.map { it.name } + home.libraryItems.values.flatten().map { it.name }
        assertTrue("同 id 的媒体数据必须仍属于当前入口 " + fixture.label, names.all { it.startsWith(fixture.label + " ") })
        home.libraryItems.values.forEach { assertEquals(12, it.size) }
    }

    private val verticalList get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))

    private fun row(fixture: ProfilesFixture, library: Int, movie: Int) = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasTestTag("poster-title/${fixture.movieId(library, movie)}")), useUnmergedTree = true)

    private data class Position(val vertical: Float, val horizontal: Float, val card: Rect, val heading: Rect)

    private fun scrollAndCapture(fixture: ProfilesFixture, library: Int, movie: Int): Position {
        verticalList.performScrollToIndex(2 + library)
        row(fixture, library, 0).performScrollToIndex(movie)
        return capturePosition(fixture, library, movie)
    }

    private fun capturePosition(fixture: ProfilesFixture, library: Int, movie: Int): Position {
        val card = compose.onNodeWithTag("poster-title/${fixture.movieId(library, movie)}", true)
        val heading = compose.onNodeWithText("最近入库 · ${fixture.libraryName(library, 1)}")
        compose.waitForIdle()
        VerificationDisplay.awaitWindowReady(compose, "scroll-${fixture.label}", card, heading)
        card.assertVerificationDisplayed(compose); heading.assertVerificationDisplayed(compose)
        val verticalNode = verticalList.fetchSemanticsNode()
        val horizontalNode = row(fixture, library, movie).fetchSemanticsNode()
        val cardNode = card.fetchSemanticsNode()
        val headingNode = heading.fetchSemanticsNode()
        return compose.runOnIdle {
            Position(verticalNode.config[SemanticsProperties.VerticalScrollAxisRange].value(),
                horizontalNode.config[SemanticsProperties.HorizontalScrollAxisRange].value(),
                cardNode.boundsInRoot, headingNode.boundsInRoot)
        }
    }

    private fun assertPosition(expected: Position, actual: Position, stage: String) {
        assertEquals("$stage 纵向位置", expected.vertical, actual.vertical, .01f)
        assertEquals("$stage 横向位置", expected.horizontal, actual.horizontal, .01f)
        assertEquals("$stage 标题纵向锚点", expected.heading.top, actual.heading.top, 1f)
        assertEquals("$stage 卡片纵向锚点", expected.card.top, actual.card.top, 1f)
        assertEquals("$stage 卡片横向锚点", expected.card.left, actual.card.left, 1f)
    }

    private fun clearViewModel() { owner?.let { main { it.viewModelStore.clear() } }; owner = null }
    private fun clearSessions() {
        check(context.packageName == VERIFICATION_PACKAGE)
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val USERNAME = "synthetic-profiles-user"
        const val PASSWORD = "synthetic-profiles-password"
        const val LIBRARIES = 6
        const val WAIT_MS = 20_000L
    }
}

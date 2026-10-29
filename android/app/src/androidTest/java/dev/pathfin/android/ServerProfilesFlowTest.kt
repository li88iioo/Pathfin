package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 两个真实 HTTP 入口共享一个后端；同 userId + DeviceId 再登录会撤销旧令牌。 */
@RunWith(AndroidJUnit4::class)
class ServerProfilesFlowTest {
    @Test
    fun fixtureReallyRevokesTheOldTokenAcrossBothEntrancesForTheSameUserAndDevice() = withServers { env ->
        runBlocking {
            val api = JellyfinApi()
            val old = api.authenticate(env.first.config, USERNAME, PASSWORD, LEGACY_DEVICE_ID)
            val fresh = api.authenticate(env.second.config, USERNAME, PASSWORD, LEGACY_DEVICE_ID)
            assertNotEquals(old.accessToken, fresh.accessToken)
            // 独立证明夹具确实吊销旧 token，而非按端口放行固定 token。
            assertExpired { api.validateSession(old) }
            assertExpired { api.validateSession(old.copy(server = env.second.config)) }
            assertEquals(USER_ID, api.validateSession(fresh).userId)
            assertEquals(USER_ID, api.validateSession(fresh.copy(server = env.first.config)).userId)
            assertEquals(1, env.first.authentications.get())
            assertEquals(1, env.second.authentications.get())
            assertEquals(2, env.backend.successfulAuthentications.get())
        }
    }

    @Test
    fun theSameDeviceDoesNotRevokeAnotherUsersSessionOnTheSharedBackend() = withServers { env ->
        runBlocking {
            val api = JellyfinApi()
            val firstUser = api.authenticate(env.first.config, USERNAME, PASSWORD, LEGACY_DEVICE_ID)
            val secondUser = api.authenticate(env.second.config, OTHER_USERNAME, PASSWORD, LEGACY_DEVICE_ID)
            assertNotEquals(firstUser.userId, secondUser.userId)
            assertEquals(firstUser.deviceId, secondUser.deviceId)
            assertEquals(USER_ID, api.validateSession(firstUser.copy(server = env.second.config)).userId)
            assertEquals(OTHER_USER_ID, api.validateSession(secondUser.copy(server = env.first.config)).userId)
        }
    }

    @Test
    fun sharedBackendProfilesSurviveRepeatedSwitchesVmRestoreAndSameAddressRelogin() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A").copy(
            icon = "A", showUserName = false, showLastPlayed = false, lastPlayedAt = 111_000,
        ).also { updateProfile(env, it) }
        // 备用地址仍属于 A；以后显式添加 B 不能因此把两份资料合并。
        val originalFirst = first.copy(addresses = listOf(env.second.config))
        main { env.app.addServerAddress(first, env.second.config) }
        awaitState(env, description = "备用地址应以同一个后端 token 校验并保留 A 资料") {
            !it.loginBusy && it.profiles == listOf(originalFirst)
        }
        assertTrue(env.second.publicProbes.get() > 0)
        assertTrue(env.second.validations.get() > 0)
        val originalSecond = login(env, env.second, "合成服务器 B").copy(
            icon = "B", showUserName = true, showLastPlayed = false, lastPlayedAt = 222_000,
        ).also { updateProfile(env, it) }
        assertNotEquals(originalFirst.id, originalSecond.id)
        assertEquals(originalFirst.session.serverId, originalSecond.session.serverId)
        assertEquals(originalFirst.session.userId, originalSecond.session.userId)
        assertNotEquals("同后端两入口不能共用 DeviceId，否则 B 登录会吊销 A", originalFirst.session.deviceId, originalSecond.session.deviceId)
        val expected = listOf(originalFirst, originalSecond)
        assertEquals(expected, env.app.state.value.profiles)
        listOf(originalFirst, originalSecond, originalFirst, originalSecond).forEach { select(env, it, expected) }
        assertEquals("切换不能偷偷重新提交用户名密码", 2, env.backend.successfulAuthentications.get())
        assertStoreRestores(expected, originalSecond)
        env.restart()
        awaitSelected(env, originalSecond)
        assertEquals(expected, env.app.state.value.profiles)
        select(env, originalFirst, expected)
        select(env, originalSecond, expected)
        assertEquals("重建 VM 和存储也只能校验会话", 2, env.backend.successfulAuthentications.get())

        val refreshedFirst = login(env, env.first, "合成服务器 A 重登", url = env.first.baseUrl + "/")
        assertEquals(originalFirst.id, refreshedFirst.id)
        assertEquals("同址重登应保留该资料独有的 DeviceId", originalFirst.session.deviceId, refreshedFirst.session.deviceId)
        assertNotEquals(originalFirst.session.accessToken, refreshedFirst.session.accessToken)
        assertEquals(originalFirst.copy(session = refreshedFirst.session, displayName = "合成服务器 A 重登"), refreshedFirst)
        val refreshed = listOf(refreshedFirst, originalSecond)
        assertEquals(refreshed, env.app.state.value.profiles)
        runBlocking {
            assertExpired { JellyfinApi().validateSession(originalFirst.session) }
            assertEquals(originalSecond.session, JellyfinApi().validateSession(originalSecond.session))
        }
        select(env, originalSecond, refreshed)
        select(env, refreshedFirst, refreshed)
        assertStoreRestores(refreshed, refreshedFirst)
        assertEquals(2, env.first.authentications.get())
        assertEquals(1, env.second.authentications.get())
    }

    @Test
    fun legacySharedDeviceTargetCanBeCancelledAndRecoveredWithoutDeletingEitherProfile() = withServers { env ->
        val (expiredFirst, validSecond) = seedLegacyProfiles(env, activeFirst = false)
        val expected = listOf(expiredFirst, validSecond)
        awaitSelected(env, validSecond)
        assertEquals(LEGACY_DEVICE_ID, expiredFirst.session.deviceId)
        assertEquals(LEGACY_DEVICE_ID, validSecond.session.deviceId)
        val authentications = env.backend.successfulAuthentications.get()
        requestReauthentication(env, expiredFirst)
        main { env.app.authenticationExpired(validSecond.session) }
        assertEquals("旧页面迟到的 401 不能覆盖已选择的恢复目标", expiredFirst, env.app.state.value.reauthenticationProfile)
        assertSelectionAndStorage(env, validSecond, expected)
        assertSessionFailureDoesNotClaimBadPassword(env.app.state.value.loginError)
        main { env.app.dismissReauthentication() }
        assertNull(env.app.state.value.reauthenticationProfile)
        assertSelectionAndStorage(env, validSecond, expected)
        assertEquals("取消不能发起登录", authentications, env.backend.successfulAuthentications.get())

        requestReauthentication(env, expiredFirst)
        val attempts = env.first.authentications.get()
        main { env.app.reauthenticate("synthetic-wrong-password") }
        val rejected = awaitState(env, description = "错误密码应保留待恢复资料及另一可用资料", allowFailure = true) {
            env.first.authentications.get() == attempts + 1 && !it.loginBusy && it.reauthenticationProfile?.id == expiredFirst.id
        }
        assertTrue(rejected.loginError.orEmpty().contains("密码"))
        assertSelectionAndStorage(env, validSecond, expected)
        runBlocking { assertEquals(validSecond.session, JellyfinApi().validateSession(validSecond.session)) }
        env.first.blockNextAuthentication().use { gate ->
            main { env.app.reauthenticate(PASSWORD) }
            gate.awaitRequest()
            main {
                assertTrue(env.app.state.value.loginBusy)
                env.app.authenticationExpired(validSecond.session)
                assertTrue("旧页面迟到的 401 不能取消正在提交的认证", env.app.state.value.loginBusy)
                assertEquals(expiredFirst, env.app.state.value.reauthenticationProfile)
            }
            gate.release()
        }
        val restored = awaitState(env, description = "旧共享 DeviceId 资料重新认证后应恢复原资料") {
            it.currentProfileId == expiredFirst.id && it.reauthenticationProfile == null &&
                !it.loginBusy && it.home.loaded && !it.home.loading
        }.profiles.single { it.id == expiredFirst.id }
        assertNotEquals("共享的旧 DeviceId 应在本次成功登录时迁移", LEGACY_DEVICE_ID, restored.session.deviceId)
        assertEquals(expiredFirst.copy(session = restored.session), restored)
        val recovered = listOf(restored, validSecond)
        assertEquals(recovered, env.app.state.value.profiles)
        assertStoreRestores(recovered, restored)
        select(env, validSecond, recovered)
        select(env, restored, recovered)
        assertEquals("只有恢复目标应新增一次成功认证", authentications + 1, env.backend.successfulAuthentications.get())
    }

    @Test
    fun expiredActiveSessionOnRestoreOrHomeRefreshKeepsProfilesAndPreferences() = withServers { env ->
        val (expiredFirst, validSecond) = seedLegacyProfiles(env, activeFirst = true)
        val expected = listOf(expiredFirst, validSecond)
        awaitState(env, description = "恢复时旧 token 失效必须要求重新认证而非删除资料", allowFailure = true) {
            !it.restoringSession && !it.loginBusy && it.reauthenticationProfile?.id == expiredFirst.id
        }
        assertSelectionAndStorage(env, expiredFirst, expected)
        main { env.app.reauthenticate(PASSWORD) }
        val recovered = awaitState(env, description = "恢复当前账号后首页应重新可用") {
            !it.loginBusy && it.reauthenticationProfile == null && it.home.loaded && !it.home.loading
        }.profiles.single { it.id == expiredFirst.id }
        val afterRecovery = listOf(recovered, validSecond)
        assertSelectionAndStorage(env, recovered, afterRecovery)
        env.backend.revoke(recovered.session.accessToken)
        main { env.app.refreshHome() }
        val expiredAgain = awaitState(env, description = "首页认证过期也必须保留原资料及偏好", allowFailure = true) {
            !it.home.loading && it.reauthenticationProfile?.id == recovered.id
        }
        assertSessionFailureDoesNotClaimBadPassword(expiredAgain.loginError)
        assertSelectionAndStorage(env, recovered, afterRecovery)
        main { env.app.dismissReauthentication() }
        assertNull(env.app.state.value.reauthenticationProfile)
        assertSelectionAndStorage(env, recovered, afterRecovery)
    }

    @Test
    fun reauthenticationCannotReplaceAProfileWithAnotherUserOrServer() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A").copy(icon = "A", showUserName = false)
            .also { updateProfile(env, it) }
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        env.backend.revoke(first.session.accessToken)
        requestReauthentication(env, first)
        // 模拟入口被错误反向代理：HTTP 200 也不能替换用户或服务器身份。
        listOf(OTHER_USER_ID to SERVER_ID, USER_ID to "synthetic-other-server", USER_ID to "").forEach { (userId, serverId) ->
            env.first.authenticationUserId.set(userId)
            env.first.authenticationServerId.set(serverId)
            val attempts = env.first.authentications.get()
            main { env.app.reauthenticate(PASSWORD) }
            awaitState(env, description = "认证返回的账号和服务器必须与恢复目标一致", allowFailure = true) {
                env.first.authentications.get() == attempts + 1 && !it.loginBusy &&
                    it.reauthenticationProfile?.id == first.id && !it.loginError.isNullOrBlank()
            }
            assertSelectionAndStorage(env, second, expected)
        }
        env.first.authenticationUserId.set(null)
        env.first.authenticationServerId.set(null)
        main { env.app.reauthenticate(PASSWORD) }
        val recovered = awaitState(env, description = "错误入口恢复正常后原资料仍能重试恢复") {
            !it.loginBusy && it.reauthenticationProfile == null && it.currentProfileId == first.id &&
                it.home.loaded && !it.home.loading
        }.profiles.single { it.id == first.id }
        assertEquals(first.copy(session = recovered.session), recovered)
        assertEquals(listOf(recovered, second), env.app.state.value.profiles)
        select(env, second, listOf(recovered, second))
    }

    @Test
    fun alternateAddressWithMissingOrDifferentServerIdentityNeverReceivesTheToken() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        val alternate = first.copy(session = first.session.copy(server = env.second.config))
        listOf("synthetic-other-server", "").forEach { publicId ->
            env.second.publicServerId.set(publicId)
            val probes = env.second.publicProbes.get()
            val authenticatedRequests = env.second.authenticatedRequests.get()
            main { env.app.showServers(); env.app.openServer(alternate) }
            val failed = awaitState(env, description = "备用地址身份不匹配时必须在发凭据之前失败", allowFailure = true) {
                env.second.publicProbes.get() == probes + 1 && !it.loginBusy && !it.loginError.isNullOrBlank()
            }
            assertNull(failed.reauthenticationProfile)
            assertEquals("服务器身份未确认时不得发送 Authorization", authenticatedRequests, env.second.authenticatedRequests.get())
            assertSelectionAndStorage(env, second, expected)
        }
        assertEquals(2, env.backend.successfulAuthentications.get())
    }

    @Test
    fun wrongUserFromCurrentUserEndpointCannotChangeSelectionOrPersistedSession() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        select(env, first, expected)
        env.second.blockNextValidation().use { gate ->
            main { env.app.showServers(); env.app.openServer(second) }
            gate.awaitRequest()
            main {
                env.app.authenticationExpired(first.session)
                assertTrue("旧账号页面迟到的 401 不能取消正在校验的服务器切换", env.app.state.value.loginBusy)
                assertNull(env.app.state.value.reauthenticationProfile)
                assertEquals(first.id, env.app.state.value.currentProfileId)
            }
            gate.release()
        }
        awaitSelected(env, second)
        assertSelectionAndStorage(env, second, expected)
        select(env, first, expected)
        env.second.validationUserId.set(OTHER_USER_ID)
        requestReauthentication(env, second)
        assertSelectionAndStorage(env, first, expected)
        assertSessionFailureDoesNotClaimBadPassword(env.app.state.value.loginError)
        assertEquals("错误的 Users/Me 响应不能自动改为密码登录", 2, env.backend.successfulAuthentications.get())
    }

    @Test
    fun pendingServerValidationCanReturnToTheCurrentWarmProfileBeforeTheResponseArrives() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        select(env, first, expected)
        val originalHome = env.app.state.value.home
        env.second.validationImageTag.set("synthetic-late-avatar")

        env.second.blockNextValidation().use { gate ->
            main { env.app.openServer(second) }
            gate.awaitRequest()
            val pending = pendingLogin(env)
            main {
                assertTrue(env.app.state.value.loginBusy)
                assertFalse("首页切换验证期间不能退回服务器管理页", env.app.state.value.showServers)
                env.app.openServer(first)
            }
            val restored = awaitSelected(env, first, timeoutMs = RESWITCH_WAIT_MS)
            awaitCancelledSwitch(pending)
            gate.assertStillBlocked()
            assertEquals("取消慢服务器时应直接保留原账号完整首页", originalHome, restored.home)
            assertNoSwitchFailure(restored)
            assertSelectionAndStorage(env, first, expected)

            gate.release()
            gate.awaitResponseAttempt(200)
            main {
                assertEquals(originalHome, env.app.state.value.home)
                assertNoSwitchFailure(env.app.state.value)
                assertSelectionAndStorage(env, first, expected)
            }
        }
        assertEquals("取消切换只能校验已有会话，不能重新认证", 2, env.backend.successfulAuthentications.get())
    }

    @Test
    fun reselectingHomeDuringPendingServerValidationRestartsTheInterruptedIncompleteHome() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        select(env, first, expected)
        env.first.homeVersion.set(2)
        env.second.validationImageTag.set("synthetic-late-avatar")

        env.first.blockHomeResponses().use { home ->
            main { env.app.refreshHome() }
            home.awaitRequest()
            env.second.blockNextValidation().use { validation ->
                main {
                    assertTrue(env.app.state.value.home.loaded)
                    assertFalse(env.app.state.value.home.complete)
                    assertTrue(env.app.state.value.home.loading)
                    env.app.openServer(second)
                }
                validation.awaitRequest()
                val pending = pendingLogin(env)
                env.first.homeVersion.set(3)
                main {
                    assertEquals(listOf(Route.Home), env.app.state.value.routes)
                    assertFalse("切服应已中断原先未完成的首页读取", env.app.state.value.home.loading)
                    env.app.selectTab(Route.Home)
                    assertNoSwitchFailure(env.app.state.value)
                    assertTrue("重复首页入口必须恢复未完成的读取，不能因同页提前返回", env.app.state.value.home.loading)
                }
                awaitCancelledSwitch(pending)
                home.assertStillBlocked()
                validation.assertStillBlocked()

                validation.release()
                validation.awaitResponseAttempt(200)
                main {
                    assertEquals(listOf(Route.Home), env.app.state.value.routes)
                    assertTrue("迟到切服成功不能中断已恢复的原账号首页读取", env.app.state.value.home.loading)
                    assertNoSwitchFailure(env.app.state.value)
                    assertSelectionAndStorage(env, first, expected)
                }
                home.release()
                val restored = awaitState(env, description = "重复首页入口应补齐被切服中断的原账号数据") {
                    it.home.complete && !it.home.loading
                }
                assertEquals(env.first.libraryName(0, 3), restored.home.libraries.single().name)
                assertEquals(listOf(env.first.movieName(0, 0, 3)), restored.home.libraryItems.getValue(env.first.libraryId(0)).map { it.name })
                assertNoSwitchFailure(restored)
                assertSelectionAndStorage(env, first, expected)
            }
        }
    }

    @Test
    fun openingTheCurrentLibraryCancelsPendingServerValidationAndLoadsFromTheCurrentAccount() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        select(env, first, expected)
        val library = env.app.state.value.home.libraries.single()
        val route = Route.Folder(library)
        env.second.validationImageTag.set("synthetic-late-avatar")

        env.second.blockNextValidation().use { gate ->
            main { env.app.openServer(second) }
            gate.awaitRequest()
            val pending = pendingLogin(env)
            main {
                assertTrue(env.app.state.value.loginBusy)
                env.app.open(library)
                val opened = env.app.state.value
                assertEquals(route, opened.route)
                assertNoSwitchFailure(opened)
                assertTrue("首读必须直接进入加载态，不能先暴露空媒体库", opened.browsers.getValue(library.id).content.loading)
            }
            val loaded = awaitState(env, description = "旧服务器未响应时仍须立即读取原账号媒体库", timeoutMs = RESWITCH_WAIT_MS) {
                val browser = it.browsers[library.id]
                it.route == route && browser != null && !browser.content.loading && browser.content.items.isNotEmpty() &&
                    !browser.filtersLoading && browser.filterOptions != null
            }
            awaitCancelledSwitch(pending)
            gate.assertStillBlocked()
            val content = loaded.browsers.getValue(library.id).content
            assertNull(content.error)
            assertNull(loaded.browsers.getValue(library.id).filtersError)
            assertEquals(listOf(env.first.movieName(0, 0)), content.items.map { it.name })
            assertEquals("读取必须发给当前 A 的认证身份", listOf(first.session.deviceId), env.first.browserDeviceIds.toList())
            assertEquals(1, env.first.browserRequests.get())
            assertEquals("慢 B 不能接收原 A 的媒体库请求", 0, env.second.browserRequests.get())
            assertSelectionAndStorage(env, first, expected)

            gate.release()
            gate.awaitResponseAttempt(200)
            main {
                assertEquals("迟到切服响应不能把媒体库导航切回首页", route, env.app.state.value.route)
                assertEquals(content, env.app.state.value.browsers.getValue(library.id).content)
                assertNoSwitchFailure(env.app.state.value)
                assertSelectionAndStorage(env, first, expected)
            }
        }
    }

    @Test
    fun openingSettingsCancelsPendingServerValidationAndKeepsTheCurrentAccount() = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val expected = listOf(first, second)
        select(env, first, expected)
        env.second.validationImageTag.set("synthetic-late-avatar")

        env.second.blockNextValidation().use { gate ->
            main { env.app.openServer(second) }
            gate.awaitRequest()
            val pending = pendingLogin(env)
            main {
                assertTrue(env.app.state.value.loginBusy)
                env.app.openSettings()
                assertEquals("设置导航必须立即生效，不能等慢服务器校验结束", Route.Settings, env.app.state.value.route)
                assertNoSwitchFailure(env.app.state.value)
                assertSelectionAndStorage(env, first, expected)
            }
            awaitCancelledSwitch(pending)
            gate.assertStillBlocked()

            gate.release()
            gate.awaitResponseAttempt(200)
            main {
                assertEquals("被取消切服的迟到成功不能把设置页切走", Route.Settings, env.app.state.value.route)
                assertNoSwitchFailure(env.app.state.value)
                assertSelectionAndStorage(env, first, expected)
            }
        }
    }

    @Test
    fun lateSuccessfulServerValidationCannotOverrideTheThirdSelectedAccount() = pendingValidationCannotOverrideThirdAccount(reject = false)

    @Test
    fun lateRejectedServerValidationCannotOverrideTheThirdSelectedAccount() = pendingValidationCannotOverrideThirdAccount(reject = true)

    private fun pendingValidationCannotOverrideThirdAccount(reject: Boolean) = withServers { env ->
        val first = login(env, env.first, "合成服务器 A")
        val second = login(env, env.second, "合成服务器 B")
        val third = login(env, env.first, "合成账号 C", username = OTHER_USERNAME)
        val expected = listOf(first, second, third)
        val thirdHome = env.app.state.value.home
        select(env, first, expected)
        env.second.validationImageTag.set("synthetic-late-avatar")

        env.second.blockNextValidation().use { gate ->
            main { env.app.openServer(second) }
            gate.awaitRequest()
            val pending = pendingLogin(env)
            if (reject) env.backend.revoke(second.session.accessToken)
            main { env.app.openServer(third) }
            val selected = awaitSelected(env, third, timeoutMs = RESWITCH_WAIT_MS)
            awaitCancelledSwitch(pending)
            gate.assertStillBlocked()
            assertEquals("第三账号应恢复自己的首页，不能被慢服务器阻塞", thirdHome, selected.home)
            assertNoSwitchFailure(selected)
            assertSelectionAndStorage(env, third, expected)

            gate.release()
            gate.awaitResponseAttempt(if (reject) 401 else 200)
            main {
                assertEquals(thirdHome, env.app.state.value.home)
                assertNoSwitchFailure(env.app.state.value)
                assertSelectionAndStorage(env, third, expected)
            }
        }
        env.restart()
        assertNoSwitchFailure(awaitSelected(env, third))
        assertSelectionAndStorage(env, third, expected)
        assertEquals("第三账号切换及进程恢复不能重新认证", 3, env.backend.successfulAuthentications.get())
    }

    private fun assertNoSwitchFailure(state: AppState) {
        assertFalse(state.loginBusy)
        assertNull("被取消目标的迟到错误不能污染新选择", state.loginError)
        assertNull(state.notice)
        assertNull("被取消目标的迟到 401 不能要求新账号重新认证", state.reauthenticationProfile)
    }

    /** 只等待实际切换协程取消完成，避免靠任意延时判断迟到回包是否还会发布。 */
    private fun pendingLogin(env: Environment): Job = main {
        checkNotNull(AppViewModel::class.java.getDeclaredField("loginJob").apply { isAccessible = true }.get(env.app) as? Job)
    }

    private fun awaitCancelledSwitch(pending: Job) = runBlocking {
        withTimeout(RESWITCH_WAIT_MS) { pending.join() }
        assertTrue("新选择必须取消仍在等待 HTTP 的旧切换", pending.isCancelled)
    }

    private fun login(env: Environment, fixture: ProfilesFixture, name: String, url: String = fixture.baseUrl,
        username: String = USERNAME): ServerProfile {
        val before = fixture.authentications.get()
        main { env.app.showServers(); env.app.login(url, username, PASSWORD, allowHttp = true, displayName = name) }
        val state = awaitState(env, description = "合成入口登录后应激活新会话") {
            fixture.authentications.get() == before + 1 && it.session?.server?.baseUrl == fixture.baseUrl &&
                !it.showServers && !it.loginBusy && it.home.loaded && !it.home.loading
        }
        return state.profiles.single { it.id == state.currentProfileId }.also {
            assertEquals(fixture.baseUrl, it.session.server.baseUrl)
            assertEquals(username, it.session.userName)
            assertEquals(if (username == OTHER_USERNAME) OTHER_USER_ID else USER_ID, it.session.userId)
            assertEquals(SERVER_ID, it.session.serverId)
        }
    }

    private fun updateProfile(env: Environment, profile: ServerProfile) {
        main { env.app.updateServer(profile) }
        awaitState(env, description = "本机显示偏好应完整持久化") {
            !it.loginBusy && it.profiles.singleOrNull { candidate -> candidate.id == profile.id } == profile
        }
    }

    private fun select(env: Environment, target: ServerProfile, expected: List<ServerProfile>) {
        val authentications = env.first.authentications.get() + env.second.authentications.get()
        main {
            env.app.showServers()
            assertEquals("打开管理列表不能删除或合并资料", expected, env.app.state.value.profiles)
            env.app.openServer(env.app.state.value.profiles.single { it.id == target.id })
        }
        awaitSelected(env, target)
        assertSelectionAndStorage(env, target, expected)
        assertEquals("切换已有资料不得重新提交密码", authentications, env.first.authentications.get() + env.second.authentications.get())
    }

    private fun awaitSelected(env: Environment, target: ServerProfile, timeoutMs: Long = WAIT_MS): AppState =
        awaitState(env, description = "目标资料应通过真实 Users/Me 后加载首页", timeoutMs = timeoutMs) {
            it.currentProfileId == target.id && it.session == target.session && !it.showServers &&
                !it.loginBusy && !it.restoringSession && it.home.loaded && !it.home.loading
        }

    private fun requestReauthentication(env: Environment, target: ServerProfile) {
        main { env.app.showServers(); env.app.openServer(target) }
        val state = awaitState(env, description = "失效目标必须打开重新认证并保留当前资料", allowFailure = true) {
            !it.loginBusy && it.reauthenticationProfile?.id == target.id && !it.loginError.isNullOrBlank()
        }
        assertEquals(target, state.reauthenticationProfile)
    }

    private fun seedLegacyProfiles(env: Environment, activeFirst: Boolean): Pair<ServerProfile, ServerProfile> {
        env.clearApp()
        val sessions = runBlocking {
            val api = JellyfinApi()
            val first = api.authenticate(env.first.config, USERNAME, PASSWORD, LEGACY_DEVICE_ID)
            val second = api.authenticate(env.second.config, USERNAME, PASSWORD, LEGACY_DEVICE_ID)
            assertExpired { api.validateSession(first) }
            assertEquals(second, api.validateSession(second))
            first to second
        }
        val first = ServerProfile("synthetic-legacy-a", sessions.first, "旧资料 A", icon = "A",
            showUserName = false, showLastPlayed = false, lastPlayedAt = 333_000, addresses = listOf(env.second.config))
        val second = ServerProfile("synthetic-legacy-b", sessions.second, "旧资料 B", icon = "B",
            showUserName = true, showLastPlayed = false, lastPlayedAt = 444_000, addresses = listOf(env.first.config))
        SessionStore(context()).apply {
            upsertProfile(first); upsertProfile(second); selectProfile(if (activeFirst) first.id else second.id)
        }
        env.restart(allowFailure = activeFirst)
        return first to second
    }

    private fun assertSelectionAndStorage(env: Environment, active: ServerProfile, expected: List<ServerProfile>) {
        val state = env.app.state.value
        assertEquals(active.id, state.currentProfileId)
        assertEquals(active.session, state.session)
        assertEquals(expected, state.profiles)
        assertStoreRestores(expected, active)
    }

    private fun assertStoreRestores(expected: List<ServerProfile>, active: ServerProfile) {
        repeat(2) {
            val store = SessionStore(context())
            assertEquals(expected, store.loadProfiles())
            assertEquals(active.id, store.activeProfileId())
            assertEquals("重建加密存储不能把会话移到另一入口", active.session, store.load())
            assertEquals(expected, store.loadProfiles())
            assertEquals(active.id, store.activeProfileId())
        }
    }

    private suspend fun assertExpired(action: suspend () -> Unit) {
        val error = try { action(); throw AssertionError("服务端应返回真实 401") } catch (error: ApiException) { error }
        assertEquals(401, error.statusCode)
        assertSessionFailureDoesNotClaimBadPassword(error.message)
    }

    private fun assertSessionFailureDoesNotClaimBadPassword(message: String?) {
        assertFalse("会话校验没有提交密码，不能误报密码错误", message.orEmpty().contains("密码"))
        assertTrue("认证失效必须提供明确消息", !message.isNullOrBlank())
    }

    private inner class Environment(val first: ProfilesFixture, val second: ProfilesFixture, val backend: BackendAuthState) : Closeable {
        private var owner: ViewModelStoreOwner? = null
        lateinit var app: AppViewModel
            private set
        fun restart(allowFailure: Boolean = false) {
            clearApp()
            val nextOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            owner = nextOwner
            app = main {
                ViewModelProvider(nextOwner, ViewModelProvider.AndroidViewModelFactory(context().applicationContext as Application))[AppViewModel::class.java]
            }
            awaitState(this, description = "验证 VM 应完成本机恢复", allowFailure = allowFailure) { !it.restoringSession }
        }
        fun clearApp() { owner?.let { previous -> main { previous.viewModelStore.clear() } }; owner = null }
        override fun close() = clearApp()
    }

    private fun withServers(block: (Environment) -> Unit) {
        val context = context()
        val preferences = AppPreferencesStore(context)
        val previous = preferences.load()
        clearSessions(context)
        preferences.save(AppPreferences())
        var failure: Throwable? = null
        try {
            val backend = BackendAuthState()
            ProfilesFixture("A", backend).use { first -> ProfilesFixture("B", backend).use { second ->
                Environment(first, second, backend).use { env ->
                    env.restart()
                    block(env)
                    first.assertHealthy(); second.assertHealthy()
                }
            } }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { clearSessions(context); preferences.save(previous) }
            catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    private fun awaitState(env: Environment, description: String, allowFailure: Boolean = false, timeoutMs: Long = WAIT_MS,
        predicate: (AppState) -> Boolean): AppState = try {
        runBlocking { withTimeout(timeoutMs) { env.app.state.first { state ->
            env.first.assertHealthy(); env.second.assertHealthy()
            if (!allowFailure && !state.loginBusy) {
                state.loginError?.let { throw AssertionError("$description；登录失败：$it") }
                state.home.error?.let { throw AssertionError("$description；首页失败：$it") }
            }
            predicate(state)
        } } }
    } catch (timeout: TimeoutCancellationException) {
        val state = env.app.state.value
        env.first.assertHealthy(); env.second.assertHealthy()
        throw AssertionError("$description；profiles=${state.profiles.size}, busy=${state.loginBusy}, " +
            "hasSession=${state.session != null}, reauth=${state.reauthenticationProfile != null}, loading=${state.home.loading}", timeout)
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == VERIFICATION_PACKAGE) { "多服务器流程仅允许 verification 包" }
    }

    private fun clearSessions(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }

    /** token 有效性完全由后端 userId + DeviceId 控制，完全不知道监听端口。 */
    internal class BackendAuthState {
        data class Principal(val userId: String, val userName: String, val deviceId: String)
        data class Authentication(val principal: Principal, val token: String)
        val successfulAuthentications = AtomicInteger()
        private val tokens = mutableMapOf<String, Principal>()
        @Synchronized
        fun authenticate(username: String, password: String, deviceId: String): Authentication? {
            val userId = when (username) { USERNAME -> USER_ID; OTHER_USERNAME -> OTHER_USER_ID; else -> return null }
            if (password != PASSWORD) return null
            check(deviceId.isNotBlank()) { "认证缺少真实 DeviceId" }
            val principal = Principal(userId, username, deviceId)
            tokens.entries.removeAll { it.value.userId == userId && it.value.deviceId == deviceId }
            val token = "synthetic-shared-token-${successfulAuthentications.incrementAndGet()}"
            tokens[token] = principal
            return Authentication(principal, token)
        }
        @Synchronized
        fun principal(token: String?, deviceId: String?): Principal? = token?.let { tokens[it] }?.takeIf { it.deviceId == deviceId }
        @Synchronized
        fun revoke(token: String) { check(tokens.remove(token) != null) { "只能撤销夹具真实签发的 token" } }
    }

    internal class RequestGate(private val maxWaitMs: Long = WAIT_MS) : Closeable {
        private val arrived = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val responseStatus = AtomicInteger()
        val requestArrived get() = arrived.count == 0L
        fun block() {
            arrived.countDown()
            check(proceed.await(maxWaitMs, TimeUnit.MILLISECONDS)) { "合成请求等待释放超时" }
        }
        fun awaitRequest() { assertTrue("合成请求没有到达控制点", arrived.await(WAIT_MS, TimeUnit.MILLISECONDS)) }
        fun assertStillBlocked() {
            assertEquals("合成请求必须已经到达", 0L, arrived.count)
            assertEquals("缓存断言不能提前释放网络", 1L, proceed.count)
        }
        fun release() = proceed.countDown()
        fun finish(status: Int) { responseStatus.set(status); finished.countDown() }
        fun awaitResponseAttempt(expectedStatus: Int) {
            assertTrue("迟到响应必须实际尝试完成", finished.await(WAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals("门闩释放后应生成预期的真实 HTTP 结果", expectedStatus, responseStatus.get())
        }
        override fun close() = release()
    }

    internal class ProfilesFixture(val label: String, private val backend: BackendAuthState,
        private val libraryCount: Int = 1, private val itemsPerLibrary: Int = 1,
        private val includeUserImage: Boolean = true, private val administrator: Boolean = true) : Closeable {
        val authentications = AtomicInteger()
        val validations = AtomicInteger()
        val publicProbes = AtomicInteger()
        val authenticatedRequests = AtomicInteger()
        val publicServerId = AtomicReference(SERVER_ID)
        val validationUserId = AtomicReference<String?>(null)
        val validationImageTag = AtomicReference<String?>(null)
        val authenticationUserId = AtomicReference<String?>(null)
        val authenticationServerId = AtomicReference<String?>(null)
        val homeVersion = AtomicInteger(1)
        val homeRequests = AtomicInteger()
        val homeResponses = AtomicInteger()
        val homeDeviceIds = ConcurrentLinkedQueue<String>()
        val browserRequests = AtomicInteger()
        val browserDeviceIds = ConcurrentLinkedQueue<String>()
        private val nextAuthenticationGate = AtomicReference<RequestGate?>()
        private val nextValidationGate = AtomicReference<RequestGate?>()
        private val homeGate = AtomicReference<RequestGate?>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-profile-response-$label").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val config = ServerConfig(baseUrl, allowInsecureHttp = true)
        val preferencesAccountKey = "$SERVER_ID/$USER_ID"
        private val acceptThread = Thread({
            while (!closed.get()) try {
                val socket = server.accept()
                sockets.add(socket)
                executor.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures.add(error) }
        }, "pathfin-profile-accept-$label").apply { isDaemon = true; start() }
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("合成入口 $label HTTP 契约失败", it) } }
        fun blockNextAuthentication() = RequestGate().also { check(nextAuthenticationGate.compareAndSet(null, it)) }
        fun blockNextValidation() = RequestGate().also { check(nextValidationGate.compareAndSet(null, it)) }
        fun blockHomeResponses() = RequestGate(WAIT_MS * 3).also { check(homeGate.compareAndSet(null, it)) }
        fun libraryId(index: Int) = if (index == 0) LIBRARY_ID else "$LIBRARY_ID-$index"
        fun libraryName(index: Int, version: Int = homeVersion.get()) = "$label library $index v$version"
        fun movieId(library: Int, index: Int) = "${libraryId(library)}-movie-$index"
        fun movieName(library: Int, index: Int, version: Int = homeVersion.get()) = "$label movie $library-$index v$version"
        private data class Reply(val status: Int, val json: Any, val gate: RequestGate? = null)

        private fun serve(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine() ?: return
                    val request = requestLine.split(' ')
                    check(request.size == 3) { "HTTP 请求行无效" }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = input.readLine() ?: return
                        if (header.isEmpty()) break
                        val separator = header.indexOf(':')
                        check(separator > 0) { "HTTP 请求头无效" }
                        headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..32_768)
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        if (count < 0) throw EOFException("请求体未完成")
                        offset += count
                    }
                    val uri = URI(request[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                    }
                    val reply = respond(request[0], uri.path, query, headers, String(body))
                    try {
                        val bytes = reply.json.toString().toByteArray(Charsets.UTF_8)
                        val reason = if (reply.status == 200) "OK" else "Unauthorized"
                        connection.getOutputStream().apply {
                            write(("HTTP/1.1 ${reply.status} $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(bytes); flush()
                        }
                    } finally { reply.gate?.finish(reply.status) }
                }
            } catch (_: IOException) {
                // VM 关闭/切换取消 HTTP 时允许正常 EOF 与断开的响应连接。
            } catch (error: Throwable) { if (!closed.get()) failures.add(error) }
            finally { sockets.remove(socket) }
        }

        private fun respond(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: String): Reply {
            val authorization = headers["authorization"].orEmpty()
            if (path == "/Users/AuthenticateByName") {
                check(method == "POST")
                authentications.incrementAndGet()
                nextAuthenticationGate.getAndSet(null)?.block()
                check(headerParameter(authorization, "Token") == null) { "密码认证不能夹带旧 token" }
                val credentials = JSONObject(body)
                val authenticated = backend.authenticate(credentials.getString("Username"), credentials.getString("Pw"),
                    requireNotNull(headerParameter(authorization, "DeviceId"))) ?: return unauthorized()
                return Reply(200, JSONObject().put("AccessToken", authenticated.token)
                    .put("ServerId", authenticationServerId.get() ?: SERVER_ID)
                    .put("User", user(authenticated.principal, authenticationUserId.get())))
            }
            check(method == "GET")
            if (path == "/System/Info/Public") {
                publicProbes.incrementAndGet()
                check(!headers.containsKey("authorization") && !headers.containsKey("x-emby-token")) { "公共身份探测不能发送凭据" }
                return Reply(200, JSONObject().put("Id", publicServerId.get()).put("ServerName", "合成共享后端").put("Version", "12.3.0"))
            }
            authenticatedRequests.incrementAndGet()
            val validationGate = if (path == "/Users/Me") {
                validations.incrementAndGet()
                nextValidationGate.getAndSet(null).also { it?.block() }
            } else null
            val principal = backend.principal(headerParameter(authorization, "Token"), headerParameter(authorization, "DeviceId"))
                ?: return unauthorized().copy(gate = validationGate)
            val version = homeVersion.get()
            val isHome = path == "/UserViews" || path == "/UserItems/Resume" || path == "/Items/Latest"
            if (isHome) { homeRequests.incrementAndGet(); homeDeviceIds.add(principal.deviceId) }
            val reply = Reply(200, when (path) {
                "/Users/Me" -> user(principal, validationUserId.get()).apply {
                    validationImageTag.get()?.let { put("PrimaryImageTag", it) }
                }
                "/UserViews" -> {
                    check(query["userId"] == principal.userId)
                    page(*(0 until libraryCount).map { index -> JSONObject().put("Id", libraryId(index))
                        .put("Name", libraryName(index, version)).put("Type", "CollectionFolder")
                        .put("IsFolder", true).put("CollectionType", "movies") }.toTypedArray())
                }
                "/UserItems/Resume" -> {
                    check(query["userId"] == principal.userId)
                    if (itemsPerLibrary == 1) page() else page(JSONObject().put("Id", "synthetic-resume")
                        .put("Name", "$label resume v$version").put("Type", "Movie").put("RunTimeTicks", 3_600_000_000L)
                        .put("UserData", JSONObject().put("PlaybackPositionTicks", (label.first().code + version) * 10_000_000L)))
                }
                "/Items/Latest" -> {
                    check(query["userId"] == principal.userId)
                    val library = requireNotNull((0 until libraryCount).firstOrNull { libraryId(it) == query["parentId"] })
                    JSONArray((0 until itemsPerLibrary).map { index -> JSONObject().put("Id", movieId(library, index))
                        .put("Name", movieName(library, index, version)).put("Type", "Movie").put("ProductionYear", 2026) })
                }
                "/Items" -> {
                    check(query["userId"] == principal.userId)
                    val library = requireNotNull((0 until libraryCount).firstOrNull { libraryId(it) == query["parentId"] })
                    val start = query.getValue("startIndex").toInt()
                    val limit = query.getValue("limit").toInt()
                    browserRequests.incrementAndGet(); browserDeviceIds.add(principal.deviceId)
                    page(*(0 until itemsPerLibrary).drop(start).take(limit).map { index ->
                        JSONObject().put("Id", movieId(library, index)).put("Name", movieName(library, index, version))
                            .put("Type", "Movie").put("ProductionYear", 2026)
                    }.toTypedArray()).put("TotalRecordCount", itemsPerLibrary)
                }
                "/Items/Filters" -> {
                    check(query["userId"] == principal.userId)
                    check((0 until libraryCount).any { libraryId(it) == query["parentId"] })
                    JSONObject()
                }
                "/Shows/NextUp" -> {
                    check(query["userId"] == principal.userId)
                    page()
                }
                else -> error("夹具收到范围外的接口")
            }, gate = validationGate)
            if (isHome) {
                // 先生成该请求的响应快照，再阻塞；切账号后释放也必须保持它原来的内容。
                homeGate.get()?.block()
                homeResponses.incrementAndGet()
            }
            return reply
        }

        private fun headerParameter(header: String, name: String): String? =
            Regex("(?:^|[ ,])${Regex.escape(name)}=\"([^\"]*)\"").find(header)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
        private fun user(principal: BackendAuthState.Principal, overrideUserId: String? = null) =
            JSONObject().put("Id", overrideUserId ?: principal.userId).put("Name", principal.userName)
                .apply { if (includeUserImage) put("PrimaryImageTag", "synthetic-avatar") }.put("Policy", JSONObject()
                    .put("IsAdministrator", administrator).put("EnableContentDownloading", true).put("EnableContentDeletion", administrator))
        private fun unauthorized() = Reply(401, JSONObject().put("error", "synthetic-expired-or-rejected"))
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size)
        override fun close() {
            closed.set(true)
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            acceptThread.join(2_000)
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val SERVER_ID = "synthetic-profiles-same-server"
        const val USER_ID = "synthetic-profiles-same-user"
        const val OTHER_USER_ID = "synthetic-profiles-other-user"
        const val USERNAME = "synthetic-profiles-user"
        const val OTHER_USERNAME = "synthetic-profiles-other-user"
        const val PASSWORD = "synthetic-profiles-password"
        const val LEGACY_DEVICE_ID = "synthetic-shared-legacy-device"
        const val LIBRARY_ID = "synthetic-shared-library"
        const val WAIT_MS = 20_000L
        const val RESWITCH_WAIT_MS = 3_000L
    }
}

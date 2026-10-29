package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.PublicServerInfo
import dev.pathfin.android.domain.PublicUser
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.ServerManagementScreen
import dev.pathfin.android.ui.ServerProbe
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实连接链路：必需服务器探测完成即可登录，可选账号列表不阻塞、不复活已离开的步骤。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ServerProbeFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun slowPublicUsersDoNotBlockManualLoginOrRestoreTheAccountStepAfterLogin() = withApp { app, fixture ->
        startProbe(app, fixture)
        val ready = app.state.value
        assertNotNull("服务器信息成功后应立即发布账号步骤", ready.serverProbe)
        assertFalse("可选公开账号仍在途时不能阻塞手动登录", ready.loginBusy)
        assertEquals(SERVER_NAME, ready.serverProbe?.info?.name)
        assertTrue(ready.serverProbe?.users.orEmpty().isEmpty())
        val oldJobs = activeJobs(app)

        fixture.users.expectCancellation.set(true)
        main { app.login(fixture.origin, USER_NAME, PASSWORD, allowHttp = true, displayName = SERVER_NAME) }
        await(app, fixture) { it.session?.accessToken == TOKEN && !it.loginBusy && it.home.loaded && !it.home.loading }
        assertEquals("可选用户请求未完成也必须已发出真实认证请求", 1, fixture.loginRequests.get())

        fixture.users.release()
        fixture.users.awaitResponseAttempt()
        join(oldJobs)
        main {
            assertEquals(TOKEN, app.state.value.session?.accessToken)
            assertNull("迟到公开账号不能把已登录的页面变回账号步骤", app.state.value.serverProbe)
            assertFalse(app.state.value.addingServer)
            assertFalse(app.state.value.loginBusy)
            assertNull(app.state.value.loginError)
        }
    }

    @Test fun leavingAccountStepCancelsOptionalUsersAndTheirLateResponseCannotReviveIt() = withApp { app, fixture ->
        startProbe(app, fixture)
        assertNotNull("公开账号返回前已允许从账号步骤后退", app.state.value.serverProbe)
        assertFalse(app.state.value.loginBusy)
        val oldJobs = activeJobs(app)
        fixture.users.expectCancellation.set(true)

        main { app.backFromConnect() }
        assertNull(app.state.value.serverProbe)
        assertTrue("账号步骤后退应先回到地址步骤", app.state.value.addingServer)
        fixture.users.release()
        fixture.users.awaitResponseAttempt()
        join(oldJobs)
        main {
            assertNull("迟到公开账号不得恢复旧服务器步骤", app.state.value.serverProbe)
            assertNull(app.state.value.loginError)
            assertFalse(app.state.value.loginBusy)
            app.backFromConnect()
            assertFalse(app.state.value.addingServer)
        }
    }

    @Test fun cancellingRequiredProbeExitsImmediatelyAndSuppressesItsLateFailure() = withApp(blockInfo = true, infoStatus = 503) { app, fixture ->
        main { app.beginAddServer(); app.probeServer(fixture.origin, allowHttp = true) }
        fixture.info.awaitRequest()
        assertTrue(app.state.value.loginBusy)
        assertNull(app.state.value.serverProbe)
        val oldJobs = activeJobs(app)
        fixture.info.expectCancellation.set(true)

        main { app.backFromConnect() }
        assertFalse("必需探测在途时也必须允许退出添加", app.state.value.addingServer)
        assertFalse("取消后不能遗留连接 busy", app.state.value.loginBusy)
        fixture.info.release()
        fixture.info.awaitResponseAttempt()
        join(oldJobs)
        main {
            assertNull(app.state.value.serverProbe)
            assertNull("已取消服务器的迟到 503 不得写入当前页", app.state.value.loginError)
            assertFalse(app.state.value.addingServer)
            assertFalse(app.state.value.loginBusy)
            assertEquals(0, fixture.publicUsersRequests.get())
        }
    }

    @Test fun publicUsersArrivingInAccountScreenPreserveTheManualFormAndPassword() {
        Fixture().use { fixture ->
            var shown by mutableStateOf(true)
            var probe by mutableStateOf(ServerProbe(ServerConfig(fixture.origin, true), PublicServerInfo(SERVER_ID, SERVER_NAME, "10.11.0"), emptyList()))
            var submitted: List<String>? = null
            val loader = ImageLoader.Builder(compose.activity).diskCache(null).build()
            compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
            try {
                compose.setContent {
                    if (shown) PathfinTheme(ThemePreference.LIGHT) {
                        CompositionLocalProvider(LocalPosterLoader provides loader) {
                            ServerManagementScreen(emptyList(), null, false, null, probe, adding = true, showcase = null,
                                onProbe = {}, onAddServer = {}, onConnectBack = {}, onOpen = {},
                                onAdd = { _, username, password, displayName -> submitted = listOf(username, password, displayName) },
                                onUpdate = {}, onDelete = {}, onAddAddress = { _, _ -> }, onPassword = { _, _, _ -> },
                                onDismissError = {})
                        }
                    }
                }
                setText("login-username", USER_NAME)
                setText("login-password", PASSWORD)
                setText("server-display-name", "My cinema")
                val usernameNode = compose.onNodeWithTag("login-username").fetchSemanticsNode().id
                val passwordNode = compose.onNodeWithTag("login-password").fetchSemanticsNode().id

                compose.runOnIdle { probe = probe.copy(users = listOf(PublicUser("public-user", "Public account"))) }
                compose.waitForIdle()
                compose.onAllNodesWithTag("login-username").assertCountEquals(1)
                compose.onNodeWithTag("login-username").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(USER_NAME)))
                assertEquals("可选用户到达不能重建正在输入的表单", usernameNode, compose.onNodeWithTag("login-username").fetchSemanticsNode().id)
                assertEquals(passwordNode, compose.onNodeWithTag("login-password").fetchSemanticsNode().id)
                compose.onNodeWithTag("login-submit").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                compose.runOnIdle { assertEquals(listOf(USER_NAME, PASSWORD, "My cinema"), submitted) }
                fixture.assertHealthy()
            } finally {
                compose.runOnIdle { shown = false }
                compose.waitForIdle()
                loader.shutdown()
            }
        }
    }

    private fun startProbe(app: AppViewModel, fixture: Fixture) {
        main { app.beginAddServer(); app.probeServer(fixture.origin, allowHttp = true) }
        fixture.users.awaitRequest()
        // Users/Public 已发出证明必需探测已成功，不依赖固定延时等待网络。
        instrumentation.waitForIdleSync()
        fixture.assertHealthy()
    }

    private fun withApp(blockInfo: Boolean = false, infoStatus: Int = 200, block: (AppViewModel, Fixture) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName == "dev.pathfin.android.verification") { "连接回归只允许独立 verification 包" }
        val preferences = AppPreferencesStore(context)
        val originalPreferences = preferences.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        clearSessions(context)
        preferences.save(AppPreferences(openLastServer = false))
        try {
            Fixture(blockInfo, infoStatus).use { fixture ->
                try {
                    val app = main {
                        ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java]
                    }
                    await(app, fixture) { !it.restoringSession }
                    block(app, fixture)
                    fixture.assertHealthy()
                } finally {
                    fixture.info.expectCancellation.set(true)
                    fixture.users.expectCancellation.set(true)
                    main { owner.viewModelStore.clear() }
                    fixture.releaseAll()
                }
            }
        } finally {
            main { owner.viewModelStore.clear() }
            clearSessions(context)
            preferences.save(originalPreferences)
        }
    }

    private fun setText(tag: String, value: String) = compose.onNodeWithTag(tag)
        .performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(value))) }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(app: AppViewModel, fixture: Fixture, predicate: (AppState) -> Boolean): AppState = runBlocking {
        withTimeout(WAIT) { app.state.first { fixture.assertHealthy(); predicate(it) } }
    }

    private fun activeJobs(app: AppViewModel): List<Job> = main { app.viewModelScope.coroutineContext[Job]!!.children.filter { it.isActive }.toList() }
    private fun join(jobs: List<Job>) = runBlocking { withTimeout(WAIT) { jobs.forEach { it.join() } } }

    private class Gate(blocked: Boolean = true) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(if (blocked) 1 else 0)
        private val finished = CountDownLatch(1)
        val expectCancellation = AtomicBoolean()
        fun block() { entered.countDown(); check(released.await(WAIT, TimeUnit.MILLISECONDS)) { "合成响应未释放" } }
        fun release() = released.countDown()
        fun finish() = finished.countDown()
        fun awaitRequest() = assertTrue("请求必须已到达回环服务", entered.await(WAIT, TimeUnit.MILLISECONDS))
        fun awaitResponseAttempt() = assertTrue("迟到响应必须实际尝试完成", finished.await(WAIT, TimeUnit.MILLISECONDS))
    }

    private class Fixture(blockInfo: Boolean = false, private val infoStatus: Int = 200) : Closeable {
        val info = Gate(blockInfo)
        val users = Gate()
        val publicUsersRequests = AtomicInteger()
        val loginRequests = AtomicInteger()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val executor = Executors.newCachedThreadPool { action -> Thread(action, "pathfin-probe-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val origin = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                executor.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures += error }
        }, "pathfin-probe-accept").apply { isDaemon = true; start() }

        fun assertHealthy() { failures.peek()?.let { throw AssertionError("服务器探测 fixture 失败", it) } }
        fun releaseAll() { info.release(); users.release() }

        private fun serve(socket: Socket) {
            var gate: Gate? = null
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT.toInt()
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val request = reader.readLine()?.split(' ') ?: return
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: throw EOFException("合成请求头未完成")
                        if (line.isEmpty()) break
                        check(':' in line)
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val body = CharArray((headers["content-length"]?.toIntOrNull() ?: 0).also { check(it in 0..65_536) })
                    var read = 0
                    while (read < body.size) {
                        val size = reader.read(body, read, body.size - read)
                        if (size < 0) throw EOFException("合成请求体未完成")
                        read += size
                    }
                    val path = URI(request[1]).path
                    gate = when (path) {
                        "/System/Info/Public" -> info
                        "/Users/Public" -> users.also { publicUsersRequests.incrementAndGet() }
                        else -> null
                    }
                    gate?.block()
                    val response = when (path) {
                        "/System/Info/Public" -> JSONObject().put("Id", SERVER_ID).put("ServerName", SERVER_NAME).put("Version", "10.11.0")
                        "/Users/Public" -> JSONArray().put(JSONObject().put("Id", "public-user").put("Name", "Public account"))
                        "/Users/AuthenticateByName" -> {
                            check(request[0] == "POST")
                            val login = JSONObject(String(body))
                            check(login.getString("Username") == USER_NAME && login.getString("Pw") == PASSWORD)
                            loginRequests.incrementAndGet()
                            JSONObject().put("AccessToken", TOKEN).put("ServerId", SERVER_ID)
                                .put("User", JSONObject().put("Id", "probe-user").put("Name", USER_NAME))
                        }
                        "/UserViews", "/UserItems/Resume", "/Shows/NextUp" -> JSONObject().put("Items", JSONArray()).put("TotalRecordCount", 0)
                        "/Items/Latest" -> JSONArray()
                        "/Branding/Splashscreen" -> JSONObject()
                        else -> error("未定义探测测试接口：${request[0]} $path")
                    }
                    val status = when (path) { "/System/Info/Public" -> infoStatus; "/Branding/Splashscreen" -> 404; else -> 200 }
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get() && !(error is IOException && gate?.expectCancellation?.get() == true)) failures += error
            } finally {
                gate?.finish()
                sockets.remove(socket)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseAll(); server.close()
            sockets.toList().forEach { runCatching { it.close() } }
            executor.shutdownNow()
            accept.join(1000)
            assertHealthy()
        }
    }

    private companion object {
        const val WAIT = 20_000L
        const val SERVER_ID = "probe-server"
        const val SERVER_NAME = "Probe fixture"
        const val USER_NAME = "manual-user"
        const val PASSWORD = "manual-password"
        const val TOKEN = "probe-token"
        fun clearSessions(context: Context) {
            check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
        }
    }
}

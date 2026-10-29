package dev.pathfin.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.ErrnoException
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import dev.pathfin.android.createVerificationComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.casting.CastDevice
import dev.pathfin.android.casting.CastState
import dev.pathfin.android.casting.CastStatus
import dev.pathfin.android.casting.DlnaCasting
import dev.pathfin.android.casting.UpnpXml
import dev.pathfin.android.casting.localIpv4
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.CastSheetContent
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 只访问本测试在本机创建的 HTTP/SOAP renderer；不发现或控制真实电视。 */
@RunWith(AndroidJUnit4::class)
class CastingFlowTest {
    private val compose = createVerificationComposeRule<ComponentActivity>()
    private val verificationOnly = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification") {
                "DLNA 回归只允许 verification 独立包"
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(verificationOnly).around(compose)

    @Test fun permissionAndEmptyDiscoveryCanActuallyPullWithoutNetwork() {
        val permission = mutableStateOf(false)
        val state = mutableStateOf(CastState())
        val permissions = AtomicInteger(); val discoveries = AtomicInteger()
        compose.setContent { PathfinTheme(ThemePreference.LIGHT) {
            CastSheetContent(state.value, "profile", permission.value, false, null, { discoveries.incrementAndGet() },
                { permissions.incrementAndGet() }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("DLNA 设备").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("cast-permission").performVerificationClick(compose)
        assertEquals(1, permissions.get()); assertEquals(0, discoveries.get())
        compose.runOnIdle { permission.value = true }
        compose.onNodeWithText("未发现 DLNA 设备").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("cast-retry").assertDoesNotExist()
        pullPageToRefresh(compose, stage = "cast-empty-discovery-pull")
        assertEquals(1, discoveries.get())
        val bounds = compose.onNodeWithTag("cast-content").readVerificationNode(compose) { it.boundsInRoot }
        compose.runOnIdle { state.value = state.value.copy(discovering = true) }
        compose.onNodeWithTag("cast-discovering").assertVerificationDisplayed(compose)
        assertEquals("加载前后底部页尺寸稳定", bounds, compose.onNodeWithTag("cast-content").readVerificationNode(compose) { it.boundsInRoot })
    }

    @Test fun controlsUseActualCallbacksBusyCannotPauseAgainAndOtherAccountIsIsolated() {
        val device = CastDevice("synthetic", "Synthetic TV", "192.168.88.2", "http://192.168.88.2/device",
            "http://192.168.88.2/control", "urn:schemas-upnp-org:service:AVTransport:1", "192.168.88.3")
        val state = mutableStateOf(CastState(device = device, devices = listOf(device), profileId = "profile", itemId = "item",
            title = "Synthetic video", status = CastStatus.PLAYING, durationTicks = 600_000_000))
        val plays = AtomicInteger(); val pauses = AtomicInteger(); val stops = AtomicInteger()
        compose.setContent { PathfinTheme(ThemePreference.LIGHT) {
            CastSheetContent(state.value, "profile", true, false, null, {}, {}, {}, { plays.incrementAndGet() },
                { pauses.incrementAndGet() }, {}, { stops.incrementAndGet() })
        } }
        scroll("cast-play-pause")
        val toggle = compose.onNodeWithTag("cast-play-pause")
        VerificationDisplay.awaitWindowReady(compose, "cast-controls-pause", toggle)
        toggle.assertVerificationDisplayed(compose).assertIsEnabled().performVerificationClick(compose)
        assertEquals(1, pauses.get())
        compose.runOnIdle { state.value = state.value.copy(status = CastStatus.PAUSED) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("cast-play-pause") and hasText("继续播放")).fetchSemanticsNodes().isNotEmpty()
        }
        VerificationDisplay.awaitWindowReady(compose, "cast-controls-resume", toggle)
        toggle.assertVerificationDisplayed(compose).assertIsEnabled().performVerificationClick(compose)
        assertEquals(1, plays.get())
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("cast-play-pause").assertIsNotEnabled()
        compose.onNodeWithTag("cast-stop").assertIsEnabled().performVerificationClick(compose); assertEquals(1, stops.get())
        compose.runOnIdle { state.value = state.value.copy(profileId = "another-profile") }
        compose.onNodeWithTag("cast-controls").assertDoesNotExist()
        compose.onNodeWithText("Synthetic video").assertDoesNotExist()
    }

    @Test fun actualForegroundCastingReadsRelayThenPausesSeeksResumesAndStops() = withOwnRenderer { context, profileId, fixture ->
        start(context, profileId, fixture)
        assertTrue("必须实际取到媒体后才进入播放状态", fixture.relayBytes.get() > 0)
        assertEquals(1, fixture.playReports.get())
        assertEquals("PLAYING", fixture.transport.get())
        compose.runOnIdle { DlnaCasting.pause(context) }
        await(fixture) { DlnaCasting.state.value.status == CastStatus.PAUSED && !DlnaCasting.state.value.busy }
        assertEquals(1, fixture.pauses.get())
        compose.runOnIdle { DlnaCasting.seek(context, 30L * 10_000_000) }
        await(fixture) { !DlnaCasting.state.value.busy && DlnaCasting.state.value.positionTicks == 30L * 10_000_000 }
        assertEquals("00:00:30", fixture.position.get())
        compose.runOnIdle { DlnaCasting.play(context) }
        await(fixture) { DlnaCasting.state.value.status == CastStatus.PLAYING && !DlnaCasting.state.value.busy }
        assertEquals(2, fixture.plays.get())
        val lease = requireNotNull(fixture.relay.get())
        compose.runOnIdle { DlnaCasting.stop(context) }
        await(fixture) { !DlnaCasting.state.value.active && !DlnaCasting.state.value.busy && fixture.stops.get() == 1 }
        assertEquals("STOPPED", fixture.transport.get())
        assertEquals(1, fixture.stopReports.get())
        assertThrows(IOException::class.java) { fixture.fetch(lease) }
        assertTrue("控制 SOAP 和 relay 不应暴露 Jellyfin token", fixture.credentialsSafe.get())
    }

    @Test fun removingOwnSyntheticProfileRevokesLeaseAndStopsDeviceWithoutOldApiCalls() = withOwnRenderer { context, profileId, fixture ->
        start(context, profileId, fixture)
        val lease = requireNotNull(fixture.relay.get())
        runBlocking(Dispatchers.IO) { SessionStore(context).removeProfile(profileId); fixture.accountRemoved.set(true); DlnaCasting.cancelProfile(context, profileId) }
        await(fixture) { !DlnaCasting.state.value.active && !DlnaCasting.state.value.busy && fixture.stops.get() == 1 }
        assertThrows(IOException::class.java) { fixture.fetch(lease) }
        assertEquals(0, fixture.apiAfterRemoval.get())
        assertEquals(0, fixture.stopReports.get())
    }

    private fun start(context: Context, profileId: String, fixture: Renderer) {
        VerificationDisplay.awaitWindowReady(compose, "cast-before-foreground-start",
            compose.onNodeWithText("DLNA isolated renderer"))
        compose.runOnIdle { DlnaCasting.start(context, profileId, "castitem", "synthetic-cast", "source", 0) }
        try {
            await(fixture, phase = "cast-start", failOnError = true) { DlnaCasting.state.value.profileId == profileId && DlnaCasting.state.value.status == CastStatus.PLAYING && !DlnaCasting.state.value.busy && fixture.relayBytes.get() > 0 }
        } catch (failure: AssertionError) {
            val probes = runBlocking(Dispatchers.IO) { fixture.probeTransport() }
            throw AssertionError("${fixture.diagnostic("cast-start", DlnaCasting.state.value)} $probes", failure)
        }
        val lease = requireNotNull(fixture.relay.get())
        assertFalse(lease.contains("fixture-jf-token")); assertFalse(lease.contains("source.mp4"))
    }

    private fun withOwnRenderer(block: (Context, String, Renderer) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("真实前台投屏回归需已允许 LAN；不由测试授予系统权限", LocalNetworkPermission.isGranted(context))
        assumeTrue("不打断其它验证会话", !DlnaCasting.state.value.active)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val candidate = connectivity.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstNotNullOfOrNull null
            connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { localIpv4(it.hostAddress.orEmpty()) != null }?.let { network to requireNotNull(it.hostAddress) }
        }
        assumeTrue("实际 socket 回归需要本机 Wi-Fi/Ethernet 私网 IPv4", candidate != null)
        val (network, address) = requireNotNull(candidate)
        val profileId = "pathfin-dlna-fixture-${UUID.randomUUID()}"
        Renderer(address, network).use { fixture ->
            val profile = ServerProfile(profileId, Session(ServerConfig(fixture.origin, true), "fixture-jf-token", "fixture-user", "DLNA fixture", "fixture-device"), "DLNA fixture")
            runBlocking(Dispatchers.IO) { SessionStore(context).upsertProfile(profile) }
            compose.setContent { PathfinTheme(ThemePreference.LIGHT) { androidx.compose.material3.Text("DLNA isolated renderer") } }
            compose.runOnIdle { DlnaCasting.update { CastState(devices = listOf(CastDevice("synthetic-cast", "Synthetic renderer", address,
                "${fixture.origin}/device.xml", "${fixture.origin}/control", "urn:schemas-upnp-org:service:AVTransport:1", address, network))) } }
            try { block(context, profileId, fixture); fixture.assertHealthy() }
            finally {
                runBlocking { DlnaCasting.cancelProfile(context, profileId); withContext(Dispatchers.IO) { SessionStore(context).removeProfile(profileId) } }
                compose.runOnIdle { DlnaCasting.update { CastState() } }
            }
        }
    }

    private fun scroll(tag: String) { compose.onNodeWithTag("cast-content").performScrollToNode(hasTestTag(tag)) }
    private fun await(fixture: Renderer, phase: String = "cast-control", failOnError: Boolean = false, condition: () -> Boolean) {
        try {
            compose.waitUntil(25_000) {
                fixture.assertHealthy()
                val state = DlnaCasting.state.value
                if (failOnError && state.status == CastStatus.ERROR && !state.busy) {
                    throw AssertionError(fixture.diagnostic(phase, state))
                }
                condition()
            }
        } catch (failure: Exception) {
            throw AssertionError(fixture.diagnostic(phase, DlnaCasting.state.value), failure)
        }
        fixture.assertHealthy()
    }

    private class Renderer(address: String, private val network: Network) : Closeable {
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName(address), 0), 12) }
        val origin = "http://$address:${server.localPort}"
        private val executor = Executors.newCachedThreadPool()
        private val closed = AtomicBoolean()
        private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
        private val failure = AtomicReference<Throwable?>()
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val relay = AtomicReference<String?>()
        val relayBytes = AtomicInteger()
        val transport = AtomicReference("STOPPED")
        val position = AtomicReference("00:00:00")
        val plays = AtomicInteger(); val pauses = AtomicInteger(); val stops = AtomicInteger()
        val itemRequests = AtomicInteger(); val playbackInfoRequests = AtomicInteger()
        val setUriRequests = AtomicInteger(); val sourceRequests = AtomicInteger()
        val playReports = AtomicInteger(); val stopReports = AtomicInteger()
        val credentialsSafe = AtomicBoolean(true)
        val accountRemoved = AtomicBoolean(); val apiAfterRemoval = AtomicInteger()
        init {
            executor.execute {
                while (!closed.get()) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets += socket
                    executor.execute { try { socket.use(::serve) } catch (error: Throwable) {
                        if (!closed.get()) failure.compareAndSet(null, error)
                    } finally { sockets -= socket } }
                }
            }
        }
        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream())
            val request = requireNotNull(input.readLine()).split(' ')
            val headers = linkedMapOf<String, String>()
            while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
            val size = headers["content-length"]?.toIntOrNull() ?: 0
            check(size in 0..(256 * 1024))
            val body = ByteArray(size); input.readFully(body)
            val path = request[1].substringBefore('?')
            when (path) {
                "/Items", "/Items/castitem/PlaybackInfo", "/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped" -> {
                    if (accountRemoved.get()) apiAfterRemoval.incrementAndGet()
                    check(headers["authorization"].orEmpty().contains("fixture-jf-token"))
                    val response = when (path) {
                        "/Items" -> { itemRequests.incrementAndGet(); JSONObject().put("Items", JSONArray().put(JSONObject().put("Id", "castitem").put("Name", "Synthetic DLNA video").put("Type", "Movie").put("RunTimeTicks", 600L * 10_000_000))).toString() }
                        "/Items/castitem/PlaybackInfo" -> { playbackInfoRequests.incrementAndGet(); JSONObject().put("PlaySessionId", "fixture-play").put("MediaSources", JSONArray().put(JSONObject()
                            .put("Id", "source").put("Path", "$origin/source.mp4").put("Protocol", "Http").put("Container", "mp4")
                            .put("RunTimeTicks", 600L * 10_000_000).put("SupportsDirectPlay", true)
                            .put("RequiredHttpHeaders", JSONObject().put("Authorization", "Bearer fixture-source")))).toString() }
                        "/Sessions/Playing" -> { playReports.incrementAndGet(); "" }
                        "/Sessions/Playing/Stopped" -> { stopReports.incrementAndGet(); "" }
                        else -> ""
                    }
                    respond(socket, if (path.startsWith("/Sessions")) 204 else 200, response.encodeToByteArray(), "application/json")
                }
                "/control" -> {
                    if (headers.values.any { it.contains("fixture-jf-token") || it.contains("MediaBrowser") }) credentialsSafe.set(false)
                    val action = headers["soapaction"].orEmpty().substringAfter('#').trim('"')
                    val xml = UpnpXml.parse(body.decodeToString())
                    when (action) {
                        "SetAVTransportURI" -> {
                            setUriRequests.incrementAndGet()
                            val value = UpnpXml.text(xml, "CurrentURI")
                            check(value.startsWith("http://${server.inetAddress.hostAddress}:"))
                            if (body.decodeToString().contains("fixture-jf-token") || body.decodeToString().contains("Bearer fixture-source")) credentialsSafe.set(false)
                            relay.set(value)
                        }
                        "Play" -> { plays.incrementAndGet(); transport.set("PLAYING"); if (relayBytes.get() == 0) executor.execute {
                            try { relayBytes.addAndGet(fetch(requireNotNull(relay.get())).size) } catch (error: Throwable) { failure.compareAndSet(null, error) }
                        } }
                        "Pause" -> { pauses.incrementAndGet(); transport.set("PAUSED_PLAYBACK") }
                        "Stop" -> { stops.incrementAndGet(); transport.set("STOPPED") }
                        "Seek" -> position.set(UpnpXml.text(xml, "Target"))
                    }
                    val fields = when (action) {
                        "GetTransportInfo" -> "<CurrentTransportState>${transport.get()}</CurrentTransportState>"
                        "GetPositionInfo" -> "<TrackDuration>00:10:00</TrackDuration><RelTime>${position.get()}</RelTime>"
                        else -> ""
                    }
                    respond(socket, 200, """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                        <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$fields</u:${action}Response></s:Body></s:Envelope>""".encodeToByteArray(), "text/xml")
                }
                "/source.mp4" -> {
                    sourceRequests.incrementAndGet()
                    if (headers["authorization"] != "Bearer fixture-source" || headers.values.any { it.contains("fixture-jf-token") }) credentialsSafe.set(false)
                    check(headers["range"] == "bytes=0-1023")
                    respond(socket, 206, ByteArray(1024) { ((it % 200) + 20).toByte() }, "video/mp4", "Content-Range: bytes 0-1023/1024\r\n")
                }
                else -> error("非本测试路由")
            }
        }
        fun fetch(url: String): ByteArray = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use {
            check(it.code == 206); it.body.bytes()
        }
        private fun respond(socket: Socket, status: Int, body: ByteArray, mime: String, extra: String = "") {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".encodeToByteArray())
                write(body); flush()
            }
        }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("隔离 renderer 断言失败", it) } }
        /** 仅失败时访问自己的 renderer；不带身份头，不输出目标、响应内容或异常 message。 */
        fun probeTransport(): String {
            val service = "urn:schemas-upnp-org:service:AVTransport:1"
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:GetTransportInfo xmlns:u=\"$service\">" +
                "<InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>"
            val request = Request.Builder().url("$origin/control").header("SOAPAction", "\"$service#GetTransportInfo\"")
                .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
            fun probe(label: String, bound: Boolean): String {
                var probeClient: OkHttpClient? = null
                return try {
                    val builder = OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                    if (bound) builder.socketFactory(network.socketFactory)
                    val activeClient = builder.build().also { probeClient = it }
                    activeClient.newCall(request).execute().use { "$label-status=${it.code}" }
                } catch (failure: Exception) {
                    val classes = generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString("->") {
                        it.javaClass.name + if (it is ErrnoException) "(errno=${it.errno})" else ""
                    }
                    "$label-failureClasses=$classes"
                } finally {
                    probeClient?.let {
                        it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown()
                    }
                }
            }
            return probe("default", false) + " " + probe("bound", true)
        }
        fun diagnostic(phase: String, state: CastState): String {
            val safeError = state.error.orEmpty().replace(Regex("(?i)https?://\\S+"), "[地址已隐藏]")
                .replace("fixture-jf-token", "[凭据已隐藏]").replace("Bearer fixture-source", "[凭据已隐藏]")
            return "$phase status=${state.status} busy=${state.busy} authExpired=${state.authExpired} error=$safeError " +
                "items=${itemRequests.get()} playbackInfo=${playbackInfoRequests.get()} setUri=${setUriRequests.get()} " +
                "plays=${plays.get()} source=${sourceRequests.get()} relayIssued=${relay.get() != null} " +
                "relayBytes=${relayBytes.get()} playReports=${playReports.get()} transport=${transport.get()}"
        }
        override fun close() {
            closed.set(true); server.close(); sockets.toList().forEach { runCatching { it.close() } }
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); executor.shutdownNow()
        }
    }
}

package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.MediaToolsScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 两个独立回环 origin：Jellyfin 管理请求带认证，外部提供商预览绝不带服务器凭据。 */
@RunWith(AndroidJUnit4::class)
class MediaImagesUiTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun providerImagesArePreviewedWithoutCredentialsAndAppliedOnlyAfterConfirmation() = withScreen { fixture, changed ->
        compose.onNodeWithTag("media-image-strip").assertDoesNotExist()
        compose.onNodeWithTag("media-tools-images-tab").assertDoesNotExist()
        compose.onNodeWithTag("media-image-dimensions/Primary/0").assertTextEquals("1600 × 2400 · 12 KB")
        openSearch()
        await(fixture) { fixture.thumbnails.get() > 0 && results() == 2 && fixture.providers.get() == 1 }
        compose.onNodeWithTag("remote-image-original").assertIsOff()
        assertEquals(0, fixture.originals.get())
        val searchesBeforeOriginal = fixture.searches.size
        compose.onNodeWithTag("remote-image-original").performClick().assertIsOn()
        await(fixture) { fixture.originals.get() > 0 }
        assertEquals("原图开关应切换真实预览源，不重新查询提供商", searchesBeforeOriginal, fixture.searches.size)
        assertEquals("预览原图仍不能选用或写入服务器", 0, fixture.applied.size)
        selectProvider("Fixture B")
        await(fixture) { fixture.searches.size == 2 && results() == 1 }
        compose.onNodeWithTag("remote-image-result").performClick()
        await(fixture) { compose.onAllNodesWithTag("remote-image-apply").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消", substring = false).performClick()
        VerificationDisplay.awaitWindowReady(compose, "remote-image-selection-cancelled", compose.onNodeWithTag("remote-image-results"))
        assertEquals(0, fixture.applied.size)
        assertEquals(0, changed.get())

        compose.onNodeWithTag("remote-image-result").performClick()
        compose.onNodeWithTag("remote-image-apply").performClick()
        await(fixture) { fixture.applied.size == 1 && changed.get() == 1 && fixture.imageLists.get() >= 2 &&
            compose.onAllNodesWithText("图片已更新").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Primary", fixture.applied.single().queryParameter("type"))
        assertTrue(requireNotNull(fixture.applied.single().queryParameter("imageUrl")).contains("/Fixture%20B/"))
        assertEquals("Fixture B", fixture.searches.last().queryParameter("providerName"))
        assertEquals(0, fixture.metadataWrites.get())
        assertTrue("外部图片必须确实通过 HTTP 读取，不能只断言 URL 字符串", fixture.previews.get() > 0)
    }

    @Test fun changingImageTypeCancelsPendingSearchAndItsLateResponseCannotReplaceTheNewType() = withScreen { fixture, changed ->
        fixture.holdNextPrimary().use { old ->
            openSearch()
            await(fixture) { old.arrived.count == 0L }
            compose.onNodeWithTag("remote-image-type").performClick()
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("remote-image-type/Backdrop").fetchSemanticsNodes().isNotEmpty() }
            VerificationDisplay.awaitWindowReady(compose, "image-search-type-menu", compose.onNodeWithTag("remote-image-type/Backdrop"))
            compose.onNodeWithTag("remote-image-type/Backdrop").performClick()
            await(fixture) { fixture.searches.size == 2 && results() == 1 &&
                compose.onAllNodesWithContentDescription("Fixture A 背景图", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            old.release()
            await(fixture) { old.finished.get() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Fixture A 背景图", useUnmergedTree = true).assertExists()
            compose.onAllNodesWithContentDescription("Fixture A 封面", useUnmergedTree = true).assertCountEquals(0)
            assertEquals("Backdrop", fixture.searches.last().queryParameter("type"))
            assertEquals(1, results())
            VerificationDisplay.awaitWindowReady(compose, "image-search-new-type", compose.onNodeWithTag("remote-image-all-languages"))
            compose.onNodeWithTag("remote-image-all-languages").assertIsOff().performClick()
            await(fixture) { fixture.searches.size == 3 && results() == 1 }
            assertEquals("true", fixture.searches.last().queryParameter("includeAllLanguages"))
            assertEquals("Backdrop", fixture.searches.last().queryParameter("type"))
            assertEquals(0, fixture.applied.size)
            assertEquals(0, changed.get())
        }
    }

    @Test fun scrollingRemoteImagesLoadsTheNextRealPageOnceAndStopsAtItsTotal() = withScreen(paginated = true) { fixture, _ ->
        openSearch()
        await(fixture) { fixture.searches.size == 1 && results() > 0 }
        repeat(12) {
            if (fixture.searches.size < 2) {
                compose.onNodeWithTag("remote-image-results").performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
        }
        await(fixture) { fixture.searches.size == 2 }
        repeat(3) { compose.onNodeWithTag("remote-image-results").performTouchInput { swipeUp() }; compose.waitForIdle() }
        assertEquals(listOf("0", "24"), fixture.searches.map { it.queryParameter("startIndex") })
        assertTrue(fixture.searches.all { it.queryParameter("limit") == "24" && it.queryParameter("type") == "Primary" })
        assertEquals("终页不能在继续滚动时反复请求", 2, fixture.searches.size)
        assertEquals(0, fixture.applied.size)
    }

    private fun openSearch() {
        VerificationDisplay.awaitWindowReady(compose, "image-search-inline", compose.onNodeWithTag("media-image-search/Primary"))
        compose.onNodeWithTag("media-image-search/Primary").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("remote-image-results").fetchSemanticsNodes().isNotEmpty() }
        VerificationDisplay.awaitWindowReady(compose, "image-search-results", compose.onNodeWithTag("remote-image-results"))
    }

    private fun selectProvider(name: String) {
        compose.onNodeWithTag("remote-image-provider").performClick()
        val tag = "remote-image-provider/$name"
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        VerificationDisplay.awaitWindowReady(compose, "image-search-provider-menu", compose.onNodeWithTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun results() = compose.onAllNodesWithTag("remote-image-result").fetchSemanticsNodes().size
    private fun await(fixture: Fixture, predicate: () -> Boolean) = compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); predicate() }

    private fun withScreen(paginated: Boolean = false, block: (Fixture, AtomicInteger) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        Fixture(paginated).use { fixture ->
            val shown = mutableStateOf(true)
            val changed = AtomicInteger()
            val session = Session(ServerConfig(fixture.api.url, true), TOKEN, "images-user", "Synthetic", "images-device", isAdministrator = true)
            val api = JellyfinApi()
            val loader = PosterImageCache.createMemoryLoader(context, OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader) {
                        if (shown.value) MediaToolsScreen(MediaItem(ITEM_ID, "Pathfin image fixture", "Movie"), session, api, "images",
                            onChanged = { changed.incrementAndGet() })
                    }
                }
            }
            var failure: Throwable? = null
            try {
                await(fixture) { fixture.imageLists.get() == 1 && compose.onAllNodes(
                    hasTestTag("media-image-dimensions/Primary/0") and hasText("1600 × 2400 · 12 KB")
                ).fetchSemanticsNodes().isNotEmpty() }
                VerificationDisplay.awaitWindowReady(compose, "images-fixture", compose.onNodeWithTag("media-image/Primary"))
                block(fixture, changed)
                fixture.assertHealthy()
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                try { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); PosterImageCache.release(loader) }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }

    private class Fixture(private val paginated: Boolean) : Closeable {
        val previews = AtomicInteger()
        val thumbnails = AtomicInteger()
        val originals = AtomicInteger()
        val providers = AtomicInteger()
        val imageLists = AtomicInteger()
        val metadataWrites = AtomicInteger()
        val applied = CopyOnWriteArrayList<okhttp3.HttpUrl>()
        val searches = CopyOnWriteArrayList<okhttp3.HttpUrl>()
        private val gate = AtomicReference<SearchGate?>()
        private val external = Endpoint { request ->
            check(request.method == "GET" && request.url.encodedPath.startsWith("/asset/"))
            check(request.headers.keys.none { it in setOf("authorization", "x-emby-authorization", "x-emby-token", "cookie") })
            check(request.headers.values.none { TOKEN in it })
            previews.incrementAndGet()
            when (request.url.pathSegments.getOrNull(1)) {
                "thumbnail" -> thumbnails.incrementAndGet()
                "original" -> originals.incrementAndGet()
                else -> error("提供商预览必须使用明确原图或缩略图入口")
            }
            Response(200, PNG, "image/png")
        }
        val api = Endpoint { request ->
            check(request.headers["authorization"]?.contains(TOKEN) == true)
            check("x-emby-token" !in request.headers)
            check(!request.url.toString().contains(TOKEN))
            when (request.method + " " + request.url.encodedPath) {
                "GET /Items/$ITEM_ID" -> Response.json(JSONObject().put("Id", ITEM_ID).put("Name", "Pathfin image fixture")
                    .put("Type", "Movie").put("Path", "/synthetic/Film.mkv").toString())
                "GET /Items/$ITEM_ID/Images" -> {
                    imageLists.incrementAndGet()
                    Response.json("""[{"ImageType":"Primary","ImageIndex":0,"ImageTag":"${if (applied.isEmpty()) "old" else "new"}","Width":1600,"Height":2400,"Size":12288}]""")
                }
                "GET /Items/$ITEM_ID/Images/Primary/0" -> Response(200, PNG, "image/png")
                "GET /Items/$ITEM_ID/RemoteImages/Providers" -> {
                    providers.incrementAndGet()
                    Response.json("""[{"Name":"Fixture A","SupportedImages":["Primary","Backdrop"]},{"Name":"Fixture B","SupportedImages":["Primary"]}]""")
                }
                "GET /Items/$ITEM_ID/RemoteImages" -> {
                    searches += request.url
                    val type = requireNotNull(request.url.queryParameter("type"))
                    val selectedProvider = request.url.queryParameter("providerName")
                    val start = requireNotNull(request.url.queryParameter("startIndex")).toInt()
                    val limit = requireNotNull(request.url.queryParameter("limit")).toInt()
                    check(type in setOf("Primary", "Backdrop") && limit == 24)
                    val total = if (type == "Backdrop" || selectedProvider != null) 1 else if (paginated) 25 else 2
                    val array = JSONArray()
                    for (index in start until minOf(start + limit, total)) {
                        val provider = selectedProvider ?: if (type == "Primary" && !paginated && index == 1) "Fixture B" else "Fixture A"
                        fun previewUrl(kind: String) = external.url.toHttpUrl().newBuilder().addPathSegment("asset").addPathSegment(kind)
                            .addPathSegment(type).addPathSegment(provider).addPathSegment("$index.png")
                            .addQueryParameter("signature", "source-only").build().toString()
                        array.put(JSONObject().put("Type", type).put("ProviderName", provider).put("Url", previewUrl("original"))
                            .put("ThumbnailUrl", previewUrl("thumbnail"))
                            .put("Width", 10).put("Height", 15))
                    }
                    val pending = if (type == "Primary") gate.getAndSet(null) else null
                    if (pending != null) { request.allowDisconnect = true; pending.block() }
                    Response.json(JSONObject().put("Images", array).put("TotalRecordCount", total).toString()).copy(onFinished = { pending?.finished?.set(true) })
                }
                "POST /Items/$ITEM_ID/RemoteImages/Download" -> {
                    check(request.body.isEmpty())
                    check(requireNotNull(request.url.queryParameter("imageUrl")).startsWith(external.url))
                    applied += request.url
                    Response(204)
                }
                "POST /Items/$ITEM_ID" -> { metadataWrites.incrementAndGet(); Response(204) }
                else -> error("图片管理回环不允许该请求")
            }
        }
        fun holdNextPrimary() = SearchGate().also { check(gate.compareAndSet(null, it)) }
        fun assertHealthy() { api.assertHealthy(); external.assertHealthy() }
        override fun close() { gate.getAndSet(null)?.release(); api.close(); external.close() }
    }

    private class SearchGate : Closeable {
        val arrived = CountDownLatch(1)
        val finished = AtomicBoolean()
        private val proceed = CountDownLatch(1)
        fun block() { arrived.countDown(); check(proceed.await(WAIT_MS * 3, TimeUnit.MILLISECONDS)) }
        fun release() = proceed.countDown()
        override fun close() = release()
    }
    private class Request(val method: String, val url: okhttp3.HttpUrl, val headers: Map<String, String>, val body: ByteArray) {
        var allowDisconnect = false
    }
    private data class Response(val code: Int, val bytes: ByteArray = ByteArray(0), val type: String = "application/json", val onFinished: () -> Unit = {}) {
        companion object { fun json(value: String) = Response(200, value.toByteArray()) }
    }
    private class Endpoint(private val handler: (Request) -> Response) : Closeable {
        private val closed = AtomicBoolean()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val executor = Executors.newCachedThreadPool()
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val url = "http://127.0.0.1:${socket.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val connection = socket.accept(); sockets.add(connection); executor.execute { serve(connection) }
            } catch (error: SocketException) { if (!closed.get()) failures.add(error) }
        }, "pathfin-images-accept").apply { isDaemon = true; start() }
        private fun serve(connection: Socket) {
            var request: Request? = null
            var response: Response? = null
            try {
                connection.use {
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = DataInputStream(connection.getInputStream())
                    val firstLine = line(input) ?: return
                    val parts = firstLine.split(' '); check(parts.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(input) ?: throw EOFException()
                        if (header.isEmpty()) break
                        val colon = header.indexOf(':'); check(colon > 0 && headers.size < 128)
                        headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                    }
                    check("transfer-encoding" !in headers)
                    val length = headers["content-length"]?.toInt() ?: 0; check(length in 0..1_048_576)
                    request = Request(parts[0], (url + parts[1]).toHttpUrl(), headers, ByteArray(length).also(input::readFully))
                    response = handler(requireNotNull(request))
                    val value = requireNotNull(response)
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 ${value.code} Fixture\r\nContent-Type: ${value.type}\r\nContent-Length: ${value.bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(value.bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get() && !(error is IOException && request?.allowDisconnect == true)) failures.add(error)
            } finally { response?.onFinished?.invoke(); sockets.remove(connection) }
        }
        private fun line(input: DataInputStream): String? {
            val buffer = java.io.ByteArrayOutputStream()
            while (buffer.size() <= 32_768) {
                val byte = input.read()
                if (byte < 0) { if (buffer.size() == 0) return null; throw EOFException() }
                if (byte == 10) return buffer.toString("ISO-8859-1").removeSuffix("\r")
                buffer.write(byte)
            }
            error("请求头过长")
        }
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("图片管理回环失败", it) } }
        override fun close() { closed.set(true); socket.close(); sockets.forEach { runCatching { it.close() } }; executor.shutdownNow(); accept.join(1000) }
    }
    private companion object {
        const val ITEM_ID = "images-fixture"
        const val TOKEN = "synthetic-images-token"
        const val WAIT_MS = 15_000L
        val PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0hoAAAAASUVORK5CYII=")
    }
}

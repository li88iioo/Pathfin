package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.*
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudPlaybackApiTest {
    private val session = Session(ServerConfig("https://jellyfin.example/jellyfin"), "fixture-jellyfin-token",
        "user", "Fixture", "device", serverId = "server")
    private val signature = "a".repeat(64)

    @Test fun candidateUrlRebasesTheValidatedExtensionAndKeepsOriginalProtocolRules() {
        val original = source("https://mf.example")
        val signed = original.path!!
        val extension = signed.replace("mf.example", "cloud.example")
        for (protocol in listOf(null, "", "Http", "HTTP", "File", "Ftp")) {
            val source = original.copy(protocol = protocol)
            val expected = signed.takeIf { protocol.isNullOrBlank() || protocol.equals("Http", true) }
            assertEquals(expected, CloudPlaybackApi.candidateUrl(session, source))
            assertEquals(signed.replace("mf.example", "jellyfin.example"),
                CloudPlaybackApi.candidateUrl(session, source.copy(cloudPlaybackUrl = extension)))
        }
        for (invalid in listOf("", "/local/movie.strm", "file:///movie.strm", "ftp://cloud.example/playgy/a/b/c/d?v=1&sig=$signature",
                extension.substringBefore('?'), extension.replace("v=1", "v=2"), extension.replace(signature, "short"),
                extension.replace("cloud.example", "user:password@cloud.example"))) {
            assertEquals(signed, CloudPlaybackApi.candidateUrl(session, original.copy(cloudPlaybackUrl = invalid)))
            assertNull(CloudPlaybackApi.candidateUrl(session, original.copy(protocol = "File", cloudPlaybackUrl = invalid)))
        }
    }

    @Test fun extensionUsesTheCompleteHttpsOriginAndPreservesTheEncodedPathAndQuery() {
        val proxySession = session.copy(server = ServerConfig("https://mediaflux.example:34488/jellyfin"))
        val suffix = "/proxy/playgy/account%2Froot/share/file/movie%20one.mkv?v=1&sig=$signature&name=a%2Fb&name=two&tag=a+b&empty=&flag"
        for (origin in listOf("https://mediaflux.example", "http://10.0.0.8:8096")) {
            val raw = origin + suffix
            val normal = source(origin).copy(path = raw, protocol = "Http")
            assertEquals("普通 HTTP Path 保持原始 origin", raw, CloudPlaybackApi.candidateUrl(proxySession, normal))
            assertEquals("扩展只替换会话 origin，不能拼接服务器 base path 或重编码签名参数",
                "https://mediaflux.example:34488$suffix",
                CloudPlaybackApi.candidateUrl(proxySession, normal.copy(protocol = "File", cloudPlaybackUrl = raw)))
        }
    }

    @Test fun reverseProxyExtensionUsesTheSessionOriginWithoutProxyCredentials() = runBlocking {
        Fixture { exchange ->
            exchange.reply(if (exchange.requestURI.rawQuery.contains("view=variants")) variantsJson() else resolveJson())
        }.use { fixture ->
            val signed = source(fixture.baseUrl).path!! + "&name=a%2Fb&name=two"
            val advertised = signed.replace(fixture.baseUrl, "http://10.0.0.8:8096")
            val proxySession = session.copy(server = ServerConfig(fixture.baseUrl, allowInsecureHttp = true))
            val source = source(fixture.baseUrl).copy(path = "${fixture.baseUrl}/proxy/stream", protocol = "File", cloudPlaybackUrl = advertised)
            val api = CloudPlaybackApi()
            val cloud = requireNotNull(api.discover(proxySession, source))
            assertEquals(signed, cloud.sourceUrl)
            val initial = request(fixture.baseUrl).copy(session = proxySession, source = source, cloudPlayback = cloud)
            val selected = api.select(initial, PlaybackQuality.STANDARD)
            assertEquals(initial.source, selected.source)
            assertEquals(initial.startPositionMs, selected.startPositionMs)
            assertEquals("hd", selected.cloudPlayback!!.selectedId)
            assertEquals(signed, selected.cloudPlayback.sourceUrl)
            assertTrue(selected.stream.headers.isEmpty())
            assertFalse(selected.stream.trustedServer)
            assertTrue(PlaybackHeaders.forUrl(selected, selected.stream.uri).isEmpty())
            assertEquals(listOf("variants", "resolve"), fixture.requests.map { it.url.queryParameter("view") })
            fixture.requests.forEach { recorded ->
                assertEquals("/proxy/playgy/account/share/file/movie.mkv", recorded.url.encodedPath)
                assertEquals(signature, recorded.url.queryParameter("sig"))
                assertEquals(listOf("a/b", "two"), recorded.url.queryParameterValues("name"))
                assertAnonymous(recorded)
            }
            for (unsafe in listOf("$advertised&api_key=${session.accessToken}",
                    "$advertised&api_key=${session.accessToken.replace("-", "%252D")}",
                    signed.replace(fixture.baseUrl, "http://${session.accessToken}.invalid"))) {
                assertSafe(expectFailure { api.discover(proxySession, source.copy(cloudPlaybackUrl = unsafe)) })
            }
            assertEquals("包含外部服务器凭据的扩展不能发出请求", 2, fixture.requests.size)
        }
    }

    @Test fun invalidExtensionsFallBackToTheNormalSourceAndNeverProbeLocalFiles() = runBlocking {
        Fixture { it.reply(variantsJson()) }.use { fixture ->
            val api = CloudPlaybackApi()
            val original = source(fixture.baseUrl)
            val invalid = "${fixture.baseUrl}/proxy/stream"
            val normal = original.copy(cloudPlaybackUrl = invalid)
            assertEquals(original.path, api.discover(session, normal)?.sourceUrl)
            val local = normal.copy(path = "/local/movie.strm", protocol = "File", supportsDirectStream = true,
                directStreamUrl = "/Videos/item/stream")
            assertNull(api.discover(session, local))
            val stream = PlaybackResolver.resolve(session, MediaItem("item", "Local", "Movie"), PlaybackInfo(listOf(local), "play"), local.id)
            assertTrue("无效扩展仍保留原 Jellyfin 播放", stream.trustedServer)
            assertNull(api.discover(session, normal.copy(protocol = "File")))
            assertEquals("只能探测原本有效的 HTTP Path", 1, fixture.requests.size)
            assertAnonymous(fixture.requests.single())
        }
    }

    @Test fun originalUsesItsCompleteSourceVideoWhileCloudRenditionsKeepTheirOwnFormat() = runBlocking {
        val inspected = mutableListOf<MediaStream>()
        Fixture { exchange ->
            exchange.reply(if (exchange.requestURI.rawQuery.contains("view=variants")) variantsJson() else resolveJson())
        }.use { fixture ->
            val video = MediaStream(type = "Video", codec = "hevc", profile = "Main 10", dvProfile = 5,
                videoRangeType = "DOVI", bitDepth = 10, width = 3840, height = 2160, averageFrameRate = 25.0)
            val source = source(fixture.baseUrl).copy(mediaStreams = listOf(video))
            val api = CloudPlaybackApi(videoSupport = { inspected += it; it.dvProfile != 5 })
            val cloud = requireNotNull(api.discover(session, source))

            assertFalse("云接口只返回HEVC时也不能丢掉原画的DV5信息", cloud.variant(PlaybackQuality.ORIGINAL)!!.playable)
            assertEquals(video, inspected.first())
            assertTrue("同一文件的H264云档不能继承原画的DV限制", cloud.variant(PlaybackQuality.STANDARD)!!.playable)
            val request = request(fixture.baseUrl).copy(source = source, cloudPlayback = cloud)
            assertSafe(expectFailure { api.select(request, PlaybackQuality.ORIGINAL) })
            assertEquals("已知无法解码的原画不能继续请求相同原画地址", 1, fixture.requests.size)

            val compatible = api.select(request, PlaybackQuality.STANDARD)
            assertEquals("hd", compatible.cloudPlayback!!.selectedId)
            assertEquals("h264", inspected.last().codec)
            assertNull(inspected.last().dvProfile)
            assertNull(inspected.last().videoRangeType)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun resolvingAnOriginalRechecksSourceCapabilityAndUnknownInformationStaysPlayable() = runBlocking {
        Fixture { it.reply(resolveJson(variantJson("original"))) }.use { fixture ->
            val video = MediaStream(type = "Video", codec = "hevc", dvProfile = 5, videoRangeType = "DOVI", bitDepth = 10)
            val initial = request(fixture.baseUrl).let { it.copy(source = it.source.copy(mediaStreams = listOf(video))) }
            val api = CloudPlaybackApi(videoSupport = { if (it.dvProfile == 5) false else null })
            assertSafe(expectFailure { api.select(initial, PlaybackQuality.ORIGINAL) })
            assertEquals("原画解析结果也必须重新校验完整DV能力", 1, fixture.requests.size)
            assertEquals("original", initial.cloudPlayback!!.selectedId)

            val unknown = api.select(initial.copy(source = initial.source.copy(mediaStreams = emptyList())), PlaybackQuality.ORIGINAL)
            assertEquals("original", unknown.cloudPlayback!!.selectedId)
            assertEquals(PlayMethod.DIRECT_PLAY, unknown.stream.method)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun discoveryOnlyProbesSignedPlaygyAndPreservesRealMetadataWithoutSourceCredentials() = runBlocking {
        Fixture { it.reply(variantsJson()) }.use { fixture ->
            val api = CloudPlaybackApi(videoSupport = { if (it.codec == "hevc") false else null })
            val source = source(fixture.baseUrl)
            val signed = source.path!!
            for (path in listOf(null, "/local/movie.strm", "file:///movie.strm",
                    "${fixture.baseUrl}/video?v=1&sig=$signature", signed.substringBefore('?'),
                    signed.replace("v=1", "v=2"), signed.replace(signature, "short"))) {
                assertNull(api.discover(session, source.copy(path = path)))
            }
            assertSafe(expectFailure { api.discover(session, source.copy(path = "$signed&api_key=${session.accessToken}")) })
            assertTrue(fixture.requests.isEmpty())

            val cloud = requireNotNull(api.discover(session, source))
            assertEquals(signed, cloud.sourceUrl)
            assertEquals(listOf("original", "hd"), cloud.variants.map { it.id })
            assertFalse(cloud.variants.first().playable)
            val standard = requireNotNull(cloud.variant(PlaybackQuality.STANDARD))
            assertEquals(1280, standard.width)
            assertEquals(536, standard.height)
            assertTrue(standard.description.contains("1280×536"))
            assertEquals("application/x-mpegURL", standard.mimeType)
            val request = fixture.requests.single()
            assertEquals("variants", request.url.queryParameter("view"))
            assertNull(request.url.queryParameter("quality"))
            assertEquals(signature, request.url.queryParameter("sig"))
            assertEquals("application/json", request.headers["accept"]?.single())
            assertAnonymous(request)
        }
    }

    @Test fun legacyRedirectsMissingEndpointsAndNonProtocolBodiesReturnNullWithoutFetchingVideo() = runBlocking {
        val reply = AtomicReference(Triple(302, "text/plain", "legacy video redirect"))
        val videos = AtomicInteger()
        Fixture { exchange ->
            if (exchange.requestURI.path == "/video") {
                videos.incrementAndGet()
                exchange.reply("video bytes", type = "video/mp4")
            } else {
                val current = reply.get()
                if (current.first == 302) exchange.responseHeaders.set("Location", "http://127.0.0.1:${exchange.localAddress.port}/video")
                exchange.reply(current.third, current.first, current.second)
            }
        }.use { fixture ->
            val api = CloudPlaybackApi(OkHttpClient.Builder().followRedirects(true).build())
            for (current in listOf(Triple(302, "text/plain", "legacy redirect"),
                    Triple(404, "application/json", "{}"), Triple(200, "video/mp4", "video bytes"),
                    Triple(200, "application/json", "{\"protocol\":\"another.protocol\"}"),
                    Triple(200, "application/json", "not JSON"))) {
                reply.set(current)
                assertNull("旧端点或非协议响应应忽略：${current.first}/${current.second}", api.discover(session, source(fixture.baseUrl)))
                assertEquals(0, videos.get())
            }
        }
    }

    @Test fun discoveryDoesNotInheritCookiesFromTheSuppliedClient() = runBlocking {
        val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(Cookie.Builder()
                .name("account").value("inherited-cookie").hostOnlyDomain(url.host).path("/").build())
        }).build()
        Fixture { it.reply(variantsJson()) }.use { fixture ->
            assertNotNull(CloudPlaybackApi(client).discover(session, source(fixture.baseUrl)))
            assertAnonymous(fixture.requests.single())
        }
    }

    @Test fun selectionResolvesAgainAndPreservesPlaybackIdentityWhileUsingExplicitHlsMime() = runBlocking {
        val resolutions = AtomicInteger()
        Fixture { exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            val selected = if (query.queryParameter("quality") == "original") variantJson("original") else variantJson()
            exchange.reply(resolveJson(selected, "https://cdn.example/opaque?id=${resolutions.incrementAndGet()}"))
        }.use { fixture ->
            val api = CloudPlaybackApi()
            val initial = request(fixture.baseUrl)
            val first = api.select(initial, PlaybackQuality.STANDARD)
            val second = api.select(first, PlaybackQuality.STANDARD)
            val original = api.select(second, PlaybackQuality.ORIGINAL)
            for (selected in listOf(first, second, original)) {
                assertEquals(initial.item, selected.item)
                assertEquals(initial.session, selected.session)
                assertEquals(initial.source, selected.source)
                assertEquals(initial.info.sources, selected.info.sources)
                assertEquals(initial.info.playSessionId, selected.info.playSessionId)
                assertEquals(initial.startPositionMs, selected.startPositionMs)
                assertEquals(initial.profileId, selected.profileId)
                assertEquals(initial.cloudPlayback!!.sourceUrl, selected.cloudPlayback!!.sourceUrl)
                assertTrue(selected.stream.headers.isEmpty())
                assertFalse(selected.stream.trustedServer)
                assertEquals(PlayMethod.DIRECT_PLAY, selected.stream.method)
                assertEquals(initial.source.id, selected.stream.sourceId)
                assertTrue(PlaybackHeaders.forUrl(selected, selected.stream.uri).isEmpty())
            }
            assertEquals("https://cdn.example/opaque?id=1", first.stream.uri)
            assertEquals("https://cdn.example/opaque?id=2", second.stream.uri)
            assertEquals("application/x-mpegURL", first.stream.mimeType)
            assertEquals("hd", first.cloudPlayback!!.selectedId)
            assertEquals(536, first.cloudPlayback!!.variant(PlaybackQuality.STANDARD)?.height)
            assertEquals(PlaybackQuality.STANDARD, first.quality)
            assertEquals("original", original.cloudPlayback!!.selectedId)
            assertEquals("video/mp4", original.stream.mimeType)
            assertEquals(listOf("hd", "hd", "original"), fixture.requests.map { it.url.queryParameter("quality") })
            fixture.requests.forEach { recorded ->
                assertEquals("resolve", recorded.url.queryParameter("view"))
                assertEquals(signature, recorded.url.queryParameter("sig"))
                assertAnonymous(recorded)
            }
        }
    }

    @Test fun unavailableVariantsWrongIdentitiesAndExternalJellyfinCredentialsAreRejected() = runBlocking {
        val response = AtomicReference(resolveJson())
        Fixture { it.reply(response.get()) }.use { fixture ->
            val api = CloudPlaybackApi(videoSupport = { it.codec != "av1" })
            val initial = request(fixture.baseUrl)
            val unavailable = initial.copy(cloudPlayback = initial.cloudPlayback!!.copy(
                variants = initial.cloudPlayback!!.variants.map { it.copy(playable = false) }))
            for (quality in listOf(PlaybackQuality.ORIGINAL, PlaybackQuality.STANDARD)) {
                assertSafe(expectFailure { api.select(unavailable, quality) })
            }
            assertTrue(fixture.requests.isEmpty())
            val encodedToken = session.accessToken.replace("-", "%252D")
            for (body in listOf(resolveJson(variantJson("another-variant")),
                    resolveJson(variantJson().put("original", true)),
                    resolveJson(variantJson().put("video_codec", "av1")),
                    resolveJson(url = "https://cdn.example/private?api_key=${session.accessToken}"),
                    resolveJson(url = "https://cdn.example/private?api_key=$encodedToken"),
                    JSONObject(resolveJson()).put("protocol", "another.protocol").toString())) {
                response.set(body)
                assertSafe(expectFailure { api.select(initial, PlaybackQuality.STANDARD) })
            }
            assertEquals(6, fixture.requests.size)
            assertEquals("original", initial.cloudPlayback!!.selectedId)
            fixture.requests.forEach(::assertAnonymous)
        }
    }

    @Test fun cancellationClosesABlockedCallAndLateResultsCannotPublishAnOldSession() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<Call>()
        val published = AtomicReference<PlaybackRequest?>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        }).build()
        Fixture { exchange ->
            if (exchange.requestURI.rawQuery.contains("generation=current")) exchange.reply(resolveJson())
            else {
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                    arrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    runCatching { exchange.responseBody.write(resolveJson().drop(1).toByteArray()) }
                } finally { finished.countDown() }
            }
        }.use { fixture ->
            val api = CloudPlaybackApi(client)
            val initial = request(fixture.baseUrl)
            val pending = async(Dispatchers.Default) { api.select(initial, PlaybackQuality.STANDARD).also { published.set(it) } }
            try {
                assertTrue(withContext(Dispatchers.IO) { arrived.await(3, TimeUnit.SECONDS) })
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertTrue(calls.first().isCanceled())
                assertTrue(withContext(Dispatchers.IO) { failed.await(3, TimeUnit.SECONDS) })
                val replacement = initial.copy(session = session.copy(accessToken = "replacement-token", userId = "other-user"),
                    cloudPlayback = initial.cloudPlayback!!.copy(sourceUrl = "${initial.cloudPlayback!!.sourceUrl}&generation=current"))
                assertEquals(replacement.session, api.select(replacement, PlaybackQuality.STANDARD).session)
                release.countDown()
                assertTrue(withContext(Dispatchers.IO) { finished.await(3, TimeUnit.SECONDS) })
                assertTrue(pending.isCancelled)
                assertNull(published.get())
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun oversizedBodiesAndHttpErrorsFailWithoutLeakingResponseSecrets() = runBlocking {
        val secret = "https://cdn.example/private?sig=$signature&api_key=${session.accessToken}"
        val response = AtomicReference(403 to "{\"detail\":\"$secret\"}")
        Fixture { it.reply(response.get().second, response.get().first) }.use { fixture ->
            val api = CloudPlaybackApi()
            val oversized = JSONObject(variantsJson()).put("padding", "x".repeat(256 * 1024) + secret).toString()
            for (body in listOf(403 to "{\"detail\":\"$secret\"}", 503 to "{\"detail\":\"$secret\"}", 200 to oversized)) {
                response.set(body)
                assertSafe(expectFailure { api.discover(session, source(fixture.baseUrl)) })
            }
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun malformedVariantValuesCannotLeakSignedUrlsOrTokensThroughJsonExceptions() = runBlocking {
        val secret = "https://cdn.example/private?sig=$signature&api_key=${session.accessToken}"
        val response = AtomicReference(JSONObject().put("protocol", "mediaflux.playback.v1")
            .put("variants", JSONArray().put(variantJson("original")).put(secret)).toString())
        Fixture { it.reply(response.get()) }.use { fixture ->
            val api = CloudPlaybackApi()
            assertSafe(expectFailure { api.discover(session, source(fixture.baseUrl)) })
            response.set(JSONObject().put("protocol", "mediaflux.playback.v1").put("variant", secret).put("url", secret).toString())
            assertSafe(expectFailure { api.select(request(fixture.baseUrl), PlaybackQuality.STANDARD) })
        }
    }

    private fun source(baseUrl: String) = MediaSource("source",
        path = "$baseUrl/proxy/playgy/account/share/file/movie.mkv?v=1&sig=$signature&view=old&quality=stale",
        requiredHttpHeaders = mapOf("Authorization" to "Bearer source-secret", "Cookie" to "source-cookie=private",
            "X-Emby-Token" to session.accessToken, "X-Source-Required" to "source-header-value"))

    private fun request(baseUrl: String): PlaybackRequest {
        val source = source(baseUrl)
        val original = CloudVariant("original", true, "Original", 3840, 1600, transport = "file", mimeType = "video/mp4")
        val standard = CloudVariant("hd", false, "HD", 1280, 720, transport = "hls", mimeType = "application/x-mpegURL")
        return PlaybackRequest(MediaItem("item", "Fixture movie", "Movie"),
            PlaybackInfo(listOf(source), "play-session", delivery = PlaybackDelivery.SERVER_STREAM), source,
            ResolvedPlayback("https://jellyfin.example/jellyfin/Videos/item/stream", PlaybackHeaders.serverHeaders(session),
                PlayMethod.DIRECT_STREAM, source.id, true), 91_234L, session, profileId = "profile",
            cloudPlayback = CloudPlayback(source.path!!, listOf(original, standard)))
    }

    private fun variantJson(id: String = "hd"): JSONObject = JSONObject().put("id", id).put("original", id == "original")
        .put("label", id).put("width", if (id == "original") 3840 else 1280).put("height", if (id == "original") 1600 else 536)
        .put("video_codec", if (id == "original") "hevc" else "h264").put("audio_codec", "aac")
        .put("transport", if (id == "original") "file" else "hls").put("mime_type", "video/mp4")

    private fun variantsJson(): String = JSONObject().put("protocol", "mediaflux.playback.v1")
        .put("variants", JSONArray().put(variantJson("original")).put(variantJson())).toString()

    private fun resolveJson(variant: JSONObject = variantJson(), url: String = "https://cdn.example/opaque"): String =
        JSONObject().put("protocol", "mediaflux.playback.v1").put("variant", variant).put("url", url).toString()

    private suspend fun expectFailure(action: suspend () -> Any?): Exception {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return error }
        throw AssertionError("Expected cloud playback failure")
    }

    private fun assertSafe(error: Exception) {
        val diagnostic = generateSequence<Throwable>(error) { it.cause }.joinToString("\n") { it.toString() }
        for (secret in listOf(session.accessToken, signature, "https://cdn.example/private", "http://127.0.0.1:")) {
            assertFalse("云盘错误不得包含签名地址或凭据", diagnostic.contains(secret))
        }
    }

    private fun assertAnonymous(request: Recorded) {
        for (name in listOf("authorization", "x-emby-token", "x-emby-authorization", "x-mediabrowser-token", "cookie", "x-source-required")) {
            assertNull("云接口不得发送 $name", request.headers[name])
        }
        assertFalse(request.headers.values.flatten().any { session.accessToken in it || "source-secret" in it })
        assertFalse(request.url.toString().contains(session.accessToken))
    }

    private data class Recorded(val url: HttpUrl, val headers: Map<String, List<String>>)

    private class Fixture(handler: (HttpExchange) -> Unit) : Closeable {
        val requests = CopyOnWriteArrayList<Recorded>()
        private val failure = AtomicReference<Throwable?>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    assertEquals("GET", exchange.requestMethod)
                    requests += Recorded("http://localhost${exchange.requestURI}".toHttpUrl(),
                        exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() })
                    handler(exchange)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    runCatching { exchange.sendResponseHeaders(500, -1) }
                } finally { exchange.close() }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            failure.get()?.let { throw AssertionError("云盘 HTTP fixture 失败", it) }
        }
    }

    private fun HttpExchange.reply(body: String, code: Int = 200, type: String = "application/json") {
        val bytes = body.toByteArray()
        responseHeaders.set("Content-Type", type)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

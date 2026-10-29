package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.PublicUser
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JellyfinApiTest {
    @Test fun authenticationAndItemsUseProxyPrefixAndHeaderAuthentication() = withServer({ exchange ->
        when (exchange.requestURI.path) {
            "/proxy/Users/AuthenticateByName" -> {
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                assertEquals("Alice", body.getString("Username"))
                assertEquals(" password ", body.getString("Pw"))
                assertEquals(setOf("Username", "Pw"), body.keys().asSequence().toSet())
                assertEquals(identityHeader, exchange.requestHeaders.getFirst("Authorization"))
                assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
                assertNull(exchange.requestHeaders.getFirst("X-Emby-Authorization"))
                exchange.json("""{"AccessToken":"fixture-token","User":{"Id":"user","Name":"Alice"},"ServerId":"server"}""")
            }
            "/proxy/Items" -> {
                assertEquals("$identityHeader, Token=\"fixture-token\"", exchange.requestHeaders.getFirst("Authorization"))
                assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
                assertNull(exchange.requestHeaders.getFirst("X-Emby-Authorization"))
                assertTrue(exchange.requestURI.rawQuery.contains("filters=IsResumable"))
                assertFalse(exchange.requestURI.rawQuery.contains("fixture-token"))
                exchange.json("""{"Items":[{"Id":"film","Name":"Movie","Type":"Movie"}],"TotalRecordCount":1}""")
            }
            else -> exchange.json("{}", 404)
        }
    }) { url ->
        runBlocking {
            val api = JellyfinApi()
            val session = api.authenticate(ServerConfig("$url/proxy", true), "Alice", " password ", "device")
            assertEquals("Alice", session.userName)
            assertEquals("film", api.items(session, resume = true).items.single().id)
            assertEquals(mapOf("Authorization" to "$identityHeader, Token=\"fixture-token\""), api.imageHeaders(session))
        }
    }

    @Test fun jellyfin12RejectsLegacyIdentityButAcceptsStandardLoginWithoutChangingPasswordBody() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            assertEquals("POST", exchange.requestMethod)
            assertEquals("/Users/AuthenticateByName", exchange.requestURI.path)
            assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/json"))
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            assertEquals(setOf("Username", "Pw"), body.keys().asSequence().toSet())
            assertEquals("Alice", body.getString("Username"))
            assertEquals(" password ", body.getString("Pw"))
            val validIdentity = exchange.requestHeaders.getFirst("Authorization") == identityHeader &&
                exchange.requestHeaders.getFirst("X-Emby-Authorization") == null &&
                exchange.requestHeaders.getFirst("X-Emby-Token") == null
            if (!validIdentity) {
                exchange.json("""{"title":"Bad Request","status":400,"errors":{"Client":["The Client field is required."],"DeviceId":["The DeviceId field is required."]}}""", 400)
            } else {
                exchange.json("""{"AccessToken":"fixture-token","ServerId":"server","User":{"Id":"user","Name":"Alice"},"SessionInfo":{"Id":"session"}}""")
            }
        }) { url ->
            val legacy = URL("$url/Users/AuthenticateByName").openConnection() as HttpURLConnection
            try {
                legacy.requestMethod = "POST"
                legacy.doOutput = true
                legacy.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                legacy.setRequestProperty("X-Emby-Authorization", identityHeader)
                legacy.outputStream.use { it.write("""{"Username":"Alice","Pw":" password "}""".toByteArray()) }
                assertEquals(400, legacy.responseCode)
                val errorBody = legacy.errorStream.bufferedReader().use { JSONObject(it.readText()) }
                assertEquals(400, errorBody.getInt("status"))
                assertTrue(errorBody.getJSONObject("errors").has("Client"))
                assertTrue(errorBody.getJSONObject("errors").has("DeviceId"))
            } finally {
                legacy.disconnect()
            }
            runBlocking {
                val session = JellyfinApi().authenticate(ServerConfig(url, true), "Alice", " password ", "device")
                assertEquals("fixture-token", session.accessToken)
                assertEquals("user", session.userId)
                assertEquals("server", session.serverId)
                assertEquals("device", session.deviceId)
            }
            assertEquals("每次操作只能发起一次请求，不能回退重试旧认证", 2, requests.get())
        }
    }

    @Test fun mediaQueriesRequestKnownMetadataFieldsAndBothImageTypes() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/Items", exchange.requestURI.path)
        assertEquals(setOf("Primary", "Backdrop", "Banner", "Logo"), query.queryParameter("enableImageTypes")!!.split(",").toSet())
        assertEquals(if (query.queryParameter("ids") == "film") "20" else "1", query.queryParameter("imageTypeLimit"))
        val fields = query.queryParameter("fields")!!.split(",").toSet()
        val expected = if (query.queryParameter("ids") == "film") {
            setOf("Overview", "MediaSources", "Genres", "ChildCount", "People", "Studios", "ExternalUrls", "Tags", "DateCreated", "DateLastMediaAdded", "CanDelete", "CanDownload", "Chapters", "ProviderIds", "ProductionLocations", "OriginalTitle")
        } else {
            setOf("Overview", "MediaSourceCount", "PrimaryImageAspectRatio", "Genres", "ChildCount", "Tags", "DateCreated", "DateLastMediaAdded", "DateLastSaved")
        }
        assertEquals(expected, fields)
        exchange.json("""{"Items":[{"Id":"film","Name":"Movie","Type":"Movie","Genres":["Drama"],"ChildCount":0}],"TotalRecordCount":1}""")
    }) { url ->
        runBlocking {
            val api = JellyfinApi()
            assertEquals(listOf("Drama"), api.items(session(url)).items.single().genres)
            assertEquals(0, api.item(session(url), "film").childCount)
        }
    }

    @Test fun badRequestMessageDoesNotInventServerVersionDiagnosis() = withServer({ exchange ->
        exchange.json("""{"status":400,"detail":"server internals fixture-token"}""", 400)
    }) { url ->
        runBlocking {
            val error = try {
                JellyfinApi().authenticate(ServerConfig(url, true), "Alice", "password", "device")
                throw AssertionError("Expected bad request")
            } catch (error: ApiException) { error }
            assertEquals(400, error.statusCode)
            assertTrue(error.message.orEmpty().contains("400"))
            assertFalse(error.message.orEmpty().contains("版本"))
            assertFalse(error.message.orEmpty().contains("fixture-token"))
        }
    }

    @Test fun serverResponseCannotLeakSecretsThroughErrorMessage() = withServer({ exchange ->
        exchange.json("""{"error":"fixture-token https://cdn.example/?signed=secret"}""", 401)
    }) { url ->
        runBlocking {
            val error = try {
                JellyfinApi().validateSession(session(url))
                throw AssertionError("Expected expired session")
            } catch (error: ApiException) { error }
            assertEquals(401, error.statusCode)
            assertFalse(error.message.orEmpty().contains("fixture-token"))
            assertFalse(error.message.orEmpty().contains("signed"))
            assertFalse("已保存会话失效不能误报密码错误", error.message.orEmpty().contains("密码"))
            assertTrue(error.message.orEmpty().contains("登录已失效"))
        }
    }

    @Test fun onlyPasswordAuthenticationReportsInvalidCredentials() = withServer({ exchange ->
        exchange.json("{}", 401)
    }) { url ->
        runBlocking {
            val error = try {
                JellyfinApi().authenticate(ServerConfig(url, true), "Alice", "wrong", "device")
                throw AssertionError("Expected rejected credentials")
            } catch (error: ApiException) { error }
            assertEquals(401, error.statusCode)
            assertTrue(error.message.orEmpty().contains("账号或密码错误"))
            assertFalse(error.message.orEmpty().contains("登录已失效"))
        }
    }

    @Test fun authenticatedApiNeverFollowsRedirect() {
        val redirectHits = AtomicInteger()
        withServer({ exchange ->
            if (exchange.requestURI.path == "/Users/Me") {
                exchange.responseHeaders.add("Location", "http://localhost:${exchange.localAddress.port}/capture")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                redirectHits.incrementAndGet()
                exchange.json("{}")
            }
        }) { url ->
            runBlocking {
                val error = try {
                    JellyfinApi().validateSession(session(url))
                    throw AssertionError("Expected redirect rejection")
                } catch (error: ApiException) { error }
                assertEquals(302, error.statusCode)
                assertEquals(0, redirectHits.get())
            }
        }
    }

    @Test fun playbackUsesConservativeProfileAndReports64BitTicks() {
        val bodies = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        withServer({ exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            bodies.add(exchange.requestURI.path to body)
            if (exchange.requestURI.path.endsWith("PlaybackInfo")) {
                exchange.json("""{"PlaySessionId":"play","MediaSources":[{"Id":"source","Path":"https://cdn.example/movie.mp4","Protocol":"Http"}]}""")
            } else {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
        }) { url ->
            runBlocking {
                val api = JellyfinApi()
                val session = session(url)
                val info = api.playbackInfo(session, "item", "source", 90_000_000_000L)
                val request = PlaybackRequest(
                    MediaItem("item", "Movie", "Movie"), info, info.sources.single(),
                    ResolvedPlayback("https://cdn.example/movie.mp4", emptyMap(), PlayMethod.DIRECT_PLAY, "source", false),
                    9_000_000L, session,
                )
                for (event in listOf("start", "progress", "stop")) api.reportPlayback(session, event, request, 9_000_000L, true)
                val profile = bodies.first().second.getJSONObject("DeviceProfile")
                val direct = profile.getJSONArray("DirectPlayProfiles").getJSONObject(0)
                assertEquals("h264", direct.getString("VideoCodec"))
                assertEquals("aac", direct.getString("AudioCodec"))
                assertEquals(90_000_000_000L, bodies.first().second.getLong("StartTimeTicks"))
                assertEquals(listOf("/Items/item/PlaybackInfo", "/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), bodies.map { it.first })
                for ((_, body) in bodies.drop(1)) {
                    assertEquals("play", body.getString("PlaySessionId"))
                    assertEquals("source", body.getString("MediaSourceId"))
                    assertEquals(90_000_000_000L, body.getLong("PositionTicks"))
                }
            }
        }
    }

    @Test fun cancellationCancelsPendingNetworkCall() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServer({ exchange ->
            arrived.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.json("{}") }
        }) { url ->
            try {
                runBlocking {
                    val request = async(Dispatchers.Default) { JellyfinApi().validateSession(session(url)) }
                    assertTrue(arrived.await(3, TimeUnit.SECONDS))
                    request.cancel()
                    try {
                        request.await()
                        throw AssertionError("Expected cancellation")
                    } catch (_: CancellationException) { /* 取消不能被转换成普通 API 错误。 */ }
                }
            } finally { release.countDown() }
        }
    }

    private fun session(url: String) = Session(ServerConfig(url, true), "fixture-token", "user", "Alice", "device")

    private val identityHeader = "MediaBrowser Client=\"Pathfin\", Device=\"Android\", DeviceId=\"device\", Version=\"0.1.0\""

    @Test fun publicUsersAreAnonymousAndSkipIncompleteEntries() = withServer({ exchange ->
        assertEquals("/proxy/Users/Public", exchange.requestURI.path)
        assertNull("公开账号列表不得携带会话令牌", exchange.requestHeaders.getFirst("X-Emby-Token"))
        assertFalse(exchange.requestHeaders.getFirst("Authorization").orEmpty().contains("Token="))
        exchange.json("""[
            {"Id":"user-a","Name":"阿林","PrimaryImageTag":"tag-a"},
            {"Id":"user-b","Name":"yuki","PrimaryImageTag":""},
            {"Id":"","Name":"无 Id"},
            {"Id":"user-c"},
            "not-an-object"
        ]""")
    }) { url ->
        val users = runBlocking { JellyfinApi().publicUsers(ServerConfig("$url/proxy", true)) }
        assertEquals(listOf(PublicUser("user-a", "阿林", "tag-a"), PublicUser("user-b", "yuki", null)), users)
    }

    @Test fun publicUsersSurfaceServerRefusalAsApiError() = withServer({ exchange ->
        exchange.json("""{"Error":"disabled"}""", 401)
    }) { url ->
        val error = runCatching { runBlocking { JellyfinApi().publicUsers(ServerConfig(url, true)) } }.exceptionOrNull()
        assertTrue("关闭公开列表的服务器应返回可识别的接口错误：$error", error is ApiException)
    }

    @Test fun publicImageUrlsKeepProxyPathAndRejectUnsafeIdentifiers() {
        val api = JellyfinApi()
        val config = ServerConfig("https://media.example.com/jellyfin")
        val avatar = api.publicUserImageUrl(config, PublicUser("user-a", "阿林", "tag-a"), width = 96)
        assertEquals("https://media.example.com/jellyfin/Users/user-a/Images/Primary?maxWidth=96&quality=80&format=Webp&tag=tag-a", avatar)
        assertNull(api.publicUserImageUrl(config, PublicUser("user-a", "阿林", null)))
        assertNull(api.publicUserImageUrl(config, PublicUser("../admin", "坏", "tag")))
        assertEquals("https://media.example.com/jellyfin/Branding/Splashscreen?maxWidth=2048&quality=80&format=Webp",
            api.splashscreenUrl(config, width = 4000))
    }

    private fun withServer(route: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "pathfin-http-test").apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { route(exchange) } catch (error: Throwable) {
                runCatching { exchange.sendResponseHeaders(500, -1); exchange.close() }
            }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.json(value: String, code: Int = 200) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}

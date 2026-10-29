package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaIdentificationApiTest {
    @Test fun eachSupportedTypeUsesItsActualPostRouteAndSearchInfoYear() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            assertEquals("POST", exchange.requestMethod)
            paths += exchange.requestURI.path
            val body = JSONObject(exchange.requestBody.readBytes().decodeToString())
            assertEquals("item", body.getString("ItemId"))
            assertFalse(body.getBoolean("IncludeDisabledProviders"))
            val info = body.getJSONObject("SearchInfo")
            assertEquals("Synthetic", info.getString("Name"))
            assertEquals(2024, info.getInt("Year"))
            assertFalse(info.has("ProductionYear"))
            assertEquals("42", info.getJSONObject("ProviderIds").getString("Tmdb"))
            exchange.reply("[]")
        }) { session -> runBlocking {
            val api = MediaIdentificationApi()
            for (type in MediaIdentificationType.entries) {
                val item = MediaItem("item", "Synthetic", type.apiValue)
                assertTrue(MediaIdentificationApi.supports(session, item))
                assertTrue(api.search(session, item, MediaIdentificationQuery(" Synthetic ", 2024, mapOf("Tmdb" to "42"))).isEmpty())
            }
        } }
        assertEquals(MediaIdentificationType.entries.map { "/proxy/Items/RemoteSearch/${it.apiValue}" }, paths.toList())
    }

    @Test fun externalIdFieldsKeepTheServerFilteredProviderTypes() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            exchange.reply("""[{"Name":"MusicBrainz","Key":"MusicBrainzAlbum","Type":"Album"},
                {"Name":"MusicBrainz Release Group","Key":"MusicBrainzReleaseGroup","Type":"ReleaseGroup"},
                {"Name":"Duplicate","Key":"MusicBrainzAlbum","Type":"Album"},
                {"Name":"","Key":"CustomAlbum"},{"Name":"Bad key","Key":"../x"},{"Key":"Bangumi"}]""")
        }) { session -> runBlocking {
            val fields = MediaManagementApi().externalIds(session, MediaItem("item", "Synthetic album", "MusicAlbum"))
            assertEquals("编号的 Album / ReleaseGroup 用途都适用于 MusicAlbum，同键去重，空名称回退为键名，非法键略去",
                listOf(ExternalIdField("MusicBrainzAlbum", "MusicBrainz"), ExternalIdField("MusicBrainzReleaseGroup", "MusicBrainz Release Group"), ExternalIdField("CustomAlbum", "CustomAlbum"),
                    ExternalIdField("Bangumi", "Bangumi")), fields)
        } }
        assertEquals(listOf("GET /proxy/Items/item/ExternalIdInfos"), requests.toList())
    }

    @Test fun personSearchSendsEveryFilledIdWithoutAYear() {
        assertFalse("人物不按年份识别", MediaIdentificationType.PERSON.searchesByYear)
        assertFalse(MediaIdentificationType.BOX_SET.searchesByYear)
        assertTrue(MediaIdentificationType.MOVIE.searchesByYear)
        withServer({ exchange ->
            assertEquals("/proxy/Items/RemoteSearch/Person", exchange.requestURI.path)
            val info = JSONObject(exchange.requestBody.readBytes().decodeToString()).getJSONObject("SearchInfo")
            assertFalse(info.has("Year"))
            val ids = info.getJSONObject("ProviderIds")
            assertEquals(setOf("Tmdb", "Imdb", "DoubanID"), ids.keys().asSequence().toSet())
            assertEquals("27251040", ids.getString("DoubanID"))
            exchange.reply("""[{"Name":"A","ProviderIds":{"Tmdb":"1"},"ImageUrl":"https://image.tmdb.org/t/p/original/a.jpg"},
                {"Name":"B","ProviderIds":{"Tmdb":"2"},"ImageUrl":"javascript:alert(1)"},{"Name":"C","ProviderIds":{"Tmdb":"3"}}]""")
        }) { session -> runBlocking {
            val results = MediaIdentificationApi().search(session, MediaItem("item", "Synthetic actor", "Person"),
                MediaIdentificationQuery("Synthetic actor", null, mapOf("Tmdb" to "85718", "Imdb" to "nm1395602", "DoubanID" to "27251040")))
            assertEquals("结果页只显示 http(s) 图片", listOf("https://image.tmdb.org/t/p/original/a.jpg", null, null), results.map { it.imageUrl })
        } }
    }

    @Test fun applyPreservesCompleteCandidateAndSendsExplicitImageReplacementChoice() {
        val candidate = JSONObject("""{"Name":"Synthetic result","ProductionYear":2024,"ProviderIds":{"Tmdb":"42"},
            "SearchProviderName":"Synthetic Provider","Overview":"Summary","IndexNumber":2,
            "Artists":[{"Name":"Artist","ProviderIds":{"MusicBrainzArtist":"a"}}],"FutureField":{"keep":[1,true,null]}}""")
        val applies = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/proxy/Items/RemoteSearch/Movie" -> exchange.reply("[$candidate]")
                "/proxy/Items/RemoteSearch/Apply/item" -> {
                    assertEquals("POST", exchange.requestMethod)
                    val replacement = "http://localhost${exchange.requestURI}".toHttpUrl().queryParameter("replaceAllImages")
                    applies += requireNotNull(replacement)
                    assertEquals(jsonValue(candidate), jsonValue(JSONObject(exchange.requestBody.readBytes().decodeToString())))
                    exchange.reply("", 204)
                }
                else -> error("错误路由")
            }
        }) { session -> runBlocking {
            val item = MediaItem("item", "Synthetic", "Movie")
            val api = MediaIdentificationApi()
            val result = api.search(session, item, MediaIdentificationQuery("Synthetic")).single()
            assertEquals("Synthetic result", result.name)
            assertEquals(2024, result.productionYear)
            assertEquals("Summary", result.overview)
            api.apply(session, item, result)
            api.apply(session, item, result, MediaIdentificationOptions(replaceAllImages = true))
        } }
        assertEquals(listOf("false", "true"), applies.toList())
    }

    @Test fun permissionUnsupportedTypesAndInvalidQueriesCannotSendHttp() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); exchange.reply("[]") }) { admin -> runBlocking {
            val api = MediaIdentificationApi()
            val item = MediaItem("item", "Synthetic", "Movie")
            val denied = admin.copy(isAdministrator = false)
            assertFalse(MediaIdentificationApi.supports(denied, item))
            assertEquals(403, expectApiFailure { api.search(denied, item, MediaIdentificationQuery("Synthetic")); Unit }.statusCode)
            for (type in listOf("Episode", "Season", "Folder", "unknown")) {
                assertFalse(MediaIdentificationApi.supports(admin, item.copy(type = type)))
                assertThrows(IllegalArgumentException::class.java) { runBlocking { api.search(admin, item.copy(type = type), MediaIdentificationQuery("Synthetic")) } }
            }
            for (query in listOf(MediaIdentificationQuery(), MediaIdentificationQuery("name", 0),
                MediaIdentificationQuery("name", 10_000), MediaIdentificationQuery("bad\nname"),
                MediaIdentificationQuery(providerIds = mapOf("Tmdb" to "")), MediaIdentificationQuery(providerIds = mapOf("../bad" to "42")))) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { api.search(admin, item, query) } }
            }
        } }
        assertEquals(0, requests.get())
    }

    @Test fun candidateCannotBeAppliedToAnotherAccountServerItemOrType() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); exchange.reply("""[{"Name":"Synthetic","ProviderIds":{"Tmdb":"42"}}]""") }) { session -> runBlocking {
            val api = MediaIdentificationApi()
            val item = MediaItem("item", "Synthetic", "Movie")
            val result = api.search(session, item, MediaIdentificationQuery("Synthetic")).single()
            for ((other, target) in listOf(session.copy(userId = "other") to item,
                session.copy(server = session.server.copy(baseUrl = "https://other.example")) to item,
                session to item.copy(id = "another"), session to item.copy(type = "Series"))) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { api.apply(other, target, result) } }
            }
            assertEquals(403, expectApiFailure { api.apply(session.copy(isAdministrator = false), item, result) }.statusCode)
        } }
        assertEquals("仅搜索请求可以联网", 1, requests.get())
    }

    @Test fun wrongResponseShapeAndPermissionFailureStayExplicitAndRetryOnlySearches() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            assertEquals("/proxy/Items/RemoteSearch/Movie", exchange.requestURI.path)
            when (calls.incrementAndGet()) {
                1 -> exchange.reply("{\"Items\":[]}")
                2 -> exchange.reply("{\"detail\":\"synthetic-token secret\"}", 403)
                else -> exchange.reply("[]")
            }
        }) { session -> runBlocking {
            val api = MediaIdentificationApi(); val item = MediaItem("item", "Synthetic", "Movie")
            assertNotNull(expectApiFailure { api.search(session, item, MediaIdentificationQuery("Synthetic")); Unit })
            val denied = expectApiFailure { api.search(session, item, MediaIdentificationQuery("Synthetic")); Unit }
            assertEquals(403, denied.statusCode)
            assertFalse(denied.message.orEmpty().contains("synthetic-token"))
            assertTrue(api.search(session, item, MediaIdentificationQuery("Synthetic")).isEmpty())
        } }
        assertEquals(3, calls.get())
    }

    private suspend fun expectApiFailure(block: suspend () -> Unit): ApiException = try { block(); throw AssertionError("应失败") } catch (error: ApiException) { error }
    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
    private fun withServer(handler: (HttpExchange) -> Unit, block: (Session) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); val failure = AtomicReference<Throwable?>()
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                assertTrue(exchange.requestHeaders.getFirst("Authorization").orEmpty().contains("synthetic-token"))
                handler(exchange)
            } catch (error: Throwable) { failure.compareAndSet(null, error); runCatching { exchange.reply("", 500) } }
            finally { exchange.close() }
        }
        server.start()
        try { block(Session(ServerConfig("http://127.0.0.1:${server.address.port}/proxy", allowInsecureHttp = true),
            "synthetic-token", "user", "Synthetic", "device", isAdministrator = true)) }
        finally { server.stop(0); executor.shutdownNow(); failure.get()?.let { throw AssertionError("识别端点断言失败", it) } }
    }
    private fun HttpExchange.reply(text: String, code: Int = 200) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = text.toByteArray(); sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
        if (code != 204) responseBody.use { it.write(bytes) }
    }
}

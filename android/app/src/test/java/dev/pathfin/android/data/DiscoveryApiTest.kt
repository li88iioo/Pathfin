package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.MediaPage
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryApiTest {
    @Test fun recommendationsUseTheOfficialSearchWordQueryWithoutLoadingImages() = withServer({ exchange ->
        assertSession(exchange)
        assertEquals("$PREFIX/Items", exchange.requestURI.path)
        val query = exchange.query()
        assertEquals(setOf("userId", "sortBy", "includeItemTypes", "limit", "recursive",
            "imageTypeLimit", "enableImages", "enableTotalRecordCount"), query.queryParameterNames)
        assertEquals("IsFavoriteOrLiked,Random", query.queryParameter("sortBy"))
        assertEquals("Movie,Series,MusicArtist", query.queryParameter("includeItemTypes"))
        assertEquals("20", query.queryParameter("limit"))
        assertEquals("true", query.queryParameter("recursive"))
        assertEquals("0", query.queryParameter("imageTypeLimit"))
        assertEquals("false", query.queryParameter("enableImages"))
        assertEquals("false", query.queryParameter("enableTotalRecordCount"))
        exchange.json("""{"Items":[
            {"Id":"movie","Name":"电影推荐","Type":"Movie"},
            {"Id":"series","Name":"剧集推荐","Type":"Series"},
            {"Id":"artist","Name":"歌手推荐","Type":"MusicArtist"}
        ],"TotalRecordCount":0}""")
    }) { session ->
        val page = runBlocking { JellyfinApi().searchSuggestions(session) }
        assertEquals(listOf("电影推荐", "剧集推荐", "歌手推荐"), page.items.map { it.name })
        assertEquals(listOf("Movie", "Series", "MusicArtist"), page.items.map { it.type })
        assertTrue(page.items.all { it.imageTag == null })
        assertEquals(3, page.totalCount)
    }

    @Test fun libraryRecommendationsStayInsideTheLibraryTypeAndOnlyLoadWords() = withServer({ exchange ->
        assertSession(exchange)
        assertEquals("$PREFIX/Items", exchange.requestURI.path)
        val query = exchange.query()
        assertEquals("IsFavoriteOrLiked,Random", query.queryParameter("sortBy"))
        assertEquals("shows", query.queryParameter("parentId"))
        assertEquals("Series", query.queryParameter("includeItemTypes"))
        assertEquals("8", query.queryParameter("limit"))
        assertEquals("与全站推荐一致只取词", "0", query.queryParameter("imageTypeLimit"))
        assertEquals("false", query.queryParameter("enableImages"))
        assertNull(query.queryParameter("searchTerm"))
        exchange.json("""{"Items":[{"Id":"series","Name":"剧集推荐","Type":"Series"}],"TotalRecordCount":0}""")
    }) { session ->
        val page = runBlocking { JellyfinApi().searchSuggestions(session, parentId = "shows", itemTypes = setOf("Series")) }
        assertEquals(listOf("剧集推荐"), page.items.map { it.name })
    }

    @Test fun everyContentCategoryHasItsOwnTypePagingAndOptionalFavoriteRestriction() {
        val seen = CopyOnWriteArrayList<Pair<String, Boolean>>()
        withServer({ exchange ->
            assertSession(exchange)
            assertEquals("$PREFIX/Items", exchange.requestURI.path)
            val query = exchange.query()
            val type = query.queryParameter("includeItemTypes")!!
            val favorite = query.queryParameter("isFavorite") == "true"
            assertTrue(type in itemTypes.values)
            assertEquals(if (favorite) null else "片名", query.queryParameter("searchTerm"))
            assertEquals(if (favorite) "true" else null, query.queryParameter("isFavorite"))
            assertEquals("48", query.queryParameter("startIndex"))
            assertEquals("24", query.queryParameter("limit"))
            assertEquals("SortName", query.queryParameter("sortBy"))
            assertEquals("Ascending", query.queryParameter("sortOrder"))
            assertEquals("true", query.queryParameter("recursive"))
            assertEquals("true", query.queryParameter("enableUserData"))
            assertNull(query.queryParameter("parentId"))
            assertNull(query.queryParameter("filters"))
            seen += type to favorite
            val item = JSONObject().put("Id", "$type-result").put("Name", "分类结果").put("Type", type)
                .put("UserData", JSONObject().put("IsFavorite", favorite))
            exchange.json(JSONObject().put("Items", JSONArray().put(item)).put("TotalRecordCount", 57).toString())
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                for ((category, type) in itemTypes) for (favorite in listOf(false, true)) {
                    val page = api.discoveryPage(session, category, query = if (favorite) "" else " 片名 ",
                        favorite = favorite, startIndex = 48, limit = 24)
                    assertEquals(57, page.totalCount)
                    assertEquals(type, page.items.single().type)
                    assertEquals(favorite, page.items.single().isFavorite)
                }
            }
        }
        assertEquals(itemTypes.values.flatMap { type -> listOf(type to false, type to true) }, seen.toList())
    }

    @Test fun actorsReturnTheWholeMatchingSetWithoutUnsupportedPagingOrItemParameters() {
        val favoriteQueries = CopyOnWriteArrayList<String?>()
        withServer({ exchange ->
            assertSession(exchange)
            assertEquals("$PREFIX/Persons", exchange.requestURI.path)
            val query = exchange.query()
            assertEquals("演员", query.queryParameter("searchTerm"))
            assertEquals("Artist,AlbumArtist", query.queryParameter("excludePersonTypes"))
            assertEquals("true", query.queryParameter("enableUserData"))
            assertEquals("Primary", query.queryParameter("enableImageTypes"))
            for (unsupported in listOf("startIndex", "limit", "sortBy", "sortOrder", "includeItemTypes",
                "recursive", "parentId", "enableTotalRecordCount")) assertNull(unsupported, query.queryParameter(unsupported))
            favoriteQueries += query.queryParameter("isFavorite")
            exchange.json("""{"Items":[
                {"Id":"actor-a","Name":"演员甲","Type":"Person","ImageTags":{"Primary":"actor-poster"},"UserData":{"IsFavorite":true}},
                {"Id":"actor-b","Name":"演员乙","Type":"Person"},
                {"Id":"actor-c","Name":"演员丙","Type":"Person"}
            ],"TotalRecordCount":3}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                for (favorite in listOf(false, true)) {
                    val page = api.discoveryPage(session, DiscoveryCategory.PEOPLE, query = " 演员 ", favorite = favorite, limit = 1)
                    assertEquals(listOf("actor-a", "actor-b", "actor-c"), page.items.map { it.id })
                    assertEquals(3, page.totalCount)
                    assertTrue(page.items.all { it.type == "Person" })
                    assertEquals("actor-poster", page.items.first().imageTag)
                    assertTrue(page.items.first().isFavorite)
                }
            }
        }
        assertEquals(listOf(null, "true"), favoriteQueries.toList())
    }

    @Test fun actorsRejectAFakeNextPageBeforeSendingARequest() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session ->
            for (offset in listOf(-1, 24)) {
                val error = runCatching { runBlocking {
                    JellyfinApi().discoveryPage(session, DiscoveryCategory.PEOPLE, startIndex = offset)
                } }.exceptionOrNull()
                assertTrue("演员完整集合不能接受分页游标：$error", error is IllegalArgumentException)
            }
        }
        assertEquals(0, requests.get())
    }

    @Test fun artistsUseTheirOwnEndpointAndPreserveTheRealTotal() {
        val restrictions = CopyOnWriteArrayList<String?>()
        withServer({ exchange ->
            assertSession(exchange)
            assertEquals("$PREFIX/Artists", exchange.requestURI.path)
            val query = exchange.query()
            assertEquals("歌手", query.queryParameter("searchTerm"))
            assertEquals("24", query.queryParameter("startIndex"))
            assertEquals("12", query.queryParameter("limit"))
            assertEquals("true", query.queryParameter("enableTotalRecordCount"))
            assertEquals("SortName", query.queryParameter("sortBy"))
            assertEquals("Ascending", query.queryParameter("sortOrder"))
            assertEquals("true", query.queryParameter("enableUserData"))
            for (unsupported in listOf("includeItemTypes", "excludePersonTypes", "recursive", "filters", "parentId")) {
                assertNull(unsupported, query.queryParameter(unsupported))
            }
            restrictions += query.queryParameter("isFavorite")
            exchange.json("""{"Items":[{"Id":"singer","Name":"歌手","Type":"MusicArtist",
                "ImageTags":{"Primary":"artist-poster"},"UserData":{"IsFavorite":true}}],"TotalRecordCount":79}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                for (favorite in listOf(false, true)) {
                    val page = api.discoveryPage(session, DiscoveryCategory.ARTISTS, " 歌手 ", favorite, startIndex = 24, limit = 12)
                    assertEquals(79, page.totalCount)
                    assertEquals("MusicArtist", page.items.single().type)
                    assertEquals("artist-poster", page.items.single().imageTag)
                    assertTrue(page.items.single().isFavorite)
                }
            }
        }
        assertEquals(listOf(null, "true"), restrictions.toList())
    }

    @Test fun emptyCategoriesAndRecommendationsRemainEmpty() = withServer({ exchange ->
        assertSession(exchange)
        exchange.json("""{"Items":[],"TotalRecordCount":0}""")
    }) { session ->
        runBlocking {
            val api = JellyfinApi()
            for (category in DiscoveryCategory.entries) {
                assertEquals(category.name, MediaPage(emptyList(), 0), api.discoveryPage(session, category, favorite = true))
            }
            assertEquals(MediaPage(emptyList(), 0), api.searchSuggestions(session))
        }
    }

    @Test fun searchEscapesReservedCharactersWithoutLosingTheProxyPrefixOrInjectingFilters() {
        val term = " 黑镜 + /?x=1&isFavorite=false#集=%20 "
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            assertSession(exchange)
            val query = exchange.query()
            assertEquals(term.trim(), query.queryParameter("searchTerm"))
            assertEquals(listOf("true"), query.queryParameterValues("isFavorite"))
            assertNull(query.queryParameter("x"))
            assertNull(query.fragment)
            assertFalse(exchange.requestURI.rawQuery.contains("#"))
            paths += exchange.requestURI.path
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                for (category in listOf(DiscoveryCategory.MOVIES, DiscoveryCategory.PEOPLE, DiscoveryCategory.ARTISTS)) {
                    api.discoveryPage(session, category, term, favorite = true)
                }
            }
        }
        assertEquals(listOf("$PREFIX/Items", "$PREFIX/Persons", "$PREFIX/Artists"), paths.toList())
    }

    @Test fun switchingSessionsChangesBothUserScopeAndHeaderAuthentication() {
        val users = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            val userId = exchange.query().queryParameter("userId")!!
            if (userId == "user") assertSession(exchange)
            else assertSession(exchange, "second-user", "second-token", "second-device")
            users += userId
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                val second = session.copy(userId = "second-user", accessToken = "second-token", deviceId = "second-device")
                for (current in listOf(session, second)) {
                    api.searchSuggestions(current)
                    api.discoveryPage(current, DiscoveryCategory.PEOPLE, favorite = true)
                    api.discoveryPage(current, DiscoveryCategory.ARTISTS, favorite = true)
                }
            }
        }
        assertEquals(listOf("user", "user", "user", "second-user", "second-user", "second-user"), users.toList())
    }

    @Test fun artistWorksAreFilteredByArtistIdsInsteadOfPersonIds() = withServer({ exchange ->
        assertSession(exchange)
        assertEquals("$PREFIX/Items", exchange.requestURI.path)
        val query = exchange.query()
        assertEquals("artist_123", query.queryParameter("artistIds"))
        assertEquals(setOf("MusicAlbum", "Audio"), query.queryParameter("includeItemTypes")!!.split(',').toSet())
        assertEquals("true", query.queryParameter("recursive"))
        assertNull(query.queryParameter("personIds"))
        assertNull(query.queryParameter("artists"))
        exchange.json("""{"Items":[{"Id":"album","Name":"专辑","Type":"MusicAlbum"},
            {"Id":"track","Name":"曲目","Type":"Audio"}],"TotalRecordCount":2}""")
    }) { session ->
        val page = runBlocking { JellyfinApi().items(session,
            options = LibraryQuery(artistId = "artist_123", itemTypes = setOf("MusicAlbum", "Audio"))) }
        assertEquals(listOf("MusicAlbum", "Audio"), page.items.map { it.type })
        assertEquals(2, page.totalCount)
    }

    @Test fun forbiddenDiscoveryRequestsSurfaceAnErrorInsteadOfAnEmptyGroup() = withServer({ exchange ->
        assertSession(exchange)
        exchange.json("""{"detail":"Access denied"}""", 403)
    }) { session ->
        runBlocking {
            val api = JellyfinApi()
            val requests: List<suspend () -> MediaPage> = listOf(
                { api.searchSuggestions(session) },
                { api.discoveryPage(session, DiscoveryCategory.MOVIES) },
                { api.discoveryPage(session, DiscoveryCategory.PEOPLE) },
                { api.discoveryPage(session, DiscoveryCategory.ARTISTS) },
            )
            for (request in requests) {
                val error = runCatching { request() }.exceptionOrNull()
                assertTrue("权限失败必须保留为 API 错误：$error", error is ApiException)
                assertEquals(403, (error as ApiException).statusCode)
            }
        }
    }

    private fun assertSession(exchange: HttpExchange, userId: String = "user", token: String = "fixture-token", deviceId: String = "device") {
        assertEquals("GET", exchange.requestMethod)
        assertEquals(userId, exchange.query().queryParameter("userId"))
        val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        assertTrue(authorization.startsWith("MediaBrowser "))
        assertTrue(authorization.contains("DeviceId=\"$deviceId\""))
        assertTrue(authorization.contains("Token=\"$token\""))
        assertNull(exchange.requestHeaders.getFirst("X-Emby-Authorization"))
        assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
        assertFalse(exchange.requestURI.toString().contains(token))
    }

    private fun HttpExchange.query(): HttpUrl = "http://localhost${requestURI}".toHttpUrl()

    private fun withServer(route: (HttpExchange) -> Unit, block: (Session) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "pathfin-discovery-http-test").apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { route(exchange) } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching { exchange.sendResponseHeaders(500, -1); exchange.close() }
            }
        }
        server.start()
        try {
            block(Session(ServerConfig("http://127.0.0.1:${server.address.port}$PREFIX/", true),
                "fixture-token", "user", "Alice", "device"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
            failure.get()?.let { throw AssertionError("Discovery fixture contract assertion failed", it) }
        }
    }

    private fun HttpExchange.json(value: String, code: Int = 200) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private companion object {
        const val PREFIX = "/proxy/jellyfin"
        val itemTypes = linkedMapOf(
            DiscoveryCategory.MOVIES to "Movie", DiscoveryCategory.SERIES to "Series",
            DiscoveryCategory.COLLECTIONS to "BoxSet", DiscoveryCategory.EPISODES to "Episode",
            DiscoveryCategory.SEASONS to "Season", DiscoveryCategory.ALBUMS to "MusicAlbum",
            DiscoveryCategory.MUSIC to "Audio", DiscoveryCategory.PROGRAMS to "Program",
        )
    }
}

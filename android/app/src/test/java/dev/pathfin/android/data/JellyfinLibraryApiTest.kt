package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.*
import java.io.IOException
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JellyfinLibraryApiTest {
    @Test fun folderRecommendationsKeepAllMediaTypesWithinTheirParent() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("folder", query.queryParameter("parentId"))
        assertEquals("true", query.queryParameter("recursive"))
        assertEquals("8", query.queryParameter("limit"))
        assertNull(query.queryParameter("includeItemTypes"))
        exchange.json("""{"Items":[{"Id":"audio","Name":"Track","Type":"Audio"},{"Id":"episode","Name":"Episode","Type":"Episode"}],"TotalRecordCount":2}""")
    }) { session ->
        val page = runBlocking { JellyfinApi().searchSuggestions(session, parentId = "folder") }
        assertEquals(listOf("Audio", "Episode"), page.items.map { it.type })
    }

    @Test fun latestUsesGroupedArrayWithoutMistakingNewEpisodeCountForSeasonCount() = withServer({ exchange ->
        assertHomeRequest(exchange, "/proxy/Items/Latest", "library", "12")
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("true", query.queryParameter("groupItems"))
        assertNull(query.queryParameter("startIndex"))
        assertNull(query.queryParameter("sortBy"))
        assertNull(query.queryParameter("recursive"))
        assertNull(query.queryParameter("enableTotalRecordCount"))
        exchange.json("""[{"Id":"show","Type":"Series","Name":"Grouped show","ChildCount":3,"ProductionYear":2022,"EndDate":"2025-12-31T00:00:00Z","ImageTags":{"Primary":"cover"},"UserData":{"IsFavorite":true}},
            {"Id":"season","Type":"Season","Name":"Season 1","ChildCount":17}]""")
    }) { session ->
        runBlocking {
            val page = JellyfinApi().latestItems(session, "library")
            assertEquals(2, page.totalCount)
            val series = page.items.first()
            assertEquals("show", series.id)
            assertEquals("Series", series.type)
            assertNull("Latest 的三条新增内容不代表三季", series.childCount)
            assertEquals(2022, series.year)
            assertEquals(2025, series.endYear)
            assertEquals(17, page.items.last().childCount)
            assertEquals("cover", series.imageTag)
            assertTrue(series.isFavorite)
        }
    }

    @Test fun latestRootSupportsEmptyArrayAndBoundsRequestedLimit() {
        val limits = CopyOnWriteArrayList<String?>()
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            limits += query.queryParameter("limit")
            assertEquals("/proxy/Items/Latest", exchange.requestURI.path)
            assertNull(query.queryParameter("parentId"))
            assertEquals("true", query.queryParameter("groupItems"))
            exchange.json("[]")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                assertEquals(MediaPage(emptyList(), 0), api.latestItems(session, limit = 0))
                assertEquals(MediaPage(emptyList(), 0), api.latestItems(session, limit = Int.MAX_VALUE))
            }
        }
        assertEquals(listOf("1", "100"), limits.toList())
    }

    @Test fun latestRejectsWrongRootInvalidEntriesAndTrailingJsonWithoutFallback() {
        val bodies = listOf(
            """{"Items":[],"TotalRecordCount":0,"detail":"fixture-token"}""",
            "null", "", "[null]", "[42]", "[\"fixture-token\"]", "[{}]", """[{"Id":""}]""",
            """[{"Id":"valid"}] {"detail":"fixture-token"}""",
        )
        val requests = AtomicInteger()
        withServer({ exchange ->
            assertEquals("/proxy/Items/Latest", exchange.requestURI.path)
            exchange.json(bodies[requests.getAndIncrement()])
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                bodies.forEach {
                    val error = expectApiFailure { api.latestItems(session) }
                    assertNull(error.statusCode)
                    assertFalse(error.message.orEmpty().contains("fixture-token"))
                }
            }
        }
        assertEquals("每个错误仅请求一次专用接口，不应回退通用 Items", bodies.size, requests.get())
    }

    @Test fun latestRejectsMoreRowsThanRequestedInsteadOfTruncatingResponse() = withServer({ exchange ->
        assertEquals("1", "http://localhost${exchange.requestURI}".toHttpUrl().queryParameter("limit"))
        exchange.json("""[{"Id":"one"},{"Id":"two"}]""")
    }) { session ->
        runBlocking {
            assertTrue(expectApiFailure { JellyfinApi().latestItems(session, limit = 1) }.message.orEmpty().contains("超过请求范围"))
        }
    }

    @Test fun latestRejectsOversizedBodyBeforeJsonMapping() {
        val oversized = "[{\"Id\":\"large\",\"Name\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}]"
        withServer({ exchange ->
            try { exchange.json(oversized) } catch (_: IOException) { exchange.close() }
        }) { session ->
            runBlocking {
                assertTrue(expectApiFailure { JellyfinApi().latestItems(session) }.message.orEmpty().contains("数据过大"))
            }
        }
    }

    @Test fun resumeUsesDedicatedHomeEndpointWithoutCountAndRetainsPlaybackData() = withServer({ exchange ->
        assertHomeRequest(exchange, "/proxy/UserItems/Resume", "library", "12")
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("false", query.queryParameter("enableTotalRecordCount"))
        assertEquals("Video", query.queryParameter("mediaTypes"))
        assertNull(query.queryParameter("includeItemTypes"))
        assertNull(query.queryParameter("filters"))
        assertNull(query.queryParameter("recursive"))
        assertNull(query.queryParameter("sortBy"))
        assertNull(query.queryParameter("startIndex"))
        exchange.json("""{"Items":[{"Id":"episode","Type":"Episode","Name":"Continue","RunTimeTicks":6000000000,"SeriesName":"Show","ImageTags":{"Primary":"poster"},"UserData":{"PlaybackPositionTicks":1200000000,"Played":false}}],"TotalRecordCount":1,"StartIndex":0}""")
    }) { session ->
        runBlocking {
            val page = JellyfinApi().resumeItems(session, "library")
            assertEquals(1, page.totalCount)
            val item = page.items.single()
            assertEquals(1_200_000_000L, item.positionTicks)
            assertEquals(6_000_000_000L, item.runTimeTicks)
            assertEquals("Show", item.seriesName)
            assertEquals("poster", item.imageTag)
            assertFalse(item.played)
        }
    }

    @Test fun resumeRootBoundsLimitAndDoesNotInventResultsForEmptyPage() {
        val limits = CopyOnWriteArrayList<String?>()
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            limits += query.queryParameter("limit")
            assertEquals("/proxy/UserItems/Resume", exchange.requestURI.path)
            assertNull(query.queryParameter("parentId"))
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                assertEquals(MediaPage(emptyList(), 0), api.resumeItems(session, limit = -1))
                assertEquals(MediaPage(emptyList(), 0), api.resumeItems(session, limit = Int.MAX_VALUE))
            }
        }
        assertEquals(listOf("1", "100"), limits.toList())
    }

    @Test fun resumeRejectsMalformedPageInsteadOfTreatingErrorObjectAsEmptyLibrary() {
        val bodies = listOf("[]", "{}", """{"detail":"fixture-token"}""", """{"Items":{}}""", """{"Items":[null]}""", """{"Items":[{}]}""")
        val requests = AtomicInteger()
        withServer({ exchange ->
            assertEquals("/proxy/UserItems/Resume", exchange.requestURI.path)
            exchange.json(bodies[requests.getAndIncrement()])
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                bodies.forEach {
                    assertFalse(expectApiFailure { api.resumeItems(session) }.message.orEmpty().contains("fixture-token"))
                }
            }
        }
        assertEquals(bodies.size, requests.get())
    }

    @Test fun newHomeEndpointsSurfaceHttpErrorsWithoutBodyOrCompatibilityRetry() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestURI.path
            exchange.json("""{"detail":"fixture-token signed-private-media"}""", if (exchange.requestURI.path.endsWith("Latest")) 404 else 401)
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                assertEquals(404, expectApiFailure { api.latestItems(session) }.statusCode)
                val expired = expectApiFailure { api.resumeItems(session) }
                assertEquals(401, expired.statusCode)
                assertFalse(expired.message.orEmpty().contains("fixture-token"))
                assertFalse(expired.message.orEmpty().contains("signed-private-media"))
            }
        }
        assertEquals(listOf("/proxy/Items/Latest", "/proxy/UserItems/Resume"), requests.toList())
    }

    @Test fun ordinaryLibraryPagingRetainsOverviewAndAccurateTotal() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Items", exchange.requestURI.path)
        assertEquals("40", query.queryParameter("startIndex"))
        assertEquals("40", query.queryParameter("limit"))
        assertNotEquals("false", query.queryParameter("enableTotalRecordCount"))
        assertTrue(query.queryParameter("fields")!!.split(',').contains("Overview"))
        exchange.json("""{"Items":[{"Id":"episode","Type":"Episode","Overview":"Keep episode synopsis"}],"TotalRecordCount":91}""")
    }) { session ->
        runBlocking {
            val page = JellyfinApi().items(session, parentId = "library", startIndex = 40)
            assertEquals(91, page.totalCount)
            assertEquals("Keep episode synopsis", page.items.single().overview)
        }
    }

    @Test fun seriesStartUsesSeasonAndEpisodeOrderInsteadOfAlphabeticalTitles() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("ParentIndexNumber,IndexNumber", query.queryParameter("sortBy"))
        assertEquals("Ascending", query.queryParameter("sortOrder"))
        assertEquals("Episode", query.queryParameter("includeItemTypes"))
        assertEquals("1", query.queryParameter("limit"))
        exchange.json("""{"Items":[],"TotalRecordCount":0}""")
    }) { session ->
        runBlocking { JellyfinApi().items(session, parentId = "series", limit = 1,
            options = LibraryQuery(itemTypes = setOf("Episode"), episodeOrder = true, descending = false)) }
    }

    @Test fun mixedDirectoryPagesKeepFoldersAndOpeningThemRetainsEveryDirectChildType() {
        val requests = CopyOnWriteArrayList<Pair<String?, Int>>()
        withServer({ exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("/proxy/Items", exchange.requestURI.path)
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            val parent = query.queryParameter("parentId")
            val start = requireNotNull(query.queryParameter("startIndex")).toInt()
            requests += parent to start
            assertEquals("false", query.queryParameter("recursive"))
            if (parent == "mixed") {
                assertEquals("Folder,Movie,Series", query.queryParameter("includeItemTypes"))
                assertEquals("2", query.queryParameter("limit"))
                exchange.json(when (start) {
                    0 -> """{"Items":[{"Id":"folder","Name":"Folder","Type":"Folder"},{"Id":"movie","Type":"Movie"}],"TotalRecordCount":3}"""
                    2 -> """{"Items":[{"Id":"series","Type":"Series"}],"TotalRecordCount":3}"""
                    else -> error("Unexpected directory cursor")
                })
            } else {
                assertEquals("folder", parent)
                assertNull(query.queryParameter("includeItemTypes"))
                assertEquals("SortName", query.queryParameter("sortBy"))
                assertEquals("Ascending", query.queryParameter("sortOrder"))
                exchange.json("""{"Items":[{"Id":"episode","Type":"Episode"},{"Id":"audio","Type":"Audio"},{"Id":"nested","Type":"Folder"}],"TotalRecordCount":3}""")
            }
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                val library = MediaItem("mixed", "Mixed", "CollectionFolder")
                val options = library.defaultLibraryQuery()
                val first = api.items(session, parentId = library.id, limit = 2, options = options)
                val second = api.items(session, parentId = library.id, startIndex = first.items.size, limit = 2, options = options)
                assertEquals(3, first.totalCount)
                assertEquals(3, second.totalCount)
                assertEquals(listOf("Folder", "Movie", "Series"), (first.items + second.items).map { it.type })
                val folder = first.items.first()
                val children = api.items(session, parentId = folder.id, options = folder.defaultLibraryQuery())
                assertEquals(3, children.totalCount)
                assertEquals(listOf("Episode", "Audio", "Folder"), children.items.map { it.type })
            }
        }
        assertEquals(listOf("mixed" to 0, "mixed" to 2, "folder" to 0), requests.toList())
    }

    @Test fun mixedMediaCollectionsAndFavoritesRetainTheirDistinctScopes() {
        val tabs = listOf(LibraryTab.MEDIA, LibraryTab.COLLECTIONS, LibraryTab.FAVORITES)
        val requests = AtomicInteger()
        withServer({ exchange ->
            assertEquals("GET", exchange.requestMethod)
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("mixed", query.queryParameter("parentId"))
            assertEquals("true", query.queryParameter("recursive"))
            when (tabs[requests.getAndIncrement()]) {
                LibraryTab.MEDIA -> assertEquals("Movie,Series", query.queryParameter("includeItemTypes"))
                LibraryTab.COLLECTIONS -> assertEquals("BoxSet", query.queryParameter("includeItemTypes"))
                LibraryTab.FAVORITES -> {
                    assertEquals("true", query.queryParameter("isFavorite"))
                    assertEquals("Folder,Movie,Series", query.queryParameter("includeItemTypes"))
                }
                else -> Unit
            }
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                val library = MediaItem("mixed", "Mixed", "CollectionFolder")
                tabs.forEach { api.items(session, parentId = library.id, options = library.defaultLibraryQuery().copy(tab = it)) }
            }
        }
        assertEquals(tabs.size, requests.get())
    }

    @Test fun mixedLibrarySearchTraversesDescendantsWithoutSearchingFolderRows() = withServer({ exchange ->
        assertEquals("GET", exchange.requestMethod)
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("mixed", query.queryParameter("parentId"))
        assertEquals("true", query.queryParameter("recursive"))
        assertEquals("Movie,Series", query.queryParameter("includeItemTypes"))
        assertEquals("目标", query.queryParameter("searchTerm"))
        exchange.json("""{"Items":[{"Id":"nested-movie","Name":"目标","Type":"Movie"}],"TotalRecordCount":1}""")
    }) { session ->
        runBlocking {
            val library = MediaItem("mixed", "Mixed", "CollectionFolder")
            val page = JellyfinApi().items(session, parentId = library.id, options = library.librarySearchQuery(" 目标 "))
            assertEquals("nested-movie", page.items.single().id)
        }
    }

    @Test fun ordinaryFolderSearchDoesNotInheritTheGlobalVideoTypeRestriction() = withServer({ exchange ->
        assertEquals("GET", exchange.requestMethod)
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("folder", query.queryParameter("parentId"))
        assertEquals("true", query.queryParameter("recursive"))
        assertNull(query.queryParameter("includeItemTypes"))
        assertEquals("目标", query.queryParameter("searchTerm"))
        exchange.json("""{"Items":[{"Id":"episode","Type":"Episode"},{"Id":"audio","Type":"Audio"},{"Id":"video","Type":"Video"}],"TotalRecordCount":3}""")
    }) { session ->
        runBlocking {
            val folder = MediaItem("folder", "Folder", "Folder")
            val page = JellyfinApi().items(session, parentId = folder.id, options = folder.librarySearchQuery("目标"))
            assertEquals(listOf("Episode", "Audio", "Video"), page.items.map { it.type })
        }
    }

    @Test fun libraryFiltersUseDocumentedSeparatorsAndRetainFalseFilters() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Items", exchange.requestURI.path)
        assertEquals("library", query.queryParameter("parentId"))
        assertEquals("true", query.queryParameter("recursive"))
        assertEquals("DateLastContentAdded,SortName", query.queryParameter("sortBy"))
        assertEquals("Ascending", query.queryParameter("sortOrder"))
        assertEquals("false", query.queryParameter("isPlayed"))
        assertEquals("false", query.queryParameter("isFavorite"))
        assertEquals("Continuing,Ended", query.queryParameter("seriesStatus"))
        assertEquals("Drama|科幻", query.queryParameter("genres"))
        assertEquals("4K|Dolby Vision", query.queryParameter("tags"))
        assertEquals("PG-13|TV-MA", query.queryParameter("officialRatings"))
        assertEquals("2024,2026", query.queryParameter("years"))
        assertEquals("Movie,Series", query.queryParameter("includeItemTypes"))
        assertEquals("person", query.queryParameter("personIds"))
        assertEquals("studio", query.queryParameter("studioIds"))
        assertEquals("genre", query.queryParameter("genreIds"))
        assertNotNull(exchange.requestHeaders.getFirst("Authorization"))
        assertFalse(exchange.requestURI.toString().contains("fixture-token"))
        exchange.json("""{"Items":[],"TotalRecordCount":0}""")
    }) { session ->
        runBlocking {
            val result = JellyfinApi().items(session, parentId = "library", options = LibraryQuery(
                sort = LibrarySort.DATE_LAST_CONTENT_ADDED, descending = false, played = false, favorite = false,
                seriesStatus = setOf("Ended", "Continuing"), genres = setOf("科幻", "Drama"), tags = setOf("Dolby Vision", "4K"),
                years = setOf(2026, 2024), officialRatings = setOf("TV-MA", "PG-13"), itemTypes = setOf("Series", "Movie"),
                personId = "person", studioId = "studio", genreId = "genre",
            ))
            assertEquals(0, result.totalCount)
        }
    }

    @Test fun alphabetFiltersKeepScopeAndPagingWhileDigitRangeIncludesOneThroughNine() {
        val names = listOf("# Symbol", "007", "1984", "2 Fast", "9 Lives", "Alpha", "Arrival", "Zodiac")
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("/proxy/Items", exchange.requestURI.path)
            assertEquals("movies-a", query.queryParameter("parentId"))
            assertEquals("Movie", query.queryParameter("includeItemTypes"))
            assertEquals("CommunityRating", query.queryParameter("sortBy"))
            assertEquals("Ascending", query.queryParameter("sortOrder"))
            assertEquals("false", query.queryParameter("isPlayed"))
            assertEquals("false", query.queryParameter("isFavorite"))
            assertEquals("4K", query.queryParameter("tags"))
            assertEquals("Drama", query.queryParameter("genres"))
            assertEquals("2024", query.queryParameter("years"))
            assertEquals("A & B", query.queryParameter("searchTerm"))
            assertEquals("2", query.queryParameter("limit"))
            val prefix = query.queryParameter("nameStartsWith")
            val lower = query.queryParameter("nameStartsWithOrGreater")
            val upper = query.queryParameter("nameLessThan")
            val selected = when {
                lower != null -> {
                    assertEquals("0", lower); assertEquals(":", upper); assertNull(prefix)
                    names.filter { it >= "0" && it < ":" }
                }
                upper != null -> {
                    assertEquals("A", upper); assertNull(prefix)
                    names.filter { it.lowercase() < "a" }
                }
                prefix != null -> {
                    assertTrue(prefix in listOf("A", "Z"))
                    names.filter { it.startsWith(prefix, ignoreCase = true) }
                }
                else -> names
            }
            val start = requireNotNull(query.queryParameter("startIndex")).toInt()
            val rows = org.json.JSONArray().apply {
                selected.drop(start).take(2).forEach { put(JSONObject().put("Id", "movie-${names.indexOf(it)}").put("Name", it).put("Type", "Movie")) }
            }
            exchange.json(JSONObject().put("Items", rows).put("TotalRecordCount", selected.size).toString())
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                val base = LibraryQuery(itemTypes = setOf("Movie"), sort = LibrarySort.COMMUNITY_RATING, descending = false,
                    played = false, favorite = false, tags = setOf("4K"), genres = setOf("Drama"), years = setOf(2024), searchTerm = "A & B")
                for ((initial, expected) in listOf(
                    "0" to listOf("007", "1984", "2 Fast", "9 Lives"), "#" to names.take(5),
                    "A" to listOf("Alpha", "Arrival"), "Z" to listOf("Zodiac"), null to names,
                )) {
                    val actual = mutableListOf<String>()
                    for (start in expected.indices step 2) {
                        val page = api.items(session, "movies-a", startIndex = start, limit = 2, options = base.copy(nameInitial = initial))
                        assertEquals(expected.size, page.totalCount)
                        actual += page.items.map { it.name }
                    }
                    assertEquals(expected, actual)
                }
            }
        }
    }

    @Test fun genreAlphabetUsesTheDocumentedNamesEndpointAndRetainsParentAndCursor() {
        val observed = CopyOnWriteArrayList<List<String?>>()
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("/proxy/Genres", exchange.requestURI.path)
            assertEquals("library", query.queryParameter("parentId"))
            assertEquals("40", query.queryParameter("startIndex"))
            assertEquals("20", query.queryParameter("limit"))
            assertEquals("SortName", query.queryParameter("sortBy"))
            assertEquals("Ascending", query.queryParameter("sortOrder"))
            observed += listOf(query.queryParameter("nameStartsWith"), query.queryParameter("nameStartsWithOrGreater"), query.queryParameter("nameLessThan"))
            exchange.json("""{"Items":[{"Id":"genre","Name":"Comedy","Type":"Genre"}],"TotalRecordCount":41}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                for (initial in listOf("0", "#", "C", null)) {
                    val page = api.items(session, "library", startIndex = 40, limit = 20,
                        options = LibraryQuery(tab = LibraryTab.GENRES, sort = LibrarySort.TITLE, descending = false, nameInitial = initial))
                    assertEquals("Genre", page.items.single().type)
                    assertEquals(41, page.totalCount)
                }
            }
        }
        assertEquals(listOf(listOf(null, "0", ":"), listOf(null, null, "A"), listOf("C", null, null), listOf(null, null, null)), observed.toList())
    }

    @Test fun invalidAlphabetInputIsRejectedBeforeAnyItemsOrGenreHttpRequest() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                for (initial in listOf("", "a", "1", "AA", "中", "A&parentId=other", "A\u0000")) {
                    for (tab in listOf(LibraryTab.ALL, LibraryTab.GENRES)) {
                        val failure = runCatching { api.items(session, "library", options = LibraryQuery(tab = tab, nameInitial = initial)) }.exceptionOrNull()
                        assertTrue("非法首字母必须显式拒绝", failure is IllegalArgumentException)
                    }
                }
            }
        }
        assertEquals(0, requests.get())
    }

    @Test fun personNameFiltersSurviveTheEndpointParameterWhitelist() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Persons", exchange.requestURI.path)
        assertEquals("P", query.queryParameter("nameStartsWith"))
        assertEquals("true", query.queryParameter("isFavorite"))
        assertNull(query.queryParameter("includeItemTypes"))
        assertNull(query.queryParameter("sortBy"))
        exchange.json("""{"Items":[{"Id":"actor","Name":"Person","Type":"Person"}],"TotalRecordCount":1}""")
    }) { session ->
        runBlocking { assertEquals("actor", JellyfinApi().items(session,
            options = LibraryQuery(itemTypes = setOf("Person"), favorite = true, nameInitial = "P")).items.single().id) }
    }

    @Test fun movieLibrarySearchKeepsParentTypesAndEncodedTermAcrossActualHttpPages() {
        val starts = CopyOnWriteArrayList<Int>()
        val term = "星际 & parentId=other+100% /?#"
        withServer({ exchange ->
            assertEquals("/proxy/Items", exchange.requestURI.path)
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals(listOf("movies-a"), query.queryParameterValues("parentId"))
            assertEquals(listOf(term), query.queryParameterValues("searchTerm"))
            assertEquals("Movie", query.queryParameter("includeItemTypes"))
            assertEquals("true", query.queryParameter("recursive"))
            assertEquals("2", query.queryParameter("limit"))
            assertNull(query.queryParameter("genres"))
            assertNull(query.queryParameter("filters"))
            assertNull(query.queryParameter("isFavorite"))
            assertFalse("搜索词必须作为编码后的参数而不是新查询结构", exchange.requestURI.rawQuery.contains("星际"))
            val start = requireNotNull(query.queryParameter("startIndex")).toInt().also(starts::add)
            exchange.json(when (start) {
                0 -> """{"Items":[{"Id":"a-1","Name":"First","Type":"Movie"},{"Id":"a-2","Name":"Second","Type":"Movie"}],"TotalRecordCount":3}"""
                2 -> """{"Items":[{"Id":"a-3","Name":"Third","Type":"Movie"}],"TotalRecordCount":3}"""
                else -> error("Unexpected search page")
            })
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                val library = MediaItem("movies-a", "Movies A", "CollectionFolder", collectionType = "movies")
                val query = library.librarySearchQuery(" $term ")
                val first = api.items(session, parentId = library.id, limit = 2, options = query)
                val second = api.items(session, parentId = library.id, startIndex = first.items.size, limit = 2, options = query)
                assertEquals(3, first.totalCount)
                assertEquals(3, second.totalCount)
                assertEquals(listOf("a-1", "a-2", "a-3"), (first.items + second.items).map { it.id })
            }
        }
        assertEquals(listOf(0, 2), starts.toList())
    }

    @Test fun televisionSearchQueryCannotBeOverriddenByTheLegacyGlobalTerm() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Items", exchange.requestURI.path)
        assertEquals("tv-only", query.queryParameter("parentId"))
        assertEquals("Series", query.queryParameter("includeItemTypes"))
        assertEquals("目标", query.queryParameter("searchTerm"))
        assertEquals("40", query.queryParameter("startIndex"))
        assertEquals("SortName", query.queryParameter("sortBy"))
        assertEquals("Ascending", query.queryParameter("sortOrder"))
        exchange.json("""{"Items":[],"TotalRecordCount":40}""")
    }) { session ->
        runBlocking {
            val library = MediaItem("tv-only", "TV", "CollectionFolder", collectionType = "tvshows")
            JellyfinApi().items(session, parentId = library.id, query = "unrelated global term", startIndex = 40,
                options = library.librarySearchQuery(" 目标 "))
        }
    }

    @Test fun legacyGlobalSearchStillUsesItsTermWithoutInventingALibraryParent() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("全站", query.queryParameter("searchTerm"))
        assertNull(query.queryParameter("parentId"))
        assertEquals("Movie,Series,Episode", query.queryParameter("includeItemTypes"))
        assertEquals("SortName", query.queryParameter("sortBy"))
        exchange.json("""{"Items":[],"TotalRecordCount":0}""")
    }) { session -> runBlocking { JellyfinApi().items(session, query = " 全站 ") } }

    @Test fun favoriteAndPlayedMutationsUseCurrentEndpointsAndRealDeleteRequests() {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        withServer({ exchange ->
            val path = exchange.requestURI.path
            requests += exchange.requestMethod to path
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("user", query.queryParameter("userId"))
            assertNotNull(exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
            val enabled = exchange.requestMethod == "POST"
            exchange.json("""{"IsFavorite":$enabled,"Played":$enabled,"PlaybackPositionTicks":0,"PlayCount":0}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                assertEquals(true, api.setFavorite(session, "film", true).isFavorite)
                assertEquals(false, api.setFavorite(session, "film", false).isFavorite)
                assertEquals(true, api.setPlayed(session, "film", true).played)
                val updated = api.setPlayed(session, "film", false).applyTo(MediaItem("film", "Film", "Movie", played = true, positionTicks = 900))
                assertFalse(updated.played)
                assertEquals(0L, updated.positionTicks)
            }
        }
        assertEquals(listOf(
            "POST" to "/proxy/UserFavoriteItems/film", "DELETE" to "/proxy/UserFavoriteItems/film",
            "POST" to "/proxy/UserPlayedItems/film", "DELETE" to "/proxy/UserPlayedItems/film",
        ), requests.toList())
    }

    @Test fun rejectedMutationDoesNotReturnInventedUserDataOrExposeBody() = withServer({ exchange ->
        exchange.json("""{"detail":"fixture-token signed-secret"}""", 403)
    }) { session ->
        runBlocking {
            val error = try { JellyfinApi().setFavorite(session, "film", true); throw AssertionError("Expected rejection") }
                catch (error: ApiException) { error }
            assertEquals(403, error.statusCode)
            assertFalse(error.message.orEmpty().contains("fixture-token"))
            assertFalse(error.message.orEmpty().contains("signed-secret"))
        }
    }

    @Test fun publicProbeHasNoIdentityAndPasswordChangeUsesPlainTextContractWithoutTrimming() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            paths += exchange.requestURI.path
            if (exchange.requestURI.path.endsWith("System/Info/Public")) {
                assertEquals("GET", exchange.requestMethod)
                assertNull(exchange.requestHeaders.getFirst("Authorization"))
                assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
                exchange.json("""{"Id":"server-id","ServerName":"My Library","Version":"12.3.0"}""")
            } else {
                assertEquals("POST", exchange.requestMethod)
                assertEquals("/proxy/Users/Password", exchange.requestURI.path)
                assertEquals("user", "http://localhost${exchange.requestURI}".toHttpUrl().queryParameter("userId"))
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                assertEquals(setOf("CurrentPw", "NewPw", "ResetPassword"), body.keys().asSequence().toSet())
                assertEquals(" old password ", body.getString("CurrentPw"))
                assertEquals(" new password ", body.getString("NewPw"))
                assertFalse(body.getBoolean("ResetPassword"))
                exchange.sendResponseHeaders(204, -1); exchange.close()
            }
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                assertEquals(PublicServerInfo("server-id", "My Library", "12.3.0"), api.publicServerInfo(session.server))
                api.changePassword(session, " old password ", " new password ")
            }
        }
        assertEquals(2, paths.size)
    }

    @Test fun filterOptionsComeFromFullServerFilterEndpoint() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Items/Filters", exchange.requestURI.path)
        assertEquals("library", query.queryParameter("parentId"))
        assertEquals("Movie,Series", query.queryParameter("includeItemTypes"))
        exchange.json("""{"Genres":["Drama","Comedy","Drama"],"Tags":["4K"],"Years":[2024,2026,2026],"OfficialRatings":["TV-MA","PG"]}""")
    }) { session ->
        runBlocking {
            assertEquals(LibraryFilterOptions(listOf("Comedy", "Drama"), listOf("4K"), listOf(2026, 2024), listOf("PG", "TV-MA")),
                JellyfinApi().filterOptions(session, "library", setOf("Series", "Movie")))
        }
    }

    @Test fun nextUpAndSimilarUseSeparateDocumentedQueryContracts() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            paths += exchange.requestURI.path
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("user", query.queryParameter("userId"))
            if (exchange.requestURI.path.endsWith("NextUp")) {
                assertEquals("series", query.queryParameter("seriesId"))
                assertEquals("false", query.queryParameter("enableResumable"))
                assertEquals("true", query.queryParameter("enableUserData"))
                assertEquals("PrimaryImageAspectRatio", query.queryParameter("fields"))
                assertEquals("false", query.queryParameter("enableTotalRecordCount"))
            } else {
                assertTrue(query.queryParameter("fields")!!.split(',').contains("Overview"))
                assertNull(query.queryParameter("enableTotalRecordCount"))
                assertNull(query.queryParameter("enableImageTypes"))
                assertNull(query.queryParameter("enableUserData"))
            }
            exchange.json("""{"Items":[{"Id":"episode","Type":"Episode","Name":"Next"}],"TotalRecordCount":1}""")
        }) { session ->
            runBlocking {
                val api = JellyfinApi()
                assertEquals("episode", api.nextUp(session, "series").items.single().id)
                assertEquals("episode", api.similar(session, "series").items.single().id)
            }
        }
        assertEquals(listOf("/proxy/Shows/NextUp", "/proxy/Items/series/Similar"), paths.toList())
    }

    @Test fun personFavoritesUsePersonsEndpointWithoutUnsupportedItemParameters() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Persons", exchange.requestURI.path)
        assertEquals("true", query.queryParameter("isFavorite"))
        assertNull(query.queryParameter("sortBy"))
        assertNull(query.queryParameter("includeItemTypes"))
        assertNull(query.queryParameter("recursive"))
        exchange.json("""{"Items":[{"Id":"person","Name":"Actor","Type":"Person","UserData":{"IsFavorite":true}}],"TotalRecordCount":1}""")
    }) { session ->
        runBlocking { assertTrue(JellyfinApi().items(session, options = LibraryQuery(favorite = true, itemTypes = setOf("Person"))).items.single().isFavorite) }
    }

    @Test fun genresTabRequestsActualGenreRows() = withServer({ exchange ->
        assertEquals("/proxy/Genres", exchange.requestURI.path)
        assertEquals("library", "http://localhost${exchange.requestURI}".toHttpUrl().queryParameter("parentId"))
        exchange.json("""{"Items":[{"Id":"genre","Name":"Drama","Type":"Genre"}],"TotalRecordCount":1}""")
    }) { session ->
        runBlocking { assertEquals("Genre", JellyfinApi().items(session, parentId = "library", options = LibraryQuery(tab = LibraryTab.GENRES)).items.single().type) }
    }

    @Test fun upcomingUsesServerScheduleWithPagingAndDoesNotLeakInactiveFilters() {
        withServer({ exchange ->
            assertEquals("/proxy/Shows/Upcoming", exchange.requestURI.path)
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("tv", query.queryParameter("parentId"))
            assertEquals("40", query.queryParameter("startIndex"))
            assertEquals("40", query.queryParameter("limit"))
            assertTrue(query.queryParameter("fields")!!.split(',').contains("AirTime"))
            for (key in listOf("minPremiereDate", "includeItemTypes", "sortBy", "sortOrder", "recursive", "nameStartsWith", "tags", "years", "isPlayed")) {
                assertNull(key, query.queryParameter(key))
            }
            exchange.json("""{"Items":[{"Id":"future","Type":"Episode","PremiereDate":"2030-01-01T00:00:00Z"}],"TotalRecordCount":81}""")
        }) { session ->
            runBlocking {
                val page = JellyfinApi().items(session, parentId = "tv", startIndex = 40, limit = 40,
                    options = LibraryQuery(tab = LibraryTab.UPCOMING, itemTypes = setOf("Series"), tags = setOf("4K"),
                        years = setOf(2026), nameInitial = "A", played = false))
                assertEquals(81, page.totalCount)
                assertEquals("future", page.items.single().id)
            }
        }
    }

    @Test fun libraryResumeScopesMatchWebAndPageUsingTheServerCount() {
        val libraries = listOf("movies" to "Movie", "tvshows" to "Episode", "mixed" to "Movie,Series")
        withServer({ exchange ->
            assertEquals("/proxy/UserItems/Resume", exchange.requestURI.path)
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            val type = libraries.single { it.first == query.queryParameter("parentId") }.second
            assertEquals(type, query.queryParameter("includeItemTypes"))
            assertEquals("true", query.queryParameter("enableTotalRecordCount"))
            assertEquals("2", query.queryParameter("limit"))
            for (key in listOf("filters", "sortBy", "sortOrder", "recursive", "tags", "genres", "years", "nameStartsWith", "isPlayed")) {
                assertNull(key, query.queryParameter(key))
            }
            exchange.json(if (query.queryParameter("startIndex") == "0")
                """{"Items":[{"Id":"one"},{"Id":"two"}],"TotalRecordCount":3}"""
            else """{"Items":[{"Id":"three"}],"TotalRecordCount":3}""")
        }) { session -> runBlocking {
            val api = JellyfinApi()
            for ((kind, _) in libraries) {
                val query = MediaItem(kind, kind, "CollectionFolder", collectionType = kind).defaultLibraryQuery()
                    .copy(tab = LibraryTab.RESUME, tags = setOf("4K"), genres = setOf("Drama"), nameInitial = "A", played = false)
                val first = api.items(session, parentId = kind, limit = 2, options = query)
                val second = api.items(session, parentId = kind, startIndex = 2, limit = 2, options = query)
                assertEquals(3, first.totalCount)
                assertEquals(listOf("one", "two", "three"), (first.items + second.items).map { it.id })
            }
        } }
    }

    @Test fun posterQueriesKeepSeasonCountsWhileEpisodeAndDetailQueriesKeepDescriptions() {
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            val fields = query.queryParameter("fields")!!.split(',').toSet()
            val type = query.queryParameter("includeItemTypes")
            if (type in setOf("Movie", "Series")) {
                assertEquals(setOf("MediaSourceCount", "PrimaryImageAspectRatio") + if (type == "Series") setOf("ChildCount") else emptySet<String>(), fields)
                assertEquals("Primary,Backdrop", query.queryParameter("enableImageTypes"))
            } else assertTrue(fields.contains("Overview"))
            exchange.json("""{"Items":[{"Id":"item","Type":"${type ?: "Series"}","ChildCount":${if ("ChildCount" in fields) "5" else "null"},"Overview":"${if ("Overview" in fields) "Full synopsis" else ""}"}],"TotalRecordCount":1}""")
        }) { session -> runBlocking {
            val api = JellyfinApi()
            for (type in listOf("Movie", "Series", "Episode")) {
                val item = api.items(session, parentId = "library", options = LibraryQuery(itemTypes = setOf(type))).items.single()
                if (type == "Movie") assertNull(item.childCount) else assertEquals(5, item.childCount)
                if (type == "Episode") assertEquals("Full synopsis", item.overview)
            }
            assertEquals("Full synopsis", api.items(session, parentId = "folder").items.single().overview)
            assertEquals("Full synopsis", api.item(session, "item").overview)
        } }
    }

    @Test fun genrePagesKeepWebTitleOrderInsteadOfInheritingMediaSortAndRetainAccuratePaging() = withServer({ exchange ->
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("/proxy/Genres", exchange.requestURI.path)
        assertEquals("tv", query.queryParameter("parentId"))
        assertEquals("Series", query.queryParameter("includeItemTypes"))
        assertEquals("SortName", query.queryParameter("sortBy"))
        assertEquals("Ascending", query.queryParameter("sortOrder"))
        assertEquals("A", query.queryParameter("nameStartsWith"))
        assertEquals("40", query.queryParameter("startIndex"))
        assertNull(query.queryParameter("tags"))
        assertNull(query.queryParameter("genres"))
        exchange.json("""{"Items":[{"Id":"genre","Type":"Genre","Name":"Action"}],"TotalRecordCount":41}""")
    }) { session -> runBlocking {
        val page = JellyfinApi().items(session, "tv", startIndex = 40, options = LibraryQuery(
            tab = LibraryTab.GENRES, itemTypes = setOf("Series"), sort = LibrarySort.COMMUNITY_RATING,
            nameInitial = "A", tags = setOf("4K"), genres = setOf("Drama")))
        assertEquals(41, page.totalCount)
    } }

    @Test fun homepageNextUpUsesRecentWindowWithoutRestrictingAnExplicitSeries() {
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            assertEquals("false", query.queryParameter("enableRewatching"))
            if (query.queryParameter("seriesId") == null) {
                assertEquals(LocalDate.now().minusDays(365).toString(), query.queryParameter("nextUpDateCutoff"))
                assertEquals("24", query.queryParameter("limit"))
            } else assertNull(query.queryParameter("nextUpDateCutoff"))
            exchange.json("""{"Items":[],"TotalRecordCount":0}""")
        }) { session -> runBlocking {
            JellyfinApi().nextUp(session, limit = 24)
            JellyfinApi().nextUp(session, seriesId = "older-show", limit = 1)
        } }
    }

    private fun withServer(route: (HttpExchange) -> Unit, block: (Session) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "pathfin-library-http-test").apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { route(exchange) } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching { exchange.sendResponseHeaders(500, -1); exchange.close() }
            }
        }
        server.start()
        try {
            val session = Session(ServerConfig("http://127.0.0.1:${server.address.port}/proxy", true), "fixture-token", "user", "Alice", "device")
            block(session)
        } finally {
            server.stop(0); executor.shutdownNow()
            failure.get()?.let { throw AssertionError("Fixture contract assertion failed", it) }
        }
    }

    private fun assertHomeRequest(exchange: HttpExchange, path: String, parentId: String?, limit: String) {
        assertEquals("GET", exchange.requestMethod)
        assertEquals(path, exchange.requestURI.path)
        val query = "http://localhost${exchange.requestURI}".toHttpUrl()
        assertEquals("user", query.queryParameter("userId"))
        assertEquals(parentId, query.queryParameter("parentId"))
        assertEquals(limit, query.queryParameter("limit"))
        assertEquals("PrimaryImageAspectRatio", query.queryParameter("fields"))
        assertEquals("true", query.queryParameter("enableUserData"))
        assertEquals("true", query.queryParameter("enableImages"))
        assertEquals("1", query.queryParameter("imageTypeLimit"))
        assertEquals(setOf("Primary", "Backdrop", "Thumb"), query.queryParameter("enableImageTypes")!!.split(',').toSet())
        val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        assertTrue(authorization.startsWith("MediaBrowser "))
        assertTrue(authorization.contains("Token=\"fixture-token\""))
        assertNull(exchange.requestHeaders.getFirst("X-Emby-Authorization"))
        assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
        assertFalse(exchange.requestURI.toString().contains("fixture-token"))
    }

    private suspend fun expectApiFailure(action: suspend () -> Unit): ApiException = try {
        action()
        throw AssertionError("Expected explicit API failure")
    } catch (error: ApiException) { error }

    private fun HttpExchange.json(value: String, code: Int = 200) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}

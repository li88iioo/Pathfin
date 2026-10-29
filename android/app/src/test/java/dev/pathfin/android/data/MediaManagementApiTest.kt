package dev.pathfin.android.data

import dev.pathfin.android.i18n.tr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.*
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaManagementApiTest {
    @Test fun capabilitiesCombineStrictPolicyAndItemAbilityWithoutAdminBypass() {
        val api = MediaManagementApi()
        val item = MediaItem("film", "Film", "Movie", canDownload = true, canDelete = true)
        val session = Session(ServerConfig("https://media.example"), "token", "user", "User", "device")
        assertEquals(MediaManagementCapabilities(), api.capabilities(session, item))
        val admin = session.copy(isAdministrator = true)
        assertTrue(api.capabilities(admin, item).canEditMetadata)
        assertFalse(api.capabilities(admin, item).canDelete)
        assertFalse(api.capabilities(admin, item).canDownload)
        val allowed = session.copy(canDelete = true, canDownload = true)
        assertTrue(api.capabilities(allowed, item).canDelete)
        assertTrue(api.capabilities(allowed, item).canDownload)
        assertFalse(api.capabilities(allowed, item.copy(canDownload = null, canDelete = false)).canDownload)
        assertFalse(api.capabilities(allowed, item.copy(canDelete = false)).canDelete)
    }

    @Test fun metadataUsesCompleteObjectAndPreservesEveryUneditedField() {
        val original = JSONObject("""{
            "Id":"film","Name":"Original","OriginalTitle":"Original title","ForcedSortName":"Sort",
            "Overview":"Old overview","ProductionYear":2024,"PremiereDate":"2024-01-02T00:00:00Z",
            "Genres":["Drama"],"Tags":["Keep"],"OfficialRating":"PG","CommunityRating":7.5,"LockData":true,
            "LockedFields":["Genres"],"ProviderIds":{"Imdb":"synthetic-id"},
            "Path":"https://source-user:source-password@media.example/Film.strm?signature=private#fragment",
            "People":[{"Id":"person","Name":"Synthetic actor"}],"CustomFutureField":{"keep":[1,2,3]}
        }""")
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            assertEquals("/proxy/Items/film", exchange.requestURI.path)
            requests += exchange.requestMethod
            if (exchange.requestMethod == "GET") {
                val query = "http://localhost${exchange.requestURI}".toHttpUrl()
                assertEquals("user", query.queryParameter("userId"))
                assertNull(query.queryParameter("fields"))
                exchange.json(original.toString())
            } else {
                val saved = JSONObject(exchange.requestBody.readBytes().decodeToString())
                val expected = JSONObject(original.toString()).put("Name", "Changed").put("Overview", "New overview")
                    .put("ProductionYear", JSONObject.NULL).put("PremiereDate", "2026-10-24T00:00:00Z")
                    .put("Tags", org.json.JSONArray()).put("CommunityRating", 8.5)
                assertEquals("未编辑的 ProviderIds/LockedFields/People/未知对象必须保留", jsonValue(expected), jsonValue(saved))
                exchange.empty()
            }
        }) { session -> runBlocking {
            val management = MediaManagementApi()
            val document = management.editableMetadata(session, "film")
            assertEquals("Original title", document.metadata.originalTitle)
            assertEquals(original.getString("Path"), document.path)
            assertTrue(document.metadata.lockData)
            management.saveMetadata(session, document, document.metadata.copy(title = "Changed", overview = "New overview",
                productionYear = null, premiereDate = "2026-10-24", tags = emptyList(), communityRating = 8.5))
        } }
        assertEquals(listOf("GET", "POST"), requests.toList())
    }

    @Test fun personDeathDateAndBirthplaceAreWrittenOnlyWhenEdited() {
        val original = JSONObject("""{"Id":"person","Type":"Person","Name":"Synthetic actor","PremiereDate":"1976-02-25T00:00:00Z",
            "EndDate":null,"ProductionLocations":["USA, Los Angeles"," Los Angeles, California "],"ProviderIds":{"Tmdb":"1"}}""")
        val saved = CopyOnWriteArrayList<JSONObject>()
        withServer({ exchange ->
            if (exchange.requestMethod == "GET") exchange.json(original.toString())
            else { saved += JSONObject(exchange.requestBody.readBytes().decodeToString()); exchange.empty() }
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            val document = api.editableMetadata(session, "person")
            assertNull(document.metadata.endDate)
            assertEquals(listOf("USA, Los Angeles", " Los Angeles, California "), document.metadata.productionLocations)
            api.saveMetadata(session, document, document.metadata.copy(endDate = "2020-01-02",
                productionLocations = listOf("美国 洛杉矶", " Los Angeles, California ")))
            api.saveMetadata(session, document, document.metadata.copy(overview = "仅改简介"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.saveMetadata(session, document, document.metadata.copy(endDate = "2020-02-30")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.saveMetadata(session, document, document.metadata.copy(productionLocations = listOf(" "))) } }
        } }
        assertEquals("非法日期与空出生地不能发出写请求", 2, saved.size)
        assertEquals("2020-01-02T00:00:00Z", saved[0].getString("EndDate"))
        assertEquals(listOf("美国 洛杉矶", " Los Angeles, California "), saved[0].getJSONArray("ProductionLocations").let { a -> List(a.length()) { a.getString(it) } })
        assertEquals("1976-02-25T00:00:00Z", saved[0].getString("PremiereDate"))
        assertTrue("未编辑时保留原值", saved[1].isNull("EndDate"))
        assertEquals(2, saved[1].getJSONArray("ProductionLocations").length())
        assertEquals("仅改简介", saved[1].getString("Overview"))
    }

    @Test fun externalIdsPatchOnlyEditedKeysAndPreserveUnexposedValues() {
        val original = JSONObject("""{"Id":"person","Type":"Person","Name":"Actor",
            "ProviderIds":{"Tmdb":"85718","Imdb":"nm1","Hidden Id":"  opaque-id  ","NullableId":null,"EmptyId":""}}""")
        val saved = CopyOnWriteArrayList<JSONObject>()
        withServer({ exchange ->
            if (exchange.requestMethod == "GET") exchange.json(original.toString())
            else { saved += JSONObject(exchange.requestBody.readBytes().decodeToString()); exchange.empty() }
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            val document = api.editableMetadata(session, "person")
            assertEquals(mapOf("Tmdb" to "85718", "Imdb" to "nm1", "Hidden Id" to "  opaque-id  ", "EmptyId" to ""), document.metadata.providerIds)
            api.saveMetadata(session, document, document.metadata.copy(overview = "只改简介"))
            api.saveMetadata(session, document, document.metadata.copy(providerIds = document.metadata.providerIds - "Imdb" +
                mapOf("Tmdb" to " 99 ", "DoubanID" to "27251040")))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                api.saveMetadata(session, document, document.metadata.copy(providerIds = document.metadata.providerIds + ("../x" to "1")))
            } }
        } }
        assertEquals("非法键不能发出写请求", 2, saved.size)
        assertEquals("未编辑时原样保留全部编号，包括空值", jsonValue(original.getJSONObject("ProviderIds")), jsonValue(saved[0].getJSONObject("ProviderIds")))
        val ids = saved[1].getJSONObject("ProviderIds")
        assertEquals("只删除清空的编号，其余隐藏键不丢失", setOf("Tmdb", "DoubanID", "Hidden Id", "NullableId", "EmptyId"), ids.keys().asSequence().toSet())
        assertEquals("99", ids.getString("Tmdb"))
        assertEquals("  opaque-id  ", ids.getString("Hidden Id"))
        assertTrue(ids.isNull("NullableId"))
        assertEquals("", ids.getString("EmptyId"))
    }

    @Test fun nonAdminCannotSendMetadataImageRefreshOrScanWrites() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); exchange.empty() }) { admin -> runBlocking {
            val session = admin.copy(isAdministrator = false)
            val api = MediaManagementApi()
            val operations: List<suspend () -> Unit> = listOf(
                { api.editableMetadata(session, "film"); Unit },
                { api.externalIds(session, MediaItem("film", "Film", "Movie")); Unit },
                { api.uploadImage(session, "film", MediaImageType.PRIMARY, png(), "image/png") },
                { api.deleteImage(session, "film", MediaImage(MediaImageType.PRIMARY, 0, "poster")) },
                { api.refreshItem(session, "film") }, { api.scanItem(session, "library") },
            )
            for (operation in operations) assertEquals(403, expectApiFailure(operation).statusCode)
        } }
        assertEquals(0, requests.get())
    }

    @Test fun metadataValidationAndCrossAccountProtectionDoNotSendWrites() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); exchange.json("""{"Id":"film","Name":"Film"}""") }) { session -> runBlocking {
            val api = MediaManagementApi()
            val document = api.editableMetadata(session, "film")
            for (metadata in listOf(document.metadata.copy(title = " "), document.metadata.copy(productionYear = 0),
                document.metadata.copy(premiereDate = "2026-02-30"), document.metadata.copy(communityRating = Double.NaN),
                document.metadata.copy(criticRating = 101.0), document.metadata.copy(tags = listOf("")))) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { api.saveMetadata(session, document, metadata) } }
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                api.saveMetadata(session.copy(userId = "other"), document, document.metadata.copy(title = "Changed"))
            } }
        } }
        assertEquals(1, requests.get())
    }

    @Test fun metadataRejectsWrongItemIdentityAndSanitizesHttpErrors() {
        withServer({ exchange -> exchange.json("""{"Id":"other","Name":"private"}""") }) { session -> runBlocking {
            assertEquals(tr("服务器返回的媒体标识不一致"), expectApiFailure { MediaManagementApi().editableMetadata(session, "film") }.message)
        } }
        withServer({ exchange -> exchange.json("""{"detail":"fixture-token private path"}""", 403) }) { session -> runBlocking {
            val error = expectApiFailure { MediaManagementApi().editableMetadata(session, "film") }
            assertEquals(403, error.statusCode)
            assertFalse(error.message.orEmpty().contains("fixture-token"))
        } }
    }

    @Test fun imageListUsesBareArrayAndUploadUsesBase64WithActualImageMimeType() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            when (exchange.requestMethod) {
                "GET" -> exchange.json("""[{"ImageType":"Primary","ImageIndex":null,"ImageTag":"poster & tag","Width":1,"Height":1,"Size":68},{"ImageType":"Backdrop","ImageIndex":2,"ImageTag":"wide"}]""")
                "POST" -> {
                    assertEquals("image/png", exchange.requestHeaders.getFirst("Content-Type"))
                    assertArrayEquals(png(), Base64.getDecoder().decode(exchange.requestBody.readBytes()))
                    exchange.empty()
                }
                "DELETE" -> exchange.empty()
            }
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            val images = api.images(session, "film")
            assertEquals(0, images[0].index)
            assertEquals(2, images[1].index)
            assertEquals(1, images[0].width)
            val url = api.imageUrl(session, "film", images[0])!!.toHttpUrl()
            assertEquals("/proxy/Items/film/Images/Primary/0", url.encodedPath)
            assertEquals("poster & tag", url.queryParameter("tag"))
            assertFalse(url.toString().contains(session.accessToken))
            api.uploadImage(session, "film", MediaImageType.PRIMARY, png(), "image/png")
            api.deleteImage(session, "film", images[1])
        } }
        assertEquals(listOf("GET /proxy/Items/film/Images", "POST /proxy/Items/film/Images/Primary", "GET /proxy/Items/film/Images", "DELETE /proxy/Items/film/Images/Backdrop/2"), requests.toList())
    }

    @Test fun invalidImageBodiesIndicesAndIdsAreRejectedBeforeHttp() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); exchange.empty() }) { session -> runBlocking {
            val api = MediaManagementApi()
            val operations: List<suspend () -> Unit> = listOf(
                { api.uploadImage(session, "film", MediaImageType.PRIMARY, byteArrayOf(), "image/png") },
                { api.uploadImage(session, "film", MediaImageType.PRIMARY, png(), "image/jpeg") },
                { api.uploadImage(session, "film", MediaImageType.PRIMARY, "<svg/>".toByteArray(), "image/svg+xml") },
                { api.deleteImage(session, "film", MediaImage(MediaImageType.PRIMARY, -1, "poster")) },
                { api.deleteImage(session, "film", MediaImage(MediaImageType.PRIMARY, 0, "")) },
                { api.images(session, "../other"); Unit },
            )
            for (operation in operations) assertThrows(IllegalArgumentException::class.java) { runBlocking { operation() } }
        } }
        assertEquals(0, requests.get())
    }

    @Test fun deletingAnImageRejectsAChangedIndexAndNeverReplaysTheWrite() {
        val selected = MediaImage(MediaImageType.BACKDROP, 1, "selected-backdrop")
        for (latest in listOf(
            """[{"ImageType":"Backdrop","ImageIndex":1,"ImageTag":"different-backdrop"}]""",
            """[{"ImageType":"Backdrop","ImageIndex":0,"ImageTag":"selected-backdrop"}]""",
            """[{"ImageType":"Primary","ImageIndex":1,"ImageTag":"selected-backdrop"}]""",
            "[]",
        )) {
            val requests = CopyOnWriteArrayList<String>()
            withServer({ exchange ->
                requests += exchange.requestMethod + " " + exchange.requestURI.path
                when (exchange.requestMethod) {
                    "GET" -> exchange.json(latest)
                    "DELETE" -> exchange.empty()
                }
            }) { session -> runBlocking {
                val error = assertThrows(MediaImageSelectionChanged::class.java) { runBlocking {
                    MediaManagementApi().deleteImage(session, "film", selected)
                } }
                assertEquals(JSONArray(latest).length(), error.images.size)
            } }
            assertEquals("变化后只读新列表，不能删除原索引或追踪并删除新索引", listOf("GET /proxy/Items/film/Images"), requests.toList())
        }
    }

    @Test fun deletingAnImageDoesNotWriteWhenItsVerificationReadFails() {
        val requests = CopyOnWriteArrayList<String>()
        val selected = MediaImage(MediaImageType.PRIMARY, 0, "selected-poster")
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            if (exchange.requestMethod == "GET") exchange.json("{}", 503) else exchange.empty()
        }) { session -> runBlocking {
            val error = expectApiFailure { MediaManagementApi().deleteImage(session, "film", selected) }
            assertEquals(503, error.statusCode)
        } }
        assertEquals(listOf("GET /proxy/Items/film/Images"), requests.toList())
    }

    @Test fun remoteImageProvidersPagesAndApplyFollowTheJellyfinContractWithoutFetchingTheExternalUrl() {
        val requests = CopyOnWriteArrayList<String>()
        val firstUrl = "https://pictures.example/poster.png?signature=a%2Fb&part=1&part=2"
        withServer({ exchange ->
            val query = "http://localhost${exchange.requestURI}".toHttpUrl()
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/proxy/Items/film/RemoteImages/Providers" -> {
                    assertEquals("GET", exchange.requestMethod)
                    exchange.json("""[{"Name":"Synthetic provider","SupportedImages":["Primary","Backdrop"]}]""")
                }
                "/proxy/Items/film/RemoteImages" -> {
                    assertEquals("GET", exchange.requestMethod)
                    assertEquals("Primary", query.queryParameter("type"))
                    assertEquals("Synthetic provider", query.queryParameter("providerName"))
                    assertEquals("true", query.queryParameter("includeAllLanguages"))
                    assertEquals("1", query.queryParameter("limit"))
                    val start = requireNotNull(query.queryParameter("startIndex")).toInt()
                    assertTrue(start in 0..1)
                    val image = JSONObject().put("Type", "Primary").put("ProviderName", "Synthetic provider")
                        .put("Url", if (start == 0) firstUrl else "https://pictures.example/second.png")
                        .put("ThumbnailUrl", "https://pictures.example/thumbnail.png").put("Width", 1000).put("Height", 1500).put("Language", "zh")
                    exchange.json(JSONObject().put("Images", JSONArray().put(image)).put("TotalRecordCount", 2)
                        .put("Providers", JSONArray().put("Synthetic provider")).toString())
                }
                "/proxy/Items/film/RemoteImages/Download" -> {
                    assertEquals("POST", exchange.requestMethod)
                    assertEquals("Primary", query.queryParameter("type"))
                    assertEquals(firstUrl, query.queryParameter("imageUrl"))
                    assertEquals(0, exchange.requestBody.readBytes().size)
                    exchange.empty()
                }
                else -> throw AssertionError("Only Jellyfin provider / image endpoints may be requested")
            }
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            assertEquals(setOf(MediaImageType.PRIMARY, MediaImageType.BACKDROP), api.imageProviders(session, "film").single().supportedTypes)
            val first = api.searchImages(session, "film", MediaImageType.PRIMARY, "Synthetic provider", true, limit = 1)
            assertEquals(2, first.totalCount)
            assertEquals(1, first.returnedCount)
            assertTrue(first.hasMore)
            assertEquals(1000, first.images.single().width)
            assertEquals("zh", first.images.single().language)
            val last = api.searchImages(session, "film", MediaImageType.PRIMARY, "Synthetic provider", true,
                startIndex = first.startIndex + first.returnedCount, limit = 1)
            assertFalse(last.hasMore)
            api.applyRemoteImage(session, "film", first.images.single())
        } }
        assertEquals(listOf("GET /proxy/Items/film/RemoteImages/Providers", "GET /proxy/Items/film/RemoteImages",
            "GET /proxy/Items/film/RemoteImages", "POST /proxy/Items/film/RemoteImages/Download"), requests.toList())
    }

    @Test fun remoteImagePermissionsArgumentsAndResultIdentityCannotSendAnUnrelatedWrite() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            exchange.json("""{"Images":[{"Type":"Primary","ProviderName":"Synthetic","Url":"https://pictures.example/poster.png"}],"TotalRecordCount":1}""")
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            val ordinary = session.copy(isAdministrator = false)
            assertEquals(403, expectApiFailure { api.imageProviders(ordinary, "film") }.statusCode)
            assertEquals(403, expectApiFailure { api.searchImages(ordinary, "film", MediaImageType.PRIMARY) }.statusCode)
            for (operation in listOf<suspend () -> Unit>(
                { api.searchImages(session, "../other", MediaImageType.PRIMARY); Unit },
                { api.searchImages(session, "film", MediaImageType.PRIMARY, provider = "bad\nprovider"); Unit },
                { api.searchImages(session, "film", MediaImageType.PRIMARY, startIndex = -1); Unit },
                { api.searchImages(session, "film", MediaImageType.PRIMARY, limit = 49); Unit },
            )) assertThrows(IllegalArgumentException::class.java) { runBlocking { operation() } }
            assertEquals(0, requests.get())
            val selected = api.searchImages(session, "film", MediaImageType.PRIMARY).images.single()
            assertEquals(403, expectApiFailure { api.applyRemoteImage(ordinary, "film", selected) }.statusCode)
            for ((account, id) in listOf(session.copy(userId = "other") to "film", session to "other",
                session.copy(server = ServerConfig("https://other.example")) to "film")) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { api.applyRemoteImage(account, id, selected) } }
            }
            assertEquals("Only the real search may send HTTP", 1, requests.get())
        } }
    }

    @Test fun remoteImageMalformedPagesAndUnsafeProviderUrlsFailExplicitly() {
        val invalid = listOf(
            "[]",
            """{"Images":[],"TotalRecordCount":-1}""",
            """{"Images":[{"Type":"Backdrop","ProviderName":"Synthetic","Url":"https://pictures.example/p.png"}],"TotalRecordCount":1}""",
            """{"Images":[{"Type":"Primary","ProviderName":"Synthetic","Url":"file:///private/image.png"}],"TotalRecordCount":1}""",
            """{"Images":[{"Type":"Primary","ProviderName":"Synthetic","Url":"https://pictures.example/image.png?api_key=fixture-token"}],"TotalRecordCount":1}""",
            """{"Images":[{"Type":"Primary","ProviderName":"Synthetic","Url":"https://user:password@pictures.example/image.png"}],"TotalRecordCount":1}""",
            """{"Images":[{},{}],"TotalRecordCount":2}""",
        )
        for (json in invalid) withServer({ exchange -> exchange.json(json) }) { session -> runBlocking {
            val failure = expectApiFailure { MediaManagementApi().searchImages(session, "film", MediaImageType.PRIMARY, limit = 1) }
            assertFalse(failure.message.orEmpty().contains("fixture-token"))
            assertFalse(failure.message.orEmpty().contains("pictures.example"))
        } }
        withServer({ exchange -> exchange.json("""{"Images":[],"TotalRecordCount":0}""") }) { session -> runBlocking {
            val empty = MediaManagementApi().searchImages(session, "film", MediaImageType.PRIMARY)
            assertTrue(empty.images.isEmpty())
            assertFalse(empty.hasMore)
        } }
    }

    @Test fun refreshAndScanKeepExplicitScopeAndDocumentedParameters() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            paths += exchange.requestURI.path
            assertEquals("POST", exchange.requestMethod)
            if (exchange.requestURI.path.endsWith("/Refresh") && exchange.requestURI.path.contains("/Items/")) {
                val query = "http://localhost${exchange.requestURI}".toHttpUrl()
                assertEquals("Default", query.queryParameter("metadataRefreshMode"))
                assertEquals("Default", query.queryParameter("imageRefreshMode"))
                assertEquals("false", query.queryParameter("replaceAllMetadata"))
                assertNull(query.queryParameter("recursive"))
            }
            exchange.empty()
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            api.refreshItem(session, "film")
            api.scanItem(session, "library")
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                api.refreshItem(session, "film", MetadataRefreshOptions(replaceAllMetadata = true))
            } }
        } }
        assertEquals(listOf("/proxy/Items/film/Refresh", "/proxy/Items/library/Refresh"), paths.toList())
    }

    @Test fun refreshScopesSendTheJellyfinWebParameters() {
        val queries = CopyOnWriteArrayList<Map<String, String?>>()
        val names = listOf("metadataRefreshMode", "imageRefreshMode", "replaceAllMetadata", "replaceAllImages", "regenerateTrickplay")
        withServer({ exchange ->
            val url = "http://localhost${exchange.requestURI}".toHttpUrl()
            queries += names.associateWith(url::queryParameter)
            exchange.empty()
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            // 扫描会忽略替换选项；后两种把两个选项原样带上。
            api.refreshItem(session, "film", MetadataRefreshScope.SCAN.options(replaceImages = true, replaceTrickplay = true))
            api.refreshItem(session, "film", MetadataRefreshScope.MISSING.options(replaceImages = true, replaceTrickplay = false))
            api.refreshItem(session, "film", MetadataRefreshScope.REPLACE_ALL.options(replaceImages = false, replaceTrickplay = true))
        } }
        fun expected(vararg values: String) = names.zip(values).toMap()
        assertEquals(listOf(
            expected("Default", "Default", "false", "false", "false"),
            expected("FullRefresh", "FullRefresh", "false", "true", "false"),
            expected("FullRefresh", "FullRefresh", "true", "false", "true"),
        ), queries.toList())
    }

    @Test fun deletionChecksLatestItemAbilityAndNeverTreatsAdminAsDeletionPermission() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            paths += exchange.requestMethod
            if (exchange.requestMethod == "GET") exchange.json("""{"Id":"film","Name":"Film","CanDelete":true}""") else exchange.empty()
        }) { session -> runBlocking {
            val api = MediaManagementApi()
            assertEquals(403, expectApiFailure { api.deleteItem(session.copy(canDelete = false), "film") }.statusCode)
            api.deleteItem(session, "film")
        } }
        assertEquals(listOf("GET", "DELETE"), paths.toList())
        withServer({ exchange -> exchange.json("""{"Id":"film","CanDelete":false}""") }) { session -> runBlocking {
            assertEquals(403, expectApiFailure { MediaManagementApi().deleteItem(session, "film") }.statusCode)
        } }
    }

    @Test fun downloadPreparationUsesAudioContainerTypesAndFallsBackToTheActualExtension() {
        val item = AtomicReference(JSONObject())
        withServer({ exchange -> exchange.json(item.get().toString()) }) { session -> runBlocking {
            val formats = listOf(
                Triple("WAV", "wav", "audio/wav"), Triple("ogg", "ogg", "audio/ogg"),
                Triple("opus", "opus", "audio/ogg"), Triple("aac", "aac", "audio/aac"),
                Triple("m4a", "mp4", "audio/mp4"), Triple("webm", "webm", "audio/webm"),
                Triple("FLAC", "unknown", "audio/flac"), Triple("bin", "unknown", "audio/*"),
            )
            for ((extension, container, mime) in formats) {
                item.set(JSONObject().put("Id", "film").put("Name", "Synthetic audio").put("Type", "Audio")
                    .put("CanDownload", true).put("Path", "/synthetic/Audio.$extension").put("Container", container))
                val source = JellyfinApi().downloadSource(session, "film")
                assertEquals(mime, source.mimeType)
                assertEquals("Audio.$extension", source.fileName)
                assertTrue(source.trustedServer)
            }
        } }
    }

    @Test fun normalDownloadUsesAuthenticatedFileEndpointWithoutTokenInUrl() {
        withServer({ exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("/proxy/Items/film", exchange.requestURI.path)
            exchange.json("""{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.mp4","Container":"mp4","MediaSources":[{"Id":"film","Protocol":"File","Container":"mp4","Size":1234}]}""")
        }) { session -> runBlocking {
            val source = JellyfinApi().downloadSource(session, "film")
            assertEquals("${session.server.baseUrl}/Items/film/Download", source.url)
            assertEquals("Film.mp4", source.fileName)
            assertEquals(1234L, source.expectedBytes)
            assertTrue(source.trustedServer)
            assertFalse(source.isShortcutFile)
            assertNotNull(source.headers["Authorization"])
            assertFalse(source.url.contains(session.accessToken))
        } }
    }

    @Test fun defaultDownloadPairsTheItemEndpointWithItsOwnVersion() {
        withServer({ exchange ->
            assertEquals("GET /proxy/Items/film", exchange.requestMethod + " " + exchange.requestURI.path)
            exchange.json("""{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.mp4","Container":"mp4","MediaSources":[{"Id":"other","Protocol":"File","Path":"/synthetic/Film-4k.mkv","Container":"mkv","Size":9876},{"Id":"film","Protocol":"File","Path":"/synthetic/Film.mp4","Container":"mp4","Size":1234}]}""")
        }) { session -> runBlocking {
            for (sourceId in listOf(null, "film")) {
                val source = JellyfinApi().downloadSource(session, "film", sourceId)
                assertEquals("${session.server.baseUrl}/Items/film/Download", source.url)
                assertEquals("Film.mp4", source.fileName)
                assertEquals("video/mp4", source.mimeType)
                assertEquals("完整性检查必须使用当前下载端点对应的版本大小", 1234L, source.expectedBytes)
                assertTrue(source.trustedServer)
            }
        } }
    }

    @Test fun defaultDownloadDoesNotBorrowAnUnmatchedVersionsMetadata() {
        withServer({ exchange ->
            assertEquals("GET /proxy/Items/film", exchange.requestMethod + " " + exchange.requestURI.path)
            exchange.json("""{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.mp4","Container":"mp4","MediaSources":[{"Id":"other","Protocol":"File","Path":"/synthetic/Film-4k.mkv","Container":"mkv","Size":9876}]}""")
        }) { session -> runBlocking {
            for (sourceId in listOf(null, "film")) {
                val source = JellyfinApi().downloadSource(session, "film", sourceId)
                assertEquals("${session.server.baseUrl}/Items/film/Download", source.url)
                assertEquals("Film.mp4", source.fileName)
                assertEquals("video/mp4", source.mimeType)
                assertNull("没有当前版本的媒体源时，不能借用其他版本大小", source.expectedBytes)
                assertTrue(source.trustedServer)
            }
        } }
    }

    @Test fun explicitVersionDownloadUsesTheRereadVersionsCurrentMetadata() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/proxy/Items/film" -> exchange.json("""{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.mp4","Container":"mp4","MediaSources":[{"Id":"film","Protocol":"File","Container":"mp4","Size":1234},{"Id":"other","Protocol":"File","Container":"webm","Size":9876}]}""")
                "/proxy/Items/other" -> exchange.json("""{"Id":"other","Name":"Film alternate","CanDownload":true,"Path":"/synthetic/Film-alternate.mkv","Container":"mkv","MediaSources":[{"Id":"film","Protocol":"File","Container":"mp4","Size":1234},{"Id":"other","Protocol":"File","Container":"mkv","Size":4321}]}""")
                else -> throw AssertionError("Unexpected fixture request")
            }
        }) { session -> runBlocking {
            val source = JellyfinApi().downloadSource(session, "film", "other")
            assertEquals("${session.server.baseUrl}/Items/other/Download", source.url)
            assertEquals("Film-alternate.mkv", source.fileName)
            assertEquals("video/x-matroska", source.mimeType)
            assertEquals("选版本后必须使用该条目重新读取的大小", 4321L, source.expectedBytes)
            assertTrue(source.trustedServer)
        } }
        assertEquals(listOf("GET /proxy/Items/film", "GET /proxy/Items/other"), requests.toList())
    }

    @Test fun localDownloadDoesNotResolveAnUnrelatedRemoteVersion() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            if (exchange.requestMethod == "GET") exchange.json("""{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.mp4","Container":"mp4","MediaSources":[{"Id":"other","Protocol":"Http","Path":"https://cdn.example/Other.mkv","Container":"mkv","Size":9876},{"Id":"film","Protocol":"File","Container":"mp4","Size":1234}]}""")
            else exchange.json("""{"MediaSources":[{"Id":"other","Protocol":"Http","Path":"https://cdn.example/Other.mkv","Container":"mkv"}]}""")
        }) { session -> runBlocking {
            val source = JellyfinApi().downloadSource(session, "film")
            assertEquals("${session.server.baseUrl}/Items/film/Download", source.url)
            assertEquals("video/mp4", source.mimeType)
            assertEquals(1234L, source.expectedBytes)
            assertTrue(source.trustedServer)
        } }
        assertEquals(listOf("GET /proxy/Items/film"), requests.toList())
    }

    @Test fun strmDownloadResolvesActualMediaWithoutExportingServerAuthentication() {
        val requests = CopyOnWriteArrayList<String>()
        val original = "https://cdn.example/Film.mkv?sig=a%2Fb&part=1&part=2"
        withServer({ exchange ->
            requests += exchange.requestURI.path
            if (exchange.requestMethod == "GET") exchange.json("""{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.strm","MediaSources":[{"Id":"source","Protocol":"Http"}]}""")
            else exchange.json("""{"MediaSources":[{"Id":"source","Path":"$original","Protocol":"Http","Container":"mkv","RequiredHttpHeaders":{"User-Agent":"Synthetic source","X-Emby-Token":"fixture-token","Authorization":"Bearer source-only"}}]}""")
        }) { session -> runBlocking {
            for (sourceId in listOf(null, "source")) {
                val source = JellyfinApi().downloadSource(session, "film", sourceId)
                assertEquals(original, source.url)
                assertEquals("Film.mkv", source.fileName)
                assertFalse(source.trustedServer)
                assertFalse(source.isShortcutFile)
                assertNull(source.expectedBytes)
                assertEquals("Synthetic source", source.headers["User-Agent"])
                assertFalse(source.headers.values.any { it.contains(session.accessToken) })
            }
        } }
        assertEquals(List(2) { listOf("/proxy/Items/film", "/proxy/Items/film/PlaybackInfo") }.flatten(), requests.toList())
    }

    @Test fun httpsServerCanResolveExplicitExternalHttpStrmWithoutServerIdentity() = runBlocking {
        val original = "http://cdn.example/Film.mp4?sig=a%2Fb"
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals("media.example", request.url.host)
            assertNotNull(request.header("Authorization"))
            requests += request.method + " " + request.url.encodedPath
            val json = when (request.method + " " + request.url.encodedPath) {
                "GET /proxy/Items/film" -> """{"Id":"film","Name":"Film","CanDownload":true,"Path":"/synthetic/Film.strm"}"""
                "POST /proxy/Items/film/PlaybackInfo" -> """{"MediaSources":[{"Id":"source","Path":"$original","Protocol":"Http","Container":"mp4","RequiredHttpHeaders":{"User-Agent":"Synthetic source","Authorization":"Bearer source-only","X-Emby-Token":"fixture-token"}}]}"""
                else -> throw AssertionError("Unexpected fixture request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = Session(ServerConfig("https://media.example/proxy"), "fixture-token", "user", "Synthetic", "device", canDownload = true)
        val source = JellyfinApi(client).downloadSource(session, "film")
        assertFalse(session.server.allowInsecureHttp)
        assertEquals(original, source.url)
        assertEquals("Film.mp4", source.fileName)
        assertFalse(source.trustedServer)
        assertFalse(source.headers.values.any { it.contains(session.accessToken) })
        assertEquals("Bearer source-only", DownloadHeaders.forUrl(session, source, source.url)["Authorization"])
        assertEquals(listOf("GET /proxy/Items/film", "POST /proxy/Items/film/PlaybackInfo"), requests.toList())
    }

    @Test fun downloadCannotBypassPolicyItemDenialOrTurnManifestsAndStrmFilesIntoVideos() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); exchange.json("""{"Id":"film","CanDownload":false}""") }) { session -> runBlocking {
            val api = JellyfinApi()
            assertEquals(403, expectApiFailure { api.downloadSource(session.copy(canDownload = false), "film") }.statusCode)
            assertEquals(403, expectApiFailure { api.downloadSource(session, "film") }.statusCode)
        } }
        assertEquals(1, requests.get())
        for (path in listOf("https://cdn.example/Film.strm", "https://cdn.example/master.m3u8", "https://cdn.example/manifest.mpd")) {
            withServer({ exchange ->
                if (exchange.requestMethod == "GET") exchange.json("""{"Id":"film","CanDownload":true,"Path":"/synthetic/Film.strm"}""")
                else exchange.json("""{"MediaSources":[{"Id":"source","Path":"$path","Protocol":"Http"}]}""")
            }) { session -> runBlocking { assertNotNull(expectApiFailure { JellyfinApi().downloadSource(session, "film") }.message) } }
        }
    }

    @Test fun sessionValidationRefreshesAvatarAndPolicyFromCurrentUser() {
        withServer({ exchange ->
            assertEquals("/proxy/Users/Me", exchange.requestURI.path)
            exchange.json("""{"Id":"user","Name":"New name","PrimaryImageTag":"current-avatar","Policy":{"IsAdministrator":false,"EnableContentDownloading":true,"EnableContentDeletion":false}}""")
        }) { session -> runBlocking {
            val current = JellyfinApi().validateSession(session)
            assertEquals("New name", current.userName)
            assertEquals("current-avatar", current.userImageTag)
            assertFalse(current.isAdministrator)
            assertTrue(current.canDownload)
            assertFalse(current.canDelete)
        } }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        JSONObject.NULL -> null
        else -> value
    }

    private suspend fun expectApiFailure(action: suspend () -> Unit): ApiException {
        try { action() } catch (error: ApiException) { return error }
        throw AssertionError("Expected ApiException")
    }

    private fun withServer(handler: (HttpExchange) -> Unit, block: (Session) -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                assertNotNull(exchange.requestHeaders.getFirst("Authorization"))
                assertNull(exchange.requestHeaders.getFirst("X-Emby-Token"))
                assertFalse(exchange.requestURI.toString().contains("fixture-token"))
                handler(exchange)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching { exchange.json("{}", 500) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val session = Session(ServerConfig("http://127.0.0.1:${server.address.port}/proxy", true), "fixture-token", "user", "Admin", "device",
                isAdministrator = true, canDownload = true, canDelete = true)
            block(session)
        } finally {
            server.stop(0); executor.shutdownNow()
            failure.get()?.let { throw AssertionError("fixture assertion failed", it) }
        }
    }

    private fun HttpExchange.json(body: String, code: Int = 200) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
    private fun HttpExchange.empty() { sendResponseHeaders(204, -1) }
    private fun png(): ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0hoAAAAASUVORK5CYII=")
}

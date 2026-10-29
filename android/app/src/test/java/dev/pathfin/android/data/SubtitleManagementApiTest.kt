package dev.pathfin.android.data

import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.SubtitleUploadFile
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubtitleManagementApiTest {
    @Test fun actualPolicyAllowsSubtitleManagementWithoutGrantingDeletionOrTrustingSessionAdminFlag() = runBlocking {
        val fixture = Fixture(canManage = true, administrator = false)
        val document = fixture.api.document(fixture.session.copy(isAdministrator = true), ITEM)
        assertTrue(document.permissions.canManage)
        assertFalse(document.permissions.canDelete)
        assertEquals(2, document.tracks.size)
        assertFalse(document.tracks.first().isExternal)
        assertTrue(document.tracks.last().isExternal)
        assertTrue(document.isDefaultSource)
    }

    @Test fun remoteSearchAndDownloadStayOnJellyfinAndPreserveOpaqueProviderIdsAsOneEncodedPathSegment() = runBlocking {
        val fixture = Fixture()
        val document = fixture.api.document(fixture.session, ITEM)
        val results = fixture.api.search(fixture.session, document, " ZHO ", perfectMatch = true)
        assertEquals(1, results.size)
        val subtitle = results.single()
        assertEquals("Fixture subtitle", subtitle.name)
        assertEquals("Fixture provider", subtitle.provider)
        assertTrue(subtitle.isHashMatch)
        fixture.api.download(fixture.session, document, subtitle)
        val search = fixture.requests.single { it.method == "GET" && "RemoteSearch" in it.url.pathSegments }
        assertEquals("zho", search.url.pathSegments.last())
        assertEquals("true", search.url.queryParameter("isPerfectMatch"))
        val download = fixture.requests.single { it.method == "POST" }
        assertEquals(REMOTE_ID, download.url.pathSegments.last())
        assertTrue(download.url.encodedPath.contains("%2F"))
        assertNull(download.url.query)
        assertEquals("", download.body)
        assertTrue(fixture.requests.all { it.url.host == "jellyfin.example" && it.url.encodedPath.startsWith("/proxy/") })
        assertTrue(fixture.requests.all { it.authorization.contains("fixture-token") })
        assertFalse(fixture.requests.any { it.url.toString().contains("fixture-token") })
    }

    @Test fun uploadUsesRequiredJsonFieldsAndUtf8Base64DataWithoutMultipart() = runBlocking {
        val fixture = Fixture()
        val document = fixture.api.document(fixture.session, ITEM)
        val file = SubtitleUploadFile.prepare(SRT.toByteArray(Charsets.UTF_16))
        fixture.api.upload(fixture.session, document, file, "eng", forced = true, hearingImpaired = true)
        val request = fixture.requests.single { it.method == "POST" }
        assertEquals("/proxy/Videos/$ITEM/Subtitles", request.url.encodedPath)
        val body = JSONObject(request.body)
        assertEquals(setOf("Language", "Format", "IsForced", "IsHearingImpaired", "Data"), body.keys().asSequence().toSet())
        assertEquals("eng", body.getString("Language"))
        assertEquals("srt", body.getString("Format"))
        assertTrue(body.getBoolean("IsForced"))
        assertTrue(body.getBoolean("IsHearingImpaired"))
        assertEquals(SRT, Base64.getDecoder().decode(body.getString("Data")).toString(Charsets.UTF_8))
        assertTrue(request.contentType.startsWith("application/json"))
    }

    @Test fun missingManagementPolicyRejectsSearchAndUploadBeforeAnyWrite() = runBlocking {
        val fixture = Fixture(canManage = false, administrator = true)
        val document = fixture.api.document(fixture.session.copy(isAdministrator = true), ITEM)
        assertEquals(403, failure { fixture.api.search(fixture.session, document, "zho", false) }.statusCode)
        assertEquals(403, failure { fixture.api.upload(fixture.session, document, SubtitleUploadFile.prepare(SRT.toByteArray()), "zho", false, false) }.statusCode)
        assertEquals(0, fixture.requests.count { "RemoteSearch" in it.url.pathSegments || it.method != "GET" })
    }

    @Test fun deletionRequiresAdministratorAndAnUnchangedExternalTrackFromTheDocument() = runBlocking {
        val fixture = Fixture(administrator = true)
        val document = fixture.api.document(fixture.session, ITEM)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.delete(fixture.session, document, document.tracks.first()) } }
        fixture.api.delete(fixture.session, document, document.tracks.last())
        assertEquals("/proxy/Videos/$ITEM/Subtitles/3", fixture.requests.single { it.method == "DELETE" }.url.encodedPath)
        assertEquals("删除前必须重新确认轨道序号与外部字幕属性", 2, fixture.itemReads.get())
    }

    @Test fun aChangedTrackOrSwitchedAccountCannotDeleteOrReuseRemoteSearchResults() = runBlocking {
        val fixture = Fixture(administrator = true)
        val document = fixture.api.document(fixture.session, ITEM)
        val result = fixture.api.search(fixture.session, document, "eng", false).single()
        val other = fixture.session.copy(userId = "other-user")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.download(other, document, result) } }
        fixture.changeExternalTrack = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.delete(fixture.session, document, document.tracks.last()) } }
        assertEquals(0, fixture.requests.count { it.method != "GET" })
    }

    @Test fun nonDefaultVersionCannotBeMistakenForTheItemTargetedByJellyfinSubtitleWrites() = runBlocking {
        val fixture = Fixture(administrator = true)
        val document = fixture.api.document(fixture.session, ITEM, sourceId = OTHER_ITEM)
        assertFalse(document.isDefaultSource)
        assertEquals("jpn", document.tracks.single().language)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.search(fixture.session, document, "eng", false) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.upload(fixture.session, document,
            SubtitleUploadFile.prepare(SRT.toByteArray()), "eng", false, false) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.delete(fixture.session, document, document.tracks.single()) } }
        assertEquals(0, fixture.requests.count { it.method != "GET" || "RemoteSearch" in it.url.pathSegments })
    }

    @Test fun aHigherResolutionVersionSortedFirstCannotChangeWhichEntityReceivesUploadsOrDeletes() = runBlocking {
        val fixture = Fixture(administrator = true, higherResolutionVersionFirst = true)
        val document = fixture.api.document(fixture.session, ITEM)
        assertTrue(document.isDefaultSource)
        assertEquals(ITEM, document.sourceId)
        assertEquals("eng", document.tracks.last().language)
        fixture.api.upload(fixture.session, document, SubtitleUploadFile.prepare(SRT.toByteArray()), "eng", false, false)
        fixture.api.delete(fixture.session, document, document.tracks.last())
        assertEquals("/proxy/Videos/$ITEM/Subtitles", fixture.requests.single { it.method == "POST" }.url.encodedPath)
        assertEquals("/proxy/Videos/$ITEM/Subtitles/3", fixture.requests.single { it.method == "DELETE" }.url.encodedPath)
        val other = fixture.api.document(fixture.session, ITEM, OTHER_ITEM)
        assertFalse(other.isDefaultSource)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.delete(fixture.session, other, other.tracks.single()) } }
        assertEquals(1, fixture.requests.count { it.method == "DELETE" })
    }

    @Test fun canonicalGuidIdentitySurvivesHyphensAndCaseWithoutDependingOnVersionOrder() = runBlocking {
        val fixture = Fixture(itemSourceId = HYPHENATED_ITEM, higherResolutionVersionFirst = true)
        val document = fixture.api.document(fixture.session, HYPHENATED_ITEM, sourceId = ITEM.uppercase())
        assertTrue(document.isDefaultSource)
        assertEquals(HYPHENATED_ITEM, document.sourceId)
        assertEquals("eng", document.tracks.last().language)
        assertEquals(ITEM, fixture.requests.single { it.url.encodedPath.endsWith("/Items") }.url.queryParameter("ids"))
    }

    @Test fun aMissingEntitySourceMustStayReadOnlyRatherThanFallingBackToTheFirstVersion() = runBlocking {
        val fixture = Fixture(administrator = true, omitEntitySource = true)
        val document = fixture.api.document(fixture.session, ITEM)
        assertFalse(document.isDefaultSource)
        assertNull(document.sourceId)
        assertTrue(document.tracks.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.api.upload(fixture.session, document,
            SubtitleUploadFile.prepare(SRT.toByteArray()), "eng", false, false) } }
        assertEquals(0, fixture.requests.count { it.method != "GET" })
    }

    @Test fun invalidServerUserAndHttpErrorsRemainSafeAndCannotCreateEditableDocuments() = runBlocking {
        val wrongUser = Fixture(userId = "wrong-user")
        assertEquals(401, failure { wrongUser.api.document(wrongUser.session, ITEM) }.statusCode)
        val denied = Fixture(searchStatus = 403)
        val document = denied.api.document(denied.session, ITEM)
        val failure = failure { denied.api.search(denied.session, document, "zho", false) }
        assertEquals(403, failure.statusCode)
        assertFalse(failure.message.orEmpty().contains("fixture-token"))
    }

    private suspend fun failure(block: suspend () -> Any?): ApiException = try {
        block()
        throw AssertionError("Expected an API error")
    } catch (failure: ApiException) { failure }

    private data class RequestRecord(val method: String, val url: HttpUrl, val body: String, val authorization: String, val contentType: String)
    private class Fixture(canManage: Boolean = true, administrator: Boolean = false, userId: String = "fixture-user", searchStatus: Int = 200,
        higherResolutionVersionFirst: Boolean = false, itemSourceId: String = ITEM, omitEntitySource: Boolean = false) {
        val session = Session(ServerConfig("https://jellyfin.example/proxy"), "fixture-token", "fixture-user", "Fixture", "fixture-device")
        val requests = CopyOnWriteArrayList<RequestRecord>()
        val itemReads = AtomicInteger()
        @Volatile var changeExternalTrack = false
        val api = SubtitleManagementApi(JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += RequestRecord(request.method, request.url, body, request.header("Authorization").orEmpty(), request.body?.contentType()?.toString().orEmpty())
            var status = 200
            val response = when {
                request.url.encodedPath.endsWith("/Users/Me") -> JSONObject().put("Id", userId).put("Policy",
                    JSONObject().put("EnableSubtitleManagement", canManage).put("IsAdministrator", administrator)).toString()
                request.url.encodedPath.endsWith("/Items") -> {
                    itemReads.incrementAndGet()
                    val entitySource = """{"Id":"$itemSourceId","Name":"1080p","MediaStreams":[
                        {"Index":0,"Type":"Video","Width":1920},{"Index":2,"Type":"Subtitle","Codec":"ass","IsExternal":false},
                        {"Index":3,"Type":"Subtitle","Codec":"srt","Language":"eng","IsExternal":${!changeExternalTrack}}]}"""
                    val alternateSource = """{"Id":"$OTHER_ITEM","Name":"4K","MediaStreams":[
                        {"Index":0,"Type":"Video","Width":3840},{"Index":3,"Type":"Subtitle","Codec":"srt","Language":"jpn","IsExternal":true}]}"""
                    val sources = when {
                        omitEntitySource -> alternateSource
                        higherResolutionVersionFirst -> "$alternateSource,$entitySource"
                        else -> "$entitySource,$alternateSource"
                    }
                    """{"Items":[{"Id":"$ITEM","Name":"Fixture film","Type":"Movie","MediaSources":[$sources]}],"TotalRecordCount":1}"""
                }
                request.method == "GET" && "RemoteSearch" in request.url.pathSegments -> {
                    status = searchStatus
                    if (status != 200) """{"detail":"fixture-token private path"}""" else
                        """[{"Id":${JSONObject.quote(REMOTE_ID)},"Name":"Fixture subtitle","ProviderName":"Fixture provider","ThreeLetterISOLanguageName":"zho","Format":"srt","IsHashMatch":true,"DownloadCount":42}]"""
                }
                else -> { status = 204; "" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()))
    }

    companion object {
        private const val ITEM = "00112233445566778899aabbccddeeff"
        private const val HYPHENATED_ITEM = "00112233-4455-6677-8899-AABBCCDDEEFF"
        private const val OTHER_ITEM = "112233445566778899aabbccddeeff00"
        private const val REMOTE_ID = "provider_a/b?part=1&part=2+percent%#fragment"
        private const val SRT = "1\n00:00:01,000 --> 00:00:03,000\n测试字幕\n"
    }
}

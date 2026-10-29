package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaSegmentType
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaSegmentsApiTest {
    @Test fun ordinaryAuthenticatedReadUsesTheRealQueryResultAndIntroFilter() = runBlocking {
        val fixture = Fixture(page(segment().put("ItemId", HYPHENATED_ITEM)))
        val result = fixture.api.mediaSegments(fixture.session, ITEM)
        assertEquals(1, result.size)
        assertEquals(MediaSegmentType.INTRO, result.single().type)
        assertEquals(10_000_000L, result.single().startTicks)
        assertEquals(50_000_000L, result.single().endTicks)
        val request = fixture.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/base/MediaSegments/$ITEM", request.url.encodedPath)
        assertEquals("Intro", request.url.queryParameter("includeSegmentTypes"))
        assertEquals(1, request.url.querySize)
        assertTrue(request.header("Authorization").orEmpty().contains("synthetic-segments-token"))
        assertFalse(fixture.session.isAdministrator)
    }

    @Test fun emptyAndOlderServer404AreCompatibleButAuthorizationAndServerFailuresAreRealFailures() = runBlocking {
        assertTrue(Fixture(page()).let { it.api.mediaSegments(it.session, ITEM) }.isEmpty())
        assertTrue(Fixture(JSONObject(), 404).let { it.api.mediaSegments(it.session, ITEM) }.isEmpty())
        for (status in listOf(401, 403, 500, 503)) {
            val fixture = Fixture(JSONObject(), status)
            assertEquals(status, expectApi { fixture.api.mediaSegments(fixture.session, ITEM) }.statusCode)
        }
    }

    @Test fun malformedRequestIdsCannotReachTheNetwork() = runBlocking {
        val fixture = Fixture(page())
        for (id in listOf("", "../private", "$ITEM/other", "0".repeat(32), "fixture-item", "$ITEM\n", "a".repeat(129))) {
            try { fixture.api.mediaSegments(fixture.session, id); fail("Invalid identifier must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun missingArraysWrongTypesAndUnboundedSegmentListsAreRejected() = runBlocking {
        val tooMany = JSONArray().apply { for (index in 1..257) put(segment().put("Id", "%032x".format(index))) }
        val cases = listOf(JSONObject(), JSONObject().put("Items", JSONObject.NULL), JSONObject().put("Items", "[]"),
            JSONObject().put("Items", JSONArray().put("invalid")), JSONObject().put("Items", tooMany))
        for (response in cases) {
            val fixture = Fixture(response)
            assertNull(expectApi { fixture.api.mediaSegments(fixture.session, ITEM) }.statusCode)
        }
    }

    @Test fun itemIdentityTypeAndDuplicateSegmentIdsAreStrict() = runBlocking {
        val cases = listOf(
            page(segment().put("ItemId", SEGMENT)), page(segment().put("ItemId", JSONObject.NULL)),
            page(segment().put("Id", "not-a-uuid")), page(segment().put("Id", "0".repeat(32))),
            page(segment().put("Type", "Outro")), page(segment().put("Type", "intro")),
            page(segment(), segment()),
        )
        for (response in cases) {
            val fixture = Fixture(response)
            assertNull(expectApi { fixture.api.mediaSegments(fixture.session, ITEM) }.statusCode)
        }
    }

    @Test fun timesMustBeExactNonnegativeInt64AndHaveAUsableEndAfterStart() = runBlocking {
        val cases = listOf(
            segment().put("StartTicks", -1L), segment().put("EndTicks", 10_000_000L),
            segment().put("StartTicks", "10000000"), segment().put("EndTicks", 50_000_000.5),
            segment().put("EndTicks", JSONObject.NULL), segment().put("StartTicks", 1L).put("EndTicks", 999L),
            segment().put("EndTicks", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
        )
        for (value in cases) {
            val fixture = Fixture(page(value))
            val error = expectApi { fixture.api.mediaSegments(fixture.session, ITEM) }
            assertNull(error.statusCode)
            assertFalse(error.message.orEmpty().contains("synthetic-segments-token"))
        }
    }

    @Test fun validSegmentsAreSortedWithoutDependingOnOptionalTotals() = runBlocking {
        val first = segment().put("Id", "11111111111111111111111111111111").put("StartTicks", 0).put("EndTicks", 1_000_000)
        val fixture = Fixture(page(segment(), first).put("TotalRecordCount", 0))
        val result = fixture.api.mediaSegments(fixture.session, ITEM)
        assertEquals(listOf(0L, 10_000_000L), result.map { it.startTicks })
        assertEquals(2, result.size)
    }

    private class Fixture(json: JSONObject, status: Int = 200) {
        val session = Session(ServerConfig("https://jellyfin.invalid/base"), "synthetic-segments-token", "user", "Synthetic", "device")
        val requests = CopyOnWriteArrayList<Request>()
        val api = JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic response")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
    }

    private suspend fun expectApi(block: suspend () -> Any?): ApiException = try {
        block(); throw AssertionError("Expected a guarded media segment failure")
    } catch (error: ApiException) { error }

    private companion object {
        const val ITEM = "22222222222222222222222222222222"
        const val HYPHENATED_ITEM = "22222222-2222-2222-2222-222222222222"
        const val SEGMENT = "33333333333333333333333333333333"
        fun segment() = JSONObject().put("Id", SEGMENT).put("ItemId", ITEM).put("Type", "Intro")
            .put("StartTicks", 10_000_000L).put("EndTicks", 50_000_000L)
        fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("StartIndex", 0)
    }
}

package dev.pathfin.android.data

import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

class LiveTvApiTest {
    @Test fun deniedOrMalformedPolicyNeverRequestsLiveTvEvenForAdministrator() = runBlocking {
        for (enabled in listOf<Any>(false, "true", JSONObject.NULL)) {
            val fixture = Fixture(policy = JSONObject().put("EnableLiveTvAccess", enabled).put("IsAdministrator", true))
            val failure = expectApi { fixture.service.channels(fixture.session) }
            assertEquals(403, failure.statusCode)
            assertEquals(listOf("/proxy/Users/Me"), fixture.requests.map { it.url.encodedPath })
        }
        val disabled = Fixture(policy = permission().put("IsDisabled", true))
        assertEquals(403, expectApi { disabled.service.channels(disabled.session) }.statusCode)
        assertEquals(1, disabled.requests.size)
    }

    @Test fun channelPagingUsesActualContractAndKeepsSourceAsLiveWithoutResume() = runBlocking {
        val fixture = Fixture { request ->
            if (request.url.encodedPath.endsWith("LiveTv/Channels")) {
                val start = request.url.queryParameter("startIndex")!!.toInt()
                if (start == 0) page(3, 0, channel("one").put("RunTimeTicks", 9999).put("UserData", JSONObject().put("PlaybackPositionTicks", 9000)), channel("two"))
                else page(3, 2, channel("three"))
            } else null
        }
        val first = fixture.service.channels(fixture.session, limit = 2)
        val second = fixture.service.channels(fixture.session, first.nextIndex, limit = 2)
        assertEquals(listOf("one", "two"), first.items.map { it.id })
        assertTrue(first.hasMore)
        assertEquals(2, first.nextIndex)
        assertFalse(second.hasMore)
        assertEquals(3, second.nextIndex)
        assertEquals("TvChannel", first.items.first().item.type)
        assertEquals(0L, first.items.first().item.positionTicks)
        assertEquals(0L, first.items.first().item.runTimeTicks)
        val requests = fixture.requests.filter { it.url.encodedPath.endsWith("LiveTv/Channels") }
        assertEquals(listOf("0", "2"), requests.map { it.url.queryParameter("startIndex") })
        assertTrue(requests.all { it.method == "GET" && it.url.queryParameter("userId") == "user" && it.url.queryParameter("limit") == "2" })
        assertTrue(requests.all { it.url.queryParameter("addCurrentProgram") == "true" && it.url.queryParameter("sortBy") == "SortName" })
        assertTrue(requests.all { it.url.queryParameter("searchTerm") == null && it.url.queryParameter("enableTotalRecordCount") == null })
        assertTrue(requests.all { it.header("Authorization")?.contains("synthetic-token") == true })
        assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("Users/Me") })
        assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("LiveTv/Info") })
    }

    @Test fun disabledSourceIsDifferentFromDeniedPlaybackAndPermissionIsRecheckedAfterTokenChange() = runBlocking {
        val unconfigured = Fixture(configured = false)
        assertTrue(unconfigured.service.capabilities(unconfigured.session).canView)
        assertTrue(unconfigured.service.channels(unconfigured.session).items.isEmpty())
        assertEquals(0, unconfigured.requests.count { it.url.encodedPath.endsWith("LiveTv/Channels") })
        val noPlayback = Fixture(policy = permission().put("EnableMediaPlayback", false))
        val access = noPlayback.service.capabilities(noPlayback.session)
        assertTrue(access.canView)
        assertFalse(access.canPlay)
        noPlayback.service.channels(noPlayback.session.copy(accessToken = "replacement-token"))
        assertEquals(2, noPlayback.requests.count { it.url.encodedPath.endsWith("Users/Me") })
        assertTrue(noPlayback.requests.last().header("Authorization")!!.contains("replacement-token"))
        assertFalse(noPlayback.requests.last().header("Authorization")!!.contains("synthetic-token"))
    }

    @Test fun guideUsesUtcOverlapBoundsAndSupportsTwentyFiveHourDstDay() = runBlocking {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 10, 25)
        val from = date.atStartOfDay(zone).toInstant()
        val until = date.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(Duration.ofHours(25), Duration.between(from, until))
        val fixture = Fixture { request ->
            if (request.url.encodedPath.endsWith("LiveTv/Programs")) page(1, 0, program("program").put("ChannelId", "channel")) else null
        }
        val result = fixture.service.programs(fixture.session, "channel", from, until)
        assertEquals("channel", result.items.single().channelId)
        val request = fixture.requests.last()
        assertEquals("/proxy/LiveTv/Programs", request.url.encodedPath)
        assertEquals(from.toString(), request.url.queryParameter("minEndDate"))
        assertEquals(until.toString(), request.url.queryParameter("maxStartDate"))
        assertEquals("channel", request.url.queryParameter("channelIds"))
        assertEquals("StartDate", request.url.queryParameter("sortBy"))
        assertEquals("true", request.url.queryParameter("enableTotalRecordCount"))
        assertEquals("false", request.url.queryParameter("enableImages"))
        assertNull(request.url.queryParameter("minStartDate"))
    }

    @Test fun invalidRangesDoNotConnectAndWrongChannelCannotBecomeCurrentProgram() = runBlocking {
        val fixture = Fixture()
        val from = Instant.parse("2026-10-24T00:00:00Z")
        for (channelId in listOf("../secret", "channel/a", "", "bad\nchannel"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.service.programs(fixture.session, channelId, from, from.plusSeconds(60)) } }
        for (until in listOf(from, from.minusSeconds(1), from.plus(Duration.ofDays(3))))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.service.programs(fixture.session, "channel", from, until) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.service.channels(fixture.session, -1) } }
        assertTrue(fixture.requests.isEmpty())
        val wrong = Fixture { request -> if (request.url.encodedPath.endsWith("LiveTv/Programs")) page(1, 0, program("p").put("ChannelId", "other")) else null }
        assertNull(expectApi { wrong.service.programs(wrong.session, "channel", from, from.plusSeconds(3600)) }.statusCode)
    }

    @Test fun malformedTotalsOffsetsDuplicatesAndTimeOrderAreRejected() = runBlocking {
        val cases = listOf(
            page(1, 0, channel("one"), channel("two")),
            page(2, 0, channel("one"), channel("one")),
            page(2, 1, channel("one")),
            page(1, 0),
            page(1, 0, channel("one").put("CurrentProgram", program("p").put("EndDate", "2026-10-23T00:00:00Z"))),
        )
        for (response in cases) {
            val fixture = Fixture { request -> if (request.url.encodedPath.endsWith("LiveTv/Channels")) response else null }
            val exception = expectApi { fixture.service.channels(fixture.session) }
            assertNull(exception.statusCode)
            assertFalse(exception.message.orEmpty().contains("synthetic-token"))
        }
    }

    @Test fun mismatchedUserCannotReceiveLiveTvQueries() = runBlocking {
        val fixture = Fixture { request -> if (request.url.encodedPath.endsWith("Users/Me")) JSONObject().put("Id", "another-user").put("Policy", permission()) else null }
        assertEquals(401, expectApi { fixture.service.channels(fixture.session) }.statusCode)
        assertEquals(1, fixture.requests.size)
    }

    private class Fixture(
        private val policy: JSONObject = permission(),
        private val configured: Boolean = true,
        private val handler: (Request) -> JSONObject? = { null },
    ) {
        val session = Session(ServerConfig("https://jellyfin.invalid/proxy"), "synthetic-token", "user", "User", "device")
        val requests = CopyOnWriteArrayList<Request>()
        val service = LiveTvApi(JellyfinApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val json = handler(request) ?: when (request.url.encodedPath) {
                "/proxy/Users/Me" -> JSONObject().put("Id", "user").put("Policy", policy)
                "/proxy/LiveTv/Info" -> JSONObject().put("IsEnabled", configured)
                "/proxy/LiveTv/Channels" -> page(0, 0)
                else -> throw AssertionError("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()))
    }

    private suspend fun expectApi(block: suspend () -> Any?): ApiException = try {
        block(); throw AssertionError("Expected a guarded API failure")
    } catch (exception: ApiException) { exception }

    companion object {
        private fun permission() = JSONObject().put("IsDisabled", false).put("EnableLiveTvAccess", true).put("EnableMediaPlayback", true)
        private fun channel(id: String) = JSONObject().put("Id", id).put("Name", "Synthetic $id").put("Type", "TvChannel")
        private fun program(id: String) = JSONObject().put("Id", id).put("Name", "Synthetic programme").put("StartDate", "2026-10-24T00:00:00Z").put("EndDate", "2026-10-24T01:00:00Z")
        private fun page(total: Int, start: Int, vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", total).put("StartIndex", start)
    }
}

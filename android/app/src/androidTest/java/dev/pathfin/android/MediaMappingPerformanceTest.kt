package dev.pathfin.android

import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅显式采样；真实 API/DTO 映射，无服务器、个人会话或通过耗时阈值。 */
@RunWith(AndroidJUnit4::class)
class MediaMappingPerformanceTest {
    @Test fun recordMainThreadPersonMappingFor500And2000Items() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.mappingSample") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification" && BuildConfig.DEBUG)
        // 合成与序列化在采样外完成，响应仍通过生产 executeJson 和 mapMediaPage。
        val responses = listOf(500, 2000).associateWith { payload(it) }
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath == "/Persons")
            check(request.url.queryParameter("excludePersonTypes") == "Artist,AlbumArtist")
            check(request.url.queryParameter("startIndex") == null && request.url.queryParameter("limit") == null)
            val count = checkNotNull(request.url.queryParameter("searchTerm")).removePrefix("mapping-").toInt()
            requests.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(checkNotNull(responses[count]).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = JellyfinApi(client)
        val session = Session(ServerConfig("http://127.0.0.1:1", true), "mapping-token", "mapping-user", "合成账号", "mapping-device")
        val samples = JSONArray()
        try {
            runBlocking { withContext(Dispatchers.Main) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                val pid = Process.myPid()
                val mainTid = Process.myTid()
                for (count in responses.keys) for (index in -2 until 20) {
                    val marker = "count=$count index=$index warmup=${index < 0} pid=$pid mainTid=$mainTid"
                    Log.i("PathfinMapping", "phase=begin $marker")
                    val started = System.nanoTime()
                    val page = api.discoveryPage(session, DiscoveryCategory.PEOPLE, query = "mapping-$count")
                    val elapsed = System.nanoTime() - started
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    assertEquals(count, page.items.size)
                    assertEquals(count, page.totalCount)
                    assertEquals(personId(count, 0), page.items.first().id)
                    assertEquals(personId(count, count - 1), page.items.last().id)
                    Log.i("PathfinMapping", "phase=complete $marker total_ns=$elapsed total_ms=${elapsed / 1_000_000.0}")
                    val record = JSONObject().put("count", count).put("index", index).put("warmup", index < 0)
                        .put("pid", pid).put("mainTid", mainTid).put("totalNs", elapsed)
                    instrumentation.sendStatus(2, Bundle().apply { putString("pathfin.mappingSample", record.toString()) })
                    if (index >= 0) samples.put(record)
                }
            } }
            assertEquals(44, requests.get())
            instrumentation.sendStatus(2, Bundle().apply {
                putString("pathfin.mappingSummary", JSONObject().put("warmupsPerCount", 2)
                    .put("samplesPerCount", 20).put("samples", samples).toString())
            })
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun personId(count: Int, index: Int) = "mapping-$count-person-$index"

    private fun payload(count: Int): ByteArray = JSONObject().put("TotalRecordCount", count)
        .put("Items", JSONArray((0 until count).map { index ->
            JSONObject().put("Id", personId(count, index)).put("Name", "合成人物 $index").put("Type", "Person")
                .put("Overview", "仅供本地映射性能采样的合成人物简介。".repeat(8)).put("IsFolder", false)
                .put("ImageTags", JSONObject()).put("PrimaryImageAspectRatio", 2.0 / 3)
                .put("Genres", JSONArray(listOf("合成类型"))).put("Tags", JSONArray(listOf("性能采样")))
                .put("ChildCount", 0).put("MediaSourceCount", 0).put("DateCreated", "2026-01-01T00:00:00Z")
                .put("UserData", JSONObject().put("IsFavorite", false).put("Played", false).put("PlaybackPositionTicks", 0))
        })).toString().toByteArray(Charsets.UTF_8)
}

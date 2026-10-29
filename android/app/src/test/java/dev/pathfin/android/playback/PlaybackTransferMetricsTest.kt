package dev.pathfin.android.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackTransferMetricsTest {
    @Test fun noReadAndTooShortWindowNeverInventASpeed() {
        var nanos = 0L
        val metrics = PlaybackTransferMetrics { nanos }
        val body = metrics.wrap(byteArrayOf(1, 2, 3, 4).toResponseBody())
        nanos = 5_000_000_000L
        assertNull(metrics.sample())
        body.use { it.source().readByte() }
        assertNull(metrics.sample())
        nanos += 249_000_000L
        assertNull(metrics.sample())
        nanos += 1_000_000L
        val first = requireNotNull(metrics.sample())
        assertEquals(4L, first.bytesRead)
        assertEquals(16L, first.bytesPerSecond)
    }

    @Test fun eachWindowUsesActualReadDeltaAndIdleWindowReturnsZero() {
        var nanos = 0L
        val metrics = PlaybackTransferMetrics { nanos }
        metrics.wrap(ByteArray(1024).toResponseBody()).use { it.bytes() }
        nanos = 500_000_000L
        assertEquals(2048L, metrics.sample()?.bytesPerSecond)
        metrics.wrap(ByteArray(512).toResponseBody()).use { it.bytes() }
        nanos = 1_000_000_000L
        assertEquals(1024L, metrics.sample()?.bytesPerSecond)
        nanos = 1_500_000_000L
        val idle = requireNotNull(metrics.sample())
        assertEquals(0L, idle.bytesPerSecond)
        assertEquals(0L, idle.bytesRead)
    }

    @Test fun resetAndNewSessionCannotRetainAnEarlierRate() {
        var nanos = 0L
        val metrics = PlaybackTransferMetrics { nanos }
        metrics.wrap(ByteArray(128).toResponseBody()).use { it.bytes() }
        nanos = 1_000_000_000L
        assertEquals(128L, metrics.sample()?.bytesPerSecond)
        metrics.reset()
        nanos += 1_000_000_000L
        assertNull(metrics.sample())
        metrics.wrap(ByteArray(64).toResponseBody()).use { it.bytes() }
        nanos += 1_000_000_000L
        assertEquals(64L, metrics.sample()?.bytesPerSecond)
        assertNull(PlaybackTransferMetrics { nanos }.sample())
    }

    @Test fun formattingUsesByteUnitsAndCannotDisplayNegativeSpeed() {
        assertEquals("0 B/s", formatPlaybackTransferRate(-1))
        assertEquals("0 B/s", formatPlaybackTransferRate(0))
        assertEquals("512 B/s", formatPlaybackTransferRate(512))
        assertEquals("1.5 KiB/s", formatPlaybackTransferRate(1536))
        assertEquals("2.5 MiB/s", formatPlaybackTransferRate(2_621_440))
    }

    @Test(timeout = 10_000)
    fun gatedRealHttpResponseCountsOnlyBytesActuallyReadWithoutExtraRequests() {
        val nanos = AtomicLong(0)
        val metrics = PlaybackTransferMetrics(nanos::get)
        val requests = AtomicInteger()
        val firstChunkSent = CountDownLatch(1)
        val secondChunkAllowed = CountDownLatch(1)
        val handlerFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/media") { exchange ->
                try {
                    requests.incrementAndGet()
                    exchange.sendResponseHeaders(200, 6144)
                    exchange.responseBody.use { output ->
                        output.write(ByteArray(4096) { 1 }); output.flush()
                        firstChunkSent.countDown()
                        check(secondChunkAllowed.await(5, TimeUnit.SECONDS))
                        output.write(ByteArray(2048) { 2 })
                    }
                } catch (failure: Throwable) { handlerFailure.compareAndSet(null, failure) }
                finally { exchange.close() }
            }
            start()
        }
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder().body(metrics.wrap(response.body)).build()
        }.build()
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/media").build()).execute().use { response ->
                assertTrue(firstChunkSent.await(3, TimeUnit.SECONDS))
                assertNull("收到 headers 并不等于读到媒体字节", metrics.sample())
                assertEquals(4096, response.body.source().readByteArray(4096).size)
                nanos.set(500_000_000L)
                assertEquals(8192L, metrics.sample()?.bytesPerSecond)
                secondChunkAllowed.countDown()
                assertEquals(2048, response.body.source().readByteArray().size)
                nanos.set(1_000_000_000L)
                assertEquals(4096L, metrics.sample()?.bytesPerSecond)
                nanos.set(1_500_000_000L)
                assertEquals(0L, metrics.sample()?.bytesPerSecond)
            }
            assertEquals("计量不允许另外发出测速请求", 1, requests.get())
            handlerFailure.get()?.let { throw AssertionError("合成 HTTP 响应失败", it) }
        } finally {
            secondChunkAllowed.countDown()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

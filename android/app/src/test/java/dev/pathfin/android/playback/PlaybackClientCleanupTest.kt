package dev.pathfin.android.playback

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** 真实回环 HTTP 连接 + 可控 Socket.close；不把任务存在当成实际后台清理完成。 */
class PlaybackClientCleanupTest {
    @Test(timeout = 10_000)
    fun blockedSocketCloseRunsOffCallerAndDoesNotBlockReturning() {
        Fixture().use { fixture ->
            val probe = CloseProbe()
            val client = fixture.idleClient(probe)
            try {
                val caller = Thread.currentThread()
                val completion = PlaybackClientCleanup.release(client)
                assertTrue("连接清理必须真正开始", probe.entered.await(3, TimeUnit.SECONDS))
                assertNotSame("Socket.close 不能在发起停止的线程执行", caller, probe.thread)
                assertTrue(probe.thread?.name.orEmpty().startsWith("Pathfin-MediaCleanup"))
                assertFalse("socket 被阻塞时调用方已拿到未完成结果", completion.isDone)
                probe.allow.countDown()
                completion.get(3, TimeUnit.SECONDS)
                assertEquals(0, client.connectionPool.connectionCount())
                assertTrue(client.dispatcher.executorService.isShutdown)
            } finally {
                probe.allow.countDown()
                PlaybackClientCleanup.release(client).get(3, TimeUnit.SECONDS)
            }
        }
    }

    @Test(timeout = 10_000)
    fun clearingCallerScopeCannotCancelSubmittedClientCleanup() {
        Fixture().use { fixture ->
            val probe = CloseProbe()
            val client = fixture.idleClient(probe)
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val submitted = CompletableFuture<CompletableFuture<Unit>>()
            try {
                owner.launch {
                    submitted.complete(PlaybackClientCleanup.release(client))
                    awaitCancellation()
                }
                val completion = submitted.get(3, TimeUnit.SECONDS)
                assertTrue(probe.entered.await(3, TimeUnit.SECONDS))
                owner.cancel()
                assertFalse("owner 取消不能取消正在执行的独立释放", completion.isDone)
                probe.allow.countDown()
                completion.get(3, TimeUnit.SECONDS)
                assertTrue(probe.closed.await(3, TimeUnit.SECONDS))
                assertEquals(0, client.connectionPool.connectionCount())
                assertTrue(client.dispatcher.executorService.isShutdown)
            } finally {
                owner.cancel()
                probe.allow.countDown()
                PlaybackClientCleanup.release(client).get(3, TimeUnit.SECONDS)
            }
        }
    }

    @Test(timeout = 10_000)
    fun delayedOldCleanupCannotCancelOrCloseNewClient() {
        Fixture().use { fixture ->
            val oldProbe = CloseProbe()
            val old = fixture.idleClient(oldProbe)
            val freshProbe = CloseProbe(block = false)
            var fresh: OkHttpClient? = null
            try {
                val oldCleanup = PlaybackClientCleanup.release(old)
                assertTrue(oldProbe.entered.await(3, TimeUnit.SECONDS))
                val current = fixture.idleClient(freshProbe)
                fresh = current
                assertNotSame(old.connectionPool, current.connectionPool)
                assertNotSame(old.dispatcher, current.dispatcher)
                oldProbe.allow.countDown()
                oldCleanup.get(3, TimeUnit.SECONDS)
                assertEquals("旧清理只关闭自己的连接", 1, current.connectionPool.idleConnectionCount())
                assertEquals(1L, freshProbe.entered.count)
                assertFalse(current.dispatcher.executorService.isShutdown)
                assertEquals("fixture", current.newCall(Request.Builder().url(fixture.url).build()).execute().use { it.body.string() })
                assertEquals(1, current.connectionPool.idleConnectionCount())
            } finally {
                oldProbe.allow.countDown()
                PlaybackClientCleanup.release(old).get(3, TimeUnit.SECONDS)
                fresh?.let { PlaybackClientCleanup.release(it).get(3, TimeUnit.SECONDS) }
            }
        }
    }

    @Test(timeout = 10_000)
    fun evictionFailureIsObservableAndStillShutsDownClientDispatcher() {
        Fixture().use { fixture ->
            val probe = CloseProbe(block = false, closeFailure = IllegalStateException("synthetic close failure"))
            val client = fixture.idleClient(probe)
            val failures = CopyOnWriteArrayList<PlaybackClientCleanup.Stage>()
            val completion = PlaybackClientCleanup.release(client) { stage, _ -> failures += stage }
            val error = assertThrows(ExecutionException::class.java) { completion.get(3, TimeUnit.SECONDS) }
            assertTrue(error.cause is IllegalStateException)
            assertEquals(listOf(PlaybackClientCleanup.Stage.EVICT_CONNECTIONS), failures.toList())
            assertTrue("某一步失败后仍必须执行剩余清理", client.dispatcher.executorService.isShutdown)
            assertNotSame(Thread.currentThread(), probe.thread)
            assertTrue(probe.closed.await(3, TimeUnit.SECONDS))
        }
    }

    private class CloseProbe(val block: Boolean = true, val closeFailure: RuntimeException? = null) {
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(if (block) 1 else 0)
        val closed = CountDownLatch(1)
        @Volatile var thread: Thread? = null
    }

    private class ProbedSocket(private val probe: CloseProbe) : Socket() {
        override fun close() {
            if (isClosed) return
            probe.thread = Thread.currentThread()
            probe.entered.countDown()
            try {
                check(probe.allow.await(5, TimeUnit.SECONDS)) { "Synthetic close gate timed out" }
            } finally {
                super.close()
                probe.closed.countDown()
            }
            probe.closeFailure?.let { throw it }
        }
    }

    private class ProbedSocketFactory(private val probe: CloseProbe) : SocketFactory() {
        override fun createSocket(): Socket = ProbedSocket(probe)
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
    }

    private class Fixture : Closeable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/") { exchange ->
                val bytes = "fixture".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/media"
        fun idleClient(probe: CloseProbe): OkHttpClient {
            val client = OkHttpClient.Builder().socketFactory(ProbedSocketFactory(probe)).build()
            assertEquals("fixture", client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() })
            assertEquals("必须先建立真实空闲连接，再验证 eviction 的 Socket.close", 1, client.connectionPool.idleConnectionCount())
            return client
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}

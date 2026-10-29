package dev.pathfin.android.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(320 * 1024) { (it % 251).toByte() }

    @Test fun completedDownloadHasExpectedContentsAndNoPartialFile() = runBlocking {
        Server { it.reply(payload) }.use { server ->
            val directory = temporary.newFolder()
            val progress = mutableListOf<Long>()
            val apk = downloadRelease(server.release(payload), directory, progress::add)
            assertArrayEquals(payload, apk.readBytes())
            assertEquals(listOf(apk.name), directory.list()!!.toList())
            assertEquals(payload.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })
        }
    }

    @Test fun releasesWithoutADigestStillRequireTheEntireFile() = runBlocking {
        Server { it.reply(payload) }.use { server ->
            val apk = downloadRelease(server.release(payload).copy(sha256 = null), temporary.newFolder()) { }
            assertArrayEquals(payload, apk.readBytes())
        }
    }

    @Test fun wrongSizeOrDigestLeavesNoInstallableOrPartialFile() {
        Server { it.reply(payload) }.use { server ->
            val release = server.release(payload)
            val invalid = listOf(
                release.copy(apkSize = payload.size - 1L),
                release.copy(apkSize = payload.size + 1L),
                release.copy(sha256 = "00".repeat(32)),
            )
            invalid.forEach { candidate ->
                val directory = temporary.newFolder()
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { downloadRelease(candidate, directory) { } }
                }
                assertTrue("失败后不能留下可安装包或半成品", directory.list()!!.isEmpty())
            }
        }
    }

    @Test fun unsuccessfulResponseDoesNotCreateAnApk() {
        Server { it.reply("Unavailable".toByteArray(), code = 503) }.use { server ->
            val directory = temporary.newFolder()
            assertThrows(UpdateFailure::class.java) {
                runBlocking { downloadRelease(server.release(payload), directory) { } }
            }
            assertTrue(directory.list()!!.isEmpty())
        }
    }

    @Test fun cancellationInterruptsBlockedHeadersAndBodyAndCleansFiles() = runBlocking {
        for (sendBody in listOf(false, true)) {
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            Server { exchange ->
                try {
                    if (sendBody) {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write(payload)
                        exchange.responseBody.flush()
                    } else started.countDown()
                    finish.await(10, TimeUnit.SECONDS)
                } catch (_: IOException) { } finally { exchange.close() }
            }.use { server ->
                val directory = temporary.newFolder()
                val job = launch {
                    downloadRelease(server.release(payload).copy(apkSize = payload.size * 2L), directory) {
                        started.countDown()
                    }
                }
                try {
                    assertTrue("合成服务未进入阻塞场景", withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                    withTimeout(2_000) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                    assertTrue("取消结束后不得再遗留写入中的文件", directory.list()!!.isEmpty())
                } finally {
                    finish.countDown()
                    job.cancelAndJoin()
                }
            }
        }
    }

    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/update.apk", handler)
            start()
        }

        fun release(payload: ByteArray) = AppRelease(
            version = "0.2.0", tag = "v0.2.0", notes = "", pageUrl = RELEASES_PAGE,
            apkUrl = "http://127.0.0.1:${server.address.port}/update.apk", apkSize = payload.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        )

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.reply(bytes: ByteArray, code: Int = 200) {
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}

package dev.pathfin.android.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.DownloadSource
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadClientTest {
    @Test fun serverDownloadRedirectsNeverCarryCredentialsToAnotherOriginOrOutsideBasePath() = runBlocking {
        Fixture().use { fixture ->
            val own = fixture.server()
            val foreign = fixture.server()
            val originHeader = AtomicReference<String?>()
            val foreignHeader = AtomicReference<String?>()
            val foreignToken = AtomicReference<String?>()
            val foreignCookie = AtomicReference<String?>()
            val outsideHeader = AtomicReference<String?>()
            val body = mediaBytes(8192)
            foreign.createContext("/file") { exchange ->
                foreignHeader.set(exchange.requestHeaders.getFirst("Authorization"))
                foreignToken.set(exchange.requestHeaders.getFirst("X-Emby-Token"))
                foreignCookie.set(exchange.requestHeaders.getFirst("Cookie"))
                exchange.file(body)
            }
            own.createContext("/jellyfin/Items/item/Download") { exchange ->
                originHeader.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.redirect(fixture.url(foreign, "/file"))
            }
            own.createContext("/jellyfin/Items/outside/Download") { exchange -> exchange.redirect("/outside/file") }
            own.createContext("/outside/file") { exchange ->
                outsideHeader.set(exchange.requestHeaders.getFirst("Authorization")); exchange.file(body)
            }
            val session = session(fixture.url(own, "/jellyfin"))
            val output = ByteArrayOutputStream()
            val receipt = MediaDownloadClient(fixture.client).copy(session, DownloadSource(
                url = fixture.url(own, "/jellyfin/Items/item/Download"),
                headers = mapOf("Cookie" to "server-only=secret", "Authorization" to "stale"),
                fileName = "film.mp4", trustedServer = true,
            ), { output })
            assertTrue(originHeader.get().orEmpty().contains("Token=\"${session.accessToken}\""))
            assertNull(foreignHeader.get())
            assertNull(foreignToken.get())
            assertNull(foreignCookie.get())
            assertEquals(body.size.toLong(), receipt.bytes)
            assertArrayEquals(body, output.toByteArray())
            MediaDownloadClient(fixture.client).copy(session, DownloadSource(
                fixture.url(own, "/jellyfin/Items/outside/Download"), emptyMap(), "film.mp4", trustedServer = true,
            ), { ByteArrayOutputStream() })
            assertNull(outsideHeader.get())
        }
    }

    @Test fun externalSourceHeadersAreUsedAtTheirOwnOriginAndDroppedOnRedirect() = runBlocking {
        Fixture().use { fixture ->
            val sourceServer = fixture.server()
            val cdn = fixture.server()
            val initialAuth = AtomicReference<String?>()
            val redirectedAuth = AtomicReference<String?>()
            val redirectedKey = AtomicReference<String?>()
            sourceServer.createContext("/video") { exchange ->
                initialAuth.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.redirect(fixture.url(cdn, "/video"))
            }
            cdn.createContext("/video") { exchange ->
                redirectedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
                redirectedKey.set(exchange.requestHeaders.getFirst("X-Media-Key"))
                exchange.file(mediaBytes(4096))
            }
            val source = DownloadSource(fixture.url(sourceServer, "/video"),
                mapOf("Authorization" to "Bearer media-only", "X-Media-Key" to "source-private"), "film.mkv")
            MediaDownloadClient(fixture.client).copy(session("http://127.0.0.1:1/jellyfin"), source, { ByteArrayOutputStream() })
            assertEquals("Bearer media-only", initialAuth.get())
            assertNull(redirectedAuth.get())
            assertNull(redirectedKey.get())
        }
    }

    @Test fun anExplicitExternalHttpSourceWorksForAnHttpsJellyfinWithoutItsCredentials() = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val sentAuth = AtomicReference<String?>()
            server.createContext("/video") { exchange ->
                sentAuth.set(exchange.requestHeaders.getFirst("Authorization")); exchange.file(mediaBytes(2048))
            }
            val secureSession = Session(ServerConfig("https://jellyfin.example/jellyfin"), "jf-private-token", "user", "User", "device")
            val output = ByteArrayOutputStream()
            val receipt = MediaDownloadClient(fixture.client).copy(secureSession, DownloadSource(
                fixture.url(server, "/video"), mapOf("Authorization" to "Bearer media-only", "X-Emby-Token" to secureSession.accessToken), "movie.mp4",
            ), { output })
            assertEquals(2048L, receipt.bytes)
            assertEquals("Bearer media-only", sentAuth.get())
            assertFalse(sentAuth.get().orEmpty().contains(secureSession.accessToken))
            assertArrayEquals(mediaBytes(2048), output.toByteArray())
        }
    }

    @Test fun httpsToHttpRedirectIsRejectedEvenWhenTheServerAllowsHttp() = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val insecureRequests = AtomicInteger()
            server.createContext("/video") { exchange -> insecureRequests.incrementAndGet(); exchange.file(mediaBytes(2048)) }
            val redirects = fixture.client.newBuilder().addInterceptor { chain ->
                if (chain.request().url.host == "source.example") Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", fixture.url(server, "/video")).body("".toResponseBody()).build()
                else chain.proceed(chain.request())
            }.build()
            var outputOpened = false
            var rejected = false
            try {
                MediaDownloadClient(redirects).copy(session("https://jellyfin.example/jellyfin"), DownloadSource(
                    "https://source.example/video", mapOf("Authorization" to "Bearer source-only"), "movie.mp4",
                ), { outputOpened = true; ByteArrayOutputStream() })
            } catch (error: DownloadFailure) { rejected = error.message.orEmpty().contains("HTTPS") }
            assertTrue(rejected)
            assertFalse(outputOpened)
            assertEquals(0, insecureRequests.get())
        }
    }

    @Test fun a200PlaylistWithoutAnExtensionNeverOpensTheOutputOrReportsCompletion() = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            server.createContext("/video") { exchange -> exchange.file("#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts\n".toByteArray(), "application/octet-stream") }
            var outputOpened = false
            var progressCalled = false
            var failed = false
            try {
                MediaDownloadClient(fixture.client).copy(session(fixture.url(server, "")), DownloadSource(
                    fixture.url(server, "/video"), emptyMap(), "movie.mp4",
                ), { outputOpened = true; ByteArrayOutputStream() }, { _, _ -> progressCalled = true })
            } catch (_: DownloadFailure) { failed = true }
            assertTrue(failed)
            assertFalse(outputOpened)
            assertFalse(progressCalled)
        }
    }

    @Test fun genericAudioDownloadsInferAudioMimeFromTheSavedName() = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val payload = mediaBytes(4096)
            server.createContext("/download") { exchange -> exchange.file(payload, "application/octet-stream") }
            val client = MediaDownloadClient(fixture.client)
            val session = session(fixture.url(server, ""))
            val formats = linkedMapOf(
                "WAV" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
                "aac" to "audio/aac", "m4a" to "audio/mp4", "flac" to "audio/flac", "mp3" to "audio/mpeg",
            )
            for ((extension, expectedMime) in formats) {
                val output = ByteArrayOutputStream()
                val receipt = client.copy(session, DownloadSource(
                    fixture.url(server, "/download?signature=fixture"), emptyMap(), "Synthetic audio.$extension",
                ), { output })
                assertEquals("$extension 应完成真实文件复制", payload.size.toLong(), receipt.bytes)
                assertArrayEquals(payload, output.toByteArray())
                assertEquals("$extension 音频下载不能变成 video/*，否则打开时找不到音频播放器", expectedMime, receipt.mimeType)
            }
        }
    }

    @Test fun explicitAudioMimeHasPriorityOverGenericResponseAndConflictingFileNames() = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val payload = mediaBytes(2048)
            server.createContext("/generic") { exchange -> exchange.file(payload, "application/octet-stream") }
            server.createContext("/explicit") { exchange -> exchange.file(payload, "audio/flac; charset=binary") }
            val client = MediaDownloadClient(fixture.client)
            val session = session(fixture.url(server, ""))
            val declared = client.copy(session, DownloadSource(
                fixture.url(server, "/generic"), emptyMap(), "opaque-download", mimeType = "audio/opus",
            ), { ByteArrayOutputStream() })
            assertEquals("没有响应媒体类型时保留服务器已解析的音频类型", "audio/opus", declared.mimeType)
            val response = client.copy(session, DownloadSource(
                fixture.url(server, "/explicit"), emptyMap(), "conflicting-name.mp4", mimeType = "audio/opus",
            ), { ByteArrayOutputStream() })
            assertEquals("明确响应类型优先于准备阶段类型及文件扩展", "audio/flac", response.mimeType)
        }
    }

    @Test fun cancellationInterruptsABlockedBodyRead() = runBlocking {
        Fixture().use { fixture ->
            val server = fixture.server()
            val outputOpened = CountDownLatch(1)
            val finishServer = CountDownLatch(1)
            server.createContext("/slow") { exchange ->
                try {
                    exchange.responseHeaders.set("Content-Type", "video/mp4")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(mediaBytes(4096))
                    exchange.responseBody.flush()
                    finishServer.await(10, TimeUnit.SECONDS)
                } catch (_: IOException) { } finally { exchange.close() }
            }
            val job = launch(Dispatchers.Default) {
                MediaDownloadClient(fixture.client).copy(session(fixture.url(server, "")), DownloadSource(
                    fixture.url(server, "/slow"), emptyMap(), "movie.mp4",
                ), { outputOpened.countDown(); ByteArrayOutputStream() })
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { outputOpened.await(5, TimeUnit.SECONDS) })
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            } finally { finishServer.countDown(); job.cancelAndJoin() }
        }
    }

    @Test fun aShortResponseCannotReturnASuccessReceipt() = runBlocking {
        Fixture().use { fixture ->
            // JDK HttpServer 在 Content-Length 不足时会保留连接，用真实 TCP 关闭证明截断分支。
            ServerSocket().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                server.soTimeout = 5_000
                val serving = thread(isDaemon = true) {
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val output = socket.getOutputStream()
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\n" +
                                "Content-Length: 8192\r\nConnection: close\r\n\r\n").toByteArray())
                            output.write(mediaBytes(2048))
                            output.flush()
                        }
                    }
                }
                val origin = "http://127.0.0.1:${server.localPort}"
                var completed = false
                try {
                    MediaDownloadClient(fixture.client).copy(session(origin), DownloadSource(
                        "$origin/short", emptyMap(), "movie.mp4",
                    ), { ByteArrayOutputStream() })
                    completed = true
                } catch (error: IOException) {
                    assertFalse("必须由实际截断触发，不能依赖读取超时", error is SocketTimeoutException)
                } finally {
                    server.close()
                    serving.join(1_000)
                }
                assertFalse(completed)
            }
        }
    }

    private fun session(baseUrl: String) = Session(ServerConfig(baseUrl, allowInsecureHttp = true), "jf-private-token", "user", "User", "device")
    private fun mediaBytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun HttpExchange.file(bytes: ByteArray, mime: String = "video/mp4") {
        responseHeaders.set("Content-Type", mime)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun HttpExchange.redirect(location: String) {
        responseHeaders.set("Location", location)
        sendResponseHeaders(302, -1)
        close()
    }

    private class Fixture : Closeable {
        val client = OkHttpClient()
        private val servers = mutableListOf<HttpServer>()
        private val executor = Executors.newCachedThreadPool()
        fun server(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = this@Fixture.executor
            start()
            servers += this
        }
        fun url(server: HttpServer, path: String) = "http://127.0.0.1:${server.address.port}$path"
        override fun close() {
            servers.forEach { it.stop(0) }
            executor.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}

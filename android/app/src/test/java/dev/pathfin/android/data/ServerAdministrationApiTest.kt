package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.*
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerAdministrationApiTest {
    @Test fun actualInfoTasksAndPagedActivityShapesAreMappedWithoutWrites() {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            assertEquals("GET", exchange.requestMethod); paths += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/proxy/System/Info" -> exchange.reply("""{"ServerName":"Synthetic server","Version":"12.3.0","ProductName":"Jellyfin","HasPendingRestart":true,"IsShuttingDown":false,"CanSelfRestart":false}""")
                "/proxy/ScheduledTasks" -> exchange.reply("""[
                    {"Id":"scan","Name":"Scan library","State":"Running","CurrentProgressPercentage":25.5,
                     "LastExecutionResult":{"StartTimeUtc":"2026-10-24T00:00:00Z","EndTimeUtc":"2026-10-24T00:00:03Z","Status":"Failed"}},
                    {"Id":"chapter","Name":"Extract chapters","State":"Cancelling"},
                    {"Id":"cleanup","Name":"Clean cache","State":"Idle"},
                    {"Id":"future","Name":"Future state","State":"UnknownFuture","CurrentProgressPercentage":1000}]""")
                "/proxy/System/ActivityLog/Entries" -> {
                    val query = "http://localhost${exchange.requestURI}".toHttpUrl()
                    assertEquals("30", query.queryParameter("limit"))
                    val start = query.queryParameter("startIndex")!!.toInt()
                    exchange.reply("""{"Items":[{"Id":${start + 1},"Name":"Task completed","Date":"2026-10-24T01:00:00Z","Severity":"Information"}],"TotalRecordCount":2,"StartIndex":$start}""")
                }
                else -> error("错误路由")
            }
        }) { session -> runBlocking {
            val api = ServerAdministrationApi()
            val info = api.info(session)
            assertEquals("12.3.0", info.version); assertTrue(info.hasPendingRestart); assertFalse(info.canSelfRestart)
            val tasks = api.tasks(session)
            assertEquals(listOf(ServerTaskState.RUNNING, ServerTaskState.CANCELLING, ServerTaskState.IDLE, ServerTaskState.UNKNOWN), tasks.map { it.state })
            assertEquals(25.5, tasks[0].progressPercent!!, 0.0)
            assertNull(tasks[3].progressPercent); assertEquals("Failed", tasks[0].lastResult)
            val first = api.activity(session); val second = api.activity(session, startIndex = 1)
            assertEquals(2, first.total); assertEquals(1L, first.items.single().id); assertEquals(2L, second.items.single().id)
        } }
        assertEquals(4, paths.size)
    }

    @Test fun taskRunCancelAndRestartUseActualMethodsAndEmptyBodies() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            assertEquals(0, exchange.requestBody.readBytes().size)
            exchange.reply("", 204)
        }) { session -> runBlocking {
            val api = ServerAdministrationApi()
            api.runTask(session, "task-42"); api.cancelTask(session, "task-42"); api.restart(session)
        } }
        assertEquals(listOf("POST /proxy/ScheduledTasks/Running/task-42", "DELETE /proxy/ScheduledTasks/Running/task-42", "POST /proxy/System/Restart"), requests.toList())
    }

    @Test fun nonAdminAndInvalidIdentifiersCannotReadOrWriteAdministrativeEndpoints() {
        val calls = AtomicInteger()
        withServer({ exchange -> calls.incrementAndGet(); exchange.reply("[]") }) { session -> runBlocking {
            val api = ServerAdministrationApi(); val denied = session.copy(isAdministrator = false)
            val readsAndWrites: List<suspend () -> Unit> = listOf({ api.info(denied); Unit }, { api.tasks(denied); Unit },
                { api.activity(denied); Unit }, { api.logs(denied); Unit }, { api.readLog(denied, ServerLogFile("log.txt", null, 0)); Unit },
                { api.runTask(denied, "task") }, { api.cancelTask(denied, "task") }, { api.restart(denied) })
            readsAndWrites.forEach { assertEquals(403, expectApiFailure(it).statusCode) }
            for (id in listOf("", "../task", "task?secret=x", "task/other")) assertThrows(IllegalArgumentException::class.java) { runBlocking { api.runTask(session, id) } }
            for (name in listOf("../log.txt", "log/part.txt", "..", "log\nname")) assertThrows(IllegalArgumentException::class.java) { runBlocking { api.readLog(session, ServerLogFile(name, null, 0)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.activity(session, -1) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.activity(session, limit = 101) } }
        } }
        assertEquals(0, calls.get())
    }

    @Test fun largePlainLogIsBoundedAndCredentialsAreRedactedWithoutUrlAuthentication() {
        val requests = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/proxy/System/Logs" -> exchange.reply("""[{"Name":"log synthetic.txt","DateModified":"2026-10-24T00:00:00Z","Size":900000}]""")
                "/proxy/System/Logs/Log" -> {
                    val url = "http://localhost${exchange.requestURI}".toHttpUrl()
                    assertEquals("log synthetic.txt", url.queryParameter("name"))
                    assertNull(url.queryParameter("api_key"))
                    val prefix = "Authorization: MediaBrowser Token=synthetic-token\nhttps://example/video?api_key=other-secret&x=1\npassword=private-password\n"
                    // 前缀读取会主动关闭响应，服务端写入剩余字节时断管是预期取消。
                    try { exchange.reply(prefix + "a".repeat(ServerAdministrationApi.MAX_LOG_BYTES + 1000), contentType = "text/plain") }
                    catch (_: IOException) { }
                }
                else -> error("错误路由")
            }
        }) { session -> runBlocking {
            val api = ServerAdministrationApi(); val file = api.logs(session).single(); val content = api.readLog(session, file)
            assertEquals("log synthetic.txt", file.name); assertTrue(content.truncated)
            assertFalse(content.text.contains("synthetic-token")); assertFalse(content.text.contains("other-secret")); assertFalse(content.text.contains("private-password"))
            assertTrue(content.text.length <= ServerAdministrationApi.MAX_LOG_BYTES + 100)
        } }
        assertEquals(2, requests.size)
    }

    @Test fun rejectedReadAndWriteErrorsAreSafeAndDoNotSilentlyFallback() {
        val calls = AtomicInteger()
        withServer({ exchange -> calls.incrementAndGet(); exchange.reply("{\"detail\":\"synthetic-token private-path\"}", 403) }) { session -> runBlocking {
            val api = ServerAdministrationApi()
            val operations: List<suspend () -> Unit> = listOf({ api.tasks(session); Unit }, { api.restart(session) })
            for (operation in operations) {
                val error = expectApiFailure(operation); assertEquals(403, error.statusCode)
                assertFalse(error.message.orEmpty().contains("synthetic-token")); assertFalse(error.message.orEmpty().contains("private-path"))
            }
        } }
        assertEquals(2, calls.get())
    }

    private suspend fun expectApiFailure(block: suspend () -> Unit): ApiException = try { block(); throw AssertionError("应失败") } catch (error: ApiException) { error }
    private fun withServer(handler: (HttpExchange) -> Unit, block: (Session) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); val failure = AtomicReference<Throwable?>()
        server.executor = executor
        server.createContext("/") { exchange ->
            try { assertTrue(exchange.requestHeaders.getFirst("Authorization").orEmpty().contains("synthetic-token")); handler(exchange) }
            catch (error: Throwable) { failure.compareAndSet(null, error); runCatching { exchange.reply("", 500) } }
            finally { exchange.close() }
        }
        server.start()
        try { block(Session(ServerConfig("http://127.0.0.1:${server.address.port}/proxy", allowInsecureHttp = true),
            "synthetic-token", "user", "Synthetic", "device", isAdministrator = true)) }
        finally { server.stop(0); executor.shutdownNow(); failure.get()?.let { throw AssertionError("管理端点断言失败", it) } }
    }
    private fun HttpExchange.reply(text: String, code: Int = 200, contentType: String = "application/json") {
        responseHeaders.add("Content-Type", contentType)
        val bytes = text.toByteArray(); sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
        if (code != 204) responseBody.use { it.write(bytes) }
    }
}

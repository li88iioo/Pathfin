package dev.pathfin.android

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 真实 MainActivity、HTTP 重新协商与 ExoPlayer；所有账号、版本和视频均为独立回环合成数据。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SameMediaRetryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolatedStorage = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == TARGET) { "同片重试回归只允许 verification 包" }
            val sessions = context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE)
            val previousSessions = sessions.all.toMap()
            val preferencesStore = AppPreferencesStore(context)
            val previousPreferences = preferencesStore.load()
            check(sessions.edit().clear().commit())
            preferencesStore.save(preferences())
            try { base.evaluate() }
            finally {
                restore(sessions, previousSessions)
                preferencesStore.save(previousPreferences)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test fun sameMediaPreparationRenegotiatesTheSelectedVersionAtTheCurrentPosition() = withPlayingVersionTwo { fixture, active ->
        val first = main { checkNotNull(active.playback.player) }
        val firstRequest = main { checkNotNull(active.app.state.value.activePlayback) }
        main { first.pause(); first.seekTo(6_250) }
        await(fixture) { main { first.playbackState == Player.STATE_READY && !first.isPlaying && abs(first.currentPosition - 6_250) <= 300 } }
        val position = main { first.currentPosition }
        val preparation = fixture.holdNextPlaybackInfo()
        main {
            active.app.preparePlayback(firstRequest.item.copy(positionTicks = TimeUnits.msToTicks(position)), sourceId = SECOND_SOURCE)
            val pending = checkNotNull(active.app.state.value.pendingPlayback)
            assertEquals(ITEM, pending.item.id)
            assertEquals(SECOND_SOURCE, pending.sourceId)
            assertEquals(position, TimeUnits.ticksToMs(pending.item.positionTicks))
            assertSame(firstRequest, active.app.state.value.activePlayback)
        }
        preparation.awaitRequest()
        main {
            assertTrue(active.app.state.value.preparingPlay)
            assertSame("接口准备期间保留当前已选版本的播放入口", firstRequest, active.app.state.value.activePlayback)
            assertSame(first, active.playback.player)
            assertFalse(first.isReleased)
        }
        preparation.release()
        await(fixture) { main {
            failIfPlaybackFailed(active)
            val player = active.playback.player
            player != null && player !== first && active.playback.state.value.hasRenderedFrame &&
                player.isPlaying && player.currentPosition >= position - 300
        } }
        val nextRequest = main { checkNotNull(active.app.state.value.activePlayback) }
        main {
            assertNull(active.app.state.value.pendingPlayback)
            assertEquals(SECOND_SOURCE, nextRequest.source.id)
            assertEquals(position, nextRequest.startPositionMs)
            assertTrue("重新准备不能将同片进度清零", abs(checkNotNull(active.playback.player).currentPosition - position) <= 1_500)
            assertTrue(first.isReleased)
            assertFalse(compose.activity.isFinishing)
        }
        assertEquals(TimeUnits.msToTicks(position), fixture.startTicks(2))
        awaitReports(active)
        assertEquals(1, fixture.events("start", firstRequest.info.playSessionId))
        assertEquals(1, fixture.events("stop", firstRequest.info.playSessionId))
        assertEquals(1, fixture.events("start", nextRequest.info.playSessionId))
        assertEquals(0, fixture.events("stop", nextRequest.info.playSessionId))
        assertEquals(2, fixture.totalStarts())
        assertEquals(2, fixture.playbackRequests.get())
        assertTrue("两个会话必须读取选择的第二个媒体版本", fixture.mediaRequests(SECOND_SOURCE) >= 2)
        assertEquals("重新准备不能改选默认的第一个版本", 0, fixture.mediaRequests(FIRST_SOURCE))
    }

    @Test fun failedPlaybackInfoPreparationKeepsTheOldPlayerPlayingWithoutAnEarlyStop() = withPlayingVersionTwo { fixture, active ->
        val player = main { checkNotNull(active.playback.player) }
        val request = main { checkNotNull(active.app.state.value.activePlayback) }
        val position = main { player.currentPosition }
        val preparation = fixture.holdNextPlaybackInfo(status = 503)
        main { active.app.preparePlayback(request.item.copy(positionTicks = TimeUnits.msToTicks(position)), sourceId = SECOND_SOURCE) }
        preparation.awaitRequest()
        main {
            assertTrue(active.app.state.value.preparingPlay)
            assertSame(request, active.app.state.value.activePlayback)
            assertSame(player, active.playback.player)
            assertTrue(player.isPlaying)
        }
        preparation.release()
        await(fixture) { main {
            val state = active.app.state.value
            !state.preparingPlay && state.playbackError != null && player.currentPosition > position + 300
        } }
        main {
            assertSame("准备失败不得提交新的播放入口", request, active.app.state.value.activePlayback)
            assertSame("准备失败应保留同一个真实播放器", player, active.playback.player)
            assertFalse(player.isReleased)
            assertTrue(player.isPlaying)
            assertNull(player.playerError)
            assertNull(active.app.state.value.sourceSelection)
            val pending = checkNotNull(active.app.state.value.pendingPlayback)
            assertEquals(ITEM, pending.item.id)
            assertEquals(SECOND_SOURCE, pending.sourceId)
            assertFalse(compose.activity.isFinishing)
        }
        awaitReports(active)
        assertEquals(2, fixture.playbackRequests.get())
        assertEquals(1, fixture.events("start", request.info.playSessionId))
        assertEquals("新会话尚未准备成功，不得提前上报停止旧播放", 0, fixture.events("stop", request.info.playSessionId))
        assertEquals(1, fixture.totalStarts())
        assertEquals(0, fixture.mediaRequests(FIRST_SOURCE))
        main {
            val pending = active.app.state.value.pendingPlayback
            active.app.dismissPlaybackError()
            assertSame("关闭提示后仍须保留可重试的播放目标", pending, active.app.state.value.pendingPlayback)
            assertNull(active.app.state.value.playbackError)
            active.app.retryPendingPlayback()
        }
        await(fixture) { main {
            failIfPlaybackFailed(active)
            val next = active.playback.player
            next != null && next !== player && active.playback.state.value.hasRenderedFrame && next.isPlaying
        } }
        val retried = main { checkNotNull(active.app.state.value.activePlayback) }
        main {
            assertTrue(player.isReleased)
            assertEquals(SECOND_SOURCE, retried.source.id)
            assertEquals(position, retried.startPositionMs)
            assertNull(active.app.state.value.pendingPlayback)
        }
        assertEquals(TimeUnits.msToTicks(position), fixture.startTicks(3))
        awaitReports(active)
        assertEquals(3, fixture.playbackRequests.get())
        assertEquals(2, fixture.totalStarts())
        assertEquals(1, fixture.events("stop", request.info.playSessionId))
        assertEquals(1, fixture.events("start", retried.info.playSessionId))
        assertEquals(0, fixture.events("stop", retried.info.playSessionId))
        assertEquals(0, fixture.mediaRequests(FIRST_SOURCE))
    }

    private fun withPlayingVersionTwo(block: (Fixture, Active) -> Unit) {
        val video = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        Fixture(video).use { fixture ->
            val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
            val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
            val active = Active(app, playback)
            var failure: Throwable? = null
            try {
                await(fixture) { !app.state.value.restoringSession }
                main { app.login(fixture.origin, USER, PASSWORD, true, "Playback retry fixture") }
                await(fixture) {
                    app.state.value.loginError?.let { throw AssertionError("合成账号登录失败：$it") }
                    app.state.value.session?.serverId == SERVER_ID && app.state.value.home.loaded && !app.state.value.home.loading
                }
                main { app.open(MediaItem(ITEM, "Retry fixture movie", "Movie", runTimeTicks = 240_000_000L)) }
                await(fixture) { ITEM in app.state.value.details && !app.state.value.detailLoading }
                main { app.preparePlayback(app.state.value.details.getValue(ITEM), fromStart = true) }
                await(fixture) { app.state.value.sourceSelection?.info?.sources?.size == 2 && !app.state.value.preparingPlay }
                main { app.chooseSource(SECOND_SOURCE) }
                await(fixture) { main {
                    failIfPlaybackFailed(active)
                    app.state.value.activePlayback?.source?.id == SECOND_SOURCE &&
                        playback.state.value.hasRenderedFrame && playback.player?.isPlaying == true
                } }
                block(fixture, active)
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                val cleanup = runCatching {
                    main { playback.stop(); app.playerClosed(playback.lastPositionMs); app.dismissPlaybackError() }
                    awaitReports(active)
                }.exceptionOrNull()
                if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private fun failIfPlaybackFailed(active: Active) {
        active.app.state.value.playbackError?.let { throw AssertionError("合成媒体准备失败：$it") }
        active.playback.state.value.errorMessage?.let { throw AssertionError("实际播放器播放失败：$it") }
    }
    private fun await(fixture: Fixture, condition: () -> Boolean) = compose.waitUntil(WAIT) { fixture.assertHealthy(); condition() }
    private fun awaitReports(active: Active) = runBlocking { withTimeout(WAIT) { active.playback.awaitPendingReports() } }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)
    private data class Active(val app: AppViewModel, val playback: PlaybackViewModel)

    private class ResponseGate(val status: Int) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun awaitRequest() = assertTrue("准备请求必须实际到达回环服务", entered.await(WAIT, TimeUnit.MILLISECONDS))
        fun waitForRelease() { entered.countDown(); check(released.await(WAIT, TimeUnit.MILLISECONDS)) { "合成准备响应未释放" } }
        fun release() = released.countDown()
    }

    private class Fixture(private val video: ByteArray) : Closeable {
        val playbackRequests = AtomicInteger()
        private val nextPreparation = AtomicReference<ResponseGate?>()
        private val gates = ConcurrentLinkedQueue<ResponseGate>()
        private val preparationPositions = ConcurrentHashMap<Int, Long>()
        private val successfulSessions = ConcurrentHashMap.newKeySet<String>()
        private val reports = ConcurrentLinkedQueue<Pair<String, String>>()
        private val mediaSources = ConcurrentLinkedQueue<String>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closed = AtomicBoolean()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-same-media-retry-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val origin = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                workers.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures += error }
        }, "pathfin-same-media-retry-accept").apply { isDaemon = true; start() }

        fun holdNextPlaybackInfo(status: Int = 200) = ResponseGate(status).also {
            check(nextPreparation.compareAndSet(null, it)); gates += it
        }
        fun startTicks(request: Int) = checkNotNull(preparationPositions[request])
        fun events(event: String, session: String) = reports.count { it == (event to session) }
        fun totalStarts() = reports.count { it.first == "start" }
        fun mediaRequests(sourceId: String) = mediaSources.count { it == sourceId }
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("同片重试 fixture 协议失败", it) } }

        private fun serve(socket: Socket) {
            var validMediaRequest = false
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT.toInt()
                    val input = connection.getInputStream().buffered()
                    val line = input.line() ?: return
                    val parts = line.split(' ')
                    check(parts.size == 3 && parts[2] == "HTTP/1.1")
                    val headers = linkedMapOf<String, String>()
                    var headerCount = 0
                    while (true) {
                        val header = input.line() ?: throw EOFException("合成请求头未完成")
                        if (header.isEmpty()) break
                        check(++headerCount <= 64 && ':' in header)
                        headers[header.substringBefore(':').lowercase(Locale.ROOT)] = header.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    check(length in 0..65_536)
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        if (count < 0) throw EOFException("合成请求体未完成")
                        offset += count
                    }
                    val uri = URI(parts[1])
                    val method = parts[0]
                    val path = uri.path
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        val pair = it.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    if (path != "/Users/AuthenticateByName" && path != "/System/Info/Public") {
                        val auth = headers["authorization"] ?: headers["x-emby-authorization"]
                        check(auth.orEmpty().contains(TOKEN)) { "合成接口缺少当前账号的认证头" }
                    }
                    if (path.startsWith("/media/")) {
                        check(method == "GET" || method == "HEAD")
                        val source = path.removePrefix("/media/").removeSuffix(".mp4")
                        check(source in setOf(FIRST_SOURCE, SECOND_SOURCE))
                        mediaSources += source
                        val range = headers["range"]?.let { checkNotNull(Regex("bytes=(\\d+)-(\\d*)").matchEntire(it)) }
                        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
                        check(start in video.indices && end >= start)
                        validMediaRequest = true
                        respond(connection, if (range == null) 200 else 206, video.copyOfRange(start, end + 1), "video/mp4",
                            head = method == "HEAD", range = if (range == null) null else "bytes $start-$end/${video.size}")
                    } else if (path == "/Items/$ITEM/PlaybackInfo") {
                        check(method == "POST")
                        val payload = JSONObject(String(body, Charsets.UTF_8))
                        check(payload.getString("UserId") == USER_ID)
                        val index = playbackRequests.incrementAndGet()
                        preparationPositions[index] = payload.getLong("StartTimeTicks")
                        val gate = nextPreparation.getAndSet(null)
                        gate?.waitForRelease()
                        val status = gate?.status ?: 200
                        if (status != 200) respond(connection, status, "Synthetic playback preparation failure".toByteArray(), "text/plain")
                        else {
                            val session = "retry-session-$index"
                            successfulSessions += session
                            json(connection, JSONObject().put("MediaSources", JSONArray().put(source(FIRST_SOURCE)).put(source(SECOND_SOURCE)))
                                .put("PlaySessionId", session))
                        }
                    } else {
                        val response: Any? = when {
                            path == "/Users/AuthenticateByName" -> {
                                check(method == "POST")
                                val credentials = JSONObject(String(body, Charsets.UTF_8))
                                check(credentials.getString("Username") == USER && credentials.getString("Pw") == PASSWORD)
                                JSONObject().put("AccessToken", TOKEN).put("ServerId", SERVER_ID).put("User", user())
                            }
                            path == "/System/Info/Public" -> JSONObject().put("Id", SERVER_ID).put("ServerName", "Playback retry fixture").put("Version", "12.3.0")
                            path == "/Users/Me" -> user()
                            path == "/UserViews" -> page(JSONObject().put("Id", "retry-library").put("Name", "Retry movies").put("Type", "CollectionFolder").put("CollectionType", "movies").put("IsFolder", true))
                            path == "/Items/Latest" -> JSONArray().put(item())
                            path == "/UserItems/Resume" || path.endsWith("/Similar") -> page()
                            path == "/Shows/NextUp" -> {
                                check(method == "GET" && query["enableResumable"] == "false")
                                page()
                            }
                            path == "/Items" -> { check(query["ids"] == ITEM); page(item()) }
                            path.startsWith("/Sessions/Playing") -> {
                                check(method == "POST")
                                val event = when (path) { "/Sessions/Playing" -> "start"; "/Sessions/Playing/Progress" -> "progress"; "/Sessions/Playing/Stopped" -> "stop"; else -> error("未知上报接口") }
                                val report = JSONObject(String(body, Charsets.UTF_8))
                                check(report.getString("ItemId") == ITEM && report.getString("MediaSourceId") == SECOND_SOURCE)
                                val session = report.getString("PlaySessionId")
                                check(session in successfulSessions)
                                reports += event to session
                                null
                            }
                            else -> error("同片重试 fixture 未定义的接口")
                        }
                        json(connection, response)
                    }
                }
            } catch (error: Throwable) {
                // 媒体 seek/释放可主动中断已确认的媒体响应，协议断言与 API 失败不能被吞掉。
                if (!closed.get() && !(validMediaRequest && error is IOException)) failures += error
            } finally { sockets -= socket }
        }

        private fun user() = JSONObject().put("Id", USER_ID).put("Name", "Retry fixture user")
        private fun item() = JSONObject().put("Id", ITEM).put("Name", "Retry fixture movie").put("Type", "Movie")
            .put("RunTimeTicks", 240_000_000L).put("ImageTags", JSONObject())
        private fun source(id: String) = JSONObject().put("Id", id).put("Name", if (id == SECOND_SOURCE) "Version two" else "Version one")
            .put("Path", "/synthetic/$id.mp4").put("Protocol", "File").put("Container", "mp4")
            .put("DirectStreamUrl", "$origin/media/$id.mp4").put("SupportsDirectPlay", true).put("SupportsDirectStream", true)
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size)
        private fun json(socket: Socket, value: Any?) = respond(socket, if (value == null) 204 else 200,
            value?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0), "application/json")
        private fun respond(socket: Socket, status: Int, bytes: ByteArray, type: String, head: Boolean = false, range: String? = null) {
            val header = buildString {
                append("HTTP/1.1 $status Synthetic\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nAccept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); if (!head) write(bytes); flush() }
        }
        private fun InputStream.line(): String? {
            val line = StringBuilder()
            while (true) {
                val value = read()
                if (value == -1) { if (line.isEmpty()) return null else throw EOFException("合成请求行未完成") }
                if (value == 10) return line.toString().removeSuffix("\r")
                check(line.length < 8_192)
                line.append(value.toChar())
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            gates.forEach(ResponseGate::release)
            server.close()
            sockets.toList().forEach { runCatching { it.close() } }
            workers.shutdownNow()
            accept.join(1_000)
            assertHealthy()
        }
    }

    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val SERVER_ID = "same-media-retry-fixture"
        const val USER_ID = "same-media-retry-user"
        const val USER = "retry-fixture-user"
        const val PASSWORD = "retry-fixture-password"
        const val TOKEN = "same-media-retry-synthetic-token"
        const val ITEM = "retry-fixture-movie"
        const val FIRST_SOURCE = "retry-source-one"
        const val SECOND_SOURCE = "retry-source-two"
        const val WAIT = 30_000L
        fun preferences() = AppPreferences(originalPathEnabled = false,
            subtitlesEnabled = false, exoAssEnabled = false, serverIntroSkipEnabled = false,
            autoPlayNextEpisode = false, showClock = false, showBattery = false)

        fun restore(preferences: SharedPreferences, values: Map<String, *>) {
            val editor = preferences.edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> { check(value.all { it is String }); editor.putStringSet(key, value.filterIsInstance<String>().toSet()) }
                else -> error("不支持的 verification 会话偏好类型")
            } }
            check(editor.commit())
        }
    }
}

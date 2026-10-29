package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.CloudPlayback
import dev.pathfin.android.domain.CloudVariant
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.PlayerScreen
import dev.pathfin.ffmpeg.video.NativeVideoDecoder
import dev.pathfin.ffmpeg.video.NativeVideoRenderer
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 经真实播放线程注入 Renderer 错误；不反射监听器或替换 Player，回退后必须实际呈现帧。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NativeVideoRecoveryTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private var contentInstalled = false

    @Test fun localAudioAndVideoDecoderFailuresRebuildTheSameSourceAndRetainPausedPositionSpeedTracksLockAndReports() {
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) withFixture { fixture, playback, preferences ->
            val request = fixture.request()
            main { playback.play(request, preferences) }
            awaitReady(playback)
            val previous = main {
                checkNotNull(playback.player).also { owner ->
                    owner.pause()
                    playback.seekToPosition(6_000)
                    playback.setPlaybackSpeed(1.75f)
                    playback.setControlsLocked(true)
                    val group = owner.currentTracks.groups.single { group ->
                        group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any { group.getTrackFormat(it).label == JAPANESE }
                    }
                    val index = (0 until group.length).single { group.getTrackFormat(it).label == JAPANESE }
                    playback.onManualTrackSelection(owner, C.TRACK_TYPE_AUDIO, group.getTrackFormat(index), false)
                    playback.onManualTrackSelection(owner, C.TRACK_TYPE_TEXT, null, true)
                    owner.trackSelectionParameters = owner.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
            }
            compose.waitUntil(WAIT) { main {
                previous.playbackState == Player.STATE_READY && abs(previous.currentPosition - 6_000) < 250 &&
                    selectedAudio(previous) == JAPANESE
            } }
            assertFalse("正常 AVC 必须先实际走系统 Renderer", activeRenderer(previous) is NativeVideoRenderer)
            assertFalse("正常 AAC 必须先实际走系统 Renderer", activeRenderer(previous, C.TRACK_TYPE_AUDIO) is FfmpegAudioRenderer)
            drainReports(playback)
            compose.waitUntil(WAIT) { fixture.segmentRequests.get() == 1 && playback.state.value.introPrompt?.fromServer == true }
            val mediaReads = fixture.mediaTargets.size
            injectDecoderFailure(previous, type = type)
            awaitReady(playback, previous)
            val next = main { checkNotNull(playback.player) }
            compose.waitUntil(WAIT) { main { selectedAudio(next) == JAPANESE } }
            assertEquals("视频恢复才切换视频 Renderer；音频失败不能把正常视频改为软解", type == C.TRACK_TYPE_VIDEO,
                activeRenderer(next) is NativeVideoRenderer)
            assertEquals("音频恢复必须实际选用 FFmpeg；视频失败不改变正常音频", type == C.TRACK_TYPE_AUDIO,
                activeRenderer(next, C.TRACK_TYPE_AUDIO) is FfmpegAudioRenderer)
            main {
                assertTrue(previous.isReleased)
                assertEquals(request.stream, playback.currentRequest?.stream)
                assertSame(request.info, playback.currentRequest?.info)
                assertSame(request.source, playback.currentRequest?.source)
                assertSame(request.cloudPlayback, playback.currentRequest?.cloudPlayback)
                assertFalse(next.playWhenReady)
                assertFalse(next.isPlaying)
                assertTrue("position=${next.currentPosition}", abs(next.currentPosition - 6_000) < 250)
                assertEquals(1.75f, next.playbackParameters.speed, .001f)
                assertEquals(JAPANESE, selectedAudio(next))
                assertTrue(C.TRACK_TYPE_TEXT in next.trackSelectionParameters.disabledTrackTypes)
                assertTrue(playback.state.value.controlsLocked)
                assertNull(playback.state.value.decodeWarning)
            }
            drainReports(playback)
            assertTrue("同一源必须真的重新读取", fixture.mediaTargets.size > mediaReads)
            assertEquals("不能改写签名地址或跳到其它交付路径", 1, fixture.mediaTargets.distinct().size)
            assertEquals("本地重建不能重复请求 Jellyfin 片头信息", 1, fixture.segmentRequests.get())
            assertEquals(1, fixture.reports.count { it.first == "/Sessions/Playing" })
            assertEquals(0, fixture.reports.count { it.first == "/Sessions/Playing/Stopped" })
            main { next.play() }
            compose.waitUntil(WAIT) { main { next.isPlaying && next.currentPosition > 6_500 } }
            main { playback.stop() }
            drainReports(playback)
            assertEquals("同一个会话不能重复 start", 1, fixture.reports.count { it.first == "/Sessions/Playing" })
            assertEquals(1, fixture.reports.count { it.first == "/Sessions/Playing/Stopped" })
            fixture.reports.forEach { assertEquals(request.info.playSessionId, it.second.getString("PlaySessionId")) }
        }
    }

    @Test fun nativeRebuildRestartsOnlyTheStillPendingIntroQueryAndPublishesItsResult() =
        withFixture(blockFirstSegments = true) { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback)
            assertTrue("原播放器必须已经开始等待真实片头响应", fixture.firstSegmentsReceived.await(5, TimeUnit.SECONDS))
            val previous = main { checkNotNull(playback.player).also {
                it.pause()
                playback.seekToPosition(1_000)
            } }
            injectDecoderFailure(previous)
            awaitReady(playback, previous)
            fixture.releaseFirstSegments.countDown()
            assertTrue("Native 重建取消尚未完成的查询后，必须给同一媒体重新查询片头",
                fixture.replacementSegmentsReceived.await(5, TimeUnit.SECONDS))
            compose.waitUntil(WAIT) { playback.state.value.introPrompt?.fromServer == true }
            main {
                assertEquals("server:3234567890abcdef1234567890abcdef", playback.state.value.introPrompt?.key)
                assertEquals(9_000L, playback.state.value.introPrompt?.endMs)
                assertEquals(ITEM, playback.currentRequest?.item?.id)
                assertFalse(checkNotNull(playback.player).playWhenReady)
                assertNull(playback.state.value.mediaSegmentsError)
            }
            assertEquals("仅重发被取消的 pending 查询，不能形成重复刷新", 2, fixture.segmentRequests.get())
        }

    @Test fun nativeFailureDoesNotLoopAndANewEntryStillUsesTheSavedHardwarePreference() =
        withFixture { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback)
            val hardware = main { checkNotNull(playback.player) }
            injectDecoderFailure(hardware, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            awaitReady(playback, hardware)
            val native = main { checkNotNull(playback.player) }
            assertTrue(activeRenderer(native) is NativeVideoRenderer)
            val reads = fixture.mediaTargets.size
            injectDecoderFailure(native, extension = true)
            compose.waitUntil(WAIT) { playback.state.value.errorMessage != null }
            main { assertSame("Native 自身失败不能再重建一次", native, playback.player) }
            assertEquals(reads, fixture.mediaTargets.size)
            main { playback.play(fixture.request(NEXT_ITEM, "native-recovery-next"), preferences) }
            awaitReady(playback, native)
            val next = main { checkNotNull(playback.player) }
            assertFalse("临时回退不能改写下一入口的硬解偏好", activeRenderer(next) is NativeVideoRenderer)
            injectDecoderFailure(next)
            awaitReady(playback, next)
            assertTrue("新入口必须得到自己的一次本地恢复预算", activeRenderer(main { checkNotNull(playback.player) }) is NativeVideoRenderer)
        }

    @Test fun audioRecoveryKeepsItsOwnOneAttemptBudgetAfterVideoRecoveryAndDoesNotChangeTheNextEntryPreference() =
        withFixture { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback)
            val hardware = main { checkNotNull(playback.player) }
            injectDecoderFailure(hardware)
            awaitReady(playback, hardware)
            val videoRecovered = main { checkNotNull(playback.player) }
            assertTrue(activeRenderer(videoRecovered) is NativeVideoRenderer)
            injectDecoderFailure(videoRecovered, type = C.TRACK_TYPE_AUDIO)
            awaitReady(playback, videoRecovered)
            val bothRecovered = main { checkNotNull(playback.player) }
            assertTrue("音频恢复不能丢掉同入口已选的视频补解", activeRenderer(bothRecovered) is NativeVideoRenderer)
            assertTrue(activeRenderer(bothRecovered, C.TRACK_TYPE_AUDIO) is FfmpegAudioRenderer)
            val reads = fixture.mediaTargets.size
            injectDecoderFailure(bothRecovered, extension = true, type = C.TRACK_TYPE_AUDIO)
            compose.waitUntil(WAIT) { playback.state.value.errorMessage != null }
            main { assertSame("FFmpeg 音频自身失败不能重复重建", bothRecovered, playback.player) }
            assertEquals("一次本地音频恢复失败后必须停止重试", reads, fixture.mediaTargets.size)
            main { playback.play(fixture.request(NEXT_ITEM, "native-recovery-next"), preferences) }
            awaitReady(playback, bothRecovered)
            val next = main { checkNotNull(playback.player) }
            assertFalse(activeRenderer(next) is NativeVideoRenderer)
            assertFalse("临时恢复不能把首选音频解码器永久改为软件", activeRenderer(next, C.TRACK_TYPE_AUDIO) is FfmpegAudioRenderer)
            injectDecoderFailure(next, type = C.TRACK_TYPE_AUDIO)
            awaitReady(playback, next)
            assertTrue("新播放入口需要独立的一次音频恢复机会",
                activeRenderer(main { checkNotNull(playback.player) }, C.TRACK_TYPE_AUDIO) is FfmpegAudioRenderer)
        }

    @Test fun unsupportedAudioSourceAndDrmFailuresDoNotEnterTheLocalDecoderRecoveryPath() {
        for (kind in listOf("audio", "source", "drm", "unsupported")) withFixture { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback)
            val owner = main { checkNotNull(playback.player) }
            val reads = fixture.mediaTargets.size
            main {
                val videoIndex = rendererIndex(owner, C.TRACK_TYPE_VIDEO)
                val audioIndex = rendererIndex(owner, C.TRACK_TYPE_AUDIO)
                val index = if (kind == "audio") audioIndex else videoIndex
                val format = when (kind) {
                    "audio" -> checkNotNull(owner.audioFormat).buildUpon().setSampleMimeType(MimeTypes.AUDIO_RAW).build()
                    "unsupported" -> Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(320).setHeight(180).build()
                    else -> checkNotNull(owner.videoFormat)
                }
                val error = if (kind == "source") ExoPlaybackException.createForSource(
                    IOException("Owned source failure"), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                else ExoPlaybackException.createForRenderer(IllegalStateException("Owned $kind failure"),
                    owner.getRenderer(index).name, index, format, C.FORMAT_HANDLED, null, false,
                    if (kind == "drm") PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR else PlaybackException.ERROR_CODE_DECODING_FAILED)
                owner.createMessage { _, _ -> throw error }.send()
            }
            compose.waitUntil(WAIT) { playback.state.value.errorMessage != null }
            main { assertSame("$kind 错误不能触发视频补解重建", owner, playback.player) }
            assertEquals("$kind 错误不能通过本地回退重新打开视频", reads, fixture.mediaTargets.size)
        }
    }

    @Test fun aQueuedRecoveryCannotResurrectAStoppedPlayerOrReplaceTheNextItem() {
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) for (replace in listOf(false, true)) withFixture { fixture, playback, preferences ->
            main { playback.play(fixture.request(), preferences) }
            awaitReady(playback)
            val observed = AtomicBoolean()
            val preparing = AtomicBoolean()
            val previous = main { checkNotNull(playback.player).also { owner ->
                // ViewModel 先向真实 playbackLooper 查询扩展能力；同批次退出/换集使迟到结果失效。
                owner.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        preparing.set(playback.state.value.isPreparing)
                        if (replace) playback.play(fixture.request(NEXT_ITEM, "native-recovery-next"), preferences)
                        else playback.stop()
                        observed.set(true)
                    }
                })
            } }
            val reads = fixture.mediaTargets.size
            injectDecoderFailure(previous, type = type)
            compose.waitUntil(WAIT) { observed.get() }
            assertTrue("处理错误时必须已排入异步本地恢复，不能绕过待返回的能力结果", preparing.get())
            main { assertTrue("退出或换集必须释放原播放器", previous.isReleased) }
            if (replace) {
                awaitReady(playback, previous)
                main { assertEquals(NEXT_ITEM, playback.currentRequest?.item?.id) }
                assertFalse(activeRenderer(main { checkNotNull(playback.player) }) is NativeVideoRenderer)
                assertFalse(activeRenderer(main { checkNotNull(playback.player) }, C.TRACK_TYPE_AUDIO) is FfmpegAudioRenderer)
            } else {
                compose.waitForIdle()
                main { assertNull(playback.player); assertNull(playback.currentRequest) }
                assertEquals("退出后迟到恢复不能重开旧视频", reads, fixture.mediaTargets.size)
            }
        }
    }

    private fun injectDecoderFailure(owner: ExoPlayer, code: Int = PlaybackException.ERROR_CODE_DECODING_FAILED,
        extension: Boolean = false, type: Int = C.TRACK_TYPE_VIDEO) = main {
        val index = rendererIndex(owner, type, extension)
        val format = if (type == C.TRACK_TYPE_AUDIO) owner.audioFormat else owner.videoFormat
        val error = ExoPlaybackException.createForRenderer(IllegalStateException("Owned $type decoder failure"),
            owner.getRenderer(index).name, index, checkNotNull(format), C.FORMAT_HANDLED, null, false, code)
        owner.createMessage { _, _ -> throw error }.send()
        Unit
    }

    private fun rendererIndex(owner: ExoPlayer, type: Int, extension: Boolean = false): Int =
        (0 until owner.rendererCount).first { owner.getRendererType(it) == type &&
            when (type) {
                C.TRACK_TYPE_VIDEO -> (owner.getRenderer(it) is NativeVideoRenderer) == extension
                C.TRACK_TYPE_AUDIO -> (owner.getRenderer(it) is FfmpegAudioRenderer) == extension
                else -> true
            } }

    private fun activeRenderer(owner: ExoPlayer, type: Int = C.TRACK_TYPE_VIDEO): Renderer {
        val candidates = main { (0 until owner.rendererCount).filter { owner.getRendererType(it) == type }
            .map(owner::getRenderer) }
        val found = AtomicReference<Renderer>()
        val done = CountDownLatch(1)
        main { owner.createMessage { _, _ ->
            try { found.set(candidates.single { it.state != Renderer.STATE_DISABLED }) }
            finally { done.countDown() }
        }.send() }
        assertTrue("未收到真实播放线程的 Renderer 状态", done.await(5, TimeUnit.SECONDS))
        return checkNotNull(found.get())
    }

    private fun selectedAudio(owner: ExoPlayer): String? = owner.currentTracks.groups.asSequence()
        .filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
            (0 until group.length).asSequence().filter(group::isTrackSelected).map { group.getTrackFormat(it).label }
        }.firstOrNull()

    private fun awaitReady(playback: PlaybackViewModel, previous: ExoPlayer? = null) {
        compose.waitUntil(WAIT) {
            playback.state.value.errorMessage?.let { throw AssertionError("本地解码恢复未实播：$it") }
            main { playback.player?.let { owner -> owner !== previous && owner.playbackState == Player.STATE_READY &&
                playback.state.value.hasRenderedFrame && !playback.state.value.isPreparing } == true }
        }
        if (previous == null) compose.waitUntil(WAIT) { main { playback.player?.isPlaying == true } }
    }

    private fun drainReports(playback: PlaybackViewModel) = runBlocking {
        withTimeout(10_000) { playback.awaitPendingReports() }
    }

    private fun <T> main(operation: () -> T): T = compose.runOnIdle(operation)

    private fun withFixture(blockFirstSegments: Boolean = false, block: (Fixture, PlaybackViewModel, AppPreferences) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
        assertTrue("本轮验证必须加载真正的视频扩展", NativeVideoDecoder.isCodecAvailable("h264"))
        Fixture(blockFirstSegments).use { fixture ->
            val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
            val preferences = AppPreferences(originalPathEnabled = true, exoAssEnabled = false,
                preferHardwareVideoDecoder = true, preferredAudioLanguage = "en", preferredSubtitleLanguage = "en",
                serverIntroSkipEnabled = true, introPromptSeconds = 0, showClock = false, showBattery = false)
            try {
                if (!contentInstalled) {
                    compose.setContent { MaterialTheme { PlayerScreen(playback, onBack = playback::stop, preferences = preferences) } }
                    contentInstalled = true
                }
                block(fixture, playback, preferences)
            } finally {
                main { playback.stop() }
                drainReports(playback)
                assertTrue("不能为本地补解请求新地址、转码或其它 API：${fixture.unexpected}", fixture.unexpected.isEmpty())
                fixture.failures.firstOrNull()?.let { throw AssertionError("自有 HTTP 夹具失败", it) }
            }
        }
    }

    private class Fixture(private val blockFirstSegments: Boolean) : AutoCloseable {
        private val media = InstrumentationRegistry.getInstrumentation().context.assets
            .open("continuous-tracks/episode-one.mkv").use { it.readBytes() }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closed = AtomicBoolean()
        private val origin = "http://127.0.0.1:${server.localPort}"
        private val session = Session(ServerConfig(origin, true), "native-recovery-token", "native-recovery-user",
            "Owned recovery", "native-recovery-device", "native-recovery-server")
        val reports = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val mediaTargets = CopyOnWriteArrayList<String>()
        val unexpected = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val segmentRequests = AtomicInteger()
        val firstSegmentsReceived = CountDownLatch(1)
        val replacementSegmentsReceived = CountDownLatch(1)
        val releaseFirstSegments = CountDownLatch(if (blockFirstSegments) 1 else 0)

        private val accept = Thread({ while (!closed.get()) try {
            val socket = server.accept()
            if (closed.get()) { runCatching { socket.close() }; continue }
            sockets += socket
            try {
                workers.execute {
                    try { serve(socket) } catch (_: IOException) { }
                    catch (error: Throwable) { failures += error }
                    finally { sockets -= socket; runCatching { socket.close() } }
                }
            } catch (error: RejectedExecutionException) {
                sockets -= socket
                runCatching { socket.close() }
                if (!closed.get()) failures += error
            }
        } catch (error: IOException) { if (!closed.get()) failures += error }
        }, "Pathfin-native-recovery-fixture").apply { isDaemon = true; start() }

        fun request(itemId: String = ITEM, playSessionId: String = "native-recovery-play"): PlaybackRequest {
            val url = "$origin/original.mkv?sig=Ab%2fCd%2B=="
            val source = MediaSource("native-recovery-source", path = url, protocol = "Http", container = "mkv",
                supportsDirectPlay = true, supportsDirectStream = true)
            return PlaybackRequest(MediaItem(itemId, "Owned native recovery", "Movie", runTimeTicks = 240_000_000),
                PlaybackInfo(listOf(source), playSessionId), source,
                ResolvedPlayback(url, emptyMap(), PlayMethod.DIRECT_PLAY, source.id, false, "video/x-matroska"), 0, session,
                cloudPlayback = CloudPlayback(url, listOf(CloudVariant("original", true, "Original", 320, 180,
                    "h264", "aac", transport = "mkv", mimeType = "video/x-matroska"))))
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = (reader.readLine() ?: return).split(' ')
            check(request.size == 3)
            val headers = mutableMapOf<String, String>()
            var finished = false
            repeat(48) { if (!finished) {
                val line = reader.readLine() ?: return
                check(line.length <= 8_192)
                if (line.isEmpty()) finished = true else headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            } }
            check(finished)
            val length = headers["content-length"]?.toInt() ?: 0
            check(length in 0..32_768)
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val count = reader.read(body, read, length - read)
                if (count < 0) return
                read += count
            }
            val path = URI(request[1]).path
            if (request[0] == "POST" && path in setOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped")) {
                check(headers["authorization"].orEmpty().contains("native-recovery-token"))
                reports += path to JSONObject(String(body))
                respond(socket, 204, ByteArray(0), "application/json")
            } else if (request[0] == "GET" && path.startsWith("/MediaSegments/")) {
                val count = segmentRequests.incrementAndGet()
                if (count == 1) {
                    firstSegmentsReceived.countDown()
                    check(releaseFirstSegments.await(30, TimeUnit.SECONDS)) { "片头测试响应未释放" }
                } else replacementSegmentsReceived.countDown()
                val response = """{"Items":[{"Id":"3234567890abcdef1234567890abcdef",
                    "ItemId":"${path.substringAfterLast('/')}","Type":"Intro","StartTicks":0,"EndTicks":90000000}]}"""
                respond(socket, 200, response.toByteArray(), "application/json")
            } else if (request[0] == "GET" && path == "/original.mkv") {
                check(headers["authorization"].isNullOrEmpty() && headers["x-emby-token"].isNullOrEmpty())
                mediaTargets += request[1]
                val range = headers["range"]?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) ?: error("Invalid owned range") }
                val first = range?.groupValues?.get(1)?.toInt() ?: 0
                val last = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(media.lastIndex) ?: media.lastIndex
                if (first !in media.indices || last < first) respond(socket, 416, ByteArray(0), "video/x-matroska")
                else respond(socket, if (range == null) 200 else 206, media.copyOfRange(first, last + 1), "video/x-matroska",
                    if (range == null) null else "bytes $first-$last/${media.size}")
            } else {
                unexpected += "${request[0]} ${request[1]}"
                respond(socket, 400, "{}".toByteArray(), "application/json")
            }
        }

        private fun respond(socket: Socket, status: Int, bytes: ByteArray, type: String, range: String? = null) {
            val header = buildString {
                append("HTTP/1.1 $status Owned\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nAccept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseFirstSegments.countDown()
            server.close()
            accept.join(1_000)
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val WAIT = 20_000L
        private const val JAPANESE = "Japanese main"
        private const val ITEM = "1234567890abcdef1234567890abcdef"
        private const val NEXT_ITEM = "2234567890abcdef1234567890abcdef"
    }
}

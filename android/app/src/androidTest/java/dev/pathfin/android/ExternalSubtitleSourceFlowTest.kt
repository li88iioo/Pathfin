package dev.pathfin.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.ass.SubtitleMediaSourceFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP + official SubtitleExtractor; no VM, mocks, credential or existing remote fixture. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ExternalSubtitleSourceFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun unselected404AndStalledSubtitleNeverOpenOrBlockActualVideo() = withPlayer { player, server ->
        awaitVideo(player)
        main { player.pause() }
        assertEquals("An unselected 404 must not even be requested", 0, server.missingOpens.get())
        assertEquals("An unselected slow subtitle must not hold video preparation", 0, server.slowOpens.get())
        assertEquals(0, server.subtitleOpens.get())
        assertTrue("The real video must retain its own duration", main { player.duration in 23_900..24_100 })
        assertTrue(main { player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT } >= 3 })
        assertNull(main { player.playerError })
    }

    @Test fun selectingThenImmediatelySeekingWhilePausedRetainsTheActualLongCueAndTrack() = withPlayer { player, server ->
        awaitVideo(player)
        main {
            player.pause()
            selectLongSubtitle(player)
            player.seekTo(15_000)
        }
        awaitCue(player, 15_000)
        assertTrue(server.subtitleOpens.get() > 0)
        assertEquals(0, server.missingOpens.get())
        assertEquals(0, server.slowOpens.get())
        assertTrue("A 22 second subtitle must not truncate a 24 second movie", main { player.duration in 23_900..24_100 })
        main {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            player.seekTo(5_000)
        }
        compose.waitUntil(WAIT) { main { player.currentCues.cues.isEmpty() &&
            player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected } } }
        main { selectLongSubtitle(player); player.seekTo(20_000) }
        awaitCue(player, 20_000)
        main { player.seekTo(3_000) }
        awaitCue(player, 3_000)
    }

    @Test fun clippingUsesOneSharedClockWithoutReplacingTheExternalTrackIdentity() = withPlayer(clip = true) { player, server ->
        awaitVideo(player)
        main { player.pause(); selectLongSubtitle(player); player.seekTo(15_000) }
        awaitCue(player, 15_000)
        assertTrue("All media sources must share the requested 1..21 second clip", main { player.duration in 19_900..20_100 })
        assertEquals(0, server.missingOpens.get())
        assertEquals(0, server.slowOpens.get())
    }

    private fun selectLongSubtitle(player: ExoPlayer) {
        val group = player.currentTracks.groups.single {
            it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).label == "Fixture long SRT"
        }
        assertTrue("The advertised known text format must really be supported", group.isTrackSupported(0))
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
    }

    private fun awaitVideo(player: ExoPlayer) {
        compose.waitUntil(WAIT) {
            main {
                player.playerError?.let { throw AssertionError("Owned HTTP video failed", it) }
                player.playbackState == Player.STATE_READY && player.currentPosition > 200
            }
        }
    }

    private fun awaitCue(player: ExoPlayer, positionMs: Long) {
        try {
            compose.waitUntil(WAIT) {
                main {
                    player.playerError?.let { throw AssertionError("Owned external subtitle failed", it) }
                    !player.playWhenReady && player.playbackState == Player.STATE_READY &&
                        kotlin.math.abs(player.currentPosition - positionMs) < 200 &&
                        player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected &&
                            it.getTrackFormat(0).label == "Fixture long SRT" } &&
                        player.currentCues.cues.any { it.text?.contains("PATHFIN REAL LONG CUE") == true }
                }
            }
        } catch (failure: Throwable) {
            val state = main {
                "state=${player.playbackState},position=${player.currentPosition},paused=${!player.playWhenReady}," +
                    "text=${player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.map { group ->
                        "${group.mediaTrackGroup.id}:${group.getTrackFormat(0).id}:supported=${group.isTrackSupported(0)}:selected=${group.isSelected}"
                    }},overrides=${player.trackSelectionParameters.overrides.keys.map { it.id }},cues=${player.currentCues.cues.map { it.text }}"
            }
            throw AssertionError("Actual paused long cue missing: $state", failure)
        }
    }

    private fun withPlayer(clip: Boolean = false, block: (ExoPlayer, HttpFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        HttpFixture(bytes).use { fixture ->
            val rendered = AtomicBoolean()
            val player = main {
                val data = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(2_000).setReadTimeoutMs(2_000))
                ExoPlayer.Builder(context).setMediaSourceFactory(SubtitleMediaSourceFactory(data, DefaultExtractorsFactory()))
                    .build().also { p ->
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        p.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { rendered.set(true) } })
                    }
            }
            var view: PlayerView? = null
            try {
                compose.setContent {
                    AndroidView(factory = { PlayerView(it).also { v -> v.useController = false; v.player = player; view = v } },
                        modifier = Modifier.fillMaxSize())
                }
                main {
                    val item = MediaItem.Builder().setUri(fixture.uri("video.mp4"))
                        .setSubtitleConfigurations(listOf(
                            subtitle(fixture, "long.srt", "long", "Fixture long SRT"),
                            subtitle(fixture, "missing.srt", "missing", "Fixture missing SRT"),
                            subtitle(fixture, "slow.srt", "slow", "Fixture slow SRT"),
                        )).apply { if (clip) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(1_000).setEndPositionMs(21_000).build()) }.build()
                    player.setMediaItem(item); player.prepare(); player.play()
                }
                compose.waitUntil(WAIT) {
                    main { player.playerError }?.let { throw AssertionError("Owned video did not render", it) }
                    rendered.get()
                }
                block(player, fixture)
            } finally { main { view?.player = null; player.release() } }
        }
    }

    private fun subtitle(fixture: HttpFixture, path: String, id: String, label: String) =
        MediaItem.SubtitleConfiguration.Builder(fixture.uri(path)).setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setId(id).setLabel(label).setLanguage("en").setRoleFlags(C.ROLE_FLAG_SUBTITLE).build()

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private class HttpFixture(private val video: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicBoolean(true)
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "pathfin-owned-subtitle-http").apply { isDaemon = true } }
        val subtitleOpens = AtomicInteger()
        val missingOpens = AtomicInteger()
        val slowOpens = AtomicInteger()
        init { workers.execute { while (active.get()) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { break }
            sockets += socket
            workers.execute { try { socket.use(::serve) } finally { sockets -= socket } }
        } } }
        fun uri(path: String): Uri = Uri.parse("http://127.0.0.1:${server.localPort}/$path")
        private fun serve(socket: Socket) {
            socket.soTimeout = 3_000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine()?.split(' ') ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine()?.takeIf(String::isNotEmpty) ?: break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            when (request.getOrNull(1)) {
                "/video.mp4" -> {
                    val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                    val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
                    if (start !in 0..end) return
                    respond(socket, if (range == null) "200 OK" else "206 Partial Content", "video/mp4", video,
                        start, end + 1, if (range != null) "Content-Range: bytes $start-$end/${video.size}\r\n" else "")
                }
                "/long.srt" -> {
                    subtitleOpens.incrementAndGet()
                    respond(socket, "200 OK", "application/x-subrip", "1\n00:00:00,000 --> 00:00:22,000\nPATHFIN REAL LONG CUE\n\n".toByteArray())
                }
                "/missing.srt" -> { missingOpens.incrementAndGet(); respond(socket, "404 Not Found", "text/plain", byteArrayOf()) }
                "/slow.srt" -> { slowOpens.incrementAndGet(); Thread.sleep(3_000); respond(socket, "200 OK", "application/x-subrip", byteArrayOf()) }
            }
        }
        private fun respond(socket: Socket, status: String, mime: String, body: ByteArray, start: Int = 0, end: Int = body.size, extra: String = "") {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${end - start}\r\nAccept-Ranges: bytes\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(body, start, end - start); flush()
            }
        }
        override fun close() {
            active.set(false); server.close()
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
            workers.shutdownNow()
        }
    }

    companion object { private const val WAIT = 15_000L }
}

package dev.pathfin.android.playback

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackReportQueueTest {
    @Test fun latestPendingSnapshotCanSeekBackwardWithoutChangingTheInFlightReport() = runBlocking {
        val queue = PlaybackReportQueue()
        val request = request()
        val inFlight = event(request, "progress", 20_000, paused = false)
        queue.offer(inFlight)
        assertEquals(inFlight, queue.next())
        queue.offer(event(request, "progress", 17_000, paused = false))
        val latest = event(request.copy(startPositionMs = 2_000), "progress", 2_000, paused = true)
        queue.offer(latest)
        queue.close()

        assertEquals(listOf(latest), drain(queue))
        assertEquals(PlaybackReport("progress", 20_000, false), inFlight.report)
    }

    @Test fun barriersAndPlaybackControlsKeepTheirOrderWhileStopSupersedesItsPendingProgress() = runBlocking {
        val queue = PlaybackReportQueue()
        val first = request()
        val next = first.copy(info = first.info.copy(playSessionId = "next-play"))
        val start = event(first, "start", 0)
        val beforeBarrier = event(first, "progress", 1_000)
        val barrier = ReportMessage.Barrier(CompletableDeferred())
        val stopped = event(first, "stop", 3_000)
        val nextStart = event(next, "start", 0)
        val nextProgress = event(next, "progress", 4_000)
        listOf(start, beforeBarrier, barrier, event(first, "progress", 2_000), stopped, nextStart, nextProgress)
            .forEach { assertTrue(queue.offer(it)) }
        queue.close()

        assertEquals(listOf(start, beforeBarrier, barrier, stopped, nextStart, nextProgress), drain(queue))
        assertFalse("出队屏障应由发送者在此前 HTTP 完成后兑现", barrier.completion.isCompleted)
    }

    @Test fun progressNeverMergesAcrossAnyStartStopOrBarrier() = runBlocking {
        val request = request()
        val other = request.copy(info = request.info.copy(playSessionId = "other-play"))
        val before = event(request, "progress", 1_000)
        val after = event(request, "progress", 2_000)
        val boundaries = listOf(event(other, "start", 0), event(other, "stop", 0),
            ReportMessage.ReleaseTranscode(other), ReportMessage.Barrier(CompletableDeferred()))
        for (boundary in boundaries) {
            val queue = PlaybackReportQueue()
            listOf(before, boundary, after).forEach { queue.offer(it) }
            queue.close()
            assertEquals(listOf(before, boundary, after), drain(queue))
        }
    }

    @Test fun transcodeReleaseStaysAfterStopAndBeforeTheReplacementStarts() = runBlocking {
        val queue = PlaybackReportQueue()
        val previous = request()
        val next = previous.copy(info = previous.info.copy(playSessionId = "replacement"))
        val stop = event(previous, "stop", 8_000)
        val release = ReportMessage.ReleaseTranscode(previous)
        val start = event(next, "start", 8_000)
        listOf(event(previous, "progress", 7_000), stop, release, start).forEach(queue::offer)
        queue.close()
        assertEquals(listOf(stop, release, start), drain(queue))
    }

    @Test fun accountPlaybackMediaAndSourceIdentityChangesKeepIndependentSnapshots() = runBlocking {
        val original = request()
        val changes = listOf(
            original.copy(session = original.session.copy(accessToken = "other-token")),
            original.copy(session = original.session.copy(userId = "other-user")),
            original.copy(session = original.session.copy(deviceId = "other-device")),
            original.copy(session = original.session.copy(serverId = "other-server")),
            original.copy(session = original.session.copy(server = ServerConfig("https://other.example"))),
            original.copy(info = original.info.copy(playSessionId = "other-play")),
            original.copy(source = original.source.copy(id = "other-source")),
            original.copy(item = original.item.copy(id = "other-item")),
        )
        for (changed in changes) {
            val queue = PlaybackReportQueue()
            val before = event(original, "progress", 1_000)
            val after = event(changed, "progress", 2_000)
            queue.offer(before); queue.offer(after); queue.close()
            assertEquals(listOf(before, after), drain(queue))
        }
    }

    @Test fun waitingConsumerWakesAndClosedQueueDrainsItsAcceptedStopBeforeEnding() = runBlocking {
        withTimeout(1_000) {
            val queue = PlaybackReportQueue()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { queue.next() }
            assertFalse(waiting.isCompleted)
            val start = event(request(), "start", 0)
            assertTrue(queue.offer(start))
            assertEquals(start, waiting.await())
            val stop = event(request(), "stop", 5_000)
            assertTrue(queue.offer(stop))
            queue.close()
            assertFalse(queue.offer(event(request(), "progress", 6_000)))
            assertEquals(stop, queue.next())
            assertNull(queue.next())
        }
    }

    @Test fun failedConsumerCanCompleteAllPendingBarriersWithoutLeavingWaiters() = runBlocking {
        val queue = PlaybackReportQueue()
        val first = ReportMessage.Barrier(CompletableDeferred())
        val second = ReportMessage.Barrier(CompletableDeferred())
        queue.offer(first)
        queue.offer(event(request(), "stop", 3_000))
        queue.offer(second)
        queue.close()
        queue.completePendingBarriers()

        assertTrue(first.completion.isCompleted)
        assertTrue(second.completion.isCompleted)
        assertNull(queue.next())
    }

    private suspend fun drain(queue: PlaybackReportQueue): List<ReportMessage> = buildList {
        while (true) {
            val message = queue.next() ?: break
            add(message)
        }
    }

    private fun event(request: PlaybackRequest, name: String, positionMs: Long, paused: Boolean = true) =
        ReportMessage.Playback(request, PlaybackReport(name, positionMs, paused))

    private fun request(): PlaybackRequest {
        val session = Session(ServerConfig("https://jellyfin.example"), "token", "user", "User", "device")
        val source = MediaSource("source", path = "https://media.example/video.mkv")
        return PlaybackRequest(MediaItem("item", "Movie", "Movie"), PlaybackInfo(listOf(source), "play"), source,
            ResolvedPlayback(source.path!!, emptyMap(), PlayMethod.DIRECT_PLAY, source.id, false), 0, session)
    }
}

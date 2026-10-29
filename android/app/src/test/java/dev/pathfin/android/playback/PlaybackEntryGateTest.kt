package dev.pathfin.android.playback

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEntryGateTest {
    @Test fun rotationCannotRestartNaturallyCompletedEntry() {
        val gate = PlaybackEntryGate()
        val request = request()
        gate.completed(request)
        assertFalse(gate.canOpen(request.copy(startPositionMs = 0)))
        assertTrue(gate.canOpen(request.copy(info = request.info.copy(playSessionId = "new-session"))))
    }

    @Test fun refreshedSessionAlsoRetainsOriginalEntryAfterCompletion() {
        val gate = PlaybackEntryGate()
        val original = request()
        val refreshed = original.copy(info = original.info.copy(playSessionId = "refreshed"))
        gate.completed(original, refreshed)
        assertFalse(gate.canOpen(original))
        assertFalse(gate.canOpen(refreshed))
    }

    @Test fun explicitExitAllowsSameMediaToBeOpenedAgain() {
        val gate = PlaybackEntryGate()
        val request = request()
        gate.completed(request)
        gate.reset()
        assertTrue(gate.canOpen(request))
    }

    @Test fun differentVersionOrAccountIsAnIndependentEntry() {
        val gate = PlaybackEntryGate()
        val request = request()
        gate.completed(request)
        assertTrue(gate.canOpen(request.copy(source = request.source.copy(id = "other-source"))))
        assertTrue(gate.canOpen(request.copy(session = request.session.copy(userId = "other-user"))))
    }

    private fun request(): PlaybackRequest {
        val session = Session(ServerConfig("https://jellyfin.example"), "token", "user", "User", "device")
        val source = MediaSource("source", path = "https://media.example/video.mkv")
        return PlaybackRequest(
            MediaItem("item", "Movie", "Movie"),
            PlaybackInfo(listOf(source), "initial-session"),
            source,
            ResolvedPlayback(source.path!!, emptyMap(), PlayMethod.DIRECT_PLAY, source.id, false),
            12_000,
            session,
        )
    }
}

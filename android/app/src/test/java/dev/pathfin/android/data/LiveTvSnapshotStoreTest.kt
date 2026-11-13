package dev.pathfin.android.data

import dev.pathfin.android.domain.LiveTvChannel
import dev.pathfin.android.domain.LiveTvProgram
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvSnapshotStoreTest {
    private val session = Session(ServerConfig("https://jellyfin.example"), "secret-token", "user-a", "Alice", "device", serverId = "server-a")
    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val channels = listOf(
        LiveTvChannel(MediaItem("c1", "CCTV1", "TvChannel", imageTag = "logo", isFavorite = true), "1",
            LiveTvProgram("p1", "c1", "新闻联播", start = now, end = now.plusSeconds(1800)), lastPlayedAt = now.minusSeconds(60)),
        LiveTvChannel(MediaItem("c2", "CQTV", "TvChannel")),
    )

    @Test fun snapshotRoundTripsDisplayFieldsPerServerAndAccount() = withStore { store ->
        runBlocking { store.save(session, channels) }
        val restored = runBlocking { store.load(session) }
        assertEquals(channels, restored)
        assertNull("其他账号读不到", runBlocking { store.load(session.copy(userId = "user-b")) })
        assertNull("其他服务器读不到", runBlocking { store.load(session.copy(serverId = "server-b")) })
    }

    @Test fun snapshotNeverContainsCredentialsAndCorruptFilesAreIgnored() = withStore { store ->
        runBlocking { store.save(session, channels) }
        val file = store.files().single()
        assertFalse(file.readText().contains(session.accessToken))
        file.writeText("{not json")
        assertNull(runBlocking { store.load(session) })
    }

    @Test fun emptySnapshotIsRemembered() = withStore { store ->
        runBlocking { store.save(session, emptyList()) }
        assertEquals(emptyList<LiveTvChannel>(), runBlocking { store.load(session) })
    }

    private class Store(val directory: java.io.File) {
        val store = LiveTvSnapshotStore(directory)
        suspend fun save(session: Session, channels: List<LiveTvChannel>) = store.save(session, channels)
        suspend fun load(session: Session) = store.load(session)
        fun files() = directory.listFiles().orEmpty().filter { it.isFile }
    }

    private fun withStore(block: (Store) -> Unit) {
        val directory = Files.createTempDirectory("live-tv-snapshots").toFile()
        try { block(Store(directory)) } finally { directory.deleteRecursively() }
    }
}

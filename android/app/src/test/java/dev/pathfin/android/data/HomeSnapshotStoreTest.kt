package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = Session(ServerConfig("https://server.test/jellyfin"),
        "private-token", "user-A", "Alice", "private-device", "server-A")
    private val episode = MediaItem("episode", "第 3 集", "Episode", overview = "简介", year = 2026,
        runTimeTicks = 28_800_000_000L, positionTicks = 12_000_000_000L,
        imageTag = "poster", primaryImageItemId = "series", seriesId = "series", seriesName = "剧名",
        indexNumber = 3, parentIndexNumber = 2, backdropTag = "backdrop", backdropItemId = "series",
        communityRating = 8.6, officialRating = "TV-14", genres = listOf("剧情"),
        isFavorite = true, playedPercentage = 41.5, unplayedItemCount = 9, playCount = 2,
        bannerTag = "banner", logoTag = "logo", logoItemId = "series", backdropTags = listOf("backdrop", "other"),
        criticRating = 90.0, premiereDate = "2026-02-01T00:00:00Z", tags = listOf("test"),
        status = "Continuing", seasonId = "season", canDownload = false, canDelete = true, endYear = 2026)
    private val library = MediaItem("library", "剧集", "CollectionFolder", isFolder = true,
        collectionType = "tvshows", imageTag = "cover", primaryImageItemId = "library", childCount = 24)

    private fun snapshot() = HomeSnapshot(
        libraries = listOf(library), recent = emptyList(), resume = listOf(episode),
        nextUp = listOf(episode.copy(id = "next", seriesId = "other-series", positionTicks = 0L)),
        libraryItems = linkedMapOf(library.id to listOf(episode), "known-empty" to emptyList()),
        loaded = true, complete = true, librariesLoaded = true, resumeLoaded = true, nextUpLoaded = true,
    )

    @Test fun recreatedStoreRestoresAllHomeSectionsArtworkProgressAndLoadedEmptyShelves() {
        val directory = temporary.newFolder()
        val expected = snapshot()
        assertTrue(HomeSnapshotStore(directory).save("A", session, expected))
        val restored = requireNotNull(HomeSnapshotStore(directory).load("A", session))
        assertEquals(expected, restored)
        assertEquals(emptyList<MediaItem>(), restored.libraryItems["known-empty"])
        assertFalse(restored.libraryItems.containsKey("unloaded"))
        assertEquals(listOf("next"), restored.nextUp.map { it.id })
        assertTrue(restored.resumeLoaded && restored.nextUpLoaded && restored.librariesLoaded)
    }

    @Test fun realEmptyAndIncompleteUnknownGroupsRemainDistinctAcrossRestart() {
        val directory = temporary.newFolder()
        val store = HomeSnapshotStore(directory)
        val empty = snapshot().copy(resume = emptyList(), nextUp = emptyList())
        assertTrue(store.save("A", session, empty))
        assertEquals(empty, HomeSnapshotStore(directory).load("A", session))
        val partial = empty.copy(complete = false, resumeLoaded = false, nextUpLoaded = false,
            libraryItems = mapOf(library.id to emptyList()))
        assertTrue(store.save("A", session, partial))
        assertEquals(partial, HomeSnapshotStore(directory).load("A", session))
    }

    @Test fun profilesUsersServersAddressesCredentialsAndPermissionsCannotReuseAnotherSnapshot() {
        val store = HomeSnapshotStore(temporary.newFolder())
        val expected = snapshot()
        assertTrue(store.save("A", session, expected))
        assertNull(store.load("B", session))
        val differentSessions = listOf(
            session.copy(userId = "user-B"), session.copy(serverId = "server-B"),
            session.copy(server = ServerConfig("https://other.test/jellyfin")),
            session.copy(accessToken = "renewed-token"), session.copy(deviceId = "other-device"),
            session.copy(isAdministrator = true), session.copy(canDownload = true), session.copy(canDelete = true),
        )
        differentSessions.forEach { assertNull(store.load("A", it)) }
        assertEquals(expected, store.load("A", session.copy(server = ServerConfig("https://server.test/jellyfin/"))))
        assertEquals(expected, store.load("A", session.copy(userName = "Renamed", userImageTag = "avatar")))
        val other = expected.copy(nextUp = emptyList())
        assertTrue(store.save("B", session, other))
        assertEquals(expected, store.load("A", session))
        assertEquals(other, store.load("B", session))
    }

    @Test fun removingOneProfileDeletesEveryAddressAndAuthenticationWithoutAffectingOthers() {
        val directory = temporary.newFolder()
        val store = HomeSnapshotStore(directory)
        val addresses = listOf(session, session.copy(server = ServerConfig("https://alternate.test")),
            session.copy(accessToken = "renewed-token"))
        addresses.forEach { assertTrue(store.save("A", it, snapshot())) }
        assertTrue(store.save("B", session, snapshot()))
        store.removeProfile("A")
        addresses.forEach { assertNull(HomeSnapshotStore(directory).load("A", it)) }
        assertEquals(snapshot(), HomeSnapshotStore(directory).load("B", session))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun corruptTruncatedOrIncompleteCacheFallsBackWithoutHidingAValidOtherAccount() {
        val directory = temporary.newFolder()
        val store = HomeSnapshotStore(directory)
        assertTrue(store.save("B", session, snapshot()))
        val otherFile = directory.listFiles()!!.single()
        val invalidData = listOf("{truncated", "[]", "{\"version\":100}",
            JSONObject().put("version", 1).put("nextUp", org.json.JSONArray()).toString())
        invalidData.forEach { invalid ->
            assertTrue(store.save("A", session, snapshot()))
            val file = directory.listFiles()!!.single { it != otherFile }
            file.writeText(invalid)
            assertNull(store.load("A", session))
            assertFalse(file.exists())
            assertEquals(snapshot(), store.load("B", session))
        }
        assertTrue("损坏后下一次真实内容仍能正常覆盖", store.save("A", session, snapshot()))
        assertEquals(snapshot(), store.load("A", session))
    }

    @Test fun cacheContainsOnlyDisplayDataAndNeverPlaybackUrlsHeadersOrSessionCredentials() {
        val directory = temporary.newFolder()
        val privateSource = MediaSource("source", path = "https://cdn.test/video?secret=media-secret",
            directStreamUrl = "https://cdn.test/stream?secret=direct-secret",
            requiredHttpHeaders = mapOf("Authorization" to "secret-authorization"))
        val snapshot = snapshot().copy(resume = listOf(episode.copy(mediaSources = listOf(privateSource))))
        val store = HomeSnapshotStore(directory)
        assertTrue(store.save("A", session, snapshot))
        val files = directory.listFiles()!!.toList()
        val storedText = files.joinToString { it.name + it.readText() }
        for (secret in listOf(session.accessToken, session.deviceId, "media-secret", "direct-secret", "secret-authorization")) {
            assertFalse(storedText.contains(secret))
        }
        assertEquals(episode, store.load("A", session)!!.resume.single())
        assertTrue("原子写入完成不能留下临时文件", files.all { it.extension == "json" })
    }

    @Test fun unavailableCacheDirectoryDoesNotTurnIntoAnApplicationFailure() {
        val store = HomeSnapshotStore(temporary.newFile())
        assertFalse(store.save("A", session, snapshot()))
        assertNull(store.load("A", session))
    }

    @Test fun oversizedUtf8SnapshotKeepsThePreviousReadableFile() {
        val directory = temporary.newFolder()
        val store = HomeSnapshotStore(directory)
        val previous = snapshot()
        assertTrue(store.save("A", session, previous))
        val oversized = previous.copy(recent = listOf(episode.copy(overview = "影".repeat(3 * 1024 * 1024))))
        assertFalse(store.save("A", session, oversized))
        assertEquals(previous, HomeSnapshotStore(directory).load("A", session))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun oversizedExternalFileIsDiscardedWithoutReadingItsContents() {
        val directory = temporary.newFolder()
        val store = HomeSnapshotStore(directory)
        assertTrue(store.save("A", session, snapshot()))
        val file = directory.listFiles()!!.single()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertNull(store.load("A", session))
        assertFalse(file.exists())
    }
}

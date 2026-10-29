package dev.pathfin.android.data

import dev.pathfin.android.domain.BrowserPageKey
import dev.pathfin.android.domain.BrowserUserDataChange
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.LibrarySort
import dev.pathfin.android.domain.LibraryTab
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaPage
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaUserData
import dev.pathfin.android.domain.ProfileHomeKey
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = Session(ServerConfig("https://server.test/jellyfin"),
        "private-token", "user-A", "Alice", "private-device", "server-A")
    private val episode = MediaItem("episode", "第 3 集", "Episode", overview = "简介", year = 2026,
        runTimeTicks = 28_800_000_000L, positionTicks = 12_000_000_000L,
        imageTag = "poster", primaryImageItemId = "series", seriesId = "series", seriesName = "剧名",
        indexNumber = 3, parentIndexNumber = 2, backdropTag = "backdrop", backdropItemId = "series",
        communityRating = 8.6, officialRating = "TV-14", genres = listOf("剧情"),
        playedPercentage = 41.5, unplayedItemCount = 9, playCount = 2,
        logoTag = "logo", logoItemId = "series", backdropTags = listOf("backdrop", "other"),
        premiereDate = "2026-02-01T00:00:00Z", tags = listOf("test"), status = "Continuing",
        seasonId = "season", canDownload = false, canDelete = true, endYear = 2026)
    private val page = MediaPage(listOf(episode), 123)

    private fun key(
        query: LibraryQuery = LibraryQuery(),
        browserId: String = "library",
        parentId: String? = "library",
        profileId: String = "A",
        activeSession: Session = session,
    ) = BrowserPageKey(ProfileHomeKey.of(profileId, activeSession), browserId, parentId, query)

    @Test fun restartRestoresOnlyFirstFortyDisplayItemsAndPreservesServerTotal() {
        val directory = temporary.newFolder()
        val items = (0 until 80).map { episode.copy(id = "episode-$it") }
        val source = MediaSource("source", path = "https://cdn.test/video?secret=stream-secret",
            directStreamUrl = "https://cdn.test/direct?secret=direct-secret",
            requiredHttpHeaders = mapOf("Authorization" to "private-header"))
        val withSources = items.map { it.copy(mediaSources = listOf(source)) }
        assertTrue(BrowserSnapshotStore(directory).save(session, key(), MediaPage(withSources, 12_000)))
        assertEquals(MediaPage(items.take(40), 12_000), BrowserSnapshotStore(directory).load(session, key()))
        val files = directory.listFiles()!!.toList()
        val persisted = files.joinToString { it.name + it.readText() }
        listOf(session.accessToken, session.deviceId, "stream-secret", "direct-secret", "private-header").forEach {
            assertFalse("快照不应保存凭据或播放源：$it", persisted.contains(it))
        }
        assertTrue("原子写入不能留下临时文件", files.all { it.extension == "json" })
    }

    @Test fun loadedEmptyPageRemainsDistinctFromMissingCache() {
        val store = BrowserSnapshotStore(temporary.newFolder())
        val empty = MediaPage(emptyList(), 0)
        assertNull(store.load(session, key()))
        assertTrue(store.save(session, key(), empty))
        assertEquals(empty, store.load(session, key()))
    }

    @Test fun equivalentSetOrderUsesOneSnapshotAndRestoresTheCompleteQuery() {
        val directory = temporary.newFolder()
        val query = LibraryQuery(tab = LibraryTab.FAVORITES, sort = LibrarySort.YEAR, descending = false,
            played = false, favorite = true, seriesStatus = linkedSetOf("Ended", "Continuing"),
            genres = linkedSetOf("剧情", "喜剧"), tags = linkedSetOf("A", "B"), years = linkedSetOf(2026, 2025),
            officialRatings = linkedSetOf("TV-14", "TV-MA"), itemTypes = linkedSetOf("Movie", "Series"),
            personId = "person", artistId = "artist", studioId = "studio", genreId = "genre", episodeOrder = true,
            nameInitial = "A", recursive = false)
        val reordered = query.copy(seriesStatus = query.seriesStatus.reversed().toSet(), genres = query.genres.reversed().toSet(),
            tags = query.tags.reversed().toSet(), years = query.years.reversed().toSet(),
            officialRatings = query.officialRatings.reversed().toSet(), itemTypes = query.itemTypes.reversed().toSet())
        val store = BrowserSnapshotStore(directory)
        assertTrue(store.save(session, key(query), page))
        assertEquals(page, BrowserSnapshotStore(directory).load(session, key(reordered)))
        assertTrue(store.save(session, key(reordered), page.copy(totalCount = 124)))
        assertEquals(1, directory.listFiles()!!.size)
        val visited = mutableListOf<LibraryQuery>()
        store.update(session, "A", { it }, { visited += it; false })
        assertEquals(listOf(query), visited)
    }

    @Test fun distinctSortFiltersOwnersAndBrowserScopesCannotCollide() {
        val store = BrowserSnapshotStore(temporary.newFolder())
        val query = LibraryQuery()
        assertTrue(store.save(session, key(query), page))
        val alternatives = listOf(
            query.copy(tab = LibraryTab.RESUME), query.copy(sort = LibrarySort.TITLE), query.copy(descending = false),
            query.copy(played = false), query.copy(favorite = false), query.copy(seriesStatus = setOf("Ended")),
            query.copy(genres = setOf("剧情")), query.copy(tags = setOf("test")), query.copy(years = setOf(2026)),
            query.copy(officialRatings = setOf("TV-14")), query.copy(itemTypes = setOf("Episode")),
            query.copy(personId = "person"), query.copy(artistId = "artist"), query.copy(studioId = "studio"),
            query.copy(genreId = "genre"), query.copy(episodeOrder = true), query.copy(nameInitial = "A"),
            query.copy(recursive = false),
        )
        alternatives.forEach { assertNull("不同查询不能读到默认库快照：$it", store.load(session, key(it))) }
        assertNull(store.load(session, key(browserId = "other")))
        assertNull(store.load(session, key(parentId = "other")))
        assertNull(store.load(session, key(parentId = null)))
        assertEquals(page, store.load(session, key()))
    }

    @Test fun profileUserReverseProxyOriginAuthenticationAndPermissionsAreIsolated() {
        val store = BrowserSnapshotStore(temporary.newFolder())
        assertTrue(store.save(session, key(), page))
        assertNull(store.load(session, key(profileId = "B")))
        val alternatives = listOf(
            session.copy(userId = "user-B"), session.copy(serverId = "server-B"),
            session.copy(server = ServerConfig("https://other.test/jellyfin")),
            session.copy(server = ServerConfig("https://server.test/other-proxy")),
            session.copy(server = ServerConfig("https://server.test:8443/jellyfin")),
            session.copy(server = session.server.copy(allowInsecureHttp = true)),
            session.copy(accessToken = "renewed-token"), session.copy(deviceId = "other-device"),
            session.copy(isAdministrator = true), session.copy(canDownload = true), session.copy(canDelete = true),
        )
        alternatives.forEach { assertNull(store.load(it, key(activeSession = it))) }
        val same = session.copy(server = ServerConfig("https://server.test/jellyfin/"),
            userName = "Renamed", userImageTag = "avatar")
        assertEquals(page, store.load(same, key(activeSession = same)))
        val other = session.copy(userId = "other-user")
        assertFalse("不匹配的页面归属不能写入其他会话", store.save(other, key(), page))
        assertNull(store.load(other, key()))
    }

    @Test fun librarySearchNeverPersistsIncludingAnEmptySearchEntry() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val queries = listOf(key(LibraryQuery(searchTerm = "电影")), key(LibraryQuery(searchTerm = " ")),
            key(browserId = "library-search/library"))
        queries.forEach {
            assertFalse(store.save(session, it, page))
            assertNull(store.load(session, it))
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun updateChangesItemsWithoutChangingTotalAndRemovesQueriesWhoseMembershipMayChange() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val defaultKey = key(LibraryQuery(sort = LibrarySort.TITLE, itemTypes = setOf("Episode")))
        val favoriteKey = key(LibraryQuery(tab = LibraryTab.FAVORITES))
        val resumeKey = key(LibraryQuery(tab = LibraryTab.RESUME))
        val otherSession = session.copy(accessToken = "other-authentication")
        val otherKey = key(activeSession = otherSession)
        val expectedItems = listOf(episode, episode.copy(id = "untouched"))
        assertTrue(store.save(session, defaultKey, MediaPage(expectedItems, 200)))
        assertTrue(store.save(session, favoriteKey, page))
        assertTrue(store.save(session, resumeKey, page))
        assertTrue(store.save(otherSession, otherKey, page))
        val reloaded = BrowserSnapshotStore(directory)
        reloaded.update(session, "A", { item ->
            if (item.id == episode.id) item.copy(isFavorite = true, positionTicks = 15L, playedPercentage = 50.0) else item
        }, { it.tab == LibraryTab.FAVORITES || it.tab == LibraryTab.RESUME })
        assertEquals(MediaPage(listOf(episode.copy(isFavorite = true, positionTicks = 15L, playedPercentage = 50.0),
            expectedItems.last()), 200), BrowserSnapshotStore(directory).load(session, defaultKey))
        assertNull(reloaded.load(session, favoriteKey))
        assertNull(reloaded.load(session, resumeKey))
        assertEquals(page, reloaded.load(otherSession, otherKey))
        val queries = mutableListOf<LibraryQuery>()
        reloaded.update(session, "A", { it }, { queries += it; false })
        assertEquals(listOf(defaultKey.query), queries)
    }

    @Test fun resettingAPartlyWatchedSeriesCannotRestoreOldResumeOrEpisodeSnapshotsAfterRestart() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val resume = key(LibraryQuery(tab = LibraryTab.RESUME, itemTypes = setOf("Series")))
        val episodes = key(LibraryQuery(itemTypes = setOf("Episode")))
        val other = key(resume.query, profileId = "B")
        val movies = key(LibraryQuery(itemTypes = setOf("Movie")))
        val moviePage = MediaPage(listOf(MediaItem("movie", "Movie", "Movie")), 1)
        listOf(resume, episodes, other).forEach { assertTrue(store.save(session, it, page)) }
        assertTrue(store.save(session, movies, moviePage))
        val series = MediaItem("series", "Series", "Series", played = false, unplayedItemCount = 9)
        val change = BrowserUserDataChange(series, MediaUserData(played = false, unplayedItemCount = 10),
            BrowserUserDataChange.Kind.PLAYED)

        BrowserSnapshotStore(directory).update(session, "A", change::apply, change::invalidates)

        val restarted = BrowserSnapshotStore(directory)
        assertNull("重启后不能恢复整剧重置之前的续播集", restarted.load(session, resume))
        assertNull(restarted.load(session, episodes))
        assertEquals(page, restarted.load(session, other))
        assertEquals(moviePage, restarted.load(session, movies))
    }

    @Test fun removeProfileDeletesAllQueriesAddressesAndTokensButKeepsOtherProfiles() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val sessions = listOf(session, session.copy(server = ServerConfig("https://other.test")),
            session.copy(accessToken = "new-token"))
        sessions.forEach { active ->
            assertTrue(store.save(active, key(activeSession = active), page))
            assertTrue(store.save(active, key(LibraryQuery(nameInitial = "A"), activeSession = active), page))
        }
        assertTrue(store.save(session, key(profileId = "B"), page))
        store.removeProfile("A")
        sessions.forEach { assertNull(store.load(it, key(activeSession = it))) }
        assertEquals(page, store.load(session, key(profileId = "B")))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun invalidateParentRemovesEveryQueryAndAliasOnlyWithinTheMatchingSessionAndProfile() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val matching = listOf(key(), key(LibraryQuery(nameInitial = "A")),
            key(LibraryQuery(favorite = true), browserId = "library-alias"))
        val unrelated = listOf(key(parentId = "other"), key(parentId = null), key(profileId = "B"))
        val otherSessions = listOf(session.copy(accessToken = "renewed-token"), session.copy(userId = "other-user"),
            session.copy(server = ServerConfig("https://other.test")), session.copy(isAdministrator = true))
        matching.forEach { assertTrue(store.save(session, it, page)) }
        unrelated.forEach { assertTrue(store.save(session, it, page)) }
        otherSessions.forEach { assertTrue(store.save(it, key(activeSession = it), page)) }

        store.invalidate(otherSessions[1], key().scope, "library")
        matching.forEach { assertEquals("不匹配的资料归属不能删除快照", page, store.load(session, it)) }
        store.invalidate(session, key().scope, "library")
        val restored = BrowserSnapshotStore(directory)
        matching.forEach { assertNull("同库的字母、筛选和别名必须一并失效", restored.load(session, it)) }
        unrelated.forEach { assertEquals("其他父库和资料应保留", page, restored.load(session, it)) }
        otherSessions.forEach { assertEquals("其他地址、身份、令牌和权限应保留", page, restored.load(it, key(activeSession = it))) }

        // 无父目录的全局浏览独立失效，不能把 null 当作清理所有父库。
        store.invalidate(session, key().scope, null)
        assertNull(restored.load(session, key(parentId = null)))
        assertEquals(page, restored.load(session, key(parentId = "other")))
        assertEquals(page, restored.load(session, key(profileId = "B")))
        otherSessions.forEach { assertEquals(page, restored.load(it, key(activeSession = it))) }
    }

    @Test fun corruptAndMismatchedSnapshotsAreRemovedWithoutAffectingAnotherAccount() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val otherKey = key(profileId = "B")
        assertTrue(store.save(session, otherKey, page))
        val otherFile = directory.listFiles()!!.single()
        val corruptions: List<(String) -> String> = listOf(
            { "{truncated" }, { "[]" }, { "{\"version\":100}" },
            { text -> JSONObject(text).put("version", 1).toString() },
            { text -> JSONObject(text).put("totalCount", -1).toString() },
            { text -> JSONObject(text).put("browserId", "wrong-library").toString() },
            { text -> JSONObject(text).apply { getJSONObject("query").remove("nameInitial") }.toString() },
            { text -> JSONObject(text).apply { getJSONObject("query").remove("recursive") }.toString() },
        )
        corruptions.forEach { corrupt ->
            assertTrue(store.save(session, key(), page))
            val file = directory.listFiles()!!.single { it != otherFile }
            file.writeText(corrupt(file.readText()))
            assertNull(store.load(session, key()))
            assertFalse(file.exists())
            assertEquals(page, store.load(session, otherKey))
        }
        assertTrue(store.save(session, key(), page))
        val corruptFile = directory.listFiles()!!.single { it != otherFile }
        corruptFile.writeText("bad-cache")
        store.update(session, "A", { it }, { false })
        assertFalse(corruptFile.exists())
    }

    @Test fun perProfileEvictionKeepsRecentlyReadPagesAndLatestWriteAcrossAuthenticationChanges() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        val keys = (0 until 16).map { key(browserId = "library-$it", parentId = "library-$it") }
        keys.forEachIndexed { index, cacheKey ->
            assertTrue(store.save(session, cacheKey, page))
            directory.listFiles()!!.forEach { file -> if (file.lastModified() > 100L) file.setLastModified(index + 1L) }
        }
        assertEquals(page, store.load(session, keys.first()))
        val newSession = session.copy(accessToken = "renewed-token")
        val latestKey = key(browserId = "latest", activeSession = newSession)
        assertTrue(store.save(newSession, latestKey, page))
        assertEquals(16, directory.listFiles()!!.size)
        assertEquals(page, store.load(session, keys.first()))
        assertNull(store.load(session, keys[1]))
        assertEquals(page, store.load(newSession, latestKey))
    }

    @Test fun directoryHasGlobalEntryAndByteLimitsAndOversizedSnapshotsAreRejected() {
        val directory = temporary.newFolder()
        val store = BrowserSnapshotStore(directory)
        (0 until 65).forEach { index -> assertTrue(store.save(session, key(profileId = "profile-$index"), page)) }
        assertEquals(64, directory.listFiles()!!.size)
        assertEquals(page, store.load(session, key(profileId = "profile-64")))
        val large = MediaPage(listOf(episode.copy(overview = "x".repeat(1_500_000))), 1)
        (0 until 24).forEach { index -> assertTrue(store.save(session, key(profileId = "large-$index"), large)) }
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 32L * 1024 * 1024)
        assertEquals(large, store.load(session, key(profileId = "large-23")))
        assertFalse(store.save(session, key(profileId = "oversized"),
            MediaPage(listOf(episode.copy(overview = "x".repeat(2 * 1024 * 1024))), 1)))
        assertNull(store.load(session, key(profileId = "oversized")))
    }

    @Test fun unavailableCacheDirectoryAndInvalidCountsDoNotFailTheApplication() {
        val store = BrowserSnapshotStore(temporary.newFile())
        assertFalse(store.save(session, key(), page))
        assertNull(store.load(session, key()))
        store.update(session, "A", { it }, { false })
        store.invalidate(session, key().scope, "library")
        store.removeProfile("A")
        val writable = BrowserSnapshotStore(temporary.newFolder())
        assertFalse(writable.save(session, key(), page.copy(totalCount = -1)))
        assertFalse(writable.save(session, key(), page.copy(totalCount = 0)))
        assertNull(writable.load(session, key()))
    }
}

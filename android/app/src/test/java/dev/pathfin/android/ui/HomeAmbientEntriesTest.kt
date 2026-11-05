package dev.pathfin.android.ui

import dev.pathfin.android.domain.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAmbientEntriesTest {
    private val movies = MediaItem("movies", "电影", "CollectionFolder", collectionType = "movies", isFolder = true)
    private val shows = MediaItem("shows", "剧集", "CollectionFolder", collectionType = "tvshows", isFolder = true)

    @Test fun resumeComesFirstAndNextUpSkipsItemsAndSeriesAlreadyBeingWatched() {
        val resume = episode("r1", "a")
        val home = HomeState(resumeLoaded = true, nextUpLoaded = true, resume = listOf(resume),
            nextUp = listOf(episode("r1", "a"), episode("a2", "a"), episode("b1", "b"), episode("c1", "c")))
        val entries = homeAmbientEntries(home)
        assertEquals(listOf("r1", "b1", "c1"), entries.map { it.item.id })
        assertEquals(listOf(AmbientKind.RESUME, AmbientKind.NEXT_UP, AmbientKind.NEXT_UP), entries.map { it.kind })
    }

    @Test fun nothingIsShownBeforeResumeArrivesSoNextUpCannotAppearThenVanish() {
        val home = HomeState(loading = true, nextUpLoaded = true, nextUp = listOf(episode("b1", "b")))
        assertTrue(homeAmbientEntries(home).isEmpty())
        assertTrue("续播未返回时先占位", homeAmbientPending(home))
        val emptyResume = HomeState(loading = true, resumeLoaded = true)
        assertTrue("续播为空且下一集未返回时继续等待", homeAmbientEntries(emptyResume).isEmpty())
        assertTrue(homeAmbientPending(emptyResume))
        assertFalse("刷新结束后不再占位", homeAmbientPending(emptyResume.copy(loading = false)))
    }

    @Test fun withoutWatchingItFallsBackToLatestMoviesAndSeriesWithBackdropsInLibraryTurns() {
        val home = HomeState(loading = false, resumeLoaded = true, nextUpLoaded = true, libraries = listOf(movies, shows),
            libraryItems = mapOf(
                movies.id to listOf(media("m1", "Movie"), media("bare", "Movie", backdrop = false), media("m2", "Movie")),
                shows.id to listOf(media("s1", "Series"), media("e1", "Episode"), media("m1", "Movie")),
            ))
        val entries = homeAmbientEntries(home)
        assertEquals(listOf("m1", "s1", "m2"), entries.map { it.item.id })
        assertTrue(entries.all { it.kind == AmbientKind.LATEST })
        assertFalse("有内容时不占位", homeAmbientPending(home.copy(loading = true)))
        assertTrue("什么都没有时不显示氛围区", homeAmbientEntries(HomeState(resumeLoaded = true, nextUpLoaded = true)).isEmpty())
    }

    @Test fun serversWithoutLibrariesUseTheirRecentItemsAndCapTheFallback() {
        val recent = List(10) { media("r$it", "Movie") }
        val entries = homeAmbientEntries(HomeState(resumeLoaded = true, nextUpLoaded = true, recent = recent))
        assertEquals(recent.take(8).map { it.id }, entries.map { it.item.id })
    }

    private fun episode(id: String, series: String) = MediaItem(id, id, "Episode", seriesId = series, seriesName = series)
    private fun media(id: String, type: String, backdrop: Boolean = true) =
        MediaItem(id, id, type, backdropTag = if (backdrop) "backdrop-$id" else null)
}

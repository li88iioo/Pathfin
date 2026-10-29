package dev.pathfin.android.domain

import dev.pathfin.android.domain.BrowserUserDataChange.Kind
import org.junit.Assert.*
import org.junit.Test

class BrowserUserDataChangeTest {
    private val movie = MediaItem("movie", "Movie", "Movie")
    private val normal = LibraryQuery(itemTypes = setOf("Movie"))

    @Test fun favoriteOnlyInvalidatesQueriesWhoseMembershipDependsOnFavorite() {
        val change = BrowserUserDataChange(movie, MediaUserData(isFavorite = true), Kind.FAVORITE)
        assertFalse(change.invalidates(normal))
        assertFalse(change.invalidates(normal.copy(played = false, sort = LibrarySort.TITLE)))
        assertTrue(change.invalidates(normal.copy(favorite = true)))
        assertTrue(change.invalidates(normal.copy(favorite = false)))
        assertTrue(change.invalidates(normal.copy(tab = LibraryTab.FAVORITES)))
        assertFalse(change.invalidates(normal.copy(favorite = true, itemTypes = setOf("Series"))))
        assertTrue(change.apply(movie).isFavorite)
        val other = movie.copy(id = "other")
        assertSame(other, change.apply(other))
    }

    @Test fun playbackRetainsOrdinaryOrderAndInvalidatesProgressQueries() {
        val change = BrowserUserDataChange(movie, MediaUserData(positionTicks = 100), Kind.PLAYBACK)
        assertFalse(change.invalidates(normal))
        assertFalse(change.invalidates(normal.copy(sort = LibrarySort.TITLE, favorite = true)))
        listOf(normal.copy(played = true), normal.copy(played = false),
            normal.copy(tab = LibraryTab.RESUME), normal.copy(sort = LibrarySort.DATE_PLAYED)).forEach {
            assertTrue("进度相关查询必须重新计算: $it", change.invalidates(it))
        }
        assertFalse(change.invalidates(normal.copy(played = false, itemTypes = setOf("Series"))))
    }

    @Test fun markingAnEntireSeriesPlayedInvalidatesItsDescendantQueries() {
        val change = BrowserUserDataChange(MediaItem("series", "Series", "Series"), MediaUserData(played = true), Kind.PLAYED)
        assertTrue(change.invalidates(LibraryQuery(itemTypes = setOf("Episode"), episodeOrder = true)))
        assertTrue(change.invalidates(LibraryQuery(itemTypes = setOf("Season"))))
        assertFalse(change.invalidates(normal))
        assertFalse(change.invalidates(normal.copy(itemTypes = setOf("Series"))))
    }

    @Test fun explicitPlayedOperationsInvalidateDescendantsEvenWhenTheParentKeepsItsPlayedState() {
        for (type in listOf("Series", "Season")) for (played in listOf(false, true)) {
            val parent = MediaItem("parent", "Parent", type, played = played)
            val change = BrowserUserDataChange(parent, MediaUserData(played = played), Kind.PLAYED)
            assertTrue(change.invalidates(LibraryQuery(itemTypes = setOf("Episode"))))
            assertTrue(change.invalidates(LibraryQuery(itemTypes = setOf("Season"))))
            assertTrue(change.invalidates(LibraryQuery(tab = LibraryTab.RESUME, itemTypes = setOf("Series"))))
            assertTrue(change.invalidates(LibraryQuery(itemTypes = setOf(type), sort = LibrarySort.DATE_PLAYED)))
            assertFalse(change.invalidates(normal.copy(played = false)))
            assertFalse(change.invalidates(LibraryQuery(tab = LibraryTab.COLLECTIONS)))
        }
    }

    @Test fun favoriteAndPlaybackResponsesDoNotImplyAnExplicitRecursivePlayedOperation() {
        val series = MediaItem("series", "Series", "Series")
        val data = MediaUserData(isFavorite = true, played = true, playCount = 1, unplayedItemCount = 0)
        for (kind in listOf(Kind.FAVORITE, Kind.PLAYBACK)) {
            val change = BrowserUserDataChange(series, data, kind)
            assertFalse(change.invalidates(LibraryQuery(itemTypes = setOf("Episode"))))
            assertFalse(change.invalidates(LibraryQuery(itemTypes = setOf("Season"))))
            assertFalse(change.invalidates(LibraryQuery(tab = LibraryTab.RESUME, itemTypes = setOf("Series"))))
            assertTrue(change.invalidates(LibraryQuery(itemTypes = setOf("Series"), favorite = true)))
            assertEquals(data.applyTo(series), change.apply(series))
        }
    }

    @Test fun tabOverridesUseTheSameEffectiveTypesAsServerQueries() {
        val episode = BrowserUserDataChange(movie.copy(type = "Episode"), MediaUserData(played = true), Kind.PLAYED)
        assertTrue(episode.invalidates(LibraryQuery(tab = LibraryTab.RESUME, itemTypes = setOf("Series"))))
        val collection = BrowserUserDataChange(movie.copy(type = "BoxSet"), MediaUserData(isFavorite = true), Kind.FAVORITE)
        assertTrue(collection.invalidates(normal.copy(tab = LibraryTab.COLLECTIONS, favorite = true)))
        assertFalse(BrowserUserDataChange(movie, MediaUserData(isFavorite = true), Kind.FAVORITE)
            .invalidates(normal.copy(tab = LibraryTab.COLLECTIONS, favorite = true)))
        val mixed = MediaItem("mixed", "Mixed", "CollectionFolder").defaultLibraryQuery()
        assertFalse("混合续播与 Web 一致仅请求 Movie/Series", episode.invalidates(mixed.copy(tab = LibraryTab.RESUME)))
        assertTrue(BrowserUserDataChange(movie, MediaUserData(positionTicks = 100), Kind.PLAYBACK)
            .invalidates(mixed.copy(tab = LibraryTab.RESUME)))
        assertFalse(BrowserUserDataChange(movie.copy(type = "Folder"), MediaUserData(played = true), Kind.PLAYED)
            .invalidates(mixed.copy(tab = LibraryTab.MEDIA, played = false)))
    }

    @Test fun inFlightResponseOverlayKeepsEveryWrittenFieldWithoutInventingUnwrittenValues() {
        val earlier = MediaUserData(isFavorite = true, played = true)
        val latest = MediaUserData(positionTicks = 1234).after(earlier)
        assertTrue(latest.applyTo(movie).isFavorite)
        assertTrue(latest.applyTo(movie).played)
        assertEquals(1234L, latest.applyTo(movie).positionTicks)
        assertNull(latest.unplayedItemCount)
        assertFalse(BrowserUserDataChange(movie, MediaUserData(isFavorite = false), Kind.FAVORITE).invalidates(normal.copy(favorite = true)))
    }
}

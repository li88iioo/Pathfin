package dev.pathfin.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDefaultsTest {
    @Test fun televisionLibraryUsesSeriesAndWebTitleOrder() {
        val library = MediaItem("tv", "Television", "CollectionFolder", collectionType = "tvshows")

        assertEquals(
            LibraryQuery(sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Series")),
            library.defaultLibraryQuery(),
        )
    }

    @Test fun movieLibraryUsesOnlyMoviesAndWebTitleOrder() {
        val library = MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")

        assertEquals(
            LibraryQuery(sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Movie")),
            library.defaultLibraryQuery(),
        )
    }

    @Test fun missingAndUnknownCollectionTypesBrowseMixedLibraryDirectories() {
        for (collectionType in listOf(null, "", "unknown")) {
            val library = MediaItem("mixed", "Mixed", "CollectionFolder", collectionType = collectionType)

            assertEquals(
                LibraryQuery(sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Folder", "Movie", "Series"), recursive = false),
                library.defaultLibraryQuery(),
            )
        }
    }

    @Test fun ordinaryFoldersBrowseAllDirectChildTypesEvenWhenTheyInheritACollectionType() {
        for (collectionType in listOf(null, "movies", "tvshows", "music")) {
            val folder = MediaItem("folder", "Folder", "Folder", collectionType = collectionType)
            assertEquals(LibraryQuery(sort = LibrarySort.TITLE, descending = false, recursive = false),
                folder.defaultLibraryQuery())
            assertEquals(LibraryQuery(sort = LibrarySort.TITLE, descending = false, searchTerm = "目标"),
                folder.librarySearchQuery(" 目标 "))
        }
    }

    @Test fun mixedLibrarySearchRecursesWithoutRestrictingResultsToTheRootDirectory() {
        val library = MediaItem("mixed", "Mixed", "CollectionFolder")
        assertEquals(LibraryQuery(sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Movie", "Series"), searchTerm = "目标"),
            library.librarySearchQuery(" 目标 "))
        assertEquals(false, library.defaultLibraryQuery().recursive)
        assertEquals(true, library.librarySearchQuery("").recursive)
    }

    @Test fun musicCollectionBrowsesAlbumsAndAlbumBrowsesOnlyItsTracks() {
        val library = MediaItem("music", "Music", "CollectionFolder", collectionType = "MuSiC")
        assertEquals(setOf("MusicAlbum"), library.defaultLibraryQuery().itemTypes)
        val album = MediaItem("album", "Album", "MusicAlbum")
        assertEquals(LibraryQuery(itemTypes = setOf("Audio"), sort = LibrarySort.TITLE, descending = false),
            album.defaultLibraryQuery())
    }

    @Test fun collectionAndHomeVideoLibrariesKeepTheirOwnContentTypes() {
        assertEquals(setOf("BoxSet"),
            MediaItem("collections", "Collections", "CollectionFolder", collectionType = "BoxSets")
                .defaultLibraryQuery().itemTypes)
        assertEquals(setOf("Video"),
            MediaItem("videos", "Videos", "CollectionFolder", collectionType = "HomeVideos")
                .defaultLibraryQuery().itemTypes)
    }

    @Test fun musicSearchIncludesAlbumsArtistsAndTracksWithoutUnrelatedVideoTypes() {
        val library = MediaItem("music", "Music", "CollectionFolder", collectionType = "music")
        val search = library.librarySearchQuery("  曲目  ")
        assertEquals("曲目", search.searchTerm)
        assertEquals(LibraryTab.ALL, search.tab)
        assertEquals(setOf("MusicAlbum", "MusicArtist", "Audio"), search.itemTypes)
    }

    @Test fun boxSetKeepsUnrestrictedContentsEvenWhenCollectionTypeIsPresent() {
        for (collectionType in listOf(null, "tvshows", "movies")) {
            val collection = MediaItem("collection", "Collection", "BoxSet", collectionType = collectionType)

            assertEquals(LibraryQuery(), collection.defaultLibraryQuery())
        }
    }

    @Test fun knownCollectionTypesAreMatchedWithoutCaseSensitivity() {
        assertEquals(setOf("Series"), MediaItem("tv", "TV", "CollectionFolder", collectionType = "TvShows")
            .defaultLibraryQuery().itemTypes)
        assertEquals(setOf("Movie"), MediaItem("movies", "Movies", "CollectionFolder", collectionType = "Movies")
            .defaultLibraryQuery().itemTypes)
    }

    @Test fun movieSearchUsesAnIndependentAllQueryAndPreservesLiteralSearchCharacters() {
        val library = MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")
        assertEquals(
            LibraryQuery(tab = LibraryTab.ALL, sort = LibrarySort.TITLE, descending = false, itemTypes = setOf("Movie"), searchTerm = "星际 & parentId=other+100% /?#"),
            library.librarySearchQuery("  星际 & parentId=other+100% /?#  "),
        )
    }

    @Test fun televisionSearchKeepsOnlySeriesRatherThanEpisodeOrCollectionRows() {
        val library = MediaItem("tv", "TV", "CollectionFolder", collectionType = "tvshows")
        assertEquals(
            LibraryQuery(tab = LibraryTab.ALL, sort = LibrarySort.TITLE, descending = false,
                itemTypes = setOf("Series"), searchTerm = "目标"),
            library.librarySearchQuery("目标"),
        )
    }

    @Test fun clearingSearchRemovesOnlyTheTermAndKeepsTheLibraryTypeContract() {
        val library = MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")
        assertEquals(library.defaultLibraryQuery(), library.librarySearchQuery(" \t\n "))
    }

    @Test fun nameInitialChangesOnlyItsOwnQueryIdentityAndClearingRestoresTheOriginalScope() {
        val base = LibraryQuery(tab = LibraryTab.ALL, sort = LibrarySort.COMMUNITY_RATING,
            descending = false, favorite = false, played = true, itemTypes = setOf("Movie"),
            tags = setOf("4K"), years = setOf(2024), searchTerm = "同库搜索")
        for (initial in listOf("0", "#", "A", "Z")) {
            val selected = base.copy(nameInitial = initial)
            assertEquals(initial, selected.nameInitial)
            assertEquals(base, selected.copy(nameInitial = null))
            org.junit.Assert.assertNotEquals(base, selected)
        }
        assertEquals(null, MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")
            .librarySearchQuery("目标").nameInitial)
    }

    @Test fun categoriesMatchWebLibraryScopesAndDoNotExposeUnsupportedPages() {
        assertEquals(listOf(LibraryTab.ALL, LibraryTab.RESUME, LibraryTab.COLLECTIONS, LibraryTab.GENRES, LibraryTab.FAVORITES),
            availableLibraryTabs("Movies"))
        assertEquals(listOf(LibraryTab.ALL, LibraryTab.RESUME, LibraryTab.UPCOMING, LibraryTab.COLLECTIONS, LibraryTab.GENRES, LibraryTab.FAVORITES),
            availableLibraryTabs("TvShows"))
        for (type in listOf(null, "", "unknown")) {
            assertEquals(listOf(LibraryTab.ALL, LibraryTab.MEDIA, LibraryTab.RESUME, LibraryTab.COLLECTIONS, LibraryTab.FAVORITES),
                availableLibraryTabs(type))
        }
        assertEquals(listOf(LibraryTab.ALL, LibraryTab.GENRES, LibraryTab.FAVORITES), availableLibraryTabs("music"))
        assertEquals(listOf(LibraryTab.ALL, LibraryTab.FAVORITES), availableLibraryTabs(null, libraryCategories = false))
    }

    @Test fun recentShelvesExcludeVirtualViewsWithoutRemovingLibraryEntries() {
        for (type in listOf("LiVeTv", "folders", "playlists", "boxsets", "channels")) {
            assertEquals(false, MediaItem("id", "Library", "CollectionFolder", collectionType = type).supportsLatestItems)
        }
        for (type in listOf(null, "movies", "tvshows", "music", "homevideos")) {
            assertEquals(true, MediaItem("id", "Library", "CollectionFolder", collectionType = type).supportsLatestItems)
        }
    }

    @Test fun latestContentOrderUsesDeterministicTitleTieBreaker() {
        assertEquals("DateLastContentAdded,SortName", LibrarySort.DATE_LAST_CONTENT_ADDED.apiValue)
    }

    @Test fun otherUserSelectableSortsKeepTheirExistingApiValues() {
        val expected = mapOf(
            LibrarySort.DATE_ADDED to "DateCreated",
            LibrarySort.DATE_CREATED to "DateCreated",
            LibrarySort.COMMUNITY_RATING to "CommunityRating",
            LibrarySort.TITLE to "SortName",
            LibrarySort.PREMIERE_DATE to "PremiereDate",
            LibrarySort.OFFICIAL_RATING to "OfficialRating",
            LibrarySort.YEAR to "ProductionYear",
            LibrarySort.CRITIC_RATING to "CriticRating",
            LibrarySort.DATE_PLAYED to "DatePlayed",
        )

        assertEquals(expected, LibrarySort.entries.filterNot { it == LibrarySort.DATE_LAST_CONTENT_ADDED }
            .associateWith { it.apiValue })
    }
}

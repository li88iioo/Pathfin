package dev.pathfin.android.ui

import dev.pathfin.android.domain.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCardTextTest {
    @Test fun seriesUsesOnlyItsExplicitSeasonCountAndOrderedYearRange() {
        val series = MediaItem("series", "合成剧集", "Series", year = 2020, endYear = 2025, childCount = 3)
        assertEquals("合成剧集", libraryCardTitle(series))
        assertEquals("共3季 · 2020–2025", libraryCardSubtitle(series))
        assertEquals("2020–2025", libraryCardSubtitle(series.copy(childCount = null)))
        assertEquals("2020", libraryCardSubtitle(series.copy(childCount = 0, endYear = 2019)))
        assertEquals("2020", libraryCardSubtitle(series.copy(childCount = -1, endYear = 2020)))
        assertEquals("共3季", libraryCardSubtitle(series.copy(year = null)))
        assertEquals("", libraryCardSubtitle(series.copy(childCount = null, year = null)))
    }

    @Test fun seasonUsesAnExplicitEpisodeCountWithoutInferringItFromItsIndex() {
        val season = MediaItem("season", "第二季", "Season", seriesName = "合成剧集",
            year = 2026, indexNumber = 2, childCount = 12)
        assertEquals("第二季", libraryCardTitle(season))
        assertEquals("共12集 · 2026", libraryCardSubtitle(season))
        assertEquals("2026", libraryCardSubtitle(season.copy(childCount = null)))
        assertEquals("共12集", libraryCardSubtitle(season.copy(year = null)))
        assertEquals("", libraryCardSubtitle(season.copy(childCount = 0, year = null)))
    }

    @Test fun episodeUsesItsSeriesNameAndItsOwnSeasonEpisodeAndName() {
        val episode = MediaItem("episode", "第17集", "Episode", seriesName = " 合成剧名 ",
            parentIndexNumber = 1, indexNumber = 17)
        assertEquals("合成剧名", libraryCardTitle(episode))
        assertEquals("S1E17：第17集", libraryCardSubtitle(episode))
        assertEquals("S1E17", libraryCardSubtitle(episode.copy(name = "合成剧名")))
        assertEquals("E17：第17集", libraryCardSubtitle(episode.copy(parentIndexNumber = null)))
        assertEquals("S0E0：第17集", libraryCardSubtitle(episode.copy(parentIndexNumber = 0, indexNumber = 0)))
    }

    @Test fun missingEpisodeMetadataKeepsItsOwnNameWithoutInventingNumbers() {
        val episode = MediaItem("unknown", "未命名单集", "Episode", seriesName = " ")
        assertEquals("未命名单集", libraryCardTitle(episode))
        assertEquals("", libraryCardSubtitle(episode))
        assertEquals("", libraryCardSubtitle(episode.copy(parentIndexNumber = -1, indexNumber = -1)))
        assertEquals("E3", libraryCardSubtitle(episode.copy(indexNumber = 3)))
        assertEquals("未命名单集", libraryCardSubtitle(episode.copy(seriesName = "合成剧名")))
    }

    @Test fun moviesKeepTheirNameAndYearAndInvalidYearsAreOmitted() {
        val movie = MediaItem("movie", "合成电影", "Movie", year = 2026, endYear = 2028,
            childCount = 20, indexNumber = 17, parentIndexNumber = 1)
        assertEquals("合成电影", libraryCardTitle(movie))
        assertEquals("2026", libraryCardSubtitle(movie))
        assertEquals("", libraryCardSubtitle(movie.copy(year = null)))
        assertEquals("", libraryCardSubtitle(movie.copy(year = 0)))
        assertEquals("", libraryCardSubtitle(movie.copy(year = 10000)))
        assertEquals("", libraryCardSubtitle(movie.copy(type = "Series", childCount = null, year = -1)))
    }
}

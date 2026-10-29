package dev.pathfin.android.ui

import dev.pathfin.android.domain.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterCardTextTest {
    @Test fun compactEpisodeIncludesSeriesAndEpisodeWhileBrowserKeepsTheItemName() {
        val episode = MediaItem("episode", "第17集", "Episode", seriesName = "合成剧名",
            parentIndexNumber = 1, indexNumber = 17)
        assertEquals("合成剧名", posterCardTitle(episode, compact = true))
        assertEquals("S1E17：第17集", posterCardSubtitle(episode, compact = true))
        assertEquals("第17集", posterCardTitle(episode, compact = false))
        assertEquals("S1E17", posterCardSubtitle(episode, compact = false))
    }

    @Test fun missingEpisodeMetadataNeverInventsASeriesOrAnEpisodeNumber() {
        val unknown = MediaItem("unknown", "未命名单集", "Episode", seriesName = " ")
        assertEquals("未命名单集", posterCardTitle(unknown, compact = true))
        assertEquals("", posterCardSubtitle(unknown, compact = true))
        assertEquals("E3", posterCardSubtitle(unknown.copy(indexNumber = 3), compact = true))
        assertEquals("", posterCardSubtitle(unknown.copy(indexNumber = -1, parentIndexNumber = -1), compact = true))
        assertEquals("S0E0", posterCardSubtitle(unknown.copy(indexNumber = 0, parentIndexNumber = 0), compact = true))
    }

    @Test fun seriesOnlyDisplaysExplicitSeasonCountAndValidYearRange() {
        val series = MediaItem("series", "合成剧集", "Series", year = 2020, endYear = 2025)
        // Latest 在 API 边界清空分组 ChildCount；这里不能凭名称或集数补造季数。
        assertEquals("2020–2025", posterCardSubtitle(series, compact = true))
        assertEquals("共3季 · 2020–2025", posterCardSubtitle(series.copy(childCount = 3), compact = true))
        assertEquals("2020", posterCardSubtitle(series.copy(childCount = 0, endYear = 2019), compact = true))
        assertEquals("", posterCardSubtitle(series.copy(year = null, childCount = null), compact = true))
        assertEquals("2020", posterCardSubtitle(series, compact = false))
    }

    @Test fun seasonAndMovieKeepTheirOwnShortSecondaryInformation() {
        val season = MediaItem("season", "第二季", "Season", seriesName = "合成剧名", year = 2026, indexNumber = 2)
        assertEquals("合成剧名", posterCardTitle(season, compact = true))
        assertEquals("第2季 · 2026", posterCardSubtitle(season, compact = true))
        val movie = MediaItem("movie", "合成电影", "Movie", year = 2026, indexNumber = 17, parentIndexNumber = 1)
        assertEquals("合成电影", posterCardTitle(movie, compact = true))
        assertEquals("2026", posterCardSubtitle(movie, compact = true))
        assertEquals("2026", posterCardSubtitle(movie, compact = false))
    }
}

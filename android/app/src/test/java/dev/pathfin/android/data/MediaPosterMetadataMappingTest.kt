package dev.pathfin.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaPosterMetadataMappingTest {
    @Test fun movieRetainsItsRealNameAndProductionYear() {
        val movie = parse("""{"Type":"Movie","Name":"真实电影名","ProductionYear":2022}""")
        assertEquals("真实电影名", movie.name)
        assertEquals(2022, movie.year)
        assertNull(movie.seriesName)
        assertNull(movie.endYear)
    }

    @Test fun episodeKeepsSeriesEpisodeNameAndSeasonEpisodeNumbersSeparately() {
        val episode = parse("""{"Type":"Episode","Name":"真实集名","SeriesName":"真实剧名","ParentIndexNumber":1,"IndexNumber":17,"ProductionYear":2024}""")
        assertEquals("真实剧名", episode.seriesName)
        assertEquals("真实集名", episode.name)
        assertEquals(1, episode.parentIndexNumber)
        assertEquals(17, episode.indexNumber)
        assertEquals(2024, episode.year)

        for (fields in listOf("{}", """{"SeriesName":null}""", """{"SeriesName":"  "}""", """{"SeriesName":7}""")) {
            val missingSeries = JsonMapping.mediaItem(JSONObject(fields).put("Id", "episode").put("Name", "独立集名"))
            assertNull(missingSeries.seriesName)
            assertEquals("独立集名", missingSeries.name)
        }
    }

    @Test fun onlyNormalSeriesChildCountCanRepresentSeasonsNeverRecursiveEpisodeCount() {
        // 官方普通 Series DTO 的 ChildCount 查询只统计直接 Season；Latest 覆盖行为由 API 测试单独验证。
        val series = parse("""{"Type":"Series","ChildCount":3,"RecursiveItemCount":51,"ProductionYear":2022,"EndDate":"2025-01-01T00:00:00.0000000Z"}""")
        assertEquals(3, series.childCount)
        assertEquals(2022, series.year)
        assertEquals(2025, series.endYear)
        val onlyEpisodes = parse("""{"Type":"Series","RecursiveItemCount":51}""")
        assertNull(onlyEpisodes.childCount)
        assertNull(onlyEpisodes.year)
        assertNull(onlyEpisodes.endYear)
    }

    @Test fun endYearRequiresARealValidEndDateAndNeverComesFromStatusOrPremiereDate() {
        assertNull(parse("""{"Type":"Series","ProductionYear":2022,"PremiereDate":"2022-04-01T00:00:00Z","Status":"Ended"}""").endYear)
        assertEquals(2025, parse("""{"EndDate":"2025-12-31T23:30:00-08:00"}""").endYear)
        for (value in listOf(JSONObject.NULL, "", " ", "2025", "2025-02-30T00:00:00Z", "0000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z", 2025, true)) {
            assertNull("非法结束日期 $value", JsonMapping.mediaItem(JSONObject().put("Id", "show").put("EndDate", value)).endYear)
        }
        assertNull(parse("""{"ProductionYear":2022,"EndDate":"2021-12-31T00:00:00Z"}""").endYear)
    }

    @Test fun yearAndEpisodeNumbersRejectFractionsOverflowAndNegativeValues() {
        for (value in listOf(-1, 2.5, 2_147_483_648L, 4_294_967_297L, "NaN", true, JSONObject.NULL)) {
            val item = JsonMapping.mediaItem(JSONObject().put("Id", "episode").put("ProductionYear", value)
                .put("IndexNumber", value).put("ParentIndexNumber", value))
            assertNull("非法年份 $value", item.year)
            assertNull("非法集数 $value", item.indexNumber)
            assertNull("非法季数 $value", item.parentIndexNumber)
        }
        val special = parse("""{"ProductionYear":0,"IndexNumber":0,"ParentIndexNumber":0}""")
        assertNull(special.year)
        assertEquals(0, special.indexNumber)
        assertEquals(0, special.parentIndexNumber)
        assertNull(parse("""{"ProductionYear":10000}""").year)
    }

    private fun parse(fields: String) = JsonMapping.mediaItem(JSONObject(fields).put("Id", "media"))
}

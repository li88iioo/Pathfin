package dev.pathfin.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvGroupsTest {
    private fun channel(name: String) = LiveTvChannel(MediaItem(name, name, "TvChannel"))

    @Test fun groupsAreMatchedByTheChannelNameJellyfinDerivesAndKeepPlaylistOrder() {
        val groups = LiveTvGroups.parse("""
            #EXTM3U x-tvg-url="http://epg.example/e.xml"
            #EXTINF:-1 tvg-id="CCTV10科教" tvg-name="CCTV10科教" tvg-logo="http://logo.example/a,b.png" group-title="央视",CCTV10科教
            https://live.example/cctv10.m3u8
            #EXTINF:-1 tvg-name="CQTV新闻" catchup="none" group-title="重庆",CQTV新闻
            http://192.168.88.110:1905/proxy/chongqing-6.m3u8
            #EXTINF:-1 tvg-id="CCTV10科教" group-title="备用",CCTV10科教
            https://backup.example/cctv10.m3u8
            #EXTINF:-1 group-title="卫视",84. 东方卫视
            https://live.example/dongfang
        """.trimIndent())
        assertEquals("属性值里的逗号不影响频道名", "央视", groups.of(channel("CCTV10科教")))
        assertEquals("重庆", groups.of(channel("CQTV新闻")))
        assertEquals("SatIp 台号前缀与服务器一样去掉", "卫视", groups.of(channel("东方卫视")))
        assertEquals("同名频道取第一次出现的分组，空分组不留", listOf("央视", "重庆", "卫视"), groups.order)
    }

    @Test fun nameFallsBackToTvgNameThenTvgIdAndEntriesWithoutGroupAreIgnored() {
        val groups = LiveTvGroups.parse("""
            #EXTINF:-1 tvg-name="凤凰中文" group-title="香港"
            http://a.example/1
            #EXTINF:-1 tvg-id="RTHK31" group-title="香港",
            http://a.example/2
            #EXTINF:-1 tvg-name="无分组",无分组
            http://a.example/3
            #extinf:-1 group-title="小写",小写前缀
            http://a.example/4
        """.trimIndent())
        assertEquals("香港", groups.of(channel("凤凰中文")))
        assertEquals("香港", groups.of(channel("RTHK31")))
        assertNull(groups.of(channel("无分组")))
        assertEquals("小写", groups.of(channel("小写前缀")))
        assertEquals(listOf("香港", "小写"), groups.order)
    }

    @Test fun sectionsFollowPlaylistOrderAndCollectUnmatchedChannelsLast() {
        val groups = LiveTvGroups(listOf("CCTV1" to "央视", "CCTV2" to "央视", "CQTV" to "重庆", "CCTV10" to "央视", "RTHK31" to "香港"))
        // 服务器按 SortName 返回：CCTV1、CCTV10、CCTV2……
        val server = listOf("CCTV1", "CCTV10", "CCTV2", "CQTV", "神秘台").map(::channel)
        val sections = groups.sections(server)
        assertEquals(listOf("央视", "重庆", null), sections.map { it.group })
        assertEquals("组内按播放列表顺序", listOf("CCTV1", "CCTV2", "CCTV10"), sections[0].channels.map { it.name })
        assertEquals("对不上分组的收在最后", listOf("神秘台"), sections.last().channels.map { it.name })
        assertTrue("没有频道的分组不出现", sections.none { it.group == "香港" })
    }

    @Test fun withoutGroupsAllChannelsFormOneSectionInServerOrder() {
        val server = listOf("B", "A").map(::channel)
        assertEquals(listOf(LiveTvSection(null, server)), LiveTvGroups().sections(server))
        assertTrue(LiveTvGroups().sections(emptyList()).isEmpty())
    }

    @Test fun emptyOrMalformedPlaylistYieldsNoGroups() {
        assertTrue(LiveTvGroups.parse("").order.isEmpty())
        assertTrue(LiveTvGroups.parse("not a playlist\n#EXTINF:-1 group-title=\"无名\"\n").byName.isEmpty())
    }
}

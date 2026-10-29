package dev.pathfin.android.domain

import dev.pathfin.android.data.JsonMapping
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalSubtitleResolverTest {
    private val session = Session(ServerConfig("https://jellyfin.example/jellyfin"), "jf-private-token", "user", "User", "device")

    @Test fun onlyExplicitExternalTextStreamsWithValidIndicesAreAttached() {
        val streams = listOf(
            subtitle(4, "srt", "/Videos/item/source/Subtitles/4/Stream.srt"),
            subtitle(5, "vtt", "/Videos/item/source/Subtitles/5/Stream.vtt").copy(isExternal = false),
            subtitle(6, "ass", "/Videos/item/source/Subtitles/6/Stream.ass").copy(type = "Audio"),
            subtitle(7, "srt", null), subtitle(-1, "srt", "/invalid.srt"),
            subtitle(8, "srt", "/missing-index.srt").copy(index = null),
            subtitle(9, "pgssub", "/image.sup"), subtitle(10, "srt", "/unknown.txt"),
        )
        val actual = ExternalSubtitleResolver.resolve(request(streams))
        assertEquals(listOf(4), actual.map { it.index })
        assertEquals("application/x-subrip", actual.single().mimeType)
    }

    @Test fun relativePathsRespectServerPrefixWithoutDuplicatingIt() {
        val streams = listOf(
            subtitle(1, "srt", "/Videos/a.srt"),
            subtitle(2, "vtt", "/jellyfin/Videos/b.vtt"),
            subtitle(3, "ass", "Videos/c.ass"),
        )
        assertEquals(listOf(
            "https://jellyfin.example/jellyfin/Videos/a.srt",
            "https://jellyfin.example/jellyfin/Videos/b.vtt",
            "https://jellyfin.example/jellyfin/Videos/c.ass",
        ), ExternalSubtitleResolver.resolve(request(streams)).map { it.uri })
    }

    @Test fun deliveredVttTakesPrecedenceOverOriginalAssAndKeepsTrackMetadata() {
        val stream = subtitle(12, "ass", "/Videos/Stream.vtt").copy(
            language = "zh-Hans", displayTitle = "中文外挂", isDefault = true, isForced = true, isHearingImpaired = true,
        )
        val actual = ExternalSubtitleResolver.resolve(request(listOf(stream))).single()
        assertEquals("text/vtt", actual.mimeType)
        assertEquals("zh-Hans", actual.language)
        assertEquals("中文外挂", actual.label)
        assertTrue(actual.isDefault && actual.isForced && actual.isHearingImpaired)
        val codecOnly = ExternalSubtitleResolver.resolve(request(listOf(subtitle(13, "ssa", "/Videos/Stream")))).single()
        assertEquals("text/x-ssa", codecOnly.mimeType)
    }

    @Test fun unsafeOrUnknownUrlsDoNotCreateConfigurations() {
        val invalid = listOf(
            "//evil.example/Stream.srt", "file:///Stream.srt", "content://subtitles/a.srt", "javascript:Stream.srt",
            "https://name:password@evil.example/Stream.srt", "https://evil.example:70000/Stream.srt",
            "https://evil.example/Stream.srt#fragment", "/../Stream.srt", "/%2e%2e/Stream.srt",
            "/%252e%252e/Stream.srt", "/%255cStream.srt", "/bad\\Stream.srt", "/bad Stream.srt",
            "https://evil.example/Stream.srt?api_key=jf%252Dprivate%252Dtoken",
        )
        for (url in invalid) assertTrue("必须拒绝测试构造的无效地址", ExternalSubtitleResolver.resolve(request(listOf(subtitle(1, "srt", url)))).isEmpty())
    }

    @Test fun serverTokenQueriesMoveToScopedHeadersAndPreserveOtherSignedParameters() {
        val url = "/Videos/Stream.srt?api_key=jf-private-token&a=one%2Btwo&%54oken=stale&a=2&signature=x%2Fy"
        val actual = ExternalSubtitleResolver.resolve(request(listOf(subtitle(1, "srt", url)))).single()
        assertEquals("https://jellyfin.example/jellyfin/Videos/Stream.srt?a=one%2Btwo&a=2&signature=x%2Fy", actual.uri)
        val external = "https://subtitles.example/Stream.srt?token=subtitle-own-token&signature=a%2Fb"
        assertEquals(external, ExternalSubtitleResolver.resolve(request(listOf(subtitle(2, "srt", external)))).single().uri)
    }

    @Test fun explicitExternalHttpIsAllowedButUnconfirmedHttpServerIsRejected() {
        val stream = subtitle(1, "srt", "http://subtitles.example/Stream.srt")
        assertEquals(stream.deliveryUrl, ExternalSubtitleResolver.resolve(request(listOf(stream))).single().uri)
        val unsafe = request(listOf(stream)).copy(session = session.copy(server = ServerConfig("http://jellyfin.example")))
        assertTrue(ExternalSubtitleResolver.resolve(unsafe).isEmpty())
    }

    @Test fun duplicateIndicesAndAddressesDoNotCreateAmbiguousTrackIds() {
        val actual = ExternalSubtitleResolver.resolve(request(listOf(
            subtitle(1, "srt", "/a.srt"), subtitle(1, "srt", "/b.srt"), subtitle(2, "srt", "/a.srt"),
        )))
        assertEquals(1, actual.size)
    }

    @Test fun detailsAndPlaybackShareExactMediaStreamMappingWithoutInventedValues() {
        val json = JSONObject("""{
          "Id":"movie","MediaSources":[{"Id":"source","Bitrate":123456,"Size":9876543210,"RunTimeTicks":1200000000,
            "MediaStreams":[
              {"Type":"Video","Index":0,"Codec":"h264","Width":1920,"Height":1080,"BitDepth":8,"VideoRange":"SDR","VideoRangeType":"SDR","AverageFrameRate":23.976,"ColorTransfer":"bt709","Profile":"High","Level":41},
              {"Type":"Audio","Index":1,"Codec":"aac","Language":"jpn","Channels":2,"ChannelLayout":"stereo","SampleRate":48000,"BitRate":192000},
              {"Type":"Subtitle","Index":4,"Codec":"ass","IsExternal":true,"DeliveryUrl":"/Videos/Stream.vtt","DisplayTitle":"中文","Language":"zho","IsDefault":true},
              {"Type":"Video","Index":-1,"Codec":null,"Width":0,"Height":-1,"AverageFrameRate":"NaN"}
            ]
          }]}
        """)
        val details = JsonMapping.mediaItem(json).mediaSources
        val playback = JsonMapping.playbackInfo(json).sources
        assertEquals(playback, details)
        assertEquals(playback, JsonMapping.mediaSources(json.getJSONArray("MediaSources")))
        val source = details.single()
        assertEquals(123456, source.bitRate)
        assertEquals(9876543210L, source.size)
        val video = source.mediaStreams[0]
        assertEquals("h264", video.codec); assertEquals(1920, video.width); assertEquals("SDR", video.videoRange)
        assertEquals(23.976, checkNotNull(video.averageFrameRate), 0.0001)
        val audio = source.mediaStreams[1]
        assertEquals("jpn", audio.language); assertEquals(2, audio.channels); assertEquals(48000, audio.sampleRate)
        assertTrue(source.mediaStreams[2].isExternal)
        val sparse = source.mediaStreams[3]
        assertNull(sparse.index); assertNull(sparse.codec); assertNull(sparse.width); assertNull(sparse.height)
        assertNull(sparse.averageFrameRate); assertNull(sparse.videoRange); assertNull(sparse.channels)
    }

    @Test fun untypedBooleansAndMalformedIndicesNeverAuthorizeSubtitleAttachment() {
        val sources = JsonMapping.mediaSources(JSONArray("""[{"Id":"source","MediaStreams":[
            {"Type":"Subtitle","Index":1,"Codec":"srt","DeliveryUrl":"/a.srt","IsExternal":"true"},
            {"Type":"Subtitle","Index":1.5,"Codec":"srt","DeliveryUrl":"/b.srt","IsExternal":true},
            {"Type":"Subtitle","Index":2147483648,"Codec":"srt","DeliveryUrl":"/c.srt","IsExternal":true}
        ]}]"""))
        assertTrue(ExternalSubtitleResolver.resolve(request(sources.single().mediaStreams)).isEmpty())
        assertTrue(JsonMapping.mediaSources(null).isEmpty())
    }

    private fun subtitle(index: Int, codec: String, url: String?) = MediaStream(index = index, type = "Subtitle", codec = codec, isExternal = true, deliveryUrl = url)

    private fun request(streams: List<MediaStream>): PlaybackRequest {
        val source = MediaSource("source", path = "https://media.example/video.mp4", mediaStreams = streams)
        return PlaybackRequest(MediaItem("item", "Fixture", "Movie"), PlaybackInfo(listOf(source), "session"), source,
            ResolvedPlayback(source.path!!, emptyMap(), PlayMethod.DIRECT_PLAY, source.id, false), 0, session)
    }
}

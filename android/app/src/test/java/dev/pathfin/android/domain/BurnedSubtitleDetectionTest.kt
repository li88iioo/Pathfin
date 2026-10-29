package dev.pathfin.android.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnedSubtitleDetectionTest {
    @Test fun onlyTheCurrentTrustedTranscodesSelectedEncodeTrackIsRecognized() {
        assertTrue(request("SubtitleStreamIndex=2&SubtitleMethod=Encode").hasServerBurnedSubtitles())
        assertTrue(request("SUBTITLESTREAMINDEX=2&SUBTITLEMETHOD=encode").hasServerBurnedSubtitles())
        assertTrue(request("SubtitleStreamIndex=2").hasServerBurnedSubtitles())
        assertTrue(request("").hasServerBurnedSubtitles())
        assertFalse(request("SubtitleStreamIndex=2&SubtitleMethod=External").hasServerBurnedSubtitles())
        assertFalse(request("SubtitleStreamIndex=3").hasServerBurnedSubtitles())
        assertFalse(request("SubtitleStreamIndex=-1&SubtitleMethod=Encode").hasServerBurnedSubtitles())
        assertFalse(request("SubtitleStreamIndex=invalid&SubtitleMethod=Encode").hasServerBurnedSubtitles())
        assertFalse(request("SubtitleStreamIndex=&SubtitleMethod=Encode").hasServerBurnedSubtitles())
    }

    @Test fun originalFilesOrMissingTracksCannotBeAssumedToContainBurnedSubtitles() {
        val current = request("SubtitleStreamIndex=2&SubtitleMethod=Encode")
        assertFalse(current.copy(stream = current.stream.copy(method = PlayMethod.DIRECT_PLAY)).hasServerBurnedSubtitles())
        assertFalse(current.copy(stream = current.stream.copy(trustedServer = false)).hasServerBurnedSubtitles())
        assertFalse(request("").let { it.copy(source = it.source.copy(mediaStreams = emptyList(), defaultSubtitleStreamIndex = null)) }
            .hasServerBurnedSubtitles())
    }

    private fun request(query: String): PlaybackRequest {
        val source = MediaSource("version", defaultSubtitleStreamIndex = 2,
            mediaStreams = listOf(MediaStream(index = 2, type = "Subtitle", deliveryMethod = "Encode")))
        return PlaybackRequest(MediaItem("film", "Fixture", "Movie"), PlaybackInfo(listOf(source), "play"), source,
            ResolvedPlayback("https://media.example/Videos/film/master.m3u8?$query", emptyMap(), PlayMethod.TRANSCODE, source.id, true),
            0, Session(ServerConfig("https://media.example"), "test-token", "user", "Test", "device"))
    }
}

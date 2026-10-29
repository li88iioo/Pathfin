package dev.pathfin.android

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.android.playback.unsupportedVideoMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExoVideoSupportTest {
    private fun group(mime: String, support: Int, selected: Boolean = false) = Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType(mime).build()),
        false, intArrayOf(support), booleanArrayOf(selected))

    @Test fun onlyAllUnsupportedVideoTracksProduceWarning() {
        assertNull(unsupportedVideoMessage(Tracks.EMPTY))
        val unsupported = group(MimeTypes.VIDEO_DOLBY_VISION, C.FORMAT_UNSUPPORTED_SUBTYPE)
        assertTrue(unsupportedVideoMessage(Tracks(listOf(unsupported)))!!.contains("Dolby Vision"))
        assertNull(unsupportedVideoMessage(Tracks(listOf(group(MimeTypes.VIDEO_H265, C.FORMAT_HANDLED)))))
        assertNull("有可解码视频轨时不提示", unsupportedVideoMessage(Tracks(listOf(unsupported, group(MimeTypes.VIDEO_H265, C.FORMAT_HANDLED)))))
        assertNull("纯音频媒体不是视频解码失败", unsupportedVideoMessage(Tracks(listOf(group(MimeTypes.AUDIO_TRUEHD, C.FORMAT_UNSUPPORTED_SUBTYPE)))))
        assertNull("直播 HLS 常标出偏高档次（超出声明能力），Media3 仍会解码，不能误报只能听声音",
            unsupportedVideoMessage(Tracks(listOf(group(MimeTypes.VIDEO_H264, C.FORMAT_EXCEEDS_CAPABILITIES)))))
        assertNull("已在渲染的视频轨不提示",
            unsupportedVideoMessage(Tracks(listOf(group(MimeTypes.VIDEO_H264, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = true)))))
        assertEquals("当前视频轨（AVC）本机无法解码，仅能播放声音。",
            unsupportedVideoMessage(Tracks(listOf(group(MimeTypes.VIDEO_H264, C.FORMAT_UNSUPPORTED_SUBTYPE)))))
    }
}

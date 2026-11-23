package dev.pathfin.android.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class PreferredMediaCodecSelectorTest {
    private data class Codec(val name: String, val hardware: Boolean, val software: Boolean)
    private val a = Codec("hardware-a", true, false)
    private val b = Codec("software-b", false, true)
    private val c = Codec("hardware-c", true, false)
    private val vendor = Codec("unknown-vendor", false, false)
    private val all = listOf(b, a, vendor, c)
    private fun order(mime: String, audio: String = "AUTO", video: Boolean = true, codecs: List<Codec> = all) =
        orderPreferredCodecs(codecs, mime, audio, video, Codec::hardware, Codec::software)
    @Test fun audioAutoAndUnknownModesKeepOriginalSystemOrder() {
        assertEquals(all, order("audio/mp4a-latm"))
        assertEquals(all, order("audio/mp4a-latm", "unknown"))
        assertEquals(all, order("text/x-ssa", "SOFTWARE"))
    }
    @Test fun preferencesAreStableAndNeverDropFallbackCandidates() {
        assertEquals(listOf(a, c, b, vendor), order("audio/mp4a-latm", "HARDWARE"))
        assertEquals(listOf(a, c, b, vendor), order("video/avc"))
        assertEquals(listOf(b, a, vendor, c), order("video/avc", video = false))
        assertEquals(listOf(a, c), order("audio/mp4a-latm", "SOFTWARE", codecs = listOf(a, c)))
        assertTrue(order("video/avc", codecs = emptyList()).isEmpty())
    }

    @Test fun reportsTheDecoderThatInitializedEvenWhenItIsNotThePreferredOne() {
        val mime = MimeTypes.VIDEO_H264
        val hardware = MediaCodecInfo.newInstance(a.name, mime, mime, null, true, false, true, false, false)
        val software = MediaCodecInfo.newInstance(b.name, mime, mime, null, false, true, false, false, false)
        val unknown = MediaCodecInfo.newInstance(vendor.name, mime, mime, null, false, false, true, false, false)
        val selector = PreferredMediaCodecSelector("AUTO", true, MediaCodecSelector { _, _, _ ->
            listOf(software, hardware, unknown)
        })
        assertEquals(hardware, selector.getDecoderInfos(mime, false, false).first())
        assertEquals(VideoDecoderKind.SYSTEM_SOFTWARE, selector.videoDecoderKind(software.name))
        assertEquals(VideoDecoderKind.HARDWARE, selector.videoDecoderKind(hardware.name))
        assertEquals(VideoDecoderKind.UNKNOWN, selector.videoDecoderKind(unknown.name))
        assertEquals(VideoDecoderKind.UNKNOWN, selector.videoDecoderKind("unrecognized"))
        assertEquals(VideoDecoderKind.FFMPEG, selector.videoDecoderKind("pathfin.ffmpeg.vvc.gpu-sdr"))
    }
}

package dev.pathfin.android.playback

import org.junit.Assert.*
import org.junit.Test

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
}

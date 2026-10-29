package dev.pathfin.ass

import org.junit.Assert.*
import org.junit.Test

class AssPacketTest {
    @Test fun matroskaRawSsaUsesMetadataStartAndDurationPrefixPreservesOverridesAndCommas() {
        val original = "17,0,Default,,0,0,0,,{\\move(80,190,560,190,0,6000)}MOVE, MORE"
        val sample = "Dialogue: 0:00:00:00,0:00:06:25,$original".toByteArray()
        val parsed = checkNotNull(AssPacket.parse(sample, 123_456_000))
        assertEquals(123_456L, parsed.startMs)
        assertEquals(6250L, parsed.durationMs)
        assertEquals(17L, parsed.readOrder)
        assertEquals(original, parsed.data.toString(Charsets.UTF_8))
    }
    @Test fun zeroNegativeOutOfRangeAndTruncatedTimesNeverReachNative() {
        for (time in listOf("0:00:00:00", "-1:00:00:00", "0:60:01:00", "0:00:60:00", "0:00:01:100", "99999999999999:00:01:00")) {
            assertNull(AssPacket.parse("Dialogue: 0:00:00:00,$time,1,0,Default,,0,0,0,,text".toByteArray(), 0))
        }
        assertNull(AssPacket.parse("Dialogue: 0:00:00:00,0:00:01:00,".toByteArray(), 0))
        assertNull(AssPacket.parse("Dialogue: 0:00:00:00,0:00:01:00,1,0,text".toByteArray(), -1))
    }
    @Test fun malformedOrUnboundedReadOrderAndOversizedPacketsAreRejected() {
        for (order in listOf("-1", "garbage", "999999999999999999999999999999")) {
            assertNull(AssPacket.parse("Dialogue: 0:00:00:00,0:00:01:00,$order,0,text".toByteArray(), 0))
        }
        assertNull(AssPacket.parse(ByteArray(1024 * 1024 + 1), 0))
    }
    @Test fun fontNamesAreNamesOnlyNeverFilePathsUrlsControlCharactersOrOtherAttachments() {
        assertTrue(AssPacket.safeFontName("字幕字体-Regular.TTC"))
        assertTrue(AssPacket.safeFontName("PathfinAssFixture.ttf"))
        for (name in listOf("../font.ttf", "a/b.ttf", "a\\b.ttf", "/tmp/font.ttf", "https://host/font.ttf", "font\u0000.ttf", "font\n.ttf", ".hidden.ttf", "poster.jpg", "script.ass")) {
            assertFalse(name, AssPacket.safeFontName(name))
        }
    }
}

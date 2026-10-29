package dev.pathfin.android.playback

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class ExoVideoSupportTest {
    // 来自自有 androidTest/assets/hdr-playback/hdr10.mp4 的真实 VPS/SPS/PPS；
    // 本测试只给基础层加 DV Format 包装，不把该 HDR10 合成片源宣称为真实 DV 片源。
    private val hevcBaseLayerCsd = (
        "0000000140010c01ffff02200000030090000003000003003f959809" +
            "0000000142010102200000030090000003000003003fa005020169365959a4932bc05a848804820000030002000003003010" +
            "000000014401c172b46240"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun dv(codecs: String, initializationData: List<ByteArray> = listOf(hevcBaseLayerCsd)) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).setInitializationData(initializationData).build()

    @Test fun profile7WithoutDolbyVisionDecoderPlaysHevcBaseLayer() {
        val mapped = dolbyVisionBaseLayerFormat(dv("dvhe.07.06"), hasDolbyVisionDecoder = false)
        assertEquals(MimeTypes.VIDEO_H265, mapped.sampleMimeType)
        assertEquals("保留 SPS 中真实的 Main10、兼容标志和 Level 2.1，而非沿用 DV 的 level 或清空校验",
            "hvc1.2.4.L63.90", mapped.codecs)
        val dvh1 = dolbyVisionBaseLayerFormat(dv("dvh1.07.06"), hasDolbyVisionDecoder = false)
        assertEquals(MimeTypes.VIDEO_H265, dvh1.sampleMimeType)
        assertEquals("hvc1.2.4.L63.90", dvh1.codecs)
    }

    @Test fun profile7BaseLayerKeepsTheOriginalColorAndCodecInitializationData() {
        val color = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084).setLumaBitdepth(10).setChromaBitdepth(10).build()
        val original = dv("dvhe.07.06").buildUpon().setColorInfo(color).setWidth(640).setHeight(360).build()
        val mapped = dolbyVisionBaseLayerFormat(original, hasDolbyVisionDecoder = false)
        assertSame(color, mapped.colorInfo)
        assertArrayEquals(hevcBaseLayerCsd, mapped.initializationData.single())
        assertEquals(640, mapped.width)
        assertEquals(360, mapped.height)
        assertEquals("hvc1.2.4.L63.90", mapped.codecs)
        assertEquals("不能修改原始 DV Format", "dvhe.07.06", original.codecs)
    }

    @Test fun profile7WithoutAReadableSpsDoesNotInventProfileOrLevel() {
        for (csd in listOf(emptyList(), listOf(byteArrayOf(0, 0, 0, 1, 0x42, 1)))) {
            val mapped = dolbyVisionBaseLayerFormat(dv("dvhe.07.06", csd), hasDolbyVisionDecoder = false)
            assertEquals(MimeTypes.VIDEO_H265, mapped.sampleMimeType)
            assertNull("缺失或损坏的 SPS 继续保留未知，不能填默认 profile/level", mapped.codecs)
        }
    }

    @Test fun deviceDecoderOtherProfilesAndNonDolbyVisionStayUntouched() {
        val p7 = dv("dvhe.07.06")
        assertSame("设备有 DV 解码器时交给它处理增强层", p7, dolbyVisionBaseLayerFormat(p7, hasDolbyVisionDecoder = true))
        val p5 = dv("dvhe.05.06")
        assertSame("Profile 5 没有兼容基础层，硬解只会偏色", p5, dolbyVisionBaseLayerFormat(p5, hasDolbyVisionDecoder = false))
        val p8 = dv("dvhe.08.06")
        assertSame("Profile 8 已由 Media3 自行回退", p8, dolbyVisionBaseLayerFormat(p8, hasDolbyVisionDecoder = false))
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        assertSame(hevc, dolbyVisionBaseLayerFormat(hevc, hasDolbyVisionDecoder = false))
        val broken = dv("dvhe")
        assertSame(broken, dolbyVisionBaseLayerFormat(broken, hasDolbyVisionDecoder = false))
    }

}

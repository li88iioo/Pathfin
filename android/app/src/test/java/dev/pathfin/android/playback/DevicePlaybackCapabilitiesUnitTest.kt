package dev.pathfin.android.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.domain.PlaybackCapabilities
import org.junit.Assert.*
import org.junit.Test

class DevicePlaybackCapabilitiesUnitTest {
    @Test fun selectedEnvelopeBelongsToOneDecoderInsteadOfCombiningIndependentMaxima() {
        val portrait = PlaybackCapabilities.VideoLimit(2048, 4096, 8, setOf("Baseline"), 40.0, 120.0)
        val landscape = PlaybackCapabilities.VideoLimit(4096, 2048, 8, setOf("High"), 51.0, 60.0)
        val selected = PlaybackCapabilities.VideoLimit.select(listOf(portrait, landscape))!!
        assertTrue(selected == portrait || selected == landscape)
        assertFalse("没有解码器支持 4096×4096", selected.maxWidth == 4096 && selected.maxHeight == 4096)
        if (selected == portrait) {
            assertEquals(setOf("Baseline"), selected.profiles)
            assertEquals(40.0, selected.maxLevel!!, 0.0)
        } else {
            assertEquals(setOf("High"), selected.profiles)
            assertEquals(60.0, selected.maxFrameRate!!, 0.0)
        }
    }

    @Test fun tenBitDecoderKeepsItsOwnDimensionsProfilesAndRate() {
        val eightBit = PlaybackCapabilities.VideoLimit(8192, 4320, 8, setOf("Main"), 186.0, 240.0)
        val tenBit = PlaybackCapabilities.VideoLimit(3840, 2160, 10, setOf("Main", "Main 10"), 153.0, 60.0)
        assertEquals("HEVC 10bit 不应被分辨率更大的 8bit 解码器覆盖", tenBit,
            PlaybackCapabilities.VideoLimit.select(listOf(eightBit, tenBit)))
        assertNull(PlaybackCapabilities.VideoLimit.select(emptyList()))
    }

    @Test fun dolbyVisionPresenceDoesNotGrantEveryProfile() {
        val capabilities = PlaybackCapabilities.Baseline.copy(dolbyVisionProfiles = setOf(8))
        assertTrue(capabilities.dolbyVision)
        assertTrue(capabilities.supportsDolbyVisionProfile(8))
        assertFalse(capabilities.supportsDolbyVisionProfile(5))
        assertFalse(capabilities.supportsDolbyVisionProfile(7))
        assertFalse(PlaybackCapabilities.Baseline.dolbyVision)
    }

    @Test fun profile8WithOnlyDoviRangeStillChecksItsHevcBaseLayer() {
        val source = MediaStream(type = "Video", codec = "hevc", dvProfile = 8, videoRangeType = "DOVI",
            profile = "Main 10", bitDepth = 10, width = 3840, height = 2160, level = 153.0,
            averageFrameRate = 25.0, bitRate = 18_600_000)
        val candidate = DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(source)
        assertNotNull("Media3 可用 HEVC 解 Profile 8 基础层，笼统的 DOVI 标签不能提前拒绝", candidate)
        val base = candidate!!
        assertEquals("未知传递函数时保留全部 Main10 解码候选，不额外猜测动态范围",
            setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
                CodecProfileLevel.HEVCProfileMain10HDR10Plus),
            DevicePlaybackCapabilities.profilesForStream("hevc", base))
        assertNull(base.dvProfile)
        assertEquals(source.width, base.width)
        assertEquals(source.height, base.height)
        assertEquals(source.level, base.level)
        assertEquals(source.averageFrameRate, base.averageFrameRate)
        assertEquals(source.bitRate, base.bitRate)
        assertTrue("未知色彩信息不能被猜成 PQ 或 HLG",
            DevicePlaybackCapabilities.colorFormatValues(base).isEmpty())
        assertEquals(8, source.dvProfile)
    }

    @Test fun profile8BaseLayerRetainsItsHdrHlgAndSdrColorMetadata() {
        val source = MediaStream(type = "Video", codec = "hevc", dvProfile = 8, profile = "Main 10", bitDepth = 10,
            colorSpace = "bt2020nc", colorRange = "tv")
        for ((range, transfer) in listOf("DOVIWithHDR10" to "smpte2084", "DOVIWithHLG" to "arib-std-b67",
            "DOVIWithSDR" to "bt709")) {
            val variant = source.copy(videoRangeType = range, colorTransfer = transfer)
            val base = checkNotNull(DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(variant))
            assertEquals(DevicePlaybackCapabilities.colorFormatValues(variant),
                DevicePlaybackCapabilities.colorFormatValues(base))
            assertEquals(variant.videoRangeType, base.videoRangeType)
            assertEquals(variant.colorTransfer, base.colorTransfer)
        }
    }

    @Test fun profile7EnhancementLayerSourceKeepsItsActualHevcBaseLayerLimits() {
        val source = MediaStream(type = "Video", codec = "hevc", dvProfile = 7, videoRangeType = "DOVIWithEL",
            profile = "Main 10", bitDepth = 10, width = 3840, height = 2160, level = 153.0,
            averageFrameRate = 23.976, bitRate = 77_617_509, colorTransfer = "smpte2084", colorPrimaries = "bt2020")
        val base = checkNotNull(DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(source))
        assertEquals(source.copy(dvProfile = null), base)
        assertEquals("基础层检查必须保留原片码率，不能伪造更低码率以绕过硬解限制", 77_617_509, base.bitRate)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, DevicePlaybackCapabilities.colorFormatValues(base)[MediaFormat.KEY_COLOR_TRANSFER])
        assertEquals("基础层候选不能把原片改成无增强层的 DV", 7, source.dvProfile)
    }

    @Test fun pureOrUnknownDolbyVisionDoesNotInventAHevcCompatibleBaseLayer() {
        val source = MediaStream(type = "Video", codec = "hevc", dvProfile = 5, videoRangeType = "DOVI", bitDepth = 10)
        assertNull("Profile 5 不能仅改 MIME 当 HEVC 播放", DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(source))
        assertNull(DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(source.copy(dvProfile = null)))
        assertNull(DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(source.copy(dvProfile = 8, codec = "h264")))
        assertNull(DevicePlaybackCapabilities.dolbyVisionBaseLayerStream(source.copy(dvProfile = 8, type = "Audio")))
    }

    @Test fun ffprobeLevelValuesAreNotAndroidBitmasksOrDisplayLevelDecimals() {
        assertEquals(40.0, DevicePlaybackCapabilities.jellyfinLevel("h264", CodecProfileLevel.AVCLevel4)!!, 0.0)
        assertEquals(153.0, DevicePlaybackCapabilities.jellyfinLevel("hevc", CodecProfileLevel.HEVCMainTierLevel51)!!, 0.0)
        assertEquals(153.0, DevicePlaybackCapabilities.jellyfinLevel("hevc", CodecProfileLevel.HEVCHighTierLevel51)!!, 0.0)
        assertEquals(51.0, DevicePlaybackCapabilities.jellyfinLevel("vp9", CodecProfileLevel.VP9Level51)!!, 0.0)
        assertEquals(13.0, DevicePlaybackCapabilities.jellyfinLevel("av1", CodecProfileLevel.AV1Level51)!!, 0.0)
        assertNull(DevicePlaybackCapabilities.jellyfinLevel("hevc", Int.MAX_VALUE))
    }

    @Test fun main10HdrVariantsRemainMain10AndUnknownProfileDoesNotBecomeTenBit() {
        for (profile in listOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus)) {
            assertEquals("Main 10", DevicePlaybackCapabilities.videoProfile("hevc", profile))
            assertEquals(10, DevicePlaybackCapabilities.profileBitDepth("hevc", profile))
        }
        assertEquals(8, DevicePlaybackCapabilities.profileBitDepth("hevc", CodecProfileLevel.HEVCProfileMain))
        assertEquals(8, DevicePlaybackCapabilities.profileBitDepth("av1", Int.MAX_VALUE))
        assertNull(DevicePlaybackCapabilities.videoProfile("hevc", Int.MAX_VALUE))
    }

    @Test fun mpeg2NativeLevelsStillMapToTheirExactFfprobeValues() {
        assertEquals(4.0, DevicePlaybackCapabilities.jellyfinLevel("mpeg2video", CodecProfileLevel.MPEG2LevelHL)!!, 0.0)
        assertEquals(8.0, DevicePlaybackCapabilities.jellyfinLevel("mpeg2video", CodecProfileLevel.MPEG2LevelML)!!, 0.0)
        assertEquals(10.0, DevicePlaybackCapabilities.jellyfinLevel("mpeg2video", CodecProfileLevel.MPEG2LevelLL)!!, 0.0)
    }

    @Test fun hdrColorMetadataIsPassedWithoutGuessingUnknownTransferOrRange() {
        val pq = DevicePlaybackCapabilities.colorFormatValues(MediaStream(colorTransfer = "smpte2084", colorSpace = "bt2020nc", colorRange = "tv"))
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, pq[MediaFormat.KEY_COLOR_TRANSFER])
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, pq[MediaFormat.KEY_COLOR_STANDARD])
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, pq[MediaFormat.KEY_COLOR_RANGE])
        val hlg = DevicePlaybackCapabilities.colorFormatValues(MediaStream(colorTransfer = "arib-std-b67", colorPrimaries = "bt2020", colorRange = "pc"))
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, hlg[MediaFormat.KEY_COLOR_TRANSFER])
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, hlg[MediaFormat.KEY_COLOR_STANDARD])
        assertEquals(MediaFormat.COLOR_RANGE_FULL, hlg[MediaFormat.KEY_COLOR_RANGE])
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, DevicePlaybackCapabilities.colorFormatValues(
            MediaStream(colorSpace = "unknown", colorPrimaries = "bt2020"))[MediaFormat.KEY_COLOR_STANDARD])
        assertTrue(DevicePlaybackCapabilities.colorFormatValues(MediaStream(colorTransfer = "unknown", colorSpace = "unknown", colorRange = "unknown")).isEmpty())
        assertTrue("纯 DV 不能猜成 PQ/HLG", DevicePlaybackCapabilities.colorFormatValues(MediaStream(videoRangeType = "DOVI")).isEmpty())
    }

    @Test fun hevcPqAndHlgUseTheSameProfilesAsMedia3() {
        val source = MediaStream(type = "Video", codec = "hevc", profile = "Main 10", bitDepth = 10)
        val pq = DevicePlaybackCapabilities.profilesForStream("hevc", source.copy(videoRangeType = "HDR10"))!!
        assertEquals(setOf(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus), pq)
        val hlg = DevicePlaybackCapabilities.profilesForStream("hevc", source.copy(videoRangeType = "HLG"))!!
        assertEquals(setOf(CodecProfileLevel.HEVCProfileMain10), hlg)
    }

    @Test fun missingHdrProfileStaysUnknownWhenTheUnderlyingMain10DecoderExists() {
        val source = MediaStream(type = "Video", codec = "hevc", profile = "Main 10", bitDepth = 10, videoRangeType = "HDR10")
        val generic = DevicePlaybackCapabilities.selectVideoProfiles("hevc", source, setOf(CodecProfileLevel.HEVCProfileMain10))!!
        assertEquals(setOf(CodecProfileLevel.HEVCProfileMain10), generic.profiles)
        assertTrue("Main10 可用时不能否定平台 tone-map 路径", generic.hdrUnknown)
        val native = DevicePlaybackCapabilities.selectVideoProfiles("hevc", source, setOf(CodecProfileLevel.HEVCProfileMain10HDR10))!!
        assertEquals(setOf(CodecProfileLevel.HEVCProfileMain10HDR10), native.profiles)
        assertFalse(native.hdrUnknown)
        val eightBitOnly = DevicePlaybackCapabilities.selectVideoProfiles("hevc", source, setOf(CodecProfileLevel.HEVCProfileMain))!!
        assertTrue("只有 8bit Main 明确不能解 Main10 HDR", eightBitOnly.profiles.isEmpty())
        assertFalse(eightBitOnly.hdrUnknown)
        assertNull(DevicePlaybackCapabilities.selectVideoProfiles("hevc", source, emptySet()))
    }

    @Test fun av1AndVp9HdrRequireTheirHdrProfilesInsteadOfJustTenBit() {
        val av1 = MediaStream(type = "Video", codec = "av1", profile = "Main", bitDepth = 10, videoRangeType = "HLG")
        assertEquals(setOf(CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus),
            DevicePlaybackCapabilities.profilesForStream("av1", av1))
        val vp9 = MediaStream(type = "Video", codec = "vp9", profile = "Profile 2", bitDepth = 10, videoRangeType = "HDR10Plus")
        assertEquals(setOf(CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus),
            DevicePlaybackCapabilities.profilesForStream("vp9", vp9))
    }
}

package dev.pathfin.android

import android.util.Log
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.playback.DevicePlaybackCapabilities
import dev.pathfin.ffmpeg.video.NativeVideoDecoder
import dev.pathfin.ffmpeg.video.NativeVideoRenderer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 真机能力必须如实上报：硬解 HEVC 与 FFmpeg 音频不能再被声明成只支持 H.264/AAC。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DevicePlaybackCapabilitiesTest {
    @Test fun actualDecodersAndFfmpegAudioAreDeclaredToTheServer() {
        val capabilities = DevicePlaybackCapabilities.current
        val profile = JellyfinApi(capabilities = { capabilities }).deviceProfile()
        Log.i("PathfinCapabilities", profile.toString())
        // Profile 常超过 logcat 单行长度；状态输出保留完整声明供真实服务器协商回归。
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("pathfinDeviceProfile", profile.toString())
        })
        assertTrue("H.264 必须可直放：$capabilities", "h264" in capabilities.video)
        val hevc = capabilities.video["hevc"]
        assertTrue("本机有 HEVC 硬解码器，必须声明且带真实分辨率：$capabilities", hevc != null && hevc.maxWidth >= 1920 && hevc.maxHeight >= 1080)
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            assertTrue("ARM64 的 FFmpeg 音频扩展可解 FLAC/TrueHD/DTS/AMR/G711：$capabilities",
                capabilities.audioCodecs.containsAll(setOf("aac", "flac", "truehd", "dts", "ac3", "eac3", "opus",
                    "amr_nb", "amr_wb", "pcm_alaw", "pcm_mulaw")))
        }
    }

    @Test fun eachDeclaredRectangleIsActuallySupportedByOneRealDecoder() {
        val mimes = mapOf("h264" to "video/avc", "hevc" to "video/hevc", "vp9" to "video/x-vnd.on2.vp9",
            "av1" to "video/av01", "vp8" to "video/x-vnd.on2.vp8", "mpeg2video" to "video/mpeg2")
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        for ((codec, limit) in DevicePlaybackCapabilities.current.video) {
            val mime = mimes.getValue(codec)
            assertTrue("$codec 声明的 ${limit.maxWidth}×${limit.maxHeight} 必须来自同一真实解码器", decoders.any { decoder ->
                runCatching {
                    val type = decoder.supportedTypes.firstOrNull { it.equals(mime, true) } ?: return@runCatching false
                    decoder.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(limit.maxWidth, limit.maxHeight) == true
                }.getOrDefault(false)
            })
        }
    }

    @Test fun sourceSpecificChecksAcceptCommonMain10AndRejectImpossibleSizes() {
        assertEquals(true, DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Video", codec = "h264",
            profile = "High", level = 40.0, width = 1920, height = 1080, bitDepth = 8, averageFrameRate = 30.0)))
        assertEquals(true, DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Video", codec = "hevc",
            profile = "Main 10", level = 153.0, width = 1920, height = 1080, bitDepth = 10, averageFrameRate = 24.0)))
        assertEquals(false, DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Video", codec = "hevc",
            profile = "Main 10", width = 32768, height = 32768, bitDepth = 10, averageFrameRate = 60.0)))
        assertEquals("Hi10P 使用已经接入的本地补解", true,
            DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Video", codec = "h264",
                profile = "High 10", width = 1920, height = 1080, bitDepth = 10)))
        assertEquals("DV5 能力包含本地解码及 GPU 色彩处理", true,
            DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Video", codec = "hevc",
                profile = "Main 10", width = 1920, height = 1080, bitDepth = 10, dvProfile = 5, videoRangeType = "DOVI")))
        assertFalse("不能声明软件路径不接受的 DV5 位深", NativeVideoRenderer.supportsSource("hevc", 5, 1920, 1080, 12))
        assertNull(DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Video", codec = "unknown-future-codec")))
        assertNull(DevicePlaybackCapabilities.supportsVideo(MediaStream(type = "Audio", codec = "aac")))
        val nativeProfiles = DevicePlaybackCapabilities.current.dolbyVisionProfiles
        if (5 !in nativeProfiles) assertFalse(DevicePlaybackCapabilities.current.supportsDolbyVisionProfile(5))
    }

    @Test fun matroskaProfile5WithoutCsdAndWithDefaultEightBitMetadataUsesNativeRenderer() {
        // 黑镜 S5 实测 Format：缺少 CSD 时 MatroskaExtractor 将未知位深默认为 8，
        // 不能因此拦住补解；真实帧与 RPU 是否为受支持的 10 位仍由 JNI 验证。
        val format = Format.Builder()
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.05.07")
            .setWidth(3840)
            .setHeight(2160)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(8).setChromaBitdepth(8).build())
            .setInitializationData(emptyList())
            .build()
        assertTrue("测试必须使用真实可用的 FFmpeg HEVC 补解", NativeVideoDecoder.isCodecAvailable("hevc"))
        val renderer = NativeVideoRenderer(0, Handler(Looper.getMainLooper()), object : VideoRendererEventListener {})
        assertEquals("DV5 容器默认 8 位不能导致只播放声音", C.FORMAT_HANDLED,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)))
    }

    @Test fun sourceBitrateUsesTheRealDecoderLimitAndIgnoresAbsentOrInvalidMetadata() {
        val stream = MediaStream(type = "Video", codec = "h264", profile = "High", width = 1920, height = 1080,
            bitDepth = 8, averageFrameRate = 30.0)
        assertEquals(true, DevicePlaybackCapabilities.supportsVideo(stream.copy(bitRate = 8_000_000)))
        assertEquals(DevicePlaybackCapabilities.supportsVideo(stream), DevicePlaybackCapabilities.supportsVideo(stream.copy(bitRate = -1)))
        val upper = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }.mapNotNull { decoder ->
            runCatching {
                val type = decoder.supportedTypes.firstOrNull { it.equals("video/avc", true) } ?: return@runCatching null
                decoder.getCapabilitiesForType(type).videoCapabilities?.bitrateRange?.upper
            }.getOrNull()
        }.maxOrNull()
        if (upper != null && upper < Int.MAX_VALUE) {
            assertEquals("超过系统解码器码率时，按已接入的本地补解能力判断，不能直接强制服务器转码",
                NativeVideoRenderer.supportsSource("h264", null, 1920, 1080, 8),
                DevicePlaybackCapabilities.supportsVideo(stream.copy(bitRate = upper + 1)))
        }
    }

    @Test fun profile7BaseLayerSoftwareDecodeSurvivesThePlatformBitrateLimit() {
        val upper = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }.flatMap { decoder ->
            decoder.supportedTypes.filter { it.equals(MimeTypes.VIDEO_H265, true) || it.equals(MimeTypes.VIDEO_DOLBY_VISION, true) }
                .mapNotNull { type -> runCatching { decoder.getCapabilitiesForType(type).videoCapabilities?.bitrateRange?.upper }.getOrNull() }
        }.maxOrNull()
        if (upper != null && upper < Int.MAX_VALUE) {
            val source = MediaStream(type = "Video", codec = "hevc", profile = "Main 10", dvProfile = 7,
                videoRangeType = "DOVIWithEL", width = 3840, height = 2160, bitDepth = 10,
                level = 153.0, averageFrameRate = 23.976, bitRate = upper + 1)
            val demotesToHevc = !DevicePlaybackCapabilities.current.supportsDolbyVisionProfile(7)
            assertEquals("只申报提取器确实会输出的 HEVC 基础层软件路径，不能因平台码率不足误转码",
                demotesToHevc && NativeVideoRenderer.supportsSource("hevc", null, 3840, 2160, 10),
                DevicePlaybackCapabilities.supportsVideo(source))
            for (invalid in listOf(source.copy(bitDepth = null), source.copy(bitDepth = 12),
                source.copy(width = null), source.copy(height = 0), source.copy(width = 8192),
                source.copy(dvProfile = null), source.copy(dvProfile = 6))) {
                assertFalse("缺失或不支持的 DV 信息不能冒充已确认可用的基础层：$invalid",
                    DevicePlaybackCapabilities.supportsVideo(invalid) == true)
            }
        }
    }
}

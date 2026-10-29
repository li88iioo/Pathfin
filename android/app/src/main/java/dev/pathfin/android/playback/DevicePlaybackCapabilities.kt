package dev.pathfin.android.playback

import android.annotation.SuppressLint
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import dev.pathfin.ffmpeg.video.NativeVideoRenderer
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.domain.PlaybackCapabilities
import java.util.Locale

/** 解码器只读取一次；单个厂商解码器异常不能抹掉其它硬解与 FFmpeg 音频能力。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object DevicePlaybackCapabilities {
    internal data class VideoProfiles(val profiles: Set<Int>, val hdrUnknown: Boolean = false)

    private data class Detection(
        val capabilities: PlaybackCapabilities,
        val decoders: Map<String, List<CodecCapabilities>>,
        val unknownTypes: Set<String>,
    )

    private val detected: Detection by lazy { detect() }
    val current: PlaybackCapabilities get() = detected.capabilities

    private val videoTypes = mapOf(
        MimeTypes.VIDEO_H264 to "h264", MimeTypes.VIDEO_H265 to "hevc", MimeTypes.VIDEO_VP9 to "vp9",
        MimeTypes.VIDEO_AV1 to "av1", MimeTypes.VIDEO_VP8 to "vp8", MimeTypes.VIDEO_MPEG2 to "mpeg2video",
    )
    private val audioTypes = mapOf(
        MimeTypes.AUDIO_AAC to "aac", MimeTypes.AUDIO_MPEG to "mp3", MimeTypes.AUDIO_MPEG_L2 to "mp2",
        MimeTypes.AUDIO_AC3 to "ac3", MimeTypes.AUDIO_E_AC3 to "eac3", MimeTypes.AUDIO_TRUEHD to "truehd",
        MimeTypes.AUDIO_DTS to "dts", MimeTypes.AUDIO_VORBIS to "vorbis", MimeTypes.AUDIO_OPUS to "opus",
        MimeTypes.AUDIO_FLAC to "flac", MimeTypes.AUDIO_ALAC to "alac",
        MimeTypes.AUDIO_AMR_NB to "amr_nb", MimeTypes.AUDIO_AMR_WB to "amr_wb",
        MimeTypes.AUDIO_ALAW to "pcm_alaw", MimeTypes.AUDIO_MLAW to "pcm_mulaw",
    )

    /** null 表示系统或片源信息不足，不能因此误拒绝本可直放的文件。 */
    fun supportsVideo(stream: MediaStream): Boolean? {
        val hardware = supportsPlatformVideo(stream)
        if (hardware == true || !stream.type.equals("Video", true)) return hardware
        val dolbyVision = stream.dvProfile != null || stream.videoRangeType?.startsWith("DOVI", true) == true
        val software = if (dolbyVision && stream.dvProfile != 5) {
            // 与渐进式提取器一致：没有 DV7 解码器时只播放 HEVC 基础层，不声明增强层重建。
            if (stream.dvProfile != 7 || current.supportsDolbyVisionProfile(7)) return hardware
            dolbyVisionBaseLayerStream(stream)?.takeIf {
                it.bitDepth == 10 && (it.width ?: 0) > 0 && (it.height ?: 0) > 0
            } ?: return hardware
        } else stream
        val codec = software.codec?.lowercase(Locale.ROOT) ?: return hardware
        return if (NativeVideoRenderer.supportsSource(codec, software.dvProfile, software.width ?: -1,
                software.height ?: -1, software.bitDepth ?: -1)) true else hardware
    }

    private fun supportsPlatformVideo(stream: MediaStream): Boolean? {
        if (!stream.type.equals("Video", true)) return null
        val codec = stream.codec?.lowercase(Locale.ROOT) ?: return null
        val mime = videoTypes.entries.firstOrNull { it.value == codec }?.key ?: return null
        val isDolbyVision = stream.dvProfile != null || stream.videoRangeType?.startsWith("DOVI", true) == true
        if (!isDolbyVision) return supports(stream, mime)

        val profile = stream.dvProfile
        val native = if (profile == null) null else supports(stream, MimeTypes.VIDEO_DOLBY_VISION, profile)
        if (native == true) return true
        val baseLayer = dolbyVisionBaseLayerStream(stream) ?: return native
        val base = supports(baseLayer, mime)
        return when {
            base == true -> true
            base == null || native == null -> null
            else -> false
        }
    }

    /** 只映射播放器已有基础层路径；保留尺寸、级别、码率与色彩信息，继续按真实 HEVC 能力校验。 */
    internal fun dolbyVisionBaseLayerStream(stream: MediaStream): MediaStream? {
        if (!stream.type.equals("Video", true) || !stream.codec.equals("hevc", true)) return null
        val profile = stream.dvProfile
        // Media3 1.11.1 为 Profile 8 提供 HEVC 基础层；笼统的 DOVI 标签不能把它误当成 Profile 5。
        // Profile 7 保持现有渐进式提取器路径，不在这里扩大基础层格式的支持范围。
        if (profile != 7 && profile != 8) return null
        return stream.copy(dvProfile = null, profile = "Main 10")
    }

    private fun supports(stream: MediaStream, mime: String, dolbyVisionProfile: Int? = null): Boolean? {
        val results = detected.decoders[mime].orEmpty().map { caps ->
            runCatching { supports(caps, stream, mime, dolbyVisionProfile) }.getOrNull()
        }
        if (results.any { it == true }) return true
        return if (mime in detected.unknownTypes || results.any { it == null }) null else false
    }

    private fun supports(caps: CodecCapabilities, stream: MediaStream, mime: String, dolbyVisionProfile: Int?): Boolean? {
        val video = caps.videoCapabilities ?: return null
        val width = stream.width?.takeIf { it > 0 }
        val height = stream.height?.takeIf { it > 0 }
        val rate = listOfNotNull(stream.averageFrameRate, stream.realFrameRate).firstOrNull { it.isFinite() && it > 0 }
        if (width != null && height != null) {
            if (!video.isSizeSupported(width, height)) return false
            if (rate != null && !video.areSizeAndRateSupported(width, height, rate)) return false
        }
        val codec = videoTypes[mime]
        val advertised = caps.profileLevels.map { it.profile }.toSet()
        if (codec != null && stream.bitDepth != null && advertised.isNotEmpty() &&
            advertised.all { videoProfile(codec, it) != null } && stream.bitDepth > advertised.maxOf { profileBitDepth(codec, it) }) return false
        val selection = if (dolbyVisionProfile != null) {
            val requested = dolbyVisionProfiles.filterValues { it == dolbyVisionProfile }.keys
            if (requested.isEmpty() || advertised.isEmpty()) null else VideoProfiles(requested.intersect(advertised))
        } else selectVideoProfiles(codec, stream, advertised)
        val matching = selection?.profiles ?: return null
        if (matching.isEmpty()) return false
        val level = if (dolbyVisionProfile == null) androidLevel(codec, stream.level) else null
        val results = matching.map { profile -> runCatching {
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, mime)
                setInteger(MediaFormat.KEY_PROFILE, profile)
                level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                width?.let { setInteger(MediaFormat.KEY_WIDTH, it) }
                height?.let { setInteger(MediaFormat.KEY_HEIGHT, it) }
                rate?.let { setFloat(MediaFormat.KEY_FRAME_RATE, it.toFloat()) }
                // 使用视频轨本身的真实码率，交给这一个 decoder 校验，不使用容器总码率或跨 decoder 上限。
                stream.bitRate?.takeIf { it > 0 }?.let { setInteger(MediaFormat.KEY_BIT_RATE, it) }
                colorFormatValues(stream).forEach { (key, value) -> setInteger(key, value) }
            }
            caps.isFormatSupported(format)
        }.getOrNull() }
        return if (results.any { it == true }) if (selection.hdrUnknown) null else true else if (results.any { it == null }) null else false
    }

    private fun detect(): Detection {
        val types = videoTypes.keys + audioTypes.keys + MimeTypes.VIDEO_DOLBY_VISION
        val unknown = mutableSetOf<String>()
        val byType = mutableMapOf<String, MutableList<CodecCapabilities>>()
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrElse {
            return Detection(PlaybackCapabilities.Baseline, emptyMap(), types)
        }
        infos.forEach { info ->
            val supported = runCatching { if (info.isEncoder) emptyList() else info.supportedTypes.toList() }.getOrElse {
                unknown.addAll(types)
                return@forEach
            }
            supported.forEach typeLoop@ { type ->
                val mime = types.firstOrNull { it.equals(type, true) } ?: return@typeLoop
                runCatching { info.getCapabilitiesForType(type) }.onSuccess { caps ->
                    byType.getOrPut(mime) { mutableListOf() }.add(caps)
                }.onFailure { unknown.add(mime) }
            }
        }
        val video = videoTypes.mapNotNull { (mime, codec) ->
            val candidates = byType[mime].orEmpty().mapNotNull { caps ->
                runCatching { limit(codec, caps) }.getOrElse { unknown.add(mime); null }
            }
            PlaybackCapabilities.VideoLimit.select(candidates)?.let { codec to it }
        }.toMap().toMutableMap()
        // Android 的必需 H.264/AAC 基线用于服务端转码，不因坏掉的厂商查询而丢失。
        video.putIfAbsent("h264", PlaybackCapabilities.Baseline.video.getValue("h264"))
        val ffmpeg = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        val audio = audioTypes.filter { (mime, _) ->
            byType[mime].orEmpty().isNotEmpty() || ffmpeg && runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
        }.values.toSet() + setOf("aac", "pcm_s16le", "pcm_s24le")
        val dvProfiles = byType[MimeTypes.VIDEO_DOLBY_VISION].orEmpty().flatMap { caps ->
            runCatching { caps.profileLevels.mapNotNull { dolbyVisionProfiles[it.profile] } }.getOrDefault(emptyList())
        }.toSet()
        return Detection(PlaybackCapabilities(video, audio, dvProfiles), byType, unknown)
    }

    private fun limit(codec: String, caps: CodecCapabilities): PlaybackCapabilities.VideoLimit? {
        val video = caps.videoCapabilities ?: return null
        val width = video.supportedWidths.upper.let { it - it % video.widthAlignment }
        // supportedWidths/Heights 的独立上限不保证能一起播放，固定宽后查询其实际允许的高度。
        val height = video.getSupportedHeightsFor(width).upper.let { it - it % video.heightAlignment }
        if (!video.isSizeSupported(width, height)) return null
        return PlaybackCapabilities.VideoLimit(width, height,
            caps.profileLevels.maxOfOrNull { profileBitDepth(codec, it.profile) } ?: 8,
            caps.profileLevels.flatMap { profile ->
                val name = videoProfile(codec, profile.profile)
                when {
                    codec == "h264" && name == "Baseline" -> listOf("Baseline", "Constrained Baseline")
                    codec == "h264" && name == "High" -> listOf("High", "Constrained High")
                    else -> listOfNotNull(name)
                }
            }.toSet(),
            caps.profileLevels.mapNotNull { jellyfinLevel(codec, it.level) }.maxOrNull(), video.supportedFrameRates.upper.toDouble())
    }

    @SuppressLint("InlinedApi")
    private val dolbyVisionProfiles = mapOf(
        CodecProfileLevel.DolbyVisionProfileDvavPer to 0, CodecProfileLevel.DolbyVisionProfileDvavPen to 1,
        CodecProfileLevel.DolbyVisionProfileDvheDer to 2, CodecProfileLevel.DolbyVisionProfileDvheDen to 3,
        CodecProfileLevel.DolbyVisionProfileDvheDtr to 4, CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
        CodecProfileLevel.DolbyVisionProfileDvheDth to 6, CodecProfileLevel.DolbyVisionProfileDvheDtb to 7,
        CodecProfileLevel.DolbyVisionProfileDvheSt to 8, CodecProfileLevel.DolbyVisionProfileDvavSe to 9,
        CodecProfileLevel.DolbyVisionProfileDvav110 to 10,
    )

    @SuppressLint("InlinedApi") // HDR profile 都是编译期整数常量，旧系统未声明时保留未知。
    internal fun profilesForStream(codec: String?, stream: MediaStream, hdrProfiles: Boolean = true): Set<Int>? {
        val name = stream.profile?.lowercase(Locale.ROOT)?.replace(" ", "")?.replace("-", "")
        val wanted = when (codec) {
            "h264" -> when (name) {
                "constrainedbaseline" -> setOf("Constrained Baseline", "Baseline")
                "constrainedhigh" -> setOf("Constrained High", "High")
                "high10", "high10intra" -> setOf("High 10")
                else -> stream.profile?.let { setOf(it) } ?: return null
            }
            "hevc" -> when {
                name == "mainstillpicture" || name == "mainstill" -> setOf("Main Still Picture")
                name?.startsWith("main10") == true || stream.bitDepth == 10 -> setOf("Main 10")
                name == "main" || stream.profile.isNullOrBlank() && stream.bitDepth == 8 -> setOf("Main")
                else -> return null
            }
            "av1" -> if (name == "main" || stream.profile.isNullOrBlank()) setOf("Main") else return null
            "vp9" -> when {
                name in setOf("profile0", "0") -> setOf("Profile 0")
                name in setOf("profile1", "1") -> setOf("Profile 1")
                name in setOf("profile2", "2") || stream.profile.isNullOrBlank() && stream.bitDepth == 10 -> setOf("Profile 2")
                name in setOf("profile3", "3") -> setOf("Profile 3")
                else -> return null
            }
            "vp8" -> setOf("Main")
            else -> stream.profile?.let { setOf(it) } ?: return null
        }
        val matches = profiles.getValue(codec ?: return null).filter { (profile, value) ->
            wanted.any { it.equals(value, true) } && (stream.bitDepth == null || profileBitDepth(codec, profile) >= stream.bitDepth)
        }.keys
        if (!hdrProfiles || !isHdr(stream)) return matches
        // 与 Media3 CodecSpecificDataUtil 一致：HEVC PQ 使用 HDR10，HLG 使用 Main10；AV1/VP9 使用 HDR profile。
        val hdr = when (codec) {
            "hevc" -> if (colorFormatValues(stream)[MediaFormat.KEY_COLOR_TRANSFER] == MediaFormat.COLOR_TRANSFER_ST2084)
                setOf(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                else setOf(CodecProfileLevel.HEVCProfileMain10)
            "av1" -> setOf(CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
            "vp9" -> setOf(CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus,
                CodecProfileLevel.VP9Profile3HDR, CodecProfileLevel.VP9Profile3HDR10Plus)
            else -> return matches
        }
        return matches.intersect(hdr)
    }

    private fun isHdr(stream: MediaStream): Boolean = colorFormatValues(stream)[MediaFormat.KEY_COLOR_TRANSFER] in
        setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)

    internal fun selectVideoProfiles(codec: String?, stream: MediaStream, advertised: Set<Int>): VideoProfiles? {
        val requested = profilesForStream(codec, stream)
        // 不认识的片源 profile 或缺失的厂商 profile 列表均为未知。
        if (requested.isNullOrEmpty() || advertised.isEmpty()) return null
        val matching = requested.intersect(advertised)
        if (matching.isNotEmpty() || !isHdr(stream)) return VideoProfiles(matching)
        // 无 HDR 专用 profile 不等于无法解码或系统无法映射；仍校验基础能力，不把未知当成不支持。
        val base = profilesForStream(codec, stream, hdrProfiles = false).orEmpty().intersect(advertised)
        return VideoProfiles(base, hdrUnknown = base.isNotEmpty())
    }

    /** 只传已知的 ffprobe 色彩信息；屏幕不列 HDR 不等于系统没有 SurfaceFlinger/HWC tone-map 路径。 */
    internal fun colorFormatValues(stream: MediaStream): Map<String, Int> = buildMap {
        val standard = listOfNotNull(stream.colorSpace, stream.colorPrimaries).firstNotNullOfOrNull { value ->
            when (value.lowercase(Locale.ROOT)) {
                "bt2020nc", "bt2020c", "bt2020" -> MediaFormat.COLOR_STANDARD_BT2020
                "bt709" -> MediaFormat.COLOR_STANDARD_BT709
                "smpte170m" -> MediaFormat.COLOR_STANDARD_BT601_NTSC
                "bt470bg" -> MediaFormat.COLOR_STANDARD_BT601_PAL
                else -> null
            }
        }
        standard?.let { put(MediaFormat.KEY_COLOR_STANDARD, it) }
        val transfer = when (stream.colorTransfer?.lowercase(Locale.ROOT)) {
            "smpte2084", "smpte-st-2084", "st2084", "pq" -> MediaFormat.COLOR_TRANSFER_ST2084
            "arib-std-b67", "hlg" -> MediaFormat.COLOR_TRANSFER_HLG
            "bt709", "smpte170m", "bt2020-10", "bt2020-12" -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            "linear" -> MediaFormat.COLOR_TRANSFER_LINEAR
            else -> when (stream.videoRangeType?.lowercase(Locale.ROOT)) {
                "hdr10", "hdr10plus", "doviwithhdr10", "doviwithhdr10plus" -> MediaFormat.COLOR_TRANSFER_ST2084
                "hlg", "doviwithhlg" -> MediaFormat.COLOR_TRANSFER_HLG
                "sdr", "doviwithsdr" -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                else -> null
            }
        }
        transfer?.let { put(MediaFormat.KEY_COLOR_TRANSFER, it) }
        when (stream.colorRange?.lowercase(Locale.ROOT)) {
            "tv", "limited", "mpeg" -> put(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            "pc", "full", "jpeg" -> put(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
    }

    internal fun videoProfile(codec: String, profile: Int): String? = profiles[codec]?.get(profile)
    @SuppressLint("InlinedApi")
    internal fun profileBitDepth(codec: String, profile: Int): Int = when {
        codec == "h264" && profile in setOf(CodecProfileLevel.AVCProfileHigh10, CodecProfileLevel.AVCProfileHigh422, CodecProfileLevel.AVCProfileHigh444) -> 10
        codec == "hevc" && profile in setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus) -> 10
        codec == "vp9" && videoProfile(codec, profile) in setOf("Profile 2", "Profile 3") -> 10
        codec == "av1" && profile in setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus) -> 10
        else -> 8
    }

    @SuppressLint("InlinedApi")
    private val profiles = mapOf(
        "h264" to mapOf(CodecProfileLevel.AVCProfileBaseline to "Baseline", CodecProfileLevel.AVCProfileConstrainedBaseline to "Constrained Baseline",
            CodecProfileLevel.AVCProfileMain to "Main", CodecProfileLevel.AVCProfileExtended to "Extended", CodecProfileLevel.AVCProfileHigh to "High",
            CodecProfileLevel.AVCProfileConstrainedHigh to "Constrained High", CodecProfileLevel.AVCProfileHigh10 to "High 10",
            CodecProfileLevel.AVCProfileHigh422 to "High 4:2:2", CodecProfileLevel.AVCProfileHigh444 to "High 4:4:4 Predictive"),
        "hevc" to mapOf(CodecProfileLevel.HEVCProfileMain to "Main", CodecProfileLevel.HEVCProfileMainStill to "Main Still Picture",
            CodecProfileLevel.HEVCProfileMain10 to "Main 10", CodecProfileLevel.HEVCProfileMain10HDR10 to "Main 10", CodecProfileLevel.HEVCProfileMain10HDR10Plus to "Main 10"),
        "vp9" to mapOf(CodecProfileLevel.VP9Profile0 to "Profile 0", CodecProfileLevel.VP9Profile1 to "Profile 1",
            CodecProfileLevel.VP9Profile2 to "Profile 2", CodecProfileLevel.VP9Profile2HDR to "Profile 2", CodecProfileLevel.VP9Profile2HDR10Plus to "Profile 2",
            CodecProfileLevel.VP9Profile3 to "Profile 3", CodecProfileLevel.VP9Profile3HDR to "Profile 3", CodecProfileLevel.VP9Profile3HDR10Plus to "Profile 3"),
        "av1" to mapOf(CodecProfileLevel.AV1ProfileMain8 to "Main", CodecProfileLevel.AV1ProfileMain10 to "Main",
            CodecProfileLevel.AV1ProfileMain10HDR10 to "Main", CodecProfileLevel.AV1ProfileMain10HDR10Plus to "Main"),
        "vp8" to mapOf(CodecProfileLevel.VP8ProfileMain to "Main"),
        "mpeg2video" to mapOf(CodecProfileLevel.MPEG2ProfileSimple to "Simple", CodecProfileLevel.MPEG2ProfileMain to "Main",
            CodecProfileLevel.MPEG2Profile422 to "4:2:2", CodecProfileLevel.MPEG2ProfileSNR to "SNR Scalable",
            CodecProfileLevel.MPEG2ProfileSpatial to "Spatially Scalable", CodecProfileLevel.MPEG2ProfileHigh to "High"),
    )

    // Jellyfin 使用 ffprobe 的原始 level 数值，不是 Android 的位掩码常量。
    @SuppressLint("InlinedApi") // 编译期常量，仅用于比较系统返回的档位值。
    private val levels = mapOf(
        "h264" to listOf(CodecProfileLevel.AVCLevel1, CodecProfileLevel.AVCLevel11, CodecProfileLevel.AVCLevel12, CodecProfileLevel.AVCLevel13,
            CodecProfileLevel.AVCLevel2, CodecProfileLevel.AVCLevel21, CodecProfileLevel.AVCLevel22, CodecProfileLevel.AVCLevel3,
            CodecProfileLevel.AVCLevel31, CodecProfileLevel.AVCLevel32, CodecProfileLevel.AVCLevel4, CodecProfileLevel.AVCLevel41,
            CodecProfileLevel.AVCLevel42, CodecProfileLevel.AVCLevel5, CodecProfileLevel.AVCLevel51, CodecProfileLevel.AVCLevel52,
            CodecProfileLevel.AVCLevel6, CodecProfileLevel.AVCLevel61, CodecProfileLevel.AVCLevel62)
            .zip(listOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62)).toMap(),
        "hevc" to listOf(30, 60, 63, 90, 93, 120, 123, 150, 153, 156, 180, 183, 186)
            .flatMapIndexed { index, value -> listOf((1 shl (index * 2)) to value, (2 shl (index * 2)) to value) }.toMap(),
        "vp9" to listOf(10, 11, 20, 21, 30, 31, 40, 41, 50, 51, 52, 60, 61, 62).mapIndexed { index, value -> (1 shl index) to value }.toMap(),
        "av1" to (0..23).associateBy { 1 shl it },
        "mpeg2video" to mapOf(CodecProfileLevel.MPEG2LevelLL to 10, CodecProfileLevel.MPEG2LevelML to 8,
            CodecProfileLevel.MPEG2LevelH14 to 6, CodecProfileLevel.MPEG2LevelHL to 4),
    )

    internal fun jellyfinLevel(codec: String, androidLevel: Int): Double? = levels[codec]?.get(androidLevel)?.toDouble()
    private fun androidLevel(codec: String?, value: Double?): Int? = levels[codec]?.entries?.firstOrNull { it.value.toDouble() == value }?.key
}

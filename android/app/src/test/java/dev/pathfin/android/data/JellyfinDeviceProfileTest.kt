package dev.pathfin.android.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.PlayMethod
import dev.pathfin.android.domain.PlaybackCapabilities
import dev.pathfin.android.domain.PlaybackDelivery
import dev.pathfin.android.domain.PlaybackHeaders
import dev.pathfin.android.domain.PlaybackInfo
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.PlaybackResolver
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.ResolvedPlayback
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.prefersOriginalSource
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JellyfinDeviceProfileTest {
    @Test fun playbackNegotiationDeclaresAudioCapabilitiesAndResolvesAudioWithoutChangingVideoProfiles() = withServer({ exchange ->
        assertEquals("POST", exchange.requestMethod)
        assertEquals("/jellyfin/Items/track/PlaybackInfo", exchange.requestURI.path)
        assertTrue(exchange.requestHeaders.getFirst("Authorization").contains("Token=\"fixture-token\""))
        val request = JSONObject(exchange.requestBody.bufferedReader().readText())
        assertEquals("user", request.getString("UserId"))
        assertTrue(request.getBoolean("EnableDirectPlay"))
        assertTrue(request.getBoolean("EnableTranscoding"))
        val profile = request.getJSONObject("DeviceProfile")
        val direct = profile.getJSONArray("DirectPlayProfiles").objects()
        val audio = direct.filter { it.getString("Type") == "Audio" }
        for ((container, codec) in listOf("mp3" to "mp3", "aac" to "aac", "m4a" to "aac", "mp4" to "aac", "wav" to "pcm_s16le")) {
            assertTrue("必须准确声明可直放的音频容器与编码", audio.any {
                container in it.getString("Container").split(',') && codec in it.getString("AudioCodec").split(',')
            })
        }
        assertTrue("音频不能附带视频解码要求", audio.none { it.has("VideoCodec") })
        assertTrue("不能宣称无编码限制的音频能力", audio.all { it.getString("AudioCodec").isNotBlank() })
        // 未注入本机能力时只声明保守的 H.264/AAC，与服务器转码输出一致。
        val video = direct.single { it.getString("Type") == "Video" }
        assertTrue("mkv" in video.getString("Container").split(',') && "mp4" in video.getString("Container").split(','))
        assertEquals("h264", video.getString("VideoCodec"))
        assertEquals("aac", video.getString("AudioCodec"))

        val transcoding = profile.getJSONArray("TranscodingProfiles").objects()
        val audioTranscode = transcoding.single { it.getString("Type") == "Audio" }
        assertEquals("mp3", audioTranscode.getString("Container"))
        assertEquals("mp3", audioTranscode.getString("AudioCodec"))
        assertEquals("http", audioTranscode.getString("Protocol"))
        assertEquals("Streaming", audioTranscode.getString("Context"))
        assertEquals("2", audioTranscode.getString("MaxAudioChannels"))
        assertFalse(audioTranscode.has("VideoCodec"))
        val videoTranscode = transcoding.single { it.getString("Type") == "Video" }
        assertEquals("hls", videoTranscode.getString("Protocol"))
        assertEquals("h264", videoTranscode.getString("VideoCodec"))
        assertEquals("aac", videoTranscode.getString("AudioCodec"))
        exchange.json("""{"MediaSources":[{"Id":"audio-source","Path":"/music/track.wav","Protocol":"File",
            "Container":"wav","SupportsDirectPlay":true}],"PlaySessionId":"audio-play"}""")
    }) { session ->
        val info = runBlocking { JellyfinApi().playbackInfo(session, "track") }
        assertEquals("audio-play", info.playSessionId)
        val source = info.sources.single()
        val audio = MediaItem("track", "Track", "Audio")
        val stream = PlaybackResolver.resolve(session, audio, info, source.id)
        assertEquals(session.server.baseUrl + "/Audio/track/stream?Static=true&MediaSourceId=audio-source&DeviceId=device", stream.uri)
        assertEquals(PlayMethod.DIRECT_PLAY, stream.method)
        assertTrue(stream.trustedServer)
        assertEquals(PlaybackHeaders.serverHeaders(session), stream.headers)
    }

    @Test fun detectedCapabilitiesAreDeclaredWithPerCodecLimitsSoPlayableFilesAreNotTranscoded() {
        val capabilities = PlaybackCapabilities(
            video = mapOf("h264" to PlaybackCapabilities.VideoLimit(4096, 2304, 8),
                "hevc" to PlaybackCapabilities.VideoLimit(7680, 4320, 10, setOf("Main", "Main 10"), 153.0, 120.0)),
            audioCodecs = setOf("aac", "flac", "truehd", "dts"))
        val profile = JellyfinApi(capabilities = { capabilities }).deviceProfile()
        val video = profile.getJSONArray("DirectPlayProfiles").objects().single { it.getString("Type") == "Video" }
        assertEquals(setOf("h264", "hevc"), video.getString("VideoCodec").split(',').toSet())
        assertEquals(setOf("aac", "flac", "truehd", "dts"), video.getString("AudioCodec").split(',').toSet())
        assertEquals("原画不能被旧 200 Mbps 上限挡成转码", Int.MAX_VALUE, profile.getInt("MaxStreamingBitrate"))
        assertEquals(Int.MAX_VALUE, profile.getInt("MaxStaticBitrate"))

        val codecs = profile.getJSONArray("CodecProfiles").objects().associateBy { it.getString("Codec") }
        fun limit(codec: String, property: String) = codecs.getValue(codec).getJSONArray("Conditions").objects()
            .single { it.getString("Property") == property }
        assertEquals("7680", limit("hevc", "Width").getString("Value"))
        assertEquals("4320", limit("hevc", "Height").getString("Value"))
        assertEquals("10", limit("hevc", "VideoBitDepth").getString("Value"))
        assertEquals("8", limit("h264", "VideoBitDepth").getString("Value"))
        assertEquals(setOf("Main", "Main 10"), limit("hevc", "VideoProfile").getString("Value").split('|').toSet())
        assertEquals(153.0, limit("hevc", "VideoLevel").getString("Value").toDouble(), 0.0)
        assertEquals(120.0, limit("hevc", "VideoFramerate").getString("Value").toDouble(), 0.0)
        val range = limit("hevc", "VideoRangeType").getString("Value").split('|')
        assertTrue("无 DV 解码器时纯 Dolby Vision（Profile 5）必须交给服务器转码", "DOVI" !in range)
        assertTrue("带兼容基础层的 DV 与 HDR10 仍应直放", "DOVIWithHDR10" in range && "HDR10" in range)
        assertTrue(codecs.getValue("h264").getJSONArray("Conditions").objects().none { it.getString("Property") == "VideoRangeType" })

        val profile8Only = JellyfinApi(capabilities = { capabilities.copy(dolbyVisionProfiles = setOf(8)) }).deviceProfile()
        assertTrue("只支持 Profile 8 不能宣称能解无基础层的 Profile 5", profile8Only.getJSONArray("CodecProfiles").objects()
            .flatMap { it.getJSONArray("Conditions").objects() }.any { it.getString("Property") == "VideoRangeType" })
        val withDolbyVision = JellyfinApi(capabilities = { capabilities.copy(dolbyVisionProfiles = setOf(5)) }).deviceProfile()
        assertTrue("已确认支持 Profile 5 时不应排除纯 DV 的 HEVC", withDolbyVision.getJSONArray("CodecProfiles").objects()
            .flatMap { it.getJSONArray("Conditions").objects() }.none { it.getString("Property") == "VideoRangeType" })
    }

    @Test fun mpeg2ReverseOrderedLevelsNeverBecomeAnAscendingServerLimit() {
        for (level in listOf(4.0, 8.0, 10.0)) {
            val capabilities = PlaybackCapabilities(
                video = mapOf("mpeg2video" to PlaybackCapabilities.VideoLimit(1920, 1080, 8, setOf("Main"), level, 60.0)),
                audioCodecs = setOf("aac"))
            val profile = JellyfinApi(capabilities = { capabilities }).deviceProfile()
            val conditions = profile.getJSONArray("CodecProfiles").objects().single().getJSONArray("Conditions").objects()
            assertTrue("MPEG2 仍声明尺寸、profile 和帧率条件", conditions.map { it.getString("Property") }
                .containsAll(listOf("Width", "Height", "VideoBitDepth", "VideoProfile", "VideoFramerate")))
            assertFalse("HL=4 不能用 <=4 误拒绝能解的 ML=8、LL=10", conditions.any { it.getString("Property") == "VideoLevel" })
        }
    }

    @Test fun speechDecodersAreAdvertisedOnlyForContainersMedia3CanExtract() {
        val speech = setOf("amr_nb", "amr_wb", "pcm_alaw", "pcm_mulaw")
        val capabilities = PlaybackCapabilities.Baseline.copy(audioCodecs = setOf("aac") + speech)
        val direct = JellyfinApi(capabilities = { capabilities }).deviceProfile().getJSONArray("DirectPlayProfiles").objects()
        for (codec in speech) {
            val declarations = direct.filter { codec in it.getString("AudioCodec").split(',') }
            assertTrue("已编译且可用的音频解码器必须向服务器声明 $codec", declarations.isNotEmpty())
            val allowed = if (codec.startsWith("amr_")) setOf("amr", "awb", "3gp", "3g2", "mp4", "m4a", "m4v", "mov")
                else setOf("wav", "3gp", "3g2", "mp4", "m4a", "m4v", "mov")
            assertTrue("FFmpeg 音频解码不能冒充缺失的容器解析 $codec", declarations.flatMap { it.getString("Container").split(',') }.all { it in allowed })
            assertTrue("MP4 族媒体可包含语音轨道", declarations.any { it.getString("Type") == "Video" && "mp4" in it.getString("Container").split(',') })
            assertTrue(declarations.any { it.getString("Type") == "Audio" && (if (codec.startsWith("amr_")) "amr" else "wav") in it.getString("Container").split(',') })
        }
        val noSpeech = JellyfinApi(capabilities = { PlaybackCapabilities.Baseline }).deviceProfile().getJSONArray("DirectPlayProfiles").objects()
        assertTrue("缺失的 decoder 不能仅因有 FFmpeg 容器就被宣称可播", noSpeech.none { it.getString("AudioCodec").split(',').any(speech::contains) })
    }

    @Test fun deliveryModesAndExplicitQualityDisableOnlyThePermittedCopyPaths() {
        for (delivery in PlaybackDelivery.entries) for (quality in listOf(PlaybackQuality.ORIGINAL, PlaybackQuality.DATA_SAVER)) {
            val effective = if (quality == PlaybackQuality.ORIGINAL) delivery else PlaybackDelivery.TRANSCODE
            withServer({ exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                assertEquals(effective == PlaybackDelivery.AUTOMATIC, body.getBoolean("EnableDirectPlay"))
                assertEquals(effective != PlaybackDelivery.TRANSCODE, body.getBoolean("EnableDirectStream"))
                assertEquals(effective != PlaybackDelivery.TRANSCODE, body.getBoolean("AllowVideoStreamCopy"))
                assertEquals(effective != PlaybackDelivery.TRANSCODE, body.getBoolean("AllowAudioStreamCopy"))
                assertTrue(body.getBoolean("EnableTranscoding"))
                assertFalse("未选音轨时沿用服务端默认，不发送 null 或伪造索引", body.has("AudioStreamIndex"))
                assertFalse("未选字幕时沿用服务端默认，不发送关闭字幕的索引", body.has("SubtitleStreamIndex"))
                if (quality == PlaybackQuality.ORIGINAL) assertFalse(body.has("MaxStreamingBitrate")) else {
                    assertEquals(quality.maxBitrate, body.getInt("MaxStreamingBitrate"))
                    assertEquals(quality.maxBitrate, body.getJSONObject("DeviceProfile").getInt("MaxStreamingBitrate"))
                }
                // 即使服务器仍回显旧支持标志，返回给 resolver 的模式和标志也必须遵守本次请求。
                exchange.json("""{"MediaSources":[{"Id":"source","SupportsDirectPlay":true,"SupportsDirectStream":true}],"PlaySessionId":"play"}""")
            }) { session ->
                val info = runBlocking { JellyfinApi().playbackInfo(session, "video", quality = quality, delivery = delivery) }
                assertEquals(effective, info.delivery)
                assertEquals(effective == PlaybackDelivery.AUTOMATIC, info.sources.single().supportsDirectPlay)
                assertEquals(effective != PlaybackDelivery.TRANSCODE, info.sources.single().supportsDirectStream)
            }
        }
    }

    @Test fun knownUnsupportedSelectedVideoRenegotiatesOnceWithBothTracksEncoded() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            val count = requests.incrementAndGet()
            assertEquals(35_000_000L, body.getLong("StartTimeTicks"))
            if (count == 1) {
                assertTrue(body.getBoolean("EnableDirectPlay"))
                assertFalse(body.has("MediaSourceId"))
            } else {
                assertEquals(2, count)
                assertEquals("source", body.getString("MediaSourceId"))
                for (key in listOf("EnableDirectPlay", "EnableDirectStream", "AllowVideoStreamCopy", "AllowAudioStreamCopy")) assertFalse(key, body.getBoolean(key))
            }
            exchange.json("""{"MediaSources":[{"Id":"source","Path":"https://cdn.example/original.mkv","Protocol":"Http",
                "SupportsDirectPlay":true,"SupportsDirectStream":true,
                "TranscodingUrl":"/Videos/video/master.m3u8","MediaStreams":[{"Type":"Video","Codec":"hevc","Profile":"Main 10","Width":7680,"Height":4320}]}],"PlaySessionId":"play-$count"}""")
        }) { session ->
            val checks = AtomicInteger()
            val info = runBlocking { JellyfinApi(videoSupport = { stream ->
                checks.incrementAndGet()
                assertEquals("hevc", stream.codec)
                assertEquals(7680, stream.width)
                false
            }).playbackInfo(session, "video", startTimeTicks = 35_000_000L) }
            assertEquals(2, requests.get())
            assertEquals(1, checks.get())
            assertEquals(PlaybackDelivery.TRANSCODE, info.delivery)
            assertEquals("play-2", info.playSessionId)
            assertFalse(info.sources.single().supportsDirectPlay)
            assertFalse(info.sources.single().supportsDirectStream)
            val resolved = PlaybackResolver.resolve(session, MediaItem("video", "Video", "Movie"), info, "source",
                preferOriginal = true)
            assertEquals("普通 STRM 仍优先本机能力预检后的服务器转码", PlayMethod.TRANSCODE, resolved.method)
            assertTrue(resolved.trustedServer)
        }
    }

    @Test fun liveDirectSourcePreferenceSkipsUnsupportedVideoPreflightOnlyWhenEnabled() {
        val channel = MediaItem("channel", "Channel", "TvChannel")
        for (direct in listOf(false, true)) {
            val requests = AtomicInteger()
            val checks = AtomicInteger()
            withServer({ exchange ->
                assertEquals("/jellyfin/Items/channel/PlaybackInfo", exchange.requestURI.path)
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                val count = requests.incrementAndGet()
                assertEquals(35_000_000L, body.getLong("StartTimeTicks"))
                assertEquals(count == 1, body.getBoolean("EnableDirectPlay"))
                exchange.json("""{"MediaSources":[{"Id":"source","Path":"https://iptv.example/live.m3u8","Protocol":"Http",
                    "SupportsDirectPlay":false,"SupportsDirectStream":false,"TranscodingUrl":"/Videos/channel/master.m3u8",
                    "MediaStreams":[{"Type":"Video","Codec":"hevc","Profile":"Main 10","Width":7680,"Height":4320}]}],"PlaySessionId":"play-$count"}""")
            }) { session ->
                val preferences = AppPreferences(originalPathEnabled = false, liveTvDirectPlay = direct)
                val info = runBlocking { JellyfinApi(videoSupport = { checks.incrementAndGet(); false })
                    .playbackInfo(session, channel.id, startTimeTicks = 35_000_000L,
                        videoPreflight = if (direct) VideoPreflightPolicy.TRY_ORIGINAL_SOURCE
                            else VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED) }
                val stream = PlaybackResolver.resolve(session, channel, info, "source", preferences.prefersOriginalSource(channel))
                assertEquals(if (direct) 1 else 2, requests.get())
                assertEquals(if (direct) 0 else 1, checks.get())
                assertEquals(if (direct) PlaybackDelivery.AUTOMATIC else PlaybackDelivery.TRANSCODE, info.delivery)
                assertEquals(if (direct) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE, stream.method)
                assertEquals(!direct, stream.trustedServer)
                if (direct) {
                    assertEquals("https://iptv.example/live.m3u8", stream.uri)
                    assertFalse("直播源不能带上服务器令牌", stream.headers.values.any { "fixture-token" in it })
                } else assertEquals(session.server.baseUrl + "/Videos/channel/master.m3u8", stream.uri)
            }
        }
    }

    @Test fun tryingLiveOriginalSourceNeverOverridesExplicitQualityOrRecoveryDelivery() {
        val channel = MediaItem("channel", "Channel", "TvChannel")
        for (delivery in PlaybackDelivery.entries) for (quality in listOf(PlaybackQuality.ORIGINAL, PlaybackQuality.DATA_SAVER)) {
            if (delivery == PlaybackDelivery.AUTOMATIC && quality == PlaybackQuality.ORIGINAL) continue
            val effective = if (quality == PlaybackQuality.ORIGINAL) delivery else PlaybackDelivery.TRANSCODE
            val requests = AtomicInteger()
            withServer({ exchange ->
                requests.incrementAndGet()
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                assertFalse(body.getBoolean("EnableDirectPlay"))
                assertEquals(effective != PlaybackDelivery.TRANSCODE, body.getBoolean("EnableDirectStream"))
                assertEquals(effective != PlaybackDelivery.TRANSCODE, body.getBoolean("AllowVideoStreamCopy"))
                assertEquals(effective != PlaybackDelivery.TRANSCODE, body.getBoolean("AllowAudioStreamCopy"))
                assertEquals(0, body.getInt("AudioStreamIndex"))
                assertEquals(-1, body.getInt("SubtitleStreamIndex"))
                if (quality.maxBitrate != null) assertEquals(quality.maxBitrate, body.getInt("MaxStreamingBitrate"))
                exchange.json("""{"MediaSources":[{"Id":"source","Path":"https://iptv.example/live.m3u8","Protocol":"Http",
                    "SupportsDirectPlay":true,"SupportsDirectStream":true,"DirectStreamUrl":"/Videos/channel/stream.ts",
                    "TranscodingUrl":"/Videos/channel/master.m3u8","MediaStreams":[{"Type":"Video","Codec":"hevc"}]}],"PlaySessionId":"play"}""")
            }) { session ->
                val info = runBlocking { JellyfinApi(videoSupport = { throw AssertionError("显式递交模式不应运行起播预检") })
                    .playbackInfo(session, channel.id, quality = quality, delivery = delivery,
                        audioStreamIndex = 0, subtitleStreamIndex = -1,
                        videoPreflight = VideoPreflightPolicy.TRY_ORIGINAL_SOURCE) }
                val stream = PlaybackResolver.resolve(session, channel, info, "source", preferOriginal = true,
                    quality = quality, delivery = delivery)
                assertEquals(1, requests.get())
                assertEquals(effective, info.delivery)
                assertEquals(if (effective == PlaybackDelivery.TRANSCODE) PlayMethod.TRANSCODE else PlayMethod.DIRECT_STREAM, stream.method)
                assertTrue("显式转码或重封装不能跳回直播原地址", stream.trustedServer)
                assertTrue(stream.uri.startsWith(session.server.baseUrl + "/Videos/channel/"))
            }
        }
    }

    @Test fun supportedOrUnknownVideoDoesNotAddAnotherNetworkRoundTrip() {
        for (support in listOf(true, null)) {
            val requests = AtomicInteger()
            withServer({ exchange ->
                requests.incrementAndGet()
                exchange.json("""{"MediaSources":[{"Id":"source","SupportsDirectPlay":true,"MediaStreams":[{"Type":"Video","Codec":"hevc","Profile":"Main 10"}]}],"PlaySessionId":"play"}""")
            }) { session ->
                val info = runBlocking { JellyfinApi(videoSupport = { support }).playbackInfo(session, "video") }
                assertEquals(1, requests.get())
                assertEquals(PlaybackDelivery.AUTOMATIC, info.delivery)
                assertTrue(info.sources.single().supportsDirectPlay)
            }
        }
    }

    @Test fun locallyPlayableVideoExcludedByStaticProfileRenegotiatesOnlyItsKnownSource() {
        val capabilities = PlaybackCapabilities(
            video = mapOf("h264" to PlaybackCapabilities.VideoLimit(1920, 1080, 8, setOf("Baseline", "Main", "High"), 41.0, 60.0),
                "hevc" to PlaybackCapabilities.VideoLimit(7680, 4320, 10, setOf("Main", "Main 10"), 153.0, 120.0)),
            audioCodecs = setOf("aac", "truehd"))
        val high10 = JSONObject("""{"Type":"Video","Codec":"h264","Profile":"High 10","Width":1920,"Height":1080,
            "BitDepth":10,"Level":51,"AverageFrameRate":24,"VideoRangeType":"SDR"}""")
        val dv5 = JSONObject("""{"Type":"Video","Codec":"hevc","Profile":"Main 10","Width":3840,"Height":2160,
            "BitDepth":10,"Level":150,"AverageFrameRate":25,"VideoRangeType":"DOVI","DvProfile":5}""")
        val dv7 = JSONObject("""{"Type":"Video","Codec":"hevc","Profile":"Main 10","Width":3840,"Height":2160,
            "BitDepth":10,"Level":153,"AverageFrameRate":23.976025,"VideoRangeType":"DOVIWithEL","DvProfile":7}""")
        for (video in listOf(high10, dv5, dv7)) {
            val requests = mutableListOf<JSONObject>()
            withServer({ exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                requests += body
                val count = requests.size
                assertTrue("最多允许一次来源限定重协商", count <= 2)
                assertEquals(35_000_000L, body.getLong("StartTimeTicks"))
                assertEquals(3, body.getInt("AudioStreamIndex"))
                assertEquals(7, body.getInt("SubtitleStreamIndex"))
                assertTrue(body.getBoolean("EnableDirectPlay"))
                assertTrue(body.getBoolean("AllowVideoStreamCopy"))
                if (count == 2) assertEquals("source", body.getString("MediaSourceId"))
                // 与 Jellyfin 一致：帧率先存为 float，再升为 double 与条件比较。
                val serverRate = video.getDouble("AverageFrameRate").toFloat().toDouble()
                val allowed = if (count == 2) {
                    val frameRate = body.getJSONObject("DeviceProfile").getJSONArray("CodecProfiles").objects()
                        .single { it.getString("Codec") == video.getString("Codec") }
                        .getJSONArray("Conditions").objects().single { it.getString("Property") == "VideoFramerate" }
                    val limit = frameRate.getString("Value").toDouble()
                    when (frameRate.getString("Condition")) {
                        "Equals" -> serverRate == limit
                        "LessThanEqual" -> serverRate <= limit
                        else -> false
                    }
                } else false
                val source = JSONObject().put("Id", "source")
                    .put("Path", if (video === dv7) "https://cloud.example/video.mkv" else "/library/video.mkv")
                    .put("Protocol", if (video === dv7) "Http" else "File")
                    .put("Container", "mkv").put("SupportsDirectPlay", allowed)
                    .put("MediaStreams", JSONArray().put(video))
                if (!allowed) source.put("TranscodingUrl", "/Videos/video/master.m3u8")
                exchange.json(JSONObject().put("MediaSources", JSONArray().put(source)).put("PlaySessionId", "play-$count").toString())
            }) { session ->
                val checks = AtomicInteger()
                val api = JellyfinApi(capabilities = { capabilities }, videoSupport = { stream ->
                    checks.incrementAndGet()
                    assertEquals(video.getString("Codec"), stream.codec)
                    true
                })
                val info = runBlocking { api.playbackInfo(session, "video", startTimeTicks = 35_000_000L,
                    audioStreamIndex = 3, subtitleStreamIndex = 7) }
                assertEquals("本机可补解且静态声明误拒时，必须重新向服务器声明当前来源", 2, requests.size)
                assertEquals(1, checks.get())
                assertEquals("play-2", info.playSessionId)
                assertEquals(PlaybackDelivery.AUTOMATIC, info.delivery)
                assertEquals(3, info.audioStreamIndex)
                assertEquals(7, info.subtitleStreamIndex)
                assertTrue(info.sources.single().supportsDirectPlay)
                val first = requests[0].getJSONObject("DeviceProfile")
                val narrowed = requests[1].getJSONObject("DeviceProfile")
                for (key in listOf("DirectPlayProfiles", "TranscodingProfiles", "SubtitleProfiles"))
                    assertEquals("视频补解不能改变容器、音频、字幕协商", first.getJSONArray(key).toString(), narrowed.getJSONArray(key).toString())
                val codec = video.getString("Codec")
                val changed = narrowed.getJSONArray("CodecProfiles").objects().single { it.getString("Codec") == codec }
                    .getJSONArray("Conditions").objects().associateBy { it.getString("Property") }
                val fields = mapOf("Width" to "Width", "Height" to "Height", "VideoBitDepth" to "BitDepth",
                    "VideoProfile" to "Profile", "VideoLevel" to "Level", "VideoFramerate" to "AverageFrameRate", "VideoRangeType" to "VideoRangeType")
                assertEquals(fields.keys, changed.keys)
                for ((property, field) in fields) {
                    val condition = changed.getValue(property)
                    val frameRate = property == "VideoFramerate"
                    assertEquals("仅帧率允许按来源取整上限，其余字段必须精确匹配",
                        if (frameRate) "LessThanEqual" else "Equals", condition.getString("Condition"))
                    assertTrue("来源已知条件不能当作可选字段忽略", condition.getBoolean("IsRequired"))
                    if (video.get(field) is Number) assertEquals(
                        if (frameRate) ceil(video.getDouble(field)) else video.getDouble(field), condition.getString("Value").toDouble(), 0.0)
                    else assertEquals(video.getString(field), condition.getString("Value"))
                }
                val other = if (codec == "h264") "hevc" else "h264"
                fun unchanged(profile: JSONObject) = profile.getJSONArray("CodecProfiles").objects().single { it.getString("Codec") == other }.toString()
                assertEquals(unchanged(first), unchanged(narrowed))
                assertEquals("来源限定声明不能污染全局能力", first.toString(), api.deviceProfile().toString())
                assertEquals(PlayMethod.DIRECT_PLAY, PlaybackResolver.resolve(session, MediaItem("video", "Video", "Movie"), info, "source", preferOriginal = false).method)
            }
        }
    }

    @Test fun softwareCodecAbsentFromStaticCapabilitiesCanBeNegotiatedWithoutInventingOtherProfiles() {
        for ((codec, name, depth) in listOf(Triple("hevc", "Main 10", 10), Triple("mpeg2video", "Main", 8))) {
            val level = if (codec == "hevc") 120 else 8
            val range = if (codec == "hevc") "HDR10" else "SDR"
            val requests = AtomicInteger()
            val initial = AtomicReference<JSONObject>()
            withServer({ exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                val count = requests.incrementAndGet()
                assertTrue("$codec 来源限定协商最多两次请求", count <= 2)
                val profile = body.getJSONObject("DeviceProfile")
                val declared = profile.getJSONArray("DirectPlayProfiles").objects().single { it.getString("Type") == "Video" }
                assertEquals("aac", declared.getString("AudioCodec"))
                if (count == 1) {
                    initial.set(profile)
                    assertEquals("h264", declared.getString("VideoCodec"))
                } else {
                    assertEquals("source", body.getString("MediaSourceId"))
                    assertEquals(setOf("h264", codec), declared.getString("VideoCodec").split(',').toSet())
                    val before = initial.get()
                    val original = before.getJSONArray("DirectPlayProfiles").objects().single { it.getString("Type") == "Video" }
                    assertEquals("视频补解不能扩大容器范围", original.getString("Container"), declared.getString("Container"))
                    assertEquals("音频声明必须原样保留",
                        before.getJSONArray("DirectPlayProfiles").objects().filter { it.getString("Type") == "Audio" }.map { it.toString() },
                        profile.getJSONArray("DirectPlayProfiles").objects().filter { it.getString("Type") == "Audio" }.map { it.toString() })
                    val rows = profile.getJSONArray("CodecProfiles").objects()
                    assertEquals("只能补充当前软件codec", setOf("h264", codec), rows.map { it.getString("Codec") }.toSet())
                    assertEquals("不能扩大已有H264的profile",
                        before.getJSONArray("CodecProfiles").objects().single().toString(),
                        rows.single { it.getString("Codec") == "h264" }.toString())
                    val conditions = rows.single { it.getString("Codec") == codec }.getJSONArray("Conditions").objects()
                        .associateBy { it.getString("Property") }
                    val exact = mapOf("Width" to "1920", "Height" to "1080", "VideoBitDepth" to depth.toString(),
                        "VideoProfile" to name, "VideoLevel" to level.toString(), "VideoFramerate" to "24", "VideoRangeType" to range)
                    assertEquals(exact.keys, conditions.keys)
                    for ((property, value) in exact) {
                        val condition = conditions.getValue(property)
                        assertTrue("来源字段必须精确约束", condition.getBoolean("IsRequired"))
                        assertEquals("MPEG2反序level只按当前来源精确匹配，不能变成递增上限",
                            if (property == "VideoFramerate") "LessThanEqual" else "Equals", condition.getString("Condition"))
                        if (value.toDoubleOrNull() == null) assertEquals(value, condition.getString("Value"))
                        else assertEquals(value.toDouble(), condition.getString("Value").toDouble(), 0.0)
                    }
                }
                exchange.json("""{"MediaSources":[{"Id":"source","SupportsDirectPlay":${count == 2},"Container":"mkv",
                    "MediaStreams":[{"Type":"Video","Codec":"$codec","Profile":"$name","Width":1920,"Height":1080,
                    "BitDepth":$depth,"Level":$level,"AverageFrameRate":24,"VideoRangeType":"$range"}]}],"PlaySessionId":"play-$count"}""")
            }) { session ->
                val api = JellyfinApi(videoSupport = { true })
                val info = runBlocking { api.playbackInfo(session, "video") }
                assertEquals(2, requests.get())
                assertTrue(info.sources.single().supportsDirectPlay)
                assertEquals("来源限定声明不能污染全局能力", initial.get().toString(), api.deviceProfile().toString())
            }
        }
    }

    @Test fun audioContainerOrSubtitleRejectionDoesNotRetryWhenStaticVideoAlreadyAllowsSource() {
        val capabilities = PlaybackCapabilities(
            mapOf("h264" to PlaybackCapabilities.VideoLimit(1920, 1080, 8, setOf("Main"), 41.0, 60.0)), setOf("aac"))
        for (rejection in listOf("audio", "container", "subtitle")) {
            val requests = AtomicInteger()
            withServer({ exchange ->
                requests.incrementAndGet()
                exchange.json("""{"MediaSources":[{"Id":"source","Container":"${if (rejection == "container") "avi" else "mkv"}",
                    "SupportsDirectPlay":false,"TranscodingUrl":"/Videos/video/master.m3u8","MediaStreams":[
                    {"Type":"Video","Codec":"h264","Profile":"Main","Width":1920,"Height":1080,"BitDepth":8,"Level":40,"AverageFrameRate":24,"VideoRangeType":"SDR"},
                    {"Type":"Audio","Codec":"${if (rejection == "audio") "future-audio" else "aac"}"},
                    {"Type":"Subtitle","Codec":"${if (rejection == "subtitle") "vobsub" else "srt"}"}]}],"PlaySessionId":"play"}""")
            }) { session ->
                val info = runBlocking { JellyfinApi(capabilities = { capabilities }, videoSupport = { true }).playbackInfo(session, "video") }
                assertEquals("$rejection 拒绝不能增加无意义视频重协商", 1, requests.get())
                assertFalse("不能伪造服务器直放许可", info.sources.single().supportsDirectPlay)
            }
        }
    }

    @Test fun sourceSpecificVideoNegotiationHasOneRetryAndKeepsServerRejection() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            val count = requests.incrementAndGet()
            assertTrue("来源限定协商不能循环重试", count <= 2)
            exchange.json("""{"MediaSources":[{"Id":"source","SupportsDirectPlay":false,"TranscodingUrl":"/Videos/video/master.m3u8",
                "MediaStreams":[{"Type":"Video","Codec":"h264","Profile":"High 10","Width":1920,"Height":1080,"BitDepth":10}]}],"PlaySessionId":"play-$count"}""")
        }) { session ->
            val info = runBlocking { JellyfinApi(videoSupport = { true }).playbackInfo(session, "video", sourceId = "source") }
            assertEquals(2, requests.get())
            assertEquals("play-2", info.playSessionId)
            assertFalse(info.sources.single().supportsDirectPlay)
            assertEquals(PlayMethod.TRANSCODE, PlaybackResolver.resolve(session, MediaItem("video", "Video", "Movie"), info, "source").method)
        }
    }

    @Test fun incompleteOrUnknownVideoAndUnselectedVersionsNeverBroadenStaticProfile() {
        for (incomplete in listOf("Width", "Height", "BitDepth", "Profile", "unknown", "multiple")) {
            val requests = AtomicInteger()
            withServer({ exchange ->
                requests.incrementAndGet()
                val video = JSONObject("""{"Type":"Video","Codec":"h264","Profile":"High 10","Width":1920,"Height":1080,"BitDepth":10}""")
                video.remove(incomplete)
                val source = JSONObject().put("Id", "source").put("SupportsDirectPlay", false).put("MediaStreams", JSONArray().put(video))
                val sources = JSONArray().put(source)
                if (incomplete == "multiple") sources.put(JSONObject(source.toString()).put("Id", "other"))
                exchange.json(JSONObject().put("MediaSources", sources).put("PlaySessionId", "play").toString())
            }) { session ->
                val info = runBlocking { JellyfinApi(videoSupport = { if (incomplete == "unknown") null else true }).playbackInfo(session, "video") }
                assertEquals("$incomplete 信息不足时不能扩大声明", 1, requests.get())
                assertFalse(info.sources.first().supportsDirectPlay)
            }
        }
    }

    @Test fun directSourcePolicyAndExplicitDeliveryNeverRunSoftwareRenegotiation() {
        val cases = listOf(PlaybackQuality.ORIGINAL to PlaybackDelivery.AUTOMATIC,
            PlaybackQuality.DATA_SAVER to PlaybackDelivery.AUTOMATIC,
            PlaybackQuality.ORIGINAL to PlaybackDelivery.SERVER_STREAM,
            PlaybackQuality.ORIGINAL to PlaybackDelivery.TRANSCODE)
        for ((quality, delivery) in cases) {
            val requests = AtomicInteger()
            withServer({ exchange ->
                requests.incrementAndGet()
                exchange.json("""{"MediaSources":[{"Id":"source","SupportsDirectPlay":false,"MediaStreams":[
                    {"Type":"Video","Codec":"h264","Profile":"High 10","Width":1920,"Height":1080,"BitDepth":10}]}],"PlaySessionId":"play"}""")
            }) { session ->
                runBlocking { JellyfinApi(videoSupport = { throw AssertionError("此模式禁止本地视频重协商") })
                    .playbackInfo(session, "video", quality = quality, delivery = delivery,
                        videoPreflight = if (delivery == PlaybackDelivery.AUTOMATIC && quality == PlaybackQuality.ORIGINAL)
                            VideoPreflightPolicy.TRY_ORIGINAL_SOURCE else VideoPreflightPolicy.NEGOTIATE_UNSUPPORTED) }
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun originalPathTryUsesSelectedLocalSourceForOnlyOneAdditionalVideoNegotiation() {
        for (acceptsVideo in listOf(true, false)) {
            val requests = mutableListOf<JSONObject>()
            withServer({ exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                requests += body
                val count = requests.size
                assertTrue("初始 TRY 与来源限定请求合计只能两次", count <= 2)
                assertEquals(35_000_000L, body.getLong("StartTimeTicks"))
                assertEquals(3, body.getInt("AudioStreamIndex"))
                assertEquals(7, body.getInt("SubtitleStreamIndex"))
                if (count == 1) assertFalse(body.has("MediaSourceId"))
                else assertEquals("chosen", body.getString("MediaSourceId"))
                val chosen = JSONObject("""{"Id":"chosen","Path":"/library/video.mkv","Protocol":"File",
                    "Container":"${if (acceptsVideo) "mkv" else "avi"}","SupportsDirectPlay":${count == 2 && acceptsVideo},
                    "MediaStreams":[{"Type":"Video","Codec":"h264","Profile":"High 10","Width":1920,"Height":1080,
                    "BitDepth":10,"Level":51,"AverageFrameRate":24,"VideoRangeType":"SDR"},
                    {"Type":"Audio","Codec":"${if (acceptsVideo) "aac" else "future-audio"}"},
                    {"Type":"Subtitle","Codec":"${if (acceptsVideo) "srt" else "vobsub"}"}]}""")
                if (count == 1 || !acceptsVideo) chosen.put("TranscodingUrl", "/Videos/video/master.m3u8")
                val sources = JSONArray().put(chosen)
                if (count == 1) sources.put(JSONObject("""{"Id":"other","Protocol":"File","SupportsDirectPlay":false,
                    "MediaStreams":[{"Type":"Video","Codec":"hevc","Profile":"Main 10","Width":7680,"Height":4320,"BitDepth":10}]}"""))
                exchange.json(JSONObject().put("MediaSources", sources).put("PlaySessionId", "play-$count").toString())
            }) { session ->
                val checks = AtomicInteger()
                val api = JellyfinApi(videoSupport = { checks.incrementAndGet(); true })
                val original = runBlocking { api.playbackInfo(session, "video", startTimeTicks = 35_000_000L,
                    audioStreamIndex = 3, subtitleStreamIndex = 7, videoPreflight = VideoPreflightPolicy.TRY_ORIGINAL_SOURCE) }
                assertEquals(1, requests.size)
                assertEquals("TRY 不能提前进行本机预检", 0, checks.get())
                val info = runBlocking { api.negotiateSelectedSourceVideo(session, "video", original, "chosen",
                    35_000_000L, PlaybackQuality.ORIGINAL) }
                assertEquals("不能先发旧静态声明再重试，必须直接发已知来源的精确条件", 2, requests.size)
                assertEquals(1, checks.get())
                assertEquals("play-2", info.playSessionId)
                assertEquals(acceptsVideo, info.sources.single().supportsDirectPlay)
                assertEquals(3, info.audioStreamIndex)
                assertEquals(7, info.subtitleStreamIndex)
                val first = requests[0].getJSONObject("DeviceProfile")
                val exact = requests[1].getJSONObject("DeviceProfile")
                for (key in listOf("DirectPlayProfiles", "TranscodingProfiles", "SubtitleProfiles"))
                    assertEquals("来源补解不得改变音频、容器、字幕能力", first.getJSONArray(key).toString(), exact.getJSONArray(key).toString())
                val conditions = exact.getJSONArray("CodecProfiles").objects().single { it.getString("Codec") == "h264" }
                    .getJSONArray("Conditions").objects().associateBy { it.getString("Property") }
                assertEquals("High 10", conditions.getValue("VideoProfile").getString("Value"))
                assertEquals("1920", conditions.getValue("Width").getString("Value"))
                assertTrue(conditions.values.all {
                    it.getString("Condition") == if (it.getString("Property") == "VideoFramerate") "LessThanEqual" else "Equals"
                })
                assertTrue(conditions.values.all { it.getBoolean("IsRequired") })
                assertEquals("临时条件不能污染全局 profile", first.toString(), api.deviceProfile().toString())
                assertEquals(if (acceptsVideo) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE,
                    PlaybackResolver.resolve(session, MediaItem("video", "Video", "Movie"), info, "chosen").method)
            }
        }
    }

    @Test fun selectedHttpVersionUsesOneExactVideoNegotiationWithoutBroadeningOtherVersions() {
        val requests = mutableListOf<JSONObject>()
        withServer({ exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            requests += body
            val selected = body.optString("MediaSourceId") == "chosen"
            if (selected) {
                assertEquals(2, requests.size)
                val video = body.getJSONObject("DeviceProfile").getJSONArray("CodecProfiles").objects()
                    .single { it.optString("Codec") == "h264" }
                val conditions = video.getJSONArray("Conditions").objects().associateBy { it.getString("Property") }
                assertEquals("High 10", conditions.getValue("VideoProfile").getString("Value"))
                assertEquals("Equals", conditions.getValue("VideoProfile").getString("Condition"))
            }
            val source = JSONObject("""{"Id":"chosen","Protocol":"Http","Path":"https://media.invalid/original.mkv",
                "Container":"mkv","SupportsDirectPlay":$selected,"MediaStreams":[{"Type":"Video","Codec":"h264",
                "Profile":"High 10","Width":1920,"Height":1080,"BitDepth":10,"Level":51,"VideoRangeType":"SDR"}]}""")
            if (!selected) source.put("TranscodingUrl", "/Videos/video/master.m3u8")
            val sources = JSONArray().put(source)
            if (!selected) sources.put(JSONObject("""{"Id":"unselected","Protocol":"File","SupportsDirectPlay":false}"""))
            exchange.json(JSONObject().put("MediaSources", sources).put("PlaySessionId", "play-${requests.size}").toString())
        }) { session ->
            val api = JellyfinApi(videoSupport = { true })
            val initial = runBlocking { api.playbackInfo(session, "video") }
            assertEquals("未选择版本不能扩大任一来源的能力声明", 1, requests.size)
            val info = runBlocking { api.negotiateSelectedSourceVideo(session, "video", initial, "chosen", 0, PlaybackQuality.ORIGINAL) }
            assertEquals("关闭原始路径时，HTTP 版本同样需要限定来源的能力声明", 2, requests.size)
            assertTrue(info.sources.single().supportsDirectPlay)
            assertEquals("play-2", info.playSessionId)
            assertEquals(PlayMethod.DIRECT_PLAY,
                PlaybackResolver.resolve(session, MediaItem("video", "Video", "Movie"), info, "chosen", preferOriginal = false).method)
        }
    }

    @Test fun originalPathSelectedSourceAddsNoRequestUnlessVideoNeedsAndSupportsExactNegotiation() {
        val cases = listOf("audio", "container", "subtitle", "unknown", "unsupported", "incomplete",
            "already-direct", "missing-source", "quality", "server-stream", "transcode", "server-error")
        for (reason in cases) {
            val requests = AtomicInteger()
            withServer({ exchange ->
                requests.incrementAndGet()
                val video = JSONObject("""{"Type":"Video","Codec":"h264","Profile":"High 10","Width":1920,
                    "Height":1080,"BitDepth":10,"Level":51,"VideoRangeType":"SDR"}""")
                if (reason in setOf("audio", "container", "subtitle")) video.put("Profile", "Main").put("BitDepth", 8).put("Level", 40)
                if (reason == "incomplete") video.remove("Width")
                val source = JSONObject().put("Id", "chosen").put("Path", "/library/video.mkv")
                    .put("Protocol", "File")
                    .put("Container", if (reason == "container") "avi" else "mkv")
                    .put("SupportsDirectPlay", reason == "already-direct")
                    .put("MediaStreams", JSONArray().put(video)
                        .put(JSONObject().put("Type", "Audio").put("Codec", if (reason == "audio") "future-audio" else "aac"))
                        .put(JSONObject().put("Type", "Subtitle").put("Codec", if (reason == "subtitle") "vobsub" else "srt")))
                exchange.json(JSONObject().put("MediaSources", JSONArray().put(source)).put("PlaySessionId", "original").toString())
            }) { session ->
                val api = JellyfinApi(videoSupport = { when (reason) { "unknown" -> null; "unsupported" -> false; else -> true } })
                val original = runBlocking { api.playbackInfo(session, "video", videoPreflight = VideoPreflightPolicy.TRY_ORIGINAL_SOURCE) }
                val selected = when (reason) {
                    "server-stream" -> original.copy(delivery = PlaybackDelivery.SERVER_STREAM)
                    "transcode" -> original.copy(delivery = PlaybackDelivery.TRANSCODE)
                    "server-error" -> original.copy(errorCode = "NoCompatibleStream")
                    else -> original
                }
                val info = runBlocking { api.negotiateSelectedSourceVideo(session, "video", selected,
                    if (reason == "missing-source") "missing" else "chosen", 0,
                    if (reason == "quality") PlaybackQuality.DATA_SAVER else PlaybackQuality.ORIGINAL) }
                assertSame("$reason 不应改写已有协商结果", selected, info)
                assertEquals("$reason 不应新增请求", 1, requests.get())
            }
        }
    }

    @Test fun selectedAudioAndSubtitleSurviveSourceSpecificTranscodingRenegotiation() {
        for ((audio, subtitle) in listOf(0 to -1, 3 to 7)) {
            val requests = AtomicInteger()
            withServer({ exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                val count = requests.incrementAndGet()
                assertEquals(audio, body.getInt("AudioStreamIndex"))
                assertEquals(subtitle, body.getInt("SubtitleStreamIndex"))
                assertEquals(count == 1, body.getBoolean("EnableDirectPlay"))
                if (count == 2) assertEquals("source", body.getString("MediaSourceId"))
                exchange.json("""{"MediaSources":[{"Id":"source","MediaStreams":[{"Type":"Video","Codec":"hevc","Profile":"Main 10"}]}],"PlaySessionId":"play-$count"}""")
            }) { session ->
                val info = runBlocking { JellyfinApi(videoSupport = { false }).playbackInfo(session, "video",
                    audioStreamIndex = audio, subtitleStreamIndex = subtitle) }
                assertEquals("精确预检只应重协商一次", 2, requests.get())
                assertEquals(PlaybackDelivery.TRANSCODE, info.delivery)
                assertEquals(audio, info.audioStreamIndex)
                assertEquals(subtitle, info.subtitleStreamIndex)
            }
        }
    }

    @Test fun invalidTrackIndicesAreRejectedBeforeAnyPlaybackRequest() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            exchange.json("""{"MediaSources":[],"PlaySessionId":"unexpected"}""")
        }) { session ->
            val api = JellyfinApi()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { api.playbackInfo(session, "video", audioStreamIndex = -1) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { api.playbackInfo(session, "video", subtitleStreamIndex = -2) }
            }
            assertEquals(0, requests.get())
        }
    }

    @Test fun multipleUnselectedSourcesAreNotAllTranscodedForOneUnsupportedVersion() {
        val requests = AtomicInteger()
        val checks = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            exchange.json("""{"MediaSources":[
                {"Id":"8k","SupportsDirectPlay":true,"MediaStreams":[{"Type":"Video","Width":7680,"Codec":"hevc"}]},
                {"Id":"1080p","SupportsDirectPlay":true,"MediaStreams":[{"Type":"Video","Width":1920,"Codec":"h264"}]}],"PlaySessionId":"play"}""")
        }) { session ->
            val info = runBlocking { JellyfinApi(videoSupport = { checks.incrementAndGet(); false }).playbackInfo(session, "video") }
            assertEquals(1, requests.get())
            assertEquals(0, checks.get())
            assertEquals(PlaybackDelivery.AUTOMATIC, info.delivery)
            assertEquals(2, info.sources.size)
        }
    }

    @Test fun explicitSourceSelectionChecksOnlyThatVersionAndPinsRenegotiationToIt() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            val count = requests.incrementAndGet()
            val body = JSONObject(exchange.requestBody.bufferedReader().readText())
            assertEquals("8k", body.getString("MediaSourceId"))
            assertEquals(count == 1, body.getBoolean("EnableDirectPlay"))
            exchange.json("""{"MediaSources":[
                {"Id":"1080p","MediaStreams":[{"Type":"Video","Width":1920,"Codec":"h264"}]},
                {"Id":"8k","TranscodingUrl":"/Videos/video/master.m3u8","MediaStreams":[{"Type":"Video","Width":7680,"Codec":"hevc"}]}],"PlaySessionId":"play-$count"}""")
        }) { session ->
            val info = runBlocking { JellyfinApi(videoSupport = { assertEquals(7680, it.width); false })
                .playbackInfo(session, "video", sourceId = "8k") }
            assertEquals(2, requests.get())
            assertEquals(PlaybackDelivery.TRANSCODE, info.delivery)
        }
    }

    @Test fun stopTranscodingDeletesOnlyTheOwnTrustedTranscodeSession() {
        val requests = AtomicInteger()
        withServer({ exchange ->
            requests.incrementAndGet()
            assertEquals("DELETE", exchange.requestMethod)
            assertEquals("/jellyfin/Videos/ActiveEncodings", exchange.requestURI.path)
            assertEquals("deviceId=device&playSessionId=play-own", exchange.requestURI.rawQuery)
            assertTrue(exchange.requestHeaders.getFirst("Authorization").contains("Token=\"fixture-token\""))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }) { session ->
            val item = MediaItem("video", "Video", "Movie")
            val source = MediaSource("source")
            val stream = ResolvedPlayback(session.server.baseUrl + "/Videos/video/master.m3u8", emptyMap(), PlayMethod.TRANSCODE, source.id, true)
            val request = PlaybackRequest(item, PlaybackInfo(listOf(source), "play-own"), source, stream, 0, session)
            val api = JellyfinApi()
            runBlocking {
                api.stopTranscoding(request.copy(stream = stream.copy(trustedServer = false)))
                api.stopTranscoding(request.copy(stream = stream.copy(method = PlayMethod.DIRECT_PLAY)))
                api.stopTranscoding(request.copy(stream = stream.copy(method = PlayMethod.DIRECT_STREAM)))
                api.stopTranscoding(request.copy(info = request.info.copy(playSessionId = "  ")))
                assertEquals(0, requests.get())
                api.stopTranscoding(request)
            }
            assertEquals(1, requests.get())
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun withServer(route: (HttpExchange) -> Unit, block: (Session) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "pathfin-audio-profile-http-test").apply { isDaemon = true }
        }
        server.executor = executor
        server.createContext("/") { exchange ->
            try { route(exchange) } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching { exchange.sendResponseHeaders(500, -1); exchange.close() }
            }
        }
        server.start()
        try {
            block(Session(ServerConfig("http://127.0.0.1:" + server.address.port + "/jellyfin", true),
                "fixture-token", "user", "Alice", "device"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
            failure.get()?.let { throw AssertionError("Audio playback profile fixture assertion failed", it) }
        }
    }

    private fun HttpExchange.json(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}

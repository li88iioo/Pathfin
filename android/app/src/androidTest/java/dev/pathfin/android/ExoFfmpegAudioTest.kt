package dev.pathfin.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.playback.ffmpegAudioExtensionMode
import dev.pathfin.android.playback.unsupportedAudioMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真 JNI 解码非静音 PCM + 真 ExoPlayer 后备选择；不能用“类能加载”代替有声验证。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ExoFfmpegAudioTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val codecs = mapOf("ac3" to MimeTypes.AUDIO_AC3, "eac3" to MimeTypes.AUDIO_E_AC3,
        "dca" to MimeTypes.AUDIO_DTS, "truehd" to MimeTypes.AUDIO_TRUEHD)

    @Test fun decodesAc3Eac3DtsAndTruehdToNonSilentPcmAndDecodesAgainAfterReset() {
        assertTrue("必须实际加载 LGPL-only FFmpeg JNI", FfmpegLibrary.isAvailable())
        assertNotNull(FfmpegLibrary.getVersion())
        for ((name, mime) in codecs) {
            assertTrue("$name decoder 必须确实编译进共享库", FfmpegLibrary.supportsFormat(mime))
            val packets = packets(name)
            val decoder = FfmpegAudioDecoder(Format.Builder().setSampleMimeType(mime)
                .setSampleRate(48_000).setChannelCount(2).build(), 8, 8, 64 * 1024, false)
            try {
                assertTrue(decoder.name.contains(name))
                assertDecodedPcm(decoder, packets, name)
                decoder.flush()
                // TrueHD 在 reset 时重建 context；其他编码 flush 同一个 context。
                assertDecodedPcm(decoder, packets, "$name reset")
            } finally {
                decoder.release()
            }
        }
    }

    @Test fun mergedAc3PacketsPreserveEveryPcmByteAcrossBufferGrowthAndFlushForIntegerAndFloatOutput() {
        assertTrue("扩容回归要求真实 FFmpeg JNI", FfmpegLibrary.isAvailable())
        val original = packets("ac3")
        val framesPerPacket = 12
        val merged = original.chunked(framesPerPacket).map { frames ->
            val bytes = ByteArray(frames.sumOf { it.bytes.size })
            var offset = 0
            frames.forEach { frame ->
                frame.bytes.copyInto(bytes, offset)
                offset += frame.bytes.size
            }
            Packet(bytes, frames.first().timeUs)
        }
        assertTrue("必须包含至少一个完整合包", original.size >= framesPerPacket)
        for (floating in listOf(false, true)) {
            val sampleBytes = if (floating) 4 else 2
            val label = "ac3 merged/${if (floating) "float" else "s16"}"
            // 单包输出73728 / 147456字节，超过现有默认65535 / 131070容量。
            val mergedOutputBytes = framesPerPacket * 1536 * 2 * sampleBytes
            val decoder = FfmpegAudioDecoder(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setSampleRate(48_000).setChannelCount(2).build(), 8, 8, 64 * 1024, floating)
            try {
                val baseline = decodePcm(decoder, original, "$label original")
                assertEquals("分包必须解出完整素材", original.size * 1536 * 2 * sampleBytes, baseline.size)
                decoder.flush()
                val grouped = decodePcm(decoder, merged, label, mergedOutputBytes)
                assertArrayEquals("合包触发扩容后不能覆盖已写PCM、丢失帧或交付未写尾部", baseline, grouped)
                decoder.flush()
                val reset = decodePcm(decoder, merged, "$label reset", mergedOutputBytes)
                assertArrayEquals("flush后重复解码的完整PCM应一致", baseline, reset)
            } finally { decoder.release() }
        }
    }

    @Test fun actualExoplayerUsesFfmpegAndAdvancesAudioWhenPlatformCodecIsUnavailable() {
        assertTrue(FfmpegLibrary.isAvailable())
        for ((name, _) in codecs) {
            val file = File.createTempFile("pathfin-$name-", ".mka", instrumentation.targetContext.cacheDir)
            instrumentation.context.assets.open("ffmpeg-audio/$name.mka").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val ended = CountDownLatch(1)
            val audioAdvancing = AtomicBoolean()
            val decoderName = AtomicReference<String>()
            val failure = AtomicReference<PlaybackException>()
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                // Reproduce the failure class deterministically, regardless of OEM AC-3 support.
                val selector = MediaCodecSelector { mime, secure, tunneling ->
                    if (MimeTypes.isAudio(mime)) emptyList()
                    else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                }
                val renderers = DefaultRenderersFactory(instrumentation.targetContext)
                    .setMediaCodecSelector(selector)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(ffmpegAudioExtensionMode("AUTO"))
                player = ExoPlayer.Builder(instrumentation.targetContext, renderers).build()
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                        name: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        decoderName.set(name)
                    }
                    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime,
                        playoutStartSystemTimeMs: Long) { audioAdvancing.set(true) }
                })
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure.set(error); ended.countDown() }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) ended.countDown()
                    }
                })
                // Avoid changing device volume or emitting test tones; PCM is verified above.
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                player.prepare()
                player.play()
            }
            try {
                assertTrue("$name 播放应结束或报告明确错误", ended.await(15, TimeUnit.SECONDS))
                assertNull("$name 不应发生播放错误", failure.get())
                assertTrue("$name 必须实际选择 FFmpeg，当前为 ${decoderName.get()}",
                    decoderName.get()?.startsWith("ffmpeg") == true)
                assertTrue("$name 必须由真实 AudioSink 推进播放", audioAdvancing.get())
            } finally {
                instrumentation.runOnMainSync { player.release() }
                file.delete()
            }
        }
    }

    @Test fun multichannelAndHighRateDecodersPreserveEveryChannelThroughCompleteEosAndResetForIntegerAndFloatPcm() {
        assertTrue("多声道验证要求真实 FFmpeg JNI", FfmpegLibrary.isAvailable())
        verifyMultichannelFixtures { fixture ->
            assertTrue("${fixture.id} decoder 必须实际编译进共享库", FfmpegLibrary.supportsFormat(fixture.mime))
            val packets = readPackets(fixture.asset("${fixture.id}.packets"))
            assertEquals(fixture.packetCount, packets.size)
            val initialization = fixture.initialization?.let { listOf(fixture.asset(it)) }.orEmpty()
            for (floating in listOf(false, true)) {
                val label = "${fixture.id}/${if (floating) "float" else "s16"}"
                val format = Format.Builder().setSampleMimeType(fixture.mime).setSampleRate(fixture.rate)
                    .setChannelCount(fixture.channels).setInitializationData(initialization).build()
                val decoder = FfmpegAudioDecoder(format, 8, 8, 64 * 1024, floating)
                try {
                    assertTrue(decoder.name.contains(fixture.decoder))
                    val before = assertMultichannelPcm(decoder, packets, fixture, floating, label)
                    decoder.flush()
                    val after = assertMultichannelPcm(decoder, packets, fixture, floating, "$label reset")
                    assertEquals("$label reset 后不能丢帧或多输出旧缓冲", before.frames, after.frames)
                    assertArrayEquals("$label reset 后所有声道的完整 PCM 应一致", before.digest, after.digest)
                } finally { decoder.release() }
            }
        }
    }

    @Test fun realDefaultAudioSinkPlaysMultichannelAndHighRateAudioOnTheCurrentRouteAndRepresentativeSpeakerRoutes() {
        assertTrue("多声道实播要求真实 FFmpeg JNI", FfmpegLibrary.isAvailable())
        val context = instrumentation.targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val runs = JSONArray()
        val report = JSONObject().put("schemaVersion", 1)
            .put("availableDeviceTypes", JSONArray(devices.map { it.type }.distinct().sorted()))
            .put("runs", runs)
        try {
            verifyMultichannelFixtures { fixture ->
                val preferredRoutes = mutableListOf<AudioDeviceInfo?>(null)
                val representative = fixture.id in setOf("truehd-5_1-48k", "flac-7_1-96k")
                if (representative) {
                    checkNotNull(speaker) { "代表组合要求真实内置扬声器，当前路由不可用" }
                    preferredRoutes += speaker
                }
                val failures = mutableListOf<Throwable>()
                for (preferred in preferredRoutes) {
                    try { verifyAudioSinkRoute(fixture, preferred, runs) }
                    catch (failure: Throwable) { failures += failure }
                }
                if (failures.isNotEmpty()) throw AssertionError("${fixture.id} 输出路由验证失败").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        } finally {
            // 仅缓存自有测试数据和设备类型，不记录设备名、地址或其他标识。
            File(context.cacheDir, "audio-output-audit.json").writeText(report.toString(2))
        }
    }

    @Test fun softwarePrefersFfmpegAndUnsupportedAudioDoesNotRemainSilentlyIgnored() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, ffmpegAudioExtensionMode("SOFTWARE"))
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, ffmpegAudioExtensionMode("HARDWARE"))
        assertNull(unsupportedAudioMessage(Tracks.EMPTY))
        fun group(mime: String, support: Int) = Tracks.Group(TrackGroup(Format.Builder()
            .setSampleMimeType(mime).build()), false, intArrayOf(support), booleanArrayOf(false))
        val unsupported = group(MimeTypes.AUDIO_DTS, C.FORMAT_UNSUPPORTED_SUBTYPE)
        assertTrue(unsupportedAudioMessage(Tracks(listOf(unsupported)))!!.contains("DTS"))
        assertNull("用户禁用可解码音轨不应被误报为解码失败", unsupportedAudioMessage(Tracks(listOf(
            group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED)))))
        assertNull("有可用后备音轨不应阻断播放", unsupportedAudioMessage(Tracks(listOf(unsupported,
            group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED)))))
    }

    private fun packets(name: String): List<Packet> {
        val bytes = instrumentation.context.assets.open("ffmpeg-audio/$name.packets").use { it.readBytes() }
        return readPackets(bytes)
    }

    private fun readPackets(bytes: ByteArray): List<Packet> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return buildList {
            while (input.hasRemaining()) {
                val length = input.int
                val timeUs = input.long
                require(length in 1..input.remaining())
                add(Packet(ByteArray(length).also(input::get), timeUs))
            }
        }
    }

    private fun assertDecodedPcm(decoder: FfmpegAudioDecoder, packets: List<Packet>, label: String) {
        val pcm = ByteBuffer.wrap(decodePcm(decoder, packets, label)).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue("$label 必须产生至少一秒双声道 PCM", pcm.remaining() >= 48_000 * 2 * 2)
        var peak = 0
        while (pcm.remaining() >= 2) peak = maxOf(peak, kotlin.math.abs(pcm.short.toInt()))
        assertTrue("$label PCM 必须有实际正弦信号，不能全为零", peak > 1_000)
        assertEquals(48_000, decoder.sampleRate)
        assertEquals(2, decoder.channelCount)
    }

    private fun decodePcm(decoder: FfmpegAudioDecoder, packets: List<Packet>, label: String,
        minimumLargestBufferBytes: Int = 0): ByteArray {
        var next = 0
        var queuedEnd = false
        var ended = false
        var largestBufferBytes = 0
        val decoded = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!ended && SystemClock.elapsedRealtime() < deadline) {
            while (next < packets.size) {
                val input = decoder.dequeueInputBuffer() ?: break
                val packet = packets[next++]
                input.ensureSpaceForWrite(packet.bytes.size)
                requireNotNull(input.data).put(packet.bytes)
                input.timeUs = packet.timeUs
                input.flip()
                decoder.queueInputBuffer(input)
            }
            if (next == packets.size && !queuedEnd) {
                decoder.dequeueInputBuffer()?.let { input ->
                    input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    decoder.queueInputBuffer(input)
                    queuedEnd = true
                }
            }
            while (true) {
                val output = decoder.dequeueOutputBuffer() ?: break
                try {
                    if (output.isEndOfStream) ended = true
                    else output.data?.duplicate()?.let { pcm ->
                        largestBufferBytes = maxOf(largestBufferBytes, pcm.remaining())
                        decoded.write(ByteArray(pcm.remaining()).also(pcm::get))
                    }
                } finally { output.release() }
            }
            if (!ended) SystemClock.sleep(1)
        }
        assertTrue("$label decoder 应处理到 EOS", ended)
        assertEquals("$label 必须消费全部压缩数据包", packets.size, next)
        assertTrue("$label 必须实际产生足以触发扩容的单包输出，当前=$largestBufferBytes", largestBufferBytes >= minimumLargestBufferBytes)
        return decoded.toByteArray()
    }

    private fun assertMultichannelPcm(decoder: FfmpegAudioDecoder, packets: List<Packet>, fixture: MultichannelFixture,
        floating: Boolean, label: String): PcmEvidence {
        val pcm = ChannelEvidence(fixture, floating)
        var next = 0
        var queuedEnd = false
        var ended = false
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!ended && SystemClock.elapsedRealtime() < deadline) {
            while (next < packets.size) {
                val input = decoder.dequeueInputBuffer() ?: break
                val packet = packets[next++]
                input.ensureSpaceForWrite(packet.bytes.size)
                requireNotNull(input.data).put(packet.bytes)
                input.timeUs = packet.timeUs
                input.flip()
                decoder.queueInputBuffer(input)
            }
            if (next == packets.size && !queuedEnd) decoder.dequeueInputBuffer()?.let { input ->
                input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                decoder.queueInputBuffer(input)
                queuedEnd = true
            }
            while (true) {
                val output = decoder.dequeueOutputBuffer() ?: break
                try {
                    if (output.isEndOfStream) ended = true
                    else pcm.add(checkNotNull(output.data).duplicate().order(ByteOrder.LITTLE_ENDIAN))
                } finally { output.release() }
            }
            if (!ended) SystemClock.sleep(1)
        }
        assertTrue("$label 必须处理完整 EOS", ended)
        assertEquals(packets.size, next)
        assertEquals("$label 不能少解码、偷偷重采样或多输出旧帧", fixture.hostFrames, pcm.frames)
        assertEquals(fixture.rate, decoder.sampleRate)
        assertEquals(fixture.channels, decoder.channelCount)
        assertEquals(if (floating) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT, decoder.encoding)
        for (channel in 0 until fixture.channels) {
            val rms = sqrt(pcm.squares[channel] / pcm.frames)
            val coherence = hypot(pcm.projectionSin[channel], pcm.projectionCos[channel]) / pcm.frames / rms
            val channelLabel = "$label ${fixture.channelLabels[channel]}(${fixture.frequencies[channel]}Hz)"
            assertTrue("$channelLabel 不能静音，peak=${pcm.peaks[channel]}", pcm.peaks[channel] > .03)
            assertTrue("$channelLabel 必须持续输出有效声音，rms=$rms", rms > .015)
            assertTrue("$channelLabel 不能丢失、串位或复制其他声道，coherence=$coherence", coherence > .45)
        }
        Log.i("PathfinAudioVerification", "$label: frames=${pcm.frames}, channels=${fixture.channels}, rate=${fixture.rate}, EOS=true")
        return PcmEvidence(pcm.frames, pcm.digest.digest())
    }

    private fun verifyAudioSinkRoute(fixture: MultichannelFixture, preferred: AudioDeviceInfo?, runs: JSONArray) {
        val context = instrumentation.targetContext
        val result = JSONObject().put("fixture", fixture.id).put("inputChannels", fixture.channels)
            .put("inputSampleRate", fixture.rate).put("requestedDeviceType", preferred?.type ?: JSONObject.NULL)
            .put("status", "pending")
        runs.put(result)
        val ended = CountDownLatch(1)
        val reachedEos = AtomicBoolean()
        val audioAdvancing = AtomicBoolean()
        val decoderName = AtomicReference<String>()
        val inputFormat = AtomicReference<Format>()
        val audioTrack = AtomicReference<AudioTrack>()
        val failure = AtomicReference<PlaybackException>()
        val sinkErrors = CopyOnWriteArrayList<Exception>()
        val file = File.createTempFile("pathfin-${fixture.id}-", ".mka", context.cacheDir)
        var owner: ExoPlayer? = null
        var route: AudioDeviceInfo? = null
        var headFrames = 0L
        try {
            file.writeBytes(fixture.asset("${fixture.id}.mka"))
            instrumentation.runOnMainSync {
                // 只观察默认输出，保留设备原有能力、声道选择、AudioTrack 和混音行为。
                val provider = object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
                    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                        super.getAudioOutput(config).also { output ->
                            check(output is AudioTrackAudioOutput)
                            audioTrack.set(output.audioTrack)
                        }
                }
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioOutputProvider(provider).build()
                }.setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                    if (MimeTypes.isAudio(mime)) emptyList()
                    else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                }).setEnableDecoderFallback(true).setExtensionRendererMode(ffmpegAudioExtensionMode("AUTO"))
                val player = ExoPlayer.Builder(context, renderers).build()
                owner = player
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                        name: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoderName.set(name) }
                    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime,
                        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) { inputFormat.set(format) }
                    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime,
                        playoutStartSystemTimeMs: Long) { audioAdvancing.set(true) }
                    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                        sinkErrors += audioSinkError
                    }
                })
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure.set(error); ended.countDown() }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) { reachedEos.set(true); ended.countDown() }
                    }
                })
                // 仅静音当前测试播放器，不改系统音量；逐声道 PCM 在 JNI 用例独立核对。
                player.volume = 0f
                player.setPreferredAudioDevice(preferred)
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                player.prepare()
                player.play()
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (ended.count != 0L && SystemClock.elapsedRealtime() < deadline) {
                audioTrack.get()?.let { track ->
                    track.routedDevice?.let { route = it }
                    headFrames = maxOf(headFrames, track.playbackHeadPosition.toLong() and 0xffff_ffffL)
                }
                ended.await(20, TimeUnit.MILLISECONDS)
            }
            assertEquals("${fixture.id} AudioSink 必须完整播放到 EOS，format=${inputFormat.get()}", 0L, ended.count)
            failure.get()?.let { throw AssertionError("${fixture.id} 真实 AudioSink 播放失败", it) }
            if (sinkErrors.isNotEmpty()) throw AssertionError("${fixture.id} 不能忽略 AudioSink 声道/采样率故障", sinkErrors.first())
            assertTrue("${fixture.id} 必须实际选择 FFmpeg，当前=${decoderName.get()}", decoderName.get()?.startsWith("ffmpeg") == true)
            assertTrue("${fixture.id} 必须由 AudioSink 推进声音时间线", audioAdvancing.get())
            assertEquals(fixture.channels, inputFormat.get()?.channelCount)
            assertEquals(fixture.rate, inputFormat.get()?.sampleRate)
            assertNotNull("${fixture.id} 无实际 routedDevice，不能声称验证了输出路由", route)
            if (preferred != null) assertEquals("${fixture.id} 扬声器请求未被采用，本路由未覆盖", preferred.type, route?.type)
            val actualTrack = checkNotNull(audioTrack.get()) { "${fixture.id} 未创建实际 AudioTrack" }
            assertTrue("${fixture.id} AudioTrack 必须实际播放至少一秒，frames=$headFrames", headFrames >= actualTrack.sampleRate)
            instrumentation.runOnMainSync {
                val player = checkNotNull(owner)
                assertEquals(Player.STATE_ENDED, player.playbackState)
                assertTrue("${fixture.id} 播放位置不能跳过整个素材", player.currentPosition >= 1_800)
            }
            result.put("status", "passed")
            Log.i("PathfinAudioVerification", "${fixture.id}: decoder=${decoderName.get()}, input=${fixture.channels}ch/${fixture.rate}Hz," +
                " AudioTrack=${actualTrack.channelCount}ch/${actualTrack.sampleRate}Hz, deviceType=${route?.type}, headFrames=$headFrames, EOS=true")
        } catch (error: Throwable) {
            result.put("status", if (preferred != null && route?.type != preferred.type) "route_not_verified" else "failed")
                .put("error", error.javaClass.simpleName).put("message", error.message ?: "")
            throw error
        } finally {
            val track = audioTrack.get()
            result.put("decoder", decoderName.get() ?: JSONObject.NULL)
                .put("actualDeviceType", route?.type ?: JSONObject.NULL)
                .put("routeAdopted", if (preferred == null) JSONObject.NULL else route?.type == preferred.type)
                .put("audioTrackChannels", track?.channelCount ?: JSONObject.NULL)
                .put("audioTrackSampleRate", track?.sampleRate ?: JSONObject.NULL)
                .put("audioTrackEncoding", track?.audioFormat ?: JSONObject.NULL)
                .put("headFrames", headFrames).put("audioPositionAdvancing", audioAdvancing.get()).put("eos", reachedEos.get())
                .put("sinkErrors", JSONArray(sinkErrors.map { "${it.javaClass.simpleName}: ${it.message}" }))
            instrumentation.runOnMainSync { owner?.release() }
            file.delete()
        }
    }

    private fun verifyMultichannelFixtures(checkFixture: (MultichannelFixture) -> Unit) {
        val manifest = JSONObject(instrumentation.context.assets.open("multichannel-audio/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val fixtures = manifest.getJSONArray("fixtures")
        assertEquals("自有矩阵必须完整保留 5.1、多声道高采样率和 7.1 两档", 7, fixtures.length())
        val failures = mutableListOf<Throwable>()
        repeat(fixtures.length()) { index ->
            val item = fixtures.getJSONObject(index)
            val signals = item.getJSONArray("channelSignals")
            val fixture = MultichannelFixture(item.getString("id"), item.getString("decoder"), item.getString("mime"),
                item.getInt("sampleRate"), item.getInt("channels"), item.getInt("packets"), item.getLong("hostDecodedFrames"),
                if (item.isNull("initialization")) null else item.getString("initialization"), item.getJSONObject("files"),
                List(signals.length()) { signals.getJSONObject(it).getString("label") },
                DoubleArray(signals.length()) { signals.getJSONObject(it).getDouble("frequencyHz") })
            try { checkFixture(fixture) }
            catch (failure: Throwable) { failures += AssertionError("多声道组合 ${fixture.id} 验证失败", failure) }
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size}/7 个多声道组合失败；不能把降声道、无声或设备错误算通过").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private inner class MultichannelFixture(val id: String, val decoder: String, val mime: String, val rate: Int,
        val channels: Int, val packetCount: Int, val hostFrames: Long, val initialization: String?, val files: JSONObject,
        val channelLabels: List<String>, val frequencies: DoubleArray) {
        fun asset(name: String): ByteArray = instrumentation.context.assets.open("multichannel-audio/$name").use { it.readBytes() }.also { bytes ->
            val expected = files.getJSONObject(name)
            assertEquals("$name 大小必须匹配受核对的自有素材", expected.getInt("bytes"), bytes.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("$name SHA-256 不匹配", expected.getString("sha256"), digest)
        }
    }

    /** 在线计算每个交错声道的能量和独有正弦分量；不缓存整段 PCM。 */
    private class ChannelEvidence(fixture: MultichannelFixture, private val floating: Boolean) {
        private val channels = fixture.channels
        private val cosineStep = DoubleArray(channels) { cos(2 * PI * fixture.frequencies[it] / fixture.rate) }
        private val sineStep = DoubleArray(channels) { sin(2 * PI * fixture.frequencies[it] / fixture.rate) }
        private val sine = DoubleArray(channels)
        private val cosine = DoubleArray(channels) { 1.0 }
        val peaks = DoubleArray(channels)
        val squares = DoubleArray(channels)
        val projectionSin = DoubleArray(channels)
        val projectionCos = DoubleArray(channels)
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var frames = 0L
            private set

        fun add(buffer: ByteBuffer) {
            assertEquals("PCM 缓冲必须包含完整交错声道帧", 0, buffer.remaining() % (channels * if (floating) 4 else 2))
            digest.update(buffer.duplicate())
            while (buffer.hasRemaining()) {
                for (channel in 0 until channels) {
                    val value = if (floating) buffer.float.toDouble() else buffer.short.toDouble() / 32_768
                    assertTrue("PCM 不能含 NaN/Infinity", value.isFinite())
                    peaks[channel] = maxOf(peaks[channel], abs(value))
                    squares[channel] += value * value
                    projectionSin[channel] += value * sine[channel]
                    projectionCos[channel] += value * cosine[channel]
                    val nextSine = sine[channel] * cosineStep[channel] + cosine[channel] * sineStep[channel]
                    cosine[channel] = cosine[channel] * cosineStep[channel] - sine[channel] * sineStep[channel]
                    sine[channel] = nextSine
                }
                frames += 1
            }
        }
    }

    private data class PcmEvidence(val frames: Long, val digest: ByteArray)

    private data class Packet(val bytes: ByteArray, val timeUs: Long)
}

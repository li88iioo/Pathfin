package dev.pathfin.android.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink

private val AudioDeviceInfo.isSpeaker: Boolean
    get() = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
        (Build.VERSION.SDK_INT >= 30 && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)

internal fun mediaUsesSpeaker(context: Context): Boolean {
    val manager = context.getSystemService(AudioManager::class.java)
    val devices = if (Build.VERSION.SDK_INT >= 33) {
        manager.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
    } else {
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
            it.type != AudioDeviceInfo.TYPE_TELEPHONY && (Build.VERSION.SDK_INT < 31 || it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX) }
    }
    return devices.isNotEmpty() && devices.all { it.isSpeaker }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun hasPcmDecoder(context: Context, format: Format): Boolean {
    val mime = format.sampleMimeType ?: return false
    if (format.cryptoType != C.CRYPTO_TYPE_NONE || format.drmInitData != null) return false
    if (FfmpegLibrary.supportsFormat(mime)) return true
    return try {
        MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.isFormatSupported(context, format) }
    } catch (_: MediaCodecUtil.DecoderQueryException) { false }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackAudioSink(context: Context, processor: PlaybackPcmProcessor, enableAudioOutputPlaybackParameters: Boolean,
    outputProvider: AudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context).build(),
    onRouteChanged: ((Boolean) -> Unit)? = null, onPassthroughChanged: ((Boolean) -> Unit)? = null): AudioSink {
    val provider = object : ForwardingAudioOutputProvider(outputProvider) {
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
            val support = super.getFormatSupport(formatConfig)
            // 外接功放可直接解压缩帧。仅在外放且手机能自行解码时才改走 PCM 混音。
            if (processor.speakerOutput && formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW &&
                (support.supportLevel == AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY || support.isFormatSupportedForOffload) &&
                hasPcmDecoder(context, formatConfig.format)) return AudioOutputProvider.FormatSupport.Builder().build()
            return support
        }

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            val output = super.getAudioOutput(config)
            if (output !is AudioTrackAudioOutput || onRouteChanged == null) return output
            val track = output.audioTrack
            val handler = Handler(Looper.getMainLooper())
            return object : ForwardingAudioOutput(output) {
                @Volatile private var released = false
                private fun updateRoute() {
                    if (released) return
                    val devices = if (Build.VERSION.SDK_INT >= 36) track.routedDevices else listOfNotNull(track.routedDevice)
                    if (devices.isNotEmpty()) onRouteChanged(devices.all { it.isSpeaker })
                }
                private val listener = AudioRouting.OnRoutingChangedListener { updateRoute() }
                init { track.addOnRoutingChangedListener(listener, handler) }
                override fun play() { super.play(); handler.post(::updateRoute) }
                override fun release() {
                    released = true
                    track.removeOnRoutingChangedListener(listener)
                    super.release()
                }
            }
        }
    }
    val sink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf(processor))
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
        .setAudioOutputProvider(provider).build()
    return object : ForwardingAudioSink(sink) {
        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            val encoded = audioSinkConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW
            if (processor.encodedOutput != encoded) {
                processor.encodedOutput = encoded
                onPassthroughChanged?.invoke(encoded)
            }
            processor.channelMask = if (audioSinkConfig.outputChannelMapping == null) audioSinkConfig.format.channelMask else 0
            super.configure(audioSinkConfig)
        }
        override fun getAudioCapabilities() = (outputProvider as? AudioTrackAudioOutputProvider)?.audioCapabilities ?: super.getAudioCapabilities()
    }
}

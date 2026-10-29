package dev.pathfin.android.playback

import android.content.Context
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import dev.pathfin.android.domain.MediaSource

/** 自动选轨沿用 Media3 的语言策略，再补充服务器默认；手动覆盖和关闭仍由父类统一处理。 */
@UnstableApi
internal class PathfinTrackSelector(
    context: Context,
    private val source: MediaSource,
) : DefaultTrackSelector(context) {
    override fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val selected = super.selectAudioTrack(
            mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params,
        )
        if (selected.matchesPreferredLanguages(params.preferredAudioLanguages) ||
            params.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED) return selected
        val server = serverDefault(C.TRACK_TYPE_AUDIO, source.defaultAudioStreamIndex, mappedTrackInfo, rendererFormatSupports) ?: return selected
        // 由 Media3 自己检查设备声道和播放约束，不能让服务器默认绕过其平台能力判断。
        val serverSupports = Array(mappedTrackInfo.rendererCount) { renderer ->
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            Array(groups.length) { group ->
                IntArray(groups[group].length) { track ->
                    if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO ||
                        (renderer == server.second && mappedTrackInfo.getTrackGroups(renderer)[group] === server.first.group &&
                            track == server.first.tracks[0])) rendererFormatSupports[renderer][group][track]
                    else C.FORMAT_UNSUPPORTED_TYPE
                }
            }
        }
        return super.selectAudioTrack(mappedTrackInfo, serverSupports, rendererMixedMimeTypeAdaptationSupports,
            params.buildUpon().setExceedAudioConstraintsIfNecessary(false).build()) ?: selected
    }

    override fun selectTextTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        params: Parameters,
        selectedAudioLanguage: String?,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val selected = super.selectTextTrack(mappedTrackInfo, rendererFormatSupports, params, selectedAudioLanguage)
        if (selected.matchesPreferredLanguages(params.preferredTextLanguages) ||
            params.audioOffloadPreferences.audioOffloadMode == AUDIO_OFFLOAD_MODE_REQUIRED) return selected
        if (source.defaultSubtitleStreamIndex == -1) return null
        return serverDefault(C.TRACK_TYPE_TEXT, source.defaultSubtitleStreamIndex, mappedTrackInfo, rendererFormatSupports) ?: selected
    }

    private fun Pair<ExoTrackSelection.Definition, Int>?.matchesPreferredLanguages(languages: List<String>): Boolean =
        this != null && first.tracks.any { track ->
            languages.any { language -> getFormatLanguageScore(first.group.getFormat(track), language, false) > 0 }
        }

    private fun serverDefault(
        type: Int,
        streamIndex: Int?,
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        if (streamIndex == null || streamIndex < 0) return null
        var selected: Pair<ExoTrackSelection.Definition, Int>? = null
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != type) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (track in 0 until group.length) {
                    if (RendererCapabilities.getFormatSupport(rendererFormatSupports[renderer][groupIndex][track]) != C.FORMAT_HANDLED) continue
                    val format = group.getFormat(track)
                    if (serverTrackIndex(source, type, format) != streamIndex) continue
                    // 两个实际轨道对应同一服务器索引时也不能猜选其中一个。
                    if (selected != null) return null
                    selected = Pair.create(ExoTrackSelection.Definition(group, track), renderer)
                }
            }
        }
        return selected
    }
}

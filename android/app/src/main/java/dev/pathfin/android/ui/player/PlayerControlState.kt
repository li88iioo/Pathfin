package dev.pathfin.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.playback.pathfinReleased
import java.util.Locale
import kotlinx.coroutines.delay

/** 只在控制层可见时轮询位置；视频和父页面不订阅这个高频状态。 */
@UnstableApi
@Composable
internal fun rememberPlayerControlSnapshot(player: Player?): PlayerControlSnapshot {
    var snapshot by remember(player) { mutableStateOf(capture(player)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot = capture(player)
            }
        }
        if (player != null && !player.pathfinReleased()) player.addListener(listener)
        onDispose { if (player != null && !player.pathfinReleased()) player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (player != null && !player.pathfinReleased()) {
            snapshot = capture(player)
            delay(250)
        }
    }
    return snapshot
}

@UnstableApi
internal data class PlayerControlSnapshot(
    val displayTitle: String = "",
    val container: String? = null,
    val sourceBytes: Long? = null,
    val sourceBitrate: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val speed: Float = 1f,
    val playWhenReady: Boolean = false,
    val canSeek: Boolean = false,
    val canSeekBack: Boolean = false,
    val canSeekForward: Boolean = false,
    val canChangeSpeed: Boolean = false,
    val tracks: Tracks = Tracks.EMPTY,
    val selection: TrackSelectionParameters = TrackSelectionParameters.DEFAULT,
    val videoFormat: Format? = null,
    val audioFormat: Format? = null,
)

@UnstableApi
private fun capture(player: Player?): PlayerControlSnapshot {
    if (player == null || player.pathfinReleased()) return PlayerControlSnapshot()
    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
    val tracks = player.currentTracks
    return PlayerControlSnapshot(
        displayTitle = (player.mediaMetadata.displayTitle ?: player.mediaMetadata.title)?.toString().orEmpty(),
        container = player.mediaMetadata.extras?.getString("pathfin.container"),
        sourceBytes = player.mediaMetadata.extras?.getLong("pathfin.sourceBytes")?.takeIf { it > 0 },
        sourceBitrate = player.mediaMetadata.extras?.getInt("pathfin.sourceBitrate")?.takeIf { it > 0 },
        positionMs = player.currentPosition.coerceAtLeast(0),
        durationMs = duration,
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
        speed = player.playbackParameters.speed,
        playWhenReady = player.playWhenReady,
        canSeek = duration > 0 && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        canSeekBack = player.isCommandAvailable(Player.COMMAND_SEEK_BACK),
        canSeekForward = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD),
        canChangeSpeed = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
        tracks = tracks,
        selection = player.trackSelectionParameters,
        videoFormat = selectedFormat(tracks, C.TRACK_TYPE_VIDEO),
        audioFormat = selectedFormat(tracks, C.TRACK_TYPE_AUDIO),
    )
}

@UnstableApi
internal data class PlayerTrackChoice(
    val id: String,
    val label: String,
    val group: TrackGroup,
    val index: Int,
    val supported: Boolean,
    val selected: Boolean,
)

@UnstableApi
internal fun trackChoices(tracks: Tracks, type: Int): List<PlayerTrackChoice> = buildList {
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type == type) repeat(group.length) { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            add(PlayerTrackChoice(
                id = "$groupIndex:$trackIndex",
                label = trackLabel(format, type, size + 1),
                group = group.mediaTrackGroup,
                index = trackIndex,
                supported = group.isTrackSupported(trackIndex),
                selected = group.isTrackSelected(trackIndex),
            ))
        }
    }
}

/** 只覆盖用户正在选择的轨道类型，保留另一类型的选择与用户语言偏好。 */
@UnstableApi
internal fun selectPlayerTrack(player: Player, type: Int, choice: PlayerTrackChoice?, disabled: Boolean = false) {
    if (player.pathfinReleased() || !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
    val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, disabled)
    if (choice != null && choice.supported) builder.setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
    player.trackSelectionParameters = builder.build()
}

@UnstableApi
private fun selectedFormat(tracks: Tracks, type: Int): Format? {
    tracks.groups.forEach { group ->
        if (group.type == type) repeat(group.length) { index ->
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

@UnstableApi
private fun trackLabel(format: Format, type: Int, number: Int): String {
    val parts = mutableListOf<String>()
    format.label?.takeIf(String::isNotBlank)?.let(parts::add)
    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it.replace('_', '-')).getDisplayName(Locale.SIMPLIFIED_CHINESE) }
        ?.takeIf(String::isNotBlank)
    if (language != null && parts.none { it.contains(language, ignoreCase = true) }) parts += language
    if (type == C.TRACK_TYPE_AUDIO) {
        format.sampleMimeType?.let(::codecLabel)?.let(parts::add)
        when (format.channelCount) {
            1 -> parts += tr("单声道")
            2 -> parts += tr("立体声")
            in 3..32 -> parts += tr("%1\$s 声道", format.channelCount)
        }
    } else if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += tr("强制字幕")
    return parts.distinct().joinToString(" · ").ifBlank { if (type == C.TRACK_TYPE_AUDIO) tr("音轨 %1\$s", number) else tr("字幕 %1\$s", number) }
}

internal fun codecLabel(mime: String): String = when (mime) {
    "audio/mp4a-latm" -> "AAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
    "audio/raw" -> "PCM"
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/av01" -> "AV1"
    else -> mime.substringAfter('/').uppercase(Locale.ROOT)
}

internal fun playerTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

internal fun playerSpeed(speed: Float): String = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

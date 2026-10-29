package dev.pathfin.android.playback

import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.ServerUrls
import java.util.Locale

internal enum class RememberedTrackType { AUDIO, SUBTITLE }

/** 仅保存媒体内稳定描述；不能携带上一集的轨道编号、TrackGroup 或播放器对象。 */
@ConsistentCopyVisibility
internal data class StableTrackIdentity private constructor(
    val language: String?,
    val label: String?,
    val roleFlags: Int,
    val forced: Boolean,
) {
    companion object {
        fun create(language: String?, label: String?, roleFlags: Int, forced: Boolean): StableTrackIdentity? {
            val normalizedLanguage = language?.trim()?.replace('_', '-')?.takeIf { it.length in 1..64 }
                ?.let { tag ->
                    val locale = Locale.forLanguageTag(tag)
                    val root = locale.language.takeIf { it.isNotBlank() && it != "und" } ?: return@let null
                    val iso = runCatching { locale.getISO3Language() }.getOrNull()?.takeIf(String::isNotBlank) ?: root
                    buildString {
                        append(iso.lowercase(Locale.ROOT))
                        if (locale.script.isNotBlank()) { append('-'); append(locale.script.lowercase(Locale.ROOT)) }
                        if (locale.country.isNotBlank()) { append('-'); append(locale.country.lowercase(Locale.ROOT)) }
                    }
                }
            val normalizedLabel = label?.trim()?.takeIf { it.isNotBlank() && it.length <= 512 }
                ?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT)
            if (normalizedLanguage == null && normalizedLabel == null) return null
            return StableTrackIdentity(normalizedLanguage, normalizedLabel, roleFlags, forced)
        }
    }
}

internal sealed interface RememberedTrack {
    data class Selected(val identity: StableTrackIdentity) : RememberedTrack
    data object Disabled : RememberedTrack
}

/** 自动选择就是清除该类型的手动记忆；关字幕与未作选择必须区分。 */
internal data class ManualTrackSelections(
    val audio: RememberedTrack? = null,
    val subtitle: RememberedTrack? = null,
) {
    operator fun get(type: RememberedTrackType): RememberedTrack? = when (type) {
        RememberedTrackType.AUDIO -> audio
        RememberedTrackType.SUBTITLE -> subtitle
    }

    fun with(type: RememberedTrackType, value: RememberedTrack?): ManualTrackSelections = when (type) {
        RememberedTrackType.AUDIO -> copy(audio = value.takeUnless { it == RememberedTrack.Disabled })
        RememberedTrackType.SUBTITLE -> copy(subtitle = value)
    }
}

/** 返回的是新媒体候选列表位置；只有唯一完全匹配才可生成本集临时 override。 */
internal fun uniqueRememberedTrackIndex(identity: StableTrackIdentity, candidates: List<StableTrackIdentity?>): Int? =
    candidates.indices.filter { candidates[it] == identity }.singleOrNull()

/** 自然结束的单次交接只需身份键，不保留 Session、令牌、播放地址或旧播放器。 */
internal data class TrackContinuityIdentity(
    val itemId: String,
    val seriesId: String,
    val profileId: String?,
    val userId: String,
    val serverId: String,
    val serverBase: String,
)

internal fun trackContinuityIdentity(request: PlaybackRequest): TrackContinuityIdentity? {
    if (request.item.type != "Episode" || request.item.id.isBlank() || request.session.userId.isBlank()) return null
    val series = request.item.seriesId?.takeIf(String::isNotBlank) ?: return null
    val server = runCatching { ServerUrls.normalize(request.session.server).baseUrl }.getOrNull() ?: return null
    return TrackContinuityIdentity(request.item.id, series, request.profileId?.takeIf(String::isNotBlank),
        request.session.userId, request.session.serverId, server)
}

internal fun canInheritManualTracks(previous: PlaybackRequest, next: PlaybackRequest, enabled: Boolean): Boolean =
    trackContinuityIdentity(previous)?.let { canInheritManualTracks(it, next, enabled) } ?: false

internal fun canInheritManualTracks(previous: TrackContinuityIdentity, next: PlaybackRequest, enabled: Boolean): Boolean {
    if (!enabled) return false
    val target = trackContinuityIdentity(next) ?: return false
    return previous.itemId != target.itemId && previous.copy(itemId = target.itemId) == target
}

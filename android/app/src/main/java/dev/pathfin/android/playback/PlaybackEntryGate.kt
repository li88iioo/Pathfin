package dev.pathfin.android.playback

import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.Session

/** 结束后的入口也保留幂等性；旋转重送入口不能自动重播，显式退出才清除此状态。 */
internal class PlaybackEntryGate {
    private var completedEntries: Set<EntryIdentity> = emptySet()

    fun canOpen(request: PlaybackRequest): Boolean = identity(request) !in completedEntries

    fun completed(originalRequest: PlaybackRequest, currentRequest: PlaybackRequest = originalRequest) {
        completedEntries = setOf(identity(originalRequest), identity(currentRequest))
    }

    fun reset() {
        completedEntries = emptySet()
    }

    companion object {
        fun sameEntry(first: PlaybackRequest, second: PlaybackRequest): Boolean = identity(first) == identity(second)

        private fun identity(request: PlaybackRequest) = EntryIdentity(
            request.session,
            request.info.playSessionId,
            request.item.id,
            request.source.id,
        )
    }

    private data class EntryIdentity(val session: Session, val playSessionId: String, val itemId: String, val sourceId: String)
}

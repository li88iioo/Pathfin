package dev.pathfin.android.playback

import dev.pathfin.android.domain.PlaybackRequest
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** 事件只有这一份 FIFO；通知只唤醒消费者，不承载或丢弃任何控制事件。 */
internal class PlaybackReportQueue {
    private val lock = Any()
    private val pending = ArrayDeque<ReportMessage>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    fun offer(message: ReportMessage): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (message is ReportMessage.Playback && (message.report.event == "progress" || message.report.event == "stop")) {
            val tail = pending.descendingIterator()
            while (tail.hasNext()) {
                val previous = tail.next() as? ReportMessage.Playback ?: break
                if (previous.report.event != "progress") break
                if (PlaybackEntryGate.sameEntry(previous.request, message.request)) tail.remove()
            }
        }
        pending.addLast(message)
        available.trySend(Unit)
        true
    }

    /** 取出后即为在途事件；后续进度不能再替换它。关闭后继续取完已接受的事件。 */
    suspend fun next(): ReportMessage? {
        while (true) {
            synchronized(lock) {
                pending.pollFirst()?.let { return it }
                if (closed) return null
            }
            available.receiveCatching()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            available.close()
        }
    }

    /** 消费者异常终止时释放等待器；正常关闭由消费者先排空全部上报。 */
    fun completePendingBarriers() {
        val barriers = synchronized(lock) {
            pending.filterIsInstance<ReportMessage.Barrier>().also { pending.clear() }
        }
        barriers.forEach { it.completion.complete(Unit) }
    }
}

internal sealed interface ReportMessage {
    data class Playback(val request: PlaybackRequest, val report: PlaybackReport) : ReportMessage
    data class ReleaseTranscode(val request: PlaybackRequest) : ReportMessage
    data class Barrier(val completion: CompletableDeferred<Unit>) : ReportMessage
}

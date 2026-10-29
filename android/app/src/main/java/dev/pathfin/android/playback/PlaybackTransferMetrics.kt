package dev.pathfin.android.playback

import java.util.Locale
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** 只统计当前媒体响应体已读取的真实字节；不发请求，也不持有 URL 或认证信息。 */
internal class PlaybackTransferMetrics(private val clockNanos: () -> Long = System::nanoTime) {
    private var totalBytes = 0L
    private var firstReadNanos: Long? = null
    private var sampledAtNanos: Long? = null
    private var sampledBytes = 0L

    fun wrap(body: ResponseBody): ResponseBody = object : ResponseBody() {
        private val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) record(it)
            }
        }.buffer()
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source(): BufferedSource = counted
    }

    @Synchronized private fun record(bytes: Long) {
        if (firstReadNanos == null) firstReadNanos = clockNanos()
        totalBytes = if (bytes > Long.MAX_VALUE - totalBytes) Long.MAX_VALUE else totalBytes + bytes
    }

    /** 首次读取前或时间窗不足时返回 null；已开始的有效窗口可返回实际 0，不沿用旧速率。 */
    @Synchronized fun sample(): PlaybackTransferSample? {
        val start = sampledAtNanos ?: firstReadNanos ?: return null
        val now = clockNanos()
        val elapsed = now - start
        if (elapsed < MIN_WINDOW_NANOS) return null
        val bytes = totalBytes - sampledBytes
        sampledAtNanos = now
        sampledBytes = totalBytes
        val rate = (bytes.toDouble() * 1_000_000_000.0 / elapsed).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        return PlaybackTransferSample(rate, bytes, elapsed)
    }

    @Synchronized fun reset() {
        totalBytes = 0
        firstReadNanos = null
        sampledAtNanos = null
        sampledBytes = 0
    }

    private companion object { const val MIN_WINDOW_NANOS = 250_000_000L }
}

internal data class PlaybackTransferSample(val bytesPerSecond: Long, val bytesRead: Long, val elapsedNanos: Long)

internal fun formatPlaybackTransferRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MiB/s", bytesPerSecond / 1_048_576.0)
    bytesPerSecond >= 1_024 -> String.format(Locale.ROOT, "%.1f KiB/s", bytesPerSecond / 1_024.0)
    else -> "${bytesPerSecond.coerceAtLeast(0)} B/s"
}

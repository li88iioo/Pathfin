package dev.pathfin.ass

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Bounded before Media3 SubtitleExtractor allocates its Content-Length-sized input buffer. */
@UnstableApi
internal class AssSubtitleDataSource private constructor(
    private val delegate: DataSource,
    private val exactAssUris: Set<String>,
    private val onRejected: (() -> Unit)?,
) : DataSource {
    private var limited = false
    private var remaining = LIMIT
    private var rejectionReported = false
    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun open(dataSpec: DataSpec): Long {
        limited = dataSpec.uri.toString() in exactAssUris
        rejectionReported = false
        remaining = LIMIT - dataSpec.position
        if (limited && (dataSpec.position < 0 || remaining < 0 ||
                    dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length > remaining)) reject()
        return try {
            val length = delegate.open(dataSpec)
            if (limited && length > remaining) reject()
            length
        } catch (error: AssSizeLimitException) {
            reportRejection()
            throw error
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!limited || length == 0) return delegate.read(buffer, offset, length)
        return try {
            if (remaining == 0L) {
                // An unknown-length response may end exactly at the limit; probe at most one byte.
                val extra = delegate.read(ByteArray(1), 0, 1)
                if (extra == C.RESULT_END_OF_INPUT) return extra
                if (extra > 0) reject()
                return 0
            }
            val read = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            read
        } catch (error: AssSizeLimitException) {
            reportRejection()
            throw error
        }
    }
    override fun close() { limited = false; delegate.close() }
    private fun reject(): Nothing {
        runCatching { delegate.close() }
        reportRejection()
        throw AssSizeLimitException()
    }
    private fun reportRejection() {
        if (!rejectionReported) { rejectionReported = true; onRejected?.invoke() }
    }
    private class AssSizeLimitException : IOException("ASS 字幕文件超过安全大小限制，未加载该字幕")
    companion object {
        const val LIMIT = 8L * 1024 * 1024
        fun factory(delegate: DataSource.Factory, exactAssUris: Set<String>, onRejected: (() -> Unit)?): DataSource.Factory {
            if (exactAssUris.isEmpty()) return delegate
            val frozen = exactAssUris.toSet()
            return DataSource.Factory { AssSubtitleDataSource(delegate.createDataSource(), frozen, onRejected) }
        }
    }
}

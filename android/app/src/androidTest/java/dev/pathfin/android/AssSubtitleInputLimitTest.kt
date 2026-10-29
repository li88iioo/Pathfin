package dev.pathfin.android

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathfin.ass.ExoAssExtension
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Probe a declared 2GiB body without allocating it; cap unknown/chunked input before parser IO. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AssSubtitleInputLimitTest {
    @Test fun enormousDeclaredAssLengthIsRejectedBeforeAnyBodyReadOrParserAllocation() {
        assertDeclaredLengthRejected("https://fixture.invalid/subtitle?opaque=1")
    }

    @Test fun enormousDeclaredSrtAndVttLengthsAreRejectedBeforeAnyBodyReadOrParserAllocation() {
        for (extension in listOf("srt", "vtt"))
            assertDeclaredLengthRejected("https://fixture.invalid/subtitle.$extension?opaque=1")
    }

    private fun assertDeclaredLengthRejected(uri: String) {
        val upstream = SyntheticSource(2L * 1024 * 1024 * 1024)
        val rejected = AtomicInteger()
        val source = ExoAssExtension.boundedSubtitleDataSource(DataSource.Factory { upstream }, setOf(uri), rejected::incrementAndGet).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(uri)))
            fail("A 2GiB Content-Length must not reach SubtitleExtractor's allocation")
        } catch (expected: IOException) {
            assertFalse(expected.message.orEmpty().contains(uri))
        }
        assertEquals(0, upstream.reads)
        assertTrue(upstream.closed)
        assertEquals(1, rejected.get())
    }

    @Test fun unknownLengthAssCanReadAtMostEightMiBAndOneBoundedEofProbe() {
        assertUnknownLengthRejected("https://fixture.invalid/opaque-media-subtitle")
    }

    @Test fun unknownLengthSrtAndVttCanReadAtMostEightMiBAndOneBoundedEofProbe() {
        for (extension in listOf("srt", "vtt"))
            assertUnknownLengthRejected("https://fixture.invalid/subtitle.$extension")
    }

    private fun assertUnknownLengthRejected(uri: String) {
        val upstream = SyntheticSource(C.LENGTH_UNSET.toLong())
        val rejected = AtomicInteger()
        val source = ExoAssExtension.boundedSubtitleDataSource(DataSource.Factory { upstream }, setOf(uri), rejected::incrementAndGet).createDataSource()
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(Uri.parse(uri))))
        val buffer = ByteArray(64 * 1024)
        var failure: IOException? = null
        try {
            // Two extra calls suffice to observe the one-byte EOF probe; a broken cap must fail,
            // rather than making the test itself read an infinite response forever.
            repeat(8 * 1024 * 1024 / buffer.size + 2) {
                assertNotEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
            }
        } catch (expected: IOException) { failure = expected }
        assertNotNull("An endless response must be rejected, never buffered forever", failure)
        assertFalse(failure?.message.orEmpty().contains(uri))
        assertEquals(8L * 1024 * 1024 + 1, upstream.consumed)
        assertTrue(upstream.maxRead <= buffer.size)
        assertTrue(upstream.closed)
        assertEquals(1, rejected.get())
    }

    @Test fun explicitUriMatchingPreservesVideoOtherSubtitlesHeadersAndDataSpec() {
        val upstream = SyntheticSource(2L * 1024 * 1024 * 1024)
        val ass = "https://fixture.invalid/text"
        val source = ExoAssExtension.boundedSubtitleDataSource(DataSource.Factory { upstream }, setOf(ass)).createDataSource()
        val spec = DataSpec.Builder().setUri("https://fixture.invalid/movie.ass")
            .setHttpRequestHeaders(mapOf("X-Fixture" to "retained" )).build()
        assertEquals(2L * 1024 * 1024 * 1024, source.open(spec))
        assertSame("Do not rebuild or strip the controlled request/auth headers", spec, upstream.lastSpec)
        assertEquals("retained", upstream.lastSpec?.httpRequestHeaders?.get("X-Fixture"))
        assertEquals(mapOf("X-Fixture-Response" to listOf("retained")), source.responseHeaders)
        source.close()
    }

    @Test fun sameBoundAppliesToBasicSsaPipelineAndRangesCannotBypassIt() {
        val upstream = SyntheticSource(1)
        val uri = "https://fixture.invalid/subtitle"
        val source = ExoAssExtension.boundedSubtitleDataSource(DataSource.Factory { upstream }, setOf(uri)).createDataSource()
        // This pure wrapper does not construct an extension or initialize any native library.
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(8L * 1024 * 1024 + 1).build())
            fail("A late byte range cannot evade the file bound")
        } catch (_: IOException) { }
        assertNull(upstream.lastSpec)
    }

    private class SyntheticSource(private val advertisedLength: Long) : DataSource {
        var reads = 0
        var maxRead = 0
        var consumed = 0L
        var closed = false
        var lastSpec: DataSpec? = null
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long { lastSpec = dataSpec; return advertisedLength }
        override fun getUri(): Uri? = lastSpec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("X-Fixture-Response" to listOf("retained"))
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++; maxRead = maxOf(maxRead, length); consumed += length
            buffer.fill(0, offset, offset + length)
            return length
        }
        override fun close() { closed = true }
    }
}

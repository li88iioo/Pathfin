package dev.pathfin.android.download

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DownloadSizeFormatTest {
    private val previous = Locale.getDefault()
    @Before fun fixLocale() = Locale.setDefault(Locale.ROOT)
    @After fun restoreLocale() = Locale.setDefault(previous)

    @Test fun eachUnitStartsExactlyAtItsBoundary() {
        assertEquals("0 B", formatDownloadSize(0))
        assertEquals("1023 B", formatDownloadSize(1023))
        assertEquals("1 KB", formatDownloadSize(1024))
        assertEquals("1024 KB", formatDownloadSize(1024L * 1024 - 1))
        assertEquals("1.0 MB", formatDownloadSize(1024L * 1024))
        assertEquals("1.00 GB", formatDownloadSize(1024L * 1024 * 1024))
        assertEquals("3.50 GB", formatDownloadSize((3.5 * 1024 * 1024 * 1024).toLong()))
    }
}

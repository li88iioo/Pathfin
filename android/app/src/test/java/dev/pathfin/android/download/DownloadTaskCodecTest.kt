package dev.pathfin.android.download

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.pathfin.android.domain.DownloadNetwork

class DownloadTaskCodecTest {
    @Test fun persistedTaskContainsOnlyIdentifiersDestinationAndProgressWith64BitSizes() {
        val task = DownloadTask("task", "work", "profile", "item", "Movie", "content://documents/file/42",
            sourceId = "source", status = DownloadStatus.DOWNLOADING, downloadedBytes = 5_000_000_000L,
            totalBytes = 9_000_000_000L, fileName = "Movie.mkv", mimeType = "video/x-matroska",
            createdAt = 10)
        val encoded = DownloadTaskCodec.encode(listOf(task))
        assertEquals(task, DownloadTaskCodec.decode(encoded).single())
        val fields = JSONArray(encoded).getJSONObject(0).keys().asSequence().toSet()
        assertFalse(fields.any { it.equals("url", true) || it.contains("token", true) || it.contains("header", true) })
        assertTrue(task.progress!! > 0.5f)
    }

    @Test fun cancelledAndCompletedTasksSurviveReloadWithoutBecomingQueued() {
        val cancelled = DownloadTask("cancelled", "work1", "profile", "item", "Movie", "content://documents/file/1",
            status = DownloadStatus.CANCELLED, error = "已取消")
        val completed = DownloadTask("complete", "work2", "profile2", "item2", "Other", "content://documents/file/2",
            status = DownloadStatus.COMPLETED, downloadedBytes = 1234, totalBytes = 1234)
        assertEquals(listOf(cancelled, completed), DownloadTaskCodec.decode(DownloadTaskCodec.encode(listOf(cancelled, completed))))
    }

    @Test fun taskReloadDoesNotPreserveAnObsoleteNetworkPolicy() {
        val task = DownloadTask("task", "work", "profile", "item", "Movie", "content://documents/file/42")
        val legacy = JSONArray(DownloadTaskCodec.encode(listOf(task))).apply { getJSONObject(0).put("network", "ANY").put("updatedAt", 42) }
        val restored = DownloadTaskCodec.decode(legacy.toString()).single()
        assertEquals(task, restored)
        assertFalse(JSONArray(DownloadTaskCodec.encode(listOf(restored))).getJSONObject(0).has("network"))
        assertFalse(JSONArray(DownloadTaskCodec.encode(listOf(restored))).getJSONObject(0).has("updatedAt"))
        assertTrue(restored.queueDescription(DownloadNetwork.UNMETERED).contains("不计费"))
        assertFalse(restored.queueDescription(DownloadNetwork.UNMETERED).contains("Wi-Fi"))
    }

    @Test fun duplicateNamesKeepTheirExtensionAndNeverChooseAnOccupiedName() {
        assertEquals("Movie (3).mkv", uniqueDownloadName("Movie.mkv", setOf("movie.MKV", "Movie (1).mkv", "Movie (2).mkv")))
        assertEquals("Movie (1)", uniqueDownloadName("Movie", setOf("Movie")))
        assertEquals("New.mp4", uniqueDownloadName("New.mp4", setOf("Old.mp4")))
    }
}

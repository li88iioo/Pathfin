package dev.pathfin.android.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import dev.pathfin.android.domain.DownloadNetwork
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun prepare() {
        check(context.packageName == "dev.pathfin.android.verification") { "下载持久化回归只允许 verification 独立测试包" }
        clear()
    }
    @After fun finish() { if (context.packageName == "dev.pathfin.android.verification") clear() }

    @Test fun newStoreRestoresTaskAndRejectsLateUpdatesFromThePreviousRetry() {
        val task = DownloadTask("task", "old-work", "profile", "item", "Movie", "content://documents/42")
        DownloadStore(context).insert(task)
        assertEquals(task, DownloadStore(context).get(task.id))
        DownloadStore(context).update(task.id, task.workId) {
            it.copy(workId = "retry-work", status = DownloadStatus.DOWNLOADING, downloadedBytes = 8192)
        }
        val late = DownloadStore(context).update(task.id, task.workId) {
            error("旧 Worker 不得触及新代次")
        }
        assertNull(late)
        assertEquals("retry-work", DownloadStore(context).get(task.id)?.workId)
        assertEquals(8192L, DownloadStore(context).get(task.id)?.downloadedBytes)
    }

    @Test fun removingAnotherAccountRecordPreservesTheCompletedFileReference() {
        val complete = DownloadTask("complete", "work1", "account1", "item", "Movie", "content://documents/1",
            status = DownloadStatus.COMPLETED, downloadedBytes = 20, totalBytes = 20)
        val other = DownloadTask("other", "work2", "account2", "item", "Other", "content://documents/2")
        val store = DownloadStore(context)
        store.insert(complete); store.insert(other)
        DownloadStore(context).remove(other.id)
        assertEquals(complete, DownloadStore(context).all().single())
    }

    @Test fun cancellingRemovedProfileIsAtomicAndPreservesOtherAccountsAndCompletedFiles() = runBlocking {
        val store = DownloadStore(context)
        fun task(id: String, profile: String, status: DownloadStatus) = DownloadTask(id, UUID.randomUUID().toString(),
            profile, "item", "Synthetic", "content://documents/$id", status = status, downloadedBytes = 4096)
        val queued = task("queued", "removed", DownloadStatus.QUEUED)
        val running = task("running", "removed", DownloadStatus.DOWNLOADING)
        val completed = task("completed", "removed", DownloadStatus.COMPLETED)
        val other = task("other", "retained", DownloadStatus.DOWNLOADING)
        listOf(queued, running, completed, other).forEach(store::insert)

        MediaDownloads.cancelProfile(context, "removed")
        val restored = DownloadStore(context)
        assertEquals(DownloadStatus.CANCELLED, restored.get(queued.id)?.status)
        assertEquals(DownloadStatus.CANCELLED, restored.get(running.id)?.status)
        assertEquals(completed, restored.get(completed.id))
        assertEquals(other, restored.get(other.id))
        assertEquals(4, restored.all().size)
        MediaDownloads.cancelProfile(context, "removed")
        assertEquals("重复取消不能改动保留账号", other, restored.get(other.id))
    }

    @Test fun networkChangeReplacesOnlyActiveGenerationsAndLateProgressCannotOverwriteThem() {
        val active = DownloadTask("active", "old-work", "profile", "item", "Movie", "content://documents/active",
            status = DownloadStatus.DOWNLOADING, downloadedBytes = 4096)
        val completed = active.copy(id = "completed", workId = "complete-work", destinationUri = "content://documents/completed",
            status = DownloadStatus.COMPLETED)
        val cancelled = active.copy(id = "cancelled", workId = "cancel-work", destinationUri = "content://documents/cancelled",
            status = DownloadStatus.CANCELLED)
        val store = DownloadStore(context)
        listOf(active, completed, cancelled).forEach(store::insert)
        val (previous, queued) = store.replaceNetwork(DownloadNetwork.UNMETERED).single()
        assertEquals(active, previous)
        assertNotEquals(active.workId, queued.workId)
        assertEquals(DownloadNetwork.UNMETERED, queued.network)
        assertEquals(DownloadStatus.QUEUED, queued.status)
        assertEquals(0L, queued.downloadedBytes)
        assertNull(store.update(active.id, active.workId) { it.copy(status = DownloadStatus.COMPLETED) })
        assertEquals(queued, DownloadStore(context).get(queued.id))
        assertEquals(completed, store.get(completed.id))
        assertEquals(cancelled, store.get(cancelled.id))
        assertTrue("相同规则不能重启已排队任务", store.replaceNetwork(DownloadNetwork.UNMETERED).isEmpty())
        val stopped = store.cancel(active.id)
        assertEquals("取消必须作用于重排后的代次", queued.workId, stopped?.workId)
        assertEquals(DownloadStatus.CANCELLED, stopped?.status)
        assertTrue("被取消的任务不能被下一次规则变化复活", store.replaceNetwork(DownloadNetwork.ANY).isEmpty())
    }

    private fun clear() { check(context.getSharedPreferences("pathfin_downloads_v1", Context.MODE_PRIVATE).edit().clear().commit()) }
}

package dev.pathfin.android.download

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.data.SafDocumentTransfer
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实系统树授权，仅在 verification 专属 test APK 私有目录内创建 UUID 文件。 */
@RunWith(AndroidJUnit4::class)
class DownloadPreferencesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test fun networkChoicesGenerateTheirActualWorkManagerConstraints() {
        assertEquals(NetworkType.CONNECTED, downloadConstraints(DownloadNetwork.ANY).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, downloadConstraints(DownloadNetwork.UNMETERED).requiredNetworkType)
    }

    @Test fun aBlockedDestinationQueryAllowsOtherQueueCommandsAndClosesItsLateCursorAfterCancellation() = runBlocking {
        check(context.packageName == VerificationDownloadDocumentsProvider.TARGET_PACKAGE)
        dev.pathfin.android.VerificationDisplay.prepare()
        val prefix = "query-${UUID.randomUUID()}-"
        val stores = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences(prefix + name, mode).also { stores += prefix + name }
        }
        val tree = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            grantTree(scenario).also { context.contentResolver.takePersistableUriPermission(it, GRANT_FLAGS) }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val documents = mutableListOf<Uri>()
        val profileId = "query-profile-${UUID.randomUUID()}"
        val store = DownloadStore(isolated)
        try {
            val blocked = checkNotNull(DocumentsContract.createDocument(context.contentResolver, parent,
                "video/mp4", "${UUID.randomUUID()}.mp4")).also(documents::add)
            val available = checkNotNull(DocumentsContract.createDocument(context.contentResolver, parent,
                "video/mp4", "${UUID.randomUUID()}.mp4")).also(documents::add)
            val original = "existing-user-document".toByteArray()
            checkNotNull(context.contentResolver.openOutputStream(blocked, "wt")).use { it.write(original) }
            SessionStore(isolated).upsertProfile(ServerProfile(profileId, Session(
                ServerConfig("https://query-fixture.invalid"), "synthetic-query-token", "query-user", "Synthetic", "query-device",
                serverId = "query-fixture", canDownload = true), "Synthetic query account"))
            fun control(action: String) = checkNotNull(instrumentation.context.contentResolver.call(
                Uri.parse("content://${VerificationDownloadDocumentsProvider.authority(instrumentation.context)}"),
                VerificationDownloadDocumentsProvider.QUERY_CONTROL + action, DocumentsContract.getDocumentId(blocked), null))
            control("register")
            val slow = launch(Dispatchers.IO) { MediaDownloads.enqueue(isolated, profileId, "blocked", "Blocked", blocked) }
            var other: kotlinx.coroutines.Deferred<String>? = null
            var recovery: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                withTimeout(10_000) { while (control("status").getInt("entered") == 0) delay(20) }
                val ready = async(Dispatchers.IO) { MediaDownloads.enqueue(isolated, profileId, "available", "Available", available) }
                other = ready
                val coordinated = async(Dispatchers.IO) { MediaDownloads.recover(isolated) }
                recovery = coordinated
                val readyId = withTimeout(2_000) { ready.await().also { coordinated.await() } }
                assertEquals("available", store.get(readyId)?.itemId)
                assertEquals("另一入队及恢复不能等慢Provider释放", 0, control("status").getInt("returned"))
                withTimeout(2_000) { slow.cancelAndJoin() }
                assertTrue("取消未完成验证的请求不能落入持久队列", store.all().none { it.itemId == "blocked" })
                control("release")
                withTimeout(10_000) {
                    while (control("status").let { it.getInt("returned") == 0 || it.getInt("cursorClosed") < it.getInt("returned") }) delay(20)
                }
                assertTrue("迟到查询返回并关闭后也不能复活已取消的入队", store.all().none { it.itemId == "blocked" })
                assertArrayEquals("取消验证不能删除或截断原有用户文档", original,
                    checkNotNull(context.contentResolver.openInputStream(blocked)).use { it.readBytes() })
            } finally {
                control("release")
                slow.cancelAndJoin()
                other?.cancelAndJoin()
                recovery?.cancelAndJoin()
                control("remove")
            }
        } finally {
            store.all().forEach { WorkManager.getInstance(context).cancelUniqueWork("pathfin-download-${it.id}").result.get(10, TimeUnit.SECONDS) }
            stores.forEach { context.deleteSharedPreferences(it) }
            documents.forEach { DocumentsContract.deleteDocument(context.contentResolver, it) }
            context.contentResolver.releasePersistableUriPermission(tree, GRANT_FLAGS)
        }
    }

    @Test fun persistentTreeGrantCreatesNewFilesWithoutOverwritingAndRejectsRevokedAccess() = runBlocking {
        check(context.packageName == VerificationDownloadDocumentsProvider.TARGET_PACKAGE)
        dev.pathfin.android.VerificationDisplay.prepare()
        val tree = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val granted = grantTree(scenario)
            assertEquals("Pathfin-verification", SafDocumentTransfer.transfer { DownloadDirectories.persist(context, granted, it) })
            granted
        }
        val created = mutableListOf<Uri>()
        val bytes = byteArrayOf(4, 2, 7, 9)
        try {
            val filename = "${UUID.randomUUID()}.mp4"
            val first = SafDocumentTransfer.transfer { operation ->
                DownloadDirectories.create(context, tree, filename, operation).also {
                    DownloadDirectories.requireDestination(context, it, operation)
                }
            }.also(created::add)
            context.contentResolver.openOutputStream(first, "w")!!.use { it.write(bytes) }
            val second = SafDocumentTransfer.transfer { DownloadDirectories.create(context, tree, filename, it) }.also(created::add)
            assertNotEquals(first, second)
            assertTrue(DocumentsContract.getDocumentId(second).endsWith(" (1).mp4"))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(first)!!.use { it.readBytes() })
            val unrelated = DocumentsContract.buildDocumentUriUsingTree(tree, "outside-${UUID.randomUUID()}")
            assertTrue("树 ID 不能授予树外文件权限", runCatching {
                SafDocumentTransfer.transfer { DownloadDirectories.requireDestination(context, unrelated, it) }
            }.isFailure)
        } finally {
            created.forEach { DocumentsContract.deleteDocument(context.contentResolver, it) }
            context.contentResolver.releasePersistableUriPermission(tree, GRANT_FLAGS)
        }
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        assertTrue("失效授权必须可识别并回到目录选择", runCatching {
            SafDocumentTransfer.transfer { DownloadDirectories.create(context, tree, "${UUID.randomUUID()}.mp4", it) }
        }.exceptionOrNull() is DownloadDirectoryUnavailable)
    }

    private fun grantTree(scenario: ActivityScenario<ComponentActivity>): Uri {
        val result = CompletableFuture<Uri>()
        scenario.onActivity { activity ->
            val launcher = activity.activityResultRegistry.register("download-tree-${UUID.randomUUID()}",
                ActivityResultContracts.StartActivityForResult()) { returned ->
                val uri = returned.data?.data
                if (returned.resultCode == Activity.RESULT_OK && uri != null) result.complete(uri)
                else result.completeExceptionally(AssertionError("测试目录未返回真实 URI 授权"))
            }
            launcher.launch(Intent().setComponent(ComponentName(instrumentation.context.packageName,
                VerificationDownloadDocumentsProvider.GrantActivity::class.java.name))
                .putExtra(VerificationDownloadDocumentsProvider.TREE_GRANT_EXTRA, true))
        }
        return result.get(15, TimeUnit.SECONDS)
    }

    private companion object {
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

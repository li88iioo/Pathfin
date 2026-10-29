package dev.pathfin.android.download

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import dev.pathfin.android.domain.DownloadNetwork
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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

    @Test fun persistentTreeGrantCreatesNewFilesWithoutOverwritingAndRejectsRevokedAccess() = runBlocking {
        check(context.packageName == VerificationDownloadDocumentsProvider.TARGET_PACKAGE)
        dev.pathfin.android.VerificationDisplay.prepare()
        val tree = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val granted = grantTree(scenario)
            assertEquals("Pathfin-verification", DownloadDirectories.persist(context, granted))
            granted
        }
        val created = mutableListOf<Uri>()
        val bytes = byteArrayOf(4, 2, 7, 9)
        try {
            val filename = "${UUID.randomUUID()}.mp4"
            val first = DownloadDirectories.create(context, tree, filename).also(created::add)
            DownloadDirectories.requireDestination(context, first)
            context.contentResolver.openOutputStream(first, "w")!!.use { it.write(bytes) }
            val second = DownloadDirectories.create(context, tree, filename).also(created::add)
            assertNotEquals(first, second)
            assertTrue(DocumentsContract.getDocumentId(second).endsWith(" (1).mp4"))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(first)!!.use { it.readBytes() })
            val unrelated = DocumentsContract.buildDocumentUriUsingTree(tree, "outside-${UUID.randomUUID()}")
            assertTrue("树 ID 不能授予树外文件权限", runCatching { DownloadDirectories.requireDestination(context, unrelated) }.isFailure)
        } finally {
            created.forEach { DocumentsContract.deleteDocument(context.contentResolver, it) }
            context.contentResolver.releasePersistableUriPermission(tree, GRANT_FLAGS)
        }
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        assertTrue("失效授权必须可识别并回到目录选择", runCatching {
            DownloadDirectories.create(context, tree, "${UUID.randomUUID()}.mp4")
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

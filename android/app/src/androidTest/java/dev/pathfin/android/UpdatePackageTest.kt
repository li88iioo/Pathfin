package dev.pathfin.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.update.UpdateFiles
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 用本机已安装的 APK 走真实的解析、签名与清理路径；不联网、不触发安装。 */
@RunWith(AndroidJUnit4::class)
class UpdatePackageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = UpdateFiles.directory(context)
    private val originalLocale = Locale.getDefault()

    @Before fun prepare() {
        check(context.packageName == "dev.pathfin.android.verification")
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After fun clean() {
        if (context.packageName == "dev.pathfin.android.verification") directory.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    private fun installedCopy(name: String) = File(directory.apply { mkdirs() }, name).also {
        File(context.applicationInfo.sourceDir).copyTo(it, overwrite = true)
    }

    @Test fun sameBuildPassesIdentityChecksButIsNotAnUpgrade() {
        val apk = installedCopy("pathfin-same.apk")
        val error = assertThrows(IllegalStateException::class.java) { UpdateFiles.verify(context, apk) }
        // 包名与签名已通过，才会走到版本检查。
        assertEquals("安装包版本不高于当前版本，已拒绝安装", error.message)
    }

    @Test fun damagedOrMissingPackagesAreRefused() {
        val broken = File(directory.apply { mkdirs() }, "pathfin-broken.apk").apply { writeText("not an apk") }
        assertEquals("安装包损坏或无法解析，请重新下载",
            assertThrows(IllegalStateException::class.java) { UpdateFiles.verify(context, broken) }.message)
        assertEquals("安装包不存在，请重新下载",
            assertThrows(IllegalStateException::class.java) { UpdateFiles.verify(context, File(directory, "missing.apk")) }.message)
    }

    @Test fun cleanupRemovesInstalledBrokenAndPartialFiles() {
        installedCopy("pathfin-installed.apk")
        File(directory, "pathfin-broken.apk").writeText("x")
        File(directory, "pathfin-1.0.part").writeText("x")
        File(directory, "notes.txt").writeText("x")
        UpdateFiles.cleanup(context, keepPartial = true)
        assertEquals(listOf("pathfin-1.0.part"), directory.list()!!.toList())
        UpdateFiles.cleanup(context)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun installIntentGrantsReadAccessThroughTheUpdateProvider() {
        val apk = installedCopy("pathfin-intent.apk")
        val intent = UpdateFiles.installIntent(context, apk)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

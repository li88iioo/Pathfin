package dev.pathfin.android.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 仅 verification；真实旧目录、AndroidKeyStore 与加密 Jellyfin 会话，不调用任何服务器。 */
@RunWith(AndroidJUnit4::class)
class LegacyTraktCleanupTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "dev.pathfin.android.verification")
    }

    @Test fun encryptedLegacyFilesAndOnlyTheirKeyAreRemovedOnceWhileJellyfinDataSurvives() = migration { directory ->
        val sessions = SessionStore(context)
        val selectedBefore = sessions.activeProfileId()
        val profilesBefore = sessions.loadProfiles()
        val preferenceStore = AppPreferencesStore(context)
        val preferencesBefore = preferenceStore.load()
        val preference = preferencesBefore.copy(imageCacheSizeMb = 1536, exoAssEnabled = !preferencesBefore.exoAssEnabled)
        val id = "legacy-removal-${UUID.randomUUID()}"
        val session = Session(ServerConfig("https://jellyfin-fixture.invalid"), "synthetic-session-token", "synthetic-user",
            "Synthetic migration account", "synthetic-device", "legacy-removal-jellyfin")
        try {
            sessions.upsertProfile(ServerProfile(id, session, "Synthetic preserved Jellyfin"))
            sessions.selectProfile(id)
            preferenceStore.save(preference)
            writeEncryptedLegacyFiles(directory)
            val accesses = AtomicInteger()
            val guarded = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File {
                    assertNotSame("旧文件路径只能在 IO 获取", Looper.getMainLooper().thread, Thread.currentThread())
                    accesses.incrementAndGet()
                    return context.noBackupFilesDir
                }
            }

            assertTrue(runBlocking { LegacyTraktCleanup.run(guarded) })
            assertFalse(Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertFalse(keys().containsAlias(ALIAS))
            assertEquals(session, sessions.load())
            assertEquals(profilesBefore + ServerProfile(id, session, "Synthetic preserved Jellyfin"), sessions.loadProfiles())
            assertEquals(preference, preferenceStore.load())
            val firstAccesses = accesses.get()
            assertTrue(firstAccesses > 0)
            assertTrue(runBlocking { LegacyTraktCleanup.run(guarded) })
            assertEquals("完成后的迁移不再访问旧目录", firstAccesses, accesses.get())
        } finally {
            sessions.removeProfile(id)
            if (selectedBefore == null) sessions.clear() else sessions.selectProfile(selectedBefore)
            preferenceStore.save(preferencesBefore)
        }
    }

    @Test fun legacyDirectorySymlinksCannotDeleteUnrelatedFiles() = migration { directory ->
        val unrelated = File(context.noBackupFilesDir, "preserved-${UUID.randomUUID()}").apply {
            mkdirs(); resolve("jellyfin-sentinel").writeText("synthetic unrelated account data")
        }
        try {
            assertTrue(directory.mkdirs())
            Files.createSymbolicLink(directory.resolve("nested-link").toPath(), unrelated.toPath())
            assertTrue(runBlocking { LegacyTraktCleanup.run(context) })
            assertEquals("synthetic unrelated account data", unrelated.resolve("jellyfin-sentinel").readText())
            assertFalse(Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS))

            // 同样覆盖整个旧目录被替换成链接，不能沿链接走进其它功能的数据。
            check(marker().edit().remove(COMPLETED).commit())
            Files.createSymbolicLink(directory.toPath(), unrelated.toPath())
            assertTrue(runBlocking { LegacyTraktCleanup.run(context) })
            assertEquals("synthetic unrelated account data", unrelated.resolve("jellyfin-sentinel").readText())
            assertFalse(Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        } finally { unrelated.deleteRecursively() }
    }

    @Test fun failedFileRemovalKeepsTheMigrationRetryableAndDoesNotKeepTheLegacyKey() = migration { directory ->
        writeEncryptedLegacyFiles(directory)
        try {
            assertTrue("fixture 必须能建立真实目录权限失败", directory.setWritable(false, true))
            assertFalse("文件未移除不能记录迁移成功", runBlocking { LegacyTraktCleanup.run(context) })
            assertFalse(marker().getBoolean(COMPLETED, false))
            assertFalse("文件失败仍需删除专用旧密钥", keys().containsAlias(ALIAS))
        } finally { assertTrue(directory.setWritable(true, true)) }
        assertTrue(runBlocking { LegacyTraktCleanup.run(context) })
        assertFalse(Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(marker().getBoolean(COMPLETED, false))
    }

    private fun migration(block: (File) -> Unit) {
        val marker = marker()
        val hadMarker = marker.contains(COMPLETED)
        val completedBefore = marker.getBoolean(COMPLETED, false)
        // verification 中已移除功能的遗留数据本就是本次迁移目标；不读取或解密其内容。
        check(marker.edit().remove(COMPLETED).commit())
        check(runBlocking { LegacyTraktCleanup.run(context) })
        check(marker.edit().remove(COMPLETED).commit())
        val directory = File(context.noBackupFilesDir, "trakt")
        var failure: Throwable? = null
        try { block(directory) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try {
                if (Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) directory.setWritable(true, true)
                check(marker.edit().remove(COMPLETED).commit())
                check(runBlocking { LegacyTraktCleanup.run(context) })
                check(marker.edit().apply {
                    if (hadMarker) putBoolean(COMPLETED, completedBefore) else remove(COMPLETED)
                }.commit())
            } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    private fun writeEncryptedLegacyFiles(directory: File) {
        check(directory.mkdirs())
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal("synthetic legacy client secret and token".toByteArray())
        val envelope = JSONObject().put("version", 1).put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(ciphertext)).toString()
        for (suffix in listOf(".enc", ".enc.bak", ".enc.new")) directory.resolve("a".repeat(64) + suffix).writeText(envelope)
        directory.resolve("b".repeat(64) + ".enc").writeText("{corrupt legacy envelope")
    }

    private fun marker() = context.getSharedPreferences("pathfin_removed_features", Context.MODE_PRIVATE)
    private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object {
        const val COMPLETED = "trakt_v1_removed"
        const val ALIAS = "dev.pathfin.android.trakt.v1"
    }
}

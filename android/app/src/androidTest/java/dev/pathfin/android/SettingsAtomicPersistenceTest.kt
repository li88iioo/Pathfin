package dev.pathfin.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.ui.theme.ThemePreference
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAtomicPersistenceTest {
    @Test fun eachLibraryKeepsItsOwnSortAfterReopeningTheStoreAndSavingOtherSettings() = isolated { context ->
        val first = AppPreferencesStore(context)
        first.saveBrowserSort("account-a/movies", dev.pathfin.android.domain.LibrarySort.YEAR, true)
        first.saveBrowserSort("account-a/shows", dev.pathfin.android.domain.LibrarySort.TITLE, false)
        first.saveBrowserSort("account-b/movies", dev.pathfin.android.domain.LibrarySort.COMMUNITY_RATING, true)
        first.save(AppPreferences(playbackSpeed = 1.5f))
        val reopened = AppPreferencesStore(context)
        assertEquals(dev.pathfin.android.domain.LibrarySort.YEAR to true, reopened.browserSort("account-a/movies"))
        assertEquals(dev.pathfin.android.domain.LibrarySort.TITLE to false, reopened.browserSort("account-a/shows"))
        assertEquals(dev.pathfin.android.domain.LibrarySort.COMMUNITY_RATING to true, reopened.browserSort("account-b/movies"))
        assertNull(reopened.browserSort("another-library"))
        assertEquals(1.5f, reopened.load().playbackSpeed, 0f)
    }

    @Test fun importedPreferencesAndThemeCommitTogetherWhileExistingStyleSurvives() = isolated { context ->
        val preferences = AppPreferencesStore(context)
        preferences.saveBrowserStyle("atomic-fixture", "BACKDROP")
        val expected = AppPreferences(imageCacheSizeMb = 2048, preferredAudioLanguage = "ja",
            hideSystemStatusBar = true, blurAdultCovers = true, playbackSpeed = 1.75f, playerBrightness = .37f,
            playerOrientation = dev.pathfin.android.domain.PlayerOrientation.LANDSCAPE, videoResizeMode = "FILL", subtitleScale = 1.35f, subtitlePositionPercent = 73,
            subtitleDelays = mapOf("a".repeat(64) to -2350L),
            bufferPreset = dev.pathfin.android.domain.BufferPreset.HUGE,
            downloadNetwork = dev.pathfin.android.domain.DownloadNetwork.UNMETERED,
            downloadDirectoryUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies")
        preferences.saveWithTheme(expected, ThemePreference.DARK)
        assertEquals(expected, AppPreferencesStore(context).load())
        assertEquals(ThemePreference.DARK, AppPreferencesStore(context).loadTheme())
        assertEquals("BACKDROP", AppPreferencesStore(context).browserStyle("atomic-fixture"))
    }

    @Test fun legacyBannerAndManualColumnsMigrateWithoutChangingOtherSettings() = isolated { context ->
        val raw = context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE)
        val expected = AppPreferences(imageCacheSizeMb = 1536, subtitlesEnabled = false)
        AppPreferencesStore(context).saveWithTheme(expected, ThemePreference.DARK)
        check(raw.edit().putString("style/legacy-layout", "BANNER").putInt("columns/legacy-layout", 5)
            .putString("style/other-profile-layout", "BACKDROP").putInt("columns/other-profile-layout", 2)
            .putStringSet("hidden/synthetic-other-account", setOf("keep-library"))
            .putString("unrelated-fixture", "keep").commit())
        val restored = AppPreferencesStore(context)
        assertEquals(expected, restored.load())
        assertEquals("POSTER", restored.browserStyle("legacy-layout"))
        assertEquals("BACKDROP", restored.browserStyle("other-profile-layout"))
        assertEquals("POSTER", raw.getString("style/legacy-layout", null))
        assertFalse(raw.contains("columns/legacy-layout"))
        assertFalse(raw.contains("columns/other-profile-layout"))
        assertEquals(setOf("keep-library"), restored.hiddenLibraries("synthetic-other-account"))
        assertEquals("keep", raw.getString("unrelated-fixture", null))
        assertEquals(ThemePreference.DARK, AppPreferencesStore(context).loadTheme())
        restored.saveBrowserStyle("legacy-layout", "BACKDROP")
        assertEquals("BACKDROP", AppPreferencesStore(context).browserStyle("legacy-layout"))
        assertEquals(expected, AppPreferencesStore(context).load())
    }

    @Test fun failedAtomicCommitCannotApplyOnlyHalfOfImport() = isolated { context ->
        val previous = AppPreferences(imageCacheSizeMb = 512, subtitlesEnabled = false)
        AppPreferencesStore(context).saveWithTheme(previous, ThemePreference.LIGHT)
        val failing = AppPreferencesStore(failingCommits(context))
        assertTrue(runCatching { failing.saveWithTheme(AppPreferences(), ThemePreference.DARK) }.isFailure)
        assertTrue("显式主题写入失败仍须向调用方报告", runCatching { failing.saveTheme(ThemePreference.DARK) }.isFailure)
        assertEquals(previous, AppPreferencesStore(context).load())
        assertEquals(ThemePreference.LIGHT, AppPreferencesStore(context).loadTheme())
    }

    @Test fun oldThemeMigratesOnceAndCurrentValueAlwaysWins() = isolated { context ->
        val current = context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("pathfin_appearance", Context.MODE_PRIVATE)
        check(current.edit().remove("theme").commit())
        check(legacy.edit().putString("mode", "DARK").commit())
        val preferences = AppPreferencesStore(context)
        assertEquals(ThemePreference.DARK, preferences.loadTheme())
        assertEquals("DARK", current.getString("theme", null))
        check(legacy.edit().putString("mode", "LIGHT").commit())
        assertEquals("主值存在后旧文件不再参与主题选择", ThemePreference.DARK, AppPreferencesStore(context).loadTheme())
        preferences.saveTheme(ThemePreference.SYSTEM)
        assertEquals(ThemePreference.SYSTEM, AppPreferencesStore(context).loadTheme())
        assertEquals("旧值保持只读，不为清理再做一次文件提交", "LIGHT", legacy.getString("mode", null))
    }

    @Test fun failedThemeMigrationPreservesLegacyValueAndCanRetry() = isolated { context ->
        val current = context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences("pathfin_appearance", Context.MODE_PRIVATE)
        check(current.edit().remove("theme").commit())
        check(legacy.edit().putString("mode", "DARK").commit())
        assertEquals(ThemePreference.DARK, AppPreferencesStore(failingCommits(context)).loadTheme())
        assertFalse("失败的迁移不能假装已落盘", current.contains("theme"))
        assertEquals("DARK", legacy.getString("mode", null))
        assertEquals(ThemePreference.DARK, AppPreferencesStore(context).loadTheme())
        assertEquals("DARK", current.getString("theme", null))
        assertEquals("DARK", legacy.getString("mode", null))
    }

    private fun failingCommits(context: Context) = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = context.getSharedPreferences(name, mode)
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                if (method.name != "edit") method.invoke(delegate, *(args ?: emptyArray()))
                else {
                    val editor = delegate.edit()
                    lateinit var proxy: SharedPreferences.Editor
                    proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, values ->
                        when (editMethod.name) {
                            "commit" -> false
                            "apply" -> error("本测试禁止异步绕过失败")
                            else -> editMethod.invoke(editor, *(values ?: emptyArray())).let { result -> if (result is SharedPreferences.Editor) proxy else result }
                        }
                    } as SharedPreferences.Editor
                    proxy
                }
            } as SharedPreferences
        }
    }

    private fun isolated(block: (Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val stores = listOf("pathfin_preferences", "pathfin_appearance").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val before = stores.map { it.all.toMap() }
        try { block(context) } finally {
            stores.zip(before).forEach { (store, values) ->
                val edit = store.edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
                    is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(edit.commit())
            }
        }
    }
}

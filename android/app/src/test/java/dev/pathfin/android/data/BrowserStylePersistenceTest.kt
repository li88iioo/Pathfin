package dev.pathfin.android.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class BrowserStylePersistenceTest {
    @Test fun startupMigrationRemovesLegacyColumnsAndNormalizesBannerInOneCommitWithoutLosingOtherSettings() {
        val memory = MemoryPreferences(mapOf(
            "settings" to """{"pullToRefreshEnabled":false,"imageCacheSizeMb":4096}""",
            "theme" to "DARK", "hidden/account-a" to setOf("private-library"),
            "style/account-a/library" to "BANNER", "columns/account-a/library" to 5,
            "style/account-b/library" to "BACKDROP", "columns/account-b/library" to 1,
            "style/account-c/library" to "unknown", "unrelated" to 17,
        ))
        val store = AppPreferencesStore(memory)
        val value = store.load()
        assertFalse(value.pullToRefreshEnabled)
        assertEquals(4096, value.imageCacheSizeMb)
        assertEquals("POSTER", store.browserStyle("account-a/library"))
        assertEquals("BACKDROP", store.browserStyle("account-b/library"))
        assertEquals("POSTER", store.browserStyle("account-c/library"))
        assertFalse(memory.all.keys.any { it.startsWith("columns/") })
        assertEquals("DARK", memory.getString("theme", null))
        assertEquals(setOf("private-library"), store.hiddenLibraries("account-a"))
        assertEquals(17, memory.getInt("unrelated", 0))
        assertEquals(1, memory.commits)
        store.load()
        assertEquals("再次读取不应继续提交迁移", 1, memory.commits)
    }

    @Test fun styleSaveCommitsOnlyTargetStyleAndItsLegacyColumnKey() {
        val memory = MemoryPreferences(mapOf(
            "style/account-a/library" to "POSTER", "columns/account-a/library" to 4,
            "style/account-b/library" to "BACKDROP", "columns/account-b/library" to 2,
            "settings" to "{\"openLastServer\":false}", "theme" to "LIGHT",
        ))
        val store = AppPreferencesStore(memory)
        store.saveBrowserStyle("account-a/library", "BACKDROP")
        assertEquals("BACKDROP", store.browserStyle("account-a/library"))
        assertFalse(memory.contains("columns/account-a/library"))
        assertEquals("BACKDROP", store.browserStyle("account-b/library"))
        assertEquals(2, memory.getInt("columns/account-b/library", 0))
        assertEquals("{\"openLastServer\":false}", memory.getString("settings", null))
        assertEquals("LIGHT", memory.getString("theme", null))
        assertEquals(1, memory.commits)
        store.saveBrowserStyle("account-a/library", "BANNER")
        assertEquals("POSTER", store.browserStyle("account-a/library"))
    }

    @Test fun rejectedSaveDoesNotPartiallyChangeStyleOrDeleteColumns() {
        val memory = MemoryPreferences(mapOf("style/account/library" to "POSTER", "columns/account/library" to 5))
        memory.acceptCommits = false
        val failure = runCatching { AppPreferencesStore(memory).saveBrowserStyle("account/library", "BACKDROP") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("POSTER", memory.getString("style/account/library", null))
        assertEquals(5, memory.getInt("columns/account/library", 0))
    }

    @Test fun failedCleanupStillReadsOrdinaryPreferencesAndSafeStyleAndCanRetryLater() {
        val memory = MemoryPreferences(mapOf(
            "settings" to "{\"pullToRefreshEnabled\":false}",
            "style/account/library" to "BANNER", "columns/account/library" to 3,
        ))
        memory.acceptCommits = false
        val store = AppPreferencesStore(memory)
        assertFalse(store.load().pullToRefreshEnabled)
        assertEquals("POSTER", store.browserStyle("account/library"))
        assertEquals("BANNER", memory.getString("style/account/library", null))
        assertEquals(3, memory.getInt("columns/account/library", 0))
        memory.acceptCommits = true
        assertFalse(store.load().pullToRefreshEnabled)
        assertEquals("POSTER", memory.getString("style/account/library", null))
        assertFalse(memory.contains("columns/account/library"))
    }

    private class MemoryPreferences(initial: Map<String, Any>) : SharedPreferences {
        private val values = initial.toMutableMap()
        var acceptCommits = true
        var commits = 0
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear(): SharedPreferences.Editor = error("布局迁移不得清空整个偏好文件")
            override fun commit(): Boolean {
                commits++
                if (!acceptCommits) return false
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { check(commit()) }
        }
    }
}

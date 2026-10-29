package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class ProfileHomeCacheTest {
    private val session = Session(ServerConfig("https://example.test/jellyfin/"), "synthetic-token", "user", "Alice", "device", "server")
    private fun key(profile: String = "A", value: Session = session) = ProfileHomeKey.of(profile, value)

    @Test fun canonicalAddressMatchesButEachProfileServerUserAndAddressRemainSeparate() {
        assertEquals(key(), key(value = session.copy(server = ServerConfig("https://example.test/jellyfin"))))
        val keys = setOf(key(), key("B"), key(value = session.copy(serverId = "other-server")),
            key(value = session.copy(userId = "other-user")),
            key(value = session.copy(server = ServerConfig("https://other.test/jellyfin"))))
        assertEquals(5, keys.size)
    }

    @Test fun tokenDeviceAndPermissionChangesDoNotExposeCredentialsInTheContentKey() {
        val renewed = session.copy(accessToken = "new-synthetic-token", deviceId = "new-device", canDelete = true)
        assertEquals(key(), key(value = renewed))
        assertFalse(key().toString().contains(session.accessToken))
        assertFalse(key().toString().contains(session.deviceId))
    }

    @Test fun holdsSixSnapshotsAndEvictsTheLeastRecentlyRead() {
        val cache = ProfileHomeCache<String>()
        repeat(6) { cache.put(key("$it"), "home-$it") }
        assertEquals("home-0", cache[key("0")])
        cache.put(key("6"), "home-6")
        assertNull(cache[key("1")])
        assertEquals("home-0", cache[key("0")])
        for (index in 2..6) assertEquals("home-$index", cache[key("$index")])
    }

    @Test fun invalidationRemovesAllAddressesForOnlyThatProfileAndRejectsLateOldCaptures() {
        val cache = ProfileHomeCache<String>()
        val alternate = key(value = session.copy(server = ServerConfig("https://other.test/jellyfin")))
        cache.put(key(), "A-home")
        cache.put(alternate, "A-alternate")
        cache.put(key("B"), "B-home")
        cache.invalidateProfile("A")
        cache.put(key(), "late-old-A-home")
        cache.put(alternate, "late-old-A-alternate")
        assertTrue(cache.isInvalidated("A"))
        assertNull(cache[key()])
        assertNull(cache[alternate])
        assertEquals("B-home", cache[key("B")])
    }

    @Test fun invalidationAlsoProtectsProfilesThatNeverHadAnEntry() {
        val cache = ProfileHomeCache<String>()
        cache.invalidateProfile("A")
        cache.put(key(), "old-current-page")
        assertNull(cache[key()])
        cache.allowProfile("A")
        assertNull("解除保护不应恢复被删除的内容", cache[key()])
        cache.put(key(), "fresh-authenticated-home")
        assertEquals("fresh-authenticated-home", cache[key()])
    }

    @Test fun legitimateEmptyShelvesRemainPresentAndDistinctFromUnloadedShelves() {
        val cache = ProfileHomeCache<Map<String, List<String>>>()
        cache.put(key(), linkedMapOf("empty-library" to emptyList(), "loaded-library" to listOf("movie")))
        val restored = requireNotNull(cache[key()])
        assertTrue(restored.containsKey("empty-library"))
        assertEquals(emptyList<String>(), restored["empty-library"])
        assertFalse(restored.containsKey("unloaded-library"))
        assertEquals(listOf("movie"), restored["loaded-library"])
    }
}

package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class ServerProfileIdentityTest {
    private val first = Session(ServerConfig("https://media.example/jellyfin"), "synthetic-token-a",
        "shared-user", "Viewer", "synthetic-device", serverId = "shared-server")

    @Test fun sameServerAndUserAtDifferentAddressesAreSeparateProfiles() {
        val second = first.copy(server = ServerConfig("https://other.example/jellyfin"))
        assertFalse(ServerProfileIdentity.matches(first, second))
        assertFalse(ServerProfileIdentity.matches(second, first))
    }

    @Test fun separateServersCannotMergeBecauseTheyUseTheSameUserId() {
        val second = first.copy(server = ServerConfig("https://other.example/jellyfin"), serverId = "other-server")
        assertFalse(ServerProfileIdentity.matches(first, second))
    }

    @Test fun conflictingKnownServerIdsCannotMergeEvenAtTheSameAddress() {
        assertFalse(ServerProfileIdentity.matches(first, first.copy(serverId = "replacement-server")))
    }

    @Test fun differentUsersOnOneServerRemainSeparate() {
        assertFalse(ServerProfileIdentity.matches(first, first.copy(userId = "other-user")))
    }

    @Test fun canonicalSameAddressReloginIgnoresRotatingTokenAndDisplayMetadata() {
        val relogged = first.copy(server = ServerConfig(" HTTPS://MEDIA.EXAMPLE:443/jellyfin/// "),
            accessToken = "synthetic-token-b", userName = "Renamed viewer", deviceId = "another-device",
            userImageTag = "new-avatar", canDownload = true)
        assertTrue(ServerProfileIdentity.matches(first, relogged))
        assertTrue(ServerProfileIdentity.matches(relogged, first))
        assertFalse(ServerProfileIdentity.matches(first, first.copy(server = ServerConfig("https://media.example/another-jellyfin"))))
        assertFalse(ServerProfileIdentity.matches(first, first.copy(server = ServerConfig("http://media.example/jellyfin", true))))
    }

    @Test fun legacyMissingServerIdCanBeFilledWithoutMergingAnotherAddress() {
        val legacy = first.copy(serverId = "")
        assertTrue(ServerProfileIdentity.matches(legacy, first))
        assertTrue(ServerProfileIdentity.matches(first, legacy))
        assertFalse(ServerProfileIdentity.matches(legacy, first.copy(server = ServerConfig("https://other.example/jellyfin"))))
    }
}

package dev.pathfin.android.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAuthenticationTest {
    private val session = Session(ServerConfig("https://media.invalid/jellyfin"), "token-a", "user-a", "Old name",
        "device-a", "server-a", userImageTag = "old-avatar")

    @Test fun completingUserPresentationAndPolicyKeepsTheAuthenticatedConnection() {
        val updated = session.copy(userName = "New name", userImageTag = "new-avatar",
            isAdministrator = true, canDelete = true, canDownload = true)
        assertTrue(session.hasSameAuthentication(updated))
        assertTrue(updated.hasSameAuthentication(session))
    }

    @Test fun changingCredentialsAccountServerOrDeviceInvalidatesInFlightResponses() {
        val replacements = listOf(
            session.copy(accessToken = "token-b"), session.copy(userId = "user-b"),
            session.copy(serverId = "server-b"), session.copy(deviceId = "device-b"),
        )
        replacements.forEach { assertFalse(session.hasSameAuthentication(it)) }
        assertFalse(session.hasSameAuthentication(null))
    }

    @Test fun anotherAddressOfTheSameServerKeepsTheConnectionButUnknownServersStaySeparatedByAddress() {
        val lan = session.copy(server = ServerConfig("http://192.0.2.10:8096", allowInsecureHttp = true))
        assertTrue(session.hasSameAuthentication(lan))
        val legacy = session.copy(serverId = "")
        assertTrue(legacy.hasSameAuthentication(legacy.copy(server = ServerConfig("https://MEDIA.invalid/jellyfin/"))))
        assertFalse(legacy.hasSameAuthentication(legacy.copy(server = ServerConfig("https://other.invalid/jellyfin"))))
        assertFalse(legacy.hasSameAuthentication(legacy.copy(server = ServerConfig("https://media.invalid/another-base"))))
    }
}

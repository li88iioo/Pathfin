package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class DownloadHeadersTest {
    private val session = Session(ServerConfig("https://media.example/proxy"), "fixture-token", "user", "Alice", "device")

    @Test fun serverIdentityIsRestrictedToConfiguredOriginAndBasePath() {
        val source = DownloadSource("https://media.example/proxy/Items/item/Download", emptyMap(), "movie.mp4", trustedServer = true)
        assertNotNull(DownloadHeaders.forUrl(session, source, source.url)["Authorization"])
        for (url in listOf("https://cdn.example/file.mp4", "https://media.example/proxy-other/file", "https://media.example/outside", "https://user:pass@media.example/proxy/file")) {
            assertTrue(DownloadHeaders.forUrl(session, source, url).isEmpty())
        }
    }

    @Test fun externalHeadersStayOnOriginalOriginAndStripJellyfinCredentials() {
        val source = DownloadSource("https://cdn.example/file.mp4?signature=a%2Fb", mapOf(
            "User-Agent" to "Synthetic source", "Referer" to "https://source.example/", "Authorization" to "Bearer source-only",
            "X-Emby-Token" to "fixture-token", "Bad-Header" to "contains fixture-token", "Connection" to "keep-alive",
        ), "movie.mp4")
        val headers = DownloadHeaders.forUrl(session, source, "https://cdn.example/redirected.mp4")
        assertEquals("Synthetic source", headers["User-Agent"])
        assertEquals("Bearer source-only", headers["Authorization"])
        assertFalse(headers.values.any { it.contains(session.accessToken) })
        assertFalse(headers.keys.any { it.equals("Connection", true) || it.equals("X-Emby-Token", true) })
        assertTrue(DownloadHeaders.forUrl(session, source, "https://other.example/file.mp4").isEmpty())
        assertTrue(DownloadHeaders.forUrl(session, source, "http://cdn.example/file.mp4").isEmpty())
    }

    @Test fun explicitExternalHttpSourceDoesNotUseServerCleartextPermissionOrIdentity() {
        assertFalse(session.server.allowInsecureHttp)
        val source = DownloadSource("http://cdn.example/file.mp4", mapOf(
            "User-Agent" to "Synthetic source", "Authorization" to "Bearer source-only",
            "X-Emby-Token" to session.accessToken,
        ), "movie.mp4")
        val headers = DownloadHeaders.forUrl(session, source, source.url)
        assertEquals("Synthetic source", headers["User-Agent"])
        assertEquals("Bearer source-only", headers["Authorization"])
        assertFalse(headers.values.any { it.contains(session.accessToken) })
        assertFalse(headers.keys.any { it.equals("X-Emby-Token", true) })
        assertTrue(DownloadHeaders.forUrl(session, source, "http://other.example/file.mp4").isEmpty())
        assertTrue(DownloadHeaders.forUrl(session, source, "https://cdn.example/file.mp4").isEmpty())

        val serverSource = DownloadSource("http://media.example/proxy/Items/item/Download", emptyMap(), "movie.mp4", trustedServer = true)
        val unconfirmedServer = session.copy(server = ServerConfig("http://media.example/proxy"))
        assertTrue(DownloadHeaders.forUrl(unconfirmedServer, serverSource, serverSource.url).isEmpty())
        val confirmedServer = unconfirmedServer.copy(server = unconfirmedServer.server.copy(allowInsecureHttp = true))
        assertNotNull(DownloadHeaders.forUrl(confirmedServer, serverSource, serverSource.url)["Authorization"])
    }
}

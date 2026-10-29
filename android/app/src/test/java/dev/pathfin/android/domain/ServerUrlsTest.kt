package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class ServerUrlsTest {
    @Test fun normalizeKeepsProxyPathAndCanonicalizesOrigin() {
        val config = ServerUrls.normalize(" HTTPS://Media.Example:443/jellyfin/// ")
        assertEquals("https://media.example/jellyfin", config.baseUrl)
        assertEquals("https://media.example/jellyfin/Users/Me", ServerUrls.endpoint(config, "/Users/Me"))
    }

    @Test fun httpMustBeExplicitAndNeverImplied() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("http://192.168.1.2:8096") }
        assertEquals("http://192.168.1.2:8096", ServerUrls.normalize("http://192.168.1.2:8096", true).baseUrl)
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("192.168.1.2:8096") }
    }

    @Test fun credentialsQueryFragmentsAndTraversalAreRejected() {
        for (url in listOf("https://user:pass@example.com", "https://example.com/?api_key=secret", "https://example.com/#x", "https://example.com/a/../b", "https://example.com/a/%2e%2e/b", "file:///x", "https:///missing-host")) {
            assertThrows(url, IllegalArgumentException::class.java) { ServerUrls.normalize(url) }
        }
    }

    @Test fun ipv6OriginCanBeNormalized() {
        assertEquals("https://[::1]:8920/jellyfin", ServerUrls.normalize("https://[::1]:8920/jellyfin/").baseUrl)
    }
}

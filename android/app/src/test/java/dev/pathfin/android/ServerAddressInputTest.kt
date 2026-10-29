package dev.pathfin.android

import dev.pathfin.android.ui.serverConfigFromFields
import dev.pathfin.android.domain.ServerAddressInputState
import dev.pathfin.android.domain.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressInputTest {
    @Test
    fun normalizesSeparateFieldsAndPreservesEncodedProxyPath() {
        val config = serverConfigFromFields("MEDIA.EXAMPLE", "https", "443", "/jellyfin%20proxy/", false)
        assertEquals("https://media.example/jellyfin%20proxy", config.baseUrl)
    }

    @Test
    fun pastedAddressAllowsExplicitPortAndPathEdits() {
        val config = serverConfigFromFields("https://media.example:8443/old", "https", "9443", "new", false)
        assertEquals("https://media.example:9443/new", config.baseUrl)
    }

    @Test
    fun ipv6HostGetsSingleBracketPair() {
        assertEquals("http://[::1]:8096/jellyfin", serverConfigFromFields("::1", "http", "8096", "jellyfin", true).baseUrl)
        assertEquals("http://[::1]:8096", serverConfigFromFields("[::1]", "http", "8096", "", true).baseUrl)
    }

    @Test
    fun rejectsCredentialsQueryFragmentsUnsafePathsAndInvalidPorts() {
        listOf("https://user:password@media.example", "https://media.example?token=x", "https://media.example#private").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { serverConfigFromFields(host, "https", "443", "", false) }
        }
        listOf("0", "65536", "invalid").forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { serverConfigFromFields("media.example", "https", port, "", false) }
        }
        assertThrows(IllegalArgumentException::class.java) { serverConfigFromFields("media.example", "https", "443", "/../admin", false) }
    }

    @Test
    fun httpNeedsExplicitPermission() {
        assertThrows(IllegalArgumentException::class.java) { serverConfigFromFields("media.example", "http", "8096", "", false) }
        assertEquals("http://media.example:8096", serverConfigFromFields("media.example", "http", "8096", "", true).baseUrl)
    }

    @Test
    fun barePrivateIpShowsJellyfinHttpDefaultsButStillNeedsConfirmation() {
        for (host in listOf("192.168.1.2", "10.0.0.8", "172.16.0.8", "172.31.255.8")) {
            val input = ServerAddressInputState().withAddress(host)
            assertEquals("http", input.scheme)
            assertEquals("8096", input.port)
            assertThrows(IllegalArgumentException::class.java) { input.toServerConfig(false) }
            assertEquals("http://$host:8096", input.toServerConfig(true).baseUrl)
        }
    }

    @Test
    fun privateIpWithCustomPortDefaultsToHttpExceptForHttpsPorts() {
        for ((address, expected) in listOf(
            "192.168.88.2:18096" to "http://192.168.88.2:18096",
            "10.0.0.8:8080" to "http://10.0.0.8:8080",
            "192.168.1.2:443" to "https://192.168.1.2",
            "192.168.1.2:8920" to "https://192.168.1.2:8920",
        )) {
            assertEquals(address, expected, ServerAddressInputState().withAddress(address).toServerConfig(true).baseUrl)
        }
        assertEquals("公网域名的自定义端口仍按 HTTPS", "https", ServerAddressInputState().withAddress("media.example.com:18096").scheme)
        assertEquals("逐字输入端口时结论稳定", "http",
            "192.168.88.2:18096".let { full -> (1..full.length).fold(ServerAddressInputState()) { state, n -> state.withAddress(full.take(n)) } }.scheme)
    }

    @Test
    fun bareIpAndInlinePortAreNotTreatedAsAnIpv6Literal() {
        val input = ServerAddressInputState().withAddress("192.168.1.2:8096")
        assertEquals("http", input.scheme)
        assertEquals("8096", input.port)
        assertEquals("http://192.168.1.2:8096", input.toServerConfig(true).baseUrl)
        assertEquals("http://192.168.1.2:8096", serverConfigFromFields("192.168.1.2:8096", "http", "8096", "", true).baseUrl)
    }

    @Test
    fun explicitHttpsAndCustomPortsSurvivePrivateAddressInference() {
        val manualPort = ServerAddressInputState().withPort("9443").withAddress("192.168.1.2")
        assertEquals("https://192.168.1.2:9443", manualPort.toServerConfig(false).baseUrl)
        assertEquals("https://192.168.1.3:9443", manualPort.withAddress("192.168.1.3:8096").toServerConfig(false).baseUrl)
        val manual = ServerAddressInputState().withScheme("https").withPort("9443").withAddress("192.168.1.2")
        assertEquals("https://192.168.1.2:9443", manual.toServerConfig(false).baseUrl)
        assertEquals("https://192.168.1.3:9443", manual.withAddress("http://192.168.1.3:8096").toServerConfig(false).baseUrl)
        val pasted = ServerAddressInputState().withAddress("https://192.168.1.2:8443").withAddress("192.168.1.3")
        assertEquals("https://192.168.1.3:8443", pasted.toServerConfig(false).baseUrl)
        val explicitStandardPort = ServerAddressInputState().withPort("8096").withScheme("https").withAddress("192.168.1.2")
        assertEquals("https://192.168.1.2:8096", explicitStandardPort.toServerConfig(false).baseUrl)
    }

    @Test
    fun fullUrlsSynchronizeStandardPortsAndEncodedProxyPaths() {
        val http = ServerAddressInputState().withAddress("http://192.168.1.2/jellyfin%20proxy/")
        assertEquals("http", http.scheme)
        assertEquals("80", http.port)
        assertEquals("http://192.168.1.2/jellyfin%20proxy", http.toServerConfig(true).baseUrl)
        val https = http.withAddress("https://192.168.1.2:8443/old")
        assertEquals("https", https.scheme)
        assertEquals("8443", https.port)
        assertEquals("https://192.168.1.2:9443/new", https.withPort("9443").copy(path = "/new").toServerConfig(false).baseUrl)
    }

    @Test
    fun editingHttpAddressWithoutPortPreservesStandardPort80() {
        for (baseUrl in listOf("http://192.168.1.2/jellyfin", "http://192.168.1.2:80/jellyfin")) {
            val input = ServerAddressInputState.fromConfig(ServerConfig(baseUrl, true))
            assertEquals("80", input.port)
            assertEquals("http://192.168.1.2/jellyfin", input.toServerConfig(true).baseUrl)
            assertEquals("http://192.168.1.3/jellyfin", input.withAddress("192.168.1.3").toServerConfig(true).baseUrl)
        }
    }

    @Test
    fun bareAndBracketedIpv6AddressesKeepTheirWholeHost() {
        for (host in listOf("fd00::1234", "[fd00::1234]", "[fd00::1234]:8096")) {
            val input = ServerAddressInputState().withAddress(host)
            assertEquals("http://[fd00::1234]:8096", input.toServerConfig(true).baseUrl)
        }
        val publicIpv6 = ServerAddressInputState().withAddress("[2001:db8::1234]:8920")
        assertEquals("https://[2001:db8::1234]:8920", publicIpv6.toServerConfig(false).baseUrl)
    }

    @Test
    fun publicHostsAndCustomTlsPortsKeepHttps() {
        assertEquals("https://203.0.113.8", ServerAddressInputState().withAddress("203.0.113.8").toServerConfig(false).baseUrl)
        assertEquals("https://media.example:8443", ServerAddressInputState().withAddress("media.example:8443").toServerConfig(false).baseUrl)
        assertEquals("https://192.168.1.2:8920", ServerAddressInputState().withAddress("192.168.1.2:8920").toServerConfig(false).baseUrl)
    }

    @Test
    fun changingProtocolOnlyAdjustsPortsThatWereNotExplicitlyChosen() {
        val inferred = ServerAddressInputState().withAddress("192.168.1.2").withScheme("https")
        assertEquals("443", inferred.port)
        val explicit = ServerAddressInputState().withAddress("192.168.1.2").withPort("9443").withScheme("https")
        assertEquals("9443", explicit.port)
        assertEquals("https://192.168.1.2:9443", explicit.toServerConfig(false).baseUrl)
    }
}

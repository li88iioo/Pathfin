package dev.pathfin.android.domain

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LocalNetworkDestinationTest {
    @Test fun privateIpv4AndLocalNamesRequireAccess() {
        listOf("192.168.88.1", "10.0.0.2", "172.16.0.1", "172.31.255.254", "169.254.2.1", "100.64.0.2", "nas", "nas.local", "nas.local.")
            .forEach { assertTrue(it, LocalNetworkDestination.isLocalHost(it)) }
    }
    @Test fun publicAndLoopbackAddressesDoNotRequireLanPermission() {
        listOf("127.0.0.1", "127.0.0.9", "localhost", "localhost.", "[::1]", "172.15.0.1", "172.32.0.1", "192.167.0.1", "100.128.0.1", "8.8.8.8", "jellyfin.example.com")
            .forEach { assertFalse(it, LocalNetworkDestination.isLocalHost(it)) }
    }
    @Test fun ipv6LocalAndMappedPrivateAddressesAreCovered() {
        listOf("fd12::123", "[fc00::1]", "[fe80::2%25wlan0]", "::ffff:192.168.1.5")
            .forEach { assertTrue(it, LocalNetworkDestination.isLocalHost(it)) }
        assertFalse(LocalNetworkDestination.isLocalHost("2001:4860:4860::8888"))
    }
    @Test fun resolvedPrivateDomainAddressesUseSamePolicy() {
        assertTrue(LocalNetworkDestination.isLocalAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 2))))
        assertFalse(LocalNetworkDestination.isLocalAddress(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))
        assertFalse(LocalNetworkDestination.isLocalAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
    }
}

package dev.pathfin.android.casting

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SsdpDiscoveryTest {
    @Test fun privateLiteralAddressesDoNotResolveDnsOrAcceptPublicMulticastAndLoopback() {
        for (address in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2", "169.254.1.2")) assertNotNull(localIpv4(address))
        for (address in listOf("localhost", "tv.local", "127.0.0.1", "0.0.0.0", "239.255.255.250", "8.8.8.8", "172.15.0.1",
            "172.32.0.1", "192.168.01.2", "192.168.1.256", "192.168.1.2:80", "::1")) assertNull(address, localIpv4(address))
        assertNotNull(localIpv4("127.0.0.1", allowLoopback = true))
    }

    @Test fun ssdpOnlyAcceptsTheRespondingPrivateDeviceAndWellFormedRendererReply() {
        assertEquals("uuid:synthetic" to "http://192.168.1.20:1400/device.xml", SsdpDiscovery.parseReply(reply(), "192.168.1.20"))
        for (invalid in listOf(reply(location = "http://8.8.8.8/device.xml"), reply(location = "http://192.168.1.21/device.xml"),
            reply(location = "http://tv.local/device.xml"), reply(location = "http://user:pass@192.168.1.20/device.xml"),
            reply(location = "http://192.168.1.20/device.xml#fragment"), reply(st = "ssdp:all"),
            (reply().decodeToString().replace("\r\n\r\n", "\r\nLOCATION: http://192.168.1.20/other\r\n\r\n")).encodeToByteArray(),
            ByteArray(SsdpDiscovery.MAX_PACKET + 1))) assertNull(SsdpDiscovery.parseReply(invalid, "192.168.1.20"))
        assertNull(SsdpDiscovery.parseReply(reply(), "8.8.8.8"))
    }

    @Test fun descriptorRequiresMatchingUdnAndResolvesOnlySameDeviceControlUrl() {
        val device = parse(description())
        assertEquals("Synthetic TV & sound", device.name)
        assertEquals("http://192.168.1.20:1400/av/control", device.controlUrl)
        assertEquals("192.168.1.5", device.localAddress)
        for (xml in listOf(description(control = "http://192.168.1.21/control"), description(control = "http://8.8.8.8/control"),
            description(control = "http://tv.local/control"), description(udn = "uuid:another"),
            description().replace("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">", "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><URLBase>http://8.8.8.8/</URLBase>"))) {
            assertThrows(CastException::class.java) { parse(xml) }
        }
        val foreignEmbedded = description().replace("<serviceList>", "<deviceList><device><serviceList>").replace("</serviceList>", "</serviceList></device></deviceList>")
        assertThrows(CastException::class.java) { parse(foreignEmbedded) }
    }

    @Test fun xmlEntitiesMalformedAndOversizedDocumentsAreRejectedWithoutExternalReads() {
        for (xml in listOf("<!DOCTYPE root [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><root>&leak;</root>",
            "<!doctype root><root/>", "<root><broken></root>", "<root>" + "a".repeat(SsdpDiscovery.MAX_DESCRIPTION) + "</root>")) {
            assertThrows(CastException::class.java) { UpnpXml.parse(xml) }
        }
    }

    @Test fun actualLoopbackUdpSearchSendsMSearchAndUsesOnlyReceivedRendererReply() = runBlocking {
        val address = InetAddress.getByName("127.0.0.1")
        DatagramSocket(InetSocketAddress(address, 0)).use { renderer ->
            renderer.soTimeout = 2_000
            val executor = Executors.newSingleThreadExecutor()
            val received = executor.submit<String> {
                val packet = DatagramPacket(ByteArray(8192), 8192); renderer.receive(packet)
                val body = reply("http://127.0.0.1:1400/device.xml")
                renderer.send(DatagramPacket(body, body.size, packet.socketAddress))
                packet.data.copyOf(packet.length).decodeToString()
            }
            try {
                DatagramSocket(InetSocketAddress(address, 0)).use { socket ->
                    val replies = SsdpDiscovery.search(socket, InetSocketAddress(address, renderer.localPort), allowLoopback = true, timeoutMillis = 450)
                    assertEquals(mapOf("uuid:synthetic" to ("http://127.0.0.1:1400/device.xml" to "127.0.0.1")), replies)
                }
                val search = received.get(2, TimeUnit.SECONDS)
                assertTrue(search.startsWith("M-SEARCH * HTTP/1.1\r\n"))
                assertTrue(search.contains("MAN: \"ssdp:discover\""))
                assertTrue(search.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
                assertFalse(search.contains("Authorization", true))
            } finally { executor.shutdownNow() }
        }
    }

    private fun parse(xml: String) = SsdpDiscovery.parseDescription(xml, "http://192.168.1.20:1400/device.xml", "uuid:synthetic", "192.168.1.20", "192.168.1.5")
    private fun reply(location: String = "http://192.168.1.20:1400/device.xml", st: String = "urn:schemas-upnp-org:device:MediaRenderer:1") =
        "HTTP/1.1 200 OK\r\nST: $st\r\nUSN: uuid:synthetic::$st\r\nLOCATION: $location\r\n\r\n".encodeToByteArray()
    private fun description(control: String = "/av/control", udn: String = "uuid:synthetic") = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Synthetic TV &amp; sound</friendlyName><UDN>$udn</UDN><serviceList><service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$control</controlURL>
        </service></serviceList></device></root>"""
}

package dev.pathfin.android.casting

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AvTransportClientTest {
    @Test fun actualSoapControlsUseAvTransportAndOnlyLocalRelayMetadata() {
        val actions = CopyOnWriteArrayList<String>()
        val position = AtomicReference("00:00:03")
        val transport = AtomicReference("STOPPED")
        withRenderer({ exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPAction").substringAfter('#').trim('"')
            actions += action
            assertEquals("POST", exchange.requestMethod)
            assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("text/xml"))
            val raw = exchange.requestBody.readBytes().decodeToString()
            assertFalse(raw.contains("MediaBrowser")); assertFalse(raw.contains("api_key")); assertFalse(raw.contains("Token="))
            val xml = UpnpXml.parse(raw)
            assertEquals("0", UpnpXml.text(xml, "InstanceID"))
            when (action) {
                "SetAVTransportURI" -> {
                    assertEquals("http://127.0.0.1:1234/random-media", UpnpXml.text(xml, "CurrentURI"))
                    val metadata = UpnpXml.parse(UpnpXml.text(xml, "CurrentURIMetaData"))
                    assertEquals("A & B <title>", UpnpXml.text(metadata, "title"))
                    assertEquals("http://127.0.0.1:1234/random-media", UpnpXml.text(metadata, "res"))
                    assertTrue((metadata.getElementsByTagNameNS("*", "res").item(0) as org.w3c.dom.Element)
                        .getAttribute("protocolInfo").contains("video/mp4"))
                }
                "Play" -> { assertEquals("1", UpnpXml.text(xml, "Speed")); transport.set("PLAYING") }
                "Pause" -> transport.set("PAUSED_PLAYBACK")
                "Stop" -> transport.set("STOPPED")
                "Seek" -> { assertEquals("REL_TIME", UpnpXml.text(xml, "Unit")); position.set(UpnpXml.text(xml, "Target")) }
            }
            exchange.reply(action, when (action) {
                "GetTransportInfo" -> "<CurrentTransportState>${transport.get()}</CurrentTransportState>"
                "GetPositionInfo" -> "<TrackDuration>01:02:03</TrackDuration><RelTime>${position.get()}</RelTime>"
                else -> ""
            })
        }) { client -> runBlocking {
            client.setUri("http://127.0.0.1:1234/random-media", "A & B <title>", "video/mp4")
            client.play(); assertEquals("PLAYING", client.transportState())
            client.pause(); assertEquals("PAUSED_PLAYBACK", client.transportState())
            client.seek(95L * 10_000_000)
            assertEquals(RendererPosition(950_000_000, 37_230_000_000), client.position())
            client.stop(); assertEquals("STOPPED", client.transportState())
        } }
        assertEquals(listOf("SetAVTransportURI", "Play", "GetTransportInfo", "Pause", "GetTransportInfo", "Seek", "GetPositionInfo", "Stop", "GetTransportInfo"), actions.toList())
    }

    @Test fun soapFaultAndMissingActionConfirmationCannotReportSuccess() {
        val mode = AtomicReference("fault")
        withRenderer({ exchange ->
            if (mode.get() == "fault") exchange.raw("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
                <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>711</errorCode><errorDescription>private-device-text</errorDescription>
                </UPnPError></detail></s:Fault></s:Body></s:Envelope>""", 500)
            else exchange.reply("AnotherAction")
        }) { client ->
            val fault = assertThrows(CastException::class.java) { runBlocking { client.seek(10_000_000) } }
            assertEquals("此设备或片源不支持跳转进度", fault.message)
            assertFalse(fault.message.orEmpty().contains("private-device-text"))
            mode.set("missing")
            assertThrows(CastException::class.java) { runBlocking { client.play() } }
        }
    }

    @Test fun timeParserUsesRealReturnedPositionAndDoesNotInventUnknownDuration() {
        assertEquals(10_000_000L, AvTransportClient.timeTicks("00:00:01.999"))
        for (value in listOf("", "NOT_IMPLEMENTED", "00:60:00", "-01:00:00", "99", "00:00:60")) assertNull(AvTransportClient.timeTicks(value))
        withRenderer({ it.reply("GetPositionInfo", "<TrackDuration>NOT_IMPLEMENTED</TrackDuration><RelTime>00:00:09</RelTime>") }) { client -> runBlocking {
            assertEquals(RendererPosition(90_000_000, null), client.position())
        } }
    }

    @Test fun controlRedirectIsNeverFollowedAndForeignRelayOrControlIsRejected() {
        withRenderer({ exchange -> exchange.responseHeaders.add("Location", "http://8.8.8.8/control"); exchange.raw("", 302) }) { client ->
            assertThrows(CastException::class.java) { runBlocking { client.play() } }
            for (uri in listOf("https://example.com/media", "http://user:pass@127.0.0.1/media", "http://127.0.0.1/media#token")) {
                assertThrows(CastException::class.java) { runBlocking { client.setUri(uri, "Media", "video/mp4") } }
            }
        }
        val invalid = CastDevice("bad", "Bad", "192.168.1.20", "http://192.168.1.20/device", "http://8.8.8.8/control",
            "urn:schemas-upnp-org:service:AVTransport:1", "192.168.1.5")
        val client = AvTransportClient(invalid)
        try { assertThrows(CastException::class.java) { runBlocking { client.play() } } } finally { client.close() }
    }

    private fun withRenderer(handler: (HttpExchange) -> Unit, block: (AvTransportClient) -> Unit) {
        val error = AtomicReference<Throwable?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); server.executor = executor
        server.createContext("/control") { exchange -> try { handler(exchange) } catch (failure: Throwable) {
            error.compareAndSet(null, failure); runCatching { exchange.raw("", 500) }
        } finally { exchange.close() } }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        val client = AvTransportClient(CastDevice("synthetic", "Synthetic renderer", "127.0.0.1", "$origin/device", "$origin/control",
            "urn:schemas-upnp-org:service:AVTransport:1", "127.0.0.1"), allowLoopback = true)
        try { block(client) } finally { client.close(); server.stop(0); executor.shutdownNow(); error.get()?.let { throw it } }
    }
    private fun HttpExchange.reply(action: String, fields: String = "") = raw("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
        <s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$fields</u:${action}Response></s:Body></s:Envelope>""")
    private fun HttpExchange.raw(body: String, status: Int = 200) {
        val bytes = body.encodeToByteArray(); responseHeaders.add("Content-Type", "text/xml")
        sendResponseHeaders(status, bytes.size.toLong()); responseBody.use { it.write(bytes) }
    }
}

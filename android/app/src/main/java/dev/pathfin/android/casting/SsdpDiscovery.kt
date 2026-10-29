package dev.pathfin.android.casting

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.LocalNetworkPermissionDenied
import dev.pathfin.android.i18n.tr
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** M-SEARCH 回应必须是同一私网设备的描述，绝不按广播中的任意 URL 访问外网。 */
internal class SsdpDiscovery {
    suspend fun discover(context: Context): List<CastDevice> = withContext(Dispatchers.IO) {
        if (!LocalNetworkPermission.isGranted(context)) throw LocalNetworkPermissionDenied()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val candidate = connectivity.allNetworks.asSequence().mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            val local = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { localIpv4(it.hostAddress.orEmpty()) != null } ?: return@mapNotNull null
            network to local
        }.firstOrNull() ?: throw CastException(tr("请连接与电视相同的 Wi-Fi 或有线局域网；目前仅支持 IPv4 发现"))
        val (network, local) = candidate
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("pathfin-dlna-discovery")?.apply { setReferenceCounted(false) }
        val client = OkHttpClient.Builder().socketFactory(network.socketFactory).followRedirects(false).followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
        val socket = DatagramSocket(null)
        try {
            lock?.acquire()
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(local, 0)); socket.soTimeout = 350
            val descriptions = search(socket, InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900))
            val devices = ConcurrentLinkedQueue<CastDevice>()
            val slots = Semaphore(4)
            // 故障设备不能让整页等待 30 × HTTP 超时；保留期限内已成功读取的设备。
            withTimeoutOrNull(6_000) { coroutineScope {
                descriptions.map { (udn, entry) -> async {
                    slots.withPermit {
                        try {
                            val (url, address) = entry
                            val text = upnpText(client, Request.Builder().url(url).get().build(), MAX_DESCRIPTION)
                            devices += parseDescription(text, url, udn, address, local.hostAddress.orEmpty()).copy(network = network)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { /* 继续显示其他正常设备。 */ }
                    }
                } }.awaitAll()
            } }
            devices.distinctBy { it.id }.sortedBy { it.name }
        } finally {
            socket.close()
            runCatching { if (lock?.isHeld == true) lock.release() }
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        const val MAX_PACKET = 8192
        const val MAX_DESCRIPTION = 256 * 1024

        /** 生产固定组播目标；回环目标仅由同模块合成 UDP 测试注入。 */
        internal suspend fun search(socket: DatagramSocket, target: InetSocketAddress,
            allowLoopback: Boolean = false, timeoutMillis: Long = 4_000): Map<String, Pair<String, String>> {
            val payload = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
            repeat(2) { socket.send(DatagramPacket(payload, payload.size, target)) }
            val descriptions = linkedMapOf<String, Pair<String, String>>()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceIn(1, 4_000))
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 350).toInt()
                val packet = DatagramPacket(ByteArray(MAX_PACKET + 1), MAX_PACKET + 1)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (packet.length > MAX_PACKET) continue
                val sender = packet.address.hostAddress.orEmpty()
                val reply = parseReply(packet.data.copyOf(packet.length), sender, allowLoopback) ?: continue
                if (descriptions.size < 30) descriptions[reply.first] = reply.second to sender
            }
            return descriptions
        }

        internal fun parseReply(bytes: ByteArray, sender: String, allowLoopback: Boolean = false): Pair<String, String>? {
            if (bytes.size > MAX_PACKET || localIpv4(sender, allowLoopback) == null) return null
            val lines = bytes.toString(Charsets.ISO_8859_1).split("\r\n")
            if (!lines.firstOrNull().orEmpty().matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) return null
            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1).takeWhile(String::isNotEmpty)) {
                if (':' !in line || line.startsWith(' ') || line.startsWith('\t')) return null
                val name = line.substringBefore(':').lowercase(); val value = line.substringAfter(':').trim()
                if (headers.put(name, value) != null) return null
            }
            if (!headers["st"].orEmpty().matches(Regex("urn:schemas-upnp-org:device:MediaRenderer:[1-9][0-9]?"))) return null
            val udn = headers["usn"]?.substringBefore("::")?.takeIf { it.matches(Regex("uuid:[A-Za-z0-9_-]{1,128}")) } ?: return null
            val location = headers["location"] ?: return null
            if (!UpnpUrls.isDeviceUrl(location, sender, allowLoopback)) return null
            return udn to location
        }

        internal fun parseDescription(xml: String, descriptionUrl: String, expectedUdn: String, address: String,
            localAddress: String, allowLoopback: Boolean = false): CastDevice {
            if (!UpnpUrls.isDeviceUrl(descriptionUrl, address, allowLoopback)) throw CastException(tr("设备描述地址无效"))
            val document = UpnpXml.parse(xml)
            val base = document.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: descriptionUrl
            if (!UpnpUrls.isDeviceUrl(base, address, allowLoopback)) throw CastException(tr("设备描述包含其他主机地址"))
            val nodes = document.getElementsByTagNameNS("*", "device")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (UpnpXml.child(node, "UDN") != expectedUdn || !UpnpXml.child(node, "deviceType").matches(Regex("urn:schemas-upnp-org:device:MediaRenderer:[1-9][0-9]?"))) continue
                val serviceList = (0 until node.childNodes.length).map { node.childNodes.item(it) }
                    .firstOrNull { it.localName == "serviceList" || it.nodeName == "serviceList" } ?: continue
                val services = serviceList.childNodes
                for (serviceIndex in 0 until services.length) {
                    val service = services.item(serviceIndex)
                    if (service.localName != "service" && service.nodeName != "service") continue
                    val type = UpnpXml.child(service, "serviceType")
                    if (!type.matches(Regex("urn:schemas-upnp-org:service:AVTransport:[1-9][0-9]?"))) continue
                    val rawControl = UpnpXml.child(service, "controlURL")
                    val control = UpnpUrls.resolve(base, rawControl, address, allowLoopback) ?: continue
                    val name = UpnpXml.child(node, "friendlyName").filterNot(Char::isISOControl).take(100).ifBlank { "DLNA $address" }
                    return CastDevice("$expectedUdn@$address", name, address, descriptionUrl, control, type, localAddress)
                }
            }
            throw CastException(tr("此设备未提供可用的 AVTransport 控制服务"))
        }
    }
}

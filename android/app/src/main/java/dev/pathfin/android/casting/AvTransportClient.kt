package dev.pathfin.android.casting

import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object UpnpUrls {
    fun isDeviceUrl(value: String, address: String, allowLoopback: Boolean = false): Boolean {
        if (localIpv4(address, allowLoopback) == null || value.contains('\\') ||
            !(value.startsWith("http://") || value.startsWith("https://"))) return false
        val url = value.toHttpUrlOrNull() ?: return false
        return url.host == address && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && url.fragment == null &&
            value.none(Char::isISOControl)
    }
    fun resolve(base: String, relative: String, address: String, allowLoopback: Boolean = false): String? {
        if (relative.isBlank() || relative.contains('\\')) return null
        val url = base.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: return null
        return url.takeIf { isDeviceUrl(it, address, allowLoopback) }
    }
}

internal object UpnpXml {
    fun parse(text: String): Document {
        if (text.toByteArray(Charsets.UTF_8).size > SsdpDiscovery.MAX_DESCRIPTION ||
            Regex("<!\\s*(?:DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) throw CastException(tr("设备 XML 描述不安全或过大"))
        try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            // Android/JVM 实现支持的 feature 不同；DOCTYPE/ENTITY 已在解析前全部拒绝。
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd")) runCatching { factory.setFeature(feature, false) }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External entities disabled") }
            builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(error: org.xml.sax.SAXParseException) { throw error }
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            })
            return builder.parse(InputSource(StringReader(text)))
        } catch (_: Exception) { throw CastException(tr("无法读取设备 XML，请重试发现")) }
    }
    fun child(parent: Node, name: String): String {
        val nodes = parent.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.firstOrNull { it.localName == name || it.nodeName == name }?.textContent?.trim().orEmpty()
    }
    fun text(document: Document, name: String): String = document.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()
    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** 请求取消会关闭 Call；不跟随设备 HTTP 重定向，响应读取有界。 */
internal suspend fun upnpText(client: OkHttpClient, request: Request, limit: Int = 64 * 1024, allowSoapFault: Boolean = false): String =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(CastException(tr("无法连接 DLNA 设备，请检查设备和局域网")))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful && !(allowSoapFault && response.code == 500)) throw CastException(tr("DLNA 设备拒绝了请求，请刷新设备状态"))
                        val source = response.body.source()
                        if (source.request(limit.toLong() + 1)) throw CastException(tr("DLNA 设备响应过大，已停止读取"))
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(if (error is CastException) error else CastException(tr("读取 DLNA 设备响应失败")))
                    }
                }
            }
        })
    }

internal class AvTransportClient(private val device: CastDevice, client: OkHttpClient = OkHttpClient(),
    private val allowLoopback: Boolean = false) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun setUri(uri: String, title: String, mimeType: String) {
        val url = uri.toHttpUrlOrNull() ?: throw CastException(tr("临时媒体地址无效"))
        if (localIpv4(url.host, allowLoopback) == null || url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() || url.fragment != null)
            throw CastException(tr("临时媒体地址无效"))
        if (!mimeType.matches(Regex("(?:video|audio)/[A-Za-z0-9.+-]{1,64}"))) throw CastException(tr("此片源格式不能用于 DLNA"))
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" +
            UpnpXml.escape(title.filterNot(Char::isISOControl).take(512)) + "</dc:title><upnp:class>object.item.${if (mimeType.startsWith("audio/")) "audioItem" else "videoItem"}</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mimeType:*\">${UpnpXml.escape(uri)}</res></item></DIDL-Lite>"
        action("SetAVTransportURI", mapOf("CurrentURI" to uri, "CurrentURIMetaData" to didl))
    }
    suspend fun play() { action("Play", mapOf("Speed" to "1")) }
    suspend fun pause() { action("Pause") }
    suspend fun stop() { action("Stop") }
    suspend fun seek(positionTicks: Long) {
        require(positionTicks >= 0) { tr("投屏进度无效") }
        val seconds = positionTicks / 10_000_000
        val time = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        action("Seek", mapOf("Unit" to "REL_TIME", "Target" to time))
    }
    suspend fun transportState(): String = UpnpXml.text(action("GetTransportInfo"), "CurrentTransportState").uppercase(Locale.ROOT)
    suspend fun position(): RendererPosition {
        val document = action("GetPositionInfo")
        return RendererPosition(timeTicks(UpnpXml.text(document, "RelTime")) ?: 0,
            timeTicks(UpnpXml.text(document, "TrackDuration"))?.takeIf { it > 0 })
    }
    private suspend fun action(name: String, values: Map<String, String> = emptyMap()): Document {
        if (!UpnpUrls.isDeviceUrl(device.controlUrl, device.address, allowLoopback) ||
            !device.serviceType.matches(Regex("urn:schemas-upnp-org:service:AVTransport:[1-9][0-9]?"))) throw CastException(tr("设备控制地址无效，请重新发现"))
        val parameters = mapOf("InstanceID" to "0") + values
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$name xmlns:u=\"${device.serviceType}\">" +
            parameters.entries.joinToString("") { (key, value) -> "<$key>${UpnpXml.escape(value)}</$key>" } + "</u:$name></s:Body></s:Envelope>"
        val request = Request.Builder().url(device.controlUrl).header("SOAPAction", "\"${device.serviceType}#$name\"")
            .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        val result = UpnpXml.parse(upnpText(client, request, allowSoapFault = true))
        if (result.getElementsByTagNameNS("*", "Fault").length > 0) throw CastException(when (UpnpXml.text(result, "errorCode")) {
            "701", "705" -> tr("设备当前不能执行此操作，请刷新播放状态")
            "710", "711" -> tr("此设备或片源不支持跳转进度")
            "714", "716" -> tr("设备不支持此媒体格式或无法读取临时媒体地址")
            else -> tr("设备返回控制错误，请重试或更换片源")
        })
        if (result.getElementsByTagNameNS("*", "${name}Response").length == 0) throw CastException(tr("设备未确认此控制操作"))
        return result
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    companion object {
        fun timeTicks(value: String): Long? {
            val parts = Regex("(\\d{1,5}):([0-5]\\d):([0-5]\\d)(?:\\.\\d+)?").matchEntire(value)?.groupValues ?: return null
            return (parts[1].toLong() * 3600 + parts[2].toLong() * 60 + parts[3].toLong()) * 10_000_000
        }
    }
}

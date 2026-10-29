package dev.pathfin.android.data

import android.util.Log
import dev.pathfin.android.BuildConfig
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response

/** 调试包的脱敏耗时：只写固定操作名和数字，不写地址、查询、账号、Header或响应体。 */
internal object PerformanceTrace {
    private val sequence = AtomicLong()
    @Volatile private var navigation: Span? = null
    private val imageCount = AtomicInteger()

    class Span internal constructor(val operation: String) {
        val id = sequence.incrementAndGet()
        val started = System.nanoTime()
        fun point(phase: String, count: Long = -1) {
            log("id=$id op=$operation phase=$phase elapsed_ms=${millis(System.nanoTime() - started)} count=$count")
        }
    }
    fun begin(operation: String): Span = Span(operation).also { it.point("begin") }
    fun navigation(operation: String): Span = begin(operation).also { navigation = it; imageCount.set(0) }
    fun imageReady(source: String) {
        if (!BuildConfig.DEBUG) return
        val current = navigation ?: return
        val count = imageCount.incrementAndGet()
        if (count <= 6) current.point(when (source) {
            "MEMORY_CACHE" -> "image_memory_ready"
            "DISK" -> "image_disk_ready"
            "NETWORK" -> "image_network_ready"
            else -> "image_ready"
        }, count.toLong())
    }
    inline fun <T> measure(operation: String, action: () -> T): T {
        val span = begin(operation)
        return try { action() } finally { span.point("complete") }
    }
    fun apiOperation(path: String, query: Map<String, String>): String = when {
        path == "Users/AuthenticateByName" -> "auth"
        path == "Users/Me" -> "session_validate"
        path == "System/Info/Public" -> "server_info"
        path == "UserViews" -> "home_views"
        path == "Items/Latest" -> "home_latest"
        path == "UserItems/Resume" -> "resume"
        path == "Shows/NextUp" -> "next_up"
        path == "Items/Filters" -> "filters"
        path == "Items" && query.containsKey("ids") -> "item_detail"
        path == "Items" && query["filters"] == "IsResumable" -> "items_resume"
        path == "Items" && query.containsKey("searchTerm") -> "search"
        path == "Items" && query["limit"] == "12" && query.containsKey("parentId") -> "home_shelf"
        path == "Items" -> "items_browse"
        else -> "api_other"
    }
    fun monitor(base: OkHttpClient, fallbackOperation: String = "network"): OkHttpClient {
        if (!BuildConfig.DEBUG) return base
        return base.newBuilder().eventListenerFactory { call ->
            val span = call.request().tag(Span::class.java) ?: begin(fallbackOperation)
            base.eventListenerFactory.create(call).plus(NetworkTrace(span))
        }.build()
    }
    private class NetworkTrace(private val span: Span) : EventListener() {
        private var queueStarted = 0L
        private var queueNs = 0L
        private var dnsStarted = 0L
        private var dnsNs = 0L
        private var connectStarted = 0L
        private var connectNs = 0L
        private var tlsStarted = 0L
        private var tlsNs = 0L
        private var requestSent = 0L
        private var headersNs = 0L
        private var bytes = 0L
        private var attempts = 0
        private var status = 0
        override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) { queueStarted = System.nanoTime() }
        override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) {
            if (queueStarted != 0L) queueNs += System.nanoTime() - queueStarted
        }
        override fun dnsStart(call: Call, domainName: String) { dnsStarted = System.nanoTime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (dnsStarted != 0L) dnsNs += System.nanoTime() - dnsStarted
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStarted = System.nanoTime(); attempts++
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            if (connectStarted != 0L) connectNs += System.nanoTime() - connectStarted
        }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            if (connectStarted != 0L) connectNs += System.nanoTime() - connectStarted
        }
        override fun secureConnectStart(call: Call) { tlsStarted = System.nanoTime() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            if (tlsStarted != 0L) tlsNs += System.nanoTime() - tlsStarted
        }
        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { requestSent = System.nanoTime() }
        override fun requestBodyEnd(call: Call, byteCount: Long) { requestSent = System.nanoTime() }
        override fun responseHeadersEnd(call: Call, response: Response) {
            headersNs = if (requestSent == 0L) 0 else System.nanoTime() - requestSent
            status = response.code
            span.point("headers", status.toLong())
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bytes = byteCount }
        override fun callEnd(call: Call) { finish("complete") }
        override fun callFailed(call: Call, ioe: IOException) { finish("failed") }
        private fun finish(result: String) {
            log("id=${span.id} op=${span.operation} phase=network_$result total_ms=${millis(System.nanoTime() - span.started)} queue_ms=${millis(queueNs)} dns_ms=${millis(dnsNs)} connect_ms=${millis(connectNs)} tls_ms=${millis(tlsNs)} headers_ms=${millis(headersNs)} bytes=$bytes attempts=$attempts status=$status")
        }
    }
    private fun millis(nanos: Long) = nanos / 1_000_000
    private fun log(message: String) { if (BuildConfig.DEBUG) runCatching { Log.d("PathfinPerf", message) } }
}

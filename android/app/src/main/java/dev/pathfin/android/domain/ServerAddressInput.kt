package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.net.URI
import java.util.Locale

internal enum class ServerAddressFieldSource { Automatic, Address, Manual }

/** 表单的自动推断只修改尚未明确指定的协议和端口；HTTP 授权由界面单独保存。 */
internal data class ServerAddressInputState(
    val host: String = "",
    val scheme: String = "https",
    val port: String = "443",
    val path: String = "",
    val protocolSource: ServerAddressFieldSource = ServerAddressFieldSource.Automatic,
    val portSource: ServerAddressFieldSource = ServerAddressFieldSource.Automatic,
) {
    fun withAddress(value: String): ServerAddressInputState {
        val parsed = runCatching { parseAddress(value) }.getOrNull() ?: return copy(host = value)
        // 局域网地址默认 HTTP（反代、STRM 网关等常用自定义端口）；443 与 Jellyfin 的 HTTPS 默认端口 8920 仍按 HTTPS。
        val inferHttp = parsed.localHost && parsed.port != 443 && parsed.port != 8920
        val nextScheme = when {
            protocolSource == ServerAddressFieldSource.Manual -> scheme
            parsed.scheme != null -> parsed.scheme
            protocolSource == ServerAddressFieldSource.Address || portSource == ServerAddressFieldSource.Manual -> scheme
            portSource == ServerAddressFieldSource.Address && parsed.port == null -> scheme
            inferHttp -> "http"
            else -> "https"
        }
        val nextPort = when {
            portSource == ServerAddressFieldSource.Manual -> port
            parsed.port != null -> parsed.port.toString()
            parsed.scheme != null -> defaultPort(nextScheme).toString()
            portSource != ServerAddressFieldSource.Automatic || protocolSource != ServerAddressFieldSource.Automatic -> port
            inferHttp -> "8096"
            else -> "443"
        }
        return copy(
            host = value,
            scheme = nextScheme,
            port = nextPort,
            path = if (parsed.scheme != null || parsed.path.isNotEmpty()) parsed.path else path,
            protocolSource = when {
                protocolSource == ServerAddressFieldSource.Manual -> protocolSource
                parsed.scheme != null -> ServerAddressFieldSource.Address
                else -> protocolSource
            },
            portSource = when {
                portSource == ServerAddressFieldSource.Manual -> portSource
                parsed.port != null -> ServerAddressFieldSource.Address
                parsed.scheme != null -> ServerAddressFieldSource.Automatic
                else -> portSource
            },
        )
    }

    fun withScheme(value: String): ServerAddressInputState {
        val nextScheme = value.lowercase(Locale.ROOT)
        require(nextScheme in setOf("http", "https")) { tr("服务器仅支持 HTTP 或 HTTPS") }
        return copy(
            scheme = nextScheme,
            port = if (portSource != ServerAddressFieldSource.Automatic || nextScheme == scheme) port else if (nextScheme == "http") "8096" else "443",
            protocolSource = ServerAddressFieldSource.Manual,
        )
    }

    fun withPort(value: String): ServerAddressInputState = copy(port = value, portSource = ServerAddressFieldSource.Manual)

    /** 分离的控件值是最终选择，粘贴地址中的 host 不再连同端口误包成 IPv6。 */
    fun toServerConfig(allowHttp: Boolean): ServerConfig {
        val parsed = parseAddress(host)
        val chosenScheme = scheme.lowercase(Locale.ROOT)
        require(chosenScheme in setOf("http", "https")) { tr("服务器仅支持 HTTP 或 HTTPS") }
        val chosenPort = port.toIntOrNull()
        require(chosenPort != null && chosenPort in 1..65535) { tr("端口应为 1 到 65535") }
        val enclosedHost = parsed.host.let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        val chosenPath = path.trim().let { if (it.isBlank()) "" else "/${it.trimStart('/')}" }
        return ServerUrls.normalize("$chosenScheme://$enclosedHost:$chosenPort$chosenPath", allowHttp)
    }

    companion object {
        fun fromConfig(config: ServerConfig): ServerAddressInputState {
            val parsed = URI(ServerUrls.normalize(config).baseUrl)
            return ServerAddressInputState(
                host = parsed.host,
                scheme = parsed.scheme,
                port = (parsed.port.takeIf { it > 0 } ?: defaultPort(parsed.scheme)).toString(),
                path = parsed.rawPath.orEmpty(),
                protocolSource = ServerAddressFieldSource.Address,
                portSource = if (parsed.port > 0) ServerAddressFieldSource.Address else ServerAddressFieldSource.Automatic,
            )
        }
    }
}

private data class ParsedServerAddress(val host: String, val scheme: String?, val port: Int?, val path: String, val localHost: Boolean)

private fun parseAddress(value: String): ParsedServerAddress {
    val input = value.trim()
    require(input.isNotEmpty() && input.none { it <= ' ' || it == '\\' }) { tr("请输入有效的服务器地址") }
    val hasScheme = input.contains("://")
    val authority = input.substringBefore('/').substringBefore('?').substringBefore('#')
    val uriInput = when {
        hasScheme -> input
        !authority.startsWith('[') && authority.count { it == ':' } > 1 -> "https://[$authority]${input.removePrefix(authority)}"
        else -> "https://$input"
    }
    val parsed = runCatching { URI(uriInput) }.getOrNull()
        ?: throw IllegalArgumentException(tr("请输入有效的服务器地址，IPv6 端口请写成 [地址]:端口"))
    require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) { tr("服务器地址不能包含账号、查询参数或片段") }
    require(!parsed.host.isNullOrBlank()) { tr("请输入有效的服务器地址") }
    require(parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)) { tr("服务器仅支持 HTTP 或 HTTPS") }
    require(parsed.port == -1 || parsed.port in 1..65535) { tr("端口应为 1 到 65535") }
    return ParsedServerAddress(
        host = parsed.host,
        scheme = parsed.scheme.lowercase(Locale.ROOT).takeIf { hasScheme },
        port = parsed.port.takeIf { it > 0 },
        path = parsed.rawPath.orEmpty(),
        localHost = isLocalHost(parsed.host),
    )
}

/** 仅检查 IP 字面量，不做 DNS 查询，也不根据域名推断明文连接。 */
private fun isLocalHost(value: String): Boolean {
    val host = value.removeSurrounding("[", "]").substringBefore('%').lowercase(Locale.ROOT)
    if (host.contains(':')) {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        val firstGroup = host.substringBefore(':').toIntOrNull(16) ?: return false
        return (firstGroup and 0xfe00) == 0xfc00 || (firstGroup and 0xffc0) == 0xfe80
    }
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 || octets[0] == 127 ||
        octets[0] == 192 && octets[1] == 168 ||
        octets[0] == 172 && octets[1] in 16..31 ||
        octets[0] == 169 && octets[1] == 254
}

private fun defaultPort(scheme: String): Int = if (scheme.equals("http", true)) 80 else 443

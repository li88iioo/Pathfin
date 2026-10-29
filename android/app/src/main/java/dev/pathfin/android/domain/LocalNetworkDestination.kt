package dev.pathfin.android.domain

import java.net.InetAddress
import java.util.Locale

/** 不发起网络请求即可识别字面私网地址；回环不属于局域网访问。 */
object LocalNetworkDestination {
    fun isLocalHost(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]").substringBefore('%').trimEnd('.').lowercase(Locale.ROOT)
        if (value == "localhost" || value.endsWith(".localhost")) return false
        val octets = value.split('.')
        if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val numbers = octets.map { it.toIntOrNull() ?: return false }
            if (numbers.any { it !in 0..255 }) return false
            return when {
                numbers[0] == 127 -> false
                numbers[0] == 10 -> true
                numbers[0] == 172 && numbers[1] in 16..31 -> true
                numbers[0] == 192 && numbers[1] == 168 -> true
                numbers[0] == 169 && numbers[1] == 254 -> true
                numbers[0] == 100 && numbers[1] in 64..127 -> true
                else -> false
            }
        }
        if (':' in value) return runCatching { isLocalAddress(InetAddress.getByName(value)) }.getOrDefault(false)
        return value.isNotBlank() && ('.' !in value || value.endsWith(".local"))
    }

    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return false
        val bytes = address.address
        val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        return address.isSiteLocalAddress || address.isLinkLocalAddress || uniqueLocalV6 ||
            (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127)
    }
}

package dev.pathfin.android.casting

import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress

data class CastDevice internal constructor(
    val id: String,
    val name: String,
    val address: String,
    internal val descriptionUrl: String,
    internal val controlUrl: String,
    internal val serviceType: String,
    internal val localAddress: String,
    internal val network: Network? = null,
)

enum class CastStatus { IDLE, CONNECTING, PLAYING, PAUSED, STOPPED, ERROR }

data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    val status: CastStatus = CastStatus.IDLE,
    val busy: Boolean = false,
    val device: CastDevice? = null,
    val profileId: String? = null,
    val itemId: String? = null,
    val title: String = "",
    val positionTicks: Long = 0,
    val durationTicks: Long? = null,
    val error: String? = null,
    val authExpired: Boolean = false,
) {
    val active: Boolean get() = status in listOf(CastStatus.CONNECTING, CastStatus.PLAYING, CastStatus.PAUSED)
}

internal class CastException(message: String) : java.io.IOException(message)
internal data class RendererPosition(val positionTicks: Long, val durationTicks: Long?)

/** 只接受字面 IPv4，不触发 DNS；默认排除回环、公网、组播及未指定地址。 */
internal fun localIpv4(value: String, allowLoopback: Boolean = false): Inet4Address? {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { ch -> ch !in '0'..'9' } }) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it !in 0..255 } || numbers.map(Int::toString).joinToString(".") != value) return null
    val local = numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 ||
        numbers[0] == 192 && numbers[1] == 168 || numbers[0] == 169 && numbers[1] == 254 ||
        allowLoopback && numbers[0] == 127
    if (!local) return null
    return InetAddress.getByAddress(numbers.map(Int::toByte).toByteArray()) as Inet4Address
}

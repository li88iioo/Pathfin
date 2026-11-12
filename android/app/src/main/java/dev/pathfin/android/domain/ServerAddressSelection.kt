package dev.pathfin.android.domain

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** 同一服务器有多个地址时挑选本次连接用的地址：局域网优先，同类中保持当前地址，其后按添加顺序。 */
object ServerAddressSelection {
    const val PROBE_TIMEOUT_MS = 2_500L

    /** 只看字面地址（私网 IP、单段主机名、.local），不做 DNS 解析。 */
    fun isLocal(address: ServerConfig): Boolean =
        LocalNetworkDestination.isLocalHost(runCatching { URI(address.baseUrl).host }.getOrNull().orEmpty())

    fun ranked(profile: ServerProfile): List<ServerConfig> = profile.connectionAddresses.sortedBy { !isLocal(it) }

    /**
     * 并发探测全部地址，按优先级取第一个确认是同一台服务器的；排在前面的地址不通时最多等一个超时。
     * 未开启自动、只有一个地址、缺少 serverId 无从核对或全部不通时返回 null，由调用方沿用当前地址。
     */
    suspend fun choose(profile: ServerProfile, serverId: suspend (ServerConfig) -> String): ServerConfig? {
        val expected = profile.session.serverId
        val candidates = ranked(profile)
        if (!profile.autoAddress || expected.isBlank() || candidates.size < 2) return null
        return coroutineScope {
            val probes = candidates.map { address -> async { reachable(address, expected, serverId) } }
            candidates.indices.firstOrNull { probes[it].await() }?.let(candidates::get)
                .also { probes.forEach { it.cancel() } }
        }
    }

    private suspend fun reachable(address: ServerConfig, expected: String, serverId: suspend (ServerConfig) -> String): Boolean =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try { serverId(address) == expected } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
        } ?: false
}

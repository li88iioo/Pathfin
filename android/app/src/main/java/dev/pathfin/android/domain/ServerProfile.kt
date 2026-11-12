package dev.pathfin.android.domain

import java.util.UUID

/** 本机服务器显示偏好；会话及备用地址一同加密保存，不保存登录密码。 */
data class ServerProfile(
    val id: String,
    val session: Session,
    val displayName: String,
    val showUserName: Boolean = true,
    val showLastPlayed: Boolean = true,
    val lastPlayedAt: Long = 0,
    val addresses: List<ServerConfig> = emptyList(),
    /** 在全部地址中自动选择：局域网优先，连不上再用其他地址；手动指定某个地址后关闭。 */
    val autoAddress: Boolean = true,
) {
    /** 当前地址在前，其余为备用地址。 */
    val connectionAddresses: List<ServerConfig> get() = (listOf(session.server) + addresses).distinctBy { it.baseUrl }

    /** 换用另一个地址时原地址转为备用，不会从列表中丢失。 */
    fun withSession(updated: Session): ServerProfile =
        copy(session = updated, addresses = connectionAddresses.filter { it.baseUrl != updated.server.baseUrl })
}

/** 本机资料按登录地址区分；同一服务器的其他地址只有显式添加后才属于备用地址。 */
object ServerProfileIdentity {
    fun matches(first: Session, second: Session): Boolean = first.userId == second.userId &&
        (first.serverId.isBlank() || second.serverId.isBlank() || first.serverId == second.serverId) &&
        ServerUrls.normalize(first.server).baseUrl == ServerUrls.normalize(second.server).baseUrl

    /** 认证前还没有 userId，只在同一登录地址内寻找用户名对应的资料。 */
    fun forLogin(profiles: List<ServerProfile>, activeId: String?, server: ServerConfig, username: String): ServerProfile? {
        val address = ServerUrls.normalize(server).baseUrl
        fun matchesLogin(profile: ServerProfile) =
            ServerUrls.normalize(profile.session.server).baseUrl == address &&
                profile.session.userName.equals(username.trim(), ignoreCase = true)
        return profiles.firstOrNull { it.id == activeId && matchesLogin(it) }
            ?: profiles.firstOrNull(::matchesLogin)
    }

    /** 旧版本共享的设备身份只在重新认证时分离，不能给已有 token 换设备身份。 */
    fun deviceIdForLogin(profile: ServerProfile?, profiles: List<ServerProfile>): String {
        if (profile == null) return UUID.randomUUID().toString()
        return profile.session.deviceId.takeIf { id ->
            profiles.none { it.id != profile.id && it.session.deviceId == id }
        } ?: UUID.randomUUID().toString()
    }
}

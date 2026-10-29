package dev.pathfin.android.domain

/** 首页内容按本机资料及实际登录地址隔离，不能复用仅含 serverId/userId 的偏好键。 */
data class ProfileHomeKey(val profileId: String, val baseUrl: String, val serverId: String, val userId: String) {
    companion object {
        fun of(profileId: String, session: Session) = ProfileHomeKey(
            profileId, ServerUrls.normalize(session.server).baseUrl, session.serverId, session.userId,
        )
    }
}

/** 由 ViewModel 主线程拥有；仅保留内存快照，失效资料在激活新内容前禁止重新捕获旧页。 */
class ProfileHomeCache<T>(private val capacity: Int = 6) {
    private val entries = LinkedHashMap<ProfileHomeKey, T>(capacity, 0.75f, true)
    private val invalidatedProfiles = mutableSetOf<String>()

    init { require(capacity > 0) }

    operator fun get(key: ProfileHomeKey): T? = entries[key]

    fun put(key: ProfileHomeKey, snapshot: T) {
        if (isInvalidated(key.profileId)) return
        entries[key] = snapshot
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun invalidateProfile(profileId: String) {
        entries.keys.removeAll { it.profileId == profileId }
        invalidatedProfiles += profileId
    }

    fun isInvalidated(profileId: String): Boolean = profileId in invalidatedProfiles

    /** 调用方必须先用新会话发布干净首页，才能重新允许捕获。 */
    fun allowProfile(profileId: String) { invalidatedProfiles -= profileId }
}

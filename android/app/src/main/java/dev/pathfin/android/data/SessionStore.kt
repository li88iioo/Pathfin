package dev.pathfin.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.ServerProfileIdentity
import dev.pathfin.android.domain.ServerUrls
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import java.io.IOException
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Keystore 加密会话与服务器列表；兼容 v1，登录密码不进入本类。 */
class SessionStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pathfin_session", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): Session? {
        val session = readSession() ?: return null
        // 只有验证过 GCM 的 v1 会话才能迁移；绝不从列表自动恢复损坏的当前会话。
        val profiles = readProfiles()
        val existing = matchingProfile(profiles, session)
        val profile = existing?.copy(session = session) ?: profileFor(session)
        val selectedId = preferences.getString(ACTIVE_PROFILE, null)
        if (existing == null || existing.session != session || selectedId != profile.id) {
            commitProfiles(replacing(profiles, profile)) {
                putString(ACTIVE_PROFILE, profile.id)
            }
        }
        return session
    }

    @Synchronized
    fun save(session: Session) {
        validateSession(session)
        val profiles = readProfiles()
        val existing = matchingProfile(profiles, session)
        val profile = existing?.copy(session = session) ?: profileFor(session)
        val encrypted = encrypt(sessionJson(session), 1, SESSION_AAD)
        commitProfiles(replacing(profiles, profile)) {
            putString(SESSION, encrypted)
            putString(ACTIVE_PROFILE, profile.id)
        }
    }

    /** 退出当前会话，保留服务器列表与设备身份；删除账户须显式 removeProfile。 */
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    @Synchronized
    fun clear() {
        check(preferences.edit().remove(SESSION).remove(ACTIVE_PROFILE).commit()) {
            tr("无法清除登录会话。")
        }
    }

    @Synchronized
    fun loadProfiles(): List<ServerProfile> {
        // 首次打开列表也会迁移旧会话，但不会从列表恢复当前登录。
        if (!preferences.contains(PROFILES) && preferences.contains(SESSION)) load()
        return readProfiles()
    }

    @Synchronized
    fun upsertProfile(profile: ServerProfile) {
        validateProfile(profile)
        val profiles = readProfiles()
        val current = preferences.getString(ACTIVE_PROFILE, null) == profile.id && preferences.contains(SESSION)
        val encryptedSession = if (current) encrypt(sessionJson(profile.session), 1, SESSION_AAD) else null
        commitProfiles(replacing(profiles, profile)) {
            if (current) putString(SESSION, encryptedSession)
        }
    }

    @Synchronized
    fun removeProfile(id: String) {
        val profiles = readProfiles()
        val selected = preferences.getString(ACTIVE_PROFILE, null) == id
        commitProfiles(profiles.filterNot { it.id == id }) {
            if (selected) remove(SESSION).remove(ACTIVE_PROFILE)
        }
    }

    @Synchronized
    fun activeProfileId(): String? =
        if (preferences.contains(SESSION)) preferences.getString(ACTIVE_PROFILE, null) else null

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    @Synchronized
    fun selectProfile(id: String?) {
        if (id == null) {
            clear()
            return
        }
        val profile = readProfiles().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException(tr("该服务器已从本机移除，请重新添加。"))
        val encrypted = encrypt(sessionJson(profile.session), 1, SESSION_AAD)
        check(preferences.edit().putString(SESSION, encrypted).putString(ACTIVE_PROFILE, id).commit()) {
            tr("无法切换服务器，请检查设备存储空间。")
        }
    }

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    @Synchronized
    fun deviceId(): String {
        preferences.getString(DEVICE_ID, null)?.takeIf(String::isNotBlank)?.let { return it }
        return UUID.randomUUID().toString().also { id ->
            check(preferences.edit().putString(DEVICE_ID, id).commit()) {
                tr("无法保存设备标识，请检查设备存储空间。")
            }
        }
    }

    private fun readSession(): Session? {
        val encrypted = preferences.getString(SESSION, null) ?: return null
        return try {
            parseSession(decrypt(encrypted, 1, SESSION_AAD))
        } catch (_: GeneralSecurityException) {
            discardUnreadableSession()
        } catch (_: JSONException) {
            discardUnreadableSession()
        } catch (_: IllegalArgumentException) {
            discardUnreadableSession()
        } catch (_: IOException) {
            discardUnreadableSession()
        }
    }

    private fun readProfiles(): List<ServerProfile> {
        val encrypted = preferences.getString(PROFILES, null) ?: return emptyList()
        return try {
            val entries = decrypt(encrypted, 2, PROFILES_AAD).getJSONArray("profiles")
            (0 until entries.length()).map { index ->
                val json = entries.getJSONObject(index)
                val addresses = json.optJSONArray("addresses") ?: JSONArray()
                ServerProfile(
                    id = json.getString("id"),
                    session = parseSession(json.getJSONObject("session")),
                    displayName = json.getString("displayName"),
                    icon = json.optString("icon", ""),
                    showUserName = json.optBoolean("showUserName", true),
                    showLastPlayed = json.optBoolean("showLastPlayed", true),
                    lastPlayedAt = json.optLong("lastPlayedAt", 0),
                    addresses = (0 until addresses.length()).map { position ->
                        val address = addresses.getJSONObject(position)
                        ServerUrls.normalize(address.getString("baseUrl"), address.getBoolean("allowInsecureHttp"))
                    }.distinct(),
                ).also(::validateProfile)
            }.also { profiles -> require(profiles.map { it.id }.distinct().size == profiles.size) }
        } catch (_: GeneralSecurityException) {
            discardUnreadableProfiles()
        } catch (_: JSONException) {
            discardUnreadableProfiles()
        } catch (_: IllegalArgumentException) {
            discardUnreadableProfiles()
        } catch (_: IOException) {
            discardUnreadableProfiles()
        }
    }

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    private fun commitProfiles(
        profiles: List<ServerProfile>,
        extra: android.content.SharedPreferences.Editor.() -> Unit = {},
    ) {
        profiles.forEach(::validateProfile)
        require(profiles.map { it.id }.distinct().size == profiles.size)
        val array = JSONArray()
        profiles.forEach { profile ->
            val addresses = JSONArray()
            profile.addresses.distinct().forEach { address ->
                addresses.put(JSONObject().put("baseUrl", address.baseUrl).put("allowInsecureHttp", address.allowInsecureHttp))
            }
            array.put(JSONObject()
                .put("id", profile.id).put("session", sessionJson(profile.session))
                .put("displayName", profile.displayName).put("icon", profile.icon)
                .put("showUserName", profile.showUserName).put("showLastPlayed", profile.showLastPlayed)
                .put("lastPlayedAt", profile.lastPlayedAt).put("addresses", addresses))
        }
        val encrypted = encrypt(JSONObject().put("profiles", array), 2, PROFILES_AAD)
        val editor = preferences.edit().putString(PROFILES, encrypted)
        editor.extra()
        check(editor.commit()) { tr("无法保存服务器列表，请检查设备存储空间。") }
    }

    private fun profileFor(session: Session): ServerProfile {
        val account = listOf(ServerUrls.normalize(session.server).baseUrl, session.serverId, session.userId).joinToString("\u0000")
        return ServerProfile(
            id = UUID.nameUUIDFromBytes(account.toByteArray(Charsets.UTF_8)).toString(),
            session = session,
            displayName = runCatching { URI(session.server.baseUrl).host }.getOrNull().orEmpty().ifBlank { "Jellyfin" },
        )
    }

    private fun replacing(profiles: List<ServerProfile>, profile: ServerProfile): List<ServerProfile> =
        if (profiles.any { it.id == profile.id }) profiles.map { if (it.id == profile.id) profile else it }
        else profiles + profile

    private fun matchingProfile(profiles: List<ServerProfile>, session: Session): ServerProfile? {
        val selectedId = preferences.getString(ACTIVE_PROFILE, null)
        return profiles.firstOrNull { it.id == selectedId && ServerProfileIdentity.matches(it.session, session) }
            ?: profiles.firstOrNull { ServerProfileIdentity.matches(it.session, session) }
    }

    private fun validateSession(session: Session) {
        require(session.accessToken.isNotBlank() && session.userId.isNotBlank() && session.deviceId.isNotBlank())
        ServerUrls.normalize(session.server)
    }

    private fun validateProfile(profile: ServerProfile) {
        require(profile.id.isNotBlank() && profile.displayName.isNotBlank())
        require(profile.lastPlayedAt >= 0)
        validateSession(profile.session)
        profile.addresses.forEach { ServerUrls.normalize(it) }
    }

    private fun sessionJson(session: Session): JSONObject {
        validateSession(session)
        return JSONObject().put("baseUrl", session.server.baseUrl).put("allowInsecureHttp", session.server.allowInsecureHttp)
            .put("accessToken", session.accessToken).put("userId", session.userId).put("userName", session.userName)
            .put("deviceId", session.deviceId).put("serverId", session.serverId)
            .put("userImageTag", session.userImageTag).put("isAdministrator", session.isAdministrator)
            .put("canDownload", session.canDownload).put("canDelete", session.canDelete)
    }

    private fun parseSession(data: JSONObject): Session = Session(
        server = ServerUrls.normalize(data.getString("baseUrl"), data.getBoolean("allowInsecureHttp")),
        accessToken = data.getString("accessToken"), userId = data.getString("userId"),
        userName = data.getString("userName"), deviceId = data.getString("deviceId"),
        serverId = data.optString("serverId", ""),
        userImageTag = (data.opt("userImageTag") as? String)?.takeIf(String::isNotBlank),
        isAdministrator = data.optBoolean("isAdministrator", false),
        canDownload = data.optBoolean("canDownload", false), canDelete = data.optBoolean("canDelete", false),
    ).also(::validateSession)

    private fun encrypt(data: JSONObject, version: Int, aad: ByteArray): String {
        val plain = data.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = encryptionCipher(aad)
            return JSONObject().put("version", version).put("iv", encode(cipher.iv))
                .put("data", encode(cipher.doFinal(plain))).toString()
        } finally {
            plain.fill(0)
        }
    }

    private fun decrypt(encrypted: String, version: Int, aad: ByteArray): JSONObject {
        val envelope = JSONObject(encrypted)
        require(envelope.getInt("version") == version)
        val iv = decode(envelope.getString("iv"))
        require(iv.size == 12)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException(tr("会话密钥不可用"))
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
        }
        val plain = cipher.doFinal(decode(envelope.getString("data")))
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    private fun discardUnreadableSession(): Session? {
        check(preferences.edit().remove(SESSION).remove(ACTIVE_PROFILE).commit()) { tr("无法清除损坏的登录会话。") }
        return null
    }

    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    private fun discardUnreadableProfiles(): List<ServerProfile> {
        check(preferences.edit().remove(PROFILES).remove(ACTIVE_PROFILE).commit()) { tr("无法清除损坏的服务器列表。") }
        return emptyList()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        val existing = try { store.getKey(KEY_ALIAS, null) as? SecretKey } catch (_: UnrecoverableKeyException) {
            store.deleteEntry(KEY_ALIAS)
            null
        }
        existing?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(false).build())
        }.generateKey()
    }

    private fun encryptionCipher(aad: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        try { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) } catch (_: KeyPermanentlyInvalidatedException) {
            keyStore().deleteEntry(KEY_ALIAS)
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        updateAAD(aad)
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val SESSION = "encrypted_session_v1"
        const val PROFILES = "encrypted_profiles_v2"
        const val ACTIVE_PROFILE = "active_profile_id"
        const val DEVICE_ID = "device_id"
        const val KEY_ALIAS = "dev.pathfin.android.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val SESSION_AAD = "Pathfin session v1".toByteArray(Charsets.UTF_8)
        val PROFILES_AAD = "Pathfin profiles v2".toByteArray(Charsets.UTF_8)
    }
}

package dev.pathfin.android

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 只在独立验证包内操作合成账户，不启动 Activity 或连接服务器。 */
@RunWith(AndroidJUnit4::class)
class ServerProfilesStoreTest {
    private lateinit var context: Context
    private lateinit var store: SessionStore

    @Before
    fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification")) { "服务器存储测试只允许独立验证包" }
        check(preferences().edit().clear().commit())
        store = SessionStore(context)
    }

    @After
    fun clean() {
        check(preferences().edit().clear().commit())
    }

    @Test
    fun migratesAuthenticatedLegacySessionAndKeepsListOnClear() {
        val session = session(1)
        store.save(session)
        check(preferences().edit().remove("encrypted_profiles_v2").remove("active_profile_id").commit())
        assertEquals(session, store.load())
        val profiles = store.loadProfiles()
        assertEquals(1, profiles.size)
        assertEquals(session, profiles.single().session)
        assertEquals(profiles.single().id, store.activeProfileId())
        store.clear()
        assertNull(store.load())
        assertNull(store.activeProfileId())
        assertEquals(profiles, store.loadProfiles())
        store.selectProfile(profiles.single().id)
        assertEquals(session, store.load())
    }

    @Test
    fun switchingAndDeletingOneAccountPreserveOtherAccountsAndOrder() {
        val first = ServerProfile("first", session(1), "本地测试一")
        val second = ServerProfile("second", session(2), "本地测试二")
        store.upsertProfile(first)
        store.upsertProfile(second)
        assertNull(store.load())
        store.selectProfile(first.id)
        val changed = first.copy(displayName = "电影", session = first.session.copy(server = ServerConfig("https://alternate-1.invalid/jellyfin")),
            addresses = listOf(first.session.server), showUserName = false)
        store.upsertProfile(changed)
        assertEquals(changed.session, store.load())
        assertEquals(listOf("first", "second"), store.loadProfiles().map { it.id })
        store.selectProfile(second.id)
        assertEquals(second.session, store.load())
        store.removeProfile(second.id)
        assertNull(store.load())
        assertNull(store.activeProfileId())
        assertEquals(listOf(changed), store.loadProfiles())
        store.selectProfile(first.id)
        assertEquals(changed.session, store.load())
    }

    @Test
    fun sameServerAndUserAtTwoAddressesSurviveSaveRestoreAndTokenRefresh() {
        val firstSession = session(1)
        store.save(firstSession)
        val first = store.loadProfiles().single().copy(displayName = "保留的旧名称", icon = "🎬",
            showUserName = false, showLastPlayed = false, lastPlayedAt = 123L,
            addresses = listOf(ServerConfig("https://backup.invalid/jellyfin")))
        store.upsertProfile(first)
        val secondSession = firstSession.copy(server = ServerConfig("https://second.invalid/jellyfin"),
            accessToken = "synthetic-second-token")
        store.save(secondSession)
        val secondId = requireNotNull(store.activeProfileId())
        assertNotEquals(first.id, secondId)
        assertEquals(listOf(first.id, secondId), store.loadProfiles().map { it.id })
        assertEquals(first, store.loadProfiles().first())

        val reopened = SessionStore(context)
        assertEquals(secondSession, reopened.load())
        assertEquals(secondId, reopened.activeProfileId())
        val refreshed = secondSession.copy(accessToken = "synthetic-refreshed-token", userImageTag = "new-avatar", canDownload = true)
        reopened.save(refreshed)
        assertEquals(secondId, reopened.activeProfileId())
        assertEquals(first, reopened.loadProfiles().first())
        assertEquals(refreshed, reopened.loadProfiles().last().session)
        reopened.selectProfile(first.id)
        assertEquals(firstSession, reopened.load())
        assertEquals(listOf(first.id, secondId), reopened.loadProfiles().map { it.id })
    }

    @Test
    fun selectedLegacyProfileIdWinsWhenTwoExistingRecordsMatchTheSameEndpoint() {
        val first = ServerProfile("existing-first-id", session(1), "旧资料一", icon = "🎬")
        val selected = ServerProfile("existing-selected-id", first.session.copy(accessToken = "synthetic-selected-token"),
            "旧资料二", showUserName = false, addresses = listOf(ServerConfig("https://backup.invalid/jellyfin")))
        store.upsertProfile(first)
        store.upsertProfile(selected)
        store.selectProfile(selected.id)
        val reopened = SessionStore(context)
        assertEquals(selected.session, reopened.load())
        assertEquals(selected.id, reopened.activeProfileId())
        assertEquals(listOf(first, selected), reopened.loadProfiles())
        val refreshed = selected.session.copy(accessToken = "synthetic-refreshed-token", canDelete = true)
        reopened.save(refreshed)
        assertEquals(selected.id, reopened.activeProfileId())
        assertEquals(listOf(first, selected.copy(session = refreshed)), reopened.loadProfiles())
    }

    @Test
    fun activeIdCannotOverwriteAProfileWhoseKnownServerIdentityConflicts() {
        val firstSession = session(1)
        store.save(firstSession)
        val first = store.loadProfiles().single()
        val secondSession = firstSession.copy(serverId = "replacement-server", accessToken = "synthetic-replacement-token")
        store.save(secondSession)
        val secondId = requireNotNull(store.activeProfileId())
        assertNotEquals(first.id, secondId)
        // 模拟旧版留下的活动指针错配；真实加密会话仍属于第二台服务器。
        check(preferences().edit().putString("active_profile_id", first.id).commit())
        val reopened = SessionStore(context)
        assertEquals(secondSession, reopened.load())
        assertEquals(secondId, reopened.activeProfileId())
        assertEquals(first, reopened.loadProfiles().first())
        assertEquals(2, reopened.loadProfiles().size)
        reopened.removeProfile(secondId)
        assertEquals(listOf(first), reopened.loadProfiles())
        assertNull(reopened.load())
    }

    @Test
    fun sameEndpointReloginWithNewServerMetadataPreservesLegacyIdAndPreferences() {
        val legacy = session(1).copy(serverId = "")
        store.save(legacy)
        val profile = store.loadProfiles().single().copy(displayName = "自定义名称", icon = "🏠", lastPlayedAt = 456L,
            addresses = listOf(ServerConfig("https://backup.invalid/jellyfin")))
        store.upsertProfile(profile)
        val refreshed = legacy.copy(serverId = "known-server", accessToken = "synthetic-refreshed-token")
        store.save(refreshed)
        assertEquals(listOf(profile.copy(session = refreshed)), store.loadProfiles())
        assertEquals(profile.id, store.activeProfileId())
        assertEquals(refreshed, SessionStore(context).load())
    }

    @Test
    fun corruptedCurrentSessionNeverAutomaticallyRevivesFromEncryptedProfiles() {
        val profile = ServerProfile("one", session(1), "合成测试")
        store.upsertProfile(profile)
        store.selectProfile(profile.id)
        val envelope = JSONObject(requireNotNull(preferences().getString("encrypted_session_v1", null)))
        val data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        envelope.put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        check(preferences().edit().putString("encrypted_session_v1", envelope.toString()).commit())
        assertNull(store.load())
        assertNull(store.activeProfileId())
        assertEquals(listOf(profile), store.loadProfiles())
        assertNull(SessionStore(context).load())
    }

    @Test
    fun profileCiphertextDoesNotExposeTokensOrAcceptSessionEnvelope() {
        val profile = ServerProfile("one", session(1), "合成测试")
        store.upsertProfile(profile)
        store.selectProfile(profile.id)
        val encrypted = requireNotNull(preferences().getString("encrypted_profiles_v2", null))
        assertFalse(encrypted.contains(profile.session.accessToken))
        assertFalse(encrypted.contains(profile.session.userName))
        assertFalse(encrypted.contains(profile.session.server.baseUrl))
        val wrongEnvelope = requireNotNull(preferences().getString("encrypted_session_v1", null))
        check(preferences().edit().putString("encrypted_profiles_v2", wrongEnvelope).commit())
        assertEquals(emptyList<ServerProfile>(), store.loadProfiles())
        assertNull(store.activeProfileId())
    }

    private fun preferences() = context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE)

    private fun session(number: Int) = Session(
        server = ServerConfig("https://fixture-$number.invalid/jellyfin"),
        accessToken = "synthetic-token-$number-not-a-real-secret",
        userId = "synthetic-user-$number", userName = "synthetic-viewer-$number",
        deviceId = "synthetic-device", serverId = "synthetic-server-$number",
    )
}

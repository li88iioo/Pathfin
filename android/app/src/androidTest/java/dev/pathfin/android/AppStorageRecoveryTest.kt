package dev.pathfin.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.theme.ThemePreference
import dev.pathfin.android.ui.theme.ThemeStore
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 AppViewModel + 真实 SharedPreferences，仅故障提交和读取由门闩控制。
 * 所有文件使用本用例 UUID 前缀；不加载、备份或清空验证包已有账号和偏好。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AppStorageRecoveryTest {
    @Test
    fun continuousChangesMergeImmediatelyAndPersistInInvocationOrder() = isolated { fixture ->
        val model = fixture.start()
        val gate = fixture.gate()
        val writes = AtomicInteger()
        fixture.app.beforeCommit = { attempt ->
            if (attempt.isPreferences) {
                assertOffMain()
                if (writes.incrementAndGet() == 1) gate.block()
            }
            true
        }
        fixture.main { model.setPreferences(model.state.value.preferences.copy(showArtwork = false)) }
        gate.awaitEntered()
        lateinit var expected: AppPreferences
        fixture.main {
            model.setPreferences(model.state.value.preferences.copy(seekSeconds = 30))
            model.setPreferences(model.state.value.preferences.copy(blurAdultCovers = true))
            expected = fixture.original.copy(showArtwork = false, seekSeconds = 30, blurAdultCovers = true)
            assertEquals("下一交互必须立即读到上一修改", expected, model.state.value.preferences)
        }
        gate.release()
        awaitCondition("三个连续设置应全部完成持久化") { writes.get() == 3 && fixture.app.inFlight.get() == 0 }
        fixture.awaitState("旧保存结果不能覆盖最新设置") { it.preferences == expected }
        assertEquals(expected, AppPreferencesStore(fixture.app).load())
        assertEquals(expected, fixture.restart().state.value.preferences)
    }

    @Test
    fun twoFailedChangesRestoreLastCommittedPreferencesInsteadOfOptimisticIntermediate() = isolated { fixture ->
        val model = fixture.start()
        val gate = fixture.gate()
        val writes = AtomicInteger()
        fixture.app.beforeCommit = { attempt ->
            if (attempt.isPreferences) {
                assertOffMain()
                if (writes.incrementAndGet() == 1) gate.block()
                false
            } else true
        }
        fixture.main { model.setPreferences(model.state.value.preferences.copy(showArtwork = false)) }
        gate.awaitEntered()
        fixture.main {
            model.setPreferences(model.state.value.preferences.copy(seekSeconds = 45))
            assertFalse(model.state.value.preferences.showArtwork)
            assertEquals(45, model.state.value.preferences.seekSeconds)
        }
        gate.release()
        awaitCondition("两次失败提交必须实际执行") { writes.get() == 2 }
        val recovered = fixture.awaitState("失败后必须回到真正保存过的设置") {
            it.preferences == fixture.original && it.notice != null
        }
        assertEquals(fixture.original, recovered.preferences)
        assertEquals(fixture.original, AppPreferencesStore(fixture.app).load())
        assertFalse(recovered.loginBusy)
    }

    @Test
    fun failedNewestChangeKeepsTheEarlierSuccessfulCommit() = isolated { fixture ->
        val model = fixture.start()
        val gate = fixture.gate()
        val writes = AtomicInteger()
        val committed = fixture.original.copy(showArtwork = false)
        fixture.app.beforeCommit = { attempt ->
            if (attempt.isPreferences) {
                assertOffMain()
                val number = writes.incrementAndGet()
                if (number == 1) gate.block()
                number == 1
            } else true
        }
        fixture.main { model.setPreferences(committed) }
        gate.awaitEntered()
        fixture.main { model.setPreferences(model.state.value.preferences.copy(seekSeconds = 50)) }
        gate.release()
        awaitCondition("必须实际尝试两次保存") { writes.get() == 2 }
        fixture.awaitState("后一保存失败不能丢掉前一成功保存") {
            it.preferences == committed && it.notice != null
        }
        assertEquals(committed, AppPreferencesStore(fixture.app).load())
    }

    @Test
    fun successfulAccountRemovalCannotLeaveGhostLoginBecauseOfAnExtraClearCommit() = isolated { fixture ->
        val session = fixture.seedSession()
        val model = fixture.start()
        val profileId = requireNotNull(model.state.value.currentProfileId)
        assertEquals(session, model.state.value.session)
        fixture.app.beforeCommit = { attempt ->
            assertOffMain()
            // removeProfile 自身已原子移除当前会话。若还多做一次 clear，让第二提交失败。
            !(attempt.name == SESSION_STORE && SESSION_KEY in attempt.removed && PROFILE_KEY !in attempt.written)
        }
        fixture.main { model.logout() }
        val removed = fixture.awaitState("删除成功后不能残留只有内存存在的登录态") {
            !it.loginBusy && it.session == null && it.showServers
        }
        assertNull(removed.currentProfileId)
        assertFalse(removed.profiles.any { it.id == profileId })
        assertFalse("退出占位必须结束", "logout" in removed.actionBusy)
        assertNull(SessionStore(fixture.app).load())
        assertFalse(SessionStore(fixture.app).loadProfiles().any { it.id == profileId })
    }

    @Test
    fun failedAtomicAccountRemovalKeepsTheRecoverableAccountAndClearsBusyState() = isolated { fixture ->
        val session = fixture.seedSession()
        val model = fixture.start()
        val profileId = model.state.value.currentProfileId
        fixture.app.beforeCommit = { attempt ->
            assertOffMain()
            attempt.name != SESSION_STORE
        }
        fixture.main { model.logout() }
        val failed = fixture.awaitState("移除失败后必须退出忙碌态并显示错误") {
            !it.loginBusy && "logout" !in it.actionBusy && it.notice != null
        }
        assertEquals(session, failed.session)
        assertEquals(profileId, failed.currentProfileId)
        assertTrue(failed.profiles.any { it.id == profileId })
        assertEquals(session, SessionStore(fixture.app).load())
        assertEquals(profileId, SessionStore(fixture.app).activeProfileId())
        fixture.app.beforeCommit = { true }
        fixture.main { model.logout() }
        fixture.awaitState("存储恢复后重试退出必须成功") { !it.loginBusy && it.session == null && it.showServers }
    }

    @Test
    fun failedInitialAccountReadFinishesRestoringAndDoesNotEraseTheSavedAccount() = isolated { fixture ->
        val session = fixture.seedSession()
        val failNextRead = AtomicBoolean(true)
        fixture.app.beforeRead = { name, key ->
            if (name == SESSION_STORE && key == SESSION_KEY && failNextRead.compareAndSet(true, false)) {
                assertOffMain()
                throw IOException("合成的暂时读取失败")
            }
        }
        val model = fixture.start()
        val failed = model.state.value
        assertTrue(failed.showServers)
        assertNotNull(failed.loginError)
        assertFalse(failed.restoringSession)
        assertFalse(failed.loginBusy)
        assertEquals("读取失败不能顺便删除本机账号", session, SessionStore(fixture.app).load())
        fixture.app.beforeRead = { _, _ -> }
        val recovered = fixture.restart().state.value
        assertEquals(session, recovered.session)
        assertNotNull(recovered.currentProfileId)
        assertEquals(fixture.original, recovered.preferences)
        assertNull(recovered.loginError)
    }

    @Test
    fun cancellingAnAlreadyCommittingImportStillPublishesItsAtomicPreferencesAndTheme() = isolated { fixture ->
        val model = fixture.start()
        val gate = fixture.gate()
        val imported = fixture.original.copy(imageCacheSizeMb = 2048, subtitlesEnabled = false)
        fixture.app.beforeCommit = { attempt ->
            if (attempt.isImport) { assertOffMain(); gate.block() }
            true
        }
        val job = fixture.launch { model.importSettings(imported, ThemePreference.DARK) }
        gate.awaitEntered()
        fixture.main { job.cancel() }
        assertEquals(fixture.original, model.state.value.preferences)
        assertEquals(ThemePreference.LIGHT, model.state.value.theme)
        gate.release()
        runBlocking { withTimeout(TIMEOUT_MS) { job.join() } }
        fixture.awaitState("已提交导入即使调用方取消也要成对发布") {
            it.preferences == imported && it.theme == ThemePreference.DARK
        }
        assertEquals(imported, AppPreferencesStore(fixture.app).load())
        assertEquals(ThemePreference.DARK, ThemeStore(fixture.app).load())
    }

    @Test
    fun laterOrdinarySettingAndThemeChangesWinOverAnInFlightImport() = isolated { fixture ->
        val model = fixture.start()
        val gate = fixture.gate()
        val imported = fixture.original.copy(imageCacheSizeMb = 2048, subtitlesEnabled = false)
        val later = fixture.original.copy(seekSeconds = 35, showClock = false)
        fixture.app.beforeCommit = { attempt ->
            assertOffMain()
            if (attempt.isImport) gate.block()
            true
        }
        val job = fixture.launch { model.importSettings(imported, ThemePreference.DARK) }
        gate.awaitEntered()
        fixture.main {
            model.setPreferences(later)
            model.setTheme(ThemePreference.SYSTEM)
            assertEquals("后续普通设置必须即时生效", later, model.state.value.preferences)
        }
        gate.release()
        runBlocking { withTimeout(TIMEOUT_MS) { job.join() } }
        fixture.awaitState("较早的导入不能覆盖后续用户修改") {
            it.preferences == later && it.theme == ThemePreference.SYSTEM
        }
        awaitCondition("后续设置与主题必须实际保存") {
            AppPreferencesStore(fixture.app).load() == later && ThemeStore(fixture.app).load() == ThemePreference.SYSTEM
        }
        val restored = fixture.restart().state.value
        assertEquals(later, restored.preferences)
        assertEquals(ThemePreference.SYSTEM, restored.theme)
    }

    private fun isolated(block: (StorageFixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification") {
            "本机存储故障测试只允许独立 verification 包"
        }
        StorageFixture(instrumentation.targetContext).use(block)
    }

    private class StorageFixture(context: Context) : Closeable {
        val original = AppPreferences(openLastServer = false, imageCacheSizeMb = 512)
        val app = IsolatedStorageApplication(context)
        private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val gates = CopyOnWriteArrayList<StorageGate>()
        private lateinit var model: AppViewModel

        init { AppPreferencesStore(app).saveWithTheme(original, ThemePreference.LIGHT) }

        fun seedSession(): Session = Session(
            server = ServerConfig("https://storage-fixture.invalid"),
            accessToken = "storage-fixture-token-${UUID.randomUUID()}",
            userId = "storage-fixture-user", userName = "Storage Fixture", deviceId = "storage-fixture-device",
            serverId = "storage-fixture-server",
        ).also { SessionStore(app).save(it) }

        fun start(): AppViewModel {
            main { model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[AppViewModel::class.java] }
            awaitState("初次恢复必须结束") { !it.restoringSession }
            return model
        }

        fun restart(): AppViewModel {
            app.beforeCommit = { true }
            main { owner.viewModelStore.clear() }
            return start()
        }

        fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

        fun gate(): StorageGate = StorageGate().also(gates::add)

        fun launch(action: suspend () -> Unit): Job {
            lateinit var job: Job
            main { job = scope.launch { action() } }
            return job
        }

        fun awaitState(message: String, predicate: (AppState) -> Boolean): AppState = try {
            runBlocking { withTimeout(TIMEOUT_MS) { model.state.first(predicate) } }
        } catch (error: Exception) { throw AssertionError(message, error) }

        override fun close() {
            app.beforeCommit = { true }
            app.beforeRead = { _, _ -> }
            gates.forEach(StorageGate::release)
            main { scope.cancel(); owner.viewModelStore.clear() }
            awaitCondition("临时存储不能仍有后台写入") { app.inFlight.get() == 0 }
            app.deleteOwnFiles()
        }
    }

    private class StorageGate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun block() {
            entered.countDown()
            check(released.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "测试未释放存储门闩" }
        }
        fun awaitEntered() = assertTrue("合成保存必须实际进入 IO", entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        fun release() = released.countDown()
    }

    private data class CommitAttempt(val name: String, val written: Set<String>, val removed: Set<String>) {
        val isPreferences get() = name == PREFERENCES_STORE && "settings" in written
        val isImport get() = isPreferences && "theme" in written
    }

    /** 只把唯一前缀文件交给生产 Store；生产实现无需接受任何测试注入接口。 */
    private class IsolatedStorageApplication(private val target: Context) : Application() {
        private val prefix = "pathfin-storage-verification-${UUID.randomUUID()}-"
        private val stores = ConcurrentHashMap<String, SharedPreferences>()
        val inFlight = AtomicInteger()
        @Volatile var beforeCommit: (CommitAttempt) -> Boolean = { true }
        @Volatile var beforeRead: (String, String?) -> Unit = { _, _ -> }

        init { attachBaseContext(target) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) {
            val delegate = target.getSharedPreferences(prefix + name, mode)
            object : SharedPreferences by delegate {
                override fun getString(key: String?, defValue: String?): String? {
                    beforeRead(name, key)
                    return delegate.getString(key, defValue)
                }
                override fun edit(): SharedPreferences.Editor = FaultEditor(name, delegate.edit())
            }
        }

        fun deleteOwnFiles() = stores.keys.forEach { name ->
            check(target.deleteSharedPreferences(prefix + name)) { "无法清理本次合成存储" }
        }

        private inner class FaultEditor(
            private val name: String,
            private val editor: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by editor {
            private val written = mutableSetOf<String>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = edit(key) { putString(key, value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = edit(key) { putStringSet(key, values) }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = edit(key) { putInt(key, value) }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = edit(key) { putLong(key, value) }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = edit(key) { putFloat(key, value) }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = edit(key) { putBoolean(key, value) }
            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let { removed += it; written -= it }
                editor.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                editor.clear()
                return this
            }
            private inline fun edit(key: String?, operation: SharedPreferences.Editor.() -> Unit): SharedPreferences.Editor {
                key?.let { written += it; removed -= it }
                editor.operation()
                return this
            }
            override fun commit(): Boolean {
                inFlight.incrementAndGet()
                return try {
                    beforeCommit(CommitAttempt(name, written.toSet(), removed.toSet())) && editor.commit()
                } finally { inFlight.decrementAndGet() }
            }
            override fun apply(): Unit = error("故障测试不允许 apply 绕过同步提交")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val PREFERENCES_STORE = "pathfin_preferences"
        const val SESSION_STORE = "pathfin_session"
        const val SESSION_KEY = "encrypted_session_v1"
        const val PROFILE_KEY = "encrypted_profiles_v2"

        fun assertOffMain() = assertFalse("加密与磁盘提交必须在后台线程", Looper.getMainLooper().isCurrentThread)

        fun awaitCondition(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                SystemClock.sleep(20)
            }
            assertTrue(message, predicate())
        }
    }
}

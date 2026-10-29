package dev.pathfin.android.download

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkManager
import coil3.ImageLoader
import dev.pathfin.android.VerificationDisplay
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.DownloadNetwork
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.ServerProfile
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Store/WorkManager/SAF；下载记录使用唯一存储前缀，系统 Worker 找不到这些测试任务，不会发出网络请求。 */
@RunWith(AndroidJUnit4::class)
class DownloadQueueRaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext

    @Test fun orphanedCommitIsScheduledBeforeTheFirstObservedSnapshot() = withFixture { fixture ->
        val task = DownloadTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.profileId,
            "orphan", "Synthetic orphan", fixture.document().toString())
        DownloadStore(fixture.context).insert(task)
        AppPreferencesStore(fixture.context).save(AppPreferences(downloadNetwork = DownloadNetwork.UNMETERED))
        runBlocking { withTimeout(15_000) { MediaDownloads.observe(fixture.context).first() } }
        assertUnmetered(fixture, task.id)
        val restored = checkNotNull(DownloadStore(fixture.context).get(task.id))
        assertNotEquals(task.workId, restored.workId)
        assertEquals(task.destinationUri, restored.destinationUri)
        assertEquals(task.profileId, restored.profileId)
    }

    @Test fun cancelledCommitStopsItsSystemRequestBeforeTheFirstObservedSnapshot() = withFixture { fixture ->
        runBlocking {
            for (replaced in listOf(false, true)) {
                val task = DownloadTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.profileId,
                    "cancelled", "Synthetic cancelled", fixture.document().toString())
                val store = DownloadStore(fixture.context)
                store.insert(task)
                defer(fixture, task)
                val current = if (replaced) checkNotNull(store.update(task.id, task.workId) { it.nextAttempt() }) else task
                store.cancel(task.id, current.workId)
                withTimeout(15_000) { MediaDownloads.observe(fixture.context).first() }
                assertEquals(androidx.work.WorkInfo.State.CANCELLED,
                    fixture.manager.getWorkInfoById(UUID.fromString(task.workId)).get(10, TimeUnit.SECONDS)?.state)
                assertEquals(DownloadStatus.CANCELLED, store.get(task.id)?.status)
            }
        }
    }

    @Test fun interruptedGenerationCommitReplacesTheOldPlanUsingTheSavedPolicy() = withFixture { fixture ->
        runBlocking {
            val store = DownloadStore(fixture.context)
            val before = DownloadTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.profileId,
                "generation", "Synthetic generation", fixture.document().toString())
            store.insert(before)
            defer(fixture, before)
            val next = checkNotNull(store.update(before.id, before.workId) { it.copy(workId = UUID.randomUUID().toString()) })
            AppPreferencesStore(fixture.context).save(AppPreferences(downloadNetwork = DownloadNetwork.UNMETERED))
            withTimeout(15_000) { MediaDownloads.observe(fixture.context).first() }
            assertUnmetered(fixture, before.id)
            val restored = checkNotNull(store.get(before.id))
            assertNotEquals(before.workId, restored.workId)
            assertNotEquals(next.workId, restored.workId)
            assertEquals(next.destinationUri, restored.destinationUri)
            val previous = fixture.manager.getWorkInfoById(UUID.fromString(before.workId)).get(10, TimeUnit.SECONDS)
            assertTrue("旧计划必须终结或已被REPLACE删除，不能继续执行", previous?.state?.isFinished != false)
        }
    }

    @Test fun terminalBusinessResultDoesNotCancelItsUnfinishedSystemPlan() = withFixture { fixture ->
        runBlocking {
            for (status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)) {
                val task = DownloadTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.profileId,
                    "finishing", "Synthetic finishing", fixture.document().toString())
                val store = DownloadStore(fixture.context)
                store.insert(task)
                defer(fixture, task)
                store.update(task.id, task.workId) { it.copy(status = status) }
                withTimeout(15_000) { MediaDownloads.observe(fixture.context).first() }
                assertEquals("正常结果已提交，尚未终结的WorkSpec不能被恢复逻辑取消", androidx.work.WorkInfo.State.ENQUEUED,
                    fixture.manager.getWorkInfoById(UUID.fromString(task.workId)).get(10, TimeUnit.SECONDS)?.state)
                assertEquals(status, store.get(task.id)?.status)
            }
        }
    }

    @Test fun explicitRetrySurvivesALateStopForItsRevokedGeneration() = withFixture { fixture ->
        runBlocking {
            val task = DownloadTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.profileId,
                "late-stop", "Synthetic late stop", fixture.document().toString())
            val store = DownloadStore(fixture.context)
            store.insert(task)
            defer(fixture, task)
            val revoked = checkNotNull(store.cancel(task.id, task.workId))
            MediaDownloads.retry(fixture.context, task.id)
            val replacement = checkNotNull(store.get(task.id))
            fixture.manager.cancelWorkById(UUID.fromString(revoked.workId)).result.get(10, TimeUnit.SECONDS)
            assertNotEquals(task.workId, replacement.workId)
            assertNotEquals(androidx.work.WorkInfo.State.CANCELLED,
                fixture.manager.getWorkInfoById(UUID.fromString(replacement.workId)).get(10, TimeUnit.SECONDS)?.state)
            assertNotEquals(DownloadStatus.CANCELLED, store.get(task.id)?.status)
            // 与旧通知接收器完全相同的代次CAS入口，不能撤销重试之后的新任务。
            MediaDownloads.cancel(fixture.context, task.id, task.workId)
            assertNotEquals(DownloadStatus.CANCELLED, store.get(task.id)?.status)
        }
    }

    @Test fun unrelatedBrokenPlanCannotFailANewEnqueueOrEscapeTheObservedList() = withFixture { fixture ->
        runBlocking {
            // 已落盘标识超过WorkManager Data的10KiB上限，fresh workId也不能修复真实排程失败。
            val broken = DownloadTask("oversized-${"x".repeat(11_000)}", UUID.randomUUID().toString(), fixture.profileId,
                "broken", "Synthetic broken plan", fixture.document().toString())
            val store = DownloadStore(fixture.context)
            store.insert(broken)
            val id = MediaDownloads.enqueue(fixture.context, fixture.profileId, "new", "Synthetic new plan", fixture.document())
            val requested = checkNotNull(store.get(id))
            assertNotNull(fixture.manager.getWorkInfoById(UUID.fromString(requested.workId)).get(10, TimeUnit.SECONDS))
            val observed = withTimeout(15_000) { MediaDownloads.observe(fixture.context).first { rows ->
                rows.any { it.id == broken.id && it.status == DownloadStatus.FAILED }
            } }
            assertTrue(observed.any { it.id == id })
            assertEquals(requested.workId, store.get(id)?.workId)
        }
    }

    private fun defer(fixture: Fixture, task: DownloadTask) {
        val request = androidx.work.OneTimeWorkRequestBuilder<MediaDownloadWorker>().setId(UUID.fromString(task.workId))
            .setInputData(androidx.work.workDataOf(MediaDownloadWorker.TASK_ID to task.id)).addTag(MediaDownloads.WORK_TAG)
            .setConstraints(downloadConstraints(DownloadNetwork.ANY)).setInitialDelay(1, TimeUnit.DAYS).build()
        fixture.manager.enqueueUniqueWork("pathfin-download-${task.id}", androidx.work.ExistingWorkPolicy.REPLACE, request)
            .result.get(10, TimeUnit.SECONDS)
    }

    @Test fun savingANewNetworkRuleWhileEnqueueHoldsTheOldValueCannotMissTheNewlyInsertedTask() = withFixture { fixture ->
        val context = fixture.context
        val firstDestination = fixture.document()
        val secondDestination = fixture.document()
        val preferences = AppPreferencesStore(context)
        preferences.save(AppPreferences(downloadNetwork = DownloadNetwork.ANY))
        val read = context.blockNextSettingsRead()
        runBlocking {
            withContext(Dispatchers.IO) {
                val enqueue = async { MediaDownloads.enqueue(context, fixture.profileId, "first", "Synthetic first", firstDestination) }
                try {
                    // 已捕获 ANY，但尚未 insert。新规则先落盘，再开始重排。
                    read.awaitEntered()
                    preferences.save(AppPreferences(downloadNetwork = DownloadNetwork.UNMETERED))
                    val reconfigure = async(start = CoroutineStart.UNDISPATCHED) {
                        MediaDownloads.recover(context)
                    }
                    // 同为 IO 上下文，UNDISPATCHED 会运行到真实 Mutex 挂起点；不靠固定延时猜时序。
                    assertFalse("重排不能先于持旧策略的入队完成，否则会漏过该任务", reconfigure.isCompleted)
                    read.release()
                    val first = withTimeout(15_000) { enqueue.await() }
                    withTimeout(15_000) { reconfigure.await() }
                    assertUnmetered(fixture, first)

                    val second = MediaDownloads.enqueue(context, fixture.profileId, "second", "Synthetic second", secondDestination)
                    assertUnmetered(fixture, second)
                    assertEquals(2, DownloadStore(context).all().size)
                    assertNull("系统 Worker 不能读取隔离记录或访问测试之外的账号", DownloadStore(target).get(first))
                } finally { read.release() }
            }
        }
    }

    @Test fun retryAfterNetworkReconfigurationUsesTheLatestSavedRuleForItsNewGeneration() = withFixture { fixture ->
        val destination = fixture.document()
        val store = DownloadStore(fixture.context)
        val task = DownloadTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), fixture.profileId,
            "retry", "Synthetic retry", destination.toString(), status = DownloadStatus.FAILED)
        store.insert(task)
        AppPreferencesStore(fixture.context).save(AppPreferences(downloadNetwork = DownloadNetwork.UNMETERED))
        runBlocking {
            MediaDownloads.recover(fixture.context)
            MediaDownloads.retry(fixture.context, task.id)
        }
        val retried = checkNotNull(store.get(task.id))
        assertNotEquals(task.workId, retried.workId)
        assertUnmetered(fixture, task.id)
    }

    @Test fun recreatingTheCompositionDuringSlowDefaultDirectoryCreationCannotEnqueueTheSameSelectionTwice() =
        defaultDirectorySurvivesRecreation(recreateActivity = false)

    @Test fun recreatingTheActivityDuringSlowDefaultDirectoryCreationKeepsOneDocumentAndOneTask() =
        defaultDirectorySurvivesRecreation(recreateActivity = true)

    private fun defaultDirectorySurvivesRecreation(recreateActivity: Boolean) = withFixture { fixture ->
        DownloadSourceFixture().use { source ->
            val context = fixture.context
            val tree = fixture.tree(source.fileName)
            runBlocking { DownloadDirectories.persist(context, tree) }
            AppPreferencesStore(context).save(AppPreferences(openLastServer = false, downloadDirectoryUri = tree.toString()))
            val sessions = SessionStore(context)
            val profile = sessions.loadProfiles().single()
            sessions.upsertProfile(profile.copy(session = profile.session.copy(server = ServerConfig(source.baseUrl, true))))
            sessions.selectProfile(profile.id)
            // 只读取合成存储；不触发一次性迁移去操作非本测试的旧文件或密钥。
            check(context.getSharedPreferences("pathfin_removed_features", Context.MODE_PRIVATE)
                .edit().putBoolean("trakt_v1_removed", true).commit())
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            val vm = main { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context))[AppViewModel::class.java] }
            val loader = ImageLoader.Builder(context).diskCache(null).build()
            val generation = mutableIntStateOf(0)
            val shown = mutableStateOf(true)
            val composed = AtomicInteger(-1)
            val disposed = AtomicInteger(-1)
            fun control(action: String): Bundle = checkNotNull(instrumentation.context.contentResolver.call(
                Uri.parse("content://${VerificationDownloadDocumentsProvider.authority(instrumentation.context)}"),
                VerificationDownloadDocumentsProvider.CREATE_CONTROL + action, source.fileName, null))
            var registered = false
            try {
                control("register"); registered = true
                await("合成会话应读取完成，不验证外部服务器") { !vm.state.value.restoringSession }
                main { vm.openSettings(); vm.prepareDownload(MediaItem("pending-item", "Synthetic pending", "Movie")) }
                await("真实 VM 应准备好本次下载") { vm.state.value.pendingDownload != null }
                val selection = checkNotNull(vm.state.value.pendingDownload)
                assertEquals(source.fileName, selection.fileName)
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    val state by vm.state.collectAsState()
                    val saved = rememberSaveableStateHolder()
                    // 隔离持久数据仍保留实际 Activity 的系统结果注册表，不能用 Application 替代它。
                    val activityResults = checkNotNull(LocalActivityResultRegistryOwner.current)
                    CompositionLocalProvider(LocalContext provides context,
                        LocalActivityResultRegistryOwner provides activityResults) {
                        PathfinTheme(ThemePreference.LIGHT) {
                            if (shown.value) key(generation.intValue) {
                                val current = generation.intValue
                                DisposableEffect(Unit) { onDispose { disposed.set(current) } }
                                SideEffect { composed.set(current) }
                                // 真实 App 根组件，保留同一个 VM；不复制其入队 LaunchedEffect。
                                PathfinApp(vm, state, saved, posterLoader = loader)
                            }
                        }
                    }
                }
                fixture.show(content)
                await("旧 Composition 必须实际进入跨 APK 的 createDocument") { control("status").getInt("entered") == 1 }
                assertEquals(selection.requestId, vm.state.value.pendingDownload?.requestId)
                if (recreateActivity) fixture.recreate()
                main { generation.intValue++ }
                if (recreateActivity) fixture.show(content)
                await("旧根作用域已释放，新根已重建且 VM 继续保留") { disposed.get() == 0 && composed.get() == 1 }
                instrumentation.waitForIdleSync()
                assertEquals(selection.requestId, vm.state.value.pendingDownload?.requestId)
                control("release")
                await("入队完成后应消费待选请求") { vm.state.value.pendingDownload == null && DownloadStore(context).all().isNotEmpty() }
                // 正确实现只创建一次；给取消中的旧根及新根等待者一个有界的真实执行窗口。
                runBlocking { withTimeout(3_000) {
                    repeat(60) { if (DownloadStore(context).all().size < 2) delay(25) }
                } }
                val tasks = DownloadStore(context).all()
                assertEquals("同一 requestId 不能在根重建后创建两个实际文档；tasks=${tasks.map { it.destinationUri }}",
                    1, control("status").getInt("created"))
                assertEquals("同一媒体选择只能产生一个持久下载任务", 1, tasks.size)
                assertEquals("重建不能重新获取一个不同的下载选择", 1, source.requests.get())
                assertNull("实际系统 Worker 不能读取本次隔离记录", DownloadStore(target).get(tasks.single().id))
                source.assertHealthy()
            } finally {
                if (registered) control("release")
                main { shown.value = false; owner.viewModelStore.clear() }
                loader.shutdown()
                if (registered) control("remove")
            }
        }
    }

    @Test fun anUnavailableDefaultDirectoryFallsBackToTheSameMediaPicker() = withPickerApp(unavailableDirectory = true) { app ->
        app.prepare()
        await("默认目录失效应保留同一媒体进入实际选择器") { app.picker.pending.get() == 1 }
        assertEquals("pending-item", app.vm.state.value.pendingDownload?.itemId)
        assertTrue(DownloadStore(app.fixture.context).all().isEmpty())
        main { app.picker.deliver() }
        await("选择新位置后应为原媒体创建一个任务") {
            DownloadStore(app.fixture.context).all().size == 1 && app.vm.state.value.actionBusy.none { it.startsWith("download/") }
        }
        val task = DownloadStore(app.fixture.context).all().single()
        assertEquals("pending-item", task.itemId)
        assertEquals(app.picker.lastUri.get().toString(), task.destinationUri)
        assertEquals(1, app.source.requests.get())
        assertEquals(1, app.picker.launches.get())
    }

    @Test fun aRealPickerResultIsDeliveredToTheRecreatedRootOnlyOnce() = withPickerApp { app ->
        app.prepare()
        await("生产 CreateDocument 应经真实 Activity 返回结果，先由夹具暂存") { app.picker.pending.get() == 1 }
        val selection = checkNotNull(app.vm.state.value.pendingDownload)
        app.rebuildRoot()
        assertEquals(selection.requestId, app.vm.state.value.pendingDownload?.requestId)
        assertEquals("根重建不能重复打开选择器", 1, app.picker.launches.get())
        main { app.picker.deliver() }
        await("重建后的生产回调应将所选文档入队一次") {
            DownloadStore(app.fixture.context).all().size == 1 && app.vm.state.value.actionBusy.none { it.startsWith("download/") }
        }
        instrumentation.waitForIdleSync()
        val task = DownloadStore(app.fixture.context).all().single()
        assertEquals(app.fixture.profileId, task.profileId)
        assertEquals(app.picker.lastUri.get().toString(), task.destinationUri)
        assertNull(app.vm.state.value.pendingDownload)
        assertEquals(1, app.picker.launches.get())
        assertNull(DownloadStore(target).get(task.id))
    }

    @Test fun cancellingTheRealPickerAllowsAnExplicitRetryWithoutAnEmptyTask() = withPickerApp { app ->
        main { app.picker.cancelNext = true }
        app.prepare()
        await("取消结果应先真实返回到 Activity registry") { app.picker.pending.get() == 1 }
        val original = checkNotNull(app.vm.state.value.pendingDownload).requestId
        main { app.picker.deliver() }
        await("系统取消必须消费待选请求") { app.vm.state.value.pendingDownload == null }
        assertTrue("系统取消不能插入下载任务", DownloadStore(app.fixture.context).all().isEmpty())
        main { app.picker.cancelNext = false }
        app.prepare()
        await("显式重试必须能重新启动选择器") { app.picker.pending.get() == 1 && app.picker.launches.get() == 2 }
        assertNotEquals(original, app.vm.state.value.pendingDownload?.requestId)
        main { app.picker.deliver() }
        await("重试选择有效位置后应只有一个任务") {
            DownloadStore(app.fixture.context).all().size == 1 && app.vm.state.value.actionBusy.none { it.startsWith("download/") }
        }
        assertEquals(2, app.source.requests.get())
        assertEquals(2, app.picker.launches.get())
    }

    @Test fun anOldPickerResultAfterSwitchingAccountsCannotQueueOrNavigateForTheNewAccount() = withPickerApp { app ->
        app.prepare()
        await("旧账号选择结果须已真实返回，但尚未交给生产回调") { app.picker.pending.get() == 1 }
        val sessions = SessionStore(app.fixture.context)
        val original = sessions.loadProfiles().single()
        val other = original.copy(id = "queue-other-${UUID.randomUUID()}", displayName = "Synthetic other",
            session = original.session.copy(userId = "queue-other-user", userName = "Other", accessToken = "queue-other-test-token"))
        sessions.upsertProfile(other)
        main { app.vm.openServer(other) }
        await("真实 validateSession 和切服必须完成") {
            app.vm.state.value.currentProfileId == other.id && !app.vm.state.value.loginBusy
        }
        val routes = app.vm.state.value.routes
        main { app.picker.deliver() }
        instrumentation.waitForIdleSync()
        assertEquals(other.id, app.vm.state.value.currentProfileId)
        assertEquals("旧结果不能跳转新账号下载页", routes, app.vm.state.value.routes)
        assertTrue("旧账号迟到结果不能以任一账号插入任务", DownloadStore(app.fixture.context).all().isEmpty())
        assertNull(app.vm.state.value.pendingDownload)
        assertEquals(1, app.picker.launches.get())
        assertEquals(1, app.source.validations.get())
        app.source.assertHealthy()
    }

    private fun withPickerApp(unavailableDirectory: Boolean = false, block: (DownloadApp) -> Unit) = withFixture { fixture ->
        DownloadSourceFixture().use { source ->
            val directory = if (unavailableDirectory) DocumentsContract.buildTreeDocumentUri(
                VerificationDownloadDocumentsProvider.authority(instrumentation.context), "queue-missing-${UUID.randomUUID()}").toString() else null
            DownloadApp(fixture, source, directory).use(block)
        }
    }

    /** 与默认目录反例共用真实 VM/隔离 Store；只将系统选择器交互定向到已有跨 APK GrantActivity。 */
    private inner class DownloadApp(val fixture: Fixture, val source: DownloadSourceFixture, directory: String?) : AutoCloseable {
        private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        private val loader = ImageLoader.Builder(fixture.context).diskCache(null).build()
        private val generation = mutableIntStateOf(0)
        private val shown = mutableStateOf(true)
        private val disposed = AtomicInteger(-1)
        private val composed = AtomicInteger(-1)
        val picker = PickerResults(fixture)
        val vm: AppViewModel
        init {
            val context = fixture.context
            AppPreferencesStore(context).save(AppPreferences(openLastServer = false, downloadDirectoryUri = directory))
            val sessions = SessionStore(context)
            val profile = sessions.loadProfiles().single()
            sessions.upsertProfile(profile.copy(session = profile.session.copy(server = ServerConfig(source.baseUrl, true))))
            sessions.selectProfile(profile.id)
            check(context.getSharedPreferences("pathfin_removed_features", Context.MODE_PRIVATE)
                .edit().putBoolean("trakt_v1_removed", true).commit())
            vm = main { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context))[AppViewModel::class.java] }
            await("合成会话应读取完成") { !vm.state.value.restoringSession }
            main { vm.openSettings() }
            fixture.show {
                val state by vm.state.collectAsState()
                val saved = rememberSaveableStateHolder()
                CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides picker) {
                    PathfinTheme(ThemePreference.LIGHT) {
                        if (shown.value) key(generation.intValue) {
                            val current = generation.intValue
                            DisposableEffect(Unit) { onDispose { disposed.set(current) } }
                            SideEffect { composed.set(current) }
                            PathfinApp(vm, state, saved, posterLoader = loader)
                        }
                    }
                }
            }
        }
        fun prepare() = main { vm.prepareDownload(MediaItem("pending-item", "Synthetic pending", "Movie")) }
        fun rebuildRoot() {
            val before = generation.intValue
            main { generation.intValue++ }
            await("旧根须实际释放且新根已挂载") { disposed.get() == before && composed.get() == before + 1 }
            instrumentation.waitForIdleSync()
        }
        override fun close() {
            main { shown.value = false; owner.viewModelStore.clear(); picker.close() }
            loader.shutdown()
        }
    }

    /** 暂存来自真实 ComponentActivity registry 的结果，不直接调用 VM，也不伪造 URI 权限。 */
    private inner class PickerResults(private val fixture: Fixture) : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = AtomicInteger()
        val pending = AtomicInteger()
        val lastUri = AtomicReference<Uri?>()
        var cancelNext = false
        private val results = ArrayDeque<Pair<Int, ActivityResult>>()
        private val registered = mutableListOf<ActivityResultLauncher<*>>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            val activity = fixture.activity
            val intent = contract.createIntent(activity, input)
            if (intent.action != Intent.ACTION_CREATE_DOCUMENT) {
                val launcher = activity.activityResultRegistry.register("queue-system-${UUID.randomUUID()}", contract) {
                    dispatchResult(requestCode, it)
                }
                registered += launcher
                launcher.launch(input, options)
                return
            }
            assertEquals("必须由生产 CreateDocument 请求默认文件名", "application/octet-stream", intent.type)
            check(intent.getStringExtra(Intent.EXTRA_TITLE)?.matches(Regex("[a-f0-9-]{36}\\.mp4")) == true)
            launches.incrementAndGet()
            val id = "${UUID.randomUUID()}.mp4"
            val launcher = activity.activityResultRegistry.register("queue-picker-${UUID.randomUUID()}",
                ActivityResultContracts.StartActivityForResult()) { result ->
                result.data?.data?.let { uri -> fixture.trackDocument(uri); lastUri.set(uri) }
                results.addLast(requestCode to result)
                pending.incrementAndGet()
            }
            registered += launcher
            launcher.launch(Intent().setComponent(ComponentName(instrumentation.context.packageName,
                VerificationDownloadDocumentsProvider.GrantActivity::class.java.name))
                .putExtra(VerificationDownloadDocumentsProvider.DOCUMENT_ID_EXTRA, id)
                .putExtra(VerificationDownloadDocumentsProvider.CANCEL_RESULT_EXTRA, cancelNext), options)
        }
        fun deliver() {
            val (request, result) = results.removeFirst()
            pending.decrementAndGet()
            assertTrue("生产选择器回调必须仍注册于新根", dispatchResult(request, result.resultCode, result.data))
        }
        fun close() { registered.forEach { it.unregister() }; results.clear(); pending.set(0) }
    }

    private fun await(reason: String, condition: () -> Boolean) {
        try { runBlocking { withTimeout(15_000) { while (!condition()) delay(20) } } }
        catch (failure: Throwable) { throw AssertionError(reason, failure) }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun assertUnmetered(fixture: Fixture, id: String) {
        val task = checkNotNull(DownloadStore(fixture.context).get(id))
        val work = checkNotNull(fixture.manager.getWorkInfoById(UUID.fromString(task.workId)).get(10, TimeUnit.SECONDS))
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        check(target.packageName == VerificationDownloadDocumentsProvider.TARGET_PACKAGE)
        VerificationDisplay.prepare()
        // 系统实例只持有真实 target application，不把隔离 Context 交给执行中的 Worker。
        val manager = WorkManager.getInstance(target)
        val context = IsolatedContext(target)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val fixture = Fixture(context, manager, scenario)
            var failure: Throwable? = null
            try {
                SessionStore(context).upsertProfile(ServerProfile(fixture.profileId, Session(
                    ServerConfig("http://127.0.0.1:9/queue-fixture", true), "queue-test-token", "queue-user", "Synthetic", "queue-device",
                    serverId = "queue-fixture", canDownload = true,
                ), "Synthetic queue test"))
                block(fixture)
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                val cleanup = runCatching { fixture.close() }.exceptionOrNull()
                if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private inner class Fixture(
        val context: IsolatedContext,
        val manager: WorkManager,
        private val scenario: ActivityScenario<ComponentActivity>,
    ) : AutoCloseable {
        val profileId = "queue-${UUID.randomUUID()}"
        private val documents = mutableListOf<Uri>()
        private val treeFiles = mutableMapOf<Uri, String>()
        lateinit var activity: ComponentActivity
            private set
        init { scenario.onActivity { activity = it } }

        fun trackDocument(uri: Uri) {
            check(uri.authority == VerificationDownloadDocumentsProvider.authority(instrumentation.context))
            check(DocumentsContract.getDocumentId(uri).matches(Regex("[a-f0-9-]{36}\\.mp4")))
            documents += uri
        }

        fun document(): Uri = grant(tree = false)

        fun tree(fileName: String): Uri = grant(tree = true).also { treeFiles[it] = fileName }

        fun show(content: @androidx.compose.runtime.Composable () -> Unit) = scenario.onActivity {
            activity = it
            it.setContent(content = content)
        }

        fun recreate() { scenario.recreate() }

        private fun grant(tree: Boolean): Uri {
            val result = CompletableFuture<Uri>()
            val id = UUID.randomUUID().toString()
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("queue-document-$id",
                    ActivityResultContracts.StartActivityForResult()) { returned ->
                    val uri = returned.data?.data
                    if (returned.resultCode == Activity.RESULT_OK && uri != null) result.complete(uri)
                    else result.completeExceptionally(AssertionError("测试文档没有返回真实系统授权"))
                }
                launcher.launch(Intent().setComponent(ComponentName(instrumentation.context.packageName,
                    VerificationDownloadDocumentsProvider.GrantActivity::class.java.name))
                    .putExtra(VerificationDownloadDocumentsProvider.DOCUMENT_ID_EXTRA, "$id.mp4")
                    .putExtra(VerificationDownloadDocumentsProvider.TREE_GRANT_EXTRA, tree))
            }
            return result.get(15, TimeUnit.SECONDS).also { uri ->
                check(uri.authority == VerificationDownloadDocumentsProvider.authority(instrumentation.context))
                if (tree) check(DocumentsContract.isTreeUri(uri)) else {
                    check(DocumentsContract.getDocumentId(uri) == "$id.mp4")
                    documents += uri
                }
                context.contentResolver.takePersistableUriPermission(uri, GRANTS)
            }
        }

        override fun close() {
            context.releaseRead()
            try {
                DownloadStore(context).all().forEach { task ->
                    manager.cancelUniqueWork("pathfin-download-${task.id}").result.get(10, TimeUnit.SECONDS)
                }
            } finally {
                try {
                    val treeDocuments = treeFiles.flatMap { (tree, fileName) ->
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
                            buildList { while (cursor.moveToNext()) {
                                val id = cursor.getString(0)
                                if (id.replace(Regex(" \\([1-9][0-9]*\\)\\.mp4$"), ".mp4") == fileName)
                                    add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                            } }
                        }
                    }
                    (documents + treeDocuments).distinct().forEach { uri ->
                        try { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        finally {
                            // DocumentsProvider 删除文档后可能已撤销授权，只释放当前仍真实持有的权限位。
                            val held = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                            val flags = (if (held?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                                (if (held?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                            if (flags != 0) context.contentResolver.releasePersistableUriPermission(uri, flags)
                        }
                    }
                    treeFiles.keys.forEach { context.contentResolver.releasePersistableUriPermission(it, GRANTS) }
                } finally { context.deleteOwnStores() }
            }
        }
    }

    private class ReadGate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun pause() { entered.countDown(); check(released.await(20, TimeUnit.SECONDS)) { "未放行已捕获的网络偏好" } }
        fun awaitEntered() { assertTrue("入队尚未实际读取旧网络偏好", entered.await(10, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
    }

    private class IsolatedContext(private val target: Context) : Application() {
        private val prefix = "queue-race-${UUID.randomUUID()}-"
        private val stores = ConcurrentHashMap<String, SharedPreferences>()
        private val nextRead = AtomicReference<ReadGate?>()
        private val reads = mutableListOf<ReadGate>()
        init { attachBaseContext(target) }
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = File(target.cacheDir, prefix).apply { check(isDirectory || mkdirs()) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) {
            val delegate = target.getSharedPreferences(prefix + name, mode)
            if (name != "pathfin_preferences") delegate else object : SharedPreferences by delegate {
                override fun getString(key: String?, defValue: String?): String? {
                    val value = delegate.getString(key, defValue)
                    if (key == "settings") nextRead.getAndSet(null)?.pause()
                    return value
                }
            }
        }
        fun blockNextSettingsRead(): ReadGate = ReadGate().also { check(nextRead.compareAndSet(null, it)); reads += it }
        fun releaseRead() { reads.forEach(ReadGate::release) }
        fun deleteOwnStores() {
            stores.keys.forEach { check(target.deleteSharedPreferences(prefix + it)) }
            val directory = File(target.cacheDir, prefix)
            if (directory.exists()) check(directory.deleteRecursively())
        }
    }

    /** 仅给真实 prepareDownload 返回本次 UUID 文件名；系统 Worker 看不到隔离任务，不会下载。 */
    private class DownloadSourceFixture : AutoCloseable {
        val fileName = "${UUID.randomUUID()}.mp4"
        val requests = AtomicInteger()
        val validations = AtomicInteger()
        private val failure = AtomicReference<Throwable?>()
        private val socket = AtomicReference<Socket?>()
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        @Volatile private var closed = false
        private val thread = Thread({
            try { while (!closed) server.accept().use { client ->
                socket.set(client)
                client.soTimeout = 10_000
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request = checkNotNull(reader.readLine())
                check(request.startsWith("GET ")) { "Unexpected method: $request" }
                val path = URI(request.split(' ')[1]).path
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = when {
                    path == "/Items/pending-item" -> {
                        requests.incrementAndGet()
                        JSONObject().put("Id", "pending-item").put("Name", "Synthetic pending").put("Type", "Movie")
                            .put("Path", "/media/$fileName").put("Container", "mp4").put("CanDownload", true).toString()
                    }
                    path == "/Users/Me" -> {
                        validations.incrementAndGet()
                        JSONObject().put("Id", "queue-other-user").put("Name", "Other")
                            .put("Policy", JSONObject().put("EnableContentDownloading", true)).toString()
                    }
                    path == "/Items/Latest" -> JSONArray().toString()
                    path in setOf("/UserViews", "/UserItems/Resume", "/Shows/NextUp") ->
                        JSONObject().put("Items", JSONArray()).put("TotalRecordCount", 0).toString()
                    else -> error("Unexpected request: $request")
                }.toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
                socket.set(null)
            } } catch (error: Throwable) { if (!closed) failure.set(error) }
        }, "pathfin-download-selection-fixture").apply { isDaemon = true; start() }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("合成下载准备接口失败", it) } }
        override fun close() { closed = true; server.close(); socket.get()?.close(); thread.join(1_500) }
    }

    private companion object {
        const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.BATCH_KEY
import dev.pathfin.android.ui.BatchMediaAction
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.PathfinApp
import dev.pathfin.android.ui.Route
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实详情菜单、VM 与回环 HTTP；不接触正常包或任何外部 Jellyfin。 */
@RunWith(AndroidJUnit4::class)
class DetailManagementActionsTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousPreferences: AppPreferences? = null
    private var owner: ViewModelStoreOwner? = null
    private lateinit var loader: ImageLoader

    @Before fun prepare() {
        check(context.packageName == VERIFICATION_PACKAGE)
        clearSessions()
        val preferences = AppPreferencesStore(context)
        previousPreferences = preferences.load()
        preferences.save(AppPreferences())
        loader = ImageLoader.Builder(context).diskCache(null).build()
    }

    @After fun cleanup() {
        if (context.packageName != VERIFICATION_PACKAGE) return
        clearViewModel()
        if (::loader.isInitialized) loader.shutdown()
        clearSessions()
        previousPreferences?.let { AppPreferencesStore(context).save(it) }
    }

    @Test fun refreshUsesDefaultNonReplacingPostWithoutLeavingTheCurrentDetail() = withApp { fixture, vm ->
        openDetail(fixture, vm)
        val detailReads = fixture.detailReads.get()
        openMore("detail-refresh")
        compose.onNodeWithTag("detail-refresh").assertVerificationDisplayed(compose, "detail-refresh").performVerificationClick(compose)
        awaitWindowTag("library-refresh-confirm", "detail-refresh-cancel-dialog")
        compose.onNodeWithTag("library-refresh-scan").assertIsSelected()
        compose.onNodeWithTag("library-refresh-replace-images").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsNotEnabled().assertIsOff()
        assertEquals("打开刷新菜单不能提前发起刷新", 0, fixture.refreshPosts.get())

        compose.onNodeWithTag("library-refresh-replace_all").assertVerificationDisplayed(compose, "detail-refresh-cancel-replace-all").performVerificationClick(compose)
        compose.onNodeWithTag("library-refresh-replace-images").assertIsEnabled().assertVerificationDisplayed(compose, "detail-refresh-cancel-replace-images").performVerificationClick(compose)
        compose.onNodeWithText("取消", substring = false).assertVerificationDisplayed(compose, "detail-refresh-cancel").performVerificationClick(compose)
        await(fixture, vm, "取消刷新后应关闭菜单并保留详情") {
            it.route == Route.Detail(ITEM_ID) &&
                compose.onAllNodesWithTag("library-refresh-confirm").fetchSemanticsNodes().isEmpty()
        }
        VerificationDisplay.awaitWindowReady(compose, "detail-refresh-cancelled", compose.onNodeWithTag("detail-more"))
        assertEquals("取消不能调用刷新接口", 0, fixture.refreshPosts.get())
        assertFalse("取消不能留下刷新任务", "refresh/$ITEM_ID" in vm.state.value.actionBusy)

        openMore("detail-refresh")
        compose.onNodeWithTag("detail-refresh").assertVerificationDisplayed(compose, "detail-refresh-reopen").performVerificationClick(compose)
        awaitWindowTag("library-refresh-confirm", "detail-refresh-default-dialog")
        compose.onNodeWithTag("library-refresh-scan").assertIsSelected()
        compose.onNodeWithTag("library-refresh-replace-images").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsNotEnabled().assertIsOff()
        assertEquals("重新打开菜单仍不能提前刷新", 0, fixture.refreshPosts.get())
        compose.onNodeWithTag("library-refresh-confirm").assertVerificationDisplayed(compose, "detail-refresh-default-confirm").performVerificationClick(compose)
        await(fixture, vm, "确认默认选项后才允许发送真实 POST") {
            fixture.refreshPosts.get() == 1 && "refresh/$ITEM_ID" in it.actionBusy
        }
        assertEquals(Route.Detail(ITEM_ID), vm.state.value.route)
        assertFalse("刷新不能跳转至媒体管理页", vm.state.value.routes.any { it is Route.MediaTools })
        assertFalse(fixture.refreshed.get())
        val request = requireNotNull(fixture.refreshRequest.get())
        assertEquals("Default", request.queryParameter("metadataRefreshMode"))
        assertEquals("Default", request.queryParameter("imageRefreshMode"))
        assertEquals("false", request.queryParameter("replaceAllMetadata"))
        assertEquals("false", request.queryParameter("replaceAllImages"))
        assertEquals("false", request.queryParameter("regenerateTrickplay"))
        assertNull("刷新单个条目不能变成递归库扫描", request.queryParameter("recursive"))
        assertEquals(0, fixture.deletes.get())

        fixture.releaseRefresh()
        await(fixture, vm, "POST 成功后应在原详情重新读取服务器数据") {
            it.route == Route.Detail(ITEM_ID) && !it.detailLoading &&
                it.details[ITEM_ID]?.name == REFRESHED_TITLE &&
                it.detailExtras[ITEM_ID]?.loading == false &&
                "refresh/$ITEM_ID" !in it.actionBusy && it.home.loaded && !it.home.loading
        }
        assertTrue("刷新后必须真正重新读取详情", fixture.detailReads.get() > detailReads)
        assertEquals(1, fixture.refreshPosts.get())
        assertFalse(vm.state.value.routes.any { it is Route.MediaTools })
        compose.onNodeWithTag("detail-content").assertVerificationDisplayed(compose)
        compose.onAllNodesWithText(REFRESHED_TITLE, substring = false).onFirst().assertExists()
    }

    @Test fun refreshSendsTheChosenMetadataImageAndTrickplayReplacementOptions() = withApp { fixture, vm ->
        openDetail(fixture, vm)
        openMore("detail-refresh")
        compose.onNodeWithTag("detail-refresh").assertVerificationDisplayed(compose, "detail-refresh-custom").performVerificationClick(compose)
        awaitWindowTag("library-refresh-confirm", "detail-refresh-custom-dialog")
        compose.onNodeWithTag("library-refresh-replace_all").assertVerificationDisplayed(compose, "detail-refresh-replace-all").performVerificationClick(compose)
        compose.onNodeWithTag("library-refresh-replace_all").assertIsSelected()
        compose.onNodeWithTag("library-refresh-replace-images").assertIsEnabled()
            .assertVerificationDisplayed(compose, "detail-refresh-replace-images").performVerificationClick(compose).assertIsOn()
        compose.onNodeWithTag("library-refresh-replace-trickplay").assertIsEnabled()
            .assertVerificationDisplayed(compose, "detail-refresh-replace-trickplay").performVerificationClick(compose).assertIsOn()
        assertEquals("选择替换选项不能提前刷新", 0, fixture.refreshPosts.get())

        compose.onNodeWithTag("library-refresh-confirm").assertVerificationDisplayed(compose, "detail-refresh-custom-confirm").performVerificationClick(compose)
        await(fixture, vm, "确认后应把自定义选项提交到真实刷新接口") {
            fixture.refreshPosts.get() == 1 && "refresh/$ITEM_ID" in it.actionBusy
        }
        val request = requireNotNull(fixture.refreshRequest.get())
        assertEquals("FullRefresh", request.queryParameter("metadataRefreshMode"))
        assertEquals("FullRefresh", request.queryParameter("imageRefreshMode"))
        assertEquals("true", request.queryParameter("replaceAllMetadata"))
        assertEquals("true", request.queryParameter("replaceAllImages"))
        assertEquals("true", request.queryParameter("regenerateTrickplay"))
        assertNull("刷新详情不能扩大为递归库扫描", request.queryParameter("recursive"))
        assertEquals(Route.Detail(ITEM_ID), vm.state.value.route)

        fixture.releaseRefresh()
        await(fixture, vm, "自定义刷新完成后应保留原详情并更新数据") {
            it.route == Route.Detail(ITEM_ID) && !it.detailLoading &&
                it.details[ITEM_ID]?.name == REFRESHED_TITLE &&
                it.detailExtras[ITEM_ID]?.loading == false &&
                "refresh/$ITEM_ID" !in it.actionBusy && it.home.loaded && !it.home.loading
        }
        assertEquals(1, fixture.refreshPosts.get())
        assertFalse(vm.state.value.routes.any { it is Route.MediaTools })
        compose.onNodeWithTag("detail-content").assertVerificationDisplayed(compose)
    }

    @Test fun deleteCancellationDoesNotWriteAndConfirmationDeletesThenRemovesDetailAndHomeCaches() = withApp { fixture, vm ->
        openDetail(fixture, vm)
        val profile = vm.state.value.currentProfileId
        assertCached(vm.state.value, present = true)

        openMore("detail-delete")
        compose.onNodeWithTag("detail-delete").assertVerificationDisplayed(compose, "detail-delete").performVerificationClick(compose)
        awaitWindowTag("detail-delete-confirm", "detail-delete-cancel-dialog")
        compose.onNodeWithText("取消", substring = false).assertVerificationDisplayed(compose, "detail-delete-cancel").performVerificationClick(compose)
        await(fixture, vm, "取消删除后应关闭确认框并保留详情") {
            it.route == Route.Detail(ITEM_ID) &&
                compose.onAllNodesWithTag("detail-delete-confirm").fetchSemanticsNodes().isEmpty()
        }
        VerificationDisplay.awaitWindowReady(compose, "detail-delete-cancelled", compose.onNodeWithTag("detail-more"))
        assertEquals("取消不能调用删除接口", 0, fixture.deletes.get())
        assertEquals("取消也不能提前进入删除 API 的能力预读", 0, fixture.capabilityReads.get())
        assertFalse(fixture.deleted.get())
        assertCached(vm.state.value, present = true)

        openMore("detail-delete")
        compose.onNodeWithTag("detail-delete").assertVerificationDisplayed(compose, "detail-delete").performVerificationClick(compose)
        awaitWindowTag("detail-delete-confirm", "detail-delete-confirm-dialog")
        compose.onNodeWithTag("detail-delete-confirm").assertTextContains("确认删除").assertVerificationDisplayed(compose, "detail-delete-confirm").performVerificationClick(compose)
        await(fixture, vm, "确认后才允许发送真实 DELETE") {
            fixture.deletes.get() == 1 && "delete/$ITEM_ID" in it.actionBusy
        }
        assertEquals(1, fixture.capabilityReads.get())
        assertFalse("后端尚未确认时不能提前移除缓存", fixture.deleted.get())
        assertEquals(Route.Detail(ITEM_ID), vm.state.value.route)
        assertCached(vm.state.value, present = true)

        fixture.releaseDelete()
        await(fixture, vm, "删除成功后必须回首页并清除所有已加载条目副本") {
            it.route == Route.Home && "delete/$ITEM_ID" !in it.actionBusy &&
                !it.details.containsKey(ITEM_ID) && !it.detailExtras.containsKey(ITEM_ID) &&
                it.home.loaded && !it.home.loading &&
                it.home.libraryItems[LIBRARY_ID]?.map { media -> media.id } == listOf(KEEPER_ID) &&
                it.home.resume.map { media -> media.id } == listOf(KEEPER_ID)
        }
        assertCached(vm.state.value, present = false)
        assertEquals(listOf(Route.Home), vm.state.value.routes)
        assertFalse(vm.state.value.showServers)
        assertEquals(profile, vm.state.value.currentProfileId)
        assertEquals(profile, SessionStore(context).activeProfileId())
        assertEquals(1, fixture.deletes.get())
        assertTrue(fixture.deleted.get())
        assertEquals(0, fixture.refreshPosts.get())
        compose.onNodeWithTag("home-list").assertVerificationDisplayed(compose)
    }

    @Test fun batchPlayedSendsEveryItemAndReportsPartialFailureHonestly() = withApp { fixture, vm ->
        val items = vm.state.value.home.libraryItems.getValue(LIBRARY_ID)
        main { vm.runBatch(items, BatchMediaAction.Played(true)) }
        await(fixture, vm, "批量操作必须逐条发出，且失败一条不中断其余") {
            fixture.playedPosts.get() == 2 && BATCH_KEY !in it.actionBusy
        }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("1 项完成，1 项失败", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val state = vm.state.value
        assertTrue("成功的一条应写回已播放", state.home.libraryItems.getValue(LIBRARY_ID).single { it.id == ITEM_ID }.played)
        assertFalse("失败的一条保持原状", state.home.libraryItems.getValue(LIBRARY_ID).single { it.id == KEEPER_ID }.played)
    }

    @Test fun batchDeleteRemovesEachConfirmedItemAndRefreshesHomeOnlyAfterTheWholeBatch() =
        assertBatchDeleteRefreshesOnce(keeperDeleteFails = false)

    @Test fun batchDeleteRefreshesSuccessfulChangesOnceEvenWhenAnotherItemFails() =
        assertBatchDeleteRefreshesOnce(keeperDeleteFails = true)

    @Test fun batchPlayedCancelsAnOlderPlaybackParentReadBeforeItCanOverwriteTheConfirmedMark() =
        withApp(delayedParentRead = true) { fixture, vm ->
            val session = requireNotNull(vm.state.value.session)
            main { vm.refreshAfterPlayback(session, EPISODE_ID) }
            await(fixture, vm, "播放结束后的父剧状态读取必须先到达服务器并保持等待") {
                fixture.parentReads.get() == 1 && !it.detailLoading && !it.home.loading
            }
            val series = vm.state.value.home.libraryItems.getValue(LIBRARY_ID).single { it.id == ITEM_ID }
            assertFalse(series.played)

            main { vm.runBatch(listOf(series), BatchMediaAction.Played(true)) }
            await(fixture, vm, "显式标记成功后必须取消此前的父剧 HTTP 读取") {
                BATCH_KEY !in it.actionBusy && fixture.parentReadCancelled.get() &&
                    it.home.libraryItems[LIBRARY_ID]?.single { media -> media.id == ITEM_ID }?.played == true
            }
            // 父项在标记之前已生成 Played=false；现在尝试发送，也不能再覆盖成功写回。
            fixture.releaseParentRead()
            await(fixture, vm, "迟到的父剧旧响应结束后仍须保持批量标记结果") {
                fixture.parentReplyFinished.get() &&
                    it.home.libraryItems[LIBRARY_ID]?.single { media -> media.id == ITEM_ID }?.played == true
            }
            assertEquals(1, fixture.playedPosts.get())
        }

    private fun assertBatchDeleteRefreshesOnce(keeperDeleteFails: Boolean) = withApp(keeperDeleteFails) { fixture, vm ->
        val items = vm.state.value.home.libraryItems.getValue(LIBRARY_ID)
        val homeReads = fixture.homeReads.get()
        val resumeReads = fixture.resumeReads.get()
        val latestReads = fixture.latestReads.get()
        fixture.watchBatchDeletion()
        main { vm.runBatch(items, BatchMediaAction.Delete) }
        await(fixture, vm, "首项删除响应未确认前不能提前移除卡片") {
            fixture.deletes.get() == 1 && BATCH_KEY in it.actionBusy
        }
        assertEquals(2, vm.state.value.home.libraryItems.getValue(LIBRARY_ID).size)

        fixture.releaseDelete()
        await(fixture, vm, "首项确认后立即移除本地条目，并等待第二项响应") {
            fixture.keeperDeletes.get() == 1 && BATCH_KEY in it.actionBusy &&
                it.home.libraryItems[LIBRARY_ID]?.map { media -> media.id } == listOf(KEEPER_ID) &&
                it.home.resume.map { media -> media.id } == listOf(KEEPER_ID)
        }
        assertEquals("整批尚未结束，不应重取首页媒体库", homeReads, fixture.homeReads.get())
        assertEquals(resumeReads, fixture.resumeReads.get())
        assertEquals(latestReads, fixture.latestReads.get())
        assertEquals(0, fixture.prematureHomeReads.get())

        fixture.releaseKeeperDelete()
        val remaining = if (keeperDeleteFails) listOf(KEEPER_ID) else emptyList()
        await(fixture, vm, "整批结束后必须统一刷新一次，保留失败项并移除成功项") {
            BATCH_KEY !in it.actionBusy && it.home.loaded && !it.home.loading &&
                it.home.libraryItems[LIBRARY_ID]?.map { media -> media.id } == remaining &&
                it.home.resume.map { media -> media.id } == remaining
        }
        assertEquals(2, fixture.deletes.get())
        assertEquals("每批只能重刷一轮首页媒体库", homeReads + 1, fixture.homeReads.get())
        assertEquals(resumeReads + 1, fixture.resumeReads.get())
        assertEquals(latestReads + 1, fixture.latestReads.get())
        assertEquals("每项成功后都不能提前启动整页重刷", 0, fixture.prematureHomeReads.get())
        if (keeperDeleteFails) compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("1 项完成，1 项失败", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openMore(action: String) {
        val more = compose.onNodeWithTag("detail-more")
        VerificationDisplay.awaitWindowReady(compose, "detail-more-$action", more)
        more.assertVerificationDisplayed(compose, "detail-more-$action").performVerificationClick(compose)
        awaitWindowTag(action, action)
    }

    private fun awaitWindowTag(tag: String, stage: String) {
        // 新 Dialog/Sheet 首次组合前没有节点，先等存在，再验证它自己的窗口首绘和焦点。
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        VerificationDisplay.awaitWindowReady(compose, stage, compose.onNodeWithTag(tag))
    }

    private fun openDetail(fixture: Fixture, vm: AppViewModel) {
        compose.onAllNodesWithText(TITLE, substring = false).onFirst().performScrollTo().assertVerificationDisplayed(compose, "open-synthetic-detail").performVerificationClick(compose)
        await(fixture, vm, "合成媒体详情必须完成真实读取") {
            it.route == Route.Detail(ITEM_ID) && !it.detailLoading &&
                it.details[ITEM_ID]?.name == TITLE && it.detailExtras[ITEM_ID]?.loading == false
        }
        VerificationDisplay.awaitWindowReady(compose, "synthetic-management-detail", compose.onNodeWithTag("detail-more"))
    }

    private fun assertCached(state: AppState, present: Boolean) {
        assertEquals(present, state.details.containsKey(ITEM_ID))
        assertEquals(present, state.detailExtras.containsKey(ITEM_ID))
        assertEquals(present, state.home.resume.any { it.id == ITEM_ID })
        assertEquals(present, state.home.libraryItems[LIBRARY_ID].orEmpty().any { it.id == ITEM_ID })
        assertTrue("删除目标不能清掉其他条目", state.home.resume.any { it.id == KEEPER_ID })
        assertTrue(state.home.libraryItems[LIBRARY_ID].orEmpty().any { it.id == KEEPER_ID })
    }

    private fun withApp(keeperDeleteFails: Boolean = false, delayedParentRead: Boolean = false,
        block: (Fixture, AppViewModel) -> Unit) {
        Fixture(keeperDeleteFails, delayedParentRead).use { fixture ->
            SessionStore(context).save(Session(ServerConfig(fixture.baseUrl, true), TOKEN, USER_ID,
                "Synthetic detail manager", "synthetic-detail-device", SERVER_ID,
                isAdministrator = true, canDelete = true))
            val nextOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            owner = nextOwner
            val vm = main { ViewModelProvider(nextOwner,
                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java] }
            val shown = mutableStateOf(true)
            var failure: Throwable? = null
            try {
                compose.setContent {
                    val state by vm.state.collectAsState()
                    val saved = rememberSaveableStateHolder()
                    PathfinTheme(ThemePreference.LIGHT) {
                        Surface(Modifier.fillMaxSize()) { if (shown.value) PathfinApp(vm, state, saved, posterLoader = loader) }
                    }
                }
                await(fixture, vm, "只恢复合成管理员并完成首页") {
                    !it.restoringSession && !it.showServers && it.session?.userId == USER_ID &&
                        it.session.isAdministrator && it.session.canDelete &&
                        it.home.loaded && !it.home.loading && it.home.libraryItems[LIBRARY_ID]?.size == 2 && it.home.resume.size == 2
                }
                VerificationDisplay.awaitWindowReady(compose, "synthetic-management-home", compose.onNodeWithTag("home-list"))
                block(fixture, vm)
                fixture.assertHealthy()
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                try { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); clearViewModel(); fixture.assertHealthy() }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }

    private fun await(fixture: Fixture, vm: AppViewModel, reason: String, condition: (AppState) -> Boolean) {
        try { compose.waitUntil(WAIT_MS) { fixture.assertHealthy(); condition(vm.state.value) } }
        catch (failure: Throwable) { throw AssertionError(reason, failure) }
    }

    private fun clearViewModel() { owner?.let { main { it.viewModelStore.clear() } }; owner = null }
    private fun clearSessions() {
        check(context.packageName == VERIFICATION_PACKAGE)
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class Fixture(private val keeperDeleteFails: Boolean, private val delayedParentRead: Boolean) : Closeable {
        val refreshPosts = AtomicInteger()
        val playedPosts = AtomicInteger()
        val deletes = AtomicInteger()
        val detailReads = AtomicInteger()
        val capabilityReads = AtomicInteger()
        val keeperDeletes = AtomicInteger()
        val homeReads = AtomicInteger()
        val resumeReads = AtomicInteger()
        val latestReads = AtomicInteger()
        val prematureHomeReads = AtomicInteger()
        val parentReads = AtomicInteger()
        val parentReadCancelled = AtomicBoolean()
        val parentReplyFinished = AtomicBoolean()
        val refreshRequest = AtomicReference<HttpUrl?>()
        val refreshed = AtomicBoolean()
        val deleted = AtomicBoolean()
        private val keeperDeleted = AtomicBoolean()
        private val targetPlayed = AtomicBoolean()
        private val batchDeleting = AtomicBoolean()
        private val refreshGate = CountDownLatch(1)
        private val deleteGate = CountDownLatch(1)
        private val keeperDeleteGate = CountDownLatch(1)
        private val parentReadGate = CountDownLatch(1)
        private val closed = AtomicBoolean()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val executor = Executors.newCachedThreadPool()
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets.add(socket); executor.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures.add(error) }
        }, "pathfin-detail-management-accept").apply { isDaemon = true; start() }

        fun releaseRefresh() = refreshGate.countDown()
        fun releaseDelete() = deleteGate.countDown()
        fun releaseKeeperDelete() = keeperDeleteGate.countDown()
        fun watchBatchDeletion() { batchDeleting.set(true) }
        fun releaseParentRead() = parentReadGate.countDown()
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("详情管理合成 HTTP 契约失败", it) } }

        private fun serve(socket: Socket) {
            var parentReply = false
            try {
                socket.use {
                    socket.soTimeout = WAIT_MS.toInt()
                    val input = DataInputStream(socket.getInputStream())
                    val firstLine = line(input) ?: return
                    val parts = firstLine.split(' ')
                    check(parts.size == 3 && parts[2] == "HTTP/1.1")
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(input) ?: throw EOFException()
                        if (header.isEmpty()) break
                        val colon = header.indexOf(':'); check(colon > 0 && headers.size < 128)
                        headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                    }
                    check("transfer-encoding" !in headers)
                    val length = headers["content-length"]?.toInt() ?: 0
                    // PlaybackInfo 携带按本机解码器逐项声明的 DeviceProfile，真实请求体会超过 4 KiB。
                    check(length in 0..64 * 1024)
                    val body = ByteArray(length).also(input::readFully)
                    val url = (baseUrl + parts[1]).toHttpUrl()
                    check(!url.toString().contains(TOKEN))
                    check(headers["authorization"]?.contains(TOKEN) == true)
                    check("x-emby-token" !in headers)
                    parentReply = delayedParentRead && parts[0] == "GET" &&
                        url.encodedPath == "/Items" && url.queryParameter("ids") == ITEM_ID
                    val response = respond(parts[0], url, body, socket)
                    val bytes = response.second?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 ${response.first} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get() && !(parentReply && parentReadCancelled.get() && error is IOException)) failures.add(error)
            } finally {
                if (parentReply) parentReplyFinished.set(true)
                sockets.remove(socket)
            }
        }

        private fun respond(method: String, url: HttpUrl, body: ByteArray, socket: Socket): Pair<Int, String?> {
            return when ("$method ${url.encodedPath}") {
                "GET /Users/Me" -> 200 to JSONObject().put("Id", USER_ID).put("Name", "Synthetic detail manager")
                    .put("Policy", JSONObject().put("IsAdministrator", true).put("EnableContentDeletion", true)).toString()
                "GET /UserViews" -> {
                    homeReads.incrementAndGet()
                    if (batchDeleting.get()) prematureHomeReads.incrementAndGet()
                    200 to page(JSONArray().put(JSONObject().put("Id", LIBRARY_ID)
                        .put("Name", "Synthetic management library").put("Type", "CollectionFolder")
                        .put("CollectionType", "movies").put("IsFolder", true)))
                }
                "GET /UserItems/Resume" -> { resumeReads.incrementAndGet(); 200 to page(items()) }
                "GET /Shows/NextUp" -> {
                    check(url.queryParameter("enableResumable") == "false")
                    200 to page(JSONArray())
                }
                "GET /Items/Latest" -> {
                    latestReads.incrementAndGet()
                    check(url.queryParameter("parentId") == LIBRARY_ID)
                    200 to items().toString()
                }
                "GET /Items" -> {
                    val id = url.queryParameter("ids")
                    check(id == ITEM_ID || (delayedParentRead && id == EPISODE_ID))
                    detailReads.incrementAndGet()
                    val result = page(if (deleted.get()) JSONArray() else JSONArray().put(item(requireNotNull(id))))
                    if (delayedParentRead && id == ITEM_ID) {
                        parentReads.incrementAndGet()
                        watchParentCancellation(socket)
                        check(parentReadGate.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "父剧旧响应门闩未释放" }
                    }
                    200 to result
                }
                "GET /Items/$ITEM_ID/Similar" -> 200 to page(JSONArray().put(item(KEEPER_ID)))
                "GET /Items/$EPISODE_ID/Similar" -> 200 to page(JSONArray())
                "GET /Items/$ITEM_ID" -> {
                    capabilityReads.incrementAndGet()
                    if (deleted.get()) 404 to "{}" else 200 to item(ITEM_ID).toString()
                }
                "GET /Items/$KEEPER_ID" -> {
                    capabilityReads.incrementAndGet()
                    if (keeperDeleted.get()) 404 to "{}" else 200 to item(KEEPER_ID).toString()
                }
                // 打开详情会预取播放信息；本测试不播放，返回无媒体源即可。
                "POST /Items/$ITEM_ID/PlaybackInfo" -> 200 to JSONObject().put("MediaSources", JSONArray())
                    .put("PlaySessionId", "detail-management-prefetch").toString()
                "POST /Items/$ITEM_ID/Refresh" -> {
                    check(body.isEmpty())
                    refreshRequest.set(url); refreshPosts.incrementAndGet()
                    check(refreshGate.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "刷新响应门闩未释放" }
                    refreshed.set(true)
                    204 to null
                }
                "DELETE /Items/$ITEM_ID" -> {
                    check(body.isEmpty())
                    deletes.incrementAndGet()
                    check(deleteGate.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "删除响应门闩未释放" }
                    deleted.set(true)
                    204 to null
                }
                "DELETE /Items/$KEEPER_ID" -> {
                    check(body.isEmpty())
                    deletes.incrementAndGet(); keeperDeletes.incrementAndGet()
                    check(keeperDeleteGate.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "第二项删除响应门闩未释放" }
                    batchDeleting.set(false)
                    if (keeperDeleteFails) 500 to "{}" else {
                        keeperDeleted.set(true)
                        204 to null
                    }
                }
                "POST /UserPlayedItems/$ITEM_ID" -> {
                    playedPosts.incrementAndGet()
                    targetPlayed.set(true)
                    200 to JSONObject().put("Played", true).put("IsFavorite", false).put("PlaybackPositionTicks", 0).toString()
                }
                "POST /UserPlayedItems/$KEEPER_ID" -> { playedPosts.incrementAndGet(); 500 to "{}" }
                else -> error("未预期的合成接口：$method ${url.encodedPath}")
            }
        }

        /** 等待中的父项读取没有后续请求体；客户端取消 Call 后会关闭这条 HTTP 连接。 */
        private fun watchParentCancellation(socket: Socket) {
            executor.execute {
                try {
                    check(socket.getInputStream().read() == -1) { "父项响应之前收到额外 HTTP 数据" }
                    parentReadCancelled.set(true)
                } catch (error: SocketTimeoutException) {
                    if (!closed.get()) failures.add(error)
                } catch (_: SocketException) {
                    if (!closed.get()) parentReadCancelled.set(true)
                } catch (error: Throwable) {
                    if (!closed.get()) failures.add(error)
                }
            }
        }

        private fun items() = JSONArray().apply {
            if (!deleted.get()) put(item(ITEM_ID))
            if (!keeperDeleted.get()) put(item(KEEPER_ID))
        }
        private fun item(id: String) = JSONObject().put("Id", id)
            .put("Type", if (id == EPISODE_ID) "Episode" else if (delayedParentRead && id == ITEM_ID) "Series" else "Movie")
            .put("Name", if (id == KEEPER_ID) "Synthetic retained movie" else if (refreshed.get()) REFRESHED_TITLE else TITLE)
            .put("CanDelete", true).put("CanDownload", false).put("ProductionYear", 2026)
            .put("Overview", "Synthetic detail-management fixture")
            .put("Path", "/synthetic/$id.mkv").put("RunTimeTicks", 600_000_000L)
            .put("UserData", JSONObject().put("IsFavorite", false).put("Played", id == ITEM_ID && targetPlayed.get())
                .put("PlaybackPositionTicks", 20_000_000L))
            .apply { if (id == EPISODE_ID) put("SeriesId", ITEM_ID).put("SeriesName", TITLE) }
        private fun page(items: JSONArray) = JSONObject().put("Items", items).put("TotalRecordCount", items.length()).toString()
        private fun line(input: DataInputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 32_768) {
                val next = input.read()
                if (next < 0) { if (bytes.size() == 0) return null; throw EOFException() }
                if (next == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(next)
            }
            error("合成 HTTP 请求头过长")
        }
        override fun close() {
            closed.set(true); releaseRefresh(); releaseDelete(); releaseKeeperDelete(); releaseParentRead(); server.close()
            sockets.forEach { runCatching { it.close() } }; executor.shutdownNow(); accept.join(1000)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val ITEM_ID = "detail-management-target"
        const val KEEPER_ID = "detail-management-keeper"
        const val EPISODE_ID = "detail-management-episode"
        const val LIBRARY_ID = "detail-management-library"
        const val USER_ID = "detail-management-user"
        const val SERVER_ID = "detail-management-server"
        const val TOKEN = "synthetic-detail-management-token"
        const val TITLE = "Pathfin detail management fixture"
        const val REFRESHED_TITLE = "Pathfin detail refreshed fixture"
        const val WAIT_MS = 20_000L
    }
}

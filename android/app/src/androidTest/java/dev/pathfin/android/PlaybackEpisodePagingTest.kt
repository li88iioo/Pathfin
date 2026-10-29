package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.ContentState
import dev.pathfin.android.ui.LocalAppPreferences
import dev.pathfin.android.ui.PlaybackEpisodesSheet
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 播放中选集的真实分页：保留已读范围、滚动追加，并隔离切换到另一部剧的回包。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackEpisodePagingTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun openingEpisodePickerRecoversFailedNeighborsOutsideItsFirstPage() = checkNeighborsRecover(cached = false)

    @Test fun reopeningCachedPickerRetriesNeighborsWithoutReloadingItsPages() = checkNeighborsRecover(cached = true)

    private fun checkNeighborsRecover(cached: Boolean) = withApp { app, fixture ->
        if (cached) {
            prepare(app, fixture, SERIES_A)
            main { app.loadPlaybackEpisodes() }
            awaitPage(app, fixture, PAGE_SIZE)
            awaitJobs(app, fixture, "首次选集和相邻集读取已完成", activeJobs(app))
        }
        val requestsBeforeFailure = fixture.neighborRequests.get()
        fixture.failNextNeighbors.set(true)
        val current = episode(SERIES_A, PAGE_SIZE + 51)
        main { app.preparePlayback(current, fromStart = true) }
        await(app, fixture, "非首屏剧集准备完成并实际请求相邻集") {
            it.activePlayback?.item?.id == current.id && !it.preparingPlay && fixture.neighborRequests.get() == requestsBeforeFailure + 1
        }
        awaitJobs(app, fixture, "等待首次相邻集500实际结束", activeJobs(app))
        assertNull(app.playbackNeighbor(1))
        val pages = fixture.starts(SERIES_A).size
        main { app.loadPlaybackEpisodes() }
        awaitPage(app, fixture, PAGE_SIZE)
        awaitJobs(app, fixture, "打开选集后读取结束", activeJobs(app))
        assertEquals("选集入口必须重试失败的相邻集", requestsBeforeFailure + 2, fixture.neighborRequests.get())
        assertEquals(episodeId(SERIES_A, PAGE_SIZE + 50), app.playbackNeighbor(-1)?.id)
        assertEquals(episodeId(SERIES_A, PAGE_SIZE + 52), app.playbackNeighbor(1)?.id)
        assertFalse("当前集不在第一页，不能用第一页猜测相邻集", app.state.value.playbackEpisodes.items.any { it.id == current.id })
        assertEquals("已缓存的选集页不需要重读", pages + if (cached) 0 else 1, fixture.starts(SERIES_A).size)
        main { app.loadPlaybackEpisodes() }
        awaitJobs(app, fixture, "成功恢复后重开面板不重复请求", activeJobs(app))
        assertEquals(requestsBeforeFailure + 2, fixture.neighborRequests.get())
        val next = requireNotNull(app.playbackNeighbor(1))
        main { app.prepareEpisodePlayback(next) }
        await(app, fixture, "恢复后的下一集可正常进入播放准备") { it.activePlayback?.item?.id == next.id && !it.preparingPlay }
    }

    @Test fun successfulPlayedWriteSurvivesPendingEpisodeReadsAndUpdatesVisiblePlaybackLists() = withApp { app, fixture ->
        val target = episode(SERIES_A, 2)
        val mutation = fixture.blockMutation()
        val neighbors = fixture.blockNeighbors()
        val page = fixture.blockPage(SERIES_A, 0)
        main { app.togglePlayed(target) }
        await(app, fixture, "详情发起的已看写入尚未完成") { mutation.requested }
        prepare(app, fixture, SERIES_A)
        main { app.loadPlaybackEpisodes() }
        await(app, fixture, "选集与相邻集已经拍下未看状态") { neighbors.requested && page.requested }
        mutation.release()
        await(app, fixture, "已看写入成功后才放行旧读取") { "played/${target.id}" !in it.actionBusy }
        assertTrue(fixture.played.contains(target.id))
        neighbors.release(); page.release()
        awaitPage(app, fixture, PAGE_SIZE)
        awaitJobs(app, fixture, "所有旧读取实际完成", activeJobs(app))
        assertTrue("相邻集旧响应不能回退成功写入", app.state.value.playbackNeighbors.single { it.id == target.id }.played)
        assertTrue("选集旧响应不能回退成功写入", app.state.value.playbackEpisodes.items.single { it.id == target.id }.played)

        main { app.togglePlayed(target.copy(played = true)) }
        await(app, fixture, "后续成功操作同步到已显示的两个播放列表") { "played/${target.id}" !in it.actionBusy }
        assertFalse(fixture.played.contains(target.id))
        assertFalse(app.state.value.playbackNeighbors.single { it.id == target.id }.played)
        assertFalse(app.state.value.playbackEpisodes.items.single { it.id == target.id }.played)

        fixture.played.add(target.id)
        main { app.loadPlaybackEpisodes(refresh = true) }
        val fresh = awaitPage(app, fixture, PAGE_SIZE)
        assertTrue("已经结束的旧写入记录不能覆盖未来刷新读到的服务端新值", fresh.items.single { it.id == target.id }.played)
    }

    @Test fun reopeningEpisodePickerKeepsTwoHundredReadEpisodesWithoutAnotherRequest() = withApp { app, fixture ->
        prepare(app, fixture, SERIES_A)
        val previous = readTwoPages(app, fixture)
        assertEquals(listOf(0, PAGE_SIZE), fixture.starts(SERIES_A))

        // MainActivity 每次打开选集都调用此入口；关闭抽屉本身不结束播放。
        main {
            app.loadPlaybackEpisodes()
            assertFalse("重开选集不能重新进入首读态", app.state.value.playbackEpisodes.loading)
            assertEquals("重开选集不能丢掉第二页", previous, app.state.value.playbackEpisodes)
        }
        awaitJobs(app, fixture, "缓存复用后在途任务结束", activeJobs(app))
        assertEquals("缓存命中不能发出新的选集 HTTP", listOf(0, PAGE_SIZE), fixture.starts(SERIES_A))
        assertPage(app.state.value.playbackEpisodes, SERIES_A, PAGE_SIZE * 2)
    }

    @Test fun scrollingActualEpisodeSheetAutomaticallyAppendsToTheLastEpisodeWithoutLoadButton() = withApp { app, fixture ->
        prepare(app, fixture, SERIES_A)
        main { app.loadPlaybackEpisodes() }
        awaitPage(app, fixture, PAGE_SIZE)
        val second = fixture.blockPage(SERIES_A, PAGE_SIZE)
        var shown by mutableStateOf(true)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        try {
            compose.setContent {
                val state by app.state.collectAsState()
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalAppPreferences provides state.preferences) {
                        if (shown) PlaybackEpisodesSheet(state.playbackEpisodes, state.activePlayback!!.item.id, state.preparingPlay,
                            onSelect = { shown = false; app.prepareEpisodePlayback(it) },
                            onRefresh = { app.loadPlaybackEpisodes(refresh = true) }, onMore = { app.loadPlaybackEpisodes(append = true) },
                            onDismiss = { shown = false })
                    }
                }
            }
            compose.onNodeWithTag("playback-episodes").performScrollToIndex(PAGE_SIZE - 1)
            compose.onNodeWithText("加载更多剧集").assertDoesNotExist()
            await(app, fixture, "滚动到第100集后，自动分页请求必须到达服务") { second.requested }
            assertEquals(PAGE_SIZE, app.state.value.playbackEpisodes.items.size)
            assertTrue(app.state.value.playbackEpisodes.loadingMore)
            compose.onNodeWithTag("playback-episodes").performScrollToIndex(0).performScrollToIndex(PAGE_SIZE - 1)
            assertEquals("同一游标在途时只能请求一次", listOf(0, PAGE_SIZE), fixture.starts(SERIES_A))
            second.release()
            awaitPage(app, fixture, PAGE_SIZE * 2, "释放第二页后发布200集")

            compose.onNodeWithTag("playback-episodes").performScrollToIndex(PAGE_SIZE * 2 - 1)
            val complete = awaitPage(app, fixture, TOTAL, "滚动到第200集后自动加载最后50集")
            assertPage(complete, SERIES_A, TOTAL)
            compose.onNodeWithTag("playback-episodes").performScrollToIndex(TOTAL - 1)
            val lastTitle = title(SERIES_A, TOTAL)
            compose.onNodeWithText(lastTitle).assertVerificationDisplayed(compose)
            compose.onNodeWithText("加载更多剧集").assertDoesNotExist()
            awaitJobs(app, fixture, "到达末集后分页和存储任务结束", activeJobs(app))
            assertEquals("到末尾后不请求第四页", listOf(0, PAGE_SIZE, PAGE_SIZE * 2), fixture.starts(SERIES_A))

            compose.onNodeWithText(lastTitle).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            await(app, fixture, "选择末集后完成播放准备") { it.activePlayback?.item?.id == episodeId(SERIES_A, TOTAL) && !it.preparingPlay }
            compose.onNodeWithTag("playback-episodes").assertDoesNotExist()
            assertPage(app.state.value.playbackEpisodes, SERIES_A, TOTAL)
        } finally {
            second.release()
            compose.runOnIdle { shown = false }
            compose.waitForIdle()
        }
    }

    @Test fun switchingSeriesDoesNotReuseThePriorRangeOrAcceptItsLateAppend() = withApp { app, fixture ->
        prepare(app, fixture, SERIES_A)
        readTwoPages(app, fixture)
        val late = fixture.blockPage(SERIES_A, PAGE_SIZE * 2)
        main { app.loadPlaybackEpisodes(true) }
        await(app, fixture, "切剧前旧剧第200游标追加已到达服务") { late.requested }
        val oldJobs = activeJobs(app)
        late.expectCancellation.set(true)

        prepare(app, fixture, SERIES_B)
        assertTrue("切换到另一部剧必须先隔离旧选集", app.state.value.playbackEpisodes.items.isEmpty())
        main { app.loadPlaybackEpisodes() }
        val current = awaitPage(app, fixture, PAGE_SIZE)
        assertPage(current, SERIES_B, PAGE_SIZE)
        late.release()
        await(app, fixture, "切剧后旧追加响应已实际尝试返回") { late.responseAttempted }
        awaitJobs(app, fixture, "切剧后旧剧所有在途任务结束", oldJobs)
        assertPage(app.state.value.playbackEpisodes, SERIES_B, PAGE_SIZE)
        assertEquals(listOf(0, PAGE_SIZE, PAGE_SIZE * 2), fixture.starts(SERIES_A))
        assertEquals("新剧必须从自己的第一页读取", listOf(0), fixture.starts(SERIES_B))
    }

    @Test fun explicitRefreshAtomicallyReplacesAllTwoHundredReadEpisodes() = withApp { app, fixture ->
        prepare(app, fixture, SERIES_A)
        val previous = readTwoPages(app, fixture)
        fixture.revision.set(1)
        val second = fixture.blockPage(SERIES_A, PAGE_SIZE)
        main { app.loadPlaybackEpisodes(refresh = true) }
        await(app, fixture, "刷新200集的第二分片已到达服务") { second.requested }
        main {
            val pending = app.state.value.playbackEpisodes
            assertTrue(pending.loading)
            assertEquals("第二片完成前，旧200集必须完整可见", previous.items, pending.items)
            assertEquals(previous.nextIndex, pending.nextIndex)
        }
        second.release()
        assertPage(awaitPage(app, fixture, PAGE_SIZE * 2), SERIES_A, PAGE_SIZE * 2, revision = 1)
        assertEquals(listOf(0, PAGE_SIZE, 0, PAGE_SIZE), fixture.starts(SERIES_A))
    }

    @Test fun failedSecondRefreshFragmentKeepsTheOldTwoHundredEpisodesAndRetriesTheWholeRange() = withApp { app, fixture ->
        prepare(app, fixture, SERIES_A)
        val previous = readTwoPages(app, fixture)
        fixture.revision.set(1)
        val second = fixture.blockPage(SERIES_A, PAGE_SIZE, fail = true)
        main { app.loadPlaybackEpisodes(refresh = true) }
        await(app, fixture, "失败范围刷新的第二分片已到达服务") { second.requested }
        assertEquals(previous.items, app.state.value.playbackEpisodes.items)
        second.release()
        val failed = await(app, fixture, "第二分片失败后结束刷新并保留旧范围", allowEpisodeError = true) {
            !it.playbackEpisodes.loading && !it.playbackEpisodes.loadingMore && it.playbackEpisodes.error != null
        }.playbackEpisodes
        assertEquals("失败不能发布已返回的新第一片", previous.items, failed.items)
        assertEquals(previous.total, failed.total)
        assertEquals(previous.nextIndex, failed.nextIndex)
        assertFalse("刷新失败应重试范围，不能变成追加", failed.retryAppend)

        main { app.loadPlaybackEpisodes(refresh = true) }
        assertPage(awaitPage(app, fixture, PAGE_SIZE * 2), SERIES_A, PAGE_SIZE * 2, revision = 1)
        assertEquals(listOf(0, PAGE_SIZE, 0, PAGE_SIZE, 0, PAGE_SIZE), fixture.starts(SERIES_A))
    }

    @Test fun pullingAfterAFailedAppendRetriesTheOriginalTwoHundredCursor() = withApp { app, fixture ->
        prepare(app, fixture, SERIES_A)
        val previous = readTwoPages(app, fixture)
        val append = fixture.blockPage(SERIES_A, PAGE_SIZE * 2, fail = true)
        main { app.loadPlaybackEpisodes(append = true) }
        await(app, fixture, "待失败的第200游标追加已到达服务") { append.requested }
        append.release()
        val failed = await(app, fixture, "第200游标追加失败且可下拉重试", allowEpisodeError = true) {
            !it.playbackEpisodes.loadingMore && it.playbackEpisodes.error != null
        }.playbackEpisodes
        assertEquals(previous.items, failed.items)
        assertEquals(previous.nextIndex, failed.nextIndex)
        assertTrue(failed.retryAppend)
        var shown by mutableStateOf(true)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        try {
            compose.setContent {
                val state by app.state.collectAsState()
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalAppPreferences provides state.preferences) {
                        if (shown) PlaybackEpisodesSheet(state.playbackEpisodes, state.activePlayback!!.item.id, state.preparingPlay,
                            onSelect = { shown = false; app.prepareEpisodePlayback(it) },
                            onRefresh = { app.loadPlaybackEpisodes(refresh = true) },
                            onMore = { app.loadPlaybackEpisodes(append = true) }, onDismiss = { shown = false })
                    }
                }
            }
            val list = compose.onNodeWithTag("playback-episodes")
            VerificationDisplay.awaitWindowReady(compose, "failed-episode-page-pull", list)
            list.performVerificationTouchInput(compose) {
                swipeDown()
            }
            compose.waitForIdle()
            assertPage(awaitPage(app, fixture, TOTAL), SERIES_A, TOTAL)
            assertEquals("下拉重试必须从失败的200游标继续，不能改为0", listOf(0, PAGE_SIZE, PAGE_SIZE * 2, PAGE_SIZE * 2),
                fixture.starts(SERIES_A))
        } finally {
            compose.runOnIdle { shown = false }
            compose.waitForIdle()
        }
    }

    private fun readTwoPages(app: AppViewModel, fixture: Fixture): ContentState {
        main { app.loadPlaybackEpisodes() }
        awaitPage(app, fixture, PAGE_SIZE)
        main { app.loadPlaybackEpisodes(true) }
        return awaitPage(app, fixture, PAGE_SIZE * 2).also { assertPage(it, SERIES_A, PAGE_SIZE * 2) }
    }

    private fun prepare(app: AppViewModel, fixture: Fixture, series: String) {
        main { app.preparePlayback(episode(series, 1), fromStart = true) }
        await(app, fixture, "准备剧集 $series 的第一集") { it.activePlayback?.item?.seriesId == series && !it.preparingPlay }
    }

    private fun assertPage(page: ContentState, series: String, count: Int, revision: Int = 0) {
        assertEquals((1..count).map { episodeId(series, it) }, page.items.map { it.id })
        assertEquals((1..count).map { episodeName(series, it, revision) }, page.items.map { it.name })
        assertTrue(page.items.all { it.seriesId == series && it.type == "Episode" })
        assertEquals(TOTAL, page.total)
        assertEquals(count, page.nextIndex)
        assertFalse(page.loading)
        assertFalse(page.loadingMore)
        assertFalse(page.retryAppend)
        assertNull(page.error)
    }

    private fun awaitPage(app: AppViewModel, fixture: Fixture, count: Int, stage: String = "选集范围更新到${count}条"): ContentState = await(app, fixture, stage) {
        !it.playbackEpisodes.loading && !it.playbackEpisodes.loadingMore && it.playbackEpisodes.items.size == count
    }.playbackEpisodes

    private fun withApp(block: (AppViewModel, Fixture) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName == TARGET) { "选集回归只允许独立 verification 包" }
        val preferences = AppPreferencesStore(context)
        val originalPreferences = preferences.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        clearSyntheticSessions(context)
        val store = SessionStore(context)
        check(store.loadProfiles().isEmpty() && store.load() == null) { "存在本测试以外的账号，拒绝启动选集回归" }
        preferences.save(AppPreferences())
        try {
            Fixture().use { fixture ->
                var created: AppViewModel? = null
                try {
                    val app = main { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java] }
                    created = app
                    await(app, fixture, "独立VM完成会话恢复") { !it.restoringSession }
                    main { app.login(fixture.origin, USER_NAME, PASSWORD, true, "Episode paging fixture") }
                    await(app, fixture, "合成账号登录并完成首页读取") { it.session?.accessToken == TOKEN && !it.loginBusy && it.home.loaded && !it.home.loading }
                    block(app, fixture)
                    awaitJobs(app, fixture, "业务断言完成后等待剩余读取和存储提交", activeJobs(app))
                    fixture.assertHealthy()
                } finally {
                    fixture.allowCancellation()
                    val scope = main { created?.viewModelScope?.coroutineContext?.get(Job).also { owner.viewModelStore.clear() } }
                    fixture.releaseAll()
                    created?.let { app -> if (scope != null) awaitJobs(app, fixture, "取消VM后等待存储IO和所有子任务收尾", listOf(scope)) }
                    clearSyntheticSessions(context)
                }
            }
        } finally {
            preferences.save(originalPreferences)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(app: AppViewModel, fixture: Fixture, stage: String, allowEpisodeError: Boolean = false,
        predicate: (AppState) -> Boolean): AppState {
        var observed = main { app.state.value }
        try {
            // 自动分页的重组和 LaunchedEffect 也由测试时钟驱动，不能只阻塞等待 VM StateFlow。
            compose.waitUntil(WAIT) {
                fixture.assertHealthy()
                observed = main { app.state.value }
                observed.loginError?.let { throw AssertionError(it) }
                observed.playbackError?.let { throw AssertionError(it) }
                if (!allowEpisodeError) observed.playbackEpisodes.error?.let { throw AssertionError(it) }
                predicate(observed)
            }
        } catch (failure: Throwable) {
            throw AssertionError("$stage：${diagnostic(app, fixture)}", failure)
        }
        return observed
    }

    private fun activeJobs(app: AppViewModel): List<Job> = main { app.viewModelScope.coroutineContext[Job]!!.children.filter { it.isActive }.toList() }
    private fun awaitJobs(app: AppViewModel, fixture: Fixture, stage: String, jobs: List<Job>) {
        try { compose.waitUntil(WAIT) { jobs.all { it.isCompleted } } }
        catch (failure: Throwable) {
            throw AssertionError("$stage：${diagnostic(app, fixture)} jobs=${jobs.map { "active=${it.isActive},cancelled=${it.isCancelled},completed=${it.isCompleted}" }}", failure)
        }
    }

    private fun diagnostic(app: AppViewModel, fixture: Fixture): String = main {
        val state = app.state.value
        val page = state.playbackEpisodes
        "items=${page.items.size} loading=${page.loading} loadingMore=${page.loadingMore} " +
            "cursor=${page.nextIndex}/${page.total} retryAppend=${page.retryAppend} error=${page.error} " +
            "active=${state.activePlayback?.item?.id} pending=${state.pendingPlayback?.item?.id} " +
            "preparing=${state.preparingPlay} restoring=${state.restoringSession} loginBusy=${state.loginBusy} " +
            "route=${state.route} ${fixture.diagnostic()}"
    }

    private data class PageKey(val series: String, val start: Int)
    private class Gate(fail: Boolean = false) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        val expectCancellation = AtomicBoolean()
        val failResponse = AtomicBoolean(fail)
        fun hold() { entered.countDown(); check(released.await(WAIT, TimeUnit.MILLISECONDS)) { "合成续页未释放" } }
        fun release() = released.countDown()
        fun finish() = finished.countDown()
        val requested get() = entered.count == 0L
        val responseAttempted get() = finished.count == 0L
        fun diagnostic() = "requested=$requested released=${released.count == 0L} responseAttempted=$responseAttempted"
    }

    private class Fixture : Closeable {
        val revision = AtomicInteger()
        val failNextNeighbors = AtomicBoolean()
        val neighborRequests = AtomicInteger()
        val played = ConcurrentHashMap.newKeySet<String>()
        private val mutationGate = AtomicReference<Gate?>()
        private val neighborsGate = AtomicReference<Gate?>()
        private val actionGates = ConcurrentLinkedQueue<Gate>()
        private val requests = ConcurrentLinkedQueue<String>()
        private val pages = ConcurrentLinkedQueue<PageKey>()
        private val gates = ConcurrentHashMap<PageKey, Gate>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val closed = AtomicBoolean()
        private val executor = Executors.newCachedThreadPool { action -> Thread(action, "pathfin-episode-page-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val origin = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept(); sockets += socket
                executor.execute { serve(socket) }
            } catch (error: SocketException) { if (!closed.get()) failures += error }
        }, "pathfin-episode-page-accept").apply { isDaemon = true; start() }

        fun starts(series: String) = pages.filter { it.series == series }.map { it.start }
        fun blockPage(series: String, start: Int, fail: Boolean = false): Gate = Gate(fail).also { check(gates.putIfAbsent(PageKey(series, start), it) == null) }
        fun blockMutation() = Gate().also { check(mutationGate.compareAndSet(null, it)); actionGates.add(it) }
        fun blockNeighbors() = Gate().also { check(neighborsGate.compareAndSet(null, it)); actionGates.add(it) }
        fun allowCancellation() { (gates.values + actionGates).forEach { it.expectCancellation.set(true) } }
        fun releaseAll() { (gates.values + actionGates).forEach(Gate::release) }
        fun assertHealthy() { failures.peek()?.let { throw AssertionError("选集分页 fixture 失败", it) } }
        fun diagnostic() = "requests=${requests.toList()} pageStarts=${pages.toList()} gates=${gates.entries.associate { it.key to it.value.diagnostic() }}"

        private fun serve(socket: Socket) {
            var gate: Gate? = null
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT.toInt()
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val request = reader.readLine()?.split(' ') ?: return
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: throw EOFException("合成请求头未完成")
                        if (line.isEmpty()) break
                        check(':' in line)
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val body = CharArray((headers["content-length"]?.toIntOrNull() ?: 0).also { check(it in 0..65_536) })
                    var read = 0
                    while (read < body.size) {
                        val size = reader.read(body, read, body.size - read)
                        if (size < 0) throw EOFException("合成请求体未完成")
                        read += size
                    }
                    val uri = URI(request[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }
                    requests += "${request[0]} ${uri.path} parent=${query["parentId"]} start=${query["startIndex"]} limit=${query["limit"]}"
                    if (uri.path != "/Users/AuthenticateByName") check(headers["authorization"].orEmpty().contains(TOKEN)) { "合成认证头缺失" }
                    val key = query["parentId"]?.let { PageKey(it, query.getValue("startIndex").toInt()) }.takeIf { uri.path == "/Items" }
                    val mutation = uri.path.startsWith("/UserPlayedItems/")
                    if (key != null) { pages += key; gate = gates[key] }
                    else if (mutation) gate = mutationGate.getAndSet(null)
                    else if (uri.path.startsWith("/Shows/") && uri.path.endsWith("/Episodes")) gate = neighborsGate.getAndSet(null)
                    if (mutation) gate?.hold()
                    val adjacent = uri.path.startsWith("/Shows/") && uri.path.endsWith("/Episodes")
                    if (adjacent) neighborRequests.incrementAndGet()
                    val status = if (gate?.failResponse?.getAndSet(false) == true ||
                        (adjacent && failNextNeighbors.getAndSet(false))) 503 else 200
                    val response = when {
                        mutation -> {
                            val id = uri.path.substringAfterLast('/')
                            when (request[0]) {
                                "POST" -> played.add(id)
                                "DELETE" -> played.remove(id)
                                else -> error("不支持的合成已看写入")
                            }
                            JSONObject().put("Played", id in played)
                        }
                        uri.path == "/Users/AuthenticateByName" -> {
                            val credentials = JSONObject(String(body))
                            check(request[0] == "POST" && credentials.getString("Username") == USER_NAME && credentials.getString("Pw") == PASSWORD)
                            JSONObject().put("AccessToken", TOKEN).put("ServerId", SERVER_ID)
                                .put("User", JSONObject().put("Id", "episode-page-user").put("Name", USER_NAME))
                        }
                        uri.path == "/UserViews" || uri.path == "/UserItems/Resume" || uri.path == "/Shows/NextUp" -> page(emptyList(), 0)
                        uri.path == "/Items/Latest" -> JSONArray()
                        uri.path == "/Items" && key != null -> {
                            check(key.series == SERIES_A || key.series == SERIES_B)
                            check(query["includeItemTypes"] == "Episode" && query["sortOrder"] == "Ascending")
                            val limit = query.getValue("limit").toInt().also { check(it in 1..PAGE_SIZE) }
                            val end = minOf(key.start + limit, TOTAL)
                            page((key.start + 1..end).map { item(key.series, it) }, TOTAL)
                        }
                        uri.path == "/Items" && query["ids"] != null -> {
                            val id = query.getValue("ids")
                            val series = id.substringBeforeLast('-')
                            page(listOf(item(series, id.substringAfterLast('-').toInt())), 1)
                        }
                        uri.path.startsWith("/Shows/") && uri.path.endsWith("/Episodes") -> {
                            val series = uri.path.removePrefix("/Shows/").substringBefore('/')
                            val number = query.getValue("adjacentTo").substringAfterLast('-').toInt()
                            val neighbors = (maxOf(1, number - 1)..minOf(TOTAL, number + 1)).map { item(series, it) }
                            page(neighbors, neighbors.size)
                        }
                        uri.path.startsWith("/Items/") && uri.path.endsWith("/PlaybackInfo") -> {
                            check(request[0] == "POST")
                            val id = uri.path.removePrefix("/Items/").substringBefore('/')
                            JSONObject().put("PlaySessionId", "page-play-$id").put("MediaSources", JSONArray().put(JSONObject()
                                .put("Id", "page-source-$id").put("Path", "/synthetic/$id.mp4").put("Protocol", "File")
                                .put("Container", "mp4").put("SupportsDirectPlay", true)))
                        }
                        else -> error("未定义选集接口：${request[0]} ${uri.path}")
                    }
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    // 响应先拍下服务端值；阻塞期间成功的写入不能悄悄改变这个旧响应。
                    if (!mutation) gate?.hold()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $status ${if (status == 200) "OK" else "Service Unavailable"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get() && !(error is IOException && gate?.expectCancellation?.get() == true)) failures += error
            } finally {
                gate?.finish()
                sockets.remove(socket)
            }
        }

        private fun item(series: String, number: Int) = JSONObject().put("Id", episodeId(series, number)).put("Name", episodeName(series, number, revision.get()))
            .put("Type", "Episode").put("SeriesId", series).put("SeriesName", series).put("ParentIndexNumber", 1)
            .put("IndexNumber", number).put("RunTimeTicks", 600_000_000L)
            .put("UserData", JSONObject().put("Played", episodeId(series, number) in played))
        private fun page(items: List<JSONObject>, total: Int) = JSONObject().put("Items", JSONArray(items)).put("TotalRecordCount", total)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseAll(); server.close()
            sockets.toList().forEach { runCatching { it.close() } }
            executor.shutdownNow()
            accept.join(1000)
            assertHealthy()
        }
    }

    private companion object {
        const val TARGET = "dev.pathfin.android.verification"
        const val WAIT = 20_000L
        const val TOTAL = 250
        const val PAGE_SIZE = 100
        const val SERIES_A = "paging-series-a"
        const val SERIES_B = "paging-series-b"
        const val TOKEN = "episode-page-token"
        const val SERVER_ID = "episode-page-server"
        const val USER_NAME = "episode-page-user"
        const val PASSWORD = "episode-page-password"
        fun episodeId(series: String, number: Int) = "$series-$number"
        fun episodeName(series: String, number: Int, revision: Int = 0) = "$series episode $number" + if (revision == 0) "" else " r$revision"
        fun title(series: String, number: Int) = "S1E$number · ${episodeName(series, number)}"
        fun episode(series: String, number: Int) = MediaItem(episodeId(series, number), episodeName(series, number), "Episode",
            seriesId = series, seriesName = series, parentIndexNumber = 1, indexNumber = number, runTimeTicks = 600_000_000L)
        fun clearSyntheticSessions(context: Context) {
            check(context.packageName == TARGET) { "只允许清理 verification 包的选集合成账号" }
            val store = SessionStore(context)
            store.load()
            val owned = store.loadProfiles().filter { it.session.serverId == SERVER_ID && it.session.accessToken == TOKEN && it.session.userId == USER_NAME }
            owned.forEach { store.removeProfile(it.id) }
            check(store.loadProfiles().none { it.session.serverId == SERVER_ID && it.session.accessToken == TOKEN }) { "选集合成账号清理后仍有残留" }
        }
    }
}

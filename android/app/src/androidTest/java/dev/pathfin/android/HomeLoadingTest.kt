package dev.pathfin.android

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.HomeSnapshotStore
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用进程内回环服务控制各响应的完成次序，验证真实 AppViewModel 的渐进首页。
 * 只允许独立 verification 包；所有账号和媒体均为本测试合成数据。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HomeLoadingTest {
    @Test
    fun slowServerInfoResumeAndSecondLibraryDoNotBlockAvailableHomeContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "首页加载回归只允许独立 verification 包" }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        var app: AppViewModel? = null
        clearSyntheticStorage(context)
        try {
            SlowHomeFixture().use { fixture ->
                lateinit var model: AppViewModel
                val startedAt = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync {
                    model = ViewModelProvider(
                        owner,
                        ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application),
                    )[AppViewModel::class.java]
                    app = model
                    model.login(fixture.baseUrl, FIXTURE_USER, FIXTURE_PASSWORD, allowHttp = true)
                }

                fixture.serverInfo.awaitRequest("可选服务器信息请求应已发出")
                awaitState(model, fixture, "认证完成后不能等待服务器名称才激活会话") {
                    it.session?.serverId == FIXTURE_SERVER_ID && !it.loginBusy
                }
                fixture.resume.awaitRequest("首页应并行读取续播")
                fixture.secondLibrary.awaitRequest("首页应读取第二个媒体库")
                val available = awaitState(model, fixture, "慢续播和第二库不能挡住已返回的库与第一库媒体") { state ->
                    state.home.libraries.map { it.id } == listOf(FIRST_LIBRARY, SECOND_LIBRARY) &&
                        state.home.libraryItems[FIRST_LIBRARY]?.map { it.id } == listOf(FIRST_MOVIE)
                }

                Log.i("PathfinHomeLoading", "first_content_ms=${SystemClock.elapsedRealtime() - startedAt}; optional_info/resume/second_library_still_blocked=true")
                fixture.serverInfo.assertStillBlocked()
                fixture.resume.assertStillBlocked()
                fixture.secondLibrary.assertStillBlocked()
                assertTrue("其它首页请求未完成时加载态仍需保持", available.home.loading)
                assertTrue("已到达的媒体库必须成为可显示内容", available.home.loaded)
                assertFalse("库清单就绪不等于首页完整成功", available.home.complete)
                assertTrue("媒体库结构已由真实响应确认", available.home.librariesLoaded)
                assertFalse("慢续播未返回时不能把未知状态当成空结果", available.home.resumeLoaded)
                assertFalse("未返回的第二库不能伪造媒体", available.home.libraryItems[SECOND_LIBRARY].orEmpty().isNotEmpty())
                assertEquals("存在媒体库时不能全局扫描最近项目", 0, fixture.globalRecentRequests.get())

                // 续播返回后应独立出现；第二库继续阻塞，第一库内容仍保留。
                fixture.resume.release()
                val resumed = awaitState(model, fixture, "续播应独立更新，不能继续等待第二库") {
                    it.home.resume.map { item -> item.id } == listOf(RESUME_MOVIE)
                }
                fixture.secondLibrary.assertStillBlocked()
                fixture.serverInfo.assertStillBlocked()
                assertTrue(resumed.home.loading)
                assertTrue("续播响应后应结束该槽的未知状态", resumed.home.resumeLoaded)
                assertEquals(listOf(FIRST_MOVIE), resumed.home.libraryItems[FIRST_LIBRARY]?.map { it.id })

                fixture.serverInfo.release()
                fixture.secondLibrary.release()
                val complete = awaitState(model, fixture, "释放剩余响应后首页必须完成") { state ->
                    state.home.loaded && !state.home.loading &&
                        state.home.libraryItems[SECOND_LIBRARY]?.map { it.id } == listOf(SECOND_MOVIE)
                }
                assertEquals(listOf(FIRST_MOVIE), complete.home.libraryItems[FIRST_LIBRARY]?.map { it.id })
                assertEquals(listOf(RESUME_MOVIE), complete.home.resume.map { it.id })
                assertTrue("必需请求全部成功后才允许完整缓存复用", complete.home.complete)
                assertTrue("有媒体库时不应展示冗余全局最近列表", complete.home.recent.isEmpty())
                assertEquals("整个加载过程中不能发出全局 recent Items 扫描", 0, fixture.globalRecentRequests.get())
                fixture.assertHealthy()
            }
        } finally {
            instrumentation.runOnMainSync {
                app?.logout()
                owner.viewModelStore.clear()
            }
            clearSyntheticStorage(context)
        }
    }

    @Test
    fun firstResumePublishesBeforeSlowNextUpAndSurvivesItsFailure() =
        withHomeApp(withNextUp = true, blockNextUp = true) { app, fixture ->
            fixture.serverInfo.release()
            fixture.resume.release()
            fixture.secondLibrary.release()
            fixture.nextUp.awaitRequest("下一集必须真实在途，才能验证续播是否被它阻塞")
            val resumed = awaitState(app, fixture, "首次续播一到即显示，不等待下一集") {
                it.home.resumeLoaded && it.home.resume.map { item -> item.id } == listOf(RESUME_MOVIE) &&
                    it.home.libraryItems[FIRST_LIBRARY]?.map { item -> item.id } == listOf(FIRST_MOVIE) &&
                    it.home.libraryItems[SECOND_LIBRARY]?.map { item -> item.id } == listOf(SECOND_MOVIE)
            }
            fixture.nextUp.assertStillBlocked()
            assertTrue(resumed.home.loading)
            assertFalse("慢下一集尚未确认，不能标记该槽已加载", resumed.home.nextUpLoaded)
            assertFalse(resumed.home.complete)
            val snapshots = HomeSnapshotStore(InstrumentationRegistry.getInstrumentation().targetContext)
            awaitCondition("已出现的续播应写入部分缓存，切页/重启不能丢掉") {
                snapshots.load(requireNotNull(resumed.currentProfileId), requireNotNull(resumed.session))?.let {
                    it.resumeLoaded && !it.nextUpLoaded && it.resume.map { item -> item.id } == listOf(RESUME_MOVIE)
                } == true
            }

            fixture.nextUpStatus.set(503)
            fixture.nextUp.release()
            val failed = awaitState(app, fixture, "下一集失败不能清掉已显示的续播", allowHomeError = true) {
                !it.home.loading && it.home.nextUpLoaded && it.home.error != null
            }
            assertEquals(resumed.home.resume, failed.home.resume)
            assertEquals(resumed.home.libraryItems, failed.home.libraryItems)
            assertFalse(failed.home.complete)
            assertTrue("失败不能伪造下一集内容", failed.home.nextUp.isEmpty())

            fixture.nextUpStatus.set(200)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.refreshHome() }
            val recovered = awaitState(app, fixture, "重新请求成功后只补齐缺失的下一集", allowHomeError = true) {
                it.home.complete && !it.home.loading && it.home.error == null
            }
            assertEquals(resumed.home.resume, recovered.home.resume)
            assertEquals(listOf(NEXT_EPISODE), recovered.home.nextUp.map { it.id })
            assertEquals(2, fixture.resumeRequests())
            assertEquals(2, fixture.nextUpRequests())
        }

    @Test
    fun reopeningLoadedCurrentAccountReusesHomeWithoutValidationOrRequests() = withHomeApp { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        fixture.secondLibrary.release()
        val loaded = awaitState(app, fixture, "同账号返回前首页必须完整加载") {
            it.home.loaded && !it.home.loading && it.home.libraryItems.keys.containsAll(listOf(FIRST_LIBRARY, SECOND_LIBRARY))
        }
        fixture.serverInfo.awaitRequest("初次登录的可选服务器信息必须已经发出")
        val profile = loaded.profiles.single { it.id == loaded.currentProfileId }
        val requests = fixture.requestCount()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.showServers()
            assertTrue(app.state.value.showServers)
            assertEquals("显示服务器列表时必须保留已完成首页", loaded.home, app.state.value.home)
            app.openServer(profile)
            assertFalse("返回已加载的当前账号应立即进入首页", app.state.value.showServers)
            assertFalse("命中当前账号缓存不能等待网络校验", app.state.value.loginBusy)
            assertEquals("返回时不能清空已有首页媒体", loaded.home, app.state.value.home)
            assertEquals(loaded.currentProfileId, app.state.value.currentProfileId)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("同账号返回不能追加任何 HTTP 请求", requests, fixture.requestCount())
        assertEquals("命中首页缓存不能重新请求 Users/Me", 0, fixture.userValidationRequests())
        assertEquals(0, fixture.globalRecentRequests.get())
        fixture.assertHealthy()
    }

    @Test
    fun reopeningPartialCurrentAccountKeepsItsExistingRequestsAndAvailableContent() = withHomeApp { app, fixture ->
        fixture.serverInfo.release()
        val partial = awaitState(app, fixture, "部分内容就绪后允许先返回服务器列表") {
            it.home.libraryItems[FIRST_LIBRARY]?.map { item -> item.id } == listOf(FIRST_MOVIE)
        }
        fixture.serverInfo.awaitRequest("初次登录的补充服务器信息应已发出")
        fixture.resume.awaitRequest("续播仍应在途")
        fixture.secondLibrary.awaitRequest("第二库仍应在途")
        val profile = partial.profiles.single { it.id == partial.currentProfileId }
        val requestCount = fixture.requestCount()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.showServers()
            app.openServer(profile)
            assertFalse(app.state.value.showServers)
            assertFalse(app.state.value.loginBusy)
            assertTrue(app.state.value.home.loading)
            assertFalse(app.state.value.home.complete)
            assertFalse("续播未确认之前不能显示下一集的临时去重结果", app.state.value.home.nextUpLoaded)
            assertEquals(partial.home.libraryItems, app.state.value.home.libraryItems)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("返回同账号不能重启仍在进行的首页请求", requestCount, fixture.requestCount())
        assertEquals(0, fixture.userValidationRequests())
        fixture.resume.release()
        fixture.secondLibrary.release()
        awaitState(app, fixture, "原在途请求必须能继续完成首页") { it.home.complete && !it.home.loading }
        assertEquals(1, fixture.resumeRequests())
        assertEquals(1, fixture.latestRequests(SECOND_LIBRARY))
    }

    @Test
    fun switchingAwayFromPartialHomeRestoresVisibleCacheThenCompletesMissingContent() = withHomeApp { app, fixture ->
        fixture.serverInfo.release()
        val partial = awaitState(app, fixture, "先获得可显示的部分 A 首页") {
            it.home.libraryItems[FIRST_LIBRARY]?.map { item -> item.id } == listOf(FIRST_MOVIE)
        }
        fixture.resume.awaitRequest("A 续播必须真实在途")
        fixture.secondLibrary.awaitRequest("A 第二库必须真实在途")
        val first = partial.profiles.single { it.id == partial.currentProfileId }
        SlowHomeFixture().use { second ->
            second.serverInfo.release()
            second.resume.release()
            second.secondLibrary.release()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                app.login(second.baseUrl, FIXTURE_USER, FIXTURE_PASSWORD, allowHttp = true)
            }
            awaitState(app, second, "B 首页应先完整完成") {
                it.session?.server?.baseUrl == second.baseUrl && it.home.complete && !it.home.loading
            }
            second.serverInfo.awaitRequest("B 的补充服务器信息应已发出")
            val secondRequests = second.requestCount()
            fixture.userValidation.release()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.openServer(first) }
            val restored = awaitState(app, fixture, "返回 A 先恢复部分缓存并后台续读") {
                it.currentProfileId == first.id && !it.loginBusy && it.home.loading
            }
            assertEquals(partial.home.libraryItems, restored.home.libraryItems)
            assertFalse(restored.home.complete)
            assertFalse(restored.home.libraryItems.containsKey(SECOND_LIBRARY))
            fixture.resume.release()
            fixture.secondLibrary.release()
            val completed = awaitState(app, fixture, "不能把部分缓存当作完成，缺失的栏必须补齐") {
                it.home.complete && !it.home.loading
            }
            assertEquals(listOf(RESUME_MOVIE), completed.home.resume.map { it.id })
            assertEquals(listOf(SECOND_MOVIE), completed.home.libraryItems[SECOND_LIBRARY]?.map { it.id })
            assertEquals("后台补齐不能读取非当前 B 账号", secondRequests, second.requestCount())
            assertEquals(2, fixture.resumeRequests())
            assertEquals(2, fixture.latestRequests(SECOND_LIBRARY))
            second.assertHealthy()
        }
    }

    @Test
    fun refreshingHomeRetainsLoadedContentAndRequestsNextUpOncePerRefresh() = withHomeApp { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        fixture.secondLibrary.release()
        val loaded = awaitState(app, fixture, "刷新前首页必须完整加载") {
            it.home.loaded && !it.home.loading &&
                it.home.libraryItems.keys.containsAll(listOf(FIRST_LIBRARY, SECOND_LIBRARY)) &&
                it.home.resume.map { item -> item.id } == listOf(RESUME_MOVIE)
        }
        val firstRequests = fixture.latestRequests(FIRST_LIBRARY)
        val secondRequests = fixture.latestRequests(SECOND_LIBRARY)
        val resumeRequests = fixture.resumeRequests()
        val nextUpRequests = fixture.nextUpRequests()
        assertEquals("首次加载应请求一次接下来", 1, nextUpRequests)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.refreshHome()
            val refreshing = app.state.value.home
            assertTrue("刷新开始应保留加载状态", refreshing.loading)
            assertTrue("暖刷新不能把已确认媒体库退回未知", refreshing.librariesLoaded)
            assertTrue("暖刷新不能把已确认续播退回未知", refreshing.resumeLoaded)
            assertEquals("刷新期间不能清空已加载媒体库", loaded.home.libraries, refreshing.libraries)
            assertEquals("刷新期间不能清空各媒体库内容", loaded.home.libraryItems, refreshing.libraryItems)
            assertEquals("刷新期间不能清空续播", loaded.home.resume, refreshing.resume)
        }
        val refreshed = awaitState(app, fixture, "显式刷新应完成首页请求") {
            it.home.loaded && !it.home.loading
        }
        assertEquals(loaded.home.libraries, refreshed.home.libraries)
        assertEquals(loaded.home.libraryItems, refreshed.home.libraryItems)
        assertEquals(loaded.home.resume, refreshed.home.resume)
        assertEquals(firstRequests + 1, fixture.latestRequests(FIRST_LIBRARY))
        assertEquals(secondRequests + 1, fixture.latestRequests(SECOND_LIBRARY))
        assertEquals(resumeRequests + 1, fixture.resumeRequests())
        assertEquals(nextUpRequests + 1, fixture.nextUpRequests())
        fixture.assertHealthy()
    }

    @Test
    fun newViewModelRestoresTheWholeHomeBeforeSlowSessionValidation() = withHomeApp(withNextUp = true) { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        fixture.secondLibrary.release()
        val loaded = awaitState(app, fixture, "冷启动回归前先完成真实首页") { it.home.complete && !it.home.loading }
        assertEquals(listOf(NEXT_EPISODE), loaded.home.nextUp.map { it.id })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshots = HomeSnapshotStore(context)
        awaitCondition("完整首页必须实际落盘，而不只存在旧 ViewModel 内存") {
            snapshots.load(requireNotNull(loaded.currentProfileId), requireNotNull(loaded.session))?.complete == true
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            lateinit var restored: AppViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                restored = ViewModelProvider(owner,
                    ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java]
            }
            fixture.userValidation.awaitRequest("新 ViewModel 应真实校验恢复的会话")
            val firstFrame = awaitState(restored, fixture, "慢会话校验之前就应恢复完整快照") {
                !it.restoringSession && it.home.loaded
            }
            fixture.userValidation.assertStillBlocked()
            assertEquals("库、观看分组及已加载标记必须在首次发布中一起恢复", loaded.home, firstFrame.home)
            assertTrue(firstFrame.home.nextUpLoaded && firstFrame.home.resumeLoaded && firstFrame.home.librariesLoaded)
            assertEquals("恢复前不能重新请求 NextUp 造成先缺行再插入", 1, fixture.nextUpRequests())
            fixture.userValidation.release()
            awaitState(restored, fixture, "校验完成后的后台刷新应保留并更新完整首页") {
                it.home.complete && !it.home.loading && fixture.nextUpRequests() == 2
            }
            assertEquals(loaded.home, restored.state.value.home)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.viewModelStore.clear() }
        }
    }

    @Test
    fun warmWatchingGroupsPublishTogetherWithoutBlockingRecentLibraryContent() = withHomeApp(withNextUp = true) { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        fixture.secondLibrary.release()
        val loaded = awaitState(app, fixture, "暖刷新前应有完整的观看分组") { it.home.complete && !it.home.loading }
        fixture.resumeSeries.set("next-series")
        fixture.nextUpSeries.set("other-series")
        fixture.latestVersion.set(1)
        fixture.nextUp = ResponseGate()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.refreshHome() }
        fixture.nextUp.awaitRequest("控制 NextUp 保持在途，不能等待它才显示最新入库")
        awaitCondition("续播新响应应已经送出") { fixture.completedResumeRequests.get() == 2 }
        val partial = awaitState(app, fixture, "NextUp 未完成时最近入库仍应独立刷新") {
            it.home.loading && it.home.libraryItems[FIRST_LIBRARY]?.singleOrNull()?.name == "已就绪影片 1"
        }
        fixture.nextUp.assertStillBlocked()
        assertEquals("不能先用新续播与旧 NextUp 去重而收起整行", loaded.home.resume, partial.home.resume)
        assertEquals(loaded.home.nextUp, partial.home.nextUp)
        fixture.nextUp.release()
        val refreshed = awaitState(app, fixture, "两份观看响应完成后应一次提交去重所需数据") {
            it.home.complete && !it.home.loading
        }
        assertEquals("next-series", refreshed.home.resume.single().seriesId)
        assertEquals("other-series", refreshed.home.nextUp.single().seriesId)
        assertTrue(refreshed.home.resumeLoaded && refreshed.home.nextUpLoaded)
    }

    @Test
    fun hiddenLibraryLoadsLatestOnlyAfterItBecomesVisible() = withHomeApp(setOf(SECOND_LIBRARY)) { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        val visible = awaitState(app, fixture, "隐藏库不能拖住可见首页加载完成") {
            it.home.loaded && !it.home.loading && it.home.libraryItems[FIRST_LIBRARY]?.map { item -> item.id } == listOf(FIRST_MOVIE)
        }
        assertTrue(SECOND_LIBRARY in visible.hiddenLibraries)
        assertFalse("隐藏库不能伪造已经读取的媒体", visible.home.libraryItems.containsKey(SECOND_LIBRARY))
        assertEquals("隐藏库不应发出 Latest 请求", 0, fixture.latestRequests(SECOND_LIBRARY))
        assertEquals(1, fixture.latestRequests(FIRST_LIBRARY))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.toggleHomeLibrary(SECOND_LIBRARY) }
        fixture.secondLibrary.awaitRequest("取消隐藏后应按需请求该库的 Latest")
        val loading = awaitState(app, fixture, "取消隐藏期间已有媒体必须保留") {
            SECOND_LIBRARY !in it.hiddenLibraries && it.home.libraryItems[FIRST_LIBRARY]?.map { item -> item.id } == listOf(FIRST_MOVIE)
        }
        fixture.secondLibrary.assertStillBlocked()
        assertFalse(loading.home.libraryItems.containsKey(SECOND_LIBRARY))
        assertEquals("取消隐藏应只发出该库的一次读取", 1, fixture.latestRequests(SECOND_LIBRARY))
        fixture.secondLibrary.release()
        val complete = awaitState(app, fixture, "取消隐藏的库响应后应出现其真实媒体") {
            it.home.libraryItems[SECOND_LIBRARY]?.map { item -> item.id } == listOf(SECOND_MOVIE) && !it.home.loading
        }
        assertEquals(listOf(FIRST_MOVIE), complete.home.libraryItems[FIRST_LIBRARY]?.map { it.id })
        assertEquals(1, fixture.latestRequests(SECOND_LIBRARY))
        assertEquals(0, fixture.globalRecentRequests.get())
    }

    @Test
    fun resumeFailureCompletesItsSlotAndRetryAndWarmFailureRetainAvailableContent() = withHomeApp { app, fixture ->
        fixture.resumeStatus.set(503)
        fixture.serverInfo.release()
        fixture.secondLibrary.release()
        fixture.resume.release()
        val failed = awaitState(app, fixture, "续播失败也必须结束未知占位，不能挡住真实媒体库", allowHomeError = true) {
            !it.home.loading && it.home.error != null && it.home.librariesLoaded && it.home.resumeLoaded
        }
        assertTrue(failed.home.loaded)
        assertFalse("局部失败不能被标为完整缓存", failed.home.complete)
        assertEquals(listOf(FIRST_LIBRARY, SECOND_LIBRARY), failed.home.libraries.map { it.id })
        assertEquals(listOf(FIRST_MOVIE), failed.home.libraryItems[FIRST_LIBRARY]?.map { it.id })
        assertTrue("失败不能伪造续播记录", failed.home.resume.isEmpty())

        fixture.resumeStatus.set(200)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.showServers()
            app.openServer(failed.profiles.single { it.id == failed.currentProfileId })
            assertEquals("重试期间仍应保留已到达的库封面", failed.home.libraryItems, app.state.value.home.libraryItems)
            assertTrue(app.state.value.home.resumeLoaded)
        }
        val recovered = awaitState(app, fixture, "真实刷新应恢复续播并清除错误", allowHomeError = true) {
            !it.home.loading && it.home.error == null && it.home.resume.map { item -> item.id } == listOf(RESUME_MOVIE)
        }
        assertTrue(recovered.home.complete)
        fixture.resumeStatus.set(503)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.refreshHome() }
        val failedWarm = awaitState(app, fixture, "暖刷新失败不能清空已有续播", allowHomeError = true) {
            !it.home.loading && it.home.error != null
        }
        assertEquals(recovered.home.resume, failedWarm.home.resume)
        assertEquals(recovered.home.libraryItems, failedWarm.home.libraryItems)
        assertTrue(failedWarm.home.librariesLoaded)
        assertTrue(failedWarm.home.resumeLoaded)
        assertFalse(failedWarm.home.complete)
        assertEquals("初次失败、恢复和暖失败应各读取一次真实续播接口", 3, fixture.resumeRequests())
    }

    @Test
    fun failedLibraryListRetainsCachedShelvesWithoutRequestingGlobalLatest() = withHomeApp { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        fixture.secondLibrary.release()
        val loaded = awaitState(app, fixture, "先完成可见媒体库首页") { it.home.complete && !it.home.loading }
        val firstRequests = fixture.latestRequests(FIRST_LIBRARY)
        val secondRequests = fixture.latestRequests(SECOND_LIBRARY)

        fixture.librariesStatus.set(503)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.refreshHome() }
        val failed = awaitState(app, fixture, "媒体库清单失败应保留旧海报并结束，不能当成真实空库", allowHomeError = true) {
            !it.home.loading && it.home.error != null
        }
        assertEquals(loaded.home.libraries, failed.home.libraries)
        assertEquals(loaded.home.libraryItems, failed.home.libraryItems)
        assertTrue(failed.home.recent.isEmpty())
        assertFalse(failed.home.complete)
        assertEquals(firstRequests, fixture.latestRequests(FIRST_LIBRARY))
        assertEquals(secondRequests, fixture.latestRequests(SECOND_LIBRARY))
        assertEquals("失败的库清单不能触发全局扫描", 0, fixture.globalRecentRequests.get())

        fixture.librariesStatus.set(200)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.refreshHome() }
        val recovered = awaitState(app, fixture, "真实清单恢复后应正常更新每个库", allowHomeError = true) {
            it.home.complete && !it.home.loading && it.home.error == null
        }
        assertEquals(loaded.home.libraryItems, recovered.home.libraryItems)
        assertEquals(firstRequests + 1, fixture.latestRequests(FIRST_LIBRARY))
        assertEquals(secondRequests + 1, fixture.latestRequests(SECOND_LIBRARY))
    }

    @Test
    fun emptyLibrariesRequireSuccessfulFallbackBeforeHomeCanBeReused() = withHomeApp(emptyLibraries = true) { app, fixture ->
        fixture.serverInfo.release()
        fixture.resume.release()
        fixture.fallbackStatus.set(503)
        fixture.fallbackLatest.awaitRequest("空库时应真实读取全局 Latest")
        val partial = awaitState(app, fixture, "空库结构响应不能提前标为完整首页") { it.home.librariesLoaded }
        assertFalse(partial.home.complete)
        fixture.fallbackLatest.release()
        val failed = awaitState(app, fixture, "fallback 失败必须结束忙态但禁止完整缓存复用", allowHomeError = true) {
            !it.home.loading && it.home.error != null
        }
        assertFalse(failed.home.complete)
        fixture.fallbackStatus.set(200)
        val profile = failed.profiles.single { it.id == failed.currentProfileId }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.showServers(); app.openServer(profile) }
        val recovered = awaitState(app, fixture, "再次进入空库首页必须完成真实 fallback", allowHomeError = true) {
            it.home.complete && !it.home.loading && it.home.error == null
        }
        assertTrue(recovered.home.libraries.isEmpty())
        assertEquals(2, fixture.globalRecentRequests.get())
        val requests = fixture.requestCount()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.showServers(); app.openServer(profile) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("成功的真实空结果也应可以复用", requests, fixture.requestCount())
    }

    @Test
    fun successfulWritesSurviveHomePublicationAndDiskRestoreButDoNotOverrideFutureRefreshes() = withHomeApp(withNextUp = true) { app, fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fixture.serverInfo.release(); fixture.resume.release(); fixture.secondLibrary.release()
        val loaded = awaitState(app, fixture, "先完成真实首页") { it.home.complete && !it.home.loading }
        val resume = loaded.home.resume.single()
        val nextUp = loaded.home.nextUp.single()
        val second = loaded.home.libraryItems.getValue(SECOND_LIBRARY).single()
        assertFalse(resume.isFavorite || resume.played || nextUp.isFavorite || second.isFavorite)

        fixture.secondLibrary = ResponseGate()
        fixture.nextUp = ResponseGate()
        instrumentation.runOnMainSync { app.refreshHome() }
        fixture.secondLibrary.awaitRequest("慢库必须先捕获操作前的海报数据")
        fixture.nextUp.awaitRequest("下一集保持阻塞，续播即使已收到也不能先发布")
        val client = JellyfinApi::class.java.getDeclaredField("client").let { field ->
            field.isAccessible = true; field.get(app.api) as OkHttpClient
        }
        awaitCondition("续播响应必须已实际交给客户端，才能覆盖等待下一集期间的写入") {
            fixture.completedResumeRequests.get() == 2 &&
                client.dispatcher.runningCalls().none { it.request().url.encodedPath == "/UserItems/Resume" }
        }
        instrumentation.waitForIdleSync()
        assertTrue(app.state.value.home.loading)
        for (item in listOf(resume, nextUp, second)) {
            instrumentation.runOnMainSync { app.toggleFavorite(item) }
            awaitState(app, fixture, "收藏 ${item.id} 应先写入成功") { "favorite/${item.id}" !in it.actionBusy }
        }
        instrumentation.runOnMainSync { app.togglePlayed(resume) }
        awaitState(app, fixture, "同一次读取中的已看写入不能擦掉较早的收藏") { "played/${resume.id}" !in it.actionBusy }
        fixture.secondLibrary.release(); fixture.nextUp.release()
        val fresh = awaitState(app, fixture, "所有首页旧响应完成") { it.home.complete && !it.home.loading }
        assertTrue("已送达但等下一集的续播仍应合并成功写入", fresh.home.resume.single().let { it.isFavorite && it.played })
        assertTrue("旧下一集响应不能回退收藏", fresh.home.nextUp.single().isFavorite)
        assertTrue("旧库海报响应不能回退收藏", fresh.home.libraryItems.getValue(SECOND_LIBRARY).single().isFavorite)
        assertEquals("无关媒体库仍完成", loaded.home.libraryItems[FIRST_LIBRARY], fresh.home.libraryItems[FIRST_LIBRARY])

        val snapshots = HomeSnapshotStore(context)
        awaitCondition("合并后的首页必须实际落盘") {
            snapshots.load(requireNotNull(fresh.currentProfileId), requireNotNull(fresh.session))?.let { home ->
                home.complete && home.resume.single().let { it.isFavorite && it.played } &&
                    home.nextUp.single().isFavorite && home.libraryItems.getValue(SECOND_LIBRARY).single().isFavorite
            } == true
        }
        val restoredOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            lateinit var restored: AppViewModel
            instrumentation.runOnMainSync {
                restored = ViewModelProvider(restoredOwner,
                    ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java]
            }
            fixture.userValidation.awaitRequest("恢复会话校验保持阻塞，只能展示磁盘数据")
            val restoredState = awaitState(restored, fixture, "新实例恢复合并后的快照") { !it.restoringSession && it.home.loaded }
            fixture.userValidation.assertStillBlocked()
            assertEquals(fresh.home, restoredState.home)
        } finally {
            instrumentation.runOnMainSync { restoredOwner.viewModelStore.clear() }
        }

        // 其他设备后来改变了服务器状态；本地成功写入不能永久遮盖未来的真实刷新。
        fixture.favorites.clear(); fixture.played.clear()
        instrumentation.runOnMainSync { app.refreshHome() }
        val later = awaitState(app, fixture, "全新刷新应接受服务器后来更改的状态") { it.home.complete && !it.home.loading }
        assertFalse(later.home.resume.single().let { it.isFavorite || it.played })
        assertFalse(later.home.nextUp.single().isFavorite)
        assertFalse(later.home.libraryItems.getValue(SECOND_LIBRARY).single().isFavorite)
    }

    private fun withHomeApp(
        initiallyHidden: Set<String> = emptySet(),
        emptyLibraries: Boolean = false,
        withNextUp: Boolean = false,
        blockNextUp: Boolean = false,
        block: (AppViewModel, SlowHomeFixture) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "首页缓存回归只允许独立 verification 包" }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var app: AppViewModel? = null
        clearSyntheticStorage(context)
        if (initiallyHidden.isNotEmpty()) {
            AppPreferencesStore(context).saveHiddenLibraries("$FIXTURE_SERVER_ID/$FIXTURE_USER_ID", initiallyHidden)
        }
        try {
            SlowHomeFixture(emptyLibraries, withNextUp, blockNextUp).use { fixture ->
                lateinit var model: AppViewModel
                instrumentation.runOnMainSync {
                    model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java]
                    app = model
                    model.login(fixture.baseUrl, FIXTURE_USER, FIXTURE_PASSWORD, allowHttp = true)
                }
                block(model, fixture)
                fixture.assertHealthy()
            }
        } finally {
            instrumentation.runOnMainSync { app?.logout(); owner.viewModelStore.clear() }
            clearSyntheticStorage(context)
        }
    }

    private fun awaitState(
        app: AppViewModel,
        fixture: SlowHomeFixture,
        description: String,
        allowHomeError: Boolean = false,
        predicate: (AppState) -> Boolean,
    ): AppState = try {
        runBlocking {
            withTimeout(WAIT_MS) {
                app.state.first { state ->
                    fixture.assertHealthy()
                    state.loginError?.let { throw AssertionError("$description；登录失败：$it") }
                    if (!allowHomeError) state.home.error?.let { throw AssertionError("$description；首页失败：$it") }
                    if (!fixture.emptyLibraries) assertEquals("存在媒体库时不能发出全局 recent 请求", 0, fixture.globalRecentRequests.get())
                    predicate(state)
                }
            }
        }
    } catch (failure: TimeoutCancellationException) {
        val state = app.state.value
        throw AssertionError(
            "$description；session=${state.session != null}, libraries=${state.home.libraries.map { it.id }}, " +
                "sections=${state.home.libraryItems.keys}, loading=${state.home.loading}",
            failure,
        )
    }

    private fun clearSyntheticStorage(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        val store = SessionStore(context)
        store.loadProfiles().forEach { HomeSnapshotStore(context).removeProfile(it.id); store.removeProfile(it.id) }
        store.clear()
        check(context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        try {
            runBlocking { withTimeout(WAIT_MS) { while (!predicate()) delay(10) } }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(description, failure)
        }
    }

    private class ResponseGate(initiallyReleased: Boolean = false) {
        private val requested = CountDownLatch(1)
        private val released = CountDownLatch(if (initiallyReleased) 0 else 1)

        fun blockResponse() {
            requested.countDown()
            check(released.await(WAIT_MS * 3, TimeUnit.MILLISECONDS)) { "首页 fixture 响应未被测试释放" }
        }

        fun awaitRequest(message: String) {
            assertTrue(message, requested.await(WAIT_MS, TimeUnit.MILLISECONDS))
        }

        fun assertStillBlocked() {
            assertEquals("慢请求必须已经进入 fixture", 0L, requested.count)
            assertEquals("断言渐进内容前不能提前释放慢请求", 1L, released.count)
        }

        fun release() = released.countDown()
    }

    /** 每个连接独立处理，慢响应不会占住快媒体库的 HTTP 工作线程。 */
    private class SlowHomeFixture(val emptyLibraries: Boolean = false, withNextUp: Boolean = false,
        blockNextUp: Boolean = false) : Closeable {
        val serverInfo = ResponseGate()
        val resume = ResponseGate()
        @Volatile var secondLibrary = ResponseGate()
        val userValidation = ResponseGate()
        val fallbackLatest = ResponseGate()
        val globalRecentRequests = AtomicInteger()
        val resumeStatus = AtomicInteger(200)
        val nextUpStatus = AtomicInteger(200)
        val librariesStatus = AtomicInteger(200)
        val fallbackStatus = AtomicInteger(200)
        @Volatile var nextUp = ResponseGate(initiallyReleased = !blockNextUp)
        val resumeSeries = AtomicReference<String?>(null)
        val nextUpSeries = AtomicReference(if (withNextUp) "next-series" else null)
        val latestVersion = AtomicInteger()
        val completedResumeRequests = AtomicInteger()
        val favorites = ConcurrentHashMap.newKeySet<String>()
        val played = ConcurrentHashMap.newKeySet<String>()
        private val requests = ConcurrentLinkedQueue<Pair<String, Map<String, String>>>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val closed = AtomicBoolean()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "pathfin-home-response").apply { isDaemon = true }
        }
        private val server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val acceptThread = Thread({
            while (!closed.get()) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    executor.execute { serve(socket) }
                } catch (failure: SocketException) {
                    if (!closed.get()) failures.add(failure)
                }
            }
        }, "pathfin-home-accept").apply { isDaemon = true; start() }

        fun assertHealthy() {
            failures.peek()?.let { throw AssertionError("首页 fixture 执行失败", it) }
        }

        fun nextUpRequests() = requests.count { it.first == "/Shows/NextUp" }

        fun requestCount() = requests.size
        fun latestRequests(parentId: String) = requests.count { it.first == "/Items/Latest" && it.second["parentId"] == parentId }
        fun resumeRequests() = requests.count { it.first == "/UserItems/Resume" }
        fun userValidationRequests() = requests.count { it.first == "/Users/Me" }

        private fun serve(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = WAIT_MS.toInt()
                    val input = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine() ?: return
                    val parts = requestLine.split(' ')
                    check(parts.size == 3) { "无效 fixture HTTP 请求行" }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: throw EOFException("fixture HTTP 头未完成")
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        check(separator > 0) { "无效 fixture HTTP 头" }
                        headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                    }
                    val bodyLength = headers["content-length"]?.toInt() ?: 0
                    check(bodyLength in 0..32_768) { "fixture 请求体过大" }
                    val body = CharArray(bodyLength)
                    var offset = 0
                    while (offset < bodyLength) {
                        val read = input.read(body, offset, bodyLength - offset)
                        if (read < 0) throw EOFException("fixture HTTP body 未完成")
                        offset += read
                    }
                    val uri = URI(parts[1])
                    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { parameter ->
                        val pair = parameter.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    requests.add(uri.path to query)
                    val response = response(parts[0], uri.path, query, String(body))
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    val output = connection.getOutputStream()
                    val status = when {
                        uri.path == "/UserItems/Resume" -> resumeStatus.get()
                        uri.path == "/Shows/NextUp" -> nextUpStatus.get()
                        uri.path == "/UserViews" -> librariesStatus.get()
                        uri.path == "/Items/Latest" && query["parentId"] == null -> fallbackStatus.get()
                        else -> 200
                    }
                    output.write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                    if (uri.path == "/UserItems/Resume") completedResumeRequests.incrementAndGet()
                }
            } catch (_: IOException) {
                // 切服取消在途 HTTP 时，服务端向已关闭连接写响应属于正常结束。
            } catch (failure: Throwable) {
                if (!closed.get()) failures.add(failure)
            } finally {
                sockets.remove(socket)
            }
        }

        private fun response(method: String, path: String, query: Map<String, String>, body: String): Any = when (path) {
            "/Users/AuthenticateByName" -> {
                check(method == "POST")
                val credentials = JSONObject(body)
                check(credentials.getString("Username") == FIXTURE_USER && credentials.getString("Pw") == FIXTURE_PASSWORD)
                JSONObject().put("AccessToken", "home-loading-token").put("ServerId", FIXTURE_SERVER_ID)
                    .put("User", JSONObject().put("Id", FIXTURE_USER_ID).put("Name", FIXTURE_USER))
            }
            "/Users/Me" -> {
                userValidation.blockResponse()
                JSONObject().put("Id", FIXTURE_USER_ID).put("Name", FIXTURE_USER)
            }
            "/System/Info/Public" -> {
                serverInfo.blockResponse()
                JSONObject().put("Id", FIXTURE_SERVER_ID).put("ServerName", "渐进加载测试服务器").put("Version", "12.3.0")
            }
            "/UserViews" -> if (emptyLibraries) page() else page(
                item(FIRST_LIBRARY, "快媒体库", folder = true),
                item(SECOND_LIBRARY, "慢媒体库", folder = true),
            )
            "/UserItems/Resume" -> {
                check(query["enableTotalRecordCount"] == "false")
                val response = page(item(RESUME_MOVIE, "慢续播").apply {
                    resumeSeries.get()?.let { put("Type", "Episode").put("SeriesId", it).put("SeriesName", "续播剧集") }
                })
                resume.blockResponse()
                response
            }
            "/Items/Latest" -> when {
                query["groupItems"] != "true" -> error("首页 Latest 必须显式使用 Series 分组")
                query["parentId"] == FIRST_LIBRARY -> JSONArray(listOf(item(FIRST_MOVIE,
                    if (latestVersion.get() == 0) "已就绪影片" else "已就绪影片 ${latestVersion.get()}")))
                query["parentId"] == SECOND_LIBRARY -> {
                    val response = JSONArray(listOf(item(SECOND_MOVIE, "慢库影片")))
                    secondLibrary.blockResponse()
                    response
                }
                else -> {
                    globalRecentRequests.incrementAndGet()
                    fallbackLatest.blockResponse()
                    JSONArray()
                }
            }
            "/Items" -> {
                globalRecentRequests.incrementAndGet()
                page()
            }
            "/Shows/NextUp" -> {
                check(query["enableResumable"] == "false") { "接下来不能混入可续播条目" }
                val response = nextUpSeries.get()?.let { series -> page(item(NEXT_EPISODE, "接下来一集")
                    .put("Type", "Episode").put("SeriesId", series).put("SeriesName", "待看剧集")) } ?: page()
                nextUp.blockResponse()
                response
            }
            else -> {
                check(method in setOf("POST", "DELETE"))
                val id = path.substringAfterLast('/')
                when {
                    path.startsWith("/UserFavoriteItems/") -> {
                        if (method == "POST") favorites.add(id) else favorites.remove(id)
                        JSONObject().put("IsFavorite", method == "POST")
                    }
                    path.startsWith("/UserPlayedItems/") -> {
                        if (method == "POST") played.add(id) else played.remove(id)
                        JSONObject().put("Played", method == "POST").put("PlaybackPositionTicks", 0L)
                    }
                    else -> error("首页 fixture 收到未定义路由：$method $path")
                }
            }
        }

        private fun item(id: String, name: String, folder: Boolean = false) = JSONObject()
            .put("Id", id).put("Name", name).put("Type", if (folder) "CollectionFolder" else "Movie")
            .put("IsFolder", folder).put("ImageTags", JSONObject())
            .put("UserData", JSONObject().put("IsFavorite", id in favorites).put("Played", id in played))

        private fun page(vararg items: JSONObject) = JSONObject()
            .put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size)

        override fun close() {
            closed.set(true)
            serverInfo.release()
            resume.release()
            secondLibrary.release()
            userValidation.release()
            fallbackLatest.release()
            nextUp.release()
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            acceptThread.join(2_000)
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val FIXTURE_USER = "home-loading"
        const val FIXTURE_PASSWORD = "home-loading"
        const val FIXTURE_SERVER_ID = "home-loading-server"
        const val FIXTURE_USER_ID = "home-loading-user"
        const val FIRST_LIBRARY = "home-loading-library-fast"
        const val SECOND_LIBRARY = "home-loading-library-slow"
        const val FIRST_MOVIE = "home-loading-movie-fast"
        const val SECOND_MOVIE = "home-loading-movie-slow"
        const val RESUME_MOVIE = "home-loading-movie-resume"
        const val NEXT_EPISODE = "home-loading-next-episode"
        const val WAIT_MS = 20_000L
    }
}

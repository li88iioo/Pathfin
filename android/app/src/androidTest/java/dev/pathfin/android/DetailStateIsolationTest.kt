package dev.pathfin.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MetadataRefreshOptions
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.ui.AppState
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.BatchMediaAction
import dev.pathfin.android.ui.Route
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 VM 与可控回环响应；验证缓存详情以及跨条目的读写请求互不取消。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DetailStateIsolationTest {
    @Test
    fun unfinishedParentDetailResumesAfterReturningFromAChildDetail() =
        withApp(blockFirstA = true) { fixture, app ->
            main { app.open(MediaItem(ITEM_A, "Synthetic parent summary", "Movie")) }
            fixture.firstA.awaitRequest("父详情的完整读取必须实际在途")
            fixture.expectCancelledExtras.set(true)
            main { app.open(MediaItem(ITEM_B, "Synthetic child summary", "Movie")) }
            await(app, fixture, "子详情独立完成") {
                it.route == Route.Detail(ITEM_B) && !it.detailLoading && it.details[ITEM_B]?.overview == overview(ITEM_B, 1)
            }
            main {
                app.back()
                assertEquals(Route.Detail(ITEM_A), app.state.value.route)
                assertTrue("返回不完整的父详情必须恢复完整读取，不能把简略 DTO 当成已加载", app.state.value.detailLoading)
            }
            fixture.firstA.release()
            val restored = await(app, fixture, "父详情必须最终补齐完整字段") {
                it.route == Route.Detail(ITEM_A) && !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 1)
            }
            assertEquals(1, restored.details.getValue(ITEM_A).people.size)
            assertEquals(2, fixture.aDetailReads.get())
            fixture.firstA.awaitFinished()
            assertEquals(Route.Detail(ITEM_A), app.state.value.route)
        }

    @Test
    fun deletingTheCurrentChildResumesItsIncompleteParentWithoutRepeatingTheDelete() =
        withApp(blockFirstA = true, blockSecondA = true) { fixture, app ->
            main { app.open(MediaItem(ITEM_A, "Synthetic parent summary", "Movie")) }
            fixture.firstA.awaitRequest("父详情的完整读取必须实际在途")
            fixture.expectCancelledExtras.set(true)
            main { app.open(MediaItem(ITEM_B, "Synthetic child summary", "Movie")) }
            val child = await(app, fixture, "待删除的子详情必须先完整读取") {
                it.route == Route.Detail(ITEM_B) && !it.detailLoading && it.details[ITEM_B]?.overview == overview(ITEM_B, 1)
            }.details.getValue(ITEM_B)

            main { app.deleteMedia(child) }
            fixture.delete.awaitRequest("删除必须真正到达服务器并等待确认")
            assertEquals("DELETE 未确认前仍停留子详情", Route.Detail(ITEM_B), app.state.value.route)
            fixture.delete.release()
            val returned = await(app, fixture, "删除完成应自动退回父详情并清除已删除子项") {
                "delete/$ITEM_B" !in it.actionBusy && it.route == Route.Detail(ITEM_A) && ITEM_B !in it.details
            }
            assertTrue("自动退回不完整父详情时必须恢复完整读取", returned.detailLoading)
            fixture.secondA.awaitRequest("自动弹栈必须实际重发被取消的父详情读取")
            assertFalse(returned.routes.any { it == Route.Detail(ITEM_B) })
            assertEquals("恢复父详情不能重复删除请求", 1, fixture.deletes.get())

            fixture.firstA.release()
            fixture.firstA.awaitFinished()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Route.Detail(ITEM_A), app.state.value.route)
            assertTrue("旧父详情的迟到响应不能结束新的读取", app.state.value.detailLoading)
            assertFalse("迟到响应不能恢复已删除子项", ITEM_B in app.state.value.details)

            fixture.secondA.release()
            val restored = await(app, fixture, "自动退回的父详情必须最终补齐完整字段") {
                it.route == Route.Detail(ITEM_A) && !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 1)
            }
            assertEquals(1, restored.details.getValue(ITEM_A).people.size)
            assertEquals("取消的读取与一次恢复读取之外不能重复请求父详情", 2, fixture.aDetailReads.get())
            assertEquals(1, fixture.bDetailReads.get())
            assertEquals(1, fixture.deletes.get())
            assertFalse(ITEM_B in restored.details)
        }

    @Test
    fun deletingAnotherItemKeepsTheCurrentDetailsPrefetchAvailableForPlayback() =
        withApp { fixture, app ->
            val current = openA(fixture, app).details.getValue(ITEM_A)
            runBlocking {
                withTimeout(WAIT_MS) {
                    while (fixture.playbackRequestedItems().isEmpty()) {
                        fixture.assertHealthy()
                        delay(1)
                    }
                }
            }
            assertEquals(listOf(ITEM_A), fixture.playbackRequestedItems())

            main { app.deleteMedia(MediaItem(ITEM_B, "Synthetic other item", "Movie")) }
            fixture.delete.awaitRequest("其他条目的删除必须实际到达服务器")
            fixture.delete.release()
            await(app, fixture, "删除其他条目不应离开当前详情") {
                "delete/$ITEM_B" !in it.actionBusy && it.route == Route.Detail(ITEM_A)
            }
            // 覆盖 600ms 预取窗口，证明删除收尾没有悄悄重排同一详情的请求。
            android.os.SystemClock.sleep(900)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("无关条目删除不能清掉并重发当前详情的预取", listOf(ITEM_A), fixture.playbackRequestedItems())

            main { app.preparePlayback(current) }
            await(app, fixture, "删除其他条目后播放准备应继续复用已有预取") {
                it.playbackError?.let { error -> throw AssertionError("播放准备失败：$error") }
                !it.preparingPlay && it.sourceSelection?.item?.id == ITEM_A
            }
            assertEquals("点播放也不应额外请求一次 PlaybackInfo", listOf(ITEM_A), fixture.playbackRequestedItems())
            assertEquals("当前详情无需重读", 1, fixture.aDetailReads.get())
            assertEquals(1, fixture.deletes.get())
        }

    @Test
    fun returningFromMetadataEditorCannotAbandonTheUnderlyingPendingDetail() =
        withApp(blockFirstA = true) { fixture, app ->
            val summary = MediaItem(ITEM_A, "Synthetic pending summary", "Movie")
            main { app.open(summary) }
            fixture.firstA.awaitRequest("打开编辑页前详情读取必须实际在途")
            fixture.expectCancelledExtras.set(true)
            main {
                app.openMediaTools(summary)
                app.back()
                assertEquals(Route.Detail(ITEM_A), app.state.value.route)
                assertTrue("无修改退出编辑页不能把下层详情的加载状态清成完成", app.state.value.detailLoading)
            }
            fixture.firstA.release()
            await(app, fixture, "退出编辑页后完整详情必须仍能完成") {
                it.route == Route.Detail(ITEM_A) && !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 1)
            }
        }

    @Test
    fun partiallySuccessfulBatchRefreshRevalidatesHomeAndOnlyItsSuccessfulCachedDetail() =
        withApp { fixture, app ->
            val originalA = openA(fixture, app).details.getValue(ITEM_A)
            main { app.open(MediaItem(ITEM_B, "Synthetic unchanged detail", "Movie")) }
            val loaded = await(app, fixture, "第二个完整详情先完成，缓存 A 留在返回栈") {
                !it.detailLoading && it.details[ITEM_B]?.overview == overview(ITEM_B, 1)
            }
            val originalB = loaded.details.getValue(ITEM_B)
            val homeReads = fixture.homeReads.get()
            val bReads = fixture.bDetailReads.get()

            main { app.runBatch(listOf(originalA, originalB), BatchMediaAction.Refresh(MetadataRefreshOptions())) }
            fixture.refresh.awaitRequest("批量刷新成功项的 POST 必须真正等待")
            assertEquals("提交尚未返回时不能提前重验首页", homeReads, fixture.homeReads.get())
            fixture.refresh.release()
            val changed = await(app, fixture, "部分成功仍须重验首页并如实报告失败") {
                it.actionBusy.isEmpty() && !it.home.loading && fixture.homeReads.get() == homeReads + 1 &&
                    it.notice?.startsWith("1 项完成，1 项失败：") == true
            }
            assertEquals(Route.Detail(ITEM_B), changed.route)
            assertSame("失败项的完整详情不应被无谓重读或清掉", originalB, changed.details[ITEM_B])
            assertEquals(bReads, fixture.bDetailReads.get())
            assertEquals(1, fixture.failedRefreshPosts.get())
            assertEquals("成功但不可见的详情先保留原缓存", 1, fixture.aDetailReads.get())

            main { app.back() }
            await(app, fixture, "返回成功刷新项时必须读取新版完整字段") {
                it.route == Route.Detail(ITEM_A) && !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 2)
            }
            assertEquals(2, fixture.aDetailReads.get())
            assertEquals(1, fixture.refreshPosts.get())
            assertEquals("整批结束只重验一次首页", homeReads + 1, fixture.homeReads.get())
        }

    @Test
    fun lateSeasonChildrenAndEpisodeSiblingsKeepSuccessfulWritesFromOtherDetailPages() =
        withApp(aType = "Season") { fixture, app ->
            main { app.open(MediaItem(ITEM_A, "Synthetic season", "Season")) }
            val season = await(app, fixture, "季详情应先显示已有单集") {
                !it.detailLoading && it.details[ITEM_A]?.type == "Season" &&
                    it.folders[ITEM_A]?.let { folder -> !folder.loading && folder.items.singleOrNull()?.id == ITEM_RESUME } == true
            }
            val child = season.folders.getValue(ITEM_A).items.single()
            main { app.open(child) }
            val episode = await(app, fixture, "单集详情应先显示同季单集") {
                it.route == Route.Detail(ITEM_RESUME) && !it.detailLoading &&
                    it.detailExtras[ITEM_RESUME]?.let { extras -> !extras.loading && extras.siblings.singleOrNull()?.id == ITEM_SIBLING } == true
            }
            val sibling = episode.detailExtras.getValue(ITEM_RESUME).siblings.single()
            main { app.back() }
            fixture.children = Gate(true)
            fixture.siblings = Gate(true)
            main { app.loadFolder(ITEM_A); app.open(child) }
            fixture.children.awaitRequest("季列表必须先捕获收藏前的子集")
            fixture.siblings.awaitRequest("同季列表必须先捕获收藏前的相邻集")
            val openedChild = await(app, fixture, "在途列表不应阻止子集详情") {
                it.route == Route.Detail(ITEM_RESUME) && !it.detailLoading
            }.details.getValue(ITEM_RESUME)
            main { app.toggleFavorite(openedChild) }
            await(app, fixture, "子集收藏先完成") {
                "favorite/$ITEM_RESUME" !in it.actionBusy && it.details[ITEM_RESUME]?.isFavorite == true
            }
            main { app.open(sibling) }
            val openedSibling = await(app, fixture, "另一单集的详情也应独立完成") {
                it.route == Route.Detail(ITEM_SIBLING) && !it.detailLoading
            }.details.getValue(ITEM_SIBLING)
            main { app.toggleFavorite(openedSibling) }
            await(app, fixture, "另一单集收藏先完成") {
                "favorite/$ITEM_SIBLING" !in it.actionBusy && it.details[ITEM_SIBLING]?.isFavorite == true
            }
            fixture.children.release(); fixture.siblings.release()
            val settled = await(app, fixture, "两份旧列表都必须实际发布完成") {
                it.folders[ITEM_A]?.loading == false && it.detailExtras[ITEM_RESUME]?.loading == false &&
                    it.detailExtras[ITEM_SIBLING]?.loading == false
            }
            assertTrue("季列表的旧响应不能回退子集收藏", settled.folders.getValue(ITEM_A).items.single().isFavorite)
            assertTrue("前一个详情的旧同季列表不能回退另一个单集的收藏",
                settled.detailExtras.getValue(ITEM_RESUME).siblings.single().isFavorite)
            assertEquals(Route.Detail(ITEM_SIBLING), settled.route)
            assertTrue(settled.details.getValue(ITEM_SIBLING).isFavorite)
            main { app.back(); app.back() }
            assertEquals(Route.Detail(ITEM_A), app.state.value.route)
            assertTrue(app.state.value.folders.getValue(ITEM_A).items.single().isFavorite)
        }

    @Test
    fun personCreditsKeepFavoriteAndDeletionStateAcrossWorkDetailsAndReturn() =
        withApp(aType = "Person") { fixture, app ->
            val opened = openA(fixture, app)
            val person = requireNotNull(opened.details[ITEM_A])
            await(app, fixture, "人物的电影和剧集应分别读取并缓存") {
                it.detailExtras[ITEM_A]?.let { extras -> !extras.loading &&
                    extras.movies.items.map { media -> media.id } == listOf(ITEM_B) &&
                    extras.series.items.map { media -> media.id } == listOf(ITEM_PERSON_SERIES) } == true
            }
            val browseKey = "person/$ITEM_A/Movie"
            main { app.openPersonCredits(person, "Movie") }
            val works = await(app, fixture, "完整作品列表应带人物与电影筛选") {
                it.route == Route.Browse(browseKey, "${person.name} · 电影") &&
                    it.browsers[browseKey]?.content?.items?.map { media -> media.id } == listOf(ITEM_B)
            }
            main { app.open(works.browsers.getValue(browseKey).content.items.single()) }
            val detail = await(app, fixture, "作品详情应能从完整作品列表进入") {
                it.route == Route.Detail(ITEM_B) && !it.detailLoading && it.details[ITEM_B]?.overview == overview(ITEM_B, 1)
            }.details.getValue(ITEM_B)
            main { app.toggleFavorite(detail) }
            await(app, fixture, "收藏作品必须同步人物预览与完整列表缓存") {
                "favorite/$ITEM_B" !in it.actionBusy && it.details[ITEM_B]?.isFavorite == true &&
                    it.detailExtras[ITEM_A]?.movies?.items?.singleOrNull()?.isFavorite == true &&
                    it.browsers[browseKey]?.content?.items?.singleOrNull()?.isFavorite == true
            }
            main { app.deleteMedia(detail) }
            fixture.delete.awaitRequest("删除作品必须由服务器确认后更新缓存")
            fixture.delete.release()
            val deleted = await(app, fixture, "删除作品应回到作品列表并同步移除人物预览") {
                "delete/$ITEM_B" !in it.actionBusy && it.route is Route.Browse &&
                    it.browsers[browseKey]?.content?.items?.isEmpty() == true &&
                    it.detailExtras[ITEM_A]?.movies?.items?.isEmpty() == true
            }
            assertEquals(0, deleted.detailExtras.getValue(ITEM_A).movies.totalCount)
            assertEquals(listOf(ITEM_PERSON_SERIES), deleted.detailExtras.getValue(ITEM_A).series.items.map { it.id })
            main { app.back() }
            assertEquals(Route.Detail(ITEM_A), app.state.value.route)
            assertTrue(app.state.value.detailExtras.getValue(ITEM_A).movies.items.isEmpty())
            assertEquals("返回人物页应沿用已同步缓存，不靠全页重读掩盖旧卡片", 1, fixture.aDetailReads.get())
        }

    @Test
    fun reopeningWarmDetailKeepsItsCompleteMetadataUntilItsOwnResponseArrives() =
        withApp(blockSecondA = true) { fixture, app ->
            val loaded = openA(fixture, app)
            val complete = requireNotNull(loaded.details[ITEM_A])
            assertTrue(complete.overview.isNotBlank())
            assertEquals(1, complete.people.size)
            assertEquals(2, complete.chapters.size)
            assertEquals(1, complete.mediaSources.size)
            fixture.aVersion.set(2)
            main {
                app.back()
                app.open(MediaItem(ITEM_A, "Synthetic summary only", "Movie"))
                assertTrue(app.state.value.detailLoading)
                assertSame("简版列表 DTO 不能覆盖已经加载的完整详情", complete, app.state.value.details[ITEM_A])
            }
            fixture.secondA.awaitRequest("暖详情应发出自己的新读取")
            val waiting = app.state.value
            assertEquals(Route.Detail(ITEM_A), waiting.route)
            assertEquals(complete, waiting.details[ITEM_A])
            assertTrue(waiting.detailLoading)
            fixture.secondA.release()
            val refreshed = await(app, fixture, "新响应返回后才替换暖详情") {
                !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 2)
            }
            assertEquals(complete.people, refreshed.details[ITEM_A]?.people)
            assertEquals(complete.chapters, refreshed.details[ITEM_A]?.chapters)
            assertEquals(2, fixture.aDetailReads.get())
        }

    @Test
    fun deletingAWhileBLoadsDoesNotCancelBAndLateAExtrasCannotReturn() =
        withApp(blockB = true, blockASimilar = true) { fixture, app ->
            val loaded = openA(fixture, app)
            fixture.aSimilar.awaitRequest("A 的关联请求应真实在途")
            val extrasA = main { extraJob(app, ITEM_A) }
            main { app.deleteMedia(requireNotNull(loaded.details[ITEM_A])) }
            fixture.delete.awaitRequest("删除必须到达真实 DELETE 并等待服务器确认")
            val requestB = openBlockedB(fixture, app)

            fixture.expectCancelledASimilar.set(true)
            fixture.delete.release()
            val deleted = await(app, fixture, "删除 A 完成时必须保留正在读取的 B") {
                "delete/$ITEM_A" !in it.actionBusy && ITEM_A !in it.details && it.route == Route.Detail(ITEM_B)
            }
            assertTrue("A 的删除响应不能把 B 的加载态清掉", deleted.detailLoading)
            assertFalse("A 的删除响应不能取消 B 的真实 HTTP 请求", requestB.isCancelled)
            assertSame(requestB, main { detailJob(app) })
            assertFalse(deleted.detailExtras.containsKey(ITEM_A))
            assertTrue("删除后必须取消 A 的关联请求", extrasA.isCancelled)
            awaitJob(extrasA)
            fixture.aSimilar.release()
            fixture.aSimilar.awaitFinished()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse("迟到的 A 关联响应不能复活已删除缓存", app.state.value.detailExtras.containsKey(ITEM_A))
            assertTrue("迟到的 A 响应也不能结束 B 的加载态", app.state.value.detailLoading)

            fixture.bDetail.release()
            val complete = await(app, fixture, "B 的原请求必须仍能正常完成") {
                it.route == Route.Detail(ITEM_B) && !it.detailLoading && it.details[ITEM_B]?.overview == overview(ITEM_B, 1)
            }
            assertFalse(complete.details.containsKey(ITEM_A))
            assertEquals(1, fixture.bDetailReads.get())
            assertEquals(1, fixture.deletes.get())
        }

    @Test
    fun refreshingAWhileBLoadsDefersAReadUntilReturningAndCancelsOldAExtras() =
        withApp(blockB = true, blockASimilar = true) { fixture, app ->
            val loaded = openA(fixture, app)
            val originalA = requireNotNull(loaded.details[ITEM_A])
            fixture.aSimilar.awaitRequest("A 的旧关联请求应真实在途")
            val extrasA = main { extraJob(app, ITEM_A) }
            main { app.refreshMetadata(originalA) }
            fixture.refresh.awaitRequest("元数据刷新必须到达真实 POST")
            val requestB = openBlockedB(fixture, app)

            fixture.expectCancelledASimilar.set(true)
            fixture.refresh.release()
            val changed = await(app, fixture, "A 的刷新响应不能抢走 B 的详情读取") {
                "refresh/$ITEM_A" !in it.actionBusy && it.notice == "已提交元数据刷新"
            }
            assertEquals(Route.Detail(ITEM_B), changed.route)
            assertTrue(changed.detailLoading)
            assertFalse(requestB.isCancelled)
            assertSame(requestB, main { detailJob(app) })
            assertEquals("离开 A 后的刷新完成不能主动重读 A 并取消 B", 1, fixture.aDetailReads.get())
            assertSame("A 的完整缓存保留到实际重读成功", originalA, changed.details[ITEM_A])
            assertTrue(extrasA.isCancelled)
            awaitJob(extrasA)
            fixture.aSimilar.release()
            fixture.aSimilar.awaitFinished()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue("旧 A 关联请求取消后不能发布迟到的推荐", app.state.value.detailExtras[ITEM_A]?.similar.orEmpty().isEmpty())

            fixture.bDetail.release()
            await(app, fixture, "B 应保持原请求并完成") {
                it.route == Route.Detail(ITEM_B) && !it.detailLoading && it.details[ITEM_B]?.overview == overview(ITEM_B, 1)
            }
            main { app.back() }
            val returned = await(app, fixture, "返回已失效的 A 时必须重新读取真实详情") {
                it.route == Route.Detail(ITEM_A) && !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 2)
            }
            assertEquals(2, fixture.aDetailReads.get())
            assertEquals(1, fixture.bDetailReads.get())
            assertEquals(1, fixture.refreshPosts.get())
            assertNull(returned.detailError)
        }

    @Test
    fun fastResumeAndNextUpPublishWhileRecommendationsAreStillBlocked() =
        withApp(blockASimilar = true, aType = "Series") { fixture, app ->
            openA(fixture, app)
            fixture.aSimilar.awaitRequest("推荐必须真实在途，不能用延时模拟")
            val ready = await(app, fixture, "续播和下一集不能等待慢推荐") {
                it.detailExtras[ITEM_A]?.resume?.map { item -> item.id } == listOf(ITEM_RESUME) &&
                    it.detailExtras[ITEM_A]?.nextUp?.map { item -> item.id } == listOf(ITEM_NEXT)
            }
            assertTrue(ready.detailExtras.getValue(ITEM_A).loading)
            assertTrue(ready.detailExtras.getValue(ITEM_A).similar.isEmpty())
            fixture.aSimilar.release()
            await(app, fixture, "推荐补齐后结束整轮忙态") {
                it.detailExtras[ITEM_A]?.loading == false && it.detailExtras[ITEM_A]?.similar?.map { item -> item.id } == listOf(ITEM_SIMILAR)
            }
        }

    @Test
    fun nextUpDeliveredBeforeResumeCannotBecomePlaybackCandidate() =
        withApp(aType = "Series", blockSeriesResume = true) { fixture, app ->
            val opened = openA(fixture, app)
            fixture.seriesResume.awaitRequest("详情续播查询必须真实在途")
            awaitNextUpDelivered(app, fixture)
            val waiting = await(app, fixture, "推荐仍应独立发布，不等待续播") {
                it.detailExtras[ITEM_A]?.similar?.map { item -> item.id } == listOf(ITEM_SIMILAR)
            }.detailExtras.getValue(ITEM_A)
            assertTrue("下一集不能抢在续播结果确认前成为候选", waiting.nextUp.isEmpty())
            assertTrue(waiting.resume.isEmpty())
            assertTrue(waiting.loading)

            main { app.playSeries(requireNotNull(opened.details[ITEM_A])) }
            fixture.playResume.awaitRequest("点击播放仍必须优先确认续播，不能直接选择先到的下一集")
            assertTrue(app.state.value.preparingPlay)
            assertTrue("续播未返回前不得请求下一集的播放信息", fixture.playbackRequestedItems().isEmpty())
            fixture.seriesResume.release()
            fixture.playResume.release()
            val ready = await(app, fixture, "最终播放目标必须是续播项") {
                it.playbackError?.let { error -> throw AssertionError("播放准备失败：$error") }
                !it.preparingPlay && it.sourceSelection?.item?.id == ITEM_RESUME &&
                    it.detailExtras[ITEM_A]?.loading == false
            }
            assertEquals(listOf(ITEM_RESUME), fixture.playbackRequestedItems())
            assertEquals(listOf(ITEM_RESUME), ready.detailExtras.getValue(ITEM_A).resume.map { it.id })
            assertEquals(listOf(ITEM_NEXT), ready.detailExtras.getValue(ITEM_A).nextUp.map { it.id })
        }

    @Test
    fun fastResumeDoesNotWaitForNextUpOrRecommendationsBeforeBecomingPlayable() =
        withApp(aType = "Series", blockASimilar = true, blockSeriesNextUp = true) { fixture, app ->
            val opened = openA(fixture, app)
            fixture.aSimilar.awaitRequest("推荐必须仍在途")
            fixture.seriesNextUp.awaitRequest("下一集必须仍在途")
            val ready = await(app, fixture, "快速续播必须独立发布") {
                it.detailExtras[ITEM_A]?.resume?.map { item -> item.id } == listOf(ITEM_RESUME)
            }.detailExtras.getValue(ITEM_A)
            assertTrue(ready.nextUp.isEmpty())
            assertTrue(ready.similar.isEmpty())
            assertTrue(ready.loading)
            main { app.playSeries(requireNotNull(opened.details[ITEM_A])) }
            await(app, fixture, "续播播放准备不能等待慢下一集或推荐") {
                it.playbackError?.let { error -> throw AssertionError("播放准备失败：$error") }
                !it.preparingPlay && it.sourceSelection?.item?.id == ITEM_RESUME
            }
            assertEquals(listOf(ITEM_RESUME), fixture.playbackRequestedItems())
            fixture.seriesNextUp.release()
            fixture.aSimilar.release()
            await(app, fixture, "所有扩展结束后应释放 busy") { it.detailExtras[ITEM_A]?.loading == false }
        }

    @Test
    fun fastSiblingsPublishBeforeSlowEpisodeRecommendations() =
        withApp(blockASimilar = true, aType = "Episode") { fixture, app ->
            openA(fixture, app)
            fixture.aSimilar.awaitRequest("单集推荐应仍在途")
            val ready = await(app, fixture, "同季剧集不能被推荐阻塞") {
                it.detailExtras[ITEM_A]?.siblings?.map { item -> item.id } == listOf(ITEM_SIBLING)
            }
            assertTrue(ready.detailExtras.getValue(ITEM_A).loading)
            assertTrue(ready.detailExtras.getValue(ITEM_A).similar.isEmpty())
            fixture.aSimilar.release()
            await(app, fixture, "单集扩展最终应正常结束") { it.detailExtras[ITEM_A]?.loading == false }
        }

    @Test
    fun cancelledExtrasDoNotKeepFailedDetailBusyAndRetryCanComplete() =
        withApp(blockSecondA = true, blockASimilar = true) { fixture, app ->
            val original = openA(fixture, app)
            fixture.aSimilar.awaitRequest("旧推荐必须真实在途")
            val old = main { extraJob(app, ITEM_A) }
            fixture.expectCancelledASimilar.set(true)
            fixture.aDetailStatus.set(503)
            main { app.mediaChanged(requireNotNull(original.session), ITEM_A) }
            fixture.secondA.awaitRequest("媒体变化后应重新读取详情")
            awaitJob(old)
            fixture.secondA.release()
            val failed = await(app, fixture, "主详情失败后，已取消推荐不能永久保留 busy", allowDetailError = true) {
                !it.detailLoading && it.detailError != null && it.detailExtras[ITEM_A]?.loading == false
            }
            assertSame("失败应保留原完整详情", original.details[ITEM_A], failed.details[ITEM_A])
            fixture.aSimilar.release()
            fixture.aSimilar.awaitFinished()
            fixture.aDetailStatus.set(200)
            main { app.refreshDetail(ITEM_A) }
            val recovered = await(app, fixture, "结束 busy 后的真实重试必须能完成") {
                !it.detailLoading && it.detailError == null && it.detailExtras[ITEM_A]?.loading == false &&
                    it.detailExtras[ITEM_A]?.similar?.map { item -> item.id } == listOf(ITEM_SIMILAR)
            }
            assertEquals(listOf(ITEM_SIMILAR), recovered.detailExtras.getValue(ITEM_A).similar.map { it.id })
            assertEquals(3, fixture.aDetailReads.get())
        }

    @Test
    fun replacedExtrasCannotClearNewRequestsBusyStateOrPublishLateRecommendations() =
        withApp(blockASimilar = true, blockSecondASimilar = true) { fixture, app ->
            openA(fixture, app)
            fixture.aSimilar.awaitRequest("首轮推荐应已在途")
            val old = main { extraJob(app, ITEM_A) }
            fixture.expectCancelledASimilar.set(true)
            main { app.refreshDetail(ITEM_A) }
            fixture.secondASimilar.awaitRequest("新一轮推荐应已独立发出")
            val current = main { extraJob(app, ITEM_A) }
            assertNotSame(old, current)
            awaitJob(old)
            assertTrue("旧 finally 不能清除新一轮 busy", app.state.value.detailExtras.getValue(ITEM_A).loading)
            fixture.aSimilar.release()
            fixture.aSimilar.awaitFinished()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(app.state.value.detailExtras.getValue(ITEM_A).loading)
            assertTrue("旧轮迟到推荐不能写入当前轮", app.state.value.detailExtras.getValue(ITEM_A).similar.isEmpty())
            assertFalse(current.isCancelled)
            fixture.secondASimilar.release()
            await(app, fixture, "只有当前轮完成才能结束 busy") {
                it.detailExtras[ITEM_A]?.loading == false && it.detailExtras[ITEM_A]?.similar?.isNotEmpty() == true
            }
        }

    @Test
    fun unauthorizedFastExtraRequestsReauthenticationWithoutWaitingForSlowRecommendations() =
        withApp(blockASimilar = true, aType = "Series") { fixture, app ->
            val profile = app.state.value.profiles.single()
            fixture.seriesResumeStatus.set(401)
            fixture.expectCancelledASimilar.set(true)
            fixture.expectCancelledExtras.set(true)
            main { app.open(MediaItem(ITEM_A, "Synthetic series", "Series")) }
            fixture.aSimilar.awaitRequest("慢推荐必须真实在途")
            val expired = await(app, fixture, "快速接口的 401 不得等待慢推荐才恢复账号", allowLoginError = true) {
                it.reauthenticationProfile?.id == profile.id
            }
            assertEquals(profile.id, expired.currentProfileId)
            assertEquals(profile.session, expired.session)
            assertEquals(listOf(profile.id), expired.profiles.map { it.id })
            assertFalse(expired.detailLoading)
            assertTrue(expired.detailExtras.values.none { it.loading })
            fixture.aSimilar.release()
            fixture.aSimilar.awaitFinished()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(app.state.value.detailExtras[ITEM_A]?.similar.orEmpty().isEmpty())
            assertEquals(profile.id, app.state.value.reauthenticationProfile?.id)
        }

    @Test
    fun unauthorizedNextUpRequestsReauthenticationWithoutWaitingForResume() =
        withApp(aType = "Series", blockASimilar = true, blockSeriesResume = true, blockSeriesNextUp = true) { fixture, app ->
            val profile = app.state.value.profiles.single()
            fixture.seriesNextUpStatus.set(401)
            fixture.expectCancelledASimilar.set(true)
            fixture.expectCancelledExtras.set(true)
            main { app.open(MediaItem(ITEM_A, "Synthetic series", "Series")) }
            fixture.aSimilar.awaitRequest("推荐必须仍在途")
            fixture.seriesResume.awaitRequest("续播必须仍在途")
            fixture.seriesNextUp.awaitRequest("下一集认证失败必须真实请求")
            fixture.seriesNextUp.release()
            val expired = await(app, fixture, "下一集401不能等待慢续播结束", allowLoginError = true) {
                it.reauthenticationProfile?.id == profile.id
            }
            assertEquals(profile.id, expired.currentProfileId)
            assertTrue(expired.detailExtras.values.none { it.loading })
            fixture.seriesResume.release()
            fixture.aSimilar.release()
            fixture.seriesResume.awaitFinished()
            fixture.aSimilar.awaitFinished()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(app.state.value.detailExtras[ITEM_A]?.nextUp.orEmpty().isEmpty())
            assertEquals(profile.id, app.state.value.reauthenticationProfile?.id)
        }

    private fun openA(fixture: Fixture, app: AppViewModel): AppState {
        main { app.open(MediaItem(ITEM_A, "Synthetic summary A", "Movie")) }
        return await(app, fixture, "A 的完整详情必须加载完成") {
            it.route == Route.Detail(ITEM_A) && !it.detailLoading && it.details[ITEM_A]?.overview == overview(ITEM_A, 1)
        }
    }

    private fun openBlockedB(fixture: Fixture, app: AppViewModel): Job {
        val job = main {
            app.open(MediaItem(ITEM_B, "Synthetic summary B", "Movie"))
            assertTrue(app.state.value.detailLoading)
            detailJob(app)
        }
        fixture.bDetail.awaitRequest("B 的详情必须已经进入真实在途读取")
        return job
    }

    private fun awaitNextUpDelivered(app: AppViewModel, fixture: Fixture) {
        fixture.seriesNextUp.awaitFinished()
        val field = JellyfinApi::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(app.api) as OkHttpClient
        runBlocking {
            withTimeout(WAIT_MS) {
                while (client.dispatcher.runningCalls().any { call ->
                        call.request().url.encodedPath == "/Shows/NextUp" && call.request().url.queryParameter("seriesId") == ITEM_A
                    }) delay(1)
            }
        }
        // OkHttp 的回调已返回，排空它投递给 Main 的 continuation 后再检查候选。
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        fixture.assertHealthy()
    }

    private fun withApp(
        blockFirstA: Boolean = false,
        blockSecondA: Boolean = false,
        blockB: Boolean = false,
        blockASimilar: Boolean = false,
        blockSecondASimilar: Boolean = false,
        aType: String = "Movie",
        blockSeriesResume: Boolean = false,
        blockSeriesNextUp: Boolean = false,
        block: (Fixture, AppViewModel) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == VERIFICATION_PACKAGE) { "详情隔离回归只允许独立 verification 包" }
        val preferences = AppPreferencesStore(context)
        val previousPreferences = preferences.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        clearSyntheticSessions(context)
        preferences.save(AppPreferences())
        try {
            Fixture(blockFirstA, blockSecondA, blockB, blockASimilar, blockSecondASimilar, aType, blockSeriesResume, blockSeriesNextUp).use { fixture ->
                SessionStore(context).save(Session(ServerConfig(fixture.baseUrl, true), TOKEN, USER_ID,
                    "Synthetic detail isolation", "synthetic-detail-isolation-device", SERVER_ID,
                    isAdministrator = true, canDelete = true))
                val app = main { ViewModelProvider(owner,
                    ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[AppViewModel::class.java] }
                try {
                    await(app, fixture, "合成会话与首页必须通过真实读取恢复") {
                        !it.restoringSession && it.session?.serverId == SERVER_ID && it.home.loaded && !it.home.loading
                    }
                    block(fixture, app)
                    fixture.assertHealthy()
                } finally {
                    main { owner.viewModelStore.clear() }
                }
            }
        } finally {
            main { owner.viewModelStore.clear() }
            clearSyntheticSessions(context)
            preferences.save(previousPreferences)
        }
    }

    private fun clearSyntheticSessions(context: Context) {
        check(context.packageName == VERIFICATION_PACKAGE)
        check(context.getSharedPreferences("pathfin_session", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun await(app: AppViewModel, fixture: Fixture, message: String,
        allowDetailError: Boolean = false, allowLoginError: Boolean = false, predicate: (AppState) -> Boolean): AppState =
        try {
            runBlocking { withTimeout(WAIT_MS) { app.state.first {
                fixture.assertHealthy()
                if (!allowLoginError) it.loginError?.let { error -> throw AssertionError("$message；登录错误：$error") }
                if (!allowDetailError) it.detailError?.let { error -> throw AssertionError("$message；详情错误：$error") }
                predicate(it)
            } } }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$message；route=${app.state.value.route.key}, detailLoading=${app.state.value.detailLoading}, " +
                "actions=${app.state.value.actionBusy}", failure)
        }

    private fun detailJob(app: AppViewModel): Job = AppViewModel::class.java.getDeclaredField("detailJob").let {
        it.isAccessible = true; it.get(app) as Job
    }

    @Suppress("UNCHECKED_CAST")
    private fun extraJob(app: AppViewModel, id: String): Job = AppViewModel::class.java.getDeclaredField("extraJobs").let {
        it.isAccessible = true
        requireNotNull((it.get(app) as Map<String, Job>)[id])
    }

    private fun awaitJob(job: Job) = runBlocking { withTimeout(WAIT_MS) { job.join() } }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class Gate(blocked: Boolean) {
        private val requested = CountDownLatch(1)
        private val released = CountDownLatch(if (blocked) 1 else 0)
        private val finished = CountDownLatch(1)
        fun enter() {
            requested.countDown()
            check(released.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "详情 fixture 响应门闩未释放" }
        }
        fun awaitRequest(message: String) = assertTrue(message, requested.await(WAIT_MS, TimeUnit.MILLISECONDS))
        fun release() = released.countDown()
        fun finish() = finished.countDown()
        fun awaitFinished() = assertTrue("迟到的合成响应必须实际结束，不能只检查释放门闩", finished.await(WAIT_MS, TimeUnit.MILLISECONDS))
    }

    private class Fixture(blockFirstA: Boolean, blockSecondA: Boolean, blockB: Boolean, blockASimilar: Boolean,
        blockSecondASimilar: Boolean, private val aType: String, blockSeriesResume: Boolean, blockSeriesNextUp: Boolean) : Closeable {
        val firstA = Gate(blockFirstA)
        val secondA = Gate(blockSecondA)
        val bDetail = Gate(blockB)
        val aSimilar = Gate(blockASimilar)
        val secondASimilar = Gate(blockSecondASimilar)
        val seriesResume = Gate(blockSeriesResume)
        val playResume = Gate(blockSeriesResume)
        val seriesNextUp = Gate(blockSeriesNextUp)
        @Volatile var children = Gate(false)
        @Volatile var siblings = Gate(false)
        val refresh = Gate(true)
        val delete = Gate(true)
        val aVersion = AtomicInteger(1)
        val aDetailReads = AtomicInteger()
        val aDetailStatus = AtomicInteger(200)
        val seriesResumeStatus = AtomicInteger(200)
        val seriesNextUpStatus = AtomicInteger(200)
        private val favorites = ConcurrentHashMap.newKeySet<String>()
        private val bDeleted = AtomicBoolean()
        private val aSimilarReads = AtomicInteger()
        val bDetailReads = AtomicInteger()
        val refreshPosts = AtomicInteger()
        val failedRefreshPosts = AtomicInteger()
        val homeReads = AtomicInteger()
        val deletes = AtomicInteger()
        val expectCancelledASimilar = AtomicBoolean()
        val expectCancelledExtras = AtomicBoolean()
        private val closed = AtomicBoolean()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val playbackItems = ConcurrentLinkedQueue<String>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val executor = Executors.newCachedThreadPool { Thread(it, "pathfin-detail-isolation-response").apply { isDaemon = true } }
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val accept = Thread({
            while (!closed.get()) try {
                val socket = server.accept()
                if (closed.get()) { runCatching { socket.close() }; continue }
                sockets.add(socket)
                try { executor.execute { serve(socket) } }
                catch (error: RejectedExecutionException) {
                    sockets.remove(socket)
                    runCatching { socket.close() }
                    if (!closed.get()) failures.add(error)
                }
            } catch (error: SocketException) { if (!closed.get()) failures.add(error) }
        }, "pathfin-detail-isolation-accept").apply { isDaemon = true; start() }

        fun assertHealthy() { failures.peek()?.let { throw AssertionError("详情隔离合成 HTTP 契约失败", it) } }
        fun playbackRequestedItems() = playbackItems.toList()

        private fun serve(socket: Socket) {
            var path: String? = null
            var responseGate: Gate? = null
            try {
                socket.use {
                    socket.soTimeout = WAIT_MS.toInt()
                    val input = DataInputStream(socket.getInputStream())
                    val first = line(input) ?: return
                    val parts = first.split(' ')
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
                    check(length in 0..32_768)
                    val body = ByteArray(length).also(input::readFully)
                    val url = (baseUrl + parts[1]).toHttpUrl()
                    path = url.encodedPath
                    check(headers["authorization"]?.contains(TOKEN) == true)
                    check(!url.toString().contains(TOKEN) && "x-emby-token" !in headers)
                    responseGate = when {
                        path == "/Items" && url.queryParameter("ids") == ITEM_A ->
                            if (aDetailReads.incrementAndGet() == 1) firstA else secondA
                        path == "/Items" && url.queryParameter("ids") == ITEM_B -> bDetail.also { bDetailReads.incrementAndGet() }
                        path == "/Items/$ITEM_A/Similar" -> if (aSimilarReads.incrementAndGet() == 1) aSimilar else secondASimilar
                        path == "/Shows/NextUp" && url.queryParameter("seriesId") == ITEM_A -> seriesNextUp
                        path == "/Items" && url.queryParameter("parentId") == ITEM_A && url.queryParameter("filters") == "IsResumable" ->
                            if (url.queryParameter("limit") == "6") seriesResume else {
                                check(url.queryParameter("limit") == "1")
                                playResume
                            }
                        path == "/Items" && url.queryParameter("parentId") == ITEM_A &&
                            url.queryParameter("recursive") == "false" && aType == "Season" -> children
                        path == "/Items" && url.queryParameter("parentId") == SEASON_ID -> siblings
                        else -> null
                    }
                    val response = respond(parts[0], url, body, responseGate)
                    val bytes = response.second?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 ${response.first} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                val expectedCancellation = error is IOException &&
                    ((path == "/Items/$ITEM_A/Similar" && expectCancelledASimilar.get()) ||
                        (expectCancelledExtras.get() && path in setOf("/Items", "/Shows/NextUp")))
                if (!closed.get() && !expectedCancellation) failures.add(error)
            } finally {
                responseGate?.finish()
                sockets.remove(socket)
            }
        }

        private fun respond(method: String, url: HttpUrl, body: ByteArray, responseGate: Gate?): Pair<Int, String?> = when ("$method ${url.encodedPath}") {
            "GET /Users/Me" -> 200 to JSONObject().put("Id", USER_ID).put("Name", "Synthetic detail isolation")
                .put("Policy", JSONObject().put("IsAdministrator", true).put("EnableContentDeletion", true)).toString()
            "GET /UserViews" -> { homeReads.incrementAndGet(); 200 to page() }
            "GET /UserItems/Resume" -> 200 to page()
            "GET /Shows/NextUp" -> {
                check(url.queryParameter("enableResumable") == "false")
                if (url.queryParameter("seriesId") == ITEM_A) {
                    requireNotNull(responseGate).enter()
                    seriesNextUpStatus.get() to page(item(ITEM_NEXT))
                } else 200 to page()
            }
            "GET /Items/Latest" -> 200 to "[]"
            "GET /Items/Filters" -> 200 to "{}"
            "GET /Items" -> when {
                url.queryParameter("personIds") == ITEM_A -> {
                    check(url.queryParameter("sortBy") == "PremiereDate" && url.queryParameter("sortOrder") == "Descending")
                    200 to when (url.queryParameter("includeItemTypes")) {
                        "Movie" -> if (bDeleted.get()) page() else page(item(ITEM_B))
                        "Series" -> page(item(ITEM_PERSON_SERIES))
                        else -> error("人物作品查询必须按电影或剧集分区")
                    }
                }
                url.queryParameter("ids") == ITEM_A -> {
                    requireNotNull(responseGate).enter()
                    aDetailStatus.get() to page(item(ITEM_A))
                }
                url.queryParameter("ids") == ITEM_B -> {
                    requireNotNull(responseGate).enter()
                    200 to page(item(ITEM_B))
                }
                url.queryParameter("ids") in setOf(ITEM_RESUME, ITEM_SIBLING) ->
                    200 to page(item(requireNotNull(url.queryParameter("ids"))))
                url.queryParameter("parentId") == ITEM_A && url.queryParameter("filters") == "IsResumable" -> {
                    requireNotNull(responseGate).enter()
                    seriesResumeStatus.get() to page(item(ITEM_RESUME))
                }
                url.queryParameter("parentId") == ITEM_A && url.queryParameter("recursive") == "false" -> {
                    val response = if (aType == "Season") page(item(ITEM_RESUME)) else page()
                    responseGate?.enter()
                    200 to response
                }
                url.queryParameter("parentId") == SEASON_ID -> {
                    val response = page(item(ITEM_SIBLING))
                    requireNotNull(responseGate).enter()
                    200 to response
                }
                else -> error("详情读取必须限定合成条目")
            }
            "GET /Items/$ITEM_A/Similar" -> {
                requireNotNull(responseGate).enter()
                200 to page(item(ITEM_SIMILAR))
            }
            "GET /Items/$ITEM_B/Similar", "GET /Items/$ITEM_RESUME/Similar", "GET /Items/$ITEM_SIBLING/Similar" -> 200 to page()
            "GET /Items/$ITEM_A" -> 200 to item(ITEM_A).toString()
            "GET /Items/$ITEM_B" -> 200 to item(ITEM_B).toString()
            "POST /UserFavoriteItems/$ITEM_B", "POST /UserFavoriteItems/$ITEM_RESUME", "POST /UserFavoriteItems/$ITEM_SIBLING" -> {
                favorites.add(url.pathSegments.last())
                200 to JSONObject().put("IsFavorite", true).put("Played", false).toString()
            }
            "POST /Items/$ITEM_A/PlaybackInfo", "POST /Items/$ITEM_RESUME/PlaybackInfo", "POST /Items/$ITEM_NEXT/PlaybackInfo",
            "POST /Items/$ITEM_B/PlaybackInfo", "POST /Items/$ITEM_SIBLING/PlaybackInfo" -> {
                val itemId = url.pathSegments[1]
                check(JSONObject(body.toString(Charsets.UTF_8)).getString("UserId") == USER_ID)
                playbackItems.add(itemId)
                200 to JSONObject().put("PlaySessionId", "detail-priority-session").put("MediaSources", JSONArray()
                    .put(JSONObject().put("Id", "priority-source-one").put("Name", "one"))
                    .put(JSONObject().put("Id", "priority-source-two").put("Name", "two"))).toString()
            }
            "POST /Items/$ITEM_A/Refresh" -> {
                check(body.isEmpty())
                check(url.queryParameter("replaceAllMetadata") == "false" && url.queryParameter("replaceAllImages") == "false")
                refreshPosts.incrementAndGet(); refresh.enter(); aVersion.set(2)
                204 to null
            }
            "POST /Items/$ITEM_B/Refresh" -> {
                check(body.isEmpty())
                failedRefreshPosts.incrementAndGet()
                503 to "{}"
            }
            "DELETE /Items/$ITEM_A" -> {
                check(body.isEmpty())
                deletes.incrementAndGet(); delete.enter()
                204 to null
            }
            "DELETE /Items/$ITEM_B" -> {
                check(body.isEmpty())
                deletes.incrementAndGet(); delete.enter(); bDeleted.set(true)
                204 to null
            }
            else -> error("未定义的详情隔离接口：$method ${url.encodedPath}")
        }

        private fun item(id: String): JSONObject {
            val version = if (id == ITEM_A) aVersion.get() else 1
            val type = if (id == ITEM_A) aType else if (id == ITEM_PERSON_SERIES) "Series"
                else if (id in setOf(ITEM_RESUME, ITEM_NEXT, ITEM_SIBLING)) "Episode" else "Movie"
            return JSONObject().put("Id", id).put("Name", "Synthetic $id v$version").put("Type", type)
                .put("SeriesId", ITEM_A).put("SeasonId", SEASON_ID)
                .put("Overview", overview(id, version)).put("CanDelete", true).put("CanDownload", false)
                .put("UserData", JSONObject().put("IsFavorite", id in favorites).put("Played", false))
                .put("People", JSONArray().put(JSONObject().put("Id", "synthetic-person").put("Name", "Synthetic actor").put("Type", "Actor")))
                .put("Chapters", JSONArray().put(JSONObject().put("Name", "One").put("StartPositionTicks", 0L))
                    .put(JSONObject().put("Name", "Two").put("StartPositionTicks", 600_000_000L)))
                .put("MediaSources", JSONArray().put(JSONObject().put("Id", "synthetic-source").put("Path", "/synthetic/movie.mkv").put("Container", "mkv")))
        }
        private fun page(vararg items: JSONObject) = JSONObject().put("Items", JSONArray(items.toList())).put("TotalRecordCount", items.size).toString()
        private fun line(input: DataInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 32_768) {
                val next = input.read()
                if (next < 0) { if (bytes.size() == 0) return null; throw EOFException() }
                if (next == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(next)
            }
            error("合成 HTTP 请求头过长")
        }
        override fun close() {
            closed.set(true)
            listOf(firstA, secondA, bDetail, aSimilar, secondASimilar, seriesResume, playResume, seriesNextUp, children, siblings, refresh, delete).forEach { it.release() }
            server.close()
            accept.join(1_000)
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val VERIFICATION_PACKAGE = "dev.pathfin.android.verification"
        const val ITEM_A = "detail-isolation-a"
        const val ITEM_B = "detail-isolation-b"
        const val ITEM_PERSON_SERIES = "detail-isolation-person-series"
        const val ITEM_SIMILAR = "detail-isolation-similar"
        const val ITEM_RESUME = "detail-isolation-resume"
        const val ITEM_NEXT = "detail-isolation-next"
        const val ITEM_SIBLING = "detail-isolation-sibling"
        const val SEASON_ID = "detail-isolation-season"
        const val USER_ID = "detail-isolation-user"
        const val SERVER_ID = "detail-isolation-server"
        const val TOKEN = "synthetic-detail-isolation-token"
        const val WAIT_MS = 20_000L
        fun overview(id: String, version: Int) = "Synthetic complete overview $id v$version"
    }
}

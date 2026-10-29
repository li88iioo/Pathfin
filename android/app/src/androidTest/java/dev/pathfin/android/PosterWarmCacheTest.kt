package dev.pathfin.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import dev.pathfin.android.playback.PlaybackClientCleanup
import dev.pathfin.android.ui.PosterRequests
import dev.pathfin.android.ui.PosterStyle
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 真实 Coil 解码/内存命中；OkHttp 合成传输只返回自制 PNG，不访问服务器和账号存储。 */
@RunWith(AndroidJUnit4::class)
class PosterWarmCacheTest {
    private lateinit var context: Context
    private val accounts = mutableListOf<Session>()
    private val clients = mutableListOf<OkHttpClient>()
    private val runId = UUID.randomUUID().toString()
    private val calls = AtomicInteger()

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
    }

    @After fun clean() {
        if (!::context.isInitialized || context.packageName != "dev.pathfin.android.verification") return
        accounts.forEach { assertTrue(PosterImageCache.clearAccount(context, it)) }
        clients.forEach { PlaybackClientCleanup.release(it).get(5, TimeUnit.SECONDS) }
    }

    @Test fun switchingAtoBtoAReusesTheDecodedImageAndTheSameLoader() {
        val client = transport()
        val a = account("a")
        val b = account("b")
        val first = PosterImageCache.createLoader(context, a, client)
        val decoded = load(first, a)
        assertEquals(DataSource.NETWORK, decoded.dataSource)
        PosterImageCache.release(first)
        val second = PosterImageCache.createLoader(context, b, client)
        assertNotSame(first, second)
        assertEquals(DataSource.NETWORK, load(second, b).dataSource)
        PosterImageCache.release(second)

        val returned = requireNotNull(PosterImageCache.acquireWarmLoader(context, a))
        try {
            assertSame("暖切必须直接复用 loader，不用磁盘 loader 替换它", first, returned)
            val hit = load(returned, a)
            assertEquals(DataSource.MEMORY_CACHE, hit.dataSource)
            assertSame("真正复用已解码的图片", decoded.image, hit.image)
            assertEquals("两个账号各一次合成请求，切回不重新请求或解码", 2, calls.get())
            assertEquals(PosterImageCache.MEMORY_BYTES_PER_LOADER, returned.memoryCache!!.maxSize)
            assertNull(PosterImageCache.acquireWarmLoader(context, account("unknown")))
            assertNull(PosterImageCache.acquireWarmLoader(context, a, 64L * 1024 * 1024))
        } finally { PosterImageCache.release(returned) }
    }

    @Test fun idleLruIsBoundedAndNeverEvictsAnActivelyBorrowedLoader() {
        val client = transport()
        val activeAccount = account("active")
        val active = PosterImageCache.createLoader(context, activeAccount, client)
        load(active, activeAccount)
        val additionalReference = requireNotNull(PosterImageCache.acquireWarmLoader(context, activeAccount))
        assertSame(active, additionalReference)
        PosterImageCache.release(active)

        val idle = (0..PosterImageCache.MAX_IDLE_LOADERS).map { index ->
            val session = account("idle-$index")
            val loader = PosterImageCache.createLoader(context, session, client)
            load(loader, session)
            PosterImageCache.release(loader)
            session to loader
        }
        assertNull("只保留最近两个空闲 loader", PosterImageCache.acquireWarmLoader(context, idle.first().first))
        await("被 LRU 淘汰的已解码图片实际释放") { idle.first().second.memoryCache!!.size == 0L }
        assertEquals(DataSource.MEMORY_CACHE, load(additionalReference, activeAccount).dataSource)
        idle.drop(1).forEach { (session, loader) ->
            val retained = requireNotNull(PosterImageCache.acquireWarmLoader(context, session))
            assertSame(loader, retained)
            PosterImageCache.release(retained)
        }
        PosterImageCache.release(additionalReference)
    }

    @Test fun disposingTheFallbackClientCannotCloseTheWarmLoadersTransport() {
        val client = transport()
        val session = account("transport")
        val loader = PosterImageCache.createLoader(context, session, client)
        assertEquals(DataSource.NETWORK, load(loader, session, "first").dataSource)
        PosterImageCache.release(loader)
        PlaybackClientCleanup.release(client).get(5, TimeUnit.SECONDS)
        assertTrue(client.dispatcher.executorService.isShutdown)

        val returned = requireNotNull(PosterImageCache.acquireWarmLoader(context, session))
        try {
            // 新 URL 排除内存/磁盘命中，必须实际经过独占 dispatcher 和合成 OkHttp 传输。
            assertEquals(DataSource.NETWORK, load(returned, session, "after-fallback-close").dataSource)
            assertEquals(2, calls.get())
        } finally { PosterImageCache.release(returned) }
    }

    @Test fun resizingKeepsOldBorrowersAliveUntilTheirFinalReleaseAndClearsOnlyTheOldLoader() {
        val client = transport()
        val session = account("resize")
        val original = PosterImageCache.createLoader(context, session, client)
        load(original, session)
        val oldDisk = requireNotNull(original.diskCache)
        val oldDirectory = oldDisk.directory.toFile()
        val key = PosterImageCache.requestKey(session, "https://poster-warm.invalid/Images/$runId/same?tag=synthetic")
        val oldEditor = requireNotNull(oldDisk.openEditor(key))
        var editorAborted = false
        var originalReleased = false
        try {
            oldDisk.fileSystem.write(oldEditor.metadata) { writeUtf8("pending-old-metadata") }
            oldDisk.fileSystem.write(oldEditor.data) { writeUtf8("pending-old-image") }
            val resized = PosterImageCache.createLoader(context, session, client, 64L * 1024 * 1024)
            try {
                val newDirectory = requireNotNull(resized.diskCache).directory.toFile()
                assertNotSame(original, resized)
                assertNotEquals(oldDirectory, newDirectory)
                assertEquals("容量变更不能关闭尚在使用的旧 loader", DataSource.MEMORY_CACHE, load(original, session).dataSource)
                assertEquals(DataSource.NETWORK, load(resized, session).dataSource)
                assertNull(PosterImageCache.acquireWarmLoader(context, session))
                oldEditor.abort()
                editorAborted = true
                assertTrue("最后引用释放前保留旧代次目录", oldDirectory.isDirectory)
                PosterImageCache.release(original)
                originalReleased = true
                await("旧代次最后引用释放后实际清内存并删除自己的目录") {
                    original.memoryCache!!.size == 0L && !oldDirectory.exists()
                }
                assertTrue("旧代次清理不能删除新目录", newDirectory.isDirectory)
                assertEquals(DataSource.MEMORY_CACHE, load(resized, session).dataSource)
                assertEquals(DataSource.NETWORK, load(resized, session, "after-old-release").dataSource)
                resized.memoryCache!!.clear()
                assertEquals("新目录在旧代次回收后仍实际可写可读", DataSource.DISK, load(resized, session, "after-old-release").dataSource)
                val current = requireNotNull(PosterImageCache.acquireWarmLoader(context, session, 64L * 1024 * 1024))
                try { assertSame(resized, current) } finally { PosterImageCache.release(current) }
            } finally { PosterImageCache.release(resized) }
        } finally {
            if (!editorAborted) oldEditor.abort()
            if (!originalReleased) PosterImageCache.release(original)
        }
    }

    @Test fun cancellationAndRemovalCannotPublishOrResurrectAPreparedLoader() {
        val client = transport()
        val session = account("cancelled")
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val blockingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS)) { "测试未释放 loader 构造门闩" }
                return context.applicationContext
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val preparation = scope.async { PosterImageCache.prepareLoader(blockingContext, session, client) }
            assertTrue("IO 已开始构造 loader", entered.await(5, TimeUnit.SECONDS))
            val removal = PosterImageCache.prepareAccountRemoval(context, session)
            preparation.cancel()
            proceed.countDown()
            runBlocking { withTimeout(5_000) { preparation.join() } }
            assertTrue(preparation.isCancelled)
            assertNull("旧任务不能安装为可见暖缓存", PosterImageCache.acquireWarmLoader(context, session))
            assertTrue(removal())

            val newLogin = PosterImageCache.createLoader(context, session.copy(accessToken = "synthetic-new-token"), client)
            assertEquals(DataSource.NETWORK, load(newLogin, session).dataSource)
            assertTrue("旧清理迟到不能删除新登录", removal())
            assertEquals(DataSource.MEMORY_CACHE, load(newLogin, session).dataSource)
            PosterImageCache.release(newLogin)
        } finally {
            proceed.countDown()
            scope.cancel()
        }
    }

    @Test fun emptySessionLoaderHasAnOwnedTransportAndIsNeverRetainedAsAnAccount() {
        val client = transport()
        val session = account("empty-fixture")
        val loader = PosterImageCache.createLoader(context, null, client)
        PlaybackClientCleanup.release(client).get(5, TimeUnit.SECONDS)
        assertEquals(DataSource.NETWORK, load(loader, session).dataSource)
        assertNull(loader.diskCache)
        PosterImageCache.release(loader)
        await("无会话 loader 不进入暖缓存，最后引用释放后实际清理") { loader.memoryCache!!.size == 0L }
        assertNull(PosterImageCache.acquireWarmLoader(context, null))
    }

    @Test fun sharedCardRequestsReuseDiskDecodedImagesFromMemoryForPostersBackdropsAndPortraitFallbacks() = runBlocking {
        val client = transport(width = 960, height = 1440)
        val session = account("poster-card-dimensions")
        val api = JellyfinApi(client)
        val loader = PosterImageCache.createLoader(context, session, client)
        try {
            val cases = listOf(
                MediaItem("poster", "海报", "Movie", imageTag = "one") to PosterStyle.POSTER,
                MediaItem("backdrop", "背景", "Movie", imageTag = "one", backdropTag = "wide") to PosterStyle.BACKDROP,
                MediaItem("fallback", "没有背景的海报", "Movie", imageTag = "one") to PosterStyle.BACKDROP,
                MediaItem("person", "人物", "Person", imageTag = "one") to PosterStyle.POSTER,
            )
            for ((item, style) in cases) {
                val width = 233 // 覆盖列像素余数；URL 取宽度档位，解码尺寸使用真实卡片宽高。
                val size = IntSize(width, (width / style.aspectRatio).roundToInt())
                val type = if (style == PosterStyle.POSTER) "Primary" else "Backdrop"
                val url = requireNotNull(PosterRequests.url(api, session, item, width, type))
                val network = loader.execute(PosterRequests.build(context, session, api, url, size)) as SuccessResult
                assertEquals(DataSource.NETWORK, network.dataSource)
                loader.memoryCache!!.clear()
                val decodedFromDisk = loader.execute(PosterRequests.build(context, session, api, url, size)) as SuccessResult
                assertEquals(DataSource.DISK, decodedFromDisk.dataSource)
                val callsBeforeCard = calls.get()
                // 与实际 PosterImage 同一个构造器，重新创建请求以排除对象本身复用造成的假命中。
                val card = loader.execute(PosterRequests.build(context, session, api, url, size)) as SuccessResult
                assertEquals("${item.id} 应复用已有解码结果，不二次解码", DataSource.MEMORY_CACHE, card.dataSource)
                assertSame(decodedFromDisk.image, card.image)
                assertEquals(callsBeforeCard, calls.get())
                assertTrue("Crop 需要足够宽的图片", card.image.width >= size.width)
                assertTrue("Crop 需要足够高的图片", card.image.height >= size.height)
            }
            assertEquals(cases.size, calls.get())
        } finally { PosterImageCache.release(loader) }
    }

    @Test fun eightSameHostPostersStartTogetherWithoutChangingTheParentClientLimit() = runBlocking {
        val started = CountDownLatch(8)
        val release = CountDownLatch(1)
        val client = transport {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "测试未放行封面响应" }
        }
        val parentLimit = client.dispatcher.maxRequestsPerHost
        val session = account("concurrent-posters")
        val api = JellyfinApi(client)
        val loader = PosterImageCache.createLoader(context, session, client)
        try {
            val requests = (0 until 8).map { index -> async(Dispatchers.Default) {
                val item = MediaItem("concurrent-$index", "封面 $index", "Movie", imageTag = "one")
                val url = requireNotNull(PosterRequests.url(api, session, item, 48, "Primary"))
                loader.execute(PosterRequests.build(context, session, api, url, IntSize(48, 72)))
            } }
            try {
                // 所有响应都未放行；默认五并发会卡在五张，不能靠请求先后完成蒙混通过。
                assertTrue("同一服务器的八张独立封面应同时开始请求", started.await(5, TimeUnit.SECONDS))
                assertEquals("封面独占 dispatcher 不能改变 API 客户端的限额", parentLimit, client.dispatcher.maxRequestsPerHost)
            } finally { release.countDown() }
            withTimeout(10_000) {
                requests.forEach { request ->
                    val result = request.await()
                    assertTrue("并发请求必须完成真实 PNG 解码", result is SuccessResult)
                    assertEquals(DataSource.NETWORK, (result as SuccessResult).dataSource)
                }
            }
            assertEquals("只请求可见的八张封面，不额外预取", 8, calls.get())
        } finally {
            release.countDown()
            PosterImageCache.release(loader)
        }
    }

    private fun load(loader: ImageLoader, session: Session, image: String = "same") = runBlocking {
        withTimeout(10_000) {
            val url = "https://poster-warm.invalid/Images/$runId/$image?tag=synthetic"
            val key = PosterImageCache.requestKey(session, url)
            val result = loader.execute(ImageRequest.Builder(context).data(url)
                .memoryCacheKey(key).diskCacheKey(key).size(48, 72).allowHardware(false).build())
            assertTrue("真实 Coil PNG 解码必须成功：${result.javaClass.simpleName}", result is SuccessResult)
            result as SuccessResult
        }
    }

    private fun transport(width: Int = 48, height: Int = 72, beforeResponse: () -> Unit = {}): OkHttpClient {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(35, 145, 220))
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "poster-warm.invalid")
            calls.incrementAndGet()
            beforeResponse()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic PNG")
                .header("Content-Type", "image/png").header("Cache-Control", "public, max-age=3600")
                .body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build().also { clients += it }
    }

    private fun account(suffix: String) = Session(ServerConfig("https://poster-warm.invalid/jellyfin"),
        "synthetic-never-sent", "fixture-$runId-$suffix", "合成暖缓存账号", "fixture-device")
        .also { accounts += it }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue(message, predicate())
    }
}

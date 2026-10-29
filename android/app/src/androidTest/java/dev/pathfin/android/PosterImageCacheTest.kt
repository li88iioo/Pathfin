package dev.pathfin.android

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 只在 verification 包中验证真实 Coil 磁盘生命周期，不联网、不读取账号存储。 */
@RunWith(AndroidJUnit4::class)
class PosterImageCacheTest {
    private lateinit var context: Context
    private val sessions = mutableListOf<Session>()
    private val runId = UUID.randomUUID().toString()
    private val client = OkHttpClient()

    @Before
    fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification") { "图片缓存测试只允许独立验证包" }
    }

    @After
    fun clean() {
        if (!::context.isInitialized || context.packageName != "dev.pathfin.android.verification") return
        sessions.forEach { check(PosterImageCache.clearAccount(context, it)) }
    }

    @Test
    fun keysPartitionAccountsOriginsAndProxyPathsWithoutTokenOrUserName() {
        val session = session()
        val url = "https://poster-cache.invalid/jellyfin/Items/film/Images/Primary?maxWidth=420&tag=one"
        val key = PosterImageCache.requestKey(session, url)
        assertEquals(key, PosterImageCache.requestKey(session.copy(accessToken = "rotated-fixture-token", userName = "另一个显示名"), url))
        assertEquals(PosterImageCache.accountKey(session), PosterImageCache.accountKey(session.copy(server = ServerConfig("https://POSTER-CACHE.invalid:443/jellyfin/"))))
        assertNotEquals(key, PosterImageCache.requestKey(session.copy(userId = "another-user"), url))
        assertNotEquals(key, PosterImageCache.requestKey(session.copy(server = ServerConfig("https://another-origin.invalid/jellyfin")), url))
        assertNotEquals(key, PosterImageCache.requestKey(session.copy(server = ServerConfig("https://poster-cache.invalid/another-server")), url))
        assertNotEquals(key, PosterImageCache.requestKey(session, url.replace("tag=one", "tag=two")))
        assertNotEquals(key, PosterImageCache.requestKey(session, url.replace("420", "720")))
        assertFalse(key.contains(session.accessToken))
        assertFalse(key.contains(session.userId))
        assertFalse(key.contains(session.userName))
        assertFalse(key.contains("poster-cache.invalid"))
    }

    @Test
    fun oneDiskInstanceIsSharedAndEntriesSurviveLoaderAndRegistryRecreation() {
        val session = session()
        val first = PosterImageCache.createLoader(context, session, client)
        val second = PosterImageCache.createLoader(context, session, client)
        val disk = requireNotNull(first.diskCache)
        val key = PosterImageCache.requestKey(session, "https://poster-cache.invalid/Images/film?tag=one")
        assertEquals(1024L * 1024 * 1024, disk.maxSize)
        assertSame("同一目录不能创建两个 DiskCache 实例", disk, second.diskCache)
        write(disk, key, "fixture-image-bytes")
        PosterImageCache.release(first)
        assertEquals("fixture-image-bytes", read(requireNotNull(second.diskCache), key))
        PosterImageCache.release(second)
        PosterImageCache.shutdown()

        val restored = PosterImageCache.createLoader(context, session.copy(accessToken = "new-fixture-token"), client)
        assertNotSame(disk, restored.diskCache)
        assertEquals("关闭进程资源后仍应从磁盘恢复", "fixture-image-bytes", read(requireNotNull(restored.diskCache), key))
        PosterImageCache.release(restored)
    }

    @Test
    fun settingsClearBothCachesAndDeletingOneAccountPreservesTheOther() {
        val firstSession = session("first-$runId")
        val secondSession = session("second-$runId")
        val first = PosterImageCache.createLoader(context, firstSession, client)
        val second = PosterImageCache.createLoader(context, secondSession, client)
        val imageUrl = "https://poster-cache.invalid/Images/film?tag=one"
        val firstKey = PosterImageCache.requestKey(firstSession, imageUrl)
        val secondKey = PosterImageCache.requestKey(secondSession, imageUrl)
        val firstDisk = requireNotNull(first.diskCache)
        val secondDisk = requireNotNull(second.diskCache)
        val firstMemory = requireNotNull(first.memoryCache)
        val secondMemory = requireNotNull(second.memoryCache)
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage()
        firstMemory[MemoryCache.Key(firstKey)] = MemoryCache.Value(image)
        secondMemory[MemoryCache.Key(secondKey)] = MemoryCache.Value(image)
        write(firstDisk, firstKey, "first-account")
        write(secondDisk, secondKey, "second-account")

        // 与设置页面相同的 Coil 清理调用，确认启用 disk 后清理仍真实生效。
        first.memoryCache?.clear()
        first.diskCache?.clear()
        assertNull(firstMemory[MemoryCache.Key(firstKey)])
        assertNull(read(firstDisk, firstKey))
        write(firstDisk, firstKey, "first-account-again")
        firstMemory[MemoryCache.Key(firstKey)] = MemoryCache.Value(image)
        val firstDirectory = File(firstDisk.directory.toString())

        assertTrue(PosterImageCache.clearAccount(context, firstSession))
        assertFalse(firstDirectory.exists())
        assertNull(firstMemory[MemoryCache.Key(firstKey)])
        assertEquals("second-account", read(secondDisk, secondKey))
        assertNotNull(secondMemory[MemoryCache.Key(secondKey)])
        PosterImageCache.release(first)
        PosterImageCache.release(second)
    }

    @Test
    fun delayedRemovalPreservesNewLoaderOnTheSameEntryAndOtherAccount() {
        val firstSession = session("relogin-$runId")
        val otherSession = session("other-$runId")
        val first = PosterImageCache.createLoader(context, firstSession, client)
        val other = PosterImageCache.createLoader(context, otherSession, client)
        val firstDisk = requireNotNull(first.diskCache)
        val otherDisk = requireNotNull(other.diskCache)
        val firstKey = PosterImageCache.requestKey(firstSession, "https://poster-cache.invalid/Images/first")
        val otherKey = PosterImageCache.requestKey(otherSession, "https://poster-cache.invalid/Images/other")
        write(firstDisk, firstKey, "old-account-cache")
        write(otherDisk, otherKey, "other-account-cache")
        val delayedRemoval = PosterImageCache.prepareAccountRemoval(context, firstSession)
        PosterImageCache.release(first)

        // 真实共享 DiskCache 的新 loader：只比较 Entry identity 无法识别这次重新登录。
        val reopened = PosterImageCache.createLoader(context, firstSession.copy(accessToken = "relogged-fixture-token"), client)
        val reopenedDisk = requireNotNull(reopened.diskCache)
        assertSame(firstDisk, reopenedDisk)
        write(reopenedDisk, firstKey, "new-account-cache")
        val memory = requireNotNull(reopened.memoryCache)
        memory[MemoryCache.Key(firstKey)] = MemoryCache.Value(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage())

        assertTrue(delayedRemoval())
        assertEquals("旧清理不能关闭或删除新登录的磁盘缓存", "new-account-cache", read(reopenedDisk, firstKey))
        assertNotNull("旧清理不能 shutdown 新 loader 并清空其内存", memory[MemoryCache.Key(firstKey)])
        write(reopenedDisk, firstKey, "new-loader-still-writable")
        assertEquals("new-loader-still-writable", read(reopenedDisk, firstKey))
        assertEquals("other-account-cache", read(otherDisk, otherKey))
        PosterImageCache.release(reopened)
        PosterImageCache.release(other)
    }

    @Test
    fun delayedRemovalWithoutInitialEntryPreservesLoaderCreatedAfterPreparation() {
        val account = session("cold-relogin-$runId")
        val delayedRemoval = PosterImageCache.prepareAccountRemoval(context, account)
        val loader = PosterImageCache.createLoader(context, account, client)
        val disk = requireNotNull(loader.diskCache)
        val key = PosterImageCache.requestKey(account, "https://poster-cache.invalid/Images/cold")
        write(disk, key, "created-after-preparation")

        assertTrue(delayedRemoval())
        assertEquals("初次无 Entry 的清理不能删除后来新建的账号目录", "created-after-preparation", read(disk, key))
        write(disk, key, "still-writable")
        assertEquals("still-writable", read(disk, key))
        PosterImageCache.release(loader)
    }

    @Test
    fun preparedRemovalStillDeletesItsAccountWhenOnlyAnotherAccountCreatesLoader() {
        val account = session("remove-$runId")
        val otherAccount = session("keep-$runId")
        val loader = PosterImageCache.createLoader(context, account, client)
        val disk = requireNotNull(loader.diskCache)
        val key = PosterImageCache.requestKey(account, "https://poster-cache.invalid/Images/remove")
        write(disk, key, "remove-this-account")
        val directory = File(disk.directory.toString())
        val delayedRemoval = PosterImageCache.prepareAccountRemoval(context, account)
        val other = PosterImageCache.createLoader(context, otherAccount, client)
        val otherDisk = requireNotNull(other.diskCache)
        val otherKey = PosterImageCache.requestKey(otherAccount, "https://poster-cache.invalid/Images/keep")
        write(otherDisk, otherKey, "keep-other-account")

        assertTrue(delayedRemoval())
        assertFalse("未重登的账号缓存必须实际删除", directory.exists())
        assertEquals("另一个账号的新 loader 不能阻止目标清理或被一起关闭", "keep-other-account", read(otherDisk, otherKey))
        write(otherDisk, otherKey, "other-still-writable")
        assertEquals("other-still-writable", read(otherDisk, otherKey))
        PosterImageCache.release(loader)
        PosterImageCache.release(other)
    }

    @Test
    fun imageWidthsFollowDisplayPixelsInBoundedReusableBuckets() {
        assertEquals(320, PosterImageCache.requestWidth(312))
        assertEquals(420, PosterImageCache.requestWidth(336))
        assertEquals(560, PosterImageCache.requestWidth(504))
        assertEquals(720, PosterImageCache.requestWidth(588))
        assertEquals(1280, PosterImageCache.requestWidth(1080))
        assertEquals(2048, PosterImageCache.requestWidth(3000))
    }

    @Test
    fun capacityChangesUseNewPhysicalGenerationsAndColdStartRestoresTheCurrentOne() {
        val account = session()
        val initial = PosterImageCache.createLoader(context, account, client)
        val firstDisk = requireNotNull(initial.diskCache)
        val key = PosterImageCache.requestKey(account, "https://poster-cache.invalid/Images/resizing")
        write(firstDisk, key, "old-generation")
        val resized = PosterImageCache.createLoader(context, account, client, 128L * 1024 * 1024)
        val resizedDisk = requireNotNull(resized.diskCache)
        assertNotSame(firstDisk, resizedDisk)
        assertNotEquals("容量变化不能重开仍有旧请求的物理目录", firstDisk.directory, resizedDisk.directory)
        assertEquals(File(firstDisk.directory.toString()).parentFile, File(resizedDisk.directory.toString()).parentFile)
        assertEquals(128L * 1024 * 1024, resizedDisk.maxSize)
        assertNull("不从可能仍被旧 editor 修改的目录迁移文件", read(resizedDisk, key))
        write(resizedDisk, key, "current-generation")
        PosterImageCache.release(initial)
        PosterImageCache.release(resized)
        PosterImageCache.shutdown()

        val restored = PosterImageCache.createLoader(context, account, client, 128L * 1024 * 1024)
        val restoredDisk = requireNotNull(restored.diskCache)
        assertNotSame(resizedDisk, restoredDisk)
        assertEquals("冷启动必须复用 current.json 指定的代次", resizedDisk.directory, restoredDisk.directory)
        assertEquals(128L * 1024 * 1024, restoredDisk.maxSize)
        assertEquals("current-generation", read(restoredDisk, key))

        val reset = PosterImageCache.createLoader(context, account, client)
        val resetDisk = requireNotNull(reset.diskCache)
        assertEquals(PosterImageCache.MAX_DISK_BYTES, resetDisk.maxSize)
        assertNotEquals("改回原容量也不能复用旧 UUID 目录", firstDisk.directory, resetDisk.directory)
        assertNotEquals(resizedDisk.directory, resetDisk.directory)
        PosterImageCache.release(restored)
        PosterImageCache.release(reset)
    }

    @Test
    fun oldEditorAbortingAfterResizeCannotDeleteTheNewGenerationFileWithTheSameKey() {
        val account = session()
        val initial = PosterImageCache.createLoader(context, account, client)
        val oldDisk = requireNotNull(initial.diskCache)
        val key = PosterImageCache.requestKey(account, "https://poster-cache.invalid/Images/same-key")
        val oldEditor = requireNotNull(oldDisk.openEditor(key))
        var aborted = false
        try {
            oldDisk.fileSystem.write(oldEditor.metadata) { writeUtf8("pending-old-metadata") }
            oldDisk.fileSystem.write(oldEditor.data) { writeUtf8("pending-old-image") }
            val resized = PosterImageCache.createLoader(context, account, client, 64L * 1024 * 1024)
            try {
                val newDisk = requireNotNull(resized.diskCache)
                assertNotEquals(oldDisk.directory, newDisk.directory)
                assertEquals(64L * 1024 * 1024, newDisk.maxSize)
                write(newDisk, key, "new-image-survives-old-abort")
                oldEditor.abort()
                aborted = true

                assertEquals("旧 editor 的清理必须只作用于旧目录", "new-image-survives-old-abort", read(newDisk, key))
                write(newDisk, key, "new-cache-still-writable")
                assertEquals("new-cache-still-writable", read(newDisk, key))
            } finally {
                PosterImageCache.release(resized)
            }
        } finally {
            if (!aborted) oldEditor.abort()
            PosterImageCache.release(initial)
        }
    }

    @Test
    fun obsoleteGenerationDeletionSerializesWithAccountRemovalAndPreservesOtherAccount() {
        val account = session("serialized-removal-$runId")
        val otherAccount = session("serialized-keep-$runId")
        val loader = PosterImageCache.createLoader(context, account, client)
        val other = PosterImageCache.createLoader(context, otherAccount, client)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var removal: Thread? = null
        val removed = AtomicReference<Result<Boolean>?>(null)
        try {
            val currentDisk = requireNotNull(loader.diskCache)
            val otherDisk = requireNotNull(other.diskCache)
            val key = PosterImageCache.requestKey(account, "https://poster-cache.invalid/Images/serialized")
            val otherKey = PosterImageCache.requestKey(otherAccount, "https://poster-cache.invalid/Images/serialized-other")
            write(currentDisk, key, "current-account-cache")
            write(otherDisk, otherKey, "other-account-cache")
            awaitCleanup()

            // 模拟进程留下的真实 Coil 旧代次；目录仍由正式缓存清理负责删除。
            val root = requireNotNull(File(currentDisk.directory.toString()).parentFile)
            val obsolete = File(root, "generation-${UUID.randomUUID()}")
            val oldDisk = DiskCache.Builder().directory(obsolete.toOkioPath()).maxSizeBytes(16L * 1024 * 1024).build()
            try { write(oldDisk, key, "obsolete-generation-cache") } finally { oldDisk.shutdown() }
            assertTrue(obsolete.isDirectory)
            val firstClassification = AtomicBoolean(true)
            val observedRoot = object : File(root.path) {
                override fun listFiles(): Array<File>? = super.listFiles()?.map { child ->
                    if (child.absolutePath != obsolete.absolutePath) child else object : File(child.path) {
                        override fun isDirectory(): Boolean {
                            val directory = super.isDirectory()
                            if (firstClassification.compareAndSet(true, false)) {
                                check(directory) { "旧代次必须是实际存在的目录" }
                                entered.countDown()
                                check(proceed.await(15, TimeUnit.SECONDS)) { "测试未释放旧代次目录分类" }
                            }
                            return directory
                        }
                    }
                }?.toTypedArray()
            }
            try {
                PosterImageCache::class.java.getDeclaredMethod("cleanObsoleteGenerations", File::class.java)
                    .apply { isAccessible = true }.invoke(PosterImageCache, observedRoot)
                assertTrue("正式旧代次清理必须已分类该目录", entered.await(5, TimeUnit.SECONDS))
                val started = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val removing = Thread({
                    started.countDown()
                    try { removed.set(runCatching { PosterImageCache.clearAccount(context, account) }) }
                    finally { finished.countDown() }
                }, "pathfin-cache-account-removal").also { removal = it; it.start() }
                assertTrue("正式账号删除线程必须已启动", started.await(5, TimeUnit.SECONDS))
                runBlocking { withTimeout(5_000) {
                    while (finished.count != 0L && !blockedAtAccountRemoval(removing)) yield()
                } }
                removed.get()?.getOrThrow()
                assertEquals("旧代次遍历未结束时，账号删除必须等待同一 IO 事务", 1L, finished.count)
            } finally {
                proceed.countDown()
                removal?.join(5_000)
                assertFalse("账号删除不能留下后台线程", removal?.isAlive == true)
                awaitCleanup()
            }
            assertTrue(requireNotNull(removed.get()).getOrThrow())
            assertFalse("正式账号删除必须实际移除目标根目录", root.exists())
            assertEquals("other-account-cache", read(otherDisk, otherKey))
            write(otherDisk, otherKey, "other-account-still-writable")
            assertEquals("other-account-still-writable", read(otherDisk, otherKey))
        } finally {
            PosterImageCache.release(loader)
            PosterImageCache.release(other)
        }
    }

    private fun blockedAtAccountRemoval(thread: Thread): Boolean {
        if (thread.state != Thread.State.BLOCKED) return false
        val frame = thread.stackTrace.firstOrNull() ?: return false
        return thread.state == Thread.State.BLOCKED && frame.className == PosterImageCache::class.java.name &&
            frame.methodName == "clearIfUnchanged"
    }

    private fun awaitCleanup() {
        val scope = PosterImageCache::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
            .get(PosterImageCache) as CoroutineScope
        runBlocking { withTimeout(5_000) { requireNotNull(scope.coroutineContext[Job]).children.toList().joinAll() } }
    }

    private fun session(user: String = "fixture-$runId") = Session(
        ServerConfig("https://poster-cache.invalid/jellyfin"), "synthetic-token-not-real", user,
        "合成图片缓存账号", "fixture-device",
    ).also { sessions += it }

    private fun write(cache: DiskCache, key: String, value: String) {
        val editor = requireNotNull(cache.openEditor(key))
        try {
            cache.fileSystem.write(editor.metadata) { writeUtf8("fixture-metadata") }
            cache.fileSystem.write(editor.data) { writeUtf8(value) }
            editor.commit()
        } catch (error: Throwable) {
            editor.abort()
            throw error
        }
    }

    private fun read(cache: DiskCache, key: String): String? = cache.openSnapshot(key)?.use { snapshot ->
        cache.fileSystem.read(snapshot.data) { readUtf8() }
    }
}

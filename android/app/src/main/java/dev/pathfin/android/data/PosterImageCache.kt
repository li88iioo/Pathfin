package dev.pathfin.android.data

import android.content.Context
import android.util.AtomicFile
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.playback.PlaybackClientCleanup
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toOkioPath
import org.json.JSONObject

/** 封面缓存按服务器地址与账号隔离；凭据只随请求发送，不参与缓存目录或 key。 */
internal object PosterImageCache {
    const val MAX_DISK_BYTES = 1024L * 1024 * 1024
    const val MEMORY_BYTES_PER_LOADER = 16L * 1024 * 1024
    const val MAX_IDLE_LOADERS = 2
    private const val DIRECTORY = "poster-images-v1"
    private const val CURRENT = "current.json"
    private val widths = intArrayOf(160, 240, 320, 420, 560, 720, 960, 1280, 1920, 2048)
    // 主线程只短暂操作状态；关闭 DiskCache、读写元数据和删除目录仅持有 ioLock。
    private val stateLock = Any()
    private val ioLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<String, Entry>()
    private val records = IdentityHashMap<ImageLoader, LoaderRecord>()
    private val loaderGenerations = mutableMapOf<String, Long>()
    private val accountEpochs = mutableMapOf<String, Long>()
    private var nextLoaderGeneration = 0L
    private var nextUse = 0L

    private class Entry(val diskCache: DiskCache, var epoch: Long) {
        var record: LoaderRecord? = null
        var retired = false
    }

    private class LoaderRecord(val loader: ImageLoader, val client: OkHttpClient, val entry: Entry?) {
        var references = 1
        var lastUse = 0L
    }

    private class Disposal(
        val record: LoaderRecord?,
        val diskCache: DiskCache? = null,
        val retiredDirectory: File? = null,
    )

    private class AccountPreparation(val directory: File, val epoch: Long)

    /** Token 轮换与账号显示名变化不使缓存失效；同 origin 下不同反代目录仍隔离。 */
    fun accountKey(session: Session): String {
        val url = session.server.baseUrl.toHttpUrl()
        return digest(listOf(url.scheme, url.host, url.port.toString(), url.encodedPath.trimEnd('/'), session.userId)
            .joinToString("\u0000"))
    }

    fun requestKey(session: Session, imageUrl: String): String = "poster-v1/${accountKey(session)}/${digest(imageUrl)}"

    /** 以真实显示像素选有限档位，避免小横图固定下载详情尺寸，或每像素产生一个变体。 */
    fun requestWidth(displayWidthPx: Int): Int = widths.firstOrNull { it >= displayWidthPx } ?: widths.last()

    /** 只查内存，不开文件、不创建客户端；命中与 prepareLoader 一样持有一个引用。 */
    fun acquireWarmLoader(context: Context, session: Session?, maxBytes: Long = MAX_DISK_BYTES): ImageLoader? {
        if (session == null) return null
        val path = accountDirectory(context, session).absolutePath
        return synchronized(stateLock) {
            val entry = entries[path] ?: return@synchronized null
            if (entry.retired || entry.epoch != (accountEpochs[path] ?: 0L) ||
                entry.diskCache.maxSize != boundedDiskBytes(maxBytes)) return@synchronized null
            val record = entry.record ?: return@synchronized null
            loaderGenerations[path] = ++nextLoaderGeneration
            record.references++
            record.lastUse = ++nextUse
            record.loader
        }
    }

    /** 生产入口：磁盘准备、容量切换和旧实例关闭均在 IO 线程进行。 */
    suspend fun prepareLoader(context: Context, session: Session?, client: OkHttpClient, maxBytes: Long = MAX_DISK_BYTES): ImageLoader {
        val account = beginPreparation(context, session)
        var prepared: ImageLoader? = null
        try {
            return withContext(Dispatchers.IO) {
                createLoaderBlocking(context, account, client, maxBytes, currentCoroutineContext()).also { prepared = it }
            }
        } catch (error: Throwable) {
            // withContext 返回前也可能取消，不能遗留一个调用方永远拿不到的 loader。
            prepared?.let(::release)
            throw error
        }
    }

    /** 异步准备期间的临时 loader；客户端仍由调用方持有，调用方必须 shutdown 并释放客户端。 */
    fun createMemoryLoader(context: Context, client: OkHttpClient): ImageLoader = buildLoader(context, client, null)

    /** 同步入口仅供已在工作线程上的调用和生命周期测试；UI 应使用 prepareLoader。 */
    fun createLoader(context: Context, session: Session?, client: OkHttpClient, maxBytes: Long = MAX_DISK_BYTES): ImageLoader {
        return createLoaderBlocking(context, beginPreparation(context, session), client, maxBytes, null)
    }

    private fun beginPreparation(context: Context, session: Session?): AccountPreparation? {
        val directory = session?.let { accountDirectory(context, it) } ?: return null
        return synchronized(stateLock) {
            // 在 IO 排队前预留代次，新登录的准备也能阻止迟到的旧账号删除。
            loaderGenerations[directory.absolutePath] = ++nextLoaderGeneration
            AccountPreparation(directory, accountEpochs[directory.absolutePath] ?: 0L)
        }
    }

    private fun createLoaderBlocking(
        context: Context,
        account: AccountPreparation?,
        client: OkHttpClient,
        maxBytes: Long,
        coroutineContext: CoroutineContext?,
    ): ImageLoader = synchronized(ioLock) creation@{
        coroutineContext?.ensureActive()
        if (account == null) {
            val record = newRecord(context, client, null)
            try {
                synchronized(stateLock) {
                    coroutineContext?.ensureActive()
                    records[record.loader] = record
                }
            } catch (error: Throwable) {
                dispose(Disposal(record))
                throw error
            }
            return@creation record.loader
        }
        val boundedBytes = boundedDiskBytes(maxBytes)
        val root = account.directory
        val previous = synchronized(stateLock) {
            requireCurrent(account, coroutineContext)
            entries[root.absolutePath]
        }
        if (previous != null && previous.diskCache.maxSize == boundedBytes) {
            val warm = synchronized(stateLock) {
                requireCurrent(account, coroutineContext)
                previous.record?.let { record ->
                    previous.epoch = account.epoch
                    record.references++
                    record.lastUse = ++nextUse
                    record.loader
                }
            }
            if (warm != null) return@creation warm
            val record = newRecord(context, client, previous)
            try {
                synchronized(stateLock) {
                    requireCurrent(account, coroutineContext)
                    previous.epoch = account.epoch
                    previous.record = record
                    record.lastUse = ++nextUse
                    records[record.loader] = record
                }
            } catch (error: Throwable) {
                dispose(Disposal(record))
                throw error
            }
            return@creation record.loader
        }

        // 旧 editor 在 DiskCache.shutdown 后仍可能 abort，绝不能重新使用它的目录。
        val directory = if (previous == null) currentDirectory(root, boundedBytes) else null
        val selected = directory ?: File(root, "generation-${UUID.randomUUID()}")
        // 先持久化新代次，保证关闭旧实例后即使取消/失败，冷启动也不会重开旧目录。
        writeCurrent(root, selected.name, boundedBytes)
        synchronized(stateLock) {
            requireCurrent(account, coroutineContext)
            entries.remove(root.absolutePath)
        }?.let { retireEntry(it, force = false)?.let(::dispose) }
        synchronized(stateLock) { requireCurrent(account, coroutineContext) }

        val entry = Entry(DiskCache.Builder().directory(selected.toOkioPath()).maxSizeBytes(boundedBytes).build(), account.epoch)
        try {
            val record = newRecord(context, client, entry)
            try {
                synchronized(stateLock) {
                    requireCurrent(account, coroutineContext)
                    entry.record = record
                    record.lastUse = ++nextUse
                    records[record.loader] = record
                    entries[root.absolutePath] = entry
                }
            } catch (error: Throwable) {
                dispose(Disposal(record))
                throw error
            }
            cleanObsoleteGenerations(root)
            record.loader
        } catch (error: Throwable) {
            entry.diskCache.shutdown()
            throw error
        }
    }

    private fun buildLoader(context: Context, client: OkHttpClient, diskCache: DiskCache?): ImageLoader =
        ImageLoader.Builder(context.applicationContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(MEMORY_BYTES_PER_LOADER).weakReferencesEnabled(false).build()
            }
            .diskCache { diskCache }
            .build()

    private fun boundedDiskBytes(maxBytes: Long) = maxBytes.coerceIn(16L * 1024 * 1024, 65536L * 1024 * 1024)

    /** 配置可复用，连接池和 dispatcher 必须独占，页面关闭自己的 fallback client 不影响暖缓存。 */
    private fun newRecord(context: Context, client: OkHttpClient, entry: Entry?): LoaderRecord {
        val owned = client.newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            .connectionPool(ConnectionPool()).build()
        try {
            return LoaderRecord(buildLoader(context, owned, entry?.diskCache), owned, entry)
        } catch (error: Throwable) {
            PlaybackClientCleanup.release(owned)
            throw error
        }
    }

    /** 必须在 stateLock 内检查，退出时能够立刻使排队中的旧会话准备失效。 */
    private fun requireCurrent(account: AccountPreparation, coroutineContext: CoroutineContext?) {
        coroutineContext?.ensureActive()
        if ((accountEpochs[account.directory.absolutePath] ?: 0L) != account.epoch) {
            throw CancellationException(tr("图片缓存会话已变更"))
        }
    }

    /** 每次 acquire/prepare 恰好释放一次；普通切服只 detach，保留最多两个空闲账号的已解码图片。 */
    fun release(loader: ImageLoader) {
        val disposals = synchronized(stateLock) {
            val record = records[loader] ?: return
            check(record.references > 0) { tr("图片 loader 已经释放") }
            record.references--
            record.lastUse = ++nextUse
            val entry = record.entry
            val pending = mutableListOf<Disposal>()
            if (record.references == 0 && (entry == null || entry.retired)) {
                records.remove(loader)
                entry?.record = null
                pending += Disposal(record, entry?.diskCache, entry?.diskCache?.directory?.toFile())
            }
            val idle = records.values.filter { it.references == 0 && it.entry?.retired == false }.sortedBy { it.lastUse }
            idle.take((idle.size - MAX_IDLE_LOADERS).coerceAtLeast(0)).forEach { oldest ->
                records.remove(oldest.loader)
                oldest.entry?.record = null
                // 只淘汰 loader/client；唯一 DiskCache 继续留在 registry，避免重开仍有旧 editor 的目录。
                pending += Disposal(oldest)
            }
            pending
        }
        if (disposals.isNotEmpty()) cleanupScope.launch { synchronized(ioLock) { disposals.forEach(::dispose) } }
    }

    /**
     * 在账号退出的调用线程捕获清理目标，返回值交给 IO 执行。
     * 同账号之后创建了新 loader 时保留其缓存，避免迟到的旧任务关闭新会话的实例。
     */
    fun prepareAccountRemoval(context: Context, session: Session): () -> Boolean {
        val directory = accountDirectory(context, session)
        return synchronized(stateLock) {
            val path = directory.absolutePath
            accountEpochs[path] = (accountEpochs[path] ?: 0L) + 1L
            val entry = entries[path]
            val generation = loaderGenerations[path]
            val removal: () -> Boolean = { clearIfUnchanged(directory, entry, generation) }
            removal
        }
    }

    private fun clearIfUnchanged(directory: File, expectedEntry: Entry?, expectedGeneration: Long?): Boolean = synchronized(ioLock) {
        val path = directory.absolutePath
        val removal = synchronized(stateLock) capture@{
            if (entries[path] !== expectedEntry || loaderGenerations[path] != expectedGeneration) return@capture false to null
            true to entries.remove(path)
        }
        if (!removal.first) return@synchronized true
        val ownedEntries = synchronized(stateLock) {
            records.values.mapNotNull { it.entry }.filter {
                it.diskCache.directory.toFile().parentFile?.absolutePath == path
            }.toSet() + listOfNotNull(removal.second)
        }
        ownedEntries.forEach { retireEntry(it, force = true)?.let(::dispose) }
        directory.deleteRecursively()
    }

    /**
     * 删除账号/退出时使用。先停止关联图片请求，再关闭唯一磁盘实例，避免旧请求写回。
     * 此方法包含文件删除，应在 IO 调度器调用；不影响其他账号的目录和内存缓存。
     */
    fun clearAccount(context: Context, session: Session): Boolean {
        return prepareAccountRemoval(context, session).invoke()
    }

    /** 释放进程内资源而不删除文件，下一次创建可从同一磁盘目录恢复。 */
    fun shutdown() = synchronized(ioLock) {
        val previous = synchronized(stateLock) {
            entries.toMap().also {
                entries.keys.forEach { path -> accountEpochs[path] = (accountEpochs[path] ?: 0L) + 1L }
                entries.clear()
            }
        }
        previous.values.forEach { retireEntry(it, force = true)?.let(::dispose) }
        val remaining = synchronized(stateLock) {
            records.values.toList().also { values -> values.forEach { records.remove(it.loader) } }
        }
        remaining.forEach { record ->
            val entry = record.entry
            if (entry != null) retireEntry(entry, force = true)?.let(::dispose) else dispose(Disposal(record))
        }
    }

    /** 容量变更淘汰旧代次时等待活跃引用离开；显式删除账号则立即停止其全部请求。 */
    private fun retireEntry(entry: Entry, force: Boolean): Disposal? = synchronized(stateLock) {
        entry.retired = true
        val record = entry.record
        if (record != null && record.references > 0 && !force) return@synchronized null
        if (record != null) records.remove(record.loader)
        entry.record = null
        Disposal(record, entry.diskCache)
    }

    private fun dispose(disposal: Disposal) {
        disposal.record?.let { record ->
            record.loader.shutdown()
            PlaybackClientCleanup.release(record.client)
        }
        disposal.diskCache?.shutdown()
        // 仅最后释放的退役代次携带路径；UUID 不复用，绝不删除账号根目录或当前代次。
        disposal.retiredDirectory?.deleteRecursively()
    }

    private fun currentDirectory(root: File, maxBytes: Long): File? = runCatching {
        val metadata = JSONObject(AtomicFile(File(root, CURRENT)).readFully().toString(Charsets.UTF_8))
        val name = metadata.getString("directory")
        if (!name.matches(Regex("generation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
            metadata.getLong("maxBytes") != maxBytes) return@runCatching null
        File(root, name)
    }.getOrNull()

    private fun writeCurrent(root: File, name: String, maxBytes: Long) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException(tr("无法准备图片缓存目录"))
        val metadata = JSONObject().put("directory", name).put("maxBytes", maxBytes).toString().toByteArray(Charsets.UTF_8)
        val file = AtomicFile(File(root, CURRENT))
        val output = file.startWrite()
        try {
            output.write(metadata)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun cleanObsoleteGenerations(root: File) {
        cleanupScope.launch {
            synchronized(ioLock) {
                val retainedDirectories = synchronized(stateLock) {
                    val current = entries[root.absolutePath]?.diskCache?.directory?.toFile()?.absolutePath ?: return@launch
                    records.values.mapNotNull { it.entry?.diskCache?.directory?.toFile()?.absolutePath }.toSet() + current
                }
                // 选择与删除共用账号移除、退役实例释放的 IO 事务，避免并发遍历同一旧目录。
                root.listFiles().orEmpty().filter { !it.name.startsWith(CURRENT) && it.absolutePath !in retainedDirectories }
                    .forEach { it.deleteRecursively() }
            }
        }
    }

    private fun accountDirectory(context: Context, session: Session): File =
        File(File(context.cacheDir, DIRECTORY), accountKey(session))

    private fun digest(value: String): String = value.encodeUtf8().sha256().hex()
}

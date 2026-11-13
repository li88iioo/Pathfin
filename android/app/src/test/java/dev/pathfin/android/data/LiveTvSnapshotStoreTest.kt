package dev.pathfin.android.data

import dev.pathfin.android.domain.LiveTvChannel
import dev.pathfin.android.domain.LiveTvProgram
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSnapshotStoreTest {
    private val session = Session(ServerConfig("https://jellyfin.example"), "secret-token", "user-a", "Alice", "device", serverId = "server-a")
    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val channels = listOf(
        LiveTvChannel(MediaItem("c1", "CCTV1", "TvChannel", imageTag = "logo", isFavorite = true), "1",
            LiveTvProgram("p1", "c1", "新闻联播", start = now, end = now.plusSeconds(1800)), lastPlayedAt = now.minusSeconds(60)),
        LiveTvChannel(MediaItem("c2", "CQTV", "TvChannel")),
    )

    @Test fun snapshotRoundTripsDisplayFieldsPerServerAndAccount() = withStore { store ->
        runBlocking { store.save(session, channels) }
        val restored = runBlocking { store.load(session) }
        assertEquals(channels, restored)
        assertNull("其他账号读不到", runBlocking { store.load(session.copy(userId = "user-b")) })
        assertNull("其他服务器读不到", runBlocking { store.load(session.copy(serverId = "server-b")) })
    }

    @Test fun snapshotNeverContainsCredentialsAndCorruptFilesAreIgnored() = withStore { store ->
        runBlocking { store.save(session, channels) }
        val file = store.files().single()
        assertFalse(file.readText().contains(session.accessToken))
        file.writeText("{not json")
        assertNull(runBlocking { store.load(session) })
    }

    @Test fun emptySnapshotIsRemembered() = withStore { store ->
        runBlocking { store.save(session, emptyList()) }
        assertEquals(emptyList<LiveTvChannel>(), runBlocking { store.load(session) })
    }

    @Test fun oversizedUtf8SnapshotDoesNotReplaceThePreviousReadableChannels() = withStore { store ->
        runBlocking { store.save(session, channels) }
        val oversized = listOf(channels.first().copy(item = channels.first().item.copy(overview = "影".repeat(3 * 1024 * 1024))))
        runBlocking { store.save(session, oversized) }
        assertEquals("超过读取上限的快照不能毁掉已有频道缓存", channels, runBlocking { store.load(session) })
        assertEquals(1, store.files().size)
    }

    @Test fun retainedAccountsKeepSharedServerIdentityAndEveryRemainingAddressButRemoveOrphanSnapshots() = withStore { store ->
        val otherUser = session.copy(userId = "user-b")
        val addressOnly = session.copy(serverId = "", server = ServerConfig("https://first.example/proxy"))
        val alternateAddress = addressOnly.copy(server = ServerConfig("https://alternate.example/proxy"))
        runBlocking { listOf(session, otherUser, addressOnly, alternateAddress).forEach { store.save(it, channels) } }
        java.io.File(store.directory, "orphan.json").writeText("{}")

        val sameAccountAtAnotherAddress = session.copy(server = ServerConfig("https://new.example"), accessToken = "new-token")
        store.store.retainAccounts(listOf(sameAccountAtAnotherAddress, addressOnly, alternateAddress))
        runBlocking {
            assertEquals("同服务器同账号换地址/令牌仍共享快照", channels, store.load(session))
            assertNull("已删除账号的快照应清理", store.load(otherUser))
            assertEquals(channels, store.load(addressOnly))
            assertEquals("没有 serverId 时仍保留调用方传入的所有备用地址", channels, store.load(alternateAddress))
        }
        assertEquals("历史孤儿 JSON 同时清理", 3, store.files().size)
        store.store.retainAccounts(emptyList())
        assertTrue(store.files().isEmpty())
    }

    @Test fun cancellingAWriteWaitingForAccountCleanupCannotRestoreTheRemovedSnapshot() = withStore { store ->
        runBlocking {
            store.save(session, channels)
            val writerThread = AtomicReference<Thread>()
            var writer: Job? = null
            try {
                // 占住实际文件事务边界，保证删除与已进入 IO 的旧写入真正竞争，而不是仅在启动前取消。
                synchronized(SnapshotFiles.lock) {
                    writer = launch(Dispatchers.IO) {
                        writerThread.set(Thread.currentThread())
                        store.save(session, channels.map { it.copy(item = it.item.copy(name = "迟到的旧频道")) })
                    }
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (writerThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(2)
                    assertEquals("旧写入必须已经等在文件事务边界", Thread.State.BLOCKED, writerThread.get()?.state)
                    checkNotNull(writer).cancel()
                    store.store.retainAccounts(emptyList())
                }
                withTimeout(5_000) { checkNotNull(writer).join() }
                assertNull(store.load(session))
                assertTrue("取消的旧写入不能在删账号后重建 JSON 或临时文件", store.files().isEmpty())
            } finally { writer?.cancelAndJoin() }
        }
    }

    private class Store(val directory: java.io.File) {
        val store = LiveTvSnapshotStore(directory)
        suspend fun save(session: Session, channels: List<LiveTvChannel>) = store.save(session, channels)
        suspend fun load(session: Session) = store.load(session)
        fun files() = directory.listFiles().orEmpty().filter { it.isFile }
    }

    private fun withStore(block: (Store) -> Unit) {
        val directory = Files.createTempDirectory("live-tv-snapshots").toFile()
        try { block(Store(directory)) } finally { directory.deleteRecursively() }
    }
}

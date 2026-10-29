package dev.pathfin.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CrashLogTest {
    private fun entry(id: Int, padding: Int = 0) = "${CrashLog.ENTRY_MARK} #$id =====\n" + "x".repeat(padding) + "\n\n"

    @Test fun credentialsAndServerAddressesAreHidden() {
        val raw = """
            java.io.IOException: GET https://media.example.com:8096/Videos/1/stream?api_key=abc123&static=true failed
            Authorization: MediaBrowser Client="Pathfin", Token="secret-token"
            X-Emby-Token: secret2
            password=hunter2
            http://192.168.1.10:8096/Users?access_token=tok&sign=sig
        """.trimIndent()
        val redacted = CrashLog.redact(raw)
        listOf("abc123", "secret-token", "secret2", "hunter2", "=tok", "=sig", "media.example.com", "192.168.1.10").forEach {
            assertFalse("$it 未隐藏：$redacted", redacted.contains(it))
        }
        assertTrue("路径和非敏感参数保留，便于定位", redacted.contains("https://[host]/Videos/1/stream?api_key=[hidden]&static=true"))
        assertTrue(redacted.contains("java.io.IOException"))
    }

    @Test fun stackTracesWithoutSecretsAreUnchanged() {
        val trace = "java.lang.IllegalStateException: boom\n\tat dev.pathfin.android.ui.AppViewModel.load(AppViewModel.kt:42)"
        assertEquals(trace, CrashLog.redact(trace))
    }

    @Test fun downloadKeysJsonCredentialsAndCloudSignaturesAreHidden() {
        val raw = """
            GET https://user:pass@media.example.test/Items/123/Download?ApiKey=download-key&static=true
            {"AccessToken":"login-token","Pw":"login password","NewPassword":"new password"}
            https://cloud.example.test/file?X-Amz-Credential=cloud-credential&X-Amz-Signature=cloud-signature&auth_key=cloud-auth
            Cookie: session=cloud-cookie; other=value
        """.trimIndent()
        val redacted = CrashLog.redact(raw)
        listOf("download-key", "login-token", "login password", "new password", "cloud-credential",
            "cloud-signature", "cloud-auth", "cloud-cookie", "user:pass", "media.example.test", "cloud.example.test").forEach {
            assertFalse("$it 未隐藏：$redacted", redacted.contains(it))
        }
        assertTrue(redacted.contains("ApiKey=[hidden]&static=true"))
    }

    @Test fun networkExceptionAddressesAreHiddenWithoutChangingTheAppVersion() {
        val raw = """
            App: Pathfin 0.1.0 (31)
            java.net.UnknownHostException: Unable to resolve host "media.example.test": No address associated with hostname
            Caused by: java.net.UnknownHostException: fallback.example.test
            java.net.ConnectException: Failed to connect to /192.0.2.10:8096 (port 8096) from /192.0.2.20 (port 12345)
        """.trimIndent()
        val redacted = CrashLog.redact(raw)
        listOf("media.example.test", "fallback.example.test", "192.0.2.10", "192.0.2.20").forEach {
            assertFalse("$it 未隐藏：$redacted", redacted.contains(it))
        }
        assertTrue(redacted.startsWith("App: Pathfin 0.1.0 (31)"))
    }

    @Test fun handlerForwardsTheOriginalCrashAfterRecordingIt() {
        val thread = Thread.currentThread()
        val error = IllegalStateException("original")
        var recorded = false
        var forwarded = false
        val handler = CrashLog.handler(Thread.UncaughtExceptionHandler { forwardedThread, forwardedError ->
            assertTrue("先记录再交给系统处理", recorded)
            assertSame(thread, forwardedThread)
            assertSame(error, forwardedError)
            forwarded = true
        }) { recordedThread, recordedError ->
            assertSame(thread, recordedThread)
            assertSame(error, recordedError)
            recorded = true
        }
        handler.uncaughtException(thread, error)
        assertTrue(forwarded)
    }

    @Test fun failedLogWriteCannotReplaceOrSwallowTheOriginalCrash() {
        val error = IllegalStateException("original")
        var forwarded: Throwable? = null
        val handler = CrashLog.handler(Thread.UncaughtExceptionHandler { _, crash -> forwarded = crash }) { _, _ ->
            throw IOException("storage full")
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, forwarded)
    }

    @Test fun newEntriesAppendWithinTheLimit() {
        assertEquals(entry(1) + entry(2), CrashLog.bounded(entry(1), entry(2)))
        assertEquals(entry(1), CrashLog.bounded("", entry(1)))
    }

    @Test fun oldestWholeEntriesAreDroppedWhenFull() {
        val big = CrashLog.MAX_BYTES / 4
        val log = CrashLog.bounded(entry(1, big) + entry(2, big) + entry(3, big), entry(4, big))
        assertTrue(log.toByteArray().size <= CrashLog.MAX_BYTES)
        assertTrue("最旧记录被整条丢弃", log.startsWith("${CrashLog.ENTRY_MARK} #2"))
        assertTrue("最新记录完整保留", log.endsWith(entry(4, big)))
    }

    @Test fun anOversizedSingleEntryKeepsItsBeginning() {
        val huge = entry(9, CrashLog.MAX_BYTES * 2)
        val log = CrashLog.bounded("", huge)
        assertTrue(log.toByteArray().size <= CrashLog.MAX_BYTES)
        assertTrue("保留异常标题与栈顶", log.startsWith("${CrashLog.ENTRY_MARK} #9"))
        val unicode = "${CrashLog.ENTRY_MARK} unicode =====\n" + "界🙂".repeat(CrashLog.MAX_BYTES)
        assertTrue("以 UTF-8 字节计算上限", CrashLog.bounded("", unicode).toByteArray().size <= CrashLog.MAX_BYTES)
    }

    @Test fun tailStartsAtAnEntryBoundary() {
        val log = entry(1, 100) + entry(2, 100) + entry(3, 10)
        assertEquals(log, CrashLog.tail(log, log.length))
        assertEquals(entry(3, 10), CrashLog.tail(log, entry(3, 10).length + 5))
        val longLast = entry(1, 10) + entry(2, 500)
        val cut = CrashLog.tail(longLast, 100)
        assertEquals(100, cut.length)
        assertTrue("最后一条超长时保留它的开头", cut.startsWith("${CrashLog.ENTRY_MARK} #2"))
    }
}

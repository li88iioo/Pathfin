package dev.pathfin.android

import android.os.NetworkOnMainThreadException
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 对照用例：证实本地 TLS 连接确实能复现用户的系统级错误，避免用普通 HTTP 假覆盖。 */
@RunWith(AndroidJUnit4::class)
class TlsCloseRegressionTest {
    @Test fun idleTlsSocketCannotBeEvictedOnMainThread() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.tlsMedia") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification")
        val client = OkHttpClient()
        try {
            client.newCall(Request.Builder().url("https://127.0.0.1:8098/sample.mp4").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertNotNull(response.handshake)
                assertTrue(response.body.bytes().size > 100_000)
            }
            assertTrue("必须有真实闲置TLS连接", client.connectionPool.idleConnectionCount() > 0)
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous).detectNetwork().penaltyDeathOnNetwork().build())
                try {
                    failure = runCatching { client.connectionPool.evictAll() }.exceptionOrNull()
                } finally {
                    StrictMode.setThreadPolicy(previous)
                }
            }
            assertTrue("原实现必须复现 NetworkOnMainThreadException，实际 ${failure?.javaClass?.simpleName}",
                failure is NetworkOnMainThreadException)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}

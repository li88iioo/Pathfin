package dev.pathfin.android.playback

import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 只接收已经脱离播放会话、独占的媒体客户端。
 * TLS cancel/close 可能发送 close_notify，因此取消请求、关闭连接和 dispatcher 都在后台执行。
 * executor 不属于任何 ViewModel/调用者协程；页面返回或 onCleared 取消不能丢弃已提交的释放。
 */
internal object PlaybackClientCleanup {
    internal enum class Stage { CANCEL_CALLS, EVICT_CONNECTIONS, SHUTDOWN_DISPATCHER }

    // 不持有 Activity/ViewModel。任务串行，空闲后退出线程；旧任务只持有自己的 client 快照。
    private val executor = ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { operation -> Thread(operation, "Pathfin-MediaCleanup").apply { isDaemon = true } },
    )

    /** 返回值用于观察完成/失败；取消观察者不会取消已经排队的资源释放。 */
    fun release(
        client: OkHttpClient,
        onFailure: (Stage, Exception) -> Unit = { stage, error ->
            // 不输出请求、媒体 URL、TLS exception message 或账户内容。
            Log.w("PathfinPlayback", "Media client cleanup failed: ${stage.name} (${error.javaClass.simpleName})")
        },
    ): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        executor.execute {
            var failure: Exception? = null
            fun attempt(stage: Stage, operation: () -> Unit) {
                try {
                    operation()
                } catch (error: Exception) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                    try { onFailure(stage, error) }
                    catch (reportingError: Exception) { failure!!.addSuppressed(reportingError) }
                }
            }
            // 一个步骤失败也要尝试余下释放，失败结果可观察且有脱敏阶段日志。
            attempt(Stage.CANCEL_CALLS) { client.dispatcher.cancelAll() }
            attempt(Stage.EVICT_CONNECTIONS) { client.connectionPool.evictAll() }
            attempt(Stage.SHUTDOWN_DISPATCHER) { client.dispatcher.executorService.shutdown() }
            val error = failure
            if (error == null) completion.complete(Unit) else completion.completeExceptionally(error)
        }
        return completion
    }
}

package dev.pathfin.android

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.*
import dev.pathfin.android.ui.*
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Choreographer 与 Window 帧耗时；不使用 Compose 测试时钟，不读取个人会话。 */
@RunWith(AndroidJUnit4::class)
class NavigationFrameMetricsTest {
    @Test fun recordProductionPagePushAndPop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("pathfin.motionSample") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        SessionStore(context).clear()
        val session = Session(ServerConfig("http://127.0.0.1:8097", allowInsecureHttp = true),
            "fixture-token", "fixture-user", "测试片库", "motion-device", serverId = "pathfin-fixture-server")
        val movies = (1..24).map { index -> MediaItem("preview-movie-${((index - 1) % 8 + 1).toString().padStart(2, '0')}",
            "合成影片 $index", "Movie", year = 2026, imageTag = "ui-preview-v1", communityRating = 8.2,
            overview = "仅用于界面导航采样的合成片库。".repeat(8)) }.take(8)
        val library = MediaItem("preview-library-movies", "测试电影", "CollectionFolder", isFolder = true, imageTag = "ui-preview-v1")
        val home = AppState(session = session, currentProfileId = "motion-profile", theme = ThemePreference.LIGHT,
            home = HomeState(libraries = listOf(library), libraryItems = mapOf(library.id to movies), resume = movies.take(2), loaded = true),
            browsers = mapOf(library.id to BrowserUiState(content = ContentState(items = movies, total = movies.size, nextIndex = movies.size))),
            details = mapOf(movies.first().id to movies.first()))
        val state = mutableStateOf(home)
        val phase = AtomicReference<String?>(null)
        data class Sample(val phase: String, val total: Long, val deadline: Long, val first: Boolean, val dropped: Int)
        val samples = ConcurrentLinkedQueue<Sample>()
        val thread = HandlerThread("PathfinFrameMetrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            phase.get()?.let { current -> samples.add(Sample(current, metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.DEADLINE), metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, dropped)) }
        }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                    activity.setContent { PathfinTheme(ThemePreference.LIGHT) {
                        PathfinApp(vm, state.value, rememberSaveableStateHolder())
                    } }
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
                SystemClock.sleep(1500)
                val routes = listOf(listOf(Route.Home, Route.Folder(library)),
                    listOf(Route.Home, Route.Folder(library), Route.Detail(movies.first().id)),
                    listOf(Route.Home, Route.Folder(library)), listOf(Route.Home))
                // 先完整预热两次路径；后续采样包含页面创建成本，不过滤慢帧。
                repeat(7) { pass ->
                    phase.set(if (pass >= 2) "navigation" else null)
                    routes.forEach { route ->
                        scenario.onActivity { state.value = home.copy(routes = route) }
                        SystemClock.sleep(400)
                    }
                }
                phase.set(null)
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally { thread.quitSafely(); thread.join(2000) }
        val groups = JSONObject()
        samples.groupBy { it.phase }.forEach { (name, records) ->
            val values = records.filter { !it.first && it.total > 0 && it.deadline > 0 }
            assertTrue("$name 必须采集实际窗口动画帧", values.size >= 60)
            val sorted = values.map { it.total / 1_000_000.0 }.sorted()
            fun percentile(f: Double) = sorted[((sorted.size - 1) * f).toInt()]
            groups.put(name, JSONObject().put("frames", values.size)
                .put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("maxMs", sorted.last())
                .put("missedDeadline", values.count { it.total >= it.deadline })
                .put("callbackDrops", records.sumOf { it.dropped }).put("firstDrawFrames", records.count { it.first })
                .put("samples", JSONArray(values.map { JSONObject().put("totalNs", it.total).put("deadlineNs", it.deadline) })))
        }
        assertTrue("必须采集实际页面推进与返回的窗口帧", groups.length() == 1 && groups.has("navigation"))
        File(context.filesDir, "ui-preview").apply { mkdirs() }.resolve("navigation-frame-metrics.json")
            .writeText(JSONObject().put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
                .put("build", "verification-debug").put("clock", "system-choreographer")
                .put("transitions", 20).put("results", groups).toString(2))
    }
}

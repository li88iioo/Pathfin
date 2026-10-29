package dev.pathfin.android

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject

/** 只发送合成帧就绪通知；真实全屏 PNG 由白名单 host 工具采集，设备端不获取截图。 */
internal object ExternalScreenshotCheckpoint {
    private val frameName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val runId = Regex("[a-f0-9]{32}")
    private val completed = mutableSetOf<Pair<String, String>>()

    fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification") {
            "真实预览截图只允许 verification 包"
        }
        check(Looper.myLooper() != Looper.getMainLooper()) { "截图握手不能阻塞界面线程" }
        require(name.length <= 80 && frameName.matches(name)) { "预览帧名无效" }
        val run = InstrumentationRegistry.getArguments().getString("pathfin.screenshotRun").orEmpty()
        check(runId.matches(run)) { "请通过 android/tools/export_ui_previews.py 启动真实屏幕导出" }
        check((run to name) !in completed) { "同一次预览不能重复导出同名帧" }
        val directory = File(instrumentation.targetContext.filesDir, "ui-preview/acks")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建预览握手目录" }
        val acknowledgement = File(directory, name)
        check(!acknowledgement.exists() || acknowledgement.delete()) { "无法清除过期预览确认" }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("pathfin.screenshotReady", JSONObject().put("run", run).put("frame", name).toString())
        })
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (acknowledgement.isFile && acknowledgement.length() == run.length.toLong() &&
                acknowledgement.readText(Charsets.US_ASCII) == run) {
                check(acknowledgement.delete()) { "无法消费预览确认" }
                completed += run to name
                return
            }
            SystemClock.sleep(25)
        }
        error("等待外部真实屏幕截图确认超时：$name")
    }
}

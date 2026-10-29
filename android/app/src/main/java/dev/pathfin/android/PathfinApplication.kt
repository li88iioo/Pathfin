package dev.pathfin.android

import android.app.Application
import dev.pathfin.android.diagnostics.CrashLog
import dev.pathfin.android.download.MediaDownloads

class PathfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        MediaDownloads.restore(this)
    }
}

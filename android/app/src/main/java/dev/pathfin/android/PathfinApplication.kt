package dev.pathfin.android

import android.app.Application
import dev.pathfin.android.diagnostics.CrashLog

class PathfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

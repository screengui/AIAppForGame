package com.gameagent.app

import android.app.Application
import com.gameagent.core.diagnostics.EventCategory

/**
 * Owns the process-wide [AppGraph].
 *
 * The graph is created once here because capture must outlive the activity: MediaProjection is owned
 * by a foreground service, and the accessibility service must be able to reach the same session
 * while no activity is on screen.
 */
class GameAgentApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.eventLog.info(
            EventCategory.SYSTEM,
            "GameAgent started",
            mapOf("version" to appVersionName()),
        )
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}

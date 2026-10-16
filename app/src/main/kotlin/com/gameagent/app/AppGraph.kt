package com.gameagent.app

import android.content.Context
import com.gameagent.app.capture.MediaProjectionCaptureEngine
import com.gameagent.app.capture.ScreenshotStore
import com.gameagent.app.data.SecureKeyStore
import com.gameagent.app.data.SettingsRepository
import com.gameagent.app.runtime.AgentRuntime
import com.gameagent.core.diagnostics.DiagnosticEventLog
import com.gameagent.core.diagnostics.DiagnosticEventSink

/**
 * Hand-rolled dependency graph.
 *
 * Why no DI framework: the graph is nine objects deep, all singleton-scoped, and every one of them
 * needs a `Context` anyway. A compiler-plugin DI framework would add build time, an annotation
 * processor and a generated-code layer to save about thirty lines. Manual wiring also keeps the
 * construction order - which matters here, because the capture engine must exist before the runtime
 * that consumes its frames - explicit and readable.
 *
 * The graph lives in [GameAgentApplication] and therefore has process lifetime, which is required:
 * the foreground capture service and the accessibility service both reach into it, and capture must
 * survive the activity being recreated on rotation.
 */
class AppGraph(context: Context) {

    private val applicationContext: Context = context.applicationContext

    val eventLog = DiagnosticEventLog(capacity = 400)

    val events: DiagnosticEventSink get() = eventLog

    val settingsRepository = SettingsRepository(applicationContext, eventLog)

    val keyStore = SecureKeyStore(applicationContext, eventLog)

    val screenshotStore = ScreenshotStore(applicationContext, eventLog)

    val captureEngine = MediaProjectionCaptureEngine(
        context = applicationContext,
        settingsProvider = { settingsRepository.settings.value },
        events = eventLog,
        previewBuilder = { frame -> MediaProjectionCaptureEngine.defaultPreviewBuilder(frame) },
    )

    val runtime = AgentRuntime(
        context = applicationContext,
        captureEngine = captureEngine,
        settingsRepository = settingsRepository,
        keyStore = keyStore,
        eventLog = eventLog,
        screenshotStore = screenshotStore,
    )
}

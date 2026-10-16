package com.gameagent.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gameagent.app.GameAgentApplication
import com.gameagent.app.ui.theme.GameAgentTheme
import com.gameagent.core.diagnostics.EventCategory

/**
 * Single activity.
 *
 * Two responsibilities, both of which must live in an `Activity` and cannot live in Compose:
 *  - launching the system screen-capture consent dialog (the result must come back through
 *    `registerForActivityResult`), and
 *  - requesting the notification permission that the mandatory capture notification needs on
 *    Android 13+.
 *
 * Everything else is delegated to the process-wide graph so that capture survives rotation and
 * backgrounding.
 */
class MainActivity : ComponentActivity() {

    private val graph get() = (application as GameAgentApplication).graph

    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        graph.runtime.onCaptureConsentResult(result.resultCode, result.data)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            graph.eventLog.warn(
                EventCategory.SYSTEM,
                "notification permission denied; the capture notification may not be visible",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            GameAgentTheme {
                GameAgentApp(
                    graph = graph,
                    onRequestCaptureConsent = { requestCaptureConsent() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The accessibility service can be toggled while the app is backgrounded, so re-read it.
        graph.runtime.refreshAccessibilityStatus()
    }

    private fun requestCaptureConsent() {
        runCatching { captureConsentLauncher.launch(graph.runtime.createCaptureConsentIntent()) }
            .onFailure { error ->
                graph.eventLog.error(
                    EventCategory.CAPTURE,
                    "could not show the screen-capture consent dialog",
                    mapOf("error" to error.toString()),
                )
            }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

package com.gameagent.app.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.gameagent.app.GameAgentApplication
import com.gameagent.app.R
import com.gameagent.app.ui.MainActivity
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory

/**
 * Foreground service that owns the screen projection.
 *
 * Ordering here is load-bearing, not stylistic. Android 14+ requires a foreground service of type
 * `mediaProjection` to be **running and promoted** before `MediaProjectionManager.getMediaProjection`
 * is called; otherwise the platform throws `SecurityException` and capture dies immediately. So the
 * service promotes itself with [startForeground] first and only then asks the engine to start.
 *
 * The service returns `START_NOT_STICKY`: a restarted service would have no valid consent token, and
 * pretending otherwise would silently capture nothing.
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as GameAgentApplication).graph
        when (intent?.action) {
            ACTION_START -> handleStart(intent, graph.events)
            ACTION_STOP -> {
                graph.events.info(EventCategory.CAPTURE, "stop requested from the notification")
                graph.runtime.stopCapture("stopped from the capture notification")
                stopSelf()
            }

            else -> {
                graph.events.warn(EventCategory.CAPTURE, "capture service started without an action")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, events: DiagnosticEventSink) {
        val graph = (application as GameAgentApplication).graph
        // Step 1: become a foreground service of the right type. Must happen before getMediaProjection.
        promoteToForeground()

        // Step 2: validate the consent result the activity handed us.
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            events.warn(
                EventCategory.CAPTURE,
                "screen capture consent was not granted",
                mapOf("resultCode" to resultCode.toString()),
            )
            // Routed through the same entry point the activity uses, so a denial reported here and a
            // denial reported there end in exactly the same session state.
            graph.runtime.onCaptureConsentResult(Activity.RESULT_CANCELED, null)
            stopSelf()
            return
        }

        // Step 3: start capture. The engine owns every capture resource and publishes state.
        val started = graph.captureEngine.start(resultCode, resultData)
        if (!started) {
            events.error(EventCategory.CAPTURE, "capture did not start; stopping the service")
            stopSelf()
        }
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        // No SDK_INT guard: notification channels exist from API 26 and minSdk is 26.
        if (manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_capture_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopCapture = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_action_stop), stopCapture)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        val graph = (application as GameAgentApplication).graph
        // Safety net: if the service dies for any reason, capture resources must not leak.
        if (graph.captureEngine.state.value.isLive) {
            graph.events.warn(EventCategory.CAPTURE, "capture service destroyed while capture was live")
            graph.runtime.stopCapture("the capture service was destroyed")
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gameagent.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.gameagent.app.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "com.gameagent.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.gameagent.app.extra.RESULT_DATA"

        const val CHANNEL_ID = "gameagent.capture"
        const val NOTIFICATION_ID = 42

        /** Starts capture with an approved consent result. Must be called from the foreground. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /** Stops the service if it is running. Safe to call when it is not. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

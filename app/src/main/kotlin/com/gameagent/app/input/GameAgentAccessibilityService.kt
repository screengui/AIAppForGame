package com.gameagent.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Gesture-injection service.
 *
 * What this service is:
 *  - a transparent, user-enabled Android AccessibilityService whose only capability is
 *    `canPerformGestures`;
 *  - the one supported way for a normal, non-rooted app to deliver touches to another app;
 *  - always visible in Android's accessibility settings, and removable there at any time.
 *
 * What this service deliberately is **not**:
 *  - it does not read window content or view hierarchies (`canRetrieveWindowContent="false"`), so it
 *    cannot scrape text, credentials or messages;
 *  - it does not hide itself, evade any app's anti-cheat, or use hidden/system APIs;
 *  - it does not act on its own: the only gestures it dispatches are actions that already passed
 *    [com.gameagent.core.validation.ActionValidator] and arrived from the companion app.
 */
class GameAgentAccessibilityService : AccessibilityService() {

    private val gestureHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
        mutableConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally ignored: perception works from captured pixels, not from accessibility events.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        instance.compareAndSet(this, null)
        mutableConnected.value = false
    }

    /** @return false when the platform refused the gesture outright. */
    fun dispatch(gesture: GestureDescription, callback: GestureResultCallback): Boolean =
        dispatchGesture(gesture, callback, gestureHandler)

    companion object {
        private val instance = AtomicReference<GameAgentAccessibilityService?>(null)

        private val mutableConnected = MutableStateFlow(false)

        /** True while the service is bound, i.e. while the user has it enabled. */
        val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

        fun current(): GameAgentAccessibilityService? = instance.get()

        /**
         * Reads the system's enabled-services list so the UI can distinguish "never enabled" from
         * "enabled but not connected yet". The app never enables anything by itself.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${GameAgentAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (entry.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}

package com.gameagent.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.input.ControllerCapabilities
import com.gameagent.core.input.ExecutionOutcome
import com.gameagent.core.input.InputController
import com.gameagent.core.model.ValidatedAction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Delivers validated actions to other apps through [GameAgentAccessibilityService].
 *
 * Documented capabilities and limits (see docs/INPUT_MECHANISMS.md for the full comparison):
 *  - **Reaches other apps** - this is the only supported mechanism for that on a non-rooted device,
 *    and it requires the user to enable the service in Android Settings.
 *  - **Single pointer only** in this implementation. Android's gesture API can describe up to 20
 *    overlapping strokes (i.e. multi-touch), but whether a target app interprets a synthesised
 *    multi-pointer gesture the way a human one is not something that can be promised, so it is not
 *    attempted here.
 *  - **No standalone RELEASE.** A `HOLD` is delivered as one completed long-press stroke. Releasing a
 *    pointer that is still down requires continuing a stroke from a previous gesture, whose
 *    behaviour varies by target app; [GesturePlanner] refuses it instead of guessing.
 *  - **No root, no hidden APIs, no anti-cheat evasion.**
 */
class AccessibilityInputController(
    private val displaySizeProvider: () -> Pair<Int, Int>,
    private val events: DiagnosticEventSink,
    private val gestureTimeoutMs: Long = DEFAULT_GESTURE_TIMEOUT_MS,
) : InputController {

    override val id: String = "accessibility-gesture"

    override val displayName: String = "Accessibility gesture injection"

    override val capabilities: ControllerCapabilities = ControllerCapabilities(
        mechanism = "accessibility-gesture",
        targetScope = "other apps' screens, when the user has enabled the service",
        supportsTap = true,
        supportsSwipe = true,
        supportsLongPress = true,
        supportsHoldRelease = false,
        supportsMultitouch = false,
        targetsOtherApps = true,
        requiresUserEnablement = true,
        notes = listOf(
            "Requires the user to enable 'GameAgent input controller' in Android accessibility settings.",
            "Android caps a single gesture at 20 strokes and 60 s total duration; both are enforced before dispatch.",
            "Single pointer only. Multi-stroke (multi-touch) gestures are expressible but not reliable across apps, so they are not produced.",
            "No standalone RELEASE: a HOLD is one completed long-press stroke.",
            "No root, no hidden APIs, and no attempt to evade any app's detection. Ranked matches are out of scope.",
        ),
    )

    override fun isAvailable(): Boolean = GameAgentAccessibilityService.current() != null

    override suspend fun execute(action: ValidatedAction): ExecutionOutcome {
        val service = GameAgentAccessibilityService.current()
            ?: return ExecutionOutcome.Unsupported(
                "the accessibility service is not connected; enable it in Android accessibility settings",
            )

        val (width, height) = displaySizeProvider()
        val plan = GesturePlanner.plan(action, width, height).getOrElse { failure ->
            return when (failure) {
                is GesturePlanException -> ExecutionOutcome.Refused(failure.code, failure.message ?: "gesture rejected")
                else -> ExecutionOutcome.Failed(failure.message ?: "gesture could not be planned")
            }
        }

        val gesture = plan.toGestureDescription()
            ?: return ExecutionOutcome.Failed("the gesture description could not be built")

        val outcome = withTimeoutOrNull(gestureTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(ExecutionOutcome.Completed)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(ExecutionOutcome.Cancelled)
                    }
                }
                val accepted = try {
                    service.dispatch(gesture, callback)
                } catch (t: Throwable) {
                    false
                }
                if (!accepted && continuation.isActive) {
                    continuation.resume(
                        ExecutionOutcome.Failed("the platform refused the gesture (it was probably over a limit)"),
                    )
                }
            }
        }

        val resolved = outcome ?: run {
            events.warn(
                EventCategory.INPUT,
                "gesture timed out",
                mapOf("timeoutMs" to gestureTimeoutMs.toString(), "action" to action.actionType.name),
            )
            ExecutionOutcome.Failed("gesture did not complete within ${gestureTimeoutMs}ms")
        }

        events.debug(
            EventCategory.INPUT,
            "gesture dispatched",
            mapOf(
                "action" to action.actionType.name,
                "strokes" to plan.strokes.size.toString(),
                "durationMs" to plan.totalDurationMs.toString(),
                "outcome" to resolved.toString(),
            ),
        )
        return resolved
    }

    override suspend fun cancelActive(): Boolean {
        // `execute` only returns once the platform reports completion or cancellation, so no gesture
        // of ours is ever left in flight. Android also cancels any in-progress gesture (including a
        // real user's) when a new one is dispatched, which is not a capability this app uses.
        return false
    }

    override fun release() {
        // Nothing is held between calls; see cancelActive.
    }

    private fun GesturePlan.toGestureDescription(): GestureDescription? = try {
        val builder = GestureDescription.Builder()
        strokes.forEach { stroke ->
            val path = Path().apply {
                moveTo(stroke.startX.toFloat(), stroke.startY.toFloat())
                if (!stroke.isTap) lineTo(stroke.endX.toFloat(), stroke.endY.toFloat())
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    stroke.durationMs.coerceAtLeast(1L),
                    false,
                ),
            )
        }
        builder.build()
    } catch (t: Throwable) {
        events.error(EventCategory.INPUT, "could not build the gesture", mapOf("error" to t.toString()))
        null
    }

    companion object {
        /** Generous but finite: a stuck gesture must never wedge the pipeline. */
        const val DEFAULT_GESTURE_TIMEOUT_MS = 4_000L
    }
}

package com.gameagent.core.input

import com.gameagent.core.model.RejectionCode
import com.gameagent.core.model.ValidatedAction

/**
 * What a controller can really do on the current device.
 *
 * This is deliberately verbose: Android has no general-purpose touch-injection API for third-party
 * apps, so the honest answer to "can this app drive another app?" depends entirely on which
 * mechanism is active and whether the user enabled it.
 */
data class ControllerCapabilities(
    /** Short machine id, e.g. `accessibility-gesture`. */
    val mechanism: String,
    /** Human summary of what the controller can reach. */
    val targetScope: String,
    val supportsTap: Boolean = false,
    val supportsSwipe: Boolean = false,
    val supportsLongPress: Boolean = false,
    val supportsHoldRelease: Boolean = false,
    val supportsMultitouch: Boolean = false,
    val targetsOtherApps: Boolean = false,
    val requiresUserEnablement: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    fun supports(actionType: com.gameagent.core.model.ActionType): Boolean = when (actionType) {
        com.gameagent.core.model.ActionType.WAIT,
        com.gameagent.core.model.ActionType.STOP,
        -> true

        com.gameagent.core.model.ActionType.TAP -> supportsTap
        com.gameagent.core.model.ActionType.SWIPE -> supportsSwipe
        com.gameagent.core.model.ActionType.HOLD -> supportsLongPress
        com.gameagent.core.model.ActionType.RELEASE -> supportsHoldRelease
    }

    companion object {
        val NONE = ControllerCapabilities(
            mechanism = "none",
            targetScope = "no controller attached",
        )
    }
}

sealed interface ExecutionOutcome {
    /** The controller delivered the action and the platform reported success. */
    data object Completed : ExecutionOutcome

    /** A previously started gesture was cancelled, or this action was cancelled mid-flight. */
    data object Cancelled : ExecutionOutcome

    data class Failed(val reason: String) : ExecutionOutcome

    /** The controller exists but cannot perform this action on this device. */
    data class Unsupported(val detail: String) : ExecutionOutcome

    /** The controller itself refused the action (defence in depth behind the validator). */
    data class Refused(val code: RejectionCode, val detail: String) : ExecutionOutcome
}

/**
 * Executes validated actions.
 *
 * Implementations must never execute anything that did not arrive as a [ValidatedAction], and must
 * return promptly when [cancelActive] is called so an emergency stop cannot be blocked by a gesture
 * that is already in flight.
 */
interface InputController {
    val id: String
    val displayName: String
    val capabilities: ControllerCapabilities

    /** False when the mechanism is missing or the user has not enabled it. */
    fun isAvailable(): Boolean

    suspend fun execute(action: ValidatedAction): ExecutionOutcome

    /** Releases/cancels anything in flight. Returns true if something was actually cancelled. */
    suspend fun cancelActive(): Boolean = false

    /** Called when the session ends. Must be idempotent. */
    fun release() = Unit
}

/** Present when no controller is available, so the app never silently degrades to a stub. */
class UnavailableInputController(
    override val id: String = "unavailable",
    override val displayName: String = "No input controller",
    private val reason: String = "no input mechanism is active",
) : InputController {
    override val capabilities: ControllerCapabilities = ControllerCapabilities.NONE

    override fun isAvailable(): Boolean = false

    override suspend fun execute(action: ValidatedAction): ExecutionOutcome = ExecutionOutcome.Unsupported(reason)
}

/**
 * Records actions in memory instead of injecting them.
 *
 * Used by unit tests and by the "dry run" mode: it proves the pipeline up to the controller without
 * touching the screen. It intentionally reports [ControllerCapabilities.targetsOtherApps] = false.
 */
class RecordingInputController(
    override val id: String = "recording",
    override val displayName: String = "Recording (dry run)",
    private val failWith: String? = null,
) : InputController {

    private val lock = Any()
    private val executed = mutableListOf<ValidatedAction>()
    private var cancellations = 0

    override val capabilities: ControllerCapabilities = ControllerCapabilities(
        mechanism = "recording",
        targetScope = "no device input is produced",
        supportsTap = true,
        supportsSwipe = true,
        supportsLongPress = true,
        supportsHoldRelease = true,
        supportsMultitouch = false,
        targetsOtherApps = false,
        requiresUserEnablement = false,
        notes = listOf("Records actions for verification; never touches the screen."),
    )

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: ValidatedAction): ExecutionOutcome {
        if (failWith != null) return ExecutionOutcome.Failed(failWith)
        synchronized(lock) { executed += action }
        return ExecutionOutcome.Completed
    }

    override suspend fun cancelActive(): Boolean {
        synchronized(lock) { cancellations++ }
        return true
    }

    fun recorded(): List<ValidatedAction> = synchronized(lock) { executed.toList() }

    fun cancellationCount(): Int = synchronized(lock) { cancellations }

    fun clear() = synchronized(lock) { executed.clear() }
}

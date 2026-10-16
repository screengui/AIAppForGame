package com.gameagent.core.validation

import com.gameagent.core.geometry.CoordinateMapper
import com.gameagent.core.geometry.PixelPoint
import com.gameagent.core.input.ControllerCapabilities
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.RejectionCode
import com.gameagent.core.model.SessionState
import com.gameagent.core.model.ValidatedAction

/**
 * Everything the validator needs to judge one decision. Assembled fresh per decision so no stale
 * value can be reused by accident.
 */
data class MappingFailure(val code: RejectionCode, val detail: String)

data class ValidationContext(
    val sessionState: SessionState,
    val emergencyStopEngaged: Boolean,
    val startPoint: PixelPoint?,
    val endPoint: PixelPoint?,
    /** Age of the geometry snapshot used for the mapping, or null when none was available. */
    val geometryAgeMs: Long?,
    /**
     * Why the coordinate mapping failed, when it did. Propagated from the mapper so the reported
     * rejection code is the real one (out of bounds vs. stale) instead of a generic guess.
     */
    val mappingFailure: MappingFailure? = null,
    val displayWidth: Int,
    val displayHeight: Int,
    val capabilities: ControllerCapabilities?,
    val nowMs: Long,
)

sealed interface ActionValidation {
    /** The action may be executed. */
    data class Accepted(val action: ValidatedAction) : ActionValidation

    /** Nothing to execute (WAIT, or a STOP the session handles itself). Not an error. */
    data class NoOp(val reason: String) : ActionValidation

    data class Rejected(val code: RejectionCode, val detail: String) : ActionValidation
}

/** Tunables for the safety gate. All limits are enforced on the device, per session. */
data class ValidationConfig(
    /** Master switch. Defaults to OFF: input stays disabled until the user enables it. */
    val actionsEnabled: Boolean = false,
    val allowedActionTypes: Set<ActionType> = setOf(
        ActionType.WAIT,
        ActionType.TAP,
        ActionType.SWIPE,
        ActionType.HOLD,
        ActionType.RELEASE,
        ActionType.STOP,
    ),
    val minGestureDurationMs: Long = 40L,
    val maxGestureDurationMs: Long = 2_000L,
    val minIntervalBetweenActionsMs: Long = 150L,
    val maxActionsPerSession: Int = 300,
    val minConfidence: Float = 0.45f,
    val maxGeometryAgeMs: Long = CoordinateMapper.DEFAULT_MAX_GEOMETRY_AGE_MS,
    val requireRunningState: Boolean = true,
) {
    init {
        require(minGestureDurationMs >= 1L) { "minGestureDurationMs must be >= 1" }
        require(maxGestureDurationMs >= minGestureDurationMs) { "maxGestureDurationMs must be >= min" }
        require(minIntervalBetweenActionsMs >= 0L) { "minIntervalBetweenActionsMs must be >= 0" }
        require(maxActionsPerSession >= 0) { "maxActionsPerSession must be >= 0" }
        require(minConfidence in 0f..1f) { "minConfidence must be in 0..1" }
        require(maxGeometryAgeMs > 0L) { "maxGeometryAgeMs must be > 0" }
    }
}

/** Snapshot for the dashboard's validation panel. */
data class ValidatorStats(
    val actionsEnabled: Boolean = false,
    val effectorActions: Int = 0,
    val maxActionsPerSession: Int = 0,
    val rejected: Long = 0L,
    val lastRejectionCode: RejectionCode? = null,
    val lastRejectionDetail: String? = null,
    val lastAcceptedAtMs: Long = 0L,
)

/**
 * The single safety gate between policy and input.
 *
 * Ordering matters and is tested: the cheapest and most safety-critical checks come first so a
 * panic stop or a disabled switch always wins over any other consideration.
 */
class ActionValidator(
    initialConfig: ValidationConfig = ValidationConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var config: ValidationConfig = initialConfig
        private set

    private val lock = Any()
    private var nextActionId = 1L
    private var effectorActions = 0
    private var lastEffectorAtMs = 0L
    private var rejected = 0L
    private var lastRejectionCode: RejectionCode? = null
    private var lastRejectionDetail: String? = null
    private var lastAcceptedAtMs = 0L

    fun updateConfig(newConfig: ValidationConfig) {
        config = newConfig
    }

    /** Called when a new session starts, and when the user resets counters. */
    fun resetSession() = synchronized(lock) {
        effectorActions = 0
        lastEffectorAtMs = 0L
        rejected = 0L
        lastRejectionCode = null
        lastRejectionDetail = null
        lastAcceptedAtMs = 0L
    }

    fun stats(): ValidatorStats = synchronized(lock) {
        ValidatorStats(
            actionsEnabled = config.actionsEnabled,
            effectorActions = effectorActions,
            maxActionsPerSession = config.maxActionsPerSession,
            rejected = rejected,
            lastRejectionCode = lastRejectionCode,
            lastRejectionDetail = lastRejectionDetail,
            lastAcceptedAtMs = lastAcceptedAtMs,
        )
    }

    fun remainingActionBudget(): Int = synchronized(lock) {
        (config.maxActionsPerSession - effectorActions).coerceAtLeast(0)
    }

    fun validate(decision: AgentDecision, context: ValidationContext): ActionValidation {
        val activeConfig = config

        // 1. Type-level checks.
        if (decision.actionType !in activeConfig.allowedActionTypes) {
            return reject(RejectionCode.INVALID_ACTION_TYPE, "${decision.actionType} is not an allowed action type")
        }
        // 2. A non-pointer action must not smuggle coordinates through.
        if (!decision.actionType.requiresPoint && (decision.point != null || decision.endPoint != null)) {
            return reject(
                RejectionCode.MALFORMED_DECISION,
                "${decision.actionType} must not carry coordinates",
            )
        }

        // 3. Actions that produce no input are accepted as no-ops (they consume no budget).
        if (decision.actionType == ActionType.STOP) {
            return ActionValidation.NoOp("STOP is handled by the session manager, not by the input controller")
        }
        if (decision.actionType == ActionType.WAIT) {
            return ActionValidation.NoOp("policy chose to wait")
        }

        // 4. Kill switch and master switch - these must win over everything else.
        if (context.emergencyStopEngaged) {
            return reject(RejectionCode.EMERGENCY_STOP, "emergency stop is engaged; no input is allowed")
        }
        if (!activeConfig.actionsEnabled) {
            return reject(RejectionCode.ACTIONS_DISABLED, "input is disabled in settings")
        }
        if (activeConfig.requireRunningState && !context.sessionState.allowsInput) {
            return reject(
                RejectionCode.SESSION_NOT_RUNNING,
                "session state is ${context.sessionState}; input requires an active session",
            )
        }

        // 5. Capability checks.
        val capabilities = context.capabilities
            ?: return reject(RejectionCode.NO_CONTROLLER, "no input controller is attached")
        if (!capabilities.supports(decision.actionType)) {
            return reject(
                RejectionCode.UNSUPPORTED_BY_CONTROLLER,
                "controller '${capabilities.mechanism}' cannot perform ${decision.actionType}",
            )
        }

        // 6. Coordinate mapping. The mapper refuses to produce a pixel for an out-of-range point or
        //    a stale snapshot; surface its own reason rather than a generic one.
        context.mappingFailure?.let { failure ->
            return reject(failure.code, "coordinate mapping failed: ${failure.detail}")
        }

        // 7. Geometry freshness. A mapping built from a stale frame must never be aimed at the
        //    screen, so this is checked before anything is aimed.
        val geometryAge = context.geometryAgeMs
            ?: return reject(RejectionCode.STALE_GEOMETRY, "no screen geometry is available")
        if (geometryAge > activeConfig.maxGeometryAgeMs) {
            return reject(
                RejectionCode.STALE_GEOMETRY,
                "geometry is ${geometryAge}ms old, limit is ${activeConfig.maxGeometryAgeMs}ms",
            )
        }

        // 8. Confidence and well-formedness.
        if (decision.confidence < activeConfig.minConfidence) {
            return reject(
                RejectionCode.LOW_CONFIDENCE,
                "confidence %.2f is below the required %.2f".format(decision.confidence, activeConfig.minConfidence),
            )
        }
        if (decision.actionType.requiresPoint) {
            if (decision.point == null) {
                return reject(RejectionCode.MISSING_COORDINATES, "${decision.actionType} requires a normalized point")
            }
            if (context.startPoint == null) {
                return reject(
                    RejectionCode.MISSING_COORDINATES,
                    "normalized point could not be mapped to the display",
                )
            }
        }
        if (decision.actionType == ActionType.SWIPE && decision.endPoint == null) {
            return reject(RejectionCode.MISSING_COORDINATES, "SWIPE requires an end point")
        }
        if (decision.actionType == ActionType.SWIPE && context.endPoint == null) {
            return reject(RejectionCode.MISSING_COORDINATES, "swipe end point could not be mapped to the display")
        }
        // 9. Coordinate bounds.
        context.startPoint?.let { point ->
            if (point.x !in 0 until context.displayWidth || point.y !in 0 until context.displayHeight) {
                return reject(
                    RejectionCode.OUT_OF_BOUNDS,
                    "point (${point.x}, ${point.y}) is outside the ${context.displayWidth}x${context.displayHeight} display",
                )
            }
        }
        context.endPoint?.let { point ->
            if (point.x !in 0 until context.displayWidth || point.y !in 0 until context.displayHeight) {
                return reject(
                    RejectionCode.OUT_OF_BOUNDS,
                    "end point (${point.x}, ${point.y}) is outside the ${context.displayWidth}x${context.displayHeight} display",
                )
            }
        }

        // 10. Gesture duration for the continuous actions.
        val needsDuration = decision.actionType == ActionType.SWIPE || decision.actionType == ActionType.HOLD
        if (needsDuration) {
            val duration = decision.durationMs
                ?: return reject(
                    RejectionCode.MALFORMED_DECISION,
                    "${decision.actionType} requires an explicit duration",
                )
            if (duration < activeConfig.minGestureDurationMs) {
                return reject(
                    RejectionCode.DURATION_TOO_SHORT,
                    "duration ${duration}ms is below the minimum ${activeConfig.minGestureDurationMs}ms",
                )
            }
            if (duration > activeConfig.maxGestureDurationMs) {
                return reject(
                    RejectionCode.DURATION_TOO_LONG,
                    "duration ${duration}ms exceeds the maximum ${activeConfig.maxGestureDurationMs}ms",
                )
            }
        }

        // 11. Rate limiting and the per-session budget.
        return synchronized(lock) {
            if (effectorActions >= activeConfig.maxActionsPerSession) {
                return@synchronized reject(
                    RejectionCode.SESSION_ACTION_LIMIT,
                    "session action budget of ${activeConfig.maxActionsPerSession} is exhausted; restart the session",
                )
            }
            if (lastEffectorAtMs != 0L) {
                val sinceLast = context.nowMs - lastEffectorAtMs
                if (sinceLast < activeConfig.minIntervalBetweenActionsMs) {
                    return@synchronized reject(
                        RejectionCode.RATE_LIMITED,
                        "only ${sinceLast}ms since the previous action; minimum interval is ${activeConfig.minIntervalBetweenActionsMs}ms",
                    )
                }
            }
            effectorActions++
            lastEffectorAtMs = context.nowMs
            lastAcceptedAtMs = context.nowMs
            ActionValidation.Accepted(
                ValidatedAction(
                    id = nextActionId++,
                    decisionId = decision.id,
                    actionType = decision.actionType,
                    start = context.startPoint,
                    end = if (decision.actionType == ActionType.SWIPE) context.endPoint else null,
                    durationMs = decision.durationMs,
                    validatedAtMs = context.nowMs,
                    provenance = decision.source,
                ),
            )
        }
    }

    /**
     * Called when the controller reports that it did **not** deliver an action, so the budget is
     * not consumed by an action that never happened.
     */
    fun refundAction() = synchronized(lock) {
        if (effectorActions > 0) effectorActions--
    }

    private fun reject(code: RejectionCode, detail: String): ActionValidation {
        synchronized(lock) {
            rejected++
            lastRejectionCode = code
            lastRejectionDetail = detail
        }
        return ActionValidation.Rejected(code, detail)
    }

    /** Convenience for callers that only need the timestamp. */
    fun now(): Long = clock()
}

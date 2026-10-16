package com.gameagent.core.model

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.NormalizedRect

/**
 * The only actions the companion can produce.
 *
 * Deliberately small: every action type maps onto something a supported controller can really do.
 * Movement/skill abstractions are expressed as [TAP]/[SWIPE]/[HOLD] on a normalized point rather
 * than as game-specific verbs, so the policy layer cannot invent capabilities the platform lacks.
 */
enum class ActionType {
    /** Do nothing this cycle. Always safe, always available. */
    WAIT,

    /** Single short press at a point. */
    TAP,

    /** Press, drag, release. */
    SWIPE,

    /** Press and hold without releasing. */
    HOLD,

    /** Release a previously held pointer. */
    RELEASE,

    /** Explicit, unconditional stop requested by the policy layer (not the user's panic button). */
    STOP,
    ;

    val requiresPoint: Boolean get() = this == TAP || this == SWIPE || this == HOLD || this == RELEASE
}

/** What a decision refers to, expressed in perception vocabulary rather than raw pixels. */
data class TargetRef(
    val kind: EntityKind,
    val label: String? = null,
    val bounds: NormalizedRect? = null,
)

/**
 * A proposed action plus the reasoning behind it.
 *
 * [validation] starts as [ValidationStatus.Pending] and is set only by the validator, so the UI
 * can always distinguish "proposed" from "accepted" and "rejected". A decision is data, never code:
 * nothing in this model can execute anything by itself.
 */
data class AgentDecision(
    val id: Long,
    val actionType: ActionType,
    val target: TargetRef? = null,
    val point: NormalizedPoint? = null,
    val endPoint: NormalizedPoint? = null,
    val durationMs: Long? = null,
    val confidence: Float,
    val explanation: String,
    val createdAtMs: Long,
    val source: String,
    val validation: ValidationStatus = ValidationStatus.Pending,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in 0..1, was $confidence" }
        require(explanation.isNotBlank()) { "every decision must explain itself" }
    }

    /** Human-readable one-liner for the dashboard. */
    fun summary(): String = buildString {
        append(actionType.name)
        point?.let { append(" @(%.3f, %.3f)".format(it.x, it.y)) }
        endPoint?.let { append(" -> (%.3f, %.3f)".format(it.x, it.y)) }
        durationMs?.let { append(" ${it}ms") }
        append(" [%.0f%%]".format(confidence * 100f))
    }

    fun withValidation(status: ValidationStatus): AgentDecision = copy(validation = status)

    companion object {
        /** The canonical safe decision. Used as the fallback everywhere. */
        fun wait(
            id: Long,
            createdAtMs: Long,
            source: String,
            reason: String,
            confidence: Float = 1f,
        ) = AgentDecision(
            id = id,
            actionType = ActionType.WAIT,
            confidence = confidence,
            explanation = reason,
            createdAtMs = createdAtMs,
            source = source,
        )

        fun stop(
            id: Long,
            createdAtMs: Long,
            source: String,
            reason: String,
        ) = AgentDecision(
            id = id,
            actionType = ActionType.STOP,
            confidence = 1f,
            explanation = reason,
            createdAtMs = createdAtMs,
            source = source,
        )
    }
}

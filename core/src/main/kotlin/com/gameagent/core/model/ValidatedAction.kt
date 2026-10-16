package com.gameagent.core.model

import com.gameagent.core.geometry.PixelPoint

/**
 * An action that has already passed [com.gameagent.core.validation.ActionValidator].
 *
 * This is the *only* type an input controller accepts, so no code path can inject input from a raw
 * model decision. Coordinates are absolute display pixels because they were produced by
 * [com.gameagent.core.geometry.CoordinateMapper] from a geometry snapshot that was fresh at
 * validation time.
 */
data class ValidatedAction(
    val id: Long,
    val decisionId: Long,
    val actionType: ActionType,
    val start: PixelPoint? = null,
    val end: PixelPoint? = null,
    val durationMs: Long? = null,
    val validatedAtMs: Long,
    /** Where the decision came from, e.g. "deterministic-policy" or "ai:gpt-vision". */
    val provenance: String,
) {
    val isContinuous: Boolean get() = actionType == ActionType.HOLD

    init {
        require(actionType.requiresPoint || (start == null && end == null)) {
            "non-pointer action $actionType must not carry coordinates"
        }
    }
}

package com.gameagent.app.input

import com.gameagent.core.model.ActionType
import com.gameagent.core.model.RejectionCode
import com.gameagent.core.model.ValidatedAction

/** A stroke of a gesture, in absolute display pixels. A tap is a stroke whose start equals its end. */
data class GestureStroke(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
) {
    val isTap: Boolean get() = startX == endX && startY == endY
}

data class GesturePlan(val strokes: List<GestureStroke>) {
    /** Longest stroke; for the single-stroke gestures produced here this is also the total span. */
    val totalDurationMs: Long get() = strokes.maxOfOrNull { it.durationMs } ?: 0L
}

class GesturePlanException(val code: RejectionCode, message: String) : Exception(message)

/**
 * Turns a validated action into a platform gesture description.
 *
 * The Android gesture API has hard limits that the platform enforces by silently failing, which
 * would look like "the app does nothing":
 *  - at most 20 strokes per gesture,
 *  - at most 60 s total gesture duration.
 * Both are checked here, before anything is dispatched, so an over-large gesture is rejected with a
 * reason instead of vanishing.
 *
 * Pure Kotlin (no Android types) so the arithmetic and the limit checks are unit tested.
 */
object GesturePlanner {

    /** Shortest stroke Android will reliably deliver as a tap. */
    const val DEFAULT_TAP_DURATION_MS = 60L

    /** A tap should not turn into a press. */
    const val MAX_TAP_DURATION_MS = 200L

    const val MAX_STROKES = 20
    const val MAX_GESTURE_DURATION_MS = 60_000L

    fun plan(action: ValidatedAction, displayWidth: Int, displayHeight: Int): Result<GesturePlan> {
        // Classified before the coordinate checks on purpose: WAIT carries no position by design, so
        // reporting MISSING_COORDINATES for it would describe a bug that is not there. The caller
        // gets the actual reason instead - this action is simply not a gesture.
        when (action.actionType) {
            ActionType.RELEASE -> return Result.failure(
                GesturePlanException(
                    RejectionCode.UNSUPPORTED_BY_CONTROLLER,
                    "RELEASE is not supported by the accessibility controller; a hold is a single long press",
                ),
            )

            ActionType.WAIT -> return Result.failure(
                GesturePlanException(RejectionCode.UNSUPPORTED_ACTION, "WAIT does not produce a gesture"),
            )

            ActionType.STOP -> return Result.failure(
                GesturePlanException(RejectionCode.UNSUPPORTED_ACTION, "STOP does not produce a gesture"),
            )

            ActionType.TAP,
            ActionType.SWIPE,
            ActionType.HOLD,
            -> Unit
        }

        if (displayWidth <= 0 || displayHeight <= 0) {
            return Result.failure(
                GesturePlanException(RejectionCode.STALE_GEOMETRY, "display size is ${displayWidth}x$displayHeight"),
            )
        }
        val start = action.start
        if (start == null) {
            return Result.failure(
                GesturePlanException(RejectionCode.MISSING_COORDINATES, "${action.actionType} has no coordinates"),
            )
        }
        if (!inBounds(start.x, start.y, displayWidth, displayHeight)) {
            return Result.failure(
                GesturePlanException(
                    RejectionCode.OUT_OF_BOUNDS,
                    "start (${start.x}, ${start.y}) is outside ${displayWidth}x$displayHeight",
                ),
            )
        }

        return when (action.actionType) {
            ActionType.TAP -> {
                val duration = DEFAULT_TAP_DURATION_MS.coerceAtMost(MAX_TAP_DURATION_MS)
                success(GestureStroke(start.x, start.y, start.x, start.y, duration))
            }

            ActionType.HOLD -> {
                val duration = (action.durationMs ?: DEFAULT_TAP_DURATION_MS).coerceIn(1L, MAX_GESTURE_DURATION_MS)
                success(GestureStroke(start.x, start.y, start.x, start.y, duration))
            }

            ActionType.SWIPE -> {
                val end = action.end
                    ?: return Result.failure(
                        GesturePlanException(RejectionCode.MISSING_COORDINATES, "SWIPE has no end point"),
                    )
                if (!inBounds(end.x, end.y, displayWidth, displayHeight)) {
                    return Result.failure(
                        GesturePlanException(
                            RejectionCode.OUT_OF_BOUNDS,
                            "end (${end.x}, ${end.y}) is outside ${displayWidth}x$displayHeight",
                        ),
                    )
                }
                val duration = (action.durationMs ?: DEFAULT_TAP_DURATION_MS).coerceIn(1L, MAX_GESTURE_DURATION_MS)
                success(GestureStroke(start.x, start.y, end.x, end.y, duration))
            }

            // Unreachable: rejected above, before the coordinate checks. Listed so the `when` stays
            // exhaustive if a new action type is added to the core model.
            ActionType.RELEASE,
            ActionType.WAIT,
            ActionType.STOP,
            -> Result.failure(
                GesturePlanException(RejectionCode.UNSUPPORTED_ACTION, "${action.actionType} does not produce a gesture"),
            )
        }
    }

    private fun success(vararg strokes: GestureStroke): Result<GesturePlan> {
        val plan = GesturePlan(strokes.toList())
        if (plan.strokes.size > MAX_STROKES) {
            return Result.failure(
                GesturePlanException(
                    RejectionCode.UNSUPPORTED_ACTION,
                    "gesture has ${plan.strokes.size} strokes, the platform allows $MAX_STROKES",
                ),
            )
        }
        if (plan.totalDurationMs > MAX_GESTURE_DURATION_MS) {
            return Result.failure(
                GesturePlanException(
                    RejectionCode.DURATION_TOO_LONG,
                    "gesture lasts ${plan.totalDurationMs}ms, the platform allows $MAX_GESTURE_DURATION_MS ms",
                ),
            )
        }
        return Result.success(plan)
    }

    private fun inBounds(x: Int, y: Int, width: Int, height: Int): Boolean =
        x in 0 until width && y in 0 until height
}

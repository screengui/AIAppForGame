package com.gameagent.core.model

/** Broad phase of the observed screen content. */
enum class GamePhase {
    /** A game menu, lobby or any non-match UI. */
    MENU,

    /** A loading screen. */
    LOADING,

    /** A match is being played. */
    IN_MATCH,

    /** The player is dead / respawning. */
    DEATH,

    /** Nothing could be determined. This is the safe default and must never be faked. */
    UNKNOWN,
}

/** Kind of entity or control that can be observed on screen. */
enum class EntityKind { SELF, ALLY, ENEMY, OBJECTIVE, UI_CONTROL, UNKNOWN }

/** Why an action was refused. Shared by the structural and the safety validators so the UI can
 *  show one consistent vocabulary. */
enum class RejectionCode {
    INVALID_ACTION_TYPE,
    MALFORMED_DECISION,
    MISSING_COORDINATES,
    INVALID_COORDINATES,
    OUT_OF_BOUNDS,
    DURATION_TOO_SHORT,
    DURATION_TOO_LONG,
    LOW_CONFIDENCE,
    UNSUPPORTED_ACTION,
    UNSUPPORTED_BY_CONTROLLER,
    RATE_LIMITED,
    SESSION_ACTION_LIMIT,
    ACTIONS_DISABLED,
    EMERGENCY_STOP,
    SESSION_NOT_RUNNING,
    STALE_GEOMETRY,
    NO_CONTROLLER,
}

/** The outcome of validating a decision, attached to the decision that was judged. */
sealed interface ValidationStatus {
    data object Pending : ValidationStatus
    data class Accepted(val detail: String) : ValidationStatus
    data class Rejected(val code: RejectionCode, val detail: String) : ValidationStatus

    val isAccepted: Boolean get() = this is Accepted
}

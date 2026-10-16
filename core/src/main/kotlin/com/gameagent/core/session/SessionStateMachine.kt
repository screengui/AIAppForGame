package com.gameagent.core.session

import com.gameagent.core.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TransitionResult {
    data class Ok(val from: SessionState, val to: SessionState, val reason: String) : TransitionResult
    data class Invalid(val from: SessionState, val to: SessionState, val reason: String) : TransitionResult

    val accepted: Boolean get() = this is Ok
}

data class StateChange(
    val from: SessionState,
    val to: SessionState,
    val reason: String,
    val timestampMs: Long,
)

/**
 * The single authority on session lifecycle.
 *
 * Every transition is checked against [ALLOWED]; an illegal transition is reported rather than
 * silently applied, which is what prevents duplicated capture sessions and half-started states.
 * All methods are synchronised because the UI thread, the capture callback thread and the pipeline
 * coroutine all drive transitions.
 */
class SessionStateMachine(
    initial: SessionState = SessionState.IDLE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val historyBuffer = ArrayDeque<StateChange>(HISTORY_CAPACITY)

    @Synchronized
    fun current(): SessionState = mutableState.value

    @Synchronized
    fun history(): List<StateChange> = historyBuffer.toList()

    @Synchronized
    fun transitionTo(next: SessionState, reason: String = ""): TransitionResult {
        val from = mutableState.value
        if (from == next) {
            return TransitionResult.Invalid(from, next, "already in $next")
        }
        val allowed = ALLOWED[from].orEmpty()
        if (next !in allowed) {
            return TransitionResult.Invalid(
                from = from,
                to = next,
                reason = "transition $from -> $next is not allowed (allowed: ${allowed.joinToString()})",
            )
        }
        mutableState.value = next
        historyBuffer.addLast(StateChange(from, next, reason, clock()))
        while (historyBuffer.size > HISTORY_CAPACITY) historyBuffer.removeFirst()
        return TransitionResult.Ok(from, next, reason)
    }

    /** True when the state owns capture resources, so callers can decide about cleanup. */
    fun holdsCaptureResources(): Boolean = current().holdsCaptureResources

    companion object {
        const val HISTORY_CAPACITY = 40

        /**
         * The complete transition table. Anything not listed is refused.
         *
         * Note three deliberate rules:
         *  - `STOPPING` only leads to `IDLE`/`ERROR`, so a stop can never be raced by a restart.
         *  - There is no self-transition; "already there" is reported as invalid so repeated
         *    start/stop presses are visible in the log instead of looking like a no-op success.
         *  - `PROCESSING` is reachable **only** from `RUNNING`, which is what makes it safe for
         *    [com.gameagent.core.model.SessionState.allowsInput] to include it.
         */
        val ALLOWED: Map<SessionState, Set<SessionState>> = mapOf(
            SessionState.IDLE to setOf(
                SessionState.REQUESTING_PERMISSION,
                SessionState.CAPTURING,
                SessionState.ERROR,
            ),
            SessionState.REQUESTING_PERMISSION to setOf(
                SessionState.CAPTURING,
                SessionState.IDLE,
                SessionState.ERROR,
            ),
            SessionState.CAPTURING to setOf(
                SessionState.READY,
                SessionState.STOPPING,
                SessionState.ERROR,
            ),
            SessionState.PROCESSING to setOf(
                SessionState.READY,
                SessionState.RUNNING,
                SessionState.CAPTURING,
                SessionState.STOPPING,
                SessionState.ERROR,
            ),
            SessionState.READY to setOf(
                SessionState.RUNNING,
                SessionState.PAUSED,
                SessionState.CAPTURING,
                SessionState.STOPPING,
                SessionState.IDLE,
                SessionState.ERROR,
            ),
            SessionState.RUNNING to setOf(
                SessionState.READY,
                SessionState.PROCESSING,
                SessionState.PAUSED,
                SessionState.STOPPING,
                SessionState.ERROR,
            ),
            SessionState.PAUSED to setOf(
                SessionState.RUNNING,
                SessionState.READY,
                SessionState.STOPPING,
                SessionState.ERROR,
            ),
            SessionState.STOPPING to setOf(
                SessionState.IDLE,
                SessionState.ERROR,
            ),
            SessionState.ERROR to setOf(
                SessionState.IDLE,
                SessionState.REQUESTING_PERMISSION,
            ),
        )
    }
}

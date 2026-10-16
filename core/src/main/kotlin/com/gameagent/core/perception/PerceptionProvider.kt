package com.gameagent.core.perception

import com.gameagent.core.model.GameState

sealed interface PerceptionResult {
    data class Success(val state: GameState, val durationMs: Long) : PerceptionResult
    data class Failure(val reason: String, val durationMs: Long) : PerceptionResult

    fun durationOrZero(): Long = when (this) {
        is Success -> durationMs
        is Failure -> durationMs
    }
}

/**
 * Turns a frame into a [GameState].
 *
 * Implementations must be safe to call from a background coroutine, must not block the main thread,
 * and must never fabricate state: if they cannot read something they return an unknown observation
 * with confidence 0 rather than a plausible-looking constant.
 *
 * Three implementations ship with the project:
 *  - [HeuristicScreenPerceptionProvider] - deterministic colour/geometry analysis (the default).
 *  - [NullPerceptionProvider] - always unknown; used when the user turns perception off.
 *  - [ScriptedPerceptionProvider] - replays prepared states; used by tests and the built-in self test.
 * A future trained model or cloud vision provider can implement this same interface.
 */
interface PerceptionProvider {
    /** Stable identifier surfaced in the UI and in diagnostics. */
    val id: String

    /** Short human description of what this provider can and cannot do. */
    val description: String

    suspend fun observe(frame: ImageFrame): PerceptionResult
}

/** Always returns an honest "unknown" state. */
class NullPerceptionProvider(
    private val reason: String = "perception provider disabled",
) : PerceptionProvider {
    override val id: String = "null-perception"
    override val description: String = "Returns no detections. Capture still works."

    override suspend fun observe(frame: ImageFrame): PerceptionResult = PerceptionResult.Success(
        state = GameState.unknown(
            timestampMs = frame.timestampMs,
            frameId = frame.frameId,
            frameWidth = frame.width,
            frameHeight = frame.height,
            source = id,
            reason = reason,
        ),
        durationMs = 0L,
    )
}

/** Always fails. Used to exercise the "perception unavailable" paths. */
class FailingPerceptionProvider(
    override val id: String = "failing-perception",
    private val reason: String = "perception provider is unavailable",
) : PerceptionProvider {
    override val description: String = "Always fails; used for error-path testing."

    override suspend fun observe(frame: ImageFrame): PerceptionResult = PerceptionResult.Failure(reason, 0L)
}

/**
 * Replays a fixed list of [GameState]s, one per call, then keeps returning the last entry.
 * Deterministic and dependency-free, which is what makes end-to-end session tests possible.
 */
class ScriptedPerceptionProvider(
    private val states: List<GameState>,
    override val id: String = "scripted-perception",
    override val description: String = "Replays prepared observations (tests and self test).",
    private val onExhausted: (GameState) -> GameState = { it },
) : PerceptionProvider {

    init {
        require(states.isNotEmpty()) { "scripted provider needs at least one state" }
    }

    private var cursor = 0

    /** Number of frames fed to this provider so far. */
    val consumed: Int get() = cursor

    override suspend fun observe(frame: ImageFrame): PerceptionResult {
        val index = cursor.coerceAtMost(states.size - 1)
        cursor++
        val template = states[index]
        val state = onExhausted(template).copy(
            timestampMs = frame.timestampMs,
            frameId = frame.frameId,
            frameWidth = frame.width,
            frameHeight = frame.height,
        )
        return PerceptionResult.Success(state, durationMs = 0L)
    }
}

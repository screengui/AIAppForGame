package com.gameagent.core.diagnostics

/**
 * Counters for the screen-capture stage.
 *
 * [framesCaptured] counts frames that were fully read into a reusable buffer.
 * [framesDropped] counts frames the engine intentionally skipped (back-pressure, no consumer,
 * stale frames in the bounded queue). Dropped frames are a healthy signal, not an error.
 */
data class CaptureMetrics(
    val running: Boolean = false,
    val framesCaptured: Long = 0L,
    val framesDropped: Long = 0L,
    val bytesPerFrame: Int = 0,
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val lastFrameAtMs: Long = 0L,
    val framesPerSecond: Double = 0.0,
    val frameInterval: LatencySummary = LatencySummary(0L, null, 0.0, 0L, 0L, 0L),
) {
    val hasFrames: Boolean get() = framesCaptured > 0L
}

/**
 * Aggregated pipeline counters surfaced on the dashboard.
 *
 * All fields describe work that actually happened; nothing here is estimated or synthesised.
 */
data class SessionMetrics(
    val capture: CaptureMetrics = CaptureMetrics(),
    val perception: LatencySummary = LatencySummary(0L, null, 0.0, 0L, 0L, 0L),
    val decision: LatencySummary = LatencySummary(0L, null, 0.0, 0L, 0L, 0L),
    val input: LatencySummary = LatencySummary(0L, null, 0.0, 0L, 0L, 0L),
    val observationsProcessed: Long = 0L,
    val observationsFailed: Long = 0L,
    val decisionsMade: Long = 0L,
    val actionsAccepted: Long = 0L,
    val actionsRejected: Long = 0L,
    val actionsExecuted: Long = 0L,
    val actionsFailed: Long = 0L,
)

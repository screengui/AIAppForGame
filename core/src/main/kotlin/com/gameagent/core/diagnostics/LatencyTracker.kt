package com.gameagent.core.diagnostics

/**
 * Fixed-window latency tracker used for capture, perception, decision and input timings.
 *
 * It keeps only [windowSize] samples, so a session that runs for hours uses a constant
 * amount of memory. Not thread-safe by itself: the caller owns synchronisation, which is
 * why the session manager records all timings from its single pipeline coroutine.
 */
class LatencyTracker(val windowSize: Int = 120) {

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    private val samples = ArrayDeque<Long>(windowSize)
    private var totalSamples = 0L
    private var worstMs = 0L

    fun record(durationMs: Long) {
        val safe = durationMs.coerceAtLeast(0L)
        samples.addLast(safe)
        while (samples.size > windowSize) samples.removeFirst()
        totalSamples++
        if (safe > worstMs) worstMs = safe
    }

    fun count(): Long = totalSamples

    /** Samples currently retained. */
    fun windowCount(): Int = samples.size

    fun lastMs(): Long? = samples.lastOrNull()

    fun meanMs(): Double = if (samples.isEmpty()) 0.0 else samples.average()

    fun maxMs(): Long = samples.maxOrNull() ?: 0L

    fun minMs(): Long = samples.minOrNull() ?: 0L

    fun peakMs(): Long = worstMs

    /** Nearest-rank percentile over the retained window. */
    fun percentile(p: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val rank = kotlin.math.ceil(p.coerceIn(0.0, 1.0) * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun reset() {
        samples.clear()
        totalSamples = 0L
        worstMs = 0L
    }

    fun summary(): LatencySummary = LatencySummary(
        samples = totalSamples,
        lastMs = lastMs(),
        meanMs = meanMs(),
        p50Ms = percentile(0.50),
        p95Ms = percentile(0.95),
        maxMs = maxMs(),
    )
}

data class LatencySummary(
    val samples: Long,
    val lastMs: Long?,
    val meanMs: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
)

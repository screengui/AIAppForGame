package com.gameagent.core.diagnostics

import com.gameagent.core.testutil.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticsTest {

    @Test
    fun `the event log is bounded and keeps the newest records`() {
        val log = DiagnosticEventLog(capacity = 3, clock = TestClock())
        repeat(10) { log.debug(EventCategory.SYSTEM, "event $it") }

        val snapshot = log.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals("event 7", snapshot[0].message)
        assertEquals("event 9", snapshot[2].message)
        assertTrue(snapshot[0].seq < snapshot[2].seq)
    }

    @Test
    fun `recent returns newest first and honours the limit`() {
        val log = DiagnosticEventLog(capacity = 10)
        repeat(5) { log.info(EventCategory.SESSION, "e$it") }
        val recent = log.recent(limit = 2)
        assertEquals(listOf("e4", "e3"), recent.map { it.message })
    }

    @Test
    fun `clear empties the log`() {
        val log = DiagnosticEventLog()
        log.warn(EventCategory.CAPTURE, "x")
        log.clear()
        assertEquals(0, log.size())
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `events carry level, category, timestamp and payload`() {
        val clock = TestClock()
        val log = DiagnosticEventLog(clock = clock)
        log.error(EventCategory.INPUT, "failed", mapOf("reason" to "timeout"))

        val event = log.snapshot().single()
        assertEquals(EventLevel.ERROR, event.level)
        assertEquals(EventCategory.INPUT, event.category)
        assertEquals("failed", event.message)
        assertEquals("timeout", event.data["reason"])
        assertEquals(clock(), event.timestampMs)
        assertTrue(event.format().contains("reason=timeout"))
    }

    @Test
    fun `capacity must be positive`() {
        assertIs<IllegalArgumentException>(runCatching { DiagnosticEventLog(capacity = 0) }.exceptionOrNull())
    }

    @Test
    fun `latency tracker summarises a window`() {
        val tracker = LatencyTracker(windowSize = 5)
        repeat(10) { tracker.record(it.toLong() * 10L) }

        val summary = tracker.summary()
        assertEquals(10L, summary.samples)
        // The window keeps the last five samples: 50, 60, 70, 80, 90.
        assertEquals(5, tracker.windowCount())
        assertEquals(50L, tracker.minMs())
        assertEquals(90L, tracker.maxMs())
        assertEquals(90L, summary.lastMs)
        assertEquals(70.0, summary.meanMs)
        assertEquals(90L, summary.p95Ms)
        // The peak is a lifetime high-water mark, not just the window.
        assertEquals(90L, tracker.peakMs())
    }

    @Test
    fun `latency tracker handles an empty window`() {
        val tracker = LatencyTracker()
        val summary = tracker.summary()
        assertEquals(0L, summary.samples)
        assertEquals(null, summary.lastMs)
        assertEquals(0L, summary.p95Ms)
        assertEquals(0.0, summary.meanMs)
    }

    @Test
    fun `negative samples are clamped rather than corrupting the mean`() {
        val tracker = LatencyTracker(windowSize = 3)
        tracker.record(-50L)
        tracker.record(10L)
        assertEquals(5.0, tracker.meanMs())
    }

    @Test
    fun `reset clears both the window and the lifetime counters`() {
        val tracker = LatencyTracker()
        repeat(5) { tracker.record(10L) }
        tracker.reset()
        assertEquals(0L, tracker.count())
        assertEquals(0, tracker.windowCount())
        assertEquals(0L, tracker.peakMs())
    }

    @Test
    fun `default metrics describe no work done`() {
        val metrics = SessionMetrics()
        assertEquals(0L, metrics.observationsProcessed)
        assertEquals(0L, metrics.actionsExecuted)
        assertEquals(false, metrics.capture.hasFrames)
        assertEquals(0L, metrics.perception.samples)
    }
}

package com.gameagent.core.pipeline

import com.gameagent.core.testutil.Builders
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameBufferTest {

    private fun frame(id: Long) = Builders.frame(width = 4, height = 4, frameId = id)

    @Test
    fun `accepts frames while there is room`() {
        val buffer = FrameBuffer(capacity = 2)
        assertEquals(OfferResult.Accepted, buffer.offer(frame(1)))
        assertEquals(OfferResult.Accepted, buffer.offer(frame(2)))
        assertEquals(0L, buffer.dropped)
        assertEquals(2L, buffer.offered)
    }

    @Test
    fun `replaces the oldest frame when full so the newest always wins`() {
        val buffer = FrameBuffer(capacity = 2)
        buffer.offer(frame(1))
        buffer.offer(frame(2))
        assertEquals(OfferResult.ReplacedOldest, buffer.offer(frame(3)))

        assertEquals(1L, buffer.dropped)
        assertEquals(3L, buffer.offered)
        // Frame 1 was evicted, so the survivors are 2 and 3 and the newest frame is still last.
        assertEquals(2L, buffer.poll()?.frameId)
        assertEquals(3L, buffer.poll()?.frameId)
        assertNull(buffer.poll())
    }

    @Test
    fun `drain discards everything pending and counts it`() {
        val buffer = FrameBuffer(capacity = 4)
        repeat(4) { buffer.offer(frame(it.toLong())) }
        assertEquals(4, buffer.drain())
        assertNull(buffer.poll())
        assertEquals(4L, buffer.dropped)
    }

    @Test
    fun `offering to a closed buffer drops the frame rather than throwing`() {
        val buffer = FrameBuffer(capacity = 1)
        buffer.close()
        assertEquals(OfferResult.Dropped, buffer.offer(frame(1)))
        assertEquals(1L, buffer.dropped)
    }

    @Test
    fun `the worker can iterate the receive channel and cancellation ends it`() = runTest {
        val buffer = FrameBuffer(capacity = 2)
        val received = mutableListOf<Long>()

        val job = launch {
            for (item in buffer.receiveChannel()) received += item.frameId
        }
        buffer.offer(frame(1))
        buffer.offer(frame(2))
        runCurrent()
        assertEquals(listOf(1L, 2L), received)

        job.cancel()
        buffer.offer(frame(3))
        runCurrent()
        assertEquals(listOf(1L, 2L), received, "a cancelled worker must not keep consuming frames")
    }

    @Test
    fun `capacity must be positive`() {
        assertIs<IllegalArgumentException>(runCatching { FrameBuffer(0) }.exceptionOrNull())
    }
}

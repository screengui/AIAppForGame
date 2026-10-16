package com.gameagent.core.pipeline

import com.gameagent.core.perception.ImageFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicLong

enum class OfferResult {
    /** Queued immediately. */
    Accepted,

    /** The queue was full; the oldest pending frame was discarded to make room. */
    ReplacedOldest,

    /** The queue is closed or the frame was discarded outright. */
    Dropped,
}

/**
 * Bounded, drop-oldest frame queue.
 *
 * Rationale: a screen-capture pipeline can always produce frames faster than perception can consume
 * them. Queueing them would add latency (the agent would act on old frames) and unbounded memory,
 * so the correct behaviour is to discard history and always work on the newest frame.
 *
 * The capacity is tiny on purpose (2 by default), and [dropped] counts every discarded frame so the
 * dashboard can show that back-pressure is working rather than hiding it.
 */
class FrameBuffer(capacity: Int = 2) {

    init {
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private val channel = Channel<ImageFrame>(capacity)
    private val offeredCount = AtomicLong()
    private val droppedCount = AtomicLong()

    val offered: Long get() = offeredCount.get()
    val dropped: Long get() = droppedCount.get()

    fun offer(frame: ImageFrame): OfferResult {
        offeredCount.incrementAndGet()
        if (channel.trySend(frame).isSuccess) return OfferResult.Accepted
        // Full: discard the oldest frame and retry once. Never block the capture callback.
        val evicted = channel.tryReceive()
        if (evicted.isSuccess) {
            droppedCount.incrementAndGet()
            // Hand the buffer back immediately: the evicted frame will never be interpreted.
            evicted.getOrNull()?.close()
        }
        return if (channel.trySend(frame).isSuccess) {
            OfferResult.ReplacedOldest
        } else {
            droppedCount.incrementAndGet()
            frame.close()
            OfferResult.Dropped
        }
    }

    /** Non-suspending poll; returns null when nothing is pending. */
    fun poll(): ImageFrame? = channel.tryReceive().getOrNull()

    /**
     * The receive side for the pipeline worker.
     *
     * Exposed as a channel rather than a suspending `take` so the worker can use plain `for (frame
     * in ...)` iteration, which cancels cleanly when the worker job is cancelled.
     */
    fun receiveChannel(): ReceiveChannel<ImageFrame> = channel

    /** Discards everything pending, e.g. on stop or pause. Returns how many frames were discarded. */
    fun drain(): Int {
        var count = 0
        while (true) {
            val result = channel.tryReceive()
            if (result.isFailure) break
            count++
            droppedCount.incrementAndGet()
            result.getOrNull()?.close()
        }
        return count
    }

    fun close() {
        channel.close()
    }
}

package com.gameagent.core.perception

/**
 * One captured frame as ARGB_8888 pixels.
 *
 * `:core` deliberately knows nothing about `android.graphics.Bitmap`: keeping frames as a raw
 * `IntArray` means the whole perception pipeline can be unit-tested on the JVM with synthetic
 * frames, and the Android layer owns the single conversion from `Image`/`Bitmap` into this type.
 *
 * [argb] may be larger than `width * height`; only the first `width * height` entries are defined.
 * That allows the Android capture layer to reuse one buffer across frames instead of allocating
 * a new array per frame.
 */
class ImageFrame(
    val frameId: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val argb: IntArray,
    /**
     * Optional buffer-ownership hook.
     *
     * The Android capture layer recycles its pixel arrays instead of allocating a multi-megabyte
     * array per frame, so it needs to know when a frame has been consumed. Every frame is consumed
     * at most once - by the session pipeline - and the releaser is invoked exactly once from
     * [close]. Frames created by tests or fixtures pass no releaser, making [close] a no-op.
     */
    private val releaser: (() -> Unit)? = null,
) {
    init {
        require(width > 0 && height > 0) { "frame dimensions must be positive, got ${width}x$height" }
        require(argb.size >= width * height) {
            "argb buffer too small: ${argb.size} < ${width * height}"
        }
    }

    private var closed = false

    val isClosed: Boolean get() = closed

    /** Returns the backing buffer to its owner. Idempotent and never throws. */
    fun close() {
        if (closed) return
        closed = true
        releaser?.invoke()
    }

    val pixelCount: Int get() = width * height

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun pixelAt(x: Int, y: Int): Int = argb[y * width + x]

    /** Renders the frame as ASCII art. Diagnostics only: never part of the decision path. */
    fun samplePixel(x: Int, y: Int): Int = pixelAt(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    companion object {
        /**
         * Creates a frame whose pixel array is handed back to its owner when the frame is closed.
         *
         * The Android capture layer uses this so a single buffer can be reused across frames.
         */
        fun pooled(
            frameId: Long,
            timestampMs: Long,
            width: Int,
            height: Int,
            argb: IntArray,
            releaser: () -> Unit,
        ) = ImageFrame(frameId, timestampMs, width, height, argb, releaser)

        /** Sum of absolute per-channel differences. Cheap and good enough for flat UI colours. */
        fun colorDistance(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
        }

        fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF
    }
}

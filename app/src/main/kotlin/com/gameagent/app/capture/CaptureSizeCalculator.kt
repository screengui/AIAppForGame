package com.gameagent.app.capture

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CaptureSize(val width: Int, val height: Int)

/**
 * Chooses the VirtualDisplay/ImageReader resolution.
 *
 * Two things matter here:
 *  - **Aspect ratio must be preserved exactly.** Normalised coordinates are defined relative to the
 *    full display, so a capture surface with a different aspect would silently skew every touch.
 *  - **Work must be bounded.** Frames are re-interpreted continuously, so the surface is capped at
 *    [DEFAULT_MAX_PIXELS] regardless of what the user asks for, and both dimensions are forced even
 *    to avoid odd-stride surprises in the platform buffer layout.
 *
 * Pure Kotlin so the arithmetic is unit tested rather than trusted.
 */
object CaptureSizeCalculator {

    /** ~720p worth of pixels: enough for HUD colour work on a phone, cheap to repack per frame. */
    const val DEFAULT_MAX_PIXELS: Int = 1280 * 720

    const val MIN_DIMENSION: Int = 2

    fun compute(
        displayWidth: Int,
        displayHeight: Int,
        scale: Float,
        maxPixels: Int = DEFAULT_MAX_PIXELS,
    ): CaptureSize {
        require(displayWidth > 0 && displayHeight > 0) {
            "display size must be positive, was ${displayWidth}x$displayHeight"
        }
        require(maxPixels > 0) { "maxPixels must be positive" }

        val clampedScale = scale.coerceIn(0.01f, 1f)
        var width = max(MIN_DIMENSION, (displayWidth * clampedScale).roundToInt())
        var height = max(MIN_DIMENSION, (displayHeight * clampedScale).roundToInt())

        // Keep the aspect ratio when the cap forces a reduction.
        val requested = width.toLong() * height.toLong()
        if (requested > maxPixels) {
            val factor = sqrt(maxPixels.toDouble() / requested.toDouble())
            width = max(MIN_DIMENSION, (width * factor).roundToInt())
            height = max(MIN_DIMENSION, (height * factor).roundToInt())
        }

        return CaptureSize(even(width), even(height))
    }

    private fun even(value: Int): Int {
        val aligned = value - (value % 2)
        return max(MIN_DIMENSION, aligned)
    }
}

package com.gameagent.core.geometry

/** A point in 0..1 image space, where (0,0) is the top-left of the captured frame. */
data class NormalizedPoint(val x: Float, val y: Float) {

    val isInsideUnitSquare: Boolean
        get() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

    fun clamped(): NormalizedPoint = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    companion object {
        val CENTER = NormalizedPoint(0.5f, 0.5f)
    }
}

/** An axis-aligned rectangle in 0..1 image space. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: NormalizedPoint get() = NormalizedPoint((left + right) / 2f, (top + bottom) / 2f)

    val isInsideUnitSquare: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right >= left && bottom >= top

    fun clamped(): NormalizedRect = NormalizedRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )

    companion object {
        fun fromCenter(cx: Float, cy: Float, halfWidth: Float, halfHeight: Float) =
            NormalizedRect(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
    }
}

/** An absolute pixel on the physical display. */
data class PixelPoint(val x: Int, val y: Int)

/** Display rotation as reported by `Display.getRotation()`. */
enum class DisplayRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270),
    ;

    companion object {
        fun fromDegrees(degrees: Int): DisplayRotation = when (degrees) {
            0 -> ROTATION_0
            90 -> ROTATION_90
            180 -> ROTATION_180
            270 -> ROTATION_270
            else -> throw IllegalArgumentException("Unsupported rotation $degrees; expected 0/90/180/270")
        }
    }
}

/**
 * The geometry snapshot that makes a normalized coordinate meaningful.
 *
 * Why both capture and display sizes: the capture surface may be a *scaled* VirtualDisplay
 * (for performance), while touch injection always happens in real display pixels. Normalized
 * coordinates are defined relative to the full display area, so [displayWidth]/[displayHeight]
 * are what action coordinates are computed against. [captureWidth]/[captureHeight] describe
 * the buffer the perception stage actually saw, and are kept so a mismatch can be detected.
 *
 * [frameId] and [capturedAtMs] make staleness detectable: an action built from an old frame
 * must never be injected into a screen that may have moved on.
 */
data class ScreenGeometry(
    val captureWidth: Int,
    val captureHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: DisplayRotation,
    val frameId: Long,
    val capturedAtMs: Long,
) {
    val isValid: Boolean
        get() = captureWidth > 0 && captureHeight > 0 && displayWidth > 0 && displayHeight > 0

    val captureScaleX: Float get() = if (displayWidth == 0) 0f else captureWidth.toFloat() / displayWidth.toFloat()
    val captureScaleY: Float get() = if (displayHeight == 0) 0f else captureHeight.toFloat() / displayHeight.toFloat()

    fun withFrame(frameId: Long, capturedAtMs: Long) = copy(frameId = frameId, capturedAtMs = capturedAtMs)
}

package com.gameagent.app.capture

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Capture sizing tests.
 *
 * The capture surface's aspect ratio *is* the coordinate system: normalised points are relative to
 * the capture, and the mapper turns them into display pixels. If the two aspects diverge, every
 * synthesised touch lands somewhere else on screen. These tests pin that property down.
 */
class CaptureSizeCalculatorTest {

    @Test
    fun `half scale keeps the aspect ratio exactly`() {
        val size = CaptureSizeCalculator.compute(displayWidth = 1080, displayHeight = 2400, scale = 0.5f)

        assertEquals(CaptureSize(540, 1200), size)
        assertAspectClose(1080.0 / 2400.0, size, tolerance = 0.001)
    }

    @Test
    fun `oversized requests are capped while keeping the aspect ratio`() {
        val size = CaptureSizeCalculator.compute(displayWidth = 1080, displayHeight = 2400, scale = 1f)

        assertTrue(
            size.width.toLong() * size.height.toLong() <= CaptureSizeCalculator.DEFAULT_MAX_PIXELS,
            "cap exceeded: ${size.width}x${size.height}",
        )
        assertAspectClose(1080.0 / 2400.0, size, tolerance = 0.01)
    }

    @Test
    fun `landscape displays are handled the same way`() {
        val size = CaptureSizeCalculator.compute(displayWidth = 2400, displayHeight = 1080, scale = 1f)

        assertTrue(size.width > size.height, "rotating must not transpose the capture: $size")
        assertTrue(
            size.width.toLong() * size.height.toLong() <= CaptureSizeCalculator.DEFAULT_MAX_PIXELS,
            "cap exceeded: ${size.width}x${size.height}",
        )
        assertAspectClose(2400.0 / 1080.0, size, tolerance = 0.01)
    }

    @Test
    fun `both dimensions are even`() {
        // Odd strides are legal but awkward for platform buffer layouts, so the calculator forces
        // even dimensions and the tests hold it to that.
        val cases = listOf(1081 to 2401, 1080 to 2399, 999 to 1001, 3 to 3, 5 to 7)
        cases.forEach { (width, height) ->
            val size = CaptureSizeCalculator.compute(width, height, scale = 0.5f)
            assertEquals(0, size.width % 2, "width ${size.width} from ${width}x$height")
            assertEquals(0, size.height % 2, "height ${size.height} from ${width}x$height")
        }
    }

    @Test
    fun `tiny displays never collapse below the minimum dimension`() {
        val size = CaptureSizeCalculator.compute(displayWidth = 10, displayHeight = 10, scale = 0.01f)

        assertTrue(size.width >= CaptureSizeCalculator.MIN_DIMENSION, "width was ${size.width}")
        assertTrue(size.height >= CaptureSizeCalculator.MIN_DIMENSION, "height was ${size.height}")
    }

    @Test
    fun `out of range scales are clamped rather than trusted`() {
        val fromAbove = CaptureSizeCalculator.compute(1080, 2400, scale = 50f)
        val fromAtOne = CaptureSizeCalculator.compute(1080, 2400, scale = 1f)
        assertEquals(fromAtOne, fromAbove, "a scale above 1.0 must behave as exactly 1.0")

        val fromZero = CaptureSizeCalculator.compute(1080, 2400, scale = 0f)
        assertTrue(fromZero.width > 0 && fromZero.height > 0, "a zero scale must not produce an empty capture: $fromZero")
    }

    @Test
    fun `a zero or negative display size is a programming error`() {
        assertFailsWith<IllegalArgumentException> { CaptureSizeCalculator.compute(0, 2400, 0.5f) }
        assertFailsWith<IllegalArgumentException> { CaptureSizeCalculator.compute(1080, -1, 0.5f) }
    }

    @Test
    fun `a non positive pixel budget is a programming error`() {
        assertFailsWith<IllegalArgumentException> { CaptureSizeCalculator.compute(1080, 2400, 0.5f, maxPixels = 0) }
    }

    private fun assertAspectClose(expected: Double, size: CaptureSize, tolerance: Double) {
        val actual = size.width.toDouble() / size.height.toDouble()
        assertTrue(
            abs(actual - expected) <= tolerance,
            "aspect drifted: expected $expected, got $actual for ${size.width}x${size.height}",
        )
    }
}

package com.gameagent.app.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Buffer repacking tests.
 *
 * This is the code that turns whatever `ImageReader` hands back into a tightly packed ARGB array. A
 * stride mistake here does not throw - it produces a smeared image and shifted coordinates - so it
 * is tested against hand-built buffers with deliberate padding.
 */
class ImageBufferLayoutTest {

    @Test
    fun `required size for a single row ignores row stride`() {
        assertEquals(16, ImageBufferLayout.requiredBufferSize(width = 4, height = 1, rowStride = 40, pixelStride = 4))
    }

    @Test
    fun `required size for multiple rows uses the last row and the final pixel`() {
        // 3 rows, stride 20, 4 pixels of 4 bytes: 20*2 + 4*4 = 56.
        assertEquals(56, ImageBufferLayout.requiredBufferSize(width = 4, height = 3, rowStride = 20, pixelStride = 4))
    }

    @Test
    fun `required size rejects nonsense arguments`() {
        assertFailsWith<IllegalArgumentException> {
            ImageBufferLayout.requiredBufferSize(width = 0, height = 3, rowStride = 20, pixelStride = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageBufferLayout.requiredBufferSize(width = 4, height = 3, rowStride = 0, pixelStride = 4)
        }
    }

    @Test
    fun `padded rows are repacked without smearing`() {
        // Two rows of two pixels at pixelStride 4, padded to a 12-byte row stride (4 bytes of junk).
        val rowStride = 12
        val source = ByteArray(rowStride * 2 + 8)
        writePixel(source, 0, 0x11, 0x22, 0x33, 0xFF)
        writePixel(source, 4, 0x44, 0x55, 0x66, 0xFF)
        // Padding bytes are deliberately non-zero: copying them would visibly corrupt the result.
        for (i in 8 until 12) source[i] = 0x7F.toByte()
        writePixel(source, rowStride, 0xAA.toInt(), 0xBB.toInt(), 0xCC.toInt(), 0xFF)
        writePixel(source, rowStride + 4, 0x01, 0x02, 0x03, 0xFF)

        val destination = IntArray(4)
        val written = ImageBufferLayout.repackRgba(
            source = source,
            sourceWidth = 2,
            sourceHeight = 2,
            rowStride = rowStride,
            pixelStride = 4,
            cropLeft = 0,
            cropTop = 0,
            outWidth = 2,
            outHeight = 2,
            destination = destination,
        )

        assertEquals(4, written)
        assertEquals(0xFF112233.toInt(), destination[0])
        assertEquals(0xFF445566.toInt(), destination[1])
        assertEquals(0xFFAABBCC.toInt(), destination[2])
        assertEquals(0xFF010203.toInt(), destination[3])
    }

    @Test
    fun `cropping offsets both source axes`() {
        val source = ByteArray(4 * 4 * 2)
        for (row in 0 until 2) {
            for (column in 0 until 4) {
                val value = (row * 4 + column + 1) * 10
                writePixel(source, (row * 4 + column) * 4, value, value, value, 0xFF)
            }
        }

        val destination = IntArray(4)
        val written = ImageBufferLayout.repackRgba(
            source = source,
            sourceWidth = 4,
            sourceHeight = 2,
            rowStride = 4 * 4,
            pixelStride = 4,
            cropLeft = 1,
            cropTop = 1,
            outWidth = 2,
            outHeight = 1,
            destination = destination,
        )

        assertEquals(2, written)
        // Value for (row, column) is ((row * 4) + column + 1) * 10, so source row 1 columns 1 and 2
        // are 60 and 70 - not the 50/60 that a one-off crop would have produced.
        assertEquals(0xFF3C3C3C.toInt(), destination[0])
        assertEquals(0xFF464646.toInt(), destination[1])
    }

    @Test
    fun `regions outside the source are skipped and left untouched`() {
        val source = ByteArray(4 * 4)
        writePixel(source, 0, 0xFF.toInt(), 0x00, 0x00, 0xFF)

        val destination = intArrayOf(-1, -1, -1, -1)
        val written = ImageBufferLayout.repackRgba(
            source = source,
            sourceWidth = 1,
            sourceHeight = 1,
            rowStride = 4,
            pixelStride = 4,
            cropLeft = 3,
            cropTop = 3,
            outWidth = 2,
            outHeight = 2,
            destination = destination,
        )

        assertEquals(0, written, "nothing should have been copied")
        assertTrue(destination.all { it == -1 }, "destination must not be written when no pixel is readable")
    }

    @Test
    fun `rgb sources without alpha are treated as opaque`() {
        val source = byteArrayOf(0x10, 0x20, 0x30)
        val destination = IntArray(1)

        val written = ImageBufferLayout.repackRgba(
            source = source,
            sourceWidth = 1,
            sourceHeight = 1,
            rowStride = 3,
            pixelStride = 3,
            cropLeft = 0,
            cropTop = 0,
            outWidth = 1,
            outHeight = 1,
            destination = destination,
        )

        assertEquals(1, written)
        assertEquals(0xFF102030.toInt(), destination[0])
    }

    @Test
    fun `a truncated source never reads past its end`() {
        // Claims four pixels per row but only supplies two.
        val source = ByteArray(8)
        val destination = IntArray(4)

        val written = ImageBufferLayout.repackRgba(
            source = source,
            sourceWidth = 4,
            sourceHeight = 1,
            rowStride = 16,
            pixelStride = 4,
            cropLeft = 0,
            cropTop = 0,
            outWidth = 4,
            outHeight = 1,
            destination = destination,
        )

        assertEquals(2, written, "only the two pixels actually present may be written")
    }

    @Test
    fun `impossible arguments are rejected`() {
        val source = ByteArray(64)
        val destination = IntArray(4)
        val base = { out: IntArray ->
            ImageBufferLayout.repackRgba(source, 2, 2, 8, 4, 0, 0, 2, 2, out)
        }

        assertFailsWith<IllegalArgumentException> { base(destination.copyOf(3)) }
        assertFailsWith<IllegalArgumentException> {
            // rowStride smaller than one full row.
            ImageBufferLayout.repackRgba(source, 4, 2, 8, 4, 0, 0, 4, 2, IntArray(8))
        }
        assertFailsWith<IllegalArgumentException> {
            ImageBufferLayout.repackRgba(source, 2, 2, 8, 2, 0, 0, 2, 2, destination)
        }
    }

    private fun writePixel(target: ByteArray, offset: Int, r: Int, g: Int, b: Int, a: Int) {
        target[offset] = r.toByte()
        target[offset + 1] = g.toByte()
        target[offset + 2] = b.toByte()
        target[offset + 3] = a.toByte()
    }
}

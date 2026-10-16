package com.gameagent.app.image

/**
 * Repacking of the padded RGBA_8888 buffers that `ImageReader` hands back.
 *
 * `Image.Plane.rowStride` is not guaranteed to equal `width * pixelStride`: devices commonly pad
 * rows to an alignment boundary. Copying such a buffer as if it were tightly packed produces a
 * diagonally smeared image, and - more importantly for this project - shifts every normalised
 * coordinate, which would make every derived touch coordinate wrong.
 *
 * This is a plain function over `ByteArray`/`IntArray` with no Android types, so the stride maths is
 * verified by JVM unit tests rather than by hoping a phone behaves.
 */
object ImageBufferLayout {

    /** Bytes per ARGB_8888 pixel. */
    const val RGBA_PIXEL_BYTES = 4

    /**
     * Minimum source buffer size needed to read a `width x height` region at the given strides.
     */
    fun requiredBufferSize(width: Int, height: Int, rowStride: Int, pixelStride: Int): Int {
        require(width > 0 && height > 0) { "size must be positive" }
        require(rowStride > 0 && pixelStride > 0) { "strides must be positive" }
        if (height == 1) return pixelStride * width
        return rowStride * (height - 1) + pixelStride * width
    }

    /**
     * Converts a padded source buffer into tightly packed ARGB pixels.
     *
     * @param source       raw bytes from the platform buffer
     * @param sourceWidth  number of readable pixels per row of the source
     * @param sourceHeight number of readable rows in the source
     * @param rowStride    bytes between the starts of consecutive rows
     * @param pixelStride  bytes between consecutive pixels of a row
     * @param cropLeft     first column to copy, in source pixels
     * @param cropTop      first row to copy
     * @param outWidth     destination width in pixels
     * @param outHeight    destination height in pixels
     * @param destination  reusable destination array (must fit `outWidth * outHeight`)
     * @return number of pixels written; pixels that fall outside the source are left untouched
     */
    fun repackRgba(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        outWidth: Int,
        outHeight: Int,
        destination: IntArray,
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "source size must be positive" }
        require(pixelStride >= 3) { "pixelStride must be at least 3 (RGB), was $pixelStride" }
        require(rowStride >= sourceWidth * pixelStride) {
            "rowStride ($rowStride) is smaller than one row of $sourceWidth pixels at stride $pixelStride"
        }
        require(outWidth > 0 && outHeight > 0) { "destination size must be positive" }
        require(destination.size >= outWidth * outHeight) {
            "destination holds ${destination.size} pixels but ${outWidth * outHeight} are required"
        }

        var written = 0
        for (row in 0 until outHeight) {
            val sourceRow = cropTop + row
            if (sourceRow < 0 || sourceRow >= sourceHeight) continue
            val sourceRowBase = sourceRow * rowStride
            val destinationRowBase = row * outWidth
            for (column in 0 until outWidth) {
                val sourceColumn = cropLeft + column
                if (sourceColumn < 0 || sourceColumn >= sourceWidth) continue
                val index = sourceRowBase + sourceColumn * pixelStride
                if (index + 2 >= source.size) continue
                val red = source[index].toInt() and 0xFF
                val green = source[index + 1].toInt() and 0xFF
                val blue = source[index + 2].toInt() and 0xFF
                val alpha = if (pixelStride >= RGBA_PIXEL_BYTES) source[index + 3].toInt() and 0xFF else 0xFF
                destination[destinationRowBase + column] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                written++
            }
        }
        return written
    }
}

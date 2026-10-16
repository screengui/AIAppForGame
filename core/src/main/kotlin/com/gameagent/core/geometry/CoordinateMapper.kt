package com.gameagent.core.geometry

import com.gameagent.core.model.RejectionCode
import kotlin.math.roundToInt

sealed interface MappingResult {
    data class Success(val point: PixelPoint, val geometry: ScreenGeometry) : MappingResult
    data class Failure(val code: RejectionCode, val detail: String) : MappingResult
}

/**
 * Maps normalized (0..1) coordinates onto physical display pixels, and refuses to map anything
 * when the geometry snapshot it would use is missing, inconsistent or stale.
 *
 * Why this exists: a decision is produced from a frame that was captured some milliseconds ago.
 * Touch injection happens *now*. If the screen rotated, the app was resized, or the decision is
 * simply older than the freshness budget, the mapping is invalid and the action must be dropped
 * rather than aimed at the wrong place.
 *
 * Rotation note: a VirtualDisplay on the default display produces a buffer that is already in the
 * current display orientation, so normalized coordinates require no extra rotation transform.
 * [ScreenGeometry.rotation] is retained so rotation *changes* can invalidate the snapshot.
 */
class CoordinateMapper(
    private val maxGeometryAgeMs: Long = DEFAULT_MAX_GEOMETRY_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var geometry: ScreenGeometry? = null

    fun update(newGeometry: ScreenGeometry) {
        require(newGeometry.isValid) { "refusing to store invalid screen geometry: $newGeometry" }
        geometry = newGeometry
    }

    fun invalidate() {
        geometry = null
    }

    fun current(): ScreenGeometry? = geometry

    fun ageMs(nowMs: Long = clock()): Long? = geometry?.let { nowMs - it.capturedAtMs }

    /** Null when the snapshot is usable, otherwise the reason it is not. */
    fun checkFreshness(nowMs: Long = clock()): MappingResult.Failure? {
        val snapshot = geometry
            ?: return MappingResult.Failure(
                RejectionCode.STALE_GEOMETRY,
                "no screen geometry has been captured yet",
            )
        if (!snapshot.isValid) {
            return MappingResult.Failure(RejectionCode.STALE_GEOMETRY, "screen geometry is not valid: $snapshot")
        }
        val age = nowMs - snapshot.capturedAtMs
        if (age < 0) {
            return MappingResult.Failure(
                RejectionCode.STALE_GEOMETRY,
                "screen geometry timestamp is in the future (clock skew of ${-age}ms)",
            )
        }
        if (age > maxGeometryAgeMs) {
            return MappingResult.Failure(
                RejectionCode.STALE_GEOMETRY,
                "screen geometry is ${age}ms old, limit is ${maxGeometryAgeMs}ms",
            )
        }
        return null
    }

    fun map(point: NormalizedPoint, nowMs: Long = clock()): MappingResult {
        checkFreshness(nowMs)?.let { return it }
        val snapshot = geometry ?: return MappingResult.Failure(RejectionCode.STALE_GEOMETRY, "no screen geometry")
        if (!point.isInsideUnitSquare) {
            return MappingResult.Failure(
                RejectionCode.OUT_OF_BOUNDS,
                "normalized point (${point.x}, ${point.y}) is outside the unit square",
            )
        }
        // Pixel centres run 0..dimension-1, so scale against dimension-1 to make 1.0 land on the
        // last addressable pixel instead of one past the edge.
        val maxX = (snapshot.displayWidth - 1).coerceAtLeast(0)
        val maxY = (snapshot.displayHeight - 1).coerceAtLeast(0)
        val x = (point.x * maxX).roundToInt().coerceIn(0, maxX)
        val y = (point.y * maxY).roundToInt().coerceIn(0, maxY)
        return MappingResult.Success(PixelPoint(x, y), snapshot)
    }

    companion object {
        /** A frame older than this is not considered a safe basis for touch injection. */
        const val DEFAULT_MAX_GEOMETRY_AGE_MS = 1_200L
    }
}

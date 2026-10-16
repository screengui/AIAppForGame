package com.gameagent.core.geometry

import com.gameagent.core.model.RejectionCode
import com.gameagent.core.testutil.Builders
import com.gameagent.core.testutil.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CoordinateMapperTest {

    private val clock = TestClock()
    private val mapper = CoordinateMapper(maxGeometryAgeMs = 1_000L, clock = clock)

    @Test
    fun `maps normalized corners onto the last addressable pixel`() {
        mapper.update(Builders.geometry(displayWidth = 1080, displayHeight = 2340))

        assertEquals(PixelPoint(0, 0), (mapper.map(NormalizedPoint(0f, 0f)) as MappingResult.Success).point)
        assertEquals(
            PixelPoint(1079, 2339),
            (mapper.map(NormalizedPoint(1f, 1f)) as MappingResult.Success).point,
        )
        assertEquals(
            PixelPoint(540, 1170),
            (mapper.map(NormalizedPoint(0.5f, 0.5f)) as MappingResult.Success).point,
        )
    }

    @Test
    fun `refuses to map without geometry`() {
        val failure = mapper.map(NormalizedPoint(0.5f, 0.5f))
        assertIs<MappingResult.Failure>(failure)
        assertEquals(RejectionCode.STALE_GEOMETRY, failure.code)
    }

    @Test
    fun `refuses to map a point outside the unit square`() {
        mapper.update(Builders.geometry())
        val failure = mapper.map(NormalizedPoint(1.4f, 0.5f))
        assertIs<MappingResult.Failure>(failure)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, failure.code)

        val negative = mapper.map(NormalizedPoint(-0.2f, 0.5f))
        assertIs<MappingResult.Failure>(negative)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, negative.code)
    }

    @Test
    fun `refuses to map NaN coordinates`() {
        mapper.update(Builders.geometry())
        val failure = mapper.map(NormalizedPoint(Float.NaN, 0.5f))
        assertIs<MappingResult.Failure>(failure)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, failure.code)
    }

    @Test
    fun `geometry becomes stale after the freshness budget`() {
        mapper.update(Builders.geometry(atMs = clock.now()))
        clock.advance(999L)
        assertIs<MappingResult.Success>(mapper.map(NormalizedPoint(0.5f, 0.5f)))
        clock.advance(2L)
        val failure = mapper.map(NormalizedPoint(0.5f, 0.5f))
        assertIs<MappingResult.Failure>(failure)
        assertEquals(RejectionCode.STALE_GEOMETRY, failure.code)
    }

    @Test
    fun `future timestamps are treated as stale rather than trusted`() {
        mapper.update(Builders.geometry(atMs = clock.now() + 5_000L))
        val failure = mapper.map(NormalizedPoint(0.5f, 0.5f))
        assertIs<MappingResult.Failure>(failure)
        assertEquals(RejectionCode.STALE_GEOMETRY, failure.code)
    }

    @Test
    fun `invalid geometry is rejected at the point of update`() {
        val bad = Builders.geometry().copy(displayWidth = 0)
        val error = runCatching { mapper.update(bad) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(error)
        assertNull(mapper.current())
    }

    @Test
    fun `invalidate clears the snapshot`() {
        mapper.update(Builders.geometry())
        mapper.invalidate()
        assertNull(mapper.current())
        assertNull(mapper.ageMs())
    }

    @Test
    fun `scaled capture size still maps against display pixels`() {
        // A 0.5x capture surface: normalized coordinates stay display-relative, so a 0.25 point
        // must land at 25% of the *display*, not of the capture buffer.
        mapper.update(
            Builders.geometry(
                displayWidth = 1080,
                displayHeight = 2340,
                captureWidth = 540,
                captureHeight = 1170,
            ),
        )
        val mapped = mapper.map(NormalizedPoint(0.25f, 0.5f))
        assertIs<MappingResult.Success>(mapped)
        assertEquals(270, mapped.point.x)
        assertEquals(1170, mapped.point.y)
        assertEquals(0.5f, mapped.geometry.captureScaleX)
    }

    @Test
    fun `rotation change requires a fresh snapshot`() {
        mapper.update(Builders.geometry(rotation = DisplayRotation.ROTATION_0, atMs = clock.now()))
        clock.advance(500L)
        // The display rotated: the capture layer publishes new geometry, and the mapper must use it.
        mapper.update(
            Builders.geometry(
                displayWidth = 2340,
                displayHeight = 1080,
                rotation = DisplayRotation.ROTATION_90,
                frameId = 2L,
                atMs = clock.now(),
            ),
        )
        val mapped = mapper.map(NormalizedPoint(1f, 1f))
        assertIs<MappingResult.Success>(mapped)
        assertEquals(2339, mapped.point.x)
        assertEquals(1079, mapped.point.y)
        assertEquals(DisplayRotation.ROTATION_90, mapped.geometry.rotation)
    }

    private fun TestClock.now(): Long = invoke()
}

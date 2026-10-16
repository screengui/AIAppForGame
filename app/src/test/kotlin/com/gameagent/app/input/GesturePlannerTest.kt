package com.gameagent.app.input

import com.gameagent.core.geometry.PixelPoint
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.RejectionCode
import com.gameagent.core.model.ValidatedAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gesture planning tests.
 *
 * The accessibility controller is the only component that can touch another app, and this planner is
 * the last pure function before the platform call. Its job is to reject anything the platform would
 * accept-but-ignore, so both the happy paths and every rejection path are exercised here.
 */
class GesturePlannerTest {

    @Test
    fun `tap becomes a zero length stroke at the requested point`() {
        val plan = plan(ActionType.TAP, start = PixelPoint(120, 340))

        val stroke = plan.strokes.single()
        assertEquals(120, stroke.startX)
        assertEquals(340, stroke.startY)
        assertEquals(stroke.startX, stroke.endX)
        assertEquals(stroke.startY, stroke.endY)
        assertTrue(stroke.isTap)
        assertEquals(GesturePlanner.DEFAULT_TAP_DURATION_MS, stroke.durationMs)
    }

    @Test
    fun `hold keeps its duration and stays a single point`() {
        val plan = plan(ActionType.HOLD, start = PixelPoint(10, 10), durationMs = 850L)

        val stroke = plan.strokes.single()
        assertTrue(stroke.isTap, "a hold is a long press at one point")
        assertEquals(850L, stroke.durationMs)
    }

    @Test
    fun `swipe keeps both end points`() {
        val plan = plan(ActionType.SWIPE, start = PixelPoint(100, 600), end = PixelPoint(700, 200), durationMs = 300L)

        val stroke = plan.strokes.single()
        assertEquals(100, stroke.startX)
        assertEquals(600, stroke.startY)
        assertEquals(700, stroke.endX)
        assertEquals(200, stroke.endY)
        assertTrue(!stroke.isTap)
        assertEquals(300L, stroke.durationMs)
    }

    @Test
    fun `durations are clamped into the platform window`() {
        val negative = plan(ActionType.HOLD, start = PixelPoint(5, 5), durationMs = -100L)
        assertTrue(negative.strokes.single().durationMs >= 1L, "duration must never be zero or negative")

        val enormous = plan(ActionType.HOLD, start = PixelPoint(5, 5), durationMs = Long.MAX_VALUE)
        assertEquals(
            GesturePlanner.MAX_GESTURE_DURATION_MS,
            enormous.strokes.single().durationMs,
            "an unbounded hold would be silently dropped by the platform",
        )
    }

    @Test
    fun `a tap can never turn into a long press`() {
        val plan = plan(ActionType.TAP, start = PixelPoint(1, 1), durationMs = 30_000L)

        assertTrue(
            plan.strokes.single().durationMs <= GesturePlanner.MAX_TAP_DURATION_MS,
            "tap duration was ${plan.strokes.single().durationMs}",
        )
    }

    @Test
    fun `coordinates outside the display are refused`() {
        val beyond = planFailure(ActionType.TAP, start = PixelPoint(1080, 10), displayWidth = 1080, displayHeight = 2400)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, beyond.code)

        val negative = planFailure(ActionType.TAP, start = PixelPoint(-1, 10), displayWidth = 1080, displayHeight = 2400)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, negative.code)

        val end = planFailure(
            ActionType.SWIPE,
            start = PixelPoint(10, 10),
            end = PixelPoint(10, 2400),
            displayWidth = 1080,
            displayHeight = 2400,
        )
        assertEquals(RejectionCode.OUT_OF_BOUNDS, end.code, "the end point must be checked as well as the start")
    }

    @Test
    fun `zero length swipe endpoints are still validated`() {
        val lastPixel = plan(ActionType.SWIPE, start = PixelPoint(1079, 2399), end = PixelPoint(0, 0), displayWidth = 1080, displayHeight = 2400)
        assertEquals(0, lastPixel.strokes.single().endX)
    }

    @Test
    fun `a stale display size is refused rather than guessed`() {
        val failure = planFailure(ActionType.TAP, start = PixelPoint(1, 1), displayWidth = 0, displayHeight = 0)
        assertEquals(RejectionCode.STALE_GEOMETRY, failure.code)
    }

    @Test
    fun `swipe without an end point is refused`() {
        val failure = planFailure(ActionType.SWIPE, start = PixelPoint(10, 10), displayWidth = 100, displayHeight = 100)
        assertEquals(RejectionCode.MISSING_COORDINATES, failure.code)
    }

    @Test
    fun `release is refused because the controller cannot continue an earlier stroke`() {
        val failure = planFailure(ActionType.RELEASE, start = PixelPoint(10, 10), displayWidth = 100, displayHeight = 100)
        assertEquals(RejectionCode.UNSUPPORTED_BY_CONTROLLER, failure.code)
    }

    @Test
    fun `actions that are not gestures are refused`() {
        listOf(ActionType.WAIT, ActionType.STOP).forEach { type ->
            val action = ValidatedAction(
                id = 1L,
                decisionId = 1L,
                actionType = type,
                start = null,
                end = null,
                durationMs = null,
                validatedAtMs = 1_000L,
                provenance = "test",
            )
            val result = GesturePlanner.plan(action, displayWidth = 100, displayHeight = 100)
            assertTrue(result.isFailure, "$type must not produce a gesture")
            assertEquals(RejectionCode.UNSUPPORTED_ACTION, (result.exceptionOrNull() as GesturePlanException).code)
        }
    }

    private fun plan(
        type: ActionType,
        start: PixelPoint,
        end: PixelPoint? = null,
        durationMs: Long? = null,
        displayWidth: Int = 1080,
        displayHeight: Int = 2400,
    ): GesturePlan {
        val result = GesturePlanner.plan(action(type, start, end, durationMs), displayWidth, displayHeight)
        return result.getOrElse { throw AssertionError("expected a plan for $type but got ${it.message}") }
    }

    private fun planFailure(
        type: ActionType,
        start: PixelPoint,
        end: PixelPoint? = null,
        durationMs: Long? = null,
        displayWidth: Int = 1080,
        displayHeight: Int = 2400,
    ): GesturePlanException {
        val result = GesturePlanner.plan(action(type, start, end, durationMs), displayWidth, displayHeight)
        assertTrue(result.isFailure, "expected $type to be refused")
        return result.exceptionOrNull() as GesturePlanException
    }

    private fun action(type: ActionType, start: PixelPoint?, end: PixelPoint?, durationMs: Long?) = ValidatedAction(
        id = 1L,
        decisionId = 1L,
        actionType = type,
        start = start,
        end = end,
        durationMs = durationMs,
        validatedAtMs = 1_000L,
        provenance = "test",
    )
}

package com.gameagent.core.validation

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.PixelPoint
import com.gameagent.core.input.ControllerCapabilities
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.RejectionCode
import com.gameagent.core.model.SessionState
import com.gameagent.core.model.ValidationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionValidatorTest {

    private val enabledConfig = ValidationConfig(
        actionsEnabled = true,
        minConfidence = 0.5f,
        minGestureDurationMs = 40L,
        maxGestureDurationMs = 1_000L,
        minIntervalBetweenActionsMs = 100L,
        maxActionsPerSession = 3,
        maxGeometryAgeMs = 1_000L,
    )

    private val capabilities = ControllerCapabilities(
        mechanism = "test",
        targetScope = "tests",
        supportsTap = true,
        supportsSwipe = true,
        supportsLongPress = false,
        supportsHoldRelease = false,
    )

    private fun context(
        state: SessionState = SessionState.RUNNING,
        emergency: Boolean = false,
        start: PixelPoint? = PixelPoint(100, 200),
        end: PixelPoint? = PixelPoint(300, 400),
        geometryAgeMs: Long? = 0L,
        mappingFailure: MappingFailure? = null,
        capabilities: ControllerCapabilities? = this.capabilities,
        nowMs: Long = 10_000L,
    ) = ValidationContext(
        sessionState = state,
        emergencyStopEngaged = emergency,
        startPoint = start,
        endPoint = end,
        geometryAgeMs = geometryAgeMs,
        mappingFailure = mappingFailure,
        displayWidth = 1080,
        displayHeight = 2340,
        capabilities = capabilities,
        nowMs = nowMs,
    )

    private fun tap(
        point: NormalizedPoint? = NormalizedPoint(0.5f, 0.5f),
        confidence: Float = 0.9f,
        actionType: ActionType = ActionType.TAP,
        durationMs: Long? = null,
        endPoint: NormalizedPoint? = null,
        id: Long = 1L,
    ) = AgentDecision(
        id = id,
        actionType = actionType,
        point = point,
        endPoint = endPoint,
        durationMs = durationMs,
        confidence = confidence,
        explanation = "test decision",
        createdAtMs = 9_000L,
        source = "test",
    )

    @Test
    fun `accepts a well formed tap`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(), context())
        assertIs<ActionValidation.Accepted>(result)
        assertEquals(ActionType.TAP, result.action.actionType)
        assertEquals(PixelPoint(100, 200), result.action.start)
        assertEquals(1, validator.stats().effectorActions)
    }

    @Test
    fun `wait is a no-op and never consumes budget`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(point = null, actionType = ActionType.WAIT), context())
        assertIs<ActionValidation.NoOp>(result)
        assertEquals(0, validator.stats().effectorActions)
    }

    @Test
    fun `stop is a no-op handled by the session`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(point = null, actionType = ActionType.STOP), context())
        assertIs<ActionValidation.NoOp>(result)
        assertEquals(0, validator.stats().effectorActions)
    }

    @Test
    fun `rejects a disallowed action type`() {
        val validator = ActionValidator(enabledConfig.copy(allowedActionTypes = setOf(ActionType.WAIT)))
        val result = validator.validate(tap(), context())
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.INVALID_ACTION_TYPE, result.code)
    }

    @Test
    fun `emergency stop wins over everything else`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(), context(state = SessionState.RUNNING, emergency = true))
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.EMERGENCY_STOP, result.code)
        assertEquals(0, validator.stats().effectorActions)
    }

    @Test
    fun `input disabled rejects even a valid action`() {
        val validator = ActionValidator(enabledConfig.copy(actionsEnabled = false))
        val result = validator.validate(tap(), context())
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.ACTIONS_DISABLED, result.code)
    }

    @Test
    fun `input requires the running state`() {
        val validator = ActionValidator(enabledConfig)
        for (state in listOf(SessionState.READY, SessionState.PAUSED, SessionState.IDLE, SessionState.CAPTURING)) {
            val result = validator.validate(tap(), context(state = state))
            assertIs<ActionValidation.Rejected>(result)
            assertEquals(RejectionCode.SESSION_NOT_RUNNING, result.code, "state $state must not allow input")
        }
    }

    @Test
    fun `missing controller is rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(), context(capabilities = null))
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.NO_CONTROLLER, result.code)
    }

    @Test
    fun `capability mismatch is rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(
            tap(actionType = ActionType.HOLD, durationMs = 200L),
            context(),
        )
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.UNSUPPORTED_BY_CONTROLLER, result.code)
    }

    @Test
    fun `low confidence is rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(confidence = 0.2f), context())
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.LOW_CONFIDENCE, result.code)
    }

    @Test
    fun `missing coordinates are rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(point = null), context())
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.MISSING_COORDINATES, result.code)
    }

    @Test
    fun `coordinates that could not be mapped are rejected with the mapper's reason`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(
            tap(point = NormalizedPoint(1.5f, 0.5f)),
            context(start = null, mappingFailure = MappingFailure(RejectionCode.OUT_OF_BOUNDS, "outside unit square")),
        )
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, result.code)
    }

    @Test
    fun `stale geometry is rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(tap(), context(geometryAgeMs = 5_000L))
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.STALE_GEOMETRY, result.code)

        val absent = validator.validate(tap(), context(geometryAgeMs = null))
        assertIs<ActionValidation.Rejected>(absent)
        assertEquals(RejectionCode.STALE_GEOMETRY, absent.code)
    }

    @Test
    fun `a swipe without an end point is rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(
            tap(actionType = ActionType.SWIPE, durationMs = 200L, endPoint = null),
            context(),
        )
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.MISSING_COORDINATES, result.code)
    }

    @Test
    fun `a swipe without a duration is rejected`() {
        val validator = ActionValidator(enabledConfig)
        val result = validator.validate(
            tap(actionType = ActionType.SWIPE, durationMs = null, endPoint = NormalizedPoint(0.9f, 0.9f)),
            context(),
        )
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.MALFORMED_DECISION, result.code)
    }

    @Test
    fun `gesture durations are bounded on both ends`() {
        val validator = ActionValidator(enabledConfig)

        val tooShort = validator.validate(
            tap(actionType = ActionType.HOLD, durationMs = 5L, point = NormalizedPoint(0.5f, 0.5f)),
            context().copy(capabilities = capabilities.copy(supportsLongPress = true)),
        )
        assertIs<ActionValidation.Rejected>(tooShort)
        assertEquals(RejectionCode.DURATION_TOO_SHORT, tooShort.code)

        val tooLong = validator.validate(
            tap(actionType = ActionType.HOLD, durationMs = 9_999L, point = NormalizedPoint(0.5f, 0.5f)),
            context().copy(capabilities = capabilities.copy(supportsLongPress = true)),
        )
        assertIs<ActionValidation.Rejected>(tooLong)
        assertEquals(RejectionCode.DURATION_TOO_LONG, tooLong.code)
    }

    @Test
    fun `a coordinate carrying non-pointer action is malformed`() {
        val validator = ActionValidator(enabledConfig)
        val malformed = AgentDecision(
            id = 7L,
            actionType = ActionType.WAIT,
            point = NormalizedPoint(0.5f, 0.5f),
            confidence = 0.9f,
            explanation = "malformed",
            createdAtMs = 1L,
            source = "test",
        )
        val result = validator.validate(malformed, context())
        assertIs<ActionValidation.Rejected>(result)
        assertEquals(RejectionCode.MALFORMED_DECISION, result.code)
        assertEquals(0, validator.stats().effectorActions)
    }

    @Test
    fun `rate limiting refuses actions that arrive too quickly`() {
        val validator = ActionValidator(enabledConfig)
        assertIs<ActionValidation.Accepted>(validator.validate(tap(id = 1L), context(nowMs = 10_000L)))
        val tooSoon = validator.validate(tap(id = 2L), context(nowMs = 10_050L))
        assertIs<ActionValidation.Rejected>(tooSoon)
        assertEquals(RejectionCode.RATE_LIMITED, tooSoon.code)
        assertIs<ActionValidation.Accepted>(validator.validate(tap(id = 3L), context(nowMs = 10_200L)))
    }

    @Test
    fun `the per session action budget is enforced and can be refunded`() {
        val validator = ActionValidator(enabledConfig)
        assertIs<ActionValidation.Accepted>(validator.validate(tap(id = 1L), context(nowMs = 1_000L)))
        assertIs<ActionValidation.Accepted>(validator.validate(tap(id = 2L), context(nowMs = 2_000L)))
        assertIs<ActionValidation.Accepted>(validator.validate(tap(id = 3L), context(nowMs = 3_000L)))

        val exhausted = validator.validate(tap(id = 4L), context(nowMs = 4_000L))
        assertIs<ActionValidation.Rejected>(exhausted)
        assertEquals(RejectionCode.SESSION_ACTION_LIMIT, exhausted.code)

        validator.refundAction()
        assertIs<ActionValidation.Accepted>(validator.validate(tap(id = 5L), context(nowMs = 5_000L)))
    }

    @Test
    fun `resetSession clears counters and rejection history`() {
        val validator = ActionValidator(enabledConfig)
        validator.validate(tap(id = 1L), context(nowMs = 1_000L))
        validator.validate(tap(confidence = 0.1f, id = 2L), context(nowMs = 2_000L))
        assertEquals(1, validator.stats().effectorActions)
        assertTrue(validator.stats().rejected > 0L)

        validator.resetSession()
        val stats = validator.stats()
        assertEquals(0, stats.effectorActions)
        assertEquals(0L, stats.rejected)
        assertEquals(null, stats.lastRejectionCode)
        assertEquals(3, validator.remainingActionBudget())
    }

    @Test
    fun `validation status is tracked separately from the decision`() {
        val validator = ActionValidator(enabledConfig)
        val decision = tap(confidence = 0.1f)
        assertEquals(ValidationStatus.Pending, decision.validation)
        val result = validator.validate(decision, context())
        assertIs<ActionValidation.Rejected>(result)
        // The decision itself is immutable; the caller annotates it.
        assertEquals(ValidationStatus.Pending, decision.validation)
    }

    @Test
    fun `configuration rejects impossible limits`() {
        assertIs<IllegalArgumentException>(runCatching { ValidationConfig(minGestureDurationMs = 0L) }.exceptionOrNull())
        assertIs<IllegalArgumentException>(
            runCatching { ValidationConfig(minGestureDurationMs = 500L, maxGestureDurationMs = 100L) }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(runCatching { ValidationConfig(minConfidence = 1.5f) }.exceptionOrNull())
    }
}

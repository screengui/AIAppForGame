package com.gameagent.core.model

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.NormalizedRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelValidationTest {

    @Test
    fun `unknown is distinct from a measured zero`() {
        val unknown = MetricObservation.UNKNOWN
        assertNull(unknown.value)
        assertFalse(unknown.isKnown)

        val measuredZero = MetricObservation(value = 0f, confidence = 0.9f)
        assertEquals(0f, measuredZero.value)
        assertTrue(measuredZero.isKnown)
    }

    @Test
    fun `a value with zero confidence is treated as unknown`() {
        assertFalse(MetricObservation(value = 0.5f, confidence = 0f).isKnown)
    }

    @Test
    fun `observations reject impossible values`() {
        assertIs<IllegalArgumentException>(
            runCatching { MetricObservation(value = 1f, confidence = 1.5f) }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            runCatching { MetricObservation(value = Float.NaN, confidence = 0.5f) }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            runCatching { SkillObservation(slot = -1, confidence = 0.5f) }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            runCatching { SkillObservation(slot = 0, cooldownFraction = 2f, confidence = 0.5f) }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            runCatching {
                DetectedEntity(kind = EntityKind.ENEMY, bounds = NormalizedRect(0f, 0f, 0.1f, 0.1f), confidence = 2f)
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `unknown game state is honest`() {
        val state = GameState.unknown(
            timestampMs = 10L,
            frameId = 3L,
            frameWidth = 100,
            frameHeight = 200,
            source = "test",
            reason = "nothing detected",
        )
        assertEquals(GamePhase.UNKNOWN, state.phase)
        assertEquals(0f, state.confidence)
        assertFalse(state.hasUsableDetection)
        assertNull(state.self)
        assertTrue(state.enemies.isEmpty())
        assertTrue(state.skills.isEmpty())
        assertTrue(state.notes.contains("nothing detected"))
    }

    @Test
    fun `game state confidence is bounded`() {
        assertIs<IllegalArgumentException>(
            runCatching {
                GameState(
                    phase = GamePhase.IN_MATCH,
                    phaseConfidence = 2f,
                    confidence = 0.5f,
                    timestampMs = 0L,
                    frameId = 0L,
                    frameWidth = 1,
                    frameHeight = 1,
                    source = "t",
                )
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `most confident enemy never invents one`() {
        val state = GameState.unknown(0L, 0L, 1, 1, "t", "n")
        assertNull(state.mostConfidentEnemy())

        val withEnemies = state.copy(
            enemies = listOf(
                DetectedEntity(EntityKind.ENEMY, bounds = NormalizedRect(0f, 0f, 0.1f, 0.1f), confidence = 0.3f),
                DetectedEntity(EntityKind.ENEMY, bounds = NormalizedRect(0.5f, 0.5f, 0.6f, 0.6f), confidence = 0.8f),
            ),
        )
        assertEquals(0.8f, withEnemies.mostConfidentEnemy()?.confidence)
    }

    @Test
    fun `decisions must explain themselves and carry a bounded confidence`() {
        assertIs<IllegalArgumentException>(
            runCatching {
                AgentDecision(
                    id = 1L,
                    actionType = ActionType.TAP,
                    confidence = 0.5f,
                    explanation = "",
                    createdAtMs = 0L,
                    source = "t",
                )
            }.exceptionOrNull(),
        )
        assertIs<IllegalArgumentException>(
            runCatching {
                AgentDecision(
                    id = 1L,
                    actionType = ActionType.TAP,
                    confidence = 1.4f,
                    explanation = "x",
                    createdAtMs = 0L,
                    source = "t",
                )
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `decision summary is human readable`() {
        val decision = AgentDecision(
            id = 1L,
            actionType = ActionType.SWIPE,
            point = NormalizedPoint(0.1f, 0.2f),
            endPoint = NormalizedPoint(0.8f, 0.9f),
            durationMs = 250L,
            confidence = 0.75f,
            explanation = "engage",
            createdAtMs = 0L,
            source = "t",
        )
        val summary = decision.summary()
        assertTrue(summary.contains("SWIPE"))
        assertTrue(summary.contains("0.100"))
        assertTrue(summary.contains("250ms"))
        assertTrue(summary.contains("75%"))
    }

    @Test
    fun `decision validation defaults to pending`() {
        val decision = AgentDecision.wait(1L, 0L, "t", "nothing to do")
        assertEquals(ValidationStatus.Pending, decision.validation)
        assertEquals(ActionType.WAIT, decision.actionType)
        assertFalse(ActionType.WAIT.requiresPoint)
        assertTrue(ActionType.TAP.requiresPoint)
        assertTrue(ActionType.RELEASE.requiresPoint)
        assertFalse(ActionType.STOP.requiresPoint)
    }

    @Test
    fun `validated actions refuse coordinates on non-pointer actions`() {
        assertIs<IllegalArgumentException>(
            runCatching {
                ValidatedAction(
                    id = 1L,
                    decisionId = 1L,
                    actionType = ActionType.WAIT,
                    start = com.gameagent.core.geometry.PixelPoint(1, 1),
                    validatedAtMs = 0L,
                    provenance = "t",
                )
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `normalized geometry validates its own bounds`() {
        assertTrue(NormalizedPoint(0f, 1f).isInsideUnitSquare)
        assertFalse(NormalizedPoint(1.01f, 0f).isInsideUnitSquare)
        assertFalse(NormalizedPoint(Float.NaN, 0f).isInsideUnitSquare)
        assertEquals(NormalizedPoint(1f, 1f), NormalizedPoint(2f, 2f).clamped())

        assertTrue(NormalizedRect(0f, 0f, 1f, 1f).isInsideUnitSquare)
        assertFalse(NormalizedRect(0.5f, 0f, 0.4f, 1f).isInsideUnitSquare)
        assertEquals(NormalizedPoint(0.5f, 0.5f), NormalizedRect(0f, 0f, 1f, 1f).center)
    }
}

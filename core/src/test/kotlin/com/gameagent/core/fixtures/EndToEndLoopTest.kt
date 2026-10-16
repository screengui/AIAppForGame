package com.gameagent.core.fixtures

import com.gameagent.core.agent.DeterministicPolicyAgent
import com.gameagent.core.diagnostics.DiagnosticEventLog
import com.gameagent.core.geometry.CoordinateMapper
import com.gameagent.core.input.RecordingInputController
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.GamePhase
import com.gameagent.core.perception.HeuristicScreenPerceptionProvider
import com.gameagent.core.session.AgentSession
import com.gameagent.core.session.SessionFixture
import com.gameagent.core.testutil.TestClock
import com.gameagent.core.validation.ActionValidator
import com.gameagent.core.validation.EmergencyStop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The demonstration the whole project exists for: a frame is rendered, interpreted, turned into a
 * decision, validated, executed against the mock game loop, and the *next* frame shows the effect.
 *
 * Every component here is the production one - the only thing that is mocked is the game itself.
 */
class EndToEndLoopTest {

    private fun distance(a: com.gameagent.core.geometry.NormalizedPoint, b: com.gameagent.core.geometry.NormalizedPoint) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    @Test
    fun `the loop perceives an enemy and drives the player token into it`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        val startingScene = f.scene.value
        val enemy = startingScene.enemies.first()
        val startDistance = distance(startingScene.self, enemy)
        assertTrue(startDistance > 0.1, "the test scene should start away from the enemy")

        repeat(3) { index ->
            val scene = f.scene.value
            f.session.submitFrame(
                f.frameOf(scene, index + 1L, f.clock()),
                f.geometry(index + 1L, f.clock()),
            )
            runCurrent()
        }

        val endingScene = f.scene.value
        assertEquals(3L, endingScene.actionCount, "each frame should have produced exactly one action")
        val endDistance = distance(endingScene.self, enemy)
        assertTrue(endDistance < startDistance, "the player should have closed on the enemy")
        assertTrue(endDistance < 0.05, "the player should have reached the enemy, distance=$endDistance")

        // The decision trail is inspectable, which is what makes this verifiable rather than magic.
        val decision = assertNotNull(f.session.lastDecision.value)
        assertEquals(ActionType.SWIPE, decision.actionType)
        assertTrue(decision.validation.isAccepted)
        assertTrue(decision.explanation.contains("enemy located"))
    }

    @Test
    fun `an ability tap changes the scene and perception sees the change`() = runTest {
        val f = SessionFixture.create(backgroundScope)
        f.scene.value = TestSceneState(
            phase = GamePhase.IN_MATCH,
            healthFraction = 0.2f,
            skillReady = listOf(true, false, false),
            enemies = emptyList(),
            objective = null,
        )
        f.bringUp()

        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()

        // The scene changed because a real action was delivered through the real controller.
        assertEquals(false, f.scene.value.skillReady[0])

        // And re-perceiving the changed scene reports the new state, closing the loop.
        val perceived = HeuristicScreenPerceptionProvider(TestScenePerceptionProfile.profile)
            .detect(f.frameOf(f.scene.value, 2L, f.clock()))
        assertEquals(true, perceived.skills[0].ready == false)

        val decision = assertNotNull(f.session.lastDecision.value)
        assertEquals(ActionType.TAP, decision.actionType)
        assertTrue(decision.validation.isAccepted)
        assertTrue(decision.explanation.contains("health"))
    }

    @Test
    fun `only validated actions reach the controller and their coordinates are inside the display`() = runTest {
        val width = 360
        val height = 780
        val clock = TestClock()
        val scene = MutableStateFlow(TestSceneState(phase = GamePhase.IN_MATCH))
        val recording = RecordingInputController()
        val session = AgentSession(
            perception = HeuristicScreenPerceptionProvider(TestScenePerceptionProfile.profile),
            agent = DeterministicPolicyAgent(clock = clock),
            validator = ActionValidator(SessionFixture.defaultConfig(), clock),
            controllerProvider = { recording },
            mapper = CoordinateMapper(60_000L, clock),
            emergencyStop = EmergencyStop(clock),
            events = DiagnosticEventLog(),
            scope = backgroundScope,
            clock = clock,
        )
        session.requestPermission()
        session.captureStarting()
        session.captureReady()
        session.startAgent()

        repeat(4) { index ->
            session.submitFrame(
                SyntheticSceneRenderer.frame(scene.value, width, height, index + 1L, clock()),
                com.gameagent.core.geometry.ScreenGeometry(width, height, width, height, com.gameagent.core.geometry.DisplayRotation.ROTATION_0, index + 1L, clock()),
            )
            runCurrent()
        }

        val actions = recording.recorded()
        assertEquals(4, actions.size, "exactly the validated actions should have been delivered")
        actions.forEach { action ->
            val start = assertNotNull(action.start)
            assertTrue(start.x in 0 until width, "x=${start.x} is outside the display")
            assertTrue(start.y in 0 until height, "y=${start.y} is outside the display")
            assertTrue(action.actionType == ActionType.SWIPE)
            val end = assertNotNull(action.end)
            assertTrue(end.x in 0 until width)
            assertTrue(end.y in 0 until height)
            assertTrue(action.durationMs != null && action.durationMs!! in 40..1_000)
        }
        assertEquals(4L, session.metrics.value.actionsExecuted)
    }

    @Test
    fun `nothing is executed while the emergency stop is latched`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        f.session.triggerEmergencyStop("panic")
        runCurrent()

        repeat(3) { index ->
            f.session.submitFrame(
                f.frameOf(f.scene.value, index + 1L, f.clock()),
                f.geometry(index + 1L, f.clock()),
            )
            runCurrent()
        }

        assertEquals(0L, f.scene.value.actionCount)
        assertEquals(0L, f.session.metrics.value.actionsExecuted)

        // The queue path never even reaches a decision, so prove the latch via the direct path too.
        val outcome = f.session.analyseOnce(
            f.frameOf(f.scene.value, 9L, f.clock()),
            f.geometry(9L, f.clock()),
        )
        val observed = outcome as com.gameagent.core.session.StepOutcome.Observed
        val rejected = observed.validation as com.gameagent.core.validation.ActionValidation.Rejected
        assertEquals(com.gameagent.core.model.RejectionCode.EMERGENCY_STOP, rejected.code)
        assertEquals(0L, f.scene.value.actionCount)
    }
}

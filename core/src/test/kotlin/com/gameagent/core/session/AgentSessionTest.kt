package com.gameagent.core.session

import com.gameagent.core.agent.DeterministicPolicyAgent
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.diagnostics.EventLevel
import com.gameagent.core.fixtures.SyntheticSceneRenderer
import com.gameagent.core.fixtures.TestSceneState
import com.gameagent.core.geometry.NormalizedRect
import com.gameagent.core.model.DetectedEntity
import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.GameState
import com.gameagent.core.model.RejectionCode
import com.gameagent.core.model.SessionState
import com.gameagent.core.perception.FailingPerceptionProvider
import com.gameagent.core.perception.ScriptedPerceptionProvider
import com.gameagent.core.provider.ProviderResponse
import com.gameagent.core.provider.RemoteVisionAgent
import com.gameagent.core.testutil.StubVisionProvider
import com.gameagent.core.testutil.TestClock
import com.gameagent.core.validation.ActionValidation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTest {

    // ---------------------------------------------------------------- scenario 1

    @Test
    fun `permission granted brings the session to ready`() = runTest {
        val f = SessionFixture.create(backgroundScope)

        assertTrue(f.session.requestPermission())
        assertEquals(SessionState.REQUESTING_PERMISSION, f.session.state.value)
        assertTrue(f.session.captureStarting())
        assertEquals(SessionState.CAPTURING, f.session.state.value)
        assertTrue(f.session.captureReady())
        assertEquals(SessionState.READY, f.session.state.value)
        assertTrue(f.session.startAgent())
        assertEquals(SessionState.RUNNING, f.session.state.value)
    }

    // ---------------------------------------------------------------- scenario 2

    @Test
    fun `permission denied returns to idle with a warning`() = runTest {
        val f = SessionFixture.create(backgroundScope)
        f.session.requestPermission()

        assertTrue(f.session.permissionDenied("user dismissed the dialog"))
        assertEquals(SessionState.IDLE, f.session.state.value)
        assertTrue(
            f.events.snapshot().any { it.level == EventLevel.WARN && it.category == EventCategory.CAPTURE },
            "denial must be logged",
        )
    }

    // ---------------------------------------------------------------- scenario 3

    @Test
    fun `running one frame executes exactly one validated action`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        val scene = f.scene.value

        f.session.submitFrame(f.frameOf(scene, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()

        assertEquals(1L, f.scene.value.actionCount, "the test scene should have received one action")
        assertEquals(SessionState.RUNNING, f.session.state.value)
        val metrics = f.session.metrics.value
        assertEquals(1L, metrics.observationsProcessed)
        assertEquals(1L, metrics.decisionsMade)
        assertEquals(1L, metrics.actionsAccepted)
        assertEquals(1L, metrics.actionsExecuted)
        assertEquals(0L, metrics.actionsRejected)
        assertTrue(metrics.perception.samples == 1L)
    }

    @Test
    fun `stopping drains the queue, releases input and returns to idle`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()
        val actionsBeforeStop = f.scene.value.actionCount

        f.session.stop("user pressed stop")
        assertEquals(SessionState.IDLE, f.session.state.value)
        runCurrent()

        // Further frames are not interpreted at all.
        f.session.submitFrame(f.frameOf(f.scene.value, 2L, f.clock()), f.geometry(2L, f.clock()))
        runCurrent()
        assertEquals(actionsBeforeStop, f.scene.value.actionCount)
        assertTrue(f.session.framesSkipped() >= 1L)
    }

    // ---------------------------------------------------------------- scenario 4

    @Test
    fun `a stale capture snapshot is refused instead of acted on`() = runTest {
        val clock = TestClock()
        val f = SessionFixture.create(backgroundScope, clock = clock, mapperMaxAgeMs = 5_000L).bringUp()
        val staleAt = clock() - 30_000L

        val outcome = f.session.analyseOnce(
            f.frameOf(f.scene.value, 1L, staleAt),
            f.geometry(1L, staleAt),
        )

        val observed = assertIs<StepOutcome.Observed>(outcome)
        val rejected = assertIs<ActionValidation.Rejected>(observed.validation)
        assertEquals(RejectionCode.STALE_GEOMETRY, rejected.code)
        assertEquals(0L, f.scene.value.actionCount)
    }

    @Test
    fun `no capture yet means no geometry and therefore no action`() = runTest {
        val f = SessionFixture.create(backgroundScope)
        assertNull(f.mapper.current())
        f.bringUp()
        // Nothing was ever submitted, so the mapper has no snapshot for a decision to use.
        assertNull(f.mapper.current())
        assertEquals(0L, f.scene.value.actionCount)
    }

    // ---------------------------------------------------------------- scenario 5 + 6

    private fun hostileState(selfCenter: Float, enemyCenter: Float) = GameState(
        phase = GamePhase.IN_MATCH,
        phaseConfidence = 0.9f,
        health = com.gameagent.core.model.MetricObservation(1f, 0.9f),
        self = DetectedEntity(
            kind = EntityKind.SELF,
            bounds = NormalizedRect(selfCenter - 0.02f, 0.5f, selfCenter + 0.02f, 0.54f),
            confidence = 0.9f,
        ),
        enemies = listOf(
            DetectedEntity(
                kind = EntityKind.ENEMY,
                bounds = NormalizedRect(enemyCenter - 0.02f, 0.38f, enemyCenter + 0.02f, 0.42f),
                confidence = 0.9f,
            ),
        ),
        confidence = 0.9f,
        timestampMs = 0L,
        frameId = 1L,
        frameWidth = SessionFixture.DEFAULT_WIDTH,
        frameHeight = SessionFixture.DEFAULT_HEIGHT,
        source = "synthetic-provider",
    )

    @Test
    fun `out of bounds coordinates coming from a provider are rejected, not clamped`() = runTest {
        val hostile = ScriptedPerceptionProvider(listOf(hostileState(selfCenter = 0.5f, enemyCenter = 1.15f)))
        val f = SessionFixture.create(backgroundScope, perception = hostile).bringUp()

        val outcome = f.session.analyseOnce(
            f.frameOf(f.scene.value, 1L, f.clock()),
            f.geometry(1L, f.clock()),
        )

        val observed = assertIs<StepOutcome.Observed>(outcome)
        val rejected = assertIs<ActionValidation.Rejected>(observed.validation)
        assertEquals(RejectionCode.OUT_OF_BOUNDS, rejected.code)
        assertEquals(0L, f.scene.value.actionCount, "an impossible target must never be turned into a tap")
    }

    @Test
    fun `a decision that cannot be trusted is reported as rejected in the outcome`() = runTest {
        val hostile = ScriptedPerceptionProvider(listOf(hostileState(selfCenter = 0.5f, enemyCenter = 1.15f)))
        val f = SessionFixture.create(backgroundScope, perception = hostile).bringUp()

        val outcome = f.session.analyseOnce(
            f.frameOf(f.scene.value, 1L, f.clock()),
            f.geometry(1L, f.clock()),
        ) as StepOutcome.Observed

        assertEquals(false, outcome.decision.validation.isAccepted)
        assertEquals(1L, f.session.metrics.value.actionsRejected)
    }

    @Test
    fun `perception failure is surfaced and executes nothing`() = runTest {
        val f = SessionFixture.create(backgroundScope, perception = FailingPerceptionProvider(reason = "model offline"))
            .bringUp()

        val outcome = f.session.analyseOnce(
            f.frameOf(f.scene.value, 1L, f.clock()),
            f.geometry(1L, f.clock()),
        )

        val failed = assertIs<StepOutcome.PerceptionFailed>(outcome)
        assertEquals("model offline", failed.reason)
        assertEquals(0L, f.scene.value.actionCount)
        assertEquals(1L, f.session.metrics.value.observationsFailed)
        assertTrue(f.events.snapshot().any { it.category == EventCategory.PERCEPTION })
    }

    // ---------------------------------------------------------------- scenario 7

    @Test
    fun `emergency stop halts everything immediately and latches`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()
        val actionsBefore = f.scene.value.actionCount
        assertEquals(1L, actionsBefore)

        assertTrue(f.session.triggerEmergencyStop("user pressed the panic button"))
        runCurrent()

        assertTrue(f.emergencyStop.isEngaged)
        assertEquals(SessionState.IDLE, f.session.state.value)
        assertTrue(f.events.snapshot().any { it.level == EventLevel.ERROR })

        // Every later decision is refused, even if the session is somehow resumed.
        val outcome = f.session.analyseOnce(
            f.frameOf(f.scene.value, 2L, f.clock()),
            f.geometry(2L, f.clock()),
        ) as StepOutcome.Observed
        val rejected = assertIs<ActionValidation.Rejected>(outcome.validation)
        assertEquals(RejectionCode.EMERGENCY_STOP, rejected.code)
        assertEquals(actionsBefore, f.scene.value.actionCount)

        // The latch is sticky: a second press changes nothing.
        assertFalse(f.session.triggerEmergencyStop("pressed again"))
        assertTrue(f.emergencyStop.isEngaged)
    }

    @Test
    fun `clearing the emergency stop allows an explicit new session`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        f.session.triggerEmergencyStop("panic")
        assertEquals(SessionState.IDLE, f.session.state.value)

        assertTrue(f.session.resetEmergencyStop())
        assertFalse(f.emergencyStop.isEngaged)

        f.bringUp()
        assertEquals(SessionState.RUNNING, f.session.state.value)
        f.session.submitFrame(f.frameOf(f.scene.value, 3L, f.clock()), f.geometry(3L, f.clock()))
        runCurrent()
        assertEquals(1L, f.scene.value.actionCount)
    }

    // ---------------------------------------------------------------- scenario 8

    @Test
    fun `pausing stops interpretation and no action is executed`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        assertTrue(f.session.pause())
        assertEquals(SessionState.PAUSED, f.session.state.value)

        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()

        assertEquals(0L, f.scene.value.actionCount)
        assertTrue(f.session.framesSkipped() >= 1L)

        assertTrue(f.session.resume())
        f.session.submitFrame(f.frameOf(f.scene.value, 2L, f.clock()), f.geometry(2L, f.clock()))
        runCurrent()
        assertEquals(1L, f.scene.value.actionCount)
    }

    // ---------------------------------------------------------------- scenario 9

    @Test
    fun `a screen size change is picked up on the next frame`() = runTest {
        val f = SessionFixture.create(backgroundScope, scenePhase = GamePhase.IN_MATCH).bringUp()

        // Portrait frame first.
        f.session.submitFrame(
            f.frameOf(f.scene.value, 1L, f.clock()),
            f.geometry(1L, f.clock(), w = 360, h = 780),
        )
        runCurrent()
        assertEquals(360, f.mapper.current()?.displayWidth)

        // The display rotates: the capture layer publishes new geometry and a new frame.
        val landscape = TestSceneState(phase = GamePhase.IN_MATCH, enemies = emptyList(), allies = emptyList())
        f.session.submitFrame(
            SyntheticSceneRenderer.frame(landscape, 780, 360, 2L, f.clock()),
            f.geometry(2L, f.clock(), w = 780, h = 360),
        )
        runCurrent()

        assertEquals(780, f.mapper.current()?.displayWidth)
        assertEquals(360, f.mapper.current()?.displayHeight)
        val action = f.validator.stats()
        assertTrue(action.effectorActions >= 1, "a tap on the objective should have been accepted")
    }

    // ---------------------------------------------------------------- scenario 10

    @Test
    fun `an unavailable AI provider falls back and the session keeps working`() = runTest {
        val provider = StubVisionProvider(ProviderResponse.Unavailable("provider unreachable"))
        val agent = RemoteVisionAgent(
            provider = provider,
            fallback = DeterministicPolicyAgent(),
            events = com.gameagent.core.diagnostics.DiagnosticEventLog(),
        )
        val f = SessionFixture.create(backgroundScope, agent = agent).bringUp()

        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()

        assertEquals(1, provider.calls)
        assertEquals(1L, f.scene.value.actionCount, "the fallback policy must still drive the loop")
    }

    @Test
    fun `an invalid AI response falls back and the session keeps working`() = runTest {
        val provider = StubVisionProvider(ProviderResponse.Invalid("malformed json", "not json"))
        val agent = RemoteVisionAgent(
            provider = provider,
            fallback = DeterministicPolicyAgent(),
            events = com.gameagent.core.diagnostics.DiagnosticEventLog(),
        )
        val f = SessionFixture.create(backgroundScope, agent = agent).bringUp()
        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()
        assertEquals(1L, f.scene.value.actionCount)
    }

    // ---------------------------------------------------------------- scenario 11

    @Test
    fun `repeated start and stop operations are safe`() = runTest {
        val f = SessionFixture.create(backgroundScope)

        repeat(2) { round ->
            f.bringUp()
            assertEquals(SessionState.RUNNING, f.session.state.value, "round $round")

            assertFalse(f.session.startAgent(), "a second start must be refused")
            assertEquals(SessionState.RUNNING, f.session.state.value)

            f.session.stop("round $round stop")
            assertEquals(SessionState.IDLE, f.session.state.value)

            // A second stop is an explicit no-op, not an error and not a state change.
            f.session.stop("round $round stop again")
            assertEquals(SessionState.IDLE, f.session.state.value)
        }
    }

    // ---------------------------------------------------------------- scenario 12

    @Test
    fun `an interrupted session leaves no residue for the next one`() = runTest {
        // The first session is abandoned while capturing, as if the process had been killed.
        val abandoned = SessionFixture.create(backgroundScope)
        abandoned.session.requestPermission()
        abandoned.session.captureStarting()
        assertEquals(SessionState.CAPTURING, abandoned.session.state.value)
        abandoned.session.submitFrame(
            abandoned.frameOf(abandoned.scene.value, 1L, abandoned.clock()),
            abandoned.geometry(1L, abandoned.clock()),
        )

        // A brand new session starts clean: no geometry, no budget consumed, no state carried over.
        val fresh = SessionFixture.create(backgroundScope)
        assertEquals(SessionState.IDLE, fresh.session.state.value)
        assertNull(fresh.mapper.current())
        assertEquals(0, fresh.validator.stats().effectorActions)
        assertEquals(0L, fresh.session.metrics.value.actionsExecuted)

        fresh.bringUp()
        fresh.session.submitFrame(fresh.frameOf(fresh.scene.value, 1L, fresh.clock()), fresh.geometry(1L, fresh.clock()))
        runCurrent()
        assertEquals(1L, fresh.scene.value.actionCount)
    }

    // ---------------------------------------------------------------- back-pressure

    @Test
    fun `frames are dropped rather than buffered when the pipeline is behind`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()

        repeat(5) { index ->
            f.session.submitFrame(
                f.frameOf(f.scene.value, index.toLong(), f.clock()),
                f.geometry(index.toLong(), f.clock()),
            )
        }
        // The worker has not run yet, so the two-slot queue must already have discarded frames.
        // The exact count depends on channel hand-off timing, so assert the observable property:
        // history is dropped instead of being queued up.
        assertTrue(
            f.session.metrics.value.capture.framesDropped >= 1L,
            "expected the frame queue to drop stale frames, dropped=${f.session.metrics.value.capture.framesDropped}",
        )

        runCurrent()
        assertTrue(
            f.scene.value.actionCount < 5L,
            "not every rapid frame should have been acted on, saw ${f.scene.value.actionCount}",
        )
    }

    @Test
    fun `validation bookkeeping reflects what actually happened`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        f.session.submitFrame(f.frameOf(f.scene.value, 1L, f.clock()), f.geometry(1L, f.clock()))
        runCurrent()

        val stats = f.validator.stats()
        assertEquals(1, stats.effectorActions)
        assertEquals(50, stats.maxActionsPerSession)
        assertEquals(49, f.validator.remainingActionBudget())
        assertEquals(0L, stats.rejected)
    }

    @Test
    fun `describe exposes the active wiring for diagnostics`() = runTest {
        val f = SessionFixture.create(backgroundScope).bringUp()
        val description = f.session.describe()
        assertTrue(description.containsKey("perception"))
        assertTrue(description.containsKey("agent"))
        assertEquals(SessionState.RUNNING.name, description["state"])
    }
}

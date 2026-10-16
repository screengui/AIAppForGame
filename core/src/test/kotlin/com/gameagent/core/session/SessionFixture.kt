package com.gameagent.core.session

import com.gameagent.core.agent.DeterministicPolicyAgent
import com.gameagent.core.agent.GameAgent
import com.gameagent.core.diagnostics.DiagnosticEventLog
import com.gameagent.core.fixtures.SyntheticSceneRenderer
import com.gameagent.core.fixtures.TestSceneInputController
import com.gameagent.core.fixtures.TestScenePerceptionProfile
import com.gameagent.core.fixtures.TestSceneState
import com.gameagent.core.geometry.CoordinateMapper
import com.gameagent.core.geometry.DisplayRotation
import com.gameagent.core.geometry.ScreenGeometry
import com.gameagent.core.model.GamePhase
import com.gameagent.core.perception.HeuristicScreenPerceptionProvider
import com.gameagent.core.perception.ImageFrame
import com.gameagent.core.perception.PerceptionProvider
import com.gameagent.core.testutil.TestClock
import com.gameagent.core.validation.ActionValidator
import com.gameagent.core.validation.EmergencyStop
import com.gameagent.core.validation.ValidationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Builds a fully wired [AgentSession] for tests: real perception profile, real policy, real
 * validator and the real in-app test controller, so the tests exercise the production wiring rather
 * than a simplified stand-in.
 */
class SessionFixture(
    val session: AgentSession,
    val scene: MutableStateFlow<TestSceneState>,
    val controller: TestSceneInputController,
    val validator: ActionValidator,
    val mapper: CoordinateMapper,
    val emergencyStop: EmergencyStop,
    val events: DiagnosticEventLog,
    val clock: TestClock,
    val width: Int,
    val height: Int,
) {
    fun geometry(frameId: Long, atMs: Long, w: Int = width, h: Int = height) = ScreenGeometry(
        captureWidth = w,
        captureHeight = h,
        displayWidth = w,
        displayHeight = h,
        rotation = if (w >= h) DisplayRotation.ROTATION_90 else DisplayRotation.ROTATION_0,
        frameId = frameId,
        capturedAtMs = atMs,
    )

    fun frameOf(state: TestSceneState, frameId: Long, atMs: Long, w: Int = width, h: Int = height): ImageFrame =
        SyntheticSceneRenderer.frame(state, w, h, frameId, atMs)

    /** Drives the session from IDLE all the way to RUNNING the way the app does. */
    fun bringUp(): SessionFixture {
        session.requestPermission()
        session.captureStarting()
        session.captureReady()
        session.startAgent()
        return this
    }

    companion object {
        const val DEFAULT_WIDTH = 360
        const val DEFAULT_HEIGHT = 780

        fun defaultConfig(maxGeometryAgeMs: Long = 60_000L) = ValidationConfig(
            actionsEnabled = true,
            minConfidence = 0.40f,
            minGestureDurationMs = 40L,
            maxGestureDurationMs = 1_000L,
            minIntervalBetweenActionsMs = 0L,
            maxActionsPerSession = 50,
            maxGeometryAgeMs = maxGeometryAgeMs,
        )

        fun create(
            scope: CoroutineScope,
            clock: TestClock = TestClock(),
            perception: PerceptionProvider = HeuristicScreenPerceptionProvider(TestScenePerceptionProfile.profile),
            agent: GameAgent = DeterministicPolicyAgent(clock = clock),
            config: ValidationConfig = defaultConfig(),
            mapperMaxAgeMs: Long = 60_000L,
            scenePhase: GamePhase = GamePhase.IN_MATCH,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            controller: TestSceneInputController? = null,
        ): SessionFixture {
            val scene = MutableStateFlow(TestSceneState(phase = scenePhase))
            val inputController = controller ?: TestSceneInputController(
                sceneState = scene,
                frameSizeProvider = { width to height },
            )
            val validator = ActionValidator(config, clock)
            val mapper = CoordinateMapper(mapperMaxAgeMs, clock)
            val emergencyStop = EmergencyStop(clock)
            val events = DiagnosticEventLog()
            val session = AgentSession(
                perception = perception,
                agent = agent,
                validator = validator,
                controllerProvider = { inputController },
                mapper = mapper,
                emergencyStop = emergencyStop,
                events = events,
                scope = scope,
                clock = clock,
            )
            return SessionFixture(
                session = session,
                scene = scene,
                controller = inputController,
                validator = validator,
                mapper = mapper,
                emergencyStop = emergencyStop,
                events = events,
                clock = clock,
                width = width,
                height = height,
            )
        }
    }
}

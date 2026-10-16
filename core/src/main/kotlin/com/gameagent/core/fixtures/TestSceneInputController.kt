package com.gameagent.core.fixtures

import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.diagnostics.NoOpEventSink
import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.PixelPoint
import com.gameagent.core.input.ControllerCapabilities
import com.gameagent.core.input.ExecutionOutcome
import com.gameagent.core.input.InputController
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.ValidatedAction
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The first *working* controller: it drives the companion's own test screen.
 *
 * This exists because Android gives third-party apps no supported way to inject touches into
 * another app's window except a user-enabled AccessibilityService. Rather than shipping something
 * that only pretends to work, the project starts with a controller that provably delivers real
 * actions into a real game loop - the built-in test scene - and proves the whole
 * capture -> perceive -> decide -> validate -> act -> observe loop in a controlled environment.
 *
 * Coordinates arrive as display pixels (produced by the mapper) and are converted back to
 * normalized scene coordinates using the frame size that was actually rendered, so a round trip
 * through pixel space is exercised rather than bypassed.
 */
class TestSceneInputController(
    private val sceneState: MutableStateFlow<TestSceneState>,
    private val frameSizeProvider: () -> Pair<Int, Int>,
    override val id: String = "test-scene",
    private val events: DiagnosticEventSink = NoOpEventSink,
) : InputController {

    override val displayName: String = "In-app test scene"

    override val capabilities: ControllerCapabilities = ControllerCapabilities(
        mechanism = "in-app-test-scene",
        targetScope = "this app's own test screen only",
        supportsTap = true,
        supportsSwipe = true,
        supportsLongPress = true,
        supportsHoldRelease = true,
        supportsMultitouch = false,
        targetsOtherApps = false,
        requiresUserEnablement = false,
        notes = listOf(
            "Delivers validated actions to the companion's own mock game loop.",
            "Cannot reach other apps; that requires the accessibility controller.",
            "Single pointer only, matching the AccessibilityService limitation.",
        ),
    )

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: ValidatedAction): ExecutionOutcome {
        val (width, height) = frameSizeProvider()
        if (width <= 1 || height <= 1) {
            return ExecutionOutcome.Failed("test scene frame size is not known yet (${width}x$height)")
        }

        val sceneAction = when (action.actionType) {
            ActionType.TAP -> action.start?.let { TestSceneAction.Tap(it.toNormalized(width, height)) }
            ActionType.SWIPE -> {
                val start = action.start
                val end = action.end
                if (start == null || end == null) null
                else TestSceneAction.Swipe(
                    from = start.toNormalized(width, height),
                    to = end.toNormalized(width, height),
                    durationMs = action.durationMs ?: 0L,
                )
            }

            ActionType.HOLD -> action.start?.let { TestSceneAction.Hold(it.toNormalized(width, height)) }
            ActionType.RELEASE -> action.start?.let { TestSceneAction.Release(it.toNormalized(width, height)) }
            else -> null
        } ?: return ExecutionOutcome.Unsupported("test scene cannot perform ${action.actionType}")

        val result = TestSceneReducer.reduce(sceneState.value, sceneAction)
        sceneState.value = result.state
        events.debug(
            EventCategory.INPUT,
            "test scene action delivered",
            mapOf(
                "action" to action.actionType.name,
                "effect" to result.effect.name,
                "description" to result.description,
            ),
        )
        return if (result.applied) {
            ExecutionOutcome.Completed
        } else {
            // Delivered, but the mock game had nothing to apply it to. Reported honestly rather
            // than as a success, because the caller may want to react to it.
            ExecutionOutcome.Failed("test scene ignored the action: ${result.description}")
        }
    }

    override suspend fun cancelActive(): Boolean {
        val state = sceneState.value
        if (!state.holdActive) return false
        sceneState.value = state.copy(holdActive = false).withEvent("active hold cancelled")
        return true
    }

    override fun release() {
        if (sceneState.value.holdActive) {
            sceneState.value = sceneState.value.copy(holdActive = false).withEvent("hold released on session end")
        }
    }

    /** Inverse of [com.gameagent.core.geometry.CoordinateMapper]'s pixel mapping. */
    private fun PixelPoint.toNormalized(width: Int, height: Int): NormalizedPoint {
        val maxX = (width - 1).coerceAtLeast(1)
        val maxY = (height - 1).coerceAtLeast(1)
        return NormalizedPoint(
            x = (x.toFloat() / maxX.toFloat()).coerceIn(0f, 1f),
            y = (y.toFloat() / maxY.toFloat()).coerceIn(0f, 1f),
        )
    }
}

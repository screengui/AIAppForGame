package com.gameagent.core.fixtures

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.model.GamePhase
import kotlin.math.abs
import kotlin.math.hypot

/** Immutable snapshot of the built-in test scene. */
data class TestSceneState(
    val phase: GamePhase = GamePhase.MENU,
    val healthFraction: Float = 1f,
    val manaFraction: Float = 0.75f,
    val skillReady: List<Boolean> = listOf(true, true, false),
    val self: NormalizedPoint = NormalizedPoint(0.5f, 0.55f),
    val allies: List<NormalizedPoint> = listOf(NormalizedPoint(0.26f, 0.50f), NormalizedPoint(0.60f, 0.72f)),
    val enemies: List<NormalizedPoint> = listOf(NormalizedPoint(0.72f, 0.42f), NormalizedPoint(0.34f, 0.68f)),
    val objective: NormalizedPoint? = NormalizedPoint(0.86f, 0.40f),
    val holdActive: Boolean = false,
    val lastTap: NormalizedPoint? = null,
    val lastSwipe: SwipeTrace? = null,
    val actionCount: Long = 0L,
    val eventLog: List<String> = emptyList(),
) {
    val readySkillCount: Int get() = skillReady.count { it }

    val enemyCount: Int get() = enemies.size

    fun withEvent(message: String): TestSceneState = copy(eventLog = (eventLog + message).takeLast(MAX_EVENT_LOG))

    companion object {
        const val MAX_EVENT_LOG = 24
    }
}

data class SwipeTrace(val from: NormalizedPoint, val to: NormalizedPoint, val durationMs: Long)

/** An input the test controller can deliver to the test scene. */
sealed interface TestSceneAction {
    data class Tap(val point: NormalizedPoint) : TestSceneAction
    data class Swipe(val from: NormalizedPoint, val to: NormalizedPoint, val durationMs: Long) : TestSceneAction
    data class Hold(val point: NormalizedPoint) : TestSceneAction
    data class Release(val point: NormalizedPoint) : TestSceneAction
}

/** What a scene action actually did. [applied] is false when the action was ignored or out of range. */
data class SceneActionResult(
    val applied: Boolean,
    val effect: SceneEffect,
    val description: String,
    val state: TestSceneState,
)

enum class SceneEffect {
    NONE,
    MOVE,
    SKILL_TOGGLE,
    ENEMY_ELIMINATED,
    MINIMAP_PING,
    HOLD_START,
    HOLD_END,
    IGNORED,
}

/**
 * Pure, deterministic reducer that applies an action to the test scene.
 *
 * This is the "game" the companion app plays against. It has no timers and no randomness, so a
 * given (state, action) pair always produces the same result - which is what makes the whole
 * capture -> perceive -> decide -> validate -> act loop reproducible in tests.
 */
object TestSceneReducer {

    fun reduce(state: TestSceneState, action: TestSceneAction): SceneActionResult {
        val incremented = state.copy(actionCount = state.actionCount + 1)
        return when (action) {
            is TestSceneAction.Tap -> reduceTap(incremented, action.point)
            is TestSceneAction.Swipe -> reduceSwipe(incremented, action)
            is TestSceneAction.Hold -> reduceHold(incremented, action.point)
            is TestSceneAction.Release -> reduceRelease(incremented, action.point)
        }
    }

    private fun reduceTap(state: TestSceneState, raw: NormalizedPoint): SceneActionResult {
        val point = raw.clamped()
        state.skillReady.forEachIndexed { index, ready ->
            if (TestSceneLayout.skillButtons[index].contains(point)) {
                val toggled = state.skillReady.toMutableList().also { it[index] = !ready }
                val name = TestSceneLayout.skillNames.getOrElse(index) { "skill_$index" }
                val next = state.copy(
                    skillReady = toggled,
                    lastTap = point,
                ).withEvent("tap $name -> ${if (!ready) "fired" else "unavailable"}")
                return SceneActionResult(
                    applied = true,
                    effect = SceneEffect.SKILL_TOGGLE,
                    description = "$name ${if (!ready) "fired" else "on cooldown"}",
                    state = next,
                )
            }
        }

        val enemyIndex = state.enemies.indexOfFirst { it.withinHitBox(point) }
        if (enemyIndex >= 0) {
            val remaining = state.enemies.toMutableList().also { it.removeAt(enemyIndex) }
            val next = state.copy(enemies = remaining, lastTap = point)
                .withEvent("tap enemy eliminated (${remaining.size} left)")
            return SceneActionResult(true, SceneEffect.ENEMY_ELIMINATED, "enemy eliminated", next)
        }

        if (TestSceneLayout.minimap.contains(point)) {
            val next = state.copy(lastTap = point).withEvent("minimap ping at (%.2f, %.2f)".format(point.x, point.y))
            return SceneActionResult(true, SceneEffect.MINIMAP_PING, "minimap ping", next)
        }

        if (!TestSceneLayout.arena.contains(point)) {
            val next = state.copy(lastTap = point).withEvent("tap outside arena ignored")
            return SceneActionResult(false, SceneEffect.IGNORED, "tap outside the arena", next)
        }

        val moved = state.moveToward(point, TestSceneLayout.MOVE_STEP)
        return SceneActionResult(
            applied = true,
            effect = SceneEffect.MOVE,
            description = "moved to (%.2f, %.2f)".format(moved.self.x, moved.self.y),
            state = moved.copy(lastTap = point)
                .withEvent("tap-to-move to (%.2f, %.2f)".format(point.x, point.y)),
        )
    }

    private fun reduceSwipe(state: TestSceneState, action: TestSceneAction.Swipe): SceneActionResult {
        val target = action.to.clamped()
        if (!TestSceneLayout.arena.contains(target) && !TestSceneLayout.arena.contains(action.from.clamped())) {
            val next = state.copy(lastSwipe = SwipeTrace(action.from, action.to, action.durationMs))
                .withEvent("swipe outside arena ignored")
            return SceneActionResult(false, SceneEffect.IGNORED, "swipe started outside the arena", next)
        }
        val moved = state.moveToward(target, TestSceneLayout.MOVE_STEP * TestSceneLayout.SWIPE_MOVE_FACTOR)
        val distance = hypot((target.x - action.from.x).toDouble(), (target.y - action.from.y).toDouble())
        val next = moved.copy(
            lastSwipe = SwipeTrace(action.from, action.to, action.durationMs),
            holdActive = false,
        ).withEvent("swipe len=%.3f -> (%.2f, %.2f)".format(distance, moved.self.x, moved.self.y))
        return SceneActionResult(true, SceneEffect.MOVE, "swiped to (%.2f, %.2f)".format(moved.self.x, moved.self.y), next)
    }

    private fun reduceHold(state: TestSceneState, raw: NormalizedPoint): SceneActionResult {
        val point = raw.clamped()
        if (!TestSceneLayout.arena.contains(point)) {
            val next = state.withEvent("hold outside arena ignored")
            return SceneActionResult(false, SceneEffect.IGNORED, "hold outside the arena", next)
        }
        val moved = state.moveToward(point, TestSceneLayout.MOVE_STEP)
        val next = moved.copy(holdActive = true).withEvent("hold active, moving toward (%.2f, %.2f)".format(point.x, point.y))
        return SceneActionResult(true, SceneEffect.HOLD_START, "holding", next)
    }

    private fun reduceRelease(state: TestSceneState, raw: NormalizedPoint): SceneActionResult {
        val point = raw.clamped()
        if (!state.holdActive) {
            val next = state.copy(lastTap = point).withEvent("release with no active hold ignored")
            return SceneActionResult(false, SceneEffect.IGNORED, "no active hold to release", next)
        }
        val next = state.copy(holdActive = false).withEvent("hold released")
        return SceneActionResult(true, SceneEffect.HOLD_END, "released", next)
    }

    private fun TestSceneState.moveToward(target: NormalizedPoint, step: Float): TestSceneState {
        val dx = target.x - self.x
        val dy = target.y - self.y
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance <= step || distance <= 0.0001f) {
            return copy(self = clampToArena(target))
        }
        val scale = step / distance
        return copy(self = clampToArena(NormalizedPoint(self.x + dx * scale, self.y + dy * scale)))
    }

    private fun clampToArena(point: NormalizedPoint): NormalizedPoint {
        val arena = TestSceneLayout.arena
        return NormalizedPoint(
            point.x.coerceIn(arena.left, arena.right),
            point.y.coerceIn(arena.top, arena.bottom),
        )
    }

    private fun NormalizedPoint.withinHitBox(other: NormalizedPoint): Boolean =
        abs(other.x - x) <= TestSceneLayout.MARKER_HIT_HALF && abs(other.y - y) <= TestSceneLayout.MARKER_HIT_HALF

    private fun com.gameagent.core.geometry.NormalizedRect.contains(point: NormalizedPoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}

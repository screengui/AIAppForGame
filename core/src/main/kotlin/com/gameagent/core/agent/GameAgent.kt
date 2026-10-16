package com.gameagent.core.agent

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.GameState
import com.gameagent.core.model.TargetRef
import java.util.Locale

/**
 * Chooses an action from an observation.
 *
 * Implementations must be pure with respect to the observation (same input -> same output) unless
 * they are explicitly network-backed, and they must never return anything other than [ActionType].
 * Nothing an agent returns is executed directly: every decision goes through the validator.
 */
interface GameAgent {
    val id: String
    val displayName: String

    /** What this agent uses to make decisions. Shown verbatim in the UI. */
    val disclosure: String

    suspend fun decide(observation: GameState): AgentDecision
}

/** Tunables for the deterministic policy. */
data class AgentPolicyConfig(
    /** Observations below this overall confidence produce WAIT. */
    val minObservationConfidence: Float = 0.40f,
    /** Health fraction below which the policy looks for a heal/ability action. */
    val lowHealthFraction: Float = 0.35f,
    /** Minimum confidence for an entity to be treated as a target. */
    val minTargetConfidence: Float = 0.50f,
    /** Duration used for swipes produced by the policy. */
    val swipeDurationMs: Long = 260L,
    /** Confidence the policy reports for its own decisions. */
    val decisionConfidence: Float = 0.6f,
) {
    init {
        require(minObservationConfidence in 0f..1f) { "minObservationConfidence must be in 0..1" }
        require(minTargetConfidence in 0f..1f) { "minTargetConfidence must be in 0..1" }
        require(lowHealthFraction in 0f..1f) { "lowHealthFraction must be in 0..1" }
        require(decisionConfidence in 0f..1f) { "decisionConfidence must be in 0..1" }
    }
}

/**
 * Deterministic, rule-based policy. No model, no network, no randomness.
 *
 * It only ever acts on values that were actually observed. When perception is unsure it waits; it
 * never substitutes a plausible-looking default for a missing detection.
 *
 * Rules, in evaluation order:
 *  1. Phase UNKNOWN, or overall confidence below [AgentPolicyConfig.minObservationConfidence] -> WAIT.
 *  2. Phase is not IN_MATCH (menu / loading / death) -> WAIT.
 *  3. Health is known and below [AgentPolicyConfig.lowHealthFraction], and a confident, located
 *     ability is ready -> TAP that ability.
 *  4. A confident enemy is located -> SWIPE from the player marker (or screen centre when the player
 *     marker was not detected) toward the enemy, i.e. an engagement move.
 *  5. A confident objective is located -> TAP it.
 *  6. Otherwise -> WAIT.
 */
class DeterministicPolicyAgent(
    private val config: AgentPolicyConfig = AgentPolicyConfig(),
    override val id: String = "deterministic-policy",
    private val clock: () -> Long = System::currentTimeMillis,
) : GameAgent {

    override val displayName: String = "Deterministic policy (no ML)"

    override val disclosure: String =
        "Rule-based policy driven only by observed values. No model, no network, no randomness. " +
            "Waits whenever the observation is unclear."

    private var nextDecisionId = 1L

    override suspend fun decide(observation: GameState): AgentDecision {
        val now = clock()

        if (observation.phase == GamePhase.UNKNOWN) {
            return wait(now, "phase is UNKNOWN, so no action is proposed")
        }
        if (observation.confidence < config.minObservationConfidence) {
            return wait(
                now,
                fmt(
                    "observation confidence %.2f is below the %.2f threshold",
                    observation.confidence,
                    config.minObservationConfidence,
                ),
            )
        }
        if (observation.phase != GamePhase.IN_MATCH) {
            return wait(now, "phase is ${observation.phase}; only IN_MATCH is actionable")
        }

        val health = observation.health.value
        if (health != null && health < config.lowHealthFraction) {
            val ability = observation
                .readySkills()
                .filter { it.bounds != null && it.confidence >= config.minTargetConfidence }
                .maxByOrNull { it.confidence }
            if (ability != null) {
                val bounds = ability.bounds!!
                return AgentDecision(
                    id = nextDecisionId++,
                    actionType = ActionType.TAP,
                    target = TargetRef(EntityKind.UI_CONTROL, ability.label ?: "skill_${ability.slot}", bounds),
                    point = bounds.center,
                    confidence = minOf(config.decisionConfidence, ability.confidence),
                    explanation = fmt(
                        "health %.2f is below %.2f and ability %d is ready",
                        health,
                        config.lowHealthFraction,
                        ability.slot,
                    ),
                    createdAtMs = now,
                    source = id,
                )
            }
        }

        val enemy = observation.enemies
            .filter { it.confidence >= config.minTargetConfidence }
            .maxByOrNull { it.confidence }
        if (enemy != null) {
            val origin = observation.self?.center ?: NormalizedPoint.CENTER
            return AgentDecision(
                id = nextDecisionId++,
                actionType = ActionType.SWIPE,
                target = TargetRef(EntityKind.ENEMY, enemy.label, enemy.bounds),
                point = origin,
                endPoint = enemy.center,
                durationMs = config.swipeDurationMs,
                confidence = minOf(config.decisionConfidence, enemy.confidence),
                explanation = fmt(
                    "enemy located at (%.2f, %.2f) with confidence %.2f; moving to engage",
                    enemy.center.x,
                    enemy.center.y,
                    enemy.confidence,
                ),
                createdAtMs = now,
                source = id,
            )
        }

        val objective = observation.objectives
            .filter { it.confidence >= config.minTargetConfidence }
            .maxByOrNull { it.confidence }
        if (objective != null) {
            return AgentDecision(
                id = nextDecisionId++,
                actionType = ActionType.TAP,
                target = TargetRef(EntityKind.OBJECTIVE, objective.label, objective.bounds),
                point = objective.center,
                confidence = minOf(config.decisionConfidence, objective.confidence),
                explanation = fmt(
                    "objective located at (%.2f, %.2f); moving to it",
                    objective.center.x,
                    objective.center.y,
                ),
                createdAtMs = now,
                source = id,
            )
        }

        return wait(now, "no enemy, objective or ready ability is actionable this cycle")
    }

    private fun wait(now: Long, reason: String) =
        AgentDecision.wait(id = nextDecisionId++, createdAtMs = now, source = id, reason = reason)

    private fun fmt(template: String, vararg args: Any?): String = String.format(Locale.US, template, *args)
}

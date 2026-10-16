package com.gameagent.core.model

import com.gameagent.core.geometry.NormalizedRect

/**
 * An observation whose value may legitimately be unknown.
 *
 * `value == null` means "could not be determined", which is fundamentally different from
 * "determined to be zero". The perception stage must return null rather than a placeholder.
 */
data class MetricObservation(
    val value: Float?,
    val confidence: Float,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in 0..1, was $confidence" }
        if (value != null) require(value.isFinite()) { "value must be finite" }
    }

    val isKnown: Boolean get() = value != null && confidence > 0f

    companion object {
        val UNKNOWN = MetricObservation(value = null, confidence = 0f)
    }
}

/** One ability slot: index 0 is usually the ultimate in MLBB, but the mapping is profile-defined. */
data class SkillObservation(
    val slot: Int,
    val label: String? = null,
    val ready: Boolean? = null,
    val cooldownFraction: Float? = null,
    val bounds: NormalizedRect? = null,
    val confidence: Float,
) {
    init {
        require(slot >= 0) { "slot must be >= 0" }
        require(confidence in 0f..1f) { "confidence must be in 0..1" }
        cooldownFraction?.let { require(it in 0f..1f) { "cooldownFraction must be in 0..1" } }
    }
}

/** Something detected on screen, in normalized coordinates. */
data class DetectedEntity(
    val kind: EntityKind,
    val label: String? = null,
    val bounds: NormalizedRect,
    val confidence: Float,
    /** Optional relative distance read from the minimap or HUD, when the profile can read it. */
    val distance: Float? = null,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in 0..1" }
    }

    val center get() = bounds.center
}

/** Minimap observations, kept separate because their semantics differ from world-space entities. */
data class MinimapObservation(
    val bounds: NormalizedRect,
    val entities: List<DetectedEntity>,
    val confidence: Float,
)

/** A binary UI indicator, for example "recall button visible" or "scoreboard open". */
data class UiIndicatorObservation(
    val id: String,
    val present: Boolean,
    val confidence: Float,
    val bounds: NormalizedRect? = null,
)

/**
 * Everything the perception stage could determine from one frame.
 *
 * Contract for implementers:
 *  - Every unknown field must be null (or [MetricObservation.UNKNOWN]) and paired with confidence 0.
 *  - [confidence] is the perception stage's confidence in the *overall* reading and is what the
 *    action validator gates on.
 *  - [frameId] must be the id of the frame this state came from, so staleness is provable.
 */
data class GameState(
    val phase: GamePhase,
    val phaseConfidence: Float,
    val health: MetricObservation = MetricObservation.UNKNOWN,
    val mana: MetricObservation = MetricObservation.UNKNOWN,
    val skills: List<SkillObservation> = emptyList(),
    /** The player's own marker, when the profile can find one. Null means "not detected". */
    val self: DetectedEntity? = null,
    val allies: List<DetectedEntity> = emptyList(),
    val enemies: List<DetectedEntity> = emptyList(),
    val objectives: List<DetectedEntity> = emptyList(),
    val minimap: MinimapObservation? = null,
    val indicators: List<UiIndicatorObservation> = emptyList(),
    val confidence: Float,
    val timestampMs: Long,
    val frameId: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val source: String,
    val notes: List<String> = emptyList(),
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in 0..1" }
        require(phaseConfidence in 0f..1f) { "phaseConfidence must be in 0..1" }
    }

    val hasUsableDetection: Boolean get() = confidence > 0f && phase != GamePhase.UNKNOWN

    fun indicator(id: String): UiIndicatorObservation? = indicators.firstOrNull { it.id == id }

    fun readySkills(): List<SkillObservation> = skills.filter { it.ready == true }

    /** Highest-confidence enemy, or null when none was detected. Never invents one. */
    fun mostConfidentEnemy(): DetectedEntity? = enemies.maxByOrNull { it.confidence }

    companion object {
        /** The only state the pipeline may use when nothing could be read. */
        fun unknown(
            timestampMs: Long,
            frameId: Long,
            frameWidth: Int,
            frameHeight: Int,
            source: String,
            reason: String,
        ) = GameState(
            phase = GamePhase.UNKNOWN,
            phaseConfidence = 0f,
            confidence = 0f,
            timestampMs = timestampMs,
            frameId = frameId,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            source = source,
            notes = listOf(reason),
        )
    }
}

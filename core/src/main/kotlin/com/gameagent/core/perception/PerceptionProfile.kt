package com.gameagent.core.perception

import com.gameagent.core.geometry.NormalizedRect
import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GamePhase

/** Flat-colour matcher: a reference ARGB colour plus a per-channel tolerance budget. */
data class ColorMatcher(val color: Int, val tolerance: Int = 24) {
    init {
        require(tolerance in 0..255) { "tolerance must be 0..255" }
    }

    fun matches(candidate: Int): Boolean = ImageFrame.colorDistance(color, candidate) <= tolerance * 3
}

/**
 * Declarative description of a game screen for [HeuristicScreenPerceptionProvider].
 *
 * A profile is calibrated from real screenshots (colour sampling) and is the only thing that needs
 * to change to retarget a new game or a new game version. Profiles are data, so they are cheap to
 * unit-test against synthetic frames with known expected output.
 */
data class PerceptionProfile(
    val id: String,
    val description: String,
    /** Banner/phase strip region, typically the top of the screen. */
    val phaseRegion: NormalizedRect,
    /** Colour that identifies each phase inside [phaseRegion]. */
    val phaseColors: Map<GamePhase, ColorMatcher>,
    /** Minimum fraction of sampled pixels in [phaseRegion] that must match for a phase to be read. */
    val phaseMinRatio: Float = 0.30f,
    val healthBarRegion: NormalizedRect? = null,
    val healthFillColor: ColorMatcher? = null,
    val healthTrackColor: ColorMatcher? = null,
    val manaBarRegion: NormalizedRect? = null,
    val manaFillColor: ColorMatcher? = null,
    /** Ability buttons; each entry is read as ready/cooldown. */
    val skillRegions: List<NormalizedRect> = emptyList(),
    val skillReadyColor: ColorMatcher? = null,
    val skillCooldownColor: ColorMatcher? = null,
    /** Entity marker colours. */
    val entityColors: Map<EntityKind, ColorMatcher> = emptyMap(),
    /** Minimum marker area, expressed as a fraction of the sampled grid, before it counts. */
    val minMarkerGridFraction: Float = 0.00035f,
    /** Region treated as the minimap; markers inside it are reported as minimap observations. */
    val minimapRegion: NormalizedRect? = null,
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
    }

    companion object {
        /**
         * The highest confidence a heuristic colour detector is allowed to report. It is capped
         * below 1.0 on purpose: a colour rule is never as trustworthy as a validated model.
         */
        const val MAX_HEURISTIC_CONFIDENCE = 0.9f
    }
}

package com.gameagent.core.fixtures

import com.gameagent.core.geometry.NormalizedRect

/**
 * Colours used by the built-in test scene.
 *
 * These are the *only* definition of the scene colours: the renderer draws with them and the
 * perception profile matches against them, so the test screen can never drift out of sync with
 * what the perception stage expects to see.
 *
 * The palette is picked so that no two colours that are read by different detectors are within
 * their match tolerance of each other (verified by `TestScenePaletteTest`).
 */
object TestScenePalette {
    val BACKGROUND = 0xFF0E1117.toInt()
    val PANEL = 0xFF171C26.toInt()
    val MINIMAP_BG = 0xFF1B2230.toInt()
    val MINIMAP_BORDER = 0xFF35405A.toInt()

    val PHASE_MENU = 0xFF2D6CDF.toInt()
    val PHASE_LOADING = 0xFFC98A16.toInt()
    val PHASE_MATCH = 0xFF1FA85C.toInt()
    val PHASE_DEATH = 0xFF9E2B2B.toInt()

    val HEALTH_TRACK = 0xFF3A3F4B.toInt()
    val HEALTH_FILL = 0xFFE02020.toInt()
    val MANA_TRACK = 0xFF2A3040.toInt()
    val MANA_FILL = 0xFF6C4CE8.toInt()

    val SKILL_READY = 0xFF29C7F0.toInt()
    val SKILL_COOLDOWN = 0xFF32404F.toInt()

    val SELF = 0xFFF2E14C.toInt()
    val ALLY = 0xFF4CF28A.toInt()
    val ENEMY = 0xFFFF5A1E.toInt()
    val OBJECTIVE = 0xFFB44CFF.toInt()
}

/**
 * Normalized layout of the built-in test scene, i.e. where the renderer draws each element.
 *
 * The arena is deliberately kept clear of the minimap region (y 0.06..0.30) and of the skill
 * buttons (y 0.80..0.98), so a world marker can never be mistaken for a minimap blip.
 */
object TestSceneLayout {
    val headerBanner = NormalizedRect(0f, 0f, 1f, 0.05f)
    val healthTrack = NormalizedRect(0.05f, 0.075f, 0.45f, 0.105f)
    val manaTrack = NormalizedRect(0.05f, 0.115f, 0.45f, 0.135f)
    val minimap = NormalizedRect(0.62f, 0.06f, 0.98f, 0.30f)
    val arena = NormalizedRect(0.02f, 0.32f, 0.98f, 0.78f)

    /** Three ability buttons, drawn as filled rectangles (readiness is signalled by colour only). */
    val skillButtons: List<NormalizedRect> = listOf(
        NormalizedRect(0.68f, 0.80f, 0.79f, 0.92f),
        NormalizedRect(0.80f, 0.86f, 0.91f, 0.98f),
        NormalizedRect(0.56f, 0.86f, 0.67f, 0.98f),
    )

    /**
     * Half-extent of a marker in normalized screen units, per axis. Pixel-exact squares require
     * the frame aspect, which the reducer does not know, so markers are drawn as aspect-corrected
     * squares while hit testing uses the generous [MARKER_HIT_HALF] box below.
     */
    const val MARKER_HALF_PX_FRACTION = 0.032f

    /** Generous axis-aligned hit box (normalized units) used to decide whether a tap hit a marker. */
    const val MARKER_HIT_HALF = 0.045f

    /** How far a single tap-to-move command displaces the player token. */
    const val MOVE_STEP = 0.12f

    /** Movement multiplier applied to a swipe. */
    const val SWIPE_MOVE_FACTOR = 3.0f

    val skillNames = listOf("skill_1", "skill_2", "ultimate")
}

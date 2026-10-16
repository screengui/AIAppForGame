package com.gameagent.core.fixtures

import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GamePhase
import com.gameagent.core.perception.ColorMatcher
import com.gameagent.core.perception.PerceptionProfile

/**
 * Perception profile for the built-in test scene, derived from [TestSceneLayout] and
 * [TestScenePalette] so it can never drift from what the renderer actually draws.
 *
 * This is the only *calibrated* profile in the project. It exists so the capture -> decide -> act
 * loop can be verified end to end without MLBB. A real MLBB profile is pending real screenshots;
 * see docs/PERCEPTION.md.
 */
object TestScenePerceptionProfile {

    private const val TOLERANCE = 16

    val profile: PerceptionProfile = PerceptionProfile(
        id = "test-scene",
        description = "Built-in test HUD: flat colours, fixed regions, no text.",
        phaseRegion = TestSceneLayout.headerBanner,
        phaseColors = mapOf(
            GamePhase.MENU to ColorMatcher(TestScenePalette.PHASE_MENU, TOLERANCE),
            GamePhase.LOADING to ColorMatcher(TestScenePalette.PHASE_LOADING, TOLERANCE),
            GamePhase.IN_MATCH to ColorMatcher(TestScenePalette.PHASE_MATCH, TOLERANCE),
            GamePhase.DEATH to ColorMatcher(TestScenePalette.PHASE_DEATH, TOLERANCE),
        ),
        phaseMinRatio = 0.30f,
        healthBarRegion = TestSceneLayout.healthTrack,
        healthFillColor = ColorMatcher(TestScenePalette.HEALTH_FILL, TOLERANCE),
        healthTrackColor = ColorMatcher(TestScenePalette.HEALTH_TRACK, TOLERANCE),
        manaBarRegion = TestSceneLayout.manaTrack,
        manaFillColor = ColorMatcher(TestScenePalette.MANA_FILL, TOLERANCE),
        skillRegions = TestSceneLayout.skillButtons,
        skillReadyColor = ColorMatcher(TestScenePalette.SKILL_READY, TOLERANCE),
        skillCooldownColor = ColorMatcher(TestScenePalette.SKILL_COOLDOWN, TOLERANCE),
        entityColors = mapOf(
            EntityKind.SELF to ColorMatcher(TestScenePalette.SELF, TOLERANCE),
            EntityKind.ALLY to ColorMatcher(TestScenePalette.ALLY, TOLERANCE),
            EntityKind.ENEMY to ColorMatcher(TestScenePalette.ENEMY, TOLERANCE),
            EntityKind.OBJECTIVE to ColorMatcher(TestScenePalette.OBJECTIVE, TOLERANCE),
        ),
        minMarkerGridFraction = 0.00035f,
        minimapRegion = TestSceneLayout.minimap,
    )
}

package com.gameagent.core.perception

import com.gameagent.core.fixtures.SyntheticSceneRenderer
import com.gameagent.core.fixtures.TestSceneLayout
import com.gameagent.core.fixtures.TestScenePerceptionProfile
import com.gameagent.core.fixtures.TestSceneState
import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.GameState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeuristicPerceptionTest {

    private val provider = HeuristicScreenPerceptionProvider(TestScenePerceptionProfile.profile)

    private fun perceive(state: TestSceneState, width: Int = 1080, height: Int = 2340): GameState {
        val frame = SyntheticSceneRenderer.frame(state, width, height, frameId = 1L, timestampMs = 42L)
        return provider.detect(frame)
    }

    @Test
    fun `reads every phase from the header banner`() {
        val expectations = mapOf(
            GamePhase.MENU to 0.9f,
            GamePhase.LOADING to 0.9f,
            GamePhase.IN_MATCH to 0.9f,
            GamePhase.DEATH to 0.9f,
        )
        for ((phase, _) in expectations) {
            val detected = perceive(TestSceneState(phase = phase, healthFraction = 0.8f))
            assertEquals(phase, detected.phase, "phase $phase was read as ${detected.phase}")
        }
        // UNKNOWN has no colour of its own, so the scene renders the background and perception
        // must report UNKNOWN with zero confidence rather than guessing.
        val unknown = perceive(TestSceneState(phase = GamePhase.UNKNOWN))
        assertEquals(GamePhase.UNKNOWN, unknown.phase)
        assertEquals(0f, unknown.confidence)
        assertTrue(unknown.notes.any { it.contains("phase colour") })
    }

    @Test
    fun `reads the health bar fraction`() {
        for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val detected = perceive(TestSceneState(phase = GamePhase.IN_MATCH, healthFraction = fraction))
            val read = detected.health.value
            assertNotNull(read, "health fraction $fraction was not readable")
            assertTrue(
                kotlin.math.abs(read - fraction) < 0.06f,
                "health fraction $fraction was read as $read",
            )
            assertEquals(true, detected.health.isKnown)
        }
    }

    @Test
    fun `a scene with no bars reports unknown metrics rather than zero`() {
        // Blank frame: nothing matches, so health must be UNKNOWN (null), not 0.
        val blank = ImageFrame(1L, 0L, 100, 200, IntArray(100 * 200) { 0xFF000000.toInt() })
        val detected = provider.detect(blank)
        assertEquals(GamePhase.UNKNOWN, detected.phase)
        assertNull(detected.health.value)
        assertEquals(false, detected.health.isKnown)
        assertNull(detected.mana.value)
        assertTrue(detected.skills.all { it.ready == null })
        assertTrue(detected.enemies.isEmpty())
        assertNull(detected.self)
        assertEquals(0f, detected.confidence)
    }

    @Test
    fun `reads skill readiness`() {
        val detected = perceive(
            TestSceneState(phase = GamePhase.IN_MATCH, skillReady = listOf(true, false, true)),
        )
        assertEquals(3, detected.skills.size)
        assertEquals(listOf(true, false, true), detected.skills.map { it.ready })
        assertTrue(detected.skills.all { it.confidence > 0f })
    }

    @Test
    fun `locates every entity kind`() {
        val state = TestSceneState(phase = GamePhase.IN_MATCH)
        val detected = perceive(state)

        assertNotNull(detected.self, "player marker was not detected")
        assertEquals(EntityKind.SELF, detected.self.kind)
        assertTrue(
            kotlin.math.abs(detected.self.center.x - state.self.x) < 0.02f &&
                kotlin.math.abs(detected.self.center.y - state.self.y) < 0.02f,
            "self marker was located at ${detected.self.center} instead of ${state.self}",
        )

        assertEquals(2, detected.enemies.size, "expected 2 enemies, saw ${detected.enemies}")
        assertEquals(2, detected.allies.size)
        assertEquals(1, detected.objectives.size)
    }

    @Test
    fun `world markers are not reported as minimap blips`() {
        val detected = perceive(TestSceneState(phase = GamePhase.IN_MATCH))
        val minimap = assertNotNull(detected.minimap)
        assertEquals(TestSceneLayout.minimap, minimap.bounds)
        assertTrue(minimap.entities.isEmpty(), "minimap should be empty in the default scene")

        // Now place an ally actually inside the minimap region.
        val insideMinimap = perceive(
            TestSceneState(
                phase = GamePhase.IN_MATCH,
                allies = listOf(NormalizedPoint(0.75f, 0.18f)),
            ),
        )
        val innerMinimap = assertNotNull(insideMinimap.minimap)
        assertEquals(1, innerMinimap.entities.size)
        assertEquals(EntityKind.ALLY, innerMinimap.entities.first().kind)
        assertTrue(insideMinimap.allies.isEmpty(), "an ally inside the minimap must not appear in world space")
    }

    @Test
    fun `indicators are derived from detections`() {
        val detected = perceive(TestSceneState(phase = GamePhase.IN_MATCH))
        assertEquals(true, detected.indicator("self_visible")?.present)
        assertEquals(true, detected.indicator("enemy_visible")?.present)
        assertEquals(true, detected.indicator("objective_visible")?.present)
        assertEquals(true, detected.indicator("ally_visible")?.present)

        val empty = perceive(TestSceneState(phase = GamePhase.IN_MATCH, enemies = emptyList(), objective = null))
        assertEquals(false, empty.indicator("enemy_visible")?.present)
        assertEquals(false, empty.indicator("objective_visible")?.present)
        assertEquals(0f, empty.indicator("enemy_visible")?.confidence)
    }

    @Test
    fun `confidence never claims more than a colour heuristic can know`() {
        val detected = perceive(TestSceneState(phase = GamePhase.IN_MATCH))
        assertTrue(detected.confidence <= PerceptionProfile.MAX_HEURISTIC_CONFIDENCE)
        assertTrue(detected.phaseConfidence <= PerceptionProfile.MAX_HEURISTIC_CONFIDENCE)
        assertTrue(detected.enemies.all { it.confidence <= PerceptionProfile.MAX_HEURISTIC_CONFIDENCE })
    }

    @Test
    fun `detection is resolution independent`() {
        val state = TestSceneState(phase = GamePhase.IN_MATCH, healthFraction = 0.5f)
        val small = perceive(state, width = 360, height = 780)
        val large = perceive(state, width = 1440, height = 3120)

        assertEquals(small.phase, large.phase)
        assertEquals(2, small.enemies.size)
        assertEquals(2, large.enemies.size)
        val smallSelf = assertNotNull(small.self)
        val largeSelf = assertNotNull(large.self)
        assertTrue(kotlin.math.abs(smallSelf.center.x - largeSelf.center.x) < 0.02f)
        assertTrue(kotlin.math.abs(smallSelf.center.y - largeSelf.center.y) < 0.02f)
        val smallHealth = assertNotNull(small.health.value)
        val largeHealth = assertNotNull(large.health.value)
        assertTrue(kotlin.math.abs(smallHealth - largeHealth) < 0.08f)
    }

    @Test
    fun `mismatched profile reports unknown instead of guessing`() {
        // A profile calibrated for a different palette must not invent detections.
        val wrongProfile = TestScenePerceptionProfile.profile.copy(
            phaseColors = mapOf(
                GamePhase.IN_MATCH to ColorMatcher(0xFF00FF00.toInt(), 4),
            ),
        )
        val foreign = HeuristicScreenPerceptionProvider(wrongProfile)
        val frame = SyntheticSceneRenderer.frame(TestSceneState(phase = GamePhase.IN_MATCH), 540, 1170, 1L, 0L)
        val detected = foreign.detect(frame)
        assertEquals(GamePhase.UNKNOWN, detected.phase)
        assertEquals(0f, detected.confidence)
    }

    @Test
    fun `observe reports failures as data and never throws`() = runTest {
        val frame = SyntheticSceneRenderer.frame(TestSceneState(), 360, 780, 1L, 0L)
        val result = provider.observe(frame)
        assertTrue(result is PerceptionResult.Success)
        assertTrue((result as PerceptionResult.Success).state.source == provider.id)
    }

    @Test
    fun `null provider returns an honest unknown state`() = runTest {
        val frame = SyntheticSceneRenderer.frame(TestSceneState(), 360, 780, 7L, 99L)
        val result = NullPerceptionProvider().observe(frame) as PerceptionResult.Success
        assertEquals(GamePhase.UNKNOWN, result.state.phase)
        assertEquals(0f, result.state.confidence)
        assertEquals(7L, result.state.frameId)
        assertEquals(99L, result.state.timestampMs)
        assertTrue(result.state.notes.isNotEmpty())
    }

    @Test
    fun `frame rejects an undersized buffer`() {
        val error = runCatching { ImageFrame(1L, 0L, 4, 4, IntArray(10)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `frame rejects non positive dimensions`() {
        assertTrue(runCatching { ImageFrame(1L, 0L, 0, 4, IntArray(0)) }.exceptionOrNull() is IllegalArgumentException)
    }
}

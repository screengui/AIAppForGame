package com.gameagent.core.fixtures

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.NormalizedRect
import com.gameagent.core.model.GamePhase
import com.gameagent.core.perception.ImageFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestSceneTest {

    private val base = TestSceneState(phase = GamePhase.IN_MATCH)

    @Test
    fun `tapping a ready ability puts it on cooldown`() {
        val result = TestSceneReducer.reduce(base, TestSceneAction.Tap(TestSceneLayout.skillButtons[0].center))
        assertTrue(result.applied)
        assertEquals(SceneEffect.SKILL_TOGGLE, result.effect)
        assertEquals(false, result.state.skillReady[0])
        assertEquals(true, result.state.skillReady[1])
    }

    @Test
    fun `tapping an enemy eliminates it`() {
        val enemy = base.enemies.first()
        val result = TestSceneReducer.reduce(base, TestSceneAction.Tap(enemy))
        assertTrue(result.applied)
        assertEquals(SceneEffect.ENEMY_ELIMINATED, result.effect)
        assertEquals(1, result.state.enemies.size)
    }

    @Test
    fun `tapping an empty part of the arena moves the player token`() {
        val target = NormalizedPoint(0.5f, 0.72f)
        val result = TestSceneReducer.reduce(base, TestSceneAction.Tap(target))
        assertTrue(result.applied)
        assertEquals(SceneEffect.MOVE, result.effect)
        assertTrue(result.state.self.y > base.self.y, "player should have moved toward the tap")
    }

    @Test
    fun `tapping the minimap only pings`() {
        val result = TestSceneReducer.reduce(base, TestSceneAction.Tap(NormalizedPoint(0.75f, 0.18f)))
        assertTrue(result.applied)
        assertEquals(SceneEffect.MINIMAP_PING, result.effect)
        assertEquals(base.self, result.state.self)
    }

    @Test
    fun `taps outside the arena are ignored yet recorded`() {
        // Inside the header banner: not the arena, not a skill, not the minimap.
        val result = TestSceneReducer.reduce(base, TestSceneAction.Tap(NormalizedPoint(0.5f, 0.02f)))
        assertFalse(result.applied)
        assertEquals(SceneEffect.IGNORED, result.effect)
        assertEquals(NormalizedPoint(0.5f, 0.02f), result.state.lastTap)
        assertEquals(1L, result.state.actionCount)
    }

    @Test
    fun `a swipe moves further than a tap and is clamped to the arena`() {
        val far = NormalizedPoint(0.99f, 0.99f)
        val swiped = TestSceneReducer.reduce(base, TestSceneAction.Swipe(base.self, far, durationMs = 200L))
        assertEquals(SceneEffect.MOVE, swiped.effect)
        val arena = TestSceneLayout.arena
        assertTrue(swiped.state.self.x <= arena.right + 0.0001f)
        assertTrue(swiped.state.self.y <= arena.bottom + 0.0001f)
        assertTrue(swiped.state.self.y > base.self.y)
        val trace = assertNotNull(swiped.state.lastSwipe)
        assertEquals(200L, trace.durationMs)
    }

    @Test
    fun `hold and release behave correctly`() {
        val hold = TestSceneReducer.reduce(base, TestSceneAction.Hold(NormalizedPoint(0.5f, 0.7f)))
        assertEquals(SceneEffect.HOLD_START, hold.effect)
        assertTrue(hold.state.holdActive)

        val release = TestSceneReducer.reduce(hold.state, TestSceneAction.Release(NormalizedPoint(0.5f, 0.7f)))
        assertEquals(SceneEffect.HOLD_END, release.effect)
        assertFalse(release.state.holdActive)

        val spurious = TestSceneReducer.reduce(base, TestSceneAction.Release(NormalizedPoint(0.5f, 0.7f)))
        assertFalse(spurious.applied)
        assertEquals(SceneEffect.IGNORED, spurious.effect)
    }

    @Test
    fun `the event log is bounded`() {
        var state = base
        repeat(TestSceneState.MAX_EVENT_LOG * 3) {
            state = TestSceneReducer.reduce(state, TestSceneAction.Tap(NormalizedPoint(0.5f, 0.5f))).state
        }
        assertEquals(TestSceneState.MAX_EVENT_LOG, state.eventLog.size)
    }

    @Test
    fun `the reducer is deterministic`() {
        val a = TestSceneReducer.reduce(base, TestSceneAction.Swipe(base.self, NormalizedPoint(0.8f, 0.6f), 150L))
        val b = TestSceneReducer.reduce(base, TestSceneAction.Swipe(base.self, NormalizedPoint(0.8f, 0.6f), 150L))
        assertEquals(a.state, b.state)
    }

    @Test
    fun `layout regions do not overlap in ways that would confuse perception`() {
        val arena = TestSceneLayout.arena
        assertFalse(overlaps(arena, TestSceneLayout.minimap), "arena must not overlap the minimap")
        TestSceneLayout.skillButtons.forEach { button ->
            assertFalse(overlaps(arena, button), "arena must not overlap a skill button")
        }
        assertFalse(
            overlaps(TestSceneLayout.headerBanner, TestSceneLayout.minimap),
            "the phase banner must not overlap the minimap",
        )
        assertFalse(overlaps(TestSceneLayout.healthTrack, TestSceneLayout.manaTrack))
        assertTrue(contains(TestSceneLayout.minimap, NormalizedPoint(0.75f, 0.18f)))
        assertFalse(contains(arena, NormalizedPoint(0.75f, 0.18f)))
        assertTrue(TestSceneLayout.skillButtons.all { contains(it, it.center) })
    }

    /**
     * The palette invariant that actually matters, expressed precisely:
     *
     *  - The entity detector scans the whole frame, so every entity colour must be distinguishable
     *    from *every* other colour that can appear anywhere on screen (backgrounds, bars, buttons,
     *    phase banner, other entities).
     *  - Phase colours must be distinguishable from each other (they share one region) and from
     *    entity colours (covered above).
     *  - Fill colours must be distinguishable from their own track colour, and ready from cooldown,
     *    because those pairs share a region.
     */
    @Test
    fun `detector colours are separated by more than the match tolerance`() {
        val threshold = 16 * 3

        fun assertApart(a: String, aColor: Int, b: String, bColor: Int) {
            val distance = ImageFrame.colorDistance(aColor, bColor)
            assertTrue(
                distance > threshold,
                "'$a' and '$b' are only $distance apart (threshold $threshold)",
            )
        }

        val entities = mapOf(
            "self" to TestScenePalette.SELF,
            "ally" to TestScenePalette.ALLY,
            "enemy" to TestScenePalette.ENEMY,
            "objective" to TestScenePalette.OBJECTIVE,
        )
        val nonEntityColors = mapOf(
            "background" to TestScenePalette.BACKGROUND,
            "panel" to TestScenePalette.PANEL,
            "minimap_bg" to TestScenePalette.MINIMAP_BG,
            "minimap_border" to TestScenePalette.MINIMAP_BORDER,
            "phase_menu" to TestScenePalette.PHASE_MENU,
            "phase_loading" to TestScenePalette.PHASE_LOADING,
            "phase_match" to TestScenePalette.PHASE_MATCH,
            "phase_death" to TestScenePalette.PHASE_DEATH,
            "health_track" to TestScenePalette.HEALTH_TRACK,
            "health_fill" to TestScenePalette.HEALTH_FILL,
            "mana_track" to TestScenePalette.MANA_TRACK,
            "mana_fill" to TestScenePalette.MANA_FILL,
            "skill_ready" to TestScenePalette.SKILL_READY,
            "skill_cooldown" to TestScenePalette.SKILL_COOLDOWN,
        )

        for ((entityName, entityColor) in entities) {
            for ((otherName, otherColor) in nonEntityColors) {
                assertApart(entityName, entityColor, otherName, otherColor)
            }
            for ((otherEntity, otherColor) in entities) {
                if (otherEntity == entityName) continue
                assertApart(entityName, entityColor, otherEntity, otherColor)
            }
        }

        val phases = mapOf(
            "phase_menu" to TestScenePalette.PHASE_MENU,
            "phase_loading" to TestScenePalette.PHASE_LOADING,
            "phase_match" to TestScenePalette.PHASE_MATCH,
            "phase_death" to TestScenePalette.PHASE_DEATH,
        )
        for ((a, aColor) in phases) {
            for ((b, bColor) in phases) {
                if (a == b) continue
                assertApart(a, aColor, b, bColor)
            }
        }

        assertApart("health_fill", TestScenePalette.HEALTH_FILL, "health_track", TestScenePalette.HEALTH_TRACK)
        assertApart("mana_fill", TestScenePalette.MANA_FILL, "mana_track", TestScenePalette.MANA_TRACK)
        assertApart("skill_ready", TestScenePalette.SKILL_READY, "skill_cooldown", TestScenePalette.SKILL_COOLDOWN)
    }

    private fun overlaps(a: NormalizedRect, b: NormalizedRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun contains(rect: NormalizedRect, point: NormalizedPoint): Boolean =
        point.x >= rect.left && point.x <= rect.right && point.y >= rect.top && point.y <= rect.bottom
}

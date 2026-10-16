package com.gameagent.core.fixtures

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.NormalizedRect
import com.gameagent.core.model.GamePhase
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the test scene into a raw ARGB buffer.
 *
 * Why a pure-Kotlin renderer inside `:core` rather than only drawing with `android.graphics`:
 *  - The unit tests can produce synthetic screenshots with exactly known content, so the perception
 *    stage can be asserted against expected values instead of vibes.
 *  - The app displays the very same buffer it feeds to perception, so what the user sees on the
 *    test screen is byte-for-byte what the pipeline analyses. There is no second renderer to drift.
 *
 * All fills are hard-edged (no anti-aliasing) so sampled pixels are exact palette values.
 */
object SyntheticSceneRenderer {

    fun render(state: TestSceneState, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "render size must be positive" }
        val pixels = IntArray(width * height) { TestScenePalette.BACKGROUND }

        fillRect(pixels, width, height, TestSceneLayout.arena, TestScenePalette.PANEL)
        fillRect(pixels, width, height, TestSceneLayout.minimap, TestScenePalette.MINIMAP_BG)
        strokeRect(pixels, width, height, TestSceneLayout.minimap, TestScenePalette.MINIMAP_BORDER, thicknessPx = 2)

        fillRect(pixels, width, height, TestSceneLayout.headerBanner, phaseColor(state.phase))

        fillRect(pixels, width, height, TestSceneLayout.healthTrack, TestScenePalette.HEALTH_TRACK)
        fillRect(pixels, width, height, progressRect(TestSceneLayout.healthTrack, state.healthFraction), TestScenePalette.HEALTH_FILL)

        fillRect(pixels, width, height, TestSceneLayout.manaTrack, TestScenePalette.MANA_TRACK)
        fillRect(pixels, width, height, progressRect(TestSceneLayout.manaTrack, state.manaFraction), TestScenePalette.MANA_FILL)

        val halfPx = max(2, (min(width, height) * TestSceneLayout.MARKER_HALF_PX_FRACTION).roundToInt())
        state.allies.forEach { marker(pixels, width, height, it, halfPx, TestScenePalette.ALLY) }
        state.objective?.let { marker(pixels, width, height, it, halfPx, TestScenePalette.OBJECTIVE) }
        state.enemies.forEach { marker(pixels, width, height, it, halfPx, TestScenePalette.ENEMY) }
        marker(pixels, width, height, state.self, halfPx, TestScenePalette.SELF)

        state.skillReady.forEachIndexed { index, ready ->
            val rect = TestSceneLayout.skillButtons.getOrNull(index) ?: return@forEachIndexed
            fillRect(
                pixels,
                width,
                height,
                rect,
                if (ready) TestScenePalette.SKILL_READY else TestScenePalette.SKILL_COOLDOWN,
            )
        }

        return pixels
    }

    fun phaseColor(phase: GamePhase): Int = when (phase) {
        GamePhase.MENU -> TestScenePalette.PHASE_MENU
        GamePhase.LOADING -> TestScenePalette.PHASE_LOADING
        GamePhase.IN_MATCH -> TestScenePalette.PHASE_MATCH
        GamePhase.DEATH -> TestScenePalette.PHASE_DEATH
        GamePhase.UNKNOWN -> TestScenePalette.BACKGROUND
    }

    /** A frame wrapping a freshly rendered test scene. */
    fun frame(state: TestSceneState, width: Int, height: Int, frameId: Long, timestampMs: Long) =
        com.gameagent.core.perception.ImageFrame(
            frameId = frameId,
            timestampMs = timestampMs,
            width = width,
            height = height,
            argb = render(state, width, height),
        )

    // ------------------------------------------------------------------ primitives

    private fun progressRect(track: NormalizedRect, fraction: Float): NormalizedRect {
        val f = fraction.coerceIn(0f, 1f)
        return NormalizedRect(track.left, track.top, track.left + track.width * f, track.bottom)
    }

    private fun marker(
        pixels: IntArray,
        width: Int,
        height: Int,
        center: NormalizedPoint,
        halfPx: Int,
        color: Int,
    ) {
        val cx = (center.x * width).roundToInt()
        val cy = (center.y * height).roundToInt()
        for (y in cy - halfPx until cy + halfPx) {
            if (y < 0 || y >= height) continue
            val rowOffset = y * width
            for (x in cx - halfPx until cx + halfPx) {
                if (x < 0 || x >= width) continue
                pixels[rowOffset + x] = color
            }
        }
    }

    private fun fillRect(pixels: IntArray, width: Int, height: Int, rect: NormalizedRect, color: Int) {
        val x0 = (rect.left * width).roundToInt().coerceIn(0, width)
        val x1 = (rect.right * width).roundToInt().coerceIn(0, width)
        val y0 = (rect.top * height).roundToInt().coerceIn(0, height)
        val y1 = (rect.bottom * height).roundToInt().coerceIn(0, height)
        for (y in y0 until y1) {
            val rowOffset = y * width
            for (x in x0 until x1) pixels[rowOffset + x] = color
        }
    }

    private fun strokeRect(
        pixels: IntArray,
        width: Int,
        height: Int,
        rect: NormalizedRect,
        color: Int,
        thicknessPx: Int,
    ) {
        val x0 = (rect.left * width).roundToInt().coerceIn(0, width - 1)
        val x1 = ((rect.right * width).roundToInt() - 1).coerceIn(0, width - 1)
        val y0 = (rect.top * height).roundToInt().coerceIn(0, height - 1)
        val y1 = ((rect.bottom * height).roundToInt() - 1).coerceIn(0, height - 1)
        for (t in 0 until thicknessPx) {
            for (x in x0..x1) {
                pixels[(y0 + t).coerceAtMost(height - 1) * width + x] = color
                pixels[(y1 - t).coerceAtLeast(0) * width + x] = color
            }
            for (y in y0..y1) {
                pixels[y * width + (x0 + t).coerceAtMost(width - 1)] = color
                pixels[y * width + (x1 - t).coerceAtLeast(0)] = color
            }
        }
    }
}

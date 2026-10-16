package com.gameagent.core.perception

import com.gameagent.core.geometry.NormalizedRect
import com.gameagent.core.model.DetectedEntity
import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.GameState
import com.gameagent.core.model.MinimapObservation
import com.gameagent.core.model.MetricObservation
import com.gameagent.core.model.SkillObservation
import com.gameagent.core.model.UiIndicatorObservation
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic, dependency-free screen understanding based on a [PerceptionProfile].
 *
 * Algorithm (all bounded, all deterministic):
 *  1. The frame is downsampled to a grid of at most [maxGridWidth] columns by point-sampling the
 *     centre of each block. This caps work per frame at ~O(gridW * gridH) regardless of frame size.
 *  2. Regional colour ratios give the game phase, health/mana fractions and skill readiness.
 *  3. A connected-component pass over the per-kind colour masks gives entity markers with
 *     normalized bounding boxes and areas.
 *  4. Markers whose centre lies inside `profile.minimapRegion` are reported separately as
 *     minimap observations.
 *
 * Honest limitations (also documented in docs/PERCEPTION.md):
 *  - It reads flat UI colours. Gradients, shadows, transparency and video compression will reduce
 *    accuracy; that is exactly why every observation carries a confidence and why the action
 *    validator refuses low-confidence decisions.
 *  - It has no notion of hero identity, skill icons or text. Reading those requires the trained
 *    model milestone; until then those fields stay null.
 *  - Only the bundled test-screen profile is calibrated. There is no calibrated MLBB profile, so
 *    against a real MLBB screen this provider normally returns [GamePhase.UNKNOWN] with confidence 0
 *    instead of guessing.
 */
class HeuristicScreenPerceptionProvider(
    private val profile: PerceptionProfile,
    override val id: String = "heuristic-${profile.id}",
    private val maxGridWidth: Int = DEFAULT_MAX_GRID_WIDTH,
    private val nanoTime: () -> Long = System::nanoTime,
) : PerceptionProvider {

    init {
        require(maxGridWidth in 16..512) { "maxGridWidth must be in 16..512, was $maxGridWidth" }
    }

    override val description: String =
        "Deterministic colour/geometry detection using profile '${profile.id}'. ${profile.description}"

    override suspend fun observe(frame: ImageFrame): PerceptionResult {
        val start = nanoTime()
        return try {
            PerceptionResult.Success(detect(frame), durationToMillis(start))
        } catch (e: IllegalArgumentException) {
            PerceptionResult.Failure("heuristic perception rejected the frame: ${e.message}", durationToMillis(start))
        } catch (e: ArithmeticException) {
            PerceptionResult.Failure("heuristic perception failed: ${e.message}", durationToMillis(start))
        }
    }

    private fun durationToMillis(startNanos: Long): Long = max(0L, (nanoTime() - startNanos) / 1_000_000L)

    // ------------------------------------------------------------------ pipeline

    fun detect(frame: ImageFrame): GameState {
        val grid = Grid(frame, maxGridWidth)
        val phaseReading = readPhase(grid)
        val health = profile.healthBarRegion?.let {
            readBar(grid, it, profile.healthFillColor, profile.healthTrackColor)
        } ?: MetricObservation.UNKNOWN
        val mana = profile.manaBarRegion?.let {
            readBar(grid, it, profile.manaFillColor, profile.manaTrackColorOrNull())
        } ?: MetricObservation.UNKNOWN
        val skills = readSkills(grid)
        val markers = detectMarkers(grid)

        val minimapBounds = profile.minimapRegion
        val minimapMarkers = if (minimapBounds == null) emptyList() else markers.filter {
            minimapBounds.contains(it.center.x, it.center.y)
        }
        val worldMarkers = if (minimapBounds == null) markers else markers.filterNot {
            minimapBounds.contains(it.center.x, it.center.y)
        }

        val self = worldMarkers.filter { it.kind == EntityKind.SELF }
        val allies = worldMarkers.filter { it.kind == EntityKind.ALLY }
        val enemies = worldMarkers.filter { it.kind == EntityKind.ENEMY }
        val objectives = worldMarkers.filter { it.kind == EntityKind.OBJECTIVE }

        val indicators = buildList {
            add(indicator("self_visible", self))
            add(indicator("ally_visible", allies))
            add(indicator("enemy_visible", enemies))
            add(indicator("objective_visible", objectives))
        }

        val notes = mutableListOf<String>()
        if (phaseReading.phase == GamePhase.UNKNOWN) {
            notes += "no phase colour reached the required ratio in the phase region"
        }
        if (!health.isKnown) notes += "health bar not readable"
        if (!mana.isKnown) notes += "mana bar not readable"

        val overall = overallConfidence(phaseReading, health, mana, skills, worldMarkers, minimapMarkers)

        return GameState(
            phase = phaseReading.phase,
            phaseConfidence = phaseReading.confidence,
            health = health,
            mana = mana,
            skills = skills,
            self = self.firstOrNull(),
            allies = allies,
            enemies = enemies,
            objectives = objectives,
            minimap = minimapBounds?.let {
                MinimapObservation(
                    bounds = it,
                    entities = minimapMarkers,
                    confidence = minimapMarkers.maxOfOrNull { marker -> marker.confidence } ?: 0f,
                )
            },
            indicators = indicators,
            confidence = overall,
            timestampMs = frame.timestampMs,
            frameId = frame.frameId,
            frameWidth = frame.width,
            frameHeight = frame.height,
            source = id,
            notes = notes,
        )
    }

    private fun indicator(id: String, observed: List<DetectedEntity>): UiIndicatorObservation =
        UiIndicatorObservation(
            id = id,
            present = observed.isNotEmpty(),
            confidence = observed.maxOfOrNull { it.confidence } ?: 0f,
        )

    private fun overallConfidence(
        phase: PhaseReading,
        health: MetricObservation,
        mana: MetricObservation,
        skills: List<SkillObservation>,
        worldMarkers: List<DetectedEntity>,
        minimapMarkers: List<DetectedEntity>,
    ): Float {
        if (phase.phase == GamePhase.UNKNOWN) return 0f
        val signals = mutableListOf(phase.confidence)
        if (health.isKnown) signals += health.confidence
        if (mana.isKnown) signals += mana.confidence
        skills.forEach { signals += it.confidence }
        worldMarkers.forEach { signals += it.confidence }
        minimapMarkers.forEach { signals += it.confidence }
        val mean = signals.average().toFloat()
        return min(PerceptionProfile.MAX_HEURISTIC_CONFIDENCE, max(0f, mean))
    }

    // ------------------------------------------------------------------- readers

    private data class PhaseReading(val phase: GamePhase, val confidence: Float)

    private fun readPhase(grid: Grid): PhaseReading {
        val cell = grid.cellsFor(profile.phaseRegion)
        if (cell.isEmpty()) return PhaseReading(GamePhase.UNKNOWN, 0f)
        val counts = LinkedHashMap<GamePhase, Int>()
        var matchedTotal = 0
        cell.forEachColor { color ->
            val phase = profile.phaseColors.entries.firstOrNull { it.value.matches(color) }?.key
            if (phase != null) {
                counts[phase] = (counts[phase] ?: 0) + 1
                matchedTotal++
            }
        }
        val best = counts.maxByOrNull { it.value } ?: return PhaseReading(GamePhase.UNKNOWN, 0f)
        val ratio = matchedTotal.toFloat() / cell.sampleCount.toFloat()
        if (ratio < profile.phaseMinRatio) return PhaseReading(GamePhase.UNKNOWN, 0f)
        val dominance = best.value.toFloat() / matchedTotal.coerceAtLeast(1).toFloat()
        val confidence = PerceptionProfile.MAX_HEURISTIC_CONFIDENCE * ratio.coerceIn(0f, 1f) * dominance.coerceIn(0f, 1f)
        return PhaseReading(best.key, confidence)
    }

    private fun readBar(
        grid: Grid,
        region: NormalizedRect,
        fill: ColorMatcher?,
        track: ColorMatcher?,
    ): MetricObservation {
        if (fill == null) return MetricObservation.UNKNOWN
        val cell = grid.cellsFor(region)
        if (cell.isEmpty()) return MetricObservation.UNKNOWN
        var fillCount = 0
        var trackCount = 0
        cell.forEachColor { color ->
            if (fill.matches(color)) fillCount++ else if (track?.matches(color) == true) trackCount++
        }
        val considered = fillCount + trackCount
        if (considered == 0) return MetricObservation.UNKNOWN
        val fraction = fillCount.toFloat() / considered.toFloat()
        val coverage = considered.toFloat() / cell.sampleCount.toFloat()
        val confidence = PerceptionProfile.MAX_HEURISTIC_CONFIDENCE *
            min(1f, coverage / MIN_BAR_COVERAGE.coerceAtLeast(0.01f))
        return MetricObservation(fraction.coerceIn(0f, 1f), confidence.coerceIn(0f, 1f))
    }

    private fun PerceptionProfile.manaTrackColorOrNull(): ColorMatcher? = manaFillColor?.let {
        // Mana uses the same track colour convention as health unless a dedicated one exists.
        healthTrackColor
    }

    private fun readSkills(grid: Grid): List<SkillObservation> {
        val ready = profile.skillReadyColor
        val cooldown = profile.skillCooldownColor
        return profile.skillRegions.mapIndexed { index, region ->
            if (ready == null) {
                SkillObservation(slot = index, confidence = 0f)
            } else {
                val cell = grid.cellsFor(region)
                var readyCount = 0
                var cooldownCount = 0
                cell.forEachColor { color ->
                    if (ready.matches(color)) readyCount++ else if (cooldown?.matches(color) == true) cooldownCount++
                }
                val considered = readyCount + cooldownCount
                if (considered == 0) {
                    SkillObservation(slot = index, ready = null, bounds = region, confidence = 0f)
                } else {
                    val readyFraction = readyCount.toFloat() / considered.toFloat()
                    val confidence = PerceptionProfile.MAX_HEURISTIC_CONFIDENCE *
                        min(1f, considered.toFloat() / cell.sampleCount.toFloat() / MIN_BAR_COVERAGE.coerceAtLeast(0.01f))
                    SkillObservation(
                        slot = index,
                        ready = readyFraction >= 0.5f,
                        cooldownFraction = (1f - readyFraction).coerceIn(0f, 1f),
                        bounds = region,
                        confidence = confidence.coerceIn(0f, 1f),
                    )
                }
            }
        }
    }

    private fun detectMarkers(grid: Grid): List<DetectedEntity> {
        if (profile.entityColors.isEmpty()) return emptyList()
        val minCells = max(1, (grid.size * profile.minMarkerGridFraction).toInt())
        val results = mutableListOf<DetectedEntity>()
        for ((kind, matcher) in profile.entityColors) {
            val mask = BooleanArray(grid.size)
            for (i in 0 until grid.size) if (matcher.matches(grid.colors[i])) mask[i] = true
            results += components(grid, mask, minCells).map { bounds ->
                val area = bounds.area
                val confidence = PerceptionProfile.MAX_HEURISTIC_CONFIDENCE *
                    min(1f, area.toFloat() / minCells.toFloat())
                DetectedEntity(kind = kind, bounds = bounds.rect, confidence = confidence.coerceIn(0f, 1f))
            }
        }
        return results
    }

    private data class Component(val rect: NormalizedRect, val area: Int)

    private fun components(grid: Grid, mask: BooleanArray, minCells: Int): List<Component> {
        val visited = BooleanArray(grid.size)
        val out = mutableListOf<Component>()
        val stack = IntArray(grid.size)
        for (start in 0 until grid.size) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var count = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val gx = idx % grid.width
                val gy = idx / grid.width
                count++
                if (gx < minX) minX = gx
                if (gx > maxX) maxX = gx
                if (gy < minY) minY = gy
                if (gy > maxY) maxY = gy
                for (neighbour in grid.neighbours(idx)) {
                    if (mask[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        stack[sp++] = neighbour
                    }
                }
            }
            if (count >= minCells) out += Component(grid.normalizedRect(minX, minY, maxX, maxY), count)
        }
        return out
    }

    // ---------------------------------------------------------------------- grid

    /** Point-sampled downscale of a frame. All detection runs on this, never on full resolution. */
    private class Grid(frame: ImageFrame, maxWidth: Int) {
        val width: Int
        val height: Int
        val colors: IntArray
        val size: Int get() = width * height

        init {
            val columns = min(maxWidth, frame.width).coerceAtLeast(1)
            width = columns
            height = max(1, (frame.height.toLong() * columns / frame.width).toInt())
            // Fractional steps so the grid spans the *whole* frame: with integer steps the right
            // and bottom edges would never be sampled and normalized coordinates would be skewed.
            val stepX = frame.width.toFloat() / width.toFloat()
            val stepY = frame.height.toFloat() / height.toFloat()
            colors = IntArray(width * height)
            for (gy in 0 until height) {
                val sy = min(frame.height - 1, ((gy + 0.5f) * stepY).toInt())
                val rowOffset = sy * frame.width
                val gridOffset = gy * width
                for (gx in 0 until width) {
                    val sx = min(frame.width - 1, ((gx + 0.5f) * stepX).toInt())
                    colors[gridOffset + gx] = frame.argb[rowOffset + sx]
                }
            }
        }

        fun neighbours(index: Int): IntArray {
            val gx = index % width
            val gy = index / width
            val out = IntArray(4)
            var n = 0
            if (gx > 0) out[n++] = index - 1
            if (gx < width - 1) out[n++] = index + 1
            if (gy > 0) out[n++] = index - width
            if (gy < height - 1) out[n++] = index + width
            return if (n == 4) out else out.copyOf(n)
        }

        fun normalizedRect(minX: Int, minY: Int, maxX: Int, maxY: Int): NormalizedRect =
            NormalizedRect(
                left = minX.toFloat() / width.toFloat(),
                top = minY.toFloat() / height.toFloat(),
                right = (maxX + 1).toFloat() / width.toFloat(),
                bottom = (maxY + 1).toFloat() / height.toFloat(),
            ).clamped()

        fun cellsFor(region: NormalizedRect): CellRange {
            val clamped = region.clamped()
            val x0 = (clamped.left * width).toInt().coerceIn(0, width - 1)
            val y0 = (clamped.top * height).toInt().coerceIn(0, height - 1)
            val x1 = (clamped.right * width).toInt().coerceIn(x0 + 1, width)
            val y1 = (clamped.bottom * height).toInt().coerceIn(y0 + 1, height)
            return CellRange(x0, y0, x1, y1)
        }

        inner class CellRange(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
            val sampleCount: Int get() = (x1 - x0) * (y1 - y0)
            fun isEmpty(): Boolean = sampleCount <= 0
            fun forEachColor(action: (Int) -> Unit) {
                for (gy in y0 until y1) {
                    val offset = gy * width
                    for (gx in x0 until x1) action(colors[offset + gx])
                }
            }
        }
    }

    private fun NormalizedRect.contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    companion object {
        /** Grid columns. 160 keeps a 2400x1080 frame down to ~160x72 cells (~11.5k samples). */
        const val DEFAULT_MAX_GRID_WIDTH = 160

        /** A bar region should be at least this covered by fill+track to be considered readable. */
        private const val MIN_BAR_COVERAGE = 0.35f
    }
}

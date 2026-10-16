package com.gameagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gameagent.app.AppGraph
import com.gameagent.app.runtime.AgentRuntime
import com.gameagent.app.ui.components.HintText
import com.gameagent.app.ui.components.MetricRow
import com.gameagent.app.ui.components.SectionCard
import com.gameagent.app.ui.theme.GameAgentColors
import com.gameagent.core.fixtures.SyntheticSceneRenderer
import com.gameagent.core.fixtures.TestSceneState
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.SessionState
import java.util.Locale

/**
 * The local test interface.
 *
 * This screen is a complete, playable stand-in for a game: it is drawn by the same renderer that the
 * fixtures use, the perception stage reads it as pixels, the policy decides, the validator gates and
 * the test controller applies the result back to this scene. That means the entire
 * capture -> perceive -> decide -> validate -> act -> observe loop can be demonstrated and measured
 * **without MLBB, without screen-capture permission and without any AI service** - which is exactly
 * what makes the rest of the system verifiable.
 *
 * It is not MLBB and does not pretend to be: the scene is flat colours and rectangles.
 */
@Composable
fun TestScreenScreen(graph: AppGraph) {
    val runtime = graph.runtime
    val scene by runtime.testScene.collectAsStateWithLifecycle()
    val sessionState by runtime.sessionState.collectAsStateWithLifecycle()
    val selfTestEnabled by runtime.selfTestEnabled.collectAsStateWithLifecycle()
    val decision by runtime.lastDecision.collectAsStateWithLifecycle()
    val captureLive by runtime.captureEngine.state.collectAsStateWithLifecycle()
    val settings by runtime.settings.collectAsStateWithLifecycle()

    val frame = rememberTestSceneImage(scene)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Mock game screen") {
            Image(
                bitmap = frame,
                contentDescription = "Mock game screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(AgentRuntime.TEST_SCENE_WIDTH.toFloat() / AgentRuntime.TEST_SCENE_HEIGHT.toFloat())
                    .background(GameAgentColors.SurfaceVariant, RoundedCornerShape(10.dp)),
            )
            MetricRow("Player token", "%.2f, %.2f".format(Locale.US, scene.self.x, scene.self.y))
            MetricRow("Health / mana", "%.2f / %.2f".format(Locale.US, scene.healthFraction, scene.manaFraction))
            MetricRow("Abilities ready", "${scene.readySkillCount} / ${scene.skillReady.size}")
            MetricRow("Enemies alive", scene.enemyCount.toString())
            MetricRow("Actions applied to the scene", scene.actionCount.toString())
            HintText(
                "This is a mock HUD made of flat colour regions, not Mobile Legends. It exists so the full " +
                    "pipeline can be tested deterministically.",
            )
        }

        SectionCard(
            title = "Autonomous loop",
            status = if (selfTestEnabled) {
                com.gameagent.app.ui.components.StatusIndicator("self-test", com.gameagent.app.ui.components.StatusTone.GOOD)
            } else {
                com.gameagent.app.ui.components.StatusIndicator("off", com.gameagent.app.ui.components.StatusTone.IDLE)
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = selfTestEnabled,
                    onCheckedChange = { enabled -> runtime.setSelfTestEnabled(enabled) },
                    enabled = !captureLive.isLive,
                )
                Column(Modifier.weight(1f)) {
                    Text("Run the agent on this test screen", style = MaterialTheme.typography.bodyMedium, color = GameAgentColors.TextPrimary)
                    Text(
                        "Feeds rendered test frames straight into the session - no screen capture, no permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GameAgentColors.TextSecondary,
                    )
                }
            }
            if (captureLive.isLive) {
                HintText("Screen capture is live, so the agent is consuming real frames instead. Stop capture to use the test scene.")
            }
            MetricRow("Session state", sessionState.name)
            MetricRow(
                "Action execution",
                if (settings.actionsEnabled) "enabled" else "disabled (detections still run)",
                tone = if (settings.actionsEnabled) com.gameagent.app.ui.components.StatusTone.WARN else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { runtime.analyseTestSceneFrame() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Analyse one frame")
                }
                OutlinedButton(
                    onClick = { runtime.resetCounters() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Reset counters")
                }
            }
            decision?.let { current ->
                MetricRow("Last decision", current.summary())
                HintText(current.explanation)
            }
            HintText(
                "To act on this scene, enable \"Allow action execution\" on the dashboard and set the input target to the test scene.",
            )
        }

        SectionCard(title = "Manual scene setup") {
            FilterChipRow(
                label = "Phase",
                options = GamePhase.entries.filter { it != GamePhase.UNKNOWN }.map { it.name },
                selected = scene.phase.name,
                onSelect = { name -> runtime.testScene.value = scene.copy(phase = GamePhase.valueOf(name)) },
            )
            SliderRow(
                label = "Health",
                value = scene.healthFraction,
                onChange = { value -> runtime.testScene.value = scene.copy(healthFraction = value) },
            )
            SliderRow(
                label = "Mana",
                value = scene.manaFraction,
                onChange = { value -> runtime.testScene.value = scene.copy(manaFraction = value) },
            )
            Text("Abilities", style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scene.skillReady.forEachIndexed { index, ready ->
                    FilterChip(
                        selected = ready,
                        onClick = {
                            val updated = scene.skillReady.toMutableList().also { it[index] = !ready }
                            runtime.testScene.value = scene.copy(skillReady = updated)
                        },
                        label = { Text("slot $index: ${if (ready) "ready" else "cooldown"}") },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        runtime.testScene.value = scene.copy(enemies = scene.enemies + com.gameagent.core.geometry.NormalizedPoint(0.6f, 0.6f))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Add enemy")
                }
                OutlinedButton(
                    onClick = { runtime.testScene.value = scene.copy(enemies = scene.enemies.dropLast(1)) },
                    enabled = scene.enemies.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Remove enemy")
                }
            }
            OutlinedButton(
                onClick = { runtime.testScene.value = TestSceneState(phase = GamePhase.IN_MATCH) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Reset scene")
            }
        }

        SectionCard(title = "Scene events") {
            if (scene.eventLog.isEmpty()) {
                HintText("No scene events yet.")
            } else {
                scene.eventLog.reversed().take(10).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = GameAgentColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option.lowercase(Locale.US)) },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextSecondary)
            Text(
                "%.2f".format(Locale.US, value),
                style = MaterialTheme.typography.bodySmall,
                color = GameAgentColors.TextPrimary,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}

/**
 * Renders the scene at its canonical size and caches the bitmap until the scene changes.
 *
 * The bitmap is rebuilt only when the scene actually changes (a few times per second at most), which
 * keeps the display in lockstep with what perception sees.
 */
@Composable
fun rememberTestSceneImage(scene: TestSceneState): ImageBitmap {
    val width = AgentRuntime.TEST_SCENE_WIDTH
    val height = AgentRuntime.TEST_SCENE_HEIGHT
    return remember(scene, width, height) {
        val pixels = SyntheticSceneRenderer.render(scene, width, height)
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

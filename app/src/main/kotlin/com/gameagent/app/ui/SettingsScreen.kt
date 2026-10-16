package com.gameagent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gameagent.app.AppGraph
import com.gameagent.app.capture.ScreenshotStore
import com.gameagent.app.data.AgentSource
import com.gameagent.app.data.AppSettings
import com.gameagent.app.data.InputTarget
import com.gameagent.app.data.PerceptionSource
import com.gameagent.app.ui.components.HintText
import com.gameagent.app.ui.components.MetricRow
import com.gameagent.app.ui.components.SectionCard
import com.gameagent.app.ui.components.SectionDivider
import com.gameagent.app.ui.theme.GameAgentColors
import com.gameagent.core.geometry.CoordinateMapper
import java.util.Locale

/**
 * Settings.
 *
 * Two things are deliberately textual rather than clever: the safety limits are shown with their
 * numbers, and every switch that relaxes a restriction carries a one-line explanation of what it
 * relaxes. The app's default posture is "capture and analyse, do not act", and the UI says so.
 */
@Composable
fun SettingsScreen(graph: AppGraph) {
    val runtime = graph.runtime
    val settings by runtime.settings.collectAsStateWithLifecycle()
    val aiConfigured by runtime.aiConfigured.collectAsStateWithLifecycle()
    val accessibilityConnected by runtime.accessibilityConnected.collectAsStateWithLifecycle()

    var apiKeyInput by remember { mutableStateOf("") }
    var keyStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Capture") {
            SliderRow(
                label = "Capture interval",
                value = settings.captureIntervalMs.toFloat(),
                valueRange = AppSettings.MIN_CAPTURE_INTERVAL_MS.toFloat()..1_000f,
                display = "${settings.captureIntervalMs} ms",
                onChange = { value ->
                    graph.settingsRepository.update { it.copy(captureIntervalMs = value.toLong().coerceAtLeast(50L)) }
                },
            )
            SliderRow(
                label = "Capture scale",
                value = settings.captureScale,
                valueRange = 0.2f..1f,
                display = "%.2f".format(Locale.US, settings.captureScale),
                onChange = { value -> graph.settingsRepository.update { it.copy(captureScale = value) } },
            )
            HintText(
                "A smaller scale is cheaper to process and keeps normalised coordinates exact, because the " +
                    "capture surface is scaled uniformly. Capture is capped at 720p worth of pixels.",
            )
            SwitchRow(
                label = "Show the live preview",
                description = "Building the preview bitmap costs work per frame; frames are analysed either way.",
                checked = settings.previewEnabled,
                onChange = { value -> graph.settingsRepository.update { it.copy(previewEnabled = value) } },
            )
        }

        SectionCard(title = "Action execution") {
            SwitchRow(
                label = "Allow action execution",
                description = "Master switch. Off by default; with it off nothing is ever injected.",
                checked = settings.actionsEnabled,
                onChange = { value -> graph.settingsRepository.update { it.copy(actionsEnabled = value) } },
            )
            SectionDivider()
            FilterChipRow(
                label = "Input target",
                options = InputTarget.entries.map { it.name },
                selected = settings.inputTarget.name,
                onSelect = { name -> graph.settingsRepository.update { it.copy(inputTarget = InputTarget.valueOf(name)) } },
            )
            when (settings.inputTarget) {
                InputTarget.TEST_SCENE -> HintText(
                    "Actions are delivered to the companion's own mock game loop. This is the only target that " +
                        "needs no special permission.",
                )

                InputTarget.ACCESSIBILITY -> HintText(
                    if (accessibilityConnected) {
                        "Actions are delivered to whatever is on screen through the accessibility service. " +
                            "Single pointer only; no standalone RELEASE. Use a practice environment, not ranked matches."
                    } else {
                        "The accessibility controller is not connected, so no action can be executed. " +
                            "Enable 'GameAgent input controller' in Android Settings → Accessibility."
                    },
                )
            }
            SectionDivider()
            SliderRow(
                label = "Minimum confidence",
                value = settings.minConfidence,
                valueRange = 0f..1f,
                display = "%.2f".format(Locale.US, settings.minConfidence),
                onChange = { value -> graph.settingsRepository.update { it.copy(minConfidence = value) } },
            )
            SliderRow(
                label = "Minimum interval between actions",
                value = settings.minIntervalBetweenActionsMs.toFloat(),
                valueRange = 0f..2_000f,
                display = "${settings.minIntervalBetweenActionsMs} ms",
                onChange = { value -> graph.settingsRepository.update { it.copy(minIntervalBetweenActionsMs = value.toLong()) } },
            )
            SliderRow(
                label = "Maximum gesture duration",
                value = settings.maxGestureDurationMs.toFloat(),
                valueRange = 100f..5_000f,
                display = "${settings.maxGestureDurationMs} ms",
                onChange = { value -> graph.settingsRepository.update { it.copy(maxGestureDurationMs = value.toLong()) } },
            )
            SliderRow(
                label = "Maximum actions per session",
                value = settings.maxActionsPerSession.toFloat(),
                valueRange = 10f..1_000f,
                display = settings.maxActionsPerSession.toString(),
                onChange = { value -> graph.settingsRepository.update { it.copy(maxActionsPerSession = value.toInt()) } },
            )
            SliderRow(
                label = "Maximum coordinate age",
                value = settings.maxGeometryAgeMs.toFloat(),
                valueRange = 200f..5_000f,
                display = "${settings.maxGeometryAgeMs} ms (default ${CoordinateMapper.DEFAULT_MAX_GEOMETRY_AGE_MS})",
                onChange = { value -> graph.settingsRepository.update { it.copy(maxGeometryAgeMs = value.toLong()) } },
            )
            HintText(
                "An action built from a frame older than this limit is refused rather than aimed at the screen.",
            )
        }

        SectionCard(title = "Perception and decision") {
            FilterChipRow(
                label = "Perception source",
                options = PerceptionSource.entries.map { it.name },
                selected = settings.perceptionSource.name,
                onSelect = { name -> graph.settingsRepository.update { it.copy(perceptionSource = PerceptionSource.valueOf(name)) } },
            )
            HintText(
                "Only the deterministic colour/geometry detector ships today, calibrated for the built-in test " +
                    "HUD. It is not trained on Mobile Legends, so against a real match it reports UNKNOWN instead " +
                    "of guessing. A trained model plugs into the same interface.",
            )
            SectionDivider()
            FilterChipRow(
                label = "Decision source",
                options = AgentSource.entries.map { it.name },
                selected = settings.agentSource.name,
                onSelect = { name -> graph.settingsRepository.update { it.copy(agentSource = AgentSource.valueOf(name)) } },
            )
            HintText(
                if (settings.agentSource == AgentSource.DETERMINISTIC) {
                    "Rule-based policy: no model, no network, no randomness. Default."
                } else {
                    "A compact JSON observation is sent per decision and only a strictly validated action proposal " +
                        "is accepted. Any failure falls back to the deterministic policy. Costs are billed by your provider."
                },
            )
        }

        SectionCard(title = "Optional AI provider") {
            MetricRow("Status", if (aiConfigured) "configured" else "not configured")
            OutlinedTextField(
                value = settings.aiBaseUrl,
                onValueChange = { value -> graph.settingsRepository.update { it.copy(aiBaseUrl = value.trim()) } },
                label = { Text("Base URL (https)") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.aiModel,
                onValueChange = { value -> graph.settingsRepository.update { it.copy(aiModel = value.trim()) } },
                label = { Text("Model name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("API key") },
                supportingText = { Text("Stored encrypted with a key held in the Android Keystore. Never logged.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val saved = graph.keyStore.storeAiApiKey(apiKeyInput)
                        apiKeyInput = ""
                        graph.runtime.refreshAiConfiguration()
                        keyStatus = if (saved) "Key stored." else "Could not store the key."
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Save key")
                }
                OutlinedButton(
                    onClick = {
                        graph.keyStore.clearAiApiKey()
                        graph.runtime.refreshAiConfiguration()
                        keyStatus = "Key cleared."
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Clear key")
                }
            }
            keyStatus?.let { HintText(it) }
            HintText(
                "The AI provider is entirely optional: every feature of the app works without it, using the local " +
                    "policy. Requests contain compact numbers, not screenshots.",
            )
        }

        SectionCard(title = "Privacy and storage") {
            SwitchRow(
                label = "Store screenshots on device",
                description = "Off by default. Saves at most one frame every ${settings.screenshotIntervalMs} ms, " +
                    "keeps at most ${ScreenshotStore.MAX_FILES} files and at most " +
                    "${ScreenshotStore.MAX_TOTAL_BYTES / (1024 * 1024)} MiB in total.",
                checked = settings.storeScreenshots,
                onChange = { value -> graph.settingsRepository.update { it.copy(storeScreenshots = value) } },
            )
            MetricRow("Stored files", graph.screenshotStore.count().toString())
            MetricRow("Bytes on disk", "${graph.screenshotStore.totalBytes() / 1024} KiB")
            OutlinedButton(
                onClick = { graph.screenshotStore.clear() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Delete stored screenshots")
            }
            SwitchRow(
                label = "Upload diagnostics (not implemented)",
                description = "There is no upload path in this build. The switch exists so the intent is explicit; " +
                    "turning it on changes no behaviour.",
                checked = settings.diagnosticsUploadEnabled,
                onChange = { value -> graph.settingsRepository.update { it.copy(diagnosticsUploadEnabled = value) } },
            )
        }

        SectionCard(title = "About") {
            HintText(
                "GameAgent is a research prototype. It captures the screen with your permission, interprets what it " +
                    "can, proposes actions, validates them and executes only the ones that pass. It does not use root, " +
                    "hidden APIs or anti-cheat evasion, and it is not intended for ranked or public competitive play.",
            )
            HintText(
                "Unit tests cover the geometry, validator, session state machine, decision parsing and the mock game " +
                    "loop. Anything requiring a physical device is reported as untested in the README, not as passing.",
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = GameAgentColors.TextPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextSecondary)
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextSecondary)
            Text(display, style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextPrimary)
        }
        Slider(value = value.coerceIn(valueRange.start, valueRange.endInclusive), onValueChange = onChange, valueRange = valueRange)
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

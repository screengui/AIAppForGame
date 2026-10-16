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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gameagent.app.AppGraph
import com.gameagent.app.capture.ScreenshotStore
import com.gameagent.app.ui.components.EventLevelDot
import com.gameagent.app.ui.components.HintText
import com.gameagent.app.ui.components.MetricRow
import com.gameagent.app.ui.components.SectionCard
import com.gameagent.app.ui.components.SectionDivider
import com.gameagent.app.ui.components.StatusIndicator
import com.gameagent.app.ui.components.StatusTone
import com.gameagent.app.ui.theme.GameAgentColors
import com.gameagent.core.diagnostics.EventLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val diagTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * Local diagnostics.
 *
 * Everything here is in-memory and on-device. Screenshots are only written when the user enables it
 * in Settings, and nothing is ever uploaded - there is deliberately no "send diagnostics" button.
 */
@Composable
fun DiagnosticsScreen(graph: AppGraph) {
    val runtime = graph.runtime
    val sessionState by runtime.sessionState.collectAsStateWithLifecycle()
    val metrics by runtime.metrics.collectAsStateWithLifecycle()
    val captureMetrics by runtime.captureEngine.metrics.collectAsStateWithLifecycle()
    val validatorStats by runtime.validatorStats.collectAsStateWithLifecycle()
    val history by runtime.stateHistory.collectAsStateWithLifecycle()
    val revision by graph.eventLog.revision.collectAsStateWithLifecycle()
    val stopState by runtime.emergencyStop.state.collectAsStateWithLifecycle()
    val settings by runtime.settings.collectAsStateWithLifecycle()

    var minLevel by remember { mutableStateOf(EventLevel.DEBUG) }
    var screenshotTick by remember { mutableIntStateOf(0) }

    val allEvents = remember(revision) { graph.eventLog.snapshot() }
    val events = remember(allEvents, minLevel) {
        allEvents.filter { it.level.ordinal >= minLevel.ordinal }.takeLast(40).reversed()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "Session",
            status = if (stopState.engaged) StatusIndicator("emergency stop", StatusTone.BAD) else StatusIndicator(sessionState.name, StatusTone.IDLE),
        ) {
            MetricRow("State", sessionState.name)
            MetricRow("Actions enabled", validatorStats.actionsEnabled.toString())
            MetricRow("Session actions used", "${validatorStats.effectorActions} / ${validatorStats.maxActionsPerSession}")
            MetricRow("Rejected actions", validatorStats.rejected.toString())
            validatorStats.lastRejectionCode?.let { code ->
                MetricRow("Last rejection", "$code — ${validatorStats.lastRejectionDetail.orEmpty()}", tone = StatusTone.WARN)
            }
            stopState.reason?.let { reason ->
                MetricRow("Emergency stop reason", reason, tone = StatusTone.BAD)
                MetricRow("Engaged at", diagTimeFormat.format(Date(stopState.engagedAtMs)))
            }
            SectionDivider()
            Text("State transitions", style = MaterialTheme.typography.bodySmall, color = GameAgentColors.TextSecondary)
            if (history.isEmpty()) {
                HintText("No transitions yet.")
            } else {
                history.takeLast(10).reversed().forEach { change ->
                    Text(
                        text = "${diagTimeFormat.format(Date(change.timestampMs))}  ${change.from} → ${change.to}  (${change.reason})",
                        style = MaterialTheme.typography.labelSmall,
                        color = GameAgentColors.TextSecondary,
                    )
                }
            }
        }

        SectionCard(title = "Capture performance") {
            MetricRow("Frames captured", captureMetrics.framesCaptured.toString())
            MetricRow("Frames dropped", captureMetrics.framesDropped.toString())
            MetricRow("Frame rate", "%.2f fps".format(Locale.US, captureMetrics.framesPerSecond))
            MetricRow("Surface", "${captureMetrics.captureWidth}x${captureMetrics.captureHeight}")
            MetricRow("Display", "${captureMetrics.displayWidth}x${captureMetrics.displayHeight}")
            MetricRow("Bytes per frame", captureMetrics.bytesPerFrame.toString())
            MetricRow(
                "Frame interval last / mean / p95",
                "%d / %.0f / %d ms".format(
                    Locale.US,
                    captureMetrics.frameInterval.lastMs ?: 0L,
                    captureMetrics.frameInterval.meanMs,
                    captureMetrics.frameInterval.p95Ms,
                ),
            )
        }

        SectionCard(title = "Perception, decision and input latency") {
            MetricRow("Perception samples", metrics.perception.samples.toString())
            MetricRow("Perception last / p50 / p95", "${metrics.perception.lastMs ?: 0L} / ${metrics.perception.p50Ms} / ${metrics.perception.p95Ms} ms")
            MetricRow("Perception max", "${metrics.perception.maxMs} ms")
            MetricRow("Decision last / p50", "${metrics.decision.lastMs ?: 0L} / ${metrics.decision.p50Ms} ms")
            MetricRow("Input last / p50", "${metrics.input.lastMs ?: 0L} / ${metrics.input.p50Ms} ms")
            MetricRow("Observations processed", metrics.observationsProcessed.toString())
            MetricRow("Observations failed", metrics.observationsFailed.toString(), tone = if (metrics.observationsFailed > 0L) StatusTone.WARN else null)
            MetricRow("Decisions made", metrics.decisionsMade.toString())
            MetricRow("Actions accepted", metrics.actionsAccepted.toString())
            MetricRow("Actions executed", metrics.actionsExecuted.toString())
            MetricRow("Actions rejected", metrics.actionsRejected.toString())
            MetricRow("Actions failed", metrics.actionsFailed.toString())
        }

        SectionCard(title = "Screenshot storage") {
            val store = graph.screenshotStore
            MetricRow("Enabled", settings.storeScreenshots.toString())
            MetricRow("Stored files", "${store.count()} / ${ScreenshotStore.MAX_FILES}")
            MetricRow("Bytes on disk", "${store.totalBytes() / 1024} KiB")
            HintText("Stored only when enabled in Settings, capped in count and total size, never uploaded.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { store.clear(); screenshotTick++ },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Delete stored screenshots")
                }
            }
            MetricRow("Cache version", screenshotTick.toString())
        }

        SectionCard(title = "Events") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventLevel.entries.forEach { level ->
                    FilterChip(
                        selected = level == minLevel,
                        onClick = { minLevel = level },
                        label = { Text(level.name.lowercase(Locale.US)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { graph.runtime.resetCounters() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Reset counters")
                }
                OutlinedButton(
                    onClick = { graph.runtime.clearDiagnostics() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Clear diagnostics")
                }
            }
            SectionDivider()
            if (events.isEmpty()) {
                HintText("No events at this level.")
            } else {
                events.forEach { event ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        EventLevelDot(event.level)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "[${event.category}] ${event.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (event.level == EventLevel.ERROR) GameAgentColors.Danger else GameAgentColors.TextPrimary,
                            )
                            if (event.data.isNotEmpty()) {
                                Text(
                                    text = event.data.entries.joinToString(" ") { "${it.key}=${it.value}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GameAgentColors.TextSecondary,
                                )
                            }
                        }
                        Text(
                            text = diagTimeFormat.format(Date(event.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = GameAgentColors.TextSecondary,
                        )
                    }
                }
            }
            HintText(
                "Diagnostics never contain API keys, tokens or personal data; the AI key is never logged.",
            )
        }
    }
}

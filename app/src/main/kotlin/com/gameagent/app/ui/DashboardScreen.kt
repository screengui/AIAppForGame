package com.gameagent.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gameagent.app.AppGraph
import com.gameagent.app.capture.CaptureState
import com.gameagent.app.capture.PreviewImage
import com.gameagent.app.data.AgentSource
import com.gameagent.app.data.InputTarget
import com.gameagent.app.data.PerceptionSource
import com.gameagent.app.ui.components.HintText
import com.gameagent.app.ui.components.MetricRow
import com.gameagent.app.ui.components.SectionCard
import com.gameagent.app.ui.components.SectionDivider
import com.gameagent.app.ui.components.StatusIndicator
import com.gameagent.app.ui.components.StatusTone
import com.gameagent.app.ui.theme.GameAgentColors
import com.gameagent.core.diagnostics.EventLevel
import com.gameagent.core.geometry.NormalizedRect
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.GameState
import com.gameagent.core.model.SessionState
import com.gameagent.core.model.ValidationStatus
import com.gameagent.core.session.StepOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * The main dashboard.
 *
 * Every value shown here comes from a real `StateFlow` produced by the pipeline; nothing is
 * fabricated, and anything that could not be determined is shown as "unknown" rather than as a
 * plausible-looking default. Controls are only enabled when their action is actually possible, so a
 * disabled button always means "not available right now", never "not implemented".
 */
@Composable
fun DashboardScreen(graph: AppGraph, onRequestCaptureConsent: () -> Unit) {
    val runtime = graph.runtime
    val captureState by runtime.captureEngine.state.collectAsStateWithLifecycle()
    val captureMetrics by runtime.captureEngine.metrics.collectAsStateWithLifecycle()
    val preview by runtime.captureEngine.preview.collectAsStateWithLifecycle()
    val sessionState by runtime.sessionState.collectAsStateWithLifecycle()
    val metrics by runtime.metrics.collectAsStateWithLifecycle()
    val decision by runtime.lastDecision.collectAsStateWithLifecycle()
    val gameState by runtime.latestGameState.collectAsStateWithLifecycle()
    val validatorStats by runtime.validatorStats.collectAsStateWithLifecycle()
    val settings by runtime.settings.collectAsStateWithLifecycle()
    val stopState by runtime.emergencyStop.state.collectAsStateWithLifecycle()
    val accessibilityConnected by runtime.accessibilityConnected.collectAsStateWithLifecycle()
    val accessibilityEnabled by runtime.accessibilityEnabledInSettings.collectAsStateWithLifecycle()
    val lastOutcome by runtime.lastOutcome.collectAsStateWithLifecycle()
    val aiConfigured by runtime.aiConfigured.collectAsStateWithLifecycle()
    val logRevision by graph.eventLog.revision.collectAsStateWithLifecycle()
    val events = remember(logRevision) { graph.eventLog.recent(limit = 8) }

    var showOverlay by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            sessionState = sessionState,
            captureState = captureState,
            emergencyEngaged = stopState.engaged,
            actionsEnabled = settings.actionsEnabled,
        )

        PermissionsCard(
            captureState = captureState,
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConnected = accessibilityConnected,
            inputTarget = settings.inputTarget,
            onRequestCaptureConsent = onRequestCaptureConsent,
            onOpenAccessibilitySettings = {
                graph.runtime.refreshAccessibilityStatus()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { error ->
                    graph.eventLog.warn(EventCategory.SYSTEM, "could not open accessibility settings", mapOf("error" to error.toString()))
                }
            },
        )

        CaptureCard(
            captureState = captureState,
            framesPerSecond = captureMetrics.framesPerSecond,
            framesCaptured = captureMetrics.framesCaptured,
            framesDropped = captureMetrics.framesDropped,
            lastFrameAtMs = captureMetrics.lastFrameAtMs,
            captureWidth = captureMetrics.captureWidth,
            captureHeight = captureMetrics.captureHeight,
            displayWidth = captureMetrics.displayWidth,
            displayHeight = captureMetrics.displayHeight,
            intervalMeanMs = captureMetrics.frameInterval.meanMs,
            onStart = onRequestCaptureConsent,
            onStop = { graph.runtime.stopCapture("stopped by the user") },
        )

        PreviewCard(
            preview = preview,
            gameState = gameState,
            decision = decision,
            showOverlay = showOverlay,
            onToggleOverlay = { showOverlay = it },
            previewEnabled = settings.previewEnabled,
        )

        ControlCard(
            settings = settings,
            sessionState = sessionState,
            captureLive = captureState.isLive,
            onToggleActions = { enabled -> graph.settingsRepository.update { it.copy(actionsEnabled = enabled) } },
            onStartAgent = { graph.runtime.startAgent() },
            onPause = { graph.runtime.pauseAgent() },
            onResume = { graph.runtime.resumeAgent() },
            onAnalyseOnce = { graph.runtime.analyseTestSceneFrame() },
            onResetCounters = { graph.runtime.resetCounters() },
        )

        AiSubsystemCard(
            perceptionId = settings.perceptionSource.name,
            perceptionDescription = if (settings.perceptionSource == PerceptionSource.HEURISTIC) {
                "Deterministic colour/geometry detector using the built-in test-scene profile"
            } else {
                "Perception disabled: every frame reports unknown"
            },
            agentSource = settings.agentSource,
            aiConfigured = aiConfigured,
        )

        GameStateCard(gameState)

        DecisionCard(decision, lastOutcome)

        InputCard(
            settings = settings,
            accessibilityConnected = accessibilityConnected,
            actionsExecuted = metrics.actionsExecuted,
            actionsAccepted = metrics.actionsAccepted,
            actionsRejected = metrics.actionsRejected,
            actionsFailed = metrics.actionsFailed,
            effectorActions = validatorStats.effectorActions,
            budget = validatorStats.maxActionsPerSession,
            lastRejection = validatorStats.lastRejectionCode?.let { code ->
                "$code — ${validatorStats.lastRejectionDetail.orEmpty()}"
            },
        )

        MetricsCard(
            perceptionP50 = metrics.perception.p50Ms,
            perceptionP95 = metrics.perception.p95Ms,
            decisionP50 = metrics.decision.p50Ms,
            inputP50 = metrics.input.p50Ms,
            observationsProcessed = metrics.observationsProcessed,
            observationsFailed = metrics.observationsFailed,
            decisionsMade = metrics.decisionsMade,
        )

        EventsCard(events)
    }
}

@Composable
private fun StatusCard(
    sessionState: SessionState,
    captureState: CaptureState,
    emergencyEngaged: Boolean,
    actionsEnabled: Boolean,
) {
    SectionCard(
        title = "Status",
        status = when {
            emergencyEngaged -> StatusIndicator("EMERGENCY STOP", StatusTone.BAD)
            sessionState == SessionState.ERROR -> StatusIndicator("error", StatusTone.BAD)
            sessionState == SessionState.RUNNING -> StatusIndicator("running", StatusTone.GOOD)
            captureState.isLive -> StatusIndicator("capture live", StatusTone.WARN)
            else -> StatusIndicator("idle", StatusTone.IDLE)
        },
    ) {
        MetricRow("Session state", sessionState.name)
        MetricRow(
            "Capture",
            when (val state = captureState) {
                is CaptureState.Idle -> "not started"
                is CaptureState.Starting -> "starting"
                is CaptureState.Running -> "${state.captureWidth}x${state.captureHeight} @ ${state.rotationDegrees}°"
                is CaptureState.Stopped -> "stopped: ${state.reason}"
                is CaptureState.Failed -> "failed: ${state.reason}"
            },
            tone = if (captureState.isLive) StatusTone.GOOD else null,
        )
        MetricRow(
            "Action execution",
            if (actionsEnabled) "enabled" else "disabled",
            tone = if (actionsEnabled) StatusTone.WARN else StatusTone.IDLE,
        )
        MetricRow(
            "Emergency stop",
            if (emergencyEngaged) "latched" else "clear",
            tone = if (emergencyEngaged) StatusTone.BAD else StatusTone.GOOD,
        )
        HintText(
            "Capture and analysis work with action execution disabled. Actions require the switch in the Control card.",
        )
    }
}

@Composable
private fun PermissionsCard(
    captureState: CaptureState,
    accessibilityEnabled: Boolean,
    accessibilityConnected: Boolean,
    inputTarget: InputTarget,
    onRequestCaptureConsent: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    SectionCard(title = "Permissions", status = capturePermissionStatus(captureState)) {
        MetricRow(
            "Screen capture",
            when (captureState) {
                is CaptureState.Running -> "granted for this session"
                is CaptureState.Stopped -> "not granted (${captureState.reason})"
                is CaptureState.Failed -> "unavailable (${captureState.reason})"
                is CaptureState.Starting -> "requesting"
                is CaptureState.Idle -> "not requested yet"
            },
        )
        MetricRow(
            "Accessibility controller",
            when {
                accessibilityConnected -> "enabled and connected"
                accessibilityEnabled -> "enabled but not connected yet"
                else -> "not enabled"
            },
            tone = if (accessibilityConnected) StatusTone.GOOD else StatusTone.IDLE,
        )
        OutlinedButton(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Open Android accessibility settings")
        }
        if (inputTarget == InputTarget.ACCESSIBILITY && !accessibilityConnected) {
            HintText(
                "The accessibility controller is selected but not connected, so no action can be executed. " +
                    "Enable 'GameAgent input controller' in Android Settings → Accessibility.",
            )
        }
        HintText(
            "Android requires a fresh consent for every capture session, so the dialog appears again after a stop.",
        )
    }
}

private fun capturePermissionStatus(state: CaptureState) = when (state) {
    is CaptureState.Running -> StatusIndicator("granted", StatusTone.GOOD)
    is CaptureState.Starting -> StatusIndicator("pending", StatusTone.WARN)
    is CaptureState.Failed -> StatusIndicator("failed", StatusTone.BAD)
    is CaptureState.Stopped -> StatusIndicator("revoked", StatusTone.WARN)
    is CaptureState.Idle -> StatusIndicator("unknown", StatusTone.IDLE)
}

@Composable
private fun CaptureCard(
    captureState: CaptureState,
    framesPerSecond: Double,
    framesCaptured: Long,
    framesDropped: Long,
    lastFrameAtMs: Long,
    captureWidth: Int,
    captureHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    intervalMeanMs: Double,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(
        title = "Screen capture",
        status = if (captureState.isLive) StatusIndicator("live", StatusTone.GOOD) else StatusIndicator("stopped", StatusTone.IDLE),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                enabled = !captureState.isLive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Start capture")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = captureState.isLive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Stop capture")
            }
        }
        SectionDivider()
        MetricRow("Frame rate", if (framesPerSecond > 0.0) "%.1f fps".format(Locale.US, framesPerSecond) else "—")
        MetricRow("Mean frame interval", if (intervalMeanMs > 0.0) "%.0f ms".format(Locale.US, intervalMeanMs) else "—")
        MetricRow("Frames captured", framesCaptured.toString())
        MetricRow("Frames dropped (throttle/back-pressure)", framesDropped.toString())
        MetricRow(
            "Last frame",
            if (lastFrameAtMs == 0L) "none yet" else timeFormat.format(Date(lastFrameAtMs)),
        )
        MetricRow("Capture surface", if (captureWidth > 0) "${captureWidth}x$captureHeight" else "—")
        MetricRow("Display", if (displayWidth > 0) "${displayWidth}x$displayHeight" else "—")
    }
}

@Composable
private fun PreviewCard(
    preview: PreviewImage?,
    gameState: GameState?,
    decision: AgentDecision?,
    showOverlay: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    previewEnabled: Boolean,
) {
    SectionCard(title = "Latest frame") {
        if (!previewEnabled) {
            HintText("Preview is turned off in Settings. Frames are still captured and analysed.")
            return@SectionCard
        }
        if (preview == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 19.5f)
                    .background(GameAgentColors.SurfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No frame captured yet.\nStart capture, or run the test scene below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GameAgentColors.TextSecondary,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(preview.width.toFloat() / preview.height.toFloat())
                    .background(GameAgentColors.SurfaceVariant, RoundedCornerShape(10.dp)),
            ) {
                Image(
                    bitmap = preview.image,
                    contentDescription = "Latest captured frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
                if (showOverlay) {
                    DetectionOverlay(gameState = gameState, decision = decision, modifier = Modifier.matchParentSize())
                }
            }
            MetricRow("Preview frame", "#${preview.frameId} · ${timeFormat.format(Date(preview.capturedAtMs))}")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = showOverlay, onCheckedChange = onToggleOverlay)
            Text(
                "Show detected overlays",
                style = MaterialTheme.typography.bodySmall,
                color = GameAgentColors.TextSecondary,
            )
        }
    }
}

/**
 * Draws the last interpretation on top of the preview.
 *
 * This is the honest counterpart of the preview: it shows exactly which boxes the perception stage
 * reported, in normalised coordinates, so a wrong detection is visible rather than hidden behind a
 * confident-looking status line.
 */
@Composable
private fun DetectionOverlay(gameState: GameState?, decision: AgentDecision?, modifier: Modifier) {
    Canvas(modifier = modifier) {
        gameState?.let { state ->
            state.enemies.forEach { drawBox(it.bounds, GameAgentColors.Danger) }
            state.allies.forEach { drawBox(it.bounds, GameAgentColors.Success) }
            state.objectives.forEach { drawBox(it.bounds, Color(0xFFB44CFF)) }
            state.self?.let { drawBox(it.bounds, GameAgentColors.Warning) }
        }
        decision?.point?.let { point ->
            val center = androidx.compose.ui.geometry.Offset(point.x * size.width, point.y * size.height)
            drawCircle(GameAgentColors.Accent, radius = 8f, center = center, style = Stroke(width = 3f))
            decision.endPoint?.let { end ->
                drawLine(
                    color = GameAgentColors.Accent,
                    start = center,
                    end = androidx.compose.ui.geometry.Offset(end.x * size.width, end.y * size.height),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBox(rect: NormalizedRect, color: Color) {
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(rect.left * size.width, rect.top * size.height),
        size = androidx.compose.ui.geometry.Size(rect.width * size.width, rect.height * size.height),
        style = Stroke(width = 3f),
    )
}

@Composable
private fun ControlCard(
    settings: com.gameagent.app.data.AppSettings,
    sessionState: SessionState,
    captureLive: Boolean,
    onToggleActions: (Boolean) -> Unit,
    onStartAgent: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAnalyseOnce: () -> Unit,
    onResetCounters: () -> Unit,
) {
    SectionCard(title = "Control") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = settings.actionsEnabled, onCheckedChange = onToggleActions)
            Column(Modifier.weight(1f)) {
                Text("Allow action execution", style = MaterialTheme.typography.bodyMedium, color = GameAgentColors.TextPrimary)
                Text(
                    "Master switch. With this off, the pipeline still detects and decides, but nothing is executed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GameAgentColors.TextSecondary,
                )
            }
        }
        SectionDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStartAgent,
                enabled = captureLive && sessionState == SessionState.READY,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Run agent")
            }
            OutlinedButton(
                onClick = onPause,
                enabled = sessionState == SessionState.RUNNING,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Pause")
            }
            OutlinedButton(
                onClick = onResume,
                enabled = sessionState == SessionState.PAUSED,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Resume")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onAnalyseOnce,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Analyse one frame")
            }
            OutlinedButton(
                onClick = onResetCounters,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Reset counters")
            }
        }
        HintText(
            "Pause keeps capture alive but stops analysis and execution. Stop ends the session and the capture.",
        )
    }
}

@Composable
private fun AiSubsystemCard(
    perceptionId: String,
    perceptionDescription: String,
    agentSource: AgentSource,
    aiConfigured: Boolean,
) {
    SectionCard(
        title = "AI subsystem",
        status = when {
            agentSource == AgentSource.AI_PROVIDER && aiConfigured -> StatusIndicator("provider", StatusTone.WARN)
            agentSource == AgentSource.AI_PROVIDER -> StatusIndicator("unconfigured", StatusTone.BAD)
            else -> StatusIndicator("local", StatusTone.GOOD)
        },
    ) {
        MetricRow("Perception source", perceptionId)
        HintText(perceptionDescription)
        MetricRow(
            "Decision source",
            if (agentSource == AgentSource.AI_PROVIDER) "AI provider with local fallback" else "Deterministic policy",
        )
        if (agentSource == AgentSource.AI_PROVIDER) {
            if (aiConfigured) {
                HintText("A compact JSON observation is sent per decision. Any failure falls back to the local policy.")
            } else {
                HintText("The provider is selected but incomplete, so the local policy is used instead. Configure it in Settings.")
            }
        } else {
            HintText("No model, no network, no randomness. Every decision comes from observed values only.")
        }
    }
}

@Composable
private fun GameStateCard(state: GameState?) {
    SectionCard(
        title = "Detected game state",
        status = when {
            state == null -> StatusIndicator("no observation", StatusTone.IDLE)
            state.phase == GamePhase.UNKNOWN -> StatusIndicator("unknown", StatusTone.WARN)
            else -> StatusIndicator(state.source, StatusTone.GOOD)
        },
    ) {
        if (state == null) {
            HintText("Nothing has been interpreted yet. Start capture, or run the test scene.")
            return@SectionCard
        }
        MetricRow("Phase", state.phase.name)
        MetricRow("Overall confidence", "%.2f".format(Locale.US, state.confidence))
        MetricRow("Phase confidence", "%.2f".format(Locale.US, state.phaseConfidence))
        MetricRow("Health", state.health.value?.let { "%.2f".format(Locale.US, it) } ?: "unknown")
        MetricRow("Mana", state.mana.value?.let { "%.2f".format(Locale.US, it) } ?: "unknown")
        MetricRow(
            "Abilities",
            if (state.skills.isEmpty()) {
                "none detected"
            } else {
                state.skills.joinToString(" ") { skill ->
                    "s${skill.slot}=" + (skill.ready?.let { if (it) "ready" else "cooldown" } ?: "unknown")
                }
            },
        )
        MetricRow("Self marker", state.self?.let { "%.2f, %.2f".format(Locale.US, it.center.x, it.center.y) } ?: "not detected")
        MetricRow(
            "Entities",
            "${state.enemies.size} enemies · ${state.allies.size} allies · ${state.objectives.size} objectives",
        )
        MetricRow(
            "Minimap blips",
            state.minimap?.let { minimap ->
                "${minimap.entities.size} blips, confidence %.2f".format(Locale.US, minimap.confidence)
            } ?: "not supported",
        )
        if (state.notes.isNotEmpty()) {
            HintText("Notes: " + state.notes.joinToString("; "))
        }
        MetricRow("Frame", "#${state.frameId} · ${state.frameWidth}x${state.frameHeight} · ${timeFormat.format(Date(state.timestampMs))}")
    }
}

@Composable
private fun DecisionCard(decision: AgentDecision?, outcome: StepOutcome?) {
    SectionCard(
        title = "Latest decision",
        status = when (val status = decision?.validation) {
            null -> StatusIndicator("none", StatusTone.IDLE)
            is ValidationStatus.Accepted -> StatusIndicator("accepted", StatusTone.GOOD)
            is ValidationStatus.Rejected -> StatusIndicator("rejected", StatusTone.BAD)
            ValidationStatus.Pending -> StatusIndicator("pending", StatusTone.WARN)
        },
    ) {
        if (decision == null) {
            HintText("No decision has been produced yet.")
            return@SectionCard
        }
        MetricRow("Action", decision.summary())
        MetricRow("Source", decision.source)
        MetricRow("Confidence", "%.2f".format(Locale.US, decision.confidence))
        MetricRow("Target", decision.target?.let { "${it.kind}${it.label?.let { label -> " ($label)" } ?: ""}" } ?: "—")
        HintText(decision.explanation)
        when (val status = decision.validation) {
            is ValidationStatus.Rejected -> HintText("Rejected: ${status.code} — ${status.detail}")
            is ValidationStatus.Accepted -> HintText("Validation: ${status.detail}")
            ValidationStatus.Pending -> HintText("Validation: pending")
        }
        when (val step = outcome) {
            is StepOutcome.PerceptionFailed -> HintText("Perception failed: ${step.reason}")
            is StepOutcome.Skipped -> HintText("Skipped: ${step.reason}")
            is StepOutcome.Observed -> MetricRow("Step latency", "${step.totalLatencyMs} ms")
            null -> Unit
        }
    }
}

@Composable
private fun InputCard(
    settings: com.gameagent.app.data.AppSettings,
    accessibilityConnected: Boolean,
    actionsExecuted: Long,
    actionsAccepted: Long,
    actionsRejected: Long,
    actionsFailed: Long,
    effectorActions: Int,
    budget: Int,
    lastRejection: String?,
) {
    SectionCard(
        title = "Input controller",
        status = when (settings.inputTarget) {
            InputTarget.TEST_SCENE -> StatusIndicator("test scene", StatusTone.GOOD)
            InputTarget.ACCESSIBILITY ->
                if (accessibilityConnected) StatusIndicator("accessibility", StatusTone.WARN)
                else StatusIndicator("unavailable", StatusTone.BAD)
        },
    ) {
        MetricRow(
            "Target",
            when (settings.inputTarget) {
                InputTarget.TEST_SCENE -> "The companion's own test screen only"
                InputTarget.ACCESSIBILITY -> "Other apps, via the AccessibilityService"
            },
        )
        MetricRow("Actions executed", actionsExecuted.toString())
        MetricRow("Actions accepted / rejected", "$actionsAccepted / $actionsRejected")
        MetricRow("Actions failed or unsupported", actionsFailed.toString())
        MetricRow("Session action budget", "$effectorActions / $budget used")
        lastRejection?.let { HintText("Last rejection: $it") }
        HintText(
            "A normal app cannot inject touches into another app without a user-enabled AccessibilityService. " +
                "Multi-touch and a standalone RELEASE are not supported by the accessibility controller.",
        )
    }
}

@Composable
private fun MetricsCard(
    perceptionP50: Long,
    perceptionP95: Long,
    decisionP50: Long,
    inputP50: Long,
    observationsProcessed: Long,
    observationsFailed: Long,
    decisionsMade: Long,
) {
    SectionCard(title = "Pipeline timings") {
        MetricRow("Perception p50 / p95", "$perceptionP50 ms / $perceptionP95 ms")
        MetricRow("Decision p50", "$decisionP50 ms")
        MetricRow("Input p50", "$inputP50 ms")
        MetricRow("Observations processed / failed", "$observationsProcessed / $observationsFailed")
        MetricRow("Decisions made", decisionsMade.toString())
    }
}

@Composable
private fun EventsCard(events: List<com.gameagent.core.diagnostics.DiagnosticEvent>) {
    SectionCard(title = "Recent events") {
        if (events.isEmpty()) {
            HintText("No events yet.")
            return@SectionCard
        }
        events.forEach { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                com.gameagent.app.ui.components.EventLevelDot(event.level)
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
                    text = timeFormat.format(Date(event.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = GameAgentColors.TextSecondary,
                )
            }
        }
        HintText("Full history is on the Diagnostics tab. Nothing is uploaded anywhere.")
    }
}

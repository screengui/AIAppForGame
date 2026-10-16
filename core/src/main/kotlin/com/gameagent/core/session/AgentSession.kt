package com.gameagent.core.session

import com.gameagent.core.agent.GameAgent
import com.gameagent.core.diagnostics.CaptureMetrics
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.diagnostics.LatencyTracker
import com.gameagent.core.diagnostics.SessionMetrics
import com.gameagent.core.geometry.CoordinateMapper
import com.gameagent.core.geometry.MappingResult
import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.geometry.PixelPoint
import com.gameagent.core.geometry.ScreenGeometry
import com.gameagent.core.input.ExecutionOutcome
import com.gameagent.core.input.InputController
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.GameState
import com.gameagent.core.model.SessionState
import com.gameagent.core.model.ValidationStatus
import com.gameagent.core.perception.ImageFrame
import com.gameagent.core.perception.PerceptionProvider
import com.gameagent.core.perception.PerceptionResult
import com.gameagent.core.pipeline.FrameBuffer
import com.gameagent.core.pipeline.OfferResult
import com.gameagent.core.validation.ActionValidation
import com.gameagent.core.validation.ActionValidator
import com.gameagent.core.validation.EmergencyStop
import com.gameagent.core.validation.MappingFailure
import com.gameagent.core.validation.ValidationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class SessionConfig(
    /** Pending frames allowed before the oldest is dropped. Kept small to bound latency. */
    val maxQueuedFrames: Int = 2,
)

sealed interface StepOutcome {
    data class Observed(
        val state: GameState,
        val decision: AgentDecision,
        val validation: ActionValidation,
        val execution: ExecutionOutcome?,
        val totalLatencyMs: Long,
    ) : StepOutcome

    data class PerceptionFailed(val reason: String, val durationMs: Long) : StepOutcome

    /** The frame was deliberately not processed (no consumer, wrong state, queue closed). */
    data class Skipped(val reason: String) : StepOutcome
}

/**
 * Runs the observe -> decide -> validate -> act loop for one session.
 *
 * Design notes:
 *  - **One worker, bounded queue.** Frames arrive from the capture callback thread and are offered
 *    to a two-slot drop-oldest [FrameBuffer]; the worker coroutine consumes only the newest. A slow
 *    AI provider therefore slows *freshness*, never memory.
 *  - **Frames are only interpreted while RUNNING.** In READY the app is just previewing, so the
 *    pipeline refuses to spend CPU on frames nobody consumes. The "analyse once" button exists for
 *    the explicit single-shot case.
 *  - **The session never does I/O for control flow.** Stopping and the emergency stop are purely
 *    local state changes plus frame-buffer drains; no network or AI response is involved.
 *  - **Every effectful step is measured** and reported through [metrics].
 */
class AgentSession(
    private val perception: PerceptionProvider,
    private val agent: GameAgent,
    private val validator: ActionValidator,
    private val controllerProvider: () -> InputController?,
    val stateMachine: SessionStateMachine = SessionStateMachine(),
    private val mapper: CoordinateMapper,
    private val emergencyStop: EmergencyStop,
    private val events: DiagnosticEventSink,
    private val scope: CoroutineScope,
    private val config: SessionConfig = SessionConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val buffer = FrameBuffer(config.maxQueuedFrames)

    private val perceptionLatency = LatencyTracker()
    private val decisionLatency = LatencyTracker()
    private val inputLatency = LatencyTracker()

    private val mutableMetrics = MutableStateFlow(SessionMetrics())
    val metrics: StateFlow<SessionMetrics> = mutableMetrics.asStateFlow()

    private val mutableLastDecision = MutableStateFlow<AgentDecision?>(null)
    val lastDecision: StateFlow<AgentDecision?> = mutableLastDecision.asStateFlow()

    private val mutableLatestState = MutableStateFlow<GameState?>(null)
    val latestGameState: StateFlow<GameState?> = mutableLatestState.asStateFlow()

    private val mutableLastOutcome = MutableStateFlow<StepOutcome?>(null)
    val lastOutcome: StateFlow<StepOutcome?> = mutableLastOutcome.asStateFlow()

    val state: StateFlow<SessionState> = stateMachine.state

    private var reportedCapture: CaptureMetrics = CaptureMetrics()

    private var observationsProcessed = 0L
    private var observationsFailed = 0L
    private var decisionsMade = 0L
    private var actionsAccepted = 0L
    private var actionsRejected = 0L
    private var actionsExecuted = 0L
    private var actionsFailed = 0L
    private var framesSkipped = 0L

    private var worker: Job? = null

    // ----------------------------------------------------------------- lifecycle

    /** IDLE/ERROR -> REQUESTING_PERMISSION. */
    fun requestPermission(): Boolean = transition(SessionState.REQUESTING_PERMISSION, "user requested screen capture")

    /** The consent dialog was dismissed or denied: go back to IDLE and say so. */
    fun permissionDenied(reason: String): Boolean {
        events.warn(EventCategory.CAPTURE, "screen capture permission not granted", mapOf("reason" to reason))
        return transition(SessionState.IDLE, "permission denied: $reason")
    }

    /** The projection was approved and capture resources are being created. */
    fun captureStarting(): Boolean = transition(SessionState.CAPTURING, "creating capture resources")

    /** Capture is live and the pipeline is warm, but the agent is not acting yet. */
    fun captureReady(): Boolean {
        val ok = transition(SessionState.READY, "capture ready")
        if (ok) startWorkerIfNeeded()
        return ok
    }

    /** READY/PAUSED -> RUNNING. */
    fun startAgent(): Boolean {
        val ok = transition(SessionState.RUNNING, "agent started by user")
        if (ok) startWorkerIfNeeded()
        return ok
    }

    fun pause(): Boolean = transition(SessionState.PAUSED, "paused by user")

    fun resume(): Boolean = transition(SessionState.RUNNING, "resumed by user")

    /** Something went wrong that the session cannot recover from on its own. */
    fun fail(reason: String): Boolean {
        events.error(EventCategory.SESSION, "session error", mapOf("reason" to reason))
        cancelWork()
        return transition(SessionState.ERROR, reason)
    }

    /** ERROR -> IDLE, so the user can retry. */
    fun recover(): Boolean = transition(SessionState.IDLE, "recovered from error")

    /**
     * Stop everything: drain the queue, cancel the worker, release any gesture, go to IDLE.
     * Safe to call from any state and repeatedly - that is required, because the stop button and
     * the emergency stop can both fire while a session is torn down.
     */
    fun stop(reason: String) {
        if (stateMachine.current() == SessionState.IDLE) {
            events.debug(EventCategory.SESSION, "stop ignored: session already idle", mapOf("reason" to reason))
            return
        }
        teardown(reason)
        events.info(EventCategory.SESSION, "session stopped", mapOf("reason" to reason))
    }

    /**
     * The user's panic button.
     *
     * Order matters and is tested: the kill switch is latched **first**, so the validator refuses
     * everything from this instant, and only then are the queue, worker and any in-flight gesture
     * torn down. The session ends in [SessionState.IDLE] with the switch still latched, so a new
     * session cannot silently start acting again - the user has to clear it explicitly. Nothing in
     * this path touches the network, a coroutine result or an AI response, so it cannot be blocked.
     */
    fun triggerEmergencyStop(reason: String): Boolean {
        val tripped = emergencyStop.engage(reason)
        if (!tripped) {
            events.debug(EventCategory.SYSTEM, "emergency stop already engaged")
            return false
        }
        events.error(EventCategory.SYSTEM, "EMERGENCY STOP engaged", mapOf("reason" to reason))
        teardown("emergency stop: $reason")
        return true
    }

    private fun teardown(reason: String) {
        if (stateMachine.current().holdsCaptureResources) {
            transition(SessionState.STOPPING, reason)
        }
        cancelWork()
        releaseController()
        transition(SessionState.IDLE, reason)
    }

    /** Clears the latched kill switch so the user can start a new session. */
    fun resetEmergencyStop(): Boolean = emergencyStop.reset()

    fun resetCounters() {
        validator.resetSession()
        perceptionLatency.reset()
        decisionLatency.reset()
        inputLatency.reset()
        observationsProcessed = 0L
        observationsFailed = 0L
        decisionsMade = 0L
        actionsAccepted = 0L
        actionsRejected = 0L
        actionsExecuted = 0L
        actionsFailed = 0L
        framesSkipped = 0L
        publishMetrics()
    }

    private fun transition(to: SessionState, reason: String): Boolean {
        val result = stateMachine.transitionTo(to, reason)
        if (result is TransitionResult.Invalid) {
            events.debug(EventCategory.SESSION, "transition refused", mapOf("detail" to result.reason))
            return false
        }
        return true
    }

    // -------------------------------------------------------------------- frames

    /** Called from the capture layer for every frame, on whatever thread produced it. */
    fun submitFrame(frame: ImageFrame, geometry: ScreenGeometry) {
        mapper.update(geometry)

        val state = stateMachine.current()
        if (state == SessionState.RUNNING) {
            when (buffer.offer(frame)) {
                OfferResult.Accepted -> Unit
                OfferResult.ReplacedOldest ->
                    events.debug(
                        EventCategory.CAPTURE,
                        "frame queue was full; oldest frame dropped",
                        mapOf("frameId" to frame.frameId.toString()),
                    )

                OfferResult.Dropped -> framesSkipped++
            }
        } else {
            // No consumer needs this frame: dropping it is the point, not a failure. The buffer is
            // released so the capture layer can recycle it instead of allocating a new one.
            framesSkipped++
            frame.close()
        }
        publishMetrics()
    }

    /** The capture layer pushes its own counters here so the dashboard has a single metrics view. */
    fun reportCaptureMetrics(metrics: CaptureMetrics) {
        reportedCapture = metrics
        publishMetrics()
    }

    /**
     * Interprets one frame immediately (the "Analyse one frame" button).
     * Input is only executed when the session is RUNNING and the validator agrees; in any other
     * state the decision is produced and reported as rejected, which is honest and visible.
     */
    suspend fun analyseOnce(frame: ImageFrame, geometry: ScreenGeometry): StepOutcome {
        mapper.update(geometry)
        // The caller owns this frame: it is a one-shot analysis, not a queued capture frame, so it is
        // deliberately not closed here.
        return runStep(frame)
    }

    private fun startWorkerIfNeeded() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (frame in buffer.receiveChannel()) {
                try {
                    processFrame(frame)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    events.error(EventCategory.SESSION, "pipeline step failed", mapOf("error" to t.toString()))
                    fail("pipeline step failed: ${t.message ?: t::class.java.simpleName}")
                }
            }
        }
    }

    /**
     * Queued frames are owned by the session: however this returns - processed, skipped, or thrown -
     * the frame's buffer is handed back to the capture layer exactly once.
     */
    private suspend fun processFrame(frame: ImageFrame) {
        try {
            if (stateMachine.current() != SessionState.RUNNING) {
                framesSkipped++
                return
            }
            val entered = stateMachine.transitionTo(SessionState.PROCESSING, "processing frame ${frame.frameId}")
            if (entered !is TransitionResult.Ok) return
            try {
                runStep(frame)
            } finally {
                // Only unwind if we are still the state we set; a concurrent stop must win.
                if (stateMachine.current() == SessionState.PROCESSING) {
                    stateMachine.transitionTo(SessionState.RUNNING, "frame processed")
                }
            }
        } finally {
            frame.close()
        }
    }


    private suspend fun runStep(frame: ImageFrame): StepOutcome {
        val stepStart = nanoTime()

        val perceptionStart = nanoTime()
        val perceptionResult = perception.observe(frame)
        perceptionLatency.record(elapsedMs(perceptionStart))

        when (perceptionResult) {
            is PerceptionResult.Failure -> {
                observationsFailed++
                events.warn(
                    EventCategory.PERCEPTION,
                    "perception failed",
                    mapOf("provider" to perception.id, "reason" to perceptionResult.reason),
                )
                return finish(StepOutcome.PerceptionFailed(perceptionResult.reason, perceptionResult.durationMs))
            }

            is PerceptionResult.Success -> {
                observationsProcessed++
                mutableLatestState.value = perceptionResult.state
                events.debug(
                    EventCategory.PERCEPTION,
                    "frame observed",
                    mapOf(
                        "frameId" to frame.frameId.toString(),
                        "phase" to perceptionResult.state.phase.name,
                        "confidence" to "%.2f".format(Locale.US, perceptionResult.state.confidence),
                        "durationMs" to perceptionResult.durationMs.toString(),
                    ),
                )

                val decisionStart = nanoTime()
                val decision = agent.decide(perceptionResult.state)
                decisionLatency.record(elapsedMs(decisionStart))
                decisionsMade++

                if (decision.actionType == ActionType.STOP) {
                    // A policy-requested stop is honoured as a soft stop: it does not latch the
                    // user's panic switch, but it does halt the loop immediately.
                    val annotated = decision.withValidation(ValidationStatus.Accepted("policy requested stop"))
                    mutableLastDecision.value = annotated
                    events.info(EventCategory.DECISION, "policy requested STOP", mapOf("explanation" to decision.explanation))
                    stop("policy requested stop")
                    return finish(
                        StepOutcome.Observed(
                            perceptionResult.state,
                            annotated,
                            ActionValidation.NoOp("policy requested stop"),
                            null,
                            elapsedMs(stepStart),
                        ),
                    )
                }

                val context = buildContext(decision)
                val validation = validator.validate(decision, context)
                var execution: ExecutionOutcome? = null

                val annotatedDecision = when (validation) {
                    is ActionValidation.Rejected -> {
                        actionsRejected++
                        events.warn(
                            EventCategory.VALIDATION,
                            "action rejected",
                            mapOf("code" to validation.code.name, "detail" to validation.detail),
                        )
                        decision.withValidation(ValidationStatus.Rejected(validation.code, validation.detail))
                    }

                    is ActionValidation.NoOp -> decision.withValidation(ValidationStatus.Accepted(validation.reason))

                    is ActionValidation.Accepted -> {
                        actionsAccepted++
                        val controller = controllerProvider()
                        if (controller == null) {
                            execution = ExecutionOutcome.Unsupported("no input controller is attached")
                        } else {
                            val inputStart = nanoTime()
                            execution = try {
                                controller.execute(validation.action)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (t: Throwable) {
                                ExecutionOutcome.Failed(t.message ?: t::class.java.simpleName)
                            }
                            inputLatency.record(elapsedMs(inputStart))
                        }
                        val outcome = execution
                        when (outcome) {
                            is ExecutionOutcome.Completed -> {
                                actionsExecuted++
                                events.info(
                                    EventCategory.INPUT,
                                    "action executed",
                                    mapOf(
                                        "type" to validation.action.actionType.name,
                                        "controller" to (controller?.id ?: "none"),
                                        "start" to "${validation.action.start?.x},${validation.action.start?.y}",
                                        "durationMs" to (validation.action.durationMs?.toString() ?: "-"),
                                    ),
                                )
                            }

                            is ExecutionOutcome.Unsupported, is ExecutionOutcome.Refused -> {
                                // Nothing was delivered, so the action budget is not consumed.
                                validator.refundAction()
                                actionsFailed++
                                events.warn(
                                    EventCategory.INPUT,
                                    "action not delivered",
                                    mapOf("result" to execution.toString()),
                                )
                            }

                            is ExecutionOutcome.Cancelled -> {
                                actionsFailed++
                                events.warn(EventCategory.INPUT, "action cancelled")
                            }

                            is ExecutionOutcome.Failed -> {
                                actionsFailed++
                                events.error(
                                    EventCategory.INPUT,
                                    "action failed",
                                    mapOf("reason" to outcome.reason),
                                )
                            }
                        }
                        decision.withValidation(ValidationStatus.Accepted("validated ${validation.action.actionType}"))
                    }
                }

                mutableLastDecision.value = annotatedDecision
                return finish(
                    StepOutcome.Observed(
                        state = perceptionResult.state,
                        decision = annotatedDecision,
                        validation = validation,
                        execution = execution,
                        totalLatencyMs = elapsedMs(stepStart),
                    ),
                )
            }
        }
    }

    private fun finish(outcome: StepOutcome): StepOutcome {
        mutableLastOutcome.value = outcome
        publishMetrics()
        return outcome
    }

    private fun buildContext(decision: AgentDecision): ValidationContext {
        val now = clock()
        val staleness = mapper.checkFreshness(now)
        val geometry = mapper.current()

        var mappingFailure: MappingFailure? = null
        fun mapOrNull(point: com.gameagent.core.geometry.NormalizedPoint?): PixelPoint? {
            if (point == null) return null
            return when (val result = mapper.map(point, now)) {
                is MappingResult.Success -> result.point
                is MappingResult.Failure -> {
                    if (mappingFailure == null) mappingFailure = MappingFailure(result.code, result.detail)
                    null
                }
            }
        }

        val start = mapOrNull(decision.point)
        val end = mapOrNull(decision.endPoint)
        return ValidationContext(
            sessionState = stateMachine.current(),
            emergencyStopEngaged = emergencyStop.isEngaged,
            startPoint = start,
            endPoint = end,
            geometryAgeMs = if (staleness != null) null else mapper.ageMs(now),
            mappingFailure = mappingFailure,
            displayWidth = geometry?.displayWidth ?: 0,
            displayHeight = geometry?.displayHeight ?: 0,
            capabilities = controllerProvider()?.capabilities,
            nowMs = now,
        )
    }

    private fun cancelWork() {
        val discarded = buffer.drain()
        if (discarded > 0) {
            events.debug(EventCategory.SESSION, "discarded queued frames", mapOf("count" to discarded.toString()))
        }
        worker?.cancel()
        worker = null
    }

    private fun releaseController() {
        val controller = controllerProvider() ?: return
        scope.launch {
            runCatching {
                controller.cancelActive()
                controller.release()
            }.onFailure { events.warn(EventCategory.INPUT, "controller release failed", mapOf("error" to it.toString())) }
        }
    }

    private fun publishMetrics() {
        val capture = reportedCapture.copy(framesDropped = reportedCapture.framesDropped + buffer.dropped)
        mutableMetrics.value = SessionMetrics(
            capture = capture,
            perception = perceptionLatency.summary(),
            decision = decisionLatency.summary(),
            input = inputLatency.summary(),
            observationsProcessed = observationsProcessed,
            observationsFailed = observationsFailed,
            decisionsMade = decisionsMade,
            actionsAccepted = actionsAccepted,
            actionsRejected = actionsRejected,
            actionsExecuted = actionsExecuted,
            actionsFailed = actionsFailed,
        )
    }

    /** Frames deliberately not interpreted, surfaced on the dashboard so it is visible work saved. */
    fun framesSkipped(): Long = framesSkipped

    private fun elapsedMs(startNanos: Long): Long =
        ((nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)

    /** Only used by diagnostics to describe the active providers. */
    fun describe(): Map<String, String> = mapOf(
        "perception" to perception.id,
        "agent" to agent.id,
        "state" to stateMachine.current().name,
        "framesOffered" to buffer.offered.toString(),
        "framesDropped" to buffer.dropped.toString(),
        "framesSkipped" to framesSkipped.toString(),
    )
}

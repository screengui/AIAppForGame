package com.gameagent.app.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.gameagent.app.capture.MediaProjectionCaptureEngine
import com.gameagent.app.capture.ScreenCaptureService
import com.gameagent.app.capture.ScreenshotStore
import com.gameagent.app.data.AgentSource
import com.gameagent.app.data.AppSettings
import com.gameagent.app.data.InputTarget
import com.gameagent.app.data.PerceptionSource
import com.gameagent.app.data.SecureKeyStore
import com.gameagent.app.data.SettingsRepository
import com.gameagent.app.input.AccessibilityInputController
import com.gameagent.app.input.GameAgentAccessibilityService
import com.gameagent.app.provider.AndroidHttpTransport
import com.gameagent.core.agent.DeterministicPolicyAgent
import com.gameagent.core.agent.GameAgent
import com.gameagent.core.diagnostics.DiagnosticEventLog
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.diagnostics.SessionMetrics
import com.gameagent.core.fixtures.SyntheticSceneRenderer
import com.gameagent.core.fixtures.TestSceneInputController
import com.gameagent.core.fixtures.TestScenePerceptionProfile
import com.gameagent.core.fixtures.TestSceneState
import com.gameagent.core.geometry.CoordinateMapper
import com.gameagent.core.geometry.DisplayRotation
import com.gameagent.core.geometry.ScreenGeometry
import com.gameagent.core.input.InputController
import com.gameagent.core.input.UnavailableInputController
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.GamePhase
import com.gameagent.core.model.GameState
import com.gameagent.core.model.SessionState
import com.gameagent.core.perception.HeuristicScreenPerceptionProvider
import com.gameagent.core.perception.NullPerceptionProvider
import com.gameagent.core.perception.PerceptionProvider
import com.gameagent.core.provider.OpenAiCompatibleVisionProvider
import com.gameagent.core.provider.RemoteVisionAgent
import com.gameagent.core.provider.VisionProviderCredentials
import com.gameagent.core.session.AgentSession
import com.gameagent.core.session.SessionConfig
import com.gameagent.core.session.SessionStateMachine
import com.gameagent.core.session.StateChange
import com.gameagent.core.session.StepOutcome
import com.gameagent.core.validation.ActionValidator
import com.gameagent.core.validation.EmergencyStop
import com.gameagent.core.validation.ValidatorStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single place where the pure `:core` pipeline meets Android.
 *
 * Responsibilities, and deliberately nothing else:
 *  - own the session, the mapper, the validator and the kill switch for the process;
 *  - translate Android capture frames into core frames;
 *  - pick the active input controller from settings, without silently substituting one;
 *  - react to settings changes by swapping perception/agent delegates and the validator config;
 *  - stop the capture service whenever the session leaves the capturing states.
 *
 * The UI never touches the pipeline directly: it reads the `StateFlow`s exposed here and calls the
 * handful of intent methods.
 */
class AgentRuntime(
    private val context: Context,
    val captureEngine: MediaProjectionCaptureEngine,
    val settingsRepository: SettingsRepository,
    val keyStore: SecureKeyStore,
    val eventLog: DiagnosticEventLog,
    val screenshotStore: ScreenshotStore,
    val emergencyStop: EmergencyStop = EmergencyStop(),
) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The mock game the companion plays against. Rendered and reduced entirely on device. */
    val testScene: MutableStateFlow<TestSceneState> = MutableStateFlow(TestSceneState(phase = GamePhase.IN_MATCH))

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val mapper = CoordinateMapper()
    private val validator = ActionValidator(settingsRepository.settings.value.toValidationConfig())
    private val sessionStateMachine = SessionStateMachine()

    private val testSceneController = TestSceneInputController(
        sceneState = testScene,
        frameSizeProvider = { TEST_SCENE_WIDTH to TEST_SCENE_HEIGHT },
        events = eventLog,
    )

    private val accessibilityController = AccessibilityInputController(
        displaySizeProvider = { currentDisplaySize() },
        events = eventLog,
    )

    private val unavailableController = UnavailableInputController(
        reason = "the accessibility controller is selected but the service is not enabled",
    )

    private val perceptionDelegate = DelegatingPerceptionProvider(buildPerception())
    private val agentDelegate = DelegatingGameAgent(buildAgent())

    val session: AgentSession = AgentSession(
        perception = perceptionDelegate,
        agent = agentDelegate,
        validator = validator,
        controllerProvider = { currentController() },
        stateMachine = sessionStateMachine,
        mapper = mapper,
        emergencyStop = emergencyStop,
        events = eventLog,
        scope = scope,
        config = SessionConfig(maxQueuedFrames = 2),
    )

    private val mutableValidatorStats = MutableStateFlow(validator.stats())
    val validatorStats: StateFlow<ValidatorStats> = mutableValidatorStats.asStateFlow()

    private val mutableLastOutcome = MutableStateFlow<StepOutcome?>(null)
    val lastOutcome: StateFlow<StepOutcome?> = mutableLastOutcome.asStateFlow()

    private val mutableStateHistory = MutableStateFlow<List<StateChange>>(emptyList())
    val stateHistory: StateFlow<List<StateChange>> = mutableStateHistory.asStateFlow()

    private val mutableSelfTestEnabled = MutableStateFlow(false)
    val selfTestEnabled: StateFlow<Boolean> = mutableSelfTestEnabled.asStateFlow()

    /**
     * Whether the optional AI provider has everything it needs.
     *
     * Cached rather than computed per read: reading the key requires a Keystore decryption, which is
     * far too expensive to run on every recomposition.
     */
    private val mutableAiConfigured = MutableStateFlow(false)
    val aiConfigured: StateFlow<Boolean> = mutableAiConfigured.asStateFlow()

    private val mutableAccessibilityEnabledInSettings = MutableStateFlow(
        GameAgentAccessibilityService.isEnabledInSettings(context),
    )
    val accessibilityEnabledInSettings: StateFlow<Boolean> = mutableAccessibilityEnabledInSettings.asStateFlow()

    val sessionState: StateFlow<SessionState> = session.state
    val metrics: StateFlow<SessionMetrics> = session.metrics
    val lastDecision: StateFlow<AgentDecision?> = session.lastDecision
    val latestGameState: StateFlow<GameState?> = session.latestGameState
    val accessibilityConnected: StateFlow<Boolean> = GameAgentAccessibilityService.connected

    private var selfTestJob: Job? = null

    init {
        // Capture frames are the only source of real frames.
        scope.launch {
            captureEngine.frames.collect { bundle ->
                session.submitFrame(bundle.frame, bundle.geometry)
                screenshotStore.maybeSave(bundle.frame, settingsRepository.settings.value, System.currentTimeMillis())
            }
        }

        scope.launch {
            captureEngine.metrics.collect { metrics -> session.reportCaptureMetrics(metrics) }
        }

        // A session that is no longer capturing must not leave the foreground service running.
        scope.launch {
            sessionState.collect { state ->
                if (!state.holdsCaptureResources && captureEngine.state.value.isLive) {
                    eventLog.info(EventCategory.CAPTURE, "session is no longer capturing; stopping the service")
                    ScreenCaptureService.stop(context)
                }
                mutableValidatorStats.value = validator.stats()
                mutableStateHistory.value = sessionStateMachine.history()
            }
        }

        scope.launch {
            session.metrics.collect { mutableValidatorStats.value = validator.stats() }
        }

        scope.launch {
            session.lastOutcome.collect { mutableLastOutcome.value = it }
        }

        scope.launch {
            settingsRepository.settings.collect { updated ->
                applySettings(updated)
            }
        }

        refreshAiConfiguration()

        captureEngine.onCaptureEnded = { reason ->
            eventLog.warn(EventCategory.CAPTURE, "capture ended", mapOf("reason" to reason))
            session.stop(reason)
            ScreenCaptureService.stop(context)
        }
    }

    // ------------------------------------------------------------------ intents

    /** The system consent intent. Nothing is captured until the user approves it. */
    fun createCaptureConsentIntent(): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjectionManager is unavailable on this device")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Asking for the default display removes the "share one app" ambiguity, which would put
            // the captured content on a different scale than the display and skew every coordinate.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    fun onCaptureConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            session.permissionDenied("the screen-capture consent dialog was dismissed")
            mutableValidatorStats.value = validator.stats()
            return
        }
        session.requestPermission()
        ScreenCaptureService.start(context, resultCode, data)
        mutableValidatorStats.value = validator.stats()
    }

    fun stopCapture(reason: String) {
        captureEngine.stop(reason)
        session.stop(reason)
        ScreenCaptureService.stop(context)
        mutableValidatorStats.value = validator.stats()
    }

    fun stopAgent() = stopCapture("stopped by the user")

    fun pauseAgent(): Boolean {
        val ok = session.pause()
        mutableValidatorStats.value = validator.stats()
        return ok
    }

    fun resumeAgent(): Boolean {
        val ok = session.resume()
        mutableValidatorStats.value = validator.stats()
        return ok
    }

    /** Starts acting. Safe to call repeatedly; a second call is refused and logged. */
    fun startAgent(): Boolean {
        val ok = session.startAgent()
        mutableValidatorStats.value = validator.stats()
        return ok
    }

    fun markCaptureReady(): Boolean = session.captureReady()

    /** The panic button. Latches the kill switch, tears the session down and stops capture. */
    fun emergencyStop(reason: String = "emergency stop pressed by the user") {
        session.triggerEmergencyStop(reason)
        captureEngine.stop("emergency stop")
        ScreenCaptureService.stop(context)
        setSelfTestEnabled(false)
        mutableValidatorStats.value = validator.stats()
    }

    fun resetEmergencyStop(): Boolean {
        val reset = session.resetEmergencyStop()
        mutableValidatorStats.value = validator.stats()
        return reset
    }

    fun resetCounters() {
        session.resetCounters()
        mutableValidatorStats.value = validator.stats()
    }

    fun clearDiagnostics() {
        eventLog.clear()
        validator.resetSession()
        mutableValidatorStats.value = validator.stats()
    }

    /** Analyses one frame of the current test scene. Never executes input unless the session runs. */
    fun analyseTestSceneFrame() {
        scope.launch {
            val frameId = System.currentTimeMillis()
            val frame = SyntheticSceneRenderer.frame(
                testScene.value,
                TEST_SCENE_WIDTH,
                TEST_SCENE_HEIGHT,
                frameId,
                System.currentTimeMillis(),
            )
            val outcome = session.analyseOnce(frame, testSceneGeometry(frameId, System.currentTimeMillis()))
            mutableLastOutcome.value = outcome
            mutableValidatorStats.value = validator.stats()
        }
    }

    /**
     * Runs the autonomous loop against the in-app test scene instead of a real screen.
     *
     * This is what makes the whole pipeline demonstrable without screen-capture permission and
     * without any AI service: the same session, perception, policy, validator and controller run,
     * only the frame source changes.
     */
    fun setSelfTestEnabled(enabled: Boolean) {
        mutableSelfTestEnabled.value = enabled
        selfTestJob?.cancel()
        selfTestJob = null
        if (!enabled) return

        eventLog.info(EventCategory.SESSION, "self-test loop enabled (test scene frames, no screen capture)")
        selfTestJob = scope.launch {
            var frameId = 0L
            while (isActive) {
                delay(settingsRepository.settings.value.effectiveCaptureIntervalMs)
                if (!mutableSelfTestEnabled.value) break
                if (captureEngine.state.value.isLive) continue
                if (session.state.value != SessionState.RUNNING) continue

                frameId++
                val now = System.currentTimeMillis()
                val frame = SyntheticSceneRenderer.frame(
                    testScene.value,
                    TEST_SCENE_WIDTH,
                    TEST_SCENE_HEIGHT,
                    frameId,
                    now,
                )
                session.submitFrame(frame, testSceneGeometry(frameId, now))
            }
        }
    }

    fun refreshAccessibilityStatus() {
        mutableAccessibilityEnabledInSettings.value = GameAgentAccessibilityService.isEnabledInSettings(context)
    }

    /** Re-reads the stored AI key. Call after the key is saved or cleared. */
    fun refreshAiConfiguration() {
        val current = settingsRepository.settings.value
        mutableAiConfigured.value = current.aiBaseUrl.isNotBlank() &&
            current.aiModel.isNotBlank() &&
            keyStore.readAiApiKey()?.isNotBlank() == true
    }

    fun shutdown() {
        captureEngine.release()
        screenshotStore.shutdown()
        scope.cancel()
    }

    // ------------------------------------------------------------------ internals

    private fun applySettings(updated: AppSettings) {
        validator.updateConfig(updated.toValidationConfig())
        perceptionDelegate.swap(buildPerception())
        agentDelegate.swap(buildAgent())
        mutableAiConfigured.value = updated.aiBaseUrl.isNotBlank() &&
            updated.aiModel.isNotBlank() &&
            keyStore.readAiApiKey()?.isNotBlank() == true
        mutableValidatorStats.value = validator.stats()
    }

    private fun buildPerception(): PerceptionProvider = when (settingsRepository.settings.value.perceptionSource) {
        PerceptionSource.HEURISTIC -> HeuristicScreenPerceptionProvider(TestScenePerceptionProfile.profile)
        PerceptionSource.DISABLED -> NullPerceptionProvider("perception is disabled in settings")
    }

    private fun buildAgent(): GameAgent {
        val current = settingsRepository.settings.value
        val local = DeterministicPolicyAgent(current.toPolicyConfig())
        if (current.agentSource != AgentSource.AI_PROVIDER) return local

        val credentials = VisionProviderCredentials(
            baseUrl = current.aiBaseUrl,
            model = current.aiModel,
            apiKey = keyStore.readAiApiKey().orEmpty(),
        )
        if (!credentials.isConfigured) {
            eventLog.warn(
                EventCategory.AI,
                "AI provider is selected but not fully configured; using the local policy",
                mapOf("baseUrlSet" to current.aiBaseUrl.isNotBlank().toString(), "modelSet" to current.aiModel.isNotBlank().toString()),
            )
            return local
        }
        return RemoteVisionAgent(
            provider = OpenAiCompatibleVisionProvider(credentials, AndroidHttpTransport(eventLog)),
            fallback = local,
            events = eventLog,
        )
    }

    private fun currentController(): InputController = when (settingsRepository.settings.value.inputTarget) {
        InputTarget.TEST_SCENE -> testSceneController
        // Never silently fall back to the test scene: a user who selected "other apps" must be told
        // that the controller is unavailable, not have their taps land somewhere else.
        InputTarget.ACCESSIBILITY -> accessibilityController.takeIf { it.isAvailable() } ?: unavailableController
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        val state = captureEngine.state.value
        return if (state is com.gameagent.app.capture.CaptureState.Running) {
            state.displayWidth to state.displayHeight
        } else {
            TEST_SCENE_WIDTH to TEST_SCENE_HEIGHT
        }
    }

    private fun testSceneGeometry(frameId: Long, timestampMs: Long) = ScreenGeometry(
        captureWidth = TEST_SCENE_WIDTH,
        captureHeight = TEST_SCENE_HEIGHT,
        displayWidth = TEST_SCENE_WIDTH,
        displayHeight = TEST_SCENE_HEIGHT,
        rotation = DisplayRotation.ROTATION_0,
        frameId = frameId,
        capturedAtMs = timestampMs,
    )

    companion object {
        /** Canonical render size for the test scene; display and perception share it. */
        const val TEST_SCENE_WIDTH = 360
        const val TEST_SCENE_HEIGHT = 780
    }
}

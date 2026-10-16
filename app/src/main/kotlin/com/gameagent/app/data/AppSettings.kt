package com.gameagent.app.data

import com.gameagent.core.agent.AgentPolicyConfig
import com.gameagent.core.geometry.CoordinateMapper
import com.gameagent.core.validation.ValidationConfig

/** Which controller receives validated actions. */
enum class InputTarget {
    /** The companion's own mock game loop. Always available, never touches other apps. */
    TEST_SCENE,

    /** The user-enabled AccessibilityService. Reaches other apps; must be enabled in Settings. */
    ACCESSIBILITY,
}

/** Where observations come from. */
enum class PerceptionSource {
    /** Deterministic colour/geometry detector using the built-in test-scene profile. */
    HEURISTIC,

    /** No detections at all; capture and preview still work. */
    DISABLED,
}

/** Where decisions come from. */
enum class AgentSource {
    /** Local rule-based policy. No network, no key. Default. */
    DETERMINISTIC,

    /** Optional OpenAI-compatible provider with the local policy as fallback. */
    AI_PROVIDER,
}

/**
 * Everything the user can configure.
 *
 * Safety-relevant defaults are deliberately conservative:
 *  - [actionsEnabled] is false, so a freshly installed app cannot act.
 *  - [inputTarget] is the in-app test scene, so the default target is the app's own screen.
 *  - [storeScreenshots] and [diagnosticsUploadEnabled] are false; nothing leaves the device.
 *
 * The AI API key is *not* here: it lives in [SecureKeyStore] so it cannot end up in a preferences
 * dump, a bug report or a backup.
 */
data class AppSettings(
    val captureIntervalMs: Long = 250L,
    val captureScale: Float = 0.5f,
    val actionsEnabled: Boolean = false,
    val inputTarget: InputTarget = InputTarget.TEST_SCENE,
    val perceptionSource: PerceptionSource = PerceptionSource.HEURISTIC,
    val agentSource: AgentSource = AgentSource.DETERMINISTIC,
    val minConfidence: Float = 0.45f,
    val minIntervalBetweenActionsMs: Long = 150L,
    val minGestureDurationMs: Long = 40L,
    val maxGestureDurationMs: Long = 2_000L,
    val maxActionsPerSession: Int = 300,
    val maxGeometryAgeMs: Long = CoordinateMapper.DEFAULT_MAX_GEOMETRY_AGE_MS,
    val previewEnabled: Boolean = true,
    val previewIntervalMs: Long = 400L,
    val storeScreenshots: Boolean = false,
    val screenshotIntervalMs: Long = 2_000L,
    val diagnosticsUploadEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val aiTimeoutMs: Long = 8_000L,
) {
    /** Builds the safety configuration that actually gates input. */
    fun toValidationConfig() = ValidationConfig(
        actionsEnabled = actionsEnabled,
        minGestureDurationMs = minGestureDurationMs,
        maxGestureDurationMs = maxGestureDurationMs,
        minIntervalBetweenActionsMs = minIntervalBetweenActionsMs,
        maxActionsPerSession = maxActionsPerSession,
        minConfidence = minConfidence,
        maxGeometryAgeMs = maxGeometryAgeMs,
    )

    /** AI requests must be bounded in both directions. */
    fun toPolicyConfig() = AgentPolicyConfig(minObservationConfidence = minConfidence)

    /** Effective capture interval, never below a floor that would spin the CPU for nothing. */
    val effectiveCaptureIntervalMs: Long get() = captureIntervalMs.coerceIn(MIN_CAPTURE_INTERVAL_MS, MAX_CAPTURE_INTERVAL_MS)

    /** Effective capture scale. Never above 1.0 and never so small that markers vanish. */
    val effectiveCaptureScale: Float get() = captureScale.coerceIn(MIN_CAPTURE_SCALE, 1.0f)

    val usesAiProvider: Boolean get() = agentSource == AgentSource.AI_PROVIDER

    companion object {
        const val MIN_CAPTURE_INTERVAL_MS = 50L
        const val MAX_CAPTURE_INTERVAL_MS = 5_000L
        const val MIN_CAPTURE_SCALE = 0.1f
        const val MIN_AI_TIMEOUT_MS = 1_000L
        const val MAX_AI_TIMEOUT_MS = 30_000L
    }
}

/**
 * Pure key/value codec so settings can be unit tested without an Android `Context`.
 *
 * Decoding is total: an unknown enum name or an unparsable number falls back to the safe default
 * rather than throwing, because a corrupt preferences file must never brick the app - it must
 * degrade to "input disabled".
 */
object AppSettingsCodec {

    const val KEY_CAPTURE_INTERVAL = "capture.intervalMs"
    const val KEY_CAPTURE_SCALE = "capture.scale"
    const val KEY_ACTIONS_ENABLED = "actions.enabled"
    const val KEY_INPUT_TARGET = "input.target"
    const val KEY_PERCEPTION_SOURCE = "perception.source"
    const val KEY_AGENT_SOURCE = "agent.source"
    const val KEY_MIN_CONFIDENCE = "validation.minConfidence"
    const val KEY_MIN_INTERVAL = "validation.minIntervalMs"
    const val KEY_MIN_GESTURE = "validation.minGestureDurationMs"
    const val KEY_MAX_GESTURE = "validation.maxGestureDurationMs"
    const val KEY_MAX_ACTIONS = "validation.maxActionsPerSession"
    const val KEY_MAX_GEOMETRY_AGE = "validation.maxGeometryAgeMs"
    const val KEY_PREVIEW_ENABLED = "preview.enabled"
    const val KEY_PREVIEW_INTERVAL = "preview.intervalMs"
    const val KEY_STORE_SCREENSHOTS = "storage.screenshots"
    const val KEY_SCREENSHOT_INTERVAL = "storage.screenshotIntervalMs"
    const val KEY_DIAGNOSTICS_UPLOAD = "privacy.diagnosticsUpload"
    const val KEY_AI_BASE_URL = "ai.baseUrl"
    const val KEY_AI_MODEL = "ai.model"
    const val KEY_AI_TIMEOUT = "ai.timeoutMs"

    fun encode(settings: AppSettings): Map<String, Any> = mapOf(
        KEY_CAPTURE_INTERVAL to settings.captureIntervalMs,
        KEY_CAPTURE_SCALE to settings.captureScale,
        KEY_ACTIONS_ENABLED to settings.actionsEnabled,
        KEY_INPUT_TARGET to settings.inputTarget.name,
        KEY_PERCEPTION_SOURCE to settings.perceptionSource.name,
        KEY_AGENT_SOURCE to settings.agentSource.name,
        KEY_MIN_CONFIDENCE to settings.minConfidence,
        KEY_MIN_INTERVAL to settings.minIntervalBetweenActionsMs,
        KEY_MIN_GESTURE to settings.minGestureDurationMs,
        KEY_MAX_GESTURE to settings.maxGestureDurationMs,
        KEY_MAX_ACTIONS to settings.maxActionsPerSession,
        KEY_MAX_GEOMETRY_AGE to settings.maxGeometryAgeMs,
        KEY_PREVIEW_ENABLED to settings.previewEnabled,
        KEY_PREVIEW_INTERVAL to settings.previewIntervalMs,
        KEY_STORE_SCREENSHOTS to settings.storeScreenshots,
        KEY_SCREENSHOT_INTERVAL to settings.screenshotIntervalMs,
        KEY_DIAGNOSTICS_UPLOAD to settings.diagnosticsUploadEnabled,
        KEY_AI_BASE_URL to settings.aiBaseUrl,
        KEY_AI_MODEL to settings.aiModel,
        KEY_AI_TIMEOUT to settings.aiTimeoutMs,
    )

    fun decode(values: (String) -> Any?): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            captureIntervalMs = values(KEY_CAPTURE_INTERVAL).asLongOr(defaults.captureIntervalMs),
            captureScale = values(KEY_CAPTURE_SCALE).asFloatOr(defaults.captureScale),
            actionsEnabled = values(KEY_ACTIONS_ENABLED).asBooleanOr(defaults.actionsEnabled),
            inputTarget = values(KEY_INPUT_TARGET).asEnumOr(InputTarget.entries, defaults.inputTarget),
            perceptionSource = values(KEY_PERCEPTION_SOURCE).asEnumOr(PerceptionSource.entries, defaults.perceptionSource),
            agentSource = values(KEY_AGENT_SOURCE).asEnumOr(AgentSource.entries, defaults.agentSource),
            minConfidence = values(KEY_MIN_CONFIDENCE).asFloatOr(defaults.minConfidence),
            minIntervalBetweenActionsMs = values(KEY_MIN_INTERVAL).asLongOr(defaults.minIntervalBetweenActionsMs),
            minGestureDurationMs = values(KEY_MIN_GESTURE).asLongOr(defaults.minGestureDurationMs),
            maxGestureDurationMs = values(KEY_MAX_GESTURE).asLongOr(defaults.maxGestureDurationMs),
            maxActionsPerSession = values(KEY_MAX_ACTIONS).asIntOr(defaults.maxActionsPerSession),
            maxGeometryAgeMs = values(KEY_MAX_GEOMETRY_AGE).asLongOr(defaults.maxGeometryAgeMs),
            previewEnabled = values(KEY_PREVIEW_ENABLED).asBooleanOr(defaults.previewEnabled),
            previewIntervalMs = values(KEY_PREVIEW_INTERVAL).asLongOr(defaults.previewIntervalMs),
            storeScreenshots = values(KEY_STORE_SCREENSHOTS).asBooleanOr(defaults.storeScreenshots),
            screenshotIntervalMs = values(KEY_SCREENSHOT_INTERVAL).asLongOr(defaults.screenshotIntervalMs),
            diagnosticsUploadEnabled = values(KEY_DIAGNOSTICS_UPLOAD).asBooleanOr(defaults.diagnosticsUploadEnabled),
            aiBaseUrl = values(KEY_AI_BASE_URL).asStringOr(defaults.aiBaseUrl),
            aiModel = values(KEY_AI_MODEL).asStringOr(defaults.aiModel),
            aiTimeoutMs = values(KEY_AI_TIMEOUT).asLongOr(defaults.aiTimeoutMs),
        ).sanitised()
    }
}

/**
 * Clamps anything the user (or a corrupt file) could put out of range.
 *
 * Top level rather than a member of [AppSettingsCodec] so it can be applied to any [AppSettings]
 * instance with a plain `settings.sanitised()` - including one produced by a UI edit, not only one
 * produced by [AppSettingsCodec.decode].
 *
 * Note the ordering in [maxGestureDurationMs]: it is clamped against the *already clamped*
 * [minGestureDurationMs], so a configuration that inverted the two cannot survive a round trip as
 * an unexecutable range.
 */
fun AppSettings.sanitised(): AppSettings = copy(
    captureIntervalMs = captureIntervalMs.coerceIn(AppSettings.MIN_CAPTURE_INTERVAL_MS, AppSettings.MAX_CAPTURE_INTERVAL_MS),
    captureScale = captureScale.coerceIn(AppSettings.MIN_CAPTURE_SCALE, 1.0f),
    minConfidence = minConfidence.coerceIn(0f, 1f),
    minGestureDurationMs = minGestureDurationMs.coerceIn(1L, 5_000L),
    maxGestureDurationMs = maxGestureDurationMs.coerceIn(minGestureDurationMs.coerceAtLeast(1L), 10_000L),
    minIntervalBetweenActionsMs = minIntervalBetweenActionsMs.coerceIn(0L, 10_000L),
    maxActionsPerSession = maxActionsPerSession.coerceIn(0, 10_000),
    maxGeometryAgeMs = maxGeometryAgeMs.coerceIn(200L, 30_000L),
    previewIntervalMs = previewIntervalMs.coerceIn(100L, 5_000L),
    screenshotIntervalMs = screenshotIntervalMs.coerceIn(500L, 60_000L),
    aiTimeoutMs = aiTimeoutMs.coerceIn(AppSettings.MIN_AI_TIMEOUT_MS, AppSettings.MAX_AI_TIMEOUT_MS),
)

private fun Any?.asLongOr(fallback: Long): Long = when (this) {
    is Long -> this
    is Int -> toLong()
    is String -> toLongOrNull() ?: fallback
    else -> fallback
}

private fun Any?.asIntOr(fallback: Int): Int = when (this) {
    is Int -> this
    is Long -> toInt()
    is String -> toIntOrNull() ?: fallback
    else -> fallback
}

private fun Any?.asFloatOr(fallback: Float): Float = when (this) {
    is Float -> this
    is Double -> toFloat()
    is String -> toFloatOrNull() ?: fallback
    else -> fallback
}

private fun Any?.asBooleanOr(fallback: Boolean): Boolean = when (this) {
    is Boolean -> this
    is String -> toBooleanStrictOrNull() ?: fallback
    else -> fallback
}

private fun Any?.asStringOr(fallback: String): String = when (this) {
    is String -> this
    else -> fallback
}

private fun <T : Enum<T>> Any?.asEnumOr(values: List<T>, fallback: T): T =
    (this as? String)?.let { name -> values.firstOrNull { it.name == name } } ?: fallback

package com.gameagent.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Codec tests.
 *
 * The property that matters is *fail-safe*: a preferences file that is missing, corrupt or hostile
 * must decode to something that cannot act, never to something that can. Every case below therefore
 * asserts the safe default rather than merely "it did not throw".
 */
class AppSettingsCodecTest {

    @Test
    fun `round trip preserves every setting`() {
        val original = AppSettings(
            captureIntervalMs = 120L,
            captureScale = 0.35f,
            actionsEnabled = true,
            inputTarget = InputTarget.ACCESSIBILITY,
            perceptionSource = PerceptionSource.DISABLED,
            agentSource = AgentSource.AI_PROVIDER,
            minConfidence = 0.7f,
            minIntervalBetweenActionsMs = 420L,
            minGestureDurationMs = 55L,
            maxGestureDurationMs = 1_500L,
            maxActionsPerSession = 42,
            maxGeometryAgeMs = 900L,
            previewEnabled = false,
            previewIntervalMs = 750L,
            storeScreenshots = true,
            screenshotIntervalMs = 4_500L,
            diagnosticsUploadEnabled = true,
            aiBaseUrl = "https://example.invalid/v1",
            aiModel = "vision-small",
            aiTimeoutMs = 3_000L,
        )

        val encoded = AppSettingsCodec.encode(original)
        val decoded = AppSettingsCodec.decode { key -> encoded[key] }

        assertEquals(original, decoded)
    }

    @Test
    fun `defaults cannot act and cannot leave the device`() {
        val defaults = AppSettings()

        assertFalse(defaults.actionsEnabled, "a fresh install must not be able to inject input")
        assertEquals(InputTarget.TEST_SCENE, defaults.inputTarget, "the default target must be the app's own screen")
        assertEquals(AgentSource.DETERMINISTIC, defaults.agentSource, "no AI service is required by default")
        assertFalse(defaults.storeScreenshots, "screenshots must not be written unless asked for")
        assertFalse(defaults.diagnosticsUploadEnabled, "nothing may be uploaded by default")
    }

    @Test
    fun `empty preferences decode to the safe defaults`() {
        val decoded = AppSettingsCodec.decode { null }

        assertEquals(AppSettings(), decoded)
        assertFalse(decoded.actionsEnabled)
    }

    @Test
    fun `corrupt values fall back instead of throwing`() {
        val decoded = AppSettingsCodec.decode { key ->
            when (key) {
                AppSettingsCodec.KEY_CAPTURE_INTERVAL -> "not-a-number"
                AppSettingsCodec.KEY_CAPTURE_SCALE -> "nonsense"
                AppSettingsCodec.KEY_ACTIONS_ENABLED -> "maybe"
                AppSettingsCodec.KEY_INPUT_TARGET -> "TOTALLY_UNKNOWN_ENUM"
                AppSettingsCodec.KEY_PERCEPTION_SOURCE -> 7
                AppSettingsCodec.KEY_AGENT_SOURCE -> null
                else -> null
            }
        }

        assertEquals(AppSettings(), decoded)
    }

    @Test
    fun `numbers stored as strings are accepted`() {
        val decoded = AppSettingsCodec.decode { key ->
            when (key) {
                AppSettingsCodec.KEY_MAX_ACTIONS -> "77"
                AppSettingsCodec.KEY_CAPTURE_INTERVAL -> "300"
                AppSettingsCodec.KEY_ACTIONS_ENABLED -> "true"
                else -> null
            }
        }

        assertEquals(77, decoded.maxActionsPerSession)
        assertEquals(300L, decoded.captureIntervalMs)
        assertTrue(decoded.actionsEnabled)
    }

    @Test
    fun `out of range values are clamped on decode`() {
        val decoded = AppSettingsCodec.decode { key ->
            when (key) {
                AppSettingsCodec.KEY_CAPTURE_INTERVAL -> 1L
                AppSettingsCodec.KEY_CAPTURE_SCALE -> 4.0f
                AppSettingsCodec.KEY_MIN_CONFIDENCE -> 12f
                AppSettingsCodec.KEY_MAX_ACTIONS -> -5
                AppSettingsCodec.KEY_MAX_GESTURE -> 10_000_000L
                AppSettingsCodec.KEY_MAX_GEOMETRY_AGE -> 5L
                else -> null
            }
        }

        assertEquals(AppSettings.MIN_CAPTURE_INTERVAL_MS, decoded.captureIntervalMs)
        assertEquals(1.0f, decoded.captureScale)
        assertEquals(1.0f, decoded.minConfidence)
        assertEquals(0, decoded.maxActionsPerSession)
        assertTrue(decoded.maxGestureDurationMs <= 10_000L, "gesture cap was ${decoded.maxGestureDurationMs}")
        assertEquals(200L, decoded.maxGeometryAgeMs)
    }

    @Test
    fun `sanitising keeps the gesture window consistent`() {
        // Both values are individually legal but inverted; a validator built from this would reject
        // every action with a duration between them.
        val inverted = AppSettings(minGestureDurationMs = 900L, maxGestureDurationMs = 100L).sanitised()

        assertTrue(
            inverted.maxGestureDurationMs >= inverted.minGestureDurationMs,
            "inverted window survived sanitising: ${inverted.minGestureDurationMs}..${inverted.maxGestureDurationMs}",
        )
    }

    @Test
    fun `effective capture values are bounded`() {
        assertTrue(AppSettings(captureIntervalMs = 0L).effectiveCaptureIntervalMs >= AppSettings.MIN_CAPTURE_INTERVAL_MS)
        assertTrue(AppSettings(captureIntervalMs = 1L).effectiveCaptureIntervalMs >= AppSettings.MIN_CAPTURE_INTERVAL_MS)

        val tiny = AppSettings(captureScale = 0.0001f).effectiveCaptureScale
        assertTrue(tiny >= AppSettings.MIN_CAPTURE_SCALE, "scale floor was $tiny")
        assertEquals(1.0f, AppSettings(captureScale = 9f).effectiveCaptureScale)
    }

    @Test
    fun `safety configuration mirrors the settings the user made`() {
        val settings = AppSettings(
            actionsEnabled = true,
            minConfidence = 0.8f,
            maxActionsPerSession = 12,
            maxGeometryAgeMs = 1_500L,
            minIntervalBetweenActionsMs = 900L,
        )
        val config = settings.toValidationConfig()

        assertTrue(config.actionsEnabled)
        assertEquals(0.8f, config.minConfidence)
        assertEquals(12, config.maxActionsPerSession)
        assertEquals(1_500L, config.maxGeometryAgeMs)
        assertEquals(900L, config.minIntervalBetweenActionsMs)
    }

    @Test
    fun `using the AI provider is opt in`() {
        assertFalse(AppSettings().usesAiProvider)
        assertTrue(AppSettings(agentSource = AgentSource.AI_PROVIDER).usesAiProvider)
    }
}

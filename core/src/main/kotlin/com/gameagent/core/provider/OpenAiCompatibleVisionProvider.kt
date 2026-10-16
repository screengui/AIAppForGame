package com.gameagent.core.provider

import com.gameagent.core.agent.GameAgent
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.json.JsonValue
import com.gameagent.core.json.MiniJson
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.GameState
import java.util.Locale

/**
 * Optional OpenAI-compatible chat-completions vision provider.
 *
 * Deliberate design choices:
 *  - **Never mandatory.** An unconfigured instance reports [isConfigured] = false and returns
 *    [ProviderResponse.Unavailable] without any network traffic, so the deterministic pipeline is
 *    the default and the app is fully usable offline.
 *  - **Explicit timeouts.** [HttpTransport] is given a hard timeout; the Android implementation maps
 *    it onto connect/read timeouts, so a hung provider cannot stall the session.
 *  - **Response validation.** The model answer must satisfy [DecisionParser] exactly. Nothing else
 *    is accepted, and a failed parse falls back to the local policy.
 *  - **No prompt injection surface from the game.** The prompt contains only numbers and enum names
 *    derived from [GameState] - never raw screen text, never a screenshot caption authored by us.
 *
 * The request encodes the observation as compact JSON; sending an actual image is intentionally out
 * of scope for this milestone (see docs/ROADMAP.md) because it requires a pixel-upload path plus an
 * explicit user consent step for uploading screenshots.
 */
class OpenAiCompatibleVisionProvider(
    private val credentials: VisionProviderCredentials,
    private val transport: HttpTransport,
    override val id: String = "openai-compatible",
    private val temperature: Double = 0.0,
    private val nanoTime: () -> Long = System::nanoTime,
) : VisionModelProvider {

    override val displayName: String = "OpenAI-compatible chat completions"

    override val costDisclosure: String =
        "Calls are billed by your provider per request. Each decision sends a small JSON " +
            "observation (no screenshots) and asks for one short JSON answer. Latency is typically " +
            "300ms-3s; the request is aborted after the configured timeout."

    override suspend fun isConfigured(): Boolean = credentials.isConfigured

    override suspend fun requestDecision(observation: GameState, timeoutMs: Long): ProviderResponse {
        if (!credentials.isConfigured) {
            return ProviderResponse.Unavailable("provider is not configured (missing base URL, model or API key)")
        }
        val body = buildRequest(observation)
        val start = nanoTime()
        val result = try {
            transport.postJson(
                url = credentials.endpoint,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer ${credentials.apiKey}",
                ),
                body = body,
                timeoutMs = timeoutMs,
            )
        } catch (t: Throwable) {
            return ProviderResponse.Unavailable("transport error: ${t.message ?: t::class.java.simpleName}")
        }
        val latencyMs = ((nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)

        return when (result) {
            is HttpResult.Failure -> ProviderResponse.Unavailable(result.message)
            is HttpResult.Success -> when {
                result.statusCode == 401 || result.statusCode == 403 ->
                    ProviderResponse.Unavailable("provider rejected the credential (HTTP ${result.statusCode})")

                result.statusCode == 429 ->
                    ProviderResponse.Unavailable("provider rate limited the request (HTTP 429)")

                result.statusCode !in 200..299 ->
                    ProviderResponse.Unavailable("provider returned HTTP ${result.statusCode}")

                else -> parseResponse(result.body, latencyMs)
            }
        }
    }

    private fun parseResponse(body: String, latencyMs: Long): ProviderResponse {
        val content = extractMessageContent(body)
            ?: return ProviderResponse.Invalid("response has no assistant message content", body.preview())
        val parsed = DecisionParser.parse(
            raw = content,
            decisionId = nextDecisionId(),
            createdAtMs = System.currentTimeMillis(),
            source = "ai:${credentials.model}",
        )
        return parsed.fold(
            onSuccess = { ProviderResponse.Decision(it, latencyMs) },
            onFailure = { ProviderResponse.Invalid(it.message ?: "invalid decision payload", content.preview()) },
        )
    }

    /** Reads `choices[0].message.content` from an OpenAI-compatible envelope. */
    private fun extractMessageContent(body: String): String? {
        val root = MiniJson.parse(body).getOrNull() ?: return null
        val choices = (root as? JsonValue.Obj)?.entries?.get("choices") as? JsonValue.Arr ?: return null
        val first = choices.items.firstOrNull() as? JsonValue.Obj ?: return null
        val message = first.entries["message"] as? JsonValue.Obj ?: return null
        return (message.entries["content"] as? JsonValue.Str)?.value
    }

    private fun buildRequest(observation: GameState): String {
        val observationJson = MiniJson.obj(
            "phase" to MiniJson.of(observation.phase.name),
            "phase_confidence" to MiniJson.of(observation.phaseConfidence),
            "confidence" to MiniJson.of(observation.confidence),
            "frame" to MiniJson.obj(
                "width" to MiniJson.of(observation.frameWidth),
                "height" to MiniJson.of(observation.frameHeight),
                "age_ms" to MiniJson.of(observation.timestampMs),
            ),
            "health" to MiniJson.of(observation.health.value),
            "mana" to MiniJson.of(observation.mana.value),
            "skills" to MiniJson.arr(
                observation.skills.map { skill ->
                    MiniJson.obj(
                        "slot" to MiniJson.of(skill.slot),
                        "ready" to MiniJson.of(skill.ready == true),
                        "confidence" to MiniJson.of(skill.confidence),
                        "center" to (skill.bounds?.let { pointJson(it.center.x, it.center.y) } ?: JsonValue.Null),
                    )
                },
            ),
            "enemies" to MiniJson.arr(
                observation.enemies.map { entity ->
                    MiniJson.obj(
                        "center" to pointJson(entity.center.x, entity.center.y),
                        "confidence" to MiniJson.of(entity.confidence),
                    )
                },
            ),
            "allies" to MiniJson.arr(
                observation.allies.map { entity ->
                    MiniJson.obj(
                        "center" to pointJson(entity.center.x, entity.center.y),
                        "confidence" to MiniJson.of(entity.confidence),
                    )
                },
            ),
        )

        val system = """
            You are a decision component inside an Android game-companion app.
            Reply with exactly one JSON object and nothing else.
            Allowed "action" values: WAIT, TAP, SWIPE, HOLD, RELEASE, STOP.
            Required keys: "action" (string), "confidence" (number 0..1), "explanation" (short string).
            TAP/RELEASE require "point" as {"x":0..1,"y":0..1}.
            SWIPE requires "point", "end_point" and integer "duration_ms" (0..5000).
            HOLD requires "point" and integer "duration_ms" (0..5000).
            WAIT/STOP must not include coordinates.
            Choose WAIT whenever the observation does not clearly justify an action.
        """.trimIndent()

        val user = "Observation:\n" + MiniJson.stringify(observationJson)

        val payload = MiniJson.obj(
            "model" to MiniJson.of(credentials.model),
            "temperature" to MiniJson.of(temperature),
            "messages" to MiniJson.arr(
                listOf(
                    MiniJson.obj("role" to MiniJson.of("system"), "content" to MiniJson.of(system)),
                    MiniJson.obj("role" to MiniJson.of("user"), "content" to MiniJson.of(user)),
                ),
            ),
        )
        return MiniJson.stringify(payload)
    }

    private fun pointJson(x: Float, y: Float) = MiniJson.obj(
        "x" to MiniJson.of(String.format(Locale.US, "%.4f", x).toDouble()),
        "y" to MiniJson.of(String.format(Locale.US, "%.4f", y).toDouble()),
    )

    private fun String.preview(): String = take(240)

    private var decisionCounter = 1_000_000L

    private fun nextDecisionId(): Long = decisionCounter++
}

/**
 * Wraps a provider in front of a local agent.
 *
 * This is the only place where a network answer can influence behaviour, and it is deliberately
 * narrow: an unavailable or invalid answer is not an error path with special handling, it is simply
 * "use the local policy". The returned decision always carries which source produced it.
 */
class RemoteVisionAgent(
    private val provider: VisionModelProvider,
    private val fallback: GameAgent,
    private val events: DiagnosticEventSink? = null,
    override val id: String = "remote-validating-agent",
    private val clock: () -> Long = System::currentTimeMillis,
) : GameAgent {

    override val displayName: String = "AI provider with deterministic fallback"

    override val disclosure: String =
        "Sends a compact JSON observation to ${provider.displayName} and accepts only a strictly " +
            "validated action proposal. Any failure falls back to ${fallback.displayName}. " +
            "Model output is never executed as code and never bypasses the action validator."

    override suspend fun decide(observation: GameState): AgentDecision {
        val response = provider.requestDecision(observation)
        return when (response) {
            is ProviderResponse.Decision -> {
                events?.debug(
                    EventCategory.AI,
                    "provider produced a decision",
                    mapOf("provider" to provider.id, "latencyMs" to response.latencyMs.toString()),
                )
                response.decision
            }

            is ProviderResponse.Unavailable -> {
                events?.warn(
                    EventCategory.AI,
                    "provider unavailable, using fallback policy",
                    mapOf("provider" to provider.id, "reason" to response.reason),
                )
                fallback.decide(observation)
            }

            is ProviderResponse.Invalid -> {
                events?.warn(
                    EventCategory.AI,
                    "provider response was invalid, using fallback policy",
                    mapOf("provider" to provider.id, "reason" to response.reason),
                )
                fallback.decide(observation)
            }
        }
    }
}

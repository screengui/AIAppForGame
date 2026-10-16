package com.gameagent.core.provider

sealed interface HttpResult {
    data class Success(val statusCode: Int, val body: String) : HttpResult
    /** The request never produced an HTTP response (DNS, TLS, timeout, offline). */
    data class Failure(val message: String, val cause: String? = null) : HttpResult
}

/**
 * Minimal HTTP abstraction.
 *
 * `:core` cannot use `HttpURLConnection` in unit tests without a network, so the transport is an
 * interface. The Android layer supplies a real implementation; tests supply a fake. This is also
 * what keeps the optional AI provider fully testable offline.
 */
interface HttpTransport {
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
    ): HttpResult
}

/** Endpoint + model + key for an OpenAI-compatible chat-completions API. */
data class VisionProviderCredentials(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val path: String = "/chat/completions",
) {
    val endpoint: String
        get() = baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** Never render the key itself. */
    override fun toString(): String =
        "VisionProviderCredentials(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isBlank()) "<missing>" else "<set>"})"
}

sealed interface ProviderResponse {
    data class Decision(
        val decision: com.gameagent.core.model.AgentDecision,
        val latencyMs: Long,
    ) : ProviderResponse

    /** The provider is not configured, offline or refused the request. Always fall back. */
    data class Unavailable(val reason: String) : ProviderResponse

    /** The provider answered, but the answer could not be trusted. Always fall back. */
    data class Invalid(val reason: String, val rawPreview: String?) : ProviderResponse
}

/**
 * Optional cloud/vision decision provider.
 *
 * Contract: implementations must never throw for ordinary failures (network, timeout, bad status,
 * malformed body); they return [ProviderResponse.Unavailable] or [ProviderResponse.Invalid] so the
 * caller can fall back deterministically. Only [AgentDecision] built from validated fields may be
 * returned - never anything that could be interpreted as code or a shell command.
 */
interface VisionModelProvider {
    val id: String
    val displayName: String
    /** Documented cost/limit disclosure shown in the UI before a user enables it. */
    val costDisclosure: String
    suspend fun isConfigured(): Boolean
    suspend fun requestDecision(
        observation: com.gameagent.core.model.GameState,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ProviderResponse

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}

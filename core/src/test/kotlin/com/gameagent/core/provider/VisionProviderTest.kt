package com.gameagent.core.provider

import com.gameagent.core.agent.DeterministicPolicyAgent
import com.gameagent.core.agent.GameAgent
import com.gameagent.core.diagnostics.DiagnosticEventLog
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.GameState
import com.gameagent.core.testutil.FakeHttpTransport
import com.gameagent.core.testutil.TestClock
import com.gameagent.core.testutil.TestNanoClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisionProviderTest {

    private val credentials = VisionProviderCredentials(
        baseUrl = "https://example.invalid/v1",
        model = "test-vision",
        apiKey = "test-key-not-a-real-secret",
    )

    private val clock = TestClock()

    private fun envelope(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${quote(content)}}}]}"""

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun provider(transport: FakeHttpTransport) = OpenAiCompatibleVisionProvider(
        credentials = credentials,
        transport = transport,
        nanoTime = TestNanoClock(),
    )

    @Test
    fun `an unconfigured provider never touches the network`() = runTest {
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(200, "{}") }
        val unconfigured = OpenAiCompatibleVisionProvider(
            credentials = credentials.copy(apiKey = ""),
            transport = transport,
        )
        assertEquals(false, unconfigured.isConfigured())
        val response = unconfigured.requestDecision(GameState.unknown(0L, 1L, 10, 10, "test", "none"))
        assertIs<ProviderResponse.Unavailable>(response)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `parses an OpenAI compatible answer`() = runTest {
        val body = envelope("""{"action":"TAP","point":{"x":0.4,"y":0.6},"confidence":0.7,"explanation":"skill ready"}""")
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(200, body) }
        val response = provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
        val decision = assertIs<ProviderResponse.Decision>(response)
        assertEquals(ActionType.TAP, decision.decision.actionType)
        assertEquals(0.4f, decision.decision.point?.x)
        assertEquals("ai:test-vision", decision.decision.source)
    }

    @Test
    fun `sends the configured endpoint, bearer token and timeout`() = runTest {
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(200, envelope("""{"action":"WAIT","confidence":1,"explanation":"x"}""")) }
        provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n"), timeoutMs = 4_321L)

        val request = transport.requests.single()
        assertEquals("https://example.invalid/v1/chat/completions", request.url)
        assertEquals("Bearer test-key-not-a-real-secret", request.headers["Authorization"])
        assertEquals(4_321L, request.timeoutMs)
        assertTrue(request.body.contains("\"model\":\"test-vision\""))
        assertTrue(request.body.contains("\"temperature\":0"))
    }

    @Test
    fun `a network failure is reported as unavailable, not as an exception`() = runTest {
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Failure("timeout after 8000ms", "SocketTimeoutException") }
        assertIs<ProviderResponse.Unavailable>(provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n")))
    }

    @Test
    fun `a transport that throws is contained`() = runTest {
        val transport = FakeHttpTransport { _, _, _ -> throw IllegalStateException("boom") }
        val response = provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
        val unavailable = assertIs<ProviderResponse.Unavailable>(response)
        assertTrue(unavailable.reason.contains("boom"))
    }

    @Test
    fun `auth, rate limit and server errors are distinct unavailable reasons`() = runTest {
        val cases = mapOf(
            401 to "credential",
            403 to "credential",
            429 to "rate limited",
            500 to "HTTP 500",
        )
        for ((status, fragment) in cases) {
            val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(status, "{}") }
            val response = provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
            val unavailable = assertIs<ProviderResponse.Unavailable>(response)
            assertTrue(unavailable.reason.contains(fragment), "status $status gave: ${unavailable.reason}")
        }
    }

    @Test
    fun `a malformed envelope is invalid`() = runTest {
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(200, """{"choices":[]}""") }
        assertIs<ProviderResponse.Invalid>(provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n")))
    }

    @Test
    fun `prose instead of json is invalid`() = runTest {
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(200, envelope("Sure! I will tap the screen.")) }
        val response = provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
        val invalid = assertIs<ProviderResponse.Invalid>(response)
        assertTrue(invalid.rawPreview!!.isNotEmpty())
    }

    @Test
    fun `an out of range action from the model is rejected before it can reach the validator`() = runTest {
        val body = envelope("""{"action":"TAP","point":{"x":9.0,"y":0.5},"confidence":0.9,"explanation":"x"}""")
        val transport = FakeHttpTransport { _, _, _ -> HttpResult.Success(200, body) }
        assertIs<ProviderResponse.Invalid>(provider(transport).requestDecision(GameState.unknown(0L, 1L, 10, 10, "t", "n")))
    }

    // -------------------------------------------------------------------- remote agent

    private class StubProvider(
        override val id: String = "stub",
        private val response: ProviderResponse,
    ) : VisionModelProvider {
        override val displayName: String = "Stub provider"
        override val costDisclosure: String = "free"
        override suspend fun isConfigured(): Boolean = true
        override suspend fun requestDecision(observation: GameState, timeoutMs: Long): ProviderResponse = response
    }

    @Test
    fun `the remote agent uses the validated provider decision when it is valid`() = runTest {
        val providerDecision = com.gameagent.core.model.AgentDecision(
            id = 1L,
            actionType = ActionType.WAIT,
            confidence = 1f,
            explanation = "provider says wait",
            createdAtMs = clock(),
            source = "ai:stub",
        )
        val agent = RemoteVisionAgent(
            provider = StubProvider(response = ProviderResponse.Decision(providerDecision, 120L)),
            fallback = DeterministicPolicyAgent(clock = clock),
            events = DiagnosticEventLog(),
        )
        val decision = agent.decide(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
        assertEquals("provider says wait", decision.explanation)
    }

    @Test
    fun `the remote agent falls back when the provider is unavailable`() = runTest {
        val events = DiagnosticEventLog()
        val fallback: GameAgent = DeterministicPolicyAgent(clock = clock)
        val agent = RemoteVisionAgent(
            provider = StubProvider(response = ProviderResponse.Unavailable("offline")),
            fallback = fallback,
            events = events,
        )
        val decision = agent.decide(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
        assertEquals(fallback.id, decision.source)
        assertEquals(ActionType.WAIT, decision.actionType)
        assertTrue(events.snapshot().any { it.message.contains("unavailable") })
    }

    @Test
    fun `the remote agent falls back when the provider response is invalid`() = runTest {
        val events = DiagnosticEventLog()
        val agent = RemoteVisionAgent(
            provider = StubProvider(response = ProviderResponse.Invalid("bad json", "nope")),
            fallback = DeterministicPolicyAgent(clock = clock),
            events = events,
        )
        val decision = agent.decide(GameState.unknown(0L, 1L, 10, 10, "t", "n"))
        assertEquals(ActionType.WAIT, decision.actionType)
        assertTrue(events.snapshot().any { it.message.contains("invalid") })
    }

    @Test
    fun `credentials never print the api key`() {
        assertTrue(credentials.toString().contains("<set>"))
        assertTrue(!credentials.toString().contains("test-key-not-a-real-secret"))
        assertTrue(credentials.copy(apiKey = "").toString().contains("<missing>"))
    }

    @Test
    fun `the endpoint is joined correctly for trailing slashes`() {
        assertEquals("https://x/v1/chat/completions", credentials.copy(baseUrl = "https://x/v1/").endpoint)
        assertEquals("https://x/v1/chat/completions", credentials.copy(baseUrl = "https://x/v1").endpoint)
        assertEquals("https://x/custom", credentials.copy(baseUrl = "https://x/", path = "custom").endpoint)
    }
}

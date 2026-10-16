package com.gameagent.core.testutil

import com.gameagent.core.geometry.DisplayRotation
import com.gameagent.core.geometry.ScreenGeometry
import com.gameagent.core.model.GameState
import com.gameagent.core.perception.ImageFrame
import com.gameagent.core.provider.HttpResult
import com.gameagent.core.provider.HttpTransport
import com.gameagent.core.provider.ProviderResponse
import com.gameagent.core.provider.VisionModelProvider

/** Deterministic wall clock. */
class TestClock(private var nowMs: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long = nowMs
    fun set(value: Long) {
        nowMs = value
    }

    fun advance(deltaMs: Long) {
        nowMs += deltaMs
    }
}

/**
 * Monotonic fake nano clock. Advances by 1ms per call so measured durations are non-zero and
 * deterministic instead of depending on real elapsed time.
 */
class TestNanoClock(private var nanos: Long = 0L) : () -> Long {
    override fun invoke(): Long = nanos
    fun advance(deltaNanos: Long) {
        nanos += deltaNanos
    }
}

/** Records requests and returns canned responses. No sockets are ever opened in unit tests. */
class FakeHttpTransport(
    private val handler: (url: String, body: String, timeoutMs: Long) -> HttpResult,
) : HttpTransport {

    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val timeoutMs: Long,
    )

    private val recorded = mutableListOf<Request>()

    val requests: List<Request> get() = recorded.toList()

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
    ): HttpResult {
        recorded += Request(url, headers, body, timeoutMs)
        return handler(url, body, timeoutMs)
    }
}

/** Returns a canned provider response without any network access. */
class StubVisionProvider(
    private val response: ProviderResponse,
    override val id: String = "stub-provider",
) : VisionModelProvider {
    override val displayName: String = "Stub vision provider"
    override val costDisclosure: String = "no cost, no network"
    var calls: Int = 0
        private set

    override suspend fun isConfigured(): Boolean = true

    override suspend fun requestDecision(observation: GameState, timeoutMs: Long): ProviderResponse {
        calls++
        return response
    }
}

object Builders {

    fun geometry(
        displayWidth: Int = 1080,
        displayHeight: Int = 2340,
        captureWidth: Int = displayWidth,
        captureHeight: Int = displayHeight,
        frameId: Long = 1L,
        atMs: Long = 1_700_000_000_000L,
        rotation: DisplayRotation = DisplayRotation.ROTATION_0,
    ) = ScreenGeometry(
        captureWidth = captureWidth,
        captureHeight = captureHeight,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        rotation = rotation,
        frameId = frameId,
        capturedAtMs = atMs,
    )

    fun frame(
        width: Int = 1080,
        height: Int = 2340,
        frameId: Long = 1L,
        atMs: Long = 1_700_000_000_000L,
        color: Int = 0xFF101010.toInt(),
    ) = ImageFrame(
        frameId = frameId,
        timestampMs = atMs,
        width = width,
        height = height,
        argb = IntArray(width * height) { color },
    )
}

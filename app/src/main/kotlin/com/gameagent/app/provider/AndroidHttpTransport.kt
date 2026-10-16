package com.gameagent.app.provider

import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.provider.HttpResult
import com.gameagent.core.provider.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Minimal HTTPS transport for the optional vision provider.
 *
 * Deliberate constraints:
 *  - **HTTPS only.** A cleartext endpoint is refused before any connection is made, so an API key
 *    cannot be sent in the clear even if the user mistypes `http://`.
 *  - **Bounded.** The supplied timeout is applied to both connect and read, so a hung provider
 *    cannot stall the agent loop.
 *  - **Never throws.** Every failure mode is returned as [HttpResult.Failure] so the caller can fall
 *    back to the local policy; a network problem must never take the session down.
 *  - **No logging of the body or headers.** The request body contains only observation numbers and
 *    the header contains the key, and neither is ever written to the event log.
 */
class AndroidHttpTransport(
    private val events: DiagnosticEventSink,
) : HttpTransport {

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
    ): HttpResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            return@withContext HttpResult.Failure("refusing a non-HTTPS endpoint")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                // Defensive upper bound: a stale setting must not hang a worker thread for minutes.
                val bounded = timeoutMs.coerceIn(500L, MAX_TIMEOUT_MS).toInt()
                connectTimeout = bounded
                readTimeout = bounded
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult.Success(status, text)
        } catch (t: SocketTimeoutException) {
            events.warn(com.gameagent.core.diagnostics.EventCategory.AI, "provider request timed out")
            HttpResult.Failure("request timed out after ${timeoutMs}ms", t::class.java.simpleName)
        } catch (t: IOException) {
            events.warn(com.gameagent.core.diagnostics.EventCategory.AI, "provider request failed")
            HttpResult.Failure("network error: ${t.message ?: t::class.java.simpleName}", t::class.java.simpleName)
        } catch (t: Throwable) {
            events.warn(com.gameagent.core.diagnostics.EventCategory.AI, "provider request failed unexpectedly")
            HttpResult.Failure("unexpected error: ${t.message ?: t::class.java.simpleName}")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    companion object {
        const val MAX_TIMEOUT_MS = 60_000L
        const val USER_AGENT = "GameAgent-Android/0.1"
    }
}

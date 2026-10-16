package com.gameagent.core.diagnostics

/** Severity of a structured diagnostic event. */
enum class EventLevel { DEBUG, INFO, WARN, ERROR }

/** Subsystem that produced an event. Used for filtering in the diagnostics screen. */
enum class EventCategory {
    SYSTEM,
    SESSION,
    CAPTURE,
    PERCEPTION,
    DECISION,
    VALIDATION,
    INPUT,
    AI,
}

/**
 * One structured diagnostics record.
 *
 * Events must never contain API keys, access tokens, personal data or image payloads.
 * [data] is deliberately `String -> String` so it stays cheap to render in a phone-sized list.
 */
data class DiagnosticEvent(
    val seq: Long,
    val timestampMs: Long,
    val level: EventLevel,
    val category: EventCategory,
    val message: String,
    val data: Map<String, String> = emptyMap(),
) {
    fun format(): String {
        val payload = if (data.isEmpty()) "" else data.entries.joinToString(prefix = " {", postfix = "}") { "${it.key}=${it.value}" }
        return "[$level/$category] $message$payload"
    }
}

/**
 * What the core components depend on. Implemented by [DiagnosticEventLog] on the app side so
 * the policy/validation/session code never needs to touch the UI.
 */
interface DiagnosticEventSink {
    fun log(level: EventLevel, category: EventCategory, message: String, data: Map<String, String> = emptyMap())

    fun debug(category: EventCategory, message: String, data: Map<String, String> = emptyMap()) =
        log(EventLevel.DEBUG, category, message, data)

    fun info(category: EventCategory, message: String, data: Map<String, String> = emptyMap()) =
        log(EventLevel.INFO, category, message, data)

    fun warn(category: EventCategory, message: String, data: Map<String, String> = emptyMap()) =
        log(EventLevel.WARN, category, message, data)

    fun error(category: EventCategory, message: String, data: Map<String, String> = emptyMap()) =
        log(EventLevel.ERROR, category, message, data)
}

/** Drops everything. Useful in unit tests and as a safe default. */
object NoOpEventSink : DiagnosticEventSink {
    override fun log(level: EventLevel, category: EventCategory, message: String, data: Map<String, String>) = Unit
}

/**
 * Bounded, thread-safe, in-memory event log.
 *
 * The ring buffer is intentionally small (default 300 records) so a long session cannot
 * grow memory without bound. Nothing is written to disk and nothing leaves the device
 * unless the user explicitly exports it.
 */
class DiagnosticEventLog(
    private val capacity: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiagnosticEventSink {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(capacity)
    private var nextSeq = 1L

    private val mutableRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Increments on every change.
     *
     * Rendering reads [recent] imperatively and only recomposes when this counter changes, which is
     * much cheaper than publishing an immutable copy of the whole log on every log call.
     */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = mutableRevision

    override fun log(level: EventLevel, category: EventCategory, message: String, data: Map<String, String>) {
        synchronized(lock) {
            buffer.addLast(DiagnosticEvent(nextSeq++, clock(), level, category, message, data))
            while (buffer.size > capacity) buffer.removeFirst()
        }
        mutableRevision.value = mutableRevision.value + 1L
    }

    /** Newest last. */
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { buffer.toList() }

    /** Newest first, limited to [limit]. Handy for the UI list. */
    fun recent(limit: Int = 50): List<DiagnosticEvent> = synchronized(lock) {
        buffer.asReversed().take(limit)
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        mutableRevision.value = mutableRevision.value + 1L
    }

    fun size(): Int = synchronized(lock) { buffer.size }
}

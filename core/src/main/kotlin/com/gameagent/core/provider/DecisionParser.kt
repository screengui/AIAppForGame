package com.gameagent.core.provider

import com.gameagent.core.geometry.NormalizedPoint
import com.gameagent.core.json.JsonValue
import com.gameagent.core.json.MiniJson
import com.gameagent.core.json.asDoubleOrNull
import com.gameagent.core.json.asObj
import com.gameagent.core.json.asStringOrNull
import com.gameagent.core.json.get
import com.gameagent.core.model.ActionType
import com.gameagent.core.model.AgentDecision
import com.gameagent.core.model.EntityKind
import com.gameagent.core.model.GameState
import com.gameagent.core.model.TargetRef

/**
 * Strictly parses a model's JSON answer into an [AgentDecision].
 *
 * Security posture:
 *  - The output is *data only*. The parser can produce an [ActionType] plus bounded numbers; there
 *    is no field through which a model could express a command, an intent, a URL or code.
 *  - Unknown `action` values are rejected outright (no fuzzy matching, no defaults).
 *  - Coordinates must be numeric and inside the unit square; durations must be integers inside a
 *    hard cap. Anything else fails the whole response, and the caller falls back.
 *  - Strings are truncated and control characters stripped, so a model cannot flood the event log.
 */
object DecisionParser {

    const val MAX_EXPLANATION_LENGTH = 200
    const val MAX_DURATION_MS = 5_000L

    /** Fields of the agreed schema. Extra keys are tolerated but ignored (and never trusted). */
    const val KEY_ACTION = "action"
    const val KEY_POINT = "point"
    const val KEY_END_POINT = "end_point"
    const val KEY_TARGET = "target"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_EXPLANATION = "explanation"

    fun parse(
        raw: String,
        decisionId: Long,
        createdAtMs: Long,
        source: String,
    ): Result<AgentDecision> {
        val root = MiniJson.parse(raw).getOrElse { return Result.failure(ProviderFormatException("not valid JSON: ${it.message}")) }
        val obj = root.asObj() ?: return Result.failure(ProviderFormatException("response is not a JSON object"))

        val actionText = (obj[KEY_ACTION]?.asStringOrNull())?.trim()
            ?: return Result.failure(ProviderFormatException("missing string field '$KEY_ACTION'"))
        val actionType = parseAction(actionText)
            ?: return Result.failure(ProviderFormatException("unsupported action '$actionText'"))

        val confidence = obj[KEY_CONFIDENCE]?.asDoubleOrNull()?.toFloat()
            ?: return Result.failure(ProviderFormatException("missing numeric field '$KEY_CONFIDENCE'"))
        if (confidence.isNaN() || confidence !in 0f..1f) {
            return Result.failure(ProviderFormatException("$KEY_CONFIDENCE must be within 0..1, got $confidence"))
        }

        val explanation = obj[KEY_EXPLANATION]?.asStringOrNull()?.sanitise()
            ?: return Result.failure(ProviderFormatException("missing string field '$KEY_EXPLANATION'"))

        val point = obj[KEY_POINT]?.let { parsePoint(it, KEY_POINT).getOrElse { e -> return Result.failure(e) } }
        val endPoint = obj[KEY_END_POINT]?.let { parsePoint(it, KEY_END_POINT).getOrElse { e -> return Result.failure(e) } }

        val duration = obj[KEY_DURATION_MS]?.asDoubleOrNull()?.let { raw ->
            val value = raw.toLong()
            if (value < 0L || value > MAX_DURATION_MS) {
                return Result.failure(
                    ProviderFormatException("$KEY_DURATION_MS must be within 0..$MAX_DURATION_MS, got $value"),
                )
            }
            value
        }

        // Structural requirements per action, so a malformed response never reaches the validator.
        if (actionType.requiresPoint && point == null) {
            return Result.failure(ProviderFormatException("action $actionType requires a '$KEY_POINT'"))
        }
        if (actionType == ActionType.SWIPE && endPoint == null) {
            return Result.failure(ProviderFormatException("action SWIPE requires a '$KEY_END_POINT'"))
        }
        if (!actionType.requiresPoint && (point != null || endPoint != null)) {
            return Result.failure(ProviderFormatException("action $actionType must not carry coordinates"))
        }
        if ((actionType == ActionType.SWIPE || actionType == ActionType.HOLD) && duration == null) {
            return Result.failure(ProviderFormatException("action $actionType requires '$KEY_DURATION_MS'"))
        }

        val target = obj[KEY_TARGET]?.let { parseTarget(it) }

        return Result.success(
            AgentDecision(
                id = decisionId,
                actionType = actionType,
                target = target,
                point = point,
                endPoint = endPoint,
                durationMs = duration,
                confidence = confidence,
                explanation = explanation,
                createdAtMs = createdAtMs,
                source = source,
            ),
        )
    }

    private fun parseAction(text: String): ActionType? =
        ActionType.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }

    private fun parsePoint(value: JsonValue, field: String): Result<NormalizedPoint> {
        val obj = value.asObj() ?: return Result.failure(ProviderFormatException("'$field' must be an object"))
        val x = obj["x"]?.asDoubleOrNull()
            ?: return Result.failure(ProviderFormatException("'$field.x' must be a number"))
        val y = obj["y"]?.asDoubleOrNull()
            ?: return Result.failure(ProviderFormatException("'$field.y' must be a number"))
        if (x !in 0.0..1.0 || y !in 0.0..1.0) {
            return Result.failure(ProviderFormatException("'$field' must be normalized to 0..1, got ($x, $y)"))
        }
        return Result.success(NormalizedPoint(x.toFloat(), y.toFloat()))
    }

    private fun parseTarget(value: JsonValue): TargetRef? {
        val obj = value.asObj() ?: return null
        val kind = obj["kind"]?.asStringOrNull()?.let { raw ->
            EntityKind.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        } ?: EntityKind.UNKNOWN
        val label = obj["label"]?.asStringOrNull()?.sanitise()
        return TargetRef(kind = kind, label = label)
    }

    private fun String.sanitise(): String =
        filter { it.code >= 0x20 && it.code != 0x7F }
            .take(MAX_EXPLANATION_LENGTH)
            .ifBlank { "(no explanation)" }
}

class ProviderFormatException(message: String) : Exception(message)

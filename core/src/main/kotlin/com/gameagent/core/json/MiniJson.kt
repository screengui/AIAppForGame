package com.gameagent.core.json

/**
 * A small, strict JSON reader/writer.
 *
 * Why hand-rolled instead of a JSON library: the only structured I/O the core module needs is the
 * optional cloud-vision request/response, and keeping `:core` free of runtime dependencies makes it
 * trivially unit-testable and keeps the APK small. This parser is strict on purpose - anything the
 * optional provider sends that does not match the expected shape must fail loudly, never be coerced.
 *
 * Limits: maximum nesting depth [MAX_DEPTH], and no NaN/Infinity literals.
 */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

class JsonParseException(message: String, val offset: Int) : Exception("$message (at offset $offset)")

object MiniJson {

    const val MAX_DEPTH = 32

    // ------------------------------------------------------------------ parsing

    fun parse(text: String): Result<JsonValue> = try {
        Result.success(Parser(text).parseDocument())
    } catch (e: JsonParseException) {
        Result.failure(e)
    }

    // ---------------------------------------------------------------- writing

    fun stringify(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> out.append(formatNumber(value.value))
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    private fun formatNumber(d: Double): String {
        if (!d.isFinite()) throw IllegalArgumentException("cannot serialise non-finite number $d")
        val asLong = d.toLong()
        return if (asLong.toDouble() == d) asLong.toString() else d.toString()
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------- builders

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(pairs.toMap())

    fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

    fun of(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

    fun of(value: Number?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toDouble())

    fun of(value: Boolean): JsonValue = JsonValue.Bool(value)

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            if (index >= text.length) throw JsonParseException("empty document", index)
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) throw JsonParseException("trailing content", index)
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonParseException("nesting deeper than $MAX_DEPTH", index)
            skipWhitespace()
            if (index >= text.length) throw JsonParseException("unexpected end of input", index)
            return when (val c = text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw JsonParseException("unexpected character '$c'", index)
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth + 1)
                entries[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Obj(entries)
                    }

                    else -> throw JsonParseException("expected ',' or '}' in object", index)
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }

                    else -> throw JsonParseException("expected ',' or ']' in array", index)
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (index >= text.length) throw JsonParseException("unterminated string", index)
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) throw JsonParseException("unterminated escape", index)
                        when (val esc = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw JsonParseException("truncated \\u escape", index)
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("invalid \\u escape '$hex'", index)
                                sb.append(code.toChar())
                                index += 4
                            }

                            else -> throw JsonParseException("invalid escape '\\$esc'", index)
                        }
                    }

                    else -> if (c < ' ') throw JsonParseException("unescaped control character", index - 1) else sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (peek() == '.') {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("invalid number '$raw'", start)
            if (!value.isFinite()) throw JsonParseException("non-finite number '$raw' is not valid JSON", start)
            return JsonValue.Num(value)
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, index)) throw JsonParseException("expected '$literal'", index)
            index += literal.length
            return value
        }

        private fun expect(c: Char) {
            if (peek() != c) throw JsonParseException("expected '$c'", index)
            index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) index++
        }
    }
}

// ------------------------------------------------------------------ accessors

fun JsonValue?.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue?.asArr(): JsonValue.Arr? = this as? JsonValue.Arr

fun JsonValue?.asStringOrNull(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.asDoubleOrNull(): Double? = when (this) {
    is JsonValue.Num -> value
    is JsonValue.Str -> value.toDoubleOrNull()
    else -> null
}

fun JsonValue?.asBooleanOrNull(): Boolean? = (this as? JsonValue.Bool)?.value

/** Object member lookup; null when this is not an object or the key is absent. */
operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.entries?.get(key)

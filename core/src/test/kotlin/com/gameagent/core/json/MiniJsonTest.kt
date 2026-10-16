package com.gameagent.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MiniJsonTest {

    @Test
    fun `round trips a nested document`() {
        val original = MiniJson.obj(
            "action" to MiniJson.of("TAP"),
            "confidence" to MiniJson.of(0.75),
            "point" to MiniJson.obj("x" to MiniJson.of(0.25), "y" to MiniJson.of(0.5)),
            "flags" to MiniJson.arr(listOf(MiniJson.of(true), JsonValue.Null)),
            "note" to MiniJson.of("line\nbreak \"quoted\""),
        )
        val text = MiniJson.stringify(original)
        val parsed = MiniJson.parse(text).getOrThrow()
        assertEquals(original, parsed)
    }

    @Test
    fun `parses primitives and accessors`() {
        val parsed = MiniJson.parse("""{"a":1.5,"b":"x","c":true,"d":null,"e":[1,2]}""").getOrThrow()
        assertEquals(1.5, parsed["a"].asDoubleOrNull())
        assertEquals("x", parsed["b"].asStringOrNull())
        assertEquals(true, parsed["c"].asBooleanOrNull())
        assertEquals(null, parsed["d"].asStringOrNull())
        assertEquals(2, parsed["e"].asArr()?.items?.size)
        assertEquals(null, parsed["missing"])
    }

    @Test
    fun `numbers without a fractional part serialise as integers`() {
        assertEquals("""{"n":3}""", MiniJson.stringify(MiniJson.obj("n" to MiniJson.of(3.0))))
    }

    @Test
    fun `rejects trailing content`() {
        val error = MiniJson.parse("""{"a":1} extra""").exceptionOrNull()
        assertIs<JsonParseException>(error)
    }

    @Test
    fun `rejects an empty document`() {
        assertIs<JsonParseException>(MiniJson.parse("   ").exceptionOrNull())
    }

    @Test
    fun `rejects unquoted keys and single quotes`() {
        assertIs<JsonParseException>(MiniJson.parse("{a:1}").exceptionOrNull())
        assertIs<JsonParseException>(MiniJson.parse("{'a':1}").exceptionOrNull())
    }

    @Test
    fun `rejects unterminated strings and objects`() {
        assertIs<JsonParseException>(MiniJson.parse("""{"a":"unterminated}""").exceptionOrNull())
        assertIs<JsonParseException>(MiniJson.parse("""{"a":1""").exceptionOrNull())
    }

    @Test
    fun `rejects invalid escapes and truncated unicode escapes`() {
        assertIs<JsonParseException>(MiniJson.parse("""{"a":"\q"}""").exceptionOrNull())
        assertIs<JsonParseException>(MiniJson.parse("""{"a":"\u12"}""").exceptionOrNull())
    }

    @Test
    fun `rejects nesting beyond the depth limit`() {
        val deep = "[".repeat(MiniJson.MAX_DEPTH + 5) + "]".repeat(MiniJson.MAX_DEPTH + 5)
        assertIs<JsonParseException>(MiniJson.parse(deep).exceptionOrNull())
    }

    @Test
    fun `accepts nesting at the depth limit`() {
        val depth = MiniJson.MAX_DEPTH
        val atLimit = "[".repeat(depth) + "]".repeat(depth)
        assertTrue(MiniJson.parse(atLimit).isSuccess)
    }

    @Test
    fun `rejects non finite and malformed numbers`() {
        assertIs<JsonParseException>(MiniJson.parse("""{"a":1e999}""").exceptionOrNull())
        assertIs<JsonParseException>(MiniJson.parse("""{"a":-}""").exceptionOrNull())
    }

    @Test
    fun `serialising a non finite number fails loudly`() {
        assertIs<IllegalArgumentException>(
            runCatching { MiniJson.stringify(MiniJson.of(Double.NaN)) }.exceptionOrNull(),
        )
    }

    @Test
    fun `escapes control characters`() {
        val text = MiniJson.stringify(MiniJson.of("tab\there"))
        assertEquals("\"tab\\there\"", text)
    }
}

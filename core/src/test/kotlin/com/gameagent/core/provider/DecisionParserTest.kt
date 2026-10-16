package com.gameagent.core.provider

import com.gameagent.core.model.ActionType
import com.gameagent.core.model.EntityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionParserTest {

    private fun parse(json: String) = DecisionParser.parse(json, decisionId = 5L, createdAtMs = 42L, source = "test")

    @Test
    fun `parses a well formed tap`() {
        val decision = parse(
            """{"action":"TAP","point":{"x":0.25,"y":0.5},"confidence":0.8,"explanation":"tap the skill"}""",
        ).getOrThrow()

        assertEquals(ActionType.TAP, decision.actionType)
        assertEquals(0.25f, decision.point?.x)
        assertEquals(0.5f, decision.point?.y)
        assertEquals(0.8f, decision.confidence)
        assertEquals("tap the skill", decision.explanation)
        assertEquals(5L, decision.id)
        assertEquals(42L, decision.createdAtMs)
        assertEquals("test", decision.source)
    }

    @Test
    fun `parses a swipe with duration and target`() {
        val decision = parse(
            """
            {"action":"swipe","point":{"x":0.5,"y":0.5},"end_point":{"x":0.8,"y":0.2},
             "duration_ms":300,"confidence":0.66,"explanation":"engage",
             "target":{"kind":"ENEMY","label":"marksman"}}
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(ActionType.SWIPE, decision.actionType)
        assertEquals(300L, decision.durationMs)
        assertEquals(EntityKind.ENEMY, decision.target?.kind)
        assertEquals("marksman", decision.target?.label)
    }

    @Test
    fun `accepts a wait without coordinates`() {
        val decision = parse("""{"action":"WAIT","confidence":1,"explanation":"nothing to do"}""").getOrThrow()
        assertEquals(ActionType.WAIT, decision.actionType)
        assertEquals(null, decision.point)
        assertEquals(1f, decision.confidence)
    }

    @Test
    fun `rejects an unknown action`() {
        val error = parse("""{"action":"LAUNCH_MISSILE","confidence":1,"explanation":"x"}""").exceptionOrNull()
        assertTrue(error is ProviderFormatException, "got ${error?.javaClass}")
        assertTrue(error!!.message!!.contains("unsupported action"))
    }

    @Test
    fun `rejects nested or injected payloads instead of executing them`() {
        // A model trying to smuggle a command cannot: extra keys are not part of the model at all.
        val result = parse(
            """{"action":"TAP","point":{"x":0.5,"y":0.5},"confidence":1,
                "explanation":"x","command":"rm -rf /","tool":"shell"}""",
        )
        // It still parses as a plain tap, with no trace of the injected keys anywhere.
        val decision = result.getOrThrow()
        assertEquals(ActionType.TAP, decision.actionType)
        assertTrue(!decision.toString().contains("rm -rf"))
    }

    @Test
    fun `rejects a missing confidence`() {
        val error = parse("""{"action":"WAIT","explanation":"x"}""").exceptionOrNull()
        assertTrue(error is ProviderFormatException)
    }

    @Test
    fun `rejects confidence outside the unit interval`() {
        assertTrue(parse("""{"action":"WAIT","confidence":1.4,"explanation":"x"}""").isFailure)
        assertTrue(parse("""{"action":"WAIT","confidence":-0.2,"explanation":"x"}""").isFailure)
    }

    @Test
    fun `rejects a missing explanation`() {
        assertTrue(parse("""{"action":"WAIT","confidence":0.5}""").isFailure)
    }

    @Test
    fun `rejects coordinates outside the unit square`() {
        assertTrue(
            parse("""{"action":"TAP","point":{"x":1.2,"y":0.5},"confidence":0.5,"explanation":"x"}""").isFailure,
        )
        assertTrue(
            parse("""{"action":"TAP","point":{"x":0.5,"y":-0.1},"confidence":0.5,"explanation":"x"}""").isFailure,
        )
    }

    @Test
    fun `rejects non numeric coordinates`() {
        assertTrue(
            parse("""{"action":"TAP","point":{"x":"left","y":0.5},"confidence":0.5,"explanation":"x"}""").isFailure,
        )
    }

    @Test
    fun `rejects a tap without a point`() {
        assertTrue(parse("""{"action":"TAP","confidence":0.5,"explanation":"x"}""").isFailure)
    }

    @Test
    fun `rejects a swipe without an end point`() {
        assertTrue(
            parse(
                """{"action":"SWIPE","point":{"x":0.1,"y":0.1},"duration_ms":200,"confidence":0.5,"explanation":"x"}""",
            ).isFailure,
        )
    }

    @Test
    fun `rejects a swipe or hold without a duration`() {
        assertTrue(
            parse(
                """{"action":"SWIPE","point":{"x":0.1,"y":0.1},"end_point":{"x":0.9,"y":0.9},
                    "confidence":0.5,"explanation":"x"}""",
            ).isFailure,
        )
        assertTrue(
            parse("""{"action":"HOLD","point":{"x":0.1,"y":0.1},"confidence":0.5,"explanation":"x"}""").isFailure,
        )
    }

    @Test
    fun `rejects an absurd duration`() {
        assertTrue(
            parse(
                """{"action":"HOLD","point":{"x":0.1,"y":0.1},"duration_ms":600000,
                    "confidence":0.5,"explanation":"x"}""",
            ).isFailure,
        )
    }

    @Test
    fun `rejects coordinates on an action that must not have them`() {
        assertTrue(
            parse("""{"action":"WAIT","point":{"x":0.2,"y":0.2},"confidence":0.5,"explanation":"x"}""").isFailure,
        )
    }

    @Test
    fun `rejects non json output`() {
        assertTrue(parse("I think you should tap the screen.").isFailure)
        assertTrue(parse("```json\n{\"action\":\"WAIT\"}\n```").isFailure)
    }

    @Test
    fun `sanitises and truncates the explanation`() {
        val long = "a".repeat(500)
        val decision = parse("""{"action":"WAIT","confidence":0.5,"explanation":"$long"}""").getOrThrow()
        assertEquals(DecisionParser.MAX_EXPLANATION_LENGTH, decision.explanation.length)
    }

    @Test
    fun `strips control characters from the explanation`() {
        val decision = parse("""{"action":"WAIT","confidence":0.5,"explanation":"ok\u0007\u0000"}""").getOrThrow()
        assertEquals("ok", decision.explanation)
    }

    @Test
    fun `blank explanations are replaced rather than failing the decision`() {
        val decision = parse("""{"action":"WAIT","confidence":0.5,"explanation":"   "}""").getOrThrow()
        assertEquals("(no explanation)", decision.explanation)
    }

    @Test
    fun `unknown target kinds degrade to UNKNOWN instead of failing`() {
        val decision = parse(
            """{"action":"WAIT","confidence":0.5,"explanation":"x","target":{"kind":"DRAGON"}}""",
        ).getOrThrow()
        assertEquals(EntityKind.UNKNOWN, decision.target?.kind)
    }

    @Test
    fun `action names are matched case insensitively`() {
        assertEquals(ActionType.HOLD, parse(
            """{"action":" hold ","point":{"x":0.5,"y":0.5},"duration_ms":100,"confidence":0.5,"explanation":"x"}""",
        ).getOrThrow().actionType)
    }
}

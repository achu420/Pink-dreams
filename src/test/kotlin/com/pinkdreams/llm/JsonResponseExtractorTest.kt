package com.pinkdreams.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for the reasoning-prose leak. The two "captured" cases below
 * are verbatim from live OpenRouter responses (deepseek-v4.1-flash) logged
 * during baseline verification: the model emitted its reasoning as plain prose
 * in `content` and then the JSON. Because every structured side-channel parser
 * treats a parse failure as "nothing found", this silently discarded real
 * extractions with no error anywhere.
 */
class JsonResponseExtractorTest {

    @Test
    fun `captured response with leading reasoning prose yields the json`() {
        val raw = "t recognize any explicit user facts. The user says \"I am planning dinner. What should I " +
            "avoid putting in it?\" ? no durable info about them (no name, location, dietary restrictions " +
            "stated). The assistant mentions coriander but that's not from user. So return empty facts." +
            "{\"facts\": []}"

        assertEquals("""{"facts": []}""", JsonResponseExtractor.extractJsonObject(raw))
    }

    @Test
    fun `captured response with json mentioned mid-prose and again at the end yields the last`() {
        val raw = "i'mta\"user\" message. The user said: \"Sorry - honestly I am barely holding it together " +
            "today.\" This is a temporary state/mood. Rules say do NOT extract temporary states, moods. " +
            "So nothing to remember. Respond {\"facts\": []}.{\"facts\": []}"

        assertEquals("""{"facts": []}""", JsonResponseExtractor.extractJsonObject(raw))
    }

    @Test
    fun `a clean json response is returned unchanged`() {
        val raw = """{"skillKey": "emotional_support"}"""
        assertEquals(raw, JsonResponseExtractor.extractJsonObject(raw))
    }

    @Test
    fun `markdown fenced json is unwrapped`() {
        val raw = "```json\n{\"facts\": [{\"fact\": \"user lives in Pune\"}]}\n```"
        assertEquals("""{"facts": [{"fact": "user lives in Pune"}]}""", JsonResponseExtractor.extractJsonObject(raw))
    }

    @Test
    fun `braces inside string values do not break brace matching`() {
        val raw = """Reasoning about it. {"facts": [{"fact": "user writes code like if (x) { y }", "factType": "habit"}]}"""

        assertEquals(
            """{"facts": [{"fact": "user writes code like if (x) { y }", "factType": "habit"}]}""",
            JsonResponseExtractor.extractJsonObject(raw),
        )
    }

    @Test
    fun `escaped quotes inside string values do not break brace matching`() {
        val raw = """Thinking. {"facts": [{"fact": "user said \"no coriander\" firmly"}]}"""

        assertEquals(
            """{"facts": [{"fact": "user said \"no coriander\" firmly"}]}""",
            JsonResponseExtractor.extractJsonObject(raw),
        )
    }

    @Test
    fun `nested objects are matched to the outermost brace`() {
        val raw = """prose {"a": {"b": {"c": 1}}} trailing"""
        assertEquals("""{"a": {"b": {"c": 1}}}""", JsonResponseExtractor.extractJsonObject(raw))
    }

    @Test
    fun `a response with no json at all yields null so the caller can fail as before`() {
        assertNull(JsonResponseExtractor.extractJsonObject("I am not going to answer that."))
        assertNull(JsonResponseExtractor.extractJsonObject(""))
    }

    @Test
    fun `an unterminated object yields null rather than a truncated span`() {
        // A response cut off by max_tokens must not be silently treated as complete.
        assertNull(JsonResponseExtractor.extractJsonObject("""reasoning {"facts": [{"fact": "user li"""))
    }
}

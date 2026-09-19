package com.pinkdreams.llm

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for the long-standing intermittent "Failed to parse OpenRouter
 * response" failure (first reported during Skill Foundation, re-observed in
 * every subsequent phase's live verification).
 *
 * Root cause: reasoning-capable models return `"content": null` alongside a
 * populated `"reasoning"` field and `finish_reason: "length"` whenever
 * reasoning tokens exhaust max_tokens before any final content is emitted.
 * OpenRouterResponseMessage.content was declared non-nullable, so kotlinx threw
 * and the client swallowed it into a generic message. Frequency tracked the
 * per-call token budget exactly (intent discovery at 64 tokens failed nearly
 * always; main generation with a large budget rarely did).
 *
 * The bodies below are trimmed from ACTUAL OpenRouter responses captured from
 * the live API, not hand-invented shapes.
 */
class OpenRouterReasoningModelParseRegressionTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Real shape: reasoning exhausted the budget, content is null, finish_reason=length. */
    private val reasoningTruncatedBody = """
        {"id":"gen-1789767194-DAVoD0vjGI211Y3C8CkE","object":"chat.completion","created":1789767194,
         "model":"deepseek/deepseek-v4.1-flash","provider":"Parasail","system_fingerprint":null,
         "service_tier":null,
         "choices":[{"index":0,"logprobs":null,"finish_reason":"length","native_finish_reason":"length",
           "message":{"role":"assistant","content":null,"refusal":null,
             "reasoning":"We need to classify the intent. The user says they want company...",
             "reasoning_details":[{"type":"reasoning.text","text":"We need to classify...","format":"unknown","index":0}]}}],
         "usage":{"prompt_tokens":51,"completion_tokens":64,"total_tokens":115,"cost":0.0003,
           "is_byok":false,"prompt_tokens_details":{"cached_tokens":0},
           "completion_tokens_details":{"reasoning_tokens":64}}}
    """.trimIndent()

    /** Real shape: reasoning finished AND content was produced — the success path. */
    private val reasoningCompleteBody = """
        {"id":"gen-1789767215-53j3EGymBUJAvMMskUEx","object":"chat.completion","created":1789767215,
         "model":"deepseek/deepseek-v4.1-flash","provider":"Parasail","system_fingerprint":null,
         "choices":[{"index":0,"logprobs":null,"finish_reason":"stop","native_finish_reason":"stop",
           "message":{"role":"assistant","content":"{\"skillKey\":\"companionship\"}","refusal":null,
             "reasoning":"The user wants company, so companionship fits.",
             "reasoning_details":[{"type":"reasoning.text","text":"The user wants company","format":"unknown","index":0}]}}],
         "usage":{"prompt_tokens":51,"completion_tokens":337,"total_tokens":388,
           "completion_tokens_details":{"reasoning_tokens":322}}}
    """.trimIndent()

    @Test
    fun `a reasoning-truncated response with null content deserializes instead of throwing`() {
        val parsed = json.decodeFromString<OpenRouterResponse>(reasoningTruncatedBody)

        val message = parsed.choices.single().message
        assertNull(message.content, "content is legitimately null on a reasoning-truncated response")
        assertTrue(!message.reasoning.isNullOrBlank(), "reasoning is where the model's tokens actually went")
        assertEquals("length", parsed.choices.single().finishReason)
    }

    @Test
    fun `a completed reasoning response still parses and exposes the real content`() {
        val parsed = json.decodeFromString<OpenRouterResponse>(reasoningCompleteBody)

        assertEquals("""{"skillKey":"companionship"}""", parsed.choices.single().message.content)
        assertEquals("stop", parsed.choices.single().finishReason)
    }

    @Test
    fun `unknown provider fields never break deserialization`() {
        // refusal, reasoning_details, native_finish_reason, logprobs, service_tier,
        // cost/prompt_tokens_details/completion_tokens_details are all present above
        // and none are declared in our DTOs.
        json.decodeFromString<OpenRouterResponse>(reasoningTruncatedBody)
        json.decodeFromString<OpenRouterResponse>(reasoningCompleteBody)
    }
}

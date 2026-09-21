package com.pinkdreams.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime Quality + Latency Verification phase.
 *
 * Live verification hit an actual production failure:
 * "OpenRouter returned no content (finish_reason=length, reasoningChars=4670,
 * completionTokens=1024)" — the primary generation call's own reasoning
 * exhausted the entire 1024-token budget before any reply content was
 * produced, failing the whole user turn. This asserts the corrected default
 * gives real headroom above that measured failure, without matching the
 * Memory Engine's much larger batch-reconciliation budget (a materially
 * different, bigger task) unconditionally.
 */
class LlmConfigDefaultBudgetTest {

    @Test
    fun `the default max output tokens has headroom above the measured live failure`() {
        val observedReasoningChars = 4670
        // ~4 characters per token is the same rough estimator this codebase
        // already uses elsewhere (CharacterTokenEstimator) for this purpose.
        val approxReasoningTokens = observedReasoningChars / 4
        val default = LlmConfig(apiKey = "k").maxOutputTokens

        assertTrue(
            default > approxReasoningTokens + 200,
            "Default budget ($default) must leave room for the observed reasoning length " +
                "(~$approxReasoningTokens tokens) plus an actual reply",
        )
    }

    @Test
    fun `the default is no longer the 1024 value that failed live`() {
        assertEquals(2048, LlmConfig(apiKey = "k").maxOutputTokens)
    }
}

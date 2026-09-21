package com.pinkdreams.config

// AMIA canonical v3 release note: default generation model changed from
// deepseek/deepseek-v4.1-flash to deepseek/deepseek-v4-flash-0731. This is the
// ONE place the model default is defined; ChatEngineFactory passes
// `llmConfig.model` into every workload's GenerationConfig (primary
// generation, intent discovery, memory extraction, memory-engine
// maintenance, continuity summarization) — none of them hardcode a model of
// their own — so this single change updates every workload consistently.
// Overridable via LLM_MODEL (unchanged mechanism) and, at runtime, via the
// admin-configurable AiRuntimeSettings (Phase ADMIN-2), which still takes
// precedence when an admin has set one.
private const val DEFAULT_MODEL = "deepseek/deepseek-v4-flash-0731"

// Runtime Quality + Latency Verification phase finding: the PRIMARY generation
// call itself failed outright during live verification —
// "OpenRouter returned no content (finish_reason=length, reasoningChars=4670,
// completionTokens=1024)" — the reasoning-capable model spent its entire
// budget "thinking" before emitting any of the actual reply. 1024 was too
// small a margin above typically-observed reasoning lengths for ordinary
// conversational turns. This does not eliminate the underlying reasoning-
// exhaustion class of failure (some prompts reasoned for 25,000+ characters
// in the same live session, on an unrelated async call — no fixed budget
// guarantees immunity), but it fixes the specific measured failure and gives
// realistic headroom for typical turns without the unbounded cost of
// matching the Memory Engine's much larger 6000-token batch-reconciliation
// budget, which faces a genuinely different (larger, multi-message) task.
private const val DEFAULT_MAX_OUTPUT_TOKENS = 2048

data class LlmConfig(
    val provider: String = "openrouter",
    val apiKey: String,
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val model: String = DEFAULT_MODEL,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val timeoutSeconds: Int = 60,
) {
    companion object {
        fun from(appConfig: AppConfig): LlmConfig {
            val apiKey = System.getenv("OPENROUTER_API_KEY") ?: "test-key-for-fake-llm"
            return LlmConfig(
                apiKey = apiKey,
                provider = System.getenv("LLM_PROVIDER") ?: "openrouter",
                endpoint = System.getenv("OPENROUTER_ENDPOINT") ?: "https://openrouter.ai/api/v1/chat/completions",
                model = System.getenv("LLM_MODEL") ?: DEFAULT_MODEL,
                maxOutputTokens = System.getenv("LLM_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: DEFAULT_MAX_OUTPUT_TOKENS,
                timeoutSeconds = System.getenv("LLM_TIMEOUT_SECONDS")?.toIntOrNull() ?: 60,
            )
        }
    }
}

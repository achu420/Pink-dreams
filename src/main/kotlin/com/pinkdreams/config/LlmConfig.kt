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

data class LlmConfig(
    val provider: String = "openrouter",
    val apiKey: String,
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val model: String = DEFAULT_MODEL,
    val maxOutputTokens: Int = 1024,
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
                maxOutputTokens = System.getenv("LLM_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: 1024,
                timeoutSeconds = System.getenv("LLM_TIMEOUT_SECONDS")?.toIntOrNull() ?: 60,
            )
        }
    }
}

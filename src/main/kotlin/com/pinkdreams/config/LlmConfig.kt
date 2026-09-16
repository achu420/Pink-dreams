package com.pinkdreams.config

data class LlmConfig(
    val provider: String = "openrouter",
    val apiKey: String,
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val model: String = "deepseek/deepseek-v4.1-flash",
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
                model = System.getenv("LLM_MODEL") ?: "deepseek/deepseek-v4.1-flash",
                maxOutputTokens = System.getenv("LLM_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: 1024,
                timeoutSeconds = System.getenv("LLM_TIMEOUT_SECONDS")?.toIntOrNull() ?: 60,
            )
        }
    }
}

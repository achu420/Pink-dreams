package com.pinkdreams.config

data class ImageProviderConfig(
    val provider: String = "openrouter",
    val apiKey: String,
    val endpoint: String = "https://openrouter.ai/api/v1/images",
    val imageModel: String = "openai/gpt-image-2.5-flare",
    val connectTimeoutSeconds: Int = 10,
    val readTimeoutSeconds: Int = 300,
    val outputFormat: String = "b64_json",
) {
    companion object {
        fun from(appConfig: AppConfig): ImageProviderConfig {
            val apiKey = System.getenv("OPENROUTER_API_KEY")
                ?: throw IllegalStateException(
                    "OPENROUTER_API_KEY environment variable is required for OpenRouter image provider"
                )
            return ImageProviderConfig(
                apiKey = apiKey,
                provider = System.getenv("IMAGE_PROVIDER") ?: "openrouter",
                endpoint = System.getenv("OPENROUTER_IMAGE_ENDPOINT") ?: "https://openrouter.ai/api/v1/images",
                imageModel = System.getenv("OPENROUTER_IMAGE_MODEL") ?: "openai/gpt-image-2.5-flare",
                connectTimeoutSeconds = System.getenv("IMAGE_CONNECT_TIMEOUT_SECONDS")?.toIntOrNull() ?: 10,
                readTimeoutSeconds = System.getenv("IMAGE_READ_TIMEOUT_SECONDS")?.toIntOrNull() ?: 300,
                outputFormat = System.getenv("OPENROUTER_IMAGE_OUTPUT_FORMAT") ?: "b64_json",
            )
        }
    }
}

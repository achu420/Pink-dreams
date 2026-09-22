package com.pinkdreams.imaging.provider.openrouter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the live OpenRouter image-model catalog (Task 26).
 * Secrets never logged; API key only used in Authorization header.
 */
class OpenRouterImageModelCatalog(
    private val apiKey: String,
    private val catalogUrl: String = "https://openrouter.ai/api/v1/images/models",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CatalogResponse(val data: List<CatalogModel> = emptyList())

    @Serializable
    data class CatalogModel(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        val architecture: Architecture? = null,
        val supports_streaming: Boolean? = null,
    )

    @Serializable
    data class Architecture(
        val input_modalities: List<String> = emptyList(),
        val output_modalities: List<String> = emptyList(),
    )

    data class DiscoveredModel(
        val modelId: String,
        val displayName: String,
        val description: String?,
        val acceptsImageInput: Boolean,
        val acceptsTextInput: Boolean,
        val referenceLikelySupported: Boolean,
    )

    fun fetch(): List<DiscoveredModel> {
        require(apiKey.isNotBlank()) { "OPENROUTER_API_KEY required for catalog discovery" }
        val conn = (URL(catalogUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("OpenRouter catalog HTTP $code: ${body.take(200)}")
            }
            val parsed = json.decodeFromString(CatalogResponse.serializer(), body)
            return parsed.data.map { m ->
                val inputs = m.architecture?.input_modalities.orEmpty().map { it.lowercase() }
                val acceptsImage = inputs.contains("image")
                DiscoveredModel(
                    modelId = m.id,
                    displayName = m.name ?: m.id.substringAfterLast('/'),
                    description = m.description,
                    acceptsImageInput = acceptsImage,
                    acceptsTextInput = inputs.contains("text") || inputs.isEmpty(),
                    referenceLikelySupported = acceptsImage,
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Preferred evaluation shortlist for Task 25 (updated when catalog changes). */
        val SELECTED_FOR_EVALUATION: List<String> = listOf(
            "openai/gpt-image-2.5-flare",
            "openai/gpt-image-2.5-sunburst",
            "bytedance-seed/seedream-5-0-pro",
        )
    }
}

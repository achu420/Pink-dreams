package com.pinkdreams.imaging.provider.openrouter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live OpenRouter `/api/v1/models` pricing snapshot. Public list prices only —
 * never used as the benchmark's actual cost field.
 */
class OpenRouterModelsPricingCatalog(
    private val apiKey: String,
    private val modelsUrl: String = "https://openrouter.ai/api/v1/models",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ModelsResponse(val data: List<ModelRow> = emptyList())

    @Serializable
    data class ModelRow(
        val id: String,
        val name: String? = null,
        val pricing: Pricing? = null,
    )

    @Serializable
    data class Pricing(
        val prompt: String? = null,
        val completion: String? = null,
        val image: String? = null,
        val image_output: String? = null,
        val request: String? = null,
    )

    data class PricingSnapshot(
        val modelId: String,
        val prompt: String?,
        val completion: String?,
        val image: String?,
        val imageOutput: String?,
        val request: String?,
        val source: String,
    )

    fun fetch(): Map<String, PricingSnapshot> {
        require(apiKey.isNotBlank()) { "OPENROUTER_API_KEY required for pricing discovery" }
        val conn = (URL(modelsUrl).openConnection() as HttpURLConnection).apply {
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
                throw IllegalStateException("OpenRouter models HTTP $code: ${body.take(200)}")
            }
            return parseBody(body)
        } finally {
            conn.disconnect()
        }
    }

    fun parseBody(body: String): Map<String, PricingSnapshot> {
        val parsed = json.decodeFromString(ModelsResponse.serializer(), body)
        return parsed.data.associate { row ->
            row.id to PricingSnapshot(
                modelId = row.id,
                prompt = row.pricing?.prompt,
                completion = row.pricing?.completion,
                image = row.pricing?.image,
                imageOutput = row.pricing?.image_output,
                request = row.pricing?.request,
                source = "OPENROUTER_MODELS_API",
            )
        }
    }
}

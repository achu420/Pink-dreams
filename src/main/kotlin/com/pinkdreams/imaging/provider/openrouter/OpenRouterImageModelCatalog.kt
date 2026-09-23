package com.pinkdreams.imaging.provider.openrouter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the live OpenRouter image-model catalog (Task 26 / Task 30).
 * Secrets never logged; API key only used in Authorization header.
 */
open class OpenRouterImageModelCatalog(
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
        val supported_parameters: JsonObject? = null,
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
        val maxCandidateCount: Int? = null,
        val maxReferenceImages: Int? = null,
        val supportedResolutions: List<String> = emptyList(),
        val supportedAspectRatios: List<String> = emptyList(),
        val supportedParameterNames: List<String> = emptyList(),
        val rawSupportedParametersJson: String? = null,
    )

    open fun fetch(): List<DiscoveredModel> {
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
            return parseCatalogBody(body)
        } finally {
            conn.disconnect()
        }
    }

    fun parseCatalogBody(body: String): List<DiscoveredModel> {
        val parsed = json.decodeFromString(CatalogResponse.serializer(), body)
        return parsed.data.map { m -> toDiscovered(m) }
    }

    private fun toDiscovered(m: CatalogModel): DiscoveredModel {
        val inputs = m.architecture?.input_modalities.orEmpty().map { it.lowercase() }
        val acceptsImage = inputs.contains("image")
        val params = m.supported_parameters
        val nMax = rangeMax(params, "n")
        val refMax = rangeMax(params, "input_references")
        val resolutions = enumValues(params, "resolution")
        val aspects = enumValues(params, "aspect_ratio")
        return DiscoveredModel(
            modelId = m.id,
            displayName = m.name ?: m.id.substringAfterLast('/'),
            description = m.description,
            acceptsImageInput = acceptsImage,
            acceptsTextInput = inputs.contains("text") || inputs.isEmpty(),
            referenceLikelySupported = acceptsImage && (refMax == null || refMax > 0),
            maxCandidateCount = nMax,
            maxReferenceImages = refMax,
            supportedResolutions = resolutions,
            supportedAspectRatios = aspects,
            supportedParameterNames = params?.keys?.toList().orEmpty(),
            rawSupportedParametersJson = params?.toString(),
        )
    }

    private fun rangeMax(params: JsonObject?, field: String): Int? {
        val obj = params?.get(field)?.jsonObject ?: return null
        return obj["max"]?.jsonPrimitive?.intOrNull
    }

    private fun enumValues(params: JsonObject?, field: String): List<String> {
        val obj = params?.get(field)?.jsonObject ?: return emptyList()
        val values = obj["values"]?.jsonArray ?: return emptyList()
        return values.mapNotNull { it.jsonPrimitive.content }
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

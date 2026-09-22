package com.pinkdreams.imaging.provider.openrouter

import com.pinkdreams.imaging.provider.*
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.ObjectStorage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit

@Serializable
data class OpenRouterImageRequest(
    val model: String,
    val prompt: String,
    val n: Int = 1,
    val resolution: String? = null,
    val aspect_ratio: String? = null,
    val quality: String = "auto",
    val output_format: String = "png",
    val input_references: List<OpenRouterReference>? = null,
)

@Serializable
data class OpenRouterReference(
    val type: String,
    val content: String,
)

@Serializable
data class OpenRouterImageResponse(
    val data: List<OpenRouterImageData>? = null,
    val error: OpenRouterImageError? = null,
)

@Serializable
data class OpenRouterImageData(
    @SerialName("b64_json")
    val b64Json: String? = null,
    val url: String? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
)

@Serializable
data class OpenRouterImageError(
    val code: String? = null,
    val message: String? = null,
    val type: String? = null,
)

class ReferenceResolutionException(message: String) : Exception(message)

class OpenRouterImageProvider(
    private val apiKey: String,
    private val endpoint: String = "https://openrouter.ai/api/v1/images",
    private val imageModel: String = "openai/gpt-image-2.5-flare",
    private val connectTimeoutSeconds: Int = 10,
    private val readTimeoutSeconds: Int = 300,
    private val outputFormat: String = "png",
    private val objectStorage: ObjectStorage? = null,
    private val referenceImageRepository: ReferenceImageRepository? = null,
) : ImageProvider {

    override val providerId: String = "openrouter"

    // Verified capability profile for openai/gpt-image-2.5-flare
    // Source: https://openrouter.ai/api/v1/images/models
    override val capabilities: ProviderCapabilities = capabilitiesFor(imageModel)

    private fun capabilitiesFor(model: String): ProviderCapabilities {
        // Prefer known verified profiles; for other models assume conservative defaults
        // unless the model family is known to accept image inputs (gpt-image / seedream / gemini image).
        return when {
            model == "openai/gpt-image-2.5-flare" || model == "openai/gpt-image-2.5-sunburst" -> ProviderCapabilities(
                maxCandidateCount = 10,
                supportsReferences = true,
                supportsMultipleReferences = true,
                supportedAspectRatios = listOf("1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16", "21:9"),
                minWidthPx = 256,
                maxWidthPx = 2048,
                minHeightPx = 256,
                maxHeightPx = 2048,
                supportsCancellation = false,
                supportsIdempotency = true,
            )
            model.startsWith("openai/gpt-image") ||
                model.contains("seedream", ignoreCase = true) ||
                model.contains("gemini", ignoreCase = true) && model.contains("image", ignoreCase = true) ||
                model.startsWith("qwen/qwen-image") ||
                model.startsWith("microsoft/mai-image") ||
                model.startsWith("meta/muse") ||
                model.startsWith("x-ai/grok-imagine") -> ProviderCapabilities(
                maxCandidateCount = 4,
                supportsReferences = true,
                supportsMultipleReferences = true,
                supportedAspectRatios = listOf("1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16"),
                minWidthPx = 256,
                maxWidthPx = 2048,
                minHeightPx = 256,
                maxHeightPx = 2048,
                supportsCancellation = false,
                supportsIdempotency = false,
            )
            else -> ProviderCapabilities(
                maxCandidateCount = 1,
                supportsReferences = false,
                supportsMultipleReferences = false,
                supportedAspectRatios = listOf("1:1"),
                minWidthPx = 256,
                maxWidthPx = 2048,
                minHeightPx = 256,
                maxHeightPx = 2048,
                supportsCancellation = false,
                supportsIdempotency = false,
            )
        }
    }

    private fun effectiveModel(request: GenerationRequest): String =
        request.modelId?.takeIf { it.isNotBlank() } ?: imageModel

    private val json = Json { ignoreUnknownKeys = true }
    private val requestCache = mutableMapOf<String, Pair<OpenRouterImageResponse, List<ByteArray>>>()

    override suspend fun submit(request: GenerationRequest): GenerationResult {
        val validation = request.validate()
        if (!validation.valid) {
            return GenerationResult(
                jobHandle = ProviderJobHandle(providerId, "invalid"),
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "VALIDATION_ERROR",
                    message = validation.errors.joinToString("; "),
                    retryable = false,
                ),
            )
        }

        // Validate candidate count against model capability (respect per-request model override)
        val caps = capabilitiesFor(effectiveModel(request))
        if (request.candidateCount > caps.maxCandidateCount) {
            return GenerationResult(
                jobHandle = ProviderJobHandle(providerId, "invalid"),
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "VALIDATION_ERROR",
                    message = "Model supports maximum ${caps.maxCandidateCount} candidates, but ${request.candidateCount} requested",
                    retryable = false,
                ),
            )
        }

        // Validate aspect ratio if provided
        if (request.aspectRatio != null && !caps.supportedAspectRatios.contains(request.aspectRatio)) {
            return GenerationResult(
                jobHandle = ProviderJobHandle(providerId, "invalid"),
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "VALIDATION_ERROR",
                    message = "Model does not support aspect ratio '${request.aspectRatio}'. Supported: ${caps.supportedAspectRatios.joinToString(", ")}",
                    retryable = false,
                ),
            )
        }

        // Validate references can be resolved if provided
        if (request.references.isNotEmpty()) {
            if (!caps.supportsReferences) {
                return GenerationResult(
                    jobHandle = ProviderJobHandle(providerId, "invalid"),
                    status = GenerationStatus.FAILED,
                    error = GenerationError(
                        code = "VALIDATION_ERROR",
                        message = "Model does not support reference images",
                        retryable = false,
                    ),
                )
            }
            if (request.references.size > 16) {  // Verified limit for gpt-image models
                return GenerationResult(
                    jobHandle = ProviderJobHandle(providerId, "invalid"),
                    status = GenerationStatus.FAILED,
                    error = GenerationError(
                        code = "VALIDATION_ERROR",
                        message = "Model supports maximum 16 reference images, but ${request.references.size} provided",
                        retryable = false,
                    ),
                )
            }
        }

        return try {
            val externalJobId = UUID.randomUUID().toString()
            val openRouterRequest = try {
                buildOpenRouterRequest(request)
            } catch (e: ReferenceResolutionException) {
                return GenerationResult(
                    jobHandle = ProviderJobHandle(providerId, externalJobId),
                    status = GenerationStatus.FAILED,
                    error = GenerationError(
                        code = "REFERENCE_RESOLUTION_FAILED",
                        message = e.message ?: "Failed to resolve reference images",
                        retryable = false,
                    ),
                )
            }
            val response = makeImageRequest(openRouterRequest)

            if (response.error != null) {
                val normalized = normalizeError(response.error!!)
                return GenerationResult(
                    jobHandle = ProviderJobHandle(providerId, externalJobId),
                    status = GenerationStatus.FAILED,
                    error = normalized,
                )
            }

            if (response.data == null || response.data!!.isEmpty()) {
                return GenerationResult(
                    jobHandle = ProviderJobHandle(providerId, externalJobId),
                    status = GenerationStatus.FAILED,
                    error = GenerationError(
                        code = "NO_DATA",
                        message = "OpenRouter returned no image data",
                        retryable = true,
                    ),
                )
            }

            val imageBytes = mutableListOf<ByteArray>()
            for (data in response.data!!) {
                val bytes = if (data.b64Json != null) {
                    Base64.getDecoder().decode(data.b64Json)
                } else if (data.url != null) {
                    retrieveImageFromUrl(data.url)
                } else {
                    ByteArray(0)
                }
                imageBytes.add(bytes)
            }

            requestCache[externalJobId] = Pair(response, imageBytes)

            val candidates = imageBytes.mapIndexed { index, bytes ->
                GeneratedCandidate(
                    id = UUID.randomUUID(),
                    imageUrl = null,
                    imageData = bytes,
                    widthPx = request.widthPx ?: 1024,
                    heightPx = request.heightPx ?: 1024,
                    checksum = calculateChecksum(bytes),
                )
            }

            GenerationResult(
                jobHandle = ProviderJobHandle(providerId, externalJobId),
                status = GenerationStatus.COMPLETED,
                candidates = candidates,
            )
        } catch (e: Exception) {
            GenerationResult(
                jobHandle = ProviderJobHandle(providerId, "error-${UUID.randomUUID()}"),
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "REQUEST_FAILED",
                    message = e.message ?: "Unknown error during image generation request",
                    retryable = true,
                ),
            )
        }
    }

    override suspend fun getStatus(jobHandle: ProviderJobHandle): GenerationResult {
        if (jobHandle.providerIdentifier != providerId) {
            return GenerationResult(
                jobHandle = jobHandle,
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "INVALID_PROVIDER",
                    message = "Job handle provider mismatch",
                    retryable = false,
                ),
            )
        }

        val cached = requestCache[jobHandle.externalJobId]
        return if (cached != null) {
            val (response, imageBytes) = cached
            GenerationResult(
                jobHandle = jobHandle,
                status = GenerationStatus.COMPLETED,
                candidates = imageBytes.mapIndexed { index, bytes ->
                    GeneratedCandidate(
                        id = UUID.randomUUID(),
                        imageData = bytes,
                        checksum = calculateChecksum(bytes),
                    )
                },
            )
        } else {
            GenerationResult(
                jobHandle = jobHandle,
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "NOT_FOUND",
                    message = "Job not found",
                    retryable = true,
                ),
            )
        }
    }

    override suspend fun getResult(jobHandle: ProviderJobHandle): GenerationResult {
        return getStatus(jobHandle)
    }

    override suspend fun cancel(jobHandle: ProviderJobHandle): Boolean {
        return false
    }

    private fun buildOpenRouterRequest(request: GenerationRequest): OpenRouterImageRequest {
        val references = if (request.references.isNotEmpty()) {
            if (objectStorage == null || referenceImageRepository == null) {
                throw ReferenceResolutionException("Reference images provided but ObjectStorage/ReferenceImageRepository not available")
            }

            request.references.map { ref ->
                val refImage = referenceImageRepository.findById(ref.referenceImageId)
                    ?: throw ReferenceResolutionException("Reference image not found: ${ref.referenceImageId}")

                val storageObj = objectStorage.retrieve(refImage.storageKey)
                    ?: throw ReferenceResolutionException("Reference object not found in storage: ${refImage.storageKey}")

                val base64Data = Base64.getEncoder().encodeToString(storageObj.content)
                val dataUrl = "data:${storageObj.contentType};base64,$base64Data"

                OpenRouterReference(
                    type = ref.role.lowercase(),
                    content = dataUrl,
                )
            }
        } else {
            null
        }

        return OpenRouterImageRequest(
            model = effectiveModel(request),
            prompt = request.prompt,
            n = request.candidateCount,
            resolution = determineResolution(request.widthPx, request.heightPx),
            aspect_ratio = request.aspectRatio ?: determineAspectRatio(request.widthPx, request.heightPx),
            quality = "auto",
            output_format = normalizeOutputFormat(outputFormat),
            input_references = references,
        )
    }

    /**
     * OpenRouter expects resolution tiers: 512 | 1K | 2K | 4K
     * (not legacy WxH strings such as 1024x1024).
     */
    private fun determineResolution(widthPx: Int?, heightPx: Int?): String? {
        if (widthPx == null && heightPx == null) return null
        val maxSide = maxOf(widthPx ?: 0, heightPx ?: 0)
        return when {
            maxSide <= 0 -> null
            maxSide <= 768 -> "512"
            maxSide <= 1536 -> "1K"
            maxSide <= 3072 -> "2K"
            else -> "4K"
        }
    }

    private fun determineAspectRatio(widthPx: Int?, heightPx: Int?): String? {
        if (widthPx == null || heightPx == null || widthPx <= 0 || heightPx <= 0) return null
        if (widthPx == heightPx) return "1:1"
        // Prefer a small set of common ratios; otherwise omit and let the provider decide.
        val ratio = widthPx.toDouble() / heightPx.toDouble()
        return when {
            kotlin.math.abs(ratio - 16.0 / 9.0) < 0.08 -> "16:9"
            kotlin.math.abs(ratio - 9.0 / 16.0) < 0.08 -> "9:16"
            kotlin.math.abs(ratio - 4.0 / 3.0) < 0.08 -> "4:3"
            kotlin.math.abs(ratio - 3.0 / 4.0) < 0.08 -> "3:4"
            kotlin.math.abs(ratio - 3.0 / 2.0) < 0.08 -> "3:2"
            kotlin.math.abs(ratio - 2.0 / 3.0) < 0.08 -> "2:3"
            else -> null
        }
    }

    private fun normalizeOutputFormat(configured: String): String {
        return when (configured.lowercase()) {
            "png", "jpeg", "jpg", "webp", "svg" -> if (configured.equals("jpg", true)) "jpeg" else configured.lowercase()
            // Legacy config value from earlier OpenRouter drafts; response still uses b64_json payload field.
            "b64_json" -> "png"
            else -> "png"
        }
    }

    private fun makeImageRequest(request: OpenRouterImageRequest): OpenRouterImageResponse {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "PinkDreams/1.0")
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(connectTimeoutSeconds.toLong()).toInt()
            connection.readTimeout = TimeUnit.SECONDS.toMillis(readTimeoutSeconds.toLong()).toInt()

            val requestJson = buildImageRequestJson(request)
            connection.doOutput = true
            connection.outputStream.use { it.write(requestJson.toByteArray()) }

            val statusCode = connection.responseCode

            val responseBody = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            return json.decodeFromString<OpenRouterImageResponse>(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildImageRequestJson(request: OpenRouterImageRequest): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"model\":\"").append(request.model).append("\"")
        sb.append(",\"prompt\":\"").append(escapeJson(request.prompt)).append("\"")
        sb.append(",\"n\":").append(request.n)
        sb.append(",\"quality\":\"").append(request.quality).append("\"")
        sb.append(",\"output_format\":\"").append(request.output_format).append("\"")
        if (request.resolution != null) {
            sb.append(",\"resolution\":\"").append(request.resolution).append("\"")
        }
        if (request.aspect_ratio != null) {
            sb.append(",\"aspect_ratio\":\"").append(request.aspect_ratio).append("\"")
        }
        if (request.input_references != null && request.input_references.isNotEmpty()) {
            sb.append(",\"input_references\":[")
            sb.append(request.input_references.joinToString(",") { ref ->
                "{\"type\":\"${ref.type}\",\"content\":\"${escapeJson(ref.content)}\"}"
            })
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun calculateChecksum(imageBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(imageBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun retrieveImageFromUrl(urlString: String): ByteArray {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(30).toInt()
            connection.readTimeout = TimeUnit.SECONDS.toMillis(30).toInt()
            return connection.inputStream.readBytes()
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeError(error: OpenRouterImageError): GenerationError {
        return when (error.code?.lowercase()) {
            "authentication_error", "invalid_api_key", "invalid_request_error" ->
                GenerationError(
                    code = "AUTH_FAILED",
                    message = error.message ?: "Authentication failed",
                    retryable = false,
                )
            "rate_limit_exceeded", "quota_exceeded" ->
                GenerationError(
                    code = "RATE_LIMITED",
                    message = error.message ?: "Rate limit exceeded",
                    retryable = true,
                )
            "server_error", "internal_error", "service_unavailable" ->
                GenerationError(
                    code = "PROVIDER_ERROR",
                    message = error.message ?: "Provider service error",
                    retryable = true,
                )
            "timeout" ->
                GenerationError(
                    code = "TIMEOUT",
                    message = error.message ?: "Request timeout",
                    retryable = true,
                )
            else ->
                GenerationError(
                    code = error.code ?: "UNKNOWN_ERROR",
                    message = error.message ?: "Unknown error",
                    retryable = true,
                )
        }
    }
}

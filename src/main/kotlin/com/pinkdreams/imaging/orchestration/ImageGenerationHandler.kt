package com.pinkdreams.imaging.orchestration

import com.pinkdreams.imaging.job.ImageJob
import com.pinkdreams.imaging.job.ImageJobResult
import com.pinkdreams.imaging.provider.GenerationRequest
import com.pinkdreams.imaging.provider.GenerationStatus
import com.pinkdreams.imaging.provider.ImageProvider
import com.pinkdreams.imaging.provider.ReferenceInput
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.ObjectStorage
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Handles image generation jobs submitted via IMG-8 orchestrator.
 *
 * Responsibility:
 * - reconstruct generation request from job payload
 * - resolve reference images
 * - invoke configured image provider
 * - handle provider success/failure
 * - return appropriate job result
 *
 * Does NOT make decisions about:
 * - provider selection (configured)
 * - image ranking/quality
 * - publishing/storage (beyond job metadata)
 */
/**
 * Note: This handler is designed to be called from an async context (worker thread/coroutine).
 * The IMG-4 worker will need to be async-aware to invoke this properly.
 * For now, use this as a helper component that processes job payloads.
 */
class ImageGenerationHandler(
    private val imageProvider: ImageProvider,
    private val referenceImageRepository: ReferenceImageRepository,
    private val objectStorage: ObjectStorage,
    private val generatedCandidateRepository: GeneratedCandidateRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asynchronously handle image generation job.
     * Must be called from an async context (coroutine/thread pool).
     */
    suspend fun handleAsync(job: ImageJob): ImageJobResult {
        return try {
            // Parse job payload
            val payload = json.parseToJsonElement(job.requestPayload).jsonObject

            // Reconstruct generation request with actual reference roles
            val generationRequest = reconstructGenerationRequestWithActualRoles(payload)

            // Submit to configured provider
            val providerResult = imageProvider.submit(generationRequest)

            // Handle provider result
            when (providerResult.status) {
                GenerationStatus.QUEUED, GenerationStatus.RUNNING -> {
                    // Provider accepted the request, job will poll for updates
                    ImageJobResult.Success(
                        metadata = buildMetadata(
                            providerJobHandle = providerResult.jobHandle.externalJobId,
                            providerIdentifier = providerResult.jobHandle.providerIdentifier,
                            candidateCount = generationRequest.candidateCount
                        )
                    )
                }

                GenerationStatus.COMPLETED -> {
                    // Provider returned immediate result - persist candidates to storage
                    if (providerResult.candidates.isEmpty()) {
                        ImageJobResult.Failure(
                            errorMessage = "Provider returned no candidates",
                            retryable = false
                        )
                    } else {
                        persistCandidates(job.id, providerResult)
                    }
                }

                GenerationStatus.FAILED -> {
                    val error = providerResult.error
                    ImageJobResult.Failure(
                        errorMessage = error?.message ?: "Provider returned failure",
                        retryable = error?.retryable ?: true
                    )
                }

                GenerationStatus.CANCELLED -> {
                    ImageJobResult.Failure(
                        errorMessage = "Provider cancelled the request",
                        retryable = false
                    )
                }
            }
        } catch (e: Exception) {
            ImageJobResult.Failure(
                errorMessage = "Generation handler error: ${e.message}",
                retryable = true
            )
        }
    }

    internal fun persistCandidates(
        imageJobId: java.util.UUID,
        providerResult: com.pinkdreams.imaging.provider.GenerationResult,
    ): ImageJobResult {
        return try {
            val candidates = mutableListOf<String>()

            providerResult.candidates.forEachIndexed { index, candidate ->
                try {
                    // Get image bytes from provider result
                    val imageBytes = candidate.imageData
                        ?: throw IllegalStateException("Provider candidate missing image data")

                    // Calculate SHA-256 checksum from actual bytes
                    val checksum = calculateChecksum(imageBytes)

                    // Store image bytes via ObjectStorage
                    val contentType = "image/png"
                    val storageKey = "jobs/$imageJobId/candidates/$index"

                    objectStorage.store(
                        key = storageKey,
                        content = imageBytes,
                        contentType = contentType
                    )

                    // Create GeneratedCandidate record with durable metadata
                    generatedCandidateRepository.create(
                        imageJobId = imageJobId,
                        storageKey = storageKey,
                        contentType = contentType,
                        fileSize = imageBytes.size.toLong(),
                        widthPx = candidate.widthPx,
                        heightPx = candidate.heightPx,
                        checksum = checksum,
                        candidateIndex = index
                    )

                    candidates.add(checksum)
                } catch (e: Exception) {
                    // Log but don't fail entire job if one candidate fails
                    // (in production, would emit error event)
                    throw e
                }
            }

            ImageJobResult.Success(
                metadata = buildMetadata(
                    candidateCount = candidates.size,
                    checksums = candidates
                )
            )
        } catch (e: Exception) {
            ImageJobResult.Failure(
                errorMessage = "Failed to persist generated candidates: ${e.message}",
                retryable = true
            )
        }
    }

    private fun buildMetadata(
        providerJobHandle: String? = null,
        providerIdentifier: String? = null,
        candidateCount: Int? = null,
        checksums: List<String> = emptyList()
    ): String {
        val metadataMap = mutableMapOf<String, String>()
        if (providerJobHandle != null) metadataMap["providerJobHandle"] = providerJobHandle
        if (providerIdentifier != null) metadataMap["providerIdentifier"] = providerIdentifier
        if (candidateCount != null) metadataMap["candidateCount"] = candidateCount.toString()
        if (checksums.isNotEmpty()) metadataMap["checksums"] = checksums.joinToString(",")
        // Encode as JSON string
        val entries = metadataMap.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        return "{$entries}"
    }

    internal suspend fun reconstructGenerationRequestWithActualRoles(payload: JsonObject): GenerationRequest {
        val prompt = payload["prompt"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing prompt in job payload")

        val idempotencyKey = payload["idempotencyKey"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing idempotencyKey in job payload")

        val candidateCount = payload["candidateCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

        val widthPx = payload["widthPx"]?.jsonPrimitive?.content?.toIntOrNull()
        val heightPx = payload["heightPx"]?.jsonPrimitive?.content?.toIntOrNull()
        val aspectRatio = payload["aspectRatio"]?.jsonPrimitive?.content

        // Reconstruct references WITH ACTUAL ROLES from ReferenceImage
        val references = mutableListOf<ReferenceInput>()
        val selectedRefIds = mutableListOf<java.util.UUID>()

        payload["selectedReferenceIds"]?.jsonPrimitive?.content?.let {
            // Parse the selected reference IDs
            json.decodeFromString<List<String>>(it).forEach { refIdStr ->
                val refId = java.util.UUID.fromString(refIdStr)
                selectedRefIds.add(refId)

                // Look up actual ReferenceImage to get its REAL role (not index-based)
                val refImage = referenceImageRepository.findById(refId)
                    ?: throw IllegalStateException("Reference not found: $refId")

                references.add(ReferenceInput(
                    referenceImageId = refId,
                    role = refImage.role.name,  // Use actual role from IMG-3
                    weight = 1.0f
                ))
            }
        }

        // Reconstruct metadata
        val metadata = mutableMapOf<String, String>()
        payload["metadata"]?.jsonObject?.forEach { (key, value) ->
            metadata[key] = value.jsonPrimitive.content
        }

        return GenerationRequest(
            prompt = prompt,
            references = references,
            candidateCount = candidateCount,
            widthPx = widthPx,
            heightPx = heightPx,
            aspectRatio = aspectRatio,
            idempotencyKey = idempotencyKey,
            clientMetadata = metadata
        )
    }

    internal fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

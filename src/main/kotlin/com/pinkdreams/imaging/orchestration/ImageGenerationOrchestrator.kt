package com.pinkdreams.imaging.orchestration

import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.job.ImageJob
import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.persistence.repositories.ImageJobRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Orchestrates image generation workflow.
 *
 * Executes decisions already made by the caller:
 * - compiles scene intent to generation request
 * - creates durable image jobs
 * - preserves all traceability metadata
 *
 * Does NOT make decisions about:
 * - which persona/version to use (explicit input)
 * - which wardrobe/references to use (explicit input)
 * - which provider to use (configured)
 * - image ranking, publishing, quality assessment
 */
class ImageGenerationOrchestrator(
    private val compiler: PromptCompiler,
    private val jobRepository: ImageJobRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Submit an explicit image generation request.
     *
     * Returns immediately with the durable job ID.
     * Actual generation executes asynchronously.
     */
    fun submit(request: ImageGenerationRequest): ImageJob {
        // Compile scene to provider-neutral generation request
        val generationRequest = compiler.compile(
            sceneIntent = request.sceneIntent,
            visualVersion = request.personaVisualVersion,
            idempotencyKey = request.idempotencyKey
        )

        // Validate the compiled request
        val validation = generationRequest.validate()
        require(validation.valid) { "Invalid generation request: ${validation.errors.joinToString("; ")}" }

        // Build job payload preserving all traceability
        val payload = buildJobPayload(request, generationRequest)

        // Create durable job
        return jobRepository.createJob(
            personaVisualVersionId = request.personaVisualVersion.id,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = request.idempotencyKey,
            requestPayload = payload,
            maxAttempts = 3,
        )
    }

    private fun buildJobPayload(
        request: ImageGenerationRequest,
        generationRequest: com.pinkdreams.imaging.provider.GenerationRequest,
    ): String {
        val payload = buildJsonObject {
            put("version", "1")
            put("personaVisualVersionId", request.personaVisualVersion.id.toString())
            put("prompt", generationRequest.prompt)
            put("candidateCount", generationRequest.candidateCount)
            put("widthPx", generationRequest.widthPx)
            put("heightPx", generationRequest.heightPx)
            put("aspectRatio", generationRequest.aspectRatio)
            put("idempotencyKey", generationRequest.idempotencyKey)

            // Preserve selected wardrobe IDs
            put("selectedWardrobeIds", json.encodeToString(request.selectedWardrobeIds))

            // Preserve selected reference IDs with roles
            put("selectedReferenceIds", json.encodeToString(
                request.selectedReferenceIds
            ))

            // Preserve reference inputs (with roles assigned by compiler)
            put("references", json.encodeToString(generationRequest.references))

            // Preserve metadata from compilation
            put("metadata", json.encodeToString(generationRequest.clientMetadata))

            // Preserve original scene intent for reconstruction
            put("sceneIntent", json.encodeToString(request.sceneIntent))
        }

        return json.encodeToString(payload)
    }
}

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
            idempotencyKey = request.idempotencyKey,
            referenceRoles = request.referenceRoles,
            includePrivateGuide = true,
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
            generationRequest.widthPx?.let { put("widthPx", it) }
            generationRequest.heightPx?.let { put("heightPx", it) }
            generationRequest.aspectRatio?.let { put("aspectRatio", it) }
            put("idempotencyKey", generationRequest.idempotencyKey)

            put("selectedWardrobeIds", json.encodeToString(request.selectedWardrobeIds.map { it.toString() }))
            put("selectedReferenceIds", json.encodeToString(request.selectedReferenceIds.map { it.toString() }))

            put(
                "references",
                json.encodeToString(
                    generationRequest.references.map { ref ->
                        mapOf(
                            "referenceImageId" to ref.referenceImageId.toString(),
                            "role" to ref.role,
                            "weight" to ref.weight.toString(),
                        )
                    }
                )
            )

            put("metadata", buildJsonObject {
                generationRequest.clientMetadata.forEach { (k, v) -> put(k, v) }
                request.conversationId?.let { put("conversationId", it.toString()) }
                request.turnRequestId?.let { put("turnRequestId", it.toString()) }
                request.userId?.let { put("userId", it.toString()) }
            })
            request.conversationId?.let { put("conversationId", it.toString()) }
            request.turnRequestId?.let { put("turnRequestId", it.toString()) }
            request.userId?.let { put("userId", it.toString()) }

            put("sceneIntent", buildJsonObject {
                put("location", request.sceneIntent.environment.location ?: "")
                put("outfit", request.sceneIntent.appearance.outfit ?: "")
                put("presentation", request.sceneIntent.subject.presentation ?: "")
                put("expression", request.sceneIntent.subject.expression ?: "")
                put("identity", request.sceneIntent.subject.identity ?: "")
            })
        }

        return json.encodeToString(JsonObject.serializer(), payload)
    }
}

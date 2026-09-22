package com.pinkdreams.imaging.orchestration

import com.pinkdreams.imaging.compiler.SceneIntent
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import java.util.UUID

/**
 * Explicit image generation request from caller.
 *
 * All decisions are already made by the caller.
 * The orchestrator executes only these explicit inputs.
 */
data class ImageGenerationRequest(
    val personaVisualVersion: PersonaVisualVersionRepository.PersonaVisualVersion,
    val sceneIntent: SceneIntent,
    val selectedWardrobeIds: List<UUID> = emptyList(),
    val selectedReferenceIds: List<UUID> = emptyList(),
    val candidateCount: Int = sceneIntent.generation.candidateCount,
    val idempotencyKey: String,
    val conversationId: UUID? = null,
    val turnRequestId: UUID? = null,
    val userId: UUID? = null,
)

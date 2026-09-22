package com.pinkdreams.imaging.orchestration

import com.pinkdreams.imaging.compiler.Appearance
import com.pinkdreams.imaging.compiler.Environment
import com.pinkdreams.imaging.compiler.GenerationSpecs
import com.pinkdreams.imaging.compiler.MoodAndStyle
import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.compiler.SceneIntent
import com.pinkdreams.imaging.compiler.Subject
import com.pinkdreams.imaging.job.ImageJob
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import java.util.UUID

/**
 * Application service that resolves persona visual identity and submits jobs.
 *
 * Precedence (IMG-03): system constraints are enforced by validation;
 * persona visual identity comes from the active (or specified) visual version;
 * scene fields from the caller never overwrite physical guide identity in PromptCompiler.
 */
class ImageGenerationService(
    private val personaRepository: PersonaRepository,
    private val visualVersionRepository: PersonaVisualVersionRepository,
    private val wardrobeRepository: WardrobeRepository,
    private val referenceImageRepository: ReferenceImageRepository,
    private val orchestrator: ImageGenerationOrchestrator,
    private val jobRepository: ImageJobRepository,
    private val candidateRepository: GeneratedCandidateRepository,
) {

    data class CreateCommand(
        val personaId: UUID,
        val idempotencyKey: String,
        val location: String? = null,
        val outfit: String? = null,
        val expression: String? = null,
        val presentation: String? = null,
        val widthPx: Int? = 512,
        val heightPx: Int? = 512,
        val candidateCount: Int = 1,
        val visualVersionId: UUID? = null,
        val conversationId: UUID? = null,
        val turnRequestId: UUID? = null,
        val userId: UUID? = null,
        val selectedWardrobeIds: List<UUID> = emptyList(),
        val selectedReferenceIds: List<UUID> = emptyList(),
    )

    data class CreateResult(
        val job: ImageJob,
        val reusedExisting: Boolean,
    )

    fun create(command: CreateCommand): CreateResult {
        require(command.idempotencyKey.isNotBlank()) { "idempotencyKey is required" }
        require(command.idempotencyKey.length <= 200) { "idempotencyKey too long" }
        require(command.candidateCount in 1..4) { "candidateCount must be 1..4" }
        if (command.widthPx != null) require(command.widthPx in 64..2048) { "widthPx out of range" }
        if (command.heightPx != null) require(command.heightPx in 64..2048) { "heightPx out of range" }

        val persona = personaRepository.findById(command.personaId)
            ?: throw NoSuchElementException("Persona not found: ${command.personaId}")

        val identityId = personaRepository.findPersonaIdentityId(command.personaId)
            ?: throw IllegalStateException("Persona has no visual identity: ${command.personaId}")

        val version = when {
            command.visualVersionId != null ->
                visualVersionRepository.findById(command.visualVersionId)
                    ?: throw NoSuchElementException("Visual version not found")
            else ->
                visualVersionRepository.findActiveForPersonaIdentity(identityId)
                    ?: visualVersionRepository.findForPersonaIdentity(identityId).maxByOrNull { it.version }
                    ?: throw IllegalStateException("Persona has no visual versions")
        }

        require(version.personaIdentityId == identityId) {
            "Visual version does not belong to persona"
        }

        val existing = jobRepository.findByIdempotencyKey(version.id, command.idempotencyKey)

        val refs = if (command.selectedReferenceIds.isNotEmpty()) {
            command.selectedReferenceIds
        } else {
            referenceImageRepository.findForVersion(version.id)
                .filter { it.status == com.pinkdreams.visual.identity.ReferenceStatus.FINALIZED ||
                    it.status == com.pinkdreams.visual.identity.ReferenceStatus.UPLOADED }
                .take(3)
                .map { it.id }
        }

        val wardrobeIds = if (command.selectedWardrobeIds.isNotEmpty()) {
            command.selectedWardrobeIds
        } else {
            wardrobeRepository.findItemsForVersion(version.id).take(5).map { it.id }
        }

        val scene = SceneIntent(
            subject = Subject(
                identity = persona.displayName,
                presentation = command.presentation,
                expression = command.expression,
            ),
            environment = Environment(location = command.location),
            appearance = Appearance(outfit = command.outfit),
            generation = GenerationSpecs(
                candidateCount = command.candidateCount,
                widthPx = command.widthPx,
                heightPx = command.heightPx,
                selectedReferences = refs,
            ),
        )

        val validation = scene.validate()
        require(validation.valid) { validation.errors.joinToString("; ") }

        val request = ImageGenerationRequest(
            personaVisualVersion = version,
            sceneIntent = scene,
            selectedWardrobeIds = wardrobeIds,
            selectedReferenceIds = refs,
            candidateCount = command.candidateCount,
            idempotencyKey = command.idempotencyKey,
            conversationId = command.conversationId,
            turnRequestId = command.turnRequestId,
            userId = command.userId,
        )

        val job = orchestrator.submit(request)
        return CreateResult(job = job, reusedExisting = existing != null && existing.id == job.id)
    }

    fun getJob(jobId: UUID): ImageJob? = jobRepository.findById(jobId)

    fun getCandidates(jobId: UUID): List<GeneratedCandidate> =
        candidateRepository.findByImageJob(jobId)
}

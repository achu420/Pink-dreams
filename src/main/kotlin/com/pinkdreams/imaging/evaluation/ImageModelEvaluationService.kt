package com.pinkdreams.imaging.evaluation

import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.observability.ImageGenerationEventRepository
import com.pinkdreams.imaging.orchestration.GeneratedCandidate
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import java.util.UUID

/**
 * Admin model-evaluation workflow: same persona/identity/seed across 2–3 models.
 * Does NOT change production OPENROUTER_IMAGE_MODEL.
 */
class ImageModelEvaluationService(
    private val evaluationRepository: ImageEvaluationRepository,
    private val imageGenerationService: ImageGenerationService,
    private val personaRepository: PersonaRepository,
    private val visualVersionRepository: PersonaVisualVersionRepository,
    private val jobRepository: ImageJobRepository,
    private val candidateRepository: GeneratedCandidateRepository,
    private val eventRepository: ImageGenerationEventRepository? = null,
) {

    data class CreateEvaluationCommand(
        val personaId: UUID,
        val seedPrompt: String,
        val modelIds: List<String>,
        val candidateCount: Int = 4,
        val visualVersionId: UUID? = null,
        val createdBy: String? = null,
    )

    data class ModelGroup(
        val modelRow: ImageEvaluationRepository.EvaluationModelRow,
        val jobStatus: String?,
        val lastError: String?,
        val latencyMs: Long?,
        val costAvailability: String,
        val candidates: List<GeneratedCandidate>,
    )

    data class EvaluationDetail(
        val evaluation: ImageEvaluationRepository.EvaluationRow,
        val personaDisplayName: String?,
        val models: List<ModelGroup>,
        val productionModelUnchanged: Boolean,
    )

    fun listCandidateModels(): List<Map<String, String>> {
        val production = productionModel()
        val fromEnv = System.getenv("OPENROUTER_EVAL_MODELS")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val defaults = listOf(
            production,
            "openai/gpt-image-2.5-flare",
            "openai/gpt-image-2.5-sunburst",
        )
        return (fromEnv + defaults)
            .distinct()
            .map { id ->
                mapOf(
                    "provider" to (System.getenv("IMAGE_PROVIDER") ?: "openrouter"),
                    "modelId" to id,
                    "displayName" to id.substringAfterLast('/'),
                    "evaluationStatus" to if (id == production) "production" else "candidate",
                    "enabled" to "true",
                )
            }
    }

    fun create(command: CreateEvaluationCommand): EvaluationDetail {
        require(command.seedPrompt.isNotBlank()) { "seedPrompt is required" }
        require(command.modelIds.size in 2..3) { "Provide 2 or 3 modelIds for evaluation" }
        require(command.candidateCount in 1..4) { "candidateCount must be 1..4" }
        val distinctModels = command.modelIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(distinctModels.size in 2..3) { "Provide 2 or 3 distinct modelIds" }

        personaRepository.findById(command.personaId)
            ?: throw NoSuchElementException("Persona not found: ${command.personaId}")
        val identityId = personaRepository.findPersonaIdentityId(command.personaId)
            ?: throw IllegalStateException("Persona has no visual identity")
        val version = when {
            command.visualVersionId != null ->
                visualVersionRepository.findById(command.visualVersionId)
                    ?: throw NoSuchElementException("Visual version not found")
            else ->
                visualVersionRepository.findActiveForPersonaIdentity(identityId)
                    ?: visualVersionRepository.findForPersonaIdentity(identityId).maxByOrNull { it.version }
                    ?: throw IllegalStateException("Persona has no visual versions")
        }
        require(version.personaIdentityId == identityId) { "Visual version does not belong to persona" }

        val productionSnapshot = productionModel()
        val evaluation = evaluationRepository.createEvaluation(
            personaId = command.personaId,
            visualVersionId = version.id,
            seedPrompt = command.seedPrompt.trim(),
            candidateCount = command.candidateCount,
            productionModelSnapshot = productionSnapshot,
            createdBy = command.createdBy,
        )

        distinctModels.forEachIndexed { index, modelId ->
            val modelRow = evaluationRepository.addModel(
                evaluationId = evaluation.id,
                modelId = modelId,
                displayName = modelId.substringAfterLast('/'),
                provider = System.getenv("IMAGE_PROVIDER") ?: "openrouter",
                imageJobId = null,
                status = "QUEUED",
                sortOrder = index,
            )
            try {
                val result = imageGenerationService.create(
                    ImageGenerationService.CreateCommand(
                        personaId = command.personaId,
                        idempotencyKey = "eval-${evaluation.id}-m$index-${UUID.randomUUID()}",
                        seedPrompt = command.seedPrompt.trim(),
                        candidateCount = command.candidateCount,
                        visualVersionId = version.id,
                        modelId = modelId,
                    )
                )
                evaluationRepository.updateModelJob(
                    modelRowId = modelRow.id,
                    imageJobId = result.job.id,
                    status = "SUBMITTED",
                )
            } catch (e: Exception) {
                evaluationRepository.updateModelJob(
                    modelRowId = modelRow.id,
                    imageJobId = null,
                    status = "FAILED",
                )
                evaluationRepository.updateModelNotes(
                    modelRow.id,
                    ImageEvaluationRepository.ModelNotesUpdate(
                        providerRestrictionNotes = e.message?.take(500) ?: "submit failed",
                    ),
                )
            }
        }

        evaluationRepository.updateEvaluationStatus(evaluation.id, "SUBMITTED")
        return getDetail(evaluation.id)
            ?: throw IllegalStateException("Evaluation vanished after create")
    }

    fun list(limit: Int = 50): List<ImageEvaluationRepository.EvaluationRow> =
        evaluationRepository.listEvaluations(limit)

    fun getDetail(evaluationId: UUID): EvaluationDetail? {
        val evaluation = evaluationRepository.findEvaluation(evaluationId) ?: return null
        val persona = personaRepository.findById(evaluation.personaId)
        val models = evaluationRepository.listModels(evaluationId).map { modelRow ->
            val job = modelRow.imageJobId?.let { jobRepository.findById(it) }
            val candidates = modelRow.imageJobId?.let { candidateRepository.findByImageJob(it) }.orEmpty()
            val event = modelRow.imageJobId?.let { eventRepository?.latestForJob(it) }
            val latency = event?.totalLatencyMs
                ?: if (job?.startedAt != null && job.completedAt != null) {
                    java.time.Duration.between(job.startedAt, job.completedAt).toMillis()
                } else null
            val jobStatus = when {
                job != null -> job.status.name
                modelRow.status == "FAILED" -> "FAILED"
                else -> modelRow.status
            }
            // Sync model row status from job when terminal
            if (job != null && (job.status == ImageJobStatus.SUCCEEDED || job.status == ImageJobStatus.FAILED)) {
                val mapped = if (job.status == ImageJobStatus.SUCCEEDED) "SUCCEEDED" else "FAILED"
                if (modelRow.status != mapped) {
                    evaluationRepository.updateModelJob(modelRow.id, job.id, mapped)
                }
            }
            ModelGroup(
                modelRow = modelRow,
                jobStatus = jobStatus,
                lastError = job?.lastError ?: modelRow.providerRestrictionNotes,
                latencyMs = latency,
                costAvailability = "UNAVAILABLE",
                candidates = candidates,
            )
        }

        val allTerminal = models.all {
            it.jobStatus == ImageJobStatus.SUCCEEDED.name ||
                it.jobStatus == ImageJobStatus.FAILED.name ||
                it.modelRow.status == "FAILED"
        }
        if (allTerminal && evaluation.status != "COMPLETED") {
            evaluationRepository.updateEvaluationStatus(evaluationId, "COMPLETED")
        }

        return EvaluationDetail(
            evaluation = evaluationRepository.findEvaluation(evaluationId)!!,
            personaDisplayName = persona?.displayName,
            models = models,
            productionModelUnchanged = evaluation.productionModelSnapshot == productionModel() ||
                evaluation.productionModelSnapshot == null,
        )
    }

    fun addNotes(
        evaluationId: UUID,
        modelRowId: UUID,
        update: ImageEvaluationRepository.ModelNotesUpdate,
    ): ImageEvaluationRepository.EvaluationModelRow {
        val model = evaluationRepository.findModel(modelRowId)
            ?: throw NoSuchElementException("Evaluation model row not found")
        require(model.evaluationId == evaluationId) { "Model row does not belong to evaluation" }
        fun checkRating(v: Int?, name: String) {
            if (v != null) require(v in 1..5) { "$name must be 1..5" }
        }
        checkRating(update.identityConsistency, "identityConsistency")
        checkRating(update.sceneAdherence, "sceneAdherence")
        checkRating(update.poseAdherence, "poseAdherence")
        checkRating(update.wardrobeAdherence, "wardrobeAdherence")
        checkRating(update.imageQuality, "imageQuality")
        checkRating(update.naturalness, "naturalness")
        checkRating(update.artifactQuality, "artifactQuality")
        return evaluationRepository.updateModelNotes(modelRowId, update)
            ?: throw NoSuchElementException("Evaluation model row not found")
    }

    fun productionModel(): String =
        System.getenv("OPENROUTER_IMAGE_MODEL")?.takeIf { it.isNotBlank() }
            ?: "openai/gpt-image-2.5-flare"
}

private fun ImageGenerationEventRepository.latestForJob(jobId: UUID) =
    findByJob(jobId).maxByOrNull { it.createdAt }

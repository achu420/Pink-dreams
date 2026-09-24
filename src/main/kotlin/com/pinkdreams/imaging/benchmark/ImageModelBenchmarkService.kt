package com.pinkdreams.imaging.benchmark

import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.orchestration.GeneratedCandidate
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.provider.openrouter.OpenRouterImageModelCatalog
import com.pinkdreams.imaging.provider.openrouter.OpenRouterModelsPricingCatalog
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.ObjectStorage
import com.pinkdreams.visual.identity.ReferenceRole
import com.pinkdreams.visual.identity.ReferenceStatus
import java.time.LocalDateTime
import java.util.UUID

class ImageModelBenchmarkService(
    private val repository: BenchmarkRepository,
    private val imageGenerationService: ImageGenerationService,
    private val personaRepository: PersonaRepository,
    private val visualVersionRepository: PersonaVisualVersionRepository,
    private val referenceImageRepository: ReferenceImageRepository,
    private val personalGuideRepository: PersonalGuideRepository,
    private val jobRepository: ImageJobRepository,
    private val candidateRepository: GeneratedCandidateRepository,
    private val objectStorage: ObjectStorage,
    private val catalogFactory: (String) -> OpenRouterImageModelCatalog = { OpenRouterImageModelCatalog(it) },
    private val pricingFactory: (String) -> OpenRouterModelsPricingCatalog = { OpenRouterModelsPricingCatalog(it) },
    private val apiKeyProvider: () -> String? = { System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() } },
    private val productionModelProvider: () -> String = {
        System.getenv("OPENROUTER_IMAGE_MODEL")?.takeIf { it.isNotBlank() }
            ?: "openai/gpt-image-2.5-flare"
    },
) {
    data class CreateRunCommand(
        val name: String,
        val personaIds: List<UUID>,
        val slotKeys: List<String>,
        val promptIds: List<String>,
        val notes: String? = null,
        val createdBy: String? = null,
        val start: Boolean = false,
    )

    data class DiscoveredSlot(
        val slotKey: String,
        val displayName: String,
        val available: Boolean,
        val modelId: String?,
        val provider: String,
        val maxReferences: Int?,
        val maxCandidates: Int?,
        val supportedResolutions: List<String>,
        val pricingSource: String,
        val pricingSnapshot: String?,
        val capabilitySnapshot: String?,
        val mappingNote: String?,
    )

    data class EligiblePersona(
        val personaId: UUID,
        val displayName: String,
        val apparentAge: Int,
        val eligible: Boolean,
        val reasons: List<String>,
        val visualVersionId: UUID?,
        val standardReferenceIds: Map<String, UUID>,
    )

    data class Readiness(
        val status: String,
        val reasons: List<String>,
        val checks: Map<String, String>,
        val productionModel: String,
    )

    fun productionModel(): String = productionModelProvider()

    fun discoverSlots(): List<DiscoveredSlot> {
        val key = apiKeyProvider() ?: throw IllegalStateException("OPENROUTER_API_KEY required")
        val catalog = catalogFactory(key).fetch()
        val pricing = try {
            pricingFactory(key).fetch()
        } catch (_: Exception) {
            emptyMap()
        }
        return BenchmarkModelSlots.ALL.map { slot ->
            val hit = BenchmarkModelSlots.match(slot, catalog)
            if (hit == null) {
                DiscoveredSlot(
                    slotKey = slot.slotKey,
                    displayName = slot.displayName,
                    available = false,
                    modelId = null,
                    provider = "openrouter",
                    maxReferences = null,
                    maxCandidates = null,
                    supportedResolutions = emptyList(),
                    pricingSource = "UNAVAILABLE",
                    pricingSnapshot = null,
                    capabilitySnapshot = null,
                    mappingNote = "UNAVAILABLE",
                )
            } else {
                val price = pricing[hit.modelId]
                val capJson = buildString {
                    append("{")
                    append("\"modelId\":\"${hit.modelId}\"")
                    append(",\"displayName\":\"${hit.displayName.replace("\"", "'")}\"")
                    append(",\"acceptsImageInput\":${hit.acceptsImageInput}")
                    append(",\"maxCandidateCount\":${hit.maxCandidateCount ?: "null"}")
                    append(",\"maxReferenceImages\":${hit.maxReferenceImages ?: "null"}")
                    append(",\"supportedResolutions\":[${hit.supportedResolutions.joinToString(",") { "\"$it\"" }}]")
                    append(",\"supportedAspectRatios\":[${hit.supportedAspectRatios.joinToString(",") { "\"$it\"" }}]")
                    append(",\"supportedParameters\":[${hit.supportedParameterNames.joinToString(",") { "\"$it\"" }}]")
                    append("}")
                }
                val priceJson = if (price == null) {
                    null
                } else {
                    "{\"source\":\"${price.source}\",\"prompt\":\"${price.prompt}\",\"completion\":\"${price.completion}\",\"image\":\"${price.image}\",\"imageOutput\":\"${price.imageOutput}\",\"request\":\"${price.request}\"}"
                }
                DiscoveredSlot(
                    slotKey = slot.slotKey,
                    displayName = slot.displayName,
                    available = true,
                    modelId = hit.modelId,
                    provider = "openrouter",
                    maxReferences = BenchmarkLiveRequestLimits.references(slot.slotKey, hit.maxReferenceImages),
                    maxCandidates = BenchmarkLiveRequestLimits.candidates(slot.slotKey, hit.maxCandidateCount),
                    supportedResolutions = hit.supportedResolutions,
                    pricingSource = price?.source ?: "UNAVAILABLE",
                    pricingSnapshot = priceJson,
                    capabilitySnapshot = capJson,
                    mappingNote = if (hit.modelId in slot.exactIds) null else "MAPPED_LIVE_ID",
                )
            }
        }
    }

    fun listEligiblePersonas(): List<EligiblePersona> =
        personaRepository.findAll().map { assessPersona(it.id) }

    fun assessPersona(personaId: UUID): EligiblePersona {
        val persona = personaRepository.findById(personaId)
            ?: return EligiblePersona(personaId, "unknown", 0, false, listOf("PERSONA_NOT_FOUND"), null, emptyMap())
        val reasons = mutableListOf<String>()
        if (persona.apparentAge < 18) reasons += "NOT_ADULT_APPARENT_AGE"
        val identityId = personaRepository.findPersonaIdentityId(personaId)
        if (identityId == null) {
            reasons += "NO_VISUAL_IDENTITY"
            return EligiblePersona(persona.id, persona.displayName, persona.apparentAge, false, reasons, null, emptyMap())
        }
        val version = visualVersionRepository.findActiveForPersonaIdentity(identityId)
            ?: visualVersionRepository.findForPersonaIdentity(identityId).maxByOrNull { it.version }
        if (version == null) {
            reasons += "NO_VISUAL_IDENTITY_VERSION"
            return EligiblePersona(persona.id, persona.displayName, persona.apparentAge, false, reasons, null, emptyMap())
        }
        val guide = try {
            personalGuideRepository.getPhysicalGuide(version.id)
        } catch (_: Exception) {
            null
        }
        if (guide != null && !guide.agePresentation.adult) {
            reasons += "PHYSICAL_GUIDE_ADULT_FALSE"
        }
        val refs = referenceImageRepository.findForVersion(version.id)
            .filter { it.status == ReferenceStatus.FINALIZED || it.status == ReferenceStatus.UPLOADED }
        val byRole = ReferenceRole.STANDARD_SLOTS.associateWith { role ->
            refs.firstOrNull { it.role == role }
        }
        val missing = byRole.filter { it.value == null }.keys
        if (missing.isNotEmpty()) {
            reasons += "MISSING_STANDARD_REFERENCES:${missing.joinToString(",") { it.name }}"
        }
        val requiredSlots = listOf(
            com.pinkdreams.visual.identity.ReferenceRole.FRONT,
            com.pinkdreams.visual.identity.ReferenceRole.FACE_CLOSE,
            com.pinkdreams.visual.identity.ReferenceRole.LEFT_PROFILE,
        )
        val missingRequired = requiredSlots.filter { byRole[it] == null }
        if (missingRequired.isNotEmpty()) {
            reasons += "MISSING_REQUIRED_REFERENCES:${missingRequired.joinToString(",") { it.name }}"
        }
        val refIds = byRole.mapNotNull { (role, img) -> img?.let { role.name to it.id } }.toMap()
        val blocking = reasons.filterNot { it.startsWith("MISSING_STANDARD_REFERENCES:") }
        return EligiblePersona(
            personaId = persona.id,
            displayName = persona.displayName,
            apparentAge = persona.apparentAge,
            eligible = blocking.isEmpty(),
            reasons = reasons,
            visualVersionId = version.id,
            standardReferenceIds = refIds,
        )
    }

    fun readiness(): Readiness {
        val reasons = mutableListOf<String>()
        val checks = linkedMapOf<String, String>()
        val key = apiKeyProvider()
        if (key == null) {
            reasons += "OPENROUTER_NOT_CONFIGURED"
            checks["openrouter"] = "NOT_READY"
            checks["modelsDiscoverable"] = "NOT_READY"
        } else {
            checks["openrouter"] = "READY"
            try {
                val slots = discoverSlots()
                checks["modelsDiscoverable"] = "READY"
                checks["availableSlots"] = slots.count { it.available }.toString()
                if (slots.none { it.available }) reasons += "NO_BENCHMARK_MODELS_AVAILABLE"
            } catch (e: Exception) {
                checks["modelsDiscoverable"] = "NOT_READY"
                reasons += "MODELS_NOT_DISCOVERABLE:${e.message?.take(120)}"
            }
        }
        val personas = listEligiblePersonas()
        val eligible = personas.filter { it.eligible }
        checks["personaExists"] = if (personas.isNotEmpty()) "READY" else "NOT_READY"
        checks["eligiblePersonas"] = eligible.size.toString()
        if (personas.isEmpty()) reasons += "NO_PERSONAS"
        if (eligible.isEmpty()) reasons += "NO_ELIGIBLE_PERSONAS"
        checks["visualIdentity"] = if (eligible.any { it.visualVersionId != null }) "READY" else "NOT_READY"
        checks["requiredReferences"] = if (eligible.any { it.standardReferenceIds.size == 5 }) "READY" else "NOT_READY"
        checks["storage"] = try {
            objectStorage.javaClass.simpleName
            "READY"
        } catch (_: Exception) {
            reasons += "STORAGE_UNAVAILABLE"
            "NOT_READY"
        }
        checks["database"] = "READY"
        checks["benchmarkTables"] = if (repository.tablesAvailable()) "READY" else {
            reasons += "BENCHMARK_TABLES_MISSING"
            "NOT_READY"
        }
        checks["costCapture"] = "WIRED_OPENROUTER_USAGE_OR_UNAVAILABLE"
        return Readiness(
            status = if (reasons.isEmpty()) "READY" else "NOT_READY",
            reasons = reasons,
            checks = checks,
            productionModel = productionModel(),
        )
    }

    fun create(command: CreateRunCommand): UUID {
        require(command.name.isNotBlank()) { "name is required" }
        require(command.personaIds.isNotEmpty()) { "Select at least one persona" }
        val promptIds = command.promptIds.ifEmpty { BenchmarkPrompts.ALL.map { it.promptId } }
        val prompts = BenchmarkPrompts.ALL.filter { it.promptId in promptIds }
        require(prompts.isNotEmpty()) { "Select at least one prompt" }
        val slotKeys = command.slotKeys.ifEmpty { BenchmarkModelSlots.ALL.map { it.slotKey } }
        val slots = BenchmarkModelSlots.ALL.filter { it.slotKey in slotKeys }
        require(slots.isNotEmpty()) { "Select at least one model slot" }

        val personas = command.personaIds.map { assessPersona(it) }
        personas.forEach {
            require(it.eligible) { "Persona ${it.displayName} is not eligible: ${it.reasons.joinToString("; ")}" }
        }

        val discovered = try {
            discoverSlots()
        } catch (_: Exception) {
            emptyList()
        }.associateBy { it.slotKey }

        val run = repository.createRun(command.name.trim(), command.notes, command.createdBy)
        slots.forEachIndexed { index, slot ->
            val d = discovered[slot.slotKey]
            repository.insertModel(
                BenchmarkRepository.ModelRow(
                    id = UUID.randomUUID(),
                    benchmarkRunId = run.id,
                    slotKey = slot.slotKey,
                    modelId = d?.modelId,
                    provider = "openrouter",
                    displayName = slot.displayName,
                    capabilitySnapshot = d?.capabilitySnapshot,
                    pricingSnapshot = d?.pricingSnapshot,
                    status = if (d?.available == true) "AVAILABLE" else "UNAVAILABLE",
                    sortOrder = index,
                )
            )
        }
        prompts.forEach { p ->
            repository.insertPrompt(
                BenchmarkRepository.PromptRow(
                    id = UUID.randomUUID(),
                    benchmarkRunId = run.id,
                    promptId = p.promptId,
                    promptText = p.text,
                    category = p.category,
                )
            )
        }

        personas.forEach { persona ->
            val refs = persona.visualVersionId?.let { referenceImageRepository.findForVersion(it) }.orEmpty()
                .filter { it.status == ReferenceStatus.FINALIZED || it.status == ReferenceStatus.UPLOADED }
            slots.forEach { slot ->
                val d = discovered[slot.slotKey]
                val selection = BenchmarkReferencePolicy.select(
                    availableStandard = refs.filter { it.role.isStandardIdentitySlot() },
                    maxReferences = BenchmarkLiveRequestLimits.references(slot.slotKey, d?.maxReferences),
                )
                val resolution = when (slot.slotKey) {
                    "SEEDREAM_4_5" -> BenchmarkResolutionPolicy.choose(listOf("2K"))
                    else -> BenchmarkResolutionPolicy.choose(d?.supportedResolutions.orEmpty())
                }
                val requestedN = BenchmarkLiveRequestLimits.candidates(slot.slotKey, d?.maxCandidates)
                prompts.forEach { prompt ->
                    val status = when {
                        d == null || !d.available -> "MODEL_UNAVAILABLE"
                        else -> "PLANNED"
                    }
                    repository.insertExecution(
                        BenchmarkRepository.ExecutionRow(
                            id = UUID.randomUUID(),
                            benchmarkRunId = run.id,
                            personaId = persona.personaId,
                            visualIdentityVersionId = persona.visualVersionId!!,
                            promptId = prompt.promptId,
                            modelId = d?.modelId,
                            slotKey = slot.slotKey,
                            referencesAvailable = selection.available.joinToString(",") { "${it.role.name}:${it.id}" },
                            referencesSent = selection.sent.joinToString(",") { "${it.role.name}:${it.id}" },
                            referencesOmitted = selection.omitted.joinToString(",") { "${it.role.name}:${it.id}" },
                            referenceOmitReason = selection.reason,
                            requestedCandidates = requestedN,
                            actualCandidates = null,
                            requestedResolution = resolution.requestedResolution,
                            actualResolution = resolution.actualResolution,
                            resolutionDeviation = resolution.deviation,
                            jobId = null,
                            providerRequestId = null,
                            status = status,
                            providerFailureType = if (status == "MODEL_UNAVAILABLE") "MODEL_UNAVAILABLE" else null,
                            providerFailureMessage = if (status == "MODEL_UNAVAILABLE") "UNAVAILABLE" else null,
                            actualCost = null,
                            currency = null,
                            createdAt = LocalDateTime.now(),
                            completedAt = null,
                        )
                    )
                }
            }
        }
        repository.updateRunStatus(run.id, "READY")
        if (command.start) start(run.id)
        return run.id
    }

    fun start(runId: UUID) {
        val run = repository.findRun(runId) ?: throw NoSuchElementException("Benchmark run not found")
        if (run.status == "RUNNING") return
        val executions = repository.listExecutions(runId)
        val personas = executions.map { it.personaId }.distinct().map { assessPersona(it) }
        if (personas.none { it.eligible }) {
            throw IllegalStateException("Benchmark refused: no eligible Personas")
        }
        repository.updateRunStatus(runId, "RUNNING", started = true)
        val prompts = repository.listPrompts(runId).associateBy { it.promptId }
        executions.filter { it.status == "PLANNED" }.forEach { exec ->
            val prompt = prompts[exec.promptId] ?: return@forEach
            val slug = personaRepository.findById(exec.personaId)?.slug ?: ""
            val seedPrompt = BenchmarkPersonaPrompts.resolve(slug, exec.promptId, prompt.promptText)
            val sentIds = parseRefIds(exec.referencesSent)
            try {
                val result = imageGenerationService.create(
                    ImageGenerationService.CreateCommand(
                        personaId = exec.personaId,
                        idempotencyKey = "bench-${runId}-${exec.id}",
                        seedPrompt = seedPrompt,
                        candidateCount = exec.requestedCandidates ?: 1,
                        visualVersionId = exec.visualIdentityVersionId,
                        selectedReferenceIds = sentIds,
                        requireStandardReferences = false,
                        modelId = exec.modelId,
                        widthPx = pixelsFor(exec.actualResolution),
                        heightPx = pixelsFor(exec.actualResolution),
                    )
                )
                repository.updateExecutionAfterSubmit(exec.id, result.job.id, "SUBMITTED", null, null)
            } catch (e: Exception) {
                val type = ProviderFailureClassifier.classify(e.message)
                repository.updateExecutionAfterSubmit(exec.id, null, type, type, e.message?.take(1500))
            }
        }
    }

    fun refresh(runId: UUID) {
        val executions = repository.listExecutions(runId)
        executions.forEach { exec ->
            val job = exec.jobId?.let { jobRepository.findById(it) } ?: return@forEach
            val candidates = candidateRepository.findByImageJob(job.id)
            when (job.status) {
                ImageJobStatus.SUCCEEDED -> {
                    val cost = job.providerCostRaw
                    repository.syncExecutionFromJob(
                        id = exec.id,
                        status = "ACCEPTED",
                        actualCandidates = candidates.size,
                        actualCost = cost,
                        currency = if (cost != null) (job.providerCostCurrency ?: "USD") else null,
                        providerRequestId = exec.providerRequestId,
                        failureType = null,
                        failureMessage = null,
                        completed = true,
                    )
                }
                ImageJobStatus.FAILED, ImageJobStatus.CANCELLED -> {
                    val type = ProviderFailureClassifier.classify(job.lastError)
                    repository.syncExecutionFromJob(
                        id = exec.id,
                        status = type,
                        actualCandidates = 0,
                        actualCost = null,
                        currency = null,
                        providerRequestId = exec.providerRequestId,
                        failureType = type,
                        failureMessage = job.lastError,
                        completed = true,
                    )
                }
                else -> Unit
            }
        }
        val latest = repository.listExecutions(runId)
        val run = repository.findRun(runId)
        val pending = latest.any { it.status in setOf("PLANNED", "SUBMITTED") }
        if (run?.status == "RUNNING" && !pending) {
            repository.updateRunStatus(runId, "COMPLETED", completed = true)
        }
    }

    fun listRuns(limit: Int = 50) = repository.listRuns(limit)

    fun getDetail(runId: UUID): RunDetail? {
        repository.findRun(runId) ?: return null
        refresh(runId)
        val refreshed = repository.findRun(runId)!!
        val models = repository.listModels(runId)
        val prompts = repository.listPrompts(runId)
        val executions = repository.listExecutions(runId)
        val evaluations = repository.listEvaluationsForRun(runId)
        val candidatesByJob = executions.mapNotNull { it.jobId }.distinct().associateWith { jobId ->
            candidateRepository.findByImageJob(jobId)
        }
        return RunDetail(refreshed, models, prompts, executions, evaluations, candidatesByJob, productionModel())
    }

    data class RunDetail(
        val run: BenchmarkRepository.RunRow,
        val models: List<BenchmarkRepository.ModelRow>,
        val prompts: List<BenchmarkRepository.PromptRow>,
        val executions: List<BenchmarkRepository.ExecutionRow>,
        val evaluations: List<BenchmarkRepository.EvaluationRow>,
        val candidatesByJob: Map<UUID, List<GeneratedCandidate>>,
        val productionModel: String,
    )

    fun evaluate(
        executionId: UUID,
        candidateId: UUID?,
        identityRating: Int?,
        identityRemarks: String?,
        realismRating: Int?,
        realismRemarks: String?,
        adminDecision: String?,
        adminRemarks: String?,
        evaluatedBy: String?,
    ): BenchmarkRepository.EvaluationRow {
        fun check(v: Int?, name: String) {
            if (v != null) require(v in 0..5) { "$name must be 0..5" }
        }
        check(identityRating, "identityRating")
        check(realismRating, "realismRating")
        repository.findExecution(executionId) ?: throw NoSuchElementException("Execution not found")
        return repository.upsertEvaluation(
            executionId, candidateId, identityRating, identityRemarks,
            realismRating, realismRemarks, adminDecision, adminRemarks, evaluatedBy,
        )
    }

    private fun parseRefIds(encoded: String?): List<UUID> =
        encoded.orEmpty().split(",").mapNotNull { part ->
            val id = part.substringAfter(":", "").trim()
            if (id.isBlank()) null else runCatching { UUID.fromString(id) }.getOrNull()
        }

    private fun pixelsFor(tier: String?): Int = when (tier?.uppercase()) {
        "512" -> 512
        "2K" -> 2048
        "4K" -> 2048
        else -> 1024
    }
}

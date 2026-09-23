package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.observability.ImageGenerationEventRepository
import com.pinkdreams.imaging.orchestration.CandidateStatus
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.orchestration.ImageWarehouseRepository
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.storage.ObjectStorage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class CreateImageJobHttpRequest(
    val personaId: String,
    val idempotencyKey: String,
    val location: String? = null,
    val outfit: String? = null,
    val expression: String? = null,
    val presentation: String? = null,
    val seedPrompt: String? = null,
    val widthPx: Int? = 512,
    val heightPx: Int? = 512,
    val candidateCount: Int = 4,
    val visualVersionId: String? = null,
    val conversationId: String? = null,
    val turnRequestId: String? = null,
    /** When true, reject generation if standard reference slots are incomplete. Default true for Admin. */
    val requireStandardReferences: Boolean = true,
    /** Optional per-job model override. Does not change production Admin/env defaults. */
    val modelId: String? = null,
)

@Serializable
data class ImageJobHttpResponse(
    val jobId: String,
    val status: String,
    val personaVisualVersionId: String,
    val idempotencyKey: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val lastError: String?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?,
    val reusedExisting: Boolean = false,
)

@Serializable
data class ImageCandidateHttpResponse(
    val id: String,
    val storageKey: String,
    val contentType: String,
    val fileSize: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val candidateIndex: Int,
    val assetUrl: String,
    val status: String,
    val adminRemark: String? = null,
    val seedPrompt: String? = null,
    val createdAt: String,
)

@Serializable
data class ImageJobResultHttpResponse(
    val jobId: String,
    val status: String,
    val lastError: String?,
    val candidates: List<ImageCandidateHttpResponse>,
)

@Serializable
data class PatchImageCandidateHttpRequest(
    val status: String? = null,
    val adminRemark: String? = null,
)

@Serializable
data class RegenerateCandidateHttpRequest(
    val correction: String? = null,
    val candidateCount: Int = 1,
)

@Serializable
data class WarehouseCandidateHttpResponse(
    val id: String,
    val jobId: String,
    val jobStatus: String,
    val candidateIndex: Int,
    val status: String,
    val adminRemark: String?,
    val seedPrompt: String?,
    val contentType: String,
    val fileSize: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val createdAt: String,
    val assetUrl: String,
    val visualVersionId: String? = null,
    val modelId: String? = null,
    val sourceCandidateId: String? = null,
    val costAvailability: String,
)

@Serializable
data class WarehouseHttpResponse(
    val personaId: String,
    val candidates: List<WarehouseCandidateHttpResponse>,
    val total: Int = 0,
    val limit: Int = 100,
    val offset: Int = 0,
)

@Serializable
data class ImageConfigHttpResponse(
    val provider: String,
    val model: String,
    val maxCandidateCount: Int,
    val defaultCandidateCount: Int,
    val providerSource: String? = null,
    val modelSource: String? = null,
    val adminConfiguredModel: String? = null,
    val environmentModel: String? = null,
    val enabled: Boolean = true,
    val notes: String? = null,
    val precedence: String = "request > database > environment > default",
)

@Serializable
data class PatchImageConfigHttpRequest(
    val provider: String? = null,
    val model: String? = null,
    val enabled: Boolean? = null,
    val notes: String? = null,
    /** When true, clears DB overrides so env/default apply. */
    val clearOverrides: Boolean = false,
)

@Serializable
data class AttachImageToMessageRequest(
    val messageId: String,
)

@Serializable
data class ImageSlaFailureItem(
    val jobId: String,
    val outcome: String,
    val errorMessage: String?,
    val model: String?,
    val provider: String?,
    val createdAt: String,
)

@Serializable
data class ImageSlaSummaryResponse(
    val samplePopulation: String,
    val windowHours: Int,
    val totalEvents: Int,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Double?,
    val failureRate: Double?,
    val p50TotalLatencyMs: Long?,
    val p95TotalLatencyMs: Long?,
    val p99TotalLatencyMs: Long? = null,
    val p50GenerationLatencyMs: Long?,
    val p95GenerationLatencyMs: Long?,
    val p50QueueLatencyMs: Long? = null,
    val p95QueueLatencyMs: Long? = null,
    val candidatesGenerated: Int? = null,
    val candidatesShortlisted: Int? = null,
    val candidatesDeclined: Int? = null,
    val candidatesSaved: Int? = null,
    val regenerations: Int? = null,
    val byProvider: Map<String, Int> = emptyMap(),
    val byModel: Map<String, Int> = emptyMap(),
    val costAvailability: String,
    val costNote: String = "Provider-reported image cost is not persisted; do not invent pricing.",
    val note: String,
    val slaTarget: String = "Not configured",
    val slaTargetNote: String = "No numeric image SLA target is configured; metrics are observational only.",
    val queuedJobs: Int? = null,
    val runningJobs: Int? = null,
    val stuckRunningJobs: Int? = null,
    val storageProbeOk: Boolean? = null,
    val storageDetail: String? = null,
    val recentFailures: List<ImageSlaFailureItem> = emptyList(),
)

@Serializable
data class ImageStorageStatusResponse(
    val mode: String,
    val multiInstanceContract: String,
    val operatorDeclaredShared: Boolean,
    val rootLabel: String?,
    val readable: Boolean,
    val writable: Boolean,
    val probeOk: Boolean,
    val detail: String,
    val guidance: String,
)

@Serializable
data class ImageJobListItem(
    val jobId: String,
    val status: String,
    val personaVisualVersionId: String,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: String,
    val completedAt: String?,
)

@Serializable
data class JobIdentityReferenceHttpResponse(
    val id: String,
    val role: String,
    val status: String,
    val assetUrl: String,
)

@Serializable
data class JobIdentityContextHttpResponse(
    val jobId: String,
    val personaId: String?,
    val visualVersionId: String,
    val seedPrompt: String?,
    val modelId: String?,
    val referenceImageIds: List<String>,
    val references: List<JobIdentityReferenceHttpResponse>,
    val sourceCandidateId: String?,
)

class AdminImageGenerationRoutes(
    private val imageGenerationService: ImageGenerationService,
    private val imageJobRepository: ImageJobRepository,
    private val candidateRepository: GeneratedCandidateRepository,
    private val objectStorage: ObjectStorage,
    private val eventRepository: ImageGenerationEventRepository,
    private val messageRepository: MessageRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
    private val generationTriggerWired: Boolean = true,
    private val warehouseRepository: ImageWarehouseRepository? = null,
    private val referenceImageRepository: com.pinkdreams.persistence.repositories.ReferenceImageRepository? = null,
    private val imageRuntimeConfig: com.pinkdreams.imaging.config.ImageRuntimeConfig? = null,
    private val imageProviderSettingsRepository: com.pinkdreams.imaging.config.ImageProviderSettingsRepository? = null,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            post("/v1/admin/images/jobs") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                if (!generationTriggerWired) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Image generation is not wired", null)),
                    )
                    return@post
                }
                val body = try {
                    call.receive<CreateImageJobHttpRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)),
                    )
                    return@post
                }
                try {
                    val result = imageGenerationService.create(
                        ImageGenerationService.CreateCommand(
                            personaId = UUID.fromString(body.personaId),
                            idempotencyKey = body.idempotencyKey,
                            location = body.location,
                            outfit = body.outfit,
                            expression = body.expression,
                            presentation = body.presentation,
                            seedPrompt = body.seedPrompt,
                            widthPx = body.widthPx,
                            heightPx = body.heightPx,
                            candidateCount = body.candidateCount,
                            visualVersionId = body.visualVersionId?.let(UUID::fromString),
                            conversationId = body.conversationId?.let(UUID::fromString),
                            turnRequestId = body.turnRequestId?.let(UUID::fromString),
                            requireStandardReferences = body.requireStandardReferences,
                            modelId = body.modelId?.takeIf { it.isNotBlank() },
                        )
                    )
                    call.respond(
                        if (result.reusedExisting) HttpStatusCode.OK else HttpStatusCode.Accepted,
                        toJobResponse(result.job, result.reusedExisting),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation error", null)),
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Conflict", null)),
                    )
                }
            }

            get("/v1/admin/images/jobs") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                call.respond(
                    imageJobRepository.findRecent(limit).map {
                        ImageJobListItem(
                            jobId = it.id.toString(),
                            status = it.status.name,
                            personaVisualVersionId = it.personaVisualVersionId.toString(),
                            attemptCount = it.attemptCount,
                            lastError = ImageGenerationEventRepository.redactSecrets(it.lastError),
                            createdAt = it.createdAt.toString(),
                            completedAt = it.completedAt?.toString(),
                        )
                    }
                )
            }

            get("/v1/admin/images/jobs/{jobId}") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val jobId = call.parseUuid("jobId") ?: return@get
                val job = imageGenerationService.getJob(jobId) ?: run {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Job not found", null)),
                    )
                    return@get
                }
                call.respond(toJobResponse(job, reusedExisting = false))
            }

            get("/v1/admin/images/jobs/{jobId}/identity") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val jobId = call.parseUuid("jobId") ?: return@get
                val job = imageGenerationService.getJob(jobId) ?: run {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Job not found", null)),
                    )
                    return@get
                }
                val personaId = extractPersonaId(job.requestPayload)
                val refIds = extractReferenceIds(job.requestPayload)
                val refs = refIds.mapNotNull { id ->
                    val ref = referenceImageRepository?.findById(id) ?: return@mapNotNull null
                    val assetUrl = if (personaId != null) {
                        "/v1/admin/personas/$personaId/visual/references/${ref.id}/content"
                    } else {
                        "/v1/admin/images/assets/${ref.id}"
                    }
                    JobIdentityReferenceHttpResponse(
                        id = ref.id.toString(),
                        role = ref.role.name,
                        status = ref.status.name,
                        assetUrl = assetUrl,
                    )
                }
                call.respond(
                    JobIdentityContextHttpResponse(
                        jobId = job.id.toString(),
                        personaId = personaId?.toString(),
                        visualVersionId = job.personaVisualVersionId.toString(),
                        seedPrompt = extractSeedPrompt(job.requestPayload),
                        modelId = extractModelId(job.requestPayload),
                        referenceImageIds = refIds.map { it.toString() },
                        references = refs,
                        sourceCandidateId = extractSourceCandidateId(job.requestPayload)?.toString(),
                    )
                )
            }

            get("/v1/admin/images/jobs/{jobId}/result") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val jobId = call.parseUuid("jobId") ?: return@get
                val job = imageGenerationService.getJob(jobId) ?: run {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Job not found", null)),
                    )
                    return@get
                }
                val seedPrompt = extractSeedPrompt(job.requestPayload)
                val candidates = imageGenerationService.getCandidates(jobId).map { c ->
                    ImageCandidateHttpResponse(
                        id = c.id.toString(),
                        storageKey = c.storageKey,
                        contentType = c.contentType,
                        fileSize = c.fileSize,
                        widthPx = c.widthPx,
                        heightPx = c.heightPx,
                        candidateIndex = c.candidateIndex,
                        assetUrl = "/v1/admin/images/assets/${c.id}",
                        status = c.status.name,
                        adminRemark = c.adminRemark,
                        seedPrompt = seedPrompt,
                        createdAt = c.createdAt.toString(),
                    )
                }
                call.respond(
                    ImageJobResultHttpResponse(
                        jobId = job.id.toString(),
                        status = job.status.name,
                        lastError = ImageGenerationEventRepository.redactSecrets(job.lastError),
                        candidates = candidates,
                    )
                )
            }

            get("/v1/admin/images/assets/{assetId}") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val assetId = call.parseUuid("assetId") ?: return@get
                val candidate = candidateRepository.findById(assetId)
                if (candidate == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Asset not found", null)),
                    )
                    return@get
                }
                val obj = objectStorage.retrieve(candidate.storageKey)
                if (obj == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Asset bytes not found", null)),
                    )
                    return@get
                }
                call.respondBytes(obj.content, ContentType.parse(obj.contentType))
            }

            post("/v1/admin/images/jobs/{jobId}/cancel") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val jobId = call.parseUuid("jobId") ?: return@post
                try {
                    call.respond(toJobResponse(imageJobRepository.cancelJob(jobId), false))
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Cannot cancel", null)),
                    )
                }
            }

            post("/v1/admin/images/jobs/{jobId}/requeue") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val jobId = call.parseUuid("jobId") ?: return@post
                try {
                    call.respond(toJobResponse(imageJobRepository.adminRequeueFailed(jobId), false))
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Cannot requeue", null)),
                    )
                }
            }

            post("/v1/admin/images/jobs/{jobId}/attach-message") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val jobId = call.parseUuid("jobId") ?: return@post
                val body = try {
                    call.receive<AttachImageToMessageRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid body", null)),
                    )
                    return@post
                }
                val job = imageGenerationService.getJob(jobId)
                if (job == null || job.status != ImageJobStatus.SUCCEEDED) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Job must be SUCCEEDED", null)),
                    )
                    return@post
                }
                val messageId = try {
                    UUID.fromString(body.messageId)
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid messageId", null)),
                    )
                    return@post
                }
                if (messageRepository.findById(messageId) == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Message not found", null)),
                    )
                    return@post
                }
                val assetIds = imageGenerationService.getCandidates(jobId).map { it.id.toString() }
                val updated = messageRepository.mergeMetadata(
                    messageId,
                    mapOf(
                        "imageJobId" to jobId.toString(),
                        "imageAssetIds" to assetIds.joinToString(","),
                        "imageAssetUrls" to assetIds.joinToString(",") { "/v1/admin/images/assets/$it" },
                    ),
                )
                call.respond(mapOf("messageId" to messageId.toString(), "metadata" to updated))
            }

            get("/v1/admin/images/sla") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val hours = call.request.queryParameters["hours"]?.toIntOrNull()?.coerceIn(1, 168) ?: 24
                val since = LocalDateTime.now().minusHours(hours.toLong())
                val events = eventRepository.findSince(since)
                val success = events.count { it.outcome == "SUCCESS" }
                val failure = events.count { it.outcome == "FAILURE" }
                val totals = events.mapNotNull { it.totalLatencyMs }.sorted()
                val gens = events.mapNotNull { it.generationLatencyMs }.sorted()
                val queues = events.mapNotNull { it.queueLatencyMs }.sorted()
                val byProvider = events.groupingBy { it.provider ?: "unknown" }.eachCount()
                val byModel = events.groupingBy { it.model ?: "unknown" }.eachCount()
                val regenerations = events.count { ev ->
                    try {
                        val payload = imageJobRepository.findById(ev.imageJobId)?.requestPayload ?: return@count false
                        payload.contains("\"sourceCandidateId\"")
                    } catch (_: Exception) {
                        false
                    }
                }
                val statusCounts = candidateRepository.countByStatusSince(since)
                val queuedJobs = imageJobRepository.findByStatus(ImageJobStatus.QUEUED, limit = 500).size
                val runningJobs = imageJobRepository.findByStatus(ImageJobStatus.RUNNING, limit = 500)
                val leaseSeconds = System.getenv("IMAGE_WORKER_LEASE_SECONDS")?.toLongOrNull() ?: 120L
                val stuckRunningJobs = imageJobRepository.findStaleLeasedJobs(leaseSeconds).size
                val storage = com.pinkdreams.imaging.storage.ImageStorageDiagnostics.from(objectStorage)
                val recentFailures = events
                    .filter { it.outcome == "FAILURE" }
                    .sortedByDescending { it.createdAt }
                    .take(20)
                    .map {
                        ImageSlaFailureItem(
                            jobId = it.imageJobId.toString(),
                            outcome = it.outcome,
                            errorMessage = ImageGenerationEventRepository.redactSecrets(it.errorMessage),
                            model = it.model,
                            provider = it.provider,
                            createdAt = it.createdAt.toString(),
                        )
                    }
                call.respond(
                    ImageSlaSummaryResponse(
                        samplePopulation = "image_generation_events",
                        windowHours = hours,
                        totalEvents = events.size,
                        successCount = success,
                        failureCount = failure,
                        successRate = if (events.isEmpty()) null else success.toDouble() / events.size,
                        failureRate = if (events.isEmpty()) null else failure.toDouble() / events.size,
                        p50TotalLatencyMs = percentile(totals, 0.50),
                        p95TotalLatencyMs = percentile(totals, 0.95),
                        p99TotalLatencyMs = percentile(totals, 0.99),
                        p50GenerationLatencyMs = percentile(gens, 0.50),
                        p95GenerationLatencyMs = percentile(gens, 0.95),
                        p50QueueLatencyMs = percentile(queues, 0.50),
                        p95QueueLatencyMs = percentile(queues, 0.95),
                        candidatesGenerated = statusCounts["GENERATED"],
                        candidatesShortlisted = statusCounts["SHORTLISTED"],
                        candidatesDeclined = statusCounts["DECLINED"],
                        candidatesSaved = statusCounts["SAVED"],
                        regenerations = regenerations,
                        byProvider = byProvider,
                        byModel = byModel,
                        costAvailability = "UNAVAILABLE",
                        costNote = "Provider-reported image cost is not persisted; do not invent pricing.",
                        note = "Image SLA only — separate from LLM observability. Not production SLA unless sample is production-only traffic.",
                        slaTarget = "Not configured",
                        slaTargetNote = "No numeric image SLA target is configured; metrics are observational only.",
                        queuedJobs = queuedJobs,
                        runningJobs = runningJobs.size,
                        stuckRunningJobs = stuckRunningJobs,
                        storageProbeOk = storage.probe.probeOk,
                        storageDetail = storage.probe.detail,
                        recentFailures = recentFailures,
                    )
                )
            }

            get("/v1/admin/images/storage") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val d = com.pinkdreams.imaging.storage.ImageStorageDiagnostics.from(objectStorage)
                call.respond(
                    ImageStorageStatusResponse(
                        mode = d.mode,
                        multiInstanceContract = d.multiInstanceContract,
                        operatorDeclaredShared = d.operatorDeclaredShared,
                        rootLabel = d.probe.rootLabel,
                        readable = d.probe.readable,
                        writable = d.probe.writable,
                        probeOk = d.probe.probeOk,
                        detail = d.probe.detail,
                        guidance = d.guidance,
                    )
                )
            }

            get("/v1/admin/images/config") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val resolved = imageRuntimeConfig?.resolve()
                if (resolved != null) {
                    val stored = imageProviderSettingsRepository?.get()
                    call.respond(
                        ImageConfigHttpResponse(
                            provider = resolved.provider,
                            model = resolved.modelId,
                            maxCandidateCount = 4,
                            defaultCandidateCount = 4,
                            providerSource = resolved.providerSource.name,
                            modelSource = resolved.modelSource.name,
                            adminConfiguredModel = resolved.adminConfiguredModel,
                            environmentModel = resolved.environmentModel,
                            enabled = resolved.enabled,
                            notes = stored?.notes,
                            precedence = "request > database > environment > default",
                        )
                    )
                } else {
                    val envProvider = System.getenv("IMAGE_PROVIDER")?.takeIf { it.isNotBlank() }
                    val provider = envProvider
                        ?: if (System.getenv("OPENROUTER_API_KEY").isNullOrBlank()) "fake" else "openrouter"
                    val model = System.getenv("OPENROUTER_IMAGE_MODEL")
                        ?.takeIf { it.isNotBlank() }
                        ?: "openai/gpt-image-2.5-flare"
                    call.respond(
                        ImageConfigHttpResponse(
                            provider = provider,
                            model = model,
                            maxCandidateCount = 4,
                            defaultCandidateCount = 4,
                            modelSource = "ENVIRONMENT",
                            environmentModel = model,
                        )
                    )
                }
            }

            patch("/v1/admin/images/config") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@patch
                val repo = imageProviderSettingsRepository
                if (repo == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Image config not wired", null)),
                    )
                    return@patch
                }
                val body = try {
                    call.receive<PatchImageConfigHttpRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)),
                    )
                    return@patch
                }
                try {
                    val current = repo.get()
                    val saved = if (body.clearOverrides) {
                        repo.save(
                            provider = null,
                            modelId = null,
                            enabled = true,
                            notes = null,
                            updatedBy = call.principal<UserIdPrincipal>()?.name,
                        )
                    } else {
                        repo.save(
                            provider = body.provider ?: current.provider,
                            modelId = body.model ?: current.modelId,
                            enabled = body.enabled ?: current.enabled,
                            notes = body.notes ?: current.notes,
                            updatedBy = call.principal<UserIdPrincipal>()?.name,
                        )
                    }
                    val resolved = imageRuntimeConfig?.resolve()
                        ?: com.pinkdreams.imaging.config.ImageRuntimeConfig(repo).resolve()
                    call.respond(
                        ImageConfigHttpResponse(
                            provider = resolved.provider,
                            model = resolved.modelId,
                            maxCandidateCount = 4,
                            defaultCandidateCount = 4,
                            providerSource = resolved.providerSource.name,
                            modelSource = resolved.modelSource.name,
                            adminConfiguredModel = saved.modelId,
                            environmentModel = resolved.environmentModel,
                            enabled = saved.enabled,
                            notes = saved.notes,
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation error", null)),
                    )
                }
            }

            get("/v1/admin/personas/{personaId}/images/warehouse") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val personaId = call.parseUuid("personaId") ?: return@get
                val repo = warehouseRepository
                if (repo == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Warehouse not wired", null)),
                    )
                    return@get
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val statusFilter = call.request.queryParameters["status"]?.let { raw ->
                    try {
                        CandidateStatus.valueOf(raw)
                    } catch (_: Exception) {
                        null
                    }
                }
                val page = repo.findCandidatesForPersona(personaId, limit, offset, statusFilter)
                call.respond(
                    WarehouseHttpResponse(
                        personaId = personaId.toString(),
                        total = page.total,
                        limit = page.limit,
                        offset = page.offset,
                        candidates = page.candidates.map { row ->
                            WarehouseCandidateHttpResponse(
                                id = row.candidate.id.toString(),
                                jobId = row.jobId.toString(),
                                jobStatus = row.jobStatus,
                                candidateIndex = row.candidate.candidateIndex,
                                status = row.candidate.status.name,
                                adminRemark = row.candidate.adminRemark,
                                seedPrompt = extractSeedPrompt(row.requestPayload),
                                contentType = row.candidate.contentType,
                                fileSize = row.candidate.fileSize,
                                widthPx = row.candidate.widthPx,
                                heightPx = row.candidate.heightPx,
                                createdAt = row.candidate.createdAt.toString(),
                                assetUrl = "/v1/admin/images/assets/${row.candidate.id}",
                                visualVersionId = row.personaVisualVersionId.toString(),
                                modelId = extractModelId(row.requestPayload),
                                sourceCandidateId = extractSourceCandidateId(row.requestPayload)?.toString(),
                                costAvailability = "UNAVAILABLE",
                            )
                        },
                    )
                )
            }

            patch("/v1/admin/images/candidates/{candidateId}") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@patch
                val candidateId = call.parseUuid("candidateId") ?: return@patch
                val body = try {
                    call.receive<PatchImageCandidateHttpRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)),
                    )
                    return@patch
                }
                if (body.status == null && body.adminRemark == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "status or adminRemark required", null)),
                    )
                    return@patch
                }
                val parsedStatus = body.status?.let { raw ->
                    try {
                        CandidateStatus.valueOf(raw)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (body.status != null && parsedStatus == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid status: ${body.status}", null)),
                    )
                    return@patch
                }
                try {
                    val updated = candidateRepository.updateStatusAndRemark(
                        id = candidateId,
                        status = parsedStatus,
                        remark = body.adminRemark,
                    )
                    val job = imageGenerationService.getJob(updated.imageJobId)
                    val seedPrompt = job?.let { extractSeedPrompt(it.requestPayload) }
                    call.respond(
                        ImageCandidateHttpResponse(
                            id = updated.id.toString(),
                            storageKey = updated.storageKey,
                            contentType = updated.contentType,
                            fileSize = updated.fileSize,
                            widthPx = updated.widthPx,
                            heightPx = updated.heightPx,
                            candidateIndex = updated.candidateIndex,
                            assetUrl = "/v1/admin/images/assets/${updated.id}",
                            status = updated.status.name,
                            adminRemark = updated.adminRemark,
                            seedPrompt = seedPrompt,
                            createdAt = updated.createdAt.toString(),
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Candidate not found", null)),
                    )
                }
            }

            post("/v1/admin/images/candidates/{candidateId}/regenerate") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val candidateId = call.parseUuid("candidateId") ?: return@post
                val body = try {
                    call.receive<RegenerateCandidateHttpRequest>()
                } catch (_: Exception) {
                    RegenerateCandidateHttpRequest()
                }
                try {
                    val candidate = candidateRepository.findById(candidateId)
                        ?: throw NoSuchElementException("Candidate not found")
                    val sourceJob = imageJobRepository.findById(candidate.imageJobId)
                        ?: throw NoSuchElementException("Source job not found")
                    val seedPrompt = extractSeedPrompt(sourceJob.requestPayload)
                    val personaId = extractPersonaId(sourceJob.requestPayload)
                        ?: throw IllegalStateException("Source job missing personaId — cannot regenerate")
                    val referenceIds = extractReferenceIds(sourceJob.requestPayload)
                    val result = imageGenerationService.create(
                        ImageGenerationService.CreateCommand(
                            personaId = personaId,
                            idempotencyKey = "regen-${candidateId}-${System.currentTimeMillis()}",
                            seedPrompt = seedPrompt,
                            candidateCount = body.candidateCount.coerceIn(1, 4),
                            widthPx = 512,
                            heightPx = 512,
                            visualVersionId = sourceJob.personaVisualVersionId,
                            selectedReferenceIds = referenceIds,
                            requireStandardReferences = false,
                            sourceCandidateId = candidateId,
                            adminCorrection = body.correction,
                            modelId = extractModelId(sourceJob.requestPayload),
                        )
                    )
                    call.respond(
                        if (result.reusedExisting) HttpStatusCode.OK else HttpStatusCode.Accepted,
                        toJobResponse(result.job, result.reusedExisting),
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Not found", null)),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation error", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Conflict", null)),
                    )
                }
            }
        }
    }

    private fun toJobResponse(job: com.pinkdreams.imaging.job.ImageJob, reusedExisting: Boolean) =
        ImageJobHttpResponse(
            jobId = job.id.toString(),
            status = job.status.name,
            personaVisualVersionId = job.personaVisualVersionId.toString(),
            idempotencyKey = job.idempotencyKey,
            attemptCount = job.attemptCount,
            maxAttempts = job.maxAttempts,
            lastError = ImageGenerationEventRepository.redactSecrets(job.lastError),
            createdAt = job.createdAt.toString(),
            startedAt = job.startedAt?.toString(),
            completedAt = job.completedAt?.toString(),
            reusedExisting = reusedExisting,
        )

    private fun percentile(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        private val payloadJson = Json { ignoreUnknownKeys = true }

        fun extractSeedPrompt(requestPayload: String): String? {
            return try {
                val root = payloadJson.parseToJsonElement(requestPayload).jsonObject
                val scene = root["sceneIntent"]?.jsonObject ?: return null
                scene["seedPrompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        fun extractPersonaId(requestPayload: String): UUID? {
            return try {
                val root = payloadJson.parseToJsonElement(requestPayload).jsonObject
                root["personaId"]?.jsonPrimitive?.contentOrNull?.let { UUID.fromString(it) }
            } catch (_: Exception) {
                null
            }
        }

        fun extractReferenceIds(requestPayload: String): List<UUID> {
            return try {
                val root = payloadJson.parseToJsonElement(requestPayload).jsonObject
                val raw = root["selectedReferenceIds"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                payloadJson.decodeFromString<List<String>>(raw).mapNotNull {
                    try {
                        UUID.fromString(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun extractModelId(requestPayload: String): String? {
            return try {
                val root = payloadJson.parseToJsonElement(requestPayload).jsonObject
                root["modelId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: root["metadata"]?.jsonObject?.get("modelId")?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
        }

        fun extractSourceCandidateId(requestPayload: String): UUID? {
            return try {
                val root = payloadJson.parseToJsonElement(requestPayload).jsonObject
                root["sourceCandidateId"]?.jsonPrimitive?.contentOrNull?.let { UUID.fromString(it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

private suspend fun ApplicationCall.requireAdmin(auth: AdminAuthorizationProvider): Boolean {
    val principal = principal<UserIdPrincipal>()
    if (principal == null) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
        )
        return false
    }
    if (!auth.isAdmin(principal.name)) {
        respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
        )
        return false
    }
    return true
}

private suspend fun ApplicationCall.parseUuid(name: String): UUID? {
    return try {
        UUID.fromString(parameters[name])
    } catch (_: Exception) {
        respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid $name", null)),
        )
        null
    }
}

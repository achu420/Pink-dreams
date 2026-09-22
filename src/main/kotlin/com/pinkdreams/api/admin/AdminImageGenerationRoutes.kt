package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.observability.ImageGenerationEventRepository
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationService
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
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
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
    val widthPx: Int? = 512,
    val heightPx: Int? = 512,
    val candidateCount: Int = 1,
    val visualVersionId: String? = null,
    val conversationId: String? = null,
    val turnRequestId: String? = null,
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
)

@Serializable
data class ImageJobResultHttpResponse(
    val jobId: String,
    val status: String,
    val lastError: String?,
    val candidates: List<ImageCandidateHttpResponse>,
)

@Serializable
data class AttachImageToMessageRequest(
    val messageId: String,
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
    val p50GenerationLatencyMs: Long?,
    val p95GenerationLatencyMs: Long?,
    val note: String,
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

class AdminImageGenerationRoutes(
    private val imageGenerationService: ImageGenerationService,
    private val imageJobRepository: ImageJobRepository,
    private val candidateRepository: GeneratedCandidateRepository,
    private val objectStorage: ObjectStorage,
    private val eventRepository: ImageGenerationEventRepository,
    private val messageRepository: MessageRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
    private val generationTriggerWired: Boolean = true,
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
                            widthPx = body.widthPx,
                            heightPx = body.heightPx,
                            candidateCount = body.candidateCount,
                            visualVersionId = body.visualVersionId?.let(UUID::fromString),
                            conversationId = body.conversationId?.let(UUID::fromString),
                            turnRequestId = body.turnRequestId?.let(UUID::fromString),
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
                        p50GenerationLatencyMs = percentile(gens, 0.50),
                        p95GenerationLatencyMs = percentile(gens, 0.95),
                        note = "Not production SLA unless sample is production-only traffic.",
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

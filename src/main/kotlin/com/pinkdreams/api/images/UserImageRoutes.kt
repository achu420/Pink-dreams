package com.pinkdreams.api.images

import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.storage.ObjectStorage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class UserImageJobResponse(
    val jobId: String,
    val status: String,
    val conversationId: String?,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: String,
    val completedAt: String?,
)

@Serializable
data class UserImageCandidateResponse(
    val id: String,
    val contentType: String,
    val fileSize: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val candidateIndex: Int,
    val assetUrl: String,
)

@Serializable
data class UserImageResultResponse(
    val jobId: String,
    val status: String,
    val lastError: String?,
    val candidates: List<UserImageCandidateResponse>,
)

/**
 * End-user image job/result/asset APIs.
 * Ownership: job payload must carry conversationId; caller must own that conversation.
 */
class UserImageRoutes(
    private val imageGenerationService: ImageGenerationService,
    private val imageJobRepository: ImageJobRepository,
    private val candidateRepository: GeneratedCandidateRepository,
    private val conversationRepository: ConversationRepository,
    private val objectStorage: ObjectStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun register(route: Route) {
        route.authenticate("dev-auth") {
            get("/v1/images/jobs/{jobId}") {
                val userId = call.requireUser() ?: return@get
                val jobId = call.parseUuid("jobId") ?: return@get
                val job = imageJobRepository.findById(jobId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Job not found", null)))
                    return@get
                }
                if (!call.ownsJob(userId, job.requestPayload)) return@get
                call.respond(
                    UserImageJobResponse(
                        jobId = job.id.toString(),
                        status = job.status.name,
                        conversationId = extractConversationId(job.requestPayload)?.toString(),
                        attemptCount = job.attemptCount,
                        lastError = job.lastError?.take(500),
                        createdAt = job.createdAt.toString(),
                        completedAt = job.completedAt?.toString(),
                    )
                )
            }

            get("/v1/images/jobs/{jobId}/result") {
                val userId = call.requireUser() ?: return@get
                val jobId = call.parseUuid("jobId") ?: return@get
                val job = imageJobRepository.findById(jobId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Job not found", null)))
                    return@get
                }
                if (!call.ownsJob(userId, job.requestPayload)) return@get
                val candidates = imageGenerationService.getCandidates(jobId).map { c ->
                    UserImageCandidateResponse(
                        id = c.id.toString(),
                        contentType = c.contentType,
                        fileSize = c.fileSize,
                        widthPx = c.widthPx,
                        heightPx = c.heightPx,
                        candidateIndex = c.candidateIndex,
                        assetUrl = "/v1/images/assets/${c.id}",
                    )
                }
                call.respond(
                    UserImageResultResponse(
                        jobId = job.id.toString(),
                        status = job.status.name,
                        lastError = job.lastError?.take(500),
                        candidates = candidates,
                    )
                )
            }

            get("/v1/images/assets/{assetId}") {
                val userId = call.requireUser() ?: return@get
                val assetId = call.parseUuid("assetId") ?: return@get
                val candidate = candidateRepository.findById(assetId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Asset not found", null)))
                    return@get
                }
                val job = imageJobRepository.findById(candidate.imageJobId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Job not found", null)))
                    return@get
                }
                if (!call.ownsJob(userId, job.requestPayload)) return@get
                val obj = objectStorage.retrieve(candidate.storageKey) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Asset bytes not found", null)))
                    return@get
                }
                call.respondBytes(obj.content, ContentType.parse(obj.contentType))
            }
        }
    }

    private suspend fun ApplicationCall.requireUser(): UUID? {
        val principal = principal<UserIdPrincipal>()
        if (principal == null) {
            respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
            return null
        }
        return try {
            UUID.fromString(principal.name)
        } catch (_: Exception) {
            respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Invalid user", null)))
            null
        }
    }

    private suspend fun ApplicationCall.parseUuid(name: String): UUID? {
        return try {
            UUID.fromString(parameters[name])
        } catch (_: Exception) {
            respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid $name", null)))
            null
        }
    }

    private suspend fun ApplicationCall.ownsJob(userId: UUID, requestPayload: String): Boolean {
        val conversationId = extractConversationId(requestPayload)
        if (conversationId == null) {
            respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Job is not conversation-scoped", null)))
            return false
        }
        val conversation = conversationRepository.findByIdForUser(conversationId, userId)
        if (conversation == null) {
            respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Not your image job", null)))
            return false
        }
        return true
    }

    private fun extractConversationId(requestPayload: String): UUID? {
        return try {
            val root = json.parseToJsonElement(requestPayload).jsonObject
            val meta = root["metadata"]?.jsonObject
            val fromMeta = meta?.get("conversationId")?.jsonPrimitive?.content
            val fromRoot = root["conversationId"]?.jsonPrimitive?.content
            (fromMeta ?: fromRoot)?.let(UUID::fromString)
        } catch (_: Exception) {
            null
        }
    }
}

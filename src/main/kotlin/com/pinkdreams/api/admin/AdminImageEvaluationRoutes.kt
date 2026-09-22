package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.imaging.evaluation.ImageEvaluationRepository
import com.pinkdreams.imaging.evaluation.ImageModelEvaluationService
import com.pinkdreams.imaging.observability.ImageGenerationEventRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreateImageEvaluationHttpRequest(
    val personaId: String,
    val seedPrompt: String,
    val modelIds: List<String>,
    val candidateCount: Int = 4,
    val visualVersionId: String? = null,
)

@Serializable
data class EvaluationCandidateHttpResponse(
    val id: String,
    val candidateIndex: Int,
    val status: String,
    val adminRemark: String?,
    val assetUrl: String,
    val createdAt: String,
)

@Serializable
data class EvaluationModelHttpResponse(
    val id: String,
    val modelId: String,
    val displayName: String?,
    val provider: String,
    val imageJobId: String?,
    val status: String,
    val jobStatus: String?,
    val lastError: String?,
    val latencyMs: Long?,
    val costAvailability: String,
    val notes: String?,
    val identityConsistency: Int?,
    val sceneAdherence: Int?,
    val poseAdherence: Int?,
    val wardrobeAdherence: Int?,
    val imageQuality: Int?,
    val naturalness: Int?,
    val artifactQuality: Int?,
    val providerRestrictionNotes: String?,
    val candidates: List<EvaluationCandidateHttpResponse>,
)

@Serializable
data class ImageEvaluationHttpResponse(
    val evaluationId: String,
    val personaId: String,
    val personaDisplayName: String?,
    val visualVersionId: String,
    val seedPrompt: String,
    val candidateCount: Int,
    val status: String,
    val productionModelSnapshot: String?,
    val productionModelUnchanged: Boolean,
    val createdAt: String,
    val models: List<EvaluationModelHttpResponse>,
)

@Serializable
data class ImageEvaluationListItemHttpResponse(
    val evaluationId: String,
    val personaId: String,
    val seedPrompt: String,
    val status: String,
    val candidateCount: Int,
    val createdAt: String,
)

@Serializable
data class EvaluationNotesHttpRequest(
    val modelRowId: String,
    val notes: String? = null,
    val identityConsistency: Int? = null,
    val sceneAdherence: Int? = null,
    val poseAdherence: Int? = null,
    val wardrobeAdherence: Int? = null,
    val imageQuality: Int? = null,
    val naturalness: Int? = null,
    val artifactQuality: Int? = null,
    val providerRestrictionNotes: String? = null,
)

@Serializable
data class EvaluationCandidateModelHttpResponse(
    val provider: String,
    val modelId: String,
    val displayName: String,
    val evaluationStatus: String,
    val enabled: Boolean,
)

@Serializable
data class ImageModelDiscoveryItemHttpResponse(
    val modelId: String,
    val displayName: String,
    val description: String?,
    val acceptsImageInput: Boolean,
    val referenceLikelySupported: Boolean,
    val selectedForEvaluation: Boolean,
)

@Serializable
data class ImageModelDiscoveryHttpResponse(
    val catalogCheckedAt: String,
    val catalogSource: String,
    val modelCount: Int,
    val selectedForEvaluation: List<String>,
    val models: List<ImageModelDiscoveryItemHttpResponse>,
    val note: String,
)

class AdminImageEvaluationRoutes(
    private val evaluationService: ImageModelEvaluationService,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            get("/v1/admin/images/evaluation-models") {
                if (!call.requireAdminEval(adminAuthorizationProvider)) return@get
                call.respond(
                    evaluationService.listCandidateModels().map {
                        EvaluationCandidateModelHttpResponse(
                            provider = it["provider"] ?: "openrouter",
                            modelId = it["modelId"] ?: "",
                            displayName = it["displayName"] ?: "",
                            evaluationStatus = it["evaluationStatus"] ?: "candidate",
                            enabled = it["enabled"] != "false",
                        )
                    }
                )
            }

            get("/v1/admin/images/models/discovery") {
                if (!call.requireAdminEval(adminAuthorizationProvider)) return@get
                val key = System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
                if (key == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(
                            ApiError(
                                ErrorCode.INTERNAL_SERVER_ERROR,
                                "OPENROUTER_API_KEY required for live catalog discovery",
                                null,
                            ),
                        ),
                    )
                    return@get
                }
                try {
                    val catalog = com.pinkdreams.imaging.provider.openrouter.OpenRouterImageModelCatalog(key)
                    val models = catalog.fetch()
                    val selected = com.pinkdreams.imaging.provider.openrouter.OpenRouterImageModelCatalog.SELECTED_FOR_EVALUATION
                    call.respond(
                        ImageModelDiscoveryHttpResponse(
                            catalogCheckedAt = java.time.Instant.now().toString(),
                            catalogSource = "https://openrouter.ai/api/v1/images/models",
                            modelCount = models.size,
                            selectedForEvaluation = selected,
                            models = models.map {
                                ImageModelDiscoveryItemHttpResponse(
                                    modelId = it.modelId,
                                    displayName = it.displayName,
                                    description = it.description,
                                    acceptsImageInput = it.acceptsImageInput,
                                    referenceLikelySupported = it.referenceLikelySupported,
                                    selectedForEvaluation = it.modelId in selected,
                                )
                            },
                            note = "Live catalog. Selection for Task 25 is fixed in OpenRouterImageModelCatalog.SELECTED_FOR_EVALUATION until Admin changes OPENROUTER_EVAL_MODELS.",
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponse(
                            ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message?.take(300) ?: "Catalog fetch failed", null),
                        ),
                    )
                }
            }

            post("/v1/admin/images/evaluations") {
                if (!call.requireAdminEval(adminAuthorizationProvider)) return@post
                val body = try {
                    call.receive<CreateImageEvaluationHttpRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)),
                    )
                    return@post
                }
                try {
                    val detail = evaluationService.create(
                        ImageModelEvaluationService.CreateEvaluationCommand(
                            personaId = UUID.fromString(body.personaId),
                            seedPrompt = body.seedPrompt,
                            modelIds = body.modelIds,
                            candidateCount = body.candidateCount,
                            visualVersionId = body.visualVersionId?.let(UUID::fromString),
                            createdBy = call.principal<UserIdPrincipal>()?.name,
                        )
                    )
                    call.respond(HttpStatusCode.Accepted, toResponse(detail))
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

            get("/v1/admin/images/evaluations") {
                if (!call.requireAdminEval(adminAuthorizationProvider)) return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                call.respond(
                    evaluationService.list(limit).map {
                        ImageEvaluationListItemHttpResponse(
                            evaluationId = it.id.toString(),
                            personaId = it.personaId.toString(),
                            seedPrompt = it.seedPrompt,
                            status = it.status,
                            candidateCount = it.candidateCount,
                            createdAt = it.createdAt.toString(),
                        )
                    }
                )
            }

            get("/v1/admin/images/evaluations/{evaluationId}") {
                if (!call.requireAdminEval(adminAuthorizationProvider)) return@get
                val evaluationId = try {
                    UUID.fromString(call.parameters["evaluationId"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid evaluationId", null)),
                    )
                    return@get
                }
                val detail = evaluationService.getDetail(evaluationId)
                if (detail == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Evaluation not found", null)),
                    )
                    return@get
                }
                call.respond(toResponse(detail))
            }

            post("/v1/admin/images/evaluations/{evaluationId}/notes") {
                if (!call.requireAdminEval(adminAuthorizationProvider)) return@post
                val evaluationId = try {
                    UUID.fromString(call.parameters["evaluationId"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid evaluationId", null)),
                    )
                    return@post
                }
                val body = try {
                    call.receive<EvaluationNotesHttpRequest>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)),
                    )
                    return@post
                }
                try {
                    val updated = evaluationService.addNotes(
                        evaluationId = evaluationId,
                        modelRowId = UUID.fromString(body.modelRowId),
                        update = ImageEvaluationRepository.ModelNotesUpdate(
                            notes = body.notes,
                            identityConsistency = body.identityConsistency,
                            sceneAdherence = body.sceneAdherence,
                            poseAdherence = body.poseAdherence,
                            wardrobeAdherence = body.wardrobeAdherence,
                            imageQuality = body.imageQuality,
                            naturalness = body.naturalness,
                            artifactQuality = body.artifactQuality,
                            providerRestrictionNotes = body.providerRestrictionNotes,
                        ),
                    )
                    call.respond(
                        mapOf(
                            "id" to updated.id.toString(),
                            "modelId" to updated.modelId,
                            "notes" to (updated.notes ?: ""),
                            "identityConsistency" to (updated.identityConsistency?.toString() ?: ""),
                            "sceneAdherence" to (updated.sceneAdherence?.toString() ?: ""),
                            "providerRestrictionNotes" to (updated.providerRestrictionNotes ?: ""),
                        )
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
                }
            }
        }
    }

    private fun toResponse(detail: ImageModelEvaluationService.EvaluationDetail): ImageEvaluationHttpResponse {
        val e = detail.evaluation
        return ImageEvaluationHttpResponse(
            evaluationId = e.id.toString(),
            personaId = e.personaId.toString(),
            personaDisplayName = detail.personaDisplayName,
            visualVersionId = e.visualVersionId.toString(),
            seedPrompt = e.seedPrompt,
            candidateCount = e.candidateCount,
            status = e.status,
            productionModelSnapshot = e.productionModelSnapshot,
            productionModelUnchanged = detail.productionModelUnchanged,
            createdAt = e.createdAt.toString(),
            models = detail.models.map { g ->
                EvaluationModelHttpResponse(
                    id = g.modelRow.id.toString(),
                    modelId = g.modelRow.modelId,
                    displayName = g.modelRow.displayName,
                    provider = g.modelRow.provider,
                    imageJobId = g.modelRow.imageJobId?.toString(),
                    status = g.modelRow.status,
                    jobStatus = g.jobStatus,
                    lastError = ImageGenerationEventRepository.redactSecrets(g.lastError),
                    latencyMs = g.latencyMs,
                    costAvailability = g.costAvailability,
                    notes = g.modelRow.notes,
                    identityConsistency = g.modelRow.identityConsistency,
                    sceneAdherence = g.modelRow.sceneAdherence,
                    poseAdherence = g.modelRow.poseAdherence,
                    wardrobeAdherence = g.modelRow.wardrobeAdherence,
                    imageQuality = g.modelRow.imageQuality,
                    naturalness = g.modelRow.naturalness,
                    artifactQuality = g.modelRow.artifactQuality,
                    providerRestrictionNotes = g.modelRow.providerRestrictionNotes,
                    candidates = g.candidates.map { c ->
                        EvaluationCandidateHttpResponse(
                            id = c.id.toString(),
                            candidateIndex = c.candidateIndex,
                            status = c.status.name,
                            adminRemark = c.adminRemark,
                            assetUrl = "/v1/admin/images/assets/${c.id}",
                            createdAt = c.createdAt.toString(),
                        )
                    },
                )
            },
        )
    }
}

private suspend fun ApplicationCall.requireAdminEval(auth: AdminAuthorizationProvider): Boolean {
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

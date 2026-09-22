package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AdminWardrobeItemResponse(
    val id: String,
    val category: String,
    val subcategory: String,
    val name: String,
    val description: String?,
    val color: String?,
    val material: String?,
    val fit: String?,
    val pattern: String?,
    val seasonTags: List<String>,
    val styleTags: List<String>,
    val accessories: List<String>,
    val isAvailable: Boolean,
)

@Serializable
data class AdminReferenceImageResponse(
    val id: String,
    val storageKey: String,
    val contentType: String,
    val fileSize: Long,
    val width: Int?,
    val height: Int?,
    val role: String,
    val status: String,
    val source: String,
    val notes: String?,
    val createdAt: String?,
)

@Serializable
data class AdminGeneratedCandidateResponse(
    val id: String,
    val storageKey: String,
    val contentType: String,
    val fileSize: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val candidateIndex: Int,
    val createdAt: String,
)

@Serializable
data class AdminImageJobResponse(
    val id: String,
    val jobType: String,
    val status: String,
    val idempotencyKey: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val claimedByWorker: String?,
    val lastError: String?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?,
    val candidates: List<AdminGeneratedCandidateResponse>,
)

@Serializable
data class AdminVisualVersionResponse(
    val id: String,
    val personaIdentityId: String,
    val version: Int,
    val status: String,
    // Stored as raw JSON text in persona_visual_versions; surfaced verbatim for
    // read-only display rather than re-parsed here.
    val physicalGuide: String,
    val styleConstraints: String,
    val changelogNote: String?,
    val author: String?,
    val isActive: Boolean,
    val wardrobe: List<AdminWardrobeItemResponse>,
    val referenceImages: List<AdminReferenceImageResponse>,
    val imageJobs: List<AdminImageJobResponse>,
)

@Serializable
data class AdminPersonaVisualResponse(
    val personaId: String,
    val personaIdentityId: String?,
    val hasIdentity: Boolean,
    val activeVisualVersionId: String?,
    val versions: List<AdminVisualVersionResponse>,
    /**
     * True when Application has wired ImageGenerationOrchestrator + worker
     * and POST /v1/admin/images/jobs is available.
     */
    // No Kotlin default: kotlinx-serialization omits default-valued fields from
    // the JSON, and this flag must always be present for the UI to trust it.
    val generationTriggerWired: Boolean,
    val note: String,
)

/**
 * Module — Persona Detail → Visual Identity tab. Strictly read-only over the
 * existing persona_identity / persona_visual_versions / wardrobe / reference
 * image / image job schema. No new tables, no writes, no orchestration calls.
 */
class AdminPersonaVisualRoutes(
    private val personaRepository: PersonaRepository,
    private val personaIdentityRepository: PersonaIdentityRepository,
    private val visualVersionRepository: PersonaVisualVersionRepository,
    private val wardrobeRepository: WardrobeRepository,
    private val referenceImageRepository: ReferenceImageRepository,
    private val imageJobRepository: ImageJobRepository,
    private val generatedCandidateRepository: GeneratedCandidateRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
    private val generationTriggerWired: Boolean = false,
) {
    private companion object {
        const val READ_ONLY_NOTE =
            "Physical guide, style constraints, wardrobe and reference images " +
                "are managed by the visual-identity pipeline."
        const val GENERATE_NOTE =
            "Image generation is wired via POST /v1/admin/images/jobs."
    }

    private val responseNote: String
        get() = if (generationTriggerWired) {
            "$READ_ONLY_NOTE $GENERATE_NOTE"
        } else {
            "$READ_ONLY_NOTE Triggering generation from this tab is not wired — view only."
        }

    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/personas/{personaId}/visual
            get("/v1/admin/personas/{personaId}/visual") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@get
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@get
                }

                val personaId = try {
                    UUID.fromString(call.parameters["personaId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid persona ID format", null)),
                    )
                    return@get
                }

                val persona = personaRepository.findById(personaId)
                if (persona == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Persona not found", null)),
                    )
                    return@get
                }

                val identityId = personaRepository.findPersonaIdentityId(personaId)
                if (identityId == null) {
                    call.respond(
                        HttpStatusCode.OK,
                        AdminPersonaVisualResponse(
                            personaId = personaId.toString(),
                            personaIdentityId = null,
                            hasIdentity = false,
                            activeVisualVersionId = null,
                            versions = emptyList(),
                            generationTriggerWired = generationTriggerWired,
                            note = "This persona has no visual identity linked yet. $responseNote",
                        ),
                    )
                    return@get
                }

                val activeVisualVersionId = personaIdentityRepository.findById(identityId)?.activeVisualVersionId
                val versions = visualVersionRepository.findForPersonaIdentity(identityId)
                    .sortedBy { it.version }
                    .map { v ->
                        AdminVisualVersionResponse(
                            id = v.id.toString(),
                            personaIdentityId = v.personaIdentityId.toString(),
                            version = v.version,
                            status = v.status,
                            physicalGuide = v.physicalGuide,
                            styleConstraints = v.styleConstraints,
                            changelogNote = v.changelogNote,
                            author = v.author,
                            isActive = v.id == activeVisualVersionId,
                            wardrobe = wardrobeRepository.findItemsForVersion(v.id).map { w ->
                                AdminWardrobeItemResponse(
                                    id = w.id.toString(),
                                    category = w.category,
                                    subcategory = w.subcategory,
                                    name = w.name,
                                    description = w.description,
                                    color = w.color,
                                    material = w.material,
                                    fit = w.fit,
                                    pattern = w.pattern,
                                    seasonTags = w.seasonTags,
                                    styleTags = w.styleTags,
                                    accessories = w.accessories,
                                    isAvailable = w.isAvailable,
                                )
                            },
                            referenceImages = referenceImageRepository.findForVersion(v.id).map { r ->
                                AdminReferenceImageResponse(
                                    id = r.id.toString(),
                                    storageKey = r.storageKey,
                                    contentType = r.contentType,
                                    fileSize = r.fileSize,
                                    width = r.width,
                                    height = r.height,
                                    role = r.role.name,
                                    status = r.status.name,
                                    source = r.source.name,
                                    notes = r.notes,
                                    createdAt = r.createdAt?.toString(),
                                )
                            },
                            imageJobs = imageJobRepository.findForVersion(v.id).map { j ->
                                AdminImageJobResponse(
                                    id = j.id.toString(),
                                    jobType = j.jobType.name,
                                    status = j.status.name,
                                    idempotencyKey = j.idempotencyKey,
                                    attemptCount = j.attemptCount,
                                    maxAttempts = j.maxAttempts,
                                    claimedByWorker = j.claimedByWorker,
                                    lastError = j.lastError,
                                    createdAt = j.createdAt.toString(),
                                    startedAt = j.startedAt?.toString(),
                                    completedAt = j.completedAt?.toString(),
                                    candidates = generatedCandidateRepository.findByImageJob(j.id).map { c ->
                                        AdminGeneratedCandidateResponse(
                                            id = c.id.toString(),
                                            storageKey = c.storageKey,
                                            contentType = c.contentType,
                                            fileSize = c.fileSize,
                                            widthPx = c.widthPx,
                                            heightPx = c.heightPx,
                                            candidateIndex = c.candidateIndex,
                                            createdAt = c.createdAt.toString(),
                                        )
                                    },
                                )
                            },
                        )
                    }

                call.respond(
                    HttpStatusCode.OK,
                    AdminPersonaVisualResponse(
                        personaId = personaId.toString(),
                        personaIdentityId = identityId.toString(),
                        hasIdentity = true,
                        activeVisualVersionId = activeVisualVersionId?.toString(),
                        versions = versions,
                        generationTriggerWired = generationTriggerWired,
                        note = responseNote,
                    ),
                )
            }
        }
    }
}

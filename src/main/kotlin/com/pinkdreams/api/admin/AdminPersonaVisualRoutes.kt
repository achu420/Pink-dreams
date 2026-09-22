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
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import com.pinkdreams.visual.identity.PhysicalGuide
import com.pinkdreams.visual.identity.PrivateVisualGuide
import com.pinkdreams.visual.identity.ReferenceRole
import com.pinkdreams.visual.identity.ImagePipelineTestPersonaSeeder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    val assetUrl: String? = null,
    val isPrivate: Boolean = false,
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
    // display rather than re-parsed here.
    val physicalGuide: String,
    val privateGuide: String,
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

@Serializable
data class AdminEnsureVisualDraftResponse(
    val identityId: String,
    val draftVersionId: String,
    val draftVersion: Int,
    val createdIdentity: Boolean,
    val createdDraft: Boolean,
)

@Serializable
data class SeededPersonaHttpResponse(
    val slug: String,
    val personaId: String,
    val referencesUploaded: Int,
    val created: Boolean,
)

@Serializable
data class SeedImageFixturesHttpResponse(
    val seeded: List<SeededPersonaHttpResponse>,
)

@Serializable
data class AdminPublishActivateResponse(
    val id: String,
    val status: String,
    val version: Int,
)

/**
 * Accepts styleConstraints as a JSON string or a nested JSON object/array;
 * always stores/returns a JSON string.
 */
object StyleConstraintsAsStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StyleConstraintsAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> element.contentOrNull ?: element.toString()
            else -> element.toString()
        }
    }
}

@Serializable
data class StyleConstraintsUpdateRequest(
    @Serializable(with = StyleConstraintsAsStringSerializer::class)
    val styleConstraints: String,
)

@Serializable
data class ReferenceNotesUpdateRequest(
    val notes: String? = null,
)

/**
 * Module — Persona Detail → Visual Identity tab.
 * Admin can read all versions and edit the draft visual identity
 * (guides, style constraints, references), then publish/activate.
 */
class AdminPersonaVisualRoutes(
    private val personaRepository: PersonaRepository,
    private val personaIdentityRepository: PersonaIdentityRepository,
    private val visualVersionRepository: PersonaVisualVersionRepository,
    private val wardrobeRepository: WardrobeRepository,
    private val referenceImageRepository: ReferenceImageRepository,
    private val imageJobRepository: ImageJobRepository,
    private val generatedCandidateRepository: GeneratedCandidateRepository,
    private val personalGuideRepository: PersonalGuideRepository,
    private val visualAdminService: PersonaVisualAdminService,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
    private val generationTriggerWired: Boolean = false,
) {
    // Kept for constructor parity with Application wiring / future direct guide reads.
    @Suppress("unused")
    private val guides = personalGuideRepository

    private companion object {
        const val EDITABLE_NOTE =
            "Admin can edit draft visual identity (physical guide, private guide, " +
                "style constraints, references) and publish/activate."
        const val GENERATE_NOTE =
            "Image generation is wired via POST /v1/admin/images/jobs."
    }

    private val responseNote: String
        get() = if (generationTriggerWired) {
            "$EDITABLE_NOTE $GENERATE_NOTE"
        } else {
            "$EDITABLE_NOTE Triggering generation from this tab is not wired — use jobs API when available."
        }

    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/personas/{personaId}/visual
            get("/v1/admin/personas/{personaId}/visual") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get

                val personaId = call.parseUuid("personaId") ?: return@get

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
                    .map { v -> toVersionResponse(personaId, v, activeVisualVersionId) }

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

            // POST /v1/admin/personas/{personaId}/visual/ensure
            post("/v1/admin/personas/{personaId}/visual/ensure") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val personaId = call.parseUuid("personaId") ?: return@post
                val author = call.principal<UserIdPrincipal>()?.name

                try {
                    if (personaRepository.findById(personaId) == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Persona not found", null)),
                        )
                        return@post
                    }
                    val result = visualAdminService.ensureDraft(personaId, author = author)
                    call.respond(
                        HttpStatusCode.OK,
                        AdminEnsureVisualDraftResponse(
                            identityId = result.identityId.toString(),
                            draftVersionId = result.draftVersionId.toString(),
                            draftVersion = result.draftVersion,
                            createdIdentity = result.createdIdentity,
                            createdDraft = result.createdDraft,
                        ),
                    )
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // POST /v1/admin/personas/seed-image-test-fixtures — Ananya + Richa from docs/23 sept
            post("/v1/admin/personas/seed-image-test-fixtures") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                try {
                    val seeder = ImagePipelineTestPersonaSeeder(
                        personaRepository = personaRepository,
                        visualAdminService = visualAdminService,
                    )
                    val results = seeder.seedAll()
                    call.respond(
                        HttpStatusCode.OK,
                        SeedImageFixturesHttpResponse(
                            seeded = results.map {
                                SeededPersonaHttpResponse(
                                    slug = it.slug,
                                    personaId = it.personaId,
                                    referencesUploaded = it.referencesUploaded,
                                    created = it.created,
                                )
                            },
                        ),
                    )
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // PUT /v1/admin/personas/{personaId}/visual/physical-guide
            put("/v1/admin/personas/{personaId}/visual/physical-guide") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@put
                val personaId = call.parseUuid("personaId") ?: return@put
                try {
                    val guide = call.receive<PhysicalGuide>()
                    val updated = visualAdminService.updatePhysicalGuide(personaId, guide)
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // PUT /v1/admin/personas/{personaId}/visual/private-guide
            put("/v1/admin/personas/{personaId}/visual/private-guide") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@put
                val personaId = call.parseUuid("personaId") ?: return@put
                try {
                    val guide = call.receive<PrivateVisualGuide>()
                    val updated = visualAdminService.updatePrivateGuide(personaId, guide)
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // PUT /v1/admin/personas/{personaId}/visual/style-constraints
            put("/v1/admin/personas/{personaId}/visual/style-constraints") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@put
                val personaId = call.parseUuid("personaId") ?: return@put
                try {
                    val body = call.receive<StyleConstraintsUpdateRequest>()
                    val updated = visualAdminService.updateStyleConstraints(personaId, body.styleConstraints)
                    call.respond(
                        HttpStatusCode.OK,
                        StyleConstraintsUpdateRequest(styleConstraints = updated),
                    )
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // POST /v1/admin/personas/{personaId}/visual/publish-activate
            post("/v1/admin/personas/{personaId}/visual/publish-activate") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val personaId = call.parseUuid("personaId") ?: return@post
                try {
                    val version = visualAdminService.publishAndActivateDraft(personaId)
                    call.respond(
                        HttpStatusCode.OK,
                        AdminPublishActivateResponse(
                            id = version.id.toString(),
                            status = version.status,
                            version = version.version,
                        ),
                    )
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // POST /v1/admin/personas/{personaId}/visual/references
            post("/v1/admin/personas/{personaId}/visual/references") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@post
                val personaId = call.parseUuid("personaId") ?: return@post

                var fileBytes: ByteArray? = null
                var contentType = "image/jpeg"
                var roleRaw: String? = null
                var notes: String? = null
                var finalize = true

                try {
                    call.receiveMultipart().forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                if (part.name == "file" || fileBytes == null) {
                                    // Ktor 2.3 FileItem.provider() returns Input (not ByteReadChannel).
                                    fileBytes = part.provider().readBytes()
                                    contentType = part.contentType?.toString() ?: "image/jpeg"
                                }
                            }
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "role" -> roleRaw = part.value
                                    "notes" -> notes = part.value
                                    "finalize" -> finalize = part.value.equals("true", ignoreCase = true)
                                }
                            }
                            else -> Unit
                        }
                        part.dispose()
                    }

                    val bytes = fileBytes
                    if (bytes == null || bytes.isEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Multipart part 'file' is required", null)),
                        )
                        return@post
                    }
                    val roleName = roleRaw?.trim().orEmpty()
                    if (roleName.isEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Multipart part 'role' is required", null)),
                        )
                        return@post
                    }
                    val role = try {
                        ReferenceRole.valueOf(roleName.uppercase())
                    } catch (_: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid reference role: $roleName", null)),
                        )
                        return@post
                    }

                    val uploaded = visualAdminService.uploadReference(
                        personaId = personaId,
                        role = role,
                        content = bytes,
                        contentType = contentType,
                        notes = notes,
                        finalize = finalize,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        toReferenceResponse(personaId, uploaded),
                    )
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // GET /v1/admin/personas/{personaId}/visual/references/{referenceId}/content
            get("/v1/admin/personas/{personaId}/visual/references/{referenceId}/content") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@get
                val personaId = call.parseUuid("personaId") ?: return@get
                val referenceId = call.parseUuid("referenceId") ?: return@get

                val identityId = personaRepository.findPersonaIdentityId(personaId)
                if (identityId == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Persona visual identity not found", null)),
                    )
                    return@get
                }

                val pair = referenceImageRepository.retrieveContentWithMeta(referenceId)
                if (pair == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Reference content not found", null)),
                    )
                    return@get
                }
                val (ref, bytes) = pair
                val version = visualVersionRepository.findById(ref.personaVisualVersionId)
                if (version == null || version.personaIdentityId != identityId) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Reference not found for persona", null)),
                    )
                    return@get
                }

                val parsedType = try {
                    ContentType.parse(ref.contentType)
                } catch (_: Exception) {
                    ContentType.Application.OctetStream
                }
                call.respondBytes(bytes, parsedType)
            }

            // DELETE /v1/admin/personas/{personaId}/visual/references/{referenceId}
            delete("/v1/admin/personas/{personaId}/visual/references/{referenceId}") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@delete
                val personaId = call.parseUuid("personaId") ?: return@delete
                val referenceId = call.parseUuid("referenceId") ?: return@delete
                try {
                    visualAdminService.removeReference(personaId, referenceId)
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }

            // PATCH /v1/admin/personas/{personaId}/visual/references/{referenceId}
            patch("/v1/admin/personas/{personaId}/visual/references/{referenceId}") {
                if (!call.requireAdmin(adminAuthorizationProvider)) return@patch
                val personaId = call.parseUuid("personaId") ?: return@patch
                val referenceId = call.parseUuid("referenceId") ?: return@patch
                try {
                    val body = call.receive<ReferenceNotesUpdateRequest>()
                    val updated = visualAdminService.updateReferenceNotes(personaId, referenceId, body.notes)
                    call.respond(HttpStatusCode.OK, toReferenceResponse(personaId, updated))
                } catch (e: Exception) {
                    call.respondMappedVisualError(e)
                }
            }
        }
    }

    private fun toVersionResponse(
        personaId: UUID,
        v: PersonaVisualVersionRepository.PersonaVisualVersion,
        activeVisualVersionId: UUID?,
    ): AdminVisualVersionResponse =
        AdminVisualVersionResponse(
            id = v.id.toString(),
            personaIdentityId = v.personaIdentityId.toString(),
            version = v.version,
            status = v.status,
            physicalGuide = v.physicalGuide,
            privateGuide = v.privateGuide,
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
                toReferenceResponse(personaId, r)
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

    private fun toReferenceResponse(
        personaId: UUID,
        r: com.pinkdreams.visual.identity.ReferenceImage,
    ): AdminReferenceImageResponse =
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
            assetUrl = "/v1/admin/personas/$personaId/visual/references/${r.id}/content",
            isPrivate = r.role == ReferenceRole.PRIVATE,
        )
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

private suspend fun ApplicationCall.respondMappedVisualError(e: Exception) {
    when (e) {
        is IllegalArgumentException -> respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation failed", null)),
        )
        is IllegalStateException -> {
            val message = e.message ?: "Conflict"
            // Missing draft / ensure-first is a conflict; other state errors stay 400.
            val status = if (
                message.contains("ensure", ignoreCase = true) ||
                message.contains("no draft", ignoreCase = true) ||
                message.contains("no visual identity", ignoreCase = true)
            ) {
                HttpStatusCode.Conflict
            } else {
                HttpStatusCode.BadRequest
            }
            respond(
                status,
                ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, message, null)),
            )
        }
        is kotlinx.serialization.SerializationException -> respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Invalid request body", null)),
        )
        else -> respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message ?: "Internal error", null)),
        )
    }
}

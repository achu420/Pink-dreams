package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.patch
import io.ktor.server.auth.authenticate
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreatePersonaRequest(
    val slug: String,
    val displayName: String,
    val gender: String,
    val orientation: String,
    val apparentAge: Int,
    val languageProfile: Map<String, String> = emptyMap(),
)

@Serializable
data class UpdatePersonaRequest(
    val displayName: String? = null,
    val gender: String? = null,
    val orientation: String? = null,
    val apparentAge: Int? = null,
    val languageProfile: Map<String, String>? = null,
    // PROFILE metadata. Omitted => unchanged; empty string / empty list => cleared.
    val bio: String? = null,
    val city: String? = null,
    val occupation: String? = null,
    val interests: String? = null,
    val tags: List<String>? = null,
)

/**
 * IDENTITY (slug, displayName, gender, orientation, apparentAge, status) and
 * PROFILE (bio, city, occupation, interests, tags) in one payload but as
 * clearly distinct groups. Neither is the Persona Core — that is versioned
 * separately and fetched through the versions endpoints.
 */
@Serializable
data class PersonaResponse(
    val id: String,
    val slug: String,
    val displayName: String,
    val gender: String,
    val orientation: String,
    val apparentAge: Int,
    val status: String,
    val activeCoreVersionId: String?,
    val bio: String? = null,
    val city: String? = null,
    val occupation: String? = null,
    val interests: String? = null,
    val tags: List<String> = emptyList(),
)

/** Single mapping point, so a new profile field cannot be forgotten on one route. */
internal fun personaResponse(p: PersonaRepository.Persona) = PersonaResponse(
    id = p.id.toString(),
    slug = p.slug,
    displayName = p.displayName,
    gender = p.gender,
    orientation = p.orientation,
    apparentAge = p.apparentAge,
    status = p.status,
    activeCoreVersionId = p.activeCoreVersionId?.toString(),
    bio = p.bio,
    city = p.city,
    occupation = p.occupation,
    interests = p.interests,
    tags = p.tags,
)

@Serializable
data class PersonaListResponse(
    val personas: List<PersonaResponse>,
)

@Serializable
data class CreateCoreVersionRequest(
    val content: String,
    val changelogNote: String? = null,
)

@Serializable
data class CoreVersionResponse(
    val id: String,
    val personaId: String,
    val version: Int,
    val content: String,
    val status: String,
    val changelogNote: String?,
    val author: String?,
    val isActive: Boolean,
)

@Serializable
data class CoreVersionListResponse(
    val versions: List<CoreVersionResponse>,
)

class AdminPersonaRoutes(
    private val personaRepository: PersonaRepository,
    private val coreVersionRepository: PersonaCoreVersionRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    // Persona core versions have no isActive column of their own (unlike
    // ConversationEngines) — "active" is a pointer (Personas.active_core_version_id)
    // on the parent persona row, so every response must look it up explicitly
    // rather than reading a field off the version itself.
    private fun isActiveVersion(personaId: UUID, versionId: UUID): Boolean =
        personaRepository.findById(personaId)?.activeCoreVersionId == versionId

    fun register(route: Route) {
        // Serves the admin UI HTML itself. Gated by the admin session cookie
        // check in Application.kt's intercept (see AdminSessionAuth) — this
        // route body has no auth logic of its own, matching how every other
        // admin route below also defers to a shared mechanism rather than
        // re-implementing it.
        route.get("/admin") {
            val resource = Thread.currentThread().contextClassLoader.getResource("admin-ui.html")
            if (resource != null) {
                try {
                    val html = resource.readText()
                    call.respondText(html, ContentType.Text.Html)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to load admin UI")
                }
            } else {
                call.respond(HttpStatusCode.NotFound, "Admin UI not found")
            }
        }

        route.authenticate("session-auth", "dev-auth") {
            // POST /v1/admin/personas
            post("/v1/admin/personas") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@post
                }

                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@post
                }

                val request = try {
                    call.receive<CreatePersonaRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                try {
                    val persona = personaRepository.create(
                        slug = request.slug,
                        displayName = request.displayName,
                        gender = request.gender,
                        orientation = request.orientation,
                        apparentAge = request.apparentAge,
                        languageProfile = request.languageProfile,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        personaResponse(persona),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create persona", null)),
                    )
                }
            }

            // GET /v1/admin/personas
            get("/v1/admin/personas") {
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

                // Phase ADMIN-3: hidden memory-scope personas created for Test
                // Chat conversations (see TestChatService) are internal
                // bookkeeping, never a persona an admin can select or edit.
                val personas = personaRepository.findAll().filterNot { it.slug.startsWith("__test_memory_scope__") }
                call.respond(
                    HttpStatusCode.OK,
                    PersonaListResponse(
                        personas = personas.map { p ->
                            personaResponse(p)
                        },
                    ),
                )
            }

            // PATCH /v1/admin/personas/{personaId}
            patch("/v1/admin/personas/{personaId}") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@patch
                }

                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@patch
                }

                val personaId = try {
                    UUID.fromString(call.parameters["personaId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid persona ID format", null)),
                    )
                    return@patch
                }

                val request = try {
                    call.receive<UpdatePersonaRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@patch
                }

                try {
                    val persona = personaRepository.update(
                        id = personaId,
                        displayName = request.displayName,
                        gender = request.gender,
                        orientation = request.orientation,
                        apparentAge = request.apparentAge,
                        bio = request.bio,
                        city = request.city,
                        occupation = request.occupation,
                        interests = request.interests,
                        tags = request.tags,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        personaResponse(persona),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Persona not found", null)),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to update persona", null)),
                    )
                }
            }

            // GET /v1/admin/personas/{personaId}/core-versions
            get("/v1/admin/personas/{personaId}/core-versions") {
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

                val versions = coreVersionRepository.findForPersona(personaId)
                val activeCoreVersionId = personaRepository.findById(personaId)?.activeCoreVersionId
                call.respond(
                    HttpStatusCode.OK,
                    CoreVersionListResponse(
                        versions = versions.map { v ->
                            CoreVersionResponse(
                                id = v.id.toString(),
                                personaId = v.personaId.toString(),
                                version = v.version,
                                content = v.content,
                                status = v.status,
                                changelogNote = v.changelogNote,
                                author = v.author,
                                isActive = v.id == activeCoreVersionId,
                            )
                        },
                    ),
                )
            }


            // POST /v1/admin/personas/{personaId}/core-versions
            post("/v1/admin/personas/{personaId}/core-versions") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@post
                }

                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@post
                }

                val personaId = try {
                    UUID.fromString(call.parameters["personaId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid persona ID format", null)),
                    )
                    return@post
                }

                val request = try {
                    call.receive<CreateCoreVersionRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                try {
                    // Version number is always server-computed (persona's current
                    // max version + 1) — the client cannot supply or influence it.
                    val version = coreVersionRepository.createNextVersion(
                        personaId = personaId,
                        content = request.content,
                        changelogNote = request.changelogNote,
                        author = principal.name,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        CoreVersionResponse(
                            id = version.id.toString(),
                            personaId = version.personaId.toString(),
                            version = version.version,
                            content = version.content,
                            status = version.status,
                            changelogNote = version.changelogNote,
                            author = version.author,
                            isActive = false,
                        ),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create core version", null)),
                    )
                }
            }

            // POST /v1/admin/personas/{personaId}/core-versions/{versionId}/publish
            post("/v1/admin/personas/{personaId}/core-versions/{versionId}/publish") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@post
                }

                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@post
                }

                val versionId = try {
                    UUID.fromString(call.parameters["versionId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid version ID format", null)),
                    )
                    return@post
                }

                try {
                    val version = coreVersionRepository.publishCoreVersion(versionId)
                    call.respond(
                        HttpStatusCode.OK,
                        CoreVersionResponse(
                            id = version.id.toString(),
                            personaId = version.personaId.toString(),
                            version = version.version,
                            content = version.content,
                            status = version.status,
                            changelogNote = version.changelogNote,
                            author = version.author,
                            isActive = false,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Version not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }

            // POST /v1/admin/personas/{personaId}/core-versions/{versionId}/activate
            post("/v1/admin/personas/{personaId}/core-versions/{versionId}/activate") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@post
                }

                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@post
                }

                val personaId = try {
                    UUID.fromString(call.parameters["personaId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid persona ID format", null)),
                    )
                    return@post
                }

                val versionId = try {
                    UUID.fromString(call.parameters["versionId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid version ID format", null)),
                    )
                    return@post
                }

                try {
                    personaRepository.activateCoreVersion(personaId, versionId)
                    val version = coreVersionRepository.findById(versionId)
                    if (version != null) {
                        call.respond(
                            HttpStatusCode.OK,
                            CoreVersionResponse(
                                id = version.id.toString(),
                                personaId = version.personaId.toString(),
                                version = version.version,
                                content = version.content,
                                status = version.status,
                                changelogNote = version.changelogNote,
                                author = version.author,
                                isActive = true,
                            ),
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Version not found", null)),
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Resource not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }

            // POST /v1/admin/personas/{personaId}/core-versions/{versionId}/archive
            post("/v1/admin/personas/{personaId}/core-versions/{versionId}/archive") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@post
                }

                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@post
                }

                val versionId = try {
                    UUID.fromString(call.parameters["versionId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid version ID format", null)),
                    )
                    return@post
                }

                try {
                    val version = coreVersionRepository.archiveCoreVersion(versionId)
                    call.respond(
                        HttpStatusCode.OK,
                        CoreVersionResponse(
                            id = version.id.toString(),
                            personaId = version.personaId.toString(),
                            version = version.version,
                            content = version.content,
                            status = version.status,
                            changelogNote = version.changelogNote,
                            author = version.author,
                            isActive = false,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Version not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }
        }
    }
}

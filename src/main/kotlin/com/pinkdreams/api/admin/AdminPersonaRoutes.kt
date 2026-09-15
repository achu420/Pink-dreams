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
data class PersonaResponse(
    val id: String,
    val slug: String,
    val displayName: String,
    val gender: String,
    val orientation: String,
    val apparentAge: Int,
    val status: String,
    val activeCoreVersionId: String?,
)

@Serializable
data class PersonaListResponse(
    val personas: List<PersonaResponse>,
)

@Serializable
data class CreateCoreVersionRequest(
    val version: Int,
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
    fun register(route: Route) {
        // Serve admin UI without auth (public access to QA console)
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

        route.authenticate("dev-auth") {
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
                        PersonaResponse(
                            id = persona.id.toString(),
                            slug = persona.slug,
                            displayName = persona.displayName,
                            gender = persona.gender,
                            orientation = persona.orientation,
                            apparentAge = persona.apparentAge,
                            status = persona.status,
                            activeCoreVersionId = persona.activeCoreVersionId?.toString(),
                        ),
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

                val personas = personaRepository.findAll()
                call.respond(
                    HttpStatusCode.OK,
                    PersonaListResponse(
                        personas = personas.map { p ->
                            PersonaResponse(
                                id = p.id.toString(),
                                slug = p.slug,
                                displayName = p.displayName,
                                gender = p.gender,
                                orientation = p.orientation,
                                apparentAge = p.apparentAge,
                                status = p.status,
                                activeCoreVersionId = p.activeCoreVersionId?.toString(),
                            )
                        },
                    ),
                )
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
                    val version = coreVersionRepository.create(
                        personaId = personaId,
                        version = request.version,
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

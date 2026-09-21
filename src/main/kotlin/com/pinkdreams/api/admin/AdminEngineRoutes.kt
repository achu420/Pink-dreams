package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.auth.authenticate
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreateEngineRequest(
    val content: String,
    val changelogNote: String? = null,
)

@Serializable
data class EngineResponse(
    val id: String,
    val version: Int,
    val content: String,
    val status: String,
    val isActive: Boolean,
    val changelogNote: String?,
    val createdBy: String?,
)

@Serializable
data class EngineListResponse(
    val engines: List<EngineResponse>,
)

class AdminEngineRoutes(
    private val engineRepository: ConversationEngineRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/engines
            get("/v1/admin/engines") {
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

                val engines = engineRepository.findAll()
                call.respond(
                    HttpStatusCode.OK,
                    EngineListResponse(
                        engines = engines.map { e ->
                            EngineResponse(
                                id = e.id.toString(),
                                version = e.version,
                                content = e.content,
                                status = e.status,
                                isActive = e.isActive,
                                changelogNote = e.changelogNote,
                                createdBy = e.createdBy,
                            )
                        },
                    ),
                )
            }

            // GET /v1/admin/engines/{engineId}
            get("/v1/admin/engines/{engineId}") {
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

                val engineId = try {
                    UUID.fromString(call.parameters["engineId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid engine ID format", null)),
                    )
                    return@get
                }

                val engine = engineRepository.findById(engineId)
                if (engine == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Engine not found", null)),
                    )
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    EngineResponse(
                        id = engine.id.toString(),
                        version = engine.version,
                        content = engine.content,
                        status = engine.status,
                        isActive = engine.isActive,
                        changelogNote = engine.changelogNote,
                        createdBy = engine.createdBy,
                    ),
                )
            }

            // POST /v1/admin/engines
            post("/v1/admin/engines") {
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
                    call.receive<CreateEngineRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                try {
                    // Version number is always server-computed (current global max
                    // version + 1) — the client cannot supply or influence it.
                    val engine = engineRepository.createNextVersion(
                        content = request.content,
                        changelogNote = request.changelogNote,
                        createdBy = principal.name,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        EngineResponse(
                            id = engine.id.toString(),
                            version = engine.version,
                            content = engine.content,
                            status = engine.status,
                            isActive = engine.isActive,
                            changelogNote = engine.changelogNote,
                            createdBy = engine.createdBy,
                        ),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create engine", null)),
                    )
                }
            }

            // POST /v1/admin/engines/{engineId}/publish
            post("/v1/admin/engines/{engineId}/publish") {
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

                val engineId = try {
                    UUID.fromString(call.parameters["engineId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid engine ID format", null)),
                    )
                    return@post
                }

                try {
                    val engine = engineRepository.publishEngine(engineId)
                    call.respond(
                        HttpStatusCode.OK,
                        EngineResponse(
                            id = engine.id.toString(),
                            version = engine.version,
                            content = engine.content,
                            status = engine.status,
                            isActive = engine.isActive,
                            changelogNote = engine.changelogNote,
                            createdBy = engine.createdBy,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Engine not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }

            // POST /v1/admin/engines/{engineId}/activate
            post("/v1/admin/engines/{engineId}/activate") {
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

                val engineId = try {
                    UUID.fromString(call.parameters["engineId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid engine ID format", null)),
                    )
                    return@post
                }

                try {
                    val engine = engineRepository.activateEngine(engineId)
                    call.respond(
                        HttpStatusCode.OK,
                        EngineResponse(
                            id = engine.id.toString(),
                            version = engine.version,
                            content = engine.content,
                            status = engine.status,
                            isActive = engine.isActive,
                            changelogNote = engine.changelogNote,
                            createdBy = engine.createdBy,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Engine not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }

            // POST /v1/admin/engines/{engineId}/archive
            post("/v1/admin/engines/{engineId}/archive") {
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

                val engineId = try {
                    UUID.fromString(call.parameters["engineId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid engine ID format", null)),
                    )
                    return@post
                }

                try {
                    val engine = engineRepository.archiveEngine(engineId)
                    call.respond(
                        HttpStatusCode.OK,
                        EngineResponse(
                            id = engine.id.toString(),
                            version = engine.version,
                            content = engine.content,
                            status = engine.status,
                            isActive = engine.isActive,
                            changelogNote = engine.changelogNote,
                            createdBy = engine.createdBy,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Engine not found", null)),
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

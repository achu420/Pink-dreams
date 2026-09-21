package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
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
data class CreateMemoryEngineRequest(
    val content: String,
    val changelogNote: String? = null,
    // Operational configuration travels with the version. Omitted => baseline defaults.
    val batchSize: Int? = null,
    val relevantMemoryTarget: Int? = null,
)

@Serializable
data class MemoryEngineResponse(
    val id: String,
    val version: Int,
    val content: String,
    val status: String,
    val isActive: Boolean,
    val changelogNote: String?,
    val createdBy: String?,
    val batchSize: Int,
    val relevantMemoryTarget: Int,
)

@Serializable
data class MemoryEngineListResponse(
    val memoryEngines: List<MemoryEngineResponse>,
)

/** Admin-only Memory Engine versioning — an exact structural mirror of AdminEngineRoutes. */
class AdminMemoryEngineRoutes(
    private val memoryEngineRepository: MemoryEngineRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/memory-engines
            get("/v1/admin/memory-engines") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
                    return@get
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
                    return@get
                }
                val engines = memoryEngineRepository.findAll()
                call.respond(HttpStatusCode.OK, MemoryEngineListResponse(memoryEngines = engines.map(::toResponse)))
            }

            // GET /v1/admin/memory-engines/{id}
            get("/v1/admin/memory-engines/{id}") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
                    return@get
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
                    return@get
                }
                val id = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid memory engine ID format", null)))
                    return@get
                }
                val engine = memoryEngineRepository.findById(id)
                if (engine == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Memory engine not found", null)))
                    return@get
                }
                call.respond(HttpStatusCode.OK, toResponse(engine))
            }

            // POST /v1/admin/memory-engines
            post("/v1/admin/memory-engines") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
                    return@post
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
                    return@post
                }
                val request = try {
                    call.receive<CreateMemoryEngineRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)))
                    return@post
                }
                try {
                    // Version number is always server-computed (current global max
                    // version + 1) — the client cannot supply or influence it.
                    val engine = memoryEngineRepository.createNextVersion(
                        content = request.content,
                        changelogNote = request.changelogNote,
                        createdBy = principal.name,
                        batchSize = request.batchSize ?: MemoryEngineRepository.DEFAULT_BATCH_SIZE,
                        relevantMemoryTarget = request.relevantMemoryTarget ?: MemoryEngineRepository.DEFAULT_RELEVANT_MEMORY_TARGET,
                    )
                    call.respond(HttpStatusCode.Created, toResponse(engine))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create memory engine", null)))
                }
            }

            // POST /v1/admin/memory-engines/{id}/publish
            post("/v1/admin/memory-engines/{id}/publish") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
                    return@post
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
                    return@post
                }
                val id = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid memory engine ID format", null)))
                    return@post
                }
                try {
                    call.respond(HttpStatusCode.OK, toResponse(memoryEngineRepository.publish(id)))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Memory engine not found", null)))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)))
                }
            }

            // POST /v1/admin/memory-engines/{id}/activate
            post("/v1/admin/memory-engines/{id}/activate") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
                    return@post
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
                    return@post
                }
                val id = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid memory engine ID format", null)))
                    return@post
                }
                try {
                    call.respond(HttpStatusCode.OK, toResponse(memoryEngineRepository.activate(id)))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Memory engine not found", null)))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)))
                }
            }

            // POST /v1/admin/memory-engines/{id}/archive
            post("/v1/admin/memory-engines/{id}/archive") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
                    return@post
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
                    return@post
                }
                val id = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid memory engine ID format", null)))
                    return@post
                }
                try {
                    call.respond(HttpStatusCode.OK, toResponse(memoryEngineRepository.archive(id)))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Memory engine not found", null)))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)))
                }
            }
        }
    }

    private fun toResponse(engine: MemoryEngineRepository.MemoryEngine): MemoryEngineResponse = MemoryEngineResponse(
        id = engine.id.toString(),
        version = engine.version,
        content = engine.content,
        status = engine.status,
        isActive = engine.isActive,
        changelogNote = engine.changelogNote,
        createdBy = engine.createdBy,
        batchSize = engine.batchSize,
        relevantMemoryTarget = engine.relevantMemoryTarget,
    )
}

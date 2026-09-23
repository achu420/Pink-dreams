package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AdminCreateMemoryFactRequest(
    val userId: String,
    val personaId: String,
    val content: String,
    val factType: String = "interest",
    val criticality: String = "medium",
    val tier: String = "hot",
    val status: String = "open",
    val source: String = "manual",
    val owner: String = "USER",
)

@Serializable
data class AdminPatchMemoryFactRequest(
    /** New fact text. Omit to leave content unchanged. */
    val content: String? = null,
    /** New status. Allowed values: open, resolved, removed. Omit to leave unchanged. */
    val status: String? = null,
)

@Serializable
data class AdminMemoryFactResponse(
    val id: String,
    val userId: String,
    val personaId: String,
    val personaDisplayName: String?,
    val content: String,
    val factType: String,
    val criticality: String,
    val tier: String,
    val status: String,
    val owner: String,
    val source: String,
    val learnedAt: String,
    val updatedAt: String?,
    val supersedesId: String?,
)

@Serializable
data class AdminMemoryFactListResponse(
    val memoryFacts: List<AdminMemoryFactResponse>,
)

/**
 * Admin CRUD for memory_facts — complements the read-only
 * GET /v1/admin/users/{userId}/memory already on AdminUserRoutes.
 *
 * Endpoints:
 *   GET    /v1/admin/memory-facts?userId=&personaId=   list (relationship or per-user)
 *   POST   /v1/admin/memory-facts                       create
 *   PATCH  /v1/admin/memory-facts/{factId}              update content / status
 *   DELETE /v1/admin/memory-facts/{factId}              soft-delete (status→removed)
 */
class AdminMemoryFactsRoutes(
    private val memoryFactRepository: MemoryFactRepository,
    private val personaRepository: PersonaRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/memory-facts?userId=&personaId=
            get("/v1/admin/memory-facts") {
                val principal = call.principal<UserIdPrincipal>() ?: run {
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

                val userIdParam = call.request.queryParameters["userId"]
                val personaIdParam = call.request.queryParameters["personaId"]

                if (userIdParam == null && personaIdParam == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            ApiError(
                                ErrorCode.VALIDATION_ERROR,
                                "At least one of userId or personaId query parameters is required",
                                null,
                            ),
                        ),
                    )
                    return@get
                }

                val userId = userIdParam?.let {
                    try { UUID.fromString(it) } catch (_: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid userId format", null)),
                        )
                        return@get
                    }
                }
                val personaId = personaIdParam?.let {
                    try { UUID.fromString(it) } catch (_: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid personaId format", null)),
                        )
                        return@get
                    }
                }

                val personaNames = personaRepository.findAll().associate { it.id.toString() to it.displayName }
                val facts = when {
                    userId != null && personaId != null ->
                        memoryFactRepository.findForRelationship(userId, personaId)
                    userId != null ->
                        memoryFactRepository.findAllForUser(userId)
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                ApiError(ErrorCode.VALIDATION_ERROR, "userId is required when personaId alone is given", null),
                            ),
                        )
                        return@get
                    }
                }

                call.respond(
                    HttpStatusCode.OK,
                    AdminMemoryFactListResponse(
                        memoryFacts = facts.sortedByDescending { it.learnedAt }.map { f -> toResponse(f, personaNames) },
                    ),
                )
            }

            // POST /v1/admin/memory-facts
            post("/v1/admin/memory-facts") {
                val principal = call.principal<UserIdPrincipal>() ?: run {
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
                    call.receive<AdminCreateMemoryFactRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                if (request.content.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "content must not be blank", null)),
                    )
                    return@post
                }

                val userId = try { UUID.fromString(request.userId) } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid userId format", null)),
                    )
                    return@post
                }
                val personaId = try { UUID.fromString(request.personaId) } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid personaId format", null)),
                    )
                    return@post
                }

                try {
                    val fact = memoryFactRepository.create(
                        userId = userId,
                        personaId = personaId,
                        fact = request.content,
                        factType = request.factType,
                        criticality = request.criticality,
                        tier = request.tier,
                        status = request.status,
                        source = request.source,
                        owner = request.owner,
                    )
                    val personaNames = personaRepository.findAll().associate { it.id.toString() to it.displayName }
                    call.respond(HttpStatusCode.Created, toResponse(fact, personaNames))
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation failed", null)),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create memory fact", null)),
                    )
                }
            }

            // PATCH /v1/admin/memory-facts/{factId}
            patch("/v1/admin/memory-facts/{factId}") {
                val principal = call.principal<UserIdPrincipal>() ?: run {
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

                val factId = try {
                    UUID.fromString(call.parameters["factId"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid factId format", null)),
                    )
                    return@patch
                }

                val request = try {
                    call.receive<AdminPatchMemoryFactRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@patch
                }

                if (request.content == null && request.status == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            ApiError(ErrorCode.VALIDATION_ERROR, "At least one of content or status must be provided", null),
                        ),
                    )
                    return@patch
                }

                try {
                    // Apply content update first (if requested), then status update.
                    val existing = memoryFactRepository.findById(factId)
                    if (existing == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Memory fact not found", null)),
                        )
                        return@patch
                    }

                    if (request.content != null) {
                        memoryFactRepository.updateContent(factId, request.content)
                    }
                    val updated = if (request.status != null) {
                        memoryFactRepository.updateStatus(factId, request.status)
                    } else {
                        memoryFactRepository.findById(factId)!!
                    }
                    val personaNames = personaRepository.findAll().associate { it.id.toString() to it.displayName }
                    call.respond(HttpStatusCode.OK, toResponse(updated, personaNames))
                } catch (e: IllegalArgumentException) {
                    // findById throws require when fact not found, or updateStatus with bad status
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation failed", null)),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to update memory fact", null)),
                    )
                }
            }

            // DELETE /v1/admin/memory-facts/{factId}
            // Soft-delete: sets status="removed" (never a SQL DELETE — preserves history
            // and matches the Memory Engine's own non-destructive remove convention).
            delete("/v1/admin/memory-facts/{factId}") {
                val principal = call.principal<UserIdPrincipal>() ?: run {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@delete
                }
                if (!adminAuthorizationProvider.isAdmin(principal.name)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)),
                    )
                    return@delete
                }

                val factId = try {
                    UUID.fromString(call.parameters["factId"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid factId format", null)),
                    )
                    return@delete
                }

                try {
                    memoryFactRepository.remove(factId)
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Memory fact not found", null)),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to delete memory fact", null)),
                    )
                }
            }
        }
    }

    private fun toResponse(
        f: MemoryFactRepository.MemoryFact,
        personaNames: Map<String, String>,
    ): AdminMemoryFactResponse = AdminMemoryFactResponse(
        id = f.id.toString(),
        userId = f.userId.toString(),
        personaId = f.personaId.toString(),
        personaDisplayName = personaNames[f.personaId.toString()],
        content = f.fact,
        factType = f.factType,
        criticality = f.criticality,
        tier = f.tier,
        status = f.status,
        owner = f.owner,
        source = f.source,
        learnedAt = f.learnedAt.toString(),
        updatedAt = f.updatedAt?.toString(),
        supersedesId = f.supersedesId?.toString(),
    )
}

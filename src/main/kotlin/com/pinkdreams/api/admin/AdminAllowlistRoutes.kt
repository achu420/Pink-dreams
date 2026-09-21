package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.AdminAllowlistRepository
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
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AddAllowlistEntryRequest(
    val userId: String,
    val note: String? = null,
)

@Serializable
data class AllowlistEntryResponse(
    val userId: String,
    val note: String?,
    val addedBy: String?,
    val addedAt: String,
)

@Serializable
data class AllowlistListResponse(
    val entries: List<AllowlistEntryResponse>,
    /** How many admin ids come from ADMIN_USER_IDS — never the ids themselves. */
    val envAllowlistCount: Int,
)

/**
 * Manages the DB-backed admin allowlist. This route governs admin access
 * itself, so it is gated by exactly the same mechanism as every other admin
 * route (session cookie or dev-auth principal, then isAdmin) and adds one
 * extra safety rule on removal — see the lockout guard in the DELETE handler.
 */
class AdminAllowlistRoutes(
    private val allowlistRepository: AdminAllowlistRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/allowlist
            get("/v1/admin/allowlist") {
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

                call.respond(
                    HttpStatusCode.OK,
                    AllowlistListResponse(
                        entries = allowlistRepository.list().map {
                            AllowlistEntryResponse(
                                userId = it.userId.toString(),
                                note = it.note,
                                addedBy = it.addedBy,
                                addedAt = it.addedAt.toString(),
                            )
                        },
                        envAllowlistCount = adminAuthorizationProvider.envAdminUserIds().size,
                    ),
                )
            }

            // POST /v1/admin/allowlist
            post("/v1/admin/allowlist") {
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
                    call.receive<AddAllowlistEntryRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                // Must be a real UUID. A blank or wildcard value is rejected
                // here rather than being stored as something permissive.
                val userId = try {
                    UUID.fromString(request.userId.trim())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "userId must be a UUID", null)),
                    )
                    return@post
                }

                val entry = allowlistRepository.add(
                    userId = userId,
                    note = request.note?.takeIf { it.isNotBlank() },
                    addedBy = principal.name,
                )
                call.respond(
                    HttpStatusCode.Created,
                    AllowlistEntryResponse(
                        userId = entry.userId.toString(),
                        note = entry.note,
                        addedBy = entry.addedBy,
                        addedAt = entry.addedAt.toString(),
                    ),
                )
            }

            // DELETE /v1/admin/allowlist/{userId}
            delete("/v1/admin/allowlist/{userId}") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
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

                val userId = try {
                    UUID.fromString(call.parameters["userId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid user ID format", null)),
                    )
                    return@delete
                }

                // LOCKOUT GUARD. This endpoint manages admin access itself, so
                // it must never be able to empty out every source of admin
                // identity: if ADMIN_USER_IDS is empty, the LAST remaining
                // allowlist row cannot be removed. Otherwise a single DELETE
                // could leave the union of (env var ∪ table) empty and lock
                // every admin out of the console with no in-app way back in.
                val envAdmins = adminAuthorizationProvider.envAdminUserIds()
                if (envAdmins.isEmpty() && allowlistRepository.count() <= 1L && allowlistRepository.contains(userId)) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(
                            ApiError(
                                ErrorCode.VALIDATION_FAILED,
                                "Refusing to remove the last admin: ADMIN_USER_IDS is empty, so this would lock " +
                                    "everyone out. Add another admin first.",
                                null,
                            ),
                        ),
                    )
                    return@delete
                }

                val removed = allowlistRepository.remove(userId)
                if (!removed) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Allowlist entry not found", null)),
                    )
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

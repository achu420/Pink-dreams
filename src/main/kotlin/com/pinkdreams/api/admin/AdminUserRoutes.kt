package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.AdminUserProvisioning
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import io.ktor.http.HttpStatusCode
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

@Serializable
data class CreateAdminUserRequest(
    val displayName: String,
    val gender: String? = null,
    val interest: String? = null,
    val city: String? = null,
    val age: Int? = null,
)

@Serializable
data class AdminUserProfileResponse(
    val userId: String,
    val displayName: String?,
    val gender: String?,
    val interest: String?,
    val city: String?,
    val age: Int?,
)

@Serializable
data class AdminUserListResponse(
    val users: List<AdminUserProfileResponse>,
)

/**
 * Admin-only test-user creation and listing, for the future Admin Console Chat
 * tab's user-selection loop. Does not create update/delete endpoints (out of
 * scope for this phase) and does not expose provider diagnostics, credentials,
 * or any data beyond the profile fields the console needs.
 */
class AdminUserRoutes(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val provisioning: AdminUserProvisioning,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // POST /v1/admin/users
            post("/v1/admin/users") {
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
                    call.receive<CreateAdminUserRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                if (request.displayName.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "displayName is required", null)),
                    )
                    return@post
                }

                try {
                    val created = provisioning.createUserWithProfile(
                        displayName = request.displayName,
                        gender = request.gender,
                        interest = request.interest,
                        city = request.city,
                        age = request.age,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        AdminUserProfileResponse(
                            userId = created.userId.toString(),
                            displayName = created.profile.displayName,
                            gender = created.profile.gender,
                            interest = created.profile.interest,
                            city = created.profile.city,
                            age = created.profile.age,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Invalid request", null)),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create user", null)),
                    )
                }
            }

            // GET /v1/admin/users
            get("/v1/admin/users") {
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

                val users = userRepository.findAll()
                    .map { userId -> userId to userProfileRepository.findByUserId(userId) }
                    .map { (userId, profile) ->
                        AdminUserProfileResponse(
                            userId = userId.toString(),
                            displayName = profile?.displayName,
                            gender = profile?.gender,
                            interest = profile?.interest,
                            city = profile?.city,
                            age = profile?.age,
                        )
                    }
                    .sortedWith(compareBy({ it.displayName ?: "" }, { it.userId }))

                call.respond(HttpStatusCode.OK, AdminUserListResponse(users))
            }
        }
    }
}

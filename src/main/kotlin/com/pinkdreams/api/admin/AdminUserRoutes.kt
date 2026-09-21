package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.AdminUserProvisioning
import com.pinkdreams.persistence.repositories.AdminStatsRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

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

@Serializable
data class AdminUserConversationResponse(
    val id: String,
    val personaId: String,
    val personaDisplayName: String?,
    val state: String,
    val executionMode: String,
    val lastMessageAt: String?,
    val createdAt: String,
)

@Serializable
data class AdminUserConversationListResponse(
    val conversations: List<AdminUserConversationResponse>,
)

@Serializable
data class AdminUserMemoryFactResponse(
    val id: String,
    val personaId: String,
    val personaDisplayName: String?,
    val fact: String,
    val factType: String,
    val criticality: String,
    val tier: String,
    val status: String,
    val owner: String,
    val source: String,
    val learnedAt: String,
)

@Serializable
data class AdminUserMemoryListResponse(
    val memoryFacts: List<AdminUserMemoryFactResponse>,
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
    // User Detail page (Conversations + Memory tabs). Both are read-only and
    // reuse existing repositories — findAllAdmin already exists for the
    // Conversations Inspector and takes an arbitrary target user id, so no
    // admin-specific conversation query had to be added.
    private val conversationRepository: ConversationRepository? = null,
    private val memoryFactRepository: MemoryFactRepository? = null,
    private val personaRepository: PersonaRepository? = null,
    // Optional: supplies per-user conversation count / last-activity columns
    // to the CSV export, via ONE grouped query for the whole list. Absent, the
    // export simply reports 0/blank for those two columns rather than failing.
    private val statsRepository: AdminStatsRepository? = null,
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

            // GET /v1/admin/users/export.csv — the Users list as CSV.
            //
            // Registered as a LITERAL path segment, so Ktor's routing always
            // prefers it over the `{userId}` route below (constant segments
            // outrank parameters); "export.csv" is not a UUID anyway.
            //
            // The same filters the Users list UI already offers (free-text
            // search, gender, interest, city, min/max age) are accepted as
            // optional query parameters and applied here, so "Export" exports
            // exactly what the admin is looking at rather than silently
            // dumping everyone. Omitting them all exports the full list.
            get("/v1/admin/users/export.csv") {
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

                val params = call.request.queryParameters
                val search = params["search"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val gender = params["gender"]?.takeIf { it.isNotBlank() }
                val interest = params["interest"]?.takeIf { it.isNotBlank() }
                val city = params["city"]?.takeIf { it.isNotBlank() }
                val minAge = params["minAge"]?.toIntOrNull()
                val maxAge = params["maxAge"]?.toIntOrNull()

                val activity = statsRepository?.activityByUser() ?: emptyMap()
                val rows = userRepository.findAll()
                    .map { userId -> userId to userProfileRepository.findByUserId(userId) }
                    .filter { (userId, profile) ->
                        if (gender != null && (profile?.gender ?: "") != gender) return@filter false
                        if (interest != null && (profile?.interest ?: "") != interest) return@filter false
                        if (city != null && (profile?.city ?: "") != city) return@filter false
                        // An unknown age is excluded only when a bound is
                        // actually set — never coerced to 0. Mirrors the UI.
                        val age = profile?.age
                        if (minAge != null && (age == null || age < minAge)) return@filter false
                        if (maxAge != null && (age == null || age > maxAge)) return@filter false
                        if (search == null) return@filter true
                        listOf(profile?.displayName, profile?.city, userId.toString(), profile?.gender, profile?.interest)
                            .any { (it ?: "").lowercase().contains(search) }
                    }
                    .sortedWith(compareBy({ it.second?.displayName ?: "" }, { it.first.toString() }))
                    .map { (userId, profile) ->
                        val stats = activity[userId]
                        listOf(
                            userId.toString(),
                            profile?.displayName ?: "",
                            profile?.gender ?: "",
                            profile?.interest ?: "",
                            profile?.city ?: "",
                            profile?.age ?: "",
                            stats?.conversations ?: 0L,
                            stats?.lastActiveAt?.toString() ?: "",
                        )
                    }

                val csv = AdminCsv.document(
                    header = listOf("userId", "displayName", "gender", "interest", "city", "age", "conversations", "lastActiveAt"),
                    rows = rows,
                )
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"users.csv\"")
                call.respondText(csv, ContentType.Text.CSV)
            }

            // GET /v1/admin/users/{userId}
            get("/v1/admin/users/{userId}") {
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

                val userId = parseUserId(call.parameters["userId"])
                if (userId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid user ID format", null)),
                    )
                    return@get
                }
                if (!userRepository.exists(userId)) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "User not found", null)),
                    )
                    return@get
                }

                val profile = userProfileRepository.findByUserId(userId)
                call.respond(
                    HttpStatusCode.OK,
                    AdminUserProfileResponse(
                        userId = userId.toString(),
                        displayName = profile?.displayName,
                        gender = profile?.gender,
                        interest = profile?.interest,
                        city = profile?.city,
                        age = profile?.age,
                    ),
                )
            }

            // GET /v1/admin/users/{userId}/conversations
            get("/v1/admin/users/{userId}/conversations") {
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

                val userId = parseUserId(call.parameters["userId"])
                if (userId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid user ID format", null)),
                    )
                    return@get
                }
                val conversations = conversationRepository
                if (conversations == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Conversation lookup is not configured", null)),
                    )
                    return@get
                }

                val personaNames = personaRepository?.findAll()?.associate { it.id.toString() to it.displayName } ?: emptyMap()
                val rows = conversations.findAllAdmin(limit = 200, offset = 0, userId = userId, personaId = null)
                    .map { c ->
                        AdminUserConversationResponse(
                            id = c.id.toString(),
                            personaId = c.personaId.toString(),
                            personaDisplayName = personaNames[c.personaId.toString()],
                            state = c.state,
                            executionMode = c.executionMode,
                            lastMessageAt = c.lastMessageAt?.toString(),
                            createdAt = c.createdAt.toString(),
                        )
                    }
                call.respond(HttpStatusCode.OK, AdminUserConversationListResponse(rows))
            }

            // GET /v1/admin/users/{userId}/memory/export.csv — the SAME facts
            // the Memory tab lists, as a downloadable CSV. Every field goes
            // through AdminCsv.escape, so a fact containing a comma, a quote
            // or a newline (all of which real, LLM-extracted facts do contain)
            // cannot corrupt the file's column structure.
            get("/v1/admin/users/{userId}/memory/export.csv") {
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

                val userId = parseUserId(call.parameters["userId"])
                if (userId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid user ID format", null)),
                    )
                    return@get
                }
                val memory = memoryFactRepository
                if (memory == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Memory lookup is not configured", null)),
                    )
                    return@get
                }

                val personaNames = personaRepository?.findAll()?.associate { it.id.toString() to it.displayName } ?: emptyMap()
                val rows = memory.findAllForUser(userId)
                    .sortedByDescending { it.learnedAt }
                    .map { f ->
                        listOf(
                            f.id.toString(),
                            f.personaId.toString(),
                            personaNames[f.personaId.toString()] ?: "",
                            f.fact,
                            f.factType,
                            f.criticality,
                            f.tier,
                            f.status,
                            f.owner,
                            f.source,
                            f.learnedAt.toString(),
                        )
                    }
                val csv = AdminCsv.document(
                    header = listOf(
                        "factId", "personaId", "personaDisplayName", "fact", "factType",
                        "criticality", "tier", "status", "owner", "source", "learnedAt",
                    ),
                    rows = rows,
                )
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"memory-facts-$userId.csv\"")
                call.respondText(csv, ContentType.Text.CSV)
            }

            // GET /v1/admin/users/{userId}/memory — every memory fact for this
            // user across every persona they have talked to.
            get("/v1/admin/users/{userId}/memory") {
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

                val userId = parseUserId(call.parameters["userId"])
                if (userId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid user ID format", null)),
                    )
                    return@get
                }
                val memory = memoryFactRepository
                if (memory == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Memory lookup is not configured", null)),
                    )
                    return@get
                }

                val personaNames = personaRepository?.findAll()?.associate { it.id.toString() to it.displayName } ?: emptyMap()
                val facts = memory.findAllForUser(userId)
                    .sortedByDescending { it.learnedAt }
                    .map { f ->
                        AdminUserMemoryFactResponse(
                            id = f.id.toString(),
                            personaId = f.personaId.toString(),
                            personaDisplayName = personaNames[f.personaId.toString()],
                            fact = f.fact,
                            factType = f.factType,
                            criticality = f.criticality,
                            tier = f.tier,
                            status = f.status,
                            owner = f.owner,
                            source = f.source,
                            learnedAt = f.learnedAt.toString(),
                        )
                    }
                call.respond(HttpStatusCode.OK, AdminUserMemoryListResponse(facts))
            }
        }
    }

    private fun parseUserId(raw: String?): UUID? = try {
        UUID.fromString(raw)
    } catch (e: Exception) {
        null
    }
}

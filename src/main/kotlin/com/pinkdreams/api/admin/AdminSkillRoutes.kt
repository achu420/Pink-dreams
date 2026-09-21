package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.SkillRepository
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
import java.util.UUID

@Serializable
data class CreateSkillRequest(
    val key: String,
    val content: String,
    val changelogNote: String? = null,
)

@Serializable
data class SkillResponse(
    val id: String,
    val key: String,
    val version: Int,
    val content: String,
    val status: String,
    val isActive: Boolean,
    val changelogNote: String?,
    val author: String?,
)

@Serializable
data class SkillListResponse(
    val skills: List<SkillResponse>,
)

/**
 * Admin-only Skill versioning, mirroring AdminEngineRoutes exactly — the only
 * structural difference is that Skills are versioned PER KEY (many
 * independently-active keys) rather than one single global active row.
 */
class AdminSkillRoutes(
    private val skillRepository: SkillRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/skills
            get("/v1/admin/skills") {
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

                val keyFilter = call.request.queryParameters["key"]
                val skills = if (keyFilter != null) skillRepository.findByKey(keyFilter) else skillRepository.findAll()
                call.respond(
                    HttpStatusCode.OK,
                    SkillListResponse(skills = skills.map(::toResponse)),
                )
            }

            // GET /v1/admin/skills/{skillId}
            get("/v1/admin/skills/{skillId}") {
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

                val skillId = try {
                    UUID.fromString(call.parameters["skillId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid skill ID format", null)),
                    )
                    return@get
                }

                val skill = skillRepository.findById(skillId)
                if (skill == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Skill not found", null)),
                    )
                    return@get
                }
                call.respond(HttpStatusCode.OK, toResponse(skill))
            }

            // POST /v1/admin/skills
            post("/v1/admin/skills") {
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
                    call.receive<CreateSkillRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                    )
                    return@post
                }

                if (request.key.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "key is required", null)),
                    )
                    return@post
                }

                try {
                    // Version number is always server-computed (current max version for
                    // this key + 1) — the client cannot supply or influence it.
                    val skill = skillRepository.createNextVersion(
                        key = request.key,
                        content = request.content,
                        changelogNote = request.changelogNote,
                        author = principal.name,
                    )
                    call.respond(HttpStatusCode.Created, toResponse(skill))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create skill", null)),
                    )
                }
            }

            // POST /v1/admin/skills/{skillId}/publish
            post("/v1/admin/skills/{skillId}/publish") {
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

                val skillId = try {
                    UUID.fromString(call.parameters["skillId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid skill ID format", null)),
                    )
                    return@post
                }

                try {
                    val skill = skillRepository.publish(skillId)
                    call.respond(HttpStatusCode.OK, toResponse(skill))
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Skill not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }

            // POST /v1/admin/skills/{skillId}/activate
            post("/v1/admin/skills/{skillId}/activate") {
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

                val skillId = try {
                    UUID.fromString(call.parameters["skillId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid skill ID format", null)),
                    )
                    return@post
                }

                try {
                    val skill = skillRepository.activate(skillId)
                    call.respond(HttpStatusCode.OK, toResponse(skill))
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Skill not found", null)),
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)),
                    )
                }
            }

            // POST /v1/admin/skills/{skillId}/archive
            post("/v1/admin/skills/{skillId}/archive") {
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

                val skillId = try {
                    UUID.fromString(call.parameters["skillId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid skill ID format", null)),
                    )
                    return@post
                }

                try {
                    val skill = skillRepository.archive(skillId)
                    call.respond(HttpStatusCode.OK, toResponse(skill))
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Skill not found", null)),
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

    private fun toResponse(skill: SkillRepository.Skill): SkillResponse = SkillResponse(
        id = skill.id.toString(),
        key = skill.key,
        version = skill.version,
        content = skill.content,
        status = skill.status,
        isActive = skill.isActive,
        changelogNote = skill.changelogNote,
        author = skill.author,
    )
}

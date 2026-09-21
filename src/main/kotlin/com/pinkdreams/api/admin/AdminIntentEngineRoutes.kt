package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.IntentEngineRepository
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
data class CreateIntentEngineRequest(
    val content: String,
    val changelogNote: String? = null,
)

@Serializable
data class IntentEngineResponse(
    val id: String,
    val version: Int,
    val content: String,
    val status: String,
    val isActive: Boolean,
    val changelogNote: String?,
    val createdBy: String?,
)

@Serializable
data class IntentEngineListResponse(
    val intentEngines: List<IntentEngineResponse>,
    /**
     * The skill keys the Intent Engine may currently choose from. Supplied by
     * the runtime from the ACTIVE skills, never authored into the prompt — the
     * admin UI shows this so it is obvious what the engine can actually select.
     */
    val activeSkillCandidates: List<String>,
)

/** Admin-only Intent Engine versioning — a structural mirror of AdminMemoryEngineRoutes. */
class AdminIntentEngineRoutes(
    private val intentEngineRepository: IntentEngineRepository,
    private val skillRepository: SkillRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            get("/v1/admin/intent-engines") {
                if (!authorize()) return@get
                call.respond(
                    HttpStatusCode.OK,
                    IntentEngineListResponse(
                        intentEngines = intentEngineRepository.findAll().map(::toResponse),
                        activeSkillCandidates = skillRepository.findAllActiveKeys().sorted(),
                    ),
                )
            }

            get("/v1/admin/intent-engines/{id}") {
                if (!authorize()) return@get
                val id = parseId() ?: return@get
                val engine = intentEngineRepository.findById(id)
                if (engine == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Intent engine not found", null)))
                    return@get
                }
                call.respond(HttpStatusCode.OK, toResponse(engine))
            }

            post("/v1/admin/intent-engines") {
                val principal = requirePrincipal() ?: return@post
                val request = try {
                    call.receive<CreateIntentEngineRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)))
                    return@post
                }
                try {
                    // Version number is always server-computed — the client
                    // cannot supply or influence it.
                    val engine = intentEngineRepository.createNextVersion(
                        content = request.content,
                        changelogNote = request.changelogNote,
                        createdBy = principal.name,
                    )
                    call.respond(HttpStatusCode.Created, toResponse(engine))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create intent engine", null)))
                }
            }

            post("/v1/admin/intent-engines/{id}/publish") { transition { intentEngineRepository.publish(it) } }
            post("/v1/admin/intent-engines/{id}/activate") { transition { intentEngineRepository.activate(it) } }
            post("/v1/admin/intent-engines/{id}/archive") { transition { intentEngineRepository.archive(it) } }
        }
    }

    // --- shared plumbing, identical in behavior to the other admin route classes ---

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.requirePrincipal(): UserIdPrincipal? {
        val principal = call.principal<UserIdPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
            return null
        }
        if (!adminAuthorizationProvider.isAdmin(principal.name)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
            return null
        }
        return principal
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.authorize(): Boolean =
        requirePrincipal() != null

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.parseId(): UUID? = try {
        UUID.fromString(call.parameters["id"])
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid intent engine ID format", null)))
        null
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.transition(
        action: (UUID) -> IntentEngineRepository.IntentEngine,
    ) {
        if (!authorize()) return
        val id = parseId() ?: return
        try {
            call.respond(HttpStatusCode.OK, toResponse(action(id)))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Intent engine not found", null)))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid state transition", null)))
        }
    }

    private fun toResponse(engine: IntentEngineRepository.IntentEngine) = IntentEngineResponse(
        id = engine.id.toString(),
        version = engine.version,
        content = engine.content,
        status = engine.status,
        isActive = engine.isActive,
        changelogNote = engine.changelogNote,
        createdBy = engine.createdBy,
    )
}

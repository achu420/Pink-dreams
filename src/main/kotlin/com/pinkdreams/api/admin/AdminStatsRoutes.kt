package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.AdminStatsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class AdminActivityStatsResponse(
    val totalConversations: Long,
    val productionConversations: Long,
    val testConversations: Long,
    val conversationsLast24h: Long,
    val totalMessages: Long,
    val messagesLast24h: Long,
    val userMessagesLast24h: Long,
    // Additional real KPIs. Every one is an additional cheap COUNT/GROUP BY
    // over a table this repository already reads — see AdminStatsRepository.
    // Deliberately NOT given Kotlin defaults: kotlinx-serialization omits a
    // property whose value equals its default, which would make a genuine
    // zero disappear from the JSON and render as "undefined" in the console.
    val totalMemoryFacts: Long,
    val openMemoryFacts: Long,
    val skillsInProduction: Long,
    val skillsInTesting: Long,
    val skillsInDraft: Long,
    /** Persona with the most conversations; null when there are no conversations at all. Names are resolved client-side from the persona list the Dashboard already loads. */
    val topPersonaId: String?,
    val topPersonaConversations: Long,
)

/** One row of GET /v1/admin/stats/users — per-user activity for the Users list. */
@Serializable
data class AdminUserActivityRow(
    val userId: String,
    val conversations: Long,
    val lastActiveAt: String?,
)

@Serializable
data class AdminUserActivityStatsResponse(val users: List<AdminUserActivityRow>)

/** One row of GET /v1/admin/stats/personas — per-persona conversation count. */
@Serializable
data class AdminPersonaActivityRow(
    val personaId: String,
    val conversations: Long,
)

@Serializable
data class AdminPersonaActivityStatsResponse(val personas: List<AdminPersonaActivityRow>)

/**
 * Module 05 — Dashboard activity counters.
 *
 * Read-only. Exists solely so the Dashboard can show real conversation/message
 * activity rather than a hardcoded placeholder. Auth gating copies the exact
 * pattern every other admin route uses (AdminObservabilityRoutes /
 * AdminUserRoutes): `authenticate("dev-auth")` for identity, then an explicit
 * `adminAuthorizationProvider.isAdmin(principal.name)` check — an authenticated
 * non-admin is rejected with 403, and a missing principal with 401. The check
 * is on the RESOLVED principal's identity, never on the mere presence of a
 * header.
 */
class AdminStatsRoutes(
    private val statsRepository: AdminStatsRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            // GET /v1/admin/stats/activity
            get("/v1/admin/stats/activity") {
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

                val counts = statsRepository.activityCounts()
                val memory = statsRepository.memoryFactCounts()
                val skills = statsRepository.skillStageCounts()
                // One grouped query, reused here only to pick its single
                // largest entry — never a per-persona query.
                val topPersona = statsRepository.conversationCountsByPersona().maxByOrNull { it.value }
                call.respond(
                    HttpStatusCode.OK,
                    AdminActivityStatsResponse(
                        totalConversations = counts.totalConversations,
                        productionConversations = counts.productionConversations,
                        testConversations = counts.testConversations,
                        conversationsLast24h = counts.conversationsLast24h,
                        totalMessages = counts.totalMessages,
                        messagesLast24h = counts.messagesLast24h,
                        userMessagesLast24h = statsRepository.messagesLast24hByRole("user"),
                        totalMemoryFacts = memory.totalFacts,
                        openMemoryFacts = memory.openFacts,
                        skillsInProduction = skills.production,
                        skillsInTesting = skills.testing,
                        skillsInDraft = skills.draft,
                        topPersonaId = topPersona?.key?.toString(),
                        topPersonaConversations = topPersona?.value ?: 0,
                    ),
                )
            }

            // GET /v1/admin/stats/users — per-user conversation count and last
            // activity for the WHOLE list in one grouped query (never N+1).
            get("/v1/admin/stats/users") {
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
                val rows = statsRepository.activityByUser().map { (userId, activity) ->
                    AdminUserActivityRow(
                        userId = userId.toString(),
                        conversations = activity.conversations,
                        lastActiveAt = activity.lastActiveAt?.toString(),
                    )
                }
                call.respond(HttpStatusCode.OK, AdminUserActivityStatsResponse(rows))
            }

            // GET /v1/admin/stats/personas — per-persona conversation count,
            // one grouped query for the whole list.
            get("/v1/admin/stats/personas") {
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
                val rows = statsRepository.conversationCountsByPersona().map { (personaId, count) ->
                    AdminPersonaActivityRow(personaId = personaId.toString(), conversations = count)
                }
                call.respond(HttpStatusCode.OK, AdminPersonaActivityStatsResponse(rows))
            }
        }
    }
}

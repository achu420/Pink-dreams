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
)

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
                    ),
                )
            }
        }
    }
}

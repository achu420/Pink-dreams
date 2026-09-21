package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import com.pinkdreams.persistence.repositories.PerformanceMetricsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

@Serializable
data class LatencyStatsResponse(
    val count: Long,
    val p50: Long?,
    val p75: Long?,
    val p95: Long?,
    val p99: Long?,
    val max: Long?,
    val avg: Double?,
)

@Serializable
data class SlaBucketsResponse(
    val le5s: Long,
    val le8s: Long,
    val le10s: Long,
    val gt10s: Long,
    val gt20s: Long,
    val total: Long,
    val within10sRatePercent: Double,
)

@Serializable
data class ErrorRateResponse(
    val total: Long,
    val success: Long,
    val malformed: Long,
    val budgetExhaustion: Long,
    val providerError: Long,
    val exception: Long,
    val failureRatePercent: Double,
)

/**
 * Task 5 — Admin Observability UI, latency dashboard endpoint.
 *
 * Every field in [PerformanceMetricsRepository.MetricsFilter] is exposed as
 * an optional query parameter. Missing/invalid query params are ignored
 * (fail open to "no filter on this dimension") rather than 400ing, since a
 * dashboard should degrade to "show everything" instead of erroring, per
 * the spec's own UX requirement ("clear errors, explicit unavailable
 * fields" — a bad filter is not a broken dashboard, it's an ignored one).
 */
@Serializable
data class LatencyDashboardResponse(
    val overall: LatencyStatsResponse,
    val slaBuckets: SlaBucketsResponse,
    val errorRate: ErrorRateResponse,
    val byWorkload: Map<String, LatencyStatsResponse>,
    val byModel: Map<String, LatencyStatsResponse>,
    val byProvider: Map<String, LatencyStatsResponse>,
)

@Serializable
data class ExchangeSummaryResponse(
    val id: String,
    val turnRequestId: String,
    val conversationId: String,
    val workload: String,
    val isTestChat: Boolean,
    val model: String?,
    val provider: String?,
    val latencyMs: Long,
    val totalTokens: Int?,
    val reasoningTokens: Int?,
    val finishReason: String?,
    val outcome: String,
    val createdAt: String,
)

@Serializable
data class ExchangeDetailResponse(
    val summary: ExchangeSummaryResponse,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val httpStatusCode: Int?,
    val errorClass: String?,
    val errorMessage: String?,
    /** Lazily included only on the detail endpoint, never on list/summary — matches the spec's "lazy raw-payload loading" UX requirement. Secrets already redacted at capture time (Task 2). */
    val requestBody: String?,
    val responseBody: String?,
)

@Serializable
data class ExchangeListResponse(
    val exchanges: List<ExchangeSummaryResponse>,
    val count: Int,
)

@Serializable
data class TurnTraceResponse(
    val turnRequestId: String,
    val conversationId: String,
    val onPathLatencyMs: Long,
    val exchanges: List<ExchangeSummaryResponse>,
)

/**
 * Task 5 — Admin Observability UI (backend). Read-only surface over the
 * data Task 2 (raw capture) and Task 3 (aggregation) already built — this
 * route layer adds no new persistence, only query/filter/pagination and
 * response shaping for an admin frontend.
 *
 * Export (Task 6) is deliberately out of scope here — these endpoints
 * return the same shapes an export would reuse, but no CSV/bulk-download
 * format is implemented in this task.
 */
class AdminObservabilityRoutes(
    private val exchangeRepository: LlmExchangeRepository,
    private val metricsRepository: PerformanceMetricsRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("dev-auth") {
            get("/v1/admin/observability/latency-dashboard") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val filter = parseFilter(call.request.queryParameters)
                call.respond(
                    HttpStatusCode.OK,
                    LatencyDashboardResponse(
                        overall = metricsRepository.latencyStats(filter).toResponse(),
                        slaBuckets = metricsRepository.slaBuckets(filter).toResponse(),
                        errorRate = metricsRepository.errorRate(filter).toResponse(),
                        byWorkload = metricsRepository.statsByWorkload(filter).mapValues { it.value.toResponse() },
                        byModel = metricsRepository.statsByModel(filter).mapValues { it.value.toResponse() },
                        byProvider = metricsRepository.statsByProvider(filter).mapValues { it.value.toResponse() },
                    ),
                )
            }

            get("/v1/admin/observability/exchanges") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val workload = call.request.queryParameters["workload"]
                val isTestChat = call.request.queryParameters["isTestChat"]?.toBooleanStrictOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val conversationId = call.request.queryParameters["conversationId"]?.let { parseUuidOrNull(it) }

                val exchanges = when {
                    conversationId != null -> exchangeRepository.findForConversation(conversationId, limit)
                    workload != null -> exchangeRepository.findByWorkload(workload, isTestChat, limit)
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Provide at least conversationId or workload to list exchanges", null)),
                        )
                        return@get
                    }
                }
                call.respond(HttpStatusCode.OK, ExchangeListResponse(exchanges.map { it.toSummary() }, exchanges.size))
            }

            get("/v1/admin/observability/exchanges/{id}") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val id = parseUuidOrNull(call.parameters["id"])
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid exchange id", null)))
                    return@get
                }
                val exchange = exchangeRepository.findById(id)
                if (exchange == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Exchange not found", null)))
                    return@get
                }
                call.respond(
                    HttpStatusCode.OK,
                    ExchangeDetailResponse(
                        summary = exchange.toSummary(),
                        promptTokens = exchange.promptTokens,
                        completionTokens = exchange.completionTokens,
                        httpStatusCode = exchange.httpStatusCode,
                        errorClass = exchange.errorClass,
                        errorMessage = exchange.errorMessage,
                        requestBody = exchange.requestBody,
                        responseBody = exchange.responseBody,
                    ),
                )
            }

            get("/v1/admin/observability/turns/{turnRequestId}") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val turnRequestId = parseUuidOrNull(call.parameters["turnRequestId"])
                if (turnRequestId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid turn request id", null)))
                    return@get
                }
                val breakdown = metricsRepository.turnBreakdown(turnRequestId)
                if (breakdown == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "No exchanges found for this turn", null)))
                    return@get
                }
                call.respond(
                    HttpStatusCode.OK,
                    TurnTraceResponse(
                        turnRequestId = breakdown.turnRequestId.toString(),
                        conversationId = breakdown.conversationId.toString(),
                        onPathLatencyMs = breakdown.onPathLatencyMs(),
                        exchanges = breakdown.exchanges.map { it.toSummary() },
                    ),
                )
            }

            // Task 6 — Export/Analysis.
            get("/v1/admin/observability/export/exchanges.csv") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val filter = parseFilter(call.request.queryParameters)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50_000) ?: 10_000
                val exchanges = metricsRepository.findExchanges(filter, limit)
                call.respondText(contentType = io.ktor.http.ContentType.Text.CSV) { exchangesToCsv(exchanges) }
            }

            get("/v1/admin/observability/export/performance.json") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val filter = parseFilter(call.request.queryParameters)
                call.respond(
                    HttpStatusCode.OK,
                    LatencyDashboardResponse(
                        overall = metricsRepository.latencyStats(filter).toResponse(),
                        slaBuckets = metricsRepository.slaBuckets(filter).toResponse(),
                        errorRate = metricsRepository.errorRate(filter).toResponse(),
                        byWorkload = metricsRepository.statsByWorkload(filter).mapValues { it.value.toResponse() },
                        byModel = metricsRepository.statsByModel(filter).mapValues { it.value.toResponse() },
                        byProvider = metricsRepository.statsByProvider(filter).mapValues { it.value.toResponse() },
                    ),
                )
            }
        }
    }

    /**
     * CSV of exchange SUMMARY fields only — deliberately excludes raw
     * request/response bodies. Rationale: (1) conversation content is
     * sensitive (per Task 2's own security section) and a bulk CSV export
     * is a much easier thing to mishandle (download, email, paste into a
     * spreadsheet tool) than a single exchange viewed in the admin UI's
     * lazy-loaded detail panel; (2) CSV is a poor format for large,
     * multi-line JSON payloads anyway. Exchange IDs ARE preserved on every
     * row specifically so an analyst can join back to
     * `/exchanges/{id}` for the full raw payload of any specific row that
     * turns out to matter.
     */
    private fun exchangesToCsv(exchanges: List<LlmExchangeRepository.Exchange>): String {
        val header = listOf(
            "id", "turnRequestId", "conversationId", "workload", "isTestChat", "model", "provider",
            "latencyMs", "promptTokens", "completionTokens", "reasoningTokens", "totalTokens",
            "finishReason", "httpStatusCode", "outcome", "errorClass", "createdAt",
        ).joinToString(",")
        val rows = exchanges.joinToString("\n") { e ->
            listOf(
                e.id, e.turnRequestId, e.conversationId, e.workload, e.isTestChat, e.model ?: "",
                e.provider ?: "", e.latencyMs, e.promptTokens ?: "", e.completionTokens ?: "",
                e.reasoningTokens ?: "", e.totalTokens ?: "", e.finishReason ?: "", e.httpStatusCode ?: "",
                e.outcome.name, csvEscape(e.errorClass ?: ""), e.createdAt,
            ).joinToString(",")
        }
        return if (rows.isEmpty()) header else "$header\n$rows"
    }

    /** Quotes a CSV field only when it contains a character that would otherwise break column alignment. */
    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun parseFilter(params: io.ktor.http.Parameters): PerformanceMetricsRepository.MetricsFilter {
        return PerformanceMetricsRepository.MetricsFilter(
            workload = params["workload"],
            model = params["model"],
            provider = params["provider"],
            conversationId = params["conversationId"]?.let { parseUuidOrNull(it) },
            isTestChat = params["isTestChat"]?.toBooleanStrictOrNull(),
            from = params["from"]?.let { parseDateTimeOrNull(it) },
            to = params["to"]?.let { parseDateTimeOrNull(it) },
        )
    }

    private fun parseUuidOrNull(value: String?): UUID? = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun parseDateTimeOrNull(value: String): LocalDateTime? = try {
        LocalDateTime.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun LlmExchangeRepository.Exchange.toSummary() = ExchangeSummaryResponse(
        id = id.toString(),
        turnRequestId = turnRequestId.toString(),
        conversationId = conversationId.toString(),
        workload = workload,
        isTestChat = isTestChat,
        model = model,
        provider = provider,
        latencyMs = latencyMs,
        totalTokens = totalTokens,
        reasoningTokens = reasoningTokens,
        finishReason = finishReason,
        outcome = outcome.name,
        createdAt = createdAt.toString(),
    )

    private fun PerformanceMetricsRepository.LatencyStats.toResponse() = LatencyStatsResponse(count, p50, p75, p95, p99, max, avg)

    private fun PerformanceMetricsRepository.SlaBuckets.toResponse() = SlaBucketsResponse(le5s, le8s, le10s, gt10s, gt20s, total, within10sRate)

    private fun PerformanceMetricsRepository.ErrorRate.toResponse() = ErrorRateResponse(total, success, malformed, budgetExhaustion, providerError, exception, failureRate)
}

private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.requirePrincipal(
    adminAuthorizationProvider: AdminAuthorizationProvider,
): UserIdPrincipal? {
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

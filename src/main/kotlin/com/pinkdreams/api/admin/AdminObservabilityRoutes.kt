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
/**
 * Task 10 — one dimension's (workload/skill/model/provider) full diagnostic
 * picture: latency percentiles AND the same SLA buckets/error-rate the
 * overall section already has, so "which workload/skill/model/provider is
 * driving the SLA miss" is answerable directly from one table row instead
 * of cross-referencing p95 against a separate overall bucket count.
 */
@Serializable
data class DimensionStatsResponse(
    val count: Long,
    val p50: Long?,
    val p75: Long?,
    val p95: Long?,
    val p99: Long?,
    val max: Long?,
    val avg: Double?,
    val le10s: Long,
    val gt10s: Long,
    val gt20s: Long,
    val within10sRatePercent: Double,
    val errorRatePercent: Double,
)

@Serializable
data class LatencyDashboardResponse(
    val overall: LatencyStatsResponse,
    val slaBuckets: SlaBucketsResponse,
    val errorRate: ErrorRateResponse,
    val byWorkload: Map<String, DimensionStatsResponse>,
    val byModel: Map<String, DimensionStatsResponse>,
    val byProvider: Map<String, DimensionStatsResponse>,
    // Task 8 Part 3/4 — "none" covers both a genuine SkillSelection.None
    // outcome and any exchange recorded before skill attribution existed;
    // see MetricsFilter's own doc comment for why those stay merged.
    val bySkill: Map<String, DimensionStatsResponse>,
    // Task 10 Step 10 — anchors the UI's relative date-range presets ("Last
    // 1 hour", etc.) to the SERVER's clock, since LlmExchanges.createdAt is
    // written with the server's LocalDateTime.now() (no timezone). Deriving
    // "from"/"to" from the browser's own clock instead would silently
    // misalign the queried window whenever admin and server run in
    // different timezones — exactly the bug Task 10 was warned against.
    val serverTimeNow: String,
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
    // Task 8 Part 4/8 — null means SkillSelection.None OR a pre-Task-8
    // exchange; both render as "no skill attributed", never guessed at.
    val skillKey: String?,
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

/**
 * Task 8 Part 2 — one workload's ACTUALLY EFFECTIVE generation config, as
 * [com.pinkdreams.chat.ChatEngineFactory.build] genuinely constructs it —
 * not a re-derivation, a direct report of the same values/sources that
 * would be passed to the LLM client for the next call on this workload.
 */
@Serializable
data class WorkloadEffectiveConfigResponse(
    val workload: String,
    val model: String,
    val modelSource: String,
    val temperature: Double?,
    val temperatureSource: String,
    val maxOutputTokens: Int,
    val maxOutputTokensSource: String,
    val reasoningEnabled: Boolean?,
    val jsonMode: Boolean?,
    // Task 9 — truthful source for jsonMode, mirroring the others. Optional
    // with a PROVIDER_DEFAULT default so every pre-Task-9 construction site
    // (the three side-channel workloads below) keeps compiling unchanged.
    val jsonModeSource: String = "PROVIDER_DEFAULT",
    val providerSort: String?,
    val providerSortSource: String,
)

@Serializable
data class EffectiveConfigurationResponse(
    val workloads: List<WorkloadEffectiveConfigResponse>,
)

@Serializable
data class ExchangeListResponse(
    val exchanges: List<ExchangeSummaryResponse>,
    val count: Int,
)

/** Task 10 Step 11 — one row of the Slow Turn Explorer table. */
@Serializable
data class SlowTurnSummaryResponse(
    val turnRequestId: String,
    val conversationId: String,
    val onPathLatencyMs: Long,
    val intentLatencyMs: Long?,
    val generationLatencyMs: Long?,
    val skillKey: String?,
    val intentModel: String?,
    val generationModel: String?,
    val generationProvider: String?,
    val outcome: String,
    val isTestChat: Boolean,
    val createdAt: String,
)

@Serializable
data class SlowTurnListResponse(
    val turns: List<SlowTurnSummaryResponse>,
    val count: Int,
)

@Serializable
data class TurnTraceResponse(
    val turnRequestId: String,
    val conversationId: String,
    val onPathLatencyMs: Long,
    val exchanges: List<ExchangeSummaryResponse>,
    // Task 8 Part 5/6 — the wall-clock pipeline-stage timings ChatEngine
    // already measures (context_assembly, intent_discovery,
    // memory_context_selection, skill_context_enrichment, generation,
    // persist, total_before_persist — whichever ran for this turn), read
    // from the assistant message's existing metadata, not re-instrumented.
    // Null when no assistant message exists for this turn (e.g. it failed
    // before PERSIST) or it predates this capture — the UI must render that
    // as "not currently measured", never a fabricated zero.
    val stageTimingsMs: Map<String, Long>?,
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
    // Task 8 Part 2 / Task 9 Part 13 — Effective Configuration. As of Task
    // 9, `aiRuntimeSettings.resolve()` is the ONE authoritative resolution
    // path for every one of these settings (DB override, else code
    // default) — this endpoint is now a pure read of that single source,
    // with no independent precedence logic of its own.
    private val aiRuntimeSettings: com.pinkdreams.config.AiRuntimeSettings? = null,
) {
    fun register(route: Route) {
        route.authenticate("dev-auth") {
            get("/v1/admin/observability/latency-dashboard") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val filter = parseFilter(call.request.queryParameters)
                call.respond(HttpStatusCode.OK, buildDashboardResponse(filter))
            }

            // Task 8 Part 2 — read-only report of the config ChatEngineFactory
            // actually builds for each workload right now. No new persistence,
            // no behavior change: this endpoint only reads AiRuntimeSettings
            // and the same code-level overrides Application.kt already wires.
            get("/v1/admin/observability/effective-configuration") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                call.respond(HttpStatusCode.OK, effectiveConfiguration())
            }

            get("/v1/admin/observability/exchanges") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val workload = call.request.queryParameters["workload"]
                val isTestChat = call.request.queryParameters["isTestChat"]?.toBooleanStrictOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val conversationId = call.request.queryParameters["conversationId"]?.let { parseUuidOrNull(it) }
                val skillKey = call.request.queryParameters["skillKey"]
                val outcome = call.request.queryParameters["outcome"]

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
                // Task 12 Part 17 audit fix (Category B — small, isolated filter
                // bug): workload/skillKey/outcome all apply on top of whichever
                // primary dimension (conversationId or workload) selected the
                // base result set above. Previously `workload` was silently
                // DROPPED whenever conversationId was also supplied — the branch
                // above only used it to pick a fetch method, never as an actual
                // filter, so `?conversationId=X&workload=Y` silently ignored Y
                // and returned every workload for that conversation. Filters must
                // combine consistently everywhere (dashboard/exports already do
                // this correctly via MetricsFilter); this brings the exchange
                // list endpoint in line. In-memory — this endpoint's result sets
                // are already capped by `limit`, never a full-table scan.
                val filtered = exchanges
                    .let { list -> if (conversationId != null && workload != null) list.filter { it.workload == workload } else list }
                    .let { list -> if (skillKey != null) list.filter { it.skillKey == skillKey } else list }
                    .let { list -> if (outcome != null) list.filter { it.outcome.name == outcome } else list }
                call.respond(HttpStatusCode.OK, ExchangeListResponse(filtered.map { it.toSummary() }, filtered.size))
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
                        stageTimingsMs = metricsRepository.stageTimingsForTurn(turnRequestId),
                    ),
                )
            }

            // Task 10 Step 11 — Slow Turn Explorer. minLatencyMs defaults to
            // the project's single current SLA measurement point (10s) — not
            // a formal contractual guarantee, just the one bucket boundary
            // already established in Task 3 (Step 30's own instruction: use
            // the existing SLA semantics, don't invent new target numbers).
            get("/v1/admin/observability/slow-turns") {
                if (requirePrincipal(adminAuthorizationProvider) == null) return@get
                val filter = parseFilter(call.request.queryParameters)
                val minLatencyMs = call.request.queryParameters["minLatencyMs"]?.toLongOrNull() ?: 10_000L
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val slowTurns = metricsRepository.slowTurns(filter, minLatencyMs, limit)
                call.respond(HttpStatusCode.OK, SlowTurnListResponse(slowTurns.map { it.toResponse() }, slowTurns.size))
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
                call.respond(HttpStatusCode.OK, buildDashboardResponse(filter))
            }
        }
    }

    private fun buildDashboardResponse(filter: PerformanceMetricsRepository.MetricsFilter): LatencyDashboardResponse =
        LatencyDashboardResponse(
            overall = metricsRepository.latencyStats(filter).toResponse(),
            slaBuckets = metricsRepository.slaBuckets(filter).toResponse(),
            errorRate = metricsRepository.errorRate(filter).toResponse(),
            byWorkload = metricsRepository.statsByWorkload(filter).mapValues { it.value.toResponse() },
            byModel = metricsRepository.statsByModel(filter).mapValues { it.value.toResponse() },
            byProvider = metricsRepository.statsByProvider(filter).mapValues { it.value.toResponse() },
            bySkill = metricsRepository.statsBySkill(filter).mapValues { it.value.toResponse() },
            serverTimeNow = LocalDateTime.now().toString(),
        )

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
            "id", "turnRequestId", "conversationId", "workload", "skillKey", "isTestChat", "model", "provider",
            "latencyMs", "promptTokens", "completionTokens", "reasoningTokens", "totalTokens",
            "finishReason", "httpStatusCode", "outcome", "errorClass", "createdAt",
        ).joinToString(",")
        val rows = exchanges.joinToString("\n") { e ->
            listOf(
                e.id, e.turnRequestId, e.conversationId, e.workload, e.skillKey ?: "", e.isTestChat, e.model ?: "",
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
            skillKey = params["skillKey"],
            outcome = params["outcome"],
        )
    }

    /**
     * Task 8 Part 2 / Task 9 Part 13. `aiRuntimeSettings.resolve()` is now
     * the single authoritative resolution for model/temperature/
     * maxOutputTokens (primary generation) AND intentModel/intentJsonMode/
     * intentMaxOutputTokens/generationProviderSort (Intent Discovery +
     * provider routing) — this endpoint just reads it and reports the truthful
     * source for each field, live, with no independent precedence logic.
     * Documented, unchanged discrepancy: memory_extraction/
     * continuity_summarization/memory_engine_maintenance still build their
     * GenerationConfig directly from the raw environment/default model in
     * ChatEngineFactory (never resolve()'d) — see AiRuntimeSettings.environmentModel's
     * own doc comment. That was flagged, not fixed, in Task 8 and remains
     * out of scope here (Task 9 targets Intent + primary-generation
     * provider-routing only, per its own Part 18).
     */
    private fun effectiveConfiguration(): EffectiveConfigurationResponse {
        val resolved = aiRuntimeSettings?.resolve()
        val environmentModel = aiRuntimeSettings?.environmentModel ?: "unknown"

        val primaryGeneration = WorkloadEffectiveConfigResponse(
            workload = "primary_generation",
            model = resolved?.model ?: environmentModel,
            modelSource = resolved?.modelSource?.name ?: "ENVIRONMENT_OR_DEFAULT",
            temperature = resolved?.temperature,
            temperatureSource = resolved?.temperatureSource?.name ?: "ENVIRONMENT_OR_DEFAULT",
            maxOutputTokens = resolved?.maxOutputTokens ?: 0,
            maxOutputTokensSource = resolved?.maxOutputTokensSource?.name ?: "ENVIRONMENT_OR_DEFAULT",
            reasoningEnabled = false,
            jsonMode = null,
            providerSort = resolved?.generationProviderSort,
            providerSortSource = resolved?.generationProviderSortSource?.name ?: "PROVIDER_DEFAULT",
        )
        val intentDiscovery = WorkloadEffectiveConfigResponse(
            workload = "intent_discovery",
            model = resolved?.intentModel ?: environmentModel,
            modelSource = resolved?.intentModelSource?.name ?: "ENVIRONMENT_OR_DEFAULT",
            temperature = null,
            temperatureSource = "PROVIDER_DEFAULT",
            maxOutputTokens = resolved?.intentMaxOutputTokens ?: 600,
            maxOutputTokensSource = resolved?.intentMaxOutputTokensSource?.name ?: "CODE_DEFAULT",
            reasoningEnabled = null,
            jsonMode = resolved?.intentJsonMode,
            jsonModeSource = resolved?.intentJsonModeSource?.name ?: "PROVIDER_DEFAULT",
            providerSort = null,
            providerSortSource = "PROVIDER_DEFAULT",
        )
        fun sideChannel(workload: String, maxOutputTokens: Int) = WorkloadEffectiveConfigResponse(
            workload = workload,
            model = environmentModel,
            // Documented discrepancy: side-channel calls use raw environmentModel
            // directly (ChatEngineFactory.build()), NEVER the DB-resolved model —
            // so this is always ENVIRONMENT_OR_DEFAULT regardless of ai_settings.
            modelSource = "ENVIRONMENT_OR_DEFAULT",
            temperature = null,
            temperatureSource = "PROVIDER_DEFAULT",
            maxOutputTokens = maxOutputTokens,
            maxOutputTokensSource = "CODE_DEFAULT",
            reasoningEnabled = null,
            jsonMode = null,
            providerSort = null,
            providerSortSource = "PROVIDER_DEFAULT",
        )
        return EffectiveConfigurationResponse(
            workloads = listOf(
                primaryGeneration,
                intentDiscovery,
                sideChannel("memory_extraction", 1200),
                sideChannel("continuity_summarization", 1000),
                sideChannel("memory_engine_maintenance", 6000),
            ),
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
        skillKey = skillKey,
    )

    private fun PerformanceMetricsRepository.LatencyStats.toResponse() = LatencyStatsResponse(count, p50, p75, p95, p99, max, avg)

    private fun PerformanceMetricsRepository.SlaBuckets.toResponse() = SlaBucketsResponse(le5s, le8s, le10s, gt10s, gt20s, total, within10sRate)

    private fun PerformanceMetricsRepository.ErrorRate.toResponse() = ErrorRateResponse(total, success, malformed, budgetExhaustion, providerError, exception, failureRate)

    private fun PerformanceMetricsRepository.SlowTurnSummary.toResponse() = SlowTurnSummaryResponse(
        turnRequestId = turnRequestId.toString(),
        conversationId = conversationId.toString(),
        onPathLatencyMs = onPathLatencyMs,
        intentLatencyMs = intentLatencyMs,
        generationLatencyMs = generationLatencyMs,
        skillKey = skillKey,
        intentModel = intentModel,
        generationModel = generationModel,
        generationProvider = generationProvider,
        outcome = outcome,
        isTestChat = isTestChat,
        createdAt = createdAt.toString(),
    )

    private fun PerformanceMetricsRepository.DimensionStats.toResponse() = DimensionStatsResponse(
        count = latency.count,
        p50 = latency.p50,
        p75 = latency.p75,
        p95 = latency.p95,
        p99 = latency.p99,
        max = latency.max,
        avg = latency.avg,
        le10s = sla.le10s,
        gt10s = sla.gt10s,
        gt20s = sla.gt20s,
        within10sRatePercent = sla.within10sRate,
        errorRatePercent = errorRate.failureRate,
    )
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

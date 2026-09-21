package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.LlmExchanges
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.ceil

/**
 * Task 3 — SLA / Performance Data Model.
 *
 * Pure aggregation layer over the raw [LlmExchanges] rows Task 2 already
 * persists. Deliberately reads the same table Task 2 writes — no second
 * write path, no duplicated capture. Everything here is a query, computed
 * fresh on each call; nothing is cached or materialized, since the raw
 * table is small enough (one row per LLM call, not per token) for direct
 * aggregation to be cheap, and a stale cache would be a worse admin
 * experience than a slightly slower query.
 *
 * Percentiles are computed in application code, not SQL, so the exact same
 * nearest-rank definition works identically against both H2 (tests) and
 * PostgreSQL (production) — the two engines don't agree on a single
 * percentile function/syntax.
 */
class PerformanceMetricsRepository(private val db: Database) {

    /**
     * Every dimension this task's spec lists that [LlmExchanges] can actually
     * answer directly: workload (stands in for "Engine" — each workload IS a
     * pipeline stage/engine), model, provider, conversation, turn, date-time,
     * and production vs Test Chat. Skill/Persona/Intent-result breakdowns are
     * NOT implemented here — see the honest gap note in the Task 3 report:
     * the skill selected for a turn is not currently a queryable column on
     * any table, only free text inside a per-message metadata blob, and
     * fabricating a join against that would not be a real dimension, just a
     * best-effort string match.
     */
    data class MetricsFilter(
        val workload: String? = null,
        val model: String? = null,
        val provider: String? = null,
        val conversationId: UUID? = null,
        val isTestChat: Boolean? = null,
        val from: LocalDateTime? = null,
        val to: LocalDateTime? = null,
    )

    data class LatencyStats(
        val count: Long,
        val p50: Long?,
        val p75: Long?,
        val p95: Long?,
        val p99: Long?,
        val max: Long?,
        val avg: Double?,
    )

    data class SlaBuckets(
        val le5s: Long,
        val le8s: Long,
        val le10s: Long,
        val gt10s: Long,
        val gt20s: Long,
        val total: Long,
    ) {
        val within10sRate: Double get() = if (total == 0L) 0.0 else (le10s.toDouble() / total) * 100.0
    }

    data class ErrorRate(
        val total: Long,
        val success: Long,
        val malformed: Long,
        val budgetExhaustion: Long,
        val providerError: Long,
        val exception: Long,
    ) {
        val failureRate: Double get() = if (total == 0L) 0.0 else ((total - success).toDouble() / total) * 100.0
    }

    /** One row of a per-turn latency reconstruction — every LLM call that happened as part of one turn, in order. */
    data class TurnLatencyBreakdown(
        val turnRequestId: UUID,
        val conversationId: UUID,
        val exchanges: List<LlmExchangeRepository.Exchange>,
    ) {
        /** Sum of every ON-PATH (user-facing) workload's latency — excludes async side-channels by design. */
        fun onPathLatencyMs(onPathWorkloads: Set<String> = setOf("intent_discovery", "primary_generation")): Long =
            exchanges.filter { it.workload in onPathWorkloads }.sumOf { it.latencyMs }
    }

    /**
     * Task 6 — Export/Analysis. Every raw exchange matching [filter], for
     * bulk export. Deliberately duplicates [LlmExchangeRepository]'s row
     * mapping (rather than importing that class's private mapper) to keep
     * this class a one-directional consumer of [LlmExchanges] — it already
     * depends on [LlmExchangeRepository.Exchange] and [LlmExchangeRepository.Outcome]
     * as return/enum types, and adding a back-reference the other direction
     * would create a needless two-way coupling for a ~15-line mapping.
     *
     * Default limit (10,000) exists so an export call without an explicit
     * limit fails safe to "a lot, but bounded" rather than accidentally
     * streaming an unbounded table.
     */
    fun findExchanges(filter: MetricsFilter = MetricsFilter(), limit: Int = 10_000): List<LlmExchangeRepository.Exchange> = transaction(db) {
        LlmExchanges.select { buildCondition(filter) }
            .orderBy(LlmExchanges.createdAt to SortOrder.DESC)
            .limit(limit)
            .map(::rowToExchange)
    }

    private fun rowToExchange(row: ResultRow) = LlmExchangeRepository.Exchange(
        id = row[LlmExchanges.id],
        turnRequestId = row[LlmExchanges.turnRequestId],
        conversationId = row[LlmExchanges.conversationId],
        workload = row[LlmExchanges.workload],
        isTestChat = row[LlmExchanges.isTestChat],
        model = row[LlmExchanges.model],
        provider = row[LlmExchanges.provider],
        latencyMs = row[LlmExchanges.latencyMs],
        promptTokens = row[LlmExchanges.promptTokens],
        completionTokens = row[LlmExchanges.completionTokens],
        reasoningTokens = row[LlmExchanges.reasoningTokens],
        totalTokens = row[LlmExchanges.totalTokens],
        finishReason = row[LlmExchanges.finishReason],
        httpStatusCode = row[LlmExchanges.httpStatusCode],
        outcome = LlmExchangeRepository.Outcome.valueOf(row[LlmExchanges.outcome]),
        errorClass = row[LlmExchanges.errorClass],
        errorMessage = row[LlmExchanges.errorMessage],
        requestBody = row[LlmExchanges.requestBody],
        responseBody = row[LlmExchanges.responseBody],
        createdAt = row[LlmExchanges.createdAt],
    )

    fun latencyStats(filter: MetricsFilter = MetricsFilter()): LatencyStats = transaction(db) {
        val latencies = LlmExchanges.select { buildCondition(filter) }
            .map { it[LlmExchanges.latencyMs] }
            .sorted()
        toLatencyStats(latencies)
    }

    fun slaBuckets(filter: MetricsFilter = MetricsFilter()): SlaBuckets = transaction(db) {
        val latencies = LlmExchanges.select { buildCondition(filter) }.map { it[LlmExchanges.latencyMs] }
        SlaBuckets(
            le5s = latencies.count { it <= 5000 }.toLong(),
            le8s = latencies.count { it <= 8000 }.toLong(),
            le10s = latencies.count { it <= 10000 }.toLong(),
            gt10s = latencies.count { it > 10000 }.toLong(),
            gt20s = latencies.count { it > 20000 }.toLong(),
            total = latencies.size.toLong(),
        )
    }

    fun errorRate(filter: MetricsFilter = MetricsFilter()): ErrorRate = transaction(db) {
        val outcomes = LlmExchanges.select { buildCondition(filter) }.map { it[LlmExchanges.outcome] }
        ErrorRate(
            total = outcomes.size.toLong(),
            success = outcomes.count { it == LlmExchangeRepository.Outcome.SUCCESS.name }.toLong(),
            malformed = outcomes.count { it == LlmExchangeRepository.Outcome.MALFORMED.name }.toLong(),
            budgetExhaustion = outcomes.count { it == LlmExchangeRepository.Outcome.BUDGET_EXHAUSTION.name }.toLong(),
            providerError = outcomes.count { it == LlmExchangeRepository.Outcome.PROVIDER_ERROR.name }.toLong(),
            exception = outcomes.count { it == LlmExchangeRepository.Outcome.EXCEPTION.name }.toLong(),
        )
    }

    /** Latency stats grouped by workload (Intent/Generation/Memory/Continuity/Memory-Engine). */
    fun statsByWorkload(filter: MetricsFilter = MetricsFilter()): Map<String, LatencyStats> =
        groupedStats(filter) { it[LlmExchanges.workload] }

    fun statsByModel(filter: MetricsFilter = MetricsFilter()): Map<String, LatencyStats> =
        groupedStats(filter) { it[LlmExchanges.model] ?: "unknown" }

    fun statsByProvider(filter: MetricsFilter = MetricsFilter()): Map<String, LatencyStats> =
        groupedStats(filter) { it[LlmExchanges.provider] ?: "unknown" }

    private fun groupedStats(filter: MetricsFilter, keyOf: (org.jetbrains.exposed.sql.ResultRow) -> String): Map<String, LatencyStats> =
        transaction(db) {
            LlmExchanges.select { buildCondition(filter) }
                .groupBy(keyOf)
                .mapValues { (_, rows) -> toLatencyStats(rows.map { it[LlmExchanges.latencyMs] }.sorted()) }
        }

    /**
     * Reconstructs every LLM call that belonged to one turn, in the order
     * they were recorded — the "Stage Skill Engine Model Provider Intent
     * Conversation Turn" breakdown the spec asks for, keyed by turn instead
     * of pre-aggregated.
     */
    fun turnBreakdown(turnRequestId: UUID): TurnLatencyBreakdown? = transaction(db) {
        val exchanges = LlmExchangeRepository(db).findForTurn(turnRequestId)
        if (exchanges.isEmpty()) return@transaction null
        TurnLatencyBreakdown(turnRequestId, exchanges.first().conversationId, exchanges)
    }

    private fun buildCondition(filter: MetricsFilter): Op<Boolean> {
        var condition: Op<Boolean> = Op.TRUE
        filter.workload?.let { condition = condition and (LlmExchanges.workload eq it) }
        filter.model?.let { condition = condition and (LlmExchanges.model eq it) }
        filter.provider?.let { condition = condition and (LlmExchanges.provider eq it) }
        filter.conversationId?.let { condition = condition and (LlmExchanges.conversationId eq it) }
        filter.isTestChat?.let { condition = condition and (LlmExchanges.isTestChat eq it) }
        filter.from?.let { condition = condition and (LlmExchanges.createdAt greaterEq it) }
        filter.to?.let { condition = condition and (LlmExchanges.createdAt lessEq it) }
        return condition
    }

    /** Nearest-rank percentile (ceil(p/100 * n)), 1-indexed into the sorted list — the same definition regardless of DB engine. */
    private fun percentile(sorted: List<Long>, p: Int): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun toLatencyStats(sorted: List<Long>): LatencyStats = LatencyStats(
        count = sorted.size.toLong(),
        p50 = percentile(sorted, 50),
        p75 = percentile(sorted, 75),
        p95 = percentile(sorted, 95),
        p99 = percentile(sorted, 99),
        max = sorted.maxOrNull(),
        avg = if (sorted.isEmpty()) null else sorted.average(),
    )
}

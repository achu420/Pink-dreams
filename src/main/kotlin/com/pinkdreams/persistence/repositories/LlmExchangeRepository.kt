package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.LlmExchanges
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/**
 * LLM Observability and Raw Exchange Capture phase.
 *
 * The single, uniform write path for every LLM exchange, across every
 * workload (Intent, primary generation, memory extraction, continuity,
 * memory-engine maintenance) and both production and Test Chat traffic.
 * Written exclusively by [com.pinkdreams.llm.observability.ObservableLlmClient].
 */
class LlmExchangeRepository(private val db: Database) {

    enum class Outcome { SUCCESS, MALFORMED, BUDGET_EXHAUSTION, PROVIDER_ERROR, EXCEPTION }

    data class Exchange(
        val id: UUID,
        val turnRequestId: UUID,
        val conversationId: UUID,
        val workload: String,
        val isTestChat: Boolean,
        val model: String?,
        val provider: String?,
        val latencyMs: Long,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val reasoningTokens: Int?,
        val totalTokens: Int?,
        val finishReason: String?,
        val httpStatusCode: Int?,
        val outcome: Outcome,
        val errorClass: String?,
        val errorMessage: String?,
        val requestBody: String?,
        val responseBody: String?,
        val createdAt: LocalDateTime,
    )

    data class RecordInput(
        val turnRequestId: UUID,
        val conversationId: UUID,
        val workload: String,
        val isTestChat: Boolean,
        val model: String?,
        val provider: String?,
        val latencyMs: Long,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val totalTokens: Int? = null,
        val finishReason: String? = null,
        val httpStatusCode: Int? = null,
        val outcome: Outcome,
        val errorClass: String? = null,
        val errorMessage: String? = null,
        val requestBody: String? = null,
        val responseBody: String? = null,
    )

    /**
     * Insert-only. Every caller (ObservableLlmClient) invokes this from a
     * try/catch that swallows any failure here — diagnostics persistence
     * must never fail the user-facing turn (Task 2 requirement 8). This
     * method itself does not swallow exceptions; that is the caller's job,
     * matching the same best-effort convention already used by
     * BestEffortMemoryExtraction and friends.
     */
    fun record(input: RecordInput): UUID = transaction(db) {
        val id = UUID.randomUUID()
        LlmExchanges.insert {
            it[LlmExchanges.id] = id
            it[turnRequestId] = input.turnRequestId
            it[conversationId] = input.conversationId
            it[workload] = input.workload
            it[isTestChat] = input.isTestChat
            it[model] = input.model
            it[provider] = input.provider
            it[latencyMs] = input.latencyMs
            it[promptTokens] = input.promptTokens
            it[completionTokens] = input.completionTokens
            it[reasoningTokens] = input.reasoningTokens
            it[totalTokens] = input.totalTokens
            it[finishReason] = input.finishReason
            it[httpStatusCode] = input.httpStatusCode
            it[outcome] = input.outcome.name
            it[errorClass] = input.errorClass
            it[errorMessage] = input.errorMessage
            it[requestBody] = input.requestBody
            it[responseBody] = input.responseBody
            it[createdAt] = defaultNow()
        }
        id
    }

    /**
     * Reclassifies the most recent SUCCESS exchange for this turn/workload as
     * MALFORMED — a valid HTTP response was received, but its content did not
     * parse into the shape the caller required. This is deliberately a
     * follow-up update rather than something [ObservableLlmClient] decides on
     * its own: content-shape validity is workload-specific (Intent expects
     * `{"skillKey": ...}`, primary generation expects free text), so only the
     * caller that actually attempted the parse can make this call. Currently
     * wired from LlmIntentDiscovery only — the one caller with a pre-existing,
     * precise parse-outcome signal.
     */
    fun markMalformed(turnRequestId: UUID, workload: String): Boolean = transaction(db) {
        val latest = LlmExchanges.select {
            (LlmExchanges.turnRequestId eq turnRequestId) and
                (LlmExchanges.workload eq workload) and
                (LlmExchanges.outcome eq Outcome.SUCCESS.name)
        }.orderBy(LlmExchanges.createdAt to SortOrder.DESC).limit(1).map { it[LlmExchanges.id] }.singleOrNull()
            ?: return@transaction false
        LlmExchanges.update({ LlmExchanges.id eq latest }) {
            it[outcome] = Outcome.MALFORMED.name
        }
        true
    }

    fun findById(id: UUID): Exchange? = transaction(db) {
        LlmExchanges.select { LlmExchanges.id eq id }.map(::rowToModel).singleOrNull()
    }

    fun findForTurn(turnRequestId: UUID): List<Exchange> = transaction(db) {
        LlmExchanges.select { LlmExchanges.turnRequestId eq turnRequestId }
            .orderBy(LlmExchanges.createdAt to SortOrder.ASC)
            .map(::rowToModel)
    }

    fun findForConversation(conversationId: UUID, limit: Int = 200): List<Exchange> = transaction(db) {
        LlmExchanges.select { LlmExchanges.conversationId eq conversationId }
            .orderBy(LlmExchanges.createdAt to SortOrder.ASC)
            .limit(limit)
            .map(::rowToModel)
    }

    /** Basic filtering for the admin/export surface — extended as Task 4/6 require more dimensions. */
    fun findByWorkload(workload: String, isTestChat: Boolean? = null, limit: Int = 200): List<Exchange> = transaction(db) {
        val condition = if (isTestChat != null) {
            (LlmExchanges.workload eq workload) and (LlmExchanges.isTestChat eq isTestChat)
        } else {
            LlmExchanges.workload eq workload
        }
        LlmExchanges.select { condition }
            .orderBy(LlmExchanges.createdAt to SortOrder.DESC)
            .limit(limit)
            .map(::rowToModel)
    }

    private fun rowToModel(row: ResultRow): Exchange = Exchange(
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
        outcome = Outcome.valueOf(row[LlmExchanges.outcome]),
        errorClass = row[LlmExchanges.errorClass],
        errorMessage = row[LlmExchanges.errorMessage],
        requestBody = row[LlmExchanges.requestBody],
        responseBody = row[LlmExchanges.responseBody],
        createdAt = row[LlmExchanges.createdAt],
    )
}

package com.pinkdreams.persistence.repositories

import com.pinkdreams.observability.TurnAttributionRecord
import com.pinkdreams.observability.TurnAttributionRecorder
import com.pinkdreams.persistence.database.TurnAttributions
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Task 24 — the single write/read path for the per-turn attribution record.
 *
 * Structurally identical to [LlmExchangeRepository]: insert-only, no business
 * logic, and it does NOT swallow its own exceptions — isolation is the
 * caller's responsibility, exactly as ObservableLlmClient already does for
 * exchanges (see [FailureIsolatedTurnAttributionRecorder] below, which is the
 * one production caller).
 */
class TurnAttributionRepository(private val db: Database) {

    fun record(record: TurnAttributionRecord) = transaction(db) {
        TurnAttributions.insert {
            it[turnRequestId] = record.turnRequestId
            it[conversationId] = record.conversationId
            it[userId] = record.userId
            it[isTestChat] = record.isTestChat
            it[personaId] = record.personaId
            it[personaVersionId] = record.personaVersionId
            it[personaVersion] = record.personaVersion
            it[conversationEngineId] = record.conversationEngineId
            it[conversationEngineVersion] = record.conversationEngineVersion
            it[intentEngineId] = record.intentEngineId
            it[intentEngineVersion] = record.intentEngineVersion
            it[intentModel] = record.intentModel
            it[intentModelSource] = record.intentModelSource
            it[intentJsonMode] = record.intentJsonMode
            it[intentJsonModeSource] = record.intentJsonModeSource
            it[intentMaxOutputTokens] = record.intentMaxOutputTokens
            it[intentMaxOutputTokensSource] = record.intentMaxOutputTokensSource
            it[intentOutcome] = record.intentOutcome
            it[intentResultSkillKey] = record.intentResultSkillKey
            it[selectedSkillKey] = record.selectedSkillKey
            it[skillContextInjected] = record.skillContextInjected
            it[memoryIdsUsed] = record.memoryIdsUsed?.joinToString(",", "[", "]") { id -> "\"$id\"" }
            it[memoryCountUsed] = record.memoryCountUsed
            it[memoryCandidateCount] = record.memoryCandidateCount
            it[memorySelectionSource] = record.memorySelectionSource
            it[userProfilePresent] = record.userProfilePresent
            it[userProfileUpdatedAt] = record.userProfileUpdatedAt
            it[generationModel] = record.generationModel
            it[generationModelSource] = record.generationModelSource
            it[generationTemperature] = record.generationTemperature
            it[generationTemperatureSource] = record.generationTemperatureSource
            it[generationMaxOutputTokens] = record.generationMaxOutputTokens
            it[generationMaxOutputTokensSource] = record.generationMaxOutputTokensSource
            it[generationReasoning] = record.generationReasoning
            it[generationJsonMode] = record.generationJsonMode
            it[generationProviderSort] = record.generationProviderSort
            it[generationProviderSortSource] = record.generationProviderSortSource
            it[regenerationOccurred] = record.regenerationOccurred
            it[regenerationCount] = record.regenerationCount
            it[clientMessageId] = record.clientMessageId
            it[assistantMessageId] = record.assistantMessageId
            it[outcome] = record.outcome
            it[failedStage] = record.failedStage
            it[createdAt] = defaultNow()
        }
        Unit
    }

    fun findByTurnRequestId(turnRequestId: UUID): TurnAttributionRecord? = transaction(db) {
        TurnAttributions.select { TurnAttributions.turnRequestId eq turnRequestId }
            .map(::rowToModel)
            .singleOrNull()
    }

    private fun rowToModel(row: ResultRow) = TurnAttributionRecord(
        turnRequestId = row[TurnAttributions.turnRequestId],
        conversationId = row[TurnAttributions.conversationId],
        userId = row[TurnAttributions.userId],
        isTestChat = row[TurnAttributions.isTestChat],
        personaId = row[TurnAttributions.personaId],
        personaVersionId = row[TurnAttributions.personaVersionId],
        personaVersion = row[TurnAttributions.personaVersion],
        conversationEngineId = row[TurnAttributions.conversationEngineId],
        conversationEngineVersion = row[TurnAttributions.conversationEngineVersion],
        intentEngineId = row[TurnAttributions.intentEngineId],
        intentEngineVersion = row[TurnAttributions.intentEngineVersion],
        intentModel = row[TurnAttributions.intentModel],
        intentModelSource = row[TurnAttributions.intentModelSource],
        intentJsonMode = row[TurnAttributions.intentJsonMode],
        intentJsonModeSource = row[TurnAttributions.intentJsonModeSource],
        intentMaxOutputTokens = row[TurnAttributions.intentMaxOutputTokens],
        intentMaxOutputTokensSource = row[TurnAttributions.intentMaxOutputTokensSource],
        intentOutcome = row[TurnAttributions.intentOutcome],
        intentResultSkillKey = row[TurnAttributions.intentResultSkillKey],
        selectedSkillKey = row[TurnAttributions.selectedSkillKey],
        skillContextInjected = row[TurnAttributions.skillContextInjected],
        memoryIdsUsed = parseMemoryIds(row[TurnAttributions.memoryIdsUsed]),
        memoryCountUsed = row[TurnAttributions.memoryCountUsed],
        memoryCandidateCount = row[TurnAttributions.memoryCandidateCount],
        memorySelectionSource = row[TurnAttributions.memorySelectionSource],
        userProfilePresent = row[TurnAttributions.userProfilePresent],
        userProfileUpdatedAt = row[TurnAttributions.userProfileUpdatedAt],
        generationModel = row[TurnAttributions.generationModel],
        generationModelSource = row[TurnAttributions.generationModelSource],
        generationTemperature = row[TurnAttributions.generationTemperature],
        generationTemperatureSource = row[TurnAttributions.generationTemperatureSource],
        generationMaxOutputTokens = row[TurnAttributions.generationMaxOutputTokens],
        generationMaxOutputTokensSource = row[TurnAttributions.generationMaxOutputTokensSource],
        generationReasoning = row[TurnAttributions.generationReasoning],
        generationJsonMode = row[TurnAttributions.generationJsonMode],
        generationProviderSort = row[TurnAttributions.generationProviderSort],
        generationProviderSortSource = row[TurnAttributions.generationProviderSortSource],
        regenerationOccurred = row[TurnAttributions.regenerationOccurred],
        regenerationCount = row[TurnAttributions.regenerationCount],
        clientMessageId = row[TurnAttributions.clientMessageId],
        assistantMessageId = row[TurnAttributions.assistantMessageId],
        outcome = row[TurnAttributions.outcome],
        failedStage = row[TurnAttributions.failedStage],
    )

    /**
     * A malformed/legacy value yields null ("not attributed") rather than an
     * exception — a diagnostic read must never 500 the admin UI over a value
     * it cannot parse.
     */
    private fun parseMemoryIds(raw: String?): List<UUID>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            raw.trim().removePrefix("[").removeSuffix("]")
                .split(',')
                .mapNotNull { it.trim().trim('"').takeIf(String::isNotBlank) }
                .map(UUID::fromString)
        }.getOrNull()
    }
}

/**
 * The one production [TurnAttributionRecorder]. Mirrors ObservableLlmClient's
 * `persistSafely` convention exactly: attribution persistence failing must
 * never fail the user-facing turn (Task 24 Part 18).
 */
class FailureIsolatedTurnAttributionRecorder(
    private val repository: TurnAttributionRepository,
) : TurnAttributionRecorder {
    override fun record(record: TurnAttributionRecord) {
        try {
            repository.record(record)
        } catch (e: Exception) {
            System.err.println("TURN_ATTRIBUTION_PERSIST_FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

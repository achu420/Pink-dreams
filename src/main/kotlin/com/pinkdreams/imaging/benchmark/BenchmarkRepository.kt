package com.pinkdreams.imaging.benchmark

import com.pinkdreams.persistence.database.BenchmarkEvaluations
import com.pinkdreams.persistence.database.BenchmarkExecutions
import com.pinkdreams.persistence.database.BenchmarkModels
import com.pinkdreams.persistence.database.BenchmarkPrompts
import com.pinkdreams.persistence.database.BenchmarkRuns
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class BenchmarkRepository(private val db: Database) {

    data class RunRow(
        val id: UUID,
        val name: String,
        val status: String,
        val notes: String?,
        val createdBy: String?,
        val createdAt: LocalDateTime,
        val startedAt: LocalDateTime?,
        val completedAt: LocalDateTime?,
    )

    data class ModelRow(
        val id: UUID,
        val benchmarkRunId: UUID,
        val slotKey: String,
        val modelId: String?,
        val provider: String,
        val displayName: String,
        val capabilitySnapshot: String?,
        val pricingSnapshot: String?,
        val status: String,
        val sortOrder: Int,
    )

    data class PromptRow(
        val id: UUID,
        val benchmarkRunId: UUID,
        val promptId: String,
        val promptText: String,
        val category: String,
    )

    data class ExecutionRow(
        val id: UUID,
        val benchmarkRunId: UUID,
        val personaId: UUID,
        val visualIdentityVersionId: UUID,
        val promptId: String,
        val modelId: String?,
        val slotKey: String,
        val referencesAvailable: String?,
        val referencesSent: String?,
        val referencesOmitted: String?,
        val referenceOmitReason: String?,
        val requestedCandidates: Int?,
        val actualCandidates: Int?,
        val requestedResolution: String?,
        val actualResolution: String?,
        val resolutionDeviation: String?,
        val jobId: UUID?,
        val providerRequestId: String?,
        val status: String,
        val providerFailureType: String?,
        val providerFailureMessage: String?,
        val actualCost: BigDecimal?,
        val currency: String?,
        val createdAt: LocalDateTime,
        val completedAt: LocalDateTime?,
    )

    data class EvaluationRow(
        val id: UUID,
        val executionId: UUID,
        val candidateId: UUID?,
        val identityRating: Int?,
        val identityRemarks: String?,
        val realismRating: Int?,
        val realismRemarks: String?,
        val adminDecision: String?,
        val adminRemarks: String?,
        val evaluatedAt: LocalDateTime,
        val evaluatedBy: String?,
    )

    fun createRun(name: String, notes: String?, createdBy: String?): RunRow = transaction(db) {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        BenchmarkRuns.insert {
            it[BenchmarkRuns.id] = id
            it[BenchmarkRuns.name] = name
            it[BenchmarkRuns.status] = "DRAFT"
            it[BenchmarkRuns.notes] = notes
            it[BenchmarkRuns.createdBy] = createdBy
            it[BenchmarkRuns.createdAt] = now
        }
        findRun(id)!!
    }

    fun findRun(id: UUID): RunRow? = transaction(db) {
        BenchmarkRuns.select { BenchmarkRuns.id eq id }.singleOrNull()?.let { mapRun(it) }
    }

    fun listRuns(limit: Int = 50): List<RunRow> = transaction(db) {
        BenchmarkRuns.selectAll()
            .orderBy(BenchmarkRuns.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { mapRun(it) }
    }

    fun updateRunStatus(id: UUID, status: String, started: Boolean = false, completed: Boolean = false) = transaction(db) {
        val now = LocalDateTime.now()
        BenchmarkRuns.update({ BenchmarkRuns.id eq id }) {
            it[BenchmarkRuns.status] = status
            if (started) it[BenchmarkRuns.startedAt] = now
            if (completed) it[BenchmarkRuns.completedAt] = now
        }
    }

    fun insertModel(row: ModelRow) = transaction(db) {
        BenchmarkModels.insert {
            it[id] = row.id
            it[benchmarkRunId] = row.benchmarkRunId
            it[slotKey] = row.slotKey
            it[modelId] = row.modelId
            it[provider] = row.provider
            it[displayName] = row.displayName
            it[capabilitySnapshot] = row.capabilitySnapshot
            it[pricingSnapshot] = row.pricingSnapshot
            it[status] = row.status
            it[sortOrder] = row.sortOrder
        }
    }

    fun listModels(runId: UUID): List<ModelRow> = transaction(db) {
        BenchmarkModels.select { BenchmarkModels.benchmarkRunId eq runId }
            .orderBy(BenchmarkModels.sortOrder to SortOrder.ASC)
            .map { mapModel(it) }
    }

    fun insertPrompt(row: PromptRow) = transaction(db) {
        BenchmarkPrompts.insert {
            it[id] = row.id
            it[benchmarkRunId] = row.benchmarkRunId
            it[promptId] = row.promptId
            it[promptText] = row.promptText
            it[category] = row.category
        }
    }

    fun listPrompts(runId: UUID): List<PromptRow> = transaction(db) {
        BenchmarkPrompts.select { BenchmarkPrompts.benchmarkRunId eq runId }.map { mapPrompt(it) }
    }

    fun insertExecution(row: ExecutionRow) = transaction(db) {
        BenchmarkExecutions.insert {
            it[id] = row.id
            it[benchmarkRunId] = row.benchmarkRunId
            it[personaId] = row.personaId
            it[visualIdentityVersionId] = row.visualIdentityVersionId
            it[promptId] = row.promptId
            it[modelId] = row.modelId
            it[slotKey] = row.slotKey
            it[referencesAvailable] = row.referencesAvailable
            it[referencesSent] = row.referencesSent
            it[referencesOmitted] = row.referencesOmitted
            it[referenceOmitReason] = row.referenceOmitReason
            it[requestedCandidates] = row.requestedCandidates
            it[actualCandidates] = row.actualCandidates
            it[requestedResolution] = row.requestedResolution
            it[actualResolution] = row.actualResolution
            it[resolutionDeviation] = row.resolutionDeviation
            it[jobId] = row.jobId
            it[providerRequestId] = row.providerRequestId
            it[status] = row.status
            it[providerFailureType] = row.providerFailureType
            it[providerFailureMessage] = row.providerFailureMessage
            it[actualCost] = row.actualCost
            it[currency] = row.currency
            it[createdAt] = row.createdAt
            it[completedAt] = row.completedAt
        }
    }

    fun listExecutions(runId: UUID): List<ExecutionRow> = transaction(db) {
        BenchmarkExecutions.select { BenchmarkExecutions.benchmarkRunId eq runId }
            .orderBy(BenchmarkExecutions.createdAt to SortOrder.ASC)
            .map { mapExecution(it) }
    }

    fun findExecution(id: UUID): ExecutionRow? = transaction(db) {
        BenchmarkExecutions.select { BenchmarkExecutions.id eq id }.singleOrNull()?.let { mapExecution(it) }
    }

    fun updateExecutionAfterSubmit(
        id: UUID,
        jobId: UUID?,
        status: String,
        failureType: String?,
        failureMessage: String?,
    ) = transaction(db) {
        BenchmarkExecutions.update({ BenchmarkExecutions.id eq id }) {
            it[BenchmarkExecutions.jobId] = jobId
            it[BenchmarkExecutions.status] = status
            it[BenchmarkExecutions.providerFailureType] = failureType
            it[BenchmarkExecutions.providerFailureMessage] = failureMessage
        }
    }

    fun syncExecutionFromJob(
        id: UUID,
        status: String,
        actualCandidates: Int?,
        actualCost: BigDecimal?,
        currency: String?,
        providerRequestId: String?,
        failureType: String?,
        failureMessage: String?,
        completed: Boolean,
    ) = transaction(db) {
        val now = LocalDateTime.now()
        BenchmarkExecutions.update({ BenchmarkExecutions.id eq id }) {
            it[BenchmarkExecutions.status] = status
            if (actualCandidates != null) it[BenchmarkExecutions.actualCandidates] = actualCandidates
            it[BenchmarkExecutions.actualCost] = actualCost
            it[BenchmarkExecutions.currency] = currency
            if (providerRequestId != null) it[BenchmarkExecutions.providerRequestId] = providerRequestId
            it[BenchmarkExecutions.providerFailureType] = failureType
            it[BenchmarkExecutions.providerFailureMessage] = failureMessage
            if (completed) it[BenchmarkExecutions.completedAt] = now
        }
    }

    fun upsertEvaluation(
        executionId: UUID,
        candidateId: UUID?,
        identityRating: Int?,
        identityRemarks: String?,
        realismRating: Int?,
        realismRemarks: String?,
        adminDecision: String?,
        adminRemarks: String?,
        evaluatedBy: String?,
    ): EvaluationRow = transaction(db) {
        val existing = BenchmarkEvaluations.select {
            BenchmarkEvaluations.executionId eq executionId
        }.firstOrNull()
        val now = LocalDateTime.now()
        if (existing != null) {
            val evalId = existing[BenchmarkEvaluations.id]
            BenchmarkEvaluations.update({ BenchmarkEvaluations.id eq evalId }) {
                if (candidateId != null) it[BenchmarkEvaluations.candidateId] = candidateId
                if (identityRating != null) it[BenchmarkEvaluations.identityRating] = identityRating
                if (identityRemarks != null) it[BenchmarkEvaluations.identityRemarks] = identityRemarks
                if (realismRating != null) it[BenchmarkEvaluations.realismRating] = realismRating
                if (realismRemarks != null) it[BenchmarkEvaluations.realismRemarks] = realismRemarks
                if (adminDecision != null) it[BenchmarkEvaluations.adminDecision] = adminDecision
                if (adminRemarks != null) it[BenchmarkEvaluations.adminRemarks] = adminRemarks
                it[BenchmarkEvaluations.evaluatedAt] = now
                it[BenchmarkEvaluations.evaluatedBy] = evaluatedBy
            }
            listEvaluationsForExecution(executionId).first()
        } else {
            val id = UUID.randomUUID()
            BenchmarkEvaluations.insert {
                it[BenchmarkEvaluations.id] = id
                it[BenchmarkEvaluations.executionId] = executionId
                it[BenchmarkEvaluations.candidateId] = candidateId
                it[BenchmarkEvaluations.identityRating] = identityRating
                it[BenchmarkEvaluations.identityRemarks] = identityRemarks
                it[BenchmarkEvaluations.realismRating] = realismRating
                it[BenchmarkEvaluations.realismRemarks] = realismRemarks
                it[BenchmarkEvaluations.adminDecision] = adminDecision
                it[BenchmarkEvaluations.adminRemarks] = adminRemarks
                it[BenchmarkEvaluations.evaluatedAt] = now
                it[BenchmarkEvaluations.evaluatedBy] = evaluatedBy
            }
            listEvaluationsForExecution(executionId).first()
        }
    }

    fun listEvaluationsForRun(runId: UUID): List<EvaluationRow> = transaction(db) {
        val execIds = BenchmarkExecutions.select { BenchmarkExecutions.benchmarkRunId eq runId }
            .map { it[BenchmarkExecutions.id] }
            .toSet()
        if (execIds.isEmpty()) emptyList()
        else BenchmarkEvaluations.selectAll()
            .map { mapEval(it) }
            .filter { it.executionId in execIds }
    }

    fun listEvaluationsForExecution(executionId: UUID): List<EvaluationRow> = transaction(db) {
        BenchmarkEvaluations.select { BenchmarkEvaluations.executionId eq executionId }
            .map { mapEval(it) }
    }

    fun tablesAvailable(): Boolean = transaction(db) {
        try {
            BenchmarkRuns.selectAll().limit(1).toList()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun mapRun(row: org.jetbrains.exposed.sql.ResultRow) = RunRow(
        id = row[BenchmarkRuns.id],
        name = row[BenchmarkRuns.name],
        status = row[BenchmarkRuns.status],
        notes = row[BenchmarkRuns.notes],
        createdBy = row[BenchmarkRuns.createdBy],
        createdAt = row[BenchmarkRuns.createdAt],
        startedAt = row[BenchmarkRuns.startedAt],
        completedAt = row[BenchmarkRuns.completedAt],
    )

    private fun mapModel(row: org.jetbrains.exposed.sql.ResultRow) = ModelRow(
        id = row[BenchmarkModels.id],
        benchmarkRunId = row[BenchmarkModels.benchmarkRunId],
        slotKey = row[BenchmarkModels.slotKey],
        modelId = row[BenchmarkModels.modelId],
        provider = row[BenchmarkModels.provider],
        displayName = row[BenchmarkModels.displayName],
        capabilitySnapshot = row[BenchmarkModels.capabilitySnapshot],
        pricingSnapshot = row[BenchmarkModels.pricingSnapshot],
        status = row[BenchmarkModels.status],
        sortOrder = row[BenchmarkModels.sortOrder],
    )

    private fun mapPrompt(row: org.jetbrains.exposed.sql.ResultRow) = PromptRow(
        id = row[BenchmarkPrompts.id],
        benchmarkRunId = row[BenchmarkPrompts.benchmarkRunId],
        promptId = row[BenchmarkPrompts.promptId],
        promptText = row[BenchmarkPrompts.promptText],
        category = row[BenchmarkPrompts.category],
    )

    private fun mapExecution(row: org.jetbrains.exposed.sql.ResultRow) = ExecutionRow(
        id = row[BenchmarkExecutions.id],
        benchmarkRunId = row[BenchmarkExecutions.benchmarkRunId],
        personaId = row[BenchmarkExecutions.personaId],
        visualIdentityVersionId = row[BenchmarkExecutions.visualIdentityVersionId],
        promptId = row[BenchmarkExecutions.promptId],
        modelId = row[BenchmarkExecutions.modelId],
        slotKey = row[BenchmarkExecutions.slotKey],
        referencesAvailable = row[BenchmarkExecutions.referencesAvailable],
        referencesSent = row[BenchmarkExecutions.referencesSent],
        referencesOmitted = row[BenchmarkExecutions.referencesOmitted],
        referenceOmitReason = row[BenchmarkExecutions.referenceOmitReason],
        requestedCandidates = row[BenchmarkExecutions.requestedCandidates],
        actualCandidates = row[BenchmarkExecutions.actualCandidates],
        requestedResolution = row[BenchmarkExecutions.requestedResolution],
        actualResolution = row[BenchmarkExecutions.actualResolution],
        resolutionDeviation = row[BenchmarkExecutions.resolutionDeviation],
        jobId = row[BenchmarkExecutions.jobId],
        providerRequestId = row[BenchmarkExecutions.providerRequestId],
        status = row[BenchmarkExecutions.status],
        providerFailureType = row[BenchmarkExecutions.providerFailureType],
        providerFailureMessage = row[BenchmarkExecutions.providerFailureMessage],
        actualCost = row[BenchmarkExecutions.actualCost],
        currency = row[BenchmarkExecutions.currency],
        createdAt = row[BenchmarkExecutions.createdAt],
        completedAt = row[BenchmarkExecutions.completedAt],
    )

    private fun mapEval(row: org.jetbrains.exposed.sql.ResultRow) = EvaluationRow(
        id = row[BenchmarkEvaluations.id],
        executionId = row[BenchmarkEvaluations.executionId],
        candidateId = row[BenchmarkEvaluations.candidateId],
        identityRating = row[BenchmarkEvaluations.identityRating],
        identityRemarks = row[BenchmarkEvaluations.identityRemarks],
        realismRating = row[BenchmarkEvaluations.realismRating],
        realismRemarks = row[BenchmarkEvaluations.realismRemarks],
        adminDecision = row[BenchmarkEvaluations.adminDecision],
        adminRemarks = row[BenchmarkEvaluations.adminRemarks],
        evaluatedAt = row[BenchmarkEvaluations.evaluatedAt],
        evaluatedBy = row[BenchmarkEvaluations.evaluatedBy],
    )
}

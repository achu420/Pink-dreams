package com.pinkdreams.imaging.evaluation

import com.pinkdreams.persistence.database.ImageEvaluationModels
import com.pinkdreams.persistence.database.ImageEvaluations
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class ImageEvaluationRepository(private val db: Database) {

    data class EvaluationRow(
        val id: UUID,
        val personaId: UUID,
        val visualVersionId: UUID,
        val seedPrompt: String,
        val candidateCount: Int,
        val status: String,
        val productionModelSnapshot: String?,
        val createdBy: String?,
        val createdAt: LocalDateTime,
    )

    data class EvaluationModelRow(
        val id: UUID,
        val evaluationId: UUID,
        val modelId: String,
        val displayName: String?,
        val provider: String,
        val imageJobId: UUID?,
        val status: String,
        val sortOrder: Int,
        val notes: String?,
        val identityConsistency: Int?,
        val sceneAdherence: Int?,
        val poseAdherence: Int?,
        val wardrobeAdherence: Int?,
        val imageQuality: Int?,
        val naturalness: Int?,
        val artifactQuality: Int?,
        val providerRestrictionNotes: String?,
        val createdAt: LocalDateTime,
    )

    data class ModelNotesUpdate(
        val notes: String? = null,
        val identityConsistency: Int? = null,
        val sceneAdherence: Int? = null,
        val poseAdherence: Int? = null,
        val wardrobeAdherence: Int? = null,
        val imageQuality: Int? = null,
        val naturalness: Int? = null,
        val artifactQuality: Int? = null,
        val providerRestrictionNotes: String? = null,
    )

    fun createEvaluation(
        personaId: UUID,
        visualVersionId: UUID,
        seedPrompt: String,
        candidateCount: Int,
        productionModelSnapshot: String?,
        createdBy: String?,
    ): EvaluationRow = transaction(db) {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        ImageEvaluations.insert {
            it[ImageEvaluations.id] = id
            it[ImageEvaluations.personaId] = personaId
            it[ImageEvaluations.visualVersionId] = visualVersionId
            it[ImageEvaluations.seedPrompt] = seedPrompt
            it[ImageEvaluations.candidateCount] = candidateCount
            it[ImageEvaluations.status] = "RUNNING"
            it[ImageEvaluations.productionModelSnapshot] = productionModelSnapshot
            it[ImageEvaluations.createdBy] = createdBy
            it[ImageEvaluations.createdAt] = now
        }
        EvaluationRow(
            id = id,
            personaId = personaId,
            visualVersionId = visualVersionId,
            seedPrompt = seedPrompt,
            candidateCount = candidateCount,
            status = "RUNNING",
            productionModelSnapshot = productionModelSnapshot,
            createdBy = createdBy,
            createdAt = now,
        )
    }

    fun addModel(
        evaluationId: UUID,
        modelId: String,
        displayName: String?,
        provider: String,
        imageJobId: UUID?,
        status: String,
        sortOrder: Int,
    ): EvaluationModelRow = transaction(db) {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        ImageEvaluationModels.insert {
            it[ImageEvaluationModels.id] = id
            it[ImageEvaluationModels.evaluationId] = evaluationId
            it[ImageEvaluationModels.modelId] = modelId
            it[ImageEvaluationModels.displayName] = displayName
            it[ImageEvaluationModels.provider] = provider
            it[ImageEvaluationModels.imageJobId] = imageJobId
            it[ImageEvaluationModels.status] = status
            it[ImageEvaluationModels.sortOrder] = sortOrder
            it[ImageEvaluationModels.createdAt] = now
        }
        EvaluationModelRow(
            id = id,
            evaluationId = evaluationId,
            modelId = modelId,
            displayName = displayName,
            provider = provider,
            imageJobId = imageJobId,
            status = status,
            sortOrder = sortOrder,
            notes = null,
            identityConsistency = null,
            sceneAdherence = null,
            poseAdherence = null,
            wardrobeAdherence = null,
            imageQuality = null,
            naturalness = null,
            artifactQuality = null,
            providerRestrictionNotes = null,
            createdAt = now,
        )
    }

    fun updateEvaluationStatus(evaluationId: UUID, status: String) = transaction(db) {
        ImageEvaluations.update({ ImageEvaluations.id eq evaluationId }) {
            it[ImageEvaluations.status] = status
        }
    }

    fun updateModelJob(modelRowId: UUID, imageJobId: UUID?, status: String) = transaction(db) {
        ImageEvaluationModels.update({ ImageEvaluationModels.id eq modelRowId }) {
            it[ImageEvaluationModels.imageJobId] = imageJobId
            it[ImageEvaluationModels.status] = status
        }
    }

    fun updateModelNotes(modelRowId: UUID, update: ModelNotesUpdate): EvaluationModelRow? = transaction(db) {
        if (ImageEvaluationModels.select { ImageEvaluationModels.id eq modelRowId }.empty()) {
            return@transaction null
        }
        ImageEvaluationModels.update({ ImageEvaluationModels.id eq modelRowId }) {
            update.notes?.let { v -> it[ImageEvaluationModels.notes] = v }
            update.identityConsistency?.let { v -> it[ImageEvaluationModels.identityConsistency] = v }
            update.sceneAdherence?.let { v -> it[ImageEvaluationModels.sceneAdherence] = v }
            update.poseAdherence?.let { v -> it[ImageEvaluationModels.poseAdherence] = v }
            update.wardrobeAdherence?.let { v -> it[ImageEvaluationModels.wardrobeAdherence] = v }
            update.imageQuality?.let { v -> it[ImageEvaluationModels.imageQuality] = v }
            update.naturalness?.let { v -> it[ImageEvaluationModels.naturalness] = v }
            update.artifactQuality?.let { v -> it[ImageEvaluationModels.artifactQuality] = v }
            update.providerRestrictionNotes?.let { v -> it[ImageEvaluationModels.providerRestrictionNotes] = v }
        }
        mapModel(ImageEvaluationModels.select { ImageEvaluationModels.id eq modelRowId }.single())
    }

    fun findEvaluation(id: UUID): EvaluationRow? = transaction(db) {
        ImageEvaluations.select { ImageEvaluations.id eq id }
            .singleOrNull()
            ?.let(::mapEvaluation)
    }

    fun listEvaluations(limit: Int = 50): List<EvaluationRow> = transaction(db) {
        ImageEvaluations.selectAll()
            .orderBy(ImageEvaluations.createdAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, 200))
            .map(::mapEvaluation)
    }

    fun listModels(evaluationId: UUID): List<EvaluationModelRow> = transaction(db) {
        ImageEvaluationModels.select { ImageEvaluationModels.evaluationId eq evaluationId }
            .orderBy(ImageEvaluationModels.sortOrder to SortOrder.ASC)
            .map(::mapModel)
    }

    fun findModel(modelRowId: UUID): EvaluationModelRow? = transaction(db) {
        ImageEvaluationModels.select { ImageEvaluationModels.id eq modelRowId }
            .singleOrNull()
            ?.let(::mapModel)
    }

    private fun mapEvaluation(row: org.jetbrains.exposed.sql.ResultRow) = EvaluationRow(
        id = row[ImageEvaluations.id],
        personaId = row[ImageEvaluations.personaId],
        visualVersionId = row[ImageEvaluations.visualVersionId],
        seedPrompt = row[ImageEvaluations.seedPrompt],
        candidateCount = row[ImageEvaluations.candidateCount],
        status = row[ImageEvaluations.status],
        productionModelSnapshot = row[ImageEvaluations.productionModelSnapshot],
        createdBy = row[ImageEvaluations.createdBy],
        createdAt = row[ImageEvaluations.createdAt],
    )

    private fun mapModel(row: org.jetbrains.exposed.sql.ResultRow) = EvaluationModelRow(
        id = row[ImageEvaluationModels.id],
        evaluationId = row[ImageEvaluationModels.evaluationId],
        modelId = row[ImageEvaluationModels.modelId],
        displayName = row[ImageEvaluationModels.displayName],
        provider = row[ImageEvaluationModels.provider],
        imageJobId = row[ImageEvaluationModels.imageJobId],
        status = row[ImageEvaluationModels.status],
        sortOrder = row[ImageEvaluationModels.sortOrder],
        notes = row[ImageEvaluationModels.notes],
        identityConsistency = row[ImageEvaluationModels.identityConsistency],
        sceneAdherence = row[ImageEvaluationModels.sceneAdherence],
        poseAdherence = row[ImageEvaluationModels.poseAdherence],
        wardrobeAdherence = row[ImageEvaluationModels.wardrobeAdherence],
        imageQuality = row[ImageEvaluationModels.imageQuality],
        naturalness = row[ImageEvaluationModels.naturalness],
        artifactQuality = row[ImageEvaluationModels.artifactQuality],
        providerRestrictionNotes = row[ImageEvaluationModels.providerRestrictionNotes],
        createdAt = row[ImageEvaluationModels.createdAt],
    )
}

package com.pinkdreams.imaging.orchestration

import com.pinkdreams.persistence.database.GeneratedCandidates
import com.pinkdreams.persistence.database.ImageJobs
import com.pinkdreams.persistence.database.PersonaVisualVersions
import com.pinkdreams.persistence.database.Personas
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class WarehouseCandidateRow(
    val candidate: GeneratedCandidate,
    val jobId: UUID,
    val jobStatus: String,
    val personaVisualVersionId: UUID,
    val requestPayload: String,
)

/**
 * Persona-scoped warehouse listing across all visual versions/jobs.
 */
class ImageWarehouseRepository(private val db: Database) {
    fun findCandidatesForPersona(personaId: UUID, limit: Int = 100): List<WarehouseCandidateRow> = transaction(db) {
        val identityId = Personas.select { Personas.id eq personaId }
            .map { it[Personas.personaIdentityId] }
            .singleOrNull()
            ?: return@transaction emptyList()
        val linkedIdentity = identityId ?: return@transaction emptyList()

        val versionIds = PersonaVisualVersions.select { PersonaVisualVersions.personaIdentityId eq linkedIdentity }
            .map { it[PersonaVisualVersions.id] }
        if (versionIds.isEmpty()) return@transaction emptyList()

        val jobs = ImageJobs.select { ImageJobs.personaVisualVersionId inList versionIds }
            .orderBy(ImageJobs.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                Triple(
                    row[ImageJobs.id],
                    row[ImageJobs.status],
                    row[ImageJobs.personaVisualVersionId] to row[ImageJobs.requestPayload],
                )
            }
        if (jobs.isEmpty()) return@transaction emptyList()

        val jobIds = jobs.map { it.first }
        val jobMeta = jobs.associate { it.first to it }

        GeneratedCandidates.select { GeneratedCandidates.imageJobId inList jobIds }
            .orderBy(GeneratedCandidates.createdAt to SortOrder.DESC)
            .map { row ->
                val jobId = row[GeneratedCandidates.imageJobId]
                val meta = jobMeta[jobId]!!
                val status = try {
                    CandidateStatus.valueOf(row[GeneratedCandidates.status])
                } catch (_: Exception) {
                    CandidateStatus.GENERATED
                }
                WarehouseCandidateRow(
                    candidate = GeneratedCandidate(
                        id = row[GeneratedCandidates.id],
                        imageJobId = jobId,
                        storageKey = row[GeneratedCandidates.storageKey],
                        contentType = row[GeneratedCandidates.contentType],
                        fileSize = row[GeneratedCandidates.fileSize],
                        widthPx = row[GeneratedCandidates.widthPx],
                        heightPx = row[GeneratedCandidates.heightPx],
                        checksum = row[GeneratedCandidates.checksum],
                        candidateIndex = row[GeneratedCandidates.candidateIndex],
                        createdAt = row[GeneratedCandidates.createdAt],
                        status = status,
                        adminRemark = row[GeneratedCandidates.adminRemark],
                    ),
                    jobId = jobId,
                    jobStatus = meta.second,
                    personaVisualVersionId = meta.third.first,
                    requestPayload = meta.third.second,
                )
            }
    }
}

package com.pinkdreams.imaging.orchestration

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import com.pinkdreams.persistence.database.GeneratedCandidates
import java.time.LocalDateTime
import java.util.UUID

class GeneratedCandidateRepository(private val db: Database) {
    fun create(
        imageJobId: UUID,
        storageKey: String,
        contentType: String,
        fileSize: Long,
        widthPx: Int?,
        heightPx: Int?,
        checksum: String,
        candidateIndex: Int,
    ): GeneratedCandidate = transaction(db) {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()

        GeneratedCandidates.insert {
            it[GeneratedCandidates.id] = id
            it[GeneratedCandidates.imageJobId] = imageJobId
            it[GeneratedCandidates.storageKey] = storageKey
            it[GeneratedCandidates.contentType] = contentType
            it[GeneratedCandidates.fileSize] = fileSize
            it[GeneratedCandidates.widthPx] = widthPx
            it[GeneratedCandidates.heightPx] = heightPx
            it[GeneratedCandidates.checksum] = checksum
            it[GeneratedCandidates.candidateIndex] = candidateIndex
            it[GeneratedCandidates.createdAt] = now
        }

        GeneratedCandidate(
            id = id,
            imageJobId = imageJobId,
            storageKey = storageKey,
            contentType = contentType,
            fileSize = fileSize,
            widthPx = widthPx,
            heightPx = heightPx,
            checksum = checksum,
            candidateIndex = candidateIndex,
            createdAt = now
        )
    }

    fun findByImageJob(imageJobId: UUID): List<GeneratedCandidate> = transaction(db) {
        GeneratedCandidates.select { GeneratedCandidates.imageJobId eq imageJobId }
            .map { row ->
                GeneratedCandidate(
                    id = row[GeneratedCandidates.id],
                    imageJobId = row[GeneratedCandidates.imageJobId],
                    storageKey = row[GeneratedCandidates.storageKey],
                    contentType = row[GeneratedCandidates.contentType],
                    fileSize = row[GeneratedCandidates.fileSize],
                    widthPx = row[GeneratedCandidates.widthPx],
                    heightPx = row[GeneratedCandidates.heightPx],
                    checksum = row[GeneratedCandidates.checksum],
                    candidateIndex = row[GeneratedCandidates.candidateIndex],
                    createdAt = row[GeneratedCandidates.createdAt]
                )
            }
    }

    fun findById(id: UUID): GeneratedCandidate? = transaction(db) {
        GeneratedCandidates.select { GeneratedCandidates.id eq id }
            .map { row ->
                GeneratedCandidate(
                    id = row[GeneratedCandidates.id],
                    imageJobId = row[GeneratedCandidates.imageJobId],
                    storageKey = row[GeneratedCandidates.storageKey],
                    contentType = row[GeneratedCandidates.contentType],
                    fileSize = row[GeneratedCandidates.fileSize],
                    widthPx = row[GeneratedCandidates.widthPx],
                    heightPx = row[GeneratedCandidates.heightPx],
                    checksum = row[GeneratedCandidates.checksum],
                    candidateIndex = row[GeneratedCandidates.candidateIndex],
                    createdAt = row[GeneratedCandidates.createdAt]
                )
            }
            .singleOrNull()
    }
}

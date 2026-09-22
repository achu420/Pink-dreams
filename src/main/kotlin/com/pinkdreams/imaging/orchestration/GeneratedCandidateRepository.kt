package com.pinkdreams.imaging.orchestration

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import com.pinkdreams.persistence.database.GeneratedCandidates
import java.time.LocalDateTime
import java.util.UUID

class GeneratedCandidateRepository(private val db: Database) {
    /** Removes prior candidate rows for a job so retries do not duplicate assets. */
    fun deleteByImageJob(imageJobId: UUID): Int = transaction(db) {
        GeneratedCandidates.deleteWhere { GeneratedCandidates.imageJobId eq imageJobId }
    }

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
            it[GeneratedCandidates.status] = CandidateStatus.GENERATED.name
            it[GeneratedCandidates.adminRemark] = null
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
            createdAt = now,
            status = CandidateStatus.GENERATED,
            adminRemark = null,
        )
    }

    fun findByImageJob(imageJobId: UUID): List<GeneratedCandidate> = transaction(db) {
        GeneratedCandidates.select { GeneratedCandidates.imageJobId eq imageJobId }
            .map { row -> mapRow(row) }
    }

    fun findById(id: UUID): GeneratedCandidate? = transaction(db) {
        GeneratedCandidates.select { GeneratedCandidates.id eq id }
            .map { row -> mapRow(row) }
            .singleOrNull()
    }

    fun updateStatus(id: UUID, status: CandidateStatus): GeneratedCandidate = transaction(db) {
        val updated = GeneratedCandidates.update({ GeneratedCandidates.id eq id }) {
            it[GeneratedCandidates.status] = status.name
        }
        if (updated == 0) throw NoSuchElementException("Candidate not found: $id")
        mapRow(
            GeneratedCandidates.select { GeneratedCandidates.id eq id }.single()
        )
    }

    fun updateRemark(id: UUID, remark: String?): GeneratedCandidate = transaction(db) {
        val updated = GeneratedCandidates.update({ GeneratedCandidates.id eq id }) {
            it[GeneratedCandidates.adminRemark] = remark
        }
        if (updated == 0) throw NoSuchElementException("Candidate not found: $id")
        mapRow(
            GeneratedCandidates.select { GeneratedCandidates.id eq id }.single()
        )
    }

    fun updateStatusAndRemark(
        id: UUID,
        status: CandidateStatus?,
        remark: String?,
    ): GeneratedCandidate = transaction(db) {
        if (status == null && remark == null) {
            return@transaction mapRow(
                GeneratedCandidates.select { GeneratedCandidates.id eq id }.singleOrNull()
                    ?: throw NoSuchElementException("Candidate not found: $id")
            )
        }
        val updated = GeneratedCandidates.update({ GeneratedCandidates.id eq id }) {
            if (status != null) it[GeneratedCandidates.status] = status.name
            if (remark != null) it[GeneratedCandidates.adminRemark] = remark
        }
        if (updated == 0) throw NoSuchElementException("Candidate not found: $id")
        mapRow(
            GeneratedCandidates.select { GeneratedCandidates.id eq id }.single()
        )
    }

    private fun mapRow(row: ResultRow): GeneratedCandidate =
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
            createdAt = row[GeneratedCandidates.createdAt],
            status = parseStatus(row[GeneratedCandidates.status]),
            adminRemark = row[GeneratedCandidates.adminRemark],
        )

    private fun parseStatus(raw: String?): CandidateStatus =
        try {
            if (raw.isNullOrBlank()) CandidateStatus.GENERATED
            else CandidateStatus.valueOf(raw)
        } catch (_: Exception) {
            CandidateStatus.GENERATED
        }
}

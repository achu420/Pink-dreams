package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.MemoryFacts
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class MemoryFactRepository(private val db: Database) {
    data class MemoryFact(
        val id: UUID,
        val userId: UUID,
        val personaId: UUID,
        val fact: String,
        val factType: String,
        val criticality: String,
        val criticalityRank: Short?,
        val tier: String,
        val status: String,
        val source: String,
        val learnedAt: LocalDateTime,
        val lastReferencedAt: LocalDateTime?,
        val evictedAt: LocalDateTime?,
    )

    fun create(
        userId: UUID,
        personaId: UUID,
        fact: String,
        factType: String,
        criticality: String,
        tier: String = "hot",
        status: String = "open",
        source: String = "llm_extracted",
        learnedAt: LocalDateTime = defaultNow(),
    ): MemoryFact = transaction(db) {
        val id = UUID.randomUUID()
        MemoryFacts.insert {
            it[MemoryFacts.id] = id
            it[MemoryFacts.userId] = userId
            it[MemoryFacts.personaId] = personaId
            it[MemoryFacts.fact] = fact
            it[MemoryFacts.factType] = factType
            it[MemoryFacts.criticality] = criticality
            it[MemoryFacts.tier] = tier
            it[MemoryFacts.status] = status
            it[MemoryFacts.factSource] = source
            it[MemoryFacts.learnedAt] = learnedAt
            it[MemoryFacts.lastReferencedAt] = null
            it[MemoryFacts.evictedAt] = null
        }
        findById(id)!!
    }

    fun findById(id: UUID): MemoryFact? = transaction(db) {
        MemoryFacts.select { MemoryFacts.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findForRelationship(userId: UUID, personaId: UUID): List<MemoryFact> = transaction(db) {
        MemoryFacts.select {
            (MemoryFacts.userId eq userId) and (MemoryFacts.personaId eq personaId)
        }.map(::rowToModel)
    }

    fun moveToCold(userId: UUID, personaId: UUID, factId: UUID, evictedAt: LocalDateTime = defaultNow()): MemoryFact = transaction(db) {
        val updated = MemoryFacts.update({
            (MemoryFacts.id eq factId) and
                (MemoryFacts.userId eq userId) and
                (MemoryFacts.personaId eq personaId) and
                (MemoryFacts.tier eq "hot")
        }) {
            it[MemoryFacts.tier] = "cold"
            it[MemoryFacts.evictedAt] = evictedAt
        }
        require(updated == 1) { "Hot memory fact not found: $factId" }
        findById(factId)!!
    }

    fun markReferenced(
        userId: UUID,
        personaId: UUID,
        factId: UUID,
        referencedAt: LocalDateTime = defaultNow(),
    ): MemoryFact = transaction(db) {
        val updated = MemoryFacts.update({
            (MemoryFacts.id eq factId) and
                (MemoryFacts.userId eq userId) and
                (MemoryFacts.personaId eq personaId) and
                (MemoryFacts.tier eq "hot")
        }) {
            it[MemoryFacts.lastReferencedAt] = referencedAt
        }
        require(updated == 1) { "Hot memory fact not found: $factId" }
        findById(factId)!!
    }

    private fun rowToModel(row: ResultRow): MemoryFact = MemoryFact(
        id = row[MemoryFacts.id],
        userId = row[MemoryFacts.userId],
        personaId = row[MemoryFacts.personaId],
        fact = row[MemoryFacts.fact],
        factType = row[MemoryFacts.factType],
        criticality = row[MemoryFacts.criticality],
        criticalityRank = row[MemoryFacts.criticalityRank],
        tier = row[MemoryFacts.tier],
        status = row[MemoryFacts.status],
        source = row[MemoryFacts.factSource],
        learnedAt = row[MemoryFacts.learnedAt],
        lastReferencedAt = row[MemoryFacts.lastReferencedAt],
        evictedAt = row[MemoryFacts.evictedAt],
    )
}
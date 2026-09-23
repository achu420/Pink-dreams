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
        val owner: String = "USER",
        val supersedesId: UUID? = null,
        val updatedAt: LocalDateTime? = null,
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
        owner: String = "USER",
        supersedesId: UUID? = null,
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
            it[MemoryFacts.owner] = owner
            it[MemoryFacts.supersedesId] = supersedesId
            it[MemoryFacts.updatedAt] = null
        }
        findById(id)!!
    }

    /**
     * In-place content refinement (Memory Engine UPDATE action) — the same
     * durable fact, restated more precisely. Not for contradictions; see
     * [supersede] for that.
     */
    fun updateContent(factId: UUID, newContent: String, updatedAt: LocalDateTime = defaultNow()): MemoryFact = transaction(db) {
        val updated = MemoryFacts.update({ MemoryFacts.id eq factId }) {
            it[MemoryFacts.fact] = newContent
            it[MemoryFacts.updatedAt] = updatedAt
        }
        require(updated == 1) { "Memory fact not found: $factId" }
        findById(factId)!!
    }

    /**
     * Memory Engine SUPERSEDE action: the existing fact is marked
     * status="superseded" (never deleted — history is preserved, matching
     * the same non-destructive convention as SensitivePreferenceRepository's
     * own supersession), and a new row is created linked via supersedesId.
     */
    fun supersede(
        factId: UUID,
        newContent: String,
        factType: String? = null,
        criticality: String? = null,
        owner: String? = null,
    ): MemoryFact = transaction(db) {
        val existing = findById(factId) ?: throw IllegalArgumentException("Memory fact not found: $factId")
        val now = defaultNow()
        MemoryFacts.update({ MemoryFacts.id eq factId }) {
            it[MemoryFacts.status] = "superseded"
            it[MemoryFacts.updatedAt] = now
        }
        create(
            userId = existing.userId,
            personaId = existing.personaId,
            fact = newContent,
            factType = factType ?: existing.factType,
            criticality = criticality ?: existing.criticality,
            tier = existing.tier,
            owner = owner ?: existing.owner,
            supersedesId = factId,
        )
    }

    /**
     * Admin PATCH action — change only the status field (e.g. open → resolved).
     * Complementary to [updateContent], which changes only the fact text.
     * Valid target statuses: "open", "resolved", "removed".
     */
    fun updateStatus(factId: UUID, newStatus: String, updatedAt: LocalDateTime = defaultNow()): MemoryFact = transaction(db) {
        require(newStatus in setOf("open", "resolved", "removed")) {
            "Invalid status '$newStatus'; allowed: open, resolved, removed"
        }
        val updated = MemoryFacts.update({ MemoryFacts.id eq factId }) {
            it[MemoryFacts.status] = newStatus
            it[MemoryFacts.updatedAt] = updatedAt
        }
        require(updated == 1) { "Memory fact not found: $factId" }
        findById(factId)!!
    }

    /**
     * Memory Engine REMOVE action: a soft, auditable state change
     * (status="removed") — never a SQL DELETE. Distinct from context-selection
     * "not selected this turn," which never touches the database at all.
     */
    fun remove(factId: UUID, removedAt: LocalDateTime = defaultNow()): MemoryFact = transaction(db) {
        val updated = MemoryFacts.update({ MemoryFacts.id eq factId }) {
            it[MemoryFacts.status] = "removed"
            it[MemoryFacts.updatedAt] = removedAt
        }
        require(updated == 1) { "Memory fact not found: $factId" }
        findById(factId)!!
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

    /**
     * Admin User Detail → Memory tab. Every fact learned for this user across
     * EVERY persona they have talked to — memory itself stays relationship-
     * scoped (user+persona) row by row; this is only a read that spans the
     * relationships. Additive: purely a new SELECT, no existing query, write
     * path or scoping rule is changed, and [findForRelationship] is untouched.
     */
    fun findAllForUser(userId: UUID): List<MemoryFact> = transaction(db) {
        MemoryFacts.select { MemoryFacts.userId eq userId }
            .map(::rowToModel)
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
        // Not sourced from Exposed (see DatabaseFactory.MemoryFacts) — the real
        // column is a Postgres-side GENERATED value; MemoryService.rank()
        // computes the same mapping from `criticality` whenever this is null.
        criticalityRank = null,
        tier = row[MemoryFacts.tier],
        status = row[MemoryFacts.status],
        source = row[MemoryFacts.factSource],
        learnedAt = row[MemoryFacts.learnedAt],
        lastReferencedAt = row[MemoryFacts.lastReferencedAt],
        evictedAt = row[MemoryFacts.evictedAt],
        owner = row[MemoryFacts.owner],
        supersedesId = row[MemoryFacts.supersedesId],
        updatedAt = row[MemoryFacts.updatedAt],
    )
}
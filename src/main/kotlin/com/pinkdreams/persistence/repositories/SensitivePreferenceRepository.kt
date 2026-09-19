package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.SensitivePreferences
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

/**
 * Storage for explicit, user-stated romantic/intimacy/sexual preferences and
 * boundaries — deliberately separate from MemoryFactRepository (see
 * DatabaseFactory.SensitivePreferences for why) and from UserProfileRepository
 * (stable identity attributes, not intimacy statements).
 */
class SensitivePreferenceRepository(private val db: Database) {
    data class SensitivePreference(
        val id: UUID,
        val userId: UUID,
        val personaId: UUID,
        val category: String,
        val preferenceType: String,
        val content: String,
        val provenance: String,
        val source: String,
        val status: String,
        val supersedesId: UUID?,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
    )

    fun create(
        userId: UUID,
        personaId: UUID,
        category: String,
        preferenceType: String,
        content: String,
        provenance: String,
        source: String = "user_stated",
        supersedesId: UUID? = null,
    ): SensitivePreference {
        require(category in CATEGORIES) { "Invalid sensitive preference category: $category (must be one of $CATEGORIES)" }
        require(preferenceType in TYPES) { "Invalid sensitive preference type: $preferenceType (must be one of $TYPES)" }
        require(provenance in PROVENANCES) { "Invalid sensitive preference provenance: $provenance (must be one of $PROVENANCES)" }
        require(content.isNotBlank()) { "Sensitive preference content cannot be blank" }

        return transaction(db) {
            val id = UUID.randomUUID()
            val now = defaultNow()
            SensitivePreferences.insert {
                it[SensitivePreferences.id] = id
                it[SensitivePreferences.userId] = userId
                it[SensitivePreferences.personaId] = personaId
                it[SensitivePreferences.category] = category
                it[SensitivePreferences.preferenceType] = preferenceType
                it[SensitivePreferences.content] = content
                it[SensitivePreferences.provenance] = provenance
                it[SensitivePreferences.preferenceSource] = source
                it[SensitivePreferences.status] = "active"
                it[SensitivePreferences.supersedesId] = supersedesId
                it[SensitivePreferences.createdAt] = now
                it[SensitivePreferences.updatedAt] = now
            }
            findById(id)!!
        }
    }

    fun findById(id: UUID): SensitivePreference? = transaction(db) {
        SensitivePreferences.select { SensitivePreferences.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    /** All rows for this user+persona relationship, every status — full history. */
    fun findForRelationship(userId: UUID, personaId: UUID): List<SensitivePreference> = transaction(db) {
        SensitivePreferences.select {
            (SensitivePreferences.userId eq userId) and (SensitivePreferences.personaId eq personaId)
        }.map(::rowToModel)
    }

    /** Only currently-active rows — the current state used for context retrieval. */
    fun findActiveForRelationship(userId: UUID, personaId: UUID): List<SensitivePreference> =
        findForRelationship(userId, personaId).filter { it.status == "active" }

    private fun findActiveOne(userId: UUID, personaId: UUID, category: String, preferenceType: String): SensitivePreference? =
        findActiveForRelationship(userId, personaId).singleOrNull { it.category == category && it.preferenceType == preferenceType }

    /**
     * Records a new explicit statement, superseding any existing active row for
     * the same (user, persona, category, type) rather than duplicating it — the
     * old row is kept (status="superseded"), not deleted, preserving provenance.
     */
    fun recordSuperseding(
        userId: UUID,
        personaId: UUID,
        category: String,
        preferenceType: String,
        content: String,
        provenance: String,
        source: String = "user_stated",
    ): SensitivePreference = transaction(db) {
        val existing = findActiveOne(userId, personaId, category, preferenceType)
        if (existing != null) {
            SensitivePreferences.update({ SensitivePreferences.id eq existing.id }) {
                it[SensitivePreferences.status] = "superseded"
                it[SensitivePreferences.updatedAt] = defaultNow()
            }
        }
        create(
            userId = userId,
            personaId = personaId,
            category = category,
            preferenceType = preferenceType,
            content = content,
            provenance = provenance,
            source = source,
            supersedesId = existing?.id,
        )
    }

    /** Soft-deletes an active preference. Does not remove the row (preserves auditability). */
    fun deactivate(id: UUID): SensitivePreference = transaction(db) {
        val updated = SensitivePreferences.update({ SensitivePreferences.id eq id }) {
            it[SensitivePreferences.status] = "deleted"
            it[SensitivePreferences.updatedAt] = defaultNow()
        }
        require(updated == 1) { "Sensitive preference not found: $id" }
        findById(id)!!
    }

    private fun rowToModel(row: ResultRow): SensitivePreference = SensitivePreference(
        id = row[SensitivePreferences.id],
        userId = row[SensitivePreferences.userId],
        personaId = row[SensitivePreferences.personaId],
        category = row[SensitivePreferences.category],
        preferenceType = row[SensitivePreferences.preferenceType],
        content = row[SensitivePreferences.content],
        provenance = row[SensitivePreferences.provenance],
        source = row[SensitivePreferences.preferenceSource],
        status = row[SensitivePreferences.status],
        supersedesId = row[SensitivePreferences.supersedesId],
        createdAt = row[SensitivePreferences.createdAt],
        updatedAt = row[SensitivePreferences.updatedAt],
    )

    companion object {
        val CATEGORIES = setOf("romantic", "intimacy", "sexual")
        val TYPES = setOf("preference", "boundary", "history")
        val PROVENANCES = setOf("explicit", "inferred")
    }
}

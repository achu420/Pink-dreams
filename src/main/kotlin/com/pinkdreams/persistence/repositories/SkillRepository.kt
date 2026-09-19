package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Skills
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Skills are versioned PER KEY (e.g. "flirting", "friendship") — unlike
 * ConversationEngineRepository (one single global active row), many distinct
 * keys can each independently have their own active version at the same
 * time. Mirrors the version-numbering pattern from PersonaCoreVersionRepository
 * (per-parent MAX+1 with retry) combined with the isActive/deactivate-then-
 * activate pattern from ConversationEngineRepository (scoped per key here,
 * not globally).
 */
open class SkillRepository(private val db: Database) {
    data class Skill(
        val id: UUID,
        val key: String,
        val version: Int,
        val content: String,
        val status: String,
        val isActive: Boolean,
        val changelogNote: String?,
        val author: String?,
    )

    fun create(
        key: String,
        version: Int,
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        author: String? = null,
    ): Skill = transaction(db) {
        val id = UUID.randomUUID()
        Skills.insert {
            it[Skills.id] = id
            it[Skills.key] = key
            it[Skills.version] = version
            it[Skills.content] = content
            it[Skills.status] = status
            it[Skills.isActive] = false
            it[Skills.changelogNote] = changelogNote
            it[Skills.author] = author
            it[Skills.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    /**
     * Creates the next version for a skill key with a SERVER-computed version
     * number — callers never supply one. nextVersion = (current max version
     * for this key) + 1, or 1 if none exist yet. The (key, version) unique
     * index is the actual safety net under concurrent creation: a losing
     * concurrent insert is caught here and retried against a freshly computed
     * max, up to [maxRetries] times, exactly mirroring
     * PersonaCoreVersionRepository.createNextVersion.
     */
    fun createNextVersion(
        key: String,
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        author: String? = null,
        maxRetries: Int = 5,
    ): Skill {
        var attempt = 0
        while (true) {
            attempt++
            val nextVersion = nextVersionNumber(key)
            try {
                return create(key = key, version = nextVersion, content = content, status = status, changelogNote = changelogNote, author = author)
            } catch (e: ExposedSQLException) {
                if (attempt >= maxRetries) {
                    throw IllegalStateException("Failed to allocate a unique skill version number for key '$key' after $maxRetries attempts", e)
                }
            }
        }
    }

    private fun nextVersionNumber(key: String): Int = transaction(db) {
        (Skills.select { Skills.key eq key }.maxOfOrNull { it[Skills.version] } ?: 0) + 1
    }

    fun findById(id: UUID): Skill? = transaction(db) {
        Skills.select { Skills.id eq id }.map(::rowToModel).singleOrNull()
    }

    fun findByKey(key: String): List<Skill> = transaction(db) {
        Skills.select { Skills.key eq key }.map(::rowToModel)
    }

    /** The single active version for this key, if any. */
    open fun getActiveForKey(key: String): Skill? = transaction(db) {
        Skills.select { (Skills.key eq key) and (Skills.isActive eq true) }.map(::rowToModel).singleOrNull()
    }

    /** Every distinct skill key that currently has an active version. */
    open fun findAllActiveKeys(): List<String> = transaction(db) {
        Skills.select { Skills.isActive eq true }.map { it[Skills.key] }
    }

    fun findAll(): List<Skill> = transaction(db) { Skills.selectAll().map(::rowToModel) }

    fun publish(skillId: UUID): Skill = transaction(db) {
        val skill = findById(skillId) ?: throw IllegalArgumentException("Skill not found: $skillId")
        require(skill.status == "draft") { "Only draft skills can be published" }
        Skills.update({ Skills.id eq skillId }) {
            it[Skills.status] = "published"
            it[Skills.isActive] = false
        }
        findById(skillId) ?: throw IllegalStateException("Failed to reload skill $skillId")
    }

    /**
     * Activates a published skill version, deactivating any other currently
     * active version for the SAME key (never touching other keys) — the same
     * mutual-exclusion approach as ConversationEngineRepository.activateEngine,
     * scoped per key rather than globally.
     */
    fun activate(skillId: UUID): Skill = transaction(db) {
        val skill = findById(skillId) ?: throw IllegalArgumentException("Skill not found: $skillId")
        require(skill.status == "published") { "Only published skills can be activated" }

        val currentActive = getActiveForKey(skill.key)
        if (currentActive != null && currentActive.id != skillId) {
            Skills.update({ Skills.id eq currentActive.id }) { it[Skills.isActive] = false }
        }
        Skills.update({ Skills.id eq skillId }) { it[Skills.isActive] = true }

        findById(skillId) ?: throw IllegalStateException("Failed to reload skill $skillId")
    }

    fun archive(skillId: UUID): Skill = transaction(db) {
        val skill = findById(skillId) ?: throw IllegalArgumentException("Skill not found: $skillId")
        if (skill.isActive) throw IllegalStateException("Active skill cannot be archived")
        require(skill.status == "published") { "Only published skills can be archived" }
        Skills.update({ Skills.id eq skillId }) { it[Skills.status] = "archived" }
        findById(skillId) ?: throw IllegalStateException("Failed to reload skill $skillId")
    }

    private fun rowToModel(row: ResultRow): Skill = Skill(
        id = row[Skills.id],
        key = row[Skills.key],
        version = row[Skills.version],
        content = row[Skills.content],
        status = row[Skills.status],
        isActive = row[Skills.isActive],
        changelogNote = row[Skills.changelogNote],
        author = row[Skills.author],
    )
}

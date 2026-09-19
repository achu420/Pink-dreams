package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.IntentEngines
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Intent Engine versioning — a deliberate structural mirror of
 * MemoryEngineRepository / ConversationEngineRepository (single global active
 * row, draft → published → active → archived), not a new lifecycle model.
 *
 * The Intent Engine stores only the behavioral decision rules. The candidate
 * skill keys are NOT stored here: they are supplied at runtime from the
 * currently active Skills, so authoring a new skill needs no edit to this
 * prompt and no code change.
 */
open class IntentEngineRepository(private val db: Database) {
    data class IntentEngine(
        val id: UUID,
        val version: Int,
        val content: String,
        val status: String,
        val isActive: Boolean,
        val changelogNote: String?,
        val createdBy: String?,
    )

    fun create(
        version: Int,
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        createdBy: String? = null,
    ): IntentEngine = transaction(db) {
        val id = UUID.randomUUID()
        IntentEngines.insert {
            it[IntentEngines.id] = id
            it[IntentEngines.version] = version
            it[IntentEngines.content] = content
            it[IntentEngines.status] = status
            it[IntentEngines.isActive] = false
            it[IntentEngines.changelogNote] = changelogNote
            it[IntentEngines.createdBy] = createdBy
            it[IntentEngines.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    // Version number is always server-computed (global current max version + 1)
    // — the client cannot supply or influence it. Same bounded-retry pattern as
    // MemoryEngineRepository.createNextVersion.
    fun createNextVersion(
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        createdBy: String? = null,
        maxRetries: Int = 5,
    ): IntentEngine {
        var attempt = 0
        while (true) {
            attempt++
            val nextVersion = nextVersionNumber()
            try {
                return create(version = nextVersion, content = content, status = status, changelogNote = changelogNote, createdBy = createdBy)
            } catch (e: ExposedSQLException) {
                if (attempt >= maxRetries) {
                    throw IllegalStateException("Failed to allocate a unique intent engine version number after $maxRetries attempts", e)
                }
            }
        }
    }

    private fun nextVersionNumber(): Int = transaction(db) {
        (IntentEngines.selectAll().maxOfOrNull { it[IntentEngines.version] } ?: 0) + 1
    }

    fun findById(id: UUID): IntentEngine? = transaction(db) {
        IntentEngines.select { IntentEngines.id eq id }.map(::rowToModel).singleOrNull()
    }

    open fun findByVersion(version: Int): IntentEngine? = transaction(db) {
        IntentEngines.select { IntentEngines.version eq version }.map(::rowToModel).singleOrNull()
    }

    open fun getActiveEngine(): IntentEngine? = transaction(db) {
        IntentEngines.select { IntentEngines.isActive eq true }.map(::rowToModel).singleOrNull()
    }

    fun findAll(): List<IntentEngine> = transaction(db) { IntentEngines.selectAll().map(::rowToModel) }

    fun publish(engineId: UUID): IntentEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Intent engine not found: $engineId")
        require(engine.status == "draft") { "Only draft intent engines can be published" }
        IntentEngines.update({ IntentEngines.id eq engineId }) {
            it[IntentEngines.status] = "published"
            it[IntentEngines.isActive] = false
        }
        findById(engineId) ?: throw IllegalStateException("Failed to reload intent engine $engineId")
    }

    /** Deactivate-then-activate, so at most one version is ever active. */
    fun activate(engineId: UUID): IntentEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Intent engine not found: $engineId")
        require(engine.status == "published") { "Only published intent engines can be activated" }
        val currentActive = getActiveEngine()
        if (currentActive != null && currentActive.id != engineId) {
            IntentEngines.update({ IntentEngines.id eq currentActive.id }) { it[IntentEngines.isActive] = false }
        }
        IntentEngines.update({ IntentEngines.id eq engineId }) { it[IntentEngines.isActive] = true }
        findById(engineId) ?: throw IllegalStateException("Failed to reload intent engine $engineId")
    }

    fun archive(engineId: UUID): IntentEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Intent engine not found: $engineId")
        if (engine.isActive) throw IllegalStateException("Active intent engine cannot be archived")
        require(engine.status == "published") { "Only published intent engines can be archived" }
        IntentEngines.update({ IntentEngines.id eq engineId }) { it[IntentEngines.status] = "archived" }
        findById(engineId) ?: throw IllegalStateException("Failed to reload intent engine $engineId")
    }

    private fun rowToModel(row: ResultRow): IntentEngine = IntentEngine(
        id = row[IntentEngines.id],
        version = row[IntentEngines.version],
        content = row[IntentEngines.content],
        status = row[IntentEngines.status],
        isActive = row[IntentEngines.isActive],
        changelogNote = row[IntentEngines.changelogNote],
        createdBy = row[IntentEngines.createdBy],
    )
}

package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.MemoryEngines
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
 * Memory Engine versioning — structurally identical to
 * ConversationEngineRepository (single global active row), a deliberate
 * mirror rather than a new pattern. See DatabaseFactory.MemoryEngines.
 */
open class MemoryEngineRepository(private val db: Database) {
    data class MemoryEngine(
        val id: UUID,
        val version: Int,
        val content: String,
        val status: String,
        val isActive: Boolean,
        val changelogNote: String?,
        val createdBy: String?,
        val batchSize: Int = DEFAULT_BATCH_SIZE,
        val relevantMemoryTarget: Int = DEFAULT_RELEVANT_MEMORY_TARGET,
    )

    fun create(
        version: Int,
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        createdBy: String? = null,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        relevantMemoryTarget: Int = DEFAULT_RELEVANT_MEMORY_TARGET,
    ): MemoryEngine = transaction(db) {
        val id = UUID.randomUUID()
        MemoryEngines.insert {
            it[MemoryEngines.id] = id
            it[MemoryEngines.version] = version
            it[MemoryEngines.content] = content
            it[MemoryEngines.status] = status
            it[MemoryEngines.isActive] = false
            it[MemoryEngines.changelogNote] = changelogNote
            it[MemoryEngines.createdBy] = createdBy
            it[MemoryEngines.createdAt] = defaultNow()
            it[MemoryEngines.batchSize] = batchSize
            it[MemoryEngines.relevantMemoryTarget] = relevantMemoryTarget
        }
        findById(id)!!
    }

    // Version number is always server-computed (global current max version +
    // 1) — the client cannot supply or influence it. Same retry pattern as
    // ConversationEngineRepository.createNextVersion.
    fun createNextVersion(
        content: String,
        status: String = "draft",
        changelogNote: String? = null,
        createdBy: String? = null,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        relevantMemoryTarget: Int = DEFAULT_RELEVANT_MEMORY_TARGET,
        maxRetries: Int = 5,
    ): MemoryEngine {
        var attempt = 0
        while (true) {
            attempt++
            val nextVersion = nextVersionNumber()
            try {
                return create(
                    version = nextVersion, content = content, status = status, changelogNote = changelogNote,
                    createdBy = createdBy, batchSize = batchSize, relevantMemoryTarget = relevantMemoryTarget,
                )
            } catch (e: ExposedSQLException) {
                if (attempt >= maxRetries) {
                    throw IllegalStateException("Failed to allocate a unique memory engine version number after $maxRetries attempts", e)
                }
            }
        }
    }

    private fun nextVersionNumber(): Int = transaction(db) {
        (MemoryEngines.selectAll().maxOfOrNull { it[MemoryEngines.version] } ?: 0) + 1
    }

    fun findById(id: UUID): MemoryEngine? = transaction(db) {
        MemoryEngines.select { MemoryEngines.id eq id }.map(::rowToModel).singleOrNull()
    }

    open fun findByVersion(version: Int): MemoryEngine? = transaction(db) {
        MemoryEngines.select { MemoryEngines.version eq version }.map(::rowToModel).singleOrNull()
    }

    open fun getActiveEngine(): MemoryEngine? = transaction(db) {
        MemoryEngines.select { MemoryEngines.isActive eq true }.map(::rowToModel).singleOrNull()
    }

    fun findAll(): List<MemoryEngine> = transaction(db) { MemoryEngines.selectAll().map(::rowToModel) }

    fun publish(engineId: UUID): MemoryEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Memory engine not found: $engineId")
        require(engine.status == "draft") { "Only draft memory engines can be published" }
        MemoryEngines.update({ MemoryEngines.id eq engineId }) {
            it[MemoryEngines.status] = "published"
            it[MemoryEngines.isActive] = false
        }
        findById(engineId) ?: throw IllegalStateException("Failed to reload memory engine $engineId")
    }

    fun activate(engineId: UUID): MemoryEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Memory engine not found: $engineId")
        require(engine.status == "published") { "Only published memory engines can be activated" }
        val currentActive = getActiveEngine()
        if (currentActive != null && currentActive.id != engineId) {
            MemoryEngines.update({ MemoryEngines.id eq currentActive.id }) { it[MemoryEngines.isActive] = false }
        }
        MemoryEngines.update({ MemoryEngines.id eq engineId }) { it[MemoryEngines.isActive] = true }
        findById(engineId) ?: throw IllegalStateException("Failed to reload memory engine $engineId")
    }

    fun archive(engineId: UUID): MemoryEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Memory engine not found: $engineId")
        if (engine.isActive) throw IllegalStateException("Active memory engine cannot be archived")
        require(engine.status == "published") { "Only published memory engines can be archived" }
        MemoryEngines.update({ MemoryEngines.id eq engineId }) { it[MemoryEngines.status] = "archived" }
        findById(engineId) ?: throw IllegalStateException("Failed to reload memory engine $engineId")
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
        const val DEFAULT_RELEVANT_MEMORY_TARGET = 20
    }

    private fun rowToModel(row: ResultRow): MemoryEngine = MemoryEngine(
        id = row[MemoryEngines.id],
        version = row[MemoryEngines.version],
        content = row[MemoryEngines.content],
        status = row[MemoryEngines.status],
        isActive = row[MemoryEngines.isActive],
        changelogNote = row[MemoryEngines.changelogNote],
        createdBy = row[MemoryEngines.createdBy],
        batchSize = row[MemoryEngines.batchSize],
        relevantMemoryTarget = row[MemoryEngines.relevantMemoryTarget],
    )
}

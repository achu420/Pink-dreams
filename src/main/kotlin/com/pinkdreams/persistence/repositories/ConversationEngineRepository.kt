package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.ConversationEngines
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

open class ConversationEngineRepository(private val db: Database) {
    data class ConversationEngine(
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
    ): ConversationEngine = transaction(db) {
        val id = UUID.randomUUID()
        ConversationEngines.insert {
            it[ConversationEngines.id] = id
            it[ConversationEngines.version] = version
            it[ConversationEngines.content] = content
            it[ConversationEngines.status] = status
            it[ConversationEngines.isActive] = false
            it[ConversationEngines.changelogNote] = changelogNote
            it[ConversationEngines.createdBy] = createdBy
            it[ConversationEngines.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    fun findById(id: UUID): ConversationEngine? = transaction(db) {
        ConversationEngines.select { ConversationEngines.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findByVersion(version: Int): ConversationEngine? = transaction(db) {
        ConversationEngines.select { ConversationEngines.version eq version }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun getActiveEngine(): ConversationEngine? = transaction(db) {
        ConversationEngines.select { ConversationEngines.isActive eq true }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findAll(): List<ConversationEngine> = transaction(db) {
        ConversationEngines.selectAll()
            .map(::rowToModel)
    }

    fun publishEngine(engineId: UUID): ConversationEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Engine not found: $engineId")
        require(engine.status == "draft") { "Only draft engines can be published" }

        ConversationEngines.update({ ConversationEngines.id eq engineId }) {
            it[ConversationEngines.status] = "published"
            it[ConversationEngines.isActive] = false
        }

        findById(engineId) ?: throw IllegalStateException("Failed to reload engine $engineId")
    }

    fun activateEngine(engineId: UUID): ConversationEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Engine not found: $engineId")
        require(engine.status == "published") { "Only published engines can be activated" }

        val currentActive = getActiveEngine()
        if (currentActive != null && currentActive.id != engineId) {
            ConversationEngines.update({ ConversationEngines.id eq currentActive.id }) {
                it[ConversationEngines.isActive] = false
            }
        }

        ConversationEngines.update({ ConversationEngines.id eq engineId }) {
            it[ConversationEngines.isActive] = true
        }

        findById(engineId) ?: throw IllegalStateException("Failed to reload engine $engineId")
    }

    fun archiveEngine(engineId: UUID): ConversationEngine = transaction(db) {
        val engine = findById(engineId) ?: throw IllegalArgumentException("Engine not found: $engineId")
        if (engine.isActive) {
            throw IllegalStateException("Active engine cannot be archived")
        }
        require(engine.status == "published") { "Only published engines can be archived" }

        ConversationEngines.update({ ConversationEngines.id eq engineId }) {
            it[ConversationEngines.status] = "archived"
        }

        findById(engineId) ?: throw IllegalStateException("Failed to reload engine $engineId")
    }

    private fun rowToModel(row: ResultRow): ConversationEngine = ConversationEngine(
        id = row[ConversationEngines.id],
        version = row[ConversationEngines.version],
        content = row[ConversationEngines.content],
        status = row[ConversationEngines.status],
        isActive = row[ConversationEngines.isActive],
        changelogNote = row[ConversationEngines.changelogNote],
        createdBy = row[ConversationEngines.createdBy],
    )
}

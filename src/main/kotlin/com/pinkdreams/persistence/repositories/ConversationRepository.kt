package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Conversations
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

open class ConversationRepository(private val db: Database) {
    data class Conversation(
        val id: UUID,
        val userId: UUID,
        val personaId: UUID,
        val state: String,
        val lastMessageAt: LocalDateTime?,
        val createdAt: LocalDateTime,
    )

    fun create(userId: UUID, personaId: UUID, state: String = "active"): Conversation = transaction(db) {
        require(state in setOf("active", "idle", "archived")) { "Invalid conversation state: $state" }
        val id = UUID.randomUUID()
        Conversations.insert {
            it[Conversations.id] = id
            it[Conversations.userId] = userId
            it[Conversations.personaId] = personaId
            it[Conversations.state] = state
            it[Conversations.lastMessageAt] = null
            it[Conversations.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    fun findById(id: UUID): Conversation? = transaction(db) {
        Conversations.select { Conversations.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    open fun findByIdForUser(id: UUID, userId: UUID): Conversation? = transaction(db) {
        Conversations.select { (Conversations.id eq id) and (Conversations.userId eq userId) }
            .map(::rowToModel)
            .singleOrNull()
    }

    open fun findAllForUser(userId: UUID, limit: Int = 20, offset: Int = 0): List<Conversation> = transaction(db) {
        Conversations.select { Conversations.userId eq userId }
            .orderBy(Conversations.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC, Conversations.id to org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map(::rowToModel)
    }

    fun updateLastMessageAt(id: UUID, timestamp: LocalDateTime = defaultNow()): Conversation = transaction(db) {
        require(findById(id) != null) { "Conversation not found: $id" }
        Conversations.update({ Conversations.id eq id }) {
            it[Conversations.lastMessageAt] = timestamp
        }
        findById(id)!!
    }

    private fun rowToModel(row: ResultRow): Conversation = Conversation(
        id = row[Conversations.id],
        userId = row[Conversations.userId],
        personaId = row[Conversations.personaId],
        state = row[Conversations.state],
        lastMessageAt = row[Conversations.lastMessageAt],
        createdAt = row[Conversations.createdAt],
    )
}
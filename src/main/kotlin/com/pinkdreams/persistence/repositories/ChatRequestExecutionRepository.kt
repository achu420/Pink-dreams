package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.ChatRequestExecutions
import com.pinkdreams.persistence.database.Conversations
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class ChatRequestExecutionRepository(private val db: Database) {
    data class ChatRequestExecution(
        val conversationId: UUID,
        val clientMessageId: UUID,
        val requestId: UUID,
        val status: String,
        val assistantMessageId: UUID?,
        val errorCode: String?,
        val createdAt: LocalDateTime,
        val completedAt: LocalDateTime?,
    )

    data class ClaimResult(val winner: Boolean, val execution: ChatRequestExecution)

    fun claim(
        conversationId: UUID,
        clientMessageId: UUID,
        requestId: UUID,
    ): ClaimResult = transaction(db) {
        require(findConversation(conversationId) != null) { "Conversation not found: $conversationId" }

        val inserted = ChatRequestExecutions.insertIgnore {
            it[ChatRequestExecutions.conversationId] = conversationId
            it[ChatRequestExecutions.clientMessageId] = clientMessageId
            it[ChatRequestExecutions.requestId] = requestId
            it[ChatRequestExecutions.status] = "processing"
            it[ChatRequestExecutions.createdAt] = defaultNow()
        }
        if (inserted.insertedCount > 0) {
            return@transaction ClaimResult(true, find(conversationId, clientMessageId)!!)
        }

        val existing = find(conversationId, clientMessageId)!!
        if (existing.status == "failed") {
            val retried = ChatRequestExecutions.update({
                (ChatRequestExecutions.conversationId eq conversationId) and
                    (ChatRequestExecutions.clientMessageId eq clientMessageId) and
                    (ChatRequestExecutions.status eq "failed")
            }) {
                it[ChatRequestExecutions.requestId] = requestId
                it[ChatRequestExecutions.status] = "processing"
                it[ChatRequestExecutions.assistantMessageId] = null
                it[ChatRequestExecutions.errorCode] = null
                it[ChatRequestExecutions.completedAt] = null
            }
            if (retried == 1) {
                return@transaction ClaimResult(true, find(conversationId, clientMessageId)!!)
            }
        }
        ClaimResult(false, find(conversationId, clientMessageId)!!)
    }

    fun find(conversationId: UUID, clientMessageId: UUID): ChatRequestExecution? = transaction(db) {
        ChatRequestExecutions.select {
            (ChatRequestExecutions.conversationId eq conversationId) and
                (ChatRequestExecutions.clientMessageId eq clientMessageId)
        }.map(::rowToModel).singleOrNull()
    }

    fun complete(
        conversationId: UUID,
        clientMessageId: UUID,
        assistantMessageId: UUID,
        completedAt: LocalDateTime = defaultNow(),
    ): ChatRequestExecution = transaction(db) {
        val execution = find(conversationId, clientMessageId) ?: throw IllegalArgumentException("Execution not found")
        require(execution.status == "processing") { "Only processing executions can be completed" }
        ChatRequestExecutions.update({
            (ChatRequestExecutions.conversationId eq conversationId) and
                (ChatRequestExecutions.clientMessageId eq clientMessageId)
        }) {
            it[ChatRequestExecutions.status] = "completed"
            it[ChatRequestExecutions.assistantMessageId] = assistantMessageId
            it[ChatRequestExecutions.completedAt] = completedAt
            it[ChatRequestExecutions.errorCode] = null
        }
        find(conversationId, clientMessageId)!!
    }

    fun fail(
        conversationId: UUID,
        clientMessageId: UUID,
        errorCode: String,
        completedAt: LocalDateTime = defaultNow(),
    ): ChatRequestExecution = transaction(db) {
        val execution = find(conversationId, clientMessageId) ?: throw IllegalArgumentException("Execution not found")
        require(execution.status == "processing") { "Only processing executions can fail" }
        ChatRequestExecutions.update({
            (ChatRequestExecutions.conversationId eq conversationId) and
                (ChatRequestExecutions.clientMessageId eq clientMessageId)
        }) {
            it[ChatRequestExecutions.status] = "failed"
            it[ChatRequestExecutions.errorCode] = errorCode
            it[ChatRequestExecutions.completedAt] = completedAt
            it[ChatRequestExecutions.assistantMessageId] = null
        }
        find(conversationId, clientMessageId)!!
    }

    fun count(): Long = transaction(db) { ChatRequestExecutions.selectAll().count() }

    private fun findConversation(id: UUID) = Conversations.select { Conversations.id eq id }.singleOrNull()

    private fun rowToModel(row: ResultRow): ChatRequestExecution = ChatRequestExecution(
        conversationId = row[ChatRequestExecutions.conversationId],
        clientMessageId = row[ChatRequestExecutions.clientMessageId],
        requestId = row[ChatRequestExecutions.requestId],
        status = row[ChatRequestExecutions.status],
        assistantMessageId = row[ChatRequestExecutions.assistantMessageId],
        errorCode = row[ChatRequestExecutions.errorCode],
        createdAt = row[ChatRequestExecutions.createdAt],
        completedAt = row[ChatRequestExecutions.completedAt],
    )
}
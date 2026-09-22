package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Conversations
import com.pinkdreams.persistence.database.Messages
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import java.util.UUID

open class MessageRepository(private val db: Database) {
    data class Message(
        val id: UUID,
        val conversationId: UUID,
        val role: String,
        val content: String,
        val engineVersionId: UUID?,
        val personaCoreVersionId: UUID?,
        val clientMessageId: UUID?,
        val requestId: UUID?,
        val metadata: String,
        val createdAt: LocalDateTime,
    )

    fun createUserMessage(
        conversationId: UUID,
        content: String,
        clientMessageId: UUID?,
        requestId: UUID?,
        metadata: String = "{}",
        createdAt: LocalDateTime = defaultNow(),
    ): Message = create(
        conversationId = conversationId,
        role = "user",
        content = content,
        clientMessageId = clientMessageId,
        requestId = requestId,
        metadata = metadata,
        createdAt = createdAt,
    )

    fun createAssistantMessage(
        conversationId: UUID,
        content: String,
        engineVersionId: UUID,
        personaCoreVersionId: UUID,
        requestId: UUID?,
        metadata: String = "{}",
        createdAt: LocalDateTime = defaultNow(),
    ): Message = create(
        conversationId = conversationId,
        role = "assistant",
        content = content,
        engineVersionId = engineVersionId,
        personaCoreVersionId = personaCoreVersionId,
        requestId = requestId,
        metadata = metadata,
        createdAt = createdAt,
    )

    fun createSystemMessage(
        conversationId: UUID,
        content: String,
        requestId: UUID? = null,
        metadata: String = "{}",
        createdAt: LocalDateTime = defaultNow(),
    ): Message = create(
        conversationId = conversationId,
        role = "system",
        content = content,
        requestId = requestId,
        metadata = metadata,
        createdAt = createdAt,
    )

    fun findById(id: UUID): Message? = transaction(db) {
        Messages.select { Messages.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findForConversation(conversationId: UUID): List<Message> = transaction(db) {
        Messages.select { Messages.conversationId eq conversationId }
            .orderBy(Messages.createdAt to SortOrder.ASC, Messages.id to SortOrder.ASC)
            .map(::rowToModel)
    }

    open fun findForConversationPaginated(conversationId: UUID, limit: Int = 20, offset: Int = 0): List<Message> = transaction(db) {
        Messages.select { Messages.conversationId eq conversationId }
            .orderBy(Messages.createdAt to SortOrder.ASC, Messages.id to SortOrder.ASC)
            .limit(limit, offset.toLong())
            .map(::rowToModel)
    }

    private fun create(
        conversationId: UUID,
        role: String,
        content: String,
        engineVersionId: UUID? = null,
        personaCoreVersionId: UUID? = null,
        clientMessageId: UUID? = null,
        requestId: UUID? = null,
        metadata: String = "{}",
        createdAt: LocalDateTime,
    ): Message = transaction(db) {
        require(role in setOf("user", "assistant", "system")) { "Invalid message role: $role" }
        require(findConversation(conversationId) != null) { "Conversation not found: $conversationId" }
        if (role == "assistant") {
            require(engineVersionId != null && personaCoreVersionId != null) {
                "Assistant messages require engine and persona core version IDs"
            }
            require(clientMessageId == null) { "Assistant messages cannot have a client message ID" }
        } else {
            require(engineVersionId == null && personaCoreVersionId == null) {
                "Only assistant messages can have provenance version IDs"
            }
            if (role == "system") {
                require(clientMessageId == null) { "System messages cannot have a client message ID" }
            }
        }

        val id = UUID.randomUUID()
        Messages.insert {
            it[Messages.id] = id
            it[Messages.conversationId] = conversationId
            it[Messages.role] = role
            it[Messages.content] = content
            it[Messages.engineVersionId] = engineVersionId
            it[Messages.personaCoreVersionId] = personaCoreVersionId
            it[Messages.clientMessageId] = clientMessageId
            it[Messages.requestId] = requestId
            it[Messages.metadata] = metadata
            it[Messages.createdAt] = createdAt
        }
        findById(id)!!
    }

    private fun findConversation(id: UUID) = Conversations.select { Conversations.id eq id }.singleOrNull()

    fun mergeMetadata(messageId: UUID, additions: Map<String, String>): String = transaction(db) {
        val existing = findById(messageId) ?: throw IllegalArgumentException("Message not found: $messageId")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val base = try {
            json.parseToJsonElement(existing.metadata).jsonObject.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
        additions.forEach { (k, v) ->
            base[k] = kotlinx.serialization.json.JsonPrimitive(v)
        }
        val merged = kotlinx.serialization.json.JsonObject(base).toString()
        Messages.update({ Messages.id eq messageId }) {
            it[Messages.metadata] = merged
        }
        merged
    }

    private fun rowToModel(row: ResultRow): Message = Message(
        id = row[Messages.id],
        conversationId = row[Messages.conversationId],
        role = row[Messages.role],
        content = row[Messages.content],
        engineVersionId = row[Messages.engineVersionId],
        personaCoreVersionId = row[Messages.personaCoreVersionId],
        clientMessageId = row[Messages.clientMessageId],
        requestId = row[Messages.requestId],
        metadata = row[Messages.metadata],
        createdAt = row[Messages.createdAt],
    )
}
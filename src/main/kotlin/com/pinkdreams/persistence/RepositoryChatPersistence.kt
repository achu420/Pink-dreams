package com.pinkdreams.persistence

import com.pinkdreams.chat.ChatPersistence
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.GenerationResponse
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.StageResult
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.persistence.database.ChatRequestExecutions
import com.pinkdreams.persistence.database.Conversations
import com.pinkdreams.persistence.database.Messages
import com.pinkdreams.persistence.database.defaultNow
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class RepositoryChatPersistence(
    private val db: Database,
    private val executions: ChatRequestExecutionRepository,
) : ChatPersistence {
    override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
        val engineVersionId = response.engineVersionId
        val personaCoreVersionId = response.personaCoreVersionId
        if (engineVersionId == null || personaCoreVersionId == null) {
            executions.fail(request.conversationId, request.clientMessageId, ErrorCode.PERSIST_FAILED.name)
            return StageResult.Failed(ErrorCode.PERSIST_FAILED)
        }

        return try {
            transaction(db) {
                val conversation = Conversations.select {
                    (Conversations.id eq request.conversationId) and
                        (Conversations.userId eq request.userId) and
                        (Conversations.personaId eq request.personaId)
                }.singleOrNull() ?: error("Conversation ownership or persona mismatch")

                val execution = ChatRequestExecutions.select {
                    (ChatRequestExecutions.conversationId eq request.conversationId) and
                        (ChatRequestExecutions.clientMessageId eq request.clientMessageId)
                }.singleOrNull() ?: error("Execution not found")
                require(execution[ChatRequestExecutions.status] == "processing") { "Execution is not processing" }

                val timestamp = defaultNow()
                val userMessageId = UUID.randomUUID()
                Messages.insert {
                    it[Messages.id] = userMessageId
                    it[Messages.conversationId] = request.conversationId
                    it[Messages.role] = "user"
                    it[Messages.content] = request.content
                    it[Messages.clientMessageId] = request.clientMessageId
                    it[Messages.requestId] = request.requestId
                    it[Messages.metadata] = "{}"
                    it[Messages.createdAt] = timestamp
                }

                val assistantMessageId = UUID.randomUUID()
                Messages.insert {
                    it[Messages.id] = assistantMessageId
                    it[Messages.conversationId] = request.conversationId
                    it[Messages.role] = "assistant"
                    it[Messages.content] = response.content
                    it[Messages.engineVersionId] = engineVersionId
                    it[Messages.personaCoreVersionId] = personaCoreVersionId
                    it[Messages.requestId] = request.requestId
                    it[Messages.metadata] = "{}"
                    it[Messages.createdAt] = timestamp
                    // Note: clientMessageId must NOT be set for assistant messages per schema constraint
                }

                Conversations.update({ Conversations.id eq request.conversationId }) {
                    it[Conversations.lastMessageAt] = timestamp
                }
                ChatRequestExecutions.update({
                    (ChatRequestExecutions.conversationId eq request.conversationId) and
                        (ChatRequestExecutions.clientMessageId eq request.clientMessageId)
                }) {
                    it[ChatRequestExecutions.status] = "completed"
                    it[ChatRequestExecutions.assistantMessageId] = assistantMessageId
                    it[ChatRequestExecutions.completedAt] = timestamp
                    it[ChatRequestExecutions.errorCode] = null
                }
                PersistedResponse(assistantMessageId, response.content)
            }.let { StageResult.Succeeded(it) }
        } catch (e: Exception) {
            // Print the real cause to the server console — PERSIST_FAILED alone gives no diagnostic signal.
            System.err.println("RepositoryChatPersistence.persist failed for conversation=${request.conversationId} clientMessageId=${request.clientMessageId}: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            try {
                executions.fail(request.conversationId, request.clientMessageId, ErrorCode.PERSIST_FAILED.name)
            } catch (_: Exception) {
                // Keep the original persistence failure as the pipeline result.
            }
            StageResult.Failed(ErrorCode.PERSIST_FAILED)
        }
    }
}
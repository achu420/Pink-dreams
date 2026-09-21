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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    private fun buildMessageMetadata(response: GenerationResponse): String {
        return buildJsonObject {
            if (response.executionDiagnostics != null) {
                val diag = response.executionDiagnostics
                put("lvm_context_blocks", buildJsonObject {
                    diag.contextBlocks?.forEach { block ->
                        put("${block.role}_${System.identityHashCode(block)}", block.content.take(200))
                    }
                }.toString())
                put("lvm_config", buildJsonObject {
                    diag.generationConfig?.forEach { (k, v) -> put(k, v) }
                }.toString())
                put("lvm_response_metadata", buildJsonObject {
                    diag.llmResponseMetadata?.forEach { (k, v) -> put(k, v) }
                }.toString())
                put("lvm_provider_exchange", buildJsonObject {
                    diag.providerExchange?.forEach { (k, v) -> put(k, v) }
                }.toString())
                put("lvm_stage_timings", buildJsonObject {
                    diag.stageTimingsMs?.forEach { (k, v) -> put(k, v) }
                }.toString())
            }
        }.toString()
    }

    override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
        val engineVersionId = response.engineVersionId
        val personaCoreVersionId = response.personaCoreVersionId
        if (engineVersionId == null || personaCoreVersionId == null) {
            executions.fail(request.conversationId, request.clientMessageId, ErrorCode.PERSIST_FAILED.name)
            return StageResult.Failed(ErrorCode.PERSIST_FAILED)
        }

        return try {
            transaction(db) {
                try {
                    val conversation = Conversations.select {
                        (Conversations.id eq request.conversationId) and
                            (Conversations.userId eq request.userId) and
                            (Conversations.personaId eq request.personaId)
                    }.singleOrNull() ?: error("Conversation ownership or persona mismatch")
                } catch (e: Exception) {
                    System.err.println("PERSIST: Conversation lookup failed: ${e.message}")
                    throw e
                }

                try {
                    val execution = ChatRequestExecutions.select {
                        (ChatRequestExecutions.conversationId eq request.conversationId) and
                            (ChatRequestExecutions.clientMessageId eq request.clientMessageId)
                    }.singleOrNull() ?: error("Execution not found")
                    require(execution[ChatRequestExecutions.status] == "processing") { "Execution is not processing" }
                } catch (e: Exception) {
                    System.err.println("PERSIST: Execution lookup failed: ${e.message}")
                    throw e
                }

                // The user message must sort strictly before its assistant reply when
                // retrieved later (findForConversation orders by createdAt then id, and
                // id is a random UUID — sharing one timestamp between the two rows would
                // make their relative order a coin flip on the UUID tiebreak). Giving the
                // assistant row a timestamp a moment later guarantees deterministic,
                // causally-correct ordering regardless of UUID value.
                val userTimestamp = defaultNow()
                val assistantTimestamp = userTimestamp.plusNanos(1000)
                val userMessageId = UUID.randomUUID()
                try {
                    Messages.insert {
                        it[Messages.id] = userMessageId
                        it[Messages.conversationId] = request.conversationId
                        it[Messages.role] = "user"
                        it[Messages.content] = request.content
                        it[Messages.clientMessageId] = request.clientMessageId
                        it[Messages.requestId] = request.requestId
                        it[Messages.metadata] = "{}"
                        it[Messages.createdAt] = userTimestamp
                    }
                } catch (e: Exception) {
                    System.err.println("PERSIST: User message insert failed: ${e.message}")
                    throw e
                }

                val assistantMessageId = UUID.randomUUID()
                try {
                    val metadataJson = buildMessageMetadata(response)
                    Messages.insert {
                        it[Messages.id] = assistantMessageId
                        it[Messages.conversationId] = request.conversationId
                        it[Messages.role] = "assistant"
                        it[Messages.content] = response.content
                        it[Messages.engineVersionId] = engineVersionId
                        it[Messages.personaCoreVersionId] = personaCoreVersionId
                        it[Messages.requestId] = request.requestId
                        it[Messages.metadata] = metadataJson
                        it[Messages.createdAt] = assistantTimestamp
                        // Note: clientMessageId must NOT be set for assistant messages per schema constraint
                    }
                } catch (e: Exception) {
                    System.err.println("PERSIST: Assistant message insert failed: ${e.message}")
                    throw e
                }

                try {
                    Conversations.update({ Conversations.id eq request.conversationId }) {
                        it[Conversations.lastMessageAt] = assistantTimestamp
                    }
                } catch (e: Exception) {
                    System.err.println("PERSIST: Conversation update failed: ${e.message}")
                    throw e
                }

                try {
                    ChatRequestExecutions.update({
                        (ChatRequestExecutions.conversationId eq request.conversationId) and
                            (ChatRequestExecutions.clientMessageId eq request.clientMessageId)
                    }) {
                        it[ChatRequestExecutions.status] = "completed"
                        it[ChatRequestExecutions.assistantMessageId] = assistantMessageId
                        it[ChatRequestExecutions.completedAt] = assistantTimestamp
                        it[ChatRequestExecutions.errorCode] = null
                    }
                } catch (e: Exception) {
                    System.err.println("PERSIST: ChatRequestExecution update failed: ${e.message}")
                    throw e
                }
                PersistedResponse(assistantMessageId, response.content)
            }.let { StageResult.Succeeded(it) }
        } catch (e: Exception) {
            // Print the real cause to the server console — PERSIST_FAILED alone gives no diagnostic signal.
            System.err.println("RepositoryChatPersistence.persist FINAL FAILURE for conversation=${request.conversationId} clientMessageId=${request.clientMessageId}: ${e.javaClass.simpleName}: ${e.message}")
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
package com.pinkdreams.persistence

import com.pinkdreams.chat.ChatExecutionCoordinator
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ExecutionClaim
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.StageResult
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository

class RepositoryChatExecutionCoordinator(
    private val executions: ChatRequestExecutionRepository,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
) : ChatExecutionCoordinator {
    override fun claim(request: ChatRequest): StageResult<ExecutionClaim> {
        return try {
            if (conversations.findByIdForUser(request.conversationId, request.userId) == null) {
                StageResult.Failed(ErrorCode.PERSIST_FAILED)
            } else {
                val result = executions.claim(request.conversationId, request.clientMessageId, request.requestId)
                if (result.winner) {
                    StageResult.Succeeded(ExecutionClaim.Winner)
                } else {
                    when (result.execution.status) {
                        "completed" -> {
                            val assistantId = result.execution.assistantMessageId
                            val message = assistantId?.let(messages::findById)
                            if (message == null) {
                                StageResult.Failed(ErrorCode.PERSIST_FAILED)
                            } else {
                                StageResult.Succeeded(ExecutionClaim.Completed(PersistedResponse(message.id, message.content)))
                            }
                        }
                        "processing" -> StageResult.Succeeded(ExecutionClaim.Processing)
                        else -> StageResult.Failed(ErrorCode.PERSIST_FAILED)
                    }
                }
            }
        } catch (_: Exception) {
            StageResult.Failed(ErrorCode.PERSIST_FAILED)
        }
    }

    override fun fail(request: ChatRequest, code: ErrorCode) {
        try {
            executions.fail(request.conversationId, request.clientMessageId, code.name)
        } catch (_: Exception) {
            // Preserve the original pipeline failure; the persistence layer remains authoritative.
        }
    }
}
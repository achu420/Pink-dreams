package com.pinkdreams.chat.context

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextAssembler
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository

class RepositoryContextAssembler(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val userProfileRepository: UserProfileRepository,
    private val memoryService: MemoryService,
    private val engineRepository: ConversationEngineRepository,
    private val personaRepository: PersonaRepository,
    private val memoryLimit: Int = MemoryService.DEFAULT_SELECTION_LIMIT,
    private val messageLimit: Int = DEFAULT_MESSAGE_LIMIT,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    private val tokenEstimator: TokenEstimator = CharacterTokenEstimator,
) : ContextAssembler {
    override fun assemble(request: ChatRequest): StageResult<ChatContext> {
        val conversation = conversationRepository.findByIdForUser(request.conversationId, request.userId)
            ?: return StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.PERSIST_FAILED)
        require(conversation.personaId == request.personaId) { "Conversation persona mismatch" }

        val engine = engineRepository.getActiveEngine()
            ?: return StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.PERSIST_FAILED)
        val core = personaRepository.getActiveCoreVersion(request.personaId)
            ?: return StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.PERSIST_FAILED)
        val profile = userProfileRepository.findByUserId(request.userId)
        val memories = memoryService.selectForContext(request.userId, request.personaId, memoryLimit)
        val messages = messageRepository.findForConversation(request.conversationId).takeLast(messageLimit)

        val block0 = ContextBlock("system", engine.content + "\n\n" + core.content)
        val block1 = ContextBlock("system", profileContent(profile))
        val block2Facts = memories.toMutableList()
        val block3Messages = messages.toMutableList()

        while (totalTokens(
                block0,
                block1,
                ContextBlock("system", block2Content(block2Facts, conversation.lastMessageAt)),
                ContextBlock("system", block3Content(block3Messages)),
            ) > tokenBudget
        ) {
            if (block2Facts.isNotEmpty()) {
                block2Facts.removeAt(block2Facts.lastIndex)
            } else if (block3Messages.isNotEmpty()) {
                block3Messages.removeAt(0)
            } else {
                break
            }
        }

        val block2 = ContextBlock("system", block2Content(block2Facts, conversation.lastMessageAt))
        val block3 = ContextBlock("system", block3Content(block3Messages))
        return StageResult.Succeeded(
            ChatContext(
                blocks = listOf(block0, block1, block2, block3),
                engineVersionId = engine.id,
                personaCoreVersionId = core.id,
            ),
        )
    }

    private fun profileContent(profile: UserProfileRepository.UserProfile?): String = buildString {
        append("display_name: ").append(profile?.displayName ?: "")
        append("\npreferred_language: ").append(profile?.preferredLanguage ?: "")
        append("\ncommunication_style: ").append(profile?.communicationStyle ?: "")
    }

    private fun block2Content(
        facts: List<com.pinkdreams.persistence.repositories.MemoryFactRepository.MemoryFact>,
        lastMessageAt: java.time.LocalDateTime?,
    ): String = buildString {
        facts.forEach { append(it.factType).append(": ").append(it.fact).append('\n') }
        if (lastMessageAt != null) append("continuity: Last message at ").append(lastMessageAt)
    }

    private fun block3Content(messages: List<MessageRepository.Message>): String =
        messages.joinToString("\n") { "${it.role}: ${it.content}" }

    private fun totalTokens(vararg blocks: ContextBlock): Int = blocks.sumOf { tokenEstimator.estimate(it.content) }

    companion object {
        const val DEFAULT_MESSAGE_LIMIT = 20
        const val DEFAULT_TOKEN_BUDGET = 8192
    }
}

fun interface TokenEstimator {
    fun estimate(content: String): Int
}

object CharacterTokenEstimator : TokenEstimator {
    override fun estimate(content: String): Int = (content.length + 3) / 4
}
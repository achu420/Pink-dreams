package com.pinkdreams.testchat

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextAssembler
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.context.PersonaContextProvider
import com.pinkdreams.chat.memory.MemoryScopeResolver
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.ProductionMemoryScope
import com.pinkdreams.chat.sensitive.SensitivePreferenceService
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SensitivePreferenceRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository

/**
 * Test-only context assembler that knows the skill upfront (from test scenario).
 * Allows fair comparison of full-persona (A) vs selective-persona (B).
 * Production chat remains completely unchanged.
 */
class TestChatContextAssembler(
    conversationRepository: ConversationRepository,
    messageRepository: MessageRepository,
    userProfileRepository: UserProfileRepository,
    memoryService: MemoryService,
    engineRepository: ConversationEngineRepository,
    personaRepository: PersonaRepository,
    memoryLimit: Int = MemoryService.DEFAULT_SELECTION_LIMIT,
    messageLimit: Int = 10,
    tokenBudget: Int = 12000,
    tokenEstimator: com.pinkdreams.chat.context.TokenEstimator = com.pinkdreams.chat.context.CharacterTokenEstimator,
    sensitivePreferenceService: SensitivePreferenceService? = null,
    memoryScopeResolver: MemoryScopeResolver = ProductionMemoryScope,
    personaContextProvider: PersonaContextProvider? = null,
    // TEST-ONLY: The skill is known upfront in test scenarios
    private val skillKey: String = "general_chat",
) : ContextAssembler {

    private val delegate = com.pinkdreams.chat.context.RepositoryContextAssembler(
        conversationRepository,
        messageRepository,
        userProfileRepository,
        memoryService,
        engineRepository,
        personaRepository,
        memoryLimit,
        messageLimit,
        tokenBudget,
        tokenEstimator,
        sensitivePreferenceService,
        memoryScopeResolver,
        personaContextProvider,
    )

    override fun assemble(request: ChatRequest): StageResult<ChatContext> {
        // Delegate to standard assembler, which will use the skill-aware persona if provider is set
        val result = delegate.assemble(request)

        // Mark context as test variant (allows downstream instrumentation)
        return when (result) {
            is StageResult.Succeeded -> {
                StageResult.Succeeded(result.value.copy(selectedSkillKey = skillKey))
            }
            is StageResult.Failed -> result
        }
    }
}

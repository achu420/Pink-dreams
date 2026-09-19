package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FullContextPipelineRegressionTest {
    @Test
    fun `full context pipeline includes current message history persona profile memory and engine config`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        // Setup: Create persona with distinctive core content
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("test-persona-${UUID.randomUUID()}", "TestPersona", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "PERSONA_CORE_TEST_123", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        // Setup: Create conversation engine with distinctive content
        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "CONVERSATION_ENGINE_TEST_456", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        // Setup: Create user profile with populated fields
        val profileRepository = UserProfileRepository(db)
        profileRepository.create(userId, "PROFILE_TEST_USER_789", "en_US", "PROFILE_TEST_STYLE_999")

        // Setup: Create conversation
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        // Setup: Add historical message
        val messageRepository = MessageRepository(db)
        messageRepository.createUserMessage(conversation.id, "HISTORY_TEST_111", UUID.randomUUID(), UUID.randomUUID())

        // Setup: Add memory fact
        val memoryRepository = MemoryFactRepository(db)
        memoryRepository.create(userId, persona.id, "MEMORY_TEST_789", "interest", "high")

        // Setup: Create ChatRequest with distinctive current message
        val currentMessage = "CURRENT_MESSAGE_TEST_222"
        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = currentMessage,
        )

        // Execute: Run context assembly
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            profileRepository,
            MemoryService(memoryRepository),
            engineRepository,
            personaRepository,
        )
        val contextResult = contextAssembler.assemble(chatRequest)
        val chatContext = assertIs<StageResult.Succeeded<ChatContext>>(contextResult).value

        // Verify: All blocks are present (3 system blocks + 1 native history message + 1 current message)
        assertEquals(5, chatContext.blocks.size, "Should have 5 blocks: engine+core, profile, memory, history, current")

        val block0 = chatContext.blocks[0]
        val block1 = chatContext.blocks[1]
        val block2 = chatContext.blocks[2]
        val block3 = chatContext.blocks[3]
        val block4 = chatContext.blocks[4]

        // Verify: Block 0 contains engine and persona core
        assertTrue(block0.content.contains("CONVERSATION_ENGINE_TEST_456"), "Engine content should be in block 0")
        assertTrue(block0.content.contains("PERSONA_CORE_TEST_123"), "Persona core should be in block 0")

        // Verify: Block 1 contains user profile
        assertTrue(block1.content.contains("PROFILE_TEST_USER_789"), "User profile display name should be in block 1")
        assertTrue(block1.content.contains("PROFILE_TEST_STYLE_999"), "User communication style should be in block 1")

        // Verify: Block 2 contains memory
        assertTrue(block2.content.contains("MEMORY_TEST_789"), "Memory fact should be in block 2")

        // Verify: Block 3 is a NATIVE user-role history message, not a flattened system transcript
        assertEquals("user", block3.role, "Persisted history must remain a native user-role message")
        assertEquals("HISTORY_TEST_111", block3.content, "Historical message content must be exact")

        // Verify: Block 4 is the current message with "user" role
        assertEquals("user", block4.role, "Final block should be user role")
        assertEquals("CURRENT_MESSAGE_TEST_222", block4.content, "Final block content must be exact current message")

        // Execute: Pass through LlmGenerator to verify actual LLM request includes current message
        val mockLlmResponse = LlmResponse(
            content = "test response",
            provider = "test",
            model = "test-model",
            metadata = mapOf(
                "prompt_tokens" to "100",
                "completion_tokens" to "50",
                "total_tokens" to "150",
            ),
        )
        val fakeLlmClient = FakeLlmClient(response = mockLlmResponse)
        val generator = LlmGenerator(fakeLlmClient, GenerationConfig(model = "test-model"))

        val generationResult = generator.generate(chatRequest, chatContext)
        val generationResponse = assertIs<StageResult.Succeeded<GenerationResponse>>(generationResult).value

        // Verify: Execution diagnostics captured
        val diagnostics = generationResponse.executionDiagnostics
        assertNotNull(diagnostics, "Execution diagnostics should be captured")

        // Verify: Captured context blocks include current message
        val capturedBlocks = diagnostics.contextBlocks
        assertNotNull(capturedBlocks, "Context blocks should be captured")
        val lastBlock = capturedBlocks.last()
        assertEquals("user", lastBlock.role, "Last block should be user role")
        assertTrue(lastBlock.content.contains("CURRENT_MESSAGE_TEST_222"), "Captured context must include current message")

        // Verify: Actual LlmClient received all context
        val lastRequest = fakeLlmClient.lastRequest
        assertNotNull(lastRequest, "LlmClient should have received request")
        val messages = lastRequest.context.blocks
        assertEquals(5, messages.size, "All 5 blocks should reach LlmClient")

        // Final comprehensive check: all distinctive test values present in final request
        val allContent = messages.joinToString("\n") { it.content }
        assertTrue(allContent.contains("CONVERSATION_ENGINE_TEST_456"), "Engine content missing from final LLM request")
        assertTrue(allContent.contains("PERSONA_CORE_TEST_123"), "Persona core missing from final LLM request")
        assertTrue(allContent.contains("PROFILE_TEST_USER_789"), "User profile missing from final LLM request")
        assertTrue(allContent.contains("MEMORY_TEST_789"), "Memory missing from final LLM request")
        assertTrue(allContent.contains("HISTORY_TEST_111"), "History missing from final LLM request")
        assertTrue(allContent.contains("CURRENT_MESSAGE_TEST_222"), "Current message missing from final LLM request")
    }

    @Test
    fun `current message substitutes correctly over old historical messages`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        // Setup minimal persona and engine
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("test-persona", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core content", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine rules", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val messageRepository = MessageRepository(db)
        val historyBase = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
        messageRepository.createUserMessage(conversation.id, "FIRST_MESSAGE_OLD", UUID.randomUUID(), UUID.randomUUID(), createdAt = historyBase)
        messageRepository.createUserMessage(conversation.id, "SECOND_MESSAGE_OLD", UUID.randomUUID(), UUID.randomUUID(), createdAt = historyBase.plusMinutes(1))

        // Current request with new message content (not the old messages)
        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = "NEW_MESSAGE_CURRENT",
        )

        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            MemoryService(MemoryFactRepository(db)),
            engineRepository,
            personaRepository,
        )

        val contextResult = contextAssembler.assemble(chatRequest)
        val chatContext = assertIs<StageResult.Succeeded<ChatContext>>(contextResult).value

        // Verify: Old messages are NATIVE user-role history blocks (not a flattened system transcript)
        assertEquals(6, chatContext.blocks.size, "3 system blocks + 2 native history messages + 1 current message")
        val firstHistory = chatContext.blocks[3]
        val secondHistory = chatContext.blocks[4]
        assertEquals("FIRST_MESSAGE_OLD", firstHistory.content, "First message should be in history")
        assertEquals("user", firstHistory.role)
        assertEquals("SECOND_MESSAGE_OLD", secondHistory.content, "Second message should be in history")
        assertEquals("user", secondHistory.role)

        // Verify: New message is the CURRENT message (last block)
        val lastBlock = chatContext.blocks.last()
        assertEquals("user", lastBlock.role)
        assertEquals("NEW_MESSAGE_CURRENT", lastBlock.content)
    }
}

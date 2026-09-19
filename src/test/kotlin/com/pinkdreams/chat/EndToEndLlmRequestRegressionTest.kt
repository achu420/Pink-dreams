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

class EndToEndLlmRequestRegressionTest {
    @Test
    fun `verify actual llm request contains current message with user role and all context`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        // Setup: Create persona with distinctive core content
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("test-persona", "TestPersona", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "PERSONA_CORE_TEST_456", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        // Setup: Create engine with distinctive content
        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "ENGINE_TEST_123", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        // Setup: Create user profile
        val profileRepository = UserProfileRepository(db)
        profileRepository.create(userId, "PROFILE_TEST_789", "en_US", "PROFILE_TEST_STYLE_999")

        // Setup: Create conversation with history
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val messageRepository = MessageRepository(db)
        messageRepository.createUserMessage(conversation.id, "HISTORY_TEST_222", UUID.randomUUID(), UUID.randomUUID())

        // Setup: Add memory
        val memoryRepository = MemoryFactRepository(db)
        memoryRepository.create(userId, persona.id, "MEMORY_TEST_111", "interest", "high")

        // Execute: Create chat request with distinctive current message
        val currentMessageContent = "CURRENT_MESSAGE_TEST_333"
        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = currentMessageContent,
        )

        // Setup: Create fake LLM client to capture actual request
        val mockResponse = LlmResponse(
            content = "test response",
            provider = "test",
            model = "test-model",
            metadata = mapOf("tokens" to "100"),
        )
        val fakeLlmClient = FakeLlmClient(response = mockResponse)

        // Execute: Run through the full pipeline
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

        val generator = LlmGenerator(fakeLlmClient, GenerationConfig(model = "test-model"))
        val generationResult = generator.generate(chatRequest, chatContext)
        val generationResponse = assertIs<StageResult.Succeeded<GenerationResponse>>(generationResult).value

        // CRITICAL ASSERTION: Check the actual request received by LLM client
        val actualRequest = fakeLlmClient.lastRequest
        assertNotNull(actualRequest, "LLM client should have received a request")

        val messages = actualRequest.context.blocks
        // 3 system blocks + 1 native history message + 1 current message.
        assertEquals(5, messages.size, "Should have 5 blocks")

        // Verify: Engine and core in first block
        assertTrue(messages[0].content.contains("ENGINE_TEST_123"), "Engine content missing")
        assertTrue(messages[0].content.contains("PERSONA_CORE_TEST_456"), "Persona core missing")
        assertEquals("system", messages[0].role, "Block 0 should be system")

        // Verify: Profile in second block
        assertTrue(messages[1].content.contains("PROFILE_TEST_789"), "Profile missing")
        assertEquals("system", messages[1].role, "Block 1 should be system")

        // Verify: Memory in third block
        assertTrue(messages[2].content.contains("MEMORY_TEST_111"), "Memory missing")
        assertEquals("system", messages[2].role, "Block 2 should be system")

        // Verify: History is a NATIVE user-role message, not folded into a system transcript
        assertEquals("HISTORY_TEST_222", messages[3].content, "History content must be exact persisted text")
        assertEquals("user", messages[3].role, "Persisted user history must remain a native user-role message")

        // CRITICAL ASSERTION: Fifth block MUST be current message with role "user"
        assertEquals("user", messages[4].role, "Current message MUST have role 'user', not 'system'")
        assertEquals("CURRENT_MESSAGE_TEST_333", messages[4].content, "Current message must be exact")
    }

    @Test
    fun `verify current message does not appear in history blocks`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        // Setup minimal persona and engine
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("test-persona", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val messageRepository = MessageRepository(db)
        val base = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
        messageRepository.createUserMessage(conversation.id, "FIRST_MESSAGE", UUID.randomUUID(), UUID.randomUUID(), createdAt = base)
        messageRepository.createUserMessage(conversation.id, "SECOND_MESSAGE", UUID.randomUUID(), UUID.randomUUID(), createdAt = base.plusMinutes(1))

        // Create fake LLM client
        val fakeLlmClient = FakeLlmClient(response = LlmResponse("response", "test", "model"))
        val generator = LlmGenerator(fakeLlmClient)

        // Execute with NEW current message
        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = "NEW_CURRENT_MESSAGE",
        )

        val assembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            MemoryService(MemoryFactRepository(db)),
            engineRepository,
            personaRepository,
        )

        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler.assemble(chatRequest)).value
        generator.generate(chatRequest, context)

        // Two persisted history messages must now be two NATIVE user-role blocks,
        // never a single flattened system transcript.
        val firstHistoryBlock = context.blocks[3]
        val secondHistoryBlock = context.blocks[4]
        assertEquals("user", firstHistoryBlock.role)
        assertEquals("FIRST_MESSAGE", firstHistoryBlock.content)
        assertEquals("user", secondHistoryBlock.role)
        assertEquals("SECOND_MESSAGE", secondHistoryBlock.content)

        assertTrue(
            context.blocks.none { it.content == "NEW_CURRENT_MESSAGE" && it !== context.blocks.last() },
            "Current message MUST NOT appear among history blocks",
        )

        // CRITICAL: Check that current message is ONLY in final block
        val currentBlock = context.blocks[5]
        assertEquals("user", currentBlock.role, "Final block MUST have role 'user'")
        assertEquals("NEW_CURRENT_MESSAGE", currentBlock.content)
    }
}

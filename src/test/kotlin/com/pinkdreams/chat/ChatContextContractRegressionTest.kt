package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the full ChatContext contract end-to-end against the ACTUAL request
 * received by the fake LlmClient — not merely against the intermediate ChatContext.
 *
 * Contract sections asserted:
 * 1. Conversation Engine
 * 2. Persona Core
 * 3. User Profile
 * 4. Retrieved Memory
 * 5. Previous Conversation History — maximum 10 messages, oldest excluded first
 * 6. Current User Message — role=user, exact content, not part of history
 */
class ChatContextContractRegressionTest {
    @Test
    fun `full chat context contract reaches the actual llm client request with correct roles and history boundary`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()

        // --- Engine ---
        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "ENGINE_CONTRACT_TEST_123", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        // --- Persona core ---
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("contract-persona-${UUID.randomUUID()}", "ContractPersona", "female", "straight", 28, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "PERSONA_CONTRACT_TEST_456", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        // --- User profile ---
        val profileRepository = UserProfileRepository(db)
        profileRepository.create(userId, "PROFILE_CONTRACT_TEST_789", "en_US", "PROFILE_CONTRACT_STYLE")

        // --- Conversation ---
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        // --- Memory (via real MemoryService retrieval path, not a manually inserted block) ---
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        memoryService.record(
            userId,
            persona.id,
            listOf(com.pinkdreams.chat.memory.MemoryCandidate("MEMORY_CONTRACT_TEST_111", "interest", "high")),
        )

        // --- History: create 12 messages, oldest first, so only the last 10 should survive ---
        val messageRepository = MessageRepository(db)
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
        val historyLabels = (1..12).map { "HISTORY_%02d".format(it) }
        historyLabels.forEachIndexed { index, label ->
            messageRepository.createUserMessage(
                conversation.id,
                label,
                UUID.randomUUID(),
                UUID.randomUUID(),
                createdAt = base.plusMinutes(index.toLong()),
            )
        }

        // --- Current message ---
        val currentMessageContent = "CURRENT_MESSAGE_CONTRACT_TEST_999"
        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = currentMessageContent,
        )

        // --- Execute the real production context assembly + generation path ---
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            profileRepository,
            memoryService,
            engineRepository,
            personaRepository,
        )
        val context = assertIs<StageResult.Succeeded<ChatContext>>(contextAssembler.assemble(chatRequest)).value

        val fakeLlmClient = FakeLlmClient(
            response = LlmResponse(
                content = "assistant reply",
                provider = "test",
                model = "test-model",
                metadata = mapOf("total_tokens" to "10"),
            ),
        )
        val generator = LlmGenerator(fakeLlmClient, GenerationConfig(model = "test-model"))
        val generationResult = generator.generate(chatRequest, context)
        assertIs<StageResult.Succeeded<GenerationResponse>>(generationResult)

        // --- Inspect the ACTUAL request received by the fake LLM client ---
        val actualRequest = fakeLlmClient.lastRequest
        assertNotNull(actualRequest, "Fake LlmClient must have received a request")
        val blocks = actualRequest.context.blocks
        // 3 system sections + 10 native history messages (of 12 persisted; oldest 2 excluded) + 1 current message.
        assertEquals(14, blocks.size, "Contract requires 3 system blocks + 10 native history messages + 1 current message")

        val engineBlock = blocks[0]
        val profileBlock = blocks[1]
        val memoryBlock = blocks[2]
        val historyBlocks = blocks.subList(3, 13)
        val currentBlock = blocks[13]

        // 1. Engine
        assertEquals("system", engineBlock.role)
        assertTrue(engineBlock.content.contains("ENGINE_CONTRACT_TEST_123"), "Engine content missing from actual LLM request")

        // 2. Persona core
        assertTrue(engineBlock.content.contains("PERSONA_CONTRACT_TEST_456"), "Persona core content missing from actual LLM request")

        // 3. User profile
        assertEquals("system", profileBlock.role)
        assertTrue(profileBlock.content.contains("PROFILE_CONTRACT_TEST_789"), "Profile content missing from actual LLM request")

        // 4. Memory (retrieved through the real MemoryService path)
        assertEquals("system", memoryBlock.role)
        assertTrue(memoryBlock.content.contains("MEMORY_CONTRACT_TEST_111"), "Memory content missing from actual LLM request")

        // 5. History — exactly the last 10 of 12 messages, as NATIVE per-message blocks
        // (never a single flattened system transcript), oldest 2 excluded.
        val historyContents = historyBlocks.map { it.content }
        assertFalse(historyContents.contains("HISTORY_01"), "Oldest message beyond the 10-message window must be excluded")
        assertFalse(historyContents.contains("HISTORY_02"), "Second-oldest message beyond the 10-message window must be excluded")
        for (i in 3..12) {
            val label = "HISTORY_%02d".format(i)
            assertTrue(historyContents.contains(label), "Expected $label to be present in the last-10 history window")
        }
        historyBlocks.forEach { block ->
            assertEquals("user", block.role, "All persisted history in this test is user role and must remain native, not system")
        }
        // Chronological (oldest -> newest) order among the retained native blocks.
        for (i in 3..11) {
            assertTrue(
                historyContents.indexOf("HISTORY_%02d".format(i)) < historyContents.indexOf("HISTORY_%02d".format(i + 1)),
                "History messages must remain chronologically ordered oldest to newest",
            )
        }

        // 6. Current message — separate final role=user block, exact content, NOT among history blocks
        assertEquals("user", currentBlock.role, "Current message must have role 'user'")
        assertEquals(currentMessageContent, currentBlock.content, "Current message content must be exact, with no extra headers")
        assertFalse(historyContents.contains(currentMessageContent), "Current message must NOT appear as a history block")
    }
}

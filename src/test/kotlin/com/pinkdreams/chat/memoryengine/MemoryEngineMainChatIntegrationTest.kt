package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.CompositePostDeliveryHook
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end: real conversation turns through PipelineChatEngine, real
 * post-delivery Memory Engine maintenance (synchronous executor so the
 * assertions can run immediately after), verifying the canonical database is
 * actually updated and that the VERY NEXT turn's context assembly reflects
 * the new memory — proving the full loop, not just isolated units.
 */
class MemoryEngineMainChatIntegrationTest {

    private class RoutingFakeLlmClient(private val chatReplies: MutableList<String>) : LlmClient {
        override fun generate(request: GenerationRequest): LlmResponse {
            val firstBlock = request.context.blocks.firstOrNull()?.content ?: ""
            return if (firstBlock.contains("MEMORY_ENGINE_TEST_INSTRUCTIONS")) {
                LlmResponse(
                    content = """{"userMemoryChanges":[{"action":"ADD","memoryType":"interest","content":"MEMORY_ENGINE_EXTRACTED_FACT","criticality":"medium"}]}""",
                    provider = "test",
                )
            } else {
                LlmResponse(content = chatReplies.removeAt(0), provider = "test")
            }
        }
    }

    @Test
    fun `a full batch of messages triggers maintenance and the resulting memory reaches the next turn's context`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("memeng-int-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core content", "draft")
        personaRepository.activateCoreVersion(persona.id, coreRepository.publishCoreVersion(core.id).id)

        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "engine rules", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)

        val memoryEngineRepository = MemoryEngineRepository(db)
        memoryEngineRepository.activate(memoryEngineRepository.publish(memoryEngineRepository.createNextVersion("MEMORY_ENGINE_TEST_INSTRUCTIONS").id).id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val executions = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val llmClient = RoutingFakeLlmClient((1..10).map { "reply-$it" }.toMutableList())
        val generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model"))
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository, messageRepository, UserProfileRepository(db), memoryService, engineRepository, personaRepository,
        )

        val maintainer = LlmMemoryEngineMaintainer(llmClient, memoryEngineRepository)
        val applier = MemoryEngineChangeApplier(memoryFactRepository)
        val memoryEngineHook = BestEffortMemoryEngineMaintenance(
            maintainer, applier, memoryService, conversationRepository, messageRepository,
            batchSize = 10, executor = Executor { it.run() },
        )
        val postDelivery = CompositePostDeliveryHook(listOf(memoryEngineHook))

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            postDeliveryMemoryExtraction = postDelivery,
        )

        fun sendTurn(content: String): ChatResult {
            val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), content)
            return engine.process(request)
        }

        // 5 turns = 10 persisted messages (user + assistant each) = exactly one full batch.
        repeat(5) { i -> assertIs<ChatResult.Success>(sendTurn("turn $i")) }

        val canonical = memoryFactRepository.findForRelationship(userId, persona.id)
        assertEquals(1, canonical.size, "Memory Engine maintenance must have run exactly once for this one full batch")
        assertEquals("MEMORY_ENGINE_EXTRACTED_FACT", canonical.single().fact)
        assertEquals(10, conversationRepository.findById(conversation.id)!!.memoryEngineProcessedCount)

        // The NEXT turn's context assembly (a fresh call, unrelated to the hook)
        // must now see this memory via the pre-existing, unmodified
        // RepositoryContextAssembler/MemoryService.selectForContext path.
        val nextRequest = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "turn 6")
        val nextContext = assertIs<StageResult.Succeeded<ChatContext>>(contextAssembler.assemble(nextRequest)).value
        val memoryBlock = nextContext.blocks.first { it.content.startsWith("RETRIEVED MEMORY:") }
        assertTrue(memoryBlock.content.contains("MEMORY_ENGINE_EXTRACTED_FACT"), "The main chat context must reflect what the Memory Engine just persisted")
    }
}

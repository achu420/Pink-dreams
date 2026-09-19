package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
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
 * Full-pipeline integration tests for Tier 3 memory write/extraction — real
 * PipelineChatEngine, real MemoryService/MemoryFactRepository, real
 * RepositoryContextAssembler, real BestEffortMemoryExtraction. Only the
 * LlmClient is fake, since no real model call is available in tests.
 *
 * Uses a synchronous Executor so BestEffortMemoryExtraction's async dispatch
 * completes before assertions run, matching the pattern already established in
 * Phase5GEndToEndChatEngineTest / Phase5FMemoryExtractionTest.
 */
class MemoryExtractionPipelineIntegrationTest {

    /** Routes to a chat-reply response or a queued extraction-JSON response based on which prompt was sent. */
    private class RoutingFakeLlmClient(
        private val chatReplies: MutableList<String>,
        private val extractionResponses: MutableList<String>,
    ) : LlmClient {
        val extractionRequests = mutableListOf<GenerationRequest>()

        override fun generate(request: GenerationRequest): LlmResponse {
            val isExtraction = request.context.blocks.firstOrNull()?.content?.contains("memory-extraction system") == true
            return if (isExtraction) {
                extractionRequests += request
                LlmResponse(content = extractionResponses.removeAt(0), provider = "test", model = "test-model")
            } else {
                LlmResponse(content = chatReplies.removeAt(0), provider = "test", model = "test-model")
            }
        }
    }

    private class Fixture(
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val memoryFactRepository: MemoryFactRepository,
        val memoryService: MemoryService,
        val engine: PipelineChatEngine,
        val contextAssembler: RepositoryContextAssembler,
    )

    private fun fixture(
        chatReplies: List<String>,
        extractionResponses: List<String>,
        extractorOverride: MemoryExtractor? = null,
    ): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("memext-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core content", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "engine rules", "draft")
        val publishedEngine = engineRepository.publishEngine(engineRow.id)
        engineRepository.activateEngine(publishedEngine.id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val llmClient = RoutingFakeLlmClient(chatReplies.toMutableList(), extractionResponses.toMutableList())
        val generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model"))
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            memoryService,
            engineRepository,
            personaRepository,
        )

        val extractor = extractorOverride ?: LlmMemoryExtractor(llmClient)
        val postDeliveryMemoryExtraction = BestEffortMemoryExtraction(
            extractor = extractor,
            memoryService = memoryService,
            executor = Executor { it.run() }, // synchronous for deterministic assertions
        )

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            postDeliveryMemoryExtraction = postDeliveryMemoryExtraction,
        )

        return Fixture(userId, persona.id, conversation.id, memoryFactRepository, memoryService, engine, contextAssembler)
    }

    private fun sendTurn(fixture: Fixture, content: String): ChatResult {
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = fixture.userId,
            conversationId = fixture.conversationId,
            personaId = fixture.personaId,
            clientMessageId = UUID.randomUUID(),
            content = content,
        )
        return fixture.engine.process(request)
    }

    // --- E. Duplicate prevention (reuses MemoryService.record()'s existing dedup) ---
    @Test
    fun `scenario E existing memory prevents duplicate creation from a re-extracted equivalent fact`() {
        val fixture = fixture(
            chatReplies = listOf("Got it!"),
            extractionResponses = listOf("""{"facts": [{"fact": "user lives in Delhi", "factType": "interest", "criticality": "medium"}]}"""),
        )

        // Pre-existing memory, as if learned on a prior turn.
        fixture.memoryService.record(fixture.userId, fixture.personaId, listOf(MemoryCandidate("user lives in Delhi", "interest", "medium")))
        assertEquals(1, fixture.memoryFactRepository.findForRelationship(fixture.userId, fixture.personaId).size)

        assertIs<ChatResult.Success>(sendTurn(fixture, "I live in Delhi."))

        val allFacts = fixture.memoryFactRepository.findForRelationship(fixture.userId, fixture.personaId)
        assertEquals(1, allFacts.size, "Re-extracting an equivalent fact must not create a duplicate")
    }

    // --- G. Extraction failure isolation ---
    @Test
    fun `scenario G extraction failure never turns a successful chat response into a failure`() {
        val throwingExtractor = MemoryExtractor { throw IllegalStateException("simulated extraction failure") }
        val fixture = fixture(
            chatReplies = listOf("Here is my reply."),
            extractionResponses = emptyList(),
            extractorOverride = throwingExtractor,
        )

        val result = sendTurn(fixture, "I live in Delhi.")

        val success = assertIs<ChatResult.Success>(result, "Extraction failure must not affect the primary chat result")
        assertEquals("Here is my reply.", success.response.content)

        val facts = fixture.memoryFactRepository.findForRelationship(fixture.userId, fixture.personaId)
        assertTrue(facts.isEmpty(), "A failed extraction must not persist any memory")
    }

    // --- H. Full pipeline integration: write on turn 1, read on turn 2 via the real retrieval path ---
    @Test
    fun `scenario H memory extracted on one turn is retrievable through selectForContext on the next turn`() {
        val fixture = fixture(
            chatReplies = listOf("Nice to know!", "Sure, here's a suggestion."),
            extractionResponses = listOf(
                """{"facts": [{"fact": "user lives in Delhi", "factType": "interest", "criticality": "medium"}]}""",
                """{"facts": []}""",
            ),
        )

        // Turn 1: user states a durable fact; extraction should persist it after delivery.
        assertIs<ChatResult.Success>(sendTurn(fixture, "I live in Delhi."))

        val storedDirectly = fixture.memoryService.selectForContext(fixture.userId, fixture.personaId)
        assertEquals(1, storedDirectly.size)
        assertEquals("user lives in Delhi", storedDirectly.single().fact)

        // Turn 2: send a new request and assemble ITS context through the exact same
        // RepositoryContextAssembler instance the real pipeline uses. This proves the
        // fact learned on turn 1 reaches the actual ChatContext memory block that would
        // be sent to the LLM — not merely a direct repository/service query.
        assertIs<ChatResult.Success>(sendTurn(fixture, "What's a good weekend activity?"))

        val turn3Request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = fixture.userId,
            conversationId = fixture.conversationId,
            personaId = fixture.personaId,
            clientMessageId = UUID.randomUUID(),
            content = "Anything else you remember about me?",
        )
        val turn3Context = assertIs<StageResult.Succeeded<ChatContext>>(
            fixture.contextAssembler.assemble(turn3Request),
        ).value
        val memoryBlock = turn3Context.blocks[2]
        assertTrue(
            memoryBlock.content.contains("user lives in Delhi"),
            "Memory learned on turn 1 must appear in the real ChatContext memory block for a later turn",
        )
    }
}
